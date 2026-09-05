package com.noop.mgecg;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.app.Activity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;

    private TextView logView;
    private ScrollView scroll;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGattCharacteristic cmdWrite;

    private int sequence = 1;
    private int rxCount = 0;

    private boolean scanning = false;
    private boolean experimentRunning = false;
    private boolean recordingComplete = false;

    private final BlockingQueue<byte[]> writeQueue =
            new ArrayBlockingQueue<>(100);

    private boolean writing = false;

    /*
     * ------------------------------------------------------------------
     * Persistent Labrador capture
     * ------------------------------------------------------------------
     */

    private final List<byte[]> labradorFragments = new ArrayList<>();

    private File rawLogFile;
    private File binaryFile;
    private File analysisFile;

    private long experimentStartMillis = 0;
    private int labradorFragmentCount = 0;

    private String experimentTimestamp() {
        return new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
    }

    private void prepareCaptureFiles() {
        File dir = getExternalFilesDir(null);

        if (dir == null) {
            append("ERROR: external files directory unavailable");
            return;
        }

        String stamp = experimentTimestamp();

        binaryFile = new File(
                dir,
                "labrador_" + stamp + ".bin"
        );

        analysisFile = new File(
                dir,
                "labrador_" + stamp + "_analysis.txt"
        );

        append("LABRADOR BINARY FILE:");
        append(binaryFile.getAbsolutePath());

        append("LABRADOR ANALYSIS FILE:");
        append(analysisFile.getAbsolutePath());
    }

    private void saveBinaryFragment(byte[] data) {
        if (binaryFile == null) {
            prepareCaptureFiles();
        }

        try (FileOutputStream fos =
                     new FileOutputStream(binaryFile, true)) {

            fos.write(data);

        } catch (Exception e) {
            append("BINARY SAVE ERROR: " + e);
        }
    }

    private void writeAnalysis(String text) {
        if (analysisFile == null) {
            prepareCaptureFiles();
        }

        try (FileWriter fw =
                     new FileWriter(analysisFile, true)) {

            fw.write(text);
            fw.write("\n");

        } catch (Exception e) {
            append("ANALYSIS SAVE ERROR: " + e);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Basic UI
     * ------------------------------------------------------------------
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Button scanButton = new Button(this);
        scanButton.setText("SCAN / CONNECT");

        Button wristButton = new Button(this);
        wristButton.setText("SELECT WRIST");

        Button filterButton = new Button(this);
        filterButton.setText("FILTERED ON");

        Button rawButton = new Button(this);
        rawButton.setText("RAW SAVE ON");

        Button startButton = new Button(this);
        startButton.setText("START LABRADOR");

        Button tripleButton = new Button(this);
        tripleButton.setText("3x START EXPERIMENT");

        Button stopButton = new Button(this);
        stopButton.setText("STOP LABRADOR");

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);

        scroll = new ScrollView(this);
        scroll.addView(logView);

        root.addView(scanButton);
        root.addView(wristButton);
        root.addView(filterButton);
        root.addView(rawButton);
        root.addView(startButton);
        root.addView(tripleButton);
        root.addView(stopButton);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        setContentView(root);

        adapter = BluetoothAdapter.getDefaultAdapter();

        scanButton.setOnClickListener(v -> scan());

        wristButton.setOnClickListener(v ->
                sendNamed(
                        "SELECT_WRIST",
                        0x23,
                        0x7B,
                        0x00
                )
        );

        filterButton.setOnClickListener(v ->
                sendNamed(
                        "FILTERED_ON",
                        0x23,
                        0x8B,
                        0x01
                )
        );

        rawButton.setOnClickListener(v ->
                sendNamed(
                        "RAW_SAVE_ON",
                        0x23,
                        0x7D,
                        0x01
                )
        );

        startButton.setOnClickListener(v -> {
            prepareNewExperiment();
            sendNamed(
                    "LABRADOR_START",
                    0x23,
                    0x7C,
                    0x01
            );
        });

        tripleButton.setOnClickListener(v ->
                startTripleExperiment()
        );

        stopButton.setOnClickListener(v ->
                sendNamed(
                        "LABRADOR_STOP",
                        0x23,
                        0x7C,
                        0x00
                )
        );

        if (checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(
                        Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQ_BT
            );
        }
    }

    /*
     * ------------------------------------------------------------------
     * Experiment handling
     * ------------------------------------------------------------------
     */

    private void prepareNewExperiment() {

        labradorFragments.clear();

        labradorFragmentCount = 0;

        recordingComplete = false;

        experimentRunning = true;

        experimentStartMillis = System.currentTimeMillis();

        prepareCaptureFiles();

        writeAnalysis(
                "=================================================="
        );

        writeAnalysis(
                "LABRADOR EXPERIMENT START "
                        + new Date(experimentStartMillis)
        );

        writeAnalysis(
                "=================================================="
        );

        append("");
        append("========== NEW LABRADOR CAPTURE ==========");
        append("Binary capture enabled");
        append("CBOR structural decoder enabled");
    }

    private void startTripleExperiment() {

        prepareNewExperiment();

        append("");
        append("*** 3x LABRADOR START EXPERIMENT ***");
        append("*** START #1 NOW ***");

        sendNamed(
                "EXPERIMENT_LABRADOR_START_1",
                0x23,
                0x7C,
                0x01
        );

        handler.postDelayed(() -> {

            append("");
            append("*** START #2 — 40 seconds ***");

            sendNamed(
                    "EXPERIMENT_LABRADOR_START_2",
                    0x23,
                    0x7C,
                    0x01
            );

        }, 40000);

        handler.postDelayed(() -> {

            append("");
            append("*** START #3 — 80 seconds ***");

            sendNamed(
                    "EXPERIMENT_LABRADOR_START_3",
                    0x23,
                    0x7C,
                    0x01
            );

        }, 80000);
    }

    /*
     * ------------------------------------------------------------------
     * Bluetooth scanning
     * ------------------------------------------------------------------
     */

    @SuppressLint("MissingPermission")
    private void scan() {

        if (adapter == null) {
            append("Bluetooth unavailable");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();

        if (scanner == null) {
            append("BLE scanner unavailable");
            return;
        }

        append("SCANNING 10s...");

        scanning = true;

        scanner.startScan(scanCallback);

        handler.postDelayed(() -> {

            if (scanning) {

                scanning = false;

                try {
                    scanner.stopScan(scanCallback);
                } catch (Exception ignored) {
                }

                append("SCAN STOP");
            }

        }, 10000);
    }

    private final ScanCallback scanCallback =
            new ScanCallback() {

                @SuppressLint("MissingPermission")
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {

                    BluetoothDevice device =
                            result.getDevice();

                    String name = device.getName();

                    if (name == null)
                        return;

                    if (!name.toUpperCase(Locale.US)
                            .contains("WHOOP")) {

                        return;
                    }

                    append(
                            "FOUND WHOOP "
                                    + name
                                    + " "
                                    + device.getAddress()
                                    + " RSSI="
                                    + result.getRssi()
                    );

                    scanning = false;

                    try {
                        scanner.stopScan(this);
                    } catch (Exception ignored) {
                    }

                    append("SCAN STOP");

                    connect(device);
                }
            };

    /*
     * ------------------------------------------------------------------
     * GATT
     * ------------------------------------------------------------------
     */

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {

        append(
                "CONNECTING "
                        + device.getAddress()
        );

        gatt = device.connectGatt(
                this,
                false,
                gattCallback
        );
    }

    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {

                @Override
                public void onConnectionStateChange(
                        BluetoothGatt g,
                        int status,
                        int newState) {

                    append(
                            "GATT state="
                                    + newState
                                    + " status="
                                    + status
                    );

                    if (newState ==
                            android.bluetooth.BluetoothProfile.STATE_CONNECTED) {

                        append("GATT CONNECTED");

                        if (MainActivity.this.checkSelfPermission(
                                Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED) {

                            g.discoverServices();
                        }
                    }
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt g,
                        int status) {

                    append(
                            "services discovered status="
                                    + status
                    );

                    BluetoothGattService service =
                            g.getService(
                                    java.util.UUID.fromString(
                                            Protocol.SERVICE
                                    )
                            );

                    if (service == null) {

                        append("fd4b service NOT FOUND");

                        return;
                    }

                    append("fd4b service found");

                    cmdWrite =
                            service.getCharacteristic(
                                    java.util.UUID.fromString(
                                            Protocol.CMD_WRITE
                                    )
                            );

                    subscribe(
                            g,
                            service,
                            Protocol.CMD_NOTIFY,
                            "0003"
                    );

                    subscribe(
                            g,
                            service,
                            Protocol.EVENT_NOTIFY,
                            "0004"
                    );

                    subscribe(
                            g,
                            service,
                            Protocol.DATA_NOTIFY,
                            "0005"
                    );

                    subscribe(
                            g,
                            service,
                            Protocol.EXTRA_NOTIFY,
                            "0007"
                    );

                    if (cmdWrite != null) {

                        append(
                                "TX CLIENT_HELLO "
                                        + "(confirmed write)"
                        );

                        enqueueWrite(
                                Protocol.clientHello()
                        );
                    }
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt g,
                        BluetoothGattCharacteristic c,
                        byte[] value) {

                    handleRx(
                            c.getUuid().toString(),
                            value
                    );
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt g,
                        BluetoothGattCharacteristic c,
                        int status) {

                    append(
                            "WRITE "
                                    + shortUuid(c.getUuid())
                                    + " status="
                                    + status
                    );

                    writing = false;

                    writeNext();
                }
            };

    @SuppressLint("MissingPermission")
    private void subscribe(
            BluetoothGatt g,
            BluetoothGattService service,
            String uuid,
            String label) {

        BluetoothGattCharacteristic c =
                service.getCharacteristic(
                        java.util.UUID.fromString(uuid)
                );

        if (c == null) {

            append(
                    "CHAR "
                            + label
                            + " NOT FOUND"
            );

            return;
        }

        boolean ok =
                g.setCharacteristicNotification(
                        c,
                        true
                );

        append(
                "subscribe "
                        + label
                        + " local="
                        + ok
        );

        BluetoothGattDescriptor d =
                c.getDescriptor(
                        java.util.UUID.fromString(
                                "00002902-0000-1000-8000-00805f9b34fb"
                        )
                );

        if (d == null) {

            append(
                    "CCCD "
                            + label
                            + " NOT FOUND"
            );

            return;
        }

        d.setValue(
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        );

        g.writeDescriptor(d);
    }

    private String shortUuid(String uuid) {

        if (uuid == null)
            return "?";

        if (uuid.length() > 8)
            return uuid.substring(
                    uuid.length() - 8
            );

        return uuid;
    }

    /*
     * ------------------------------------------------------------------
     * TX
     * ------------------------------------------------------------------
     */

    private void sendNamed(
            String name,
            int type,
            int cmd,
            int arg) {

        int seq = sequence++ & 0xff;

        byte[] frame =
                Protocol.labrador(
                        type,
                        cmd,
                        arg,
                        seq
                );

        append("");
        append("TX " + name);
        append(
                String.format(
                        Locale.US,
                        "TX TYPE=0x%02X",
                        type
                )
        );
        append(
                String.format(
                        Locale.US,
                        "TX CMD =0x%02X",
                        cmd
                )
        );
        append(
                String.format(
                        Locale.US,
                        "TX ARG =0x%02X",
                        arg
                )
        );
        append(
                String.format(
                        Locale.US,
                        "TX SEQ =0x%02X",
                        seq
                )
        );
        append(
                "TX LEN ="
                        + frame.length
        );
        append(
                "TX RAW ="
                        + Protocol.hex(frame)
        );

        enqueueWrite(frame);
    }

    private void enqueueWrite(byte[] data) {

        try {

            writeQueue.put(data);

            writeNext();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            append(
                    "WRITE QUEUE ERROR "
                            + e
            );
        }
    }

    @SuppressLint("MissingPermission")
    private void writeNext() {

        if (writing)
            return;

        byte[] data =
                writeQueue.poll();

        if (data == null)
            return;

        if (gatt == null ||
                cmdWrite == null) {

            append(
                    "WRITE ERROR: GATT/characteristic null"
            );

            return;
        }

        writing = true;

        cmdWrite.setWriteType(
                BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT
        );

        cmdWrite.setValue(data);

        boolean ok =
                gatt.writeCharacteristic(
                        cmdWrite
                );

        if (!ok) {

            append(
                    "writeCharacteristic returned FALSE"
            );

            writing = false;

            handler.postDelayed(
                    this::writeNext,
                    100
            );
        }
    }

    /*
     * ------------------------------------------------------------------
     * RX handling
     * ------------------------------------------------------------------
     */

    private void handleRx(
            String uuid,
            byte[] value) {

        rxCount++;

        String channel =
                shortUuid(uuid);

        append("");
        append(
                "========== RX #"
                        + rxCount
                        + " =========="
        );

        append(
                "CHANNEL "
                        + channel
        );

        append(
                "LENGTH  "
                        + value.length
        );

        append(
                "RAW     "
                        + Protocol.hex(value)
        );

        append(
                "FRAME   "
                        + Protocol.frameSummary(value)
        );

        if (value.length >= 8 &&
                (value[0] & 0xff) == 0xAA) {

            append(
                    "HEADER  "
                            + Protocol.hex(
                            new byte[]{
                                    value[0],
                                    value[1],
                                    value[2],
                                    value[3],
                                    value[4],
                                    value[5],
                                    value[6],
                                    value[7]
                            }
                    )
            );
        }

        /*
         * 0007 is the Labrador data channel.
         */
        if (channel.endsWith("0007")) {

            captureLabradorFragment(
                    value
            );
        }

        /*
         * 0004 completion event.
         */
        if (channel.endsWith("0004")) {

            if (isLabradorCompletion(value)) {

                append(
                        "*** RECORDING COMPLETE DETECTED ***"
                );

                recordingComplete = true;

                finishLabradorCapture();
            }
        }

        append(
                "========== END RX =========="
        );
    }

    private boolean isLabradorCompletion(
            byte[] b) {

        if (b.length < 13)
            return false;

        if ((b[0] & 0xff) != 0xAA)
            return false;

        /*
         * Envelope payload begins at byte 8.
         *
         * type = byte 8
         * seq  = byte 9
         * cmd  = byte 10
         */
        return (b[8] & 0xff) == 0x30 &&
                (b[10] & 0xff) == 0x1D;
    }

    /*
     * ------------------------------------------------------------------
     * Labrador capture
     * ------------------------------------------------------------------
     */

    private void captureLabradorFragment(
            byte[] value) {

        if (!experimentRunning) {

            /*
             * Still preserve it if a capture happens
             * outside an explicitly started experiment.
             */
            append(
                    "0007 received outside experiment"
            );

            if (binaryFile == null)
                prepareCaptureFiles();
        }

        byte[] copy =
                new byte[value.length];

        System.arraycopy(
                value,
                0,
                copy,
                0,
                value.length
        );

        labradorFragments.add(copy);

        labradorFragmentCount++;

        saveBinaryFragment(copy);

        append("");
        append(
                "LABRADOR 0007 FRAGMENT #"
                        + labradorFragmentCount
        );

        append(
                "LABRADOR LENGTH="
                        + copy.length
        );

        append(
                "LABRADOR HEAD "
                        + Protocol.hex(
                        firstBytes(copy, 32)
                )
        );

        append(
                "LABRADOR TAIL "
                        + Protocol.hex(
                        lastBytes(copy, 32)
                )
        );

        analyseAscii(copy);

        analysePotentialCbor(copy);
    }

    private void finishLabradorCapture() {

        if (labradorFragments.isEmpty()) {

            append(
                    "NO 0007 DATA TO REASSEMBLE"
            );

            return;
        }

        byte[] combined =
                combineFragments();

        append("");
        append(
                "=================================================="
        );

        append(
                "LABRADOR REASSEMBLED"
        );

        append(
                "FRAGMENTS="
                        + labradorFragments.size()
        );

        append(
                "TOTAL BYTES="
                        + combined.length
        );

        append(
                "FULL HEX:"
        );

        append(
                Protocol.hex(combined)
        );

        writeAnalysis("");
        writeAnalysis(
                "LABRADOR REASSEMBLED"
        );
        writeAnalysis(
                "FRAGMENTS="
                        + labradorFragments.size()
        );
        writeAnalysis(
                "TOTAL BYTES="
                        + combined.length
        );

        writeAnalysis(
                "FULL HEX:"
        );

        writeAnalysis(
                Protocol.hex(combined)
        );

        append("");
        append(
                "========== STRUCTURAL CBOR ANALYSIS =========="
        );

        writeAnalysis("");
        writeAnalysis(
                "========== STRUCTURAL CBOR ANALYSIS =========="
        );

        CborParser parser =
                new CborParser(combined);

        parser.parseTopLevel();

        for (String line :
                parser.lines) {

            append(line);
            writeAnalysis(line);
        }

        append("");
        append(
                "========== F6 ANALYSIS =========="
        );

        F6Analysis f6 =
                analyseF6(combined);

        append(
                "F6 COUNT="
                        + f6.count
        );

        append(
                "F6 RUNS="
                        + f6.runs
        );

        writeAnalysis(
                "F6 COUNT="
                        + f6.count
        );

        writeAnalysis(
                "F6 RUNS="
                        + f6.runs
        );

        append("");
        append(
                "========== CAPTURE COMPLETE =========="
        );

        append(
                "BINARY="
                        + binaryFile
        );

        append(
                "ANALYSIS="
                        + analysisFile
        );

        experimentRunning = false;
    }

    private byte[] combineFragments() {

        int total = 0;

        for (byte[] f :
                labradorFragments) {

            total += f.length;
        }

        byte[] out =
                new byte[total];

        int pos = 0;

        for (byte[] f :
                labradorFragments) {

            System.arraycopy(
                    f,
                    0,
                    out,
                    pos,
                    f.length
            );

            pos += f.length;
        }

        return out;
    }

    /*
     * ------------------------------------------------------------------
     * ASCII discovery
     * ------------------------------------------------------------------
     */

    private void analyseAscii(
            byte[] b) {

        int start = -1;

        for (int i = 0;
             i < b.length;
             i++) {

            int x = b[i] & 0xff;

            boolean printable =
                    x >= 32 && x <= 126;

            if (printable) {

                if (start < 0)
                    start = i;

            } else {

                if (start >= 0 &&
                        i - start >= 4) {

                    String s =
                            new String(
                                    b,
                                    start,
                                    i - start,
                                    StandardCharsets.US_ASCII
                            );

                    String line =
                            String.format(
                                    Locale.US,
                                    "ASCII @%d \"%s\"",
                                    start,
                                    s
                            );

                    append(line);
                    writeAnalysis(line);
                }

                start = -1;
            }
        }

        if (start >= 0 &&
                b.length - start >= 4) {

            String s =
                    new String(
                            b,
                            start,
                            b.length - start,
                            StandardCharsets.US_ASCII
                    );

            String line =
                    String.format(
                            Locale.US,
                            "ASCII @%d \"%s\"",
                            start,
                            s
                    );

            append(line);
            writeAnalysis(line);
        }
    }

    /*
     * ------------------------------------------------------------------
     * Preliminary CBOR decoder
     * ------------------------------------------------------------------
     */

    private void analysePotentialCbor(
            byte[] fragment) {

        /*
         * Search for likely CBOR map markers.
         *
         * We deliberately don't assume that the fragment
         * starts on a CBOR boundary.
         */

        for (int i = 0;
             i < fragment.length;
             i++) {

            int x =
                    fragment[i] & 0xff;

            if (x == 0xA7 ||
                    x == 0xA2 ||
                    x == 0xA1 ||
                    x == 0x98) {

                String line =
                        String.format(
                                Locale.US,
                                "CBOR-CANDIDATE @%d = 0x%02X",
                                i,
                                x
                        );

                append(line);
                writeAnalysis(line);
            }
        }
    }

    private static class CborParser {

        byte[] data;
        int pos = 0;

        List<String> lines =
                new ArrayList<>();

        int depth = 0;

        CborParser(byte[] data) {
            this.data = data;
        }

        void parseTopLevel() {

            add(
                    "CBOR parse begins at offset 0"
            );

            try {

                parseItem(0);

            } catch (Exception e) {

                add(
                        "CBOR parser stopped: "
                                + e.getMessage()
                );
            }

            add(
                    "CBOR parser final offset="
                            + pos
                            + "/"
                            + data.length
            );

            if (pos < data.length) {

                add(
                        "TRAILING BYTES @"
                                + pos
                                + ": "
                                + Protocol.hex(
                                slice(
                                        pos,
                                        data.length
                                )
                        )
                );
            }
        }

        private void parseItem(
                int level) {

            if (pos >= data.length)
                throw new RuntimeException(
                        "EOF"
                );

            int offset = pos;

            int initial =
                    data[pos++] & 0xff;

            int major =
                    initial >>> 5;

            int ai =
                    initial & 0x1f;

            String indent =
                    indent(level);

            if (initial == 0xF6) {

                add(
                        indent
                                + "@"
                                + offset
                                + " NULL (F6)"
                );

                return;
            }

            if (ai == 31) {

                add(
                        indent
                                + "@"
                                + offset
                                + " INDEFINITE/UNSUPPORTED"
                );

                return;
            }

            long n =
                    readAdditional(ai);

            switch (major) {

                case 0:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " UINT "
                                    + n
                    );

                    break;

                case 1:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " NINT "
                                    + (-1L - n)
                    );

                    break;

                case 2:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " BYTES len="
                                    + n
                    );

                    skip(n);

                    break;

                case 3:

                    if (n > Integer.MAX_VALUE ||
                            pos + (int)n > data.length) {

                        throw new RuntimeException(
                                "bad text length"
                        );
                    }

                    String s =
                            new String(
                                    data,
                                    pos,
                                    (int)n,
                                    StandardCharsets.UTF_8
                            );

                    pos += (int)n;

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " TEXT \""
                                    + s
                                    + "\""
                    );

                    break;

                case 4:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " ARRAY len="
                                    + n
                    );

                    if (n > 10000)
                        throw new RuntimeException(
                                "array too large"
                        );

                    for (int i = 0;
                         i < n;
                         i++) {

                        add(
                                indent
                                        + "  ["
                                        + i
                                        + "]"
                        );

                        parseItem(level + 1);
                    }

                    break;

                case 5:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " MAP pairs="
                                    + n
                    );

                    if (n > 10000)
                        throw new RuntimeException(
                                "map too large"
                        );

                    for (int i = 0;
                         i < n;
                         i++) {

                        add(
                                indent
                                        + "  KEY"
                        );

                        parseItem(level + 1);

                        add(
                                indent
                                        + "  VALUE"
                        );

                        parseItem(level + 1);
                    }

                    break;

                case 6:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " TAG "
                                    + n
                    );

                    parseItem(level + 1);

                    break;

                case 7:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " SIMPLE/FLOAT ai="
                                    + ai
                    );

                    break;

                default:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " UNKNOWN"
                    );
            }
        }

        private long readAdditional(
                int ai) {

            if (ai < 24)
                return ai;

            if (ai == 24) {

                ensure(1);

                return data[pos++] & 0xffL;
            }

            if (ai == 25) {

                ensure(2);

                long v =
                        ((data[pos] & 0xffL) << 8)
                                |
                                (data[pos + 1] & 0xffL);

                pos += 2;

                return v;
            }

            if (ai == 26) {

                ensure(4);

                long v =
                        ((data[pos] & 0xffL) << 24)
                                |
                                ((data[pos + 1] & 0xffL) << 16)
                                |
                                ((data[pos + 2] & 0xffL) << 8)
                                |
                                (data[pos + 3] & 0xffL);

                pos += 4;

                return v;
            }

            if (ai == 27) {

                ensure(8);

                long v = 0;

                for (int i = 0;
                     i < 8;
                     i++) {

                    v =
                            (v << 8)
                                    |
                                    (data[pos + i] & 0xffL);
                }

                pos += 8;

                return v;
            }

            throw new RuntimeException(
                    "unsupported additional info "
                            + ai
            );
        }

        private void skip(long n) {

            if (n < 0 ||
                    n > Integer.MAX_VALUE)
                throw new RuntimeException(
                        "invalid length"
                );

            ensure((int)n);

            pos += (int)n;
        }

        private void ensure(int n) {

            if (n < 0 ||
                    pos + n > data.length) {

                throw new RuntimeException(
                        "EOF at "
                                + pos
                                + " need "
                                + n
                );
            }
        }

        private byte[] slice(
                int a,
                int b) {

            if (a < 0)
                a = 0;

            if (b > data.length)
                b = data.length;

            if (b < a)
                b = a;

            byte[] x =
                    new byte[b - a];

            System.arraycopy(
                    data,
                    a,
                    x,
                    0,
                    x.length
            );

            return x;
        }

        private String indent(
                int level) {

            StringBuilder s =
                    new StringBuilder();

            for (int i = 0;
                 i < level;
                 i++) {

                s.append("  ");
            }

            return s.toString();
        }

        private void add(String s) {

            lines.add(s);
        }
    }

    /*
     * ------------------------------------------------------------------
     * F6 analysis
     * ------------------------------------------------------------------
     */

    private static class F6Analysis {

        int count = 0;

        List<Integer> runs =
                new ArrayList<>();
    }

    private F6Analysis analyseF6(
            byte[] b) {

        F6Analysis result =
                new F6Analysis();

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

        if (run > 0)
            result.runs.add(run);

        return result;
    }

    /*
     * ------------------------------------------------------------------
     * Utility
     * ------------------------------------------------------------------
     */

    private byte[] firstBytes(
            byte[] b,
            int n) {

        n = Math.min(
                n,
                b.length
        );

        byte[] x =
                new byte[n];

        System.arraycopy(
                b,
                0,
                x,
                0,
                n
        );

        return x;
    }

    private byte[] lastBytes(
            byte[] b,
            int n) {

        n = Math.min(
                n,
                b.length
        );

        byte[] x =
                new byte[n];

        System.arraycopy(
                b,
                b.length - n,
                x,
                0,
                n
        );

        return x;
    }

    private void append(
            String s) {

        runOnUiThread(() -> {

            String timestamp =
                    new SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.US
                    ).format(
                            new Date()
                    );

            logView.append(
                    timestamp
                            + "  "
                            + s
                            + "\n"
            );

            scroll.post(() ->
                    scroll.fullScroll(
                            ScrollView.FOCUS_DOWN
                    )
            );

            /*
             * Also save the complete textual log.
             */
            saveRawTextLog(
                    timestamp
                            + "  "
                            + s
            );
        });
    }

    private void saveRawTextLog(
            String line) {

        try {

            File dir =
                    getExternalFilesDir(null);

            if (dir == null)
                return;

            if (rawLogFile == null) {

                rawLogFile =
                        new File(
                                dir,
                                "labrador_log_"
                                        + System.currentTimeMillis()
                                        + ".txt"
                        );

                /*
                 * This line intentionally isn't sent through
                 * append(), preventing recursion.
                 */
            }

            try (FileWriter fw =
                         new FileWriter(
                                 rawLogFile,
                                 true
                         )) {

                fw.write(line);
                fw.write("\n");
            }

        } catch (Exception ignored) {
        }
    }
}
