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
 * The model in the fitted block (v2) was fitted on the PhysioNet MIT-BIH Atrial Fibrillation Database and
 * Long-Term AF Database (109 Holter patients, hand-labelled rhythm), on 30 s and 3 min windows of beat timing.
 * Patient-grouped cross-validation: 94% of AF windows flagged with 1.2-1.6% of sinus windows flagged at 30 s;
 * 98% and 0.4-0.8% at 3 min. Score above THRESHOLD_STRONG marks an AF-like pattern (86% and 0.25% at 30 s).
 * Other irregular rhythms (frequent atrial ectopy, short SVT) are also flagged. It has NOT been validated on MG data.
 * REGULAR does not mean normal: regular-timed arrhythmias and conduction problems pass.
 */
public final class EcgRhythm {

    private EcgRhythm() {}

    public enum Verdict { REGULAR, IRREGULAR, CANNOT_ANALYSE, NOT_CALIBRATED }

    // ===== BEGIN FITTED BLOCK (v2: fitted on PhysioNet MIT-BIH AF DB + Long-Term AF DB, 109 patients) =====
    public static final boolean MODEL_FITTED = true;
    public static final int MIN_INTERVALS = 20;
    public static final double MAX_SUSPECT_FRACTION = 0.1;
    public static final double[] FEATURE_MEAN  = {0.1915408343299813, 0.14006060239819995, 0.5511210479720331, 0.9281592052559742, 1.519685958484666, -1.2520688752829292, 0.11674891123293302, 0.4417221528332365, 0.035602689274737094};
    public static final double[] FEATURE_SCALE = {0.13760222829378335, 0.09163426259522256, 0.15063828979612762, 1.1699912447382315, 0.6721354365896501, 0.9934428740583704, 0.1049880093202434, 0.3494931370320718, 0.0465585266995468};
    public static final double[] WEIGHTS       = {0.7493262192329084, -0.1319365032449861, -0.2546909983773472, -1.511004987738392, 0.54232689237211, 3.0419240854366145, -0.6704199809256892, 3.2861205886217073, -1.2338045643167581};
    public static final double INTERCEPT = 0.1332214729687626;
    public static final double THRESHOLD = 0.5491340225003096;
    public static final double THRESHOLD_STRONG = 0.9390492145511384;
    // ===== END FITTED BLOCK =====

    /** An R-R interval outside this range breaks the run (lost beat, noise or contact loss). */
    public static final double MIN_RR_S = 0.30;
    public static final double MAX_RR_S = 2.00;

    public static final String[] FEATURE_NAMES = {"nRMSSD", "CV", "TPR", "SD1/SD2", "SampEn", "CoSEn", "MADD", "PD8", "Premature"};

    public static final class Result {
        public final Verdict verdict;
        public final String reason;          // empty unless CANNOT_ANALYSE / NOT_CALIBRATED
        public final int intervals;          // clean R-R intervals used
        public final double meanHrBpm;       // NaN if too few intervals
        public final double[] features;      // FEATURE_NAMES order; null if not computed
        public final double suspectFraction; // NaN if not computed
        public final double probability;     // model output 0..1; NaN if not calibrated
        public boolean strong;               // score above the strict AF-like cutoff
        public double[] atrial;              // v4: {P-wave amplitude / R, split-half reliability, bad-shape share}; null if unavailable
        public String model = "A";           // A = timing only, B = timing + P wave

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
            if (strong) sb.append(" AF_LIKE_PATTERN");
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
            sb.append(" model=").append(model);
            if (atrial != null && atrial.length >= 3) {
                sb.append(String.format(Locale.US, " pAmpRel=%.3f pSplit=%.2f badShare=%.3f", atrial[0], atrial[1], atrial[2]));
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
        return analyzeRuns(runs, null);
    }

    public static Result analyzeRuns(List<double[]> runs, double[] atrial) {
        double[] best = new double[0];
        if (runs != null) {
            for (double[] r : runs) {
                double[] rr = longestValidRun(r);
                if (rr.length > best.length) best = rr;
            }
        }
        return analyzeIntervals(best, atrial);
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
    public static Result analyzeIntervals(double[] rrIn) {
        return analyzeIntervals(rrIn, null);
    }

    public static Result analyzeIntervals(double[] rrIn, double[] atrial) {
        double[] rr = tidy(rrIn);
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
        double lo = THRESHOLD, hi = THRESHOLD_STRONG;
        String model = "A";
        if (EcgRhythmB.READY && atrial != null && atrial.length >= 3 && finite(atrial)) {
            double[] f12 = new double[f.length + 3];
            System.arraycopy(f, 0, f12, 0, f.length);
            f12[f.length] = atrial[0]; f12[f.length + 1] = Math.max(-1.0, Math.min(1.0, atrial[1])); f12[f.length + 2] = atrial[2];
            double pb = EcgRhythmB.predict(f12);
            if (!Double.isNaN(pb)) { p = pb; lo = EcgRhythmB.THRESHOLD; hi = EcgRhythmB.THRESHOLD_STRONG; model = "B"; }
        }
        Result res = new Result(p >= lo ? Verdict.IRREGULAR : Verdict.REGULAR,
                "", n, hr, f, susp, p);
        res.strong = p >= hi;
        res.atrial = atrial;
        res.model = model;
        return res;
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

        // v2 extras. CoSEn: sample entropy with a fixed 30 ms tolerance, offset for rate (Lake and Moorman).
        double cosen = sampEn(rr, 1, 0.03) + Math.log(0.06) - Math.log(mean);
        double med = median(rr);
        double[] ad = new double[nd];
        int big = 0;
        for (int i = 0; i < nd; i++) {
            ad[i] = Math.abs(d[i]);
            if (ad[i] > 0.08 * med) big++;
        }
        double madd = median(ad) / med;
        double pd8 = (double) big / nd;
        // premature beats: an interval under 80% of the median of the 4 before it, followed by a longer one
        int prem = 0;
        for (int i = 4; i < n - 1; i++) {
            double m4 = median(Arrays.copyOfRange(rr, i - 4, i));
            if (rr[i] < 0.8 * m4 && rr[i + 1] > m4) prem++;
        }
        double pf = (double) prem / n;
        return new double[] {nRmssd, cv, tpr, sd1sd2, se, cosen, madd, pd8, pf};
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
                if (dmax <= r + 1e-9) {
                    b++;
                    if (Math.abs(x[i + m] - x[j + m]) <= r + 1e-9) a++;
                }
            }
        }
        if (a < 1) a = 1;
        if (b < 1) b = 1;
        return -Math.log((double) a / (double) b) + 0.0; // + 0.0 turns -0.0 into 0.0
    }

    /** Intervals are rebuilt by adding up beat times, which leaves rounding noise of about 1e-16 s. Equal intervals
     *  would then count as a random turning point, so round to 0.1 ms (500 Hz data is exact at 2 ms). */
    static boolean finite(double[] v) {
        for (double x : v) if (Double.isNaN(x) || Double.isInfinite(x)) return false;
        return true;
    }

    public static double[] tidy(double[] rr) {
        if (rr == null) return null;
        double[] o = new double[rr.length];
        for (int i = 0; i < o.length; i++) o[i] = Math.round(rr[i] * 1e4) / 1e4;
        return o;
    }

    static double median(double[] v) {
        double[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int n = c.length;
        return (n % 2 == 1) ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }
}
