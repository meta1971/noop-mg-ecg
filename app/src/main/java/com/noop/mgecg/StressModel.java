package com.noop.mgecg;

// FILE VERSION 0.53.5 (12 Nov 2026): the STRESS estimate of the home ring, version 2 (decode log 167). Research only; not a medical measure.
// WHY VERSION 2: version 1 scored only STILL stretches. A physical day (Ame: 06:00-17:00, 29 % of his awake 5-minute bins are still, 22 % are over 40 steps a minute) leaves none: the ring stayed empty and the panel bare.
// WHAT IT IS NOW: how far the heart rate sits above what YOUR OWN heart rate is at that movement. Movement is measured as steps a minute over each 5 minutes; the person's own baseline is the 20th percentile of
// the heart rate in five movement bands (still under 0.5 steps a minute, light 0.5-5, steady 5-20, brisk 20-40, fast over 40) over the last 14 days; between the band centres it is interpolated, so a hard day's walking
// raises the expected heart rate and only the part above it counts. The heart rate lags movement: the cadence that sets the expectation is max(now, 0.6 x the previous 5 minutes, 0.35 x the 5 minutes before).
//   TIME OF DAY: the heart rate also follows the clock (the waking surge, the evening meal and rest: on his 14 days the 06:00 hour sat 17 bpm and the 19:00 hour 8 bpm above the same movement at 17:00). So the expected heart rate adds an
//   offset for the hour: the 20th percentile of what is left after the movement baseline, for each clock hour (smoothed over the neighbouring hours). The score then says: above YOUR usual for this movement AND this time of day.
//   WAKING: the first 60 minutes after the first awake stretch of the day are not scored (on his 14 days 56 % of the first half hour reads high: the waking surge, which does not repeat at the same minute).
//   score of a 5-minute stretch = 100 x clamp((percent above the expected heart rate - 3) / 22, 0, 1): 3 % above reads 0, 25 % above reads 100; low under 20, medium 20-49, high 50 and over.
// DATA: on his 14 days steps alone explain half of the awake heart-rate variance (R2 0.49: baseline 63.9 bpm + 0.25 bpm per step a minute; wrist acceleration adds only 0.02 and is not used); by band the 20th percentile is
// 57.7 / 60.1 / 62.9 / 68.3 / 73.4 bpm. In the PhysioNet non-EEG data set (20 people) the same score separates cognitive tasks from relaxing with an AUC of 0.93 and reads 100 in the physical stage.
// LIMITS: it sees load that raises the heart rate above what movement explains (mental effort; also illness, heat, caffeine, alcohol, a meal, heavy lifting without steps); it does NOT see emotion without a heart-rate rise.
// The 5-minute bins of the last 14 days are kept in the day log's table stress_bin and extended each time (the first run reads the whole window once). Pure Java apart from load().

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StressModel {
    private StressModel() {}

    static final int BIN_S = 300, MIN_HR_SECONDS = 150, WINDOW_DAYS = 14, MIN_BAND_BINS = 12, MIN_SCORED_BINS = 6;
    static final double PCT_ZERO = 3.0, PCT_SPAN = 22.0;
    static final double[] EDGES = { 0.5, 5.0, 20.0, 40.0 };
    static final String[] BAND = { "still", "light", "steady", "brisk", "fast" };
    static final double FALLBACK_SLOPE = 0.25;       // bpm per step a minute, from the 14 days above

    static final class Bin { long t; float hr = Float.NaN, spm, eff, exp = Float.NaN; boolean awake, waking; int sec, band, score = -1; }

    static final class Result {
        int today = -1, now = -1, peak = -1, stillScore = -1, movingScore = -1;
        long peakT, dayStart, nowT;
        int lowMin, medMin, highMin, stillMin, movingMin;
        double yesterday = Double.NaN, week = Double.NaN, baseline = Double.NaN;      // baseline = the still band's expected heart rate
        double[] bandBase = new double[5]; int[] bandN = new int[5]; double[] bandCentre = new double[5]; double[] hourOffset = new double[24];      // hourOffset: bpm added to the movement baseline at each clock hour
        boolean fallback, baselineFromNights;
        final List<Bin> bins = new ArrayList<>();      // today's scored bins
        final List<Bin> all = new ArrayList<>();       // 0.53.8: every bin of the 14-day window, scored where it can be (the full-screen graphs)
        String level() { return levelOf(today); }
    }

    static String levelOf(int s) { return s < 0 ? "" : s < 20 ? "low" : s < 50 ? "medium" : "high"; }
    static int scoreOf(double hr, double expected) { double pct = 100.0 * (hr / expected - 1.0); return (int) Math.round(100.0 * Math.max(0.0, Math.min(1.0, (pct - PCT_ZERO) / PCT_SPAN))); }
    static int bandOf(double eff) { int b = 0; while (b < EDGES.length && eff >= EDGES[b]) b++; return b; }

    // ------------------------------------------------------------------ 5-minute bins from per-second records
    /** Feed per-second records in time order; finish() returns the bins. state 2 = the strap's sleep state; steps = the running step counter (16 bit). */
    static final class Agg {
        final List<Bin> out = new ArrayList<>(); private long cur = Long.MIN_VALUE; private final int[] hrs = new int[BIN_S]; private int k, sec, sleep, s0 = -1, s1 = -1;
        void feed(long ts, int hr, int state, int steps) {
            long b = ts / BIN_S * BIN_S;
            if (b != cur) { flush(); cur = b; k = 0; sec = 0; sleep = 0; s0 = -1; s1 = -1; }
            sec++; if (hr > 30 && hr < 230 && k < BIN_S) hrs[k++] = hr; if (state == 2) sleep++; if (s0 < 0) s0 = steps; s1 = steps;
        }
        private void flush() {
            if (cur == Long.MIN_VALUE) return;
            Bin B = new Bin(); B.t = cur; B.sec = sec; B.awake = sec > 0 && sleep < 0.1 * sec;
            if (k >= MIN_HR_SECONDS) { int[] v = Arrays.copyOf(hrs, k); Arrays.sort(v); B.hr = v[k / 2]; }
            int d = s0 < 0 ? 0 : (s1 >= s0 ? s1 - s0 : s1 + 65536 - s0); B.spm = (float) Math.min(200.0, d / Math.max(1.0, sec / 60.0));
            out.add(B);
        }
        List<Bin> finish() { flush(); cur = Long.MIN_VALUE; return out; }
    }

    // ------------------------------------------------------------------ the score
    private static double pct20(float[] v, int n) { Arrays.sort(v, 0, n); return v[(int) Math.floor(0.20 * (n - 1))]; }

    /** bins ascending by time. dayStart = local 04:00 of today; now = the newest strap second; nightsLowHr = the median lowest heart rate of the nights (NaN if none). */
    static Result score(List<Bin> all, long dayStart, long now, double nightsLowHr) { return score(all, dayStart, now, nightsLowHr, 0); }

    /** zoneSec = the phone's offset from UTC in seconds (the clock hour of a bin). */
    static Result score(List<Bin> all, long dayStart, long now, double nightsLowHr, int zoneSec) {
        Result R = new Result(); R.dayStart = dayStart; R.nowT = now;
        long from = now - WINDOW_DAYS * 86400L; List<Bin> bins = new ArrayList<>(); for (Bin b : all) if (b.t >= from) bins.add(b);
        int n = bins.size(); if (n == 0) return R; R.all.addAll(bins);
        java.util.HashMap<Long, Long> firstAwake = new java.util.HashMap<>();             // the first awake stretch of each day (04:00 to 04:00): its first hour is the waking surge
        for (Bin b : bins) if (b.awake) { long dk = Math.floorDiv(b.t + zoneSec - 4 * 3600L, 86400L); if (!firstAwake.containsKey(dk)) firstAwake.put(dk, b.t); }
        for (Bin b : bins) { Long f = firstAwake.get(Math.floorDiv(b.t + zoneSec - 4 * 3600L, 86400L)); b.waking = f != null && b.t < f + 3600L; }
        for (int i = 0; i < n; i++) {                                              // effective cadence: the heart rate lags the movement
            Bin b = bins.get(i); double e = b.spm;
            if (i >= 1 && b.t - bins.get(i - 1).t == BIN_S) e = Math.max(e, 0.6 * bins.get(i - 1).spm);
            if (i >= 2 && b.t - bins.get(i - 2).t == 2 * BIN_S) e = Math.max(e, 0.35 * bins.get(i - 2).spm);
            b.eff = (float) e; b.band = bandOf(e);
        }
        float[][] hv = new float[5][n]; int[] cnt = new int[5]; double[] esum = new double[5];
        for (Bin b : bins) if (b.awake && !Float.isNaN(b.hr)) { hv[b.band][cnt[b.band]++] = b.hr; esum[b.band] += b.eff; }
        List<double[]> pts = new ArrayList<>();
        for (int j = 0; j < 5; j++) { R.bandN[j] = cnt[j]; if (cnt[j] >= MIN_BAND_BINS) { double c = esum[j] / cnt[j], p = pct20(hv[j], cnt[j]); R.bandCentre[j] = c; R.bandBase[j] = p; pts.add(new double[] { c, p }); } else R.bandBase[j] = Double.NaN; }
        for (int i = 1; i < pts.size(); i++) if (pts.get(i)[1] < pts.get(i - 1)[1]) pts.get(i)[1] = pts.get(i - 1)[1];    // expected heart rate never falls as movement rises
        double base0 = Double.NaN;
        if (pts.size() >= 2) { base0 = pts.get(0)[1]; }
        else {
            if (pts.size() == 1 && pts.get(0)[0] < 1.0) base0 = pts.get(0)[1];
            else if (!Double.isNaN(nightsLowHr) && nightsLowHr > 30) { base0 = nightsLowHr * 1.15; R.baselineFromNights = true; }
            if (Double.isNaN(base0)) return R;
            R.fallback = true;
        }
        R.baseline = base0;
        // movement baseline per bin, then the offset of each clock hour (20th percentile of what movement leaves), smoothed over the neighbouring hours
        double[] mv = new double[n]; for (int i = 0; i < n; i++) { Bin b = bins.get(i); mv[i] = R.fallback ? base0 + FALLBACK_SLOPE * Math.min(b.eff, 80.0) : interp(pts, b.eff); }
        float[][] rv = new float[24][n]; int[] rc = new int[24];
        for (int i = 0; i < n; i++) { Bin b = bins.get(i); if (!b.awake || b.waking || Float.isNaN(b.hr)) continue; int hh = (int) ((((b.t + zoneSec) % 86400L) + 86400L) % 86400L / 3600L); rv[hh][rc[hh]++] = (float) (b.hr - mv[i]); }
        double[] raw = new double[24]; boolean[] have = new boolean[24]; int nh = 0;
        for (int h = 0; h < 24; h++) if (rc[h] >= 20) { raw[h] = Math.max(-6.0, Math.min(25.0, pct20(rv[h], rc[h]))); have[h] = true; nh++; }
        if (nh >= 6) {
            for (int h = 0; h < 24; h++) {
                double sw = 0, sv = 0; for (int d = -1; d <= 1; d++) { int k = (h + d + 24) % 24; if (!have[k]) continue; double w = d == 0 ? 0.5 : 0.25; sw += w; sv += w * raw[k]; }
                if (sw == 0) { int a = h, guard = 0; while (!have[a] && guard++ < 24) a = (a + 1) % 24; R.hourOffset[h] = have[a] ? raw[a] : 0.0; } else R.hourOffset[h] = sv / sw;
            }
        }
        for (int i = 0; i < n; i++) {
            Bin b = bins.get(i); if (!b.awake || b.waking || Float.isNaN(b.hr)) continue;
            int hh = (int) ((((b.t + zoneSec) % 86400L) + 86400L) % 86400L / 3600L); double ex = mv[i] + R.hourOffset[hh];
            b.exp = (float) ex; b.score = scoreOf(b.hr, ex);
        }
        long sumT = 0; int cntT = 0, sumS = 0, cS = 0, sumM = 0, cM = 0; long sumY = 0; int cY = 0, sumW = 0, cW = 0;
        for (Bin b : bins) {
            if (b.score < 0) continue;
            if (b.t >= dayStart && b.t <= now) {
                sumT += b.score; cntT++; R.bins.add(b);
                if (b.score < 20) R.lowMin += BIN_S / 60; else if (b.score < 50) R.medMin += BIN_S / 60; else R.highMin += BIN_S / 60;
                if (b.band == 0) { R.stillMin += BIN_S / 60; sumS += b.score; cS++; } else { R.movingMin += BIN_S / 60; sumM += b.score; cM++; }
                if (b.score > R.peak) { R.peak = b.score; R.peakT = b.t; }
                if (now - (b.t + BIN_S) <= 1200 && now >= b.t + BIN_S - 60) R.now = b.score;
            } else if (b.t >= dayStart - 86400L && b.t < dayStart) { sumY += b.score; cY++; }
            else if (b.t >= dayStart - 8 * 86400L && b.t < dayStart - 86400L) { sumW += b.score; cW++; }
        }
        if (cntT >= MIN_SCORED_BINS) R.today = (int) Math.round(sumT / (double) cntT);
        if (cS > 0) R.stillScore = (int) Math.round(sumS / (double) cS);
        if (cM > 0) R.movingScore = (int) Math.round(sumM / (double) cM);
        if (cY >= MIN_SCORED_BINS) R.yesterday = sumY / (double) cY;
        if (cW >= 3 * MIN_SCORED_BINS) R.week = sumW / (double) cW;
        return R;
    }

    private static double interp(List<double[]> p, double x) {
        if (x <= p.get(0)[0]) return p.get(0)[1];
        for (int i = 1; i < p.size(); i++) if (x <= p.get(i)[0]) { double[] a = p.get(i - 1), b = p.get(i); double w = (x - a[0]) / Math.max(1e-6, b[0] - a[0]); return a[1] + w * (b[1] - a[1]); }
        double[] a = p.get(p.size() - 2), b = p.get(p.size() - 1); double s = Math.max(0.0, (b[1] - a[1]) / Math.max(1e-6, b[0] - a[0])); return b[1] + s * Math.min(40.0, x - b[0]);
    }

    // ------------------------------------------------------------------ database (Android)
    /** Extends the stored 5-minute bins with the new seconds (the first run reads the whole 14-day window) and scores them. Call off the main thread. */
    static Result load(SQLiteDatabase db, long newest, List<TodayModel.NightStat> nights) {
        double low = Double.NaN; if (nights != null && !nights.isEmpty()) { List<Double> l = new ArrayList<>(); for (TodayModel.NightStat s : nights) if (!Double.isNaN(s.hrLow5)) l.add(s.hrLow5); if (!l.isEmpty()) { double[] v = new double[l.size()]; for (int i = 0; i < v.length; i++) v[i] = l.get(i); Arrays.sort(v); low = v[v.length / 2]; } }
        long windowStart = newest - WINDOW_DAYS * 86400L;
        db.execSQL("CREATE TABLE IF NOT EXISTS stress_bin (t INTEGER PRIMARY KEY, hr REAL, spm REAL, awake INTEGER, sec INTEGER)");
        long last = 0; Cursor m = db.rawQuery("SELECT MAX(t) FROM stress_bin", null); try { if (m.moveToFirst() && !m.isNull(0)) last = m.getLong(0); } finally { m.close(); }
        long from = Math.max(windowStart, last > 0 ? last - 2L * BIN_S : 0L);
        Agg agg = new Agg(); Cursor q = db.rawQuery("SELECT ts, hr, state, steps FROM r18 WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(from) });
        try { while (q.moveToNext()) agg.feed(q.getLong(0), q.getInt(1), q.getInt(2), q.getInt(3)); } finally { q.close(); }
        List<Bin> fresh = agg.finish();
        db.beginTransaction();
        try {
            for (Bin b : fresh) db.execSQL("INSERT OR REPLACE INTO stress_bin (t, hr, spm, awake, sec) VALUES (?,?,?,?,?)", new Object[] { b.t, Float.isNaN(b.hr) ? null : (Object) Double.valueOf(b.hr), (double) b.spm, b.awake ? 1 : 0, b.sec });
            db.execSQL("DELETE FROM stress_bin WHERE t < ?", new Object[] { windowStart - 9L * 86400L });
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        List<Bin> all = new ArrayList<>(); Cursor r = db.rawQuery("SELECT t, hr, spm, awake, sec FROM stress_bin WHERE t >= ? ORDER BY t", new String[] { String.valueOf(windowStart - 9L * 86400L) });
        try { while (r.moveToNext()) { Bin b = new Bin(); b.t = r.getLong(0); b.hr = r.isNull(1) ? Float.NaN : (float) r.getDouble(1); b.spm = (float) r.getDouble(2); b.awake = r.getInt(3) == 1; b.sec = r.getInt(4); all.add(b); } } finally { r.close(); }
        return score(all, TodayModel.dayStartOf(newest), newest, low, java.util.TimeZone.getDefault().getOffset(newest * 1000L) / 1000);
    }
}
