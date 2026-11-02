package com.noop.mgecg;

// FILE VERSION 0.38.0 (1 Nov 2026): corrections for the MG wrist strap, fitted against the other devices on 26 Oct to 1 Nov 2026 (Helio and WHOOP 5 on the biceps, Polar H10 on the chest). Personal to this strap and this person; shown as
// a second opinion beside the raw numbers, never in place of them.
//  - STEPS: the wrist counter over-counts when you are not walking steadily. Helio steps over MG steps by MG steps a minute: 1-19 0.27, 20-39 0.42, 40-59 0.58, 60-79 0.63, 80-99 0.70, 100 or more 0.80; weighting each minute by these
//    brought the daily total within -11 % to +13 % of the Helio on 6 days (factors fitted on the same week).
//  - HRV: ECG RMSSD = 3.867 x (median successive difference of the optical beat intervals, ms) ^ 0.548 in still, pulse-locked 5-minute windows; tested on the second half of 75 H10 windows: median error 11 %, against 37 % for the raw optical RMSSD.
//  - HEART RATE: in sustained walking (100 or more steps a minute) the strap read 24 bpm too high against the H10 on 1 Nov; minutes like that are counted and flagged.

import android.content.Context;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class MgCalibrations {
    private MgCalibrations() {}

    static double stepFactor(int perMin) { return perMin < 1 ? 0.0 : perMin < 20 ? 0.27 : perMin < 40 ? 0.42 : perMin < 60 ? 0.58 : perMin < 80 ? 0.63 : perMin < 100 ? 0.70 : 0.80; }

    /** ECG-equivalent RMSSD (ms) from the median absolute successive difference (ms) of optical intervals. */
    static double eqRmssd(double medAbsDiffMs) { return 3.867 * Math.pow(medAbsDiffMs, 0.548); }

    static final class Day { long dayStart; double raw, corrected; int sustainedMin, sustainedLost; }
    static final class NightH { long bed; double rawFirst3 = Double.NaN, eqFirst3 = Double.NaN, eqLast3 = Double.NaN; }
    static final class Result { final List<Day> days = new ArrayList<Day>(); final List<NightH> nights = new ArrayList<NightH>(); int sustainedMin24, sustainedLost24; double sustainedHr24 = Double.NaN; }

    private static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    /** Pure: per-second {ts, step counter, pulse lock lost 0/1, hr}; returns days with raw and corrected steps and sustained-walking minutes. */
    static Result steps(List<long[]> secs, long nowSec) {
        Result r = new Result(); TreeMap<Long, int[]> minute = new TreeMap<Long, int[]>(); long prevT = -1, prevS = 0;       // minute start -> {steps, seconds, lost seconds, hr sum, hr n}
        for (long[] s : secs) {
            if (prevT >= 0 && s[0] == prevT + 1) { long d = (s[1] - prevS) & 0xFFFFL; if (d < 12) { int[] m = minute.get(s[0] / 60); if (m == null) { m = new int[5]; minute.put(s[0] / 60, m); } m[0] += (int) d; } }
            int[] m = minute.get(s[0] / 60); if (m == null) { m = new int[5]; minute.put(s[0] / 60, m); } m[1]++; if (s[2] == 1) m[2]++; if (s[3] > 30) { m[3] += (int) s[3]; m[4]++; }
            prevT = s[0]; prevS = s[1];
        }
        Calendar cal = Calendar.getInstance(); Map<Long, Day> days = new TreeMap<Long, Day>(); List<Double> hr24 = new ArrayList<Double>();
        for (Map.Entry<Long, int[]> e : minute.entrySet()) {
            long t = e.getKey() * 60L; int[] m = e.getValue(); if (m[1] < 30) continue;
            cal.setTimeInMillis(t * 1000L); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0); long d0 = cal.getTimeInMillis() / 1000L;
            Day dy = days.get(d0); if (dy == null) { dy = new Day(); dy.dayStart = d0; days.put(d0, dy); }
            dy.raw += m[0]; dy.corrected += m[0] * stepFactor(m[0]);
            if (m[0] >= 100) { dy.sustainedMin++; if (m[2] >= m[1] / 2) dy.sustainedLost++; if (t >= nowSec - 86400L) { r.sustainedMin24++; if (m[2] >= m[1] / 2) r.sustainedLost24++; if (m[4] > 0) hr24.add((double) m[3] / m[4]); } }
        }
        r.days.addAll(days.values()); r.sustainedHr24 = median(hr24); return r;
    }

    /** Pure: ECG-equivalent and raw optical RMSSD over still, pulse-locked 5-minute windows of the records in [from, to); NaN with fewer than 3 windows. out = {raw median, equivalent median}. */
    static double[] hrv(List<byte[]> recs, long from, long to) {
        List<Double> raw = new ArrayList<Double>(), eq = new ArrayList<Double>(); long w0 = (from / 300L) * 300L;
        int i = 0, n = recs.size();
        for (long w = w0; w < to; w += 300L) {
            List<Double> iv = new ArrayList<Double>(); int sec = 0, still = 0;
            while (i < n && R18Codec.ts(recs.get(i)) < w) i++;
            int j = i; while (j < n && R18Codec.ts(recs.get(j)) < w + 300L) {
                byte[] f = recs.get(j); j++; long t = R18Codec.ts(f); if (t < from || t >= to) continue; sec++; if (R18Codec.state(f) >= 1) still++;
                if ((R18Codec.u8(f, 33) & 0xF0) != 0 || f[108] == (byte) 0x80) continue; int k = Math.min(4, R18Codec.u8(f, 23));
                for (int q = 0; q < k; q++) { double v = R18Codec.rr(f, q); if (v >= 300 && v <= 2000) iv.add(v); }
            }
            if (iv.size() < 100 || sec == 0 || still < 0.8 * sec) continue;
            List<Double> kept = new ArrayList<Double>();
            for (int a = 0; a < iv.size(); a++) { int lo = Math.max(0, a - 11); double ref; if (a - lo >= 5) { List<Double> p = new ArrayList<Double>(iv.subList(lo, a)); Collections.sort(p); int m = p.size(); ref = m % 2 == 1 ? p.get(m / 2) : 0.5 * (p.get(m / 2 - 1) + p.get(m / 2)); } else ref = iv.get(a); if (Math.abs(iv.get(a) - ref) <= 0.4 * ref) kept.add(iv.get(a)); }
            if (kept.size() < 100) continue;
            List<Double> ad = new ArrayList<Double>(); double sq = 0; for (int a = 1; a < kept.size(); a++) { double d = kept.get(a) - kept.get(a - 1); ad.add(Math.abs(d)); sq += d * d; }
            raw.add(Math.sqrt(sq / ad.size())); eq.add(eqRmssd(median(ad)));
        }
        return raw.size() >= 3 ? new double[] { median(raw), median(eq) } : new double[] { Double.NaN, Double.NaN };
    }

    /** Reads the last 8 days for the steps and the last nights for the HRV (call off the main thread). */
    static Result compute(Context c, List<TodayModel.NightStat> nights) {
        long now = System.currentTimeMillis() / 1000L; List<long[]> secs = new ArrayList<long[]>();
        try {
            Cursor cu = DayLog.db(c).rawQuery("SELECT ts, steps, substr(rec,109,1), hr FROM r18 WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(now - 8 * 86400L) });
            try { while (cu.moveToNext()) { byte[] lb = cu.getBlob(2); secs.add(new long[] { cu.getLong(0), cu.getLong(1), lb != null && lb.length == 1 && lb[0] == (byte) 0x80 ? 1 : 0, cu.getLong(3) }); } } finally { cu.close(); }
        } catch (Throwable ignored) { }
        Result r = steps(secs, now);
        List<TodayModel.NightStat> ns = new ArrayList<TodayModel.NightStat>(nights); Collections.sort(ns, new java.util.Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat x, TodayModel.NightStat y) { return Long.compare(y.bed, x.bed); } });
        int cnt = 0;
        for (TodayModel.NightStat s : ns) {
            if (cnt++ >= 7) break;
            try {
                Cursor q = DayLog.db(c).rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] { String.valueOf(s.bed - 600L), String.valueOf(s.bed + 13 * 3600L) });
                List<byte[]> recs = new ArrayList<byte[]>(); try { while (q.moveToNext()) { byte[] b = q.getBlob(0); if (b != null) recs.add(b); } } finally { q.close(); }
                SleepNight.Night nt = SleepNight.analyse(recs); if (nt == null) continue; NightH h = new NightH(); h.bed = nt.bedTs;
                double[] f = hrv(recs, nt.bedTs, nt.bedTs + 3 * 3600L), l = hrv(recs, nt.endTs - 3 * 3600L, nt.endTs); h.rawFirst3 = f[0]; h.eqFirst3 = f[1]; h.eqLast3 = l[1]; r.nights.add(h);
            } catch (Throwable ignored) { }
        }
        return r;
    }
}
