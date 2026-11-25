package com.noop.mgecg;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// FILE VERSION 0.89.0 (25 Nov 2026): NEW. Finds the slow-tone scale check (file LabQ_BOTH.wav, decode log 437.4 / 438) in a reading and measures the strap's amplitude in each of its four blocks.
/**
 * Slow-tone scale check. A DC meter and the strap sit on the same slowly swinging voltage (0.10 Hz, 0.05 Hz, 0.25 Hz, 0.10 Hz), so
 * scale (uV per count) = meter amplitude (mV) x strap factor x 1000 / strap amplitude (counts), strap factor = f / sqrt(f^2 + fc^2) for the strap's own high-pass corner fc.
 * Pure arithmetic on the 500 Hz counts: no Android, tested off the phone on pull 1795561719 (2,637 / 1,315 / 3,299 / 2,689 counts).
 */
final class SlowTone {
    private SlowTone() { }

    /** Block frequencies (Hz) and block starts / ends in seconds from the start of the file. */
    static final double[] F = { 0.10, 0.05, 0.25, 0.10 };
    static final double[] T_ON = { 10, 76, 162, 208 }, T_OFF = { 70, 156, 202, 248 };
    static final double FILE_S = 253.0;
    /** The strap's own corner and its measured range (phone-free step decays, decode log 434). */
    static final double FC = 0.40, FC_LO = 0.38, FC_HI = 0.42;

    static final class Result {
        double t0S;                              // where the file starts in the reading
        final double[] amp = new double[4];      // strap amplitude per block, counts
        final double[] r2 = new double[4];       // share of the block's slow variance the sine explains
        /** Scale in uV per count for a meter swing (peak to peak, mV) in block k, at corner fc. */
        double scale(int k, double meterPpMv, double fc) { return meterPpMv / 2.0 * factor(F[k], fc) * 1000.0 / amp[k]; }
        /** The meter swing (peak to peak, mV) that a given scale predicts for block k. */
        double meterPp(int k, double uvPerCount, double fc) { return 2.0 * uvPerCount * amp[k] / (factor(F[k], fc) * 1000.0); }
    }

    static double factor(double f, double fc) { return f / Math.sqrt(f * f + fc * fc); }

    /** All samples of the reading in order (frames with no samples are skipped). */
    static double[] counts(List<byte[]> recs) {
        int n = 0;
        for (byte[] f : recs) { if (f.length != 1584) continue; int c = EcgWhoopSpec.r16Count(f); if (c > 0) n += c; }
        double[] x = new double[n]; int k = 0;
        for (byte[] f : recs) { if (f.length != 1584) continue; int c = EcgWhoopSpec.r16Count(f); for (int i = 0; i < c; i++) x[k++] = EcgWhoopSpec.r16Sample(f, i); }
        return x;
    }

    /** Sine + cosine + level + slope fitted to y[a..b) sampled at 10 Hz; returns {amplitude, explained share}. */
    private static double[] fit(double[] y, int a, int b, double f) {
        int n = b - a; if (n < 100) return null;
        double[][] m = new double[4][5];
        double mid = (a + b - 1) / 2.0;
        for (int i = a; i < b; i++) {
            double t = (i - mid) / 10.0, w = 2 * Math.PI * f * t;
            double[] v = { Math.sin(w), Math.cos(w), 1, t };
            for (int p = 0; p < 4; p++) { for (int q = 0; q < 4; q++) m[p][q] += v[p] * v[q]; m[p][4] += v[p] * y[i]; }
        }
        double[] c = solve(m); if (c == null) return null;
        double ssRes = 0, ssSlow = 0, sy = 0, st = 0, stt = 0, sty = 0;
        for (int i = a; i < b; i++) { double t = (i - mid) / 10.0; sy += y[i]; st += t; stt += t * t; sty += t * y[i]; }
        double sl = (n * sty - st * sy) / (n * stt - st * st), ic = (sy - sl * st) / n;
        for (int i = a; i < b; i++) {
            double t = (i - mid) / 10.0, w = 2 * Math.PI * f * t;
            double d = y[i] - (c[0] * Math.sin(w) + c[1] * Math.cos(w) + c[2] + c[3] * t); ssRes += d * d;
            double e = y[i] - (ic + sl * t); ssSlow += e * e;
        }
        return new double[] { Math.hypot(c[0], c[1]), ssSlow > 0 ? 1 - ssRes / ssSlow : 0 };
    }

    private static double[] solve(double[][] m) {
        int n = m.length;
        for (int i = 0; i < n; i++) {
            int p = i; for (int r = i + 1; r < n; r++) if (Math.abs(m[r][i]) > Math.abs(m[p][i])) p = r;
            if (Math.abs(m[p][i]) < 1e-9) return null;
            double[] t = m[i]; m[i] = m[p]; m[p] = t;
            for (int r = 0; r < n; r++) { if (r == i) continue; double g = m[r][i] / m[i][i]; for (int q = i; q <= n; q++) m[r][q] -= g * m[i][q]; }
        }
        double[] c = new double[n]; for (int i = 0; i < n; i++) c[i] = m[i][n] / m[i][i];
        return c;
    }

