package com.noop.mgecg;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * RR-interval irregularity screen for ONE contiguous run of R-peak times.
 *
 * RESEARCH USE ONLY. This is not an AF detector and not a diagnosis. It says whether the
 * beat-to-beat timing in one short recording looked regular or irregular, or that it could
 * not tell. Pure Java, no Android dependencies.
 *
 * Feed it beat times (seconds, ascending) from quality-3 seconds only, from the OFFLINE beat
 * detector (not the old live one, which inflated RMSSD about 4x).
 *
 * The model in the fitted block was fitted on the PhysioNet/CinC 2017 training set (AliveCor
 * single lead, 300 Hz): AF versus Normal, 5-fold AUC 0.978, threshold set for 98% of Normal records
 * to stay REGULAR (83% of analysable AF flagged). It has NOT been validated on MG data.
 * If MODEL_FITTED is false the verdict is NOT_CALIBRATED; the features are still computed.
 * REGULAR does not mean normal: regular-timed arrhythmias and conduction problems pass.
 */
public final class EcgRhythm {

    private EcgRhythm() {}

    public enum Verdict { REGULAR, IRREGULAR, CANNOT_ANALYSE, NOT_CALIBRATED }

    // ===== BEGIN FITTED BLOCK (replace with the block printed by physionet_rr_eval.py) =====
    public static final boolean MODEL_FITTED = true;
    public static final int MIN_INTERVALS = 20;
    public static final double MAX_SUSPECT_FRACTION = 0.1;
    public static final double[] FEATURE_MEAN  = {0.09537569951914383, 0.08906628269590408, 0.5136881185163641, 0.6262344751983354, 1.1998413890386697};
    public static final double[] FEATURE_SCALE = {0.09959812721247684, 0.07281575572660863, 0.1361979844421205, 0.41732666551347725, 0.6568186000465759};
    public static final double[] WEIGHTS       = {3.6392424276512507, -1.4360287149349271, 0.942011410853356, -0.15470138841659264, 0.8324871855516435};
    public static final double INTERCEPT = -3.7793415846952887;
    public static final double THRESHOLD = 0.3657557282486893;
    // ===== END FITTED BLOCK =====

    /** An R-R interval outside this range breaks the run (lost beat, noise or contact loss). */
    public static final double MIN_RR_S = 0.30;
    public static final double MAX_RR_S = 2.00;

    public static final String[] FEATURE_NAMES = {"nRMSSD", "CV", "TPR", "SD1/SD2", "SampEn"};

    public static final class Result {
        public final Verdict verdict;
        public final String reason;          // empty unless CANNOT_ANALYSE / NOT_CALIBRATED
        public final int intervals;          // clean R-R intervals used
        public final double meanHrBpm;       // NaN if too few intervals
        public final double[] features;      // FEATURE_NAMES order; null if not computed
        public final double suspectFraction; // NaN if not computed
        public final double probability;     // model output 0..1; NaN if not calibrated

        Result(Verdict verdict, String reason, int intervals, double meanHrBpm,
               double[] features, double suspectFraction, double probability) {
            this.verdict = verdict;
            this.reason = reason;
            this.intervals = intervals;
            this.meanHrBpm = meanHrBpm;
            this.features = features;
            this.suspectFraction = suspectFraction;
            this.probability = probability;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(verdict.name());
            if (reason.length() > 0) sb.append(" (").append(reason).append(")");
            sb.append(String.format(Locale.US, " | n=%d", intervals));
            if (!Double.isNaN(meanHrBpm)) sb.append(String.format(Locale.US, " HR=%.1f", meanHrBpm));
            if (features != null) {
                for (int i = 0; i < features.length; i++) {
                    sb.append(String.format(Locale.US, " %s=%.3f", FEATURE_NAMES[i], features[i]));
                }
            }
            if (!Double.isNaN(suspectFraction)) {
                sb.append(String.format(Locale.US, " suspect=%.0f%%", suspectFraction * 100.0));
            }
            if (!Double.isNaN(probability)) {
                sb.append(String.format(Locale.US, " p=%.3f", probability));
            }
            return sb.toString();
        }
    }

    /** One contiguous run of R-peak times in seconds. Splits at any bad interval and uses the longest piece. */
    public static Result analyzeBeats(double[] beatTimesS) {
        return analyzeIntervals(longestValidRun(beatTimesS));
    }

    /** Several runs (for example separate quality-3 stretches). Uses the longest clean piece of any of them. */
    public static Result analyzeRuns(List<double[]> runs) {
        double[] best = new double[0];
        if (runs != null) {
            for (double[] r : runs) {
                double[] rr = longestValidRun(r);
                if (rr.length > best.length) best = rr;
            }
        }
        return analyzeIntervals(best);
    }

