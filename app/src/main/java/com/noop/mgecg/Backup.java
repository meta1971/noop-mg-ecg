package com.noop.mgecg;

// FILE VERSION 0.16.0 (27 Oct): the daily backup of all app data. Research only.

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Every day at the time you choose (03:30 unless changed) the app copies ALL its data into Download/MGbackup, which survives an uninstall:
 *   db/daylog_<date>_<time>.db.gz   a consistent snapshot of the day-log database (every second, event, night and export record), compressed
 *   prefs/prefs_<date>_<time>.zip   the app's settings (including the ECG history list)
 *   files/                          a mirror of every file in the app's own folder (history captures, ECG reports and strips, logs, H10 files),
 *                                   only what is new or changed since the last backup; nothing is ever deleted from the mirror
 * Database and settings snapshots are kept for the chosen number of days; the mirror is kept for good.
 * The alarm is exact (Android 11 needs no permission), survives a restart and an app update, and a backup missed because the phone was off
 * runs as soon as the app is opened or the phone is back. The copy itself runs in a foreground service so Android does not end it half way.
 * The pure parts (times, what to copy, what to prune) are tested off the phone.
 */
final class Backup {
    private Backup() {}

    static final String ROOT = "MGbackup";
    static final String ACTION_RUN = "com.noop.mgecg.BACKUP_RUN";
    static final int[] KEEP_CHOICES = {3, 7, 14, 30};
    private static final String PREFS = "backup";
    private static final int REQ = 7301;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    interface Listener { void on(String s); }

    static final class Result {
        boolean ok;
        String message = "";
        long dbBytes, dbSeconds, dbFirst, dbLast;
        int filesNew, filesUpdated, filesSkipped, fileErrors;
        long bytesCopied;
        int pruned;
        double secs;
    }

    // ================================================================ pure parts

