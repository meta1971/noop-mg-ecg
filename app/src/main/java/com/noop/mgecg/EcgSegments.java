package com.noop.mgecg;

// FILE VERSION 0.7.1 (20 Oct): every wave and segment of the averaged beat, with 95% ranges and accuracy gates; U wave (0.7.0); TP segment, ST vs TP, ST shape and area, Tp-Te, QTp and more QTc formulas (0.7.1)

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Segment measurement on the AVERAGED heartbeat of one recording. RESEARCH ONLY. Not a diagnosis.
 *
 * Measures: QRS onset and J point (slope thresholds), PR-segment baseline, Q/R/S heights, ST level at J+60 / J+80 ms and slope,
 * P wave (onset, peak, offset, PR interval, PR segment), T peak, T end (tangent plus 15 ms), QT, QTcF (Fridericia), QTcB (Bazett).
 * Each interval gets a 95% range from 200 bootstrap resamples of the beats and is flagged "ok" only when the range is within +-10 ms
 * (QT also needs the T wave at 8x the noise). P duration is not reported: its error was too large.
 *
 * Accuracy (PhysioNet QT Database, 104 annotated records, thresholds tuned on the odd records, scored on the even ones, 10 ms gate):
 * QRS duration median abs error 8 ms, PR 10 ms, QT 12 ms. About one accepted PR or QT value in four is off by more than 20 ms.
 * The test shows how the method behaves on annotated ECGs; it does not validate this strap's lead.
 */
public final class EcgSegments {

    private EcgSegments() {}

    public static final double DT = 2.0;                 // ms per sample at 500 Hz
    public static final double LOW = 0.04;               // "flat" = slope below 4% of the steepest QRS slope, for 3 samples
    public static final double PTHR = 0.12;              // P edges at 12% of the P height
    public static final double TEND_OFF_MS = 15.0;       // tangent T end plus this many ms (same as EcgIntervals)
    public static final double MAX_HALF_WIDTH_MS = 10.0;
    public static final double MIN_T_TO_NOISE = 8.0;
    public static final int BOOTSTRAPS = 200;

    public static final class Result {
        public boolean valid;
        public int beats;
        public double rrMs = Double.NaN, hrBpm = Double.NaN;
        public double rUv = Double.NaN, qUv = Double.NaN, sUv = Double.NaN;
        public double qrsOnMs = Double.NaN, qrsOffMs = Double.NaN, qrsMs = Double.NaN, qrsHwMs = Double.NaN;
        public boolean pPresent;
        public double pAmpUv = Double.NaN, pOnMs = Double.NaN, pPeakMs = Double.NaN, pOffMs = Double.NaN;
        public double prMs = Double.NaN, prHwMs = Double.NaN, prSegMs = Double.NaN;
        public double stJUv = Double.NaN, st60Uv = Double.NaN, st80Uv = Double.NaN, stSlopeUvPerMs = Double.NaN, st60SdUv = Double.NaN;
        public double tAmpUv = Double.NaN, tPeakMs = Double.NaN, tEndMs = Double.NaN, tOverR = Double.NaN;
        public double qtMs = Double.NaN, qtHwMs = Double.NaN, qtcFMs = Double.NaN, qtcFHwMs = Double.NaN, qtcBMs = Double.NaN;
        public double noiseUv = Double.NaN, tToNoise = Double.NaN;
        public boolean qrsOk, prOk, qtOk;
        /** U wave: a small same-polarity bump after the T wave. uPresent only when it passes the peak, size and split-half checks. */
        public boolean uPresent;
        public double uAmpUv = Double.NaN, uPeakMs = Double.NaN, uAfterTEndMs = Double.NaN, uPromUv = Double.NaN;
        // 0.7.1: TP segment (level against the PR baseline, flatness, window, duration when the P wave is clear), ST against the TP baseline, ST shape and area,
        // QRS shape numbers, Tp-Te / QTp, more QTc formulas. None of these except Tp-Te and the T peak has been validated against annotations.
        public double tpLevelUv = Double.NaN, tpFlatSdUv = Double.NaN, tpStartMs = Double.NaN, tpEndMs = Double.NaN, tpDurMs = Double.NaN;
        public double st60TpUv = Double.NaN, st80TpUv = Double.NaN, stAreaUvMs = Double.NaN;
        public String stShape = "";
        public double qrsAreaUvMs = Double.NaN, rPeakTimeMs = Double.NaN, rsRatio = Double.NaN;
        public double qtpMs = Double.NaN, tpeMs = Double.NaN, tpeOverQt = Double.NaN, qtOverRr = Double.NaN;
        public double qtcFraminghamMs = Double.NaN, qtcHodgesMs = Double.NaN, qtcRautaharjuMs = Double.NaN;
        public double[] avg;            // mean beat in uV
        public int rIdx;                // index of the R peak in avg
        public String note = "";
    }

