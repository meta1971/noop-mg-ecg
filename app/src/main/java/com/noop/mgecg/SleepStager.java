package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep / light / REM / awake ESTIMATE for one night from heart rate, wrist movement and beat intervals.
 *
 * <p>This is a Java port of the sleep-staging recipe in NOOP's {@code SleepStagerV2.kt} (NOOP, Copyright 2026 NoopApp,
 * PolyForm Noncommercial License 1.0.0; used here for personal, noncommercial research). The constants, the per-night
 * z-scores, the HR-flatness deep gate, the cycle prior, the REM-latency guard and the sticky Viterbi path are carried over
 * unchanged, and {@code SleepStagerParityCheck} compares this port with the Kotlin original epoch by epoch on a real night.
 *
 * <p>What it is NOT: a measurement. Nothing in a wrist band sees brain activity, so every stage here is an inference. NOOP's own
 * benchmark of this recipe against lab sleep studies (PhysioNet sleep-accel, 31 people) gives a four-class kappa of about 0.36.
 * Last night's checks on this strap: the early deep sleep and one REM-like stretch were backed by an overnight chest-strap ECG,
 * a long REM block after 04:00 was not. The app therefore draws stages as an estimate and marks where a second method disagrees.
 */
final class SleepStager {
    private SleepStager() {}

    static final class Segment {
        long start, end;
        String stage;       // "wake", "light", "deep", "rem"
        Segment(long s, long e, String st) { start = s; end = e; stage = st; }
    }

    /** One second of input. NaN means no measurement. [rr] holds the beat intervals (ms) that landed in this second. */
    static final class Sec {
        long ts;
        double hr = Double.NaN;
        double gx = Double.NaN, gy = Double.NaN, gz = Double.NaN;
        double[] rr = new double[0];
    }

    private static final String[] STAGES = {"deep", "rem", "light", "awake"};           // tie order
    private static final double[] BASE_LOG_PRIOR = {Math.log(0.15), Math.log(0.22), Math.log(0.50), Math.log(0.10)};
    private static final double DEEP_GATE_THRESH = 0.25, DEEP_GATE_SLOPE = 5.0;
    private static final double JERK_MOVE_MULT = 38.0, JERK_GATE_MULT = 55.0, MOTION_GATE_BOOST = 2.0, RESP_WEIGHT = 0.6;
    private static final double REM_LAT_PENALTY = 3.0, REM_LAT_MINUTES = 60.0;
    private static final int ONSET_EPOCHS = 10;
    //                                          to:  deep   rem   light  awake
    private static final double[][] TRANSITION = {{0.86, 0.007, 0.126, 0.007},      // from deep
                                                  {0.005, 0.88, 0.10, 0.015},       // from rem
                                                  {0.06, 0.06, 0.85, 0.03},         // from light
                                                  {0.0, 0.0, 0.10, 0.90}};          // from awake

    private static final class Epoch {
        long start;
        double hr = Double.NaN, hrVar = Double.NaN, hrFlat11 = Double.NaN, respReg = Double.NaN;
        double moveFrac, jerkMax, clock, jerkScale, minutes;
    }

    /** Stage [start, end) (unix seconds). Returns segments tiling the window; "wake" is the awake label. */
    static List<Segment> stage(long start, long end, List<Sec> secs) {
        List<Epoch> feats = features(start, end, secs);
        List<Segment> out = new ArrayList<>();
        if (feats.isEmpty()) {
            out.add(new Segment(start, end, "light"));
            return out;
        }
        int[] labels = stageEpochs(feats);
        for (int i = 0; i < feats.size(); i++) {
            String st = STAGES[labels[i]].equals("awake") ? "wake" : STAGES[labels[i]];
            long s0 = i == 0 ? start : feats.get(i).start;
            long s1 = i == feats.size() - 1 ? end : feats.get(i + 1).start;
            if (!out.isEmpty() && out.get(out.size() - 1).stage.equals(st)) out.get(out.size() - 1).end = s1;
            else out.add(new Segment(s0, s1, st));
        }
        return out;
    }

