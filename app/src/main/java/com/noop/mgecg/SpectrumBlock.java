package com.noop.mgecg;

// FILE VERSION 0.51.0 (10 Nov 2026): noise spectrum of one reading (build brief 0.51.0 item 3). Pure Java (no Android), tested off the phone on synthetic frames.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Welch spectrum of the 500 Hz R16 stream at the DESIGN scale (ProAnalysis.UV, 0.0621 uV per count; this class only reports, it changes nothing).
 * Usable second: 500 samples, every sample lead-on, no fast-recovery bit, no rail (|counts| under 126,900), strap quality 3 (per-reading mode), at least 13 s
 * from the first frame, not from 0.5 s before to 4 s after a step (ProMcr.steps on the raw trace), the frame counter contiguous, and both neighbours usable
 * by the same rules. Windows: 500 samples (1 Hz bins), hop 250, Hann, mean removed per window, one-sided PSD = 2 |X|^2 / (fs x sum w^2), averaged over every
 * window that lies wholly inside a run of usable seconds.
 * Per-reading mode subtracts the reading's own mean beat at every detected beat before the spectrum (0.5 Hz high-pass first), so the bands below 40 Hz
 * hold the noise between and under the beats rather than the QRS power; psd_full_d10_40 keeps the band without the subtraction. The sim / floor mode does
 * no subtraction and no quality-3 rule (a resistor gives the strap no beats).
 */
final class SpectrumBlock {
    private SpectrumBlock() {}

    static final int FS = 500, N = 500, HOP = 250, NB = N / 2 + 1, RAIL = 126900;
    static final double SPEC_10_40 = 0.095;            // data sheet 0.6 uV RMS over 0.05-40 Hz = 0.095 uV/rtHz

    static final class Out {
        int usableSec, windows, beatsSub = -1;
        String skipped;
        double[] psd, psdFull;                          // uV^2 / Hz, bins 0..250 Hz
        final Map<String, String> notes = new LinkedHashMap<>();
    }

    /** The R16 frames (1,584 B, CRC good) of a live raw bin, in file order. */
    static List<byte[]> r16(byte[] bin) {
        List<byte[]> out = new ArrayList<>(); if (bin == null) return out;
        for (byte[] f : ProAnalysis.frames(bin)) if (f.length == 1584 && ProAnalysis.crcOk(f)) out.add(f);
        return out;
    }

    static int counts(byte[] f, int k) { int b0 = f[34 + 3 * k] & 0xFF, b1 = f[35 + 3 * k] & 0xFF, b2 = f[36 + 3 * k] & 0xFF; int r = ((b0 & 3) << 16) | (b1 << 8) | b2; if ((r & 0x20000) != 0) r -= 0x40000; return r; }
    static boolean sampleOk(byte[] f, int k) { int b0 = f[34 + 3 * k] & 0xFF; return (b0 & 0x80) != 0 && (b0 & 0x40) == 0 && Math.abs(counts(f, k)) < RAIL; }

    /** Per-reading spectrum (quality 3, beat subtraction, at least 20 usable seconds). */
    static Out reading(List<byte[]> fr) { return analyse(fr, null, 20, "psd_", true, true); }