    /** Fiducials of one beat (times in ms relative to the R peak, heights in uV relative to the PR-segment baseline). */
    static final class Fid {
        double r, q, s, base, qrsOn, qrsOff, qrsMs, stJ, st60, st80, stSlope;
        boolean hasP, pEdge;
        double pAmp = Double.NaN, pOn = Double.NaN, pPeak = Double.NaN, pOff = Double.NaN, pr = Double.NaN, prSeg = Double.NaN;
        boolean hasT;
        double tAmp = Double.NaN, tPeak = Double.NaN, tEnd = Double.NaN, qt = Double.NaN, tRel = Double.NaN;
    }

    // ---------------------------------------------------------------- helpers

    static double[] smooth(double[] b, int n) {              // centred moving average, zero padded like a 'same' convolution
        int half = n / 2;
        double[] o = new double[b.length];
        for (int i = 0; i < b.length; i++) {
            double s = 0;
            for (int k = -half; k <= half; k++) { int j = i + k; if (j >= 0 && j < b.length) s += b[j]; }
            o[i] = s / n;
        }
        return o;
    }

    static double[] gradient(double[] y, double dt) {
        int n = y.length;
        double[] g = new double[n];
        if (n < 2) return g;
        g[0] = (y[1] - y[0]) / dt;
        g[n - 1] = (y[n - 1] - y[n - 2]) / dt;
        for (int i = 1; i < n - 1; i++) g[i] = (y[i + 1] - y[i - 1]) / (2 * dt);
        return g;
    }

    /** first index, scanning from start by step, where mask holds for n consecutive samples (the first sample of that run); -1 if none. */
    static int consec(boolean[] mask, int start, int step, int n) {
        int k = start, run = 0, first = -1;
        while (k >= 0 && k < mask.length) {
            if (mask[k]) {
                run++;
                if (run == 1) first = k;
                if (run >= n) return first;
            } else run = 0;
            k += step;
        }
        return -1;
    }

    static double median(double[] a, int from, int to) {     // [from, to)
        double[] c = Arrays.copyOfRange(a, from, to);
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : 0.5 * (c[m - 1] + c[m]);
    }

    // ---------------------------------------------------------------- one beat

