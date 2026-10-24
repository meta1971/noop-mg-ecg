package com.noop.mgecg;

// FILE VERSION 0.1.0 (24 Oct 2026): one-tap export of everything needed to analyse the ECG readings off the phone.
// Writes one or more zips (about 240 MB each, so each is easy to upload) to Download/MGexport. Reads only the app's own ECG files; changes nothing.
// RESEARCH ONLY. The zips hold your heart recordings: share them only with people you trust.

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class EcgExport {
    private EcgExport() {}

    static final String FOLDER = "MGexport";
    /** Compressed bytes after which the next file starts a new zip part. */
    static final long PART_LIMIT = 240L * 1024L * 1024L;

    interface Progress { void on(String text); }

    static final class Result {
        int files, parts, readings;
        long bytes;
        String message = "";
        boolean ok;
    }

    /** The ECG-related files the app writes into its own folder (the same set Health's import understands, plus the H10 and strap logs). */
    static boolean wanted(String n) {
        return (n.startsWith("historical_bin_") && n.endsWith(".bin"))
                || (n.startsWith("ecg_report_") && n.endsWith(".html"))
                || (n.startsWith("ecg_strip_") && n.endsWith(".png"))
                || (n.startsWith("labrador_") && (n.endsWith(".txt") || n.endsWith(".bin")))
                || (n.startsWith("realtime_ecg_bin_") && n.endsWith(".bin"))
                || (n.startsWith("reconstructed_waveform_") && n.endsWith(".csv"))
                || (n.startsWith("h10_") && (n.endsWith(".csv") || n.endsWith(".txt")));
    }

    /** days = 0 exports everything, otherwise only files changed in the last `days` days. */
    static Result run(Context c, int days, Progress pr) {
        Result res = new Result();
        PartWriter pw = null;
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            File[] all = dir.listFiles();
            List<File> files = new ArrayList<File>();
            long cutoff = days <= 0 ? 0 : System.currentTimeMillis() - days * 86400000L;
            if (all != null) for (File f : all) if (f.isFile() && wanted(f.getName()) && f.lastModified() >= cutoff) files.add(f);
            Collections.sort(files, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); } });
            if (files.isEmpty()) { res.message = "No ECG files found in the app yet."; return res; }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.UK).format(new Date());
            pw = new PartWriter(c, stamp);
            pw.open();

            // ---- the readings table, the app's own ECG history and a manifest go in the first part
            String readings = readingsCsv(c, res);
            pw.addText("readings.csv", readings);
            String hist = c.getSharedPreferences("labrador_ecg_control", Context.MODE_PRIVATE).getString("ecg_history_json", "[]");
            pw.addText("ecg_history.json", hist);
            pw.addText("manifest.txt", manifest(c, files.size(), days, res));

            int i = 0;
            for (File f : files) {
                i++;
                if (pr != null && (i % 10 == 1 || i == files.size())) pr.on("Exporting " + i + " of " + files.size() + "\u2026");
                if (pw.compressedBytes() > PART_LIMIT) pw.nextPart();
                pw.addFile(f);
                res.files++;
            }
            pw.close();
            res.parts = pw.parts;
            res.bytes = pw.totalBytes;
            res.ok = true;
            res.message = "Saved " + res.files + " files in " + res.parts + (res.parts == 1 ? " zip" : " zips") + " (" + mb(res.bytes) + ") in Download/" + FOLDER;
        } catch (Throwable t) {
            res.ok = false;
            res.message = "Export stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            try { if (pw != null) pw.abort(); } catch (Throwable ignored) { }
        }
        return res;
    }

    private static volatile boolean running;

    /** The one-tap flow every screen uses: choose the range, export in the background, say where the zips are when done. */
    static void ask(final android.app.Activity act) {
        if (running) {
            android.widget.Toast.makeText(act, "An export is already running.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = {"Everything", "Last 30 days", "Last 7 days"};
        final int[] days = {0, 30, 7};
        new android.app.AlertDialog.Builder(act).setTitle("Export ECG data for analysis")
                .setItems(items, (d, w) -> startRun(act, days[w]))
                .setNegativeButton("Cancel", null).show();
    }

    private static void startRun(final android.app.Activity act, final int days) {
        running = true;
        final Context app = act.getApplicationContext();
        android.widget.Toast.makeText(act, "Exporting in the background. You can keep using the app.", android.widget.Toast.LENGTH_LONG).show();
        new Thread(() -> {
            final Result r = run(app, days, null);
            act.runOnUiThread(() -> {
                running = false;
                if (act.isFinishing()) return;
                new android.app.AlertDialog.Builder(act).setTitle(r.ok ? "Export finished" : "Export problem")
                        .setMessage(r.message + (r.ok ? "\n\nOpen your Files app, then Downloads, then MGexport. These zips hold your heart recordings: share them only with people you trust." : ""))
                        .setPositiveButton("OK", null).show();
            });
        }, "ecg-export").start();
    }

    private static String mb(long b) { return b >= 1073741824L ? String.format(Locale.UK, "%.2f GB", b / 1073741824.0) : String.format(Locale.UK, "%.0f MB", b / 1048576.0); }

    /** One row per saved report: time, heart rate, HRV, rhythm word, grade. Same columns as the Health screen shows. */
    private static String readingsCsv(Context c, Result res) {
        StringBuilder b = new StringBuilder("time_local,unix,hr_bpm,hrv_ms,rhythm,grade,report_file\n");
        try {
            List<HealthModel.Reading> rs = HealthModel.readings(c);
            Collections.sort(rs, new java.util.Comparator<HealthModel.Reading>() { @Override public int compare(HealthModel.Reading a, HealthModel.Reading z) { return Long.compare(a.timeMs, z.timeMs); } });
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK);
            for (HealthModel.Reading r : rs) {
                b.append(f.format(new Date(r.timeMs))).append(',').append(r.timeMs / 1000L).append(',').append(q(r.hr)).append(',').append(q(r.hrv)).append(',')
                        .append(q(r.rhythm)).append(',').append(q(r.grade)).append(',').append(r.file == null ? "" : r.file.getName()).append('\n');
            }
            res.readings = rs.size();
        } catch (Throwable t) {
            b.append("# could not read the saved reports: ").append(t.getClass().getSimpleName()).append('\n');
        }
        return b.toString();
    }

    private static String q(String s) { return s == null ? "" : s.replace(",", ";").replace("\n", " "); }

    private static String manifest(Context c, int fileCount, int days, Result res) {
        String ver = "?";
        try { ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable ignored) { }
        StringBuilder m = new StringBuilder();
        m.append("MG ECG export for analysis\n");
        m.append("created: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.UK).format(new Date())).append('\n');
        m.append("app version: ").append(ver).append('\n');
        m.append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n');
        m.append("range: ").append(days <= 0 ? "everything" : "last " + days + " days").append('\n');
        m.append("files: ").append(fileCount).append('\n');
        m.append("saved reports in the readings table: ").append(res.readings).append('\n');
        m.append("\nWhat is inside\n");
        m.append("  historical_bin_*.bin      the strap's history pulls (raw ECG, optical, motion and summary records)\n");
        m.append("  ecg_report_*.html         one report per reading\n");
        m.append("  ecg_strip_*.png           the strip picture of each reading\n");
        m.append("  labrador_*                strap session logs, snapshots and the app's own analysis text\n");
        m.append("  h10_*                     Polar H10 reference recordings, when you made them\n");
        m.append("  readings.csv              one row per saved report\n");
        m.append("  ecg_history.json          the app's own list of readings, with the strap's verdict\n");
        m.append("\nThese files contain your heart recordings. Share them only with people you trust.\n");
        m.append("Research instrumentation, not a medical device.\n");
        return m.toString();
    }

    // ================================================================ zip parts

    /** A zip written straight into Download/MGexport through MediaStore (or the app's own folder before Android 10), rolled over by size. */
    private static final class PartWriter {
        private final Context c;
        private final String stamp;
        int parts;
        long totalBytes;
        private ZipOutputStream zip;
        private CountingStream counting;
        private Uri uri;
        private File fallback;
        private OutputStream raw;

        PartWriter(Context c, String stamp) { this.c = c; this.stamp = stamp; }

        void open() throws IOException {
            parts++;
            String name = "mg_ecg_export_" + stamp + "_part" + parts + ".zip";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + FOLDER + "/");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("could not create " + name);
                raw = cr.openOutputStream(uri);
                if (raw == null) throw new IOException("could not open " + name);
            } else {
                File d = new File(c.getExternalFilesDir(null), "export");
                if (!d.exists()) d.mkdirs();
                fallback = new File(d, name);
                raw = new FileOutputStream(fallback);
            }
            counting = new CountingStream(new BufferedOutputStream(raw, 1 << 16));
            zip = new ZipOutputStream(counting);
            zip.setLevel(6);
        }

        long compressedBytes() { return counting == null ? 0 : counting.count; }

        void nextPart() throws IOException {
            finishPart();
            open();
        }

        private void finishPart() throws IOException {
            zip.finish();
            zip.close();
            totalBytes += counting.count;
            if (uri != null) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                c.getContentResolver().update(uri, done, null, null);
            }
            zip = null; counting = null; uri = null;
        }

        void close() throws IOException { finishPart(); }

        /** Leaves no half-written zip behind after a failure. */
        void abort() {
            try { if (zip != null) zip.close(); } catch (Throwable ignored) { }
            try { if (uri != null) c.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) { }
            try { if (fallback != null) fallback.delete(); } catch (Throwable ignored) { }
        }

        void addText(String name, String text) throws IOException {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        void addFile(File f) throws IOException {
            ZipEntry e = new ZipEntry(f.getName());
            e.setTime(f.lastModified());
            zip.putNextEntry(e);
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            } finally {
                in.close();
            }
            zip.closeEntry();
        }
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
