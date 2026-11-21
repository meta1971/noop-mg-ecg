package com.noop.mgecg;

// FILE VERSION 0.74.0 (21 Nov 2026): copy B knows the last four characters of strap B's label (B_NAME_TAIL): when exactly one strap in range has a name ending with them, Choose strap picks it without a list; several, only those are listed; none, the list is as before. Not a hard lock (the advertised name form of strap B is not in the files held), and no serial or address is compiled in. FILE VERSION 0.70.0 (17 Nov 2026): which strap this copy of the app talks to, chosen at run time (addendum 197.4: "a third strap needs no rebuild").
// Copy A (the normal build) keeps the strap typed into MainActivity unless the owner chooses another; copy B (CopyB.B) has NO strap until the owner
// picks one from a list of the straps in range (Today > the link chip > Change strap, or the "No strap chosen" card). The pick is two strings in the
// preference file strap_lock: the Bluetooth address and the serial read from the advertised name. In copy B the strap of copy A is never offered, so the
// two copies can never fight over one strap. Copy B masks the identifiers in its strap-lock log lines (mask()); the older lines (SCAN_FOUND, EASY_CONNECT, DAYLOG_RECONNECT) still print the address, as in copy A, so a copy-B log is not for publishing.

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class StrapLock {
    private StrapLock() { }

    static final String PREFS = "strap_lock";
    /** 0.74.0: the last four characters printed on strap B's case (copy B only; copy A never reads it). Four characters, not the serial. */
    static final String B_NAME_TAIL = "0609";
    static boolean tailMatch(String name) { return CopyB.B && B_NAME_TAIL.length() > 0 && name != null && name.trim().endsWith(B_NAME_TAIL); }
    static final long SCAN_MS = 8000L;

    // ------------------------------------------------------------------ pure helpers (tested off the phone in XQ70)

    /** The saved value if there is one; otherwise the built-in default in copy A and nothing in copy B. */
    static String pick(String saved, String dflt, boolean copyB) {
        if (saved != null && saved.length() > 0) return saved;
        return copyB ? "" : (dflt == null ? "" : dflt);
    }

    /** The serial from an advertised name: the last word when it is 8 to 16 letters or digits with at least one digit ("WHOOP 4C1234567" or the bare serial); else "". The address is then the only key. */
    static String serialFromName(String name) {
        if (name == null) return "";
        String t = name.trim();
        if (t.length() == 0) return "";
        String[] w = t.split("\\s+");
        String last = w[w.length - 1];
        if (last.length() < 8 || last.length() > 16) return "";
        boolean digit = false;
        for (int i = 0; i < last.length(); i++) {
            char ch = last.charAt(i);
            if (!Character.isLetterOrDigit(ch)) return "";
            if (Character.isDigit(ch)) digit = true;
        }
        return digit ? last : "";
    }

    /** First two and last two characters with a star between: enough to tell two straps apart on the phone, nothing to publish. */
    static String mask(String s) {
        if (s == null || s.length() == 0) return "";
        if (s.length() <= 5) return "*";
        return s.substring(0, 2) + "*" + s.substring(s.length() - 2);
    }

    /** True when this advertisement is the other copy's strap (matched by address or by the serial inside the name). */
    static boolean excluded(String addr, String name, String otherAddr, String otherSerial) {
        if (addr != null && otherAddr != null && otherAddr.length() > 0 && otherAddr.equalsIgnoreCase(addr)) return true;
        return name != null && otherSerial != null && otherSerial.length() > 0 && name.contains(otherSerial);
    }

    // ------------------------------------------------------------------ the saved choice

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String addr(Context c, String dflt) { return pick(prefs(c).getString("addr", ""), dflt, CopyB.B); }

    static String serial(Context c, String dflt) { return pick(prefs(c).getString("serial", ""), dflt, CopyB.B); }

    /** True when the owner has chosen a strap in this copy. */
    static boolean has(Context c) {
        SharedPreferences p = prefs(c);
        return p.getString("addr", "").length() > 0 || p.getString("serial", "").length() > 0;
    }

    static void save(Context c, String addr, String serial) {
        prefs(c).edit().putString("addr", addr == null ? "" : addr).putString("serial", serial == null ? "" : serial).apply();
    }

    // ------------------------------------------------------------------ the chooser (a visible screen only)

    /** Scans for 8 s for straps in range, lists them strongest first, saves the one tapped and starts or restarts the link. done runs after a choice. */
    static void choose(final Activity a, final Runnable done) {
        if (!permitted(a)) return;
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { toast(a, "Switch Bluetooth on, then tap Choose strap again."); return; }
        final BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) { toast(a, "Bluetooth is not ready. Try again in a few seconds."); return; }
        final Map<String, String[]> found = new LinkedHashMap<String, String[]>();
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) { add(found, r); }
            @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) add(found, r); }
        };
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUID.fromString(Protocol.SERVICE))).build();
        ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            sc.startScan(Collections.singletonList(f), ss, cb);
        } catch (Throwable t) {
            toast(a, "Could not scan: " + t.getClass().getSimpleName());
            return;
        }
        toast(a, "Looking for straps for 8 seconds. Keep the one you want next to the phone.");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { sc.stopScan(cb); } catch (Throwable ignored) { }
                show(a, found, done);
            }
        }, SCAN_MS);
    }

    private static void add(Map<String, String[]> found, ScanResult r) {
        BluetoothDevice d = r.getDevice();
        if (d == null) return;
        String addr = d.getAddress();
        String name = null;
        try { name = d.getName(); } catch (SecurityException ignored) { }
        if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
        if (CopyB.B && excluded(addr, name, MainActivity.KNOWN_STRAP_ADDR, MainActivity.RESEARCH_STRAP_SERIAL)) return;      // never offer copy A's strap
        found.put(addr, new String[] { name == null ? "" : name, String.valueOf(r.getRssi()) });
    }

    private static void show(final Activity a, final Map<String, String[]> found, final Runnable done) {
        if (a.isFinishing()) return;
        final List<String> addrs = new ArrayList<String>(found.keySet());
        Collections.sort(addrs, new Comparator<String>() {
            @Override public int compare(String x, String y) { return Integer.parseInt(found.get(y)[1]) - Integer.parseInt(found.get(x)[1]); }
        });
        if (CopyB.B) {      // 0.74.0: strap B is the one whose name ends with its label tail
            List<String> hit = new ArrayList<String>();
            for (String ad : addrs) if (tailMatch(found.get(ad)[0])) hit.add(ad);
            if (hit.size() == 1) { picked(a, hit.get(0), found.get(hit.get(0))[0], done); return; }
            if (hit.size() > 1) { addrs.clear(); addrs.addAll(hit); }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        if (addrs.isEmpty()) {
            b.setTitle("No strap found")
                    .setMessage("Keep the strap within a metre of the phone, and not connected to another phone or app. On Android 11 or older, Location must be switched on for scanning.")
                    .setPositiveButton("Scan again", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { choose(a, done); }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        String[] items = new String[addrs.size()];
        for (int i = 0; i < items.length; i++) {
            String[] v = found.get(addrs.get(i));
            items[i] = (v[0].length() == 0 ? "(no name)" : v[0]) + "    " + v[1] + " dBm" + (i == 0 ? "    nearest" : "");
        }
        b.setTitle("Choose the strap")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String addr = addrs.get(which);
                        picked(a, addr, found.get(addr)[0], done);
                    }
                })
                .setNegativeButton("None of these", null)
                .show();
    }

    /** Saves the chosen strap and starts or restarts the link (0.74.0: the body of the list tap, shared with the automatic pick). */
    private static void picked(Activity a, String addr, String name, Runnable done) {
        save(a, addr, serialFromName(name));
        toast(a, "Strap chosen. Connecting.");
        if (DayLog.enabled(a)) MainActivity.requestStrapChanged(a);
        else { DayLog.setEnabled(a, true); DayLogService.start(a); MainActivity.startLinkHost(a); }
        if (done != null) done.run();
    }

    private static boolean permitted(Activity a) {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (a.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        a.requestPermissions(need.toArray(new String[0]), 7071);
        toast(a, "Allow the permission, then tap Choose strap again.");
        return false;
    }

    private static void toast(Context c, String m) { Toast.makeText(c, m, Toast.LENGTH_LONG).show(); }
}