    /** b = mean beat (R positive), i0 = index of the R peak. Returns null if the QRS cannot be delineated. */
    static Fid fid(double[] b, int i0, double rrMs) {
        int n = b.length;
        double[] bs = smooth(b, 3);
        double[] d = gradient(bs, DT);
        double[] ad = new double[n];
        double smax = 0;
        for (int i = 0; i < n; i++) {
            ad[i] = Math.abs(d[i]);
            double t = (i - i0) * DT;
            if (t >= -80 && t <= 100 && ad[i] > smax) smax = ad[i];
        }
        if (!(smax > 0)) return null;
        boolean[] big = new boolean[n], lowm = new boolean[n];
        for (int i = 0; i < n; i++) { big[i] = ad[i] >= 0.25 * smax; lowm[i] = ad[i] < LOW * smax; }
        int firstSteep = -1, lastSteep = -1;
        for (int i = 0; i < n; i++) {
            double t = (i - i0) * DT;
            if (big[i] && t >= -100 && t <= 0 && firstSteep < 0) firstSteep = i;
            if (big[i] && t >= 0 && t <= 120) lastSteep = i;
        }
        if (firstSteep < 0 || lastSteep < 0) return null;
        int onset = consec(lowm, firstSteep - 1, -1, 3);
        if (onset < 0) return null;
        int J = consec(lowm, lastSteep + 1, 1, 3);
        if (J < 0) return null;
        Fid f = new Fid();
        int bsA = Math.max(onset - 20, 0), bsE = Math.max(onset - 5, 1);
        if (bsE <= bsA) return null;
        f.base = median(b, bsA, bsE);
        f.r = b[i0] - f.base;
        double qm = Double.MAX_VALUE, sm = Double.MAX_VALUE;
        for (int i = onset; i <= i0; i++) qm = Math.min(qm, b[i]);
        for (int i = i0; i <= J && i < n; i++) sm = Math.min(sm, b[i]);
        f.q = qm - f.base; f.s = sm - f.base;
        f.qrsOn = (onset - i0) * DT; f.qrsOff = (J - i0) * DT; f.qrsMs = f.qrsOff - f.qrsOn;
        f.stJ = at(b, f.base, i0, f.qrsOff);
        f.st60 = at(b, f.base, i0, f.qrsOff + 60);
        f.st80 = at(b, f.base, i0, f.qrsOff + 80);
        f.stSlope = (f.st80 - at(b, f.base, i0, f.qrsOff + 20)) / 60.0;
        // P wave
        int a = Math.max(onset - 160, 0), z = onset - 10;
        if (z - a > 20) {
            int len = z - a + 1;
            double lo = median(bs, a, a + 8), hi = median(bs, z + 1 - 8, z + 1);
            double[] p = new double[len];
            for (int k = 0; k < len; k++) p[k] = bs[a + k] - (lo + (hi - lo) * k / (len - 1.0));
            int pk = 0;
            for (int k = 0; k < len; k++) if (Math.abs(p[k]) > Math.abs(p[pk])) pk = k;
            double pa = Math.abs(p[pk]);
            double pol = p[pk] < 0 ? -1 : 1;
            double thr = PTHR * pa;
            int l = pk;
            while (l > 0 && Math.abs(p[l]) > thr) l--;
            int rr = pk;
            while (rr < len - 1 && Math.abs(p[rr]) > thr) rr++;
            f.hasP = true;
            f.pAmp = pol * pa;
            f.pOn = (a + l - i0) * DT; f.pPeak = (a + pk - i0) * DT; f.pOff = (a + rr - i0) * DT;
            f.pr = f.qrsOn - f.pOn; f.prSeg = f.qrsOn - f.pOff;
            f.pEdge = (l == 0 || rr == len - 1);
        }
        // T wave
        int lo = (int) Math.rint((f.qrsOff + 40) / DT) + i0;
        int hi = Math.min((int) Math.rint(Math.min(f.qrsOff + 420, 0.62 * rrMs) / DT) + i0, n - 5);
        if (hi - lo > 20) {
            double[] y = smooth(b, 5);
            for (int i = 0; i < n; i++) y[i] -= f.base;
            int ta = lo;
            for (int i = lo; i < hi; i++) if (Math.abs(y[i]) > Math.abs(y[ta])) ta = i;
            double tp = y[ta] < 0 ? -1 : 1;
            double[] yy = new double[n];
            for (int i = 0; i < n; i++) yy[i] = y[i] * tp;
            double[] dy = gradient(yy, DT);
            int zEnd = Math.min(ta + 150, n - 2), dd = ta;
            for (int i = ta; i < zEnd; i++) if (dy[i] < dy[dd]) dd = i;
            double tend = dy[dd] < 0 ? (dd - i0) * DT + yy[dd] / (-dy[dd]) + TEND_OFF_MS : Double.NaN;
            f.hasT = true;
            f.tAmp = y[ta]; f.tPeak = (ta - i0) * DT; f.tEnd = tend;
            f.qt = tend - f.qrsOn;
            f.tRel = f.r != 0 ? y[ta] / f.r : Double.NaN;
        }
        return f;
    }

    static double at(double[] b, double base, int i0, double ms) {
        int k = (int) Math.rint(ms / DT) + i0;
        return k >= 0 && k < b.length ? b[k] - base : Double.NaN;
    }

    // ---------------------------------------------------------------- one recording

