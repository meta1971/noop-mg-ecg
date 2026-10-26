package com.noop.mgecg;

// FILE VERSION 0.14.0 (26 Oct): dated, catalogued exports: one folder and one zip per export, every file named with the date and time. Research only.

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Every export gets an id such as MG_2026-10-26_1052_24h (local date and time of the export, then the period). Download/MGexports/<id>/ holds each
 * file as <id>_<name> plus <id>_00_INDEX.txt (what each file is, how many rows, the time span of its data, size, md5) and <id>.zip with all of it, so one
 * file is enough to send. The app remembers its exports in the day-log database and shows them on the Export screen with Send and Delete.
 * Pure parts (names, row counts and time spans, index text, zip) are tested off the phone.
 */
final class ExportCatalog {
    private ExportCatalog() {}

    static final String ROOT = "MGexports";

    static final class Entry {
        String id, scope, zipUri, indexUri, rawUri;
        long madeMs, dataFrom, dataTo, bytes;
        int files;
    }

    // ------------------------------------------------------------------ names (pure)
    static String stamp(long ms) { return new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date(ms)); }
    static String scopeTag(int days) { return days <= 0 ? "all" : days == 1 ? "24h" : days + "d"; }
    static String scopeText(int days) { return days <= 0 ? "all data" : days == 1 ? "last 24 hours" : "last " + days + " days"; }
    static String id(long ms, int days) { return "MG_" + stamp(ms) + "_" + scopeTag(days); }
    static String shortName(String fileKey) { return fileKey.startsWith("mg_") ? fileKey.substring(3) : fileKey; }

    static String what(String shortName) {
        switch (shortName) {
            case "ecg_readings.csv": return "ECG readings saved by the app: time, heart rate, HRV, rhythm, grade";
            case "nights.csv": return "One row per night: sleep, resting heart rate, optical HRV, skin, breathing, SpO2";
            case "r18_minutes.csv": return "Per minute from the strap's per-second records: heart rate, beat intervals, skin, steps, state, SpO2 byte, pulse lock, quality, motion";
            case "ecg_status.csv": return "Stored ECG, one row per second: quality, verdict, progress, HRV field, lead-off";
            case "v26.csv": return "Optical pulse packets (layout 26) summarised per second";
            case "strap_events.csv": return "Strap events with their raw payload, from the app log";
            case "idx_runs.csv": return "Record counter against the strap clock: runs and gaps";
            case "export_info.csv": return "Counts and settings of this export";
            case "r16_fixture.json": return "Real, unedited stored-ECG frames with every decoded field (for decoder tests)";
            case "r16_fixture_hex.txt": return "The same frames, one hex line each";
            default: return "";
        }
    }

    /** {first time column, last time column, unit: 0 none, 1 seconds, 2 milliseconds} for the time span shown in the index. */
    static int[] timeCols(String shortName) {
        switch (shortName) {
            case "ecg_readings.csv": return new int[] {1, 1, 1};
            case "nights.csv": return new int[] {3, 4, 1};
            case "r18_minutes.csv": return new int[] {0, 0, 1};
            case "ecg_status.csv": return new int[] {0, 0, 1};
            case "v26.csv": return new int[] {0, 0, 1};
            case "strap_events.csv": return new int[] {0, 0, 2};
            case "idx_runs.csv": return new int[] {0, 1, 1};
            default: return new int[] {0, 0, 0};
        }
    }

    // ------------------------------------------------------------------ csv helpers (pure)
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = false; } else cur.append(ch); }
            else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static long num(List<String> cols, int i, int unit) {
        if (i < 0 || i >= cols.size()) return -1L;
        try { long v = Long.parseLong(cols.get(i).trim()); return unit == 2 ? v / 1000L : v; } catch (Exception e) { return -1L; }
    }

    /** {data rows, first time (s), last time (s)}; times are 0 when the file has no time columns or no rows. */
    static long[] csvWindow(String csv, int firstCol, int lastCol, int unit) {
        long rows = 0, first = 0, last = 0;
        boolean head = true;
        for (String line : csv.split("\n")) {
            if (line.isEmpty()) continue;
            if (head) { head = false; continue; }
            rows++;
            if (unit == 0) continue;
            List<String> c = splitCsv(line);
            long a = num(c, firstCol, unit), b = num(c, lastCol, unit);
            if (a > 0 && (first == 0 || a < first)) first = a;
            if (b > 0 && b > last) last = b;
            if (a > 0 && b <= 0 && a > last) last = a;
        }
        return new long[] {rows, first, last};
    }

    /** Keeps the header and every row whose time (column col) is at or after sinceSec; rows with no readable time are kept. */
    static String filterSince(String csv, int col, int unit, long sinceSec) {
        if (sinceSec <= 0 || unit == 0) return csv;
        StringBuilder out = new StringBuilder();
        boolean head = true;
        for (String line : csv.split("\n")) {
            if (line.isEmpty()) continue;
            if (head) { out.append(line).append('\n'); head = false; continue; }
            long t = num(splitCsv(line), col, unit);
            if (t < 0 || t >= sinceSec) out.append(line).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ index text (pure)
    static final class Row { String name, what, md5; long rows, from, to, bytes; }

    static String local(long sec) { return sec <= 0 ? "-" : new SimpleDateFormat("EEE d MMM yyyy HH:mm:ss", Locale.UK).format(new Date(sec * 1000L)); }
    static String utc(long sec) {
        if (sec <= 0) return "-";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new Date(sec * 1000L)) + " UTC";
    }

    static String buildIndex(String id, long madeMs, int days, long sinceSec, String appVersion, String phone, long newestStrapSec, boolean raw, List<Row> rows) {
        SimpleDateFormat tz = new SimpleDateFormat("EEE d MMM yyyy HH:mm:ss z (Z)", Locale.UK);
        StringBuilder s = new StringBuilder();
        s.append("MG strap data export\n");
        s.append("====================\n");
        s.append("Name:     ").append(id).append('\n');
        s.append("Made:     ").append(tz.format(new Date(madeMs))).append("   [").append(utc(madeMs / 1000L)).append("]\n");
        s.append("Period:   ").append(scopeText(days));
        if (sinceSec > 0) s.append(", from ").append(local(sinceSec)).append(" to ").append(local(madeMs / 1000L));
        s.append('\n');
        s.append("Newest strap record: ").append(local(newestStrapSec)).append('\n');
        s.append("App:      ").append(appVersion).append("   Phone: ").append(phone).append('\n');
        s.append("Times in the files are unix seconds (UTC) unless a column says local.\n");
        s.append("To send this data: send the ").append(id).append(".zip file; it holds everything listed here.\n\n");
        s.append("FILES\n");
        for (Row r : rows) {
            s.append("- ").append(r.name).append('\n');
            s.append("    ").append(r.what.isEmpty() ? "" : r.what).append('\n');
            s.append("    rows ").append(r.rows).append(", ").append(r.bytes < 1024 ? r.bytes + " B" : (r.bytes / 1024) + " KB");
            if (r.from > 0) s.append(", data ").append(local(r.from)).append(" to ").append(local(r.to));
            s.append(", md5 ").append(r.md5).append('\n');
        }
        if (raw) s.append("\n- ").append(id).append("_raw_history.zip\n    The raw history files (historical_bin_*.bin) of this period, as the strap sent them.\n");
        return s.toString();
    }

    // ------------------------------------------------------------------ zip (pure)
    static byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            ZipEntry ze = new ZipEntry(e.getKey());
            z.putNextEntry(ze);
            z.write(e.getValue());
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    static String md5(byte[] b) {
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            StringBuilder s = new StringBuilder();
            for (byte x : m.digest(b)) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Exception e) { return "?"; }
    }

    // ------------------------------------------------------------------ writing (Android)
    static Uri write(Context c, String sub, String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + ROOT + "/" + sub + "/");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("could not create " + name);
            OutputStream os = cr.openOutputStream(u);
            if (os == null) throw new Exception("could not open " + name);
            try { os.write(bytes); } finally { os.close(); }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(u, done, null, null);
            return u;
        }
        File dir = new File(c.getExternalFilesDir(null), ROOT + "/" + sub);
        dir.mkdirs();
        File f = new File(dir, name);
        java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
        try { fo.write(bytes); } finally { fo.close(); }
        return Uri.fromFile(f);
    }

    private static boolean idTaken(Context c, String id) {
        try {
            Cursor q = table(c).rawQuery("SELECT 1 FROM export_catalog WHERE id = ?", new String[] {id});
            try { return q.moveToFirst(); } finally { q.close(); }
        } catch (Throwable t) { return false; }
    }

    static SQLiteDatabase table(Context c) {
        SQLiteDatabase d = DayLog.db(c);
        d.execSQL("CREATE TABLE IF NOT EXISTS export_catalog (id TEXT PRIMARY KEY, made_ms INTEGER, scope TEXT, data_from INTEGER, data_to INTEGER, files INTEGER, bytes INTEGER, zip_uri TEXT, index_uri TEXT, raw_uri TEXT)");
        return d;
    }

    /**
     * Writes one export: the files, the index and the zip (and a raw-history zip when rawFiles is not empty), and remembers it.
     * @param files file key (mg_nights.csv ...) to text
     */
    static SummaryExport.Result commit(Context app, Map<String, String> files, long madeMs, int days, long sinceSec, String appVersion, String phone, long newestStrapSec, List<File> rawFiles) {
        SummaryExport.Result res = new SummaryExport.Result();
        String id = id(madeMs, days);
        for (int n = 2; idTaken(app, id); n++) id = id(madeMs, days) + "-" + n;
        List<Row> rows = new ArrayList<>();
        Map<String, byte[]> blobs = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            String sn = shortName(e.getKey());
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            int[] tc = timeCols(sn);
            long[] w = sn.endsWith(".csv") ? csvWindow(e.getValue(), tc[0], tc[1], tc[2]) : new long[] {0, 0, 0};
            Row r = new Row();
            r.name = id + "_" + sn; r.what = what(sn); r.md5 = md5(b); r.bytes = b.length; r.rows = w[0]; r.from = w[1]; r.to = w[2];
            rows.add(r);
            blobs.put(r.name, b);
            total += b.length;
        }
        boolean raw = rawFiles != null && !rawFiles.isEmpty();
        String index = buildIndex(id, madeMs, days, sinceSec, appVersion, phone, newestStrapSec, raw, rows);
        byte[] ib = index.getBytes(StandardCharsets.UTF_8);
        String indexName = id + "_00_INDEX.txt";
        blobs.put(indexName, ib);
        long dataFrom = 0, dataTo = 0;
        for (Row r : rows) { if (r.from > 0 && (dataFrom == 0 || r.from < dataFrom)) dataFrom = r.from; if (r.to > dataTo) dataTo = r.to; }
        Entry en = new Entry();
        en.id = id; en.madeMs = madeMs; en.scope = scopeText(days); en.dataFrom = dataFrom; en.dataTo = dataTo; en.files = blobs.size();
        StringBuilder err = new StringBuilder();
        try {
            for (Map.Entry<String, byte[]> b : blobs.entrySet()) {
                String mime = b.getKey().endsWith(".csv") ? "text/csv" : b.getKey().endsWith(".json") ? "application/json" : "text/plain";
                Uri u = write(app, id, b.getKey(), mime, b.getValue());
                if (b.getKey().equals(indexName) && u != null) en.indexUri = u.toString();
            }
            byte[] zb = zip(blobs);
            Uri zu = write(app, id, id + ".zip", "application/zip", zb);
            en.zipUri = zu == null ? null : zu.toString();
            en.bytes = zb.length;
            if (zu != null) res.uris.add(zu);
            if (raw) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                ZipOutputStream z = new ZipOutputStream(bo);
                byte[] buf = new byte[65536];
                for (File f : rawFiles) {
                    z.putNextEntry(new ZipEntry(f.getName()));
                    FileInputStream in = new FileInputStream(f);
                    try { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); } finally { in.close(); }
                    z.closeEntry();
                }
                z.close();
                Uri ru = write(app, id, id + "_raw_history.zip", "application/zip", bo.toByteArray());
                en.rawUri = ru == null ? null : ru.toString();
                en.bytes += bo.size();
            }
        } catch (Throwable t) { err.append(" FAILED: ").append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()); }
        if (en.zipUri != null) save(app, en);
        res.note = (en.zipUri == null ? "Export failed" : "Saved " + id + " (" + en.files + " files, " + sizeText(en.bytes) + ") in Download/" + ROOT + "/" + id) + err;
        return res;
    }

    static String sizeText(long b) { return b >= 1048576L ? String.format(Locale.UK, "%.1f MB", b / 1048576.0) : Math.max(1, b / 1024) + " KB"; }

    // ------------------------------------------------------------------ catalog
    static void save(Context c, Entry e) {
        try {
            table(c).execSQL("INSERT OR REPLACE INTO export_catalog (id, made_ms, scope, data_from, data_to, files, bytes, zip_uri, index_uri, raw_uri) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    new Object[] {e.id, e.madeMs, e.scope, e.dataFrom, e.dataTo, e.files, e.bytes, e.zipUri, e.indexUri, e.rawUri});
        } catch (Throwable ignored) { }
    }

    private static boolean exists(Context c, String uri) {
        if (uri == null) return false;
        try {
            Uri u = Uri.parse(uri);
            if ("file".equals(u.getScheme())) return new File(u.getPath()).exists();
            Cursor q = c.getContentResolver().query(u, new String[] {MediaStore.MediaColumns.SIZE}, null, null, null);
            if (q == null) return false;
            try { return q.moveToFirst(); } finally { q.close(); }
        } catch (Throwable t) { return false; }
    }

    /** Newest first. Exports whose zip was deleted in the Files app drop off the list. */
    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            SQLiteDatabase d = table(c);
            Cursor q = d.rawQuery("SELECT id, made_ms, scope, data_from, data_to, files, bytes, zip_uri, index_uri, raw_uri FROM export_catalog ORDER BY made_ms DESC", null);
            List<String> gone = new ArrayList<>();
            try {
                while (q.moveToNext()) {
                    Entry e = new Entry();
                    e.id = q.getString(0); e.madeMs = q.getLong(1); e.scope = q.getString(2); e.dataFrom = q.getLong(3); e.dataTo = q.getLong(4);
                    e.files = q.getInt(5); e.bytes = q.getLong(6); e.zipUri = q.getString(7); e.indexUri = q.getString(8); e.rawUri = q.getString(9);
                    if (exists(c, e.zipUri)) out.add(e); else gone.add(e.id);
                }
            } finally { q.close(); }
            for (String g : gone) d.execSQL("DELETE FROM export_catalog WHERE id = ?", new Object[] {g});
        } catch (Throwable ignored) { }
        return out;
    }

    static void delete(Context c, Entry e) {
        try {
            if (Build.VERSION.SDK_INT >= 29)
                c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[] {"Download/" + ROOT + "/" + e.id + "/"});
            else {
                File dir = new File(c.getExternalFilesDir(null), ROOT + "/" + e.id);
                File[] fs = dir.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                dir.delete();
            }
            table(c).execSQL("DELETE FROM export_catalog WHERE id = ?", new Object[] {e.id});
        } catch (Throwable ignored) { }
    }

    static void send(Activity a, String uri, String mime, String subject) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Send " + subject));
        } catch (Throwable t) { android.widget.Toast.makeText(a, "Could not open the share sheet", android.widget.Toast.LENGTH_SHORT).show(); }
    }
}
