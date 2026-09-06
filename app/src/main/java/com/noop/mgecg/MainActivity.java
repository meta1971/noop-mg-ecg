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
                g.discoverServices();

            } else if (state ==
                    BluetoothProfile.STATE_DISCONNECTED) {

                line("GATT DISCONNECTED");

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
                " raw=" + Protocol.hex(value));

        line("");
        line("LABRADOR 0007 FRAGMENT #" +
                labradorPacketCount);

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