    static double[] meanOf(List<double[]> ws, int[] pick) {
        int len = ws.get(0).length, n = pick == null ? ws.size() : pick.length;
        double[] m = new double[len];
        for (int g = 0; g < n; g++) {
            double[] w = ws.get(pick == null ? g : pick[g]);
            for (int j = 0; j < len; j++) m[j] += w[j];
        }
        for (int j = 0; j < len; j++) m[j] /= n;
        return m;
    }

    /** beats = the beats that passed the shape gate (R positive, R at index pre); rrS = median RR in seconds; noiseUv from the odd/even split. */
    public static Result measure(List<double[]> beats, double rrS, double noiseUv, int pre) {
        Result r = new Result();
        if (beats == null || beats.size() < 10 || !(rrS > 0.3)) { r.note = "too few beats"; return r; }
        r.beats = beats.size();
        r.rrMs = rrS * 1000.0; r.hrBpm = 60.0 / rrS; r.noiseUv = noiseUv;
        double[] avg = meanOf(beats, null);
        int ri = pre;
        for (int i = Math.max(pre - 12, 0); i <= Math.min(pre + 12, avg.length - 1); i++) if (avg[i] > avg[ri]) ri = i;
        r.avg = avg; r.rIdx = ri;
        Fid f = fid(avg, ri, r.rrMs);
        if (f == null) { r.note = "QRS not delineated"; return r; }
        r.valid = true;
        r.rUv = f.r; r.qUv = f.q; r.sUv = f.s;
        r.qrsOnMs = f.qrsOn; r.qrsOffMs = f.qrsOff; r.qrsMs = f.qrsMs;
        r.stJUv = f.stJ; r.st60Uv = f.st60; r.st80Uv = f.st80; r.stSlopeUvPerMs = f.stSlope;
        r.pPresent = f.hasP && !f.pEdge && Math.abs(f.pAmp) >= Math.max(0.03 * f.r, 4.0 * noiseUv);
        if (r.pPresent) { r.pAmpUv = f.pAmp; r.pOnMs = f.pOn; r.pPeakMs = f.pPeak; r.pOffMs = f.pOff; r.prMs = f.pr; r.prSegMs = f.prSeg; }
        if (f.hasT) {
            r.tAmpUv = f.tAmp; r.tPeakMs = f.tPeak; r.tEndMs = f.tEnd; r.tOverR = f.tRel; r.qtMs = f.qt;
            r.qtcFMs = f.qt / Math.cbrt(rrS); r.qtcBMs = f.qt / Math.sqrt(rrS);
            r.tToNoise = noiseUv > 0 ? Math.abs(f.tAmp) / noiseUv : Double.NaN;
        }
        try { uWave(r, beats, f, r.rrMs, noiseUv, ri); } catch (RuntimeException e) { r.uPresent = false; }
        try { extras(r, f, r.rrMs, noiseUv, ri); } catch (RuntimeException e) { /* extras stay NaN */ }
        // bootstrap
        Random rng = new Random(12345L);
        double[] qrs = new double[BOOTSTRAPS], pr = new double[BOOTSTRAPS], qt = new double[BOOTSTRAPS], qtc = new double[BOOTSTRAPS], st = new double[BOOTSTRAPS];
        int nq = 0, np = 0, nt = 0, ns = 0;
        int[] pick = new int[beats.size()];
        for (int b = 0; b < BOOTSTRAPS; b++) {
            for (int i = 0; i < pick.length; i++) pick[i] = rng.nextInt(beats.size());
            double[] ba = meanOf(beats, pick);
            Fid g = fid(ba, ri, r.rrMs);
            if (g == null) continue;
            qrs[nq++] = g.qrsMs;
            st[ns++] = g.st60;
            if (g.hasP && !g.pEdge) pr[np++] = g.pr;
            if (g.hasT && !Double.isNaN(g.qt)) { qt[nt] = g.qt; qtc[nt] = g.qt / Math.cbrt(rrS); nt++; }
        }
        r.qrsHwMs = halfWidth(qrs, nq);
        r.prHwMs = r.pPresent ? halfWidth(pr, np) : Double.NaN;
        r.qtHwMs = halfWidth(qt, nt);
        r.qtcFHwMs = halfWidth(qtc, nt);
        if (ns >= 20) r.st60SdUv = sd(Arrays.copyOf(st, ns));
        r.qrsOk = nq >= 0.9 * BOOTSTRAPS && r.qrsHwMs <= MAX_HALF_WIDTH_MS;
        r.prOk = r.pPresent && np >= 0.9 * BOOTSTRAPS && r.prHwMs <= MAX_HALF_WIDTH_MS;
        r.qtOk = f.hasT && !Double.isNaN(r.qtMs) && nt >= 0.9 * BOOTSTRAPS && r.qtHwMs <= MAX_HALF_WIDTH_MS && r.tToNoise >= MIN_T_TO_NOISE;
        return r;
    }

