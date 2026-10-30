package com.noop.mgecg;

// FILE VERSION 0.26.0 (31 Oct 2026): blood pressure RESEARCH capture. You enter a cuff reading; the app stores it with the time, and the
// export pairs each one with what the strap recorded around it (per-second heart rate, optical beat intervals, skin temperature, motion)
// and, when an ECG reading covers that moment, the ECG figures and the pulse arrival time. Kept apart from every health figure in the app.
// Research only: no blood pressure is estimated or shown from the strap.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class BpCapture {
    private BpCapture() {}
    static final String FILE = "bp_captures.csv";

    static final class Cap { long ms; int sys, dia, pulse; String note = ""; }

    static List<Cap> load(File dir) {
        List<Cap> out = new ArrayList<>(); File f = new File(dir, FILE); if (!f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) { String ln; while ((ln = br.readLine()) != null) { String[] c = ln.split(",", 5); if (c.length < 4) continue; try { Cap k = new Cap(); k.ms = Long.parseLong(c[0]); k.sys = Integer.parseInt(c[1]); k.dia = Integer.parseInt(c[2]); k.pulse = Integer.parseInt(c[3]); k.note = c.length > 4 ? c[4] : ""; out.add(k); } catch (NumberFormatException ignored) { } } } catch (Exception ignored) { }
        return out;
    }

    static boolean save(File dir, Cap k) {
        try (FileWriter w = new FileWriter(new File(dir, FILE), true)) { w.write(k.ms + "," + k.sys + "," + k.dia + "," + k.pulse + "," + k.note.replace(',', ';').replace('\n', ' ') + "\n"); return true; } catch (Exception e) { return false; }
    }

    private static double med(List<Double> v) { if (v.isEmpty()) return Double.NaN; double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); return ProDsp.median(a); }
    private static String f(double v, int d) { return Double.isNaN(v) ? "" : String.format(Locale.UK, "%." + d + "f", v); }

    /** One CSV row per capture with what the strap recorded 2 minutes either side, and the ECG reading that covers it if there is one. Pure Java. */
    static String buildCsv(File dir, List<Cap> caps) throws Exception {
        StringBuilder sb = new StringBuilder("capture_time_utc,unix_s,cuff_systolic,cuff_diastolic,cuff_pulse,note,strap_seconds,strap_hr_median,optical_interval_n,optical_rmssd_ms,skin_c_median,accel_step_median_g,activity_seconds,ecg_reading,ecg_seconds_in_window,ecg_hr,ecg_rmssd_ms,pulse_arrival_median_ms,pulse_arrival_sd_ms,green_perfusion_pct,ir_near_far_ratio\n");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK); iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (Cap k : caps) {
            double t = k.ms / 1000.0; ReadingFrames.Sets S = ReadingFrames.collect(dir, t - 120, t + 120);
            List<Double> hr = new ArrayList<>(), sk = new ArrayList<>(), ac = new ArrayList<>(), iv = new ArrayList<>(); int act = 0, n = 0;
            for (byte[] r : S.r18) { long u = ProAnalysis.u32(r, 15); if (u < t - 120 || u > t + 120) continue; n++; int h = r[22] & 0xFF; if (h > 25) hr.add((double) h); int sv = (short) ((r[73] & 0xFF) | ((r[74] & 0xFF) << 8)); if (sv > -5000) sk.add(sv / 100.0); ac.add((double) Float.intBitsToFloat((int) ProAnalysis.u32(r, 41))); if ((r[63] & 0xFF) > 0) act++; int c = r[23] & 0xFF; for (int j = 0; j < c && j < 4; j++) { int ms = ProAnalysis.u16(r, 24 + 2 * j); if (ms > 300 && ms < 2000) iv.add((double) ms); } }
            double rm = Double.NaN; if (iv.size() > 10) { double s2 = 0; int m = 0; for (int i = 1; i < iv.size(); i++) { double d = iv.get(i) - iv.get(i - 1); if (Math.abs(d) < 200) { s2 += d * d; m++; } } if (m > 5) rm = Math.sqrt(s2 / m); }
            String ecgName = ""; int ecgSec = 0; double eHr = Double.NaN, eRm = Double.NaN, pat = Double.NaN, patSd = Double.NaN, pi = Double.NaN, irr = Double.NaN;
            File[] fs = dir.listFiles();
            if (fs != null) for (File b : fs) {
                String nm = b.getName(); if (!nm.startsWith("realtime_ecg_bin_") || !nm.endsWith(".bin") || b.length() < 60000) continue; long st; try { st = Long.parseLong(nm.substring(17, 27)); } catch (Exception e) { continue; }
                if (st > t + 60 || st < t - 900) continue;
                ProAnalysis.Result r = ProAnalysis.analyse(Files.readAllBytes(b.toPath())); if (r == null || r.t0 + r.seconds < t - 60) continue;
                ecgName = nm; ecgSec = (int) Math.max(0, Math.min(r.t0 + r.seconds, t + 120) - Math.max(r.t0, t - 120)); eHr = r.hrv.hr; eRm = r.hrv.rmssd;
                ReadingFrames.Sets E = ReadingFrames.collect(dir, r.t0, r.t0 + r.seconds); ProDetect.Pat P = ProDetect.pat(r, E.r20); pat = P.medianMs; patSd = P.sdMs; ProDetect.Opt O = ProDetect.optics(E.r20, r.t0, r.t0 + r.seconds); pi = O.greenPi; irr = O.irRatio; break;
            }
            sb.append(iso.format(new Date(k.ms))).append(',').append(k.ms / 1000L).append(',').append(k.sys).append(',').append(k.dia).append(',').append(k.pulse).append(',').append(k.note.replace(',', ';')).append(',').append(n).append(',').append(f(med(hr), 0)).append(',').append(iv.size()).append(',').append(f(rm, 1)).append(',').append(f(med(sk), 2)).append(',').append(f(med(ac), 3)).append(',').append(act).append(',').append(ecgName).append(',').append(ecgSec).append(',').append(f(eHr, 1)).append(',').append(f(eRm, 1)).append(',').append(f(pat, 0)).append(',').append(f(patSd, 1)).append(',').append(f(pi, 2)).append(',').append(f(irr, 2)).append('\n');
        }
        return sb.toString();
    }

    static void show(final Activity a) {
        final File dir = a.getExternalFilesDir(null);
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); int pad = (int) (16 * a.getResources().getDisplayMetrics().density); col.setPadding(pad, pad / 2, pad, 0);
        TextView how = new TextView(a); how.setTextSize(12); how.setText("Research capture, kept apart from every health figure. For the most useful pairing take the cuff reading while an ECG reading runs (fingers on the clasp, 2 minutes or more), cuff on the other arm, seated, after 5 minutes of rest, then type it in here within a minute. The export adds what the strap recorded 2 minutes either side and the ECG figures, including the pulse arrival time.");
        final EditText sys = num(a, "Systolic (mmHg)"), dia = num(a, "Diastolic (mmHg)"), pul = num(a, "Cuff pulse (bpm), optional"); final EditText note = new EditText(a); note.setHint("Note: arm, posture, anything unusual");
        final TextView list = new TextView(a); list.setTextSize(12); list.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(how); col.addView(sys); col.addView(dia); col.addView(pul); col.addView(note); col.addView(list);
        final Runnable refresh = new Runnable() { @Override public void run() { List<Cap> c = load(dir); StringBuilder sb = new StringBuilder(c.size() + " captures saved"); SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.UK); for (int i = Math.max(0, c.size() - 5); i < c.size(); i++) sb.append("\n").append(df.format(new Date(c.get(i).ms))).append("  ").append(c.get(i).sys).append("/").append(c.get(i).dia).append(c.get(i).pulse > 0 ? "  pulse " + c.get(i).pulse : ""); list.setText(sb.toString()); } };
        refresh.run();
        ScrollView sv = new ScrollView(a); sv.addView(col);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Blood pressure research capture").setView(sv).setPositiveButton("Save", null).setNeutralButton("Export", null).setNegativeButton("Close", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            int s, d, p = 0; try { s = Integer.parseInt(sys.getText().toString().trim()); d = Integer.parseInt(dia.getText().toString().trim()); } catch (Exception e) { Toast.makeText(a, "Type the systolic and diastolic numbers.", Toast.LENGTH_SHORT).show(); return; }
            try { p = Integer.parseInt(pul.getText().toString().trim()); } catch (Exception ignored) { }
            if (s < 60 || s > 260 || d < 30 || d > 160 || d >= s) { Toast.makeText(a, "Those numbers look mistyped (systolic 60 to 260, diastolic 30 to 160, below systolic).", Toast.LENGTH_LONG).show(); return; }
            Cap k = new Cap(); k.ms = System.currentTimeMillis(); k.sys = s; k.dia = d; k.pulse = p; k.note = note.getText().toString().trim();
            Toast.makeText(a, save(dir, k) ? "Saved." : "Could not save.", Toast.LENGTH_SHORT).show(); sys.setText(""); dia.setText(""); pul.setText(""); note.setText(""); refresh.run(); } });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { export(a, dir); } });
    }

    private static EditText num(Activity a, String hint) { EditText e = new EditText(a); e.setHint(hint); e.setInputType(InputType.TYPE_CLASS_NUMBER); return e; }

    static void export(final Activity a, final File dir) {
        Toast.makeText(a, "Pairing each capture with the strap's records (can take a minute)...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                List<Cap> caps = load(dir); if (caps.isEmpty()) msg = "No captures saved yet.";
                else {
                    byte[] csv = buildCsv(dir, caps).getBytes("UTF-8"); String name = "MG_bp_captures_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.UK).format(new Date()) + ".csv";
                    ContentResolver cr = a.getContentResolver(); ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name); cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv"); cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + ReadingPull.FOLDER + "/"); cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) msg = "Could not save the export.";
                    else { try (OutputStream os = cr.openOutputStream(uri)) { os.write(csv); } ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, done, null, null); msg = "Saved Download/" + ReadingPull.FOLDER + "/" + name + " (" + caps.size() + " captures)"; }
                }
            } catch (Throwable t) { msg = "Export failed: " + t; }
            final String m = msg; a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } });
        } }, "bp-export").start();
    }
}
