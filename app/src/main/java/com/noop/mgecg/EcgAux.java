package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;

/**
 * The two other streams the strap banks once a second next to the stored ECG
 * (R16), on the same strap clock and the same record sequence numbers:
 *
 *  R20 (2140 B): optical pulse. Header byte 26 = samples per channel (50; 25 on
 *      the first, partial record). Words are 4-byte little-endian, signed 24-bit
 *      values sign-extended to 32 bits. Six channels of 50 samples each start at byte offsets
 *      47, 247, 1313, 1513, 1735 and 1935. Five of the six lock to the
 *      heartbeat; the one at 1513 does not (swing no bigger than a random
 *      control). What each channel measures (wavelength, photodiode) is unknown.
 *  R21 (1244 B): motion. From offset 28, int16 LE: accel x,y,z (100 samples
 *      each), 3 header words, gyro x,y,z (100 each). 4096 counts = 1 g.
 *
 * Timing is taken from the R-triggered AVERAGE pulse, not from each beat: the
 * pulse peak is broad, so per-beat peaks and slopes wander by tens of ms while
 * the average is repeatable to a few ms (29 Sep 2026: three channels agreed
 * between two runs to within 0, 2 and 6 ms). Pure Java. Research use only, not
 * a diagnosis and not a blood pressure measurement.
 */
final class EcgAux {

    static final int PULSE_FS = 50;
    static final int MOTION_FS = 100;
    static final int[] PULSE_OFFS = {47, 247, 1313, 1513, 1735, 1935};
    static final double ACC_COUNTS_PER_G = 4096.0;
    private static final int R20_LEN = 2140;
    private static final int R21_LEN = 1244, R21_ACC_AT = 28;
    private static final double W0 = -0.2, W1 = 1.0, DT = 0.002;      // beat-locked window, seconds
    private static final int NW = (int) Math.round((W1 - W0) / DT) + 1;

    private EcgAux() {}

    private static long u32(byte[] f, int o) {
        return (f[o] & 0xffL) | ((f[o + 1] & 0xffL) << 8) | ((f[o + 2] & 0xffL) << 16) | ((f[o + 3] & 0xffL) << 24);
    }

    // ---------------------------------------------------------------- attach

