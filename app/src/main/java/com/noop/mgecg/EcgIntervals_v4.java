package com.noop.mgecg;

// FILE VERSION 0.2.8 (3 Oct): slope-based QRS onset, shape gate 0.60, QT per heart-rate band, wide-band detail beat

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

    /** T-end method: 0 = tangent on the steepest descent (used; best across both high-pass cases on 95 annotated QT Database records),
     *  1 = cumulative-area point (overshot by 19-87 ms, unused), 2 = fraction of the T amplitude (good only if the strap has the high-pass). */
    public static volatile int TEND_METHOD = 0;
    /** Share of the area under the T wave (apex to apex + 300 ms) that must be reached for the area method. */
    public static volatile double AREA_FRAC = 0.95;
    /** Fraction of the T amplitude the smoothed down-slope must fall to for the threshold method. */
    public static volatile double THR_FRAC = 0.10;
    /** The strap's stored ECG looks like it passed a first-order high-pass near 1.5 Hz (T wave about halved, deeper dip after
     *  the QRS). PROVISIONAL: one person, one reference device, cause open (issue #891). 0 = leave the beat as recorded. */
    public static volatile double STRAP_HP_FC_HZ = 1.5;

    public enum Status { OK, TOO_FEW_BEATS, NO_CLEAR_T_WAVE, UNCERTAIN, RATE_OUT_OF_RANGE, FAILED }

    public static final class Result {
        public Status status = Status.FAILED;
        public String reason = "";
        public int beatsUsed, beatsOffered;
        public double rrS = Double.NaN;
        public double qtMs = Double.NaN, ciLoMs = Double.NaN, ciHiMs = Double.NaN, qtcFMs = Double.NaN;
        public double qtAsRecordedMs = Double.NaN;   // same beat and T-end method, strap high-pass NOT undone
        public double qrsOnsetMs = Double.NaN, tApexMs = Double.NaN, tEndMs = Double.NaN;
        public double tAmpUv = Double.NaN, rAmpUv = Double.NaN, noiseUv = Double.NaN;
        /** QRS shape of the averaged beat (research; QRS duration is logged only, never shown). */
        public double qrsOffMs = Double.NaN, qrsDurMs = Double.NaN, rHalfWidthMs = Double.NaN, qsSpanMs = Double.NaN,
                qDepthUv = Double.NaN, sDepthUv = Double.NaN;
        public double stUv = Double.NaN, stSdUv = Double.NaN;
        public int validBootstraps;
        /** Averaged beat in uV, PRE samples before R (for drawing). Null if not computed. */
        public double[] avg;
        /** The same beats averaged with a 0.5-150 Hz band (fine detail of the QRS). Null if not computed. */
        public double[] avgWide;

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

    /** Zero-phase 0.5-150 Hz plus 50 Hz notch: the "detail" band. Used only for the averaged beat, never for rhythm. */
    public static double[] cleanWide(double[] uv) {
        double[][] hp = EcgR16Analyzer.coeffs(false, 0.5);
        double[][] lp = EcgR16Analyzer.coeffs(true, 150.0);
        double[] y = zeroPhase(uv, hp[0], hp[1]);
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
        return windows(uv, pk, bad, inv, null);
    }

    /** As above; rrOut (if given) receives, for each kept window, the gap since the previous R peak in seconds (NaN if none). */
    public static List<double[]> windows(double[] uv, List<Integer> pk, Set<Integer> bad, boolean inv, List<Double> rrOut) {
        return windows(uv, pk, bad, inv, rrOut, null);
    }

    /** As above; wideOut (if given) receives, for each kept window, the same beat filtered 0.5-150 Hz. */
    public static List<double[]> windows(double[] uv, List<Integer> pk, Set<Integer> bad, boolean inv, List<Double> rrOut,
                                         List<double[]> wideOut) {
        List<double[]> out = new ArrayList<>();
        if (uv.length < EDGE_START + EDGE_END + PRE + POST) return out;
        double[] z = clean(uv);
        double[] zw = wideOut == null ? null : cleanWide(uv);
        double sign = inv ? -1.0 : 1.0;
        for (int i = 0; i < pk.size(); i++) {
            int p = pk.get(i);
            if (bad != null && bad.contains(p)) continue;
            if (p < EDGE_START || p > z.length - EDGE_END || p - PRE < 0 || p + POST > z.length) continue;
            double[] w = new double[PRE + POST];
            for (int j = 0; j < w.length; j++) w[j] = sign * z[p - PRE + j];
            out.add(w);
            if (zw != null) {
                double[] ww = new double[PRE + POST];
                for (int j = 0; j < ww.length; j++) ww[j] = sign * zw[p - PRE + j];
                wideOut.add(ww);
            }
            if (rrOut != null) rrOut.add(i > 0 ? (p - pk.get(i - 1)) / (double) FS : Double.NaN);
        }
        return out;
    }

    // ---------------------------------------------------------------- QT per heart-rate band

    /** One slice of the recording measured on its own, so a changing heart rate does not smear the T wave. */
    public static final class Band {
        public double rrS, hr;
        public Result res;
    }

    public static final double BAND_MIN_SPREAD = 1.20;   // slowest/fastest typical gap must differ by at least this much

    /**
     * Splits the beat windows by the gap before each beat into 2 or 3 equal groups and measures each group separately.
     * Returns an empty list when the heart rate barely changed (one average is then right) or there are too few beats.
     */
    public static List<Band> bands(List<double[]> wins, List<Double> rrs) {
        List<Band> out = new ArrayList<>();
        if (wins == null || rrs == null || wins.size() != rrs.size()) return out;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < wins.size(); i++) {
            double v = rrs.get(i);
            if (!Double.isNaN(v) && v > 0.35 && v < 1.8) idx.add(i);
        }
        int n = idx.size();
        if (n < 2 * MIN_BEATS) return out;
        final List<Double> fr = rrs;
        java.util.Collections.sort(idx, (a, b) -> Double.compare(fr.get(a), fr.get(b)));
        double p10 = rrs.get(idx.get((int) (0.10 * (n - 1)))), p90 = rrs.get(idx.get((int) (0.90 * (n - 1))));
        if (p90 / p10 < BAND_MIN_SPREAD) return out;
        int nb = n >= 3 * MIN_BEATS + 30 ? 3 : 2;
        for (int g = 0; g < nb; g++) {
            int a = g * n / nb, b = (g + 1) * n / nb;
            List<double[]> gw = new ArrayList<>();
            double[] gr = new double[b - a];
            for (int k = a; k < b; k++) {
                gw.add(wins.get(idx.get(k)));
                gr[k - a] = rrs.get(idx.get(k));
            }
            java.util.Arrays.sort(gr);
            double med = gr[gr.length / 2];
            Band bd = new Band();
            bd.rrS = med;
            bd.hr = 60.0 / med;
            bd.res = analyse(gw, med);
            out.add(bd);
        }
        return out;
    }

    // ---------------------------------------------------------------- analysis

    public static Result analyse(List<double[]> wins, double rrMedianS) {
        return analyse(wins, rrMedianS, null);
    }

    /** wide (optional) holds the same beats filtered 0.5-150 Hz, in the same order as wins. */
    public static Result analyse(List<double[]> wins, double rrMedianS, List<double[]> wide) {
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
        List<double[]> keptWide = new ArrayList<>();
        boolean haveWide = wide != null && wide.size() == wins.size();
        for (int wi = 0; wi < wins.size(); wi++) {
            double[] w = wins.get(wi);
            if (corr(w, tmpl, PRE - 50, PRE + 225) >= SHAPE_GATE) {
                kept.add(w);
                if (haveWide) keptWide.add(wide.get(wi));
            }
        }
        r.beatsUsed = kept.size();
        if (kept.size() < 10) {
            r.status = Status.TOO_FEW_BEATS;
            r.reason = kept.size() + " beats matched the typical shape, need " + MIN_BEATS;
            return r;
        }
        double[] avg = mean(kept, null);
        r.avg = avg;
        if (haveWide && keptWide.size() == kept.size()) r.avgWide = mean(keptWide, null);

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
        double[] m0 = measure(avg, rrMedianS, 0, TEND_METHOD);
        if (m0 != null) r.qtAsRecordedMs = m0[3];
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
        return measure(w, rrS, STRAP_HP_FC_HZ, TEND_METHOD);
    }

    /** Undoes a first-order high-pass of corner fc on one averaged beat: x[n] = x[n-1] + y[n]/a - y[n-1], then removes the
     *  straight-line drift between the two quiet ends of the window (the integration turns small offsets into a ramp). */
    static double[] undoHighPass(double[] y, double fc) {
        int n = y.length;
        double rc = 1.0 / (2 * Math.PI * fc), dt = 1.0 / FS, a = rc / (rc + dt);
        double[] x = new double[n];
        x[0] = 0;
        for (int i = 1; i < n; i++) x[i] = x[i - 1] + y[i] / a - y[i - 1];
        int e = (int) (0.08 * FS);
        double m0 = median(x, 0, e), m1 = median(x, n - e, n);
        double c0 = e / 2.0, c1 = n - e / 2.0;
        for (int i = 0; i < n; i++) x[i] -= m0 + (m1 - m0) * (i - c0) / (c1 - c0);
        return x;
    }

    /** T end by the chosen method; falls back to NaN when the method cannot decide. */
    static double tEnd(double[] w, int ta, double base, double tpol, int method) {
        int xmax = Math.min(w.length - 3, ta + (int) (0.30 * FS));
        if (method == 1) {
            double tot = 0;
            for (int i = ta; i <= xmax; i++) tot += Math.max(0, (w[i] - base) * tpol);
            if (tot <= 0) return Double.NaN;
            double acc = 0;
            for (int i = ta; i <= xmax; i++) {
                acc += Math.max(0, (w[i] - base) * tpol);
                if (acc >= AREA_FRAC * tot) return i;
            }
            return Double.NaN;
        }
        if (method == 2) {
            double amp = (w[ta] - base) * tpol;
            if (amp <= 0) return Double.NaN;
            for (int i = ta + 2; i <= xmax - 2; i++) {
                double sm = (w[i - 2] + w[i - 1] + w[i] + w[i + 1] + w[i + 2]) / 5.0;
                if ((sm - base) * tpol <= THR_FRAC * amp) return i;
            }
            return Double.NaN;
        }
        return Double.NaN;
    }

    static double[] measure(double[] w0, double rrS, double hpFc, int tendMethod) {
        double[] w = hpFc > 0 ? undoHighPass(w0, hpFc) : w0;
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
        if (tendMethod != 0) {
            double alt = tEnd(w, ta, base, tpol, tendMethod);
            if (!Double.isNaN(alt)) te = alt;
        }

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
