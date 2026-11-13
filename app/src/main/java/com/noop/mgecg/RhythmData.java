package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. Reads the day log into per-minute series on the LOCAL clock (minute 0 = local midnight of the first day) and runs the sleep-regularity
// and heart-rate-rhythm calculations of the sleep research screen (SleepRegularity, RhythmFit).
//   asleep minute : the strap's own sleep state (R18 byte 81 bits 4-5 = 2) in at least half of the seconds of that minute; the minute needs 30 s of records and 20 s with a heart rate to count at all
//   heart rate    : mean of the seconds with a heart rate, minutes with at least 20 such seconds
// Minutes of days that were compacted (one row a minute in r18_min) are used where no per-second rows exist. Call off the main thread.

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.Arrays;
import java.util.Calendar;
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
            int from = Math.max(0, (s.days - HR_DAYS) * 1440);
            b.hrFit = RhythmFit.fitMinutes(s.hr, from, s.days * 1440);
            int usedDays = 0;
            for (int d = from / 1440; d < s.days; d++) { int k = 0; for (int i = d * 1440; i < (d + 1) * 1440; i++) if (!Double.isNaN(s.hr[i])) k++; if (k >= 720) usedDays++; }
            b.hrDaysUsed = usedDays;
        } catch (Throwable t) { b.error = t.getClass().getSimpleName(); }
        return b;
    }
}