    /** The next run strictly after now (at least 30 s ahead) at hour:minute local time. DST safe: the calendar does the arithmetic. */
    static long nextRun(long nowMs, int hour, int minute, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs + 30000L) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** The most recent scheduled moment at or before now. */
    static long lastScheduled(long nowMs, int hour, int minute, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() > nowMs) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.getTimeInMillis();
    }

    /** A scheduled backup was missed: the last scheduled moment is 10 minutes or more ago, nothing succeeded since, and the last try was 3 hours or more ago. */
    static boolean due(long nowMs, long lastOkMs, long lastTryMs, int hour, int minute, TimeZone tz) {
        long prev = lastScheduled(nowMs, hour, minute, tz);
        return lastOkMs < prev && nowMs >= prev + 10L * 60L * 1000L && nowMs - lastTryMs >= 3L * 3600L * 1000L;
    }

    /** Copy a file when it is not in the mirror yet or its size or modified time changed. */
    static boolean needsCopy(boolean known, long oldSize, long oldMtime, long size, long mtime) {
        return !known || size != oldSize || mtime != oldMtime;
    }

    /** Files that must not be mirrored: the app's own export and backup folders, temporary files, and anything touched in the last minute (still being written). */
    static boolean skip(String rel, long mtime, long nowMs) {
        String top = rel.indexOf('/') > 0 ? rel.substring(0, rel.indexOf('/')) : "";
        if (top.equals("MGexports") || top.equals("MGbackup") || top.equals("export") || top.equals("cache")) return true;
        String l = rel.toLowerCase(Locale.UK);
        if (l.endsWith(".tmp") || l.endsWith(".part") || l.endsWith("-journal") || l.endsWith("-wal") || l.endsWith("-shm")) return true;
        return nowMs - mtime < 60000L;
    }

    /** Names carry a sortable date; keep the newest `keep`, return the rest. */
    static List<String> toDelete(List<String> names, int keep) {
        List<String> s = new ArrayList<String>(names);
        Collections.sort(s, Collections.reverseOrder());
        if (keep < 1) keep = 1;
        return s.size() <= keep ? new ArrayList<String>() : new ArrayList<String>(s.subList(keep, s.size()));
    }

    static String stamp(long ms) { return new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.UK).format(new Date(ms)); }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.UK);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".gz")) return "application/gzip";
        return "application/octet-stream";
    }

    static String sizeText(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576L) return String.format(Locale.UK, "%.0f KB", b / 1024.0);
        if (b < 1073741824L) return String.format(Locale.UK, "%.1f MB", b / 1048576.0);
        return String.format(Locale.UK, "%.2f GB", b / 1073741824.0);
    }

    // ================================================================ settings

    static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return p(c).getBoolean("on", true); }
    static int hour(Context c) { return p(c).getInt("hour", 3); }
    static int minute(Context c) { return p(c).getInt("minute", 30); }
    static int keep(Context c) { return p(c).getInt("keep", 7); }
    static boolean running() { return RUNNING.get(); }

    static void setEnabled(Context c, boolean on) { p(c).edit().putBoolean("on", on).apply(); schedule(c); }
    static void setTime(Context c, int h, int m) { p(c).edit().putInt("hour", h).putInt("minute", m).apply(); schedule(c); }
    static void setKeep(Context c, int days) { p(c).edit().putInt("keep", days).apply(); }

    static String timeText(Context c) { return String.format(Locale.UK, "%02d:%02d", hour(c), minute(c)); }

    // ================================================================ scheduling

    private static PendingIntent pending(Context c, int flags) {
        Intent i = new Intent(c.getApplicationContext(), BackupReceiver.class).setAction(ACTION_RUN);
        return PendingIntent.getBroadcast(c.getApplicationContext(), REQ, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets (or cancels) the one alarm for the next run. Safe to call as often as you like. */
    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = pending(c, PendingIntent.FLAG_UPDATE_CURRENT);
            if (am == null || pi == null) return;
            if (!enabled(c)) { am.cancel(pi); p(c).edit().putLong("next_ms", 0L).apply(); return; }
            long at = nextRun(System.currentTimeMillis(), hour(c), minute(c), TimeZone.getDefault());
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (SecurityException se) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            p(c).edit().putLong("next_ms", at).apply();
        } catch (Throwable t) {
            try { DayLog.addEvent(c, "backup", "schedule failed: " + t); } catch (Throwable ignored) { }
        }
    }

    /** Called whenever the app opens, restarts or is updated: make sure the alarm exists, and run a missed backup. */
    static void ensure(Context c) {
        try {
            if (!enabled(c)) return;
            SharedPreferences pr = p(c);
            if (pending(c, PendingIntent.FLAG_NO_CREATE) == null || pr.getLong("next_ms", 0L) < System.currentTimeMillis()) schedule(c);
            if (!running() && due(System.currentTimeMillis(), pr.getLong("ok_ms", 0L), pr.getLong("try_ms", 0L), hour(c), minute(c), TimeZone.getDefault())) startNow(c, "catch-up");
        } catch (Throwable ignored) { }
    }

    /** Starts a backup in the foreground service (the screen, the alarm and the catch-up all use this). */
    static void startNow(Context c, String why) {
        try {
            Context a = c.getApplicationContext();
            if (running()) return;
            p(a).edit().putLong("try_ms", System.currentTimeMillis()).apply();
            Intent i = new Intent(a, BackupService.class).putExtra("why", why);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
        } catch (Throwable t) {
            try { DayLog.addEvent(c, "backup", "could not start: " + t); } catch (Throwable ignored) { }
        }
    }

    // ================================================================ the backup

    private static void say(Listener l, String s) { if (l != null) { try { l.on(s); } catch (Throwable ignored) { } } }

    static Result run(Context ctx, String why, Listener l) {
        final Context c = ctx.getApplicationContext();
        Result r = new Result();
        if (!RUNNING.compareAndSet(false, true)) { r.message = "A backup is already running."; return r; }
        long t0 = System.currentTimeMillis();
        p(c).edit().putLong("try_ms", t0).putBoolean("running", true).apply();
        try {
            String stamp = stamp(t0);
            say(l, "Copying the database\u2026");
            snapshotDb(c, stamp, r);
            say(l, "Copying the settings\u2026");
            snapshotPrefs(c, stamp);
            say(l, "Copying new files\u2026");
            mirrorFiles(c, r, l);
            say(l, "Tidying old snapshots\u2026");
            r.pruned = prune(c, keep(c));
            r.ok = true;
        } catch (Throwable t) {
            r.ok = false;
            r.message = "Backup stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            r.secs = (System.currentTimeMillis() - t0) / 1000.0;
            if (r.ok) {
                StringBuilder s = new StringBuilder();
                s.append("Database ").append(sizeText(r.dbBytes)).append(" (").append(String.format(Locale.UK, "%,d", r.dbSeconds)).append(" seconds of data");
                if (r.dbFirst > 0) s.append(", ").append(new SimpleDateFormat("d MMM", Locale.UK).format(new Date(r.dbFirst * 1000L))).append(" to ").append(new SimpleDateFormat("d MMM", Locale.UK).format(new Date(r.dbLast * 1000L)));
                s.append("), ");
                s.append(r.filesNew + r.filesUpdated).append(" new or changed files (").append(sizeText(r.bytesCopied)).append("), ").append(r.filesSkipped).append(" already backed up");
                if (r.fileErrors > 0) s.append(", ").append(r.fileErrors).append(" files could not be copied");
                s.append(", ").append(String.format(Locale.UK, "%.0f", r.secs)).append(" s");
                r.message = s.toString();
            }
            SharedPreferences.Editor ed = p(c).edit().putBoolean("running", false).putString("msg", r.message).putBoolean("last_ok", r.ok);
            if (r.ok) ed.putLong("ok_ms", System.currentTimeMillis());
            ed.apply();
            try { DayLog.addEvent(c, "backup", (r.ok ? "ok (" + why + "): " : "FAILED (" + why + "): ") + r.message); } catch (Throwable ignored) { }
            RUNNING.set(false);
        }
        return r;
    }

    private static void snapshotDb(Context c, String stamp, Result r) throws Exception {
        File tmp = new File(c.getCacheDir(), "bk_daylog.db");
        tmp.delete();
        SQLiteDatabase d = DayLog.db(c);
        try {
            d.execSQL("VACUUM INTO '" + tmp.getAbsolutePath().replace("'", "''") + "'");
        } catch (Throwable t) {                                          // older SQLite: fold the journal in and copy the file
            try { Cursor q = d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null); q.moveToFirst(); q.close(); } catch (Throwable ignored) { }
            copy(c.getDatabasePath("daylog.db"), tmp);
        }
        try {                                                            // prove the copy opens, and say what is in it
            SQLiteDatabase s = SQLiteDatabase.openDatabase(tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try {
                Cursor q = s.rawQuery("SELECT COUNT(*), IFNULL(MIN(ts), 0), IFNULL(MAX(ts), 0) FROM r18", null);
                try { if (q.moveToFirst()) { r.dbSeconds = q.getLong(0); r.dbFirst = q.getLong(1); r.dbLast = q.getLong(2); } } finally { q.close(); }
            } finally { s.close(); }
        } catch (Throwable t) {
            tmp.delete();
            throw new Exception("the database copy did not open: " + t.getMessage());
        }
        Uri u = create(c, "db", "daylog_" + stamp + ".db.gz", "application/gzip");
        OutputStream os = new GZIPOutputStream(open(c, u, true), 65536);
        InputStream in = new FileInputStream(tmp);
        try { pump(in, os); } finally { in.close(); os.close(); }
        finish(c, u);
        r.dbBytes = sizeOf(c, u);
        tmp.delete();
    }

    private static void snapshotPrefs(Context c, String stamp) throws Exception {
        File dir = new File(c.getApplicationInfo().dataDir, "shared_prefs");
        File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) return;
        Uri u = create(c, "prefs", "prefs_" + stamp + ".zip", "application/zip");
        ZipOutputStream z = new ZipOutputStream(open(c, u, true));
        try {
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".xml")) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                InputStream in = new FileInputStream(f);
                try { pump(in, z); } finally { in.close(); }
                z.closeEntry();
            }
        } finally { z.close(); }
        finish(c, u);
    }

    private static void mirrorFiles(Context c, Result r, Listener l) {
        File root = c.getExternalFilesDir(null);
        if (root == null) return;
        SQLiteDatabase d = DayLog.db(c);
        d.execSQL("CREATE TABLE IF NOT EXISTS backup_files (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, uri TEXT)");
        Map<String, String[]> idx = new HashMap<String, String[]>();
        Cursor q = d.rawQuery("SELECT path, size, mtime, uri FROM backup_files", null);
        try { while (q.moveToNext()) idx.put(q.getString(0), new String[] {String.valueOf(q.getLong(1)), String.valueOf(q.getLong(2)), q.getString(3)}); } finally { q.close(); }
        List<File> all = new ArrayList<File>();
        walk(root, all, 0);
        long now = System.currentTimeMillis();
        int n = 0;
        for (File f : all) {
            n++;
            String rel = rel(root, f);
            if (skip(rel, f.lastModified(), now)) continue;
            String[] old = idx.get(rel);
            boolean known = old != null;
            if (!needsCopy(known, known ? Long.parseLong(old[0]) : 0L, known ? Long.parseLong(old[1]) : 0L, f.length(), f.lastModified())) { r.filesSkipped++; continue; }
            if (n % 25 == 0) say(l, "Copying files\u2026 " + n + " of " + all.size());
            try {
                Uri u = null;
                if (known && old[2] != null) {
                    try { u = Uri.parse(old[2]); OutputStream t = open(c, u, true); InputStream in = new FileInputStream(f); try { pump(in, t); } finally { in.close(); t.close(); } finish(c, u); }
                    catch (Throwable gone) { u = null; }                 // the copy was deleted by hand: make it again
                }
                if (u == null) {
                    String sub = "files" + (rel.indexOf('/') > 0 ? "/" + rel.substring(0, rel.lastIndexOf('/')) : "");
                    u = create(c, sub, f.getName(), mime(f.getName()));
                    OutputStream t = open(c, u, true);
                    InputStream in = new FileInputStream(f);
                    try { pump(in, t); } finally { in.close(); t.close(); }
                    finish(c, u);
                }
                ContentValues v = new ContentValues();
                v.put("path", rel); v.put("size", f.length()); v.put("mtime", f.lastModified()); v.put("uri", u.toString());
                d.insertWithOnConflict("backup_files", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                if (known) r.filesUpdated++; else r.filesNew++;
                r.bytesCopied += f.length();
            } catch (Throwable t) {
                r.fileErrors++;
            }
        }
    }

    private static void walk(File dir, List<File> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 4) return;
        for (File f : fs) { if (f.isDirectory()) walk(f, out, depth + 1); else out.add(f); }
    }

    private static String rel(File root, File f) {
        String a = root.getAbsolutePath(), b = f.getAbsolutePath();
        return b.startsWith(a) ? b.substring(a.length()).replaceFirst("^/", "") : f.getName();
    }

    /** Keeps the newest `keep` database and settings snapshots. */
    private static int prune(Context c, int keep) {
        int n = 0;
        n += pruneSub(c, "db", keep);
        n += pruneSub(c, "prefs", keep);
        return n;
    }

    private static int pruneSub(Context c, String sub, int keep) {
        int deleted = 0;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                Map<String, Long> ids = new HashMap<String, Long>();
                Cursor q = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[] {"Download/" + ROOT + "/" + sub + "/"}, null);
                try { while (q != null && q.moveToNext()) ids.put(q.getString(1), q.getLong(0)); } finally { if (q != null) q.close(); }
                for (String name : toDelete(new ArrayList<String>(ids.keySet()), keep)) {
                    deleted += cr.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ids.get(name)), null, null);
                }
            } else {
                File dir = new File(c.getExternalFilesDir(null), ROOT + "/" + sub);
                String[] names = dir.list();
                if (names != null) for (String name : toDelete(java.util.Arrays.asList(names), keep)) if (new File(dir, name).delete()) deleted++;
            }
        } catch (Throwable ignored) { }
        return deleted;
    }

    // ================================================================ files (Android)

    /** Download/MGbackup/<sub>/<name> through MediaStore (survives an uninstall); the app's own folder before Android 10. */
    private static Uri create(Context c, String sub, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + ROOT + "/" + sub + "/");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("could not create " + name);
            return u;
        }
        File dir = new File(c.getExternalFilesDir(null), ROOT + "/" + sub);
        dir.mkdirs();
        return Uri.fromFile(new File(dir, name));
    }

    private static OutputStream open(Context c, Uri u, boolean truncate) throws Exception {
        if ("file".equals(u.getScheme())) return new FileOutputStream(new File(u.getPath()), false);
        OutputStream os = c.getContentResolver().openOutputStream(u, truncate ? "wt" : "w");
        if (os == null) throw new Exception("could not open " + u);
        return os;
    }

    private static void finish(Context c, Uri u) {
        if (!"content".equals(u.getScheme()) || Build.VERSION.SDK_INT < 29) return;
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try { c.getContentResolver().update(u, done, null, null); } catch (Throwable ignored) { }
    }

    private static long sizeOf(Context c, Uri u) {
        try {
            if ("file".equals(u.getScheme())) return new File(u.getPath()).length();
            Cursor q = c.getContentResolver().query(u, new String[] {MediaStore.MediaColumns.SIZE}, null, null, null);
            try { if (q != null && q.moveToFirst()) return q.getLong(0); } finally { if (q != null) q.close(); }
        } catch (Throwable ignored) { }
        return 0L;
    }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        try { pump(in, out); } finally { in.close(); out.close(); }
    }

    private static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[65536];
        int k;
        while ((k = in.read(b)) > 0) out.write(b, 0, k);
    }

    // ================================================================ what the screens show

    static String statusText(Context c) {
        SharedPreferences pr = p(c);
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK);
        StringBuilder s = new StringBuilder();
        if (running()) s.append("Backing up now\u2026\n");
        long ok = pr.getLong("ok_ms", 0L);
        if (ok > 0) s.append("Last good backup: ").append(f.format(new Date(ok))).append('\n');
        else s.append("No backup has finished yet.\n");
        String msg = pr.getString("msg", "");
        if (msg.length() > 0) s.append(pr.getBoolean("last_ok", true) ? "" : "Last try FAILED: ").append(msg).append('\n');
        long next = pr.getLong("next_ms", 0L);
        if (enabled(c) && next > 0) s.append("Next backup: ").append(f.format(new Date(next))).append('\n');
        else if (!enabled(c)) s.append("Daily backup is switched off.\n");
        s.append("Folder: Download/").append(ROOT);
        return s.toString();
    }

    /** One line for the Strap data page. */
    static String shortStatus(Context c) {
        SharedPreferences pr = p(c);
        if (!enabled(c)) return "off";
        long ok = pr.getLong("ok_ms", 0L);
        if (running()) return "running";
        if (ok <= 0) return "none yet, next " + timeText(c);
        long ageH = (System.currentTimeMillis() - ok) / 3600000L;
        return (ageH < 1 ? "under 1 h ago" : ageH + " h ago") + ", next " + timeText(c);
    }
}
