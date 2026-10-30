package com.noop.mgecg;

// FILE VERSION 0.25.1 (30 Oct 2026): readings the strap stored but the app has no live frames for (for example the session that starts right after the 10 minute cap): finds them in the app logs, rebuilds a frame file from the strap's stored 500 Hz records, then makes the Pro PDF and the Full pull. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StoredReading {
    private StoredReading() {}
    static final class Sess { long start, stop; }

    /** Sessions seen in the logs (STORED_ECG_PULL_BEGIN) of 30 s to 50 min that have no usable live frame file. Newest first. */
    static List<Sess> missing(File dir) {
        Map<Long, Sess> m = new TreeMap<>(); Pattern p = Pattern.compile("STORED_ECG_PULL_BEGIN sessionStart=(\\d+) sessionStop=(\\d+)");
        File[] fs = dir == null ? null : dir.listFiles(); if (fs == null) return new ArrayList<>();
        for (File f : fs) {
            String n = f.getName(); if (!n.startsWith("labrador_log_") || !n.endsWith(".txt") || f.length() > 300L * 1024 * 1024 || System.currentTimeMillis() - f.lastModified() > 21L * 86400000L) continue;      // 0.25.3: the strap keeps about 3 weeks
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String ln; while ((ln = br.readLine()) != null) { if (ln.indexOf("STORED_ECG_PULL_BEGIN") < 0) continue; Matcher mt = p.matcher(ln); if (!mt.find()) continue;
                    long a = Long.parseLong(mt.group(1)), b = Long.parseLong(mt.group(2)); if (b - a >= 30 && b - a <= 3000) { Sess s = new Sess(); s.start = a; s.stop = b; m.put(a, s); } }
            } catch (Exception ignored) { }
        }
        List<Sess> out = new ArrayList<>();
        for (Sess s : m.values()) { File bin = ProReportMaker.findBin(dir, s.start); if (bin != null && bin.length() > 60000) continue; out.add(s); }
        Collections.reverse(out); return out;
    }

    static void show(final Activity a) {
        final File dir = a.getExternalFilesDir(null);
        Toast.makeText(a, "Looking for readings the strap stored...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            final List<Sess> list = missing(dir);
            a.runOnUiThread(new Runnable() { @Override public void run() {
                if (list.isEmpty()) { Toast.makeText(a, "No missing readings: every session the logs show has its live frames.", Toast.LENGTH_LONG).show(); return; }
                final int n = Math.min(list.size(), 12); String[] items = new String[n]; SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK); f.setTimeZone(TimeZone.getDefault());
                for (int i = 0; i < n; i++) items[i] = f.format(new Date(list.get(i).start * 1000L)) + " (" + (list.get(i).stop - list.get(i).start) + " s)";
                new AlertDialog.Builder(a).setTitle("Readings with no live frames").setItems(items, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { recover(a, dir, list.get(w)); } }).setNegativeButton("Close", null).show();
            } });
        } }, "stored-scan").start();
    }

    static void recover(final Activity a, final File dir, final Sess s) {
        Toast.makeText(a, "Rebuilding from the strap's stored records...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            String msg = null;
            try {
                ReadingFrames.Sets S = ReadingFrames.collect(dir, s.start, s.stop);
                int have = 0; for (byte[] f : S.r16) { long u = ProAnalysis.u32(f, 15); if (u >= s.start && u <= s.stop + 5) have++; }
                if (have < 30) msg = "The strap has only " + have + " stored seconds for this reading (30 are needed): the records may already be trimmed.";
                else {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream(); int keepN = 0; for (byte[] f : S.r16) { long u = ProAnalysis.u32(f, 15); if (u >= s.start && u <= s.stop + 5) { bo.write(f); keepN++; } }      // 0.25.2: not the seconds of the session before
                    File bin = new File(dir, "realtime_ecg_bin_" + s.start + ".bin"); try (FileOutputStream os = new FileOutputStream(bin)) { os.write(bo.toByteArray()); }
                    final File html = new File(dir, "ecg_report_" + s.start + ".html"); final int secs = keepN;
                    a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, "Rebuilt from " + secs + " stored seconds. Making the Pro PDF and the Full pull...", Toast.LENGTH_LONG).show(); ProReportMaker.make(a, html); ReadingPull.make(a, html); } });
                }
            } catch (Throwable t) { msg = "Could not rebuild: " + t; }
            if (msg != null) { final String m = msg; a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } }); }
        } }, "stored-rebuild").start();
    }
}
