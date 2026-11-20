package com.noop.mgecg;

// FILE VERSION 0.71.3 (20 Nov 2026): Bundle.nights = the nights of sri.nights with the bed, wake and minutes asleep of the SleepNight night they overlap (restNights / reconcile): the wellbeing brief now shows the
// figure of the Today header and the Rest page. b.sri (the index, the research page) is unchanged. Finished nights are cached in the "rest_nights" preferences, keyed by REST_RULE and the cluster's first and last second.
// FILE VERSION 0.54.0 (13 Nov 2026): NEW. Reads the day log into per-minute series on the LOCAL clock (minute 0 = local midnight of the first day) and runs the sleep-regularity
// and heart-rate-rhythm calculations of the sleep research screen (SleepRegularity, RhythmFit).
//   asleep minute : the strap's own sleep state (R18 byte 81 bits 4-5 = 2) in at least half of the seconds of that minute; the minute needs 30 s of records and 20 s with a heart rate to count at all
//   heart rate    : mean of the seconds with a heart rate, minutes with at least 20 such seconds
// Minutes of days that were compacted (one row a minute in r18_min) are used where no per-second rows exist. Call off the main thread.

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

final class RhythmData {
    private RhythmData() {}

    static final int DAYS = 14, HR_DAYS = 7;

    static final class Series {
        long baseLocalSec;                  // local-clock seconds of minute 0
        int days;
        byte[] sleep;                       // 1 asleep, 0 awake, -1 none
        double[] hr;                        // bpm, NaN none
        long newest;
        int sleepMinutes, hrMinutes;
    }

    static final class Bundle {
        Series s;
        SleepRegularity.Result sri;
        List<SleepRegularity.Night> nights = new ArrayList<SleepRegularity.Night>();    // 0.71.3: sri.nights reconciled with SleepNight; the same objects as sri.nights when that failed
        boolean reconciled;
        RhythmFit.Result hrFit;
        int hrDaysUsed;
        String error = "";
    }

    private static long localSec(long ts) { return ts + TimeZone.getDefault().getOffset(ts * 1000L) / 1000L; }

    /** Local midnight (unix seconds) `back` days before the day of `ts`. */
    static long midnight(long ts, int back) {
        Calendar g = Calendar.getInstance();
        g.setTimeInMillis(ts * 1000L);
        g.set(Calendar.HOUR_OF_DAY, 0); g.set(Calendar.MINUTE, 0); g.set(Calendar.SECOND, 0); g.set(Calendar.MILLISECOND, 0);
        g.add(Calendar.DAY_OF_MONTH, -back);
        return g.getTimeInMillis() / 1000L;
    }

    static Series load(Context c, int days) {
        Series s = new Series();
        s.days = days;
        s.sleep = new byte[days * 1440];
        Arrays.fill(s.sleep, (byte) -1);
        s.hr = new double[days * 1440];
        Arrays.fill(s.hr, Double.NaN);
        SQLiteDatabase d = ActivityStore.db(c);
        long newest = 0;
        Cursor q = d.rawQuery("SELECT MAX(ts) FROM r18", null);
        if (q.moveToFirst() && !q.isNull(0)) newest = q.getLong(0);
        q.close();
        s.newest = newest;
        if (newest <= 0) return s;
        long m0 = midnight(newest, days - 1);
        s.baseLocalSec = localSec(m0);
        String[] args = {String.valueOf(m0), String.valueOf(newest + 1)};
        boolean[] done = new boolean[days * 1440];
        q = d.rawQuery("SELECT ts/60, SUM(CASE WHEN state=2 THEN 1 ELSE 0 END), COUNT(*), SUM(CASE WHEN hr>0 THEN 1 ELSE 0 END), SUM(CASE WHEN hr>0 THEN hr ELSE 0 END) FROM r18 WHERE ts >= ? AND ts < ? GROUP BY ts/60", args);
        while (q.moveToNext()) {
            long tsMin = q.getLong(0) * 60L;
            int idx = (int) ((localSec(tsMin) - s.baseLocalSec) / 60L);
            if (idx < 0 || idx >= s.sleep.length) continue;
            int asleep = q.getInt(1), n = q.getInt(2), nHr = q.getInt(3);
            long sumHr = q.getLong(4);
            if (n >= 30 && nHr >= 20) { s.sleep[idx] = (byte) (2 * asleep >= n ? 1 : 0); if (!done[idx]) s.sleepMinutes++; }
            if (nHr >= 20) { s.hr[idx] = sumHr / (double) nHr; if (!done[idx]) s.hrMinutes++; }
            done[idx] = true;
        }
        q.close();
        try {
            q = d.rawQuery("SELECT ts/60, state, hr, n FROM r18_min WHERE ts >= ? AND ts < ?", args);
            while (q.moveToNext()) {
                long tsMin = q.getLong(0) * 60L;
                int idx = (int) ((localSec(tsMin) - s.baseLocalSec) / 60L);
                if (idx < 0 || idx >= s.sleep.length || done[idx]) continue;
                int n = q.getInt(3);
                if (n < 30 || q.isNull(2) || q.getDouble(2) <= 0) continue;
                s.sleep[idx] = (byte) (q.getInt(1) == 2 ? 1 : 0); s.sleepMinutes++;
                s.hr[idx] = q.getDouble(2); s.hrMinutes++;
            }
            q.close();
        } catch (Throwable ignored) { }
        return s;
    }

