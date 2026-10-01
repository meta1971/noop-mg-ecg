package com.noop.mgecg;

import java.util.ArrayList;
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

    // ------------------------------------------------------------ front end (0.1.7)

    /** Outcome states of {@link #screen}. */
    public static final int AF_LIKE = 1, IRREGULAR = 2, NOT_AF_LIKE = 3, INCONCLUSIVE = 4;
    /** Smallest window the screen will run on, and the share of intervals above which it gives up. */
    public static final int MIN_WINDOW = 64;
    public static final double MAX_CORRECTED_SHARE = 0.25;

    /**
     * What the screen decided, and how it got there.
     *
     * AF_LIKE        the cleaned series is AF-like (and, with 128+ beats, both 64-beat halves agree)
     * IRREGULAR      not AF-like after cleaning, but more than 15% of intervals differ by over 20% from
     *                their neighbours, or the raw series is AF-like: never reassuring, cause unclear
     * NOT_AF_LIKE    clean, regular enough, and not AF-like on either the cleaned or the raw series
     * INCONCLUSIVE   fewer than 64 clean beats, or more than 25% had to be corrected or removed
     */
    public static final class Outcome {
        public int state = INCONCLUSIVE;
        /** Beats the verdict used: 128, 64, or 0 when it did not run. */
        public int window;
        /** Dash features of the verdict window (the last 128 or last 64), or null. */
        public double[] dash;
        /** With a 128-beat window: whether both 64-beat halves were also AF-like. */
        public boolean halvesAgree;
        public int input, merged, split, premature, cleaned;
        public double correctedShare, rhythmOutShare;
        public boolean rawAfLike;
        public String why = "";

        public String toLog() {
            return String.format(java.util.Locale.US,
                    "afState=%d window=%d input=%d cleaned=%d merged=%d split=%d prematureRemoved=%d "
                            + "correctedShare=%.3f rhythmOutShare=%.3f rawAfLike=%b halvesAgree=%b%s",
                    state, window, input, cleaned, merged, split, premature,
                    correctedShare, rhythmOutShare, rawAfLike, halvesAgree,
                    why.isEmpty() ? "" : " why=" + why.replace(' ', '_'));
        }
    }

    private static double median(double[] v, int from, int to) {
        double[] t = Arrays.copyOfRange(v, from, to);
        Arrays.sort(t);
        int n = t.length;
        return n % 2 == 1 ? t[n / 2] : 0.5 * (t[n / 2 - 1] + t[n / 2]);
    }

    /** Intervals more than 20% away from the median of their up-to-4 neighbours. */
    private static int neighbourOut(double[] rr) {
        int bad = 0;
        for (int i = 0; i < rr.length; i++) {
            int lo = Math.max(0, i - 2), hi = Math.min(rr.length, i + 3);
            double[] nb = new double[hi - lo - 1];
            int k = 0;
            for (int j = lo; j < hi; j++) if (j != i) nb[k++] = rr[j];
            if (nb.length == 0) continue;
            Arrays.sort(nb);
            double med = nb.length % 2 == 1 ? nb[nb.length / 2] : 0.5 * (nb[nb.length / 2 - 1] + nb[nb.length / 2]);
            if (Math.abs(rr[i] - med) > 0.2 * med) bad++;
        }
        return bad;
    }

    /**
     * Repairs detector errors: a pair of short intervals that add up to one normal interval (a doubled
     * beat) is merged; an interval about twice normal (a missed beat) is split in two. counts[0] = merges,
     * counts[1] = splits.
     */
    private static double[] repair(double[] rr, int[] counts) {
        List<Double> out = new ArrayList<>();
        int i = 0;
        while (i < rr.length) {
            int lo = Math.max(0, i - 2), hi = Math.min(rr.length, i + 3);
            double[] nb = new double[hi - lo - 1];
            int k = 0;
            for (int j = lo; j < hi; j++) if (j != i) nb[k++] = rr[j];
            double med = nb.length == 0 ? rr[i] : median(nb, 0, nb.length);
            if (i < rr.length - 1 && rr[i] < 0.65 * med && rr[i] + rr[i + 1] >= 0.8 * med && rr[i] + rr[i + 1] <= 1.2 * med) {
                out.add(rr[i] + rr[i + 1]);
                counts[0]++;
                i += 2;
                continue;
            }
            if (rr[i] >= 1.8 * med && rr[i] <= 2.2 * med) {
                out.add(rr[i] / 2);
                out.add(rr[i] / 2);
                counts[1]++;
                i++;
                continue;
            }
            out.add(rr[i]);
            i++;
        }
        double[] r = new double[out.size()];
        for (int j = 0; j < r.length; j++) r[j] = out.get(j);
        return r;
    }

    /** Removes each premature beat together with the pause that follows it. Returns the number removed. */
    private static double[] removePremature(double[] rr, int[] removedOut) {
        boolean[] drop = new boolean[rr.length];
        for (int i = 4; i < rr.length - 1; i++) {
            double med = median(rr, i - 4, i);
            if (rr[i] < 0.8 * med && rr[i + 1] > med) {
                drop[i] = true;
                drop[i + 1] = true;
            }
        }
        int n = 0;
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < rr.length; i++) {
            if (drop[i]) n++;
            else out.add(rr[i]);
        }
        removedOut[0] += n;
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static boolean afLike(double[] d) {
        return d[6] > 0.5;
    }

    private static double[] lastN(List<Double> v, int n, int skipFromEnd) {
        double[] w = new double[n];
        int end = v.size() - skipFromEnd;
        for (int i = 0; i < n; i++) w[i] = v.get(end - n + i);
        return w;
    }

    /**
     * The 0.1.7 AF screen. Takes beat intervals in ms, one array per contiguous run (a run never spans a
     * contact loss), repairs detector errors, removes premature beats with their pauses, then applies the
     * Dash screen on the last 128 cleaned beats (and requires both 64-beat halves to agree) or, with 64 to
     * 127 cleaned beats, on the last 64.
     *
     * Checked on synthetic series only (400 per case, 2026-10-01): 78% of synthetic AF flagged, 1% shown as
     * not AF-like, 0.4% of normal-rhythm series flagged, and frequent premature beats or detector errors
     * land on IRREGULAR rather than reassurance. No real AF recording has been available; this has never
     * been run on a real arrhythmia. Not a diagnosis.
     */
    public static Outcome screen(List<double[]> runs) {
        Outcome o = new Outcome();
        List<Double> cleaned = new ArrayList<>(), raw = new ArrayList<>();
        int rhythmOut = 0, netChange = 0;
        int[] counts = new int[2], removed = new int[1];
        for (double[] run : runs) {
            o.input += run.length;
            for (double v : run) raw.add(v);
            rhythmOut += neighbourOut(run);
            double[] r = repair(run, counts);
            netChange += Math.abs(r.length - run.length);
            double[] c = removePremature(r, removed);
            for (double v : c) cleaned.add(v);
        }
        o.merged = counts[0];
        o.split = counts[1];
        o.premature = removed[0];
        o.cleaned = cleaned.size();
        if (o.input == 0) { o.why = "no intervals"; return o; }
        o.correctedShare = (removed[0] + netChange) / (double) o.input;
        o.rhythmOutShare = rhythmOut / (double) o.input;
        int n = cleaned.size();
        if (n < MIN_WINDOW) { o.why = "fewer than " + MIN_WINDOW + " clean beats"; return o; }
        if (o.correctedShare > MAX_CORRECTED_SHARE) { o.why = "too many beats corrected"; return o; }

        boolean flagged;
        if (n >= WINDOW) {
            double[] d128 = dash(lastN(cleaned, WINDOW, 0));
            double[] a = dash(lastN(cleaned, MIN_WINDOW, 0));
            double[] b = dash(lastN(cleaned, MIN_WINDOW, MIN_WINDOW));
            o.halvesAgree = afLike(a) && afLike(b);
            flagged = afLike(d128) && o.halvesAgree;
            o.window = WINDOW;
            o.dash = d128;
        } else {
            double[] d64 = dash(lastN(cleaned, MIN_WINDOW, 0));
            flagged = afLike(d64);
            o.window = MIN_WINDOW;
            o.dash = d64;
        }
        int rn = raw.size();
        int rw = rn >= WINDOW ? WINDOW : (rn >= MIN_WINDOW ? MIN_WINDOW : 0);
        o.rawAfLike = rw > 0 && afLike(dash(lastN(raw, rw, 0)));
        if (flagged) o.state = AF_LIKE;
        else if (o.rhythmOutShare > 0.15 || o.rawAfLike) o.state = IRREGULAR;
        else o.state = NOT_AF_LIKE;
        return o;
    }
}