    // ---------------------------------------------------------------- 0.7.1 extras

    static String stShape(double slope) {                 // slope in uV per ms (= mV per s)
        if (slope > 1.0) return "upsloping";
        if (slope >= 0.2) return "slowly upsloping";
        if (slope >= -0.2) return "flat";
        return "downsloping";
    }

    static void extras(Result r, Fid f, double rrMs, double noise, int i0) {
        double[] y = smooth(r.avg, 5);
        for (int i = 0; i < y.length; i++) y[i] -= f.base;
        // TP segment: the flattest 40 ms between the T wave tail (and the U wave) and the next P wave
        if (f.hasT && !Double.isNaN(f.tEnd)) {
            double start = f.tEnd + 60.0;
            if (r.uPresent && !Double.isNaN(r.uPeakMs)) start = Math.max(start, r.uPeakMs + 50.0);
            boolean pOk = f.hasP && !f.pEdge;
            double end = Math.min(pOk ? rrMs + f.pOn - 25.0 : rrMs - 260.0, 700.0);
            if (end - start >= 60) {
                int ia = (int) Math.rint(start / DT) + i0, ib = (int) Math.rint(end / DT) + i0;
                double bestSd = Double.NaN, bestMed = Double.NaN;
                for (int i = ia; i < ib - 20; i++) {
                    double m = 0;
                    for (int k = 0; k < 20; k++) m += y[i + k];
                    m /= 20;
                    double v = 0;
                    for (int k = 0; k < 20; k++) v += (y[i + k] - m) * (y[i + k] - m);
                    double sd = Math.sqrt(v / 20);
                    if (Double.isNaN(bestSd) || sd < bestSd) { bestSd = sd; bestMed = median(y, i, i + 20); }
                }
                if (!Double.isNaN(bestSd)) {
                    r.tpLevelUv = bestMed; r.tpFlatSdUv = bestSd; r.tpStartMs = start; r.tpEndMs = end;
                    r.tpDurMs = pOk ? rrMs + f.pOn - f.tEnd : Double.NaN;
                    r.st60TpUv = f.st60 - bestMed; r.st80TpUv = f.st80 - bestMed;
                }
            }
        }
        r.stShape = stShape(f.stSlope);
        int jk = (int) Math.rint(f.qrsOff / DT) + i0, ok = (int) Math.rint(f.qrsOn / DT) + i0;
        double sa = 0;
        for (int k = jk; k < jk + 41 && k < r.avg.length; k++) sa += r.avg[k] - f.base;
        r.stAreaUvMs = sa * DT;
        double qa = 0;
        for (int k = ok; k <= jk && k < r.avg.length; k++) qa += r.avg[k] - f.base;
        r.qrsAreaUvMs = qa * DT;
        r.rPeakTimeMs = -f.qrsOn;
        r.rsRatio = Math.abs(f.s) >= 10.0 ? f.r / Math.abs(f.s) : Double.NaN;
        if (f.hasT && !Double.isNaN(f.tEnd)) {
            double hr = 60000.0 / rrMs;
            r.qtpMs = f.tPeak - f.qrsOn;
            r.tpeMs = f.tEnd - f.tPeak;
            r.tpeOverQt = r.tpeMs / f.qt;
            r.qtOverRr = f.qt / rrMs;
            r.qtcFraminghamMs = f.qt + 154.0 * (1.0 - rrMs / 1000.0);
            r.qtcHodgesMs = f.qt + 1.75 * (hr - 60.0);
            r.qtcRautaharjuMs = f.qt * (120.0 + hr) / 180.0;
        }
    }

    // ---------------------------------------------------------------- U wave

