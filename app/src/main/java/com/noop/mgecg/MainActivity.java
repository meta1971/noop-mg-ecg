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

                /*
                 * Do NOT automatically fire another experimental
                 * command here.
                 *
                 * For Step 1 we want a clean capture so that we
                 * know exactly what the WHOOP sends before/after
                 * the pull.
                 */
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
     * Protocol transmission
     * ------------------------------------------------------------------
     */

    private void send(
            int opcode,
            int arg,
            String name) {

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
                24,
                24,
                24,
                24);

        TextView title =
                new TextView(this);

        title.setText(
                "NOOP MG ECG Experimental\n" +
                "WHOOP 5/MG Labrador capture");

        title.setTextSize(20);

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        -1,
                        -2));

        scanBtn =
                new Button(this);

        scanBtn.setText(
                "SCAN FOR WHOOP 5/MG");

        scanBtn.setOnClickListener(
                v -> scan());

        root.addView(scanBtn);

        Button c1 =
                btn(
                        "0x7B SELECT_WRIST " +
                                "(RIGHT=0)",
                        v -> send(
                                0x7B,
                                0,
                                "SELECT_WRIST"));

        root.addView(c1);

        Button c4 =
                btn(
                        "0x8B FILTERED ON",
                        v -> send(
                                0x8B,
                                1,
                                "FILTERED_ON"));

        root.addView(c4);

        Button c3 =
                btn(
                        "0x7D RAW SAVE ON",
                        v -> send(
                                0x7D,
                                1,
                                "RAW_SAVE_ON"));

        root.addView(c3);

        Button c2 =
                btn(
                        "0x7C LABRADOR " +
                                "GENERATION START",
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

        root.addView(c2);

        Button c5 =
                btn(
                        "0x3F SPO2 STREAM ON",
                        v -> send(
                                0x3F,
                                1,
                                "SPO2_ON"));

        root.addView(c5);

        Button stop =
                btn(
                        "0x7C LABRADOR STOP",
                        v -> {

                            labradorActive = false;

                            line("");
                            line("*** LABRADOR STOP ***");

                            send(
                                    0x7C,
                                    0,
                                    "LABRADOR_STOP");
                        });

        root.addView(stop);

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

        root.addView(pull);

        customInput =
                new EditText(this);

        customInput.setHint(
                "type cmd arg hex, " +
                        "e.g. 2F 01 00");

        customInput.setSingleLine(true);

        root.addView(customInput);

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

        root.addView(sendCustomBtn);

        /*
         * Fast-iteration SET_CLOCK guess: type + cmd only,
         * current Unix time is filled in automatically as the
         * 4-byte argument.
         */
        clockInput = new EditText(this);

        clockInput.setHint(
                "type cmd hex for clock guess, e.g. 23 2C");

        clockInput.setSingleLine(true);

        root.addView(clockInput);

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

        root.addView(sendClockBtn);

        log =
                new TextView(this);

        log.setTextIsSelectable(true);
        log.setTextSize(11);

        scrollView =
                new ScrollView(this);

        scrollView.addView(log);

        root.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1));

        setContentView(root);
    }

    private Button btn(
            String text,
            View.OnClickListener listener) {

        Button b =
                new Button(this);

        b.setText(text);
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
