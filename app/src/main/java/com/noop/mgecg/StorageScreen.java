package com.noop.mgecg;

// FILE VERSION 0.39.0 (3 Nov 2026): ARCHIVE card: files older than 3 / 7 / 14 days packed into files/archive zips (each entry verified by size and CRC32 before the original goes), and put back. 0.36.0 (1 Nov 2026): Storage. What takes the space (the app's folder by kind, the database, the backup folder), buttons that trim the H10 ECG files (keeping the minutes around
// readings) and old raw history in the app and in the backup copies, a shorter run of database snapshots, an optional nightly auto-trim, ONE backup zip of everything (compact or full), and a Restore from
// such a zip. Deleting is irreversible, so every button first says what it will remove and asks. The per-second records stay in the database whatever is trimmed.

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.noop.mgecg.HealthViews.*;

final class StorageScreen {
    private StorageScreen() {}

    static final int REQ_RESTORE = 7410;
    private static final String PREFS = "storage";
    static final String[] CAT = { "H10 ECG files (130 Hz)", "Raw history from the strap", "Live reading frames", "Reports and strips", "H10 R-R, logs and events", "Everything else" };

    static int category(String n) {
        if (n.startsWith("h10_ecg_")) return 0; if (n.startsWith("historical_bin_")) return 1; if (n.startsWith("realtime_ecg_bin_")) return 2;
        if (n.startsWith("ecg_report_") || n.startsWith("ecg_strip_")) return 3; if (n.startsWith("h10_")) return 4; return 5;
    }
    static long stamp(String name) { try { String d = name.replaceAll("[^0-9]", ""); return d.length() >= 10 ? Long.parseLong(d.substring(0, 10)) : 0L; } catch (Exception e) { return 0L; } }

    // ------------------------------------------------------------------ pure rules (tested off the phone)
    /** H10 ECG file: keep when younger than 24 h or when it can overlap a reading (a file runs at most 30 minutes from its stamp). */
    static boolean keepH10(long stampSec, long nowSec, long[] readings) {
        if (stampSec >= nowSec - 86400L) return true;
        for (long r : readings) if (stampSec <= r + 900 && stampSec + 1800 >= r - 120) return true;
        return false;
    }
    /** Raw history file: keep when newer than the cut-off or when a reading lies within an hour of its stamp (the stamp is the time of the pull that wrote it). */
    static boolean keepRaw(long stampSec, long nowSec, int days, long[] readings) {
        if (stampSec >= nowSec - days * 86400L) return true;
        for (long r : readings) if (r >= stampSec - 3600L && r <= stampSec + 60L) return true;
        return false;
    }

