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

    /*
     * New, polished ECG screen - built as an overlay on top of the
     * existing debug UI (root), toggled by a single button, rather
     * than restructuring the app's existing Activity/BLE architecture.
     * Nothing about the proven connection/send logic changes; this is
     * a presentation layer wired directly to it.
     */
    private FrameLayout ecgScreenContainer;
    private EcgWaveformView ecgWaveformView;
    private TextView ecgStatusText;
    private TextView ecgHrText;
    private TextView ecgElapsedText;
    private Button ecgStartStopButton;
    private boolean ecgScreenActive = false;
    private boolean ecgSessionRunning = false;
    private long ecgSessionStartMs = 0L;
    private final java.util.List<Integer> ecgLiveBeatIntervalsMs =
            new java.util.ArrayList<>();
    private int ecgLastPeakSampleIndex = -1;
    private int ecgSampleCounter = 0;
    private final Handler ecgUiHandler = new Handler(Looper.getMainLooper());
    private Runnable ecgElapsedTicker;
    private TextView statusBar;
    private TextView field113Display;
    private final ArrayDeque<Float> field113History = new ArrayDeque<>();
    private TextView byte180Display;
    private final ArrayDeque<Integer> byte180History = new ArrayDeque<>();

    /*
     * Always-visible readout for the most recent GET_FF_VALUE_REPLY -
     * same pattern as field113Display/byte180Display, for the same
     * reason: during a live test (like the staged R22 disable probe)
     * the log scrolls too fast to reliably spot one specific line
     * before deciding whether to proceed. This makes that decision
     * possible by just glancing at a fixed spot on screen instead of
     * hunting through a fast-scrolling log.
     */
    private TextView lastFfValueDisplay;

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
     * Generic Attribute Service / Service Changed - never subscribed
     * before now. Standard BLE mechanism for the peripheral to
     * announce that its own GATT database changed. If contact/wear
     * state ever triggers any structural change in what the strap
     * exposes, this is the one thing that would announce it, and
     * we've been deaf to it the whole investigation.
     */
    private final UUID gattService =
            UUID.fromString("00001801-0000-1000-8000-00805f9b34fb");

    private final UUID serviceChangedChar =
            UUID.fromString("00002A05-0000-1000-8000-00805f9b34fb");

    /*
     * Battery Service / Battery Level - we've subscribed to its
     * NOTIFY before, but never explicitly READ it. Low probability
     * of reacting to touch, but free to check and completes the
     * picture of every standard characteristic actually watched.
     */
    private final UUID battService =
            UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb");

    private final UUID battLevelChar =
            UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb");

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
     * Continuous RSSI monitoring flag - true for the whole life of a
     * connection, false once disconnected, so the polling loop in
     * onReadRemoteRssi() knows when to stop rescheduling itself.
     */
    private boolean rssiMonitoringActive = false;

    /*
     * Guards against Android's BLE stack firing
     * onConnectionStateChange(CONNECTED) twice in a row for the same
     * connection - a real, confirmed bug (2 of 3 real logs checked
     * showed it, ~10ms apart each time). Reset on every disconnect.
     */
    private boolean alreadyHandledThisConnection = false;

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

    /*
     * Tallies hist_version byte values seen among historical records
     * that don't match any of our known lengths (124/88/188/1584) -
     * built to surface R24/R25/R26/PIP-R26 if they're already
     * arriving in pulls and being silently lumped into "unknown
     * length" noise. Reset at the start of each pull, reported at the
     * end.
     */
    private final java.util.Map<Integer, Integer>
            unknownRecordHistVersionTally = new java.util.HashMap<>();
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
     * Tracks the most recent STATUS31 frame's own fields, separately
     * from lastKnownCursor (which is sourced from parsed console
     * text, not this frame). Lets us log both side by side whenever
     * a CURSOR_ACK fires, and lets a dedicated test build the ACK
     * arg directly from THIS frame's fields instead - testing the
     * hypothesis (from external analysis of a real capture) that the
     * historical-transfer cursor may need to echo STATUS31's own
     * counter/oscillating/tail values, not whatever source currently
     * feeds lastKnownCursor.
     */
    private Integer lastStatus31Counter = null;
    private Integer lastStatus31Oscillating = null;
    private byte[] lastStatus31Tail = null;

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

                /*
                 * CORRECTED: a real, reproducible bug caught by
                 * cross-checking a real captured log - Android's own
                 * BLE stack fires onConnectionStateChange(CONNECTED)
                 * TWICE in a row (~10ms apart) on some connection
                 * attempts, confirmed in 2 of 3 real logs checked.
                 * Without this guard, discoverServices() (and
                 * everything downstream of it - CLIENT_HELLO, R22,
                 * every subscription) would fire twice per actual
                 * connection, risking duplicate sends and general
                 * protocol confusion nothing in this investigation
                 * has ever specifically accounted for.
                 */
                if (alreadyHandledThisConnection) {

                    line("(duplicate GATT_CONNECTED callback - " +
                            "ignoring, already handled)");
                    logRaw("GATT_CONNECTED_DUPLICATE_IGNORED");

                    return;
                }

                alreadyHandledThisConnection = true;

                line("GATT CONNECTED");
                logRaw("GATT_CONNECTED");
                updateStatus("● CONNECTED");
                g.discoverServices();

            } else if (state ==
                    BluetoothProfile.STATE_DISCONNECTED) {

                line("GATT DISCONNECTED");
                logRaw("GATT_DISCONNECTED");
                updateStatus("○ DISCONNECTED");

                rssiMonitoringActive = false;
                alreadyHandledThisConnection = false;

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

            rssiMonitoringActive = true;
            g.readRemoteRssi();
            schedulePeriodicBatteryRead(g);

            /*
             * Request the 2M PHY explicitly. Real finding from NOOP's
             * own upstream (PR #537): the app never called
             * setPreferredPhy() at all, so every connection ran on
             * the older 1M PHY by default despite 2M (roughly double
             * the symbol rate) being available since Bluetooth 5.
             * Checked our own code - same gap, confirmed. This is
             * about throughput, not acknowledgment - a slower PHY
             * would still work, just more slowly - so it's unlikely
             * to explain the ECG silence directly, but it's a real,
             * low-risk improvement worth having regardless,
             * especially given the v16 timing hypothesis where a
             * faster offload burst could matter.
             */
            g.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED);

            dumpAllServices(g);

            mainH.postDelayed(() -> {
                if (gatt != null && rssiMonitoringActive) {
                    schedulePeriodicGattReEnum(gatt);
                }
            }, 30000);

            subscribeHeartRateIfPresent(g);
            subscribeServiceChangedIfPresent(g);
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

            /*
             * ORDER FIXED - per a real community finding (b-nnett/
             * goose issue #60): the confirmed-working order is
             * CLIENT_HELLO first, THEN subscribe to notifications.
             * We had this backwards (subscribing to all four
             * characteristics before ever sending the hello) since
             * the very start of this investigation. Unlikely to be
             * the whole explanation for our silence given we do get
             * real responses for plenty of other opcodes already -
             * but it's a genuine correctness issue worth fixing
             * regardless, and matches the confirmed real order
             * rather than something we assumed.
             */
            if (cmdWrite != null) {

                enqueue(() -> {

                    line("TX CLIENT_HELLO " +
                            "(confirmed write) - sent BEFORE " +
                            "subscribing, per #60's confirmed order");

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
        }

        @Override
        public void onReadRemoteRssi(
                BluetoothGatt g,
                int rssi,
                int status) {

            /*
             * Continuous RSSI monitoring - a physically distinct
             * channel from everything else we've watched all
             * investigation (frame content). Body tissue near the
             * antenna/clasp can measurably affect RF coupling, so
             * this is checked as its own dimension, logged
             * continuously so it can be correlated against the
             * MARK_TOUCH_START/END timestamps independent of
             * whatever the strap chooses to send us.
             */
            logRaw("RSSI value=" + rssi + " status=" + status);

            if (rssiMonitoringActive) {

                mainH.postDelayed(() -> {
                    if (gatt != null && rssiMonitoringActive) {
                        gatt.readRemoteRssi();
                    }
                }, 1000);
            }
        }

        @Override
        public void onMtuChanged(
                BluetoothGatt g,
                int mtu,
                int status) {

            /*
             * Never logged before this point despite clearly being
             * negotiated already (we've reliably seen 244-byte
             * notification payloads, which can't fit in the default
             * 23-byte MTU) - most likely peer-initiated, since we've
             * never called requestMtu() ourselves. Added purely for
             * visibility; not expected to be touch-differential since
             * this normally negotiates once, early in the connection.
             */
            line("MTU changed to " + mtu + " status=" + status);
            logRaw("MTU_CHANGED mtu=" + mtu + " status=" + status);
        }

        @Override
        public void onPhyUpdate(
                BluetoothGatt g,
                int txPhy,
                int rxPhy,
                int status) {

            /*
             * Passive - we never request a PHY change ourselves, this
             * only fires if something (peer-initiated or a stack
             * default) changes it. A subsystem powering on internally
             * and needing more throughput is exactly the kind of
             * thing that could trigger this, and it's a channel
             * nothing else we've built could have caught.
             */
            line("*** PHY UPDATE: txPhy=" + txPhy + " rxPhy=" + rxPhy +
                    " status=" + status + " ***");
            logRaw("PHY_UPDATE txPhy=" + txPhy + " rxPhy=" + rxPhy +
                    " status=" + status);
        }

        @Override
        public void onPhyRead(
                BluetoothGatt g,
                int txPhy,
                int rxPhy,
                int status) {

            logRaw("PHY_READ txPhy=" + txPhy + " rxPhy=" + rxPhy +
                    " status=" + status);
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
                    envCmd >= 115 && envCmd <= 118) {
                handleKeyWalkReply(envCmd, value);
            } else if (envType == 0x24 &&
                    (envCmd == 119 || envCmd == 121)) {

                /*
                 * CORRECTED - previously just said "see ASCII/hex
                 * above", far less rigorous than the GET_FF_VALUE
                 * decoder despite being the SAME structure (per
                 * #890: [0x01] + 32-byte NUL-padded key + value) and
                 * being the exact namespace enable_raw_data_w_ecg
                 * itself lives in. Now applies the same result-code
                 * check (the #2193 bug pattern - a FAILURE reply can
                 * echo the key with zero padding, indistinguishable
                 * from a real empty value if the result code is
                 * never checked) and multi-byte value reading (the
                 * #907 bug pattern - values are not always single-
                 * byte) that GET_FF_VALUE already has.
                 */
                int dcResultCode = value.length > 12 ?
                        (value[12] & 0xff) : -1;
                boolean dcResultIsSuccess = dcResultCode == 1;

                String dcKeyEchoed = "";
                String dcValueStr = "";

                if (dcResultIsSuccess && value.length >= 13 + 1 + 32 &&
                        value[13] == 0x01) {

                    StringBuilder kb = new StringBuilder();

                    for (int i = 0; i < 32; i++) {
                        int b = value[14 + i] & 0xff;
                        if (b == 0) break;
                        kb.append((char) b);
                    }

                    dcKeyEchoed = kb.toString();

                    StringBuilder vb = new StringBuilder();

                    for (int i = 0; i < 32 &&
                            14 + 32 + i < value.length; i++) {
                        int b = value[14 + 32 + i] & 0xff;
                        if (b == 0) break;
                        vb.append((char) b);
                    }

                    dcValueStr = vb.toString();

                    line("*** GET_DEVICE_CONFIG_VALUE REPLY (cmd=" +
                            envCmd + "): key=\"" + dcKeyEchoed +
                            "\" storedValue=\"" + dcValueStr +
                            "\" - REAL STORED STATE ***");

                    logRaw("DEVICE_CONFIG_VALUE_REPLY cmd=" + envCmd +
                            " key=" + dcKeyEchoed +
                            " storedValue=" + dcValueStr +
                            " raw=" + Protocol.hex(value));

                } else if (!dcResultIsSuccess &&
                        value.length >= 13 + 1 + 32 && value[13] == 0x01) {

                    StringBuilder kb = new StringBuilder();

                    for (int i = 0; i < 32; i++) {
                        int b = value[14 + i] & 0xff;
                        if (b == 0) break;
                        kb.append((char) b);
                    }

                    dcKeyEchoed = kb.toString();

                    line("*** GET_DEVICE_CONFIG_VALUE REPLY (cmd=" +
                            envCmd + "): key=\"" + dcKeyEchoed +
                            "\" resultCode=" + dcResultCode + " - NOT " +
                            "SUCCESS, this is a REFUSED/FAILED read, " +
                            "NOT a real stored value ***");

                    logRaw("DEVICE_CONFIG_VALUE_REFUSED cmd=" + envCmd +
                            " key=" + dcKeyEchoed +
                            " resultCode=" + dcResultCode +
                            " raw=" + Protocol.hex(value));

                } else {

                    line("*** DEVICE_CONFIG_VALUE REPLY (cmd=" +
                            envCmd + ") resultCode=" + dcResultCode +
                            " - unrecognized shape, see hex above ***");

                    logRaw("DEVICE_CONFIG_VALUE_REPLY cmd=" + envCmd +
                            " resultCode=" + dcResultCode +
                            " raw=" + Protocol.hex(value));
                }

            } else if (envType == 0x24 && envCmd == 128) {

                /*
                 * GET_FF_VALUE reply - the real, authoritative
                 * read-back for the SET_FF_VALUE/SET_CONFIG (120)
                 * namespace. Payload after the header is expected to
                 * mirror the SET_FF_VALUE echo shape: [0x01][32-byte
                 * NUL-padded key][value]. Decoded explicitly so
                 * the actual stored value is visible directly, not
                 * left as raw hex to parse by hand.
                 *
                 * CORRECTED: values are NOT always single-byte - a
                 * real bug found in #907 ("max_collection_backlog
                 * reads '0.0'" - a 3-character value truncated by a
                 * single-byte reader). Now reads the FULL NUL-
                 * terminated ASCII string after the key field, same
                 * as the key itself, capped at 32 bytes as a sane
                 * bound.
                 */
                String keyEchoed = "";
                String storedValueStr = "";
                int storedValueSingleByte = -1;

                /*
                 * CORRECTED per a real bug found and fixed in #2193:
                 * a FAILURE reply echoes the requested key back with
                 * zero padding, which - if the result code is never
                 * checked - is indistinguishable from a genuine
                 * stored value of empty/zero. Check the result code
                 * (value[12], same position as every other
                 * COMMAND_RESPONSE we decode) FIRST; only trust the
                 * echoed value when it reads SUCCESS(1).
                 */
                int resultCode = value.length > 12 ?
                        (value[12] & 0xff) : -1;
                boolean resultIsSuccess = resultCode == 1;

                if (resultIsSuccess && value.length >= 13 + 1 + 32 + 1 &&
                        value[13] == 0x01) {

                    StringBuilder kb = new StringBuilder();

                    for (int i = 0; i < 32; i++) {
                        int b = value[14 + i] & 0xff;
                        if (b == 0) break;
                        kb.append((char) b);
                    }

                    keyEchoed = kb.toString();

                    StringBuilder vb = new StringBuilder();

                    for (int i = 0; i < 32 &&
                            14 + 32 + i < value.length; i++) {
                        int b = value[14 + 32 + i] & 0xff;
                        if (b == 0) break;
                        vb.append((char) b);
                    }

                    storedValueStr = vb.toString();
                    storedValueSingleByte = value[14 + 32] & 0xff;

                } else if (!resultIsSuccess && value.length >= 13 + 1 + 32 &&
                        value[13] == 0x01) {

                    /*
                     * A FAILURE (or other non-success) reply that
                     * still echoes the key - exactly the shape #2193
                     * warns about. Extract the key for context, but
                     * NEVER claim a stored value from this reply.
                     */
                    StringBuilder kb = new StringBuilder();

                    for (int i = 0; i < 32; i++) {
                        int b = value[14 + i] & 0xff;
                        if (b == 0) break;
                        kb.append((char) b);
                    }

                    keyEchoed = kb.toString();

                    line("*** GET_FF_VALUE REPLY: key=\"" + keyEchoed +
                            "\" resultCode=" + resultCode + " - NOT " +
                            "SUCCESS, this is a REFUSED/FAILED read, " +
                            "NOT a real stored value of empty/zero " +
                            "(the exact #2193 bug pattern) ***");

                    logRaw("GET_FF_VALUE_REFUSED key=" + keyEchoed +
                            " resultCode=" + resultCode +
                            " raw=" + Protocol.hex(value));

                    return;
                }

                line("*** GET_FF_VALUE REPLY: key=\"" + keyEchoed +
                        "\" storedValue=\"" + storedValueStr +
                        "\" (firstByte=" + storedValueSingleByte +
                        " 0x" + String.format("%02X", storedValueSingleByte) +
                        ") - THIS IS THE REAL STORED STATE, not an " +
                        "echo of what we wrote ***");

                logRaw("GET_FF_VALUE_REPLY key=" + keyEchoed +
                        " storedValue=" + storedValueStr +
                        " raw=" + Protocol.hex(value));

                updateLastFfValueDisplay(keyEchoed, storedValueStr);

            } else if (envType == 0x24 &&
                    (envCmd == 117 || envCmd == 118 || envCmd == 115 ||
                            envCmd == 116)) {

                /*
                 * Real key-walk reply, confirmed from #917 with
                 * actual byte examples - two namespaces, same shape:
                 * 117/118 = feature-flag keys, 115/116 = device-config
                 * keys. START(117/115) reply: [revision][count LE
                 * u16, or byte+pad - width genuinely ambiguous per
                 * the PR itself]. SEND_NEXT(118/116) reply:
                 * [const=0x01][index][validKey][key name ASCII if
                 * valid, zero-padded otherwise].
                 */
                String namespace = (envCmd == 117 || envCmd == 118)
                        ? "FEATURE_FLAG" : "DEVICE_CONFIG";

                if (envCmd == 117 || envCmd == 115) {

                    /*
                     * CORRECTED per a real bug found and fixed in
                     * #898/#907: a FAILURE(0) or PENDING(2) reply was
                     * being misread as "successfully enumerated, zero
                     * keys" because revision/count were printed
                     * without checking the result code first. Only
                     * SUCCESS(1) opens a real walk - anything else is
                     * the verb declining, not an empty list.
                     */
                    int resultCode = value.length > 12 ?
                            (value[12] & 0xff) : -1;

                    if (resultCode != 1) {

                        line("*** START_" + namespace + "_KEY_EXCHANGE " +
                                "REPLY: resultCode=" + resultCode +
                                " - NOT SUCCESS, this is the verb " +
                                "declining/pending, NOT a real " +
                                "revision/count - walk should not " +
                                "proceed on this ***");
                        logRaw("START_KEY_EXCHANGE_DECLINED namespace=" +
                                namespace + " resultCode=" + resultCode +
                                " raw=" + Protocol.hex(value));

                    } else {

                    int revision = value.length > 13 ?
                            (value[13] & 0xff) : -1;
                    int countByte = value.length > 14 ?
                            (value[14] & 0xff) : -1;
                    int countU16 = value.length > 15 ?
                            countByte | ((value[15] & 0xff) << 8) :
                            countByte;

                    line("*** START_" + namespace + "_KEY_EXCHANGE " +
                            "REPLY: revision=" + revision +
                            " count(byte)=" + countByte +
                            " count(u16)=" + countU16 +
                            " - width genuinely ambiguous, both " +
                            "printed ***");
                    logRaw("START_KEY_EXCHANGE_REPLY namespace=" +
                            namespace + " revision=" + revision +
                            " countByte=" + countByte +
                            " countU16=" + countU16 +
                            " raw=" + Protocol.hex(value));

                    }

                } else {

                    int index = value.length > 14 ?
                            (value[14] & 0xff) : -1;
                    boolean validKey = value.length > 15 &&
                            value[15] != 0;

                    String keyName = "";

                    if (validKey) {
                        StringBuilder kb = new StringBuilder();
                        for (int i = 16; i < value.length - 4 &&
                                i < 16 + 32; i++) {
                            int b = value[i] & 0xff;
                            if (b == 0) break;
                            kb.append((char) b);
                        }
                        keyName = kb.toString();
                    }

                    boolean isEndMarker = index == 0xFF;

                    line("*** SEND_NEXT_" + namespace + " REPLY: " +
                            "index=" + index + " validKey=" + validKey +
                            " key=\"" + keyName + "\"" +
                            (isEndMarker ? " (0xFF END MARKER)" : "") +
                            " ***");

                    logRaw("SEND_NEXT_REPLY namespace=" + namespace +
                            " index=" + index + " validKey=" + validKey +
                            " key=" + keyName +
                            " raw=" + Protocol.hex(value));

                    if (validKey && !keyName.isEmpty()) {
                        line("    >>> REAL KEY DISCOVERED: \"" +
                                keyName + "\" <<<");
                    }
                }

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

            } else if (envType == 0x24 && envCmd == 120) {

                /*
                 * SET_FF_VALUE echo (cmd=120/0x78) - the confirmed-
                 * working mechanism we've used constantly since early
                 * in this investigation for R22 flags, the ECG gate,
                 * and the enable_sig12 calibration test. Never had
                 * its own named branch before now, so it was falling
                 * through to the generic "may be a real hit" catch
                 * below - alarming, but not a new finding each time;
                 * just this echo, unlabeled.
                 */
                int resultCode = value.length > 12 ? (value[12] & 0xff) : -1;

                logRaw("SET_FF_VALUE_ECHO cmd=120 resultByte=" +
                        resultCode + " raw=" + Protocol.hex(value));

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

                /*
                 * UNCONDITIONAL AUTO-PULL - found by rechecking our
                 * own project's memory notes: an earlier session
                 * documented this exact event (type=48 cmd=0x1D on
                 * fd4b0004) as the confirmed ECG completion signal,
                 * with data retrieved via a type-47 pull immediately
                 * after. The only existing auto-pull path required
                 * experimentActive (the old EXPERIMENT 3x button
                 * specifically) - every other test this session,
                 * including all of today's, never sets that flag, so
                 * every RECORDING_COMPLETE this session has fired
                 * with no pull ever following it. This fires an
                 * immediate pull on ANY recording-complete event,
                 * regardless of which test triggered it - the first
                 * time this session that's actually happened.
                 */
                if (!experimentActive) {

                    line("*** AUTO-PULLING NOW - RECORDING_COMPLETE " +
                            "means data is ready, per this project's " +
                            "own earlier confirmed finding ***");

                    logRaw("AUTO_PULL_ON_RECORDING_COMPLETE_FIRING");

                    mainH.postDelayed(
                            () -> sendCustom(0x2F, 0x01, 0x00), 500);
                }

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

        /*
         * FIXED - real, decoded type=43 data is unambiguous success on
         * its own, regardless of maxEcgOnSeen (a console-text match
         * that has never fired for this client) or the toggle result
         * codes. Found after the frame-padding fix produced 190 real
         * type=43 frames in one session, which this function still
         * reported as "Inconclusive" because it only ever checked
         * maxEcgOnSeen, not realtimeEcgFragments directly. Checked
         * first, ahead of anyFailure/anyUnsupported, since a genuine
         * data stream settles the question outright regardless of
         * what any individual toggle's result code was.
         */
        if (realtimeEcgFragments != null && !realtimeEcgFragments.isEmpty()) {

            int totalBytes = 0;
            for (byte[] frag : realtimeEcgFragments) {
                totalBytes += frag.length;
            }

            line("*** VERDICT: RealDataReceived - " +
                    realtimeEcgFragments.size() + " real type=43 " +
                    "frames (" + totalBytes + " bytes) actually " +
                    "arrived and were decoded. This is genuine, " +
                    "confirmed ECG data, not an inference from a " +
                    "toggle's result code. ***");

            logRaw("ECG_VERDICT=RealDataReceived detail=" + detail +
                    " frameCount=" + realtimeEcgFragments.size() +
                    " totalBytes=" + totalBytes);

            return;
        }

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
     *
     * CORRECTED - a real, serious bug found by precisely checking a
     * captured log: this only ever checked whether the flag NAME
     * appeared in the echo text, never the result code. Verified
     * against every session where the exact echo can be reconstructed
     * (going back to September 11th): enable_raw_data_w_ecg's
     * SET_CONFIG write consistently gets resultByte=0 (FAILURE),
     * while every other R22 flag in the same burst gets resultByte=1
     * (SUCCESS) - a real, distinct refusal, not a matching pattern.
     * Every prior "ECG_GATE_REAL_ECHO_CONFIRMED" was a name match on
     * a write that had actually been refused. Now checks the result
     * code (byte 12, same position as every other COMMAND_RESPONSE)
     * and reports plainly when the gate was refused rather than
     * silently proceeding as if it had succeeded.
     */
    private void checkPendingEcgGateConfirmation(byte[] v) {

        if (pendingEcgGateConfirmationCallback == null
                || pendingEcgGateConfirmationFlagName == null) {
            return;
        }

        String text = new String(
                v, java.nio.charset.StandardCharsets.US_ASCII);

        if (text.contains(pendingEcgGateConfirmationFlagName)) {

            int gateResultCode = v.length > 12 ? (v[12] & 0xff) : -1;

            if (gateResultCode != 1) {

                line("*** ECG GATE WRITE REFUSED for \"" +
                        pendingEcgGateConfirmationFlagName +
                        "\" - resultCode=" + gateResultCode +
                        " (NOT SUCCESS). This is a REAL, DISTINCT " +
                        "refusal - every other R22 flag in this same " +
                        "burst gets resultByte=1. Every prior attempt " +
                        "proceeded to START anyway because this check " +
                        "only looked for the flag name, never the " +
                        "result code - now fixed. Proceeding to START " +
                        "regardless, so this can still be compared " +
                        "against a genuinely-accepted run if one is " +
                        "ever seen ***");

                logRaw("ECG_GATE_WRITE_REFUSED flag=" +
                        pendingEcgGateConfirmationFlagName +
                        " resultCode=" + gateResultCode);

            } else {

                line("*** REAL ECG GATE CONFIRMATION ECHO RECEIVED for " +
                        "\"" + pendingEcgGateConfirmationFlagName +
                        "\" - resultCode=1 (SUCCESS), genuinely " +
                        "accepted this time ***");

                logRaw("ECG_GATE_REAL_ECHO_CONFIRMED flag=" +
                        pendingEcgGateConfirmationFlagName +
                        " resultCode=1");
            }

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

    /*
     * Periodic full GATT re-enumeration, every 30s for the life of
     * the connection - a backstop for Service Changed, since some
     * peripherals don't reliably send that indication even when
     * their structure genuinely changes. Piggybacks on the same
     * rssiMonitoringActive lifetime as the other periodic checks.
     */
    private void schedulePeriodicGattReEnum(BluetoothGatt g) {

        if (!rssiMonitoringActive) {
            return;
        }

        line("--- periodic GATT re-enumeration (30s interval) ---");
        logRaw("PERIODIC_GATT_REENUM_BEGIN");
        dumpAllServices(g);
        logRaw("PERIODIC_GATT_REENUM_END");

        mainH.postDelayed(() -> {
            if (gatt != null && rssiMonitoringActive) {
                schedulePeriodicGattReEnum(gatt);
            }
        }, 30000);
    }

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

            byte[] f = Protocol.puffinFrame(0x23, 121, arg, thisSeq);

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

    /*
     * ------------------------------------------------------------------
     * GET_FF_VALUE (cmd=128/0x80) - the REAL, authoritative read-back
     * verb for the SET_FF_VALUE/SET_CONFIG (cmd=120) namespace we've
     * actually been writing enable_raw_data_w_ecg through. Found
     * directly in the real NOOP source (R22Disable.kt): a completely
     * different opcode from GET_DEVICE_CONFIG_VALUE (121), which
     * belongs to the SEPARATE SET_DEVICE_CONFIG_VALUE (119) namespace
     * we abandoned earlier. Their own code explicitly does not trust
     * a SET_FF_VALUE echo as proof of a real stored change - only a
     * 128 read-back counts as state. We've never sent this opcode at
     * all until now. Request body mirrors GET_DEVICE_CONFIG_VALUE's
     * own shape (0x01 + 32-byte NUL-padded key), by symmetry with the
     * sibling namespace's GET verb - not independently confirmed, but
     * the most reasonable inference available.
     * ------------------------------------------------------------------
     */
    private void getFeatureFlagValue(String key) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        byte[] arg = buildDeviceConfigArg(key, null);
        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.puffinFrame(0x23, 128, arg, thisSeq);

            logRaw("TX GET_FF_VALUE key=" + key +
                    " seq=0x" + String.format("%02X", thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX GET_FF_VALUE key=\"" + key + "\" (the REAL " +
                    "read-back verb, cmd=128 - never sent before now)");
            line("TX LEN =" + f.length);
            line("TX RAW =" + Protocol.hex(f));

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (GET_FF_VALUE)");
                opDone();
            }
        });
    }

    /*
     * ------------------------------------------------------------------
     * CALIBRATION TEST for GET_FF_VALUE(128), using the one key the
     * real community has ALREADY confirmed gets a genuine read-back:
     * "enable_sig12" is documented in the real source (R22Disable.kt)
     * as moving from '2' (0x32) to '1' (0x31), CONFIRMED BY A REAL
     * GET_FF_VALUE(128) READ BEFORE AND AFTER (#423, #103) - the only
     * key with hardware proof this mechanism works at all.
     *
     * Our own two attempts to read enable_raw_data_w_ecg via
     * GET_FF_VALUE(128) got zero reply both times. This test
     * disambiguates why: if enable_sig12 ALSO comes back silent here,
     * our inferred request format itself must be wrong. If it comes
     * back with a real value, the format is right and the silence on
     * the ECG gate specifically is the real, meaningful finding.
     * ------------------------------------------------------------------
     */
    private void runFeatureFlagCalibrationTest() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run calibration test");
            return;
        }

        line("");
        line("*** CALIBRATION TEST: enable_sig12 via SET_FF_VALUE then " +
                "GET_FF_VALUE(128) - this exact key/mechanism combo is " +
                "documented as working on real hardware (#423/#103). " +
                "If THIS comes back silent too, our GET_FF_VALUE request " +
                "format is wrong, not the ECG gate's storage ***");
        logRaw("FF_CALIBRATION_TEST_BEGIN key=enable_sig12");

        /*
         * Value comes from r22ValueFor() - confirmed '1' by #522, a
         * later, more specific live-workout capture that corrected a
         * transcription error in the original #103 capture (which
         * had read this one flag as '2').
         */
        sendR22Flag("enable_sig12", r22ValueFor("enable_sig12"));

        mainH.postDelayed(() -> {

            line("--- now reading it back via GET_FF_VALUE(128) ---");
            getFeatureFlagValue("enable_sig12");

            mainH.postDelayed(() ->
                    logRaw("FF_CALIBRATION_TEST_DONE - compare the " +
                            "GET_FF_VALUE_REPLY line above (or its " +
                            "absence) against this known-working case"),
                    3000);

        }, 1500);
    }

    /*
     * ------------------------------------------------------------------
     * STAGED R22 DISABLE PROBE - matches the real, careful methodology
     * from a merged PR (#932): '0' has NEVER been observed as a
     * confirmed real off-value in the FEATURE-FLAG namespace
     * specifically (120/128) - the assumption it works there is
     * borrowed by convention from the DEVICE-CONFIG namespace
     * (119/121), where it IS confirmed (the Broadcast-HR flag). Our
     * own "clean-slate" test has been blindly writing '0' to all 16
     * flags this entire investigation with no verification it does
     * anything at all - the same category of silent-no-op bug #932
     * found and fixed in the real app's own disable feature.
     *
     * This probes ONE flag first (enable_sig12 - the same probe key
     * #932 used, for the same reason: it's the one flag with a
     * confirmed hardware demonstration that a write to it moves
     * stored state at all), reads it back, and only proceeds to the
     * other 15 if that one genuinely changed. If GET_FF_VALUE stays
     * silent (as it has for us every time), this at minimum stops us
     * from ever again claiming "R22 cleared" without having checked.
     * ------------------------------------------------------------------
     */
    private void runStagedR22DisableProbe() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run staged disable probe");
            return;
        }

        line("");
        line("*** STAGED R22 DISABLE PROBE - '0' has never been " +
                "confirmed to work in the feature-flag namespace. " +
                "Probing enable_sig12 alone first; only clearing the " +
                "other 15 if the read-back confirms it actually " +
                "changed ***");
        logRaw("STAGED_R22_DISABLE_BEGIN probeKey=enable_sig12");

        /*
         * Reset the always-visible readout to a clear "pending" state
         * before this probe's own reply (if any) can arrive - so a
         * stale reply left over from an earlier run/session can never
         * be mistaken for confirmation of THIS probe.
         */
        runOnUiThread(() -> {
            if (lastFfValueDisplay != null) {
                lastFfValueDisplay.setText(
                        "GET_FF_VALUE: (waiting for this probe's " +
                                "reply...)");
            }
        });

        line("--- PROBE: writing '0' to enable_sig12 only ---");
        sendR22Flag("enable_sig12", '0');

        mainH.postDelayed(() -> {

            line("--- reading it back via GET_FF_VALUE(128) - this " +
                    "is the only thing that can confirm the probe, " +
                    "not the write's own ack ***");
            getFeatureFlagValue("enable_sig12");

            /*
             * We can't programmatically branch on the reply here
             * (it's asynchronous and may never arrive), so this pauses
             * long enough for a human to read the GET_FF_VALUE_REPLY
             * line (or its absence) before deciding whether to
             * proceed - matching #932's "gate" stage conceptually,
             * just decided by the person running it rather than
             * automatically.
             */
            mainH.postDelayed(() -> {

                line("*** PROBE STAGE DONE - check the line above: " +
                        "did GET_FF_VALUE_REPLY show enable_sig12 " +
                        "storedValue=0 (i.e. 0x30)? If yes, tap " +
                        "CONFIRM PROBE PASSED - CLEAR REMAINING 15 " +
                        "below. If silent or unchanged, the probe " +
                        "failed - do not claim R22 was cleared ***");
                logRaw("STAGED_R22_DISABLE_PROBE_STAGE_DONE - " +
                        "awaiting manual confirmation before clearing " +
                        "remaining flags");

            }, 3000);

        }, 1500);
    }

    /*
     * Second stage, run only if the probe above was manually confirmed
     * to have actually changed the stored value - clears the
     * remaining 15 flags. Deliberately a separate, manually-triggered
     * step rather than automatic, so a silent/failed probe can never
     * lead to a false "R22 cleared" claim.
     */
    private void confirmProbePassedClearRemaining() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot clear remaining flags");
            return;
        }

        line("");
        line("*** PROBE CONFIRMED PASSED - clearing remaining 15 " +
                "R22 flags now ***");
        logRaw("STAGED_R22_DISABLE_CLEARING_REMAINING");

        int stepIndex = 0;

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];

            if (flag.equals("enable_sig12")) {
                continue;
            }

            int thisStep = stepIndex++;
            long delayMs = 300L * (thisStep + 1);

            mainH.postDelayed(() -> {
                line("--- clearing: \"" + flag + "\" ---");
                sendR22Flag(flag, '0');
            }, delayMs);
        }

        long afterMs = 300L * (stepIndex + 2);

        mainH.postDelayed(() ->
                logRaw("STAGED_R22_DISABLE_COMPLETE"), afterMs);
    }

    /*
     * ------------------------------------------------------------------
     * DEVICE-CONFIG EXCHANGE DURING ACTIVE HISTORICAL PULL - found in a
     * real export from the actual NOOP app: its own successful
     * SET_DEVICE_CONFIG_VALUE(119)/GET_DEVICE_CONFIG_VALUE(121)
     * exchange for enable_raw_data_w_ecg happened ~90s into the
     * connection, DURING an active historical-data offload (chunks
     * being acked back-to-back at that exact moment) - not sent in
     * isolation right after connecting, which is how every one of our
     * own attempts has done it. The request bytes are byte-for-byte
     * identical to ours, so this timing/context difference is the
     * one thing left to test directly: maybe this command only gets
     * answered while the strap's backfill state machine is actively
     * streaming, not when it's idle.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * R24/R25/R26/PIP-R26 CONFIG-KEY PROBE - active probe for the
     * three real, confirmed firmware flag names that have never been
     * decoded by anyone: enable_write_r24_packets,
     * enable_write_r25_packets, disable_pip_r26_packets. Uses the
     * exact same SET(119)/GET(121) mechanism proven working on
     * enable_raw_data_w_ecg earlier in this investigation.
     *
     * The two "enable_*" flags are SET to 1 then read back - if
     * either is a real, recognized key, the strap should echo the
     * value rather than staying silent (matching the confirmed
     * behavior of every other real flag name tried this whole
     * project). "disable_pip_r26_packets" is deliberately NOT written
     * here - it's a disable flag, so writing it risks turning R26 off
     * if it's already streaming by default. It's read-only probed
     * first, to see its current state, before ever considering a
     * write.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * R24/R25/R26 PROBE VIA THE CONFIRMED-WORKING MECHANISM - the
     * earlier probe used setDeviceConfigValue (opcode 119/121), which
     * gave silence for all three names. That's genuinely informative
     * but not conclusive: 119/121 has never itself been confirmed to
     * echo ANY real flag on this firmware. sendR22Flag (opcode 0x78/
     * 120, the actual SET_CONFIG mechanism) is the one with a real
     * track record - it's what enable_raw_data_w_ecg and every other
     * confirmed-real flag on this project actually echoes through.
     * Trying these three names through the mechanism proven to work,
     * not just the one that seemed most obviously named for it.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * STRAP KEY LIST - asks the strap to enumerate its OWN key names.
     * Read-only: no value is ever written from this path.
     *
     * Spec from ryanbr/noop FeatureFlagProbe.kt (#761/#872), itself
     * built from a decompiled official client's response types plus a
     * hands-on WHOOP 4.0 dump, and run on a WHOOP MG (WS50_r03), which
     * announced 16 feature flags and served indices 1-10 then 13-18.
     * That walk is almost certainly where enable_write_r24/r25_packets
     * were first seen.
     *
     *   117 / 115 request body [0x01] -> record [rev][count u16 LE]
     *   118 / 116 request body [0x01], repeated (the strap keeps its own
     *             cursor) -> record [rev][index][validKey][ASCII key\0]
     *   index 0xFF = the strap's own end marker
     *
     * Record = frame bytes 13..len-4 (byte 12 is the result code), same
     * offsets every other COMMAND_RESPONSE decoder here uses.
     *
     * Our old walks never reached the strap: every 115-118 frame sent
     * before the pad4 fix was unpadded and silently dropped. This is the
     * first walk this unit will actually receive.
     * ------------------------------------------------------------------
     */
    private boolean keyWalkActive = false;
    private int keyWalkStartCmd = 117;
    private int keyWalkNextCmd = 118;
    private String keyWalkLabel = "FEATURE-FLAG";
    private int keyWalkSteps = 0;
    private int keyWalkAnnounced = -1;
    private int keyWalkLastIndex = -1;
    private int keyWalkEmptyRun = 0;
    private final java.util.List<String> keyWalkKeys =
            new java.util.ArrayList<>();
    private Runnable keyWalkTimeout;
    private static final int KEY_WALK_MAX_STEPS = 128;
    private static final int KEY_WALK_OVERSHOOT = 4;
    private static final int KEY_WALK_MAX_EMPTY = 8;

    private void runStrapKeyList(boolean featureFlags) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this probe");
            return;
        }
        if (keyWalkActive) {
            line("A key walk is already running - wait for it to finish");
            return;
        }

        keyWalkStartCmd = featureFlags ? 117 : 115;
        keyWalkNextCmd = featureFlags ? 118 : 116;
        keyWalkLabel = featureFlags ? "FEATURE-FLAG" : "DEVICE-CONFIG";
        keyWalkSteps = 0;
        keyWalkAnnounced = -1;
        keyWalkLastIndex = -1;
        keyWalkEmptyRun = 0;
        keyWalkKeys.clear();
        keyWalkActive = true;

        line("");
        line("*** STRAP " + keyWalkLabel + " KEY LIST (" +
                keyWalkStartCmd + "/" + keyWalkNextCmd +
                ", read-only) - asking the strap to name its own keys ***");
        logRaw("KEY_LIST_BEGIN namespace=" + keyWalkLabel);

        sendPuffinPayload(keyWalkStartCmd, new byte[]{0x01},
                "KEY_LIST_START(" + keyWalkStartCmd + ")");
        armKeyWalkTimeout(keyWalkStartCmd);
    }

    private void armKeyWalkTimeout(int awaitingCmd) {
        if (keyWalkTimeout != null) mainH.removeCallbacks(keyWalkTimeout);
        keyWalkTimeout = () -> finishKeyWalk(
                "no reply to opcode " + awaitingCmd + " within 8s");
        mainH.postDelayed(keyWalkTimeout, 8000);
    }

    private boolean keyWalkFrameCrcOk(byte[] v) {
        if (v.length < 16) return false;
        int h = Protocol.crc16Modbus(v, 0, 6);
        if ((v[6] & 0xff) != (h & 0xff) ||
                (v[7] & 0xff) != ((h >>> 8) & 0xff)) return false;
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(v, 8, v.length - 12);
        long crc = c.getValue();
        int p = v.length - 4;
        long got = (v[p] & 0xffL) | ((v[p + 1] & 0xffL) << 8) |
                ((v[p + 2] & 0xffL) << 16) | ((v[p + 3] & 0xffL) << 24);
        return crc == got;
    }

    private void handleKeyWalkReply(int cmd, byte[] v) {

        logRaw("KEY_LIST_REPLY cmd=" + cmd + " raw=" + Protocol.hex(v));

        if (!keyWalkActive ||
                (cmd != keyWalkStartCmd && cmd != keyWalkNextCmd)) {
            return;
        }
        if (!keyWalkFrameCrcOk(v)) {
            line("KEY LIST: reply for cmd " + cmd +
                    " failed CRC - rejected, not decoded");
            finishKeyWalk("CRC failure on a reply (our rejection)");
            return;
        }

        int result = v[12] & 0xff;
        int recLen = v.length - 4 - 13;
        if (recLen < 2) {
            finishKeyWalk("reply record too short to decode");
            return;
        }

        if (cmd == keyWalkStartCmd) {
            int rev = v[13] & 0xff;
            int count = recLen >= 3 ?
                    ((v[14] & 0xff) | ((v[15] & 0xff) << 8)) : -1;
            line("KEY LIST START -> revision=" + rev + " announced=" +
                    count + " result=" + result);
            logRaw("KEY_LIST_START_REPLY namespace=" + keyWalkLabel +
                    " revision=" + rev + " announced=" + count +
                    " result=" + result);
            if (result == 3) {
                finishKeyWalk("strap answered UNSUPPORTED(3) to " +
                        keyWalkStartCmd);
                return;
            }
            keyWalkAnnounced = count;
            sendPuffinPayload(keyWalkNextCmd, new byte[]{0x01},
                    "KEY_LIST_NEXT(" + keyWalkNextCmd + ")");
            armKeyWalkTimeout(keyWalkNextCmd);
            return;
        }

        // cmd == keyWalkNextCmd
        keyWalkSteps++;
        int index = v[14] & 0xff;
        boolean validKey = recLen >= 3 && (v[15] & 0xff) != 0;
        String key = null;
        if (recLen >= 4) {
            StringBuilder kb = new StringBuilder();
            for (int i = 16; i < v.length - 4; i++) {
                int b = v[i] & 0xff;
                if (b == 0) break;
                if (b < 32 || b > 126) { kb.setLength(0); break; }
                kb.append((char) b);
                if (kb.length() > 32) { kb.setLength(0); break; }
            }
            if (kb.length() > 0) key = kb.toString();
        }

        line("  [" + keyWalkSteps + "] index=" + index +
                " validKey=" + validKey +
                (key != null ? " key=\"" + key + "\"" : "") +
                " result=" + result);

        if (result == 3) {
            finishKeyWalk("strap answered UNSUPPORTED(3) to " +
                    keyWalkNextCmd);
            return;
        }
        if (index == 0xFF) {
            finishKeyWalk("strap's own end marker (index 0xFF)");
            return;
        }
        if (!validKey) {
            keyWalkEmptyRun++;
            if (index == keyWalkLastIndex) {
                finishKeyWalk("validKey=0 repeated at index " + index +
                        " - cursor parked");
                return;
            }
            if (keyWalkEmptyRun >= KEY_WALK_MAX_EMPTY) {
                finishKeyWalk(KEY_WALK_MAX_EMPTY +
                        " consecutive empty slots (client-side cap)");
                return;
            }
        } else {
            keyWalkEmptyRun = 0;
            if (key != null && !keyWalkKeys.contains(key)) {
                keyWalkKeys.add(key);
            }
        }
        keyWalkLastIndex = index;

        if (keyWalkSteps >= KEY_WALK_MAX_STEPS) {
            finishKeyWalk("safety cap of " + KEY_WALK_MAX_STEPS +
                    " replies (client-side)");
            return;
        }
        if (keyWalkAnnounced > 0 && keyWalkAnnounced <= KEY_WALK_MAX_STEPS
                && keyWalkSteps >= keyWalkAnnounced + KEY_WALK_OVERSHOOT) {
            finishKeyWalk("announced " + keyWalkAnnounced + " + " +
                    KEY_WALK_OVERSHOOT + " overshoot reached " +
                    "(client-side - strap sent no end marker)");
            return;
        }

        mainH.postDelayed(() -> {
            if (!keyWalkActive) return;
            sendPuffinPayload(keyWalkNextCmd, new byte[]{0x01},
                    "KEY_LIST_NEXT(" + keyWalkNextCmd + ")");
            armKeyWalkTimeout(keyWalkNextCmd);
        }, 150);
    }

    private void finishKeyWalk(String reason) {

        if (!keyWalkActive) return;
        keyWalkActive = false;
        if (keyWalkTimeout != null) mainH.removeCallbacks(keyWalkTimeout);

        line("");
        line("*** " + keyWalkLabel + " KEY LIST FINISHED - " +
                keyWalkKeys.size() + " name(s), announced " +
                keyWalkAnnounced + " - stopped: " + reason + " ***");

        StringBuilder all = new StringBuilder();
        for (int i = 0; i < keyWalkKeys.size(); i++) {
            String k = keyWalkKeys.get(i);
            String lk = k.toLowerCase(java.util.Locale.US);
            boolean flag = lk.contains("r24") || lk.contains("r25") ||
                    lk.contains("r26") || lk.contains("pip") ||
                    lk.contains("ecg") || lk.contains("labrador") ||
                    lk.contains("write");
            line(String.format(java.util.Locale.US, "  %2d. %s%s",
                    i + 1, k, flag ? "   <-- OF INTEREST" : ""));
            if (all.length() > 0) all.append(",");
            all.append(k);
        }

        logRaw("KEY_LIST_SUMMARY namespace=" + keyWalkLabel +
                " announced=" + keyWalkAnnounced +
                " steps=" + keyWalkSteps +
                " named=" + keyWalkKeys.size() +
                " stop=\"" + reason + "\" keys=" + all);
    }

    /*
     * The strap's complete device-config key list on 50.40.1.0, as it
     * reported it itself via the 115/116 walk (26 Sep): 7 keys, ended
     * on its own 0xFF marker. READ-ONLY: GET_DEVICE_CONFIG_VALUE (121)
     * for each, nothing written. Device-config values persist on the
     * strap, and several of these (cont_collection_mode,
     * max_collection_backlog) could plausibly change how it collects
     * or stores data - so current values are documented first, before
     * any write is ever considered.
     */
    private static final String[] DEVICE_CONFIG_KEYS_50_40_1_0 = {
            "sigproc_wear_detect",
            "enable_rfid",
            "max_collection_backlog",
            "cont_collection_mode",
            "whoop_live_hr_in_adv_ind_pkt",
            "whoop_live_2_hrm_devices",
            "enable_raw_data_w_ecg",
    };

    private void readAllDeviceConfigValues() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this probe");
            return;
        }

        line("");
        line("*** READ ALL " + DEVICE_CONFIG_KEYS_50_40_1_0.length +
                " DEVICE-CONFIG VALUES (121, read-only - nothing " +
                "written) ***");
        logRaw("READ_ALL_DEVICE_CONFIG_BEGIN keys=" +
                DEVICE_CONFIG_KEYS_50_40_1_0.length);

        for (int i = 0; i < DEVICE_CONFIG_KEYS_50_40_1_0.length; i++) {
            final String key = DEVICE_CONFIG_KEYS_50_40_1_0[i];
            mainH.postDelayed(() -> getDeviceConfigValue(key),
                    700L * i);
        }
    }

    private void runR24R25R26ViaConfirmedMechanism() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this probe");
            return;
        }

        line("");
        line("*** R24/R25/R26 PROBE VIA SET_CONFIG (0x78/120) - the " +
                "actual confirmed-working mechanism, not the 119/121 " +
                "namespace already tried and found silent ***");
        logRaw("R24_R25_R26_VIA_SET_CONFIG_BEGIN");

        /*
         * CORRECTED (2026-09-26) after checking our own logs:
         * disable_pip_r26_packets is ALREADY a confirmed-real feature
         * flag on this strap - SET_CONFIG(120) returned SUCCESS twice
         * on 20 Sep, and it is one of the 16 flags the official app
         * itself writes on every connect (value '2', from HCI
         * captures #103/#522). It is therefore NEVER written here:
         * '0' has unknown meaning and could move the strap away from
         * the official app's own setting. Read-only baseline for all
         * three via GET_FF_VALUE(128) first - NOOP's own code treats
         * only a 128 read-back as proof of stored state, not a 120
         * echo - then write R24/R25 only, then read those back.
         */
        getFeatureFlagValue("enable_write_r24_packets");
        mainH.postDelayed(() ->
                getFeatureFlagValue("enable_write_r25_packets"), 700);
        mainH.postDelayed(() ->
                getFeatureFlagValue("disable_pip_r26_packets"), 1400);

        mainH.postDelayed(() ->
                sendR22Flag("enable_write_r24_packets", '1'), 2400);
        mainH.postDelayed(() ->
                sendR22Flag("enable_write_r25_packets", '1'), 3100);

        mainH.postDelayed(() ->
                getFeatureFlagValue("enable_write_r24_packets"), 4100);
        mainH.postDelayed(() ->
                getFeatureFlagValue("enable_write_r25_packets"), 4800);
    }

    private void runR24R25R26ConfigProbe() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this probe");
            return;
        }

        line("");
        line("*** R24/R25/R26 CONFIG-KEY PROBE - three flag names " +
                "never decoded by anyone, using the same SET/GET " +
                "mechanism proven on enable_raw_data_w_ecg ***");
        logRaw("R24_R25_R26_CONFIG_PROBE_BEGIN");

        setDeviceConfigValue("enable_write_r24_packets", 0x31);
        mainH.postDelayed(() ->
                getDeviceConfigValue("enable_write_r24_packets"), 800);

        mainH.postDelayed(() ->
                setDeviceConfigValue("enable_write_r25_packets", 0x31),
                1800);
        mainH.postDelayed(() ->
                getDeviceConfigValue("enable_write_r25_packets"), 2600);

        // read-only - never written, per the comment above
        mainH.postDelayed(() ->
                getDeviceConfigValue("disable_pip_r26_packets"), 3600);
    }

    private void runDeviceConfigDuringActivePull() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** DEVICE-CONFIG EXCHANGE DURING ACTIVE PULL - starting " +
                "historical pull, then interleaving the SET/GET " +
                "DEVICE_CONFIG_VALUE exchange for enable_raw_data_w_ecg " +
                "while it's actively streaming (matches how the real " +
                "app's own successful exchange happened) ***");
        logRaw("DEVICE_CONFIG_DURING_PULL_BEGIN");

        sendR22UnlockPartial();

        mainH.postDelayed(this::startRealHistoricalPull, 1500);

        mainH.postDelayed(() -> {

            line("--- pull should be actively streaming now - sending " +
                    "the device-config exchange interleaved ---");
            logRaw("DEVICE_CONFIG_DURING_PULL_SENDING_NOW");

            setDeviceConfigValue("enable_raw_data_w_ecg", 0x31);

            mainH.postDelayed(() ->
                    getDeviceConfigValue("enable_raw_data_w_ecg"), 800);

        }, 3500);
    }

    /*
     * ------------------------------------------------------------------
     * DISABLE_ALARM (cmd=69/0x45) and TOGGLE_REALTIME_HR (cmd=3/0x03) -
     * found directly in the real source (Enums.kt). Both are sent by
     * the real app very early in every connection, right after
     * CLIENT_HELLO acks - before SET_CLOCK, before DIS resolves,
     * before anything else. We have never sent either. Found while
     * tracing three separate real, confirmed-successful exports of
     * the actual app's SET_DEVICE_CONFIG_VALUE(119)/
     * GET_DEVICE_CONFIG_VALUE(121) exchange for enable_raw_data_w_ecg
     * on this exact strap - the one thing all three shared that we've
     * never replicated ourselves. The "during an active historical
     * pull" hypothesis this replaces was directly falsified by one of
     * those same exports (Backfill was explicitly skipped that
     * session, and the gate still succeeded).
     * ------------------------------------------------------------------
     */
    private void sendDisableAlarm() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        byte[] arg = {0x01}; // NOOP: send(.disableAlarm, payload: [0x01])
        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.puffinFrame(0x23, 69, arg, thisSeq);

            logRaw("TX DISABLE_ALARM raw=" + Protocol.hex(f));
            line("TX DISABLE_ALARM (cmd=69, never sent before now)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (DISABLE_ALARM)");
                opDone();
            }
        });
    }

    private void sendToggleRealtimeHr(int val) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.puffinFrame(0x23, 3, new byte[]{(byte) val}, thisSeq);

            logRaw("TX TOGGLE_REALTIME_HR val=" + val +
                    " raw=" + Protocol.hex(f));
            line("TX TOGGLE_REALTIME_HR val=" + val +
                    " (cmd=3, never sent before now)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(TOGGLE_REALTIME_HR)");
                opDone();
            }
        });
    }

    /*
     * Replicates the real app's exact early-connection sequence
     * (DISABLE_ALARM -> TOGGLE_REALTIME_HR(1)) before attempting the
     * device-config exchange - testing whether this specific,
     * previously-unreplicated precondition is what unlocks the reply.
     */
    private void runRealAppSequenceReplication() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REPLICATING REAL APP'S EARLY SEQUENCE: " +
                "DISABLE_ALARM -> TOGGLE_REALTIME_HR(1) -> then the " +
                "device-config exchange for enable_raw_data_w_ecg ***");
        logRaw("REAL_APP_SEQUENCE_REPLICATION_BEGIN");

        sendDisableAlarm();

        mainH.postDelayed(() -> sendToggleRealtimeHr(1), 500);

        mainH.postDelayed(() -> {

            line("--- now sending the device-config exchange ---");
            setDeviceConfigValue("enable_raw_data_w_ecg", 0x31);

            mainH.postDelayed(() ->
                    getDeviceConfigValue("enable_raw_data_w_ecg"), 800);

        }, 2000);
    }

    /*
     * ------------------------------------------------------------------
     * KEY-WALK SWEEPS WITH THE REAL APP'S EARLY-CONNECTION PRECONDITION
     * - a genuine, previously-untried combination. DISABLE_ALARM +
     * TOGGLE_REALTIME_HR (found tracing the real app's confirmed-
     * successful enable_raw_data_w_ecg exchanges) has only ever been
     * tested against that ONE named-key exchange (119/121). The
     * key-walk enumeration mechanisms (115/116, 117/118) were built
     * afterward, from a completely separate thread of this
     * investigation (#917), and have never been tried WITH this same
     * precondition - two real, separately-confirmed pieces that
     * simply never got combined until now.
     * ------------------------------------------------------------------
     */
    private void runKeyWalksWithRealAppPrecondition() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** KEY-WALK SWEEPS WITH REAL APP PRECONDITION: " +
                "DISABLE_ALARM -> TOGGLE_REALTIME_HR(1) -> THEN both " +
                "115/116 and 117/118 walks - never combined before now ***");
        logRaw("KEY_WALK_WITH_PRECONDITION_BEGIN");

        sendDisableAlarm();

        mainH.postDelayed(() -> sendToggleRealtimeHr(1), 500);

        mainH.postDelayed(() -> {

            line("--- precondition sent - now walking DEVICE-CONFIG " +
                    "keys (115/116) ---");

            sendStartDeviceConfigKeyExchange();

            int steps = 25;

            for (int i = 0; i < steps; i++) {

                int stepNum = i;
                long delayMs = 800L * (i + 1);

                mainH.postDelayed(() -> {
                    line("--- SEND_NEXT_DEVICE_CONFIG step " + stepNum +
                            " (with precondition) ---");
                    sendNextDeviceConfig();
                }, delayMs);
            }

            long afterDeviceConfigMs = 800L * (steps + 1) + 1000;

            mainH.postDelayed(() -> {

                line("--- device-config walk done - now walking " +
                        "FEATURE-FLAG keys (117/118) ---");

                sendStartFfKeyExchange();

                for (int i = 0; i < steps; i++) {

                    int stepNum = i;
                    long delayMs = 800L * (i + 1);

                    mainH.postDelayed(() -> {
                        line("--- SEND_NEXT_FF step " + stepNum +
                                " (with precondition) ---");
                        sendNextFf();
                    }, delayMs);
                }

                long afterFfMs = 800L * (steps + 1) + 500;

                mainH.postDelayed(() ->
                        logRaw("KEY_WALK_WITH_PRECONDITION_COMPLETE"),
                        afterFfMs);

            }, afterDeviceConfigMs);

        }, 2000);
    }

    private void setDeviceConfigValue(String key, int valueByte) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        byte[] arg = buildDeviceConfigArg(key, valueByte);
        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.puffinFrame(0x23, 119, arg, thisSeq);

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

                mainH.postDelayed(
                    () -> sendR22Flag(flag, r22ValueFor(flag)), delayMs);
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
    /*
     * ------------------------------------------------------------------
     * Precise touch markers - a real gap in every prior contact test.
     * These just log a clearly-tagged timestamp the instant they're
     * tapped, so any RX event (or any change in one) can be checked
     * against the EXACT moment contact started/stopped, rather than
     * estimated from wall-clock guesses. No BLE traffic sent.
     * ------------------------------------------------------------------
     */
    private void markTouchStart() {
        line("");
        line("*** MARK: TOUCH START ***");
        logRaw("MARK_TOUCH_START");
    }

    private void markTouchEnd() {
        line("");
        line("*** MARK: TOUCH END ***");
        logRaw("MARK_TOUCH_END");
    }

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
     * ENTER_HIGH_FREQ_SYNC (cmd=96) - explicitly named by the real
     * project's own maintainer as one of only TWO remaining real
     * avenues on the v16 flash-ECG-record mystery (issue #1100's own
     * closure comment: "the remaining open avenues are hardware-/
     * protocol-gated - a device that actually populates ECG, and
     * confirming the ENTER_HIGH_FREQ_SYNC opcode (#592)"). #1100
     * itself declined to send this specific opcode due to unresolved
     * numbering uncertainty between two competing RE sources (96/97/98
     * vs 85/86/87, tracked in #592). That numbering has since settled
     * on 96/97/98 (confirmed by #592's own PR trail - a real, shipped
     * battery-voltage feature built directly on "command 98" as
     * GET_EXTENDED_BATTERY_INFO), which removes the exact uncertainty
     * that made #1100 avoid sending 96 to a real strap.
     *
     * Rationale for trying this: a 30-second ECG at 500Hz is tens of
     * KB - #1100's own reasoning is that this is plausibly why the
     * ordinary offload (v16/v18) never carries it, and
     * ENTER_HIGH_FREQ_SYNC would be the natural transport for a
     * large flash-resident record like that. Read-only in spirit
     * (per #592's own framing) but genuinely untested on THIS unit -
     * sent once, watched closely, not repeated blindly.
     * ------------------------------------------------------------------
     */
    private void probeEnterHighFreqSync() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot probe");
            return;
        }

        line("");
        line("*** PROBING ENTER_HIGH_FREQ_SYNC (cmd=96) - explicitly " +
                "named by the real project's own maintainer as one of " +
                "only two remaining real avenues on the v16 flash-ECG " +
                "mystery. Never sent before now - watching closely for " +
                "ANY response or behavior change ***");
        logRaw("ENTER_HIGH_FREQ_SYNC_PROBE_BEGIN cmd=96");

        line("*** ENTER_HIGH_FREQ_SYNC NOT SENT - now that frames are correctly padded the strap would ACT on it; it is on NOOP's forbidden list (can park the strap in high-frequency mode) ***");

        mainH.postDelayed(() ->
                logRaw("ENTER_HIGH_FREQ_SYNC_PROBE_DONE - check above " +
                        "for any COMMAND_RESPONSE or unexpected " +
                        "notification"), 3000);
    }

    /*
     * ------------------------------------------------------------------
     * EXIT_HIGH_FREQ_SYNC (cmd=97) - a safety-relevant gap: we added
     * ENTER(96) without its exit counterpart, despite #1100's own
     * explicit warning ("particularly given the existing note about
     * straps found parked in high-frequency mode"). If ENTER ever
     * does something real on this unit, this is how to cleanly back
     * out rather than leaving the strap in an unknown state.
     * ------------------------------------------------------------------
     */
    private void probeExitHighFreqSync() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot probe");
            return;
        }

        line("");
        line("*** SENDING EXIT_HIGH_FREQ_SYNC (cmd=97) - the safety " +
                "counterpart to ENTER(96). Use this after ENTER if you " +
                "want to back out cleanly ***");
        logRaw("EXIT_HIGH_FREQ_SYNC_PROBE_BEGIN cmd=97");

        sendPuffinPayload(97, new byte[0], "EXIT_HIGH_FREQ_SYNC_PROBE (empty body)");

        mainH.postDelayed(() ->
                logRaw("EXIT_HIGH_FREQ_SYNC_PROBE_DONE"), 3000);
    }

    /*
     * ------------------------------------------------------------------
     * GET_BODY_LOCATION_AND_STATUS (cmd=84/0x54) - a real, confirmed
     * read-only probe (issue #690, merged) we learned about but never
     * actually implemented. Response format from the real decompiled
     * parser: [revision][location][confidence][status], 4 bytes.
     * Location enum: 0=UNKNOWN, 1=WRIST, 2=BICEP, 3=CALF,
     * 4=SIDE_TORSO, 5=GLUTE, 7=ANKLE, 128=NOT_CONCLUSIVE,
     * 160=UNKNOWN_GARMENT. Genuinely relevant to us: this is a
     * completely separate, real wear/location-detection mechanism
     * from anything we've tried on the ECG side - if it reports
     * something other than WRIST, or a low confidence, that would be
     * a real, independent signal about physical contact state we've
     * never had before.
     * ------------------------------------------------------------------
     */
    private void probeBodyLocationAndStatus() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot probe");
            return;
        }

        line("");
        line("*** PROBING GET_BODY_LOCATION_AND_STATUS (cmd=84) - a " +
                "real, confirmed read-only wear/location probe (#690), " +
                "never implemented before now ***");
        logRaw("BODY_LOCATION_PROBE_BEGIN cmd=84");

        sendPuffinPayload(84, new byte[]{0x01}, "GET_BODY_LOCATION_AND_STATUS_PROBE (revision body)");

        mainH.postDelayed(() ->
                logRaw("BODY_LOCATION_PROBE_DONE - check above for the " +
                        "COMMAND_RESPONSE; decode revision/location/" +
                        "confidence/status from its raw hex"), 3000);
    }

    /*
     * ------------------------------------------------------------------
     * GET_EXTENDED_BATTERY_INFO (cmd=98/0x62) - directly adjacent to
     * the ENTER_HIGH_FREQ_SYNC family above, same resolved numbering
     * uncertainty (#592), never actually sent by us despite being
     * discussed. Read-only, low-risk. Not expected to relate to ECG
     * directly, but completes the family we just started probing and
     * costs one round-trip.
     * ------------------------------------------------------------------
     */
    private void probeExtendedBatteryInfo() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot probe");
            return;
        }

        line("");
        line("*** PROBING GET_EXTENDED_BATTERY_INFO (cmd=98) - same " +
                "resolved-numbering family as cmd=96, never actually " +
                "sent before now ***");
        logRaw("EXTENDED_BATTERY_INFO_PROBE_BEGIN cmd=98");

        sendPuffinPayload(98, new byte[]{0x01}, "GET_EXTENDED_BATTERY_INFO_PROBE (revision body)");

        mainH.postDelayed(() ->
                logRaw("EXTENDED_BATTERY_INFO_PROBE_DONE"), 3000);
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

                line("=== WRIST VALUE TEST DONE - IMPORTANT: per real " +
                        "PR discussion (#907), SELECT_WRIST's convention " +
                        "is INVERTED from normal expectation - " +
                        "SUCCESS means a NO-OP (value already set), " +
                        "FAILURE means it actually CHANGED something. " +
                        "Read the two results above with that in mind, " +
                        "not as pass/fail ===");

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

            mainH.postDelayed(
                    () -> sendR22Flag(flag, r22ValueFor(flag)), delayMs);
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

    /*
     * ------------------------------------------------------------------
     * CONTACT-FIRST ECG ATTEMPT - tests whether contact needs to be
     * established and settled BEFORE the command sequence, not at or
     * after it as every prior attempt has done. Motivated by the
     * MAX86176's own datasheet: it features "ultra-low power DC
     * lead-on detection during standby mode" - a real, chip-level
     * autonomous contact-detection feature that operates before the
     * full ECG acquisition pipeline ever spins up. If the firmware's
     * higher-level "start generation" command only means something
     * once the chip has already latched a lead-on state itself,
     * touching at/after START (every attempt so far) could be too
     * late relative to whatever the chip needs to have already
     * settled internally.
     *
     * Sequence: prompt for contact now, wait 5s for it to settle,
     * THEN run the normal R22+gate+start sequence while contact
     * continues throughout - not tested in this order before.
     * ------------------------------------------------------------------
     */
    private void runContactFirstEcgAttempt() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run contact-first attempt");
            return;
        }

        line("");
        line("*** CONTACT-FIRST ECG ATTEMPT - TOUCH THE CLASP NOW, " +
                "BEFORE ANYTHING IS SENT - hold steady, waiting 5s for " +
                "it to settle before the command sequence starts ***");
        logRaw("CONTACT_FIRST_ATTEMPT_BEGIN");

        mainH.postDelayed(() -> {

            line("*** contact settle wait done - now running the " +
                    "normal R22+gate+start sequence - KEEP TOUCHING ***");
            logRaw("CONTACT_FIRST_SEQUENCE_NOW_SENDING");

            for (int i = 0; i < R22_FLAGS.length; i++) {

                String flag = R22_FLAGS[i];
                long delayMs = 80L * (i + 1);

                mainH.postDelayed(
                    () -> sendR22Flag(flag, r22ValueFor(flag)), delayMs);
            }

            long afterR22Ms = 80L * (R22_FLAGS.length + 2);

            mainH.postDelayed(() -> {

                pendingEcgGateConfirmationFlagName = "enable_raw_data_w_ecg";
                pendingEcgGateConfirmationCallback =
                        this::fireRealEcgStartAfterGateConfirmed;

                sendR22Flag("enable_raw_data_w_ecg", '1');

                mainH.postDelayed(() -> {

                    if (pendingEcgGateConfirmationCallback != null) {

                        line("*** gate echo TIMED OUT - proceeding " +
                                "anyway ***");
                        logRaw("ECG_GATE_ECHO_TIMEOUT flag=" +
                                "enable_raw_data_w_ecg");

                        Runnable cb = pendingEcgGateConfirmationCallback;
                        pendingEcgGateConfirmationCallback = null;
                        pendingEcgGateConfirmationFlagName = null;
                        cb.run();
                    }

                }, 3000);

            }, afterR22Ms);

        }, 5000);
    }

    /*
     * ------------------------------------------------------------------
     * CLEAN-SLATE ECG ATTEMPT - explicitly clears all ten R22 flags
     * first (mirroring the real R22Disable mechanism: write '0' via
     * SET_FF_VALUE, same opcode ECG uses), then runs ONLY the gate +
     * 3-toggle sequence with nothing else active this session, with
     * deliberately unhurried 2s pacing between each of the three
     * toggles rather than near-instant timing. Isolates whether R22
     * being active, or rushed timing between steps, could be masking
     * something - every prior combined attempt enabled R22 first and
     * moved through the three toggles within milliseconds of each
     * other.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * REAL DOCUMENTED SEQUENCE ORDER - genuinely never tried before now.
     * Every one of our combined ECG attempts (FULL_COMBINED,
     * CONTACT_FIRST, CLEAN_SLATE, ULTIMATE, SUGGESTED_SEQUENCE) sends
     * the gate + three toggles, but NONE of them send SELECT_WRIST
     * first - despite issue #1100's own real, documented, confirmed
     * turn-on sequence explicitly listing it as step one, before
     * anything else:
     *
     *   select wrist (123) -> filtered (139=1) -> raw-save (125=1)
     *       -> data-generation (124=2, start)
     *
     * We've tested SELECT_WRIST only in isolation (testBothWristValues)
     * and the three toggles only without it. This sends the exact
     * documented order, for the first time, with confirmed-good
     * contact quality methodology (touch held throughout, corrected
     * R22 values, proper gate-refusal reporting already in place).
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * REAL APP EXACT FLOW - PURE ECG, NO R22 - the single most
     * important new test from decompiling the real app's actual ECG
     * code path (ts1.a, the real LabradorServiceImpl). Traced through
     * bs1.g -> zr1.d -> ts1.a in full: the real ECG-reading flow is
     * SELECT_WRIST -> two ENABLE toggles (matching 139/125) -> THEN,
     * as a SEPARATE, LATER USER ACTION (a different button tap in the
     * real app's own UI, not fired in the same burst) -> the single
     * START/STOP/RESTART command (124).
     *
     * CRITICALLY: R22_FLAGS never appear ANYWHERE in this real,
     * decompiled code path. Every combined attempt we've ever run
     * sent R22 first, based on third-party community assumptions -
     * this is the first test that deliberately sends NONE, matching
     * exactly what the real app's own ECG-specific code does.
     *
     * Split into two explicit stages (not one burst) to genuinely
     * match the real app's two-stage UX - tap PREP, confirm/wait,
     * THEN separately tap START - rather than assuming a fixed delay
     * is equivalent to a real, separate user action.
     * ------------------------------------------------------------------
     */
    private void runRealAppExactFlowPrep() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REAL APP EXACT FLOW - STAGE 1: PREP (SELECT_WRIST + " +
                "2 enable toggles, deliberately NO R22 flags - matches " +
                "the real decompiled LabradorServiceImpl exactly). " +
                "TOUCH NOW AND HOLD ***");
        logRaw("REAL_APP_EXACT_FLOW_PREP_BEGIN");

        send(0x7B, WRIST_ARG, "SELECT_WRIST (real app exact flow, stage 1)");

        mainH.postDelayed(() ->
                send(0x8B, 1,
                        "TOGGLE_REALTIME_FILTERED_ECG_ON (real app " +
                                "exact flow, stage 1 - matches f0/l0 " +
                                "ENABLE toggle)"), 500);

        mainH.postDelayed(() ->
                send(0x7D, 1,
                        "TOGGLE_SAVE_RAW_ECG_ON (real app exact flow, " +
                                "stage 1 - matches f0/l0 ENABLE toggle)"),
                1000);

        mainH.postDelayed(() ->
                logRaw("REAL_APP_EXACT_FLOW_PREP_DONE - now tap STAGE " +
                        "2: START as a genuinely separate action, " +
                        "matching the real app's own two-stage UX"),
                1500);
    }

    private void runRealAppExactFlowStart() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REAL APP EXACT FLOW - STAGE 2: START (the single " +
                "u(v) command from ts1.a, sent as a genuinely separate " +
                "action from prep) - KEEP TOUCHING ***");
        logRaw("REAL_APP_EXACT_FLOW_START_BEGIN");

        realtimeEcgFragments.clear();
        ecgCommandResponsesThisAttempt.clear();
        realtimeEcgTotalBytes = 0;
        realtimeEcgBinaryFile = null;
        maxEcgOnSeen = false;

        labradorActive = true;
        recordingComplete = false;
        labradorPacketCount = 0;

        ecgEverRunThisConnection = true;

        send(0x7C, 2,
                "MAIN_CONTROL_ECG_DATA_GENERATION_START (real app " +
                        "exact flow, stage 2 - matches u(v.START))");

        ecgListenActive = true;
        ecgListenGeneration++;
        ecgListenStartedAtMs = System.currentTimeMillis();

        final int myGen = ecgListenGeneration;

        mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);
    }

    /*
     * ------------------------------------------------------------------
     * REAL APP EXACT FLOW WITH RETRY - a genuinely important discovery
     * from an independent iOS decompile (Goose project's own research,
     * of the real official WHOOP app): a confirmed real string,
     * "failure_after_three_attempts", from LabradorReadingStrapInteractor
     * .swift - the real app's own ECG state machine explicitly expects
     * it may need UP TO THREE ATTEMPTS before succeeding, and only
     * reports failure after all three fail.
     *
     * We have NEVER retried our own combined attempt - every test this
     * entire investigation has been a single shot. If a single failed
     * attempt is normal, even on working hardware, then our own single-
     * shot tests may have been stopping exactly where the real app
     * would have silently tried again. This runs PREP+START up to
     * three times, stopping early only if real data actually arrives.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * TWO-COMMAND TURN-ON (139 THEN 124, DELIBERATELY NO 125) - the
     * exact, precise sequence stated in NOOP's own upstream PR #1765
     * as what actually produced 315 REAL, confirmed type=43
     * REALTIME_RAW_DATA records on a WHOOP MG (same hardware
     * revision, WS50_r00, as this unit): "the packet the MG streams
     * once the #1727 turn-on order (139 = 1 then 124 = 2) has opened
     * the stream."
     *
     * Every combined attempt we have ever built - Ultimate,
     * ContactFirst, CleanSlate, FullCombined, RealAppExactFlow, the
     * retry variant, the documented-sequence variant - has always
     * included 125 (TOGGLE_SAVE_RAW_ECG) as part of the sequence.
     * Checked directly against our own code before writing this:
     * confirmed, no exceptions. This is the first test that
     * deliberately omits it, matching precisely what the one person
     * in the whole thread who got real data actually sent.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * REAL SEQUENCE WITH ABORT_HISTORICAL (opcode 20) - the single most
     * important new finding of the entire investigation. Found in a
     * completely independent, previously-unknown project (OpenStrap/
     * edge, lib/ble/ble_engine.dart), citing the real official
     * Android app's own compiled logic: the true START sequence is
     * NOT just "toggles then 124" - it's:
     *
     *   PREPARE: 123 (SELECT_WRIST) -> 139=1 (FILTERED ON) ->
     *            125=1 (RAW_SAVE ON)
     *   START:   20 (ABORT_HISTORICAL_TRANSMITS, UNCONDITIONAL,
     *            single zero byte body) -> 124=2 (GENERATION START)
     *   CLEANUP: 124=1 (STOP) -> 139=0 (FILTERED OFF) ->
     *            125=0 (RAW_SAVE OFF)
     *
     * We have NEVER, not once, sent opcode 20 as part of any ECG
     * attempt this entire investigation - every test has gone
     * straight from the toggles to 124 START. If the firmware
     * genuinely refuses to arm ECG generation while it believes a
     * historical transmission might still be pending - even when
     * none actually is - this single missing command would explain
     * silence on 124 specifically while everything else (R22,
     * historical pulls) keeps working normally.
     *
     * Also implements the full three-step CLEANUP (124 STOP, THEN
     * 139 OFF, THEN 125 OFF) - we've only ever sent 124=1 alone
     * before, never followed by turning the two toggles back off.
     * ------------------------------------------------------------------
     */
    private void runRealSequenceWithAbortHistorical() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REAL SEQUENCE WITH ABORT_HISTORICAL (opcode 20) - " +
                "found in an independent project citing the real " +
                "official app's own compiled logic. Never tried " +
                "before now. TOUCH NOW AND HOLD ***");
        logRaw("REAL_SEQUENCE_WITH_ABORT_HISTORICAL_BEGIN");

        line("--- PREPARE: SELECT_WRIST -> FILTERED ON -> RAW_SAVE ON ---");

        send(0x7B, WRIST_ARG, "SELECT_WRIST (real sequence, prepare)");

        mainH.postDelayed(() ->
                send(0x8B, 1,
                        "TOGGLE_LABRADOR_FILTERED_ON (real sequence, " +
                                "prepare)"), 500);

        mainH.postDelayed(() ->
                send(0x7D, 1,
                        "TOGGLE_LABRADOR_RAW_SAVE_ON (real sequence, " +
                                "prepare)"), 1000);

        mainH.postDelayed(() -> {

            line("--- START: ABORT_HISTORICAL_TRANSMITS (opcode 20, " +
                    "NEVER SENT BEFORE) -> GENERATION START ---");

            realtimeEcgFragments.clear();
            ecgCommandResponsesThisAttempt.clear();
            realtimeEcgTotalBytes = 0;
            realtimeEcgBinaryFile = null;
            maxEcgOnSeen = false;

            labradorActive = true;
            recordingComplete = false;
            labradorPacketCount = 0;

            ecgEverRunThisConnection = true;

            sendPuffinPayload(0x14, new byte[]{0x00},
                    "ABORT_HISTORICAL_TRANSMITS (real sequence, " +
                            "gen5 exact shape [35][seq][20][00])");

            mainH.postDelayed(() ->
                    send(0x7C, 2,
                            "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                    "(real sequence, after abort-" +
                                    "historical)"), 500);

            ecgListenActive = true;
            ecgListenGeneration++;
            ecgListenStartedAtMs = System.currentTimeMillis();

            final int myGen = ecgListenGeneration;

            mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);

        }, 1500);
    }

    private void runTwoCommandTurnOn() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** TWO-COMMAND TURN-ON (139 then 124, DELIBERATELY NO " +
                "125) - the exact sequence #1765 states produced 315 " +
                "real type=43 records on a matching WS50_r00 unit. " +
                "TOUCH NOW AND HOLD ***");
        logRaw("TWO_COMMAND_TURNON_BEGIN");

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
                "TOGGLE_REALTIME_FILTERED_ECG_ON (two-command turn-on, " +
                        "no 125)", () ->
                send(0x7C, 2,
                        "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                "(two-command turn-on, no 125)"));

        ecgListenActive = true;
        ecgListenGeneration++;
        ecgListenStartedAtMs = System.currentTimeMillis();

        final int myGen = ecgListenGeneration;

        mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);
    }

    private void runRealAppExactFlowWithRetry() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REAL APP EXACT FLOW WITH RETRY - confirmed from a " +
                "real string in the official app's own decompiled " +
                "source (\"failure_after_three_attempts\") - the real " +
                "app expects up to 3 attempts before giving up. We've " +
                "only ever tried once. TOUCH NOW AND HOLD THROUGH ALL " +
                "THREE ATTEMPTS ***");
        logRaw("REAL_APP_EXACT_FLOW_RETRY_BEGIN");

        attemptRealAppExactFlowOnce(1);
    }

    private void attemptRealAppExactFlowOnce(int attemptNumber) {

        if (gatt == null || cmdWrite == null) {
            return;
        }

        line("");
        line("=== ATTEMPT " + attemptNumber + " of 3 ===");
        logRaw("REAL_APP_EXACT_FLOW_ATTEMPT_" + attemptNumber + "_BEGIN");

        send(0x7B, WRIST_ARG, "SELECT_WRIST (retry attempt " + attemptNumber + ")");

        mainH.postDelayed(() ->
                send(0x8B, 1,
                        "TOGGLE_REALTIME_FILTERED_ECG_ON (retry attempt " +
                                attemptNumber + ")"), 500);

        mainH.postDelayed(() ->
                send(0x7D, 1,
                        "TOGGLE_SAVE_RAW_ECG_ON (retry attempt " +
                                attemptNumber + ")"), 1000);

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

            send(0x7C, 2,
                    "MAIN_CONTROL_ECG_DATA_GENERATION_START (retry " +
                            "attempt " + attemptNumber + ")");

            ecgListenActive = true;
            ecgListenGeneration++;
            ecgListenStartedAtMs = System.currentTimeMillis();

            final int myGen = ecgListenGeneration;

            mainH.postDelayed(() -> runEcgListenHeartbeat(myGen), 5000);

        }, 1500);

        /*
         * After a real listen window, stop this attempt and decide
         * whether to retry. Only retries if NOTHING arrived - any
         * real COMMAND_RESPONSE or type=43 data means this attempt is
         * worth stopping on and reporting, not masking with a retry.
         *
         * CORRECTED to 90s - superseding the earlier 120s estimate
         * (from OpenStrap/edge, a general-purpose engine, not
         * confirmed working). This 90s figure comes from NOOP's own
         * actual, SHIPPED, working live-ECG capture screen
         * (EcgCaptureView.maxDuration, merged into ayiskakov/noop as
         * part of the mg-ecg-live PR that produces real records) -
         * the most authoritative source available, since it's live,
         * working code rather than a general estimate.
         */
        mainH.postDelayed(() -> {

            boolean gotAnyResponse = !ecgCommandResponsesThisAttempt.isEmpty();
            boolean gotAnyData = realtimeEcgFragments != null &&
                    !realtimeEcgFragments.isEmpty();

            send(0x7C, 1,
                    "MAIN_CONTROL_ECG_DATA_GENERATION_STOP (retry " +
                            "attempt " + attemptNumber + ")");

            line("--- attempt " + attemptNumber + " done: gotResponse=" +
                    gotAnyResponse + " gotData=" + gotAnyData + " ---");

            logRaw("REAL_APP_EXACT_FLOW_ATTEMPT_" + attemptNumber +
                    "_DONE gotResponse=" + gotAnyResponse +
                    " gotData=" + gotAnyData);

            if (gotAnyResponse || gotAnyData) {

                line("*** ATTEMPT " + attemptNumber + " GOT SOMETHING - " +
                        "STOPPING HERE, not retrying further ***");
                logRaw("REAL_APP_EXACT_FLOW_RETRY_STOPPED_EARLY_ON_" +
                        "ATTEMPT_" + attemptNumber);

                reportEcgAttemptVerdict();

            } else if (attemptNumber < 3) {

                line("--- nothing this attempt - retrying (matches " +
                        "the real app's own confirmed up-to-3-attempts " +
                        "behavior) ---");

                mainH.postDelayed(
                        () -> attemptRealAppExactFlowOnce(attemptNumber + 1),
                        2000);

            } else {

                line("*** ALL 3 ATTEMPTS EXHAUSTED, NOTHING RECEIVED - " +
                        "matches the real app's own " +
                        "\"failure_after_three_attempts\" outcome ***");
                logRaw("REAL_APP_EXACT_FLOW_RETRY_ALL_3_EXHAUSTED");

                reportEcgAttemptVerdict();
            }

        }, 90000);
    }

    private void runRealDocumentedSequenceOrder() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run this test");
            return;
        }

        line("");
        line("*** REAL DOCUMENTED SEQUENCE ORDER (#1100) - SELECT_WRIST " +
                "FIRST, then filtered->raw-save->data-generation. Never " +
                "tried in this order before now. TOUCH NOW AND HOLD " +
                "THROUGH THE WHOLE SEQUENCE ***");
        logRaw("REAL_DOCUMENTED_SEQUENCE_BEGIN");

        line("--- step 1: SELECT_WRIST (never before sent as part of " +
                "an actual combined attempt) ---");
        send(0x7B, WRIST_ARG, "SELECT_WRIST (documented sequence, step 1)");

        mainH.postDelayed(() -> {

            line("--- step 2: R22 unlock (corrected values) ---");

            for (int i = 0; i < R22_FLAGS.length; i++) {

                String flag = R22_FLAGS[i];
                long delayMs = 80L * (i + 1);

                mainH.postDelayed(
                        () -> sendR22Flag(flag, r22ValueFor(flag)),
                        delayMs);
            }

            long afterR22Ms = 80L * (R22_FLAGS.length + 2);

            mainH.postDelayed(() -> {

                line("--- step 3: ECG gate ---");

                pendingEcgGateConfirmationFlagName =
                        "enable_raw_data_w_ecg";
                pendingEcgGateConfirmationCallback = () -> {

                    ecgEverRunThisConnection = true;

                    realtimeEcgFragments.clear();
                    ecgCommandResponsesThisAttempt.clear();
                    realtimeEcgTotalBytes = 0;
                    realtimeEcgBinaryFile = null;
                    maxEcgOnSeen = false;

                    labradorActive = true;
                    recordingComplete = false;
                    labradorPacketCount = 0;

                    line("--- step 4: the three toggles, in the " +
                            "documented order - SELECT_WRIST was " +
                            "already sent as step 1 ---");

                    sendWithCallback(0x8B, 1,
                            "TOGGLE_REALTIME_FILTERED_ECG_ON " +
                                    "(documented sequence)", () ->
                            sendWithCallback(0x7D, 1,
                                    "TOGGLE_SAVE_RAW_ECG_ON " +
                                            "(documented sequence)", () ->
                            send(0x7C, 2,
                                    "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                            "(documented sequence)")));

                    ecgListenActive = true;
                    ecgListenGeneration++;
                    ecgListenStartedAtMs = System.currentTimeMillis();

                    final int myGen = ecgListenGeneration;

                    mainH.postDelayed(
                            () -> runEcgListenHeartbeat(myGen), 5000);
                };

                sendR22Flag("enable_raw_data_w_ecg", '1');

                mainH.postDelayed(() -> {

                    if (pendingEcgGateConfirmationCallback != null) {

                        line("*** gate echo TIMED OUT - proceeding " +
                                "anyway ***");
                        logRaw("ECG_GATE_ECHO_TIMEOUT flag=" +
                                "enable_raw_data_w_ecg");

                        Runnable cb = pendingEcgGateConfirmationCallback;
                        pendingEcgGateConfirmationCallback = null;
                        pendingEcgGateConfirmationFlagName = null;
                        cb.run();
                    }

                }, 3000);

            }, afterR22Ms);

        }, 1000);
    }

    private void runCleanSlateEcgAttempt() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot run clean-slate attempt");
            return;
        }

        line("");
        line("*** CLEAN-SLATE ECG ATTEMPT - clearing all R22 flags " +
                "first, then ONLY gate+3-toggle, slowly paced. TOUCH " +
                "NOW AND HOLD THROUGH THE WHOLE SEQUENCE ***");
        logRaw("CLEAN_SLATE_ATTEMPT_BEGIN");

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];
            long delayMs = 300L * (i + 1);

            mainH.postDelayed(() -> {
                line("--- clearing: \"" + flag + "\" ---");
                sendR22Flag(flag, '0');
            }, delayMs);
        }

        long afterClearMs = 300L * (R22_FLAGS.length + 2);

        mainH.postDelayed(() -> {

            line("*** R22 flags cleared - now sending ONLY the ECG " +
                    "gate + 3-toggle sequence, slowly paced ***");
            logRaw("CLEAN_SLATE_SEQUENCE_NOW_SENDING");

            pendingEcgGateConfirmationFlagName = "enable_raw_data_w_ecg";
            pendingEcgGateConfirmationCallback = () -> {

                /*
                 * FIXED: this callback never set
                 * ecgEverRunThisConnection, unlike every other
                 * combined attempt - found by checking this exact
                 * session's own control-summary output, which showed
                 * "this_pull_ecg_first=false" despite ECG commands
                 * having genuinely fired earlier in the same
                 * connection. Any pull following a clean-slate run
                 * was being silently miscategorized.
                 */
                ecgEverRunThisConnection = true;

                mainH.postDelayed(() -> send(0x8B, 1,
                        "TOGGLE_REALTIME_FILTERED_ECG_ON " +
                                "(clean-slate)"), 2000);

                mainH.postDelayed(() -> send(0x7D, 1,
                        "TOGGLE_SAVE_RAW_ECG_ON (clean-slate)"), 4000);

                mainH.postDelayed(() -> send(0x7C, 2,
                        "MAIN_CONTROL_ECG_DATA_GENERATION_START " +
                                "(clean-slate)"), 6000);
            };

            sendR22Flag("enable_raw_data_w_ecg", '1');

            mainH.postDelayed(() -> {

                if (pendingEcgGateConfirmationCallback != null) {

                    line("*** gate echo TIMED OUT - proceeding " +
                            "anyway ***");
                    logRaw("ECG_GATE_ECHO_TIMEOUT flag=" +
                            "enable_raw_data_w_ecg");

                    Runnable cb = pendingEcgGateConfirmationCallback;
                    pendingEcgGateConfirmationCallback = null;
                    pendingEcgGateConfirmationFlagName = null;
                    cb.run();
                }

            }, 3000);

        }, afterClearMs);
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

            mainH.postDelayed(
                    () -> sendR22Flag(flag, r22ValueFor(flag)), delayMs);
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
    /*
     * Wrist argument for SELECT_WRIST(123). docs/PROTOCOL_ECG.md (from the
     * real app's own compiled handler): payload 01 01 = RIGHT, 01 02 = LEFT;
     * every other argument FAILS - including the 0 we sent in every test
     * before this fix. Change to 1 if the strap is worn on the right wrist.
     */
    private static final int WRIST_ARG = 2;

    /*
     * Opcodes that must never be sent. Harmless in every earlier build ONLY
     * because unpadded frames were silently discarded; with pad4 fixed the
     * strap will now EXECUTE what it receives. 25 FORCE_TRIM, 32
     * POWER_CYCLE_STRAP, 36/37/38 firmware load, 45 ENTER_BLE_DFU (NOOP
     * docs/PROTOCOL.md destructive list), 96 ENTER_HIGH_FREQ_SYNC and 140
     * SET_ADVERTISING_NAME (NOOP EcgResearchAllowList FORBIDDEN census),
     * plus the undocumented opcodes our neighbour sweeps used to probe.
     */
    private static boolean isForbiddenOpcode(int op) {
        if (op == 25 || op == 32 || op == 36 || op == 37 || op == 38
                || op == 45 || op == 96 || op == 140) return true;
        if (op >= 129 && op <= 138) return true;
        if (op >= 142 && op <= 144) return true;
        return op >= 146;
    }

    /*
     * Send a command with an exact payload using the NOOP-exact builder
     * (no injected length byte, pad4). For bodyless commands, revision-only
     * bodies, and single-byte bodies like TOGGLE_REALTIME_HR.
     */
    private void sendPuffinPayload(int cmd, byte[] payload, String name) {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }
        if (isForbiddenOpcode(cmd)) {
            line("*** BLOCKED opcode " + cmd + " (" + name + ") - forbidden now that frames are delivered ***");
            logRaw("TX_BLOCKED_FORBIDDEN_OPCODE cmd=" + cmd + " name=" + name);
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.puffinFrame(0x23, cmd, payload, thisSeq);

            logRaw("TX name=" + name +
                    " cmd=0x" + String.format("%02X", cmd) +
                    " (puffin exact)" +
                    " seq=0x" + String.format("%02X", thisSeq & 0xff) +
                    " raw=" + Protocol.hex(f));

            line("");
            line("TX " + name);
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
         * R17 OPTICAL/LABRADOR FILTERED CHECK - a record type we've
         * NEVER once specifically looked for, found via an
         * independent project's own real Rust protocol source
         * (Goose). Their "packet_k" (their payload[1]) aligns
         * exactly with our own confirmed hist_version@9 (#845) -
         * meaning packet_k=17 corresponds to OUR absolute offset 9,
         * with flags@21, sample_count@32, samples@34+. Checked
         * directly on the layout-version byte, BEFORE length-based
         * dispatch, so a record of this type is never missed even if
         * its total length doesn't match one of our four known
         * shapes (124/88/188/1584).
         */
        if (value.length > 9 && (value[9] & 0xff) == 17) {

            int flags = value.length > 22 ?
                    ((value[21] & 0xff) | ((value[22] & 0xff) << 8)) : -1;
            boolean flagBit9 = (flags & (1 << 9)) != 0;
            boolean flagBit11 = (flags & (1 << 11)) != 0;

            int sampleCount = value.length > 33 ?
                    ((value[32] & 0xff) | ((value[33] & 0xff) << 8)) : -1;

            line("*** R17 OPTICAL/LABRADOR FILTERED RECORD DETECTED - " +
                    "a type we've NEVER specifically looked for before " +
                    "now (found via an independent project's real " +
                    "Rust source) - flags=0x" +
                    String.format("%04X", flags) +
                    " bit9=" + flagBit9 + " bit11=" + flagBit11 +
                    " sampleCount=" + sampleCount + " ***");

            logRaw("R17_OPTICAL_LABRADOR_FILTERED len=" + value.length +
                    " flags=0x" + String.format("%04X", flags) +
                    " bit9=" + flagBit9 + " bit11=" + flagBit11 +
                    " sampleCount=" + sampleCount +
                    " raw=" + Protocol.hex(value));
        }

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
             * "Layout v16" flash-banked ECG record. REBUILT to match
             * a far more precise, rigorously re-derived scheme from
             * a real primary-source report (ryanbr/noop#891,
             * ayiskakov, WS50_r00/50.39.1.0 - the closest hardware
             * match to this unit of anyone in the whole thread):
             * validated across 128 real frames, with an explicit
             * negative control against the #194 framing-artifact
             * problem, continuity checks across record boundaries,
             * and a correction posted for an earlier version of the
             * same analysis (a second tag class, not zero-fill).
             *
             * @11 record_index (u32 LE, monotonic, lockstep with
             *     v18/v20/v21/v26)
             * @15 unix (u32 LE, strap RTC seconds, one record/sec)
             * @32 word_count (u16 LE, number of 3-byte FIFO words)
             * @34 FIFO body, word_count x 3 bytes:
             *     byte0: tag - bits7-6 = 4-way class field
             *            (0x80=ECG, 0x00=a second, non-ECG channel,
             *            0x40/0xC0 rare/unexplained), bits1-0 =
             *            sample bits 17-16
             *     byte1: sample bits 15-8 (big-endian)
             *     byte2: sample bits 7-0
             *     sample = sign-extend the resulting 18-bit value
             *
             * Per that report's own hardware, this record has NEVER
             * once appeared in this unit's own captures across the
             * entire investigation (checked directly - every
             * historical pull to date has produced only 88/124/188-
             * byte records) - possibly a firmware difference
             * (50.39.1.0 vs this unit's 50.40.1.0), or possibly a
             * timing issue: that report's own data shows the
             * recording runs a full 64 seconds, well past our
             * typical listen windows, so a pull that happens too
             * soon after a combined attempt may simply precede the
             * record's completion.
             */
            int nonZero = 0;
            for (byte b : value) {
                if (b != 0) {
                    nonZero++;
                }
            }

            line("*** V16 FLASH ECG RECORD (len=1584) SEEN - " +
                    nonZero + "/" + value.length + " non-zero bytes " +
                    "- THIS HAS NEVER APPEARED IN ANY PAST CAPTURE ON " +
                    "THIS UNIT, INVESTIGATE IMMEDIATELY ***");

            if (value.length >= 36) {

                long recordIndex = (value[11] & 0xffL) |
                        ((value[12] & 0xffL) << 8) |
                        ((value[13] & 0xffL) << 16) |
                        ((value[14] & 0xffL) << 24);

                long unixTs = (value[15] & 0xffL) |
                        ((value[16] & 0xffL) << 8) |
                        ((value[17] & 0xffL) << 16) |
                        ((value[18] & 0xffL) << 24);

                int wordCount = (value[32] & 0xff) |
                        ((value[33] & 0xff) << 8);

                java.util.Date recordDate =
                        new java.util.Date(unixTs * 1000L);

                line(String.format(Locale.US,
                        "V16 STRUCTURE: recordIndex=%d unixTs=%d " +
                                "(%s) wordCount=%d",
                        recordIndex, unixTs, recordDate.toString(),
                        wordCount));

                /*
                 * Decode the FIFO body - word_count x 3-byte words,
                 * classified by the tag byte's top two bits. Only
                 * class 0x80 is confirmed ECG; others are tracked
                 * separately, never mixed into the ECG sample series
                 * (the exact mistake the primary source's own
                 * correction warned against).
                 */
                int fifoStart = 34;
                int ecgSamples = 0;
                int otherClassSamples = 0;  // count of samples with flag7 (contact/lead-state) set
                int fifoBytesAvailable =
                        Math.max(0, value.length - fifoStart);
                int wordsAvailable = Math.min(
                        wordCount, fifoBytesAvailable / 3);

                StringBuilder ecgPreview = new StringBuilder();

                for (int w = 0; w < wordsAvailable; w++) {

                    int base = fifoStart + w * 3;
                    int tag = value[base] & 0xff;
                    int b1 = value[base + 1] & 0xff;
                    int b2 = value[base + 2] & 0xff;

                    /*
                     * CORRECTED against docs/PROTOCOL_ECG.md: bits 7/6
                     * are two INDEPENDENT flags, not a combined 4-way
                     * "class" selector as this decoder previously
                     * assumed. flag7 comes from a SLOWER contact/lead-
                     * state stream (do not treat as a per-sample
                     * channel selector); flag6 is supplied per sample,
                     * meaning unresolved. Every sample is real ECG
                     * waveform data regardless of either flag's value -
                     * neither flag routes a sample to a "different
                     * channel". Bits 2-5 remain uninterpreted reserved
                     * bits.
                     */
                    boolean flag6 = ((tag >> 6) & 1) != 0;
                    boolean flag7 = ((tag >> 7) & 1) != 0;
                    int sampleHighBits = tag & 0x03;

                    int raw18 = (sampleHighBits << 16) |
                            (b1 << 8) | b2;

                    // sign two's-complement 18-bit: values >= 131072
                    // subtract 262144 (docs/PROTOCOL_ECG.md)
                    int sample = (raw18 >= 131072) ?
                            (raw18 - 262144) : raw18;

                    ecgSamples++;

                    if (flag7) {
                        otherClassSamples++;   // contact/lead-state set
                    }

                    if (ecgPreview.length() < 200) {
                        ecgPreview.append(sample).append(",");
                    }
                }

                line("V16 FIFO DECODE: " + wordsAvailable +
                        " total samples, flag7(contact/lead-state)Set=" +
                        otherClassSamples);

                if (ecgSamples > 0) {

                    line("*** V16 ECG SAMPLES DECODED - first values: " +
                            ecgPreview + " ***");

                    logRaw("V16_ECG_SAMPLES_DECODED recordIndex=" +
                            recordIndex + " unixTs=" + unixTs +
                            " ecgSamples=" + ecgSamples +
                            " preview=" + ecgPreview);
                }

                logRaw("V16_STRUCTURE recordIndex=" + recordIndex +
                        " unixTs=" + unixTs + " wordCount=" + wordCount +
                        " ecgSamples=" + ecgSamples +
                        " otherClassSamples=" + otherClassSamples);
            }

            logRaw("V16_FLASH_ECG_RECORD len=1584 nonZeroBytes=" +
                    nonZero + " raw=" + Protocol.hex(value));

        } else {

            /*
             * ENHANCED (2026-09-26) - matching what NOOP's own
             * upstream #927 fix added ("count the packet types a
             * history offload drops, instead of dropping them
             * silently"). R24, R25, R26 and PIP-R26 have never been
             * decoded anywhere by anyone - the firmware's own config-
             * key names (enable_write_r24_packets,
             * enable_write_r25_packets, disable_pip_r26_packets)
             * are known to exist, but no capture of what they
             * actually contain has ever been reported. If any of
             * these are already arriving in our own historical pulls,
             * the length-only catch-all below would have been
             * silently swallowing them into one undifferentiated
             * bucket - this pulls out the actual hist_version byte
             * (@9, the same field that identifies every other known
             * record type) and tallies it separately, so a genuinely
             * new record type stands out immediately rather than
             * blending into "unknown length=N" noise.
             */
            int unknownHistVersion = value.length > 9 ?
                    (value[9] & 0xff) : -1;

            Integer priorCount =
                    unknownRecordHistVersionTally.get(unknownHistVersion);
            int newCount = (priorCount == null ? 0 : priorCount) + 1;
            unknownRecordHistVersionTally.put(unknownHistVersion, newCount);

            String r24r25r26Flag = "";
            if (unknownHistVersion == 24) {
                r24r25r26Flag = " *** THIS IS R24 - NEVER DECODED " +
                        "BEFORE, BY ANYONE, ANYWHERE - SAVE THIS BIN " +
                        "FILE IMMEDIATELY ***";
            } else if (unknownHistVersion == 25) {
                r24r25r26Flag = " *** THIS IS R25 - NEVER DECODED " +
                        "BEFORE, BY ANYONE, ANYWHERE - SAVE THIS BIN " +
                        "FILE IMMEDIATELY ***";
            } else if (unknownHistVersion == 26) {
                r24r25r26Flag = " *** THIS IS R26/PIP-R26 - NEVER " +
                        "DECODED BEFORE, BY ANYONE, ANYWHERE - SAVE " +
                        "THIS BIN FILE IMMEDIATELY ***";
            }

            line("*** UNKNOWN HISTORICAL RECORD LENGTH=" + value.length +
                    " hist_version=" + unknownHistVersion +
                    " (seen " + newCount + "x this pull) - neither " +
                    "the known R22 (124), waveform-88 (88), nor " +
                    "waveform-188 (188) shape ***" + r24r25r26Flag);

            logRaw("UNRECOGNIZED_RECORD_SHAPE len=" + value.length +
                    " hist_version=" + unknownHistVersion +
                    " tallyThisVersion=" + newCount +
                    " raw=" + Protocol.hex(value));
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
         * CONFIRMED MEANING - this was "unidentified field113", now
         * resolved by a real, rigorous census (issue #845, ~18,650
         * real v18 records, cross-validated against shuffle/circular-
         * shift controls): a graded SIGNAL-QUALITY metric. Floors at
         * -5.2869 when quality is GOOD; moves toward 0 as quality
         * degrades, taking P(failed beat detection) from 18.3% (at
         * the floor) to 78.0% (worst end). This is genuinely useful
         * for us: during a touch/contact test, this field staying
         * near its floor is real, confirmed evidence of good signal
         * quality - not just "some mystery number that happens to
         * move".
         */
        Float field113 = null;

        if (value.length >= 117) {
            field113 = readFloatLE(value, 113);
            updateField113Display(field113);
        }

        /*
         * CONFIRMED companion field (issue #845) - a saturating 0-255
         * confidence score for the HR/RR detection pipeline. 255 =
         * maximum confidence; falls as quality degrades. Correlated
         * r=-0.80 with field113 (both track the SAME underlying
         * quality, from two independent bytes) - having both lets us
         * cross-check one against the other rather than trusting a
         * single number.
         */
        int hrConfidence = value.length > 40 ? (value[40] & 0xff) : -1;

        /*
         * CONFIRMED companion field (issue #845) - bit 0 is a real-
         * time motion-artifact flag (byte-identical to @81 bit0 in
         * the real census, 18,650/18,650 agreement). Set = motion is
         * currently degrading beat detection.
         */
        int hrQualityFlags = value.length > 33 ? (value[33] & 0xff) : -1;
        boolean motionArtifact = (hrQualityFlags & 0x01) != 0;

        line(String.format(
                "R22 DECODE: accel x=%.3f y=%.3f z=%.3f |v|=%.3f  " +
                        "HR=%d bpm  quality(field113)=%s  " +
                        "hrConfidence=%d/255  motionArtifact=%b",
                accelX, accelY, accelZ, mag, heartRate,
                field113 == null ? "n/a" : String.format("%.3f", field113),
                hrConfidence, motionArtifact));

        logRaw(String.format(
                "R22_DECODE accel_x=%.4f accel_y=%.4f accel_z=%.4f " +
                        "mag=%.4f hr=%d quality_field113=%s " +
                        "hr_confidence=%d motion_artifact=%b",
                accelX, accelY, accelZ, mag, heartRate,
                field113 == null ? "n/a" : String.format("%.4f", field113),
                hrConfidence, motionArtifact));

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

            /*
             * Explicit disclaimer line, so this file can never be
             * mistaken for R22, ADC, or ECG data by any tool or
             * person reading it later - a real confusion that
             * happened once already (an external tool relabeled this
             * exact data "R22 Channel 1 raw ADC"). This is the
             * waveform-188 historical record's confirmed real,
             * high-cardinality signal region (byte offset 26-175,
             * 75 x int16 LE per record) - genuine, non-garbage
             * signal, but its physiological meaning (if any) remains
             * UNIDENTIFIED. Not confirmed as accelerometer, PPG, or
             * ECG. Not the 124-byte R22 record's field113 either.
             */
            fw.write("# WAVEFORM-188 - confirmed real signal, " +
                    "UNIDENTIFIED meaning. NOT R22. NOT ADC. NOT " +
                    "confirmed ECG. Do not relabel without checking " +
                    "this file's own source comments first.\n");

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

        lastStatus31Counter = counter;
        lastStatus31Oscillating = oscillating;
        lastStatus31Tail = tail;

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
     * Builds a CURSOR_ACK arg directly from the most recent STATUS31
     * frame's own fields (counter + oscillating16, little-endian, per
     * the same 8-byte cursor shape the real ack uses) instead of
     * lastKnownCursor's console-text-derived value. Tests the
     * hypothesis directly rather than just logging a correlation.
     */
    private void sendCursorAckFromStatus31Fields() {

        if (lastStatus31Counter == null) {
            line("NO STATUS31 FRAME SEEN YET - cannot build ack from " +
                    "its fields");
            return;
        }

        byte[] arg = new byte[9];
        arg[0] = 0x01;
        arg[1] = (byte) (lastStatus31Counter & 0xFF);
        arg[2] = (byte) (lastStatus31Oscillating & 0xFF);
        arg[3] = (byte) ((lastStatus31Oscillating >> 8) & 0xFF);
        System.arraycopy(lastStatus31Tail, 0, arg, 4, 5);

        line("*** CURSOR_ACK BUILT FROM STATUS31's OWN FIELDS - " +
                "counter=" + lastStatus31Counter + " oscillating16=" +
                lastStatus31Oscillating + " (testing external " +
                "hypothesis that this, not the console-text cursor, " +
                "is what advances the transfer) ***");
        logRaw("CURSOR_ACK_FROM_STATUS31_FIELDS counter=" +
                lastStatus31Counter + " oscillating16=" +
                lastStatus31Oscillating);

        sendPullAckGenerationChecked(0x17, arg,
                "CURSOR_ACK (built from STATUS31's own fields, " +
                        "experimental)",
                pullGeneration);
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

        if (!unknownRecordHistVersionTally.isEmpty()) {

            StringBuilder tallyStr = new StringBuilder();
            for (java.util.Map.Entry<Integer, Integer> e :
                    unknownRecordHistVersionTally.entrySet()) {
                if (tallyStr.length() > 0) tallyStr.append(", ");
                tallyStr.append("hist_version=").append(e.getKey())
                        .append(" x").append(e.getValue());
            }

            line("");
            line("*** UNRECOGNIZED RECORD TYPES THIS PULL: " +
                    tallyStr + " - check the log for any flagged as " +
                    "R24/R25/R26 above ***");

            logRaw("UNKNOWN_RECORD_TALLY_THIS_PULL " + tallyStr);
        }

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
        unknownRecordHistVersionTally.clear();

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

            logRaw("CURSOR_ACK_CORRELATION sendingCursor=" +
                    Protocol.hex(lastKnownCursor) +
                    " lastStatus31Counter=" + lastStatus31Counter +
                    " lastStatus31Oscillating=" +
                    lastStatus31Oscillating);

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

    /*
     * CORRECTED against a real, complete 16-key enumeration (#761,
     * vishk23, real WHOOP 5 MG WS50_r03, via START_FF_KEY_EXCHANGE
     * 117 + SEND_NEXT_FF 118). We had only 10 of these 16 - missing
     * v4, disable_pip_r26_packets, wear_detect_bias, ir_hw_switching,
     * dorset_inhibit_wpt, and enable_sig12 entirely. This is now the
     * complete, confirmed list - no more, no less, per that real
     * hardware enumeration (which the strap's own count=16 confirms
     * is exhaustive, not a guess).
     */
    private static final String[] R22_FLAGS = {
        "enable_r22_packets",
        "enable_r22_v2_packets",
        "enable_r22_v3_packets",
        "enable_r22_v4_packets",
        "enable_r22_v5_packets",
        "enable_r22_v6_packets",
        "enable_r22_v8_packets",
        "make_hrfm_visible",
        "disable_pip_r26_packets",
        "wear_detect_bias",
        "hr_ch_switching",
        "ir_hw_switching",
        "enable_passive_strap_fit_gen5",
        "enable_sig11_during_sleep",
        "dorset_inhibit_wpt",
        "enable_sig12",
    };

    /*
     * CORRECTED per-flag values, confirmed from real HCI captures of
     * the ACTUAL OFFICIAL WHOOP APP itself - not a guess, not an
     * enumeration, the real app's own captured traffic. Two captures:
     * #103 (digitalerdude, history sync) established the baseline,
     * and #522 (a later, more specific live-workout capture) caught
     * and corrected a transcription error in #103's own read of
     * enable_sig12. We had been sending '1' for every flag this
     * entire investigation; the real app sends '2' for thirteen of
     * them, and '1' for three: enable_r22_v4_packets,
     * enable_passive_strap_fit_gen5, and enable_sig12 (corrected by
     * #522 from an earlier, wrong '2' - the more specific capture
     * wins over the first one where they conflict).
     */
    private char r22ValueFor(String flag) {
        if (flag.equals("enable_r22_v4_packets") ||
                flag.equals("enable_passive_strap_fit_gen5") ||
                flag.equals("enable_sig12")) {
            return '1';
        }
        return '2';
    }

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

    /*
     * ------------------------------------------------------------------
     * FEATURE-FLAG KEY-WALK ENUMERATION - opcodes 117/118, found via a
     * real merged PR (#917, "Separate the two enumeration terminators:
     * don't stop a key walk on validKey=0") and confirmed by a real,
     * complete enumeration on firmware 50.41.1.0 that found four
     * previously-unknown flag names (enable_r22_v9_packets,
     * enable_frizzle_burst_mode, ir_1x_enable, enable_rocky2) - keys
     * that could never have been found by guessing, only by walking.
     *
     * This is DISTINCT from the SET/GET_DEVICE_CONFIG_VALUE(119/121)
     * pair we already use for named-key read/write - 117/118 walk
     * EVERY valid key sequentially, without needing to know its name
     * in advance. Exact byte format is NOT confirmed from any source
     * we've read - only the opcode numbers and the "SEND_NEXT walk,
     * validKey terminator" concept are real. Implemented as a
     * reasonable first attempt (incrementing index argument, by
     * symmetry with how most other opcodes take a small integer arg)
     * - the actual reply format, if any, will reveal itself via the
     * generic COMMAND_RESPONSE catch already in place, the same way
     * we've reverse-engineered other things from real bytes rather
     * than guessing the decode up front.
     * ------------------------------------------------------------------
     */
    /*
     * ------------------------------------------------------------------
     * FEATURE-FLAG KEY-WALK - CORRECTED to match the real, confirmed
     * protocol from a merged PR (#917), not inference. Real examples
     * from that PR:
     *   START_FF_KEY_EXCHANGE(117) -> revision=1 count=2 raw=01 02 00
     *   SEND_NEXT_FF(118) -> index=0 validKey=true
     *       key="enable_r22_packets" raw=01 00 01 65 6e 61 62...
     *   SEND_NEXT_FF(118) -> index=255 validKey=false
     *       raw=01 ff 00 00 00 00 00  (end/empty marker)
     *
     * Two real corrections from our first attempt: 117 is called ONCE
     * to start the exchange, not repeated - and 118 is called
     * repeatedly with NO index argument at all, since the strap
     * tracks its own cursor internally between calls. Reply shape:
     * [const=0x01][index][validKey][key name ASCII if valid].
     *
     * Also newly confirmed: 115/116 is a SEPARATE, parallel walk pair
     * for the device-config namespace (the 119/121 family) - a whole
     * second enumerable space we didn't know existed until this PR.
     * ------------------------------------------------------------------
     */
    private void sendStartFfKeyExchange() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labrador(0x23, 117, 1, thisSeq);

            logRaw("TX START_FF_KEY_EXCHANGE raw=" + Protocol.hex(f));
            line("TX START_FF_KEY_EXCHANGE (cmd=117, starts the walk - " +
                    "call once, then SEND_NEXT repeatedly)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(START_FF_KEY_EXCHANGE)");
                opDone();
            }
        });
    }

    private void sendNextFf() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labrador(0x23, 118, 1, thisSeq);

            logRaw("TX SEND_NEXT_FF raw=" + Protocol.hex(f));
            line("TX SEND_NEXT_FF (cmd=118, no index - strap tracks " +
                    "its own cursor)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected (SEND_NEXT_FF)");
                opDone();
            }
        });
    }

    /*
     * Parallel walk for the DEVICE-CONFIG namespace (115/116) -
     * confirmed as a separate, real pair from the same #917 PR
     * ("namespace-parameterised... deviceConfigNamespace = 115/116").
     * Same reply shape as 117/118, different opcode pair.
     */
    private void sendStartDeviceConfigKeyExchange() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labrador(0x23, 115, 1, thisSeq);

            logRaw("TX START_DEVICE_CONFIG_KEY_EXCHANGE raw=" +
                    Protocol.hex(f));
            line("TX START_DEVICE_CONFIG_KEY_EXCHANGE (cmd=115, " +
                    "starts the device-config namespace walk)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(START_DEVICE_CONFIG_KEY_EXCHANGE)");
                opDone();
            }
        });
    }

    private void sendNextDeviceConfig() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED");
            return;
        }

        final int thisSeq = seq++;

        enqueue(() -> {

            byte[] f = Protocol.labrador(0x23, 116, 1, thisSeq);

            logRaw("TX SEND_NEXT_DEVICE_CONFIG raw=" + Protocol.hex(f));
            line("TX SEND_NEXT_DEVICE_CONFIG (cmd=116, no index)");

            cmdWrite.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            cmdWrite.setValue(f);

            if (!gatt.writeCharacteristic(cmdWrite)) {
                line("writeCharacteristic() rejected " +
                        "(SEND_NEXT_DEVICE_CONFIG)");
                opDone();
            }
        });
    }

    private void sweepDeviceConfigKeyWalk() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot walk device-config keys");
            return;
        }

        line("");
        line("*** DEVICE-CONFIG KEY-WALK (cmd=115 once, then cmd=116 " +
                "repeatedly) - the second, parallel namespace from " +
                "#917. This is the same family enable_raw_data_w_ecg " +
                "lives in (119/121) ***");
        logRaw("DEVICE_CONFIG_KEY_WALK_BEGIN");

        sendStartDeviceConfigKeyExchange();

        int steps = 25;

        for (int i = 0; i < steps; i++) {

            int stepNum = i;
            long delayMs = 800L * (i + 1);

            mainH.postDelayed(() -> {
                line("--- SEND_NEXT_DEVICE_CONFIG step " + stepNum +
                        " ---");
                sendNextDeviceConfig();
            }, delayMs);
        }

        long afterMs = 800L * (steps + 1) + 500;

        mainH.postDelayed(() ->
                logRaw("DEVICE_CONFIG_KEY_WALK_COMPLETE"), afterMs);
    }

    private void sweepFeatureFlagKeyWalk() {

        if (gatt == null || cmdWrite == null) {
            line("NOT CONNECTED - cannot walk feature-flag keys");
            return;
        }

        line("");
        line("*** FEATURE-FLAG KEY-WALK (cmd=117 once, then cmd=118 " +
                "repeatedly) - real protocol confirmed from #917, not " +
                "inferred. Watching for real key names in the replies ***");
        logRaw("FEATURE_FLAG_KEY_WALK_BEGIN");

        sendStartFfKeyExchange();

        int steps = 25;

        for (int i = 0; i < steps; i++) {

            int stepNum = i;
            long delayMs = 800L * (i + 1);

            mainH.postDelayed(() -> {
                line("--- SEND_NEXT_FF step " + stepNum + " ---");
                sendNextFf();
            }, delayMs);
        }

        long afterMs = 800L * (steps + 1) + 500;

        mainH.postDelayed(() ->
                logRaw("FEATURE_FLAG_KEY_WALK_COMPLETE"), afterMs);
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
        line("*** OFFICIAL FLAG BURST: GET_ADVERTISING_NAME -> all " +
                R22_FLAGS.length + " feature flags at the official " +
                "app's own values - STRAP MUST BE WORN ***");
        logRaw("R22_UNLOCK_BEGIN flags=" + R22_FLAGS.length);

        sendGetAdvertisingName();

        for (int i = 0; i < R22_FLAGS.length; i++) {

            String flag = R22_FLAGS[i];
            long delayMs = 80L * (i + 1);

            mainH.postDelayed(
                    () -> sendR22Flag(flag, r22ValueFor(flag)), delayMs);
        }

        /*
         * Read-back (26 Sep): the strap's own 117/118 walk confirmed
         * these 16 names ARE its complete feature-flag list on
         * 50.40.1.0, in this exact order - so this burst is the whole
         * namespace, and restores every flag to the official app's
         * value. disable_pip_r26_packets had persisted at '0' (our
         * own pre-correction write on 20 Sep) instead of the official
         * '2'; read it back after the burst to confirm the restore.
         */
        long afterBurstMs = 80L * (R22_FLAGS.length + 2) + 400;
        mainH.postDelayed(
                () -> getFeatureFlagValue("disable_pip_r26_packets"),
                afterBurstMs);
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
     * CORRECTED against docs/PROTOCOL_ECG.md, sourced directly from the
     * real official app's own compiled handlers (ayiskakov/noop) - the
     * most authoritative layout we have, superseding the earlier,
     * upstream-community-derived guess this decoder used before:
     *
     *   @8      packet type (43 live / 47 historical)
     *   @9      layout selector (16 raw / 17 filtered)
     *   @10     common sensor flags, meaning unresolved
     *   @11-14  shared record sequence, u32 LE
     *   @15-18  timestamp main word, u32 LE
     *   @19-20  additional timestamp word, u16 LE
     *   @21     quality code (0-3, vocabulary unresolved)
     *   @22     state/presence bits: bit0=entered from state!=1,
     *           bit1=state==1, bit2=state2 follows state1, bit3=presence
     *   @23     classifier result code
     *   @24     classifier state code
     *   @25     progress (percentage-like; 255 = strap's "no session")
     *   @26     4 independent booleans, bits 0-3, names unresolved
     *   @27     HR-related classifier value
     *   @28     additional HR value for R17 (zero placeholder on R16)
     *   @29-30  HRV-related value, u16 LE, units unresolved
     *   @31     zero placeholder, not a stress value
     *   @32-33  declared waveform sample count, u16 LE
     *   @34-233 100 x i16 LE sample slots - THE ACTUAL WAVEFORM
     *   @234-235 two zero alignment bytes - NEVER a sample (this
     *            decoder previously read 101 slots here, mistakenly
     *            treating the alignment pair as a real sample - fixed)
     *   @236-239 CRC32 trailer over [8,236)
     *
     * We have never actually received a type=43 frame this whole
     * investigation, so this remains unverified against our own real
     * data - wired in now so the moment one arrives, it's decoded
     * properly instead of just hex-dumped.
     * ------------------------------------------------------------------
     */
    private void decodeRealtimeEcg240(byte[] v) {

        if (v.length != 240) {

            line("*** type=43 frame len=" + v.length +
                    " (expected 240 per docs/PROTOCOL_ECG.md) - " +
                    "structure below assumes 240, treat with caution ***");

            logRaw("REALTIME_ECG_UNEXPECTED_LENGTH len=" + v.length +
                    " raw=" + Protocol.hex(v));

            return;
        }

        int quality = v[21] & 0xff;
        int stateBits = v[22] & 0xff;
        boolean presence = (stateBits & 0x08) != 0;
        boolean stateIsOne = (stateBits & 0x02) != 0;
        boolean state2FollowsState1 = (stateBits & 0x04) != 0;
        int classifierState = v[24] & 0xff;
        int progressRaw = v[25] & 0xff;
        Integer progress = (progressRaw == 255) ? null : progressRaw;
        int declaredCount = (v[32] & 0xff) | ((v[33] & 0xff) << 8);
        int usableCount = Math.min(declaredCount, 100);

        /*
         * Real, frame-level "establishing" status for the ECG screen -
         * from a real, confirmed finding (ayiskakov, ryanbr/noop#891,
         * 23 Sep): the classifier state turns to 2 on exactly the
         * record where progress reaches 100, every time, in every
         * recording checked. That's a more direct, reliable
         * "recording established" signal than progress alone, since
         * it's a discrete state flip rather than a number to
         * interpret. No-ops if the ECG screen isn't open.
         *
         * Posted to the main thread for the same reason as the
         * sample feed above - a plain TextView update often appears
         * to tolerate a background thread, but it isn't guaranteed,
         * and this makes it genuinely correct rather than "happens
         * to work".
         */
        if (presence && ecgUiHandler != null) {
            final int qualityForUi = quality;
            final Integer progressForUi = progress;
            final int classifierStateForUi = classifierState;
            final int declaredCountForUi = declaredCount;
            ecgUiHandler.post(() -> feedEcgFrameStatus(
                    qualityForUi, progressForUi, classifierStateForUi,
                    declaredCountForUi));
        }

        int[] samples = new int[usableCount];

        for (int i = 0; i < usableCount; i++) {

            int lo = v[34 + i * 2] & 0xff;
            int hi = v[35 + i * 2];              // signed on purpose

            samples[i] = (hi << 8) | lo;
        }

        /*
         * FIXED - real threading bug found by direct on-screen
         * observation: connectGatt() here is never given a Handler,
         * so Android runs onCharacteristicChanged (and everything it
         * calls, including this whole decode function) on a
         * background binder thread, not the main thread. Simple
         * TextView updates often appear to work anyway - which is why
         * the status line correctly showed "Recording established" -
         * but ValueAnimator (used for the idle-sweep animation) must
         * be touched only from the thread that created it. Canceling
         * it from a background thread was silently failing, so
         * liveMode never flipped and the idle sweep kept running
         * forever even with real data genuinely arriving underneath
         * it. Batches the whole frame's samples into one post to the
         * main thread, rather than one post per sample.
         */
        if (presence && ecgUiHandler != null) {
            final int[] samplesForUi = samples;
            ecgUiHandler.post(() -> {
                for (int s : samplesForUi) {
                    feedEcgLiveSample(s);
                }
            });
        }

        line(String.format(Locale.US,
                "*** REAL ECG WAVEFORM FRAME - quality=%d presence=%b " +
                        "stateIsOne=%b state2FollowsState1=%b " +
                        "progress=%s declaredCount=%d (capacity 100) ***",
                quality, presence, stateIsOne, state2FollowsState1,
                progress == null ? "none(255=no session)" :
                        String.valueOf(progress),
                declaredCount));

        if (usableCount > 0) {

            int min = samples[0];
            int max = samples[0];
            long sum = 0;

            for (int s : samples) {
                if (s < min) min = s;
                if (s > max) max = s;
                sum += s;
            }

            double mean = sum / (double) usableCount;

            line(String.format(Locale.US,
                    "*** %d samples decoded: min=%d max=%d pp=%d " +
                            "mean=%.1f ***",
                    usableCount, min, max, max - min, mean));
        }

        StringBuilder sampleStr = new StringBuilder();

        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                sampleStr.append(',');
            }
            sampleStr.append(samples[i]);
        }

        logRaw("REALTIME_ECG_DECODED quality=" + quality +
                " presence=" + presence +
                " progress=" + (progress == null ? "none" : progress) +
                " declaredCount=" + declaredCount +
                " samples=" + sampleStr.toString());
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

        if (opcode == 0x7C && arg == 2) {
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

        if (isForbiddenOpcode(opcode)) {
            line("*** BLOCKED opcode " + opcode + " (" + name + ") - " +
                    "forbidden now that frames are correctly padded and " +
                    "the strap will actually execute them ***");
            logRaw("TX_BLOCKED_FORBIDDEN_OPCODE cmd=" + opcode +
                    " name=" + name);
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

    private void subscribeServiceChangedIfPresent(BluetoothGatt g) {

        BluetoothGattService gatt1801 = g.getService(gattService);

        if (gatt1801 == null) {
            return;
        }

        BluetoothGattCharacteristic c =
                gatt1801.getCharacteristic(serviceChangedChar);

        if (c == null) {
            return;
        }

        line("Generic Attribute Service Changed found - subscribing " +
                "(never watched before this point)");
        subscribe(g, c);
    }

    private void readBatteryLevelIfPresent(BluetoothGatt g) {

        BluetoothGattService batt = g.getService(battService);

        if (batt == null) {
            return;
        }

        BluetoothGattCharacteristic c =
                batt.getCharacteristic(battLevelChar);

        if (c == null) {
            return;
        }

        line("Battery Service found - reading level explicitly " +
                "(never read before this point, only subscribed)");
        queueRead(g, c);
    }

    /*
     * Re-reads battery level every 20s for the life of the connection,
     * not just once at connect - low probability of reacting to touch,
     * but a single reading can't show a differential at all. Piggybacks
     * on the same rssiMonitoringActive flag rather than adding a second
     * one, since both should run for exactly the same lifetime.
     */
    private void schedulePeriodicBatteryRead(BluetoothGatt g) {

        if (!rssiMonitoringActive) {
            return;
        }

        readBatteryLevelIfPresent(g);

        mainH.postDelayed(() -> {
            if (gatt != null && rssiMonitoringActive) {
                schedulePeriodicBatteryRead(gatt);
            }
        }, 20000);
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

    /*
     * ------------------------------------------------------------------
     * NEW ECG SCREEN - a real, polished capture view: a live-sweeping
     * waveform, a live HR readout from a lightweight real-time beat
     * detector, elapsed time, and a single clear start/stop control.
     * Wired directly to the exact, proven sequence confirmed working
     * on hardware: prepare (SELECT_WRIST -> 139 -> 125) -> START
     * (ABORT_HISTORICAL(20) -> 124=2), and a clean three-step stop
     * (124=1 -> 139=0 -> 125=0) - nothing here is a new, untested
     * command sequence, only new presentation around it.
     * ------------------------------------------------------------------
     */
    private FrameLayout buildEcgScreen() {

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF0A0E14);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(28, 40, 28, 28);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Button backBtn = new Button(this);
        backBtn.setText("< BACK");
        backBtn.setTextSize(13);
        backBtn.setBackgroundColor(0xFF1A2230);
        backBtn.setTextColor(0xFFB8C4D9);
        backBtn.setOnClickListener(v -> showEcgScreen(false));
        topRow.addView(backBtn,
                new LinearLayout.LayoutParams(-2, -2));

        TextView heading = new TextView(this);
        heading.setText("  ECG");
        heading.setTextSize(22);
        heading.setTextColor(0xFFEAF2FF);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headingLp =
                new LinearLayout.LayoutParams(0, -2, 1f);
        headingLp.gravity = Gravity.CENTER_VERTICAL;
        topRow.addView(heading, headingLp);

        col.addView(topRow,
                new LinearLayout.LayoutParams(-1, -2));

        Space sp1 = new Space(this);
        col.addView(sp1, new LinearLayout.LayoutParams(-1, 24));

        // status / instruction line
        ecgStatusText = new TextView(this);
        ecgStatusText.setText(
                "Two fingers on the metal clasp, then tap Start");
        ecgStatusText.setTextSize(14);
        ecgStatusText.setTextColor(0xFF8FA1BD);
        col.addView(ecgStatusText,
                new LinearLayout.LayoutParams(-1, -2));

        Space sp2 = new Space(this);
        col.addView(sp2, new LinearLayout.LayoutParams(-1, 18));

        // the live waveform, in a nicely framed card
        LinearLayout waveformCard = new LinearLayout(this);
        waveformCard.setOrientation(LinearLayout.VERTICAL);
        waveformCard.setBackgroundColor(0xFF0A0E14);
        waveformCard.setPadding(4, 4, 4, 4);

        ecgWaveformView = new EcgWaveformView(this);
        waveformCard.addView(ecgWaveformView,
                new LinearLayout.LayoutParams(-1, 520));

        col.addView(waveformCard,
                new LinearLayout.LayoutParams(-1, -2));

        Space sp3 = new Space(this);
        col.addView(sp3, new LinearLayout.LayoutParams(-1, 24));

        // live HR + elapsed time row
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout hrBlock = new LinearLayout(this);
        hrBlock.setOrientation(LinearLayout.VERTICAL);
        hrBlock.setGravity(Gravity.CENTER_HORIZONTAL);

        ecgHrText = new TextView(this);
        ecgHrText.setText("--");
        ecgHrText.setTextSize(42);
        ecgHrText.setTextColor(0xFF39FF6A);
        ecgHrText.setTypeface(null, android.graphics.Typeface.BOLD);
        ecgHrText.setGravity(Gravity.CENTER);
        hrBlock.addView(ecgHrText,
                new LinearLayout.LayoutParams(-2, -2));

        TextView hrLabel = new TextView(this);
        hrLabel.setText("BPM (live estimate)");
        hrLabel.setTextSize(11);
        hrLabel.setTextColor(0xFF5A6B85);
        hrLabel.setGravity(Gravity.CENTER);
        hrBlock.addView(hrLabel,
                new LinearLayout.LayoutParams(-2, -2));

        statsRow.addView(hrBlock,
                new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout timeBlock = new LinearLayout(this);
        timeBlock.setOrientation(LinearLayout.VERTICAL);
        timeBlock.setGravity(Gravity.CENTER_HORIZONTAL);

        ecgElapsedText = new TextView(this);
        ecgElapsedText.setText("0:00");
        ecgElapsedText.setTextSize(42);
        ecgElapsedText.setTextColor(0xFFEAF2FF);
        ecgElapsedText.setTypeface(null, android.graphics.Typeface.BOLD);
        ecgElapsedText.setGravity(Gravity.CENTER);
        timeBlock.addView(ecgElapsedText,
                new LinearLayout.LayoutParams(-2, -2));

        TextView timeLabel = new TextView(this);
        timeLabel.setText("ELAPSED");
        timeLabel.setTextSize(11);
        timeLabel.setTextColor(0xFF5A6B85);
        timeLabel.setGravity(Gravity.CENTER);
        timeBlock.addView(timeLabel,
                new LinearLayout.LayoutParams(-2, -2));

        statsRow.addView(timeBlock,
                new LinearLayout.LayoutParams(0, -2, 1f));

        col.addView(statsRow,
                new LinearLayout.LayoutParams(-1, -2));

        Space sp4 = new Space(this);
        col.addView(sp4, new LinearLayout.LayoutParams(-1, 32));

        // the single start/stop control
        ecgStartStopButton = new Button(this);
        ecgStartStopButton.setText("START ECG");
        ecgStartStopButton.setTextSize(17);
        ecgStartStopButton.setTypeface(null, android.graphics.Typeface.BOLD);
        ecgStartStopButton.setBackgroundColor(0xFF39FF6A);
        ecgStartStopButton.setTextColor(0xFF0A0E14);
        ecgStartStopButton.setPadding(0, 36, 0, 36);
        ecgStartStopButton.setOnClickListener(v -> onEcgStartStopTapped());
        col.addView(ecgStartStopButton,
                new LinearLayout.LayoutParams(-1, -2));

        Space sp5 = new Space(this);
        col.addView(sp5, new LinearLayout.LayoutParams(-1, 14));

        /*
         * Rhythm-regularity result - shown only after a session ends
         * with enough clean beats. A real Poincaré-plot analysis
         * (SD1/SD2), the same general technique behind Apple Watch's
         * own published AFib-detection method, computed here as an
         * honest, descriptive, non-diagnostic personal-project result
         * rather than a validated clinical classification.
         */
        ecgRhythmResultText = new TextView(this);
        ecgRhythmResultText.setText("");
        ecgRhythmResultText.setTextSize(13);
        ecgRhythmResultText.setTextColor(0xFFB8C4D9);
        ecgRhythmResultText.setGravity(Gravity.CENTER);
        ecgRhythmResultText.setLineSpacing(6, 1f);
        LinearLayout.LayoutParams rhythmLp =
                new LinearLayout.LayoutParams(-1, -2);
        rhythmLp.topMargin = 20;
        rhythmLp.bottomMargin = 20;
        col.addView(ecgRhythmResultText, rhythmLp);

        TextView disclaimer = new TextView(this);
        disclaimer.setText(
                "Research instrumentation, not a medical device. Not " +
                        "a diagnosis. Does not detect any heart " +
                        "condition.");
        disclaimer.setTextSize(11);
        disclaimer.setTextColor(0xFF4A5568);
        disclaimer.setGravity(Gravity.CENTER);
        col.addView(disclaimer,
                new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(col);
        container.addView(scroller,
                new FrameLayout.LayoutParams(-1, -1));

        return container;
    }

    /** Shows or hides the new ECG screen over the existing debug UI. */
    private void showEcgScreen(boolean show) {
        ecgScreenActive = show;
        ecgScreenContainer.setVisibility(
                show ? View.VISIBLE : View.GONE);
        if (!show && ecgSessionRunning) {
            // leaving mid-session doesn't stop the strap, just the
            // screen - the underlying send/listen logic is unaffected,
            // matching how every other test button in this app behaves
        }

        /*
         * Keep the screen awake for the duration of the ECG screen -
         * a real, confirmed cause of session fragmentation (this
         * project's own last session: contact lost reaching to
         * unlock the phone mid-hold). Only applied while this screen
         * is the one showing, and cleared the moment it's left, so
         * the rest of the app is unaffected.
         */
        if (show) {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams
                            .FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams
                            .FLAG_KEEP_SCREEN_ON);
        }
    }

    private void onEcgStartStopTapped() {

        if (gatt == null || cmdWrite == null) {
            ecgStatusText.setText(
                    "Not connected - tap SCAN/CONNECT first, then " +
                            "come back here");
            return;
        }

        if (!ecgSessionRunning) {
            startEcgScreenSession();
        } else {
            stopEcgScreenSession();
        }
    }

    private void startEcgScreenSession() {

        ecgSessionRunning = true;
        ecgSessionEstablishedThisRun = false;
        ecgWaveformView.reset();
        ecgLiveBeatIntervalsMs.clear();
        ecgFullSessionIntervalsMs.clear();
        ecgFrameArrivalLog.clear();
        ecgRhythmResultText.setText("");
        ecgLastPeakSampleIndex = -1;
        ecgCurrentQualityRunFrames = 0;
        ecgSampleCounter = 0;
        ecgHrText.setText("--");
        ecgElapsedText.setTextColor(0xFFEAF2FF);
        ecgSessionStartMs = System.currentTimeMillis();

        ecgStartStopButton.setText("STOP");
        ecgStartStopButton.setBackgroundColor(0xFFFF5555);
        ecgStartStopButton.setTextColor(0xFFFFFFFF);
        ecgStatusText.setText(
                "Recording - keep fingers on the clasp");

        // exactly the confirmed-working sequence, unchanged
        runRealSequenceWithAbortHistorical();

        /*
         * 10-minute auto-stop awareness - a real, confirmed strap
         * behavior (ayiskakov, ryanbr/noop#891, 23 Sep): "the strap
         * stops a session itself at 10 minutes... the console logged
         * SENSORS: ECG active timeout." Not something this app
         * controls or can prevent - just makes it visible rather than
         * looking like an unexplained stop if it happens. Amber past
         * 9:00, and a plain note once past 10:00 rather than letting a
         * silent stop look like a bug.
         */
        ecgElapsedTicker = () -> {
            if (!ecgSessionRunning) {
                return;
            }
            long elapsedS = (System.currentTimeMillis() - ecgSessionStartMs) / 1000;
            ecgElapsedText.setText(
                    String.format(Locale.US, "%d:%02d",
                            elapsedS / 60, elapsedS % 60));

            if (elapsedS >= 600) {
                ecgElapsedText.setTextColor(0xFFFF5555);
                if (elapsedS == 600) {
                    ecgStatusText.setText(
                            "Past 10:00 - the strap itself stops " +
                                    "sessions here, so this may end " +
                                    "on its own shortly");
                }
            } else if (elapsedS >= 540) {
                ecgElapsedText.setTextColor(0xFFFFC947);
            }

            ecgUiHandler.postDelayed(ecgElapsedTicker, 250);
        };
        ecgUiHandler.post(ecgElapsedTicker);
    }

    private void stopEcgScreenSession() {

        ecgSessionRunning = false;
        if (ecgElapsedTicker != null) {
            ecgUiHandler.removeCallbacks(ecgElapsedTicker);
        }

        // clean three-step stop, matching the confirmed sequence:
        // generation stop, then both toggles off
        send(0x7C, 1, "MAIN_CONTROL_ECG_DATA_GENERATION_STOP (ECG screen)");
        mainH.postDelayed(() ->
                send(0x8B, 0, "TOGGLE_LABRADOR_FILTERED_OFF (ECG screen)"),
                400);
        mainH.postDelayed(() ->
                send(0x7D, 0, "TOGGLE_LABRADOR_RAW_SAVE_OFF (ECG screen)"),
                800);

        ecgStartStopButton.setText("START ECG");
        ecgStartStopButton.setBackgroundColor(0xFF39FF6A);
        ecgStartStopButton.setTextColor(0xFF0A0E14);

        int total = ecgWaveformView.getTotalSamplesReceived();
        if (total > 0) {
            ecgStatusText.setText(
                    total + " real samples captured this session");
        } else {
            ecgStatusText.setText(
                    "No live data arrived - check contact and try again");
        }

        runRhythmRegularityAnalysis();
        measureR17SampleRate();
    }

    /*
     * ------------------------------------------------------------------
     * DIRECT R17 RATE MEASUREMENT - the exact method used to first
     * confirm this rate on this project's own captured data: isolates
     * the longest run of CONSECUTIVE frames each with the full
     * declaredCount==100, then divides the samples in that run
     * (excluding the last frame's own count, since the measured span
     * only covers up to that frame's arrival, not its full duration)
     * by the real wall-clock span between the run's first and last
     * frame. This is a genuine measurement, not the widely-cited but
     * explicitly unmeasured 100 Hz assumption (docs/PROTOCOL_ECG.md
     * and ayiskakov's own ryanbr/noop#891 both state outright that
     * this rate "is not measured").
     * ------------------------------------------------------------------
     */
    private void measureR17SampleRate() {

        if (ecgFrameArrivalLog.size() < 10) {
            return;
        }

        java.util.List<long[]> bestRun = new java.util.ArrayList<>();
        java.util.List<long[]> currentRun = new java.util.ArrayList<>();

        for (long[] entry : ecgFrameArrivalLog) {
            if (entry[1] == 100) {
                currentRun.add(entry);
            } else {
                if (currentRun.size() > bestRun.size()) {
                    bestRun = currentRun;
                }
                currentRun = new java.util.ArrayList<>();
            }
        }
        if (currentRun.size() > bestRun.size()) {
            bestRun = currentRun;
        }

        if (bestRun.size() < 5) {
            logRaw("R17_RATE_MEASUREMENT insufficient consecutive " +
                    "full-count frames this session (best run=" +
                    bestRun.size() + ")");
            return;
        }

        long spanMs = bestRun.get(bestRun.size() - 1)[0] -
                bestRun.get(0)[0];
        long samplesExclLast = 100L * (bestRun.size() - 1);
        double measuredHz = samplesExclLast / (spanMs / 1000.0);

        line("");
        line(String.format(Locale.US,
                "*** R17 RATE MEASURED THIS SESSION: %.2f Hz " +
                        "(%d consecutive full frames, %.1fs span) - " +
                        "a genuine measurement, not the previously " +
                        "unmeasured 100 Hz assumption ***",
                measuredHz, bestRun.size(), spanMs / 1000.0));

        logRaw(String.format(Locale.US,
                "R17_RATE_MEASURED hz=%.3f frames=%d spanMs=%d",
                measuredHz, bestRun.size(), spanMs));
    }

    /*
     * ------------------------------------------------------------------
     * Rhythm-regularity analysis - a real, well-established statistical
     * method (Poincare-plot SD1/SD2, plus pRR-style successive-
     * difference counting), the same general category of technique
     * behind Apple Watch's own published AFib-detection approach. This
     * is NOT a clinical implementation and makes NO diagnostic claim -
     * it describes, in plain language, how regular or irregular this
     * session's beat timing was, exactly the same restraint NOOP's own
     * RhythmScreen uses ("NOT an ECG... cannot diagnose, detect, or
     * rule out any heart condition"). Runs only on quality-3,
     * confirmed-clean beats (ecgFullSessionIntervalsMs), never on the
     * noisier quality-1/2 data that produced misleading numbers
     * earlier tonight.
     * ------------------------------------------------------------------
     */
    private void runRhythmRegularityAnalysis() {

        java.util.List<Integer> rr = ecgFullSessionIntervalsMs;

        if (rr.size() < 15) {
            ecgRhythmResultText.setText(
                    rr.isEmpty() ?
                            "" :
                            "Not enough clean (quality 3/3) beats this " +
                                    "session for a rhythm read-out (" +
                                    rr.size() + " collected, 15+ needed)");
            return;
        }

        int n = rr.size();

        // successive differences: RR[i+1] - RR[i]
        double[] diffs = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            diffs[i] = rr.get(i + 1) - rr.get(i);
        }

        double meanRr = 0;
        for (int v : rr) meanRr += v;
        meanRr /= n;

        double varRr = 0;
        for (int v : rr) varRr += (v - meanRr) * (v - meanRr);
        varRr /= (n - 1);

        double meanDiff = 0;
        for (double d : diffs) meanDiff += d;
        meanDiff /= diffs.length;

        double varDiff = 0;
        for (double d : diffs) varDiff += (d - meanDiff) * (d - meanDiff);
        varDiff /= (diffs.length - 1);

        // Standard Poincare-plot formulas:
        // SD1 = short-term (beat-to-beat) variability
        // SD2 = longer-term variability
        double sd1 = Math.sqrt(0.5 * varDiff);
        double sd2Sq = (2 * varRr) - (0.5 * varDiff);
        double sd2 = sd2Sq > 0 ? Math.sqrt(sd2Sq) : 0;

        double sd1Sd2Ratio = (sd2 > 1) ? (sd1 / sd2) : 0;

        // pRR50-style: share of successive differences over 50ms -
        // a simple, widely-used irregularity indicator on its own
        int over50 = 0;
        for (double d : diffs) {
            if (Math.abs(d) > 50) over50++;
        }
        double pOver50 = 100.0 * over50 / diffs.length;

        // Plain-language classification, deliberately coarse and
        // descriptive rather than a precise clinical threshold - the
        // cut points below are a reasonable personal-project reading
        // of the literature's general pattern (a Poincare plot that's
        // notably rounder / less cigar-shaped, and a higher share of
        // large successive differences, is the pattern associated with
        // irregular rhythms including AFib), not a validated cutoff.
        String label;
        String color;
        if (sd1Sd2Ratio < 0.35 && pOver50 < 15) {
            label = "Regular";
            color = "#39FF6A";
        } else if (sd1Sd2Ratio < 0.6 && pOver50 < 35) {
            label = "Some irregularity";
            color = "#FFC947";
        } else {
            label = "Notably irregular";
            color = "#FF5555";
        }

        /*
         * SHANNON ENTROPY - a second, independent irregularity
         * measure, using the actual technique behind Apple Watch's
         * own published AFib method (the Apple Heart Study
         * methodology bins R-R intervals and measures how spread out/
         * unpredictable that distribution is), rather than continuing
         * to rely on Poincare SD1/SD2 alone. Two independent methods
         * agreeing is a meaningfully stronger signal than either one
         * alone - the same cross-validation principle already used
         * for the heart-rate figure itself earlier in this project.
         *
         * 50ms bins, matching pNN50's own granularity. Entropy
         * normalized to 0-1 by dividing by log2(bin count), so the
         * result doesn't depend on how many bins this particular
         * session's range happened to produce.
         */
        int minRr = rr.get(0), maxRr = rr.get(0);
        for (int v : rr) {
            if (v < minRr) minRr = v;
            if (v > maxRr) maxRr = v;
        }
        int binWidthMs = 50;
        int numBins = Math.max(1, (maxRr - minRr) / binWidthMs + 1);
        int[] bins = new int[numBins];
        for (int v : rr) {
            int idx = (v - minRr) / binWidthMs;
            if (idx >= numBins) idx = numBins - 1;
            bins[idx]++;
        }

        double shannonEntropy = 0;
        for (int count : bins) {
            if (count == 0) continue;
            double p = count / (double) n;
            shannonEntropy -= p * (Math.log(p) / Math.log(2));
        }
        double maxPossibleEntropy = numBins > 1 ?
                (Math.log(numBins) / Math.log(2)) : 1;
        double normalizedEntropy = maxPossibleEntropy > 0 ?
                shannonEntropy / maxPossibleEntropy : 0;

        // same three-band scheme as the Poincare result, for a
        // directly comparable label - reasoned bands, not a
        // validated clinical cutoff, same caveat as above
        String entropyLabel;
        if (normalizedEntropy < 0.5) {
            entropyLabel = "Regular";
        } else if (normalizedEntropy < 0.75) {
            entropyLabel = "Some irregularity";
        } else {
            entropyLabel = "Notably irregular";
        }

        boolean methodsAgree = entropyLabel.equals(label);

        String result = String.format(Locale.US,
                "<b><font color='%s'>%s</font></b><br>" +
                        "%d clean beats analysed &middot; SD1/SD2 " +
                        "ratio %.2f &middot; %.0f%% of beat-to-beat " +
                        "changes over 50ms<br>" +
                        "<small>A Poincar\u00e9-plot read of beat " +
                        "timing regularity - the same general " +
                        "technique behind Apple Watch's own AFib " +
                        "feature, but not clinically validated here. " +
                        "\"Notably irregular\" describes the pattern " +
                        "that can occur with AFib among other causes " +
                        "(including motion, poor contact, or normal " +
                        "sinus arrhythmia) - it is not a diagnosis." +
                        "</small><br><br>" +
                        "<b>Shannon entropy (independent check): %s</b> " +
                        "(normalised %.2f)<br>" +
                        "<small>Bins beat-to-beat timing and measures " +
                        "how spread out the distribution is - the " +
                        "actual method behind Apple's own published " +
                        "AFib approach. %s</small>",
                color, label, n, sd1Sd2Ratio, pOver50,
                entropyLabel, normalizedEntropy,
                methodsAgree ?
                        "Agrees with the Poincar\u00e9 result above." :
                        "Disagrees with the Poincar\u00e9 result above " +
                                "- worth treating this session's " +
                                "reading with more caution than one " +
                                "where both methods agree.");

        ecgRhythmResultText.setText(
                android.text.Html.fromHtml(
                        result, android.text.Html.FROM_HTML_MODE_LEGACY));

        logRaw("RHYTHM_ANALYSIS beats=" + n +
                " sd1=" + String.format(Locale.US, "%.1f", sd1) +
                " sd2=" + String.format(Locale.US, "%.1f", sd2) +
                " ratio=" + String.format(Locale.US, "%.3f", sd1Sd2Ratio) +
                " pOver50=" + String.format(Locale.US, "%.1f", pOver50) +
                " label=" + label +
                " shannonEntropy=" + String.format(Locale.US, "%.3f",
                        normalizedEntropy) +
                " entropyLabel=" + entropyLabel +
                " methodsAgree=" + methodsAgree);
    }

    /**
     * True once classifierState has hit 2 this session - per
     * ayiskakov's confirmed finding (ryanbr/noop#891, 23 Sep), this
     * flips on exactly the record where progress reaches 100, every
     * time, and never flips back. Used so the status line doesn't
     * flicker between "establishing" and "established" if progress
     * itself briefly dips.
     */
    private boolean ecgSessionEstablishedThisRun = false;

    /*
     * Latest quality code (0-3), updated once per frame by
     * feedEcgFrameStatus - used to gate the live BPM readout below,
     * since a real, confirmed finding (ayiskakov, ryanbr/noop#891,
     * 23 Sep) showed clean complexes appear only in quality-3 records;
     * quality-0/1 stretches produce beat trains unrelated to the
     * strap's own optical HR (mains interference, open-circuit
     * artifacts). The waveform itself still always shows the real,
     * raw data regardless of quality - only the derived BPM number is
     * gated, so the number shown is never misleadingly confident.
     */
    private int ecgLatestQualityForGate = 0;

    /*
     * Full-session record of beat-to-beat intervals, quality-gated
     * (only recorded while quality==3, same principle as the live BPM
     * gate) - kept separately from the short rolling window used for
     * the live BPM number, since a genuine rhythm-regularity analysis
     * needs the whole session's beats, not just the last few.
     */
    private final java.util.List<Integer> ecgFullSessionIntervalsMs =
            new java.util.ArrayList<>();
    private TextView ecgRhythmResultText;

    /**
     * Updates the ECG screen's status line from real, frame-level
     * fields (quality, progress, classifierState) - called once per
     * decoded type=43 frame while presence is confirmed, not once per
     * sample. No-ops if the ECG screen isn't open or no session is
     * running, so it never overwrites the idle "tap Start" prompt or
     * the post-session summary.
     */
    /*
     * Tracks how many consecutive frames the current quality run has
     * held, and resets the beat-interval tracker at every quality
     * transition - fixing a real gap found by independent offline
     * verification: an interval was previously gated only by the
     * quality at its SECOND peak, so a peak detected during a noisy
     * stretch could still be paired with a later, clean-quality peak
     * and counted as a "quality-3" interval, when only one of its two
     * endpoints genuinely was. Requiring 3 full seconds of sustained
     * quality=3 before counting anything from a run matches
     * ayiskakov's own stated, proven threshold exactly
     * (ryanbr/noop#891: "quality-3 runs of at least 3 s").
     */
    private int ecgCurrentQualityRunFrames = 0;
    private static final int ECG_MIN_QUALITY3_RUN_FRAMES = 3;

    /*
     * Real, per-frame arrival log for this session - (arrival
     * timestamp ms, declaredCount) pairs, used to directly MEASURE
     * the live R17 rate rather than continuing to assume the
     * long-cited but explicitly unmeasured 100 Hz figure
     * (docs/PROTOCOL_ECG.md and ayiskakov's own ryanbr/noop#891 both
     * state outright: "not measured"). Reset each session; the rate
     * is computed at stop from the longest run of consecutive,
     * fully-declared (declaredCount==100) frames, isolating genuine
     * steady-state throughput from the settling/gap periods that
     * would otherwise dilute a simple whole-session average.
     */
    private final java.util.List<long[]> ecgFrameArrivalLog =
            new java.util.ArrayList<>();

    private void feedEcgFrameStatus(
            int quality, Integer progress, int classifierState,
            int declaredCount) {

        if (ecgScreenContainer == null || !ecgScreenActive
                || !ecgSessionRunning) {
            return;
        }

        ecgFrameArrivalLog.add(new long[]{
                System.currentTimeMillis(), declaredCount});

        if (quality != ecgLatestQualityForGate) {
            // a genuine quality transition - never let a beat interval
            // span across it, and restart this run's own duration count
            ecgLastPeakSampleIndex = -1;
            ecgCurrentQualityRunFrames = 0;
        }
        ecgCurrentQualityRunFrames++;

        ecgLatestQualityForGate = quality;

        if (classifierState == 2) {
            ecgSessionEstablishedThisRun = true;
        }

        if (ecgSessionEstablishedThisRun) {
            ecgStatusText.setText(
                    "Recording established \u2713  (quality " +
                            quality + "/3)");
            return;
        }

        String progressStr = (progress == null) ?
                "starting" : (progress + "%");

        ecgStatusText.setText(
                "Establishing... " + progressStr +
                        "  (quality " + quality + "/3) - hold contact");
    }

    /*
     * A lightweight, real-time beat detector for the live HR readout
     * only - intentionally simple, favoring responsiveness on screen
     * over the rigor of the full offline analysis already done on
     * this project's captured data. Uses a rolling median/MAD-style
     * threshold, updated as samples arrive, with a 300ms refractory
     * period matching normal physiological limits.
     */
    private final java.util.List<Integer> ecgRecentAbsForThreshold =
            new java.util.ArrayList<>();

    private void feedEcgLiveSample(int rawSample) {

        if (ecgScreenContainer == null || !ecgScreenActive) {
            return;
        }

        ecgWaveformView.addSample(rawSample);
        ecgSampleCounter++;

        int absVal = Math.abs(rawSample);
        ecgRecentAbsForThreshold.add(absVal);
        if (ecgRecentAbsForThreshold.size() > 300) {
            ecgRecentAbsForThreshold.remove(0);
        }

        if (ecgRecentAbsForThreshold.size() < 40) {
            return;
        }

        java.util.List<Integer> sorted =
                new java.util.ArrayList<>(ecgRecentAbsForThreshold);
        java.util.Collections.sort(sorted);
        int median = sorted.get(sorted.size() / 2);
        int threshold = median * 4 + 300;

        int refractorySamples = 30; // 300ms at 100Hz

        if (absVal > threshold &&
                (ecgLastPeakSampleIndex < 0 ||
                        ecgSampleCounter - ecgLastPeakSampleIndex
                                >= refractorySamples)) {

            if (ecgLastPeakSampleIndex >= 0) {

                int deltaSamples =
                        ecgSampleCounter - ecgLastPeakSampleIndex;
                int intervalMs = deltaSamples * 10; // 100Hz

                if (intervalMs >= 300 && intervalMs <= 2000) {

                    ecgLiveBeatIntervalsMs.add(intervalMs);
                    if (ecgLiveBeatIntervalsMs.size() > 6) {
                        ecgLiveBeatIntervalsMs.remove(0);
                    }

                    if (ecgLatestQualityForGate == 3 &&
                            ecgCurrentQualityRunFrames >=
                                    ECG_MIN_QUALITY3_RUN_FRAMES) {
                        ecgFullSessionIntervalsMs.add(intervalMs);
                    }

                    if (ecgLiveBeatIntervalsMs.size() >= 3) {

                        if (ecgLatestQualityForGate == 3) {

                            long sum = 0;
                            for (int iv : ecgLiveBeatIntervalsMs) sum += iv;
                            double avgMs = sum /
                                    (double) ecgLiveBeatIntervalsMs.size();
                            int bpm = (int) Math.round(60000.0 / avgMs);
                            if (bpm >= 30 && bpm <= 220) {
                                ecgHrText.setText(String.valueOf(bpm));
                                ecgHrText.setTextColor(0xFF39FF6A);
                            }

                        } else {

                            /*
                             * Below quality 3, the strap's own signal
                             * isn't clean enough for a trustworthy
                             * rate yet (per ayiskakov's confirmed
                             * finding) - show that honestly rather
                             * than a number that may just be mains
                             * interference or contact artifact.
                             */
                            ecgHrText.setText("--");
                            ecgHrText.setTextColor(0xFF5A6B85);
                        }
                    }
                }
            }
            ecgLastPeakSampleIndex = ecgSampleCounter;
        }
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
         * New, polished entry point to the ECG screen - placed first,
         * above every debug/probe control, since this is now the
         * actual product-facing feature, not just another test.
         */
        Button openEcgScreenBtn = new Button(this);
        openEcgScreenBtn.setText("\u2764  ECG");
        openEcgScreenBtn.setTextSize(18);
        openEcgScreenBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        openEcgScreenBtn.setBackgroundColor(0xFF39FF6A);
        openEcgScreenBtn.setTextColor(0xFF0A0E14);
        openEcgScreenBtn.setPadding(0, 30, 0, 30);
        openEcgScreenBtn.setOnClickListener(v -> showEcgScreen(true));

        LinearLayout.LayoutParams ecgBtnLp =
                new LinearLayout.LayoutParams(-1, -2);
        ecgBtnLp.topMargin = 8;
        ecgBtnLp.bottomMargin = 24;
        root.addView(openEcgScreenBtn, ecgBtnLp);

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

        lastFfValueDisplay = new TextView(this);
        lastFfValueDisplay.setText("GET_FF_VALUE: (no reply yet)");
        lastFfValueDisplay.setTextSize(16);
        lastFfValueDisplay.setTextColor(0xFF7FFF7F);
        lastFfValueDisplay.setTypeface(null, android.graphics.Typeface.BOLD);
        lastFfValueDisplay.setPadding(0, 0, 0, 16);

        root.addView(
                lastFfValueDisplay,
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

        /*
         * Touch markers placed right at the top, outside the
         * scrollable grid below, so they're always one tap away
         * during a live contact test - no hunting through buttons
         * at the exact moment contact starts or stops.
         */
        LinearLayout markerRow = new LinearLayout(this);
        markerRow.setOrientation(LinearLayout.HORIZONTAL);

        Button touchStartBtn = btn("MARK TOUCH START", v -> markTouchStart());
        Button touchEndBtn = btn("MARK TOUCH END", v -> markTouchEnd());

        markerRow.addView(touchStartBtn,
                new LinearLayout.LayoutParams(0, -2, 1f));
        markerRow.addView(touchEndBtn,
                new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(markerRow,
                new LinearLayout.LayoutParams(-1, -2));

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

        addSectionHeader(controls, "ECG ATTEMPT SEQUENCES " +
                "(full runs - wear + touch)", 0xFFFF6B6B);

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
        /*
         * Pruned (2026-09-26): BANK-TO-FLASH TEST and REAL DOCUMENTED
         * SEQUENCE ORDER were both genuine, well-motivated attempts
         * from earlier in the investigation, before the real, working
         * sequence was confirmed. Now that REAL SEQUENCE WITH
         * ABORT_HISTORICAL below is confirmed to produce real data on
         * hardware, these two no longer add anything the confirmed
         * sequence and its retry variant don't already cover - kept
         * out to reduce clutter, not because either was wrong.
         *
         * REAL SEQUENCE WITH ABORT_HISTORICAL - the single most
         * important new finding of the entire investigation. Found
         * in a completely independent, previously-unknown project
         * (OpenStrap/edge), citing the real official app's own
         * compiled logic directly. Opcode 20 has never been sent
         * before now. Placed at the absolute top.
         */
        Button abortHistoricalBtn = btn(
                "REAL SEQUENCE WITH ABORT_HISTORICAL (opcode 20, NEVER " +
                        "SENT BEFORE - from an independent project citing " +
                        "the real app's own logic) - TOUCH NOW",
                v -> runRealSequenceWithAbortHistorical());
        addToCurrentSection(abortHistoricalBtn);

        /*
         * Pruned (2026-09-26): TWO-COMMAND TURN-ON (139 then 124, no
         * 125) was a genuine, precisely-sourced test in its own
         * right, but the confirmed-working sequence above already
         * includes 125 and is proven on real hardware - this variant
         * no longer adds a distinct, needed test.
         *
         * REAL APP EXACT FLOW WITH RETRY - genuinely important
         * discovery from an independent iOS decompile (Goose
         * project's own research): a real, confirmed string,
         * "failure_after_three_attempts", from the actual official
         * app's own LabradorReadingStrapInteractor.swift. The real
         * app expects up to 3 attempts before giving up - we've only
         * ever tried once, every single test this whole
         * investigation. Placed at the very top given how directly
         * it's sourced and how significant a gap it fills.
         */
        Button realAppFlowRetryBtn = btn(
                "REAL APP EXACT FLOW WITH RETRY (up to 3 attempts, " +
                        "matching the real app's own confirmed " +
                        "\"failure_after_three_attempts\" behavior)",
                v -> runRealAppExactFlowWithRetry());
        addToCurrentSection(realAppFlowRetryBtn);

        /*
         * Pruned (2026-09-26): REAL APP EXACT FLOW (two-stage, no
         * retry), FULL COMBINED, CONTACT-FIRST, CLEAN-SLATE and
         * ULTIMATE were all genuine, well-motivated tests from earlier
         * in the investigation, before the real, working sequence and
         * its retry variant were confirmed on hardware. None adds a
         * distinct, still-needed test now - kept out to reduce
         * clutter, not because any was wrong. The underlying
         * functions are left in place, unused, in case any is ever
         * worth revisiting.
         */

        addSectionHeader(controls, "OPCODE & ARGUMENT PROBES " +
                "(quick, standalone)", 0xFFFFD166);

        /*
         * R24/R25/R26/PIP-R26 CONFIG-KEY PROBE - never decoded by
         * anyone, anywhere. Placed first in this section given how
         * genuinely unexplored this territory is.
         */
        Button r24r25r26Btn = btn(
                "PROBE R24/R25/R26/PIP-R26 CONFIG KEYS (never decoded " +
                        "by anyone - via the proven SET/GET(119/121) " +
                        "mechanism)",
                v -> runR24R25R26ConfigProbe());
        addToCurrentSection(r24r25r26Btn);

        /*
         * Same three names, tried through the OTHER mechanism -
         * SET_CONFIG(0x78/120), the one actually confirmed to echo
         * real flags this whole investigation (enable_raw_data_w_ecg
         * and every real R22 flag). 119/121 giving silence doesn't
         * rule these names out; this checks the mechanism with an
         * actual track record.
         */
        Button r24r25r26ViaSetConfigBtn = btn(
                "PROBE R24/R25/R26 VIA FF NAMESPACE (read 128 -> write " +
                        "R24/R25 via 120 -> read back; R26 read-only)",
                v -> runR24R25R26ViaConfirmedMechanism());
        addToCurrentSection(r24r25r26ViaSetConfigBtn);

        Button probeArgBtn = btn(
                "PROBE UNDOCUMENTED cmd=0x7C arg=3",
                v -> probeUndocumentedEcgArg());
        addToCurrentSection(probeArgBtn);

        Button highFreqSyncBtn = btn(
                "PROBE ENTER_HIGH_FREQ_SYNC (cmd=96) - maintainer's own " +
                        "named next step for the v16 flash mystery (#1100)",
                v -> probeEnterHighFreqSync());
        addToCurrentSection(highFreqSyncBtn);

        Button exitHighFreqSyncBtn = btn(
                "EXIT_HIGH_FREQ_SYNC (cmd=97) - safety counterpart, use " +
                        "after ENTER to back out cleanly",
                v -> probeExitHighFreqSync());
        addToCurrentSection(exitHighFreqSyncBtn);

        Button bodyLocationBtn = btn(
                "PROBE GET_BODY_LOCATION_AND_STATUS (cmd=84) - real, " +
                        "confirmed wear/location probe, never implemented " +
                        "before now (#690)",
                v -> probeBodyLocationAndStatus());
        addToCurrentSection(bodyLocationBtn);

        Button extBatteryBtn = btn(
                "PROBE GET_EXTENDED_BATTERY_INFO (cmd=98) - same resolved " +
                        "family as cmd=96, never sent before now (#592)",
                v -> probeExtendedBatteryInfo());
        addToCurrentSection(extBatteryBtn);

        Button suggestedSeqBtn = btn(
                "TEST SUGGESTED SEQUENCE (flags->probe->wait->1 start) - " +
                        "WEAR+TOUCH",
                v -> runSuggestedSequenceTest());
        addToCurrentSection(suggestedSeqBtn);

        Button wristValueTestBtn = btn(
                "TEST BOTH SELECT_WRIST VALUES (0 vs 1)",
                v -> testBothWristValues());
        addToCurrentSection(wristValueTestBtn);

        Button neighboringOpcodeSweepBtn = btn(
                "SWEEP NEIGHBORING OPCODES (maybe ECG moved on this fw)",
                v -> sweepNeighboringOpcodes());
        addToCurrentSection(neighboringOpcodeSweepBtn);

        Button highRangeOpcodeSweepBtn = btn(
                "SWEEP HIGH-RANGE OPCODES 148-160 (confirmed remap zone)",
                v -> sweepHighRangeOpcodes());
        addToCurrentSection(highRangeOpcodeSweepBtn);

        addSectionHeader(controls, "R22 / HISTORICAL PULL", 0xFF06D6A0);

        /*
         * R22 unlock - still useful on its own for historical/motion
         * data specifically, confirmed three independent ways in
         * NOOP's own docs.
         */
        Button r22Btn = btn(
                "SEND OFFICIAL 16-FLAG BURST (restores all feature " +
                        "flags to the official app's values - WEAR STRAP)",
                v -> sendR22UnlockPartial());
        addToCurrentSection(r22Btn);

        /*
         * Real historical pull moved directly below R22 UNLOCK -
         * these two are now the actual recommended test sequence
         * (unlock, then pull), so keeping them adjacent means
         * reaching the second one never needs any scrolling.
         */
        Button realPullBtn = btn(
                "REAL HISTORICAL PULL (SET_CLOCK...SEND_HIST_DATA)",
                v -> startRealHistoricalPull());
        addToCurrentSection(realPullBtn);

        Button stopPullBtn = btn(
                "STOP PULL ACK LOOP",
                v -> stopPullAckLoop());
        addToCurrentSection(stopPullBtn);

        Button cursorFromStatus31Btn = btn(
                "SEND ONE CURSOR_ACK FROM STATUS31's OWN FIELDS " +
                        "(experimental)",
                v -> sendCursorAckFromStatus31Fields());
        addToCurrentSection(cursorFromStatus31Btn);

        addSectionHeader(controls, "ECG GATE DIAGNOSTICS", 0xFF7FDBFF);

        /*
         * ECG gate controls - earlier, less fruitful experimentation
         * than the R22/historical-pull pair above, so no longer
         * given top billing, but kept available.
         */
        Button ecgGateStartBtn = btn(
                "ENABLE ECG GATE + START",
                v -> enableEcgGateThenStart());
        addToCurrentSection(ecgGateStartBtn);

        Button ecgGateSetGetBtn = btn(
                "SET ECG GATE (confirmed-working mechanism)",
                v -> sendR22Flag("enable_raw_data_w_ecg", '1'));
        addToCurrentSection(ecgGateSetGetBtn);

        Button ecgGateRealReadBackBtn = btn(
                "GET REAL STORED VALUE (GET_FF_VALUE 128, never sent " +
                        "before now)",
                v -> getFeatureFlagValue("enable_raw_data_w_ecg"));
        addToCurrentSection(ecgGateRealReadBackBtn);

        Button ffCalibrationBtn = btn(
                "CALIBRATE GET_FF_VALUE via enable_sig12 (known-working " +
                        "on real hardware, #423/#103)",
                v -> runFeatureFlagCalibrationTest());
        addToCurrentSection(ffCalibrationBtn);

        Button stagedDisableProbeBtn = btn(
                "STAGED R22 DISABLE: PROBE enable_sig12 with '0' first " +
                        "(is '0' even valid in this namespace?)",
                v -> runStagedR22DisableProbe());
        addToCurrentSection(stagedDisableProbeBtn);

        Button confirmClearRemainingBtn = btn(
                "CONFIRM PROBE PASSED - CLEAR REMAINING 15 (only tap " +
                        "if GET_FF_VALUE above confirmed the change)",
                v -> confirmProbePassedClearRemaining());
        addToCurrentSection(confirmClearRemainingBtn);

        Button featureFlagWalkBtn = btn(
                "LIST STRAP'S FEATURE FLAGS (117/118, read-only - " +
                        "strap names its own keys)",
                v -> runStrapKeyList(true));
        addToCurrentSection(featureFlagWalkBtn);

        Button deviceConfigWalkBtn = btn(
                "LIST STRAP'S DEVICE-CONFIG KEYS (115/116, read-only)",
                v -> runStrapKeyList(false));
        addToCurrentSection(deviceConfigWalkBtn);

        Button readAllDeviceConfigBtn = btn(
                "READ ALL 7 DEVICE-CONFIG VALUES (121, read-only - " +
                        "nothing written)",
                v -> readAllDeviceConfigValues());
        addToCurrentSection(readAllDeviceConfigBtn);

        Button keyWalksWithPreconditionBtn = btn(
                "KEY-WALKS WITH REAL APP PRECONDITION (DISABLE_ALARM+" +
                        "HR first, then BOTH walks - never combined " +
                        "before now)",
                v -> runKeyWalksWithRealAppPrecondition());
        addToCurrentSection(keyWalksWithPreconditionBtn);

        Button deviceConfigDuringPullBtn = btn(
                "DEVICE_CONFIG EXCHANGE DURING ACTIVE PULL (matches " +
                        "real app's successful timing)",
                v -> runDeviceConfigDuringActivePull());
        addToCurrentSection(deviceConfigDuringPullBtn);

        Button realAppSeqBtn = btn(
                "REPLICATE REAL APP SEQUENCE (DISABLE_ALARM + " +
                        "TOGGLE_REALTIME_HR first, then device-config)",
                v -> runRealAppSequenceReplication());
        addToCurrentSection(realAppSeqBtn);

        Button ecgGateValueTestBtn = btn(
                "TEST ECG GATE VALUE: raw 0x01 vs ASCII '1'",
                v -> testEcgGateValueConvention());
        addToCurrentSection(ecgGateValueTestBtn);

        Button ecgFlagGuessSweepBtn = btn(
                "SWEEP SPECULATIVE ECG FLAG NAMES (unconfirmed guesses)",
                v -> sweepEcgFlagGuesses());
        addToCurrentSection(ecgFlagGuessSweepBtn);

        Button ecgGateViaRealMechBtn = btn(
                "TEST enable_raw_data_w_ecg VIA REAL FLAG MECHANISM (0x78)",
                v -> testEcgGateViaRealFlagMechanism());
        addToCurrentSection(ecgGateViaRealMechBtn);

        addSectionHeader(controls, "MANUAL / GENERIC TOOLS", 0xFFB388EB);

        EditText configKeyInput = new EditText(this);
        configKeyInput.setHint("device config key, e.g. enable_raw_data_w_ecg");
        configKeyInput.setText("enable_raw_data_w_ecg");
        configKeyInput.setSingleLine(true);
        configKeyInput.setTextColor(0xFFFFFFFF);
        configKeyInput.setHintTextColor(0xFF888888);
        addToCurrentSection(configKeyInput);

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
        addToCurrentSection(getConfigValueBtn);

        /*
         * Sweep and GATT dump promoted to the top of this panel -
         * these are the highest-priority tools right now, worth
         * seeing immediately rather than after scrolling past
         * everything else.
         */
        Button gattDumpBtn = btn(
                "DUMP ALL GATT SERVICES",
                v -> manualGattDump());
        addToCurrentSection(gattDumpBtn);

        Button startSweepBtn = btn(
                "GET CONFIG VALUE SWEEP (0x79, key 0x00-0x1F)",
                v -> startCmdSweep());
        addToCurrentSection(startSweepBtn);

        Button stopSweepBtn = btn(
                "STOP SWEEP",
                v -> stopCmdSweep());
        addToCurrentSection(stopSweepBtn);

        Button c5 =
                btn(
                        "0x3F SPO2 STREAM ON",
                        v -> send(
                                0x3F,
                                1,
                                "SPO2_ON"));

        addToCurrentSection(c5);

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
        addToCurrentSection(experimentIntervalInput);

        autoPullCheckbox = new CheckBox(this);
        autoPullCheckbox.setText(
                "Auto-PULL (0x2F 01 00) after 3rd completion");
        autoPullCheckbox.setTextColor(0xFFDDDDDD);
        autoPullCheckbox.setOnCheckedChangeListener(
                (btn2, checked) ->
                        autoPullAfterExperiment = checked);
        addToCurrentSection(autoPullCheckbox);

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

        addToCurrentSection(pull);

        customInput =
                new EditText(this);

        customInput.setHint(
                "type cmd arg hex, " +
                        "e.g. 2F 01 00");

        customInput.setSingleLine(true);
        customInput.setTextColor(0xFFFFFFFF);
        customInput.setHintTextColor(0xFF888888);

        addToCurrentSection(customInput);

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

        addToCurrentSection(sendCustomBtn);

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

        addToCurrentSection(clockInput);

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

        addToCurrentSection(sendClockBtn);

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

        FrameLayout outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(-1, -1));

        ecgScreenContainer = buildEcgScreen();
        ecgScreenContainer.setVisibility(View.GONE);
        outer.addView(ecgScreenContainer,
                new FrameLayout.LayoutParams(-1, -1));

        setContentView(outer);
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
     * Collapsible, color-coded section system - replaces the plain
     * text dividers. Each section header is now tappable: toggles a
     * dedicated body container's visibility, so a category can be
     * hidden entirely while working in another. Buttons/fields still
     * route through addToCurrentSection() exactly like they used to
     * add directly to the flat controls list - same order, same
     * parent chain, just wrapped one level deeper so it can collapse.
     */
    private LinearLayout currentSectionContainer;

    private void addSectionHeader(LinearLayout parent, String title,
            int accentColor) {

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(8, 36, 8, 12);
        headerRow.setBackgroundColor(0xFF1A1A1A);

        TextView arrow = new TextView(this);
        arrow.setText("▼");
        arrow.setTextColor(accentColor);
        arrow.setTextSize(14);
        arrow.setPadding(0, 0, 16, 0);

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(accentColor);
        header.setTextSize(14);
        header.setTypeface(null, android.graphics.Typeface.BOLD);

        headerRow.addView(arrow);
        headerRow.addView(header);
        parent.addView(headerRow,
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout sectionBody = new LinearLayout(this);
        sectionBody.setOrientation(LinearLayout.VERTICAL);
        sectionBody.setBackgroundColor(0xFF141414);
        sectionBody.setPadding(4, 4, 4, 12);
        parent.addView(sectionBody,
                new LinearLayout.LayoutParams(-1, -2));

        headerRow.setOnClickListener(v -> {
            boolean nowVisible = sectionBody.getVisibility() != android.view.View.VISIBLE;
            sectionBody.setVisibility(
                    nowVisible ? android.view.View.VISIBLE
                            : android.view.View.GONE);
            arrow.setText(nowVisible ? "▼" : "▶");
        });

        currentSectionContainer = sectionBody;
    }

    /*
     * Drop-in replacement for the old controls.addView(v) calls -
     * routes into whichever section container is currently active
     * instead of the flat top-level list, so the collapse/expand
     * behavior applies automatically to every button and field
     * already written against that call shape. Every call site is
     * confirmed to come after the first addSectionHeader call, so
     * currentSectionContainer is always set by the time this runs.
     */
    private void addToCurrentSection(android.view.View v) {
        currentSectionContainer.addView(v);
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

                    /*
                     * Distinguishing three outcomes explicitly, per a
                     * real bug found and fixed in #1646: createBond()
                     * needs BLUETOOTH_CONNECT, and an uncaught/
                     * swallowed SecurityException was being
                     * misreported as "the stack declined to pair" -
                     * a confident claim about the strap for what was
                     * actually a local permission problem. These are
                     * genuinely different findings and share none of
                     * their meaning.
                     */
                    boolean started;

                    try {

                        started = d.createBond();
                        logRaw("CREATE_BOND_CALLED result=" + started);

                    } catch (SecurityException se) {

                        line("createBond() THREW SecurityException - " +
                                "missing BLUETOOTH_CONNECT permission, " +
                                "a LOCAL problem, not the strap " +
                                "declining anything");
                        logRaw("CREATE_BOND_THREW_SECURITY_EXCEPTION " +
                                "msg=" + se.getMessage());

                        pendingDevice = null;
                        return;
                    }

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

    /*
     * Always-visible readout for the most recent GET_FF_VALUE_REPLY -
     * timestamped so it's obvious at a glance whether this is a fresh
     * reply or a stale one left over from before the current test
     * started (e.g. if a probe genuinely got no reply this time, this
     * display should NOT be quietly showing an old confirmation from
     * an earlier session).
     */
    private void updateLastFfValueDisplay(String key, String storedValue) {

        String timestamp = new java.text.SimpleDateFormat(
                "HH:mm:ss.SSS", Locale.US).format(new Date());

        String text = String.format(Locale.US,
                "GET_FF_VALUE: \"%s\" = \"%s\"  @ %s",
                key, storedValue, timestamp);

        runOnUiThread(() -> {
            if (lastFfValueDisplay != null) {
                lastFfValueDisplay.setText(text);
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