    // ------------------------------------------------------------------ 0.71.3: one source for the night
    /** Bump together with DayLog's night_rule whenever SleepNight's night rules change: it keys the stored nights. */
    static final int REST_RULE = 1;

    interface RecLoader { List<byte[]> load(long fromTs, long toTs); }
    interface NightCache { String get(String key); void put(String key, String value); }

    /**
     * The nights SleepNight finds, as the Today header reads them: for each cluster of SLEEP seconds the analysed night {bedTs, endTs, seconds asleep} (the records from 2 h before to 10 min after the cluster,
     * as TodayModel does). sleepTs: sorted timestamps of every SLEEP second; clusters ending before fromTs are skipped. A night that ended more than 2 h before `newest` is read from / stored in the cache.
     */
    static List<long[]> restNightList(long[] sleepTs, long fromTs, long newest, RecLoader rl, NightCache cache) {
        List<long[]> out = new ArrayList<long[]>();
        for (long[] cc : SleepNight.clusters(sleepTs)) {
            if (cc[1] < fromTs) continue;
            boolean fin = cache != null && cc[1] < newest - 7200L;
            String key = REST_RULE + "_" + cc[0] + "_" + cc[1];
            long[] r = null;
            String v = fin ? cache.get(key) : null;
            if (v != null) {
                try { String[] p = v.split(","); r = new long[] {Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])}; } catch (Throwable ignored) { r = null; }
            }
            if (r == null) {
                SleepNight.Night n = SleepNight.analyse(rl.load(cc[0] - 7200L, cc[1] + 600L));
                if (n == null) continue;
                r = new long[] {n.bedTs, n.endTs, Math.round(n.tstH * 3600.0)};
                if (fin) cache.put(key, r[0] + "," + r[1] + "," + r[2]);
            }
            out.add(r);
        }
        return out;
    }

    /** restNightList on the day log: the SLEEP seconds of the 14 days (and the day before) and the records of each night. Call off the main thread. */
    static List<long[]> restNights(Context c, Series s) {
        final SQLiteDatabase d = ActivityStore.db(c);
        long m0 = midnight(s.newest, s.days - 1);
        Cursor q = d.rawQuery("SELECT ts FROM r18 WHERE state = 2 AND ts >= ? ORDER BY ts", new String[] {String.valueOf(m0 - 86400L)});
        long[] st = new long[q.getCount()];
        int k = 0;
        while (k < st.length && q.moveToNext()) st[k++] = q.getLong(0);
        q.close();
        if (k < st.length) st = Arrays.copyOf(st, k);
        final SharedPreferences sp = c.getApplicationContext().getSharedPreferences("rest_nights", Context.MODE_PRIVATE);
        return restNightList(st, m0, s.newest, new RecLoader() {
            @Override public List<byte[]> load(long fromTs, long toTs) {
                List<byte[]> recs = new ArrayList<byte[]>();
                Cursor r = d.rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] {String.valueOf(fromTs), String.valueOf(toTs)});
                while (r.moveToNext()) recs.add(r.getBlob(0));
                r.close();
                return recs;
            }
        }, new NightCache() {
            @Override public String get(String key) { return sp.getString(key, null); }
            @Override public void put(String key, String value) { sp.edit().putString(key, value).apply(); }
        });
    }

    /** Minute index of a time on the series' local clock. */
    static int minuteIdx(Series s, long ts) { return (int) Math.floorDiv(localSec(ts) - s.baseLocalSec, 60L); }

    /** SleepRegularity.reconciled with the SleepNight nights put on the series' minute index (wake = the minute of the last SLEEP second, as the Rest page shows it). */
    static List<SleepRegularity.Night> reconcile(Series s, List<SleepRegularity.Night> nights, List<long[]> rest) {
        long[][] m = new long[rest.size()][];
        for (int i = 0; i < m.length; i++) {
            long[] r = rest.get(i);
            m[i] = new long[] {minuteIdx(s, r[0]), minuteIdx(s, r[1]), Math.round(r[2] / 60.0)};
        }
        return SleepRegularity.reconciled(nights, m);
    }

    private static Bundle cachedB; private static long cachedAt;

    /** bundle(), reused for 5 minutes. */
    static synchronized Bundle bundleCached(Context c) {
        long now = System.currentTimeMillis();
        if (cachedB != null && now - cachedAt < 300000L) return cachedB;
        cachedB = bundle(c); cachedAt = now;
        return cachedB;
    }

    static Bundle bundle(Context c) {
        Bundle b = new Bundle();
        try {
            Series s = load(c, DAYS);
            b.s = s;
            b.sri = SleepRegularity.analyse(s.sleep);
            b.nights = b.sri.nights;
            try { List<long[]> rn = restNights(c, s); b.nights = reconcile(s, b.sri.nights, rn); b.reconciled = !rn.isEmpty(); } catch (Throwable ignored) { }
            int from = Math.max(0, (s.days - HR_DAYS) * 1440);
            b.hrFit = RhythmFit.fitMinutes(s.hr, from, s.days * 1440);
            int usedDays = 0;
            for (int d = from / 1440; d < s.days; d++) { int k = 0; for (int i = d * 1440; i < (d + 1) * 1440; i++) if (!Double.isNaN(s.hr[i])) k++; if (k >= 720) usedDays++; }
            b.hrDaysUsed = usedDays;
        } catch (Throwable t) { b.error = t.getClass().getSimpleName(); }
        return b;
    }
}
