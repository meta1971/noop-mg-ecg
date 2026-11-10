package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): silent per-reading cross-checks (build brief 0.51.0 item 5). Pure Java (no Android), tested off the phone.

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
            for (int i = s; i < e; i++) { byte[] f = f16.get(i); q[i - s] = (f[21] & 0xFF) == 3; for (int k = 0; k < 500; k++) { if (!SpectrumBlock.sampleOk(f, k)) { q[i - s] = false; } x[(i - s) * 500 + k] = SpectrumBlock.counts(f, k) * ProAnalysis.UV; } }
            if (e - s >= 20 && !bad) { segX.add(x); segT0.add(ProAnalysis.u32(f16.get(s), 15) + ProAnalysis.u16(f16.get(s), 19) / 32768.0); segQ.add(q); }
            s = e;
        }
        if (segX.isEmpty()) { m.put("r17_r16_skipped", "no_r16_stretch"); return m; }
        // filtered R16
        double[] hp = ProDsp.hp2(0.51, FS, ProDsp.Q2);
        List<double[]> segY = new ArrayList<>();
        for (double[] x : segX) { double[] y = ProDsp.sos(x, new double[][] { hp }, 1.0); y = ProDsp.filtfilt(y, ProDsp.butterLow(4, 40, FS)); segY.add(y); }
        // R17 points
        List<double[]> pts = new ArrayList<>();
        for (byte[] f : f17) { int n = ProAnalysis.u16(f, 32); if (n < 1 || n > 100 || 34 + 2 * n > f.length - 4) continue; double t = ProAnalysis.u32(f, 15) + ProAnalysis.u16(f, 19) / 32768.0; for (int k = 0; k < n; k++) pts.add(new double[] { t + k * 0.01, (short) ((f[34 + 2 * k] & 0xFF) | (f[35 + 2 * k] << 8)) }); }
        // lag scan: R17(t) against R16f(t - lag)
        double best = -2, bestLag = 0;
        for (int lagMs = -1000; lagMs <= 1000; lagMs += 2) { double c = corrAt(pts, segY, segT0, segQ, lagMs / 1000.0, null); if (c > best) { best = c; bestLag = lagMs; } }
        double[] g = new double[2]; double c = corrAt(pts, segY, segT0, segQ, bestLag / 1000.0, g);
        if (g[1] < 20 * 100) { m.put("r17_r16_skipped", "q3_overlap_" + (int) (g[1] / 100) + "_s"); return m; }
        m.put("r17_r16_gain", String.format(Locale.US, "%.4f", g[0]));
        m.put("r17_r16_corr", String.format(Locale.US, "%.4f", c));
        m.put("r17_lag_ms", String.format(Locale.US, "%.0f", bestLag));
        m.put("r17_r16_sec", String.format(Locale.US, "%.0f", g[1] / 100));
        return m;
    }

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
