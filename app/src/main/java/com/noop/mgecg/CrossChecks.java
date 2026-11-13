package com.noop.mgecg;

// 0.51.1 (10 Nov 2026): frequency-domain R17 / R16 gain and the event 118 window decode test. FILE VERSION 0.51.0 (10 Nov 2026): silent per-reading cross-checks (build brief 0.51.0 item 5). Pure Java (no Android), tested off the phone.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * (1) R17 against R16: the strap's own 100 Hz stream (R17, 240-byte type 43 frames, int16 at byte 34 + 2k, said to be integer microvolts) is regressed on
 * R16 x 0.0621 passed through a causal 0.51 Hz second-order high-pass (the strap's R17 filter as logged), a zero-phase 40 Hz anti-alias low-pass and taken
 * at the R17 sample times; lag by cross-correlation (-1 to +1 s), then gain (no intercept, both de-meaned per stretch) and correlation over R16 quality-3
 * seconds, at least 20 s. Logged before: gain 1.000 +- 0.008, lag 264 ms. A drift of this ratio is itself a finding.
 * (2) Event 118 field E against our average beat: E_pred = 1.326 x (R minus S of the mean beat, uV at the design scale) - 107 (decode log, 16 sessions).
 */
final class CrossChecks {
    private CrossChecks() {}

    static final int FS = 500;

    static Map<String, String> r17(byte[] bin) {
        List<byte[]> f16 = new ArrayList<>(), f17 = new ArrayList<>();
        for (byte[] f : ProAnalysis.frames(bin)) { if (!ProAnalysis.crcOk(f)) continue; if (f.length == 1584) f16.add(f); else if (f.length == 240) f17.add(f); }
        return r17(f16, f17);
    }

    static Map<String, String> r17(List<byte[]> f16, List<byte[]> f17) {
        Map<String, String> m = new LinkedHashMap<>();
        if (f17.isEmpty()) { m.put("r17_r16_skipped", "no_r17"); return m; }
        if (f16.size() < 20) { m.put("r17_r16_skipped", "no_r16"); return m; }
        // R16 continuous stretches (finite, contiguous counter), times on the strap clock
        int S = f16.size();
        List<double[]> segX = new ArrayList<>(); List<Double> segT0 = new ArrayList<>(); List<boolean[]> segQ = new ArrayList<>();
        int s = 0;
        while (s < S) {
            if (ProAnalysis.u16(f16.get(s), 32) != 500) { s++; continue; }                 // every reading opens with an empty and a ~245-sample frame (138.8)
            int e = s + 1; while (e < S && ProAnalysis.u32(f16.get(e), 11) == ProAnalysis.u32(f16.get(e - 1), 11) + 1 && ProAnalysis.u16(f16.get(e), 32) == 500) e++;
            double[] x = new double[(e - s) * 500]; boolean[] q = new boolean[e - s]; boolean bad = false;
            for (int i = s; i < e; i++) { byte[] f = f16.get(i); q[i - s] = (f[21] & 0xFF) == 3; for (int k = 0; k < 500; k++) { if (!SpectrumBlock.sampleOk(f, k)) { q[i - s] = false; } x[(i - s) * 500 + k] = SpectrumBlock.counts(f, k) * ProAnalysis.uv(); } }
            if (e - s >= 20 && !bad) { segX.add(x); segT0.add((double) ProAnalysis.u32(f16.get(s), 11)); segQ.add(q); }      // 0.51.1: times from the frame COUNTER (the frame time stamps jitter in 10 ms steps, sd up to 4.7 ms)
            s = e;
        }
        if (segX.isEmpty()) { m.put("r17_r16_skipped", "no_r16_stretch"); return m; }
        // filtered R16
        double[] hp = ProDsp.hp2(EcgWhoopSpec.R17_HP_CORNER_HZ, FS, EcgWhoopSpec.R17_HP_Q);      // 0.53.2: the measured R17 high-pass (0.53 Hz, Q 0.72; was 0.51 Hz, Q 0.7071)
        List<double[]> segY = new ArrayList<>();
        for (double[] x : segX) { double[] y = ProDsp.sos(x, new double[][] { hp }, 1.0); y = ProDsp.filtfilt(y, ProDsp.butterLow(4, 40, FS)); segY.add(y); }
        // R17 points
        List<double[]> pts = new ArrayList<>();
        for (byte[] f : f17) { int n = ProAnalysis.u16(f, 32); if (n < 1 || n > 100 || 34 + 2 * n > f.length - 4) continue; double t = ProAnalysis.u32(f, 11); for (int k = 0; k < n; k++) pts.add(new double[] { t + k * 0.01, (short) ((f[34 + 2 * k] & 0xFF) | (f[35 + 2 * k] << 8)) }); }
        // lag scan: R17(t) against R16f(t - lag)
        double best = -2, bestLag = 0;
        for (int lagMs = -1000; lagMs <= 1000; lagMs += 2) { double c = corrAt(pts, segY, segT0, segQ, lagMs / 1000.0, null); if (c > best) { best = c; bestLag = lagMs; } }
        double[] g = new double[2]; double c = corrAt(pts, segY, segT0, segQ, bestLag / 1000.0, g);
        if (g[1] < 20 * 100) { m.put("r17_r16_skipped", "q3_overlap_" + (int) (g[1] / 100) + "_s"); return m; }
        m.put("r17_r16_gain", String.format(Locale.US, "%.4f", g[0]));
        m.put("r17_r16_corr", String.format(Locale.US, "%.4f", c));
        m.put("r17_lag_ms", String.format(Locale.US, "%.0f", bestLag));
        m.put("r17_r16_sec", String.format(Locale.US, "%.0f", g[1] / 100));
        try { freqGain(m, pts, segY, segT0, segQ, bestLag / 1000.0); } catch (Throwable ignored) { }
        return m;
    }

    /**
     * 0.51.1: R17 / R16 gain in the frequency domain, 6-30 Hz, where both streams are flat and coherent: Welch cross-spectrum over 10 s Hann windows (hop 5 s)
     * of R17 against the filtered R16 at the R17 sample times (after the lag), H = Sxy / Sxx per 0.1 Hz bin, corrected for our own zero-phase 40 Hz low-pass,
     * averaged with coherence weights over bins with coherence 0.98 or more. Unlike the time-domain regression above it is not pulled down by disturbed
     * stretches or by the filter mismatch at the band edges. r17_uV_per_count = 0.0621 x gain = the strap firmware's own microvolts per R16 count.
     * Python reference on the 10 Nov pulls (raw R16, FIR anti-alias): 1.0078 / 1.0107 / 1.0136; delay 265.5 ms in all three.
     */
    static void freqGain(Map<String, String> m, List<double[]> pts, List<double[]> segY, List<Double> segT0, List<boolean[]> segQ, double lag) {
        final int L = 1000, HOPW = 500, K0 = 60, K1 = 300;
        List<double[]> xs = new ArrayList<>(), ys = new ArrayList<>();
        double[] curX = new double[0]; List<Double> cx = new ArrayList<>(), cy = new ArrayList<>(); double lastT = -1e9; int si = 0;
        for (double[] p : pts) {
            double t = p[0] - lag; boolean ok = false; double xv = 0;
            while (si < segY.size() && t > segT0.get(si) + segY.get(si).length / (double) FS) si++;
            if (si < segY.size()) { double rel = t - segT0.get(si); int sec = (int) rel; if (rel >= 2.0 && sec < segQ.get(si).length && segQ.get(si)[sec]) { double fi = rel * FS; int i0 = (int) fi; if (i0 + 1 < segY.get(si).length) { double fr = fi - i0; xv = segY.get(si)[i0] * (1 - fr) + segY.get(si)[i0 + 1] * fr; ok = true; } } }
            if (!ok || p[0] - lastT > 0.015) { if (cx.size() >= L) { xs.add(toArr(cx)); ys.add(toArr(cy)); } cx.clear(); cy.clear(); }
            if (ok) { cx.add(xv); cy.add(p[1]); lastT = p[0]; }
        }
        if (cx.size() >= L) { xs.add(toArr(cx)); ys.add(toArr(cy)); }
        int nb = K1 - K0 + 1; double[] sxr = new double[nb], sxi = new double[nb], sxx = new double[nb], syy = new double[nb]; int nw = 0;
        double[] w = new double[L]; for (int i = 0; i < L; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / L);
        double[] cs = new double[L], sn = new double[L]; for (int i = 0; i < L; i++) { cs[i] = Math.cos(2 * Math.PI * i / L); sn[i] = Math.sin(2 * Math.PI * i / L); }
        for (int s = 0; s < xs.size(); s++) {
            double[] x = xs.get(s), y = ys.get(s);
            for (int a = 0; a + L <= x.length; a += HOPW) {
                double mx = 0, my = 0; for (int i = 0; i < L; i++) { mx += x[a + i]; my += y[a + i]; } mx /= L; my /= L;
                for (int k = K0; k <= K1; k++) {
                    double xr = 0, xi = 0, yr = 0, yi = 0; int idx = 0;
                    for (int i = 0; i < L; i++) { double u = (x[a + i] - mx) * w[i], v = (y[a + i] - my) * w[i]; xr += u * cs[idx]; xi -= u * sn[idx]; yr += v * cs[idx]; yi -= v * sn[idx]; idx += k; if (idx >= L) idx -= L; }
                    int b = k - K0; sxr[b] += xr * yr + xi * yi; sxi[b] += xr * yi - xi * yr; sxx[b] += xr * xr + xi * xi; syy[b] += yr * yr + yi * yi;
                }
                nw++;
            }
        }
        if (nw < 3) { m.put("r17_gain_f6_30", "skipped_" + nw + "_windows"); return; }
        double sw = 0, sg = 0, sc = 0; int nc = 0;
        for (int b = 0; b < nb; b++) {
            double f = (b + K0) * 0.1, mag = Math.hypot(sxr[b], sxi[b]), coh = mag * mag / (sxx[b] * syy[b]);
            double lp = 1.0 / (1.0 + Math.pow(f / 40.0, 8));                 // our filtfilt 4th-order 40 Hz low-pass, squared magnitude
            double gain = mag / sxx[b] / lp;
            sc += coh; nc++;
            if (coh < 0.98) continue; double wt = coh / (1 - coh); sg += wt * gain; sw += wt;
        }
        if (sw == 0) { m.put("r17_gain_f6_30", "low_coherence"); m.put("r17_coh_f6_30", String.format(Locale.US, "%.4f", sc / nc)); return; }
        double G = sg / sw;
        m.put("r17_gain_f6_30", String.format(Locale.US, "%.4f", G));
        m.put("r17_coh_f6_30", String.format(Locale.US, "%.4f", sc / nc));
        m.put("r17_uV_per_count", String.format(Locale.US, "%.5f", G * ProAnalysis.uv()));
        m.put("r17_windows", String.valueOf(nw));
    }

    static double[] toArr(List<Double> l) { double[] a = new double[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }

    /**
     * 0.51.1 DECODE TEST of event 118 against our own beats in the strap's window. Finding of 10 Nov (3 readings): with B in MILLISECONDS from the first
     * R16 sample, the beats in [B / 1000 + 5.49 s, 36.21 s] number n1 (27 / 27, 24 / 24, 25 / 26), and on R16 x 0.0621 through a CAUSAL 0.51 Hz
     * second-order high-pass (the strap's R17 filter) the R-minus-S amplitudes (S = minimum within 60 ms after the R apex) give mean / E = 1.003, 0.994
     * (two clean readings; 0.935 on a noisy one), min ~ C and max ~ D within 1-3 %, and A - B ~ one R-R interval. This writes the same numbers for every
     * reading so the rule is tested on many: ev118_n1_ours, ev118_mean_ours, ev118_min_ours, ev118_max_ours, ev118_E_over_mean, ev118_C_over_min,
     * ev118_D_over_max, ev118_AB_ms, ev118_rr_ms.
     */
    static Map<String, String> ev118Window(byte[] in, List<byte[]> f16) {
        Map<String, String> m = new LinkedHashMap<>();
        if (in == null || in.length < 31 || (in[0] & 0xFF) != 2 || f16 == null || f16.size() < 40) return m;
        long A = u32(in, 5), B = u32(in, 9); int C = u16(in, 13), D = u16(in, 15), E = u16(in, 17); long n1 = u32(in, 23);
        long c0 = ProAnalysis.u32(f16.get(0), 11); int S = (int) Math.min(900, ProAnalysis.u32(f16.get(f16.size() - 1), 11) - c0 + 1); if (S < 40) return m;
        double[] x = new double[S * 500]; int i0 = -1;
        for (byte[] f : f16) { long s = ProAnalysis.u32(f, 11) - c0; if (s < 0 || s >= S) continue; int n = Math.min(500, ProAnalysis.u16(f, 32)); for (int k = 0; k < n; k++) if (SpectrumBlock.sampleOk(f, k)) { int i = (int) s * 500 + k; x[i] = SpectrumBlock.counts(f, k) * ProAnalysis.uv(); if (i0 < 0 || i < i0) i0 = i; } }
        if (i0 < 0) return m;
        int end = Math.min(x.length, i0 + 40 * FS); double[] xs = java.util.Arrays.copyOfRange(x, 0, end);
        double[] z = ProDsp.sos(xs, new double[][] { ProDsp.hp2(0.51, FS, ProDsp.Q2) }, 0.0);
        int[] pk0 = ProAnalysis.detectR(java.util.Arrays.copyOfRange(z, i0, z.length));      // threshold from the median of block maxima: a contact spike does not hide the beats
        int[] pk = new int[pk0.length]; for (int i = 0; i < pk.length; i++) pk[i] = pk0[i] + i0;
        double lo = B / 1000.0 + 5.49, hi = 36.21; List<Double> amp = new ArrayList<>(), tb = new ArrayList<>();
        for (int k : pk) {
            int a = Math.max(0, k - 20), b = Math.min(z.length - 31, k + 20); if (b <= a) continue; int c = a; for (int i = a; i <= b; i++) if (z[i] > z[c]) c = i;
            double mn = z[c]; for (int i = c; i < c + 30; i++) mn = Math.min(mn, z[i]);
            double t = (c - i0) / (double) FS; tb.add(t); if (t >= lo && t <= hi) amp.add(z[c] - mn);
        }
        m.put("ev118_A_ms", String.valueOf(A)); m.put("ev118_B_ms", String.valueOf(B)); m.put("ev118_C", String.valueOf(C)); m.put("ev118_D", String.valueOf(D)); m.put("ev118_n1", String.valueOf(n1));
        m.put("ev118_AB_ms", String.valueOf(A - B));
        { double best = Double.NaN; for (int i = 1; i < tb.size(); i++) if (tb.get(i - 1) >= B / 1000.0 - 0.5) { best = (tb.get(i) - tb.get(i - 1)) * 1000; break; } if (!Double.isNaN(best)) m.put("ev118_rr_ms", String.format(Locale.US, "%.0f", best)); }
        m.put("ev118_n1_ours", String.valueOf(amp.size()));
        if (amp.size() < 3) return m;
        double s = 0, mn = 1e18, mx = -1e18; for (double v : amp) { s += v; mn = Math.min(mn, v); mx = Math.max(mx, v); } double mean = s / amp.size();
        m.put("ev118_mean_ours", String.format(Locale.US, "%.0f", mean)); m.put("ev118_min_ours", String.format(Locale.US, "%.0f", mn)); m.put("ev118_max_ours", String.format(Locale.US, "%.0f", mx));
        m.put("ev118_E_over_mean", String.format(Locale.US, "%.4f", E / mean)); m.put("ev118_E_over_mean_0p0621", String.format(Locale.US, "%.4f", E / (mean * ProAnalysis.UV_OLD / ProAnalysis.uv())));      // 0.53.2: the same ratio at the old scale 0.0621 (the series before 0.53.0 is on that scale) m.put("ev118_C_over_min", String.format(Locale.US, "%.4f", C / mn)); m.put("ev118_D_over_max", String.format(Locale.US, "%.4f", D / mx));
        return m;
    }
    private static long u32(byte[] b, int o) { return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24); }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    /** Correlation of R17 with the filtered R16 at t - lag over quality-3 seconds; out[0] = gain (R17 per R16), out[1] = points used. */
    private static double corrAt(List<double[]> pts, List<double[]> segY, List<Double> segT0, List<boolean[]> segQ, double lag, double[] out) {
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0; int n = 0;
        int si = 0;
        for (double[] p : pts) {
            double t = p[0] - lag;
            while (si < segY.size() && t > segT0.get(si) + segY.get(si).length / (double) FS) si++;
            if (si >= segY.size()) break;
            double rel = t - segT0.get(si); if (rel < 2.0) continue;                      // 2 s of filter settling at a stretch start
            int sec = (int) rel; if (sec >= segQ.get(si).length || !segQ.get(si)[sec]) continue;
            double fi = rel * FS; int i0 = (int) fi; if (i0 + 1 >= segY.get(si).length) continue; double fr = fi - i0;
            double xv = segY.get(si)[i0] * (1 - fr) + segY.get(si)[i0 + 1] * fr, yv = p[1];
            sx += xv; sy += yv; sxx += xv * xv; syy += yv * yv; sxy += xv * yv; n++;
        }
        if (n < 100) { if (out != null) { out[0] = Double.NaN; out[1] = n; } return -2; }
        double cxx = sxx - sx * sx / n, cyy = syy - sy * sy / n, cxy = sxy - sx * sy / n;
        if (out != null) { out[0] = cxy / cxx; out[1] = n; }
        return cxx > 0 && cyy > 0 ? cxy / Math.sqrt(cxx * cyy) : -2;
    }

    /** R minus S of a mean beat (ProAnalysis meanFull, PRE samples before R): R = max within +-20 ms of the apex index, S = min from R to +60 ms. */
    static double p2p(double[] meanFull) {
        if (meanFull == null || meanFull.length < ProAnalysis.PRE + 40) return Double.NaN;
        int P = ProAnalysis.PRE; int ri = P; for (int k = P - 10; k <= P + 10; k++) if (meanFull[k] > meanFull[ri]) ri = k;
        double mn = meanFull[ri]; for (int k = ri; k <= Math.min(meanFull.length - 1, ri + 30); k++) mn = Math.min(mn, meanFull[k]);
        return meanFull[ri] - mn;
    }

    /** Notes from the 118 inner bytes (E u16 at 17) and our mean beat. */
    static Map<String, String> ev118(byte[] inner118, double[] meanFull) {
        Map<String, String> m = new LinkedHashMap<>();
        if (inner118 == null || inner118.length < 19 || (inner118[0] & 0xFF) != 2) { m.put("ev118_skipped", "no_event"); return m; }
        int E = (inner118[17] & 0xFF) | ((inner118[18] & 0xFF) << 8);
        m.put("ev118_E", String.valueOf(E));
        double pp = p2p(meanFull);
        if (Double.isNaN(pp)) { m.put("ev118_skipped", "no_mean_beat"); return m; }
        double pred = 1.326 * pp - 107;
        m.put("our_p2p_uV", String.format(Locale.US, "%.0f", pp));
        m.put("E_pred", String.format(Locale.US, "%.0f", pred));
        m.put("E_resid_pct", String.format(Locale.US, "%.1f", 100.0 * (E - pred) / pred));
        return m;
    }
}
