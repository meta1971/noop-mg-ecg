package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline R-peak timing for the live R17 stream (100 Hz, 1 count = 1 uV).
 *
 * Why this exists: MainActivity's live detector thresholds the raw |x| samples
 * and quantises every interval to 10 ms. On the 29 Sep capture (190 s, quality
 * 3, sinus rhythm) that gave RMSSD 29 ms after the neighbour rule against 6.9 ms
 * from the stored 500 Hz record of the same seconds, i.e. sinus rhythm looked
 * about four times too irregular. R17 is the same signal as R16 decimated to
 * 100 Hz (coherence 1.000 from 1 to 40 Hz), so the timing is there; the live
 * detector was the weak part. This class takes the same approach as
 * EcgR16Analyzer.peaks(): zero-phase 5-19 Hz band-pass, an amplitude threshold,
 * and sub-sample refinement. On the same capture it gives RMSSD 6.8 ms.
 *
 * Pure Java, no Android types. Research use only, not a diagnosis.
 */
final class EcgR17Beats {

    static final int FS = 100;
    /** Segments shorter than this are dropped (filter start-up, too few beats to trust). */
    static final int MIN_SEGMENT = 8 * FS;
    /** Beats this close to a segment edge are dropped (band-pass edge transient). */
    static final int EDGE = (int) (0.4 * FS);
    /** A candidate is the largest lobe within this many samples of the threshold crossing. */
    private static final int LOBE = 6;

    private EcgR17Beats() {}

    // ---------------------------------------------------------------- filters

    private static double[][] coeffs(boolean low, double fc) {
        double K = Math.tan(Math.PI * fc / FS), q = 1 / Math.sqrt(2), n = 1 / (1 + K / q + K * K);
        double[] b = low ? new double[]{K * K * n, 2 * K * K * n, K * K * n} : new double[]{n, -2 * n, n};
        double[] a = {1, 2 * (K * K - 1) * n, (1 - K / q + K * K) * n};
        return new double[][]{b, a};
    }

    private static double[] biquad(double[] x, double[] b, double[] a) {
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double o = b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = o; y[i] = o;
        }
        return y;
    }

    private static double[] reverse(double[] x) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = x[x.length - 1 - i];
        return r;
    }

    /** Zero-phase 5 Hz high-pass then 19 Hz low-pass (each run forward and backward). */
    static double[] bandpass(double[] x) {
        boolean[] kinds = {false, true};
        double[] fcs = {5.0, 19.0};
        for (int i = 0; i < 2; i++) {
            double[][] c = coeffs(kinds[i], fcs[i]);
            x = biquad(x, c[0], c[1]);
            x = reverse(biquad(reverse(x), c[0], c[1]));
        }
        return x;
    }

    private static double percentile(double[] v, int from, int to, double p) {   // numpy 'linear'
        double[] a = Arrays.copyOfRange(v, from, to);
        Arrays.sort(a);
        double pos = p / 100.0 * (a.length - 1);
        int f = (int) Math.floor(pos), c = Math.min(f + 1, a.length - 1);
        return a[f] + (a[c] - a[f]) * (pos - f);
    }

    // ------------------------------------------------------------------ beats

    /**
     * R-peak times in samples (fractional) from the start of one contiguous
     * segment. Empty when the segment is too short.
     */
    static double[] beatSamples(int[] seg) {
        int n = seg.length;
        if (n < MIN_SEGMENT) return new double[0];
        double[] x = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) mean += seg[i];
        mean /= n;
        for (int i = 0; i < n; i++) x[i] = seg[i] - mean;
        double[] y = bandpass(x);
        // R wave positive, as EcgR16Analyzer.peaks() does
        if (-percentile(y, 0, n, 0.5) > percentile(y, 0, n, 99.5)) {
            for (int i = 0; i < n; i++) y[i] = -y[i];
        }
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = Math.abs(y[i]);
        // per 5 s block threshold from the local 99.5th percentile (+-5 s context)
        int w = 5 * FS;
        double[] thr = new double[n];
        for (int s = 0; s < n; s += w) {
            double t = 0.5 * percentile(e, Math.max(0, s - w), Math.min(n, s + 2 * w), 99.5);
            for (int i = s; i < Math.min(n, s + w); i++) thr[i] = t;
        }
        int refr = (int) (0.33 * FS);
        List<Double> out = new ArrayList<>();
        int last = Integer.MIN_VALUE / 2;
        int i = 1;
        while (i < n - 1) {
            if (e[i] > thr[i] && i - last >= refr) {
                int m = i, hi = Math.min(n - 2, i + LOBE);
                for (int j = i; j <= hi; j++) if (e[j] > e[m]) m = j;
                double t = m;
                if (m > 0 && m < n - 1) {
                    double d = e[m - 1] - 2 * e[m] + e[m + 1];
                    if (d != 0) t = m + 0.5 * (e[m - 1] - e[m + 1]) / d;
                }
                if (m >= EDGE && m < n - EDGE) out.add(t);
                last = m;
                i = m + 1;
            } else {
                i++;
            }
        }
        double[] r = new double[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    /**
     * RR intervals in ms from every segment. An interval is never formed
     * across two segments, and only those between 300 and 2000 ms are kept.
     */
    static List<Integer> rrMs(List<int[]> segments) {
        List<Integer> rr = new ArrayList<>();
        for (int[] seg : segments) {
            double[] b = beatSamples(seg);
            for (int k = 1; k < b.length; k++) {
                long ms = Math.round((b[k] - b[k - 1]) * 1000.0 / FS);
                if (ms >= 300 && ms <= 2000) rr.add((int) ms);
            }
        }
        return rr;
    }
}
