package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): makes the professional ECG report (PDF) for one saved reading, from the reading's own live raw frames, and saves it in Download/MGreports.

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Locale;

final class ProReportMaker {
    private ProReportMaker() {}
    static final String FOLDER = "MGreports";
    private static volatile boolean busy;

    /** The reading's saved report is ecg_report_<unix>.html; the live raw frames are realtime_ecg_bin_<unix>.bin saved within a few seconds of it. 0.23.2: the LARGEST file within 30 s (a one-frame stub written just before the session start event used to win as the nearest name). */
    static File findBin(File dir, long unix) {
        File best = null; long bl = -1;
        File[] fs = dir.listFiles(); if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName(); if (!n.startsWith("realtime_ecg_bin_") || !n.endsWith(".bin")) continue;
            try { long s = Long.parseLong(n.substring(17, n.length() - 4)); long d = Math.abs(s - unix); if (d <= 30 && f.length() > bl) { bl = f.length(); best = f; } } catch (NumberFormatException ignored) { }
        }
        return best;
    }
    /** An H10 recording that was running when the reading started (file name = its start in unix seconds). */
    static File findH10(File dir, double readingStart, double readingSeconds) {
        File best = null; long bs = -1; File[] fs = dir.listFiles(); if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName(); if (!n.startsWith("h10_ecg_") || !n.endsWith(".csv")) continue;
            try { long s = Long.parseLong(n.substring(8, n.length() - 4)); if (s <= readingStart + 5 && s >= readingStart - 1800 && s > bs) { bs = s; best = f; } } catch (NumberFormatException ignored) { }
        }
        return best;
    }
    static long unixOf(File html) { String d = html.getName().replaceAll("[^0-9]", ""); try { return Long.parseLong(d.length() > 10 ? d.substring(0, 10) : d); } catch (Exception e) { return 0L; } }

    static void make(final Activity a, final File html) {
        if (html == null) { Toast.makeText(a, "No reading selected.", Toast.LENGTH_SHORT).show(); return; }
        if (busy) { Toast.makeText(a, "A report is already being made.", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        Toast.makeText(a, "Making the professional report (about 15 seconds)...", Toast.LENGTH_LONG).show();
        new Thread(new Runnable() { @Override public void run() {
            String msg; Uri open = null;
            try {
                File dir = html.getParentFile(); File bin = findBin(dir, unixOf(html));
                if (bin == null) msg = "No live raw frames were saved for this reading (readings before 0.17.0, or with live raw switched off, cannot be analysed at 500 Hz).";
                else if (bin.length() > 60L * 1024 * 1024) msg = "The frame file of this reading is too large to analyse here.";
                else {
                    ProAnalysis.Result r = ProAnalysis.analyse(Files.readAllBytes(bin.toPath()));
                    if (r == null) msg = "The report cannot be made: " + ProAnalysis.why + ". It needs 20 seconds of frames, a stretch of 8 clean seconds and at least 30 clean beats.";
                    else {
                        try { r.tag = ReadingTag.forReading(dir, r.t0); ReadingFrames.Sets stored = ReadingFrames.collect(dir, r.t0 - 600, r.t0 + r.seconds); r.pat = ProDetect.pat(r, stored.r20); r.ex = ProDetect.exercise(r, stored.r18); } catch (Throwable ignored) { r.pat = null; }
                        H10Compare.Result h = null; File hf = findH10(dir, r.t0, r.seconds);
                        if (hf != null) { try { h = H10Compare.compare(r, H10Compare.read(hf)); } catch (Throwable ignored) { h = null; } }
                        ProPdf.Info info = new ProPdf.Info(); info.when = ProPdf.whenText(r.t0); info.fileName = html.getName();
                        info.note = "Source files: " + bin.getName() + (hf != null ? ", " + hf.getName() : "") + ". App build " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName + ".";
                        ProPdfAndroid pen = new ProPdfAndroid(); ProPdf.render(pen, r, h, info);
                        String name = "MG_ECG_report_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.UK).format(new java.util.Date((long) (r.t0 * 1000))) + ".pdf";
                        open = save(a, name, pen);
                        msg = open != null ? "Saved in Download/" + FOLDER + ": " + name + (h != null && h.nPaired >= 20 ? " (with the H10 check)" : "") : "Could not save the report.";
                    }
                }
            } catch (Throwable t) { msg = "The report failed: " + t; }
            final String m = msg; final Uri u = open;
            a.runOnUiThread(new Runnable() { @Override public void run() {
                busy = false; Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                if (u != null) { try { Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(Intent.createChooser(v, "Open the report")); } catch (Throwable ignored) { } }
            } });
        } }, "pro-report").start();
    }

    private static Uri save(Activity a, String name, ProPdfAndroid pen) throws Exception {
        ContentResolver cr = a.getContentResolver(); ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf"); v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER + "/"); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v); if (uri == null) return null;
        try (OutputStream os = cr.openOutputStream(uri)) { pen.write(os); }
        ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, done, null, null);
        return uri;
    }

    /** 0.23.1: writes the bundled analysis code (Python reference and the Java port) to Download/MGreports/MG_ECG_analysis_code.zip. */
    static void saveCode(final Activity a) {
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                ContentResolver cr = a.getContentResolver(); ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "MG_ECG_analysis_code.zip"); v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip"); v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER + "/"); v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) msg = "Could not save the analysis code.";
                else { try (OutputStream os = cr.openOutputStream(uri)) { os.write(AnalysisCodeData.bytes()); } ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, done, null, null); msg = "Saved in Download/" + FOLDER + ": MG_ECG_analysis_code.zip"; }
            } catch (Throwable t) { msg = "Could not save the analysis code: " + t; }
            final String m = msg; a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } });
        } }, "save-code").start();
    }
}
