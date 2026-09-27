package com.noop.mgecg;

import java.util.Arrays;
import java.util.List;

/**
 * Published atrial-fibrillation screen on RR intervals, plus an early-beat
 * count. Pure Java (no Android) so it can be tested off-device.
 *
 * Dash S, Chon KH, Lu S, Raeder EA. "Automatic real time detection of
 * atrial fibrillation." Ann Biomed Eng 2009;37(9):1701-1709.
 * Three features on a 128-beat window; all three must hold for an
 * AF-like pattern:
 *   - nRMSSD = RMSSD / mean RR                        > 0.1
 *   - Shannon entropy over 16 equal-width bins, after trimming the 8
 *     shortest and 8 longest intervals, normalised by ln(16)  > 0.7
 *   - turning-point count consistent with a random series:
 *     |TP - (2N-4)/3| <= 1.96 * sqrt((16N-29)/90)
 *
 * Checked in this project (27 Sep 2026, offline): all 8 recorded sessions
 * with >= 128 clean beats -> not AF-like (nRMSSD 0.014-0.024), at both
 * 500 Hz and 10 ms RR resolution; a synthetic irregular series -> AF-like.
 * The entropy term is resolution-dependent (0.91-0.96 at 500 Hz vs
 * 0.53-0.73 at 10 ms on the same normal rhythm); nRMSSD and the
 * turning-point test carry the discrimination. No AF recordings were
 * available to test sensitivity. Not a diagnosis.
 */
public final class AfScreen {

    public static final int WINDOW = 128;

    private AfScreen() {}

    /**
     * @param rr RR intervals in ms (use exactly WINDOW values)
     * @return {nRMSSD, shannonEntropy, turningPoints, tpExpected, tpSd,
     *          tpRandom (1/0), afLike (1/0)}
     */
    public static double[] dash(double[] rr) {
        int n = rr.length;
        double mean = 0;
        for (double v : rr) mean += v;
        mean /= n;

        double ss = 0;
        for (int i = 1; i < n; i++) {
            double d = rr[i] - rr[i - 1];
            ss += d * d;
        }
        double nrmssd = Math.sqrt(ss / (n - 1)) / mean;

        double[] t = rr.clone();
        Arrays.sort(t);
        if (n > 24) t = Arrays.copyOfRange(t, 8, n - 8);
        double lo = t[0], hi = t[t.length - 1];
        int[] bins = new int[16];
        for (double v : t) {
            int b = (hi > lo) ? (int) ((v - lo) / (hi - lo) * 16) : 0;
            if (b >= 16) b = 15;
            bins[b]++;
        }
        double she = 0;
        for (int c : bins) {
            if (c == 0) continue;
            double p = c / (double) t.length;
            she -= p * Math.log(p);
        }
        she /= Math.log(16);

        int tp = 0;
        for (int i = 1; i < n - 1; i++) {
            boolean peak = rr[i] > rr[i - 1] && rr[i] > rr[i + 1];
            boolean trough = rr[i] < rr[i - 1] && rr[i] < rr[i + 1];
            if (peak || trough) tp++;
        }
        double mu = (2.0 * n - 4) / 3.0;
        double sd = Math.sqrt((16.0 * n - 29) / 90.0);
        boolean random = Math.abs(tp - mu) <= 1.96 * sd;

        boolean af = nrmssd > 0.1 && she > 0.7 && random;
        return new double[]{nrmssd, she, tp, mu, sd, random ? 1 : 0, af ? 1 : 0};
    }

    /**
     * Premature beats: an interval shorter than 80% of the median of the
     * previous 4, followed by an interval longer than that median
     * (the compensatory pause).
     */
    public static int earlyBeats(List<Integer> rr) {
        int n = 0;
        for (int i = 2; i < rr.size() - 1; i++) {
            int from = Math.max(0, i - 4);
            double[] prev = new double[i - from];
            for (int k = from; k < i; k++) prev[k - from] = rr.get(k);
            Arrays.sort(prev);
            double med = prev.length % 2 == 1 ? prev[prev.length / 2]
                    : (prev[prev.length / 2 - 1] + prev[prev.length / 2]) / 2.0;
            if (rr.get(i) < 0.8 * med && rr.get(i + 1) > med) n++;
        }
        return n;
    }
}
