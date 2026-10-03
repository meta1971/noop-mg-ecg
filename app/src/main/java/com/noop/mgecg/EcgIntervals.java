package com.noop.mgecg;

// FILE VERSION 0.2.1 (3 Oct): slope-based QRS onset, shape gate 0.60

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * EXPERIMENTAL single-lead QT measurement from the AVERAGED heartbeat of a stored 500 Hz recording.
 * RESEARCH ONLY. Not a diagnosis, not validated against a 12-lead ECG.
 *
 * Pipeline (pure Java, no Android):
 *  1. each quality-3 run is cleaned with a zero-phase 0.67-40 Hz band-pass plus a 50 Hz notch
 *     (a zero-phase digital high-pass up to 0.67 Hz is within the AHA/AAMI allowance for ST/QT work)
 *  2. a window of 400 ms before to 750 ms after every R peak is cut out (beats that failed the
 *     shape check, and beats near the run edges where the filter has not settled, are skipped)
 *  3. windows that do not match the median beat (correlation < 0.90) are dropped
 *  4. the rest are averaged; noise of the average comes from an odd/even split
 *  5. QRS onset = tangent on the steepest slope into the QRS meeting the PR baseline
 *     T end      = tangent on the steepest descent of the T wave meeting the baseline
 *     QT = T end - QRS onset;  QTcF = QT / RR^(1/3) (Fridericia; Bazett over-corrects at high rates)
 *  6. 200 bootstrap resamples of the beats give a 95% interval
 *
 * A number is only shown when: >= 40 beats, T wave >= 8x the averaged-beat noise, interval half-width
 * <= 30 ms, heart rate 40-110 bpm. Otherwise the reason is shown instead.
 *
 * The ST level (J+60 style, relative to the PR baseline) is computed for the log only. It is NOT
 * shown or interpreted: the wrist-to-finger vector is not a standard lead, so standard ST criteria do
 * not apply, and low-frequency noise (0.5-5 Hz) is large next to a 0.1 mV deviation.
 */
public final class EcgIntervals {

    private EcgIntervals() {}

    public static final int FS = 500;
    public static final int PRE = 200;     // 400 ms before R
    public static final int POST = 375;    // 750 ms after R
    public static final int EDGE_START = 750;   // skip beats in the first 1.5 s of a run (filter settling)
    public static final int EDGE_END = 500;     // and the last 1.0 s
    public static final int MIN_BEATS = 40;
    public static final double MIN_T_TO_NOISE = 8.0;
    public static final double MAX_HALF_WIDTH_MS = 30.0;
    /** Beats must correlate this well with the median beat. 0.90 threw away about 60% of beats that were only noisy, not odd
     *  (two real sessions); 0.60 keeps them, narrows the QT range, and still rejects genuinely different beats. */
    public static final double SHAPE_GATE = 0.60;
    public static final int BOOTSTRAPS = 200;

    public enum Status { OK, TOO_FEW_BEATS, NO_CLEAR_T_WAVE, UNCERTAIN, RATE_OUT_OF_RANGE, FAILED }

    public static final class Result {
        public Status status = Status.FAILED;
        public String reason = "";
        public int beatsUsed, beatsOffered;
        public double rrS = Double.NaN;
        public double qtMs = Double.NaN, ciLoMs = Double.NaN, ciHiMs = Double.NaN, qtcFMs = Double.NaN;
        public double qrsOnsetMs = Double.NaN, tApexMs = Double.NaN, tEndMs = Double.NaN;
        public double tAmpUv = Double.NaN, rAmpUv = Double.NaN, noiseUv = Double.NaN;
        /** QRS shape of the averaged beat (research; QRS duration is logged only, never shown). */
        public double qrsOffMs = Double.NaN, qrsDurMs = Double.NaN, rHalfWidthMs = Double.NaN, qsSpanMs = Double.NaN,
                qDepthUv = Double.NaN, sDepthUv = Double.NaN;
        public double stUv = Double.NaN, stSdUv = Double.NaN;
        public int validBootstraps;
        /** Averaged beat in uV, PRE samples before R (for drawing). Null if not computed. */
        public double[] avg;

