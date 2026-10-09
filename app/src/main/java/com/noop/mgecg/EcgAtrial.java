package com.noop.mgecg;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Waveform evidence for the P wave. In atrial fibrillation the P wave before each QRS is absent or
 * inconsistent, so averaging the 280 ms before every beat leaves a small, unreliable bump; in sinus
 * rhythm it leaves a clear, repeatable one. Two numbers, both independent of how many beats there are:
 *   pampRel   peak-to-peak of the averaged P window (-260..-80 ms before R) divided by the median R height
 *   splitHalf Spearman-Brown corrected correlation between the averages of two random halves of the beats
 * Measured on 1,210 labelled CinC 2017 recordings, pampRel alone lifts AF detection at 1.5% false alarms
 * from about 74% to about 79% (cross-validated). RESEARCH ONLY, not a diagnosis.
 */
final class EcgAtrial {
    private EcgAtrial() {}

    static final int FS = 500;
    static final int PRE = 160;     // 320 ms
    static final int END = 20;      // 40 ms
    static final int A0 = 30;       // 60 ms into the segment  (-260 ms)
    static final int A1 = 120;      // 240 ms into the segment (-80 ms)
    static final int MIN_BEATS = 12;
    static final int SMOOTH = 9;    // 18 ms box

    /** Zero-phase 0.67-40 Hz (two 2nd-order sections each way). */
    static double[] wide(double[] x) {
        double[][] hp = EcgR16Analyzer.coeffs(false, 0.67);
        double[][] lp = EcgR16Analyzer.coeffs(true, 40.0);
        double[] y = EcgR16Analyzer.biquad(x, hp[0], hp[1]);
        y = EcgR16Analyzer.reverse(EcgR16Analyzer.biquad(EcgR16Analyzer.reverse(y), hp[0], hp[1]));
        y = EcgR16Analyzer.biquad(y, lp[0], lp[1]);
        y = EcgR16Analyzer.reverse(EcgR16Analyzer.biquad(EcgR16Analyzer.reverse(y), lp[0], lp[1]));
        return y;
    }

    /** Adds the P-window segment of every accepted beat of one run. {@code inv}: the run's QRS points down. */
    static void collect(double[] counts, List<Integer> pk, Set<Integer> bad, boolean inv,
                        List<double[]> segs, List<Double> rAbs) {
        if (counts.length < 4 * FS || pk.size() < 3) return;
        double[] w = wide(counts);
        if (inv) for (int i = 0; i < w.length; i++) w[i] = -w[i];
        for (int i = 0; i < pk.size(); i++) {
            int p = pk.get(i);
            if (bad.contains(p) || p < 0 || p >= w.length) continue;
            rAbs.add(Math.abs(w[p]));
            if (i == 0) continue;
            int rr = p - pk.get(i - 1);
            if (rr < 225 || rr > 900 || p - PRE < 0) continue;
            double[] s = new double[PRE - END];
            System.arraycopy(w, p - PRE, s, 0, s.length);
            double base = median(s, 0, 20);
            for (int k = 0; k < s.length; k++) s[k] -= base;
            segs.add(s);
        }
    }

    /** {pampRel, splitHalf, beats} or null when fewer than MIN_BEATS beats had a usable P window. */
    static double[] features(List<double[]> segs, List<Double> rAbs) {
        int n = segs.size();
        if (n < MIN_BEATS || rAbs.isEmpty()) return null;
        int len = A1 - A0;
        double[] tpl = new double[len];
        for (double[] s : segs) for (int k = 0; k < len; k++) tpl[k] += s[A0 + k] / n;
        double[] sm = smooth(tpl);
        double mn = 1e18, mx = -1e18;
        for (double v : sm) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        double[] ra = new double[rAbs.size()];
        for (int i = 0; i < ra.length; i++) ra[i] = rAbs.get(i);
        double rmed = median(ra, 0, ra.length) + 1e-9;
        Random rnd = new Random(7);
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        int h = n / 2;
        double sum = 0; int cnt = 0;
        for (int rep = 0; rep < 12; rep++) {
            for (int i = n - 1; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = idx[i]; idx[i] = idx[j]; idx[j] = t; }
            double[] t1 = new double[len], t2 = new double[len];
            for (int a = 0; a < h; a++) for (int k = 0; k < len; k++) {
                t1[k] += segs.get(idx[a])[A0 + k] / h;
                t2[k] += segs.get(idx[h + a])[A0 + k] / h;
            }
            double c = corr(smooth(t1), smooth(t2));
            if (!Double.isNaN(c)) { sum += c; cnt++; }
        }
        double r = cnt == 0 ? 0 : sum / cnt;
        double rsb = r > -0.9 ? 2 * r / (1 + r) : 0;
        return new double[] {(mx - mn) / rmed, rsb, n};
    }

    static double[] smooth(double[] v) {
        int n = v.length, half = SMOOTH / 2;
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = -half; j <= half; j++) { int q = i + j; if (q >= 0 && q < n) s += v[q]; }
            o[i] = s / SMOOTH;
        }
        return o;
    }

    static double corr(double[] a, double[] b) {
        int n = a.length; double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) { double x = a[i] - ma, y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y; }
        return (saa <= 0 || sbb <= 0) ? Double.NaN : sab / Math.sqrt(saa * sbb);
    }

    static double median(double[] v, int from, int to) {
        double[] a = java.util.Arrays.copyOfRange(v, from, to);
        java.util.Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }
}