    /**
     * allow: optional per-second mask (frame index) that is ANDed with the usability rules; minSec: fewer usable seconds gives skipped; prefix for the note keys
     * (psd_ or sim_floor_); q3: require strap quality 3; subtract: remove the mean beat first.
     */
    static Out analyse(List<byte[]> fr, boolean[] allow, int minSec, String prefix, boolean q3, boolean subtract) {
        Out o = new Out();
        int S = fr == null ? 0 : fr.size();
        if (S == 0) { o.skipped = "no_frames"; o.notes.put(prefix + "skipped", o.skipped); return o; }
        double[] x = new double[S * 500]; boolean[] base = new boolean[S], fin = new boolean[S]; long[] ctr = new long[S];
        for (int s = 0; s < S; s++) {
            byte[] f = fr.get(s); ctr[s] = ProAnalysis.u32(f, 11);
            int n = ProAnalysis.u16(f, 32); boolean ok = f.length == 1584 && n == 500;
            for (int k = 0; k < 500; k++) {
                if (k < n && sampleOk(f, k)) x[s * 500 + k] = counts(f, k) * ProAnalysis.uv(); else { x[s * 500 + k] = Double.NaN; ok = false; }
            }
            fin[s] = ok;
            base[s] = ok && s >= 13 && (!q3 || (f[21] & 0xFF) == 3) && (allow == null || (s < allow.length && allow[s]));
        }
        boolean[] cont = new boolean[S]; for (int s = 1; s < S; s++) cont[s] = ctr[s] == ctr[s - 1] + 1;     // cont[s]: second s follows s-1 without a gap
        // steps on every finite contiguous stretch
        boolean[] dist = new boolean[S];
        for (int s = 0; s < S; ) {
            if (!fin[s]) { s++; continue; }
            int e = s + 1; while (e < S && fin[e] && cont[e]) e++;
            double[] seg = Arrays.copyOfRange(x, s * 500, e * 500);
            int[] st = ProMcr.steps(seg);
            for (int c : st) { int a = (int) Math.floor((c - 250) / 500.0), b = (int) Math.floor((c + 2000) / 500.0); for (int k = Math.max(0, a); k <= Math.min(e - s - 1, b); k++) dist[s + k] = true; }
            s = e;
        }
        boolean[] use = new boolean[S];
        for (int s = 0; s < S; s++) {
            boolean b = base[s] && !dist[s];
            boolean l = s > 0 && cont[s] && base[s - 1] && !dist[s - 1], r = s + 1 < S && cont[s + 1] && base[s + 1] && !dist[s + 1];
            use[s] = b && l && r;
        }
        int nUse = 0; for (boolean u : use) if (u) nUse++;
        o.usableSec = nUse; o.notes.put(prefix + "sec", String.valueOf(nUse));
        if (nUse < minSec) { o.skipped = "usable_" + nUse + "_of_" + S + "_s"; o.notes.put(prefix + "skipped", o.skipped); return o; }
        // runs of usable seconds
        List<int[]> runs = new ArrayList<>();
        for (int s = 0; s < S; ) { if (!use[s]) { s++; continue; } int e = s + 1; while (e < S && use[e] && cont[e]) e++; runs.add(new int[] { s, e }); s = e; }
        List<double[]> segs = new ArrayList<>(), full = new ArrayList<>();
        for (int[] r : runs) full.add(Arrays.copyOfRange(x, r[0] * 500, r[1] * 500));
        if (subtract) {
            double[][] HP = ProDsp.butterHigh2(0.5, FS);
            List<double[]> hp = new ArrayList<>(); for (double[] sgm : full) hp.add(sgm.length >= 1500 ? ProDsp.filtfilt(sgm, HP) : demean(sgm));
            int sub = subtractBeats(hp);
            o.beatsSub = sub; segs = hp;
            o.psdFull = welch(full);
        } else segs = full;
        o.psd = welch(segs);
        int w = 0; for (double[] sgm : segs) if (sgm.length >= N) w += (sgm.length - N) / HOP + 1;
        o.windows = w;
        double[] p = o.psd;
        o.notes.put(prefix + "d2_7", f2(dens(p, 2, 7)));
        o.notes.put(prefix + "d10_40", f2(dens(p, 10, 40)));
        o.notes.put(prefix + "d60_100", f2(dens(p, 60, 100)));
        o.notes.put(prefix + "d100_150", f2(dens(p, 100, 150)));
        o.notes.put(prefix + "d150_200", f2(dens(p, 150, 200)));
        o.notes.put(prefix + "d200_240", f2(dens(p, 200, 240)));
        o.notes.put(prefix + "flat_10_150", String.format(Locale.US, "%.1f", flatnessDb(p)));
        o.notes.put(prefix.equals("psd_") ? "mains50_uV" : prefix + "mains50_uV", f2(mains50(p)));
        o.notes.put(prefix.equals("psd_") ? "spur_hz" : prefix + "spur_hz", spurs(p));
        o.notes.put(prefix + "d10_40_over_spec", String.format(Locale.US, "%.1f", dens(p, 10, 40) / SPEC_10_40));
        if (o.psdFull != null) { o.notes.put(prefix + "full_d10_40", f2(dens(o.psdFull, 10, 40))); o.notes.put(prefix + "beats_sub", String.valueOf(o.beatsSub)); }
        return o;
    }

    private static double[] demean(double[] a) { double m = 0; for (double v : a) m += v; m /= a.length; double[] r = new double[a.length]; for (int i = 0; i < a.length; i++) r[i] = a[i] - m; return r; }

