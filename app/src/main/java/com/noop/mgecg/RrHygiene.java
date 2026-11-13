package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. Beat-interval hygiene for the strap's per-second records (R18 bytes 23-31: a count 0-4 and four u16 intervals in ms), the three checks of MG_rr_check.py:
//   1. an exact 500 ms filler interval at rest (count at 500 against the mean of the 20 neighbours 490-510, with 488 and 512 as controls);
//   2. a second that repeats the last beat(s) of the second before it (against a +30 s control that cannot overlap);
//   3. coverage (sum of intervals / seconds) and night RMSSD with and without the cleaning.
// Reference values from OTHER people's straps (whoop-rs): healthy coverage 0.93-1.10, a doubled stream 1.49-2.40. Nothing is removed unless this strap's own numbers show the pattern. Pure Java.
// 14 Nov 2026 (MG_rr_check output of 36 nights, this strap): exact-500 filler ratio 581 asleep under 100 bpm (other strap 44 falling to 4); repeats 2.20 % at +1 s against 1.24 % at +30 s (not a pattern: rule needs
// over 3 % and 4 x the control); night coverage 0.88-0.94 (median 0.91, 0.88 after cleaning), never doubled; median RMSSD change with the filler out -1.9 % (20-percent local-median rule), 7 of 20 nights over 5 %, largest 26 %.
// The app's STORED night RMSSD (SleepNight.rmssd) keeps a difference only when it is under 12 % of the interval, which already drops most filler pairs, and reads about 15 % below the check's figure on the same nights
// (median 0.86 of rmssd1, 20 nights). So the card compares like with like: app0 / app1 / app2 below are the app's own 12 % rule applied to the all-intervals, filler-out and filler-and-repeats-out streams.

import java.util.Arrays;
import java.util.Locale;

final class RrHygiene {
    private RrHygiene() {}

    static final int RR_LO = 300, RR_HI = 2000;