    // ------------------------------------------------------------------ features
    private static List<Epoch> features(long start, long end, List<Sec> secs) {
        List<Epoch> none = new ArrayList<>();
        if (end <= start) return none;
        double span = Math.max(1L, end - start);
        Map<Long, Double> secHR = new HashMap<>();
        Map<Long, double[]> secG = new HashMap<>();
        Map<Long, double[]> rrBy = new HashMap<>();
        for (Sec s : secs) {
            if (!Double.isNaN(s.hr)) secHR.put(s.ts, s.hr);
            if (!Double.isNaN(s.gx)) secG.put(s.ts, new double[] {s.gx, s.gy, s.gz});
            if (s.rr.length > 0) rrBy.put(s.ts, s.rr);
        }
        // prefix sums over the integer-second HR axis
        long axisLo = 0;
        double[] sumP = new double[1], sqP = new double[1];
        int[] cntP = new int[1];
        if (!secHR.isEmpty()) {
            long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
            for (long k : secHR.keySet()) { if (k < lo) lo = k; if (k > hi) hi = k; }
            int len = (int) (hi - lo + 1);
            sumP = new double[len + 1]; sqP = new double[len + 1]; cntP = new int[len + 1];
            for (int i = 0; i < len; i++) {
                Double v = secHR.get(lo + i);
                sumP[i + 1] = sumP[i] + (v != null ? v : 0.0);
                sqP[i + 1] = sqP[i] + (v != null ? v * v : 0.0);
                cntP[i + 1] = cntP[i] + (v != null ? 1 : 0);
            }
            axisLo = lo;
        }
        final long axLo = axisLo;
        final long axHiEx = axisLo + (cntP.length - 1);

        List<double[]> jerksByEpoch = new ArrayList<>();
        List<int[]> gaps = new ArrayList<>();
        List<Epoch> eps = new ArrayList<>();
        List<Double> allJerks = new ArrayList<>();
        Map<Integer, double[][]> dft = new HashMap<>();
        long firstE = ((start + 29) / 30) * 30;
        for (long e = firstE; e < end; e += 30) {
            List<Double> hrs = new ArrayList<>();
            List<double[]> gseq = new ArrayList<>();
            for (long s = e; s < e + 30; s++) {
                Double h = secHR.get(s);
                if (h != null) hrs.add(h);
                double[] g = secG.get(s);
                if (g != null) gseq.add(g);
            }
            if (hrs.isEmpty() && gseq.isEmpty()) continue;
            int nj = Math.max(0, gseq.size() - 1);
            double[] jerks = new double[nj];
            double jerkMax = 0.0;
            for (int i = 1; i < gseq.size(); i++) {
                double[] a = gseq.get(i - 1), b = gseq.get(i);
                double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
                jerks[i - 1] = Math.sqrt(dx * dx + dy * dy + dz * dz);
                allJerks.add(jerks[i - 1]);
                if (jerks[i - 1] > jerkMax) jerkMax = jerks[i - 1];
            }
            Epoch ep = new Epoch();
            ep.start = e;
            if (!hrs.isEmpty()) { double sum = 0; for (double h : hrs) sum += h; ep.hr = sum / hrs.size(); }
            ep.hrVar = stdOfSeconds(sumP, sqP, cntP, axLo, axHiEx, e - 150, e + 30 + 150);
            ep.hrFlat11 = stdOfSeconds(sumP, sqP, cntP, axLo, axHiEx, e - 330, e + 30 + 360);
            // RSA respiration regularity over [e-90, e+120)
            List<double[]> beats = new ArrayList<>();
            for (long bs = e - 90; bs < e + 120; bs++) {
                double[] vs = rrBy.get(bs);
                if (vs != null) for (double v : vs) beats.add(new double[] {bs, Math.min(2000.0, Math.max(300.0, v))});
            }
            java.util.Collections.sort(beats, new java.util.Comparator<double[]>() {
                @Override public int compare(double[] a, double[] b) { int c = Double.compare(a[0], b[0]); return c != 0 ? c : Double.compare(a[1], b[1]); }
            });
            ep.respReg = respRegularity(beats, dft);
            ep.jerkMax = jerkMax;
            ep.clock = (e + 15 - start) / span;
            ep.minutes = (e + 15 - start) / 60.0;
            eps.add(ep);
            jerksByEpoch.add(jerks);
            gaps.add(new int[] {Math.max(1, gseq.size() - 1)});
        }
        double jerkScale;
        if (allJerks.isEmpty()) jerkScale = 1e-6;
        else {
            double[] sj = new double[allJerks.size()];
            for (int i = 0; i < sj.length; i++) sj[i] = allJerks.get(i);
            Arrays.sort(sj);
            int n = sj.length;
            jerkScale = n % 2 == 1 ? sj[n / 2] : 0.5 * (sj[n / 2 - 1] + sj[n / 2]);
        }
        double moveThr = jerkScale * JERK_MOVE_MULT;
        for (int i = 0; i < eps.size(); i++) {
            int moves = 0;
            for (double j : jerksByEpoch.get(i)) if (j > moveThr) moves++;
            eps.get(i).moveFrac = moves / (double) gaps.get(i)[0];
            eps.get(i).jerkScale = jerkScale;
        }
        return eps;
    }

