package com.noop.mgecg;

// FILE VERSION 0.31.0 (31 Oct 2026): "Your day, hour by hour": steps, still heart rate and skin temperature by local clock hour (median over the last days the day logger holds),
// the evening bump, and the link from each evening's still heart rate to that night. Computed from the day logger's per-second records; nothing is sent anywhere.

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static com.noop.mgecg.HealthViews.*;

final class DayTimeline {
    private DayTimeline() {}

    static final class Result {
        final double[] steps = new double[24], hr = new double[24], skin = new double[24], still = new double[24];
        final int[] hrDays = new int[24];
        int days; double lockWork = Double.NaN; String workWindow = "";
        final List<double[]> nights = new ArrayList<double[]>();         // {bed, evening still HR, first-3-hour HR, lowest 5-minute HR}
        double rho = Double.NaN; int rhoN;
        double rhoBump = Double.NaN; int rhoBumpN;                      // evening bump (evening minus afternoon still heart rate) against the first 3 hours of the night
        double hrDay = Double.NaN, hrEve = Double.NaN, hrNight = Double.NaN, skWork = Double.NaN, skEve = Double.NaN, skNight = Double.NaN;
    }

    private static final class HB { int n, still, skinN, lockOk, hn; double steps, skinSum; final int[] hist = new int[120]; }

    private static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    private static double histMedian(int[] h, int n) { int half = (n + 1) / 2, c = 0; for (int i = 0; i < h.length; i++) { c += h[i]; if (c >= half) return 35 + i; } return Double.NaN; }

