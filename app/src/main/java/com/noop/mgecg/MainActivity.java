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
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int REQ_BT = 1001;

    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;

    private BluetoothGattCharacteristic cmdWrite;

    private TextView logView;
    private ScrollView scroll;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Queue<byte[]> writeQueue =
            new ArrayDeque<>();

    private boolean writing = false;
    private boolean scanning = false;

    private int sequence = 1;
    private int rxCount = 0;

    private File rawLogFile;
    private File binaryFile;
    private File analysisFile;

    private final List<byte[]> labradorFragments =
            new ArrayList<>();

    private int labradorFragmentCount = 0;
    private boolean recordingComplete = false;

    /*
     * Notification subscription queue.
     *
     * We deliberately subscribe one characteristic at a time.
     * This avoids several simultaneous CCCD writes fighting for
     * the GATT operation slot.
     */
    private final List<SubscribeItem> subscribeQueue =
            new ArrayList<>();

    private int subscribeIndex = 0;

    private static class SubscribeItem {
        final String uuid;
        final String label;

        SubscribeItem(String uuid, String label) {
            this.uuid = uuid;
            this.label = label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createUserInterface();

        append("NOOP MG ECG Experimental");
        append("Initializing Bluetooth...");

        initializeBluetooth();
    }

    /*
     * ================================================================
     * BLUETOOTH INITIALIZATION
     * ================================================================
     */

    private void initializeBluetooth() {

        /*
         * Android's recommended way to obtain the adapter.
         */
        try {
            bluetoothManager =
                    (BluetoothManager) getSystemService(
                            BLUETOOTH_SERVICE
                    );

            if (bluetoothManager == null) {
                append("ERROR: BluetoothManager unavailable");
                return;
            }

            adapter = bluetoothManager.getAdapter();

        } catch (Exception e) {
            append("ERROR initializing Bluetooth: " + e);
            return;
        }

        if (adapter == null) {
            append("ERROR: Bluetooth adapter unavailable");
            return;
        }

        append("Bluetooth adapter found");

        requestBluetoothPermissions();

        /*
         * On Android 11 the permissions can already be granted.
         * On Android 12+ we wait for the runtime permission callback.
         */
        if (permissionsGranted()) {
            finishBluetoothInitialization();
        }
    }

    private boolean permissionsGranted() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            return checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
                    &&
                    checkSelfPermission(
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED;

        } else {

            return checkSelfPermission(
                    Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
                    &&
                    checkSelfPermission(
                            Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBluetoothPermissions() {

        ArrayList<String> permissions =
                new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            if (checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.BLUETOOTH_SCAN
                );
            }

            if (checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.BLUETOOTH_CONNECT
                );
            }

        } else {

            if (checkSelfPermission(
                    Manifest.permission.BLUETOOTH
            ) != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.BLUETOOTH
                );
            }

            if (checkSelfPermission(
                    Manifest.permission.BLUETOOTH_ADMIN
            ) != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.BLUETOOTH_ADMIN
                );
            }

            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {

                permissions.add(
                        Manifest.permission.ACCESS_FINE_LOCATION
                );
            }
        }

        if (!permissions.isEmpty()) {

            append(
                    "Requesting Bluetooth permissions..."
            );

            requestPermissions(
                    permissions.toArray(
                            new String[0]
                    ),
                    REQ_BT
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode != REQ_BT)
            return;

        boolean ok = permissionsGranted();

        if (ok) {

            append(
                    "Bluetooth permissions GRANTED"
            );

            finishBluetoothInitialization();

        } else {

            append(
                    "ERROR: Bluetooth permissions NOT GRANTED"
            );

            append(
                    "Go to Android Settings > Apps > NOOP MG ECG > Permissions"
            );
        }
    }

    @SuppressLint("MissingPermission")
    private void finishBluetoothInitialization() {

        if (adapter == null) {
            append("ERROR: adapter is NULL");
            return;
        }

        if (!adapter.isEnabled()) {

            append(
                    "Bluetooth is OFF - requesting enable"
            );

            try {

                Intent intent =
                        new Intent(
                                BluetoothAdapter.ACTION_REQUEST_ENABLE
                        );

                startActivityForResult(
                        intent,
                        2001
                );

            } catch (Exception e) {

                append(
                        "Unable to request Bluetooth enable: "
                                + e
                );
            }

            return;
        }

        append("Bluetooth is ON");
        append(
                "BLE adapter ready - press SCAN / CONNECT"
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode == 2001) {

            if (adapter != null &&
                    adapter.isEnabled()) {

                append("Bluetooth enabled");
                append(
                        "BLE adapter ready - press SCAN / CONNECT"
                );

            } else {

                append(
                        "Bluetooth remains OFF"
                );
            }
        }
    }

    /*
     * ================================================================
     * USER INTERFACE
     * ================================================================
     */

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
                "3X START EXPERIMENT"
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
     * LABRADOR EXPERIMENT
     * ================================================================
     */

    private void prepareNewExperiment() {

        labradorFragments.clear();

        labradorFragmentCount = 0;
        recordingComplete = false;

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
                            "*** START #2 - 40 SECONDS ***"
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
                            "*** START #3 - 80 SECONDS ***"
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
     * FILE CAPTURE
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

        if (binaryFile == null)
            prepareCaptureFiles();

        if (binaryFile == null)
            return;

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

        if (analysisFile == null)
            return;

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

        if (!permissionsGranted()) {

            append(
                    "Bluetooth permissions missing"
            );

            requestBluetoothPermissions();

            return;
        }

        if (adapter == null) {

            append(
                    "ERROR: Bluetooth adapter is NULL"
            );

            initializeBluetooth();

            return;
        }

        if (!adapter.isEnabled()) {

            append(
                    "Bluetooth is OFF"
            );

            finishBluetoothInitialization();

            return;
        }

        scanner =
                adapter.getBluetoothLeScanner();

        if (scanner == null) {

            append(
                    "ERROR: BLE scanner unavailable"
            );

            return;
        }

        append("");
        append(
                "SCANNING 10s..."
        );

        scanning = true;

        ScanSettings settings =
                new ScanSettings.Builder()
                        .setScanMode(
                                ScanSettings.SCAN_MODE_LOW_LATENCY
                        )
                        .build();

        try {

            scanner.startScan(
                    null,
                    settings,
                    scanCallback
            );

        } catch (Exception e) {

            scanning = false;

            append(
                    "SCAN ERROR: "
                            + e
            );

            return;
        }

        handler.postDelayed(
                () -> {

                    if (!scanning)
                        return;

                    scanning = false;

                    try {

                        if (scanner != null)
                            scanner.stopScan(
                                    scanCallback
                            );

                    } catch (Exception ignored) {
                    }

                    append(
                            "SCAN STOP"
                    );

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

                    if (device == null)
                        return;

                    String name;

                    try {
                        name = device.getName();
                    } catch (Exception e) {
                        return;
                    }

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

                        if (scanner != null)
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

                @Override
                public void onScanFailed(
                        int errorCode) {

                    scanning = false;

                    append(
                            "SCAN FAILED error="
                                    + errorCode
                    );
                }
            };

    /*
     * ================================================================
     * GATT CONNECTION
     * ================================================================
     */

    @SuppressLint("MissingPermission")
    private void connect(
            BluetoothDevice device) {

        if (!permissionsGranted()) {

            append(
                    "Cannot connect: Bluetooth permission missing"
            );

            return;
        }

        if (gatt != null) {

            try {
                gatt.close();
            } catch (Exception ignored) {
            }

            gatt = null;
        }

        cmdWrite = null;

        append("");
        append(
                "CONNECTING "
                        + device.getAddress()
        );

        try {

            gatt =
                    device.connectGatt(
                            this,
                            false,
                            gattCallback
                    );

        } catch (Exception e) {

            append(
                    "GATT CONNECT ERROR: "
                            + e
            );
        }
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

                            handler.postDelayed(
                                    () -> {

                                        try {

                                            boolean ok =
                                                    g.discoverServices();

                                            append(
                                                    "discoverServices="
                                                            + ok
                                            );

                                        } catch (Exception e) {

                                            append(
                                                    "discoverServices ERROR "
                                                            + e
                                            );
                                        }

                                    },
                                    300
                            );
                        }

                    } else if (
                            newState ==
                                    BluetoothProfile.STATE_DISCONNECTED
                    ) {

                        append(
                                "GATT DISCONNECTED status="
                                        + status
                        );

                        cmdWrite = null;
                    }
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt g,
                        int status) {

                    append(
                            "SERVICES DISCOVERED status="
                                    + status
                    );

                    if (status != BluetoothGatt.GATT_SUCCESS) {

                        append(
                                "Service discovery failed"
                        );

                        return;
                    }

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
                            "fd4b service FOUND"
                    );

                    cmdWrite =
                            service.getCharacteristic(
                                    UUID.fromString(
                                            Protocol.CMD_WRITE
                                    )
                            );

                    if (cmdWrite == null) {

                        append(
                                "CMD WRITE CHARACTERISTIC NOT FOUND"
                        );

                        return;
                    }

                    append(
                            "CMD WRITE CHARACTERISTIC FOUND"
                    );

                    subscribeQueue.clear();

                    subscribeQueue.add(
                            new SubscribeItem(
                                    Protocol.CMD_NOTIFY,
                                    "0003"
                            )
                    );

                    subscribeQueue.add(
                            new SubscribeItem(
                                    Protocol.EVENT_NOTIFY,
                                    "0004"
                            )
                    );

                    subscribeQueue.add(
                            new SubscribeItem(
                                    Protocol.DATA_NOTIFY,
                                    "0005"
                            )
                    );

                    subscribeQueue.add(
                            new SubscribeItem(
                                    Protocol.EXTRA_NOTIFY,
                                    "0007"
                            )
                    );

                    subscribeIndex = 0;

                    subscribeNext(
                            g,
                            service
                    );
                }

                @Override
                public void onDescriptorWrite(
                        BluetoothGatt g,
                        BluetoothGattDescriptor descriptor,
                        int status) {

                    append(
                            "CCCD WRITE "
                                    + shortUuid(
                                    descriptor.getCharacteristic()
                                            .getUuid()
                            )
                                    + " status="
                                    + status
                    );

                    subscribeIndex++;

                    BluetoothGattService service =
                            g.getService(
                                    UUID.fromString(
                                            Protocol.SERVICE
                                    )
                            );

                    if (service == null)
                        return;

                    handler.postDelayed(
                            () -> subscribeNext(
                                    g,
                                    service
                            ),
                            150
                    );
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt g,
                        BluetoothGattCharacteristic c,
                        byte[] value) {

                    handleRx(
                            c.getUuid(),
                            value
                    );
                }

                /*
                 * Android 13+ callback.
                 */
                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt g,
                        BluetoothGattCharacteristic c) {

                    byte[] value =
                            c.getValue();

                    if (value != null) {

                        handleRx(
                                c.getUuid(),
                                value
                        );
                    }
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt g,
                        BluetoothGattCharacteristic c,
                        int status) {

                    append(
                            "WRITE "
                                    + channelName(
                                    c.getUuid()
                            )
                                    + " status="
                                    + status
                    );

                    writing = false;

                    handler.postDelayed(
                            MainActivity.this::writeNext,
                            50
                    );
                }
            };

    /*
     * ================================================================
     * NOTIFICATION SUBSCRIPTIONS
     * ================================================================
     */

    @SuppressLint("MissingPermission")
    private void subscribeNext(
            BluetoothGatt g,
            BluetoothGattService service) {

        if (subscribeIndex >=
                subscribeQueue.size()) {

            append(
                    "ALL NOTIFICATIONS SUBSCRIBED"
            );

            if (cmdWrite != null) {

                append(
                        "TX CLIENT_HELLO"
                );

                enqueueWrite(
                        Protocol.clientHello()
                );
            }

            return;
        }

        SubscribeItem item =
                subscribeQueue.get(
                        subscribeIndex
                );

        BluetoothGattCharacteristic c =
                service.getCharacteristic(
                        UUID.fromString(
                                item.uuid
                        )
                );

        if (c == null) {

            append(
                    "CHAR "
                            + item.label
                            + " NOT FOUND"
            );

            subscribeIndex++;

            subscribeNext(
                    g,
                    service
            );

            return;
        }

        boolean local =
                g.setCharacteristicNotification(
                        c,
                        true
                );

        append(
                "SUBSCRIBE "
                        + item.label
                        + " local="
                        + local
        );

        BluetoothGattDescriptor d =
                c.getDescriptor(
                        CCCD_UUID
                );

        if (d == null) {

            append(
                    "CCCD "
                            + item.label
                            + " NOT FOUND"
            );

            subscribeIndex++;

            subscribeNext(
                    g,
                    service
            );

            return;
        }

        d.setValue(
                BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
        );

        boolean writeOk =
                g.writeDescriptor(d);

        append(
                "CCCD WRITE REQUEST "
                        + item.label
                        + " ok="
                        + writeOk
        );

        if (!writeOk) {

            subscribeIndex++;

            handler.postDelayed(
                    () -> subscribeNext(
                            g,
                            service
                    ),
                    200
            );
        }
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
                "TX "
                        + name
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
                        "TX CMD=0x%02X",
                        cmd
                )
        );

        append(
                String.format(
                        Locale.US,
                        "TX ARG=0x%02X",
                        arg
                )
        );

        append(
                String.format(
                        Locale.US,
                        "TX SEQ=0x%02X",
                        seq
                )
        );

        append(
                "TX LEN="
                        + frame.length
        );

        append(
                "TX RAW="
                        + Protocol.hex(frame)
        );

        enqueueWrite(frame);
    }

    private synchronized void enqueueWrite(
            byte[] data) {

        if (data == null)
            return;

        byte[] copy =
                new byte[data.length];

        System.arraycopy(
                data,
                0,
                copy,
                0,
                data.length
        );

        writeQueue.offer(copy);

        runOnUiThread(
                this::writeNext
        );
    }

    @SuppressLint("MissingPermission")
    private synchronized void writeNext() {

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

        if (!hasBluetoothConnectPermission()) {

            append(
                    "WRITE ERROR: Bluetooth CONNECT permission missing"
            );

            return;
        }

        writing = true;

        cmdWrite.setWriteType(
                BluetoothGattCharacteristic
                        .WRITE_TYPE_DEFAULT
        );

        cmdWrite.setValue(data);

        boolean ok;

        try {

            ok =
                    gatt.writeCharacteristic(
                            cmdWrite
                    );

        } catch (Exception e) {

            ok = false;

            append(
                    "writeCharacteristic ERROR "
                            + e
            );
        }

        if (!ok) {

            append(
                    "writeCharacteristic returned FALSE"
            );

            writing = false;

            handler.postDelayed(
                    this::writeNext,
                    150
            );
        }
    }

    /*
     * ================================================================
     * RX
     * ================================================================
     */

    private void handleRx(
            UUID uuid,
            byte[] value) {

        if (value == null)
            return;

        byte[] copy =
                new byte[value.length];

        System.arraycopy(
                value,
                0,
                copy,
                0,
                value.length
        );

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
                "LENGTH "
                        + copy.length
        );

        append(
                "RAW "
                        + Protocol.hex(copy)
        );

        append(
                "FRAME "
                        + Protocol.frameSummary(copy)
        );

        if (copy.length >= 8 &&
                (copy[0] & 0xFF) == 0xAA) {

            append(
                    "HEADER "
                            + Protocol.hex(
                            firstBytes(
                                    copy,
                                    8
                            )
                    )
            );

            if (copy.length >= 11) {

                append(
                        String.format(
                                Locale.US,
                                "FIELDS type=0x%02X seq=0x%02X cmd=0x%02X",
                                copy[8] & 0xFF,
                                copy[9] & 0xFF,
                                copy[10] & 0xFF
                        )
                );
            }
        }

        if ("0007".equals(channel)) {

            captureLabradorFragment(
                    copy
            );
        }

        if ("0004".equals(channel)
                &&
                isLabradorCompletion(copy)) {

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
            UUID uuid) {

        if (uuid == null)
            return "?";

        if (uuid.equals(
                UUID.fromString(
                        Protocol.CMD_NOTIFY
                )))
            return "0003";

        if (uuid.equals(
                UUID.fromString(
                        Protocol.EVENT_NOTIFY
                )))
            return "0004";

        if (uuid.equals(
                UUID.fromString(
                        Protocol.DATA_NOTIFY
                )))
            return "0005";

        if (uuid.equals(
                UUID.fromString(
                        Protocol.EXTRA_NOTIFY
                )))
            return "0007";

        return shortUuid(uuid);
    }

    private String shortUuid(
            UUID uuid) {

        if (uuid == null)
            return "?";

        String s =
                uuid.toString();

        if (s.length() >= 8)
            return s.substring(
                    0,
                    8
            );

        return s;
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
     * LABRADOR CAPTURE
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
         * Specifically parse every A7 candidate.
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
                        + binaryFile.getAbsolutePath()
        );

        append(
                "ANALYSIS="
                        + analysisFile.getAbsolutePath()
        );

        writeAnalysis(
                "CAPTURE COMPLETE"
        );
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
     * ASCII ANALYSIS
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
                    x >= 32 &&
                            x <= 126;

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

    private void findCborCandidatesCombined(
            byte[] b) {

        findCborCandidates(b);

        if (b.length > 37) {

            String line =
                    String.format(
                            Locale.US,
                            "KNOWN-AREA CHECK @37: 0x%02X",
                            b[37] & 0xFF
                    );

            append(line);
            writeAnalysis(line);

            if ((b[37] & 0xFF) == 0xA7) {

                append(
                        "KNOWN CBOR MAP FOUND AT OFFSET 37"
                );

                writeAnalysis(
                        "KNOWN CBOR MAP FOUND AT OFFSET 37"
                );
            }
        }
    }

    private int[] findCandidateOffsets(
            byte[] b) {

        ArrayList<Integer> list =
                new ArrayList<>();

        for (int i = 0;
             i < b.length;
             i++) {

            if ((b[i] & 0xFF) == 0xA7)
                list.add(i);
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

    /*
     * ================================================================
     * CBOR DECODER
     * ================================================================
     */

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

        CborReader reader =
                new CborReader(
                        data,
                        offset
                );

        try {

            reader.parseItem(
                    0
            );

            append(
                    "CBOR PARSE END="
                            + reader.pos
            );

            append(
                    "CBOR BYTES CONSUMED="
                            + (
                            reader.pos - offset
                    )
            );

            writeAnalysis(
                    "CBOR PARSE END="
                            + reader.pos
            );

            writeAnalysis(
                    "CBOR BYTES CONSUMED="
                            + (
                            reader.pos - offset
                    )
            );

        } catch (Exception e) {

            String line =
                    "CBOR PARSE ERROR @"
                            + reader.pos
                            + ": "
                            + e.getMessage();

            append(line);
            writeAnalysis(line);
        }
    }

    private class CborReader {

        private final byte[] data;
        private int pos;

        private int itemCount = 0;

        CborReader(
                byte[] data,
                int start) {

            this.data = data;
            this.pos = start;
        }

        void parseItem(
                int depth) {

            if (depth > 20)
                throw new RuntimeException(
                        "maximum CBOR depth"
                );

            if (++itemCount > 500)
                throw new RuntimeException(
                        "CBOR item limit reached"
                );

            ensure(1);

            int offset = pos;

            int first =
                    data[pos++] & 0xFF;

            int major =
                    first >>> 5;

            int ai =
                    first & 0x1F;

            String indent =
                    indent(depth);

            switch (major) {

                case 0: {

                    long value =
                            readAdditional(ai);

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " UINT "
                                    + value
                                    + " 0x"
                                    + Long.toHexString(
                                    value
                            )
                    );

                    break;
                }

                case 1: {

                    long value =
                            readAdditional(ai);

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " NEG "
                                    + (-1L - value)
                    );

                    break;
                }

                case 2: {

                    long length =
                            readAdditional(ai);

                    if (length >
                            Integer.MAX_VALUE) {

                        throw new RuntimeException(
                                "byte string too large"
                        );
                    }

                    ensure(
                            (int) length
                    );

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " BYTES length="
                                    + length
                                    + " "
                                    + hexRange(
                                    pos,
                                    pos + (int) length
                            )
                    );

                    pos +=
                            (int) length;

                    break;
                }

                case 3: {

                    long length =
                            readAdditional(ai);

                    if (length >
                            Integer.MAX_VALUE) {

                        throw new RuntimeException(
                                "text string too large"
                        );
                    }

                    ensure(
                            (int) length
                    );

                    String text =
                            new String(
                                    data,
                                    pos,
                                    (int) length,
                                    StandardCharsets.UTF_8
                            );

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " TEXT \""
                                    + text
                                    + "\""
                    );

                    pos +=
                            (int) length;

                    break;
                }

                case 4: {

                    long count =
                            readAdditional(ai);

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " ARRAY count="
                                    + count
                    );

                    if (count >
                            500) {

                        throw new RuntimeException(
                                "array too large"
                        );
                    }

                    for (int i = 0;
                         i < count;
                         i++) {

                        addAnalysis(
                                indent
                                        + "  ["
                                        + i
                                        + "]"
                        );

                        parseItem(
                                depth + 1
                        );
                    }

                    break;
                }

                case 5: {

                    long pairs =
                            readAdditional(ai);

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " MAP pairs="
                                    + pairs
                    );

                    if (pairs >
                            500) {

                        throw new RuntimeException(
                                "map too large"
                        );
                    }

                    for (int i = 0;
                         i < pairs;
                         i++) {

                        addAnalysis(
                                indent
                                        + "  KEY"
                        );

                        parseItem(
                                depth + 1
                        );

                        addAnalysis(
                                indent
                                        + "  VALUE"
                        );

                        parseItem(
                                depth + 1
                        );
                    }

                    break;
                }

                case 6: {

                    long tag =
                            readAdditional(ai);

                    addAnalysis(
                            indent
                                    + "@"
                                    + offset
                                    + " TAG "
                                    + tag
                    );

                    parseItem(
                            depth + 1
                    );

                    break;
                }

                case 7: {

                    if (ai == 20) {

                        addAnalysis(
                                indent
                                        + "@"
                                        + offset
                                        + " FALSE"
                        );

                    } else if (ai == 21) {

                        addAnalysis(
                                indent
                                        + "@"
                                        + offset
                                        + " TRUE"
                        );

                    } else if (ai == 22) {

                        addAnalysis(
                                indent
                                        + "@"
                                        + offset
                                        + " NULL"
                        );

                    } else if (ai == 23) {

                        addAnalysis(
                                indent
                                        + "@"
                                        + offset
                                        + " UNDEFINED"
                        );

                    } else {

                        addAnalysis(
                                indent
                                        + "@"
                                        + offset
                                        + " SIMPLE/FLOAT ai="
                                        + ai
                        );
                    }

                    break;
                }

                default:

                    throw new RuntimeException(
                            "unknown CBOR major type "
                                    + major
                    );
            }
        }

        private long readAdditional(
                int ai) {

            if (ai < 24)
                return ai;

            if (ai == 24) {

                ensure(1);

                return data[pos++] &
                        0xFFL;
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

            if (ai == 31) {

                throw new RuntimeException(
                        "indefinite-length CBOR not implemented"
                );
            }

            throw new RuntimeException(
                    "unsupported additional info "
                            + ai
            );
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
    }

    private void addAnalysis(
            String line) {

        append(line);
        writeAnalysis(line);
    }

    /*
     * ================================================================
     * F6 ANALYSIS
     * ================================================================
     */

    private static class F6Analysis {

        int count;
        int runs;
    }

    private F6Analysis analyseF6(
            byte[] data) {

        F6Analysis result =
                new F6Analysis();

        boolean inRun = false;

        for (byte b : data) {

            if ((b & 0xFF) == 0xF6) {

                result.count++;

                if (!inRun) {

                    result.runs++;
                    inRun = true;
                }

            } else {

                inRun = false;
            }
        }

        return result;
    }

    private void analyseF6Fragment(
            byte[] data) {

        int count = 0;

        for (byte b : data) {

            if ((b & 0xFF) == 0xF6)
                count++;
        }

        append(
                "F6 COUNT IN FRAGMENT="
                        + count
        );

        writeAnalysis(
                "F6 COUNT IN FRAGMENT="
                        + count
        );
    }

    /*
     * ================================================================
     * PERMISSION HELPERS
     * ================================================================
     */

    private boolean hasBluetoothConnectPermission() {

        if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.S) {

            return checkSelfPermission(
                    Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED;
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

    private String hexRange(
            int start,
            int end) {

        if (start < 0)
            start = 0;

        if (end > 1000000)
            end = 1000000;

        if (end <= start)
            return "";

        byte[] x =
                new byte[end - start];

        System.arraycopy(
                getCurrentCborData(),
                start,
                x,
                0,
                x.length
        );

        return Protocol.hex(x);
    }

    /*
     * The CBOR reader uses this helper only while decoding.
     * It is replaced by the direct data reference below.
     */
    private byte[] currentCborData;

    private byte[] getCurrentCborData() {

        return currentCborData;
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

    @Override
    protected void onDestroy() {

        handler.removeCallbacksAndMessages(
                null
        );

        try {

            if (scanner != null &&
                    scanning) {

                scanner.stopScan(
                        scanCallback
                );
            }

        } catch (Exception ignored) {
        }

        try {

            if (gatt != null) {

                gatt.close();
                gatt = null;
            }

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }
}