        public double halfWidthMs() { return (ciHiMs - ciLoMs) / 2.0; }

        @Override public String toString() {
            return String.format(Locale.US,
                    "%s%s beats=%d/%d rr=%.3f qt=%.0f ci=%.0f-%.0f qtcF=%.0f qrsOnset=%.0f tApex=%.0f tEnd=%.0f " +
                            "tAmpUv=%.0f rAmpUv=%.0f noiseUv=%.1f stUv=%.0f stSdUv=%.1f boot=%d " +
                            "qrsDur=%.0f rHalfW=%.1f qsSpan=%.0f qDepth=%.0f sDepth=%.0f",
                    status, reason.isEmpty() ? "" : "(" + reason + ")", beatsUsed, beatsOffered, rrS, qtMs,
                    ciLoMs, ciHiMs, qtcFMs, qrsOnsetMs, tApexMs, tEndMs, tAmpUv, rAmpUv, noiseUv, stUv, stSdUv,
                    validBootstraps, qrsDurMs, rHalfWidthMs, qsSpanMs, qDepthUv, sDepthUv);
        }
    }

    // ---------------------------------------------------------------- cleaning and windows

    /** Zero-phase 0.67-40 Hz Butterworth band-pass (2nd order each side) plus 50 Hz notch. Input and output in uV. */
    public static double[] clean(double[] uv) {
        double[] y = uv;
        double[][] hp = EcgR16Analyzer.coeffs(false, 0.67);
        double[][] lp = EcgR16Analyzer.coeffs(true, 40.0);
        y = zeroPhase(y, hp[0], hp[1]);
        y = zeroPhase(y, lp[0], lp[1]);
        double[][] nt = notch(50.0, 30.0);
        return zeroPhase(y, nt[0], nt[1]);
    }

    static double[][] notch(double f0, double q) {
        double w = 2 * Math.PI * f0 / FS, c = Math.cos(w), al = Math.sin(w) / (2 * q);
        double a0 = 1 + al;
        return new double[][]{{1 / a0, -2 * c / a0, 1 / a0}, {1, -2 * c / a0, (1 - al) / a0}};
    }

    static double[] zeroPhase(double[] x, double[] b, double[] a) {
        double[] y = EcgR16Analyzer.biquad(x, b, a);
        return EcgR16Analyzer.reverse(EcgR16Analyzer.biquad(EcgR16Analyzer.reverse(y), b, a));
    }

    /**
     * Cuts beat windows out of one run. uv = the run in microvolts (counts x uV/count), pk = R-peak
     * sample indices in that run, bad = peaks that failed the shape check, inv = run polarity is inverted.
     */
    public static List<double[]> windows(double[] uv, List<Integer> pk, Set<Integer> bad, boolean inv) {
        List<double[]> out = new ArrayList<>();
        if (uv.length < EDGE_START + EDGE_END + PRE + POST) return out;
        double[] z = clean(uv);
        double sign = inv ? -1.0 : 1.0;
        for (int p : pk) {
            if (bad != null && bad.contains(p)) continue;
            if (p < EDGE_START || p > z.length - EDGE_END || p - PRE < 0 || p + POST > z.length) continue;
            double[] w = new double[PRE + POST];
            for (int i = 0; i < w.length; i++) w[i] = sign * z[p - PRE + i];
            out.add(w);
        }
        return out;
    }

    // ---------------------------------------------------------------- analysis

