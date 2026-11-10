package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): the scale-and-corner lab, analysis part (build brief 0.51.0 item 4). Pure Java (no Android), tested off the phone on synthetic frames.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LADDER v3 (player seconds; supersedes sim_gen v2): 0-4 silence; 4-24 DC +0.30 FS; 24-28 zero; 28-48 DC -0.30 FS; 48-52 zero; 52-98 six 10 Hz blocks of 6 s
 * at 0.05 / 0.10 / 0.20 / 0.40 / 0.60 / 0.80 FS with 2 s gaps; 102-156 seven 0.40 FS tones of 6 s (0.5, 1, 2, 5, 10, 20, 40 Hz) with 2 s gaps; 156-168 silence.
 * 2 ms raised-cosine edges. 168 s.
 * MODE A (audio ladder through the divider): t0 = first DC edge in R16; coupling from the meter (V at 0:22 over V at 0:06 at least 0.95 = DC-coupled output);
 * K (uV at the strap per count) = V6 x 1000 / ((R1 + R2) / R2) / step, step = mean counts 0.2-0.7 s after t0 minus mean 2.0-0.5 s before; K_fit = mean over the four DC edges of
 * y = A exp(-t / tau) + B fitted from 0.1 s (0.3 s when the first 0.3 s holds a flagged sample) to 12 s and taken back to the edge, less what was still
 * decaying from the edge before (the value shown); tau of the +0.30 and the -0.30 block, corner 1 / (2 pi tau);
 * tone gains by lock-in against the 10 Hz 0.40 FS block (phone + strap chain); linearity over the six 10 Hz blocks; floor from the silences (SpectrumBlock).
 * MODE B (cell steps, AC-coupled phone): ON / OFF edges over 1 mV in 150 ms at the design scale, matched to the plan (ON 1:00, 3:00, 5:00, OFF 2:00, 4:00,
 * 6:00 +-15 s), K = median over ON edges of (V_ON - V_OFF from the R2 meter readings) / step (fit), tau from every edge, missing OFF edges listed.
 * MODE C (no source, 180 s): SpectrumBlock floor and a one-line verdict.
 * Everything is a measurement of THIS wiring. Nothing is applied: ProAnalysis.UV stays 0.0621.
 */
final class SimLadder {
    private SimLadder() {}

    static final int FS = 500;
    static final double DESIGN = ProAnalysis.UV;
    static final double LADDER_S = 168.0, DC = 0.30;
    static final double[] LEVELS = { 0.05, 0.10, 0.20, 0.40, 0.60, 0.80 };
    static final double[] TONES = { 0.5, 1, 2, 5, 10, 20, 40 };
    static double levelStart(int i) { return 52 + 8 * i; }
    static double toneStart(int i) { return 102 + 8 * i; }

    private static double edge(double p, double a, double b) {      // raised-cosine envelope of [a, b) with 2 ms edges
        double e = 0.002; if (p < a || p >= b) return 0; if (p < a + e) return 0.5 - 0.5 * Math.cos(Math.PI * (p - a) / e); if (p > b - e) return 0.5 - 0.5 * Math.cos(Math.PI * (b - p) / e); return 1;
    }
    /** Ladder value in full-scale units at player time p (seconds). */
    static double level(double p) {
        double v = DC * edge(p, 4, 24) - DC * edge(p, 28, 48);
        for (int i = 0; i < LEVELS.length; i++) { double a = levelStart(i); if (p >= a && p < a + 6) v += LEVELS[i] * edge(p, a, a + 6) * Math.sin(2 * Math.PI * 10 * (p - a)); }
        for (int i = 0; i < TONES.length; i++) { double a = toneStart(i); if (p >= a && p < a + 6) v += 0.40 * edge(p, a, a + 6) * Math.sin(2 * Math.PI * TONES[i] * (p - a)); }
        return v;
    }
    /** 16-bit mono PCM of the ladder. */
    static short[] pcm(int sr) { int n = (int) Math.round(LADDER_S * sr); short[] s = new short[n]; for (int i = 0; i < n; i++) s[i] = (short) Math.round(Math.max(-1, Math.min(1, level(i / (double) sr))) * 32767); return s; }
    /** A WAV file (RIFF, PCM 16-bit mono). */
    static byte[] wav(int sr) {
        short[] s = pcm(sr); int data = s.length * 2; java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(data);
        for (short v : s) b.putShort(v); return b.array();
    }