    /** Spearman rank correlation with average ranks for ties. */
    static double spearman(double[] a, double[] b) {
        int n = a.length; if (n < 3) return Double.NaN; double[] ra = ranks(a), rb = ranks(b); double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += ra[i]; mb += rb[i]; } ma /= n; mb /= n;
        double sa = 0, sb = 0, sab = 0; for (int i = 0; i < n; i++) { double x = ra[i] - ma, y = rb[i] - mb; sa += x * x; sb += y * y; sab += x * y; } return sa == 0 || sb == 0 ? Double.NaN : sab / Math.sqrt(sa * sb);
    }
    private static double[] ranks(double[] v) {
        int n = v.length; Integer[] idx = new Integer[n]; for (int i = 0; i < n; i++) idx[i] = i; final double[] vv = v; Arrays.sort(idx, new java.util.Comparator<Integer>() { @Override public int compare(Integer x, Integer y) { return Double.compare(vv[x], vv[y]); } });
        double[] r = new double[n]; int i = 0; while (i < n) { int j = i; while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++; double rk = 0.5 * (i + j) + 1; for (int k = i; k <= j; k++) r[idx[k]] = rk; i = j + 1; } return r;
    }

    /** Pure analysis of per-second records in time order (tested off the phone against the Python reference). lk = pulse locked. beds in unix seconds with the night's lowest 5-minute heart rate. */
    static Result analyse(long[] t, short[] hr, byte[] st, boolean[] lk, float[] skin, int[] stp, int n, long[] beds, double[] low5, TimeZone tz) {
        Result R = new Result(); Map<Long, HB> map = new HashMap<Long, HB>(); long lastH = Long.MIN_VALUE; long off = 0;
        for (int i = 0; i < n; i++) {
            long h = t[i] / 3600L; if (h != lastH) { off = tz.getOffset(t[i] * 1000L) / 1000L; lastH = h; }
            long key = Math.floorDiv(t[i] + off, 3600L); HB b = map.get(key); if (b == null) { b = new HB(); map.put(key, b); }
            b.n++; if (st[i] >= 1) b.still++; if (lk[i]) b.lockOk++;
            if (i > 0 && t[i] - t[i - 1] == 1) { int d = stp[i] - stp[i - 1]; if (d > 0 && d < 50) b.steps += d; }
            if (skin[i] > 10f) { b.skinSum += skin[i]; b.skinN++; }
            if (st[i] >= 1 && lk[i] && hr[i] >= 35 && hr[i] <= 154) { b.hist[hr[i] - 35]++; b.hn++; }
        }
        List<List<Double>> ls = new ArrayList<List<Double>>(), lh = new ArrayList<List<Double>>(), lk2 = new ArrayList<List<Double>>(), lst = new ArrayList<List<Double>>();
        for (int i = 0; i < 24; i++) { ls.add(new ArrayList<Double>()); lh.add(new ArrayList<Double>()); lk2.add(new ArrayList<Double>()); lst.add(new ArrayList<Double>()); }
        java.util.HashSet<Long> dayset = new java.util.HashSet<Long>();
        for (Map.Entry<Long, HB> e : map.entrySet()) {
            HB b = e.getValue(); if (b.n < 2700) continue; int hod = (int) Math.floorMod(e.getKey(), 24L); dayset.add(Math.floorDiv(e.getKey(), 24L));
            ls.get(hod).add(b.steps * 3600.0 / b.n); lst.get(hod).add((double) b.still / b.n); if (b.skinN > 1800) lk2.get(hod).add(b.skinSum / b.skinN); if (b.hn >= 120) lh.get(hod).add(histMedian(b.hist, b.hn));
        }
        R.days = dayset.size();
        for (int h = 0; h < 24; h++) { R.steps[h] = median(ls.get(h)); R.still[h] = median(lst.get(h)); R.skin[h] = median(lk2.get(h)); R.hr[h] = median(lh.get(h)); R.hrDays[h] = lh.get(h).size(); }
        // active hours: median at least 800 steps an hour; first and last
        int a0 = -1, a1 = -1; for (int h = 0; h < 24; h++) if (!Double.isNaN(R.steps[h]) && R.steps[h] >= 800) { if (a0 < 0) a0 = h; a1 = h; }
        R.workWindow = a0 < 0 ? "" : String.format("%02d:00 to %02d:59", a0, a1);
        if (a0 >= 0) { double lo = 0, nn = 0; for (Map.Entry<Long, HB> e : map.entrySet()) { HB b = e.getValue(); if (b.n < 2700) continue; int hod = (int) Math.floorMod(e.getKey(), 24L); if (hod >= a0 && hod <= a1) { lo += b.lockOk; nn += b.n; } } if (nn > 0) R.lockWork = 100.0 * lo / nn; }
        R.hrDay = avg(R.hr, 7, 17); R.hrEve = avg(R.hr, 19, 22); R.hrNight = avg(R.hr, 0, 5);
        R.skWork = a0 < 0 ? Double.NaN : avg(R.skin, a0, a1); R.skEve = avg(R.skin, 19, 22); R.skNight = avg(R.skin, 0, 5);
        // evenings and nights
        List<Double> xe = new ArrayList<Double>(), xn = new ArrayList<Double>();
        for (int k = 0; k < beds.length; k++) {
            long bed = beds[k], o = tz.getOffset(bed * 1000L) / 1000L, lb = bed + o; long dayL = Math.floorDiv(lb, 86400L); if (Math.floorMod(lb, 86400L) < 12 * 3600L) dayL--;
            long e0 = dayL * 86400L + 19 * 3600L - o, e1 = bed - 1800L; double eve = rangeHr(t, hr, st, lk, n, e0, e1, true, 900), f3 = rangeHr(t, hr, st, lk, n, bed, bed + 3 * 3600L, false, 3000);
            double aft = rangeHr(t, hr, st, lk, n, dayL * 86400L + 12 * 3600L - o, dayL * 86400L + 18 * 3600L - o, true, 300);
            if (!Double.isNaN(eve) || !Double.isNaN(f3)) R.nights.add(new double[] { bed, eve, f3, low5[k], aft, Double.NaN, 0, 0 });   // {bed, evening, first 3 h, lowest 5 min, afternoon, first-3-hour RMSSD, drinks logged, alcohol-free marks}
            if (!Double.isNaN(eve) && !Double.isNaN(f3)) { xe.add(eve); xn.add(f3); }
        }
        R.rhoN = xe.size(); if (R.rhoN >= 6) { double[] a = new double[R.rhoN], b = new double[R.rhoN]; for (int i = 0; i < a.length; i++) { a[i] = xe.get(i); b[i] = xn.get(i); } R.rho = spearman(a, b); }
        List<Double> xb = new ArrayList<Double>(), xf = new ArrayList<Double>(); for (double[] x : R.nights) if (!Double.isNaN(x[1]) && !Double.isNaN(x[4]) && !Double.isNaN(x[2])) { xb.add(x[1] - x[4]); xf.add(x[2]); }
        R.rhoBumpN = xb.size(); if (R.rhoBumpN >= 6) { double[] a = new double[R.rhoBumpN], b = new double[R.rhoBumpN]; for (int i = 0; i < a.length; i++) { a[i] = xb.get(i); b[i] = xf.get(i); } R.rhoBump = spearman(a, b); }
        return R;
    }

    private static double avg(double[] v, int a, int b) { double s = 0; int c = 0; for (int h = a; h <= b; h++) if (!Double.isNaN(v[h])) { s += v[h]; c++; } return c == 0 ? Double.NaN : s / c; }

    /** Median (still seconds only) or mean heart rate over [from, to) for pulse-locked seconds; NaN when fewer than minN seconds. */
    private static double rangeHr(long[] t, short[] hr, byte[] st, boolean[] lk, int n, long from, long to, boolean stillOnly, int minN) {
        int i = lowerBound(t, n, from); int[] hist = new int[120]; int c = 0; double sum = 0;
        for (; i < n && t[i] < to; i++) { if (!lk[i] || hr[i] < 35 || hr[i] > 154) continue; if (stillOnly && st[i] < 1) continue; hist[hr[i] - 35]++; c++; sum += hr[i]; }
        if (c < minN) return Double.NaN; return stillOnly ? histMedian(hist, c) : sum / c;
    }
    private static int lowerBound(long[] t, int n, long x) { int lo = 0, hi = n; while (lo < hi) { int m = (lo + hi) >>> 1; if (t[m] < x) lo = m + 1; else hi = m; } return lo; }

    /** Reads the last days from the day logger database and analyses them (call off the main thread). */
    static Result load(Context c, List<TodayModel.NightStat> nights, int days) {
        long since = System.currentTimeMillis() / 1000L - days * 86400L;
        Cursor cu = DayLog.db(c).rawQuery("SELECT ts, hr, state, skin, steps, substr(rec,109,1) FROM r18 WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(since) });
        try {
            int cap = cu.getCount(); long[] t = new long[cap]; short[] hr = new short[cap]; byte[] st = new byte[cap]; boolean[] lk = new boolean[cap]; float[] sk = new float[cap]; int[] sp = new int[cap]; int n = 0;
            while (cu.moveToNext() && n < cap) { t[n] = cu.getLong(0); hr[n] = (short) cu.getInt(1); st[n] = (byte) cu.getInt(2); sk[n] = (float) cu.getDouble(3); sp[n] = cu.getInt(4); byte[] bl = cu.getBlob(5); lk[n] = bl != null && bl.length == 1 && bl[0] != (byte) 0x80; n++; }
            List<TodayModel.NightStat> ns = new ArrayList<TodayModel.NightStat>(); for (TodayModel.NightStat x : nights) if (x.bed >= since) ns.add(x);
            long[] beds = new long[ns.size()]; double[] low = new double[ns.size()]; for (int i = 0; i < beds.length; i++) { beds[i] = ns.get(i).bed; low[i] = ns.get(i).hrLow5; }
            Result res = analyse(t, hr, st, lk, sk, sp, n, beds, low, TimeZone.getDefault());
            try {
                List<Long> drinks = new ArrayList<Long>(), dry = new ArrayList<Long>(); HrvScreen.SQLiteHelper.markers(c, "alcohol", drinks); HrvScreen.SQLiteHelper.markers(c, "no_alcohol", dry);
                for (double[] x : res.nights) {
                    long bed = (long) x[0]; int dn = 0, yn = 0;
                    for (long m : drinks) if (m / 1000L >= bed - 10L * 3600L && m / 1000L <= bed + 3600L) dn++;
                    for (long m : dry) if (m / 1000L >= bed - 10L * 3600L && m / 1000L <= bed + 3600L) yn++;
                    x[6] = dn; x[7] = yn;
                    Cursor rc = DayLog.db(c).rawQuery("SELECT rec FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] { String.valueOf(bed), String.valueOf(bed + 3 * 3600L) });
                    List<byte[]> recs = new ArrayList<byte[]>(); try { while (rc.moveToNext()) { byte[] b = rc.getBlob(0); if (b != null) recs.add(b); } } finally { rc.close(); }
                    List<IrregularWatch.Win> ws = IrregularWatch.windows(recs); List<Double> v = new ArrayList<Double>(); for (IrregularWatch.Win w : ws) if (w.rest && w.hr > 0) v.add(w.nr * 60000.0 / w.hr);
                    x[5] = v.size() >= 3 ? median(v) : Double.NaN;
                }
            } catch (Throwable ignored) { }
            return res;
        } finally { cu.close(); }
    }

    /** 24 columns by local hour: bars (steps) and a line (still heart rate) or a line only. */
    static final class HourChart extends View {
        private double[] bars, line; private String note = ""; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        HourChart(Context c) { super(c); }
        void set(double[] b, double[] l, String n) { bars = b; line = l; note = n; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (line == null && bars == null) return; Context c = getContext(); float w = getWidth(), h = getHeight(), lab = dp(c, 14), ch = h - lab, bw = w / 24f;
            p.setTextSize(dp(c, 9)); p.setTextAlign(Paint.Align.CENTER);
            if (bars != null) { double mx = 1; for (double v : bars) if (!Double.isNaN(v)) mx = Math.max(mx, v); p.setStyle(Paint.Style.FILL); p.setColor(alpha(BLUE, 0.45f));
                for (int i = 0; i < 24; i++) if (!Double.isNaN(bars[i])) { float bh = (float) (ch * bars[i] / mx); cv.drawRoundRect(new RectF(i * bw + bw * 0.12f, ch - bh, i * bw + bw * 0.88f, ch), 3, 3, p); } }
            if (line != null) {
                double lo = 1e9, hi = -1e9; for (double v : line) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } if (hi > lo) { double pad = Math.max(0.5, (hi - lo) * 0.15); lo -= pad; hi += pad;
                    float[] xs = new float[24], ys = new float[24]; int m = 0; Path path = new Path(); int seg = 0;
                    for (int i = 0; i < 24; i++) { if (Double.isNaN(line[i])) { if (m >= 2) Trends.smoothPath(path, xs, ys, m); m = 0; continue; } xs[m] = i * bw + bw / 2f; ys[m] = (float) (ch * (1 - (line[i] - lo) / (hi - lo))); m++; }
                    if (m >= 2) Trends.smoothPath(path, xs, ys, m);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(c, 2.2f)); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(ROSE); cv.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL); for (int i = 0; i < 24; i++) if (!Double.isNaN(line[i])) cv.drawCircle(i * bw + bw / 2f, (float) (ch * (1 - (line[i] - lo) / (hi - lo))), dp(c, 2.6f), p);
                    p.setColor(0x99FFFFFF); p.setTextAlign(Paint.Align.LEFT); cv.drawText(String.format("%.0f", hi), 2, dp(c, 9), p); cv.drawText(String.format("%.0f", lo), 2, ch - 2, p); p.setTextAlign(Paint.Align.CENTER); } }
            p.setStyle(Paint.Style.FILL); p.setColor(0x99FFFFFF); for (int i = 0; i < 24; i += 3) cv.drawText(String.valueOf(i), i * bw + bw / 2f, h - 2, p);
        }
    }
}
