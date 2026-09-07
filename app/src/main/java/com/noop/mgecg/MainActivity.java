package com.noop.mgecg;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ = 42;

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdWrite;

    /*
     * Protocol sequence counter.
     *
     * Keep this separate from the Labrador command fields.
     * Protocol.labrador() currently receives:
     *
     *     type, opcode, argument, sequence
     */
    private int seq = 1;

    private BluetoothLeScanner scanner;
    private BluetoothDevice pendingDevice;

    private TextView log;
    private TextView statusBar;
    private Button scanBtn;
    private EditText customInput;
    private EditText clockInput;
    private EditText experimentIntervalInput;
    private CheckBox autoPullCheckbox;
    private ScrollView scrollView;

    private final UUID svc =
            UUID.fromString(Protocol.SERVICE);

    private final UUID cmd =
            UUID.fromString(Protocol.CMD_WRITE);

    private final UUID cmdN =
            UUID.fromString(Protocol.CMD_NOTIFY);

    private final UUID dataN =
            UUID.fromString(Protocol.DATA_NOTIFY);

    private final UUID eventN =
            UUID.fromString(Protocol.EVENT_NOTIFY);

    private final UUID extraN =
            UUID.fromString(Protocol.EXTRA_NOTIFY);

    /*
     * ------------------------------------------------------------------
     * Standard Bluetooth SIG services discovered via the full GATT
     * enumeration - Heart Rate and Device Information. Unlike the
     * fd4b custom protocol, these are publicly documented and
     * require no command guessing.
     * ------------------------------------------------------------------
     */
    private final UUID hrService =
            UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");

    private final UUID hrMeasurement =
            UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");

    private final UUID disService =
            UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");

    private final UUID manufacturerNameChar =
            UUID.fromString("00002A29-0000-1000-8000-00805f9b34fb");

    private final UUID modelNumberChar =
            UUID.fromString("00002A24-0000-1000-8000-00805f9b34fb");

    private final UUID serialNumberChar =
            UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb");

    private final UUID hardwareRevisionChar =
            UUID.fromString("00002A26-0000-1000-8000-00805f9b34fb");

    private final UUID firmwareRevisionChar =
            UUID.fromString("00002A27-0000-1000-8000-00805f9b34fb");

    /*
     * Serialize BLE operations.
     */
    private final ArrayDeque<Runnable> opQueue =
            new ArrayDeque<>();

    private boolean opInFlight = false;

    private final Handler mainH =
            new Handler(Looper.getMainLooper());

    private Runnable timeoutRunnable;

    /*
     * Labrador capture state.
     */
    private boolean labradorActive = false;
    private boolean recordingComplete = false;
    private int labradorPacketCount = 0;

    /*
     * Count all notifications independently.
     */
    private int rxCount = 0;

    /*
     * ------------------------------------------------------------------
     * Controlled 3x-START experiment state
     * ------------------------------------------------------------------
     */
    private boolean experimentActive = false;
    private int experimentStartsSent = 0;
    private int experimentCompletionsSeen = 0;
    private boolean autoPullAfterExperiment = false;

    /*
     * ------------------------------------------------------------------
     * Config-value sweep state (GET_DEVICE_CONFIG_VALUE key sweep)
     * ------------------------------------------------------------------
     */
    private boolean sweepActive = false;
    private int sweepKey = 0;

    /*
     * ------------------------------------------------------------------
     * Real historical-data pull state (SET_CLOCK -> GET_CLOCK ->
     * GET_DATA_RANGE -> SEND_HISTORICAL_DATA -> repeated ACK), reverse
     * engineered from a real NOOP-app BLE HCI snoop capture against
     * this same strap. Completely separate mechanism from Labrador
     * START/STOP - this is what the real app actually uses to
     * retrieve data over DATA_NOTIFY (0005).
     * ------------------------------------------------------------------
     */
    private boolean pullAckActive = false;
    private int pullAckCounter = 0;

    private java.io.File historicalBinaryFile;
    private final List<byte[]> historicalFragments = new ArrayList<>();
    private int historicalTotalBytes = 0;

    /*
     * ------------------------------------------------------------------
     * Persistent raw log file
     * ------------------------------------------------------------------
     */
    private java.io.File rawLogFile;

    /*
     * ------------------------------------------------------------------
     * Binary + structural-analysis capture (merged in from the CBOR
     * decoder branch)
     * ------------------------------------------------------------------
     */
    private java.io.File binaryFile;
    private java.io.File analysisFile;
    private final List<byte[]> labradorFragments = new ArrayList<>();

    private void prepareCaptureFiles() {

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("ERROR: external files directory unavailable");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        binaryFile = new java.io.File(
                dir, "labrador_bin_" + stamp + ".bin");

        analysisFile = new java.io.File(
                dir, "labrador_analysis_" + stamp + ".txt");

        line("LABRADOR BINARY FILE:");
        line(binaryFile.getAbsolutePath());

        line("LABRADOR ANALYSIS FILE:");
        line(analysisFile.getAbsolutePath());
    }

    private void saveBinaryFragment(byte[] data) {

        if (binaryFile == null) {
            prepareCaptureFiles();
        }

        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(binaryFile, true)) {

            fos.write(data);

        } catch (Exception e) {
            line("BINARY SAVE ERROR: " + e);
        }
    }

    private void writeAnalysis(String text) {

        if (analysisFile == null) {
            prepareCaptureFiles();
        }

        try (java.io.FileWriter fw =
                     new java.io.FileWriter(analysisFile, true)) {

            fw.write(text);
            fw.write("\n");

        } catch (Exception e) {
            line("ANALYSIS SAVE ERROR: " + e);
        }
    }

    private void initRawLogFile() {

        long epochNow = System.currentTimeMillis() / 1000L;

        rawLogFile = new java.io.File(
                getExternalFilesDir(null),
                "labrador_log_" + epochNow + ".txt");

        logRaw("LOG_FILE_OPENED path=" +
                rawLogFile.getAbsolutePath());
    }

    /*
     * Structured, always-persisted, never-truncated log.
     *
     * Separate from line(), which is the on-screen scrolling
     * view and IS capped/trimmed. This file is not.
     */
    private void logRaw(String s) {

        if (rawLogFile == null) {
            return;
        }

        long ms = System.currentTimeMillis();

        String stamped =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US)
                        .format(new Date(ms)) +
                "\t" + ms + "\t" + s;

        try (java.io.FileWriter fw =
                     new java.io.FileWriter(rawLogFile, true)) {

            fw.write(stamped + "\n");

        } catch (Exception e) {

            line("FILE LOG WRITE ERROR: " + e);
        }
    }

    /*
     * ------------------------------------------------------------------
     * BLE operation queue
     * ------------------------------------------------------------------
     */

    private void enqueue(Runnable op) {
        opQueue.add(op);
        drainQueue();
    }

    private void drainQueue() {
        if (opInFlight || opQueue.isEmpty()) {
            return;
        }

        opInFlight = true;

        timeoutRunnable = () -> {
            line("TIMEOUT - no BLE callback, unsticking queue");
            timeoutRunnable = null;
            opInFlight = false;
            drainQueue();
        };

        mainH.postDelayed(timeoutRunnable, 4000);

        Runnable next = opQueue.poll();

        try {
            next.run();
        } catch (Exception e) {
            line("BLE operation exception: " + e);
            opDone();
        }
    }

    private void opDone() {
        if (timeoutRunnable != null) {
            mainH.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        opInFlight = false;
        drainQueue();
    }

    /*
     * ------------------------------------------------------------------
     * Bond receiver
     * ------------------------------------------------------------------
     */

    private final BroadcastReceiver bondReceiver =
            new BroadcastReceiver() {

        @Override
        public void onReceive(Context ctx, Intent i) {

            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED
                    .equals(i.getAction())) {
                return;
            }

            BluetoothDevice d =
                    i.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE);

            int state =
                    i.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE,
                            -1);

            String label;

            if (state == BluetoothDevice.BOND_BONDED) {
                label = "BONDED";
            } else if (state == BluetoothDevice.BOND_BONDING) {
                label = "BONDING";
            } else if (state == BluetoothDevice.BOND_NONE) {
                label = "BOND_NONE";
            } else {
                label = String.valueOf(state);
            }

            line("BOND STATE " + label +
                    " (" +
                    (d != null ? d.getAddress() : "?") +
                    ")");

            if (state == BluetoothDevice.BOND_BONDED
                    && pendingDevice != null
                    && d != null
                    && d.getAddress().equals(
                    pendingDevice.getAddress())
                    && gatt == null) {

                BluetoothDevice toConnect = pendingDevice;
                pendingDevice = null;

                line("CONNECTING (post-bond) " +
                        toConnect.getAddress());

                updateStatus("● CONNECTING " + toConnect.getName());

                gatt = toConnect.connectGatt(
                        MainActivity.this,
                        false,
                        cb,
                        BluetoothDevice.TRANSPORT_LE);

            } else if (state == BluetoothDevice.BOND_NONE
                    && pendingDevice != null) {

                line("BONDING FAILED/CANCELLED - " +
                        "not connecting");

                pendingDevice = null;
            }
        }
    };

    /*
     * ------------------------------------------------------------------
     * GATT callback
     * ------------------------------------------------------------------
     */

    private final BluetoothGattCallback cb =
            new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(
                BluetoothGatt g,
                int status,
                int state) {

            line("GATT state=" +
                    state +
                    " status=" +
                    status);

            if (state ==
                    BluetoothProfile.STATE_CONNECTED) {

                line("GATT CONNECTED");
                updateStatus("● CONNECTED");
                g.discoverServices();

            } else if (state ==
                    BluetoothProfile.STATE_DISCONNECTED) {

                line("GATT DISCONNECTED");
                updateStatus("○ DISCONNECTED");

                try {
                    g.close();
                } catch (Exception ignored) {
                }

                if (timeoutRunnable != null) {
                    mainH.removeCallbacks(
                            timeoutRunnable);
                    timeoutRunnable = null;
                }

                gatt = null;
                cmdWrite = null;

                opQueue.clear();
                opInFlight = false;

                labradorActive = false;
                recordingComplete = false;

                experimentActive = false;
            }
        }

        @Override
        public void onServicesDiscovered(
                BluetoothGatt g,
                int status) {

            line("services discovered status=" +
                    status);

            dumpAllServices(g);

            subscribeHeartRateIfPresent(g);
            readDeviceInfoIfPresent(g);

            BluetoothGattService s =
                    g.getService(svc);

            if (s == null) {
                line("ERROR: fd4b service not found");
                return;
            }

            cmdWrite =
                    s.getCharacteristic(cmd);

            line("fd4b service found");

            subscribe(
                    g,
                    s.getCharacteristic(cmdN));

            subscribe(
                    g,
                    s.getCharacteristic(eventN));

            subscribe(
                    g,
                    s.getCharacteristic(dataN));

            subscribe(
                    g,
                    s.getCharacteristic(extraN));

            if (cmdWrite != null) {

                enqueue(() -> {

                    line("TX CLIENT_HELLO " +
                            "(confirmed write)");

                    cmdWrite.setWriteType(
                            BluetoothGattCharacteristic
                                    .WRITE_TYPE_DEFAULT);

                    cmdWrite.setValue(
                            Protocol.clientHello());

                    if (!g.writeCharacteristic(
                            cmdWrite)) {

                        line("writeCharacteristic() " +
                                "rejected " +
                                "(CLIENT_HELLO)");

                        opDone();
                    }
                });
            }
        }

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt g,
                BluetoothGattCharacteristic c,
                int status) {

            line("WRITE " +
                    shortUuid(c.getUuid()) +
                    " status=" +
                    status);

            opDone();
        }

        @Override
        public void onDescriptorWrite(
                BluetoothGatt g,
                BluetoothGattDescriptor d,
                int status) {

            line("CCCD " +
                    shortUuid(
                            d.getCharacteristic()
                                    .getUuid()) +
                    " status=" +
                    status);

            opDone();
        }

        /*
         * Device Information reads - modern signature (API 33+)
         * supplies value directly.
         */
        @Override
        public void onCharacteristicRead(
                BluetoothGatt g,
                BluetoothGattCharacteristic c,
                byte[] value,
                int status) {

            handleCharacteristicRead(c, value, status);
        }

        /*
         * Older Android read callback.
         */
        @Override
        public void onCharacteristicRead(
                BluetoothGatt g,
                BluetoothGattCharacteristic c,
                int status) {

            handleCharacteristicRead(c, c.getValue(), status);
        }

        /*
         * Android versions which supply value directly.
         */
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt g,
                BluetoothGattCharacteristic c,
                byte[] value) {

            handleRx(c, value);
        }

        /*
         * Older Android callback.
         */
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt g,
                BluetoothGattCharacteristic c) {

            byte[] value = c.getValue();

            handleRx(c, value);
        }
    };

    /*
     * ------------------------------------------------------------------
     * RX decoder / capture
     * ------------------------------------------------------------------
     */

    private void handleRx(
            BluetoothGattCharacteristic c,
            byte[] value) {

        if (value == null) {
            return;
        }

        rxCount++;

        String uuid = shortUuid(c.getUuid());

        /*
         * Always preserve the raw packet first.
         */
        line("");
        line("========== RX #" + rxCount + " ==========");
        line("CHANNEL " + uuid);
        line("LENGTH  " + value.length);
        line("RAW     " + Protocol.hex(value));

        /*
         * Existing generic protocol summary.
         */
        try {
            line("FRAME   " +
                    Protocol.frameSummary(value));
        } catch (Exception e) {
            line("FRAME   summary-error: " + e);
        }

        /*
         * Decode the normal AA 01 envelope where possible.
         */
        decodeEnvelope(value);

        /*
         * Timestamp candidates.
         */
        scanForTimestamps(value);

        /*
         * Special handling for Labrador 0007 traffic.
         */
        if ("0007".equals(uuid)) {
            handleLabradorFragment(value);
        }

        /*
         * Standard Bluetooth SIG Heart Rate Measurement.
         */
        if (hrMeasurement.equals(c.getUuid())) {
            parseHeartRate(value);
        }

        /*
         * Real historical-data burst (type=0x2F cmd=0x80) and its
         * accompanying human-readable firmware debug log
         * (type=0x32 cmd=0x02) - both identified from a real NOOP
         * app BLE snoop capture. These can arrive on any subscribed
         * channel, not just 0007, so this check is channel-agnostic
         * and reads the envelope's type/cmd bytes directly.
         */
        if (value.length >= 11 && (value[0] & 0xff) == 0xAA) {

            int envType = value[8] & 0xff;
            int envCmd = value[10] & 0xff;

            if (envType == 0x2F && envCmd == 0x80) {
                handleHistoricalBurstFrame(uuid, value);
            } else if (envType == 0x32 && envCmd == 0x02) {
                dumpAsciiRuns(value);
            }
        }

        /*
         * Recording-complete event.
         */
        if ("0004".equals(uuid)) {
            if (isRecordingComplete(value)) {
                recordingComplete = true;

                line("");
                line("*** RECORDING COMPLETE DETECTED ***");

                runFullCborAnalysisOnCompletion();

                logRaw("RECORDING_COMPLETE raw=" +
                        Protocol.hex(value) +
                        " experimentActive=" + experimentActive +
                        " startsSent=" + experimentStartsSent +
                        " completionsSeenBefore=" +
                        experimentCompletionsSeen);

                if (experimentActive) {

                    experimentCompletionsSeen++;

                    line("*** EXPERIMENT: completion #" +
                            experimentCompletionsSeen +
                            " of 3 seen ***");

                    if (experimentCompletionsSeen >= 3) {

                        line("*** EXPERIMENT: all 3 " +
                                "completions seen ***");

                        logRaw("EXPERIMENT_ALL_COMPLETIONS_SEEN");

                        if (autoPullAfterExperiment) {

                            line("*** EXPERIMENT: auto-firing " +
                                    "PULL now ***");

                            logRaw("EXPERIMENT_AUTO_PULL_FIRING");

                            sendCustom(0x2F, 0x01, 0x00);

                        } else {

                            line("(auto-pull not enabled - " +
                                    "use PULL button manually)");
                        }

                        experimentActive = false;
                    }
                }
            }
        }

        line("========== END RX ==========");
    }

    /*
     * Decode the common AA 01 packet header without assuming
     * that every byte has already been understood.
     */
    private void decodeEnvelope(byte[] v) {

        if (v.length < 11) {
            return;
        }

        if ((v[0] & 0xff) != 0xAA) {
            return;
        }

        line(String.format(
                "HEADER  AA 01  len/field=%02X %02X",
                v[2] & 0xff,
                v[3] & 0xff));

        line(String.format(
                "FIELDS  type=0x%02X seq=0x%02X " +
                        "cmd=0x%02X",
                v[8] & 0xff,
                v[9] & 0xff,
                v[10] & 0xff));
    }

    /*
     * Labrador packets are currently delivered on 0007.
     *
     * Do not interpret their payload as ECG yet.
     */
    private void handleLabradorFragment(
            byte[] value) {

        labradorPacketCount++;

        /*
         * Always persist the full raw packet, unconditionally -
         * not just during a controlled experiment.
         */
        logRaw("RX_0007_FULL len=" + value.length +
                " active=" + labradorActive +
                " raw=" + Protocol.hex(value));

        line("");
        line("LABRADOR 0007 FRAGMENT #" +
                labradorPacketCount +
                (labradorActive ? " [ACTIVE]" : " [BACKGROUND]"));

        line("LABRADOR LENGTH=" +
                value.length);

        /*
         * First 32 bytes.
         */
        int first = Math.min(
                32,
                value.length);

        byte[] head =
                Arrays.copyOfRange(
                        value,
                        0,
                        first);

        line("HEAD    " +
                Protocol.hex(head));

        /*
         * Last 32 bytes.
         */
        int start =
                Math.max(
                        0,
                        value.length - 32);

        byte[] tail =
                Arrays.copyOfRange(
                        value,
                        start,
                        value.length);

        line("TAIL    " +
                Protocol.hex(tail));

        /*
         * Printable ASCII runs.
         */
        dumpAsciiRuns(value);

        /*
         * Common integer interpretations.
         */
        dumpIntegerCandidates(value);

        /*
         * Persist the raw bytes (separate .bin file, exact
         * concatenated payload, no text formatting in the way) and
         * buffer a copy for a full CBOR pass once the recording
         * completes.
         */
        saveBinaryFragment(value);

        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        labradorFragments.add(copy);

        /*
         * CBOR structural candidate scan on this fragment alone.
         */
        analysePotentialCbor(value);

        line("LABRADOR FRAGMENT END");
    }

    /*
     * Print printable ASCII sequences of length >= 4.
     */
    private void dumpAsciiRuns(byte[] v) {

        StringBuilder run =
                new StringBuilder();

        int start = -1;

        for (int i = 0; i < v.length; i++) {

            int b = v[i] & 0xff;

            boolean printable =
                    b >= 0x20 && b <= 0x7e;

            if (printable) {

                if (run.length() == 0) {
                    start = i;
                }

                run.append((char) b);

            } else {

                if (run.length() >= 4) {

                    line(String.format(
                            "ASCII   @%d \"%s\"",
                            start,
                            run.toString()));
                }

                run.setLength(0);
                start = -1;
            }
        }

        if (run.length() >= 4) {

            line(String.format(
                    "ASCII   @%d \"%s\"",
                    start,
                    run.toString()));
        }
    }

    /*
     * Show selected little-endian integer candidates.
     *
     * This is deliberately diagnostic only.
     */
    private void dumpIntegerCandidates(
            byte[] v) {

        int count =
                Math.min(
                        v.length - 3,
                        64);

        if (count <= 0) {
            return;
        }

        for (int i = 0; i <= count; i += 4) {

            long x =
                    Protocol.u32le(v, i);

            line(String.format(
                    "U32LE   @%d = %d (0x%08X)",
                    i,
                    x,
                    x));
        }
    }

    /*
     * ------------------------------------------------------------------
     * CBOR structural decoder (merged in from the parallel
     * ChatGPT-assisted branch - this is what actually found a real
     * CBOR map at byte offset 37 inside the Maverick/WG50 identity
     * burst on 0007, containing a 108-element array not yet mapped
     * to a meaning)
     * ------------------------------------------------------------------
     */

    private void analysePotentialCbor(byte[] fragment) {

        /*
         * Search for likely CBOR map/array markers. We deliberately
         * don't assume the fragment starts on a CBOR boundary.
         */
        for (int i = 0; i < fragment.length; i++) {

            int x = fragment[i] & 0xff;

            if (x == 0xA7 || x == 0xA2 || x == 0xA1 || x == 0x98) {

                String s = String.format(
                        Locale.US,
                        "CBOR-CANDIDATE @%d = 0x%02X",
                        i, x);

                line(s);
                writeAnalysis(s);
            }
        }

        /*
         * Known-area check: every identity burst we've seen so far
         * has a CBOR map header (0xA7 = map, 7 pairs) sitting at a
         * consistent offset of 37.
         */
        if (fragment.length > 37) {

            String s = String.format(
                    Locale.US,
                    "KNOWN-AREA CHECK @37: 0x%02X",
                    fragment[37] & 0xff);

            line(s);
            writeAnalysis(s);

            if ((fragment[37] & 0xff) == 0xA7) {

                line("KNOWN CBOR MAP FOUND AT OFFSET 37");
                writeAnalysis("KNOWN CBOR MAP FOUND AT OFFSET 37");
            }
        }
    }

    private static class CborParser {

        byte[] data;
        int pos;

        List<String> lines = new ArrayList<>();

        CborParser(byte[] data) {
            this(data, 0);
        }

        CborParser(byte[] data, int startOffset) {
            this.data = data;
            this.pos = startOffset;
        }

        void parseTopLevel() {

            add("CBOR parse begins at offset " + pos);

            try {
                parseItem(0);
            } catch (Exception e) {
                add("CBOR parser stopped: " + e.getMessage());
            }

            add("CBOR parser final offset=" + pos + "/" + data.length);

            if (pos < data.length) {
                add("TRAILING BYTES @" + pos + ": " +
                        Protocol.hex(slice(pos, data.length)));
            }
        }

        private void parseItem(int level) {

            if (pos >= data.length)
                throw new RuntimeException("EOF");

            int offset = pos;
            int initial = data[pos++] & 0xff;
            int major = initial >>> 5;
            int ai = initial & 0x1f;
            String indent = indent(level);

            if (initial == 0xF6) {
                add(indent + "@" + offset + " NULL (F6)");
                return;
            }

            if (ai == 31) {
                add(indent + "@" + offset + " INDEFINITE/UNSUPPORTED");
                return;
            }

            long n = readAdditional(ai);

            switch (major) {

                case 0:
                    add(indent + "@" + offset + " UINT " + n);
                    break;

                case 1:
                    add(indent + "@" + offset + " NINT " + (-1L - n));
                    break;

                case 2:
                    add(indent + "@" + offset + " BYTES len=" + n);
                    skip(n);
                    break;

                case 3:
                    if (n > Integer.MAX_VALUE ||
                            pos + (int) n > data.length) {
                        throw new RuntimeException("bad text length");
                    }
                    String s = new String(
                            data, pos, (int) n,
                            java.nio.charset.StandardCharsets.UTF_8);
                    pos += (int) n;
                    add(indent + "@" + offset + " TEXT \"" + s + "\"");
                    break;

                case 4:
                    add(indent + "@" + offset + " ARRAY len=" + n);
                    if (n > 10000)
                        throw new RuntimeException("array too large");
                    for (int i = 0; i < n; i++) {
                        add(indent + "  [" + i + "]");
                        parseItem(level + 1);
                    }
                    break;

                case 5:
                    add(indent + "@" + offset + " MAP pairs=" + n);
                    if (n > 10000)
                        throw new RuntimeException("map too large");
                    for (int i = 0; i < n; i++) {
                        add(indent + "  KEY");
                        parseItem(level + 1);
                        add(indent + "  VALUE");
                        parseItem(level + 1);
                    }
                    break;

                case 6:
                    add(indent + "@" + offset + " TAG " + n);
                    parseItem(level + 1);
                    break;

                case 7:
                    add(indent + "@" + offset + " SIMPLE/FLOAT ai=" + ai);
                    break;

                default:
                    add(indent + "@" + offset + " UNKNOWN");
            }
        }

        private long readAdditional(int ai) {

            if (ai < 24) return ai;

            if (ai == 24) {
                ensure(1);
                return data[pos++] & 0xffL;
            }

            if (ai == 25) {
                ensure(2);
                long v = ((data[pos] & 0xffL) << 8) | (data[pos + 1] & 0xffL);
                pos += 2;
                return v;
            }

            if (ai == 26) {
                ensure(4);
                long v = ((data[pos] & 0xffL) << 24)
                        | ((data[pos + 1] & 0xffL) << 16)
                        | ((data[pos + 2] & 0xffL) << 8)
                        | (data[pos + 3] & 0xffL);
                pos += 4;
                return v;
            }

            if (ai == 27) {
                ensure(8);
                long v = 0;
                for (int i = 0; i < 8; i++) {
                    v = (v << 8) | (data[pos + i] & 0xffL);
                }
                pos += 8;
                return v;
            }

            throw new RuntimeException("unsupported additional info " + ai);
        }

        private void skip(long n) {
            if (n < 0 || n > Integer.MAX_VALUE)
                throw new RuntimeException("invalid length");
            ensure((int) n);
            pos += (int) n;
        }

        private void ensure(int n) {
            if (n < 0 || pos + n > data.length) {
                throw new RuntimeException(
                        "EOF at " + pos + " need " + n);
            }
        }

        private byte[] slice(int a, int b) {
            if (a < 0) a = 0;
            if (b > data.length) b = data.length;
            if (b < a) b = a;
            byte[] x = new byte[b - a];
            System.arraycopy(data, a, x, 0, x.length);
            return x;
        }

        private String indent(int level) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < level; i++) s.append("  ");
            return s.toString();
        }

        private void add(String s) {
            lines.add(s);
        }
    }

    private static class F6Analysis {
        int count = 0;
        List<Integer> runs = new ArrayList<>();
    }

    private F6Analysis analyseF6(byte[] b) {

        F6Analysis result = new F6Analysis();
        int run = 0;

        for (byte x : b) {
            if ((x & 0xff) == 0xF6) {
                result.count++;
                run++;
            } else {
                if (run > 0) {
                    result.runs.add(run);
                    run = 0;
                }
            }
        }

        if (run > 0) result.runs.add(run);

        return result;
    }

    private byte[] combineLabradorFragments() {

        int total = 0;
        for (byte[] f : labradorFragments) total += f.length;

        byte[] out = new byte[total];
        int pos = 0;

        for (byte[] f : labradorFragments) {
            System.arraycopy(f, 0, out, pos, f.length);
            pos += f.length;
        }

        return out;
    }

    /*
     * Finds the first 0xA7 (CBOR map, 7 pairs) byte in the buffer.
     *
     * Every identity burst captured so far has one at offset 37
     * within its first fragment, but this scans rather than
     * hardcoding 37, since fragment sizes/order could vary.
     */
    private int findFirstCborMapOffset(byte[] b) {

        for (int i = 0; i < b.length; i++) {
            if ((b[i] & 0xff) == 0xA7) {
                return i;
            }
        }

        return -1;
    }

    private void runFullCborAnalysisOnCompletion() {

        if (labradorFragments.isEmpty()) {
            line("NO 0007 DATA CAPTURED TO ANALYSE");
            return;
        }

        byte[] combined = combineLabradorFragments();

        line("");
        line("========== LABRADOR REASSEMBLED ==========");
        line("FRAGMENTS=" + labradorFragments.size());
        line("TOTAL BYTES=" + combined.length);

        writeAnalysis("");
        writeAnalysis("========== LABRADOR REASSEMBLED ==========");
        writeAnalysis("FRAGMENTS=" + labradorFragments.size());
        writeAnalysis("TOTAL BYTES=" + combined.length);

        line("========== STRUCTURAL CBOR ANALYSIS ==========");
        writeAnalysis("========== STRUCTURAL CBOR ANALYSIS ==========");

        int mapOffset = findFirstCborMapOffset(combined);

        if (mapOffset < 0) {

            line("NO CBOR MAP MARKER (0xA7) FOUND IN REASSEMBLED DATA");
            writeAnalysis(
                    "NO CBOR MAP MARKER (0xA7) FOUND IN REASSEMBLED DATA");

        } else {

            line("Starting structural parse at confirmed map offset " +
                    mapOffset);
            writeAnalysis(
                    "Starting structural parse at confirmed map offset " +
                            mapOffset);

            CborParser parser = new CborParser(combined, mapOffset);
            parser.parseTopLevel();

            for (String s : parser.lines) {
                line(s);
                writeAnalysis(s);
            }
        }

        F6Analysis f6 = analyseF6(combined);

        line("F6 COUNT=" + f6.count);
        line("F6 RUNS=" + f6.runs);
        writeAnalysis("F6 COUNT=" + f6.count);
        writeAnalysis("F6 RUNS=" + f6.runs);

        labradorFragments.clear();
    }

    /*
     * ------------------------------------------------------------------
     * Full GATT enumeration - dumps every service/characteristic the
     * strap exposes, not just the fd4b one we've been filtering to.
     * Runs automatically on every connect.
     * ------------------------------------------------------------------
     */

    private void dumpAllServices(BluetoothGatt g) {

        line("");
        line("========== FULL GATT ENUMERATION ==========");
        logRaw("GATT_ENUM_BEGIN");

        List<BluetoothGattService> services = g.getServices();

        line("TOTAL SERVICES=" + services.size());
        logRaw("GATT_ENUM total_services=" + services.size());

        for (BluetoothGattService s : services) {

            line("SERVICE " + s.getUuid());
            logRaw("GATT_ENUM SERVICE=" + s.getUuid());

            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {

                String propsStr = describeProps(c.getProperties());

                line("  CHAR " + c.getUuid() + " props=" + propsStr);
                logRaw("GATT_ENUM   CHAR=" + c.getUuid() +
                        " props=" + propsStr);

                for (BluetoothGattDescriptor d : c.getDescriptors()) {

                    line("    DESC " + d.getUuid());
                    logRaw("GATT_ENUM     DESC=" + d.getUuid());
                }
            }
        }

        line("========== END GATT ENUMERATION ==========");
        logRaw("GATT_ENUM_END");
    }

    private String describeProps(int props) {

        StringBuilder sb = new StringBuilder();

        if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0)
            sb.append("READ ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            sb.append("WRITE ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            sb.append("WRITE_NR ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
            sb.append("NOTIFY ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            sb.append("INDICATE ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0)
            sb.append("BROADCAST ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0)
            sb.append("SIGNED_WRITE ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0)
            sb.append("EXTENDED ");

        String out = sb.toString().trim();

        return out.isEmpty() ? "(none)" : out;
    }

    private void manualGattDump() {

        if (gatt == null) {
            line("NOT CONNECTED - cannot dump GATT");
            return;
        }

        dumpAllServices(gatt);
    }

    /*
     * ------------------------------------------------------------------
     * Config-value sweep - targets cmd=0x79 (GET_DEVICE_CONFIG_VALUE),
     * a real opcode name found in NOOP's own decompiled source, sitting
     * just below the confirmed-working 0x7B-0x8B cluster. Sweeps a
     * small key range (0x00-0x1F) rather than blindly trying all 256
     * possible cmd bytes against the real device.
     * ------------------------------------------------------------------
     */

    private void startCmdSweep() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot sweep");
            return;
        }

        sweepActive = true;
        sweepKey = 0;

        line("");
        line("*** CONFIG-VALUE SWEEP BEGIN: cmd=0x79 " +
                "(GET_DEVICE_CONFIG_VALUE), key 0x00-0x1F ***");
        logRaw("SWEEP_BEGIN cmd=0x79");

        runSweepStep();
    }

    private void stopCmdSweep() {

        sweepActive = false;

        line("*** SWEEP STOPPED at key=0x" +
                String.format("%02X", sweepKey) + " ***");
        logRaw("SWEEP_STOPPED at=" + sweepKey);
    }

    private void runSweepStep() {

        if (!sweepActive) {
            return;
        }

        if (sweepKey > 0x1F) {
            sweepActive = false;
            line("*** CONFIG-VALUE SWEEP COMPLETE ***");
            logRaw("SWEEP_COMPLETE");
            return;
        }

        int thisKey = sweepKey;

        line("SWEEP: GET_DEVICE_CONFIG_VALUE key=0x" +
                String.format("%02X", thisKey) +
                " (" + thisKey + "/31)");

        sendCustom(0x23, 0x79, thisKey);

        sweepKey++;

        mainH.postDelayed(this::runSweepStep, 1000);
    }

    /*
     * ------------------------------------------------------------------
     * Real historical-data pull - reverse engineered from a real NOOP
     * app BLE HCI snoop capture against this same strap. The real app
     * never touches LABRADOR_START/PULL at all for retrieval; instead:
     *
     *   0x0A  SET_CLOCK             (4-byte epoch arg)
     *   0x0B  GET_CLOCK             (zero-byte arg)
     *   0x22  GET_DATA_RANGE        (zero-byte arg)
     *   0x16  SEND_HISTORICAL_DATA  (zero-byte arg) - triggers the burst
     *   0x17  CONTINUE/ACK          (1-byte incrementing counter,
     *                                sent repeatedly ~350ms apart while
     *                                the burst is in progress)
     *
     * The burst payload itself arrives as type=0x2F cmd=0x80
     * notifications on DATA_NOTIFY (fd4b0005) - the channel that has
     * never once fired in any of our own earlier attempts.
     * ------------------------------------------------------------------
     */

    /*
     * Sends a genuinely zero-length-argument frame via
     * Protocol.labradorBytes(), for the three real commands that use
     * no argument at all (0x0B, 0x22, 0x16) - confirmed byte-for-byte
     * from the real snoop capture. This is distinct from send()/
     * sendCustom(), which always emit a 1-byte argument.
     */
    private void sendZeroArg(int cmd, String name) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labradorBytes(
                    0x23, cmd, new byte[0], thisSeq);

            logRaw("TX name=" + name +
                    " cmd=0x" + String.format("%02X", cmd) +
                    " (zero-arg)" +
                    " seq=0x" + String.format("%02X",
                            thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX " + name + " (zero-arg)");
            line("TX CMD =0x" + String.format("%02X", cmd));
            line("TX SEQ =0x" + String.format("%02X",
                    thisSeq & 0xff));
            line("TX LEN =" + f.length);
            line("TX RAW =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (" + name + ")");
                opDone();
            }
        });
    }

    private void prepareHistoricalCaptureFile() {

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("ERROR: external files directory unavailable");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        historicalBinaryFile = new java.io.File(
                dir, "historical_bin_" + stamp + ".bin");

        line("HISTORICAL BINARY FILE:");
        line(historicalBinaryFile.getAbsolutePath());
    }

    private void saveHistoricalFragment(byte[] data) {

        if (historicalBinaryFile == null) {
            prepareHistoricalCaptureFile();
        }

        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(
                             historicalBinaryFile, true)) {

            fos.write(data);

        } catch (Exception e) {
            line("HISTORICAL SAVE ERROR: " + e);
        }
    }

    private void handleHistoricalBurstFrame(
            String uuid,
            byte[] value) {

        historicalFragments.add(value);
        historicalTotalBytes += value.length;
        saveHistoricalFragment(value);

        logRaw("HIST_BURST len=" + value.length +
                " raw=" + Protocol.hex(value));

        /*
         * Bursts can run to 1000+ frames in under 30s (confirmed in
         * the real capture) - logging every single one to the
         * on-screen view would flood the UI. Every frame is still
         * saved to the .bin file and the persistent raw log
         * regardless; only the on-screen summary is throttled.
         */
        if (historicalFragments.size() % 20 == 1) {

            line("HIST BURST #" + historicalFragments.size() +
                    " ch=" + uuid +
                    " len=" + value.length +
                    " totalBytes=" + historicalTotalBytes);
        }
    }

    private void startRealHistoricalPull() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot pull");
            return;
        }

        historicalFragments.clear();
        historicalTotalBytes = 0;
        historicalBinaryFile = null;

        line("");
        line("*** REAL HISTORICAL PULL: SET_CLOCK -> GET_CLOCK -> " +
                "GET_DATA_RANGE -> SEND_HISTORICAL_DATA ***");
        logRaw("REAL_PULL_BEGIN");

        sendClockGuess(0x23, 0x0A);
        sendZeroArg(0x0B, "GET_CLOCK");
        sendZeroArg(0x22, "GET_DATA_RANGE");
        sendZeroArg(0x16, "SEND_HISTORICAL_DATA");

        pullAckActive = true;
        pullAckCounter = 0;

        mainH.postDelayed(this::runPullAckStep, 350);
    }

    private void stopPullAckLoop() {

        pullAckActive = false;

        line("*** PULL ACK LOOP STOPPED - " +
                historicalFragments.size() + " burst frames, " +
                historicalTotalBytes + " bytes captured ***");

        logRaw("PULL_ACK_STOPPED frames=" + historicalFragments.size() +
                " bytes=" + historicalTotalBytes);
    }

    private void runPullAckStep() {

        if (!pullAckActive) {
            return;
        }

        sendCustom(0x23, 0x17, pullAckCounter & 0xFF);

        pullAckCounter++;

        if (pullAckCounter > 200) {

            pullAckActive = false;

            line("*** PULL ACK LOOP COMPLETE (200 cycles) - " +
                    historicalFragments.size() + " burst frames, " +
                    historicalTotalBytes + " bytes captured ***");

            logRaw("PULL_ACK_COMPLETE frames=" +
                    historicalFragments.size() +
                    " bytes=" + historicalTotalBytes);

            return;
        }

        mainH.postDelayed(this::runPullAckStep, 350);
    }

    /*
     * Recording-complete packet currently observed:
     *
     * characteristic 0004
     * type 0x30
     * command 0x1D
     */
    private boolean isRecordingComplete(
            byte[] value) {

        if (value.length < 11) {
            return false;
        }

        if ((value[0] & 0xff) != 0xAA) {
            return false;
        }

        int type =
                value[8] & 0xff;

        int command =
                value[10] & 0xff;

        return type == 0x30 &&
                command == 0x1D;
    }

    /*
     * ------------------------------------------------------------------
     * Controlled 3x-START experiment
     * ------------------------------------------------------------------
     */

    private void runLabradorExperiment() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run experiment");
            return;
        }

        long intervalMs;

        try {
            intervalMs = Long.parseLong(
                    experimentIntervalInput.getText()
                            .toString().trim()) * 1000L;
        } catch (Exception e) {
            intervalMs = 40000L;
            line("bad interval, defaulting to 40s");
        }

        final long finalIntervalMs = intervalMs;

        experimentActive = true;
        experimentStartsSent = 0;
        experimentCompletionsSeen = 0;

        line("");
        line("*** EXPERIMENT BEGIN: 3x LABRADOR_START, " +
                (intervalMs / 1000) + "s apart ***");

        logRaw("EXPERIMENT_BEGIN interval_ms=" + intervalMs +
                " auto_pull=" + autoPullAfterExperiment);

        fireNextExperimentStart(finalIntervalMs);

        /*
         * Safety timeout - if we never see 3 completions,
         * stop waiting rather than hang the experiment state
         * forever.
         *
         * Widened again, from 240s to 1200s (20 min): observed
         * completion delays have trended upward across sessions
         * (14s, 21s, 32s, 33s, 62s, 182s, 364s) rather than
         * settling, so a modestly larger fixed ceiling keeps
         * getting narrowly missed. Going much wider instead of
         * guessing at "safe enough" again.
         */
        mainH.postDelayed(() -> {

            if (experimentActive &&
                    experimentCompletionsSeen < 3) {

                line("*** EXPERIMENT TIMEOUT: only saw " +
                        experimentCompletionsSeen +
                        "/3 completions, giving up " +
                        "on auto-pull ***");

                logRaw("EXPERIMENT_TIMEOUT completions_seen=" +
                        experimentCompletionsSeen);

                experimentActive = false;
            }

        }, finalIntervalMs * 3 + 1200000L);
    }

    private void fireNextExperimentStart(long intervalMs) {

        if (experimentStartsSent >= 3) {

            line("*** ALL 3 EXPERIMENT STARTS SENT - " +
                    "waiting for completions ***");

            logRaw("EXPERIMENT_ALL_STARTS_SENT");

            return;
        }

        experimentStartsSent++;

        int n = experimentStartsSent;

        line("");
        line("*** EXPERIMENT: firing START #" + n + " of 3 ***");

        logRaw("EXPERIMENT_START_FIRING n=" + n);

        send(0x7C, 1, "EXPERIMENT_LABRADOR_START_" + n);

        if (experimentStartsSent < 3) {

            mainH.postDelayed(
                    () -> fireNextExperimentStart(intervalMs),
                    intervalMs);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Protocol transmission
     * ------------------------------------------------------------------
     */

    private void send(
            int opcode,
            int arg,
            String name) {

        if (opcode == 0x7C && arg == 1) {
            labradorFragments.clear();
            line("(cleared any stale 0007 fragments before this START)");
        }

        sendNamed(
                0x23,
                opcode,
                arg,
                name);
    }

    private void sendNamed(
            int type,
            int opcode,
            int arg,
            String name) {

        if (gatt == null ||
                cmdWrite == null) {

            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            /*
             * IMPORTANT:
             *
             * This matches the existing Protocol.java API:
             *
             *     labrador(type, opcode, arg, sequence)
             */
            byte[] f =
                    Protocol.labrador(
                            type,
                            opcode,
                            arg,
                            thisSeq);

            logRaw("TX name=" + name +
                    " type=0x" + String.format("%02X", type) +
                    " cmd=0x" + String.format("%02X", opcode) +
                    " arg=0x" + String.format("%02X", arg) +
                    " seq=0x" + String.format("%02X",
                            thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX " + name);
            line("TX TYPE=0x" +
                    String.format("%02X", type));

            line("TX CMD =0x" +
                    String.format("%02X", opcode));

            line("TX ARG =0x" +
                    String.format("%02X", arg));

            line("TX SEQ =0x" +
                    String.format("%02X",
                            thisSeq & 0xff));

            line("TX LEN =" +
                    f.length);

            line("TX RAW =" +
                    Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic
                            .WRITE_TYPE_DEFAULT);

            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(
                    cmdWrite)) {

                line("writeCharacteristic() " +
                        "rejected (" +
                        name +
                        ")");

                opDone();
            }
        });
    }

    private void sendCustom(
            int type,
            int opcode,
            int arg) {

        sendNamed(
                type,
                opcode,
                arg,
                String.format(
                        "CUSTOM type=0x%02X " +
                                "cmd=0x%02X " +
                                "arg=0x%02X",
                        type,
                        opcode,
                        arg));
    }

    /*
     * Send a 4-byte-argument frame carrying the current Unix
     * time, for experimenting with a possible SET_CLOCK opcode.
     *
     * Uses Protocol.labradorU32() rather than sendNamed(), since
     * sendNamed()/Protocol.labrador() only support a single arg
     * byte.
     *
     * type/cmd are unconfirmed - this is deliberately a fast,
     * no-rebuild way to try different cmd guesses.
     */
    private void sendClockGuess(int type, int cmd) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;
        final long epochNow = System.currentTimeMillis() / 1000L;

        enqueue(() -> {

            byte[] f = Protocol.labradorU32(
                    type, cmd, epochNow, thisSeq);

            logRaw("TX SET_CLOCK_GUESS type=0x" +
                    String.format("%02X", type) +
                    " cmd=0x" + String.format("%02X", cmd) +
                    " epoch=" + epochNow +
                    " seq=0x" + String.format("%02X",
                            thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX SET_CLOCK GUESS");
            line("TX TYPE =0x" + String.format("%02X", type));
            line("TX CMD  =0x" + String.format("%02X", cmd));
            line("TX ARG4 =0x" + String.format("%08X", epochNow));
            line("TX EPOCH=" + epochNow +
                    " (" + new Date(epochNow * 1000L) + ")");
            line("TX SEQ  =0x" +
                    String.format("%02X", thisSeq & 0xff));
            line("TX LEN  =" + f.length);
            line("TX RAW  =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (SET_CLOCK GUESS)");
                opDone();
            }
        });
    }

    /*
     * ------------------------------------------------------------------
     * Notification subscriptions
     * ------------------------------------------------------------------
     */

    private void subscribe(
            BluetoothGatt g,
            BluetoothGattCharacteristic c) {

        if (c == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        enqueue(() -> {

            line("subscribe " +
                    shortUuid(c.getUuid()));

            g.setCharacteristicNotification(
                    c,
                    true);

            BluetoothGattDescriptor d =
                    c.getDescriptor(
                            UUID.fromString(
                                    "00002902-0000-1000-8000-00805f9b34fb"));

            if (d != null) {

                d.setValue(
                        BluetoothGattDescriptor
                                .ENABLE_NOTIFICATION_VALUE);

                if (!g.writeDescriptor(d)) {

                    line("writeDescriptor() " +
                            "rejected " +
                            shortUuid(c.getUuid()));

                    opDone();
                }

            } else {

                line("NO CCCD for " +
                        shortUuid(c.getUuid()));

                opDone();
            }
        });
    }

    /*
     * ------------------------------------------------------------------
     * Standard Bluetooth SIG services - Heart Rate and Device
     * Information. These require no command guessing: Heart Rate
     * is a plain subscribe (reuses the existing subscribe() method
     * above), Device Information is a set of plain reads.
     * ------------------------------------------------------------------
     */

    private void subscribeHeartRateIfPresent(BluetoothGatt g) {

        BluetoothGattService hr = g.getService(hrService);

        if (hr == null) {
            return;
        }

        BluetoothGattCharacteristic c =
                hr.getCharacteristic(hrMeasurement);

        if (c == null) {
            return;
        }

        line("Heart Rate Service found - subscribing");
        subscribe(g, c);
    }

    private void readDeviceInfoIfPresent(BluetoothGatt g) {

        BluetoothGattService dis = g.getService(disService);

        if (dis == null) {
            return;
        }

        line("Device Information Service found - reading");

        queueRead(g, dis.getCharacteristic(manufacturerNameChar));
        queueRead(g, dis.getCharacteristic(modelNumberChar));
        queueRead(g, dis.getCharacteristic(serialNumberChar));
        queueRead(g, dis.getCharacteristic(hardwareRevisionChar));
        queueRead(g, dis.getCharacteristic(firmwareRevisionChar));
    }

    private void queueRead(
            BluetoothGatt g,
            BluetoothGattCharacteristic c) {

        if (c == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        enqueue(() -> {

            if (!g.readCharacteristic(c)) {

                line("readCharacteristic() rejected (" +
                        c.getUuid() + ")");

                opDone();
            }
        });
    }

    private String deviceInfoLabel(UUID u) {

        if (manufacturerNameChar.equals(u)) return "Manufacturer Name";
        if (modelNumberChar.equals(u)) return "Model Number";
        if (serialNumberChar.equals(u)) return "Serial Number";
        if (hardwareRevisionChar.equals(u)) return "Hardware Revision";
        if (firmwareRevisionChar.equals(u)) return "Firmware Revision";

        return null;
    }

    private void handleCharacteristicRead(
            BluetoothGattCharacteristic c,
            byte[] value,
            int status) {

        String label = deviceInfoLabel(c.getUuid());

        if (label != null) {

            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {

                String text = new String(
                        value,
                        java.nio.charset.StandardCharsets.UTF_8);

                line(label + ": \"" + text + "\" (" +
                        Protocol.hex(value) + ")");

                logRaw("DEVICE_INFO " + label + "=" + text);

            } else {

                line(label + ": read failed status=" + status);
            }
        }

        opDone();
    }

    /*
     * Standard Bluetooth SIG Heart Rate Measurement format:
     * byte 0 = flags (bit 0 selects 8-bit vs 16-bit HR value),
     * byte 1 (+2) = the heart rate value itself.
     */
    private void parseHeartRate(byte[] value) {

        if (value.length < 2) {
            return;
        }

        int flags = value[0] & 0xff;
        boolean is16Bit = (flags & 0x01) != 0;

        int hr;

        if (is16Bit && value.length >= 3) {
            hr = (value[1] & 0xff) | ((value[2] & 0xff) << 8);
        } else {
            hr = value[1] & 0xff;
        }

        line("HEART RATE: " + hr + " bpm");
        logRaw("HEART_RATE bpm=" + hr + " raw=" + Protocol.hex(value));
    }

    /*
     * ------------------------------------------------------------------
     * Timestamp scan
     * ------------------------------------------------------------------
     */

    private void scanForTimestamps(
            byte[] value) {

        long now =
                System.currentTimeMillis() / 1000L;

        long lo =
                now - 7L * 86400L;

        long hi =
                now + 7L * 86400L;

        for (int i = 0;
             i + 4 <= value.length;
             i++) {

            long v =
                    Protocol.u32le(
                            value,
                            i);

            if (v > lo && v < hi) {

                line(String.format(
                        "TS-CANDIDATE @%d: %d (%s)",
                        i,
                        v,
                        new Date(v * 1000L)));
            }
        }
    }

    /*
     * ------------------------------------------------------------------
     * UI
     * ------------------------------------------------------------------
     */

    @Override
    protected void onCreate(Bundle b) {

        super.onCreate(b);

        adapter =
                ((BluetoothManager)
                        getSystemService(
                                BLUETOOTH_SERVICE))
                        .getAdapter();

        buildUi();
        requestPerms();

        initRawLogFile();

        line("Raw log file: " +
                rawLogFile.getAbsolutePath());

        registerReceiver(
                bondReceiver,
                new IntentFilter(
                        BluetoothDevice
                                .ACTION_BOND_STATE_CHANGED));
    }

    private void buildUi() {

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL);

        root.setPadding(
                20,
                20,
                20,
                20);

        root.setBackgroundColor(0xFF121212);

        /*
         * ------------------------------------------------------------
         * Fixed header: title + connection-state status bar.
         * ------------------------------------------------------------
         */
        TextView title =
                new TextView(this);

        title.setText("LABRADOR MG ECG");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        statusBar = new TextView(this);
        statusBar.setText("○ NOT CONNECTED");
        statusBar.setTextSize(14);
        statusBar.setTextColor(0xFFB0B0B0);
        statusBar.setPadding(0, 8, 0, 20);

        root.addView(
                statusBar,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        /*
         * ------------------------------------------------------------
         * Primary action grid - fixed height, always visible, never
         * scrolls. Same callbacks as before, just arranged in a
         * compact 2-column layout instead of one long vertical list.
         * ------------------------------------------------------------
         */
        scanBtn = btn("SCAN / CONNECT", v -> scan());
        root.addView(
                scanBtn,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        LinearLayout row1 =
                new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        Button c1 =
                btn(
                        "SELECT WRIST",
                        v -> send(
                                0x7B,
                                0,
                                "SELECT_WRIST"));

        Button c4 =
                btn(
                        "FILTER ON",
                        v -> send(
                                0x8B,
                                1,
                                "FILTERED_ON"));

        row1.addView(
                c1,
                new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(
                c4,
                new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(
                row1,
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row2 =
                new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        Button c3 =
                btn(
                        "RAW SAVE ON",
                        v -> send(
                                0x7D,
                                1,
                                "RAW_SAVE_ON"));

        Button c2 =
                btn(
                        "START",
                        v -> {

                            labradorActive = true;
                            recordingComplete = false;
                            labradorPacketCount = 0;

                            line("");
                            line("*** STARTING " +
                                    "LABRADOR CAPTURE ***");

                            send(
                                    0x7C,
                                    1,
                                    "LABRADOR_START");
                        });

        row2.addView(
                c3,
                new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(
                c2,
                new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(
                row2,
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row3 =
                new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        Button runExperimentBtn = btn(
                "EXPERIMENT 3x",
                v -> runLabradorExperiment());

        Button stop =
                btn(
                        "STOP",
                        v -> {

                            labradorActive = false;

                            line("");
                            line("*** LABRADOR STOP ***");

                            send(
                                    0x7C,
                                    0,
                                    "LABRADOR_STOP");
                        });

        row3.addView(
                runExperimentBtn,
                new LinearLayout.LayoutParams(0, -2, 1));
        row3.addView(
                stop,
                new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(
                row3,
                new LinearLayout.LayoutParams(-1, -2));

        /*
         * ------------------------------------------------------------
         * Secondary / advanced controls - same buttons and inputs as
         * before, unchanged logic, just moved into their own smaller,
         * independently scrollable panel so they never crowd out the
         * log below. Every field assignment here matches exactly
         * what existed before this redesign.
         * ------------------------------------------------------------
         */
        LinearLayout controls =
                new LinearLayout(this);

        controls.setOrientation(
                LinearLayout.VERTICAL);

        /*
         * Real historical pull promoted to the very top - this is
         * the highest-priority tool now, reverse engineered from a
         * real NOOP app snoop capture against this same strap.
         */
        Button realPullBtn = btn(
                "REAL HISTORICAL PULL (SET_CLOCK...SEND_HIST_DATA)",
                v -> startRealHistoricalPull());
        controls.addView(realPullBtn);

        Button stopPullBtn = btn(
                "STOP PULL ACK LOOP",
                v -> stopPullAckLoop());
        controls.addView(stopPullBtn);

        /*
         * Sweep and GATT dump promoted to the top of this panel -
         * these are the highest-priority tools right now, worth
         * seeing immediately rather than after scrolling past
         * everything else.
         */
        Button gattDumpBtn = btn(
                "DUMP ALL GATT SERVICES",
                v -> manualGattDump());
        controls.addView(gattDumpBtn);

        Button startSweepBtn = btn(
                "GET CONFIG VALUE SWEEP (0x79, key 0x00-0x1F)",
                v -> startCmdSweep());
        controls.addView(startSweepBtn);

        Button stopSweepBtn = btn(
                "STOP SWEEP",
                v -> stopCmdSweep());
        controls.addView(stopSweepBtn);

        Button c5 =
                btn(
                        "0x3F SPO2 STREAM ON",
                        v -> send(
                                0x3F,
                                1,
                                "SPO2_ON"));

        controls.addView(c5);

        /*
         * Controlled 3x-START experiment controls.
         */
        experimentIntervalInput = new EditText(this);
        experimentIntervalInput.setHint(
                "seconds between STARTs, e.g. 40");
        experimentIntervalInput.setText("40");
        experimentIntervalInput.setSingleLine(true);
        experimentIntervalInput.setTextColor(0xFFFFFFFF);
        experimentIntervalInput.setHintTextColor(0xFF888888);
        controls.addView(experimentIntervalInput);

        autoPullCheckbox = new CheckBox(this);
        autoPullCheckbox.setText(
                "Auto-PULL (0x2F 01 00) after 3rd completion");
        autoPullCheckbox.setTextColor(0xFFDDDDDD);
        autoPullCheckbox.setOnCheckedChangeListener(
                (btn2, checked) ->
                        autoPullAfterExperiment = checked);
        controls.addView(autoPullCheckbox);

        /*
         * Pull is now explicit rather than automatic.
         *
         * This is important for reverse engineering:
         * we want a clean before/after boundary.
         */
        Button pull =
                btn(
                        "0x2F PULL 01 00",
                        v -> {

                            line("");
                            line("*** MANUAL PULL ***");

                            sendCustom(
                                    0x2F,
                                    0x01,
                                    0x00);
                        });

        controls.addView(pull);

        customInput =
                new EditText(this);

        customInput.setHint(
                "type cmd arg hex, " +
                        "e.g. 2F 01 00");

        customInput.setSingleLine(true);
        customInput.setTextColor(0xFFFFFFFF);
        customInput.setHintTextColor(0xFF888888);

        controls.addView(customInput);

        Button sendCustomBtn =
                btn(
                        "SEND CUSTOM FRAME",
                        v -> {

                            String text =
                                    customInput
                                            .getText()
                                            .toString()
                                            .trim();

                            String[] parts =
                                    text.split("\\s+");

                            if (parts.length != 3) {

                                line(
                                        "custom frame needs " +
                                        "exactly 3 hex bytes: " +
                                        "type cmd arg");

                                return;
                            }

                            try {

                                int t =
                                        Integer.parseInt(
                                                parts[0],
                                                16);

                                int cv =
                                        Integer.parseInt(
                                                parts[1],
                                                16);

                                int av =
                                        Integer.parseInt(
                                                parts[2],
                                                16);

                                sendCustom(
                                        t,
                                        cv,
                                        av);

                            } catch (Exception e) {

                                line(
                                        "parse error: " +
                                        e.getMessage());
                            }
                        });

        controls.addView(sendCustomBtn);

        /*
         * Fast-iteration SET_CLOCK guess: type + cmd only,
         * current Unix time is filled in automatically as the
         * 4-byte argument.
         */
        clockInput = new EditText(this);

        clockInput.setHint(
                "type cmd hex for clock guess, e.g. 23 2C");

        clockInput.setSingleLine(true);
        clockInput.setTextColor(0xFFFFFFFF);
        clockInput.setHintTextColor(0xFF888888);

        controls.addView(clockInput);

        Button sendClockBtn =
                btn(
                        "SET_CLOCK NOW (fill time + send)",
                        v -> {

                            String text =
                                    clockInput
                                            .getText()
                                            .toString()
                                            .trim();

                            String[] parts =
                                    text.split("\\s+");

                            if (parts.length != 2) {

                                line(
                                        "clock guess needs " +
                                        "exactly 2 hex bytes: " +
                                        "type cmd");

                                return;
                            }

                            try {

                                int t =
                                        Integer.parseInt(
                                                parts[0],
                                                16);

                                int cv =
                                        Integer.parseInt(
                                                parts[1],
                                                16);

                                sendClockGuess(t, cv);

                            } catch (Exception e) {

                                line(
                                        "parse error: " +
                                        e.getMessage());
                            }
                        });

        controls.addView(sendClockBtn);

        ScrollView controlsScroll =
                new ScrollView(this);

        controlsScroll.addView(controls);

        root.addView(
                controlsScroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        /*
         * ------------------------------------------------------------
         * Log section: label, CLEAR/SAVE/COPY row, then the log
         * itself - given by far the largest weight so it dominates
         * the screen regardless of how many controls exist above it.
         * ------------------------------------------------------------
         */
        TextView logLabel =
                new TextView(this);

        logLabel.setText("LOG");
        logLabel.setTextSize(14);
        logLabel.setTextColor(0xFF888888);
        logLabel.setPadding(0, 20, 0, 6);

        root.addView(
                logLabel,
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout logButtonsRow =
                new LinearLayout(this);
        logButtonsRow.setOrientation(LinearLayout.HORIZONTAL);

        Button clearLogBtn =
                btn("CLEAR LOG", v -> clearLog());
        Button saveLogBtn =
                btn("SAVE LOG", v -> saveLogSnapshot());
        Button copyLogBtn =
                btn("COPY LOG", v -> copyLogToClipboard());

        logButtonsRow.addView(
                clearLogBtn,
                new LinearLayout.LayoutParams(0, -2, 1));
        logButtonsRow.addView(
                saveLogBtn,
                new LinearLayout.LayoutParams(0, -2, 1));
        logButtonsRow.addView(
                copyLogBtn,
                new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(
                logButtonsRow,
                new LinearLayout.LayoutParams(-1, -2));

        log =
                new TextView(this);

        log.setTextIsSelectable(true);
        log.setTextSize(12);
        log.setTextColor(0xFFEFEFEF);
        log.setBackgroundColor(0xFF1A1A1A);
        log.setPadding(16, 16, 16, 16);

        scrollView =
                new ScrollView(this);

        scrollView.addView(log);

        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        4));

        setContentView(root);
    }

    private Button btn(
            String text,
            View.OnClickListener listener) {

        Button b =
                new Button(this);

        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15);
        b.setBackgroundColor(0xFF2A2A2A);
        b.setPadding(16, 28, 16, 28);
        b.setOnClickListener(listener);

        return b;
    }

    /*
     * ------------------------------------------------------------------
     * Permissions
     * ------------------------------------------------------------------
     */

    private void requestPerms() {

        if (Build.VERSION.SDK_INT >= 31) {

            ArrayList<String> p =
                    new ArrayList<>();

            if (checkSelfPermission(
                    Manifest.permission
                            .BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {

                p.add(
                        Manifest.permission
                                .BLUETOOTH_SCAN);
            }

            if (checkSelfPermission(
                    Manifest.permission
                            .BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {

                p.add(
                        Manifest.permission
                                .BLUETOOTH_CONNECT);
            }

            if (!p.isEmpty()) {

                requestPermissions(
                        p.toArray(
                                new String[0]),
                        REQ);
            }
        }
    }

    /*
     * ------------------------------------------------------------------
     * BLE scanning
     * ------------------------------------------------------------------
     */

    private void stopScanning() {

        if (scanner != null) {

            try {
                scanner.stopScan(sc);
            } catch (Exception ignored) {
            }

            scanner = null;

            line("SCAN STOP");
        }
    }

    private void scan() {

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {

            requestPerms();
            return;
        }

        scanner =
                adapter.getBluetoothLeScanner();

        line("SCANNING 10s...");
        updateStatus("● SCANNING...");

        ScanFilter f =
                new ScanFilter.Builder()
                        .setServiceUuid(
                                new android.os.ParcelUuid(
                                        svc))
                        .build();

        ScanSettings ss =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings
                                        .SCAN_MODE_LOW_LATENCY)
                        .build();

        scanner.startScan(
                Collections.singletonList(f),
                ss,
                sc);

        new Handler(
                Looper.getMainLooper())
                .postDelayed(
                        this::stopScanning,
                        10000);
    }

    private final ScanCallback sc =
            new ScanCallback() {

        @Override
        public void onScanResult(
                int type,
                ScanResult r) {

            BluetoothDevice d =
                    r.getDevice();

            line(
                    "FOUND " +
                    d.getName() +
                    " " +
                    d.getAddress() +
                    " RSSI=" +
                    r.getRssi());

            updateStatus("● FOUND " + d.getName());

            if (gatt == null &&
                    pendingDevice == null) {

                if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(
                                Manifest.permission
                                        .BLUETOOTH_CONNECT)
                                != PackageManager.PERMISSION_GRANTED) {

                    return;
                }

                stopScanning();

                if (d.getBondState() ==
                        BluetoothDevice.BOND_BONDED) {

                    line(
                            "ALREADY BONDED, " +
                            "CONNECTING " +
                            d.getAddress());

                    updateStatus("● CONNECTING " + d.getName());

                    gatt =
                            d.connectGatt(
                                    MainActivity.this,
                                    false,
                                    cb,
                                    BluetoothDevice
                                            .TRANSPORT_LE);

                } else {

                    line(
                            "NOT BONDED - " +
                            "requesting bond " +
                            d.getAddress());

                    updateStatus("● BONDING " + d.getName());

                    pendingDevice = d;

                    boolean started =
                            d.createBond();

                    if (!started) {

                        line(
                                "createBond() " +
                                "returned false");

                        pendingDevice = null;
                    }
                }
            }
        }
    };

    /*
     * ------------------------------------------------------------------
     * Logging
     * ------------------------------------------------------------------
     */

    private String shortUuid(UUID u) {

        return u.toString()
                .substring(4, 8);
    }

    /*
     * Fixed status-bar update - UI only, no BLE state changes here.
     * Called from existing BLE callbacks below to reflect connection
     * state; it does not alter what those callbacks decide to do.
     */
    private void updateStatus(String s) {

        runOnUiThread(() -> {
            if (statusBar != null) {
                statusBar.setText(s);
            }
        });
    }

    private void clearLog() {

        runOnUiThread(() -> {
            if (log != null) {
                log.setText("");
            }
        });

        line("(log cleared on screen - saved files are untouched)");
    }

    private void saveLogSnapshot() {

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("SAVE LOG ERROR: external files directory unavailable");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        java.io.File snapshot = new java.io.File(
                dir, "labrador_snapshot_" + stamp + ".txt");

        String content = log == null ? "" : log.getText().toString();

        try (java.io.FileWriter fw = new java.io.FileWriter(snapshot)) {

            fw.write(content);

            line("LOG SNAPSHOT SAVED:");
            line(snapshot.getAbsolutePath());

            Toast.makeText(this,
                    "Log saved: " + snapshot.getName(),
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {

            line("SAVE LOG ERROR: " + e);
        }
    }

    private void copyLogToClipboard() {

        String content = log == null ? "" : log.getText().toString();

        ClipboardManager cm =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        ClipData clip = ClipData.newPlainText("labrador_log", content);

        if (cm != null) {
            cm.setPrimaryClip(clip);
        }

        line("(log copied to clipboard)");

        Toast.makeText(this,
                "Log copied to clipboard",
                Toast.LENGTH_SHORT).show();
    }

    private void line(String s) {

        runOnUiThread(() -> {

            String old =
                    log == null
                            ? ""
                            : log.getText()
                            .toString();

            if (old.length() > 20000) {

                old =
                        old.substring(
                                old.length() - 16000);
            }

            if (log != null) {

                log.setText(
                        old +
                        String.format(
                                "\n%tT  %s",
                                new Date(),
                                s));
            }

            if (scrollView != null) {

                scrollView.post(() ->
                        scrollView.fullScroll(
                                View.FOCUS_DOWN));
            }
        });
    }

    /*
     * ------------------------------------------------------------------
     * Cleanup
     * ------------------------------------------------------------------
     */

    @Override
    protected void onDestroy() {

        try {
            unregisterReceiver(
                    bondReceiver);
        } catch (Exception ignored) {
        }

        try {
            if (gatt != null) {
                gatt.close();
            }
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }
}