    // ------------------------------------------------------------------ the record
    static final class Rec { double[] c; boolean[] bad; double tFirst; int n; }

    /** Counts of every R16 frame on one time axis (frame counter); NaN where a sample is missing, flagged lead-off, fast recovery or at the rail. */
    static Rec rec(List<byte[]> fr) {
        Rec r = new Rec(); if (fr.isEmpty()) { r.c = new double[0]; r.bad = new boolean[0]; return r; }
        long c0 = ProAnalysis.u32(fr.get(0), 11); long cl = c0; for (byte[] f : fr) cl = Math.max(cl, ProAnalysis.u32(f, 11));
        int secs = (int) Math.min(900, cl - c0 + 1); r.n = secs * 500; r.c = new double[r.n]; r.bad = new boolean[r.n]; Arrays.fill(r.c, Double.NaN); Arrays.fill(r.bad, true);
        r.tFirst = ProAnalysis.u32(fr.get(0), 15) + ProAnalysis.u16(fr.get(0), 19) / 32768.0;
        for (byte[] f : fr) {
            long s = ProAnalysis.u32(f, 11) - c0; if (s < 0 || s >= secs) continue; int n = Math.min(500, ProAnalysis.u16(f, 32));
            for (int k = 0; k < n; k++) { int i = (int) s * 500 + k; if (SpectrumBlock.sampleOk(f, k)) { r.c[i] = SpectrumBlock.counts(f, k); r.bad[i] = false; } }
        }
        return r;
    }

    static double meanRange(double[] c, int a, int b) { double s = 0; int n = 0; for (int i = Math.max(0, a); i < Math.min(c.length, b); i++) if (!Double.isNaN(c[i])) { s += c[i]; n++; } return n < (b - a) / 4 ? Double.NaN : s / n; }

    static double[] smooth(double[] c, int w) {
        double[] m = new double[c.length]; double s = 0; int n = 0;
        for (int i = 0; i < c.length; i++) {
            if (!Double.isNaN(c[i])) { s += c[i]; n++; } if (i - w >= 0 && !Double.isNaN(c[i - w])) { s -= c[i - w]; n--; }
            m[i] = n > w / 2 ? s / n : Double.NaN;
        }
        double[] o = new double[c.length]; int h = w / 2; for (int i = 0; i < c.length; i++) o[i] = i + h < c.length ? m[i + h] : Double.NaN; return o;
    }

    /** Sample indices of edges: the w-sample smoothed trace moves by more than thr counts within span samples; each edge = the steepest point; at least gap apart. */
    static List<int[]> edges(double[] c, int from, double thr, int span, int gap) {
        double[] m = smooth(c, 25); List<int[]> out = new ArrayList<>(); int i = Math.max(0, from);
        while (i + span < m.length) {
            double d = m[i + span] - m[i];
            if (Double.isNaN(d) || Math.abs(d) <= thr) { i++; continue; }
            int best = i; double bd = 0; for (int k = i; k < Math.min(m.length - 6, i + span); k++) { double dd = m[k + 5] - m[k]; if (!Double.isNaN(dd) && Math.abs(dd) > bd) { bd = Math.abs(dd); best = k + 2; } }
            out.add(new int[] { best, d > 0 ? 1 : -1 }); i = best + gap;
        }
        return out;
    }

    /** The edge sample within +-40 samples: the largest jump between the 5-sample means just after and just before (the smoothed search is biased early). */
    static int refine(double[] c, int e) {
        int best = e; double bd = -1;
        for (int k = Math.max(8, e - 40); k <= Math.min(c.length - 9, e + 40); k++) { double a = meanRange(c, k, k + 5), b = meanRange(c, k - 5, k); if (Double.isNaN(a) || Double.isNaN(b)) continue; double d = Math.abs(a - b); if (d > bd) { bd = d; best = k; } }
        return best;
    }