    private static double stdOfSeconds(double[] sumP, double[] sqP, int[] cntP, long axLo, long axHiEx, long lo, long hi) {
        if (cntP.length <= 1) return Double.NaN;
        long qLo = Math.min(Math.max(lo, axLo), axHiEx), qHi = Math.min(Math.max(hi, axLo), axHiEx);
        if (qHi <= qLo) return Double.NaN;
        int a = (int) (qLo - axLo), b = (int) (qHi - axLo);
        int n = cntP[b] - cntP[a];
        if (n < 2) return Double.NaN;
        double sum = sumP[b] - sumP[a], sumSq = sqP[b] - sqP[a];
        double mean = sum / n, var = sumSq / n - mean * mean;
        return Math.sqrt(var < 0.0 ? 0.0 : var);
    }

    /** Spectral peakedness of the 0.15-0.40 Hz band of the 4 Hz resampled tachogram; NaN when there are too few beats. */
    static double respRegularity(List<double[]> beats, Map<Integer, double[][]> dft) {
        if (beats.size() < 12) return Double.NaN;
        double t0 = beats.get(0)[0], tN = beats.get(beats.size() - 1)[0];
        if (tN <= t0) return Double.NaN;
        int n = (int) Math.ceil((tN - t0) / 0.25 - 1e-9);
        if (n < 16) return Double.NaN;
        double[] y = new double[n];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            double t = t0 + 0.25 * i;
            while (seg < beats.size() - 2 && beats.get(seg + 1)[0] < t) seg++;
            double ta = beats.get(seg)[0], tb = beats.get(seg + 1)[0];
            double va = beats.get(seg)[1], vb = beats.get(seg + 1)[1];
            y[i] = tb <= ta ? va : va + Math.min(1.0, Math.max(0.0, (t - ta) / (tb - ta))) * (vb - va);
        }
        double mean = 0;
        for (double v : y) mean += v;
        mean /= n;
        for (int i = 0; i < n; i++) y[i] -= mean;
        int kLo = (int) Math.ceil(0.15 * 0.25 * n), kHi = (int) Math.floor(0.40 * 0.25 * n);
        if (kHi < kLo || kLo < 0) return Double.NaN;
        double[][] table = dft.get(n);
        if (table == null) {
            table = new double[2 * (kHi - kLo + 1)][n];
            for (int k = kLo; k <= kHi; k++) {
                double w = -2.0 * Math.PI * k / n;
                for (int j = 0; j < n; j++) { double a = w * j; table[2 * (k - kLo)][j] = Math.cos(a); table[2 * (k - kLo) + 1][j] = Math.sin(a); }
            }
            dft.put(n, table);
        }
        double maxP = 0.0, sumP = 0.0;
        for (int k = kLo; k <= kHi; k++) {
            double re = 0, im = 0;
            double[] c = table[2 * (k - kLo)], s = table[2 * (k - kLo) + 1];
            for (int j = 0; j < n; j++) { re += y[j] * c[j]; im += y[j] * s[j]; }
            double p = re * re + im * im;
            sumP += p;
            if (p > maxP) maxP = p;
        }
        if (sumP == 0.0) return Double.NaN;
        return maxP / sumP;
    }

    // ------------------------------------------------------------------ staging
    private static double remLatencyGuard(double minutesSinceOnset) {
        return REM_LAT_PENALTY * Math.min(1.0, Math.max(0.0, 1.0 - minutesSinceOnset / REM_LAT_MINUTES));
    }

    private static boolean motionQuiescent(Epoch f) {
        return f.moveFrac <= 0.0 && f.jerkMax <= f.jerkScale * JERK_GATE_MULT;
    }

    private static final class Z {
        final double m, sd; final boolean any;
        Z(double[] vals) {
            int n = 0; double s = 0;
            for (double v : vals) if (!Double.isNaN(v)) { n++; s += v; }
            any = n > 0;
            if (!any) { m = 0; sd = 1; return; }
            m = s / n;
            double q = 0;
            for (double v : vals) if (!Double.isNaN(v)) q += (v - m) * (v - m);
            double sd0 = Math.sqrt(q / n);
            sd = sd0 == 0.0 ? 1.0 : sd0;
        }
        double of(double v) { return !any || Double.isNaN(v) ? 0.0 : (v - m) / sd; }
    }

    private static int[] stageEpochs(List<Epoch> feats) {
        int n = feats.size();
        double[] hr = new double[n], hv = new double[n], mv = new double[n], rg = new double[n];
        List<Double> flat = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Epoch f = feats.get(i);
            hr[i] = f.hr; hv[i] = f.hrVar; mv[i] = f.moveFrac; rg[i] = f.respReg;
            if (!Double.isNaN(f.hrFlat11)) flat.add(f.hrFlat11);
        }
        Z zhr = new Z(hr), zhv = new Z(hv), zmv = new Z(mv), zrg = new Z(rg);
        double[] fs = new double[flat.size()];
        for (int i = 0; i < fs.length; i++) fs[i] = flat.get(i);
        Arrays.sort(fs);
        double[][] seq = new double[n][4];
        for (int i = 0; i < n; i++) {
            Epoch f = feats.get(i);
            double zhrv = zhr.of(f.hr), zhvv = zhv.of(f.hrVar), zmvv = zmv.of(f.moveFrac);
            double pct = 0.5;
            if (!Double.isNaN(f.hrFlat11) && fs.length > 0) {
                int lo = 0, hi = fs.length;
                while (lo < hi) { int mid = (lo + hi) / 2; if (fs[mid] <= f.hrFlat11) lo = mid + 1; else hi = mid; }
                pct = lo / (double) fs.length;
            }
            double gate = DEEP_GATE_SLOPE * Math.max(0.0, pct - DEEP_GATE_THRESH);
            double awakeCardiac0 = 0.8 * zhvv + 0.4 * zhrv;
            double awakeCardiac = motionQuiescent(f) ? Math.min(0.0, awakeCardiac0) : awakeCardiac0;
            double[] em = seq[i];
            em[0] = -1.1 * zhvv - 0.5 * zmvv - gate + BASE_LOG_PRIOR[0];
            em[1] = 0.6 * zhvv - 0.6 * zmvv + 0.4 * zhrv + BASE_LOG_PRIOR[1];
            em[2] = BASE_LOG_PRIOR[2];
            em[3] = 1.0 * zmvv + awakeCardiac + BASE_LOG_PRIOR[3];
            double c = f.clock;
            em[0] += 1.2 * Math.max(0.0, 1.0 - c / 0.55);          // cyclePrior, guard disabled in pass 1
            em[1] += 1.0 * c;
            if (f.jerkMax > f.jerkScale * JERK_GATE_MULT) em[3] += MOTION_GATE_BOOST;
            if (!Double.isNaN(f.respReg)) {
                double z = zrg.of(f.respReg);
                em[0] += RESP_WEIGHT * z;
                em[1] -= RESP_WEIGHT * z;
            }
        }
        int[] provisional = viterbi(seq);
        double originMin = 0.0;
        int run = 0;
        for (int i = 0; i < n; i++) {
            if (provisional[i] == 3) { run = 0; continue; }
            run++;
            if (run >= ONSET_EPOCHS) { originMin = feats.get(i - ONSET_EPOCHS + 1).minutes; break; }
        }
        for (int i = 0; i < n; i++) seq[i][1] -= remLatencyGuard(feats.get(i).minutes - originMin);
        return viterbi(seq);
    }

    private static int[] viterbi(double[][] em) {
        int n = em.length;
        if (n == 0) return new int[0];
        double[][] logT = new double[4][4];
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) logT[a][b] = Math.log(Math.max(TRANSITION[a][b], 1e-9));
        double[] v = Arrays.copyOf(em[0], 4);
        int[][] back = new int[n][4];
        for (int t = 1; t < n; t++) {
            double[] nv = new double[4];
            for (int s = 0; s < 4; s++) {
                int bestPrev = 0;
                double bestVal = v[0] + logT[0][s];
                for (int p = 1; p < 4; p++) {
                    double val = v[p] + logT[p][s];
                    if (val > bestVal) { bestVal = val; bestPrev = p; }
                }
                nv[s] = bestVal + em[t][s];
                back[t][s] = bestPrev;
            }
            v = nv;
        }
        int last = 0;
        double lastV = v[0];
        for (int s = 1; s < 4; s++) if (v[s] > lastV) { lastV = v[s]; last = s; }
        int[] path = new int[n];
        path[n - 1] = last;
        for (int t = n - 1; t >= 1; t--) { last = back[t][last]; path[t - 1] = last; }
        return path;
    }
}
