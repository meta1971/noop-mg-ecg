package com.noop.mgecg;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;

    private BluetoothGattCharacteristic cmdWrite;

    private TextView logView;
    private ScrollView scroll;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final BlockingQueue<byte[]> writeQueue =
            new ArrayBlockingQueue<>(100);

    private boolean writing = false;
    private boolean scanning = false;

    private int sequence = 1;
    private int rxCount = 0;

    private boolean experimentRunning = false;
    private boolean recordingComplete = false;

    /*
     * ================================================================
     * LABRADOR CAPTURE
     * ================================================================
     */

    private final List<byte[]> labradorFragments =
            new ArrayList<>();

    private int labradorFragmentCount = 0;

    private File rawLogFile;
    private File binaryFile;
    private File analysisFile;

    /*
     * ================================================================
     * ACTIVITY
     * ================================================================
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createUserInterface();

        checkBluetoothPermissions();
    }

    private void createUserInterface() {

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        Button scanButton =
                new Button(this);

        scanButton.setText(
                "SCAN / CONNECT"
        );

        Button wristButton =
                new Button(this);

        wristButton.setText(
                "SELECT WRIST"
        );

        Button filterButton =
                new Button(this);

        filterButton.setText(
                "FILTERED ON"
        );

        Button rawButton =
                new Button(this);

        rawButton.setText(
                "RAW SAVE ON"
        );

        Button startButton =
                new Button(this);

        startButton.setText(
                "START LABRADOR"
        );

        Button tripleButton =
                new Button(this);

        tripleButton.setText(
                "3x START EXPERIMENT"
        );

        Button stopButton =
                new Button(this);

        stopButton.setText(
                "STOP LABRADOR"
        );

        logView =
                new TextView(this);

        logView.setTextSize(11);

        logView.setTextIsSelectable(true);

        scroll =
                new ScrollView(this);

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

        scanButton.setOnClickListener(
                v -> scan()
        );

        wristButton.setOnClickListener(
                v -> sendNamed(
                        "SELECT_WRIST",
                        0x23,
                        0x7B,
                        0x00
                )
        );

        filterButton.setOnClickListener(
                v -> sendNamed(
                        "FILTERED_ON",
                        0x23,
                        0x8B,
                        0x01
                )
        );

        rawButton.setOnClickListener(
                v -> sendNamed(
                        "RAW_SAVE_ON",
                        0x23,
                        0x7D,
                        0x01
                )
        );

        startButton.setOnClickListener(
                v -> {

                    prepareNewExperiment();

                    sendNamed(
                            "LABRADOR_START",
                            0x23,
                            0x7C,
                            0x01
                    );
                }
        );

        tripleButton.setOnClickListener(
                v -> startTripleExperiment()
        );

        stopButton.setOnClickListener(
                v -> sendNamed(
                        "LABRADOR_STOP",
                        0x23,
                        0x7C,
                        0x00
                )
        );
    }

    /*
     * ================================================================
     * BLUETOOTH PERMISSIONS
     * ================================================================
     */

    private void checkBluetoothPermissions() {

        if (android.os.Build.VERSION.SDK_INT >= 31) {

            if (checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
                    ||
                    checkSelfPermission(
                            Manifest.permission.BLUETOOTH_CONNECT
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
    }

    /*
     * ================================================================
     * EXPERIMENT
     * ================================================================
     */

    private void prepareNewExperiment() {

        labradorFragments.clear();

        labradorFragmentCount = 0;

        recordingComplete = false;

        experimentRunning = true;

        prepareCaptureFiles();

        writeAnalysis("");
        writeAnalysis(
                "=================================================="
        );
        writeAnalysis(
                "LABRADOR EXPERIMENT START"
        );
        writeAnalysis(
                new Date().toString()
        );
        writeAnalysis(
                "=================================================="
        );

        append("");
        append(
                "========== NEW LABRADOR CAPTURE =========="
        );
        append(
                "Persistent binary capture ENABLED"
        );
        append(
                "Structural decoder ENABLED"
        );
    }

    private void startTripleExperiment() {

        prepareNewExperiment();

        append("");
        append(
                "*** 3x LABRADOR START EXPERIMENT ***"
        );

        append(
                "*** START #1 NOW ***"
        );

        sendNamed(
                "EXPERIMENT_LABRADOR_START_1",
                0x23,
                0x7C,
                0x01
        );

        handler.postDelayed(
                () -> {

                    append("");
                    append(
                            "*** START #2 — 40 SECONDS ***"
                    );

                    sendNamed(
                            "EXPERIMENT_LABRADOR_START_2",
                            0x23,
                            0x7C,
                            0x01
                    );

                },
                40000
        );

        handler.postDelayed(
                () -> {

                    append("");
                    append(
                            "*** START #3 — 80 SECONDS ***"
                    );

                    sendNamed(
                            "EXPERIMENT_LABRADOR_START_3",
                            0x23,
                            0x7C,
                            0x01
                    );

                },
                80000
        );
    }

    /*
     * ================================================================
     * FILES
     * ================================================================
     */

    private void prepareCaptureFiles() {

        File dir =
                getExternalFilesDir(null);

        if (dir == null) {

            append(
                    "ERROR: external files directory unavailable"
            );

            return;
        }

        String stamp =
                new SimpleDateFormat(
                        "yyyyMMdd_HHmmss",
                        Locale.US
                ).format(
                        new Date()
                );

        binaryFile =
                new File(
                        dir,
                        "labrador_"
                                + stamp
                                + ".bin"
                );

        analysisFile =
                new File(
                        dir,
                        "labrador_"
                                + stamp
                                + "_analysis.txt"
                );

        append(
                "LABRADOR BINARY FILE:"
        );

        append(
                binaryFile.getAbsolutePath()
        );

        append(
                "LABRADOR ANALYSIS FILE:"
        );

        append(
                analysisFile.getAbsolutePath()
        );
    }

    private void saveBinaryFragment(
            byte[] data) {

        if (binaryFile == null) {

            prepareCaptureFiles();
        }

        try {

            FileOutputStream fos =
                    new FileOutputStream(
                            binaryFile,
                            true
                    );

            fos.write(data);

            fos.close();

        } catch (Exception e) {

            append(
                    "BINARY SAVE ERROR: "
                            + e
            );
        }
    }

    private void writeAnalysis(
            String text) {

        if (analysisFile == null) {

            prepareCaptureFiles();
        }

        try {

            FileWriter fw =
                    new FileWriter(
                            analysisFile,
                            true
                    );

            fw.write(text);
            fw.write("\n");

            fw.close();

        } catch (Exception e) {

            append(
                    "ANALYSIS SAVE ERROR: "
                            + e
            );
        }
    }

    /*
     * ================================================================
     * BLE SCANNING
     * ================================================================
     */

    @SuppressLint("MissingPermission")
    private void scan() {

        if (adapter == null) {

            append(
                    "Bluetooth unavailable"
            );

            return;
        }

        scanner =
                adapter.getBluetoothLeScanner();

        if (scanner == null) {

            append(
                    "BLE scanner unavailable"
            );

            return;
        }

        append(
                "SCANNING 10s..."
        );

        scanning = true;

        scanner.startScan(
                scanCallback
        );

        handler.postDelayed(
                () -> {

                    if (scanning) {

                        scanning = false;

                        try {

                            scanner.stopScan(
                                    scanCallback
                            );

                        } catch (Exception ignored) {
                        }

                        append(
                                "SCAN STOP"
                        );
                    }

                },
                10000
        );
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

                    String name =
                            device.getName();

                    if (name == null)
                        return;

                    if (!name.toUpperCase(
                            Locale.US
                    ).contains("WHOOP")) {

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

                        scanner.stopScan(
                                scanCallback
                        );

                    } catch (Exception ignored) {
                    }

                    append(
                            "SCAN STOP"
                    );

                    connect(device);
                }
            };

    /*
     * ================================================================
     * GATT
     * ================================================================
     */

    @SuppressLint("MissingPermission")
    private void connect(
            BluetoothDevice device) {

        append(
                "CONNECTING "
                        + device.getAddress()
        );

        gatt =
                device.connectGatt(
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
                            BluetoothProfile.STATE_CONNECTED) {

                        append(
                                "GATT CONNECTED"
                        );

                        if (hasBluetoothConnectPermission()) {

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
                                    UUID.fromString(
                                            Protocol.SERVICE
                                    )
                            );

                    if (service == null) {

                        append(
                                "fd4b service NOT FOUND"
                        );

                        return;
                    }

                    append(
                            "fd4b service found"
                    );

                    cmdWrite =
                            service.getCharacteristic(
                                    UUID.fromString(
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
                                    + channelName(
                                    c.getUuid().toString()
                            )
                                    + " status="
                                    + status
                    );

                    writing = false;

                    writeNext();
                }
            };

    private boolean hasBluetoothConnectPermission() {

        if (android.os.Build.VERSION.SDK_INT < 31)
            return true;

        return checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void subscribe(
            BluetoothGatt g,
            BluetoothGattService service,
            String uuid,
            String label) {

        BluetoothGattCharacteristic c =
                service.getCharacteristic(
                        UUID.fromString(uuid)
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
                        UUID.fromString(
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
                BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
        );

        g.writeDescriptor(d);
    }

    /*
     * ================================================================
     * TX
     * ================================================================
     */

    private void sendNamed(
            String name,
            int type,
            int cmd,
            int arg) {

        int seq =
                sequence++ & 0xFF;

        byte[] frame =
                Protocol.labrador(
                        type,
                        cmd,
                        arg,
                        seq
                );

        append("");
        append(
                "TX " + name
        );

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

    private void enqueueWrite(
            byte[] data) {

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
                    "WRITE ERROR: GATT/CHAR NULL"
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
     * ================================================================
     * RX
     * ================================================================
     */

    private void handleRx(
            String uuid,
            byte[] value) {

        rxCount++;

        String channel =
                channelName(uuid);

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
                (value[0] & 0xFF) == 0xAA) {

            append(
                    "HEADER  "
                            + Protocol.hex(
                            firstBytes(
                                    value,
                                    8
                            )
                    )
            );

            if (value.length >= 11) {

                append(
                        String.format(
                                Locale.US,
                                "FIELDS  type=0x%02X seq=0x%02X cmd=0x%02X",
                                value[8] & 0xFF,
                                value[9] & 0xFF,
                                value[10] & 0xFF
                        )
                );
            }
        }

        /*
         * 0007 = Labrador data.
         */
        if ("0007".equals(channel)) {

            captureLabradorFragment(
                    value
            );
        }

        /*
         * 0004 type 0x30 / cmd 0x1D =
         * observed Labrador completion.
         */
        if ("0004".equals(channel)
                &&
                isLabradorCompletion(value)) {

            append(
                    "*** RECORDING COMPLETE DETECTED ***"
            );

            recordingComplete = true;

            finishLabradorCapture();
        }

        append(
                "========== END RX =========="
        );
    }

    private String channelName(
            String uuid) {

        if (uuid == null)
            return "?";

        String u =
                uuid.toLowerCase(
                        Locale.US
                );

        if (u.equals(
                Protocol.CMD_NOTIFY.toLowerCase(
                        Locale.US
                )))
            return "0003";

        if (u.equals(
                Protocol.EVENT_NOTIFY.toLowerCase(
                        Locale.US
                )))
            return "0004";

        if (u.equals(
                Protocol.DATA_NOTIFY.toLowerCase(
                        Locale.US
                )))
            return "0005";

        if (u.equals(
                Protocol.EXTRA_NOTIFY.toLowerCase(
                        Locale.US
                )))
            return "0007";

        return uuid;
    }

    private boolean isLabradorCompletion(
            byte[] b) {

        if (b.length < 13)
            return false;

        if ((b[0] & 0xFF) != 0xAA)
            return false;

        return (b[8] & 0xFF) == 0x30
                &&
                (b[10] & 0xFF) == 0x1D;
    }

    /*
     * ================================================================
     * LABRADOR FRAGMENTS
     * ================================================================
     */

    private void captureLabradorFragment(
            byte[] value) {

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
                        firstBytes(
                                copy,
                                32
                        )
                )
        );

        append(
                "LABRADOR TAIL "
                        + Protocol.hex(
                        lastBytes(
                                copy,
                                32
                        )
                )
        );

        analyseAscii(copy);

        findCborCandidates(copy);

        analyseF6Fragment(copy);
    }

    private void finishLabradorCapture() {

        if (labradorFragments.isEmpty()) {

            append(
                    "NO 0007 DATA TO REASSEMBLE"
            );

            experimentRunning = false;

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
                "=================================================="
        );
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

        /*
         * Search for likely CBOR structures.
         */
        append("");
        append(
                "========== CBOR CANDIDATE ANALYSIS =========="
        );

        writeAnalysis("");
        writeAnalysis(
                "========== CBOR CANDIDATE ANALYSIS =========="
        );

        findCborCandidatesCombined(
                combined
        );

        /*
         * Specifically investigate the known
         * metadata/map area around offset 37.
         */
        int[] candidates =
                findCandidateOffsets(
                        combined
                );

        for (int offset :
                candidates) {

            decodeCborAt(
                    combined,
                    offset
            );
        }

        /*
         * F6 analysis.
         */
        append("");
        append(
                "========== F6 ANALYSIS =========="
        );

        writeAnalysis("");
        writeAnalysis(
                "========== F6 ANALYSIS =========="
        );

        F6Analysis f6 =
                analyseF6(combined);

        String countLine =
                "F6 COUNT="
                        + f6.count;

        String runsLine =
                "F6 RUNS="
                        + f6.runs;

        append(countLine);
        append(runsLine);

        writeAnalysis(countLine);
        writeAnalysis(runsLine);

        append("");
        append(
                "========== CAPTURE COMPLETE =========="
        );

        append(
                "BINARY="
                        + binaryFile.getAbsolutePath()
        );

        append(
                "ANALYSIS="
                        + analysisFile.getAbsolutePath()
        );

        writeAnalysis(
                "CAPTURE COMPLETE"
        );

        experimentRunning = false;
    }

    private byte[] combineFragments() {

        int total = 0;

        for (byte[] f :
                labradorFragments) {

            total += f.length;
        }

        byte[] result =
                new byte[total];

        int pos = 0;

        for (byte[] f :
                labradorFragments) {

            System.arraycopy(
                    f,
                    0,
                    result,
                    pos,
                    f.length
            );

            pos += f.length;
        }

        return result;
    }

    /*
     * ================================================================
     * ASCII
     * ================================================================
     */

    private void analyseAscii(
            byte[] b) {

        int start = -1;

        for (int i = 0;
             i < b.length;
             i++) {

            int x =
                    b[i] & 0xFF;

            boolean printable =
                    x >= 32
                            &&
                            x <= 126;

            if (printable) {

                if (start < 0)
                    start = i;

            } else {

                if (start >= 0
                        &&
                        i - start >= 4) {

                    String s =
                            new String(
                                    b,
                                    start,
                                    i - start,
                                    StandardCharsets.US_ASCII
                            );

                    String line =
                            "ASCII @"
                                    + start
                                    + " \""
                                    + s
                                    + "\"";

                    append(line);
                    writeAnalysis(line);
                }

                start = -1;
            }
        }

        if (start >= 0
                &&
                b.length - start >= 4) {

            String s =
                    new String(
                            b,
                            start,
                            b.length - start,
                            StandardCharsets.US_ASCII
                    );

            String line =
                    "ASCII @"
                            + start
                            + " \""
                            + s
                            + "\"";

            append(line);
            writeAnalysis(line);
        }
    }

    /*
     * ================================================================
     * CBOR CANDIDATE SEARCH
     * ================================================================
     */

    private void findCborCandidates(
            byte[] b) {

        for (int i = 0;
             i < b.length;
             i++) {

            int x =
                    b[i] & 0xFF;

            if (x == 0xA7
                    ||
                    x == 0xA2
                    ||
                    x == 0xA1
                    ||
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

    private void findCborCandidatesCombined(
            byte[] b) {

        findCborCandidates(b);

        /*
         * The previous capture showed A7 at
         * approximately offset 37.
         */
        if (b.length > 37) {

            String line =
                    "KNOWN-AREA CHECK @37: "
                            + String.format(
                            Locale.US,
                            "0x%02X",
                            b[37] & 0xFF
                    );

            append(line);
            writeAnalysis(line);
        }
    }

    private int[] findCandidateOffsets(
            byte[] b) {

        ArrayList<Integer> list =
                new ArrayList<>();

        for (int i = 0;
             i < b.length;
             i++) {

            int x =
                    b[i] & 0xFF;

            /*
             * A7 = map(7)
             */
            if (x == 0xA7) {

                list.add(i);
            }
        }

        int[] result =
                new int[list.size()];

        for (int i = 0;
             i < list.size();
             i++) {

            result[i] =
                    list.get(i);
        }

        return result;
    }

    private void decodeCborAt(
            byte[] data,
            int offset) {

        append("");
        append(
                "========== CBOR PARSE @"
                        + offset
                        + " =========="
        );

        writeAnalysis("");
        writeAnalysis(
                "========== CBOR PARSE @"
                        + offset
                        + " =========="
        );

        CborParser parser =
                new CborParser(
                        data,
                        offset
                );

        parser.parse();

        for (String line :
                parser.lines) {

            append(line);
            writeAnalysis(line);
        }
    }

    /*
     * ================================================================
     * F6
     * ================================================================
     */

    private void analyseF6Fragment(
            byte[] b) {

        int count = 0;
        int longest = 0;
        int current = 0;

        for (byte x :
                b) {

            if ((x & 0xFF) == 0xF6) {

                count++;
                current++;

                if (current > longest)
                    longest = current;

            } else {

                current = 0;
            }
        }

        String line =
                "F6 fragment count="
                        + count
                        + " longestRun="
                        + longest;

        append(line);
        writeAnalysis(line);
    }

    private static class F6Analysis {

        int count;

        List<Integer> runs =
                new ArrayList<>();
    }

    private F6Analysis analyseF6(
            byte[] b) {

        F6Analysis result =
                new F6Analysis();

        int run = 0;

        for (byte x :
                b) {

            if ((x & 0xFF) == 0xF6) {

                result.count++;

                run++;

            } else {

                if (run > 0) {

                    result.runs.add(
                            run
                    );

                    run = 0;
                }
            }
        }

        if (run > 0) {

            result.runs.add(run);
        }

        return result;
    }

    /*
     * ================================================================
     * MINI CBOR DECODER
     * ================================================================
     *
     * This is intentionally a diagnostic parser.
     *
     * It does NOT claim that the entire Labrador payload is CBOR.
     * It simply lets us test the CBOR hypothesis at candidate offsets.
     */

    private static class CborParser {

        private final byte[] data;

        private int pos;

        private final List<String> lines =
                new ArrayList<>();

        CborParser(
                byte[] data,
                int start) {

            this.data = data;
            this.pos = start;
        }

        void parse() {

            try {

                parseItem(0);

            } catch (Exception e) {

                add(
                        "CBOR STOP: "
                                + e.getMessage()
                );
            }

            add(
                    "CBOR FINAL OFFSET="
                            + pos
                            + "/"
                            + data.length
            );

            if (pos < data.length) {

                add(
                        "TRAILING FROM @"
                                + pos
                                + ": "
                                + hexRange(
                                pos,
                                Math.min(
                                        data.length,
                                        pos + 64
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
                    data[pos++] & 0xFF;

            int major =
                    initial >>> 5;

            int ai =
                    initial & 0x1F;

            String indent =
                    indent(level);

            /*
             * CBOR null.
             */
            if (initial == 0xF6) {

                add(
                        indent
                                + "@"
                                + offset
                                + " NULL (F6)"
                );

                return;
            }

            /*
             * Break/indefinite.
             */
            if (ai == 31) {

                add(
                        indent
                                + "@"
                                + offset
                                + " INDEFINITE/BREAK"
                );

                return;
            }

            long value =
                    readAdditional(ai);

            switch (major) {

                case 0:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " UINT "
                                    + value
                    );

                    break;

                case 1:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " NINT "
                                    + (-1L - value)
                    );

                    break;

                case 2:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " BYTE STRING len="
                                    + value
                    );

                    skip(value);

                    break;

                case 3:

                    if (value >
                            Integer.MAX_VALUE) {

                        throw new RuntimeException(
                                "text too large"
                        );
                    }

                    ensure(
                            (int)value
                    );

                    String text =
                            new String(
                                    data,
                                    pos,
                                    (int)value,
                                    StandardCharsets.UTF_8
                            );

                    pos +=
                            (int)value;

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " TEXT \""
                                    + text
                                    + "\""
                    );

                    break;

                case 4:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " ARRAY len="
                                    + value
                    );

                    if (value > 10000)
                        throw new RuntimeException(
                                "array too large"
                        );

                    for (int i = 0;
                         i < value;
                         i++) {

                        add(
                                indent
                                        + "  ["
                                        + i
                                        + "]"
                        );

                        parseItem(
                                level + 1
                        );
                    }

                    break;

                case 5:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " MAP pairs="
                                    + value
                    );

                    if (value > 10000)
                        throw new RuntimeException(
                                "map too large"
                        );

                    for (int i = 0;
                         i < value;
                         i++) {

                        add(
                                indent
                                        + "  KEY"
                        );

                        parseItem(
                                level + 1
                        );

                        add(
                                indent
                                        + "  VALUE"
                        );

                        parseItem(
                                level + 1
                        );
                    }

                    break;

                case 6:

                    add(
                            indent
                                    + "@"
                                    + offset
                                    + " TAG "
                                    + value
                    );

                    parseItem(
                            level + 1
                    );

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

                return data[pos++] & 0xFFL;
            }

            if (ai == 25) {

                ensure(2);

                long result =
                        ((data[pos] & 0xFFL) << 8)
                                |
                                (data[pos + 1] & 0xFFL);

                pos += 2;

                return result;
            }

            if (ai == 26) {

                ensure(4);

                long result =
                        ((data[pos] & 0xFFL) << 24)
                                |
                                ((data[pos + 1] & 0xFFL) << 16)
                                |
                                ((data[pos + 2] & 0xFFL) << 8)
                                |
                                (data[pos + 3] & 0xFFL);

                pos += 4;

                return result;
            }

            if (ai == 27) {

                ensure(8);

                long result = 0;

                for (int i = 0;
                     i < 8;
                     i++) {

                    result =
                            (result << 8)
                                    |
                                    (data[pos + i]
                                            & 0xFFL);
                }

                pos += 8;

                return result;
            }

            throw new RuntimeException(
                    "unsupported additional info "
                            + ai
            );
        }

        private void skip(
                long length) {

            if (length < 0 ||
                    length > Integer.MAX_VALUE) {

                throw new RuntimeException(
                        "invalid length"
                );
            }

            ensure(
                    (int)length
            );

            pos +=
                    (int)length;
        }

        private void ensure(
                int length) {

            if (length < 0 ||
                    pos + length >
                            data.length) {

                throw new RuntimeException(
                        "EOF at "
                                + pos
                                + " need "
                                + length
                );
            }
        }

        private void add(
                String text) {

            lines.add(text);
        }

        private String indent(
                int depth) {

            StringBuilder s =
                    new StringBuilder();

            for (int i = 0;
                 i < depth;
                 i++) {

                s.append("  ");
            }

            return s.toString();
        }

        private String hexRange(
                int start,
                int end) {

            byte[] x =
                    new byte[end - start];

            System.arraycopy(
                    data,
                    start,
                    x,
                    0,
                    x.length
            );

            return Protocol.hex(x);
        }
    }

    /*
     * ================================================================
     * UTILITY
     * ================================================================
     */

    private byte[] firstBytes(
            byte[] b,
            int count) {

        count =
                Math.min(
                        count,
                        b.length
                );

        byte[] result =
                new byte[count];

        System.arraycopy(
                b,
                0,
                result,
                0,
                count
        );

        return result;
    }

    private byte[] lastBytes(
            byte[] b,
            int count) {

        count =
                Math.min(
                        count,
                        b.length
                );

        byte[] result =
                new byte[count];

        System.arraycopy(
                b,
                b.length - count,
                result,
                0,
                count
        );

        return result;
    }

    /*
     * ================================================================
     * TEXT LOG
     * ================================================================
     */

    private void append(
            String text) {

        String timestamp =
                new SimpleDateFormat(
                        "HH:mm:ss",
                        Locale.US
                ).format(
                        new Date()
                );

        String line =
                timestamp
                        + "  "
                        + text;

        runOnUiThread(
                () -> {

                    if (logView != null) {

                        logView.append(
                                line
                                        + "\n"
                        );

                        if (scroll != null) {

                            scroll.post(
                                    () -> scroll.fullScroll(
                                            ScrollView.FOCUS_DOWN
                                    )
                            );
                        }
                    }
                }
        );

        saveRawTextLog(line);
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
            }

            FileWriter fw =
                    new FileWriter(
                            rawLogFile,
                            true
                    );

            fw.write(line);
            fw.write("\n");

            fw.close();

        } catch (Exception ignored) {
        }
    }
}