    /** Fills pulse, pulseStats, pulseShow and motionMg on the strip from stored R20/R21 records (any order). */
    static void attach(EcgR16Analyzer.Strip s, List<byte[]> recs) {
        int nRec = s.mv.length / EcgR16Analyzer.FS;
        if (nRec <= 0 || recs == null || recs.isEmpty()) return;
        int nc = PULSE_OFFS.length;
        double[][] p = new double[nc][nRec * PULSE_FS];
        for (double[] c : p) Arrays.fill(c, Double.NaN);
        double[] m = new double[nRec * MOTION_FS];
        Arrays.fill(m, Double.NaN);
        boolean anyP = false, anyM = false;
        for (byte[] f : recs) {
            if (f == null || f.length < 12) continue;
            long idx = u32(f, 11) - s.firstSeq;
            if (idx < 0 || idx >= nRec) continue;
            int ver = f[9] & 0xff;
            if (f.length == R20_LEN && ver == 20) {
                if ((f[26] & 0xff) != PULSE_FS) continue;                 // partial first record
                // Words are signed: a channel's DC can drift below zero (seen on 30 Sep 2026, channel
                // 47 going from +10000 to -24000 over the last minute), so the top byte is 0x00 or
                // 0xFF, the sign extension of a 24-bit value. Anything else is not a pulse sample.
                boolean ok = true;
                for (int c = 0; c < nc && ok; c++) {
                    for (int k = 0; k < PULSE_FS && ok; k++) {
                        int o = PULSE_OFFS[c] + 4 * k;
                        int top = f[o + 3] & 0xff;
                        boolean neg = (f[o + 2] & 0x80) != 0;
                        ok = neg ? top == 0xff : top == 0x00;
                    }
                }
                if (!ok) continue;
                for (int c = 0; c < nc; c++) {
                    for (int k = 0; k < PULSE_FS; k++) {
                        int o = PULSE_OFFS[c] + 4 * k;
                        p[c][(int) idx * PULSE_FS + k] =
                                (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8) | ((f[o + 2] & 0xff) << 16) | (f[o + 3] << 24);
                    }
                }
                anyP = true;
            } else if (f.length == R21_LEN && ver == 21) {
                double[] mean = new double[3];
                short[][] a = new short[3][MOTION_FS];
                for (int ax = 0; ax < 3; ax++) {
                    for (int k = 0; k < MOTION_FS; k++) {
                        int o = R21_ACC_AT + 2 * (ax * MOTION_FS + k);
                        a[ax][k] = (short) ((f[o] & 0xff) | (f[o + 1] << 8));
                        mean[ax] += a[ax][k];
                    }
                    mean[ax] /= MOTION_FS;
                }
                for (int k = 0; k < MOTION_FS; k++) {
                    double d0 = a[0][k] - mean[0], d1 = a[1][k] - mean[1], d2 = a[2][k] - mean[2];
                    m[(int) idx * MOTION_FS + k] = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2) / ACC_COUNTS_PER_G * 1000.0;
                }
                anyM = true;
            }
        }
        if (anyP) {
            double[][] y = new double[nc][];
            for (int c = 0; c < nc; c++) y[c] = displayPulse(p[c]);
            s.pulse = y;
            s.pulseStats = new double[nc][];
            Random rnd = new Random(20260929L);
            for (int c = 0; c < nc; c++) s.pulseStats[c] = lock(y[c], s.beats, rnd);
            s.pulseShow = best(s.pulseStats, 2);
        }
        if (anyM) s.motionMg = m;
    }

    /** Up to n channels that are clearly heartbeat-locked (swing >= 1 and >= 2x the random control), best first. */
    private static int[] best(double[][] st, int n) {
        List<Integer> idx = new ArrayList<>();
        for (int c = 0; c < st.length; c++) {
            if (st[c][7] >= 20 && st[c][0] >= 1.0 && st[c][0] >= 2.0 * st[c][1]) idx.add(c);
        }
        final double[][] fst = st;
        idx.sort((a, b) -> Double.compare(fst[b][0], fst[a][0]));
        int k = Math.min(n, idx.size());
        int[] r = new int[k];
        for (int i = 0; i < k; i++) r[i] = idx.get(i);
        return r;
    }

    // --------------------------------------------------------------- filters

    private static double[][] coeffs(boolean low, double fc, double fs) {
        double K = Math.tan(Math.PI * fc / fs), q = 1 / Math.sqrt(2), n = 1 / (1 + K / q + K * K);
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

    /** Zero-phase 0.5-8 Hz band-pass, odd-mirror padded by 2 s at each end. */
    static double[] bandpass50(double[] x) {
        int pad = Math.min(2 * PULSE_FS, x.length - 1);
        double[] e = new double[x.length + 2 * pad];
        for (int i = 0; i < pad; i++) {
            e[i] = 2 * x[0] - x[pad - i];
            e[e.length - 1 - i] = 2 * x[x.length - 1] - x[x.length - 1 - pad + i];
        }
        System.arraycopy(x, 0, e, pad, x.length);
        boolean[] kinds = {false, true};
        double[] fcs = {0.5, 8.0};
        for (int i = 0; i < 2; i++) {
            double[][] c = coeffs(kinds[i], fcs[i], PULSE_FS);
            e = biquad(e, c[0], c[1]);
            e = reverse(biquad(reverse(e), c[0], c[1]));
        }
        return Arrays.copyOfRange(e, pad, pad + x.length);
    }

    /** Band-passes every contiguous stretch of 2 s or more and inverts it (light absorbed rises when blood arrives). */
    private static double[] displayPulse(double[] raw) {
        double[] out = new double[raw.length];
        Arrays.fill(out, Double.NaN);
        int i = 0;
        while (i < raw.length) {
            if (Double.isNaN(raw[i])) { i++; continue; }
            int j = i;
            while (j < raw.length && !Double.isNaN(raw[j])) j++;
            if (j - i >= 2 * PULSE_FS) {
                double[] seg = Arrays.copyOfRange(raw, i, j);
                double mean = 0;
                for (double v : seg) mean += v;
                mean /= seg.length;
                for (int k = 0; k < seg.length; k++) seg[k] -= mean;
                double[] y = bandpass50(seg);
                for (int k = 0; k < y.length; k++) out[i + k] = -y[k];
            }
            i = j;
        }
        return out;
    }

    // ---------------------------------------------------------------- timing

    /** True when the whole -0.2 .. +1.0 s window around time t (s) lies on valid samples. */
    private static boolean windowOk(double[] y, double t) {
        int a = (int) Math.floor((t + W0) * PULSE_FS) - 1, b = (int) Math.ceil((t + W1) * PULSE_FS) + 1;
        if (a < 0 || b >= y.length) return false;
        for (int i = a; i <= b; i++) if (Double.isNaN(y[i])) return false;
        return true;
    }

    private static double at(double[] y, double tau) {
        double x = tau * PULSE_FS;
        int i = (int) Math.floor(x);
        double f = x - i;
        return y[i] * (1 - f) + y[i + 1] * f;
    }

    /** Ensemble-average steepest rise (ms), tangent foot (ms) and peak (ms) after the R wave, on a 2 ms grid. */
    private static double[] shape(double[][] E, int[] pick) {
        double[] a = new double[NW];
        for (int b : pick) for (int j = 0; j < NW; j++) a[j] += E[b][j];
        for (int j = 0; j < NW; j++) a[j] /= pick.length;
        int j0 = (int) Math.round((0.10 - W0) / DT), j1 = (int) Math.round((0.70 - W0) / DT);
        int k = j0 + 1, pk = j0;
        double gk = Double.NEGATIVE_INFINITY;
        for (int j = j0 + 1; j <= j1; j++) {
            double g = (a[j + 1] - a[j - 1]) / (2 * DT);
            if (g > gk) { gk = g; k = j; }
            if (a[j] > a[pk]) pk = j;
        }
        double mn = Double.MAX_VALUE;
        for (int j = (int) Math.round((0.0 - W0) / DT); j <= k; j++) mn = Math.min(mn, a[j]);
        double tk = W0 + k * DT;
        double foot = gk > 0 ? tk - (a[k] - mn) / gk : Double.NaN;
        return new double[]{tk * 1000.0, foot * 1000.0, (W0 + pk * DT) * 1000.0};
    }

    private static double swing(double[] y, double[] times, double sd) {
        int nb = 0;
        int nwc = 61;
        double[] a = new double[nwc];
        for (double t : times) {
            if (!windowOk(y, t)) continue;
            for (int j = 0; j < nwc; j++) a[j] += at(y, t + W0 + j * 0.02);
            nb++;
        }
        if (nb < 10) return 0;
        double mx = -Double.MAX_VALUE, mn = Double.MAX_VALUE;
        for (int j = 0; j < nwc; j++) { a[j] /= nb; mx = Math.max(mx, a[j]); mn = Math.min(mn, a[j]); }
        return (mx - mn) / sd;
    }

    /**
     * Beat-locked statistics for one channel: {swing, control, steepest rise ms,
     * SD, foot ms, SD, peak ms, beats}. Swing = peak-to-peak of the R-triggered
     * average divided by the channel's own SD (about 2.8 for a perfect sine).
     * Control = the largest swing from 40 draws of the same number of beats at
     * random times. SDs come from 60 bootstrap resamplings of the beats.
     */
    static double[] lock(double[] y, int[] beats500, Random rnd) {
        double[] out = new double[]{0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0};
        double s = 0, s2 = 0;
        int nv = 0;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : y) if (!Double.isNaN(v)) { s += v; s2 += v * v; nv++; }
        if (nv < 20 * PULSE_FS) return out;
        double sd = Math.sqrt(Math.max(1e-12, s2 / nv - (s / nv) * (s / nv)));
        List<double[]> rows = new ArrayList<>();
        List<Double> ts = new ArrayList<>();
        for (int b : beats500) {
            double t = b / (double) EcgR16Analyzer.FS;
            if (!windowOk(y, t)) continue;
            double[] r = new double[NW];
            for (int j = 0; j < NW; j++) r[j] = at(y, t + W0 + j * DT);
            rows.add(r);
            ts.add(t);
        }
        int nb = rows.size();
        out[7] = nb;
        if (nb < 20) return out;
        double[][] E = rows.toArray(new double[0][]);
        double[] times = new double[nb];
        for (int i = 0; i < nb; i++) times[i] = ts.get(i);
        out[0] = swing(y, times, sd);
        double tlo = 3, thi = y.length / (double) PULSE_FS - 3;
        double cmax = 0;
        for (int d = 0; d < 40; d++) {
            double[] rt = new double[nb];
            for (int i = 0; i < nb; i++) rt[i] = tlo + rnd.nextDouble() * (thi - tlo);
            cmax = Math.max(cmax, swing(y, rt, sd));
        }
        out[1] = cmax;
        int[] all = new int[nb];
        for (int i = 0; i < nb; i++) all[i] = i;
        double[] sh = shape(E, all);
        out[2] = sh[0]; out[4] = sh[1]; out[6] = sh[2];
        double a1 = 0, a2 = 0, f1 = 0, f2 = 0;
        int nbs = 60;
        for (int r = 0; r < nbs; r++) {
            int[] pick = new int[nb];
            for (int i = 0; i < nb; i++) pick[i] = rnd.nextInt(nb);
            double[] q = shape(E, pick);
            a1 += q[0]; a2 += q[0] * q[0]; f1 += q[1]; f2 += q[1] * q[1];
        }
        out[3] = Math.sqrt(Math.max(0, a2 / nbs - (a1 / nbs) * (a1 / nbs)));
        out[5] = Math.sqrt(Math.max(0, f2 / nbs - (f1 / nbs) * (f1 / nbs)));
        return out;
    }

    /** One line for the log and the image header, or "" when no channel locks to the heartbeat. */
    static String timingText(EcgR16Analyzer.Strip s) {
        if (s.pulseStats.length == 0) return "";
        int[] show = best(s.pulseStats, 3);
        if (show.length == 0) return "";
        StringBuilder b = new StringBuilder("R to optical pulse, beat-averaged (rise / foot, ms): ");
        for (int i = 0; i < show.length; i++) {
            double[] st = s.pulseStats[show[i]];
            if (i > 0) b.append("  |  ");
            b.append(String.format(Locale.UK, "@%d %.0f+-%.0f / %.0f+-%.0f", PULSE_OFFS[show[i]], st[2], st[3], st[4], st[5]));
        }
        b.append(String.format(Locale.UK, "  (n=%d)", (int) s.pulseStats[show[0]][7]));
        return b.toString();
    }

    // ---------------------------------------------------------------- config words

    /**
     * R20's unused bytes are mostly zero. Across 565 records on 29-30 Sep 2026 only 20 words outside the six
     * channels were ever non-zero, nearly all with one fixed value (config-like). This lists them so every
     * session adds a data point: "offset:value x records", values that change between sessions are the
     * interesting ones (byte 1291 had two).
     */
    static String configWords(List<byte[]> recs) {
        java.util.TreeMap<Integer, java.util.TreeMap<Integer, Integer>> seen = new java.util.TreeMap<>();
        int n = 0;
        int[][] ch = {{47, 247}, {247, 447}, {1313, 1513}, {1513, 1713}, {1735, 1935}, {1935, 2135}};
        for (byte[] f : recs) {
            if (f == null || f.length != R20_LEN || (f[9] & 0xff) != 20) continue;
            n++;
            for (int o = 47; o + 4 <= R20_LEN - 4; o += 4) {
                boolean inCh = false;
                for (int[] c : ch) if (o >= c[0] && o < c[1]) { inCh = true; break; }
                if (inCh) continue;
                int v = (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8) | ((f[o + 2] & 0xff) << 16) | (f[o + 3] << 24);
                if (v != 0) seen.computeIfAbsent(o, k -> new java.util.TreeMap<>()).merge(v, 1, Integer::sum);
            }
        }
        StringBuilder b = new StringBuilder("records=" + n + " nonzeroOffsets=" + seen.size());
        int k = 0;
        for (Map.Entry<Integer, java.util.TreeMap<Integer, Integer>> e : seen.entrySet()) {
            if (k++ >= 60) break;
            java.util.TreeMap<Integer, Integer> vals = e.getValue();
            if (vals.size() <= 2) {
                for (Map.Entry<Integer, Integer> ve : vals.entrySet()) {
                    b.append(String.format(Locale.US, " %d=%dx%d", e.getKey(), ve.getKey(), ve.getValue()));
                }
            } else {
                b.append(String.format(Locale.US, " %d=varies(%d values %d..%d)", e.getKey(), vals.size(),
                        vals.firstKey(), vals.lastKey()));
            }
        }
        return b.toString();
    }
}