    /** Longest stretch of consecutive beats whose R-R intervals are all inside [MIN_RR_S, MAX_RR_S]. */
    public static double[] longestValidRun(double[] t) {
        if (t == null || t.length < 2) return new double[0];
        int bestStart = 0, bestLen = 0, curStart = 0, curLen = 0;
        for (int i = 1; i < t.length; i++) {
            double rr = t[i] - t[i - 1];
            if (rr >= MIN_RR_S && rr <= MAX_RR_S) {
                if (curLen == 0) curStart = i - 1;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else {
                curLen = 0;
            }
        }
        double[] rr = new double[bestLen];
        for (int k = 0; k < bestLen; k++) {
            rr[k] = t[bestStart + k + 1] - t[bestStart + k];
        }
        return rr;
    }

    /** Gate, features, verdict for a clean R-R series in seconds. */
    public static Result analyzeIntervals(double[] rr) {
        int n = rr == null ? 0 : rr.length;
        if (n < MIN_INTERVALS) {
            return new Result(Verdict.CANNOT_ANALYSE,
                    "only " + n + " clean intervals, need " + MIN_INTERVALS,
                    n, Double.NaN, null, Double.NaN, Double.NaN);
        }
        double mean = 0.0;
        for (double v : rr) mean += v;
        mean /= n;
        double hr = 60.0 / mean;

        double susp = suspectFraction(rr);
        if (susp > MAX_SUSPECT_FRACTION) {
            return new Result(Verdict.CANNOT_ANALYSE,
                    "possible missed or extra beats", n, hr, null, susp, Double.NaN);
        }

        double[] f = features(rr);
        if (!MODEL_FITTED) {
            return new Result(Verdict.NOT_CALIBRATED, "model not fitted yet", n, hr, f, susp, Double.NaN);
        }
        double s = INTERCEPT;
        for (int i = 0; i < f.length; i++) {
            s += WEIGHTS[i] * (f[i] - FEATURE_MEAN[i]) / FEATURE_SCALE[i];
        }
        double p = 1.0 / (1.0 + Math.exp(-s));
        return new Result(p >= THRESHOLD ? Verdict.IRREGULAR : Verdict.REGULAR,
                "", n, hr, f, susp, p);
    }

    /**
     * Share of intervals that look like a missed beat (about 2x the median) or an extra one
     * (about 0.5x the median). A high share means the beat detector, not the heart, is irregular.
     */
    public static double suspectFraction(double[] rr) {
        double med = median(rr);
        int flagged = 0;
        for (double v : rr) {
            double r = v / med;
            if (Math.abs(r - 2.0) <= 0.20 || Math.abs(r - 0.5) <= 0.10) flagged++;
        }
        return (double) flagged / rr.length;
    }

    /** Feature vector in FEATURE_NAMES order. Needs at least 4 intervals. */
    public static double[] features(double[] rr) {
        int n = rr.length;
        double mean = 0.0;
        for (double v : rr) mean += v;
        mean /= n;

        double ss = 0.0;
        for (double v : rr) ss += (v - mean) * (v - mean);
        double sd = Math.sqrt(ss / (n - 1));

        int nd = n - 1;
        double[] d = new double[nd];
        double sumD = 0.0, sumD2 = 0.0;
        for (int i = 0; i < nd; i++) {
            d[i] = rr[i + 1] - rr[i];
            sumD += d[i];
            sumD2 += d[i] * d[i];
        }
        double rmssd = Math.sqrt(sumD2 / nd);
        double nRmssd = rmssd / mean;
        double cv = sd / mean;

        int turning = 0;
        for (int i = 1; i < n - 1; i++) {
            if ((rr[i] - rr[i - 1]) * (rr[i + 1] - rr[i]) < 0.0) turning++;
        }
        double tpr = (double) turning / (n - 2);

        double meanD = sumD / nd;
        double ssd = 0.0;
        for (double v : d) ssd += (v - meanD) * (v - meanD);
        double sdd = Math.sqrt(ssd / (nd - 1));
        double sd1 = sdd / Math.sqrt(2.0);
        double sd2 = Math.sqrt(Math.max(0.0, 2.0 * sd * sd - 0.5 * sdd * sdd));
        double sd1sd2 = sd2 < 1e-9 ? 1.0 : sd1 / sd2;

        double se = sampEn(rr, 2, 0.2 * sd);
        return new double[] {nRmssd, cv, tpr, sd1sd2, se};
    }

    /**
     * Sample entropy (Richman and Moorman). Counts template pairs within r (Chebyshev, inclusive).
     * If either count is zero it is raised to 1 so the result stays finite.
     */
    static double sampEn(double[] x, int m, double r) {
        int n = x.length;
        int nt = n - m;
        long a = 0, b = 0;
        for (int i = 0; i < nt - 1; i++) {
            for (int j = i + 1; j < nt; j++) {
                double dmax = 0.0;
                for (int k = 0; k < m; k++) {
                    double dd = Math.abs(x[i + k] - x[j + k]);
                    if (dd > dmax) dmax = dd;
                }
                if (dmax <= r) {
                    b++;
                    if (Math.abs(x[i + m] - x[j + m]) <= r) a++;
                }
            }
        }
        if (a < 1) a = 1;
        if (b < 1) b = 1;
        return -Math.log((double) a / (double) b) + 0.0; // + 0.0 turns -0.0 into 0.0
    }

    static double median(double[] v) {
        double[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int n = c.length;
        return (n % 2 == 1) ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }
}