    /** Subtracts the pooled mean beat (-200..+450 ms, 20 ms cosine edges, sub-sample apex alignment, scaled per beat) at every beat found by ProAnalysis.detectR; returns the beats used, 0 when there is no beat. Changes the arrays in place. */
    static int subtractBeats(List<double[]> segs) {
        final int A = 100, B = 225, L = A + B + 1;
        List<double[]> at = new ArrayList<>(); double[] sum = new double[L]; int n = 0;      // {segment, apex position with the sub-sample part}
        for (int si = 0; si < segs.size(); si++) {
            double[] y = segs.get(si); if (y.length < 8 * FS) continue;
            int[] pk;
            try { pk = ProAnalysis.detectR(y); } catch (Throwable e) { continue; }
            for (int p : pk) {
                int c = p; for (int k = Math.max(1, p - 10); k <= Math.min(y.length - 2, p + 10); k++) if (Math.abs(y[k]) > Math.abs(y[c])) c = k;
                if (c - A - 1 < 0 || c + B + 1 >= y.length) continue;
                double den = y[c - 1] - 2 * y[c] + y[c + 1], d = den != 0 ? 0.5 * (y[c - 1] - y[c + 1]) / den : 0; if (Math.abs(d) > 0.5) d = 0;
                double pos = c + d; at.add(new double[] { si, pos }); for (int k = 0; k < L; k++) sum[k] += lin(y, pos - A + k); n++;
            }
        }
        if (n < 20) return 0;
        double[] t = new double[L]; double mx = -1e18, mn = 1e18; for (int k = 0; k < L; k++) { t[k] = sum[k] / n; mx = Math.max(mx, t[k]); mn = Math.min(mn, t[k]); }
        if (mx - mn < 150.0) return 0;                                       // no beat shape above the noise: leave the trace alone
        for (int pass = 0; pass < 2; pass++) {                                // refine every beat's position against the template (QRS -40..+60 ms, 0.05-sample steps), then rebuild
            double[] s2 = new double[L];
            for (double[] q : at) {
                double[] y = segs.get((int) q[0]); double best = q[1], bs = Double.MAX_VALUE;
                for (double dd = -2; dd <= 2.0001; dd += 0.05) { double pos = q[1] + dd; if (pos - A - 1 < 0 || pos + B + 1 >= y.length) continue; double yt = 0, tt2 = 0;
                    for (int k = A - 20; k <= A + 30; k++) { double yv = lin(y, pos - A + k); yt += yv * t[k]; tt2 += t[k] * t[k]; }
                    double g = yt / tt2, e = 0; for (int k = A - 20; k <= A + 30; k++) { double r = lin(y, pos - A + k) - g * t[k]; e += r * r; } if (e < bs) { bs = e; best = pos; } }
                q[1] = best; double[] yy = segs.get((int) q[0]); for (int k = 0; k < L; k++) s2[k] += lin(yy, best - A + k);
            }
            for (int k = 0; k < L; k++) t[k] = s2[k] / n;
        }
        double e0 = 0.5 * (t[0] + t[L - 1]); for (int k = 0; k < L; k++) t[k] -= e0;
        int tap = 10; for (int k = 0; k < tap; k++) { double g = 0.5 - 0.5 * Math.cos(Math.PI * k / tap); t[k] *= g; t[L - 1 - k] *= g; }
        for (double[] q : at) {
            double[] y = segs.get((int) q[0]); double pos = q[1]; int i0 = (int) Math.ceil(pos - A), i1 = (int) Math.floor(pos + B);
            double yt = 0, tt = 0; for (int i = i0; i <= i1; i++) { double tv = lin(t, i - (pos - A)); yt += y[i] * tv; tt += tv * tv; }
            double g = tt > 0 ? Math.max(0.7, Math.min(1.3, yt / tt)) : 1;          // per-beat height (least squares, 0.7-1.3)
            for (int i = i0; i <= i1; i++) y[i] -= g * lin(t, i - (pos - A));
        }
        return n;
    }
    private static double lin(double[] a, double x) { if (x <= 0) return a[0]; if (x >= a.length - 1) return a[a.length - 1]; int i = (int) x; double f = x - i; return a[i] * (1 - f) + a[i + 1] * f; }

