package com.noop.mgecg;

// FILE VERSION 0.11.0 (25 Oct): continuous live heart rate from the strap's standard Heart Rate service (0x2A37). Research only.

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every notification the strap sends on the standard Heart Rate Measurement characteristic is kept in the day-logger database
 * (table live_hr) so the Heart tab can show a heart rate that is really live, and the graph can fill the gaps between pulls.
 * The beat-interval words in the notification are stored RAW (u16, comma separated): their unit (milliseconds or 1/1024 s) is
 * still open on this strap, so nothing here converts them. At most two rows a second; rows older than 30 days are removed.
 */
final class LiveHr {
    private LiveHr() {}

    static final class Sample {
        long ms;
        int hr;
        int flags;
        int[] rr = new int[0];
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final List<ContentValues> pending = new ArrayList<>();
    private static volatile Sample latest;
    private static long lastKeptMs = 0L, lastFlushMs = 0L, lastPruneMs = 0L;
    private static long countToday = 0L;

    /** The newest notification seen since the app started, or null. */
    static Sample latest() { return latest; }

    /** BLE Heart Rate Measurement: flags bit0 = 16-bit rate, bit3 = energy expended present, bit4 = beat intervals present. */
    static Sample parse(byte[] v, long ms) {
        if (v == null || v.length < 2) return null;
        int flags = v[0] & 0xff;
        int i = 1;
        int hr;
        if ((flags & 0x01) != 0) {
            if (v.length < 3) return null;
            hr = (v[1] & 0xff) | ((v[2] & 0xff) << 8);
            i = 3;
        } else {
            hr = v[1] & 0xff;
            i = 2;
        }
        if ((flags & 0x08) != 0) i += 2;
        List<Integer> rr = new ArrayList<>();
        if ((flags & 0x10) != 0) {
            while (i + 1 < v.length) {
                rr.add((v[i] & 0xff) | ((v[i + 1] & 0xff) << 8));
                i += 2;
            }
        }
        Sample s = new Sample();
        s.ms = ms;
        s.hr = hr;
        s.flags = flags;
        s.rr = new int[rr.size()];
        for (int k = 0; k < s.rr.length; k++) s.rr[k] = rr.get(k);
        return s;
    }

    private static volatile long lastLiveMs = 0L;

    /** When the newest live sample arrived (phone clock), 0 when none yet. */
    static long lastLiveMs() { return lastLiveMs; }

    /** Called from the Bluetooth callback for every standard heart-rate notification (0x2A37). Never throws. */
    static void onNotification(final Context c, byte[] value) {
        try { keep(c, parse(value, System.currentTimeMillis())); } catch (Throwable ignored) { }
    }

    /**
     * 0.11.1: the WHOOP 5/MG sends its live heart rate as real-time frames (type 40), not on 0x2A37, once TOGGLE_REALTIME_HR(1) is on.
     * Layout (frame bytes): type @8 = 40, unix @10, sub-second @14, heart rate @16, interval count @17, interval words @18 (u16, milliseconds).
     * The frame is accepted only when its CRC32 matches. Never throws.
     */
    static void onRealtimeFrame(final Context c, byte[] f) {
        try {
            if (f == null || f.length < 22 || (f[0] & 0xff) != 0xAA || (f[1] & 0xff) != 0x01 || (f[8] & 0xff) != 40) return;
            int len = (f[2] & 0xff) | ((f[3] & 0xff) << 8);
            if (len + 8 != f.length) return;
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(f, 8, f.length - 12);
            long want = (f[f.length - 4] & 0xffL) | ((f[f.length - 3] & 0xffL) << 8) | ((f[f.length - 2] & 0xffL) << 16) | ((f[f.length - 1] & 0xffL) << 24);
            if (crc.getValue() != want) return;
            Sample s = new Sample();
            s.ms = System.currentTimeMillis();
            s.hr = f[16] & 0xff;
            s.flags = 0x100;
            int n = Math.min(f[17] & 0xff, 8);
            List<Integer> rr = new ArrayList<>();
            for (int k = 0; k < n && 18 + 2 * k + 1 < f.length - 4; k++) {
                int w = (f[18 + 2 * k] & 0xff) | ((f[19 + 2 * k] & 0xff) << 8);
                if (w > 0) rr.add(w);
            }
            s.rr = new int[rr.size()];
            for (int k = 0; k < s.rr.length; k++) s.rr[k] = rr.get(k);
            keep(c, s);
        } catch (Throwable ignored) { }
    }

    private static void keep(final Context c, Sample s) {
        if (s == null || s.hr <= 0 || s.hr > 250) return;
        long now = s.ms;
        latest = s;
        lastLiveMs = now;
        synchronized (pending) {
            if (now - lastKeptMs < 450L) return;
            lastKeptMs = now;
            ContentValues cv = new ContentValues();
            cv.put("ms", now);
            cv.put("hr", s.hr);
            cv.put("flags", s.flags);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < s.rr.length; k++) { if (k > 0) sb.append(','); sb.append(s.rr[k]); }
            cv.put("rr", sb.toString());
            pending.add(cv);
            countToday++;
            if (now - lastFlushMs < 10000L && pending.size() < 40) return;
            lastFlushMs = now;
        }
        final Context app = c.getApplicationContext();
        EXEC.execute(new Runnable() { @Override public void run() { flush(app); } });
    }

    private static void flush(Context app) {
        List<ContentValues> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        try {
            SQLiteDatabase db = DayLog.db(app);
            db.beginTransaction();
            try {
                for (ContentValues cv : batch) db.insertWithOnConflict("live_hr", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            long now = System.currentTimeMillis();
            if (now - lastPruneMs > 6L * 3600L * 1000L) {
                lastPruneMs = now;
                db.delete("live_hr", "ms < ?", new String[] {String.valueOf(now - 30L * 86400L * 1000L)});
            }
        } catch (Throwable ignored) { }
    }

    /** Rows stored in the last [hours] hours (call off the main thread). */
    static int countSince(Context c, double hours) {
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT COUNT(*) FROM live_hr WHERE ms >= ?", new String[] {String.valueOf(System.currentTimeMillis() - (long) (hours * 3600000.0))});
            try { return q.moveToFirst() ? q.getInt(0) : 0; } finally { q.close(); }
        } catch (Throwable t) { return 0; }
    }

    /** Heart rates between two times in unix seconds, as parallel arrays (call off the main thread). */
    static long[][] seriesSec(Context c, long fromSec, long toSec) {
        List<long[]> rows = new ArrayList<>();
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT ms, hr FROM live_hr WHERE ms >= ? AND ms < ? ORDER BY ms", new String[] {String.valueOf(fromSec * 1000L), String.valueOf(toSec * 1000L)});
            try { while (q.moveToNext()) rows.add(new long[] {q.getLong(0) / 1000L, q.getLong(1)}); } finally { q.close(); }
        } catch (Throwable ignored) { }
        long[][] out = new long[2][rows.size()];
        for (int i = 0; i < rows.size(); i++) { out[0][i] = rows.get(i)[0]; out[1][i] = rows.get(i)[1]; }
        return out;
    }

    /** Short text for the Heart tab: how fresh the number is. */
    static String ageText(Sample s) {
        if (s == null) return "";
        long a = Math.max(0L, (System.currentTimeMillis() - s.ms) / 1000L);
        if (a <= 8) return "live";
        if (a < 90) return a + " s ago";
        if (a < 3600) return (a / 60) + " min ago";
        return (a / 3600) + " h ago";
    }
}