    static long[] readingTimes(File dir) {
        List<Long> v = new ArrayList<Long>(); File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) for (File f : fs) { String n = f.getName(); if (n.startsWith("ecg_report_") && n.endsWith(".html")) { long s = stamp(n); if (s > 0) v.add(s); } }
        long[] o = new long[v.size()]; for (int i = 0; i < o.length; i++) o[i] = v.get(i); return o;
    }

    // ------------------------------------------------------------------ sizes
    static final class Sizes { final long[] app = new long[6]; final int[] appN = new int[6]; long db, mirrorDb, mirrorPrefs, mirrorZip; final long[] mirrorFiles = new long[6]; final int[] mirrorN = new int[6]; long free; }

    static void walk(File d, Sizes s, int depth) {
        File[] fs = d.listFiles(); if (fs == null || depth > 3) return;
        for (File f : fs) { if (f.isDirectory()) { if (!f.getName().equals(Backup.ROOT)) walk(f, s, depth + 1); } else { int k = category(f.getName()); s.app[k] += f.length(); s.appN[k]++; } }
    }

    static Sizes measure(Context c) {
        Sizes s = new Sizes(); File root = c.getExternalFilesDir(null); if (root != null) { walk(root, s, 0); try { StatFs st = new StatFs(root.getAbsolutePath()); s.free = st.getAvailableBytes(); } catch (Throwable ignored) { } }
        File db = c.getDatabasePath("daylog.db"); s.db = db.length() + new File(db.getPath() + "-wal").length() + new File(db.getPath() + "-shm").length();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[] { MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE },
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[] { "Download/" + Backup.ROOT + "/%" }, null);
                try { while (q != null && q.moveToNext()) { String rp = q.getString(0), n = q.getString(1); long sz = q.getLong(2); if (rp.contains("/db/")) s.mirrorDb += sz; else if (rp.contains("/prefs/")) s.mirrorPrefs += sz; else if (rp.contains("/zip/")) s.mirrorZip += sz; else { int k = category(n); s.mirrorFiles[k] += sz; s.mirrorN[k]++; } } } finally { if (q != null) q.close(); }
            }
        } catch (Throwable ignored) { }
        return s;
    }

    // ------------------------------------------------------------------ trimming
    static final class Plan { final List<File> appDel = new ArrayList<File>(); final List<Long> mirrorIds = new ArrayList<Long>(); final List<String> names = new ArrayList<String>(); long bytes; int files; }

    /** which = 0: H10 ECG outside readings; which = 1: raw history older than days. Lists what would be removed from the app folder and from the backup copies. */
    static Plan plan(Context c, int which, int days) {
        Plan p = new Plan(); File root = c.getExternalFilesDir(null); long now = System.currentTimeMillis() / 1000L; long[] rd = readingTimes(root); String prefix = which == 0 ? "h10_ecg_" : "historical_bin_";
        File[] fs = root == null ? null : root.listFiles();
        if (fs != null) for (File f : fs) { String n = f.getName(); if (!n.startsWith(prefix)) continue; long st = stamp(n); if (st == 0) continue;
            boolean keep = which == 0 ? keepH10(st, now, rd) : keepRaw(st, now, days, rd);
            if (!keep) { p.appDel.add(f); p.names.add(n); p.bytes += f.length(); p.files++; } }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[] { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE },
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[] { "Download/" + Backup.ROOT + "/files/%", prefix + "%" }, null);
                try { while (q != null && q.moveToNext()) { String n = q.getString(1); long st = stamp(n); if (st == 0) continue; boolean keep = which == 0 ? keepH10(st, now, rd) : keepRaw(st, now, days, rd); if (!keep) { p.mirrorIds.add(q.getLong(0)); p.bytes += q.getLong(2); p.files++; p.names.add(n); } } } finally { if (q != null) q.close(); }
            }
        } catch (Throwable ignored) { }
        return p;
    }

    static int apply(Context c, Plan p) {
        int n = 0; for (File f : p.appDel) if (f.delete()) n++;
        try { ContentResolver cr = c.getContentResolver(); for (long id : p.mirrorIds) n += cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null); } catch (Throwable ignored) { }
        try { SQLiteDatabase d = DayLog.db(c); d.execSQL("CREATE TABLE IF NOT EXISTS backup_files (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, uri TEXT)"); for (String nm : p.names) d.execSQL("DELETE FROM backup_files WHERE path = ?", new Object[] { nm }); } catch (Throwable ignored) { }
        return n;
    }

    /** Nightly when switched on: the H10 ECG rule only (never raw history), at most once a day. Called after each history ingest. */
    static void autoTrim(Context c) {
        try {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); if (!sp.getBoolean("auto", false)) return;
            long now = System.currentTimeMillis(); if (now - sp.getLong("auto_last", 0L) < 20L * 3600L * 1000L) return; sp.edit().putLong("auto_last", now).apply();
            Plan p = plan(c.getApplicationContext(), 0, 0); int n = apply(c.getApplicationContext(), p); sp.edit().putString("auto_note", new SimpleDateFormat("d MMM HH:mm", Locale.UK).format(new Date(now)) + ": removed " + n + " H10 ECG files, " + Backup.sizeText(p.bytes)).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ 0.39.0 archive (organise the flat folder)
    static final String ARCHIVE_DIR = "archive";
    static final String[] ARCH_PREFIX = { "h10_ecg_", "h10_rr_", "h10_log_", "historical_bin_", "labrador_log_", "reconstructed_waveform", "unknown_frames_" };

    static boolean archivable(String n) { for (String p : ARCH_PREFIX) if (n.startsWith(p)) return true; return false; }

    /**
     * Pure: should this file stay in the flat folder? Per-reading files are never archivable (not in ARCH_PREFIX). Younger than the cut-off stays.
     * h10_* files follow keepH10, historical_bin_ follows keepRaw (same rules as the trims), and a labrador_log_ file stays when a reading started
     * between its stamp and the next log's stamp (it holds that reading's log lines, read by StoredReading, SummaryExport and ReadingPull).
     */
    static boolean archiveKeep(String n, long st, long nextLogSt, long nowSec, int days, long[] readings) {
        if (!archivable(n) || st == 0) return true;
        if (st >= nowSec - days * 86400L) return true;
        if (n.startsWith("h10_")) return keepH10(st, nowSec, readings);
        if (n.startsWith("historical_bin_")) return keepRaw(st, nowSec, days, readings);
        if (n.startsWith("labrador_log_")) { if (nextLogSt <= st) return true; long end = nextLogSt;      // the newest log may still be open
            for (long r : readings) if (r >= st - 60 && r < end) return true; return false; }
        return false;
    }

    static final class ArchPlan { final java.util.TreeMap<String, List<File>> byDay = new java.util.TreeMap<String, List<File>>(); long bytes; int files; }

    static ArchPlan archPlan(Context c, int days) {
        ArchPlan p = new ArchPlan(); File root = c.getExternalFilesDir(null); if (root == null) return p;
        long now = System.currentTimeMillis() / 1000L; long[] rd = readingTimes(root); File[] fs = root.listFiles(); if (fs == null) return p;
        List<Long> logs = new ArrayList<Long>(); for (File f : fs) if (f.isFile() && f.getName().startsWith("labrador_log_")) { long st = stamp(f.getName()); if (st > 0) logs.add(st); }
        java.util.Collections.sort(logs);
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        for (File f : fs) {
            if (!f.isFile()) continue; String n = f.getName(); long st = stamp(n);
            long next = 0; if (n.startsWith("labrador_log_")) for (long l : logs) if (l > st) { next = l; break; }
            if (archiveKeep(n, st, next, now, days, rd)) continue;
            String k = day.format(new Date(st * 1000L)); List<File> l = p.byDay.get(k); if (l == null) { l = new ArrayList<File>(); p.byDay.put(k, l); }
            l.add(f); p.bytes += f.length(); p.files++;
        }
        return p;
    }

    static long crcOf(InputStream in) throws Exception { java.util.zip.CRC32 c = new java.util.zip.CRC32(); try { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) c.update(b, 0, k); } finally { in.close(); } return c.getValue(); }

    /** Packs, verifies every entry (size and CRC32 against the original), then deletes the originals and their backup-mirror copies. Returns {moved, failed}. */
    static int[] archApply(Context c, ArchPlan p, Say say) {
        File root = c.getExternalFilesDir(null); File ad = new File(root, ARCHIVE_DIR); ad.mkdirs();
        String run = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(new Date());
        int moved = 0, failed = 0; List<String> gone = new ArrayList<String>();
        for (java.util.Map.Entry<String, List<File>> e : p.byDay.entrySet()) {
            File zf = new File(ad, "MG_archive_" + e.getKey() + "_" + run + ".zip");
            try {
                java.util.Map<String, long[]> want = new java.util.HashMap<String, long[]>();
                ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zf));
                try { for (File f : e.getValue()) { z.putNextEntry(new ZipEntry(f.getName())); pump(new FileInputStream(f), z); z.closeEntry(); want.put(f.getName(), new long[] { f.length(), crcOf(new FileInputStream(f)) }); } } finally { z.close(); }
                java.util.Map<String, long[]> got = new java.util.HashMap<String, long[]>();
                ZipInputStream zi = new ZipInputStream(new FileInputStream(zf)); ZipEntry ze; byte[] b = new byte[65536];
                try { while ((ze = zi.getNextEntry()) != null) { java.util.zip.CRC32 cr = new java.util.zip.CRC32(); long len = 0; int k; while ((k = zi.read(b)) > 0) { cr.update(b, 0, k); len += k; } got.put(ze.getName(), new long[] { len, cr.getValue() }); } } finally { zi.close(); }
                for (File f : e.getValue()) {
                    long[] w = want.get(f.getName()), g = got.get(f.getName());
                    if (w != null && g != null && w[0] == g[0] && w[1] == g[1] && f.length() == w[0]) { if (f.delete()) { moved++; gone.add(f.getName()); } else failed++; } else failed++;
                }
            } catch (Throwable t) { failed += e.getValue().size(); }
            if (say != null) say.on("Archived " + moved + " of " + p.files + " files...");
        }
        removeMirror(c, gone);
        return new int[] { moved, failed };
    }

    /** Deletes the backup-mirror copies of these file names (Download/MGbackup/files/...) and their backup_files rows, as the trims do. */
    static void removeMirror(Context c, List<String> names) {
        if (names.isEmpty()) return; java.util.Set<String> set = new java.util.HashSet<String>(names);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                List<Long> ids = new ArrayList<Long>();
                Cursor q = c.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[] { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME },
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[] { "Download/" + Backup.ROOT + "/files/%" }, null);
                try { while (q != null && q.moveToNext()) if (set.contains(q.getString(1))) ids.add(q.getLong(0)); } finally { if (q != null) q.close(); }
                Plan p = new Plan(); p.mirrorIds.addAll(ids); p.names.addAll(names); apply(c, p);
            } else { Plan p = new Plan(); p.names.addAll(names); apply(c, p); }
        } catch (Throwable ignored) { }
    }

    /** Unpacks every archive zip into the flat folder (a file of the same size already there is skipped); a zip whose entries are all back is then removed. Returns {put back, skipped, zips removed}. */
    static int[] archRestore(Context c, Say say) {
        File root = c.getExternalFilesDir(null); File ad = new File(root, ARCHIVE_DIR); File[] zs = ad.listFiles(); int back = 0, skip = 0, zipsGone = 0; List<String> goneZips = new ArrayList<String>();
        if (zs == null) return new int[] { 0, 0, 0 };
        byte[] b = new byte[65536];
        for (File zf : zs) {
            if (!zf.getName().endsWith(".zip")) continue; boolean all = true;
            try {
                ZipInputStream zi = new ZipInputStream(new FileInputStream(zf)); ZipEntry ze;
                try { while ((ze = zi.getNextEntry()) != null) {
                    String nm = ze.getName(); if (ze.isDirectory() || nm.contains("/") || nm.contains("..")) continue;
                    File t = new File(root, nm); File tmp = new File(root, nm + ".part"); long len = 0;
                    try (FileOutputStream fo = new FileOutputStream(tmp)) { int k; while ((k = zi.read(b)) > 0) { fo.write(b, 0, k); len += k; } }
                    if (t.exists() && t.length() == len) { tmp.delete(); skip++; } else { t.delete(); if (tmp.renameTo(t)) back++; else { tmp.delete(); all = false; } }
                } } finally { zi.close(); }
            } catch (Throwable t) { all = false; }
            if (all && zf.delete()) { zipsGone++; goneZips.add(zf.getName()); }
            if (say != null) say.on("Put back " + back + " files...");
        }
        removeMirror(c, goneZips);
        return new int[] { back, skip, zipsGone };
    }

    // ------------------------------------------------------------------ one backup zip, and restore (pure parts)
    interface Say { void on(String s); }

    /** Writes the backup zip: MANIFEST.txt, db/daylog.db (a snapshot copy), prefs/<xml>, files/<relative path>. everything = false leaves out the H10 ECG and raw history files. Returns the number of files added. */
    static int writeZip(OutputStream out, File filesDir, File dbCopy, File prefsDir, boolean everything, String manifest, Say say) throws Exception {
        ZipOutputStream z = new ZipOutputStream(out); int n = 0;
        try {
            z.putNextEntry(new ZipEntry("MANIFEST.txt")); z.write(manifest.getBytes("UTF-8")); z.closeEntry();
            if (dbCopy != null && dbCopy.exists()) { z.putNextEntry(new ZipEntry("db/daylog.db")); pump(new FileInputStream(dbCopy), z); z.closeEntry(); }
            File[] ps = prefsDir == null ? null : prefsDir.listFiles(); if (ps != null) for (File f : ps) if (f.isFile() && f.getName().endsWith(".xml")) { z.putNextEntry(new ZipEntry("prefs/" + f.getName())); pump(new FileInputStream(f), z); z.closeEntry(); }
            List<File> all = new ArrayList<File>(); collect(filesDir, all, 0);
            for (File f : all) {
                String nm = f.getName(); if (!everything && (nm.startsWith("h10_ecg_") || nm.startsWith("historical_bin_"))) continue;
                String rel = f.getAbsolutePath().substring(filesDir.getAbsolutePath().length() + 1).replace('\\', '/');
                z.putNextEntry(new ZipEntry("files/" + rel)); pump(new FileInputStream(f), z); z.closeEntry(); n++; if (say != null && n % 40 == 0) say.on("Zipping files... " + n + " of " + all.size());
            }
        } finally { z.close(); }
        return n;
    }
    private static void collect(File d, List<File> out, int depth) { File[] fs = d.listFiles(); if (fs == null || depth > 3) return; for (File f : fs) { if (f.isDirectory()) { if (!f.getName().equals(Backup.ROOT)) collect(f, out, depth + 1); } else out.add(f); } }
    private static void pump(InputStream in, OutputStream out) throws Exception { try { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) out.write(b, 0, k); } finally { in.close(); } }

    static final class Restored { int files, skipped; boolean db; int prefs; String manifest = ""; }

    /** Reads a backup zip: files go into filesDir unless one of the same size is already there; the database copy goes to dbOut; settings go to prefsOut (overwritten). */
    static Restored readZip(InputStream in, File filesDir, File dbOut, File prefsOut) throws Exception {
        ZipInputStream z = new ZipInputStream(in); ZipEntry e; Restored r = new Restored(); byte[] b = new byte[65536];
        try {
            while ((e = z.getNextEntry()) != null) {
                String nm = e.getName(); if (e.isDirectory() || nm.contains("..")) continue;
                if (nm.equals("MANIFEST.txt")) { java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); int k; while ((k = z.read(b)) > 0) bo.write(b, 0, k); r.manifest = bo.toString("UTF-8"); }
                else if (nm.equals("db/daylog.db")) { try (FileOutputStream fo = new FileOutputStream(dbOut)) { int k; while ((k = z.read(b)) > 0) fo.write(b, 0, k); } r.db = true; }
                else if (nm.startsWith("prefs/")) { prefsOut.mkdirs(); try (FileOutputStream fo = new FileOutputStream(new File(prefsOut, nm.substring(6)))) { int k; while ((k = z.read(b)) > 0) fo.write(b, 0, k); } r.prefs++; }
                else if (nm.startsWith("files/")) {
                    File t = new File(filesDir, nm.substring(6)); t.getParentFile().mkdirs();
                    File tmp = new File(t.getPath() + ".part"); long len = 0; try (FileOutputStream fo = new FileOutputStream(tmp)) { int k; while ((k = z.read(b)) > 0) { fo.write(b, 0, k); len += k; } }
                    if (t.exists() && t.length() == len) { tmp.delete(); r.skipped++; } else { t.delete(); if (!tmp.renameTo(t)) throw new Exception("could not write " + nm); r.files++; }
                }
            }
        } finally { z.close(); }
        return r;
    }

    // ------------------------------------------------------------------ the screen
    private static TextView text(Context a, String t, int sp, int color) { TextView x = HeartGraph.label(a, t, sp, color, 400); x.setLineSpacing(0, 1.25f); return x; }
    private static LinearLayout card(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(glass(c, 18)); int p = Math.round(dp(c, 16)); l.setPadding(p, p, p, p); return l; }

    static void show(final Activity a) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE); dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Storage", 22, INK, 300)); top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        dlg.setContentView(root); dlg.show();
        final Handler ui = new Handler(Looper.getMainLooper()); final Runnable[] reload = new Runnable[1];
        reload[0] = new Runnable() { @Override public void run() {
            col.removeAllViews(); final TextView st = text(a, "Measuring...", 13, MUT); col.addView(st, HeartGraph.lp(a, -1, -2, 14));
            new Thread(new Runnable() { @Override public void run() { Sizes s0 = null; try { s0 = measure(a); } catch (Throwable ignored) { } final Sizes s = s0; ui.post(new Runnable() { @Override public void run() { try { col.removeView(st); fill(a, ui, col, s, reload[0]); } catch (Throwable ignored) { } } }); } }, "storage-measure").start(); } };
        reload[0].run();
    }

    private static void fill(final Activity a, final Handler ui, final LinearLayout col, Sizes s, final Runnable reload) {
        if (s == null) { col.addView(text(a, "Could not measure.", 13, ROSE)); return; }
        long appTot = 0; for (long v : s.app) appTot += v; long mTot = s.mirrorDb + s.mirrorPrefs + s.mirrorZip; for (long v : s.mirrorFiles) mTot += v;
        LinearLayout c1 = card(a); c1.addView(HeartGraph.label(a, "WHAT TAKES THE SPACE", 11, MUT, 400));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 6; k++) sb.append(String.format(Locale.UK, "%-28s %9s  (%d files)   backup copy %s%n", CAT[k], Backup.sizeText(s.app[k]), s.appN[k], Backup.sizeText(s.mirrorFiles[k])));
        sb.append(String.format(Locale.UK, "%-28s %9s%n", "Database", Backup.sizeText(s.db)));
        TextView t1 = text(a, sb.toString().trim(), 11, INK); t1.setTypeface(android.graphics.Typeface.MONOSPACE); c1.addView(t1, HeartGraph.lp(a, -1, -2, 8));
        c1.addView(text(a, String.format(Locale.UK, "The app's folder and database: %s. The backup folder (Download/MGbackup): %s, of which database snapshots %s, settings %s, backup zips %s. Free on the phone: %s.", Backup.sizeText(appTot + s.db), Backup.sizeText(mTot), Backup.sizeText(s.mirrorDb), Backup.sizeText(s.mirrorPrefs), Backup.sizeText(s.mirrorZip), Backup.sizeText(s.free)), 13, INK), HeartGraph.lp(a, -1, -2, 8));
        col.addView(c1, HeartGraph.lp(a, -1, -2, 12));

        LinearLayout c2 = card(a); c2.addView(HeartGraph.label(a, "FREE SPACE", 11, MUT, 400));
        c2.addView(text(a, "Nothing leaves the database: every second the strap recorded stays there. Each button shows what it will remove and asks first.", 12, MUT), HeartGraph.lp(a, -1, -2, 6));
        TextView b1 = HeartGraph.pill(a, "Trim H10 ECG files (keep readings and the last 24 h)", BLUE, 0xFF05131E); b1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmTrim(a, ui, 0, 0, reload); } }); c2.addView(b1, HeartGraph.lp(a, -2, -2, 12));
        TextView b2 = HeartGraph.pill(a, "Delete raw history older than 14 days", 0x1AFFFFFF, INK); b2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmTrim(a, ui, 1, 14, reload); } }); c2.addView(b2, HeartGraph.lp(a, -2, -2, 10));
        c2.addView(text(a, "Raw history older than 14 days is kept when a reading lies in it. Deleting the rest means raw exports and Full pulls of readings in those files lose their stored 50 Hz and 100 Hz parts.", 11, MUT), HeartGraph.lp(a, -1, -2, 4));
        final TextView b3 = HeartGraph.pill(a, "Keep " + Backup.keep(a) + " database snapshots (tap for 3)", 0x1AFFFFFF, INK);
        b3.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Backup.setKeep(a, 3); int n = Backup.pruneNow(a); Toast.makeText(a, "Keeping 3 snapshots; removed " + n, Toast.LENGTH_LONG).show(); reload.run(); } }); c2.addView(b3, HeartGraph.lp(a, -2, -2, 10));
        final SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final TextView b4 = HeartGraph.pill(a, sp.getBoolean("auto", false) ? "Nightly H10 ECG trim: ON (tap to turn off)" : "Nightly H10 ECG trim: off (tap to turn on)", 0x1AFFFFFF, INK);
        b4.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sp.edit().putBoolean("auto", !sp.getBoolean("auto", false)).apply(); reload.run(); } }); c2.addView(b4, HeartGraph.lp(a, -2, -2, 10));
        String an = sp.getString("auto_note", ""); if (an.length() > 0) c2.addView(text(a, "Last automatic trim: " + an, 11, MUT), HeartGraph.lp(a, -1, -2, 4));
        col.addView(c2, HeartGraph.lp(a, -1, -2, 12));

        LinearLayout ca = card(a); ca.addView(HeartGraph.label(a, "ARCHIVE", 11, MUT, 400));      // 0.39.0
        ca.addView(text(a, "Packs H10 files, raw history, app logs, reconstructed waveforms and unknown frames older than N days into one zip per day in the app folder's archive sub-folder. "
            + "Every file a reading needs stays (the same rules as the trims, and the log of each reading's app session), and every per-reading file stays. Each entry is read back and checked (size and CRC32) before the original is removed. "
            + "Nothing is deleted for good: Put archived files back unpacks them.", 12, MUT), HeartGraph.lp(a, -1, -2, 6));
        for (final int dd : new int[] { 3, 7, 14 }) {
            TextView ab = HeartGraph.pill(a, "Archive files older than " + dd + " days", dd == 7 ? BLUE : 0x1AFFFFFF, dd == 7 ? 0xFF05131E : INK);
            ab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmArchive(a, ui, dd, reload); } }); ca.addView(ab, HeartGraph.lp(a, -2, -2, 10));
        }
        TextView ar = HeartGraph.pill(a, "Put archived files back", 0x1AFFFFFF, INK); ar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmUnarchive(a, ui, reload); } }); ca.addView(ar, HeartGraph.lp(a, -2, -2, 10));
        col.addView(ca, HeartGraph.lp(a, -1, -2, 12));

        LinearLayout c3 = card(a); c3.addView(HeartGraph.label(a, "ONE BACKUP FILE", 11, MUT, 400));
        c3.addView(text(a, "Compact: the database, your settings, every reading (reports, strips, live frames), H10 R-R and logs. Full: also the H10 ECG files and the raw history (can be several GB, takes minutes). Saved as a zip in Download/MGbackup/zip; copy it to a computer or the cloud.", 12, MUT), HeartGraph.lp(a, -1, -2, 6));
        TextView z1 = HeartGraph.pill(a, "Make a compact backup zip", BLUE, 0xFF05131E); z1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { makeZip(a, ui, false, reload); } }); c3.addView(z1, HeartGraph.lp(a, -2, -2, 12));
        TextView z2 = HeartGraph.pill(a, "Make a full backup zip", 0x1AFFFFFF, INK); z2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { makeZip(a, ui, true, reload); } }); c3.addView(z2, HeartGraph.lp(a, -2, -2, 10));
        col.addView(c3, HeartGraph.lp(a, -1, -2, 12));

        LinearLayout c4 = card(a); c4.addView(HeartGraph.label(a, "RESTORE", 11, MUT, 400));
        c4.addView(text(a, "Pick a backup zip made here. It puts back the files that are missing, replaces the database and the settings with the ones in the zip, then closes the app so it starts clean. Do this on a fresh install or to go back to a backup.", 12, MUT), HeartGraph.lp(a, -1, -2, 6));
        TextView r1 = HeartGraph.pill(a, "Restore from a backup zip", 0x1AFFFFFF, INK); r1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" }); a.startActivityForResult(i, REQ_RESTORE); } });
        c4.addView(r1, HeartGraph.lp(a, -2, -2, 12)); col.addView(c4, HeartGraph.lp(a, -1, -2, 12));
    }

    private static void confirmTrim(final Activity a, final Handler ui, final int which, final int days, final Runnable reload) {
        final Toast wait = Toast.makeText(a, "Working out what to remove...", Toast.LENGTH_SHORT); wait.show();
        new Thread(new Runnable() { @Override public void run() {
            final Plan p = plan(a, which, days);
            ui.post(new Runnable() { @Override public void run() {
                if (p.files == 0) { Toast.makeText(a, "Nothing to remove.", Toast.LENGTH_LONG).show(); return; }
                new AlertDialog.Builder(a).setTitle(which == 0 ? "Trim H10 ECG files" : "Delete old raw history").setMessage("This removes " + p.files + " files (" + Backup.sizeText(p.bytes) + ") from the app folder and the backup copies. It cannot be undone. Send or archive anything you still need first.")
                    .setPositiveButton("Remove", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) {
                        new Thread(new Runnable() { @Override public void run() { final int n = apply(a, p); ui.post(new Runnable() { @Override public void run() { Toast.makeText(a, "Removed " + n + " files.", Toast.LENGTH_LONG).show(); reload.run(); } }); } }, "storage-trim").start(); } })
                    .setNegativeButton("Cancel", null).show(); } }); } }, "storage-plan").start();
    }

    private static void confirmArchive(final Activity a, final Handler ui, final int days, final Runnable reload) {
        Toast.makeText(a, "Working out what to archive...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            final ArchPlan p = archPlan(a, days);
            ui.post(new Runnable() { @Override public void run() {
                if (p.files == 0) { Toast.makeText(a, "Nothing older than " + days + " days to archive.", Toast.LENGTH_LONG).show(); return; }
                StringBuilder sb = new StringBuilder("Moves " + p.files + " files (" + Backup.sizeText(p.bytes) + ") into " + p.byDay.size() + " zips, one per day:\n");
                int shown = 0; for (java.util.Map.Entry<String, List<File>> e : p.byDay.entrySet()) { if (shown++ < 12) sb.append(e.getKey()).append(": ").append(e.getValue().size()).append(" files\n"); }
                if (p.byDay.size() > 12) sb.append("...\n");
                sb.append("\nTheir backup-folder copies are removed too (the zips get mirrored instead). Readings keep every file they need.");
                new AlertDialog.Builder(a).setTitle("Archive files older than " + days + " days").setMessage(sb.toString())
                    .setPositiveButton("Archive", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) { runLong(a, ui, "Archive", new Job() { @Override public String run(Say say) { int[] r = archApply(a, p, say); return "Archived " + r[0] + " files" + (r[1] > 0 ? "; " + r[1] + " left in place (check failed or could not delete)" : "") + "."; } }, reload); } })
                    .setNegativeButton("Cancel", null).show(); } }); } }, "storage-arch-plan").start();
    }

    private static void confirmUnarchive(final Activity a, final Handler ui, final Runnable reload) {
        File ad = new File(a.getExternalFilesDir(null), ARCHIVE_DIR); File[] zs = ad.listFiles(); int n = 0; long bytes = 0; if (zs != null) for (File f : zs) if (f.getName().endsWith(".zip")) { n++; bytes += f.length(); }
        if (n == 0) { Toast.makeText(a, "No archive zips.", Toast.LENGTH_LONG).show(); return; }
        new AlertDialog.Builder(a).setTitle("Put archived files back").setMessage("Unpacks " + n + " archive zips (" + Backup.sizeText(bytes) + ") into the app folder. Files of the same size already there are skipped. Each zip is removed once all its files are back.")
            .setPositiveButton("Put back", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) { runLong(a, ui, "Put back", new Job() { @Override public String run(Say say) { int[] r = archRestore(a, say); return "Put back " + r[0] + " files, " + r[1] + " already there; " + r[2] + " zips removed."; } }, reload); } })
            .setNegativeButton("Cancel", null).show();
    }

    interface Job { String run(Say say) throws Exception; }
    private static void runLong(final Activity a, final Handler ui, String title, final Job job, final Runnable reload) {
        final AlertDialog[] dlg = new AlertDialog[1]; final TextView msg = new TextView(a); msg.setPadding(48, 32, 48, 8); msg.setText("Working... keep the app open."); msg.setTextSize(15);
        dlg[0] = new AlertDialog.Builder(a).setTitle(title).setView(msg).setCancelable(false).create(); dlg[0].show();
        new Thread(new Runnable() { @Override public void run() {
            String out; try { out = job.run(new Say() { @Override public void on(final String s) { ui.post(new Runnable() { @Override public void run() { msg.setText(s); } }); } }); } catch (Throwable t) { out = "Failed: " + t; }
            final String o = out; ui.post(new Runnable() { @Override public void run() { dlg[0].dismiss(); new AlertDialog.Builder(a).setMessage(o).setPositiveButton("OK", null).show(); reload.run(); } }); } }, "storage-long").start();
    }

    private static void makeZip(final Activity a, final Handler ui, final boolean everything, final Runnable reload) {
        final AlertDialog[] dlg = new AlertDialog[1]; final TextView msg = new TextView(a); msg.setPadding(48, 32, 48, 8); msg.setText("Making the zip..."); msg.setTextSize(15);
        dlg[0] = new AlertDialog.Builder(a).setTitle(everything ? "Full backup zip" : "Compact backup zip").setView(msg).setCancelable(false).create(); dlg[0].show();
        new Thread(new Runnable() { @Override public void run() {
            String result;
            try {
                File root = a.getExternalFilesDir(null); File tmp = new File(a.getCacheDir(), "zip_daylog.db"); tmp.delete();
                SQLiteDatabase d = DayLog.db(a); try { d.execSQL("VACUUM INTO '" + tmp.getAbsolutePath().replace("'", "''") + "'"); } catch (Throwable t) { try { Cursor q = d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null); q.moveToFirst(); q.close(); } catch (Throwable ignored) { } copy(a.getDatabasePath("daylog.db"), tmp); }
                String name = "MG_backup_" + (everything ? "full_" : "compact_") + Backup.stamp(System.currentTimeMillis()) + ".zip"; Uri u = Backup.create(a, "zip", name, "application/zip"); OutputStream os = Backup.open(a, u, true);
                String manifest = "MG Heart backup zip\napp " + versionName(a) + "\ncreated " + new Date() + "\n" + (everything ? "full" : "compact (no H10 ECG files, no raw history)") + "\ncontents: db/daylog.db, prefs/*.xml, files/*\nrestore: Storage > Restore from a backup zip\n";
                final TextView mm = msg; int n = writeZip(os, root, tmp, new File(a.getApplicationInfo().dataDir, "shared_prefs"), everything, manifest, new Say() { @Override public void on(final String s) { ui.post(new Runnable() { @Override public void run() { mm.setText(s); } }); } });
                Backup.finish(a, u); tmp.delete(); result = "Saved Download/MGbackup/zip/" + name + " (" + n + " files, " + Backup.sizeText(Backup.sizeOf(a, u)) + ")";
            } catch (Throwable t) { result = "The zip failed: " + t; }
            final String r = result; ui.post(new Runnable() { @Override public void run() { dlg[0].dismiss(); new AlertDialog.Builder(a).setMessage(r).setPositiveButton("OK", null).show(); reload.run(); } }); } }, "storage-zip").start();
    }
    private static String versionName(Context c) { try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; } }
    private static void copy(File from, File to) throws Exception { try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) > 0) out.write(b, 0, k); } }

    // ------------------------------------------------------------------ restore: called from HealthActivity.onActivityResult
    static void onPicked(final Activity a, int res, Intent data) {
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) return; final Uri uri = data.getData(); final Handler ui = new Handler(Looper.getMainLooper());
        new AlertDialog.Builder(a).setTitle("Restore from this zip?").setMessage("The database and settings in the zip REPLACE the ones on this phone, and missing files are put back. The app then closes; open it again. The daily snapshots in Download/MGbackup/db are your way back if this is not what you want.")
            .setPositiveButton("Restore", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) { doRestore(a, ui, uri); } }).setNegativeButton("Cancel", null).show();
    }

    private static void doRestore(final Activity a, final Handler ui, final Uri uri) {
        final AlertDialog[] dlg = new AlertDialog[1]; TextView msg = new TextView(a); msg.setPadding(48, 32, 48, 8); msg.setText("Restoring... do not close the app."); msg.setTextSize(15);
        dlg[0] = new AlertDialog.Builder(a).setTitle("Restore").setView(msg).setCancelable(false).create(); dlg[0].show();
        new Thread(new Runnable() { @Override public void run() {
            String out; boolean ok = false;
            try {
                File root = a.getExternalFilesDir(null); File dbIn = new File(a.getCacheDir(), "restore_daylog.db"); File prefsIn = new File(a.getCacheDir(), "restore_prefs"); dbIn.delete();
                Restored r; try (InputStream in = a.getContentResolver().openInputStream(uri)) { r = readZip(in, root, dbIn, prefsIn); }
                StringBuilder sb = new StringBuilder("Files put back: " + r.files + ", already there: " + r.skipped + ". ");
                if (r.db) {
                    SQLiteDatabase t = SQLiteDatabase.openDatabase(dbIn.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY); long n = 0; try { Cursor q = t.rawQuery("SELECT COUNT(*) FROM r18", null); try { if (q.moveToFirst()) n = q.getLong(0); } finally { q.close(); } } finally { t.close(); }
                    DayLog.closeDb(); File db = a.getDatabasePath("daylog.db"); new File(db.getPath() + "-wal").delete(); new File(db.getPath() + "-shm").delete(); new File(db.getPath() + "-journal").delete(); db.delete(); copy(dbIn, db); dbIn.delete();
                    sb.append("Database restored (").append(n).append(" seconds of records). ");
                }
                if (r.prefs > 0) { File sp = new File(a.getApplicationInfo().dataDir, "shared_prefs"); sp.mkdirs(); File[] fs = prefsIn.listFiles(); if (fs != null) for (File f : fs) copy(f, new File(sp, f.getName())); sb.append("Settings restored (").append(r.prefs).append(" files). "); }
                out = sb.append("The app will now close. Open it again.").toString(); ok = true;
            } catch (Throwable t) { out = "Restore failed: " + t; }
            final String o = out; final boolean good = ok;
            ui.post(new Runnable() { @Override public void run() { dlg[0].dismiss(); new AlertDialog.Builder(a).setMessage(o).setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) { if (good) android.os.Process.killProcess(android.os.Process.myPid()); } }).show(); } }); } }, "storage-restore").start();
    }
}