    public static final double U_PROM_K = 3.0, U_HALF_K = 2.0, U_MAX_REL_T = 0.6, U_MIN_PROM_UV = 5.0;

    /** scipy.signal.find_peaks(prominence=) on a whole array: local maxima (flat tops take the middle) with prominence >= minProm. Returns {index, prominence} pairs. */
    static java.util.List<double[]> peaksWithProminence(double[] x, double minProm) {
        java.util.List<double[]> out = new ArrayList<>();
        int i = 1, iMax = x.length - 1;
        while (i < iMax) {
            if (x[i - 1] < x[i]) {
                int ahead = i + 1;
                while (ahead < iMax && x[ahead] == x[i]) ahead++;
                if (x[ahead] < x[i]) {
                    int peak = (i + (ahead - 1)) / 2;
                    double leftMin = x[peak], rightMin = x[peak];
                    for (int k = peak; k >= 0 && x[k] <= x[peak]; k--) if (x[k] < leftMin) leftMin = x[k];
                    for (int k = peak; k <= iMax && x[k] <= x[peak]; k++) if (x[k] < rightMin) rightMin = x[k];
                    double prom = x[peak] - Math.max(leftMin, rightMin);
                    if (prom >= minProm) out.add(new double[]{peak, prom});
                    i = ahead;
                }
            }
            i++;
        }
        return out;
    }

    /** {amp, ms from R, prominence} of the most prominent peak in the U window of one (half-)average, or null. */
    static double[] uOne(double[] beat, Fid f, double rrMs, double promMin, int i0) {
        if (!f.hasT || Double.isNaN(f.tEnd)) return null;
        double tp = f.tAmp < 0 ? -1 : 1;
        double aMs = f.tEnd + 15.0, bMs = Math.min(Math.min(f.tEnd + 280.0, rrMs - 300.0), 650.0);
        if (bMs - aMs < 80) return null;
        int ia = (int) Math.rint(aMs / DT) + i0, ib = (int) Math.rint(bMs / DT) + i0;
        if (ib >= beat.length - 2 || ia - 10 < 0) return null;
        double[] y = smooth(beat, 5);
        for (int i = 0; i < y.length; i++) y[i] = (y[i] - f.base) * tp;
        double[] sub = Arrays.copyOfRange(y, ia - 10, ib + 1);
        double[] best = null;
        for (double[] pk : peaksWithProminence(sub, promMin)) {
            int p = (int) pk[0] + ia - 10;
            if (p >= ia && p <= ib - 2 && y[p] > 0 && (best == null || pk[1] > best[2])) best = new double[]{y[p], (p - i0) * DT, pk[1]};
        }
        return best;
    }

    static void uWave(Result r, List<double[]> beats, Fid f, double rrMs, double noise, int i0) {
        double promMin = Math.max(U_PROM_K * noise, U_MIN_PROM_UV);
        double[] u = uOne(r.avg, f, rrMs, promMin, i0);
        if (u == null) return;
        r.uAmpUv = u[0]; r.uPeakMs = u[1]; r.uPromUv = u[2];
        r.uAfterTEndMs = u[1] - f.tEnd;
        if (u[0] > U_MAX_REL_T * Math.abs(f.tAmp)) return;
        int n = beats.size();
        int[] odd = new int[(n + 1) / 2], even = new int[n / 2];
        for (int i = 0; i < n; i++) { if (i % 2 == 0) odd[i / 2] = i; else even[i / 2] = i; }
        double[] u1 = uOne(meanOf(beats, odd), f, rrMs, U_HALF_K * noise, i0);
        double[] u2 = uOne(meanOf(beats, even), f, rrMs, U_HALF_K * noise, i0);
        r.uPresent = u1 != null && u2 != null && Math.abs(u1[1] - u[1]) <= 40 && Math.abs(u2[1] - u[1]) <= 40;
    }

    static double halfWidth(double[] v, int n) {
        if (n < 20) return Double.NaN;
        double[] q = Arrays.copyOf(v, n);
        Arrays.sort(q);
        double lo = q[(int) Math.floor(0.025 * (n - 1))], hi = q[(int) Math.ceil(0.975 * (n - 1))];
        return (hi - lo) / 2.0;
    }

    static double sd(double[] v) {
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.length - 1));
    }
}