    public static Result analyse(List<double[]> wins, double rrMedianS) {
        Result r = new Result();
        r.beatsOffered = wins == null ? 0 : wins.size();
        r.rrS = rrMedianS;
        if (r.beatsOffered < 10) {
            r.status = Status.TOO_FEW_BEATS;
            r.reason = r.beatsOffered + " usable beats, need " + MIN_BEATS;
            return r;
        }
        int len = PRE + POST;
        // median beat as the shape reference
        double[] tmpl = new double[len];
        double[] col = new double[wins.size()];
        for (int j = 0; j < len; j++) {
            for (int g = 0; g < col.length; g++) col[g] = wins.get(g)[j];
            double[] s = col.clone();
            Arrays.sort(s);
            int m = s.length / 2;
            tmpl[j] = s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2.0;
        }
        List<double[]> kept = new ArrayList<>();
        for (double[] w : wins) {
            if (corr(w, tmpl, PRE - 50, PRE + 225) >= SHAPE_GATE) kept.add(w);
        }
        r.beatsUsed = kept.size();
        if (kept.size() < 10) {
            r.status = Status.TOO_FEW_BEATS;
            r.reason = kept.size() + " beats matched the typical shape, need " + MIN_BEATS;
            return r;
        }
        double[] avg = mean(kept, null);
        r.avg = avg;

        // noise of the averaged beat from an odd/even split: sd(a1 - a2) = 2 x sd of the full average
        double[] a1 = new double[len], a2 = new double[len];
        int n1 = 0, n2 = 0;
        for (int g = 0; g < kept.size(); g++) {
            double[] w = kept.get(g);
            if (g % 2 == 0) { for (int j = 0; j < len; j++) a1[j] += w[j]; n1++; }
            else { for (int j = 0; j < len; j++) a2[j] += w[j]; n2++; }
        }
        double[] diff = new double[len];
        for (int j = 0; j < len; j++) diff[j] = a1[j] / n1 - a2[j] / n2;
        r.noiseUv = sd(diff) / 2.0;

        double[] m = measure(avg, rrMedianS);
        if (m == null) {
            r.status = Status.FAILED;
            r.reason = "could not find the T wave";
            return r;
        }
        fill(r, m);
        r.rAmpUv = m[8];
        r.tAmpUv = Math.abs(m[7]);
        r.stUv = m[9];
        r.qtcFMs = r.qtMs / Math.cbrt(rrMedianS);

        // bootstrap
        Random rng = new Random(12345L);
        double[] qts = new double[BOOTSTRAPS];
        double[] sts = new double[BOOTSTRAPS];
        int nv = 0, ns = 0;
        int[] pick = new int[kept.size()];
        for (int b = 0; b < BOOTSTRAPS; b++) {
            for (int i = 0; i < pick.length; i++) pick[i] = rng.nextInt(kept.size());
            double[] ba = mean(kept, pick);
            double[] mb = measure(ba, rrMedianS);
            if (mb != null) {
                if (mb[3] > 150 && mb[3] < 600) qts[nv++] = mb[3];
                sts[ns++] = mb[9];
            }
        }
        r.validBootstraps = nv;
        if (nv >= 20) {
            double[] q = Arrays.copyOf(qts, nv);
            Arrays.sort(q);
            r.ciLoMs = q[(int) Math.floor(0.025 * (nv - 1))];
            r.ciHiMs = q[(int) Math.ceil(0.975 * (nv - 1))];
        }
        if (ns >= 20) r.stSdUv = sd(Arrays.copyOf(sts, ns));

        // gates, in the order a user should hear about them
        double hr = 60.0 / rrMedianS;
        if (hr < 40 || hr > 110) {
            r.status = Status.RATE_OUT_OF_RANGE;
            r.reason = String.format(Locale.US, "heart rate %.0f bpm is outside the 40-110 range this was checked on", hr);
        } else if (r.beatsUsed < MIN_BEATS) {
            r.status = Status.TOO_FEW_BEATS;
            r.reason = r.beatsUsed + " clean beats, need " + MIN_BEATS;
        } else if (r.tAmpUv < MIN_T_TO_NOISE * r.noiseUv || nv < 0.9 * BOOTSTRAPS) {
            r.status = Status.NO_CLEAR_T_WAVE;
            r.reason = "T wave not clear enough above the noise";
        } else if (Double.isNaN(r.ciLoMs) || r.halfWidthMs() > MAX_HALF_WIDTH_MS) {
            r.status = Status.UNCERTAIN;
            r.reason = String.format(Locale.US, "uncertainty too wide (+-%.0f ms)", r.halfWidthMs());
        } else {
            r.status = Status.OK;
        }
        return r;
    }