    private static double[] COS, SIN, WIN; private static double U;
    private static synchronized void tables() {
        if (COS != null) return;
        double[] c = new double[N], s = new double[N], w = new double[N]; double u = 0;
        for (int i = 0; i < N; i++) { c[i] = Math.cos(2 * Math.PI * i / N); s[i] = Math.sin(2 * Math.PI * i / N); w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N); u += w[i] * w[i]; }
        WIN = w; U = u; SIN = s; COS = c;
    }

    /** Welch average over all 500-sample windows (hop 250) of the given contiguous stretches. Null when there is no window. */
    static double[] welch(List<double[]> segs) {
        tables();
        double[] acc = new double[NB]; int nw = 0; double[] buf = new double[N];
        for (double[] y : segs) {
            for (int a = 0; a + N <= y.length; a += HOP) {
                double m = 0; for (int i = 0; i < N; i++) m += y[a + i]; m /= N;
                for (int i = 0; i < N; i++) buf[i] = (y[a + i] - m) * WIN[i];
                for (int k = 0; k < NB; k++) {
                    double re = 0, im = 0; int idx = 0;
                    for (int i = 0; i < N; i++) { re += buf[i] * COS[idx]; im -= buf[i] * SIN[idx]; idx += k; if (idx >= N) idx -= N; }
                    double p = (re * re + im * im) / (FS * U); if (k != 0 && k != N / 2) p *= 2; acc[k] += p;
                }
                nw++;
            }
        }
        if (nw == 0) return null;
        for (int k = 0; k < NB; k++) acc[k] /= nw;
        return acc;
    }

    static double dens(double[] p, int lo, int hi) { if (p == null) return Double.NaN; double s = 0; int n = 0; for (int k = lo; k <= hi && k < p.length; k++) { s += p[k]; n++; } return n == 0 ? Double.NaN : Math.sqrt(s / n); }

    private static boolean mainsBin(int k) { int r = k % 50; return k >= 48 && (r <= 2 || r >= 48); }

    /** Arithmetic over geometric mean of the PSD, 10-150 Hz, in dB (white = 0); the mains bins (50 / 100 / 150 Hz +-2) are left out. */
    static double flatnessDb(double[] p) {
        if (p == null) return Double.NaN; double s = 0, l = 0; int n = 0;
        for (int k = 10; k <= 150; k++) { if (mainsBin(k) || p[k] <= 0) continue; s += p[k]; l += Math.log(p[k]); n++; }
        return n == 0 ? Double.NaN : 10 * Math.log10((s / n) / Math.exp(l / n));
    }

    private static double medianOf(List<Double> v) { double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); return ProDsp.median(a); }

    /** Peak amplitude (uV) of the 50 Hz tone: power in 49-51 Hz above the local floor (median of 40-46 and 54-60 Hz, x the 3 bins summed). */
    static double mains50(double[] p) {
        if (p == null) return Double.NaN; List<Double> v = new ArrayList<>();
        for (int k = 40; k <= 46; k++) v.add(p[k]); for (int k = 54; k <= 60; k++) v.add(p[k]);
        double fl = medianOf(v), P = 0; for (int k = 49; k <= 51; k++) P += p[k] - fl;      // a Hann window puts an on-bin tone wholly in 3 bins; 3 bins (not 48-52) cut the noise of the estimate
        return P <= 0 ? 0.0 : Math.sqrt(2 * P);
    }

    /** Up to 5 narrow lines, strongest first, outside 48-52 Hz: bins over 8 x the median of the +-8 neighbouring bins, "hz:uV;..." (uV = peak amplitude from the 3 bins). */
    static String spurs(double[] p) {
        if (p == null) return "";
        List<double[]> hit = new ArrayList<>();
        for (int k = 9; k <= NB - 1 - 8; k++) {
            if (k >= 48 && k <= 52) continue;
            List<Double> v = new ArrayList<>(); for (int j = k - 8; j <= k + 8; j++) if (j != k) v.add(p[j]);
            double med = medianOf(v); if (!(med > 0)) continue;
            if (p[k] > 8 * med && p[k] >= p[k - 1] && p[k] >= p[k + 1]) { double P = 0; for (int j = k - 1; j <= k + 1; j++) P += Math.max(0, p[j] - med); hit.add(new double[] { k, Math.sqrt(2 * P), p[k] / med }); }
        }
        java.util.Collections.sort(hit, (a, b) -> Double.compare(b[2], a[2]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, hit.size()); i++) { if (sb.length() > 0) sb.append(';'); sb.append(String.format(Locale.US, "%.0f:%.2f", hit.get(i)[0], hit.get(i)[1])); }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static String f2(double v) { return Double.isNaN(v) ? "nan" : String.format(Locale.US, "%.2f", v); }

    /** ecg_psd_<start>.csv: freq_hz, psd_uV2_per_Hz (3 significant digits). */
    static String csv(Out o) {
        StringBuilder sb = new StringBuilder("freq_hz,psd_uV2_per_Hz" + (o.psdFull != null ? ",psd_full_uV2_per_Hz" : "") + "\n");
        if (o.psd == null) return sb.toString();
        for (int k = 0; k < NB; k++) { sb.append(k).append(',').append(sig3(o.psd[k])); if (o.psdFull != null) sb.append(',').append(sig3(o.psdFull[k])); sb.append('\n'); }
        return sb.toString();
    }
    static String sig3(double v) { if (v == 0 || Double.isNaN(v)) return "0"; return new java.math.BigDecimal(v).round(new java.math.MathContext(3)).stripTrailingZeros().toPlainString(); }
}