    /**
     * Looks for the four blocks in a reading (500 Hz counts). Null unless every block is found: each sine explains 70 % or more of its block's slow variance,
     * the two 0.10 Hz blocks agree within 15 %, the 0.05 Hz block is 0.38 to 0.65 of them and the 0.25 Hz block 0.8 to 1.8 of them, the 0.10 Hz amplitude is 300 counts or more,
     * and no sample of a block is at the amplifier's limit. Medians of 0.1 s remove the mains and most of the heartbeat first.
     */
    static Result find(double[] x) {
        if (x == null || x.length < (int) ((T_OFF[3] + 1) * 500)) return null;
        int n10 = x.length / 50; double[] y = new double[n10]; boolean[] rail = new boolean[n10]; double[] w = new double[50];
        for (int i = 0; i < n10; i++) {
            System.arraycopy(x, i * 50, w, 0, 50); Arrays.sort(w); y[i] = (w[24] + w[25]) / 2; rail[i] = Math.abs(w[0]) >= 125000 || Math.abs(w[49]) >= 125000;
        }
        Result best = null; double bestScore = -1;
        int last = n10 - (int) (T_OFF[3] * 10);
        for (int s = 0; s <= last; s += 5) {                                   // file start in steps of 0.5 s
            Result r = new Result(); r.t0S = s / 10.0; double score = 0; boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                int a = s + (int) ((T_ON[k] + 5) * 10), b = s + (int) (T_OFF[k] * 10);    // the first 5 s of a block settle
                double[] f = fit(y, a, b, F[k]); if (f == null) { ok = false; break; }
                r.amp[k] = f[0]; r.r2[k] = f[1]; score += f[1];
            }
            if (ok && score > bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return null;
        for (int k = 0; k < 4; k++) {
            if (best.r2[k] < 0.70) return null;
            int a = (int) ((best.t0S + T_ON[k]) * 10), b = (int) ((best.t0S + T_OFF[k]) * 10);
            for (int i = a; i < b && i < n10; i++) if (rail[i]) return null;
        }
        double a1 = best.amp[0], a4 = best.amp[3], m = (a1 + a4) / 2;
        if (m < 300 || Math.abs(a1 - a4) > 0.15 * m) return null;
        double r2 = best.amp[1] / m, r3 = best.amp[2] / m;
        if (r2 < 0.38 || r2 > 0.65 || r3 < 0.8 || r3 > 1.8) return null;
        return best;
    }

    /** The report card; "" when the reading holds no slow-tone check. appUv = the scale the app is using. */
    static String card(Result r, double appUv) {
        if (r == null) return "";
        StringBuilder b = new StringBuilder("<section class=\"card\"><div class=\"row2\"><div class=\"k\">Slow-tone scale check</div><span class=\"pill\">Experimental</span></div>");
        b.append(String.format(Locale.US, "<p class=\"hint\">This reading holds the slow test tones of LabQ_BOTH.wav, starting about %.0f s in. With a DC meter across the same two leads, each block gives the strap's scale: "
                + "<b>scale = meter swing (peak to peak, mV) &times; the factor below</b>. The factor uses the strap's own 0.40 Hz corner (0.38 to 0.42 Hz).</p><div class=\"rows\">", r.t0S));
        String[] nm = { "0.10 Hz, first block", "0.05 Hz", "0.25 Hz", "0.10 Hz, second block" };
        for (int k = 0; k < 4; k++) {
            b.append("<div class=\"row\"><span>").append(nm[k]).append("</span><span>").append(String.format(Locale.US,
                    "%,.0f counts &middot; factor %.4f (%.4f to %.4f) &middot; meter swing for 0.0477: %.2f mV, for %.5f: %.2f mV",
                    r.amp[k], r.scale(k, 1.0, FC), r.scale(k, 1.0, FC_HI), r.scale(k, 1.0, FC_LO), r.meterPp(k, 0.0477, FC), appUv, r.meterPp(k, appUv, FC))).append("</span></div>");
        }
        b.append("</div><p class=\"cap\">The first run of this kind (24 Nov 2026, a swing of only 1.2 mV on a meter that steps by 0.1 mV) gave 0.055 &micro;V per count (0.048 to 0.063). "
                + "The 0.05 and 0.10 Hz blocks are the ones to trust: a meter may average a faster swing. Keep still during the tones; the level is right when the meter swings by several millivolts and the trace stays inside the amplifier's range. Not a diagnosis.</p></section>");
        return b.toString();
    }
}