    private static void fill(Result r, double[] m) {
        r.qrsOnsetMs = m[0];
        r.tApexMs = m[1];
        r.tEndMs = m[2];
        r.qtMs = m[3];
        r.qrsOffMs = m[4];
        r.qrsDurMs = m[4] - m[0];
        r.qDepthUv = m[5];
        r.sDepthUv = m[6];
        r.rHalfWidthMs = m[10];
        r.qsSpanMs = m[11];
    }

    /**
     * Measures one averaged beat. Returns {qrsOnsetMs, tApexMs, tEndMs, qtMs, qrsOffMs, qDepthUv, sDepthUv,
     * tAmpUv(signed), rAmpUv, stUv, rHalfWidthMs, qsSpanMs} with times relative to the R peak, or null if no T wave is found.
     */
    static double[] measure(double[] w, double rrS) {
        int r = PRE;
        double[] d1 = new double[w.length];
        for (int i = 1; i < w.length - 1; i++) d1[i] = (w[i + 1] - w[i - 1]) / 2.0 * FS;
        d1[0] = d1[1];
        d1[w.length - 1] = d1[w.length - 2];

        // PR baseline (after P, before Q) and the isoelectric baseline from the ends of the window
        double pr = median(w, r - (int) (0.11 * FS), r - (int) (0.075 * FS));
        double base = medianTwo(w, 0, (int) (0.08 * FS), w.length - (int) (0.10 * FS), w.length);

        // QRS bounds from the slope envelope: walk out from the R peak until the smoothed |slope| stays under
        // max(12% of the steepest slope, 3x the baseline slope noise) for 12 ms. Unlike picking the Q dip, this does not
        // jump between two similar small dips when noise changes (it moved QT by 33 ms between gate settings).
        double[] env = new double[w.length];
        for (int i = 2; i < w.length - 2; i++) env[i] = (Math.abs(d1[i - 2]) + Math.abs(d1[i - 1]) + Math.abs(d1[i])
                + Math.abs(d1[i + 1]) + Math.abs(d1[i + 2])) / 5.0;
        double peakSlope = 0;
        for (int i = r - 30; i <= r + 30; i++) peakSlope = Math.max(peakSlope, env[i]);
        double[] ns = Arrays.copyOfRange(env, 5, 60);
        Arrays.sort(ns);
        double thr = Math.max(0.12 * peakSlope, 3.0 * ns[ns.length / 2]);
        int on = walkOut(env, r, -1, thr), off = walkOut(env, r, +1, thr);
        double q = on;

        // shape numbers of the R, Q and S waves
        int ir = r;
        for (int i = r - 6; i <= r + 6; i++) if (w[i] > w[ir]) ir = i;
        int qi = r - 40, si = r + 3;
        for (int i = r - 40; i <= r - 3; i++) if (w[i] < w[qi]) qi = i;
        for (int i = r + 3; i <= r + 40; i++) if (w[i] < w[si]) si = i;
        double rAmpPr = w[ir] - pr, halfLevel = pr + rAmpPr / 2.0;
        double left = ir, right = ir;
        for (int i = ir; i > ir - 40; i--) if (w[i - 1] <= halfLevel) { left = i - 1 + (halfLevel - w[i - 1]) / (w[i] - w[i - 1]); break; }
        for (int i = ir; i < ir + 40; i++) if (w[i + 1] <= halfLevel) { right = i + (w[i] - halfLevel) / (w[i] - w[i + 1]); break; }

        // T wave apex: largest deviation from baseline between R+120 ms and R+min(450 ms, 0.55 RR)
        int lo = r + (int) (0.12 * FS);
        int hi = r + (int) (Math.min(0.45, 0.55 * rrS) * FS);
        if (hi <= lo + 10 || hi >= w.length - 5) return null;
        int ta = lo;
        for (int i = lo; i < hi; i++) if (Math.abs(w[i] - base) > Math.abs(w[ta] - base)) ta = i;
        double tpol = Math.signum(w[ta] - base);
        // steepest descent after the apex (towards the baseline)
        int end = Math.min(w.length - 2, ta + (int) (0.25 * FS));
        int ts = ta;
        for (int i = ta; i < end; i++) if (d1[i] * -tpol > d1[ts] * -tpol) ts = i;
        double slope = d1[ts];
        if (Math.abs(slope) < 1e-6) return null;
        double te = ts + (base - w[ts]) / slope * FS;

        double rAmp = w[r];
        for (int i = r - 6; i <= r + 6; i++) if (w[i] > rAmp) rAmp = w[i];
        rAmp -= pr;
        double st = meanRange(w, r + (int) (0.10 * FS), r + (int) (0.12 * FS)) - pr;
        double ms = 1000.0 / FS;
        return new double[]{(q - r) * ms, (ta - r) * ms, (te - r) * ms, (te - q) * ms, (off - r) * ms,
                Math.max(0, pr - w[qi]), Math.max(0, pr - w[si]), w[ta] - base, rAmp, st,
                (right - left) * ms, (si - qi) * ms};
    }

