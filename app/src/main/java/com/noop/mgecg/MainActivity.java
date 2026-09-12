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
    private TextView field113Display;
    private final ArrayDeque<Float> field113History = new ArrayDeque<>();
    private TextView byte180Display;
    private final ArrayDeque<Integer> byte180History = new ArrayDeque<>();
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
     * ------------------------------------------------------------------
     * Two standard DIS characteristics never queried before this
     * point - System ID and PnP ID. PnP ID in particular carries a
     * structured Vendor ID/Product ID/Product Version, which is a
     * much more likely candidate for what NOOP's own "DIS attestation"
     * check (issue #891 - "the strap has not attested itself an MG
     * over DIS") actually inspects, versus the loosely-formatted
     * Model Number text string ("MG") we've been reading and trusting
     * all session.
     * ------------------------------------------------------------------
     */
    private final UUID systemIdChar =
            UUID.fromString("00002A23-0000-1000-8000-00805f9b34fb");

    private final UUID pnpIdChar =
            UUID.fromString("00002A50-0000-1000-8000-00805f9b34fb");

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
     * Callback for whatever operation is currently in flight on the
     * write characteristic, invoked from the real onCharacteristicWrite()
     * ack - not a timer. Lets one send() explicitly chain off another
     * send()'s confirmed completion, rather than relying on the shared
     * queue's serialization being the only thing enforcing order.
     */
    private Runnable pendingWriteCallback;

    /*
     * Waits for the REAL application-layer confirmation of a specific
     * SET_CONFIG flag - the strap's own echo of that exact flag name
     * on channel 0003 - not just the BLE-layer write ack. Added after
     * finding those two are genuinely different moments: the write ack
     * fires in ~30ms (just "the bytes went out"), while the real echo
     * confirming the strap processed it can arrive tens of ms later -
     * late enough that chaining off the write ack alone still let
     * TOGGLE_LABRADOR_FILTERED_ON fire before the gate's real
     * confirmation had come back.
     */
    private String pendingEcgGateConfirmationFlagName;
    private Runnable pendingEcgGateConfirmationCallback;

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
    private int pullGeneration = 0;

    private java.io.File historicalBinaryFile;
    private final List<byte[]> historicalFragments = new ArrayList<>();
    private int historicalTotalBytes = 0;

    /*
     * The real 8-byte progress cursor, confirmed from judes.club's
     * primary-source writeup: every status frame embeds this, and it
     * must be echoed back verbatim as the 0x17 ack payload or the
     * offload stalls after one chunk. We extract it from the firmware's
     * own "Trim: 0xBBBBBBBB:OOOOOOOO" debug-text line (already decoded
     * by dumpAsciiRuns()) rather than parsing raw status-frame bytes,
     * since the text is already proven reliable this session. null
     * until the first Trim line is seen.
     */
    private byte[] lastKnownCursor = null;

    /*
     * ------------------------------------------------------------------
     * Waveform-88 ECG-control tracking - persists across app launches
     * (SharedPreferences) so a no-ECG control pull and an ECG-then-pull
     * run, done in separate sessions on separate days, can still be
     * compared. ecgEverRunThisConnection resets on every fresh GATT
     * connection; ecgRanBeforeCurrentPull is a snapshot of it taken the
     * moment a pull starts, since that's the meaningful "was this a
     * control or not" boundary - not whatever happens to be true by
     * the time the pull finishes.
     * ------------------------------------------------------------------
     */
    private boolean ecgEverRunThisConnection = false;
    private boolean ecgRanBeforeCurrentPull = false;
    private int waveform88SeenThisPull = 0;

    /*
     * ------------------------------------------------------------------
     * Waveform-88 block reconstruction - a "block" is the 10 sub-
     * records (subId 0-9) that make up one continuous waveform chunk.
     * A subId reappearing with byte-identical content is a genuine
     * retransmission and is ignored; reappearing with DIFFERENT
     * content means a new block has started reusing subId numbers,
     * so whatever's collected so far gets archived first. Reset at
     * the start of every pull.
     * ------------------------------------------------------------------
     */
    private final Map<Integer, int[]> currentWaveformBlock = new TreeMap<>();
    private final List<Map<Integer, int[]>> completedWaveformBlocks =
            new ArrayList<>();
    private java.io.File waveformOutputFile;

    /*
     * ------------------------------------------------------------------
     * Waveform-188 tracking - simpler than the 88-byte case since we
     * don't yet have a confirmed "block size" for this shape (the one
     * real capture showed a repeated batch of 5 sub-ids followed by a
     * long unbroken climb, not a clean 0-9 wraparound). So rather than
     * guess a grouping, every distinct (non-duplicate) frame is kept
     * in arrival order and written out flat; dedup is by exact raw
     * byte match, not by sub-id, since sub-id alone isn't guaranteed
     * unique across a long pull for this shape.
     * ------------------------------------------------------------------
     */
    private static class Waveform188Record {
        final int subId;
        final int[] samples;
        Waveform188Record(int subId, int[] samples) {
            this.subId = subId;
            this.samples = samples;
        }
    }

    private final Set<String> seenWaveform188RawHex = new LinkedHashSet<>();
    private final List<Waveform188Record> waveform188Records =
            new ArrayList<>();

    /*
     * ------------------------------------------------------------------
     * Pull auto-stop, idle-based rather than a fixed cycle count. The
     * old fixed 200-cycle cutoff could stop a transfer that was still
     * genuinely active - exactly what nearly happened here, since the
     * type=0x31 status frames only start appearing once the real
     * burst is already finished, and are worth watching past the
     * point a fixed count would have cut off. pullIdleCycles resets
     * on ANY relevant activity (a history burst frame or a 0x31
     * status frame); IDLE_CYCLE_LIMIT is the real stop condition.
     * SAFETY_CYCLE_LIMIT is a generous runaway guard only, not a
     * target - it should essentially never be hit in practice.
     * ------------------------------------------------------------------
     */
    private static final int IDLE_CYCLE_LIMIT = 600;      // ~3.5min at 350ms/cycle
    private static final int SAFETY_CYCLE_LIMIT = 6000;  // ~35min, runaway guard only
    private int pullIdleCycles = 0;   // cycles since the last NEW HIST_BURST
                                        // frame specifically - NOT reset by
                                        // status-31 pings (see decodeStatusFrame31)
    private boolean historyDrainedThisPull = false;

    /*
     * ------------------------------------------------------------------
     * Real ECG generation - confirmed from NOOP's own public GitHub
     * (ryanbr/noop, PR #1727), cross-validated three ways on real MG
     * hardware: an isolation test, a 10-cycle reliability run, and the
     * strap's own firmware console log ("MAX86176: Set ECG ON") firing
     * once per real start. cmd=0x7C (124) is mainControlECGDataGeneration:
     *
     *   arg=0  REFUSED outright (FAILURE ack)
     *   arg=1  SUCCESS ack, but actually STOPS generation
     *   arg=2  SUCCESS ack, actually STARTS generation
     *
     * Prerequisite: TOGGLE_LABRADOR_FILTERED (cmd=0x8B=139) must be ON
     * first, or the real data stream stays silent even with arg=2 -
     * confirmed by an isolation test with three negative controls.
     *
     * Real data arrives as type=43 (0x2B) REALTIME_RAW_DATA
     * notifications - a completely different envelope type from
     * anything filtered for earlier this session.
     * ------------------------------------------------------------------
     */
    private java.io.File realtimeEcgBinaryFile;
    private final List<byte[]> realtimeEcgFragments = new ArrayList<>();
    private int realtimeEcgTotalBytes = 0;

    /*
     * Explicit tracking for the firmware's own "MAX86176: Set ECG ON"
     * console line, so its presence/absence is a clear, reported
     * fact rather than something that could silently scroll by
     * unnoticed in a long log.
     */
    private boolean maxEcgOnSeen = false;

    /*
     * Tracks real COMMAND_RESPONSE outcomes for the three ECG commands
     * this attempt, so the final verdict can be reported using the
     * exact same categories the real NOOP app's own Whoop5EcgProbe
     * uses (NoReplies / DataRequestRefused / AcceptedButSilent /
     * etc.) instead of us re-deriving an ad-hoc summary each time.
     * Keyed by cmd (139/124/125) -> result code (0-3, or -1 unmapped).
     */
    private final Map<Integer, Integer> ecgCommandResponsesThisAttempt =
            new HashMap<>();

    /*
     * Heartbeat so a long silent window during an armed ECG session
     * is visibly confirmed as "still listening, nothing arrived" -
     * distinguishable from the app/log having stopped. Uses the same
     * generation-guard pattern as the pull-ack loop, for the same
     * reason: immune to any stray duplicate callback.
     */
    private boolean ecgListenActive = false;
    private int ecgListenGeneration = 0;
    private long ecgListenStartedAtMs = 0;

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

            logRaw("BOND_STATE " + label +
                    " addr=" + (d != null ? d.getAddress() : "?"));

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

                logRaw("CONNECTING_POST_BOND addr=" +
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

                logRaw("BONDING_FAILED_OR_CANCELLED addr=" +
                        (d != null ? d.getAddress() : "?"));

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

            logRaw("GATT_STATE_CHANGE state=" + state +
                    " status=" + status);

            if (state ==
                    BluetoothProfile.STATE_CONNECTED) {

                line("GATT CONNECTED");
                logRaw("GATT_CONNECTED");
                updateStatus("● CONNECTED");
                g.discoverServices();

            } else if (state ==
                    BluetoothProfile.STATE_DISCONNECTED) {

                line("GATT DISCONNECTED");
                logRaw("GATT_DISCONNECTED");
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

                ecgEverRunThisConnection = false;
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

            Runnable cb = pendingWriteCallback;
            pendingWriteCallback = null;

            opDone();

            if (cb != null) {
                cb.run();
            }
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
         * Every RX event now also persists here unconditionally - not
         * just the ones with dedicated handlers below. This was the
         * same class of gap the bonding sequence had: something only
         * ever went to the on-screen line() view and silently never
         * reached the file a real capture gets analyzed from. Found
         * this time via the ECG gate value test, whose actual RX
         * reaction (a Labrador identity broadcast) only showed up in
         * a SAVE LOG snapshot, never in this file.
         */
        logRaw("RX_GENERIC channel=" + uuid +
                " len=" + value.length +
                " raw=" + Protocol.hex(value));

        checkPendingEcgGateConfirmation(value);

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
         * MAX86176 console confirmation - deliberately checked on
         * EVERY incoming frame regardless of envelope type, not just
         * type=0x32 like before. We've already found three separate
         * envelope types this session (0x31, 0x24-echoes, the waveform
         * shapes) that our code had zero awareness of until we went
         * looking - gating the one check that actually answers
         * "did the scanner really start" behind a specific assumed
         * type risked exactly the same blind spot. This runs
         * unconditionally now so a real confirmation can't be missed
         * just because it arrived somewhere we didn't expect.
         */
        checkForEcgConsoleConfirmation(value);

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
                extractCursorFromDebugText(value);
            } else if (envType == 43) {
                handleRealtimeEcgFrame(uuid, value);
            } else if (envType == 0x31 && envCmd == 0x02) {
                decodeStatusFrame31(value);
            } else if (envType == 0x24 &&
                    (envCmd == 119 || envCmd == 121)) {
                line("*** DEVICE_CONFIG_VALUE REPLY (cmd=" +
                        envCmd + ") - see ASCII/hex above for content ***");
                logRaw("DEVICE_CONFIG_VALUE_REPLY cmd=" + envCmd +
                        " raw=" + Protocol.hex(value));
            } else if (envType == 0x24 &&
                    (envCmd == 139 || envCmd == 124 || envCmd == 125 ||
                            envCmd == 123)) {

                /*
                 * A REAL COMMAND_RESPONSE for one of the four opcodes
                 * the project's own EcgResearchAllowList groups as
                 * PROBE_OPCODES = {123, 124, 125, 139} - SELECT_WRIST
                 * included, per real PR discussion (#1969) noting its
                 * right/left mapping is unverified and "one value the
                 * measured firmware refuses" - meaning it gets a real,
                 * differentiated response unlike our three ECG toggles.
                 * Tracking it the same way to find out which value is
                 * actually accepted on this unit.
                 */
                String cmdName = envCmd == 139
                        ? "TOGGLE_REALTIME_FILTERED_ECG"
                        : envCmd == 124
                        ? "MAIN_CONTROL_ECG_DATA_GENERATION"
                        : envCmd == 125
                        ? "TOGGLE_SAVE_RAW_ECG"
                        : "SELECT_WRIST";

                int resultCode = value.length > 12 ? (value[12] & 0xff) : -1;

                String resultLabel;
                switch (resultCode) {
                    case 0: resultLabel = "FAILURE(0)"; break;
                    case 1: resultLabel = "SUCCESS(1)"; break;
                    case 2: resultLabel = "PENDING(2)"; break;
                    case 3: resultLabel = "UNSUPPORTED(3)"; break;
                    default: resultLabel = "unmapped(" + resultCode + ")";
                }

                line("*** REAL COMMAND_RESPONSE for " + cmdName +
                        " (cmd=" + envCmd + "): " + resultLabel + " ***");

                logRaw("ECG_COMMAND_RESPONSE cmd=" + envCmd +
                        " name=" + cmdName +
                        " result=" + resultLabel);

                recordEcgCommandResponse(envCmd, resultCode);

            } else if (envType == 0x24) {

                /*
                 * A COMMAND_RESPONSE (type 0x24) for an opcode not in
                 * any of our named sets above - exactly what a hit
                 * from the neighboring-opcode sweep would look like.
                 * Flagged explicitly and distinctly so it can't blend
                 * into the raw log unnoticed.
                 */
                int resultCode = value.length > 12 ? (value[12] & 0xff) : -1;

                line("*** GENERIC COMMAND_RESPONSE for UNMAPPED cmd=" +
                        envCmd + " (0x" + String.format("%02X", envCmd) +
                        "): result byte=" + resultCode + " - THIS MAY " +
                        "BE A REAL HIT, INVESTIGATE ***");

                logRaw("GENERIC_COMMAND_RESPONSE cmd=" + envCmd +
                        " resultByte=" + resultCode +
                        " raw=" + Protocol.hex(value));

            } else if (envType != 0x2F && envType != 0x32 &&
                    envType != 43 && envType != 0x31 &&
                    envType != 0x24) {

                /*
                 * A genuinely unrecognized envelope type - dump its
                 * printable ASCII too (not just hex), the same way
                 * type=0x32 already gets, since console-style debug
                 * text could plausibly ride on a type we haven't
                 * catalogued yet.
                 */
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
     * Explicit check for the firmware's own "MAX86176: Set ECG ON"
     * console line - the strap's real analog-front-end confirmation
     * that generation actually started, confirmed from NOOP's own
     * PR #1727. Kept separate from the generic dumpAsciiRuns() print
     * so its presence or absence is a clear, tracked fact rather than
     * something that could scroll by unnoticed in a long log.
     */
    private void checkForEcgConsoleConfirmation(byte[] v) {

        String text = new String(
                v, java.nio.charset.StandardCharsets.US_ASCII);

        if (text.contains("MAX86176") && text.contains("ECG ON")) {

            maxEcgOnSeen = true;

            line("*** CONFIRMED: firmware console logged " +
                    "\"MAX86176: Set ECG ON\" ***");

            logRaw("MAX86176_SET_ECG_ON_CONFIRMED");
        }
    }

    private void recordEcgCommandResponse(int cmd, int resultCode) {
        ecgCommandResponsesThisAttempt.put(cmd, resultCode);
    }

    /*
     * Reports the final verdict for this ECG attempt using the same
     * categories NOOP's own Whoop5EcgProbe.kt defines - so "what
     * actually happened" is named precisely instead of re-derived
     * ad hoc each time. Call this once, right after ECG STOP.
     */
    private void reportEcgAttemptVerdict() {

        line("");
        line("=== ECG ATTEMPT VERDICT (NOOP Whoop5EcgProbe categories) ===");

        if (ecgCommandResponsesThisAttempt.isEmpty()) {

            line("*** VERDICT: NoReplies - no COMMAND_RESPONSE arrived " +
                    "at all for any of the 3 ECG commands. The strap " +
                    "answered nothing at the application layer - only " +
                    "the low-level BLE write ack was ever confirmed. ***");

            logRaw("ECG_VERDICT=NoReplies");
            return;
        }

        boolean anyFailure = false;
        boolean anyUnsupported = false;
        StringBuilder detail = new StringBuilder();

        for (Map.Entry<Integer, Integer> e : ecgCommandResponsesThisAttempt.entrySet()) {

            int cmd = e.getKey();
            int result = e.getValue();

            String cmdName = cmd == 139 ? "TOGGLE_REALTIME_FILTERED_ECG"
                    : cmd == 124 ? "MAIN_CONTROL_ECG_DATA_GENERATION"
                    : "TOGGLE_SAVE_RAW_ECG";

            detail.append(cmdName).append("=").append(result).append(" ");

            if (result == 0) anyFailure = true;
            if (result == 3) anyUnsupported = true;
        }

        line("Responses seen: " + detail);

        if (anyUnsupported) {

            line("*** VERDICT: OpcodeUnsupported - the firmware does " +
                    "not implement one of these opcodes at all. ***");

            logRaw("ECG_VERDICT=OpcodeUnsupported detail=" + detail);

        } else if (anyFailure) {

            line("*** VERDICT: a command was REFUSED (FAILURE). If " +
                    "that command asks for realtime ECG data, this is " +
                    "DataRequestRefused - the firmware knows the opcode " +
                    "and refused to run it. If not, this is just " +
                    "CommandRefused and says nothing about the block " +
                    "question. ***");

            logRaw("ECG_VERDICT=Refused detail=" + detail);

        } else if (!maxEcgOnSeen &&
                (realtimeEcgFragments == null || realtimeEcgFragments.isEmpty())) {

            line("*** VERDICT: AcceptedButSilent - every command that " +
                    "replied returned SUCCESS, yet no ECG packet arrived. " +
                    "This does not identify a cause - data banked to " +
                    "flash rather than streamed, an entitlement gate, or " +
                    "an electrode circuit that never closed would all " +
                    "look exactly like this. ***");

            logRaw("ECG_VERDICT=AcceptedButSilent detail=" + detail);

        } else {

            line("*** VERDICT: Inconclusive - mixed/unexpected result " +
                    "codes. See detail above. ***");

            logRaw("ECG_VERDICT=Inconclusive detail=" + detail);
        }
    }

    /*
     * Fires a real, waiting callback the moment the strap's own echo
     * of a specific SET_CONFIG flag name actually arrives, rather than
     * the BLE-layer write ack, which fires earlier and doesn't mean
     * the strap has actually processed the value yet. See the field
     * declaration comment above for the full context.
     */
    private void checkPendingEcgGateConfirmation(byte[] v) {

        if (pendingEcgGateConfirmationCallback == null
                || pendingEcgGateConfirmationFlagName == null) {
            return;
        }

        String text = new String(
                v, java.nio.charset.StandardCharsets.US_ASCII);

        if (text.contains(pendingEcgGateConfirmationFlagName)) {

            line("*** REAL ECG GATE CONFIRMATION ECHO RECEIVED for \"" +
                    pendingEcgGateConfirmationFlagName + "\" ***");

            logRaw("ECG_GATE_REAL_ECHO_CONFIRMED flag=" +
                    pendingEcgGateConfirmationFlagName);

            Runnable cb = pendingEcgGateConfirmationCallback;

            pendingEcgGateConfirmationCallback = null;
            pendingEcgGateConfirmationFlagName = null;

            cb.run();
        }
    }

    /*
     * Extracts the real progress cursor from a "Trim: 0xBBBBBBBB:OOOOOOOO"
     * debug-text line (confirmed format from the firmware's own console
     * output, seen repeatedly this session in "History burst success"
     * messages). Packs the two 4-byte values as an 8-byte little-endian
     * cursor, matching this protocol's general byte-order convention,
     * and stores it for the next 0x17 ack to echo back verbatim.
     */
    private static final java.util.regex.Pattern TRIM_PATTERN =
            java.util.regex.Pattern.compile(
                    "Trim:\\s*0x([0-9A-Fa-f]{8}):([0-9A-Fa-f]{8})");

    private void extractCursorFromDebugText(byte[] v) {

        String text = new String(
                v, java.nio.charset.StandardCharsets.US_ASCII);

        java.util.regex.Matcher m = TRIM_PATTERN.matcher(text);

        if (!m.find()) {
            return;
        }

        long blockIndex = Long.parseLong(m.group(1), 16);
        long byteOffset = Long.parseLong(m.group(2), 16);

        byte[] cursor = new byte[8];

        cursor[0] = (byte) (blockIndex & 0xFF);
        cursor[1] = (byte) ((blockIndex >> 8) & 0xFF);
        cursor[2] = (byte) ((blockIndex >> 16) & 0xFF);
        cursor[3] = (byte) ((blockIndex >> 24) & 0xFF);
        cursor[4] = (byte) (byteOffset & 0xFF);
        cursor[5] = (byte) ((byteOffset >> 8) & 0xFF);
        cursor[6] = (byte) ((byteOffset >> 16) & 0xFF);
        cursor[7] = (byte) ((byteOffset >> 24) & 0xFF);

        lastKnownCursor = cursor;

        line("*** REAL CURSOR CAPTURED: block=0x" +
                String.format("%08X", blockIndex) +
                " offset=0x" + String.format("%08X", byteOffset) +
                " - next 0x17 ack will echo it ***");

        logRaw("CURSOR_CAPTURED block=" + blockIndex +
                " offset=" + byteOffset +
                " raw=" + Protocol.hex(cursor));
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
     * SET/GET_DEVICE_CONFIG_VALUE - confirmed byte-exact from a real
     * NOOP debug-menu exchange against this same strap (session
     * 2026-09-07 09:06). cmd=119 (0x77) SET, cmd=121 (0x79) GET -
     * the same 0x79 we guessed correctly by opcode back in the
     * earlier config-value sweep, but with the WRONG argument shape:
     * it's not a single numeric key byte, it's a string key in a
     * fixed 32-byte null-padded field, which is why that sweep got
     * zero replies despite guessing the right opcode.
     *
     * Confirmed layout:
     *   byte 0        = 0x01 (prefix)
     *   bytes 1-32     = ASCII key, null-padded to 32 bytes
     *   byte 33 (SET only) = value byte
     *
     * GET_DEVICE_CONFIG_VALUE total = 33 bytes (no value byte)
     * SET_DEVICE_CONFIG_VALUE total = 34 bytes
     * ------------------------------------------------------------------
     */

    private static final int DEVICE_CONFIG_KEY_FIELD_LEN = 32;

    private byte[] buildDeviceConfigArg(String key, Integer valueByteOrNull) {

        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        if (keyBytes.length > DEVICE_CONFIG_KEY_FIELD_LEN) {
            throw new IllegalArgumentException(
                    "key too long for 32-byte field: " + key);
        }

        int totalLen = 1 + DEVICE_CONFIG_KEY_FIELD_LEN +
                (valueByteOrNull != null ? 1 : 0);

        byte[] out = new byte[totalLen];

        out[0] = 0x01;

        System.arraycopy(keyBytes, 0, out, 1, keyBytes.length);

        /*
         * Remaining bytes in the 32-byte field are already 0x00 -
         * Java zero-initialises new byte arrays.
         */

        if (valueByteOrNull != null) {
            out[totalLen - 1] = (byte) (int) valueByteOrNull;
        }

        return out;
    }

    private void getDeviceConfigValue(String key) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        byte[] arg = buildDeviceConfigArg(key, null);
        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labradorBytes(0x23, 121, arg, thisSeq);

            logRaw("TX GET_DEVICE_CONFIG_VALUE key=" + key +
                    " seq=0x" + String.format("%02X", thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX GET_DEVICE_CONFIG_VALUE key=\"" + key + "\"");
            line("TX LEN =" + f.length);
            line("TX RAW =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(GET_DEVICE_CONFIG_VALUE)");
                opDone();
            }
        });
    }

    private void setDeviceConfigValue(String key, int valueByte) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        byte[] arg = buildDeviceConfigArg(key, valueByte);
        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labradorBytes(0x23, 119, arg, thisSeq);

            logRaw("TX SET_DEVICE_CONFIG_VALUE key=" + key +
                    " value=0x" + String.format("%02X", valueByte) +
                    " seq=0x" + String.format("%02X", thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX SET_DEVICE_CONFIG_VALUE key=\"" + key +
                    "\" value=0x" + String.format("%02X", valueByte));
            line("TX LEN =" + f.length);
            line("TX RAW =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(SET_DEVICE_CONFIG_VALUE)");
                opDone();
            }
        });
    }

    /*
     * One-tap: set the confirmed ECG raw-data gate, read it back for
     * confirmation, then START using the validated real sequence.
     *
     * FIXED: this previously sent arg=1, which we've since confirmed
     * (PR #1727, cross-validated multiple ways) actually STOPS/no-ops
     * generation rather than starting it - meaning this button was
     * self-defeating since that correction. Now sends the confirmed
     * real start (TOGGLE_LABRADOR_FILTERED=1, real-ack-chained into
     * mainControlECGDataGeneration=2), matching ECG START (real).
     */
    /*
     * ------------------------------------------------------------------
     * Tests whether the ECG gate's value convention is really ASCII
     * digit (0x31 = '1'), as we've assumed by analogy with the R22
     * SET_CONFIG flags - or whether that assumption was never actually
     * valid for THIS command. enable_raw_data_w_ecg goes through
     * SET/GET_DEVICE_CONFIG_VALUE (cmd 119/121), a different command
     * from the R22 flags (cmd 120/0x78) where ASCII-digit was
     * independently confirmed by the community writeup. We carried
     * that convention over here without ever checking it - this sends
     * BOTH raw 0x01 and ASCII 0x31, with a GET read-back after each,
     * so the two responses can be compared directly instead of
     * continuing to assume one of them is right.
     * ------------------------------------------------------------------
     */
    private void testEcgGateValueConvention() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot test ECG gate value");
            return;
        }

        line("");
        line("*** TESTING ECG GATE VALUE CONVENTION: raw 0x01 vs " +
                "ASCII 0x31 ('1') ***");
        logRaw("ECG_GATE_VALUE_TEST_BEGIN");

        line("--- sending raw 0x01 ---");
        setDeviceConfigValue("enable_raw_data_w_ecg", 0x01);
        getDeviceConfigValue("enable_raw_data_w_ecg");

        mainH.postDelayed(() -> {

            line("--- sending ASCII 0x31 ('1') ---");
            setDeviceConfigValue("enable_raw_data_w_ecg", 0x31);
            getDeviceConfigValue("enable_raw_data_w_ecg");

            logRaw("ECG_GATE_VALUE_TEST_BOTH_SENT - compare the two " +
                    "DEVICE_CONFIG_VALUE_REPLY lines above/below");

        }, 1500);
    }

    private void enableEcgGateThenStart() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot enable ECG gate");
            return;
        }

        line("");
        line("*** ENABLE ECG GATE, THEN START (real sequence, gate now " +
                "set via the CONFIRMED-WORKING SET_CONFIG mechanism, " +
                "not the old never-once-echoed one) ***");
        logRaw("ECG_GATE_SEQUENCE_BEGIN");

        sendR22Flag("enable_raw_data_w_ecg", '1');

        /*
         * Small buffer so the strap has processed the config write
         * before we START - the real app's own exchange showed the
         * write ack and read-back both landing well under this.
         */
        mainH.postDelayed(() -> {

            realtimeEcgFragments.clear();
            ecgCommandResponsesThisAttempt.clear();
            realtimeEcgTotalBytes = 0;
            realtimeEcgBinaryFile = null;
            maxEcgOnSeen = false;

            labradorActive = true;
            recordingComplete = false;
            labradorPacketCount = 0;

            ecgEverRunThisConnection = true;

            line("");
            line("*** STARTING (ECG gate on, real start sequence) ***");

            sendWithCallback(0x8B, 1,
                    "TOGGLE_REALTIME_FILTERED_ECG_ON (gate-then-start)", () ->
                    sendWithCallback(0x7D, 1,
                            "TOGGLE_SAVE_RAW_ECG_ON (gate-then-start)", () ->
                    send(0x7C, 2,
                            "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                    "(gate-then-start)")));

            ecgListenActive = true;
            ecgListenGeneration++;
            ecgListenStartedAtMs = System.currentTimeMillis();

            final int myGen = ecgListenGeneration;

            mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);

        }, 800);
    }

    /*
     * ------------------------------------------------------------------
     * FULL COMBINED ECG ATTEMPT - chains three things this session has
     * separately validated as real and correct, but never tried
     * together in the same connection:
     *
     *   1. R22 unlock (confirmed to genuinely change what data the
     *      strap sends - validated at scale: accelerometer magnitude
     *      0.98-1.00 and plausible HR across 180 real frames)
     *   2. The enable_raw_data_w_ecg gate (confirmed byte-exact real
     *      exchange, separate narrower mechanism from R22)
     *   3. The confirmed real ECG start sequence (TOGGLE_LABRADOR_
     *      FILTERED=1 real-ack-chained into mainControlECGDataGener-
     *      ation=2 - correct per NOOP PR #1727, sent correctly per
     *      multiple verified raw-wire captures this session)
     *
     * Every ECG attempt so far ran without R22 unlocked first. Every
     * R22 test was for historical/motion data, never combined with an
     * ECG attempt. This is the first test of whether ECG specifically
     * sits behind the same broader R22 gate - a real, motivated,
     * previously-untested combination, not a new guess.
     * ------------------------------------------------------------------
     */

    /*
     * ------------------------------------------------------------------
     * ULTIMATE ECG ATTEMPT - four genuinely untried ideas combined,
     * none of which are repeats of anything tried so far:
     *
     *   1. Explicitly stops SpO2/PPG streaming first. MAX86176 is one
     *      physical chip doing both PPG and ECG - if it's implicitly
     *      left in PPG mode from earlier in the session, a mode
     *      switch to ECG might silently fail with no PPG-off step
     *      first. Never tried.
     *   2. A real settling delay after START, before prompting for
     *      contact - analog front ends often need genuine warm-up
     *      time. Every prior attempt touched at or before START.
     *   3. Periodic re-sends of START (arg=2) every 5s through the
     *      hold, in case a one-time enable isn't enough and the
     *      hardware needs periodic refresh to stay powered.
     *   4. Probes arg=3 once, after the normal 0/1/2 are already
     *      known - not expected to do anything useful, but any
     *      distinct ACK/response from an undocumented value is more
     *      information than silence.
     * ------------------------------------------------------------------
     */
    private void runUltimateEcgAttempt() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run ultimate attempt");
            return;
        }

        line("");
        line("*** ULTIMATE ECG ATTEMPT: SPO2 OFF -> R22+GATE -> START -> " +
                "WAIT FOR WARM-UP -> TOUCH -> PERIODIC RE-KICK - " +
                "STRAP MUST BE WORN ***");
        logRaw("ULTIMATE_ECG_ATTEMPT_BEGIN");

        line("--- step 1: explicit SPO2/PPG stream OFF (mode reset) ---");
        sendCustom(0x3F, 0x3F, 0x00);

        mainH.postDelayed(() -> {

            sendGetAdvertisingName();

            for (int i = 0; i < R22_FLAGS.length; i++) {

                String flag = R22_FLAGS[i];
                long delayMs = 80L * (i + 1);

                mainH.postDelayed(() -> sendR22Flag(flag, '1'), delayMs);
            }

            long afterR22Ms = 80L * (R22_FLAGS.length + 2);

            mainH.postDelayed(() -> {

                line("--- step 2: setting ECG gate, waiting for its " +
                        "real echo ---");

                pendingEcgGateConfirmationFlagName = "enable_raw_data_w_ecg";
                pendingEcgGateConfirmationCallback = () -> {

                    line("--- step 3: gate confirmed - sending real " +
                            "START now, but NOT prompting for contact " +
                            "yet - waiting 5s for AFE warm-up first ---");

                    realtimeEcgFragments.clear();
                    ecgCommandResponsesThisAttempt.clear();
                    realtimeEcgTotalBytes = 0;
                    realtimeEcgBinaryFile = null;
                    maxEcgOnSeen = false;

                    labradorActive = true;
                    recordingComplete = false;
                    labradorPacketCount = 0;

                    ecgEverRunThisConnection = true;

                    sendWithCallback(0x8B, 1,
                            "TOGGLE_REALTIME_FILTERED_ECG_ON (ultimate)", () ->
                            sendWithCallback(0x7D, 1,
                                    "TOGGLE_SAVE_RAW_ECG_ON (ultimate)", () ->
                            send(0x7C, 2,
                                    "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                            "(ultimate, kick 1)")));

                    ecgListenActive = true;
                    ecgListenGeneration++;
                    ecgListenStartedAtMs = System.currentTimeMillis();

                    final int myGen = ecgListenGeneration;

                    mainH.postDelayed(
                            () -> runEcgListenHeartbeat(myGen), 5000);

                    mainH.postDelayed(() -> {

                        line("");
                        line("*** WARM-UP WAIT DONE - TOUCH THE CLASP " +
                                "NOW ***");

                    }, 5000);

                    /*
                     * Periodic re-kick every 5s through a 40s hold -
                     * step 3 of the idea list.
                     */
                    for (int k = 1; k <= 8; k++) {

                        long kickDelay = 5000L * k;
                        int kickNum = k + 1;

                        mainH.postDelayed(() -> {

                            if (ecgListenActive) {

                                line("--- periodic re-kick #" + kickNum +
                                        " (arg=2) ---");

                                send(0x7C, 2,
                                        "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                                "(ultimate, kick " +
                                                kickNum + ")");
                            }

                        }, kickDelay);
                    }
                };

                sendR22Flag("enable_raw_data_w_ecg", '1');

                mainH.postDelayed(() -> {

                    if (pendingEcgGateConfirmationCallback != null) {

                        line("*** gate echo TIMED OUT after 3s - " +
                                "proceeding anyway ***");

                        logRaw("ECG_GATE_ECHO_TIMEOUT flag=" +
                                "enable_raw_data_w_ecg");

                        Runnable cb = pendingEcgGateConfirmationCallback;

                        pendingEcgGateConfirmationCallback = null;
                        pendingEcgGateConfirmationFlagName = null;

                        cb.run();
                    }

                }, 3000);

            }, afterR22Ms);

        }, 500);
    }

    /*
     * Sends the one probe value nobody's tried - arg=3 on cmd 0x7C,
     * once, well after a normal stop, purely to see if an
     * undocumented argument produces a distinct ACK/response instead
     * of silence. Not expected to do anything useful by itself.
     */
    private void probeUndocumentedEcgArg() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot probe");
            return;
        }

        line("");
        line("*** PROBING UNDOCUMENTED cmd=0x7C arg=3 (not 0/1/2) - " +
                "watching for ANY distinct response ***");
        logRaw("ECG_ARG_PROBE_BEGIN arg=3");

        send(0x7C, 3, "MAIN_CONTROL_ECG_DATA_GENERATION_PROBE_ARG3");
    }

    /*
     * ------------------------------------------------------------------
     * Tests a specific sequence suggested externally (not from any
     * verified source - no citation for this exact procedure exists
     * in the real NOOP source, the hardware teardown, or any community
     * thread we've found): R22+gate flags -> arg=3 probe -> a 1.5s
     * wait -> ONE non-repeated START -> a long listen window BEFORE
     * any historical pull. Genuinely untried in this exact shape
     * (single kick with a preceding probe+wait, no history pull
     * beforehand), so worth a clean test even though the claimed
     * mechanism behind it isn't something we can verify.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * Tests both SELECT_WRIST argument values (0 and 1) and reports
     * which one actually gets accepted, per real PR discussion
     * (#1969) noting the right/left mapping is unverified and one
     * value is refused on real MG firmware. We've only ever sent
     * arg=0 once, standalone, never checked whether it's the
     * accepted or refused value, and never combined it with an ECG
     * attempt despite the project's own allow-list grouping
     * SELECT_WRIST as one of the four ECG probe opcodes.
     * ------------------------------------------------------------------
     */
    private void testBothWristValues() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot test wrist values");
            return;
        }

        line("");
        line("*** TESTING BOTH SELECT_WRIST VALUES (0 and 1) - " +
                "watching for COMMAND_RESPONSE to see which is " +
                "accepted vs refused ***");
        logRaw("WRIST_VALUE_TEST_BEGIN");

        ecgCommandResponsesThisAttempt.remove(123);

        send(0x7B, 0, "SELECT_WRIST_TEST_ARG0");

        mainH.postDelayed(() -> {

            Integer resultFor0 = ecgCommandResponsesThisAttempt.get(123);

            line("--- arg=0 result: " +
                    (resultFor0 == null ? "no COMMAND_RESPONSE" :
                            resultFor0) + " ---");

            logRaw("WRIST_VALUE_ARG0_RESULT=" +
                    (resultFor0 == null ? "NoReply" : resultFor0));

            ecgCommandResponsesThisAttempt.remove(123);

            send(0x7B, 1, "SELECT_WRIST_TEST_ARG1");

            mainH.postDelayed(() -> {

                Integer resultFor1 = ecgCommandResponsesThisAttempt.get(123);

                line("--- arg=1 result: " +
                        (resultFor1 == null ? "no COMMAND_RESPONSE" :
                                resultFor1) + " ---");

                logRaw("WRIST_VALUE_ARG1_RESULT=" +
                        (resultFor1 == null ? "NoReply" : resultFor1));

                line("=== WRIST VALUE TEST DONE - compare the two " +
                        "results above (0=FAILURE/1=SUCCESS/etc) ===");

            }, 2000);

        }, 2000);
    }

    private void runSuggestedSequenceTest() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run suggested sequence");
            return;
        }

        line("");
        line("*** TESTING EXTERNALLY-SUGGESTED SEQUENCE: flags -> " +
                "gate -> arg=3 probe -> wait 1.5s -> ONE start (no " +
                "repeats) -> long listen, NO pull until type=43 seen - " +
                "WEAR + TOUCH CLASP ***");
        logRaw("SUGGESTED_SEQUENCE_TEST_BEGIN");

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];
            long delayMs = 80L * (i + 1);

            mainH.postDelayed(() -> sendR22Flag(flag, '1'), delayMs);
        }

        long afterR22Ms = 80L * (R22_FLAGS.length + 2);

        mainH.postDelayed(() -> {

            pendingEcgGateConfirmationFlagName = "enable_raw_data_w_ecg";
            pendingEcgGateConfirmationCallback = () -> {

                line("--- gate confirmed - sending arg=3 probe, then " +
                        "waiting 1.5s before the single real start ---");

                send(0x7C, 3,
                        "MAIN_CONTROL_ECG_DATA_GENERATION_PROBE_ARG3 " +
                                "(suggested sequence)");

                mainH.postDelayed(() -> {

                    realtimeEcgFragments.clear();
                    ecgCommandResponsesThisAttempt.clear();
                    realtimeEcgTotalBytes = 0;
                    realtimeEcgBinaryFile = null;
                    maxEcgOnSeen = false;

                    labradorActive = true;
                    recordingComplete = false;
                    labradorPacketCount = 0;

                    ecgEverRunThisConnection = true;

                    line("--- sending the ONE real start now - no " +
                            "repeats, per the suggested sequence ---");

                    sendWithCallback(0x8B, 1,
                            "TOGGLE_REALTIME_FILTERED_ECG_ON (suggested)",
                            () -> sendWithCallback(0x7D, 1,
                            "TOGGLE_SAVE_RAW_ECG_ON (suggested)", () ->
                            send(0x7C, 2,
                                    "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                            "(suggested, single, no repeat)")));

                    ecgListenActive = true;
                    ecgListenGeneration++;
                    ecgListenStartedAtMs = System.currentTimeMillis();

                    final int myGen = ecgListenGeneration;

                    mainH.postDelayed(
                            () -> runEcgListenHeartbeat(myGen), 5000);

                }, 1500);
            };

            sendR22Flag("enable_raw_data_w_ecg", '1');

            mainH.postDelayed(() -> {

                if (pendingEcgGateConfirmationCallback != null) {

                    line("*** gate echo TIMED OUT - proceeding anyway ***");
                    logRaw("ECG_GATE_ECHO_TIMEOUT flag=enable_raw_data_w_ecg");

                    Runnable cb = pendingEcgGateConfirmationCallback;
                    pendingEcgGateConfirmationCallback = null;
                    pendingEcgGateConfirmationFlagName = null;
                    cb.run();
                }

            }, 3000);

        }, afterR22Ms);
    }

    private void runFullCombinedEcgAttempt() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run combined attempt");
            return;
        }

        line("");
        line("*** FULL COMBINED ECG ATTEMPT: R22 UNLOCK -> ECG GATE -> " +
                "REAL ECG START - STRAP MUST BE WORN ***");
        logRaw("FULL_COMBINED_ECG_ATTEMPT_BEGIN");

        sendGetAdvertisingName();

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];
            long delayMs = 80L * (i + 1);

            mainH.postDelayed(() -> sendR22Flag(flag, '1'), delayMs);
        }

        long afterR22Ms = 80L * (R22_FLAGS.length + 2);

        mainH.postDelayed(() -> {

            line("*** R22 unlock burst done - now setting ECG gate " +
                    "via the confirmed-working mechanism - ECG start " +
                    "now waits for the REAL application-layer echo " +
                    "confirming this exact flag, not just the BLE " +
                    "write ack (which fires too early to mean anything) ***");

            pendingEcgGateConfirmationFlagName = "enable_raw_data_w_ecg";
            pendingEcgGateConfirmationCallback =
                    this::fireRealEcgStartAfterGateConfirmed;

            sendR22Flag("enable_raw_data_w_ecg", '1');

            /*
             * Safety fallback only - if the real echo somehow never
             * arrives within 3s, proceed anyway rather than hang
             * forever, but log plainly that this was a timeout, not
             * a real confirmation.
             */
            mainH.postDelayed(() -> {

                if (pendingEcgGateConfirmationCallback != null) {

                    line("*** ECG gate echo TIMED OUT after 3s - " +
                            "proceeding anyway, but this was NOT a " +
                            "real confirmation ***");

                    logRaw("ECG_GATE_ECHO_TIMEOUT flag=enable_raw_data_w_ecg");

                    Runnable cb = pendingEcgGateConfirmationCallback;

                    pendingEcgGateConfirmationCallback = null;
                    pendingEcgGateConfirmationFlagName = null;

                    cb.run();
                }

            }, 3000);

        }, afterR22Ms);
    }

    /*
     * Split out so it can be reached either from the timed R22-burst
     * callback above (via the gate write's real ack) or, in future,
     * any other path that needs the same real ECG start sequence
     * once the gate is confirmed sent.
     *
     * UPDATED: now sends THREE toggles, not two. Decompiling the real
     * NOOP app (v11.6.0) found its actual ECG-start path calls three
     * named commands in this order - TOGGLE_REALTIME_FILTERED_ECG_CMD
     * (0x8B), TOGGLE_SAVE_RAW_ECG_CMD (0x7D), then
     * MAIN_CONTROL_ECG_DATA_GENERATION_CMD (0x7C) - verified by
     * disassembling the actual bytecode of each method
     * (Whoop5Ecg.toggleRealtimeFilteredEcgFrame/toggleSaveRawEcgFrame/
     * mainControlEcgDataGenerationFrame) and reading the literal
     * opcode constant each one loads, cross-checked against the two
     * opcodes we already knew were correct (0x8B and 0x7C both
     * decoded exactly right, giving real confidence in the same
     * method finding 0x7D for the one we didn't know). We already had
     * 0x7D in our own UI as an unrelated "RAW SAVE ON" button,
     * completely disconnected from any ECG sequence, this whole
     * session - every prior attempt sent 2 of these 3 real commands.
     */
    private void fireRealEcgStartAfterGateConfirmed() {

            /*
             * Real-time bond re-verification, mirroring the actual
             * NOOP app's ecgGatesAllow() gate found in its real
             * source (BLEManager.swift) - it explicitly re-checks
             * state.encryptedBond immediately before allowing ANY ECG
             * command through, rather than trusting a historical
             * connect-time check. We've never done this - only ever
             * checked bond state once, at connect. Doesn't hurt to
             * verify it's still genuinely bonded right now, in case
             * something silently degraded since connect.
             */
            if (gatt != null && gatt.getDevice() != null) {

                int currentBondState = gatt.getDevice().getBondState();

                line("*** PRE-ECG BOND CHECK (mirroring the real app's " +
                        "ecgGatesAllow gate): bondState=" +
                        currentBondState + " (10=NONE,11=BONDING,12=BONDED) " +
                        "***");

                logRaw("PRE_ECG_BOND_CHECK bondState=" + currentBondState);

                if (currentBondState != BluetoothDevice.BOND_BONDED) {

                    line("*** WARNING: bond state is NOT BONDED right now " +
                            "- proceeding anyway to see what happens, but " +
                            "this would be refused by the real app's own " +
                            "gate ***");

                    logRaw("PRE_ECG_BOND_CHECK_FAILED bondState=" +
                            currentBondState);
                }
            }

            realtimeEcgFragments.clear();
            ecgCommandResponsesThisAttempt.clear();
            realtimeEcgTotalBytes = 0;
            realtimeEcgBinaryFile = null;
            maxEcgOnSeen = false;

            labradorActive = true;
            recordingComplete = false;
            labradorPacketCount = 0;

            ecgEverRunThisConnection = true;

            line("");
            line("*** NOW SENDING REAL ECG START (3 toggles, including " +
                    "the previously-missing TOGGLE_SAVE_RAW_ECG=0x7D) - " +
                    "TOUCH THE CLASP NOW IF NOT ALREADY TOUCHING ***");

            sendWithCallback(0x8B, 1,
                    "TOGGLE_REALTIME_FILTERED_ECG_ON (combined attempt)", () ->
                    sendWithCallback(0x7D, 1,
                            "TOGGLE_SAVE_RAW_ECG_ON (combined attempt)", () ->
                    send(0x7C, 2,
                            "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                    "(combined attempt)")));

            ecgListenActive = true;
            ecgListenGeneration++;
            ecgListenStartedAtMs = System.currentTimeMillis();

            final int myGen = ecgListenGeneration;

            mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);
    }

    /*
     * ------------------------------------------------------------------
     * BANK-TO-FLASH TEST - directly tests NOOP's own current, still-
     * open leading hypothesis (issue #891b): "the 5/MG banks Labrador
     * ECG to flash rather than streaming it [live]... has anyone seen
     * an unrecognised record type in a 5/MG offload? Nobody can
     * answer that today."
     *
     * Everything up to now assumed real-time delivery via type=43. If
     * that hypothesis is right instead, the samples would never
     * appear there at all - they'd surface later, in the SAME
     * historical-offload mechanism already validated for R22 motion/
     * HR data, as a record shape we've never specifically looked for.
     *
     * Sequence: real ECG start (touch clasp), hold, real ECG stop,
     * then immediately the real historical pull - watching every
     * returned record for anything that doesn't match the familiar
     * R22 shape (flagged automatically by decodeR22HistoricalFields()/
     * flagIfUnrecognizedRecordShape() above).
     * ------------------------------------------------------------------
     */
    private void runBankToFlashTest() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run bank-to-flash test");
            return;
        }

        line("");
        line("*** BANK-TO-FLASH TEST: REAL ECG START -> HOLD -> STOP -> " +
                "REAL HISTORICAL PULL - STRAP MUST BE WORN, TOUCH CLASP " +
                "NOW ***");
        logRaw("BANK_TO_FLASH_TEST_BEGIN");

        realtimeEcgFragments.clear();
        ecgCommandResponsesThisAttempt.clear();
        realtimeEcgTotalBytes = 0;
        realtimeEcgBinaryFile = null;
        maxEcgOnSeen = false;

        labradorActive = true;
        recordingComplete = false;
        labradorPacketCount = 0;

        ecgEverRunThisConnection = true;

        sendWithCallback(0x8B, 1,
                "TOGGLE_REALTIME_FILTERED_ECG_ON (bank-to-flash test)", () ->
                sendWithCallback(0x7D, 1,
                        "TOGGLE_SAVE_RAW_ECG_ON (bank-to-flash test)", () ->
                send(0x7C, 2,
                        "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                "(bank-to-flash test)")));

        ecgListenActive = true;
        ecgListenGeneration++;
        ecgListenStartedAtMs = System.currentTimeMillis();

        final int myGen = ecgListenGeneration;

        mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);

        /*
         * 35s hold (matches the real app's Heart Screener window with
         * a small margin), then stop, then immediately pull - no
         * pause in between, since if generation genuinely writes to
         * flash mid-session, the freshest possible pull gives it the
         * best chance of showing up before anything else overwrites
         * or ages it out.
         */
        mainH.postDelayed(() -> {

            ecgListenActive = false;
            ecgListenGeneration++;

            line("");
            line("*** BANK-TO-FLASH TEST: stopping ECG, then pulling " +
                    "history immediately ***");
            logRaw("BANK_TO_FLASH_TEST_STOP_THEN_PULL");

            send(0x7C, 1,
                    "MAIN_CONTROL_ECG_DATA_GENERATION_STOP " +
                            "(bank-to-flash test)");

            reportEcgAttemptVerdict();

            mainH.postDelayed(this::startRealHistoricalPull, 1000);

        }, 35000);
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

        pullIdleCycles = 0;

        logRaw("HIST_BURST len=" + value.length +
                " raw=" + Protocol.hex(value));

        /*
         * Route by actual frame length rather than applying the R22
         * decoder to everything - it was previously being run against
         * the 88-byte waveform-candidate frames too and producing
         * garbage (mag=Infinity, hr=0), since those simply aren't the
         * R22 shape at all.
         */
        if (value.length == 124) {

            decodeR22HistoricalFields(value);

        } else if (value.length == 88) {

            waveform88SeenThisPull++;
            decodeWaveform88(value);

        } else if (value.length == 188) {

            decodeWaveform188(value);

        } else if (value.length == 1584) {

            /*
             * The "layout v16" flash-banked ECG record - named and
             * documented in NOOP's own upstream issue #1100 as the
             * real historical ECG storage record, distinct from
             * everything we've decoded so far (R22/waveform-88/
             * waveform-188 are all much shorter). That same issue
             * reports it coming back EMPTY on firmware 50.40.1.0
             * (matching this unit's firmware) even with the clasp
             * circuit properly closed - so an empty/all-zero result
             * here would match a real, already-documented negative,
             * not necessarily something we did wrong. Flagged
             * explicitly rather than falling into the generic
             * unknown-length bucket, specifically so it's never
             * missed if it does show up.
             */
            int nonZero = 0;
            for (byte b : value) {
                if (b != 0) {
                    nonZero++;
                }
            }

            line("*** V16 FLASH ECG RECORD (len=1584) SEEN - " +
                    nonZero + "/" + value.length + " non-zero bytes ***");

            logRaw("V16_FLASH_ECG_RECORD len=1584 nonZeroBytes=" +
                    nonZero + " raw=" + Protocol.hex(value));

        } else {

            line("*** UNKNOWN HISTORICAL RECORD LENGTH=" + value.length +
                    " - neither the known R22 (124), waveform-88 (88), " +
                    "nor waveform-188 (188) shape ***");

            logRaw("UNRECOGNIZED_RECORD_SHAPE_UNKNOWN_LENGTH len=" +
                    value.length + " raw=" + Protocol.hex(value));
        }

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

    /*
     * Confirmed live decode for the R22-unlocked 124-byte historical
     * record. judes.club's primary source names accelerometer x/y/z
     * as float32 LE at bytes 37/41/45 and heart rate at byte 14, for
     * their reference "112-byte variant" frame. Validated directly
     * against 8 real captured frames from this exact strap: those
     * offsets +8 (accel at 45/49/53, HR at byte 22) gave a combined
     * accelerometer magnitude of 0.98-1.00 (true gravity) on every
     * single frame, and a heart rate of a stable, plausible 64-65 bpm
     * - the uncorrected offsets gave nonsense (an astronomically
     * out-of-range x-axis, a flat constant "1" for HR). The +8 shift
     * is consistent with the difference between their 112-byte
     * reference frame and our confirmed 124-byte one.
     *
     * Deliberately does NOT throttle like the raw hex summary above -
     * these are compact, meaningful one-liners worth seeing every
     * frame, unlike a full hex dump.
     */
    /*
     * Self-contained float32 LE reader using only java.lang.Float -
     * deliberately not relying on Protocol.java for this, since no
     * float-reading method there has been confirmed to exist.
     */
    private float readFloatLE(byte[] v, int offset) {

        int bits = (v[offset] & 0xff)
                | ((v[offset + 1] & 0xff) << 8)
                | ((v[offset + 2] & 0xff) << 16)
                | ((v[offset + 3] & 0xff) << 24);

        return Float.intBitsToFloat(bits);
    }

    /*
     * Flags any historical record that doesn't match the familiar
     * R22 motion+HR shape, rather than silently treating everything
     * as the same known type. Directly motivated by NOOP's own
     * current leading hypothesis (issue #891b, still open as of this
     * session): the 5/MG may bank Labrador ECG samples to internal
     * flash rather than streaming them live via type=43, in which
     * case they'd surface here, in the historical offload, as a
     * record type we've never specifically looked for. A plausible
     * accel magnitude near 1.0g is a reasonable "looks like the
     * known shape" check; anything outside that band gets called
     * out explicitly instead of blending into the routine log.
     */
    private void flagIfUnrecognizedRecordShape(
            byte[] value, double accelMag, int heartRate) {

        boolean magPlausible = accelMag > 0.5 && accelMag < 2.0;
        boolean hrPlausible = heartRate >= 30 && heartRate <= 220;

        if (!magPlausible || !hrPlausible) {

            line("*** POSSIBLE UNRECOGNIZED RECORD - doesn't match " +
                    "known R22 shape (mag=" +
                    String.format("%.3f", accelMag) +
                    " hr=" + heartRate +
                    ") - worth manual review, per NOOP issue #891b's " +
                    "banked-to-flash hypothesis ***");

            logRaw("UNRECOGNIZED_RECORD_SHAPE len=" + value.length +
                    " mag=" + String.format("%.4f", accelMag) +
                    " hr=" + heartRate +
                    " raw=" + Protocol.hex(value));
        }
    }

    private void decodeR22HistoricalFields(byte[] value) {

        if (value.length < 54) {

            line("*** POSSIBLE UNRECOGNIZED RECORD - too short (" +
                    value.length + " bytes) for the known R22 shape " +
                    "(needs >=54) - worth manual review ***");

            logRaw("UNRECOGNIZED_RECORD_SHAPE_SHORT len=" + value.length +
                    " raw=" + Protocol.hex(value));

            return;
        }

        float accelX = readFloatLE(value, 45);
        float accelY = readFloatLE(value, 49);
        float accelZ = readFloatLE(value, 53);

        double mag = Math.sqrt(
                accelX * accelX + accelY * accelY + accelZ * accelZ);

        int heartRate = value[22] & 0xff;

        /*
         * Unidentified field at bytes 113-116, float32 LE - found by
         * scanning every unmapped byte across ~4900 real R22 frames
         * for ones that vary too smoothly to be noise. Confirmed
         * against real data: frame-to-frame change here averages 5x
         * smaller than the same values would show if shuffled
         * randomly (0.145 vs 0.735), which is real continuity, not a
         * float32 reinterpretation of unrelated bytes landing in a
         * plausible range by chance. Real range seen so far: -5.28 to
         * -1.02. No confirmed meaning yet - logged live here so it can
         * be watched against whatever you're doing while capturing.
         */
        Float field113 = null;

        if (value.length >= 117) {
            field113 = readFloatLE(value, 113);
            updateField113Display(field113);
        }

        line(String.format(
                "R22 DECODE: accel x=%.3f y=%.3f z=%.3f |v|=%.3f  " +
                        "HR=%d bpm  field113=%s",
                accelX, accelY, accelZ, mag, heartRate,
                field113 == null ? "n/a" : String.format("%.3f", field113)));

        logRaw(String.format(
                "R22_DECODE accel_x=%.4f accel_y=%.4f accel_z=%.4f " +
                        "mag=%.4f hr=%d field113=%s",
                accelX, accelY, accelZ, mag, heartRate,
                field113 == null ? "n/a" : String.format("%.4f", field113)));

        flagIfUnrecognizedRecordShape(value, mag, heartRate);
    }

    /*
     * ------------------------------------------------------------------
     * Dedicated decoder for the 88-byte historical record shape first
     * flagged this session as structurally distinct from R22 (same
     * envelope type=0x2F cmd=0x80, only the length and internal layout
     * differ). Boundaries below were derived directly against a real
     * captured frame using the declared-length field and the CRC32
     * trailer position, not assumed:
     *
     *   bytes  0- 7  frame header (AA 01 [declLen LE] 00 01 [crc16])
     *   byte   8     type  (0x2F)
     *   byte   9     seq
     *   byte  10     cmd   (0x80)
     *   byte  11     sub-record id - seen cycling 0x00-0x09
     *   bytes 12-16  5 bytes, undecoded (candidate: timestamp/counter)
     *   bytes 17-20  4 bytes, constant 8A 6A 47 01 on every frame
     *                captured so far (candidate: session/recording id)
     *   bytes 21-22  2 bytes, constant 02 00 on every frame captured
     *                so far (candidate: channel/stream id)
     *   bytes 23-26  4-byte little-endian counter - NOT monotonic with
     *                the sub-record id in the one capture seen so far
     *                (candidate: per-channel flash offset, unconfirmed)
     *   bytes 27-82  56 bytes = 28 x int16 little-endian values
     *                (candidate: raw ADC/waveform samples, unconfirmed)
     *   byte  83     1 trailing byte, undecoded
     *   bytes 84-87  CRC32 trailer - the standard frame trailer this
     *                whole protocol uses, same as everywhere else
     *
     * A parallel analysis pass floated "30 samples" for this frame,
     * which double-counts: it miscounted where the real CRC32 trailer
     * starts and folded 4 of those trailer bytes into what it called
     * payload, inflating 28 real samples into 30. The count here is
     * anchored to the same declared-length field this app already
     * uses everywhere else to find frame boundaries, so it should be
     * trusted over that "30" figure.
     * ------------------------------------------------------------------
     */
    private void decodeWaveform88(byte[] v) {

        if (v.length != 88) {
            line("*** decodeWaveform88 called with len=" + v.length +
                    " (expected 88) - skipping ***");
            return;
        }

        int subId = v[11] & 0xff;

        byte[] field5 = Arrays.copyOfRange(v, 12, 17);
        byte[] sessionTag = Arrays.copyOfRange(v, 17, 21);
        byte[] channelTag = Arrays.copyOfRange(v, 21, 23);

        long offsetCounter = (v[23] & 0xffL)
                | ((v[24] & 0xffL) << 8)
                | ((v[25] & 0xffL) << 16)
                | ((v[26] & 0xffL) << 24);

        int[] samples = new int[28];

        for (int i = 0; i < 28; i++) {

            int lo = v[27 + i * 2] & 0xff;
            int hi = v[28 + i * 2];              // signed on purpose

            samples[i] = (hi << 8) | lo;
        }

        int trailingByte = v[83] & 0xff;

        int min = samples[0];
        int max = samples[0];
        long sum = 0;

        for (int s : samples) {
            if (s < min) min = s;
            if (s > max) max = s;
            sum += s;
        }

        double mean = sum / 28.0;

        line(String.format(Locale.US,
                "WAVEFORM-88 subId=%d offset=%d min=%d max=%d pp=%d " +
                        "mean=%.1f trailByte=0x%02X",
                subId, offsetCounter, min, max, max - min, mean,
                trailingByte));

        StringBuilder sampleStr = new StringBuilder();

        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                sampleStr.append(',');
            }
            sampleStr.append(samples[i]);
        }

        logRaw("WAVEFORM88 subId=" + subId +
                " field5=" + Protocol.hex(field5) +
                " session=" + Protocol.hex(sessionTag) +
                " channel=" + Protocol.hex(channelTag) +
                " offset=" + offsetCounter +
                " trailByte=0x" + String.format("%02X", trailingByte) +
                " samples=" + sampleStr.toString());

        recordWaveformSample(subId, samples);
    }

    /*
     * ------------------------------------------------------------------
     * Dedicated decoder for the 188-byte historical record shape,
     * same envelope (type=0x2F cmd=0x80) as R22 and waveform-88, only
     * the length and internal layout differ again.
     *
     * These boundaries are no longer a single-sample guess - they're
     * derived from a per-byte variance scan across 81 genuinely
     * distinct real records collected over many sessions: every byte
     * from 12-25 stays low-cardinality (structural) across all 81,
     * while every byte from 26-175 is high-cardinality (real signal)
     * without exception. An earlier pass got this wrong twice - once
     * assuming samples started at byte 12 (based on the 88-byte
     * shape's layout, which doesn't transfer), then again assuming
     * byte 27-182/86 samples (based on a single record, which turned
     * out to include contaminated header bytes at the front). This
     * boundary is the first one checked against a large sample rather
     * than one or two records.
     *
     *   bytes  0- 7   frame header (AA 01 [declLen LE] 00 01 [crc16])
     *   byte   8      type  (0x2F)
     *   byte   9      seq   (shared BLE-layer counter, not type-specific)
     *   byte  10      cmd   (0x80)
     *   byte  11      sub-id - climbs across a pull, not bounded 0-9
     *   bytes 12-25   14 bytes, low-cardinality across all 81 distinct
     *                 records seen - structural/header, undecoded
     *                 beyond that
     *   bytes 26-175  150 bytes = 75 x int16 LE - THE REAL SAMPLES,
     *                 confirmed high-cardinality across every one of
     *                 the 81 distinct records
     *   bytes 176-178 3 bytes, real but small-range (0-15ish) values
     *                 that vary per record - clearly NOT samples (far
     *                 too narrow a range vs. the sample region), some
     *                 kind of separate counter/flag field, meaning
     *                 undecoded
     *   byte  179     constant 0 across all 81 records
     *   byte  180     variable but on its own distinct small scale -
     *                 undecoded
     *   bytes 181-183 constant 0 across all 81 records
     *   bytes 184-187 CRC32 trailer (confirmed high-cardinality, as a
     *                 real checksum over varying content should be)
     *
     * The candidate 3-way segment split from before still holds up
     * against this corrected boundary (25 samples each) - each third
     * keeps a consistent relative ordering and drifts smoothly across
     * records, still consistent with a multi-channel signal - but it
     * remains a candidate grouping, not a confirmed channel layout.
     * ------------------------------------------------------------------
     */
    private void decodeWaveform188(byte[] v) {

        if (v.length != 188) {
            line("*** decodeWaveform188 called with len=" + v.length +
                    " (expected 188) - skipping ***");
            return;
        }

        String rawHex = Protocol.hex(v);

        if (!seenWaveform188RawHex.add(rawHex)) {
            // exact duplicate retransmission - already recorded
            return;
        }

        int subId = v[11] & 0xff;

        int[] samples = new int[75];

        for (int i = 0; i < 75; i++) {

            int lo = v[26 + i * 2] & 0xff;
            int hi = v[27 + i * 2];              // signed on purpose

            samples[i] = (hi << 8) | lo;
        }

        byte[] smallField = Arrays.copyOfRange(v, 176, 179);
        int byte180 = v[180] & 0xff;

        updateByte180Display(byte180);

        int min = samples[0];
        int max = samples[0];
        long sum = 0;

        for (int s : samples) {
            if (s < min) min = s;
            if (s > max) max = s;
            sum += s;
        }

        double mean = sum / 75.0;

        // candidate 3-way split (25 each) - still a candidate grouping,
        // now checked against the corrected boundary
        int third = 25;
        double[] segMeans = new double[3];

        for (int seg = 0; seg < 3; seg++) {

            int start = seg * third;
            int end = (seg == 2) ? samples.length : start + third;

            long segSum = 0;
            for (int i = start; i < end; i++) {
                segSum += samples[i];
            }

            segMeans[seg] = segSum / (double) (end - start);
        }

        line(String.format(Locale.US,
                "WAVEFORM-188 subId=%d min=%d max=%d pp=%d mean=%.1f " +
                        "candidateSegMeans(3x25)=[%.1f, %.1f, %.1f] " +
                        "smallField=%s byte180=%d",
                subId, min, max, max - min, mean,
                segMeans[0], segMeans[1], segMeans[2],
                Protocol.hex(smallField), byte180));

        StringBuilder sampleStr = new StringBuilder();

        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                sampleStr.append(',');
            }
            sampleStr.append(samples[i]);
        }

        logRaw("WAVEFORM188 subId=" + subId +
                " smallField=" + Protocol.hex(smallField) +
                " byte180=" + byte180 +
                " samples=" + sampleStr.toString());

        waveform188Records.add(new Waveform188Record(subId, samples));
    }

    private void saveReconstructedWaveform188() {

        if (waveform188Records.isEmpty()) {

            line("(no waveform-188 records captured this pull - " +
                    "nothing to reconstruct)");

            return;
        }

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("ERROR: external files directory unavailable for " +
                    "waveform-188 reconstruction");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        java.io.File out = new java.io.File(
                dir, "reconstructed_waveform188_" + stamp + ".csv");

        try (java.io.FileWriter fw = new java.io.FileWriter(out)) {

            fw.write("recordIndex,subId,sampleIndexInRecord,flatIndex," +
                    "value\n");

            int flatIndex = 0;

            for (int r = 0; r < waveform188Records.size(); r++) {

                Waveform188Record rec = waveform188Records.get(r);

                for (int s = 0; s < rec.samples.length; s++) {

                    fw.write(r + "," + rec.subId + "," + s + "," +
                            flatIndex + "," + rec.samples[s] + "\n");

                    flatIndex++;
                }
            }

            line("*** RECONSTRUCTED WAVEFORM-188 SAVED: " +
                    waveform188Records.size() + " record(s), " +
                    flatIndex + " total samples ***");

            line(out.getAbsolutePath());

            logRaw("WAVEFORM188_RECONSTRUCTED records=" +
                    waveform188Records.size() +
                    " samples=" + flatIndex +
                    " path=" + out.getAbsolutePath());

        } catch (Exception e) {

            line("WAVEFORM188 RECONSTRUCTION SAVE ERROR: " + e);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Dedicated decoder for the type=0x31 cmd=0x02 status frame first
     * seen this session - arrives only AFTER the real historical burst
     * has finished, not interleaved with it, so it looks like a
     * trailing status/heartbeat rather than a mid-transfer flow
     * control cursor. 36 bytes total; byte 11 (within a fixed 21-byte
     * payload starting at byte 11) climbs slowly across the 5 samples
     * captured so far (15,17,20,22,25); bytes 15-16 also move but do
     * NOT progress monotonically (16056, 29163, 13107, 29491, 13107 -
     * oscillating/repeating, not a second counter); everything from
     * byte 17 onward has been a constant 15-byte tail in every sample
     * seen. No ASCII content anywhere in this frame, so it's not
     * something extractCursorFromDebugText() would ever match (that
     * looks for "Trim:" text inside type=0x32 frames specifically).
     * Only 5 real samples exist so far (one capture, cut short by a
     * manual stop) - not enough to say what byte 11 or the
     * oscillating field actually track. This just logs every field
     * cleanly so a longer capture has something to compare against.
     * ------------------------------------------------------------------
     */
    private void decodeStatusFrame31(byte[] v) {

        /*
         * Deliberately does NOT reset pullIdleCycles here anymore.
         * A larger capture showed these arrive roughly every ~4s
         * continuously throughout a pull, independent of whether new
         * history is still coming in - they kept firing even during
         * long stretches with no new HIST_BURST frames. Counting them
         * as "history still active" would mean the idle/drained
         * detector could never fire as long as these pings continue,
         * which defeats its purpose. Idle time is now tracked purely
         * against real HIST_BURST activity - see handleHistoricalBurstFrame.
         */

        if (v.length != 36) {

            line("*** type=0x31 cmd=0x02 frame with unexpected " +
                    "length=" + v.length + " (expected 36) - " +
                    "dumping raw only ***");

            logRaw("STATUS31_UNEXPECTED_LENGTH len=" + v.length +
                    " raw=" + Protocol.hex(v));

            return;
        }

        int counter = v[11] & 0xff;

        int oscillating = (v[15] & 0xff) | ((v[16] & 0xff) << 8);

        byte[] tail = Arrays.copyOfRange(v, 17, 32);

        line(String.format(Locale.US,
                "STATUS-31 counter=%d oscillating16=%d (0x%04X) " +
                        "tail=%s",
                counter, oscillating, oscillating, Protocol.hex(tail)));

        logRaw("STATUS31 counter=" + counter +
                " oscillating16=" + oscillating +
                " tail=" + Protocol.hex(tail) +
                " raw=" + Protocol.hex(v));
    }

    /*
     * ------------------------------------------------------------------
     * Waveform-88 block reconstruction - see field-declaration comment
     * above for the archiving rules.
     * ------------------------------------------------------------------
     */
    private void recordWaveformSample(int subId, int[] samples) {

        int[] existing = currentWaveformBlock.get(subId);

        if (existing != null && Arrays.equals(existing, samples)) {
            // exact duplicate retransmission - already have it
            return;
        }

        if (existing != null) {
            // subId reused with DIFFERENT content mid-block - a new
            // block has started; archive whatever we have first.
            archiveCurrentWaveformBlock();
        }

        currentWaveformBlock.put(subId, samples);

        if (currentWaveformBlock.size() == 10) {
            archiveCurrentWaveformBlock();
        }
    }

    private void archiveCurrentWaveformBlock() {

        if (currentWaveformBlock.isEmpty()) {
            return;
        }

        completedWaveformBlocks.add(new TreeMap<>(currentWaveformBlock));
        currentWaveformBlock.clear();
    }

    /*
     * Writes every completed (and any still-partial, now archived)
     * waveform block to a CSV, ordered by block then subId then
     * sample index - stitching the 28-sample sub-records into one
     * flat, continuous series per block, ready to plot.
     */
    private void saveReconstructedWaveform() {

        archiveCurrentWaveformBlock();

        if (completedWaveformBlocks.isEmpty()) {

            line("(no waveform-88 records captured this pull - " +
                    "nothing to reconstruct)");

            return;
        }

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("ERROR: external files directory unavailable for " +
                    "waveform reconstruction");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        waveformOutputFile = new java.io.File(
                dir, "reconstructed_waveform_" + stamp + ".csv");

        try (java.io.FileWriter fw =
                     new java.io.FileWriter(waveformOutputFile)) {

            fw.write("block,subId,sampleIndexInBlock,flatIndex,value\n");

            int flatIndex = 0;

            for (int b = 0; b < completedWaveformBlocks.size(); b++) {

                Map<Integer, int[]> block = completedWaveformBlocks.get(b);

                for (Map.Entry<Integer, int[]> e : block.entrySet()) {

                    int subId = e.getKey();
                    int[] samples = e.getValue();

                    for (int s = 0; s < samples.length; s++) {

                        fw.write(b + "," + subId + "," + s + "," +
                                flatIndex + "," + samples[s] + "\n");

                        flatIndex++;
                    }
                }
            }

            line("*** RECONSTRUCTED WAVEFORM SAVED: " +
                    completedWaveformBlocks.size() + " block(s), " +
                    flatIndex + " total samples ***");

            line(waveformOutputFile.getAbsolutePath());

            for (int b = 0; b < completedWaveformBlocks.size(); b++) {

                line("  block " + b + ": " +
                        completedWaveformBlocks.get(b).size() +
                        "/10 sub-records present");
            }

            logRaw("WAVEFORM_RECONSTRUCTED blocks=" +
                    completedWaveformBlocks.size() +
                    " samples=" + flatIndex +
                    " path=" + waveformOutputFile.getAbsolutePath());

        } catch (Exception e) {

            line("WAVEFORM RECONSTRUCTION SAVE ERROR: " + e);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Waveform-88 / ECG control-comparison summary - persisted via
     * SharedPreferences so runs done on different days/launches still
     * accumulate into one running tally. Called once a pull finishes,
     * however it finishes (full completion or manual stop).
     * ------------------------------------------------------------------
     */
    private void recordPullOutcomeAndSummarize() {

        SharedPreferences prefs =
                getSharedPreferences(
                        "labrador_ecg_control", MODE_PRIVATE);

        String totalKey = ecgRanBeforeCurrentPull
                ? "pulls_with_ecg_total"
                : "pulls_no_ecg_total";

        String sawKey = ecgRanBeforeCurrentPull
                ? "pulls_with_ecg_saw_waveform88"
                : "pulls_no_ecg_saw_waveform88";

        int total = prefs.getInt(totalKey, 0) + 1;
        int saw = prefs.getInt(sawKey, 0) +
                (waveform88SeenThisPull > 0 ? 1 : 0);

        prefs.edit()
                .putInt(totalKey, total)
                .putInt(sawKey, saw)
                .apply();

        int noEcgTotal = prefs.getInt("pulls_no_ecg_total", 0);
        int noEcgSaw = prefs.getInt("pulls_no_ecg_saw_waveform88", 0);
        int withEcgTotal = prefs.getInt("pulls_with_ecg_total", 0);
        int withEcgSaw = prefs.getInt("pulls_with_ecg_saw_waveform88", 0);

        line("");
        line("*** WAVEFORM-88 / ECG CONTROL SUMMARY " +
                "(persists across app launches) ***");

        line("this pull: ECG ran first = " + ecgRanBeforeCurrentPull +
                " | waveform-88 frames seen = " + waveform88SeenThisPull +
                " | history drained = " + historyDrainedThisPull);

        line("ALL-TIME  no-ECG-first pulls:   " + noEcgSaw + "/" +
                noEcgTotal + " saw waveform-88");

        line("ALL-TIME  ECG-first pulls:      " + withEcgSaw + "/" +
                withEcgTotal + " saw waveform-88");

        logRaw("WAVEFORM88_CONTROL_SUMMARY " +
                "this_pull_ecg_first=" + ecgRanBeforeCurrentPull +
                " this_pull_seen=" + waveform88SeenThisPull +
                " history_drained=" + historyDrainedThisPull +
                " all_no_ecg=" + noEcgSaw + "/" + noEcgTotal +
                " all_with_ecg=" + withEcgSaw + "/" + withEcgTotal);
    }

    private void startRealHistoricalPull() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot pull");
            return;
        }

        if (pullAckActive) {
            line("PULL ALREADY IN PROGRESS - ignoring duplicate " +
                    "start (tap STOP PULL ACK LOOP first if you " +
                    "want to restart)");
            return;
        }

        historicalFragments.clear();
        historicalTotalBytes = 0;
        historicalBinaryFile = null;
        lastKnownCursor = null;

        currentWaveformBlock.clear();
        completedWaveformBlocks.clear();
        waveformOutputFile = null;
        seenWaveform188RawHex.clear();
        waveform188Records.clear();
        pullIdleCycles = 0;
        historyDrainedThisPull = false;
        field113History.clear();
        byte180History.clear();

        ecgRanBeforeCurrentPull = ecgEverRunThisConnection;
        waveform88SeenThisPull = 0;

        line("");
        line("*** REAL HISTORICAL PULL: SET_CLOCK -> GET_CLOCK -> " +
                "GET_DATA_RANGE -> SEND_HISTORICAL_DATA (ECG ran " +
                "first this connection = " + ecgRanBeforeCurrentPull +
                ") ***");
        logRaw("REAL_PULL_BEGIN");

        sendRealSetClock();
        sendZeroArg(0x0B, "GET_CLOCK");
        sendZeroArg(0x22, "GET_DATA_RANGE");
        sendZeroArg(0x16, "SEND_HISTORICAL_DATA");

        pullAckActive = true;
        pullAckCounter = 0;
        pullGeneration++;

        final int myGeneration = pullGeneration;

        mainH.postDelayed(
                () -> runPullAckStep(myGeneration), 350);
    }

    private void stopPullAckLoop() {

        pullAckActive = false;
        pullGeneration++;

        line("*** PULL ACK LOOP STOPPED (manual - history NOT " +
                "confirmed drained) - " + historicalFragments.size() +
                " burst frames, " + historicalTotalBytes +
                " bytes captured ***");

        logRaw("PULL_ACK_STOPPED frames=" + historicalFragments.size() +
                " bytes=" + historicalTotalBytes);

        recordPullOutcomeAndSummarize();
        saveReconstructedWaveform();
        saveReconstructedWaveform188();
    }

    /*
     * generation guards against ANY stray duplicate callback ever
     * running concurrently with a newer (or stopped) pull, no
     * matter what causes the duplicate - a double-tap, a Doze/
     * battery-optimisation quirk, or anything else. Once
     * pullGeneration no longer matches what this specific chain
     * was stamped with, it silently stops - it cannot un-stop
     * itself or race with a fresher chain.
     *
     * Corrected per judes.club's primary-source writeup: the real
     * ack echoes the strap's own 8-byte progress cursor verbatim,
     * with a fixed b3=0x01 marker - not a blind local counter in a
     * 1-byte arg, which is what this sent before. Falls back to the
     * old counter-based send ONLY if no real cursor has been
     * captured yet (e.g. right at the very start, before any debug-
     * text status line has arrived), so the loop still does
     * something reasonable before real cursor data exists.
     */
    /*
     * Fixes a real bug: the generation guard in runPullAckStep() only
     * checked pullAckActive/pullGeneration at SCHEDULING time, not at
     * the moment a queued write actually reaches the front of the
     * shared BLE queue. If writes take longer than the 350ms gap
     * between acks (easy to happen while a large burst is also
     * arriving and being logged), a backlog of already-queued sends
     * builds up - and nothing stopped that backlog from draining out
     * one by one even after STOP was pressed, since enqueue()/
     * drainQueue() has no knowledge of pull-loop state at all.
     *
     * This re-checks the SAME guard condition again, right at actual
     * execution time inside the enqueued closure - if the pull was
     * stopped (or a newer pull started) while this was sitting in
     * the backlog, it's skipped rather than sent, and opDone() is
     * still called so the rest of the queue keeps draining normally.
     */
    private void sendPullAckGenerationChecked(
            int cmd,
            byte[] b3AndPayload,
            String name,
            int myGeneration) {

        if (gatt == null || cmdWrite == null) {
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            if (!pullAckActive || myGeneration != pullGeneration) {

                line("(skipping stale queued " + name +
                        " - pull was stopped/restarted before " +
                        "this reached the front of the queue)");

                opDone();
                return;
            }

            byte[] f = buildManualFrame(
                    0x23, thisSeq, cmd, b3AndPayload);

            logRaw("TX (manual) name=" + name +
                    " cmd=0x" + String.format("%02X", cmd) +
                    " seq=0x" + String.format("%02X",
                            thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX " + name + " (manual frame)");
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

    private void runPullAckStep(int myGeneration) {

        if (!pullAckActive || myGeneration != pullGeneration) {
            return;
        }

        if (lastKnownCursor != null) {

            byte[] b3AndCursor = new byte[9];
            b3AndCursor[0] = 0x01;
            System.arraycopy(lastKnownCursor, 0, b3AndCursor, 1, 8);

            sendPullAckGenerationChecked(0x17, b3AndCursor,
                    "CURSOR_ACK (real, echoing captured Trim value)",
                    myGeneration);

        } else {

            line("(no real cursor captured yet - falling back to " +
                    "counter-based ack for this cycle)");

            byte[] counterArg = { (byte) (pullAckCounter & 0xFF) };

            sendPullAckGenerationChecked(0x17, counterArg,
                    "FALLBACK_COUNTER_ACK", myGeneration);
        }

        pullAckCounter++;
        pullIdleCycles++;

        /*
         * No fixed cycle cap anymore - see the field-declaration
         * comment above for why. Idle stop is the real condition;
         * the safety limit is a runaway guard only.
         */
        if (pullIdleCycles >= IDLE_CYCLE_LIMIT) {

            pullAckActive = false;
            historyDrainedThisPull = true;

            line("*** HISTORY DRAINED - no new burst frames for " +
                    IDLE_CYCLE_LIMIT + " cycles (~" +
                    (IDLE_CYCLE_LIMIT * 350 / 1000) + "s), even if " +
                    "status-31 pings kept arriving in that window - " +
                    historicalFragments.size() + " burst frames, " +
                    historicalTotalBytes + " bytes captured ***");

            logRaw("HISTORY_DRAINED frames=" +
                    historicalFragments.size() +
                    " bytes=" + historicalTotalBytes);

            recordPullOutcomeAndSummarize();
            saveReconstructedWaveform();
            saveReconstructedWaveform188();

            return;
        }

        if (pullAckCounter > SAFETY_CYCLE_LIMIT) {

            pullAckActive = false;

            line("*** PULL ACK LOOP SAFETY-STOPPED at " +
                    SAFETY_CYCLE_LIMIT + " cycles (runaway guard, " +
                    "not a real limit - history NOT confirmed drained) " +
                    "- " + historicalFragments.size() +
                    " burst frames, " + historicalTotalBytes +
                    " bytes captured ***");

            logRaw("PULL_ACK_SAFETY_STOP frames=" +
                    historicalFragments.size() +
                    " bytes=" + historicalTotalBytes);

            recordPullOutcomeAndSummarize();
            saveReconstructedWaveform();
            saveReconstructedWaveform188();

            return;
        }

        mainH.postDelayed(
                () -> runPullAckStep(myGeneration), 350);
    }

    /*
     * ------------------------------------------------------------------
     * WHOOP 5/MG "R22" deep-data unlock - confirmed byte-exact from
     * judes.club's primary-source writeup ("Cracking the WHOOP 5.0
     * over Bluetooth"), the same source NOOP's own docs cite. Real
     * confirmed sequence: GET_HELLO, GET_ADVERTISING_NAME, then 15x
     * SET_CONFIG writes (one per flag). No cmd=0x73 step exists in
     * this primary source - an earlier guess at one has been removed.
     *
     * GET_HELLO, GET_ADVERTISING_NAME, and SET_CONFIG all use a FIXED
     * b3=0x01 marker byte (not length-based) - see buildManualFrame()
     * above. Our existing CLIENT_HELLO handshake already produces the
     * same 0x91 identity replies GET_HELLO would, so it isn't
     * duplicated here.
     *
     * SET_CONFIG (cmd=0x78) body: flag name as ASCII, NUL-padded to
     * 32 bytes, then a 1-byte ASCII value ('1'/'2'), then 7 zero
     * bytes - 40 bytes total, sent as b3(0x01) + that 40-byte body.
     *
     * CRITICAL: confirmed on-wrist gated - the strap must be
     * genuinely worn, not just BLE-connected, for the r22 stream to
     * flow at all.
     *
     * We have ~10 of the real 15 flag names (the primary source
     * lists these and says "a handful more" beyond them) - sent
     * with all we have rather than waiting for full certainty.
     * ------------------------------------------------------------------
     */

    private static final int R22_FLAG_NAME_FIELD_LEN = 32;

    private static final String[] R22_FLAGS = {
        "enable_r22_packets",
        "enable_r22_v2_packets",
        "enable_r22_v3_packets",
        "enable_r22_v5_packets",
        "enable_r22_v6_packets",
        "enable_r22_v8_packets",
        "make_hrfm_visible",
        "hr_ch_switching",
        "enable_passive_strap_fit_gen5",
        "enable_sig11_during_sleep",
    };

    /*
     * ------------------------------------------------------------------
     * Speculative ECG-specific flag names - none of these are confirmed
     * to exist. They're built by pattern-matching the naming convention
     * of the confirmed real R22_FLAGS above (enable_<feature>_packets,
     * make_<feature>_visible, <feature>_ch_switching, enable_sig<N>_
     * during_<state>), substituting ecg/afib/heart_screener wherever
     * r22/hrfm/hr appeared. Sent via the same real SET_CONFIG mechanism
     * (cmd=0x78) already confirmed to work for the real R22 flags, with
     * a GET_DEVICE_CONFIG_VALUE read-back after each so a genuine
     * difference (versus every other guess coming back identical/
     * refused) would actually be visible in the log.
     * ------------------------------------------------------------------
     */
    private static final String[] ECG_FLAG_GUESSES = {
        "enable_ecg_packets",
        "enable_ecg_v1_packets",
        "enable_ecg_v2_packets",
        "enable_ecg_raw_data",
        "enable_ecg_stream",
        "enable_realtime_ecg",
        "enable_ecg_recording",
        "make_ecg_visible",
        "ecg_ch_switching",
        "enable_heart_screener",
        "heart_screener_enable",
        "enable_afib_detection",
        "afib_detection_enable",
        "enable_lead_on_detection",
        "enable_clasp_contact",
        "enable_two_lead_ecg",
        "enable_sig12_during_ecg",
    };

    /*
     * ------------------------------------------------------------------
     * Sweeps the safe, non-destructive opcodes immediately neighboring
     * the known ECG cluster (123/124/125/139), on the hypothesis that
     * firmware 50.40.1.0 may have remapped or dropped these opcodes
     * from its dispatcher entirely rather than keeping them mapped and
     * gating the feature behind them - which would produce exactly our
     * symptom (zero COMMAND_RESPONSE, not a SUCCESS-then-silence) where
     * every other reported case in #891 gets a real SUCCESS ack.
     * Opcode remapping between firmware generations on this platform
     * is independently confirmed real (docs/PROTOCOL.md: SET_CLOCK/
     * GET_CLOCK/GET_HELLO sit at different numbers on MAVERICK vs the
     * 4.0). Deliberately excludes the confirmed-destructive opcodes
     * (25 FORCE_TRIM, 32 POWER_CYCLE_STRAP, 36/37/38 firmware, 45
     * ENTER_BLE_DFU, 99, 142/143/144) and everything already tested
     * (119/120/121/123/124/125/139).
     * ------------------------------------------------------------------
     */
    private static final int[] NEIGHBORING_OPCODES_TO_SWEEP = {
        122, 126, 127, 128, 129, 130, 131, 132, 133,
        134, 135, 136, 137, 138, 140, 141,
    };

    /*
     * ------------------------------------------------------------------
     * Extends the neighboring-opcode sweep into the high-140s/150s -
     * motivated directly by confirming cmd=145 is GET_HELLO's reply
     * (the CLIENT_HELLO ack we've seen since the start of this whole
     * investigation) and by the #891 thread's own docs/PROTOCOL.md
     * excerpt: this exact MAVERICK/5-MG firmware family remaps
     * SET_CLOCK->146, GET_CLOCK->147, GET_HELLO->145 - a confirmed,
     * real example of this firmware clustering remapped commands
     * right above where the first sweep stopped (141). If ECG moved
     * anywhere, this is a far more motivated place to look than
     * blind guessing.
     *
     * Genuinely less-charted territory than the first sweep - we only
     * know 142/143/144 are destructive, nothing confirms 148+ is
     * safe the way 122-141 mostly was. Kept to the gentle arg=1
     * convention throughout, same as every known-safe toggle, and
     * capped at 160 rather than sweeping further blind.
     * ------------------------------------------------------------------
     */
    private static final int[] HIGH_RANGE_OPCODES_TO_SWEEP = {
        148, 149, 150, 151, 152, 153, 154, 155,
        156, 157, 158, 159, 160,
    };

    private void sweepHighRangeOpcodes() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot sweep high-range opcodes");
            return;
        }

        line("");
        line("*** SWEEPING " + HIGH_RANGE_OPCODES_TO_SWEEP.length +
                " OPCODES IN THE 148-160 RANGE - motivated by confirmed " +
                "clock-family remapping to 145/146/147 on this exact " +
                "firmware family. Less-charted than the first sweep - " +
                "watching closely ***");
        logRaw("HIGH_RANGE_OPCODE_SWEEP_BEGIN count=" +
                HIGH_RANGE_OPCODES_TO_SWEEP.length);

        for (int i = 0; i < HIGH_RANGE_OPCODES_TO_SWEEP.length; i++) {

            int cmd = HIGH_RANGE_OPCODES_TO_SWEEP[i];
            long delayMs = 600L * i;

            mainH.postDelayed(() -> {

                line("--- probing cmd=" + cmd + " (0x" +
                        String.format("%02X", cmd) + ") ---");

                send(cmd, 1, "HIGH_RANGE_OPCODE_PROBE_" + cmd);

            }, delayMs);
        }

        long afterMs = 600L * HIGH_RANGE_OPCODES_TO_SWEEP.length + 500;

        mainH.postDelayed(() ->
                logRaw("HIGH_RANGE_OPCODE_SWEEP_COMPLETE"), afterMs);
    }

    private void sweepNeighboringOpcodes() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot sweep neighboring opcodes");
            return;
        }

        line("");
        line("*** SWEEPING " + NEIGHBORING_OPCODES_TO_SWEEP.length +
                " SAFE NEIGHBORING OPCODES (destructive ones excluded) " +
                "- watching for ANY COMMAND_RESPONSE, in case the real " +
                "ECG opcodes moved on this firmware ***");
        logRaw("NEIGHBORING_OPCODE_SWEEP_BEGIN count=" +
                NEIGHBORING_OPCODES_TO_SWEEP.length);

        for (int i = 0; i < NEIGHBORING_OPCODES_TO_SWEEP.length; i++) {

            int cmd = NEIGHBORING_OPCODES_TO_SWEEP[i];
            long delayMs = 500L * i;

            mainH.postDelayed(() -> {

                line("--- probing cmd=" + cmd + " (0x" +
                        String.format("%02X", cmd) + ") ---");

                send(cmd, 1, "NEIGHBORING_OPCODE_PROBE_" + cmd);

            }, delayMs);
        }

        long afterMs = 500L * NEIGHBORING_OPCODES_TO_SWEEP.length + 500;

        mainH.postDelayed(() ->
                logRaw("NEIGHBORING_OPCODE_SWEEP_COMPLETE"), afterMs);
    }

    private void sweepEcgFlagGuesses() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot sweep ECG flag guesses");
            return;
        }

        line("");
        line("*** SWEEPING " + ECG_FLAG_GUESSES.length + " SPECULATIVE " +
                "ECG FLAG NAMES via the CONFIRMED-WORKING SET_CONFIG " +
                "mechanism (cmd 0x78) - the original version of this " +
                "sweep used SET_DEVICE_CONFIG_VALUE (cmd 0x77/0x79), " +
                "which we later proved never replies to ANYTHING, even " +
                "confirmed-real keys, making every prior run of this " +
                "sweep uninformative regardless of the guesses' " +
                "validity. Watching channel 0003 for a real echo this " +
                "time ***");
        logRaw("ECG_FLAG_GUESS_SWEEP_BEGIN count=" + ECG_FLAG_GUESSES.length);

        for (int i = 0; i < ECG_FLAG_GUESSES.length; i++) {

            String flag = ECG_FLAG_GUESSES[i];
            long delayMs = 400L * i;

            mainH.postDelayed(() -> {

                line("--- guess: \"" + flag + "\" ---");
                sendR22Flag(flag, '1');

            }, delayMs);
        }

        long afterMs = 400L * ECG_FLAG_GUESSES.length + 500;

        mainH.postDelayed(() ->
                logRaw("ECG_FLAG_GUESS_SWEEP_COMPLETE"), afterMs);
    }

    private byte[] buildR22FlagBody(String flagName, char asciiValue) {

        byte[] nameBytes = flagName.getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);

        if (nameBytes.length > R22_FLAG_NAME_FIELD_LEN) {
            throw new IllegalArgumentException(
                    "flag name too long for 32-byte field: " + flagName);
        }

        byte[] body = new byte[40];

        System.arraycopy(nameBytes, 0, body, 0, nameBytes.length);
        /* remaining name-field bytes are already 0x00 */

        body[32] = (byte) asciiValue;
        /* body[33..39] are already 0x00 - the 7 trailing zero bytes */

        return body;
    }

    private void sendR22Flag(String flagName, char asciiValue) {

        sendR22Flag(flagName, asciiValue, null);
    }

    private void sendR22Flag(
            String flagName, char asciiValue, Runnable onWriteComplete) {

        byte[] body = buildR22FlagBody(flagName, asciiValue);

        byte[] b3AndBody = new byte[1 + body.length];
        b3AndBody[0] = 0x01;
        System.arraycopy(body, 0, b3AndBody, 1, body.length);

        sendManualCommand(0x78, b3AndBody,
                "SET_CONFIG flag=\"" + flagName + "\" value='" +
                        asciiValue + "'",
                onWriteComplete);
    }

    private void sendGetAdvertisingName() {
        sendManualCommand(0x8D, new byte[]{0x01}, "GET_ADVERTISING_NAME");
    }

    /*
     * ------------------------------------------------------------------
     * Tests whether enable_raw_data_w_ecg was sent through the wrong
     * command this whole time. It's been sent via SET_DEVICE_CONFIG_
     * VALUE (cmd 0x77) - a different, largely unresponsive command -
     * based on an assumption made before the real R22 flag mechanism
     * (SET_CONFIG, cmd 0x78) was ever confirmed. It shares the exact
     * naming convention of the 10 confirmed-real R22 flags
     * (enable_r22_packets, enable_passive_strap_fit_gen5, ...), all of
     * which get an immediate, confirmed echo reply through cmd 0x78 -
     * something 0x77 has never once produced for this key. This sends
     * it through the mechanism that actually works, at both '1' and
     * '2' (mirroring the confirmed start/restart split found for the
     * ECG generation command itself), watching specifically for the
     * same kind of echo the real flags get.
     * ------------------------------------------------------------------
     */
    private void testEcgGateViaRealFlagMechanism() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot test");
            return;
        }

        line("");
        line("*** TESTING enable_raw_data_w_ecg VIA THE REAL SET_CONFIG " +
                "MECHANISM (cmd 0x78), not SET_DEVICE_CONFIG_VALUE " +
                "(cmd 0x77) - watch channel 0003 for an echo ***");
        logRaw("ECG_GATE_VIA_REAL_FLAG_MECHANISM_BEGIN");

        sendR22Flag("enable_raw_data_w_ecg", '1');

        mainH.postDelayed(() ->
                sendR22Flag("enable_raw_data_w_ecg", '2'), 800);

        mainH.postDelayed(() ->
                logRaw("ECG_GATE_VIA_REAL_FLAG_MECHANISM_BOTH_SENT"), 1600);
    }

    /*
     * One-tap: GET_ADVERTISING_NAME, then the ~10 confirmed R22 flags,
     * ~80ms apart (matching the real app's timing), each write-with-
     * response. Not the full real 15-flag burst - the remaining
     * names weren't recoverable - but a substantially more complete
     * attempt than the single-flag version tried earlier.
     */
    private void sendR22UnlockPartial() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot send R22 unlock");
            return;
        }

        line("");
        line("*** R22 UNLOCK: GET_ADVERTISING_NAME -> " +
                R22_FLAGS.length + " of ~15 real flags - " +
                "STRAP MUST BE WORN ***");
        logRaw("R22_UNLOCK_BEGIN flags=" + R22_FLAGS.length);

        sendGetAdvertisingName();

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];
            long delayMs = 80L * (i + 1);

            mainH.postDelayed(
                    () -> sendR22Flag(flag, '1'), delayMs);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Real ECG start/stop - see field-declaration comment above for the
     * full confirmed background (NOOP PR #1727).
     * ------------------------------------------------------------------
     */

    private void prepareRealtimeEcgCaptureFile() {

        java.io.File dir = getExternalFilesDir(null);

        if (dir == null) {
            line("ERROR: external files directory unavailable");
            return;
        }

        long stamp = System.currentTimeMillis() / 1000L;

        realtimeEcgBinaryFile = new java.io.File(
                dir, "realtime_ecg_bin_" + stamp + ".bin");

        line("REALTIME ECG BINARY FILE:");
        line(realtimeEcgBinaryFile.getAbsolutePath());
    }

    private void saveRealtimeEcgFragment(byte[] data) {

        if (realtimeEcgBinaryFile == null) {
            prepareRealtimeEcgCaptureFile();
        }

        try (java.io.FileOutputStream fos =
                     new java.io.FileOutputStream(
                             realtimeEcgBinaryFile, true)) {

            fos.write(data);

        } catch (Exception e) {
            line("REALTIME ECG SAVE ERROR: " + e);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Real type=43 REALTIME_RAW_DATA decoder - layout confirmed via
     * NOOP's own upstream repo (PR #1765), verified there across 315
     * real records on a WHOOP MG (same hw revision, WS50_r00, as this
     * unit): 240 bytes total.
     *
     *   bytes  0- 7   frame header (byte 8 is the inner record's type)
     *   bytes  8-23   inner header/envelope (unused here)
     *   bytes 24-33   constant 5 x int16 LE sub-header - CONFIRMED NOT
     *                 waveform, do not treat these as signal
     *   bytes 34-235  101 x int16 LE samples - THE ACTUAL ECG WAVEFORM
     *   bytes 236-239 CRC32 trailer
     *
     * We have never actually received a type=43 frame this whole
     * session, so this decoder is unverified against our own real
     * data - it's wired in now so that the moment one arrives, it's
     * decoded properly instead of just hex-dumped.
     * ------------------------------------------------------------------
     */
    private void decodeRealtimeEcg240(byte[] v) {

        if (v.length != 240) {

            line("*** type=43 frame len=" + v.length +
                    " (expected 240 per upstream's confirmed layout) - " +
                    "structure below assumes 240, treat with caution ***");

            logRaw("REALTIME_ECG_UNEXPECTED_LENGTH len=" + v.length +
                    " raw=" + Protocol.hex(v));

            return;
        }

        int[] samples = new int[101];

        for (int i = 0; i < 101; i++) {

            int lo = v[34 + i * 2] & 0xff;
            int hi = v[35 + i * 2];              // signed on purpose

            samples[i] = (hi << 8) | lo;
        }

        int min = samples[0];
        int max = samples[0];
        long sum = 0;

        for (int s : samples) {
            if (s < min) min = s;
            if (s > max) max = s;
            sum += s;
        }

        double mean = sum / 101.0;

        line(String.format(Locale.US,
                "*** REAL ECG WAVEFORM DECODED: 101 samples  " +
                        "min=%d max=%d pp=%d mean=%.1f ***",
                min, max, max - min, mean));

        StringBuilder sampleStr = new StringBuilder();

        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                sampleStr.append(',');
            }
            sampleStr.append(samples[i]);
        }

        logRaw("REALTIME_ECG_DECODED samples=" + sampleStr.toString());
    }

    private void handleRealtimeEcgFrame(
            String uuid,
            byte[] value) {

        realtimeEcgFragments.add(value);
        realtimeEcgTotalBytes += value.length;
        saveRealtimeEcgFragment(value);

        logRaw("REALTIME_ECG len=" + value.length +
                " raw=" + Protocol.hex(value));

        line("*** REALTIME_RAW_DATA (type=43) #" +
                realtimeEcgFragments.size() +
                " ch=" + uuid +
                " len=" + value.length +
                " totalBytes=" + realtimeEcgTotalBytes + " ***");

        decodeRealtimeEcg240(value);
    }

    private void startRealEcg() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot start ECG");
            return;
        }

        realtimeEcgFragments.clear();
        ecgCommandResponsesThisAttempt.clear();
        realtimeEcgTotalBytes = 0;
        realtimeEcgBinaryFile = null;
        maxEcgOnSeen = false;

        ecgEverRunThisConnection = true;

        line("");
        line("*** REAL ECG START: TOGGLE_REALTIME_FILTERED_ECG(139)=1 -> " +
                "TOGGLE_SAVE_RAW_ECG(125)=1 -> " +
                "mainControlECGDataGeneration(124)=2, chained off " +
                "the real write ack (not a timer) ***");
        logRaw("REAL_ECG_START_BEGIN");

        sendWithCallback(0x8B, 1, "TOGGLE_REALTIME_FILTERED_ECG_ON", () ->
                sendWithCallback(0x7D, 1, "TOGGLE_SAVE_RAW_ECG_ON", () ->
                send(0x7C, 2, "MAIN_CONTROL_ECG_DATA_GENERATION_START")));

        ecgListenActive = true;
        ecgListenGeneration++;
        ecgListenStartedAtMs = System.currentTimeMillis();

        final int myGen = ecgListenGeneration;

        mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);
    }

    private void stopRealEcg() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot stop ECG");
            return;
        }

        ecgListenActive = false;
        ecgListenGeneration++;

        line("");
        line("*** REAL ECG STOP: mainControlECGDataGeneration(124)=1 " +
                "(arg=0 is refused on real hardware) ***");
        line("*** SUMMARY: MAX86176 Set ECG ON " +
                (maxEcgOnSeen ? "SEEN" : "NOT SEEN") +
                " - type=43 frames: " + realtimeEcgFragments.size() +
                " (" + realtimeEcgTotalBytes + " bytes) ***");
        logRaw("REAL_ECG_STOP maxEcgOnSeen=" + maxEcgOnSeen +
                " type43Frames=" + realtimeEcgFragments.size() +
                " type43Bytes=" + realtimeEcgTotalBytes);

        reportEcgAttemptVerdict();

        send(0x7C, 1, "MAIN_CONTROL_ECG_DATA_GENERATION_STOP");
    }

    /*
     * Purely diagnostic - confirms the app is still alive and
     * listening during a long silent window, so "nothing arrived"
     * and "the app/log stopped working" are never ambiguous. Uses
     * the same generation-guard pattern as the pull-ack loop.
     */
    private void runEcgListenHeartbeat(int myGen) {

        if (!ecgListenActive || myGen != ecgListenGeneration) {
            return;
        }

        long elapsedS = (System.currentTimeMillis() -
                ecgListenStartedAtMs) / 1000L;

        line("(still listening for type=43... " + elapsedS +
                "s since start, " + realtimeEcgFragments.size() +
                " frames so far, MAX86176 line " +
                (maxEcgOnSeen ? "seen" : "not seen yet") + ")");

        mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);
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
        line("*** EXPERIMENT BEGIN: 3x REAL ECG START, " +
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
        line("*** EXPERIMENT: firing REAL ECG START #" + n + " of 3 ***");

        logRaw("EXPERIMENT_START_FIRING n=" + n);

        /*
         * Corrected to the validated real start sequence (NOOP PR
         * #1727): FILTER ON, then a beat later, arg=2 - not the old
         * arg=1 send, which is now confirmed to just stop/no-op
         * generation rather than start it. Deliberately does NOT
         * clear realtimeEcgFragments between cycles, so repeated
         * starts accumulate into one capture - useful for testing
         * whether each cycle independently produces real data.
         */
        ecgEverRunThisConnection = true;

        send(0x8B, 1, "EXPERIMENT_FILTERED_ON_" + n);

        mainH.postDelayed(() ->
                send(0x7C, 2,
                        "EXPERIMENT_ECG_GENERATION_START_" + n),
                600);

        if (experimentStartsSent < 3) {

            mainH.postDelayed(
                    () -> fireNextExperimentStart(intervalMs),
                    intervalMs);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Manual frame builder - for commands where the byte right after
     * cmd (called "b3" in the primary judes.club writeup) is a FIXED
     * marker value, not an auto-computed payload length. Our existing
     * Protocol.labrador()/labradorBytes() helpers always compute that
     * byte as the given argument's length, which is correct for the
     * commands we validated earlier (GET/SET_DEVICE_CONFIG_VALUE,
     * GET_CLOCK, GET_DATA_RANGE, SEND_HISTORICAL_DATA) but wrong for
     * GET_HELLO/GET_ADVERTISING_NAME/SET_CONFIG/the 0x17 cursor ack,
     * which all use a fixed b3=0x01 regardless of what follows.
     *
     * CRC16-Modbus here is a direct port of the validated Swift
     * reference (poly 0x8005 reflected, init 0xFFFF, refin/refout,
     * xorout 0). CRC32 uses java.util.zip.CRC32, which is the
     * standard zlib/IEEE 802.3 CRC32 the same source confirms this
     * protocol's trailer uses.
     * ------------------------------------------------------------------
     */

    private int crc16Modbus(byte[] bytes) {

        int crc = 0xFFFF;

        for (byte bb : bytes) {

            crc ^= (bb & 0xFF);

            for (int i = 0; i < 8; i++) {

                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = crc >>> 1;
                }
            }
        }

        return crc & 0xFFFF;
    }

    private byte[] buildManualFrame(
            int type,
            int seq,
            int cmd,
            byte[] b3AndPayload) {

        int innerLen = 3 + b3AndPayload.length;

        byte[] inner = new byte[innerLen];
        inner[0] = (byte) type;
        inner[1] = (byte) seq;
        inner[2] = (byte) cmd;
        System.arraycopy(
                b3AndPayload, 0, inner, 3, b3AndPayload.length);

        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(inner);
        long crc32Val = crc32.getValue();

        int declLen = innerLen + 4;

        byte[] headerPre = new byte[6];
        headerPre[0] = (byte) 0xAA;
        headerPre[1] = 0x01;
        headerPre[2] = (byte) (declLen & 0xFF);
        headerPre[3] = (byte) ((declLen >> 8) & 0xFF);
        headerPre[4] = 0x00;
        headerPre[5] = 0x01;

        int crc16 = crc16Modbus(headerPre);

        byte[] frame = new byte[8 + innerLen + 4];
        System.arraycopy(headerPre, 0, frame, 0, 6);
        frame[6] = (byte) (crc16 & 0xFF);
        frame[7] = (byte) ((crc16 >> 8) & 0xFF);
        System.arraycopy(inner, 0, frame, 8, innerLen);
        frame[8 + innerLen]     = (byte) (crc32Val & 0xFF);
        frame[8 + innerLen + 1] = (byte) ((crc32Val >> 8) & 0xFF);
        frame[8 + innerLen + 2] = (byte) ((crc32Val >> 16) & 0xFF);
        frame[8 + innerLen + 3] = (byte) ((crc32Val >> 24) & 0xFF);

        return frame;
    }

    private void sendManualCommand(
            int cmd,
            byte[] b3AndPayload,
            String name) {

        sendManualCommand(cmd, b3AndPayload, name, null);
    }

    /*
     * Same as sendManualCommand(), but onWriteComplete fires from the
     * REAL GATT write-ack for this specific frame, not a fixed-delay
     * guess. Added after finding a real timing bug: the combined ECG
     * attempt used independently-scheduled postDelayed() calls for
     * "send the gate" and "start ECG", which assumed a fixed queue-
     * drain speed. A real capture showed the gate's actual write went
     * out only ~30ms before TOGGLE_LABRADOR_FILTERED_ON fired - nowhere
     * near the intended 800ms buffer - because the BLE queue was still
     * busy with earlier R22 flag sends and the two delays weren't
     * chained to each other, just to the same start time. Chaining
     * off the real ack removes that guesswork entirely.
     */
    private void sendManualCommand(
            int cmd,
            byte[] b3AndPayload,
            String name,
            Runnable onWriteComplete) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = buildManualFrame(
                    0x23, thisSeq, cmd, b3AndPayload);

            logRaw("TX (manual) name=" + name +
                    " cmd=0x" + String.format("%02X", cmd) +
                    " seq=0x" + String.format("%02X",
                            thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX " + name + " (manual frame)");
            line("TX CMD =0x" + String.format("%02X", cmd));
            line("TX SEQ =0x" + String.format("%02X",
                    thisSeq & 0xff));
            line("TX LEN =" + f.length);
            line("TX RAW =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            pendingWriteCallback = onWriteComplete;

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (" + name + ")");
                pendingWriteCallback = null;
                opDone();
            }
        });
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

        sendWithCallback(opcode, arg, name, null);
    }

    /*
     * Same as send(), but onWriteComplete fires from the REAL GATT
     * write-ack (onCharacteristicWrite()) for this specific frame, not
     * a timer - lets one command explicitly chain off another's
     * confirmed completion.
     */
    private void sendWithCallback(
            int opcode,
            int arg,
            String name,
            Runnable onWriteComplete) {

        if (opcode == 0x7C && arg == 1) {
            labradorFragments.clear();
            line("(cleared any stale 0007 fragments before this START)");
        }

        sendNamed(
                0x23,
                opcode,
                arg,
                name,
                onWriteComplete);
    }

    private void sendNamed(
            int type,
            int opcode,
            int arg,
            String name) {

        sendNamed(type, opcode, arg, name, null);
    }

    private void sendNamed(
            int type,
            int opcode,
            int arg,
            String name,
            Runnable onWriteComplete) {

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

            /*
             * Set right before the actual write call, not earlier -
             * this operation may sit queued for a while before its
             * turn comes up, and we only want the callback tied to
             * THIS specific write's real ack.
             */
            pendingWriteCallback = onWriteComplete;

            if (!gatt.writeCharacteristic(
                    cmdWrite)) {

                line("writeCharacteristic() " +
                        "rejected (" +
                        name +
                        ")");

                pendingWriteCallback = null;

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
     * Corrected SET_CLOCK - confirmed from NOOP's own protocol docs to
     * be an 8-byte payload ([seconds u32 LE][subseconds u32 LE]), not
     * the 4-byte epoch-only guess above. Per that same doc: "a wrong-
     * length payload is ack'd but not latched, leaving the RTC lost
     * and breaking history" - meaning every SET_CLOCK this session
     * before this fix likely silently failed to actually take effect
     * despite appearing to succeed.
     */
    private void sendRealSetClock() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;
        final long epochNow = System.currentTimeMillis() / 1000L;

        enqueue(() -> {

            byte[] body = new byte[8];

            body[0] = (byte) (epochNow & 0xFF);
            body[1] = (byte) ((epochNow >> 8) & 0xFF);
            body[2] = (byte) ((epochNow >> 16) & 0xFF);
            body[3] = (byte) ((epochNow >> 24) & 0xFF);
            /* body[4..7] = subseconds, left as 0 - we have no
               sub-second-accurate clock source worth encoding here */

            byte[] f = Protocol.labradorBytes(0x23, 0x0A, body, thisSeq);

            logRaw("TX SET_CLOCK (corrected 8-byte) epoch=" + epochNow +
                    " seq=0x" + String.format("%02X", thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX SET_CLOCK (corrected 8-byte)");
            line("TX EPOCH=" + epochNow +
                    " (" + new Date(epochNow * 1000L) + ")");
            line("TX LEN  =" + f.length);
            line("TX RAW  =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (SET_CLOCK)");
                opDone();
            }
        });
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
        queueRead(g, dis.getCharacteristic(systemIdChar));
        queueRead(g, dis.getCharacteristic(pnpIdChar));
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
        if (systemIdChar.equals(u)) return "System ID";
        if (pnpIdChar.equals(u)) return "PnP ID";

        return null;
    }

    /*
     * System ID and PnP ID are structured binary fields, not text -
     * decoding them as UTF-8 (like the other five DIS strings) would
     * just produce garbage. PnP ID especially is worth decoding
     * properly: Vendor ID Source(1) + Vendor ID(2 LE) +
     * Product ID(2 LE) + Product Version(2 LE), 7 bytes total - a
     * real candidate for the machine-readable "variant" NOOP's own
     * DIS-attestation check (issue #891) inspects.
     */
    private void logStructuredDeviceInfo(
            UUID uuid,
            String label,
            byte[] value) {

        line(label + ": (" + Protocol.hex(value) + ")");
        logRaw("DEVICE_INFO " + label + "_hex=" + Protocol.hex(value));

        if (pnpIdChar.equals(uuid) && value.length >= 7) {

            int vendorIdSource = value[0] & 0xff;
            int vendorId = (value[1] & 0xff) | ((value[2] & 0xff) << 8);
            int productId = (value[3] & 0xff) | ((value[4] & 0xff) << 8);
            int productVersion = (value[5] & 0xff) |
                    ((value[6] & 0xff) << 8);

            String detail = String.format(
                    "  vendorIdSource=0x%02X vendorId=0x%04X " +
                            "productId=0x%04X productVersion=0x%04X",
                    vendorIdSource, vendorId, productId, productVersion);

            line(detail);
            logRaw("DEVICE_INFO PnP_ID" + detail);
        }
    }

    private void handleCharacteristicRead(
            BluetoothGattCharacteristic c,
            byte[] value,
            int status) {

        String label = deviceInfoLabel(c.getUuid());

        if (label != null) {

            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {

                if (systemIdChar.equals(c.getUuid()) ||
                        pnpIdChar.equals(c.getUuid())) {

                    logStructuredDeviceInfo(c.getUuid(), label, value);

                } else {

                    String text = new String(
                            value,
                            java.nio.charset.StandardCharsets.UTF_8);

                    line(label + ": \"" + text + "\" (" +
                            Protocol.hex(value) + ")");

                    logRaw("DEVICE_INFO " + label + "=" + text);
                }

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
         * Always-visible live readout for the unidentified R22 byte
         * 113-116 field - separate from the scrolling log, which
         * moves too fast during a burst to actually watch a value
         * change in real time against something you're doing.
         */
        field113Display = new TextView(this);
        field113Display.setText("field113: --");
        field113Display.setTextSize(16);
        field113Display.setTextColor(0xFF7FDBFF);
        field113Display.setTypeface(null, android.graphics.Typeface.BOLD);
        field113Display.setPadding(0, 0, 0, 16);

        root.addView(
                field113Display,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        /*
         * Live readout for waveform-188's byte180 field - shown
         * alongside field113 since the two correlated (r=0.843) in a
         * first analysis pass. Watching both together during a
         * deliberate move/still test is the way to tell whether that
         * correlation is real or just shared time-drift.
         */
        byte180Display = new TextView(this);
        byte180Display.setText("byte180: --");
        byte180Display.setTextSize(16);
        byte180Display.setTextColor(0xFFFFB347);
        byte180Display.setTypeface(null, android.graphics.Typeface.BOLD);
        byte180Display.setPadding(0, 0, 0, 16);

        root.addView(
                byte180Display,
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
                        "ECG START (real)",
                        v -> {

                            labradorActive = true;
                            recordingComplete = false;
                            labradorPacketCount = 0;

                            startRealEcg();
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
                        "ECG STOP (real)",
                        v -> {

                            labradorActive = false;

                            stopRealEcg();
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
         * BANK-TO-FLASH TEST - now the single highest-priority test.
         * Directly derived from NOOP's own current, still-open
         * leading hypothesis (issue #891b): ECG may be banked to
         * flash rather than streamed live, meaning every prior
         * type=43-focused test this session may have been watching
         * the wrong channel entirely. Placed above even the combined
         * attempt, since this tests a fundamentally different theory
         * of where the data goes, not just a different way of asking
         * for it.
         */
        Button bankToFlashBtn = btn(
                "BANK-TO-FLASH TEST (ECG start/stop, then pull history) " +
                        "- WEAR + TOUCH CLASP",
                v -> runBankToFlashTest());
        controls.addView(bankToFlashBtn);

        /*
         * FULL COMBINED ECG ATTEMPT - chains R22 unlock + the ECG
         * gate + the confirmed real ECG start sequence together,
         * something never tried this whole session before it.
         */
        Button combinedBtn = btn(
                "FULL COMBINED ECG ATTEMPT (R22+gate+real start) - " +
                        "WEAR + TOUCH CLASP",
                v -> runFullCombinedEcgAttempt());
        controls.addView(combinedBtn);

        /*
         * ULTIMATE ECG ATTEMPT - four new, previously-untried ideas:
         * explicit SpO2/PPG-off mode reset, a real warm-up delay
         * before touch, periodic START re-kicks through the hold,
         * and a separate probe of an undocumented argument value.
         */
        Button ultimateBtn = btn(
                "ULTIMATE ECG ATTEMPT (SpO2-off + warmup + re-kick) - " +
                        "WEAR, WAIT FOR TOUCH PROMPT",
                v -> runUltimateEcgAttempt());
        controls.addView(ultimateBtn);

        Button probeArgBtn = btn(
                "PROBE UNDOCUMENTED cmd=0x7C arg=3",
                v -> probeUndocumentedEcgArg());
        controls.addView(probeArgBtn);

        Button suggestedSeqBtn = btn(
                "TEST SUGGESTED SEQUENCE (flags->probe->wait->1 start) - " +
                        "WEAR+TOUCH",
                v -> runSuggestedSequenceTest());
        controls.addView(suggestedSeqBtn);

        Button wristValueTestBtn = btn(
                "TEST BOTH SELECT_WRIST VALUES (0 vs 1)",
                v -> testBothWristValues());
        controls.addView(wristValueTestBtn);

        Button neighboringOpcodeSweepBtn = btn(
                "SWEEP NEIGHBORING OPCODES (maybe ECG moved on this fw)",
                v -> sweepNeighboringOpcodes());
        controls.addView(neighboringOpcodeSweepBtn);

        Button highRangeOpcodeSweepBtn = btn(
                "SWEEP HIGH-RANGE OPCODES 148-160 (confirmed remap zone)",
                v -> sweepHighRangeOpcodes());
        controls.addView(highRangeOpcodeSweepBtn);

        /*
         * R22 unlock - still useful on its own for historical/motion
         * data specifically, confirmed three independent ways in
         * NOOP's own docs.
         */
        Button r22Btn = btn(
                "SEND R22 UNLOCK (10 flags, corrected frames - " +
                        "STRAP MUST BE WORN)",
                v -> sendR22UnlockPartial());
        controls.addView(r22Btn);

        /*
         * Real historical pull moved directly below R22 UNLOCK -
         * these two are now the actual recommended test sequence
         * (unlock, then pull), so keeping them adjacent means
         * reaching the second one never needs any scrolling.
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
         * ECG gate controls - earlier, less fruitful experimentation
         * than the R22/historical-pull pair above, so no longer
         * given top billing, but kept available.
         */
        Button ecgGateStartBtn = btn(
                "ENABLE ECG GATE + START",
                v -> enableEcgGateThenStart());
        controls.addView(ecgGateStartBtn);

        Button ecgGateSetGetBtn = btn(
                "SET ECG GATE (confirmed-working mechanism)",
                v -> sendR22Flag("enable_raw_data_w_ecg", '1'));
        controls.addView(ecgGateSetGetBtn);

        Button ecgGateValueTestBtn = btn(
                "TEST ECG GATE VALUE: raw 0x01 vs ASCII '1'",
                v -> testEcgGateValueConvention());
        controls.addView(ecgGateValueTestBtn);

        Button ecgFlagGuessSweepBtn = btn(
                "SWEEP SPECULATIVE ECG FLAG NAMES (unconfirmed guesses)",
                v -> sweepEcgFlagGuesses());
        controls.addView(ecgFlagGuessSweepBtn);

        Button ecgGateViaRealMechBtn = btn(
                "TEST enable_raw_data_w_ecg VIA REAL FLAG MECHANISM (0x78)",
                v -> testEcgGateViaRealFlagMechanism());
        controls.addView(ecgGateViaRealMechBtn);

        EditText configKeyInput = new EditText(this);
        configKeyInput.setHint("device config key, e.g. enable_raw_data_w_ecg");
        configKeyInput.setText("enable_raw_data_w_ecg");
        configKeyInput.setSingleLine(true);
        configKeyInput.setTextColor(0xFFFFFFFF);
        configKeyInput.setHintTextColor(0xFF888888);
        controls.addView(configKeyInput);

        Button getConfigValueBtn = btn(
                "GET CONFIG VALUE (key above)",
                v -> {
                    String key = configKeyInput.getText()
                            .toString().trim();
                    if (key.isEmpty()) {
                        line("enter a key first");
                        return;
                    }
                    getDeviceConfigValue(key);
                });
        controls.addView(getConfigValueBtn);

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
                        3));

        /*
         * ------------------------------------------------------------
         * Log section: label, CLEAR/SAVE/COPY row, then the log
         * itself. Weight rebalanced from 4 down to 2 (controls raised
         * from 1 to 3, above) - with ~18 buttons/inputs now
         * accumulated in the controls panel over this session, the
         * old 1:4 ratio rendered it too small to reach buttons like
         * REAL HISTORICAL PULL without an easy-to-miss scroll nested
         * inside it, which read as "buttons not visible". The log
         * remains reachable in full via SAVE LOG/COPY LOG regardless
         * of its on-screen height.
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
                        2));

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
            logRaw("SCAN_STOP");
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
        logRaw("SCAN_START");
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

            logRaw("SCAN_FOUND name=" + d.getName() +
                    " addr=" + d.getAddress() +
                    " rssi=" + r.getRssi() +
                    " bondStateAtScan=" + d.getBondState());

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

                    logRaw("ALREADY_BONDED_CONNECTING addr=" +
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

                    logRaw("NOT_BONDED_REQUESTING_BOND addr=" +
                            d.getAddress());

                    updateStatus("● BONDING " + d.getName());

                    pendingDevice = d;

                    boolean started =
                            d.createBond();

                    logRaw("CREATE_BOND_CALLED result=" + started);

                    if (!started) {

                        line(
                                "createBond() " +
                                "returned false");

                        logRaw("CREATE_BOND_RETURNED_FALSE");

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

    /*
     * Live readout for the unidentified R22 byte 113-116 field.
     * Keeps a short rolling history (last 20 values) purely to show
     * a trend arrow - up/down/flat compared to ~20 frames ago - since
     * a single instantaneous number doesn't show whether it's
     * drifting with whatever you're doing. No meaning is assumed
     * beyond "this is the raw decoded value and its recent direction".
     */
    private void updateField113Display(float value) {

        field113History.addLast(value);

        while (field113History.size() > 20) {
            field113History.removeFirst();
        }

        float oldest = field113History.peekFirst();
        float delta = value - oldest;

        String trend;

        if (Math.abs(delta) < 0.05f) {
            trend = "flat";
        } else if (delta > 0) {
            trend = "up";
        } else {
            trend = "down";
        }

        String text = String.format(Locale.US,
                "field113: %.3f  (%s over last %d)",
                value, trend, field113History.size());

        runOnUiThread(() -> {
            if (field113Display != null) {
                field113Display.setText(text);
            }
        });
    }

    /*
     * Live readout for waveform-188's byte180 - same rolling-trend
     * approach as field113. History window is smaller (10 vs 20)
     * since waveform-188 records arrive far less often per pull than
     * R22 frames do.
     */
    private void updateByte180Display(int value) {

        byte180History.addLast(value);

        while (byte180History.size() > 10) {
            byte180History.removeFirst();
        }

        int oldest = byte180History.peekFirst();
        int delta = value - oldest;

        String trend;

        if (Math.abs(delta) < 3) {
            trend = "flat";
        } else if (delta > 0) {
            trend = "up";
        } else {
            trend = "down";
        }

        String text = String.format(Locale.US,
                "byte180: %d  (%s over last %d)",
                value, trend, byte180History.size());

        runOnUiThread(() -> {
            if (byte180Display != null) {
                byte180Display.setText(text);
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