    static final class Result {
        int seconds;                                    // seconds used (strap state asleep)
        double cov0 = Double.NaN, cov2 = Double.NaN;    // coverage before and after cleaning
        double rmssd0 = Double.NaN, rmssd1 = Double.NaN, rmssd2 = Double.NaN;
        double app0 = Double.NaN, app1 = Double.NaN, app2 = Double.NaN;      // the same three streams under the app's own stored-RMSSD rule (difference under 12 % of the interval)
        int n500, tested, hits, ctested, chits;
        double fillerRatio = Double.NaN, ratio488 = Double.NaN, ratio512 = Double.NaN;
        double repeatRate = Double.NaN, repeatCtl = Double.NaN;      // percent
        boolean fillerFlag, repeatFlag;
        /** 0 not enough data, 1 clean, 2 cleaned (a pattern was found and removed), 3 doubtful (coverage outside 0.80-1.15 after cleaning) */
        int grade;
        double best() { return !Double.isNaN(rmssd2) ? rmssd2 : !Double.isNaN(rmssd1) ? rmssd1 : rmssd0; }
        String badge() {
            switch (grade) {
                case 1: return "R-R clean";
                case 2: return "R-R cleaned";
                case 3: return "R-R doubtful";
                default: return "R-R: too little";
            }
        }
        String line() {
            if (grade == 0) return "Not enough asleep seconds with beat intervals to check.";
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.UK, "coverage %.2f", cov0));
            if (!Double.isNaN(cov2) && Math.abs(cov2 - cov0) > 0.005) b.append(String.format(Locale.UK, " (%.2f cleaned)", cov2));
            b.append(fillerFlag ? String.format(Locale.UK, "  ·  500 ms filler found (x%.1f of its neighbours)", fillerRatio) : "  ·  no 500 ms filler");
            b.append(repeatFlag ? String.format(Locale.UK, "  ·  repeated beats %.1f %% of seconds", repeatRate) : "  ·  no repeated beats");
            return b.toString();
        }
    }

    /**
     * Records in time order, one per second. ts unix seconds; hr bpm; cnt = interval count (0-4); slots = 4 u16 per record; use[j] = the second counts (strap state asleep).
     * Nothing is removed unless the pattern is present in these seconds.
     */
    static Result analyse(long[] ts, int[] hr, int[] cnt, int[] slots, boolean[] use) {
        Result r = new Result();
        int n = ts.length;
        // ---- check 1: the 500 ms filler, among the used seconds below 100 bpm
        int[] c = new int[2001];
        int nUse = 0;
        for (int j = 0; j < n; j++) {
            if (!use[j]) continue;
            nUse++;
            if (!(hr[j] > 0 && hr[j] < 100)) continue;
            for (int q = 0; q < cnt[j] && q < 4; q++) { int v = slots[4 * j + q]; if (v >= 0 && v <= 2000) c[v]++; }
        }
        r.seconds = nUse;
        r.n500 = c[500];
        double b500 = base(c, 500), b488 = base(c, 488), b512 = base(c, 512);
        r.fillerRatio = b500 > 0 ? c[500] / b500 : (c[500] > 0 ? Double.POSITIVE_INFINITY : Double.NaN);
        r.ratio488 = b488 > 0 ? c[488] / b488 : Double.NaN;
        r.ratio512 = b512 > 0 ? c[512] / b512 : Double.NaN;
        double ctl = Math.max(nz(r.ratio488), nz(r.ratio512));
        r.fillerFlag = r.n500 >= 20 && r.fillerRatio >= 3.0 && r.fillerRatio >= 2.0 * Math.max(1.0, ctl);
        // ---- check 2: repeated beats
        int[] dropk = new int[n];
        for (int j = 1; j < n; j++) {
            if (ts[j] != ts[j - 1] + 1 || cnt[j] == 0 || cnt[j - 1] == 0) continue;
            r.tested++;
            int k = overlap(cnt, slots, j - 1, j);
            if (k > 0) { r.hits++; dropk[j] = k; }
        }
        for (int j = 30; j < n; j++) {
            if (ts[j] != ts[j - 30] + 30 || cnt[j] == 0 || cnt[j - 30] == 0) continue;
            r.ctested++;
            if (overlap(cnt, slots, j - 30, j) > 0) r.chits++;
        }
        r.repeatRate = r.tested > 0 ? 100.0 * r.hits / r.tested : Double.NaN;
        r.repeatCtl = r.ctested > 0 ? 100.0 * r.chits / r.ctested : Double.NaN;
        r.repeatFlag = r.tested >= 500 && r.repeatRate > Math.max(3.0, 4.0 * nz(r.repeatCtl));
        // ---- check 3: coverage and RMSSD
        double[] v0 = new double[nUse * 4 + 4]; boolean[] k0 = new boolean[v0.length]; int m0 = build(ts, hr, cnt, slots, use, dropk, 0, v0, k0);
        double[] v1 = new double[v0.length]; boolean[] k1 = new boolean[v0.length]; int m1 = build(ts, hr, cnt, slots, use, dropk, 1, v1, k1);
        double[] v2 = new double[v0.length]; boolean[] k2 = new boolean[v0.length]; int m2 = build(ts, hr, cnt, slots, use, dropk, 2, v2, k2);
        r.rmssd0 = cleanRmssd(v0, k0, m0);
        r.rmssd1 = cleanRmssd(v1, k1, m1);
        r.rmssd2 = cleanRmssd(v2, k2, m2);
        r.app0 = appRmssd(v0, k0, m0); r.app1 = appRmssd(v1, k1, m1); r.app2 = appRmssd(v2, k2, m2);
        if (nUse >= 1800) {
            double s0 = 0; for (int i = 0; i < m0; i++) s0 += v0[i];
            double s2 = 0; for (int i = 0; i < m2; i++) s2 += v2[i];
            r.cov0 = s0 / 1000.0 / nUse;
            r.cov2 = s2 / 1000.0 / nUse;
        }
        if (nUse < 1800 || Double.isNaN(r.cov0)) r.grade = 0;
        else {
            boolean found = r.fillerFlag || r.repeatFlag;
            double fin = found ? r.cov2 : r.cov0;
            r.grade = (fin >= 0.80 && fin <= 1.15) ? (found ? 2 : 1) : 3;
        }
        return r;
    }

    /** The app's own variant of the quote (same choice rule, app's 12 % rule). */
    static double chosenApp(Result r) {
        if (r.fillerFlag && r.repeatFlag) return r.app2;
        if (r.fillerFlag) return r.app1;
        if (r.repeatFlag) return r.app2;
        return r.app0;
    }

    /** SleepNight.rmssd on a stream: successive differences kept when under 12 % of the interval, not across a break; NaN under 100 differences. */
    static double appRmssd(double[] vals, boolean[] brk, int m) {
        double ss = 0; int c = 0;
        for (int i = 1; i < m; i++) {
            if (brk[i]) continue;
            double d = vals[i] - vals[i - 1];
            if (Math.abs(d) < SleepNight.RMSSD_KEEP_SHARE * vals[i]) { ss += d * d; c++; }
        }
        return c > 100 ? Math.sqrt(ss / c) : Double.NaN;
    }

    /** Which variant of RMSSD to quote: filler removed only when the filler is present, repeats removed only when they are present. */
    static double chosenRmssd(Result r) {
        if (r.fillerFlag && r.repeatFlag) return r.rmssd2;
        if (r.fillerFlag) return r.rmssd1;
        if (r.repeatFlag) return r.rmssd2;      // variant 2 also removes the filler; with no filler present nothing of that kind is removed (the rule needs HR under 100 and a value of exactly 500)
        return r.rmssd0;
    }

    private static double nz(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v; }

    private static double base(int[] c, int v) {
        double s = 0; int k = 0;
        for (int d = -10; d <= 10; d++) { if (d == 0) continue; int i = v + d; if (i < 0 || i > 2000) continue; s += c[i]; k++; }
        return k > 0 ? s / k : 0.0;
    }

    /** Largest k such that the last k intervals of record a equal the first k of record b; negative when every one of those is 500 (ambiguous, not counted). */
    static int overlap(int[] cnt, int[] sl, int a, int b) {
        int na = Math.min(4, cnt[a]), nb = Math.min(4, cnt[b]);
        if (na == 0 || nb == 0) return 0;
        for (int k = Math.min(na, nb); k >= 1; k--) {
            boolean eq = true;
            for (int i = 0; i < k; i++) if (sl[4 * a + na - k + i] != sl[4 * b + i]) { eq = false; break; }
            if (!eq) continue;
            boolean all500 = true; for (int i = 0; i < k; i++) if (sl[4 * b + i] != 500) { all500 = false; break; }
            return all500 ? -k : k;
        }
        return 0;
    }

    private static int build(long[] ts, int[] hr, int[] cnt, int[] slots, boolean[] use, int[] dropk, int variant, double[] vals, boolean[] brk) {
        int m = 0; boolean pend = true; long last = Long.MIN_VALUE;
        for (int j = 0; j < ts.length; j++) {
            if (!use[j]) { pend = true; last = Long.MIN_VALUE; continue; }
            long t = ts[j];
            if (last != Long.MIN_VALUE && t != last + 1) pend = true;
            last = t;
            int skip = 0;
            if (variant >= 2 && dropk[j] > 0 && j > 0 && use[j - 1] && ts[j - 1] == t - 1) skip = dropk[j];
            for (int q = skip; q < Math.min(4, cnt[j]); q++) {
                int v = slots[4 * j + q];
                if (v < RR_LO || v > RR_HI) { pend = true; continue; }
                if (variant >= 1 && v == 500 && hr[j] > 0 && hr[j] < 100) { pend = true; continue; }
                vals[m] = v; brk[m] = pend; pend = false; m++;
            }
        }
        return m;
    }

    /** RMSSD of adjacent intervals after the 20 % local-median rule (window of 5); NaN under 100 pairs. */
    static double cleanRmssd(double[] vals, boolean[] brk, int m) {
        boolean[] keep = new boolean[m];
        for (int i = 0; i < m; i++) {
            keep[i] = true;
            int a = Math.max(0, i - 2), b = Math.min(m, i + 3);
            if (b - a < 3) continue;
            double[] w = Arrays.copyOfRange(vals, a, b); Arrays.sort(w);
            int k = w.length; double med = (k % 2 == 1) ? w[k / 2] : 0.5 * (w[k / 2 - 1] + w[k / 2]);
            if (Math.abs(vals[i] - med) > 0.2 * med) keep[i] = false;
        }
        boolean have = false; double prev = 0, ss = 0; int cnt = 0;
        for (int i = 0; i < m; i++) {
            if (!keep[i]) { have = false; continue; }
            if (brk[i]) have = false;
            if (have) { double d = vals[i] - prev; ss += d * d; cnt++; }
            prev = vals[i]; have = true;
        }
        return cnt >= 100 ? Math.sqrt(ss / cnt) : Double.NaN;
    }
}