    // ---------------------------------------------------------------- small helpers

    /** Walks from the R peak in one direction until the slope envelope has stayed under thr for 6 samples (12 ms). */
    static int walkOut(double[] env, int r, int step, double thr) {
        int i = r, quiet = 0;
        while (i > 2 && i < env.length - 3 && Math.abs(i - r) < 60) {
            i += step;
            quiet = env[i] < thr ? quiet + 1 : 0;
            if (quiet >= 6) return i - step * 5;
        }
        return i;
    }

    static double[] mean(List<double[]> ws, int[] pick) {
        int len = ws.get(0).length;
        double[] a = new double[len];
        int n = pick == null ? ws.size() : pick.length;
        for (int g = 0; g < n; g++) {
            double[] w = ws.get(pick == null ? g : pick[g]);
            for (int j = 0; j < len; j++) a[j] += w[j];
        }
        for (int j = 0; j < len; j++) a[j] /= n;
        return a;
    }

    static double corr(double[] a, double[] b, int from, int to) {
        int n = to - from;
        double ma = 0, mb = 0;
        for (int i = from; i < to; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = from; i < to; i++) {
            double x = a[i] - ma, y = b[i] - mb;
            sxy += x * y; sxx += x * x; syy += y * y;
        }
        return sxy / Math.sqrt(sxx * syy + 1e-12);
    }

    static double sd(double[] v) {
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / v.length);
    }

    static double meanRange(double[] w, int a, int b) {
        double s = 0;
        for (int i = a; i < b; i++) s += w[i];
        return s / (b - a);
    }

    static double median(double[] w, int a, int b) {
        double[] c = Arrays.copyOfRange(w, a, b);
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : (c[m - 1] + c[m]) / 2.0;
    }

    static double medianTwo(double[] w, int a1, int b1, int a2, int b2) {
        double[] c = new double[(b1 - a1) + (b2 - a2)];
        int k = 0;
        for (int i = a1; i < b1; i++) c[k++] = w[i];
        for (int i = a2; i < b2; i++) c[k++] = w[i];
        Arrays.sort(c);
        int m = c.length / 2;
        return c.length % 2 == 1 ? c[m] : (c[m - 1] + c[m]) / 2.0;
    }
}