    /** Fit start after an edge: 0.1 s when the first 0.3 s has no flagged sample (lead-off, fast recovery, rail), else 0.3 s (the brief's value). */
    static double fitStart(Rec r, int e) { return anyBad(r, e, e + 150) ? 0.3 : 0.1; }

    /** Fit y = A exp(-t / tau) + B on [a, b) samples after edge e (t from e); returns {A, B, tau, rms}. Grid over tau then golden refinement. */
    static double[] expFit(double[] c, int e, double a, double b) {
        int i0 = e + (int) Math.round(a * FS), i1 = Math.min(c.length, e + (int) Math.round(b * FS));
        List<double[]> pts = new ArrayList<>(); for (int i = i0; i < i1; i += 2) if (!Double.isNaN(c[i])) pts.add(new double[] { (i - e) / (double) FS, c[i] });
        if (pts.size() < 50) return null;
        double bestT = 1, bestR = Double.MAX_VALUE;
        for (double lt = Math.log(0.03); lt <= Math.log(3000); lt += 0.05) { double r = fitRes(pts, Math.exp(lt), null); if (r < bestR) { bestR = r; bestT = Math.exp(lt); } }
        double lo = Math.log(bestT) - 0.05, hi = Math.log(bestT) + 0.05;
        for (int it = 0; it < 40; it++) { double m1 = lo + (hi - lo) * 0.382, m2 = lo + (hi - lo) * 0.618; if (fitRes(pts, Math.exp(m1), null) < fitRes(pts, Math.exp(m2), null)) hi = m2; else lo = m1; }
        double tau = Math.exp(0.5 * (lo + hi)); double[] ab = new double[2]; double r = fitRes(pts, tau, ab);
        return new double[] { ab[0], ab[1], tau, Math.sqrt(r / pts.size()) };
    }
    private static double fitRes(List<double[]> pts, double tau, double[] ab) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0; int n = pts.size();
        for (double[] p : pts) { double x = Math.exp(-p[0] / tau); sx += x; sy += p[1]; sxx += x * x; sxy += x * p[1]; }
        double den = n * sxx - sx * sx; double A = Math.abs(den) < 1e-12 ? 0 : (n * sxy - sx * sy) / den, B = (sy - A * sx) / n;
        double r = 0; for (double[] p : pts) { double d = p[1] - (A * Math.exp(-p[0] / tau) + B); r += d * d; }
        if (ab != null) { ab[0] = A; ab[1] = B; } return r;
    }

    /** Lock-in amplitude (counts, peak) of frequency f in [a, b) samples with offset and slope removed; NaN when too few samples. */
    static double lockIn(double[] c, int a, int b, double f) {
        int len = b - a; int cyc = (int) Math.floor(len / (double) FS * f); if (cyc < 1) return Double.NaN; int L = (int) Math.round(cyc / f * FS); b = a + L;
        double[][] X = new double[4][]; List<double[]> rows = new ArrayList<>();
        for (int i = Math.max(0, a); i < Math.min(c.length, b); i++) if (!Double.isNaN(c[i])) { double t = (i - a) / (double) FS; rows.add(new double[] { Math.sin(2 * Math.PI * f * t), Math.cos(2 * Math.PI * f * t), 1, t, c[i] }); }
        if (rows.size() < L / 2) return Double.NaN;
        double[][] M = new double[4][5];
        for (double[] r : rows) for (int p = 0; p < 4; p++) { for (int q = 0; q < 4; q++) M[p][q] += r[p] * r[q]; M[p][4] += r[p] * r[4]; }
        double[] s = solve(M); return s == null ? Double.NaN : Math.hypot(s[0], s[1]);
    }
    static double[] solve(double[][] M) {
        int n = M.length; double[][] a = new double[n][]; for (int i = 0; i < n; i++) a[i] = M[i].clone();
        for (int p = 0; p < n; p++) { int mx = p; for (int r = p + 1; r < n; r++) if (Math.abs(a[r][p]) > Math.abs(a[mx][p])) mx = r; double[] t = a[p]; a[p] = a[mx]; a[mx] = t; if (Math.abs(a[p][p]) < 1e-15) return null;
            for (int r = 0; r < n; r++) if (r != p) { double g = a[r][p] / a[p][p]; for (int q = p; q <= n; q++) a[r][q] -= g * a[p][q]; } }
        double[] x = new double[n]; for (int i = 0; i < n; i++) x[i] = a[i][n] / a[i][i]; return x;
    }

    static boolean anyBad(Rec r, int a, int b) { for (int i = Math.max(0, a); i < Math.min(r.n, b); i++) if (r.bad[i]) return true; return false; }

    // ------------------------------------------------------------------ results
    static final class Result { final Map<String, String> notes = new LinkedHashMap<>(); final List<String> card = new ArrayList<>(); double K = Double.NaN, Kfit = Double.NaN, fc = Double.NaN, lin = Double.NaN; String coupling = ""; }

    static String f(String fmt, double v) { return Double.isNaN(v) ? "nan" : String.format(Locale.US, fmt, v); }

    /** meterMv = readings at player 0:06, 0:15, 0:22 (+0.30 FS block) and 0:40 (-0.30 FS block, negative). */
    static Result analyseA(List<byte[]> fr, double[] meterMv, double r1, double r2, double playStartS) {
        Result R = new Result(); Rec r = rec(fr);
        R.notes.put("sim_mode", "A"); R.notes.put("sim_R1", f("%.0f", r1)); R.notes.put("sim_R2", f("%.1f", r2));
        R.notes.put("sim_meter_mV", f("%.2f", meterMv[0]) + ";" + f("%.2f", meterMv[1]) + ";" + f("%.2f", meterMv[2]) + ";" + f("%.2f", meterMv[3]));
        if (!Double.isNaN(playStartS)) R.notes.put("sim_play_start_s", f("%.2f", playStartS));
        double ratio = (r1 + r2) / r2;
        List<int[]> ed = edges(r.c, 2 * FS, 200.0 / DESIGN, 50, FS);
        if (ed.isEmpty()) { R.notes.put("sim_skipped", "no_dc_edge"); R.card.add("No step of 0.2 mV or more was found: the ladder did not reach the strap (check the plug, the volume and the wiring)."); return R; }
        int t0 = -1, pol = 1;                                                     // the first edge that has the opposite edge of the block end 20 s later
        for (int[] e : ed) { for (int[] g : ed) if (g[1] == -e[1] && Math.abs(g[0] - e[0] - 20 * FS) < FS / 2) { t0 = e[0]; pol = e[1]; break; } if (t0 >= 0) break; }
        if (t0 < 0) for (int[] e : ed) if (e[0] >= 6 * FS) { t0 = e[0]; pol = e[1]; break; }
        if (t0 < 0) { t0 = ed.get(0)[0]; pol = ed.get(0)[1]; }
        t0 = refine(r.c, t0);
        R.notes.put("sim_t0_s", f("%.3f", t0 / (double) FS)); R.notes.put("sim_polarity", pol > 0 ? "+" : "-");
        if (!Double.isNaN(playStartS) && playStartS > 1e9) R.notes.put("sim_audio_latency_s", f("%.3f", r.tFirst + t0 / (double) FS - playStartS - 4.0));      // first DC edge minus (play start + 4 s), strap clock
        // (b) coupling
        double v6 = meterMv[0], v22 = meterMv[2];
        boolean dc = v6 != 0 && !Double.isNaN(v6) && !Double.isNaN(v22) && v22 / v6 >= 0.95;
        R.coupling = dc ? "DC" : "AC"; R.notes.put("sim_coupling", R.coupling);
        // (c) K
        double pre = meanRange(r.c, t0 - 1000, t0 - 250), post = meanRange(r.c, t0 + 100, t0 + 350), step = post - pre;
        // the four DC edges: +0.30 on (t0), off (t0 + 20 s), -0.30 on (t0 + 24 s), off (t0 + 44 s); each is fitted from its own start
        int[] eAt = new int[4]; double[] eV = { v6, v22, meterMv[3], meterMv[3] }; int[] eOff = { 0, 20, 24, 44 };
        for (int j = 0; j < 4; j++) { int want = t0 + eOff[j] * FS; eAt[j] = j == 0 ? t0 : -1; if (j > 0) for (int[] e : ed) if (Math.abs(e[0] - want) < FS / 2) { eAt[j] = refine(r.c, e[0]); break; } }
        double[] taus = new double[4], ks = new double[4]; Arrays.fill(taus, Double.NaN); Arrays.fill(ks, Double.NaN);
        for (int j = 0; j < 4; j++) {
            if (eAt[j] < 0) continue; double[] fp = expFit(r.c, eAt[j], fitStart(r, eAt[j]), j % 2 == 0 ? 12.0 : 3.8); if (fp == null) continue;      // the off edges have only 4 s before the next block
            taus[j] = fp[2]; double pj = meanRange(r.c, eAt[j] - 1000, eAt[j] - 250);
            double tau = fp[2], avg = tau > 500 ? 1.0 : tau / 1.5 * (Math.exp(2.0 / tau) - Math.exp(0.5 / tau));     // mean of exp(u / tau) over u in [0.5, 2] s before the edge
            double S = fp[0] - (pj - fp[1]) / avg;                                // step = fitted amplitude minus what was still decaying from the edge before
            if (!Double.isNaN(eV[j]) && eV[j] != 0 && S != 0) ks[j] = Math.abs(eV[j] * 1000.0 / ratio / S);
        }
        R.notes.put("sim_K_edges", f("%.5f", ks[0]) + ";" + f("%.5f", ks[1]) + ";" + f("%.5f", ks[2]) + ";" + f("%.5f", ks[3]));
        if (dc) {
            R.K = Math.abs(v6 * 1000.0 / ratio / step); double sk = 0; int nk = 0; for (double k : ks) if (!Double.isNaN(k)) { sk += k; nk++; } R.Kfit = nk == 0 ? Double.NaN : sk / nk;
            R.notes.put("sim_K_uV_per_count", f("%.5f", R.K)); R.notes.put("sim_K_fit", f("%.5f", R.Kfit)); R.notes.put("sim_K_fit_n", String.valueOf(nk));
        } else R.notes.put("sim_K_uV_per_count", "not_reported_AC_output");
        // (d) corner from the +0.30 and the -0.30 block
        double tp = taus[0], tq = taus[2];
        R.notes.put("sim_tau_pos_s", f("%.3f", tp)); R.notes.put("sim_tau_neg_s", f("%.3f", tq));
        double tm = !Double.isNaN(tp) && !Double.isNaN(tq) ? Math.sqrt(tp * tq) : !Double.isNaN(tp) ? tp : tq;
        R.fc = dc && tm > 0 && tm < 1000 ? 1.0 / (2 * Math.PI * tm) : Double.NaN; R.notes.put("sim_corner_hz", !dc ? "not_reported_AC_output" : tm >= 1000 ? "under_0.0002" : f("%.4f", R.fc));      // an AC-coupled phone adds its own decay to every edge
        // (e) tones against the 10 Hz 0.40 FS block (player time p -> sample t0 + (p - 4) s)
        int ref0 = t0 + (int) Math.round((levelStart(3) - 4) * FS); double ref = lockIn(r.c, ref0 + FS / 5, ref0 + 29 * FS / 5, 10);
        String[] key = { "0p5", "1", "2", "5", "10", "20", "40" };
        for (int i = 0; i < TONES.length; i++) { int a = t0 + (int) Math.round((toneStart(i) - 4) * FS); double g = lockIn(r.c, a + FS / 5, a + 29 * FS / 5, TONES[i]) / ref; if (i != 4) R.notes.put("sim_gain_" + key[i], f("%.3f", g)); }
        // (f) linearity
        double[] amp = new double[LEVELS.length]; boolean sat = false;
        for (int i = 0; i < LEVELS.length; i++) { int a = t0 + (int) Math.round((levelStart(i) - 4) * FS); amp[i] = lockIn(r.c, a + FS / 5, a + 29 * FS / 5, 10); if (anyBad(r, a, a + 6 * FS)) sat = true; }
        double sxy = 0, sxx = 0; for (int i = 0; i < LEVELS.length; i++) if (!Double.isNaN(amp[i])) { sxy += amp[i] * LEVELS[i]; sxx += LEVELS[i] * LEVELS[i]; }
        double slope = sxy / sxx, md = 0; for (int i = 0; i < LEVELS.length; i++) if (!Double.isNaN(amp[i])) md = Math.max(md, Math.abs(amp[i] - slope * LEVELS[i]) / (slope * LEVELS[i]) * 100);
        R.lin = md; R.notes.put("sim_linearity_pct", f("%.2f", md)); if (sat) R.notes.put("sim_saturation", "1");
        R.notes.put("sim_amp10_counts", f("%.1f", amp[0]) + ";" + f("%.1f", amp[1]) + ";" + f("%.1f", amp[2]) + ";" + f("%.1f", amp[3]) + ";" + f("%.1f", amp[4]) + ";" + f("%.1f", amp[5]));
        // (g) floor: the silence after the tones (player 157-168 and the 5 s after) and the 98-102 gap
        int secs = r.n / 500; boolean[] allow = new boolean[secs];
        for (int s = 0; s < secs; s++) { double p = (s * FS - t0) / (double) FS + 4; allow[s] = (p >= 157.5 && p + 1 <= 173) || (p >= 98.5 && p + 1 <= 101.5); }
        SpectrumBlock.Out so = SpectrumBlock.analyse(fr, allow, 8, "sim_floor_", false, false); R.notes.putAll(so.notes);
        // card
        if (dc) R.card.add(String.format(Locale.UK, "Scale %.4f uV per count (design 0.0621): %+.1f %%", R.Kfit, 100 * (R.Kfit / DESIGN - 1)));
        else R.card.add("AC-coupled output (the meter read under 95 % at 0:22 against 0:06): the scale is not reported. Use mode B.");
        R.card.add(!dc ? "High-pass corner: not reported (the phone output adds its own decay)" : Double.isNaN(R.fc) ? "High-pass corner: none found (DC-coupled within 12 s)" : String.format(Locale.UK, "High-pass corner %.3f Hz (tau %.2f s)", R.fc, tm));
        R.card.add(String.format(Locale.UK, "Linearity: within %.1f %% over 0.05-0.80 FS%s", md, sat ? " (some samples saturated)" : ""));
        String fl = so.notes.get("sim_floor_d10_40"); R.card.add(fl == null || so.psd == null ? "Floor: too little quiet signal (" + so.skipped + ")" : "Floor " + fl + " uV/rtHz at 10-40 Hz");
        R.card.add("Tone gains are the whole chain (phone + strap): meaningful only above the phone's own high-pass.");
        R.card.add("These are measurements of THIS wiring. They are not applied.");
        return R;
    }

    /** Mode B: cellMv (2000m range), r2Mv = the seven R2 readings in mV (segments 0-1 ... 6-7 min). */
    static Result analyseB(List<byte[]> fr, double cellMv, double[] r2Mv) {
        Result R = new Result(); Rec r = rec(fr);
        R.notes.put("sim_mode", "B"); R.notes.put("sim_cell_mV", f("%.1f", cellMv));
        StringBuilder rs = new StringBuilder(); for (double v : r2Mv) rs.append(rs.length() > 0 ? ";" : "").append(f("%.3f", v)); R.notes.put("sim_r2_mV", rs.toString());
        List<int[]> ed = edges(r.c, 2 * FS, 1000.0 / DESIGN, 75, FS);
        int[] want = { 60, 120, 180, 240, 300, 360 }; int[] at = new int[6]; Arrays.fill(at, -1);
        for (int j = 0; j < 6; j++) { int best = -1; double bd = 15.0; for (int[] e : ed) { double d = Math.abs(e[0] / (double) FS - want[j]); if (d < bd) { bd = d; best = e[0]; } } at[j] = best < 0 ? -1 : refine(r.c, best); }
        List<Double> ks = new ArrayList<>(), tOn = new ArrayList<>(), tOff = new ArrayList<>(); StringBuilder miss = new StringBuilder();
        for (int j = 0; j < 6; j++) {
            boolean on = j % 2 == 0; if (at[j] < 0) { if (!on) miss.append(miss.length() > 0 ? ";" : "").append(want[j]); continue; }
            double[] fp = expFit(r.c, at[j], fitStart(r, at[j]), 12.0); if (fp == null) continue;
            if (on) tOn.add(fp[2]); else tOff.add(fp[2]);
            if (on) { int seg = j + 1; double pre = meanRange(r.c, at[j] - 1000, at[j] - 250); double tau = fp[2], avg = tau > 500 ? 1.0 : tau / 1.5 * (Math.exp(2.0 / tau) - Math.exp(0.5 / tau)); double step = fp[0] - (pre - fp[1]) / avg; double dv = (r2Mv[seg] - r2Mv[seg - 1]) * 1000.0; if (step != 0) ks.add(Math.abs(dv / step)); }
        }
        int nOn = 0, nOff = 0; for (int j = 0; j < 6; j++) if (at[j] >= 0) { if (j % 2 == 0) nOn++; else nOff++; }
        R.notes.put("sim_edges", "on " + nOn + "/3 off " + nOff + "/3"); R.notes.put("sim_missing_off", miss.length() == 0 ? "none" : miss.toString());
        if (!ks.isEmpty()) { double[] k = toArr(ks); R.Kfit = ProDsp.median(k); R.notes.put("sim_K_uV_per_count", f("%.5f", R.Kfit)); R.notes.put("sim_K_n", String.valueOf(k.length)); }
        double tauOn = tOn.isEmpty() ? Double.NaN : ProDsp.median(toArr(tOn)), tauOff = tOff.isEmpty() ? Double.NaN : ProDsp.median(toArr(tOff));
        R.notes.put("sim_tau_on_s", f("%.3f", tauOn)); R.notes.put("sim_tau_off_s", f("%.3f", tauOff));
        double tm = !Double.isNaN(tauOn) ? tauOn : tauOff; R.fc = tm > 0 && tm < 1000 ? 1 / (2 * Math.PI * tm) : Double.NaN; R.notes.put("sim_corner_hz", f("%.4f", R.fc));
        R.card.add(Double.isNaN(R.Kfit) ? "Scale: no ON edge with meter readings" : String.format(Locale.UK, "Scale %.4f uV per count (design 0.0621): %+.1f %%  (%d ON edges)", R.Kfit, 100 * (R.Kfit / DESIGN - 1), ks.size()));
        R.card.add(Double.isNaN(R.fc) ? "High-pass corner: not found" : String.format(Locale.UK, "High-pass corner %.3f Hz (tau ON %.2f s, OFF %s)", R.fc, tauOn, Double.isNaN(tauOff) ? "none" : String.format(Locale.UK, "%.2f s", tauOff)));
        R.card.add("Edges found: ON " + nOn + " of 3, OFF " + nOff + " of 3" + (miss.length() > 0 ? " (missing OFF at " + miss.toString().replace(";", ", ") + " s)" : ""));
        R.card.add("These are measurements of THIS wiring. They are not applied.");
        return R;
    }

    /** Mode C: a reading with no source. */
    static Result analyseC(List<byte[]> fr) {
        Result R = new Result(); R.notes.put("sim_mode", "C");
        SpectrumBlock.Out so = SpectrumBlock.analyse(fr, null, 20, "floor_", false, false); R.notes.putAll(so.notes);
        if (so.psd == null) { R.card.add("Floor: too little steady signal (" + so.skipped + ")"); R.notes.put("floor_verdict", "none"); return R; }
        double d = SpectrumBlock.dens(so.psd, 10, 40), fl = SpectrumBlock.flatnessDb(so.psd);
        String v = d < 0.3 ? "body or electrode limited" : (d >= 1.5 && d <= 3.5 && fl < 3) ? "on-device floor" : "mixed";
        R.notes.put("floor_verdict", v.replace(' ', '_'));
        R.card.add(String.format(Locale.UK, "Floor %.2f uV/rtHz at 10-40 Hz, flatness %.1f dB, 50 Hz %s uV", d, fl, so.notes.get("floor_mains50_uV")));
        R.card.add("Verdict: " + v);
        R.card.add("A measurement of THIS set-up (no source). Nothing is applied.");
        return R;
    }

    static double[] toArr(List<Double> l) { double[] a = new double[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }
}
