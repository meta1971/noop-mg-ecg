package com.noop.mgecg;

// FILE VERSION 0.39.0 (3 Nov 2026): the strap's own beat analysis (events 118 / 119, R16 bytes 23 and 29-30) beside our numbers, and the verdict check
// (SNR, max-amplitude ratio, dominant-template share). Decode log sections 102.1-102.3. The maths is pure Java so it can be tested off the phone.

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class StrapBeatCheck {
    private StrapBeatCheck() {}

    static final int FS = 500;
    /** The first live sample is 1.01 s after the stored-record start (decode log 50.4 / 102.1). */
    static final int ANCHOR_SAMPLES = 505;
    /** The strap's amplitude statistics stop about 29.1 s after B (N rule, 102.1). */
    static final double WINDOW_S = 29.1;

    static final class Ev118 { long ts; long a, b; int c, d, e; long n, n1, n2; boolean noBeats; }
    static final class Ev119 { long ts; long n, n1, n2; double q0, q1, q2, q3; }

    static final class Result {
        Ev118 e118; Ev119 e119;
        int strapRmssd = -1, strapResult;
        boolean windowFromB;                      // true = our numbers use the strap's own window (B to B + 29.1 s)
        int beats;                                 // our beats used
        double ourMedianUv = Double.NaN, ourMaxUv = Double.NaN, ourMinUv = Double.NaN;
        double maxRatio = Double.NaN;              // max per-beat amplitude / median
        double templateShare = Double.NaN;         // beats correlating > 0.9 with the median beat
        double snrDb = Double.NaN;                 // 5-25 Hz over 40-100 Hz, RMS ratio in dB
    }

    // ------------------------------------------------------------------ events (pure parse)

    static Ev118 parse118(long ts, byte[] in) {
        if (in == null || in.length < 31 || in[0] != 2) return null;
        Ev118 e = new Ev118(); e.ts = ts;
        e.a = StrapEvents.u32(in, 5); e.b = StrapEvents.u32(in, 9);
        e.c = StrapEvents.u16(in, 13); e.d = StrapEvents.u16(in, 15); e.e = StrapEvents.u16(in, 17);
        e.n = StrapEvents.u32(in, 19); e.n1 = StrapEvents.u32(in, 23); e.n2 = StrapEvents.u32(in, 27);
        e.noBeats = e.a == 0xFFFFFFFFL && e.b == 0xFFFFFFFFL;
        return e;
    }

    static Ev119 parse119(long ts, byte[] in) {
        if (in == null || in.length < 37 || in[0] != 1) return null;
        Ev119 e = new Ev119(); e.ts = ts;
        e.n = StrapEvents.u32(in, 1); e.n1 = StrapEvents.u32(in, 5); e.n2 = StrapEvents.u32(in, 9);
        e.q0 = StrapEvents.f32(in, 21); e.q1 = StrapEvents.f32(in, 25); e.q2 = StrapEvents.f32(in, 29); e.q3 = StrapEvents.f32(in, 33);
        return e;
    }

    // ------------------------------------------------------------------ entry (reads the events; never throws)

    static Result compute(Context c, EcgR16Analyzer.Strip s) {
        Result r = new Result();
        if (s == null) return r;
        r.strapRmssd = s.strapRmssd; r.strapResult = s.strapResult;
        try {
            if (c != null && s.startUnix > 0) {
                long secs = s.rawUv.length / FS;
                long from = s.startUnix, to = s.startUnix + secs + 120;
                for (StrapEvents.Raw e : StrapEvents.raw(c, 118, from, to, 20)) { Ev118 p = parse118(e.ts, e.in); if (p != null) { r.e118 = p; break; } }
                if (r.e118 != null) {
                    for (StrapEvents.Raw e : StrapEvents.raw(c, 119, r.e118.ts - 2, r.e118.ts + 2, 5)) { Ev119 p = parse119(e.ts, e.in); if (p != null) { r.e119 = p; break; } }
                } else {
                    for (StrapEvents.Raw e : StrapEvents.raw(c, 119, from, to, 20)) { Ev119 p = parse119(e.ts, e.in); if (p != null) { r.e119 = p; break; } }
                }
            }
        } catch (Throwable ignored) { }
        try { measure(r, s); } catch (Throwable ignored) { }
        return r;
    }

    // ------------------------------------------------------------------ our side (pure)

    static void measure(Result r, EcgR16Analyzer.Strip s) {
        double[] raw = s.rawUv;
        int n = raw.length;
        if (n < FS || s.beats.length == 0) return;
        int w0 = 0, w1 = n;
        if (r.e118 != null && !r.e118.noBeats && r.e118.b < 120000L) {
            w0 = s.firstSampleIdx + ANCHOR_SAMPLES + (int) (r.e118.b / 2);
            w1 = Math.min(n, w0 + (int) Math.round(WINDOW_S * FS));
            r.windowFromB = true;
        }
        double[] wide = runs(raw, ProDsp.bandPass(0.5, 100, FS), null);
        double[] sig = runs(raw, ProDsp.bandPass(5, 25, FS), null);
        double[] hf = runs(raw, ProDsp.bandPass(40, 100, FS), new double[][] { ProDsp.notch(50, 30, FS) });

        List<Double> amps = new ArrayList<>();
        List<double[]> beats = new ArrayList<>();
        final int pre = 20, post = 15, tPre = 100, tPost = 200;             // -40..+30 ms; template -200..+400 ms
        for (int b : s.beats) {
            if (b < w0 - 10 || b >= w1) continue;
            if (b - tPre < 0 || b + tPost >= n) continue;
            double mx = -1e18, mn = 1e18; boolean ok = true;
            for (int k = b - pre; k <= b + post; k++) { double v = wide[k]; if (Double.isNaN(v)) { ok = false; break; } mx = Math.max(mx, v); mn = Math.min(mn, v); }
            if (!ok) continue;
            amps.add(mx - mn);
            double[] t = new double[tPre + tPost + 1]; boolean tok = true;
            for (int k = 0; k < t.length; k++) { t[k] = wide[b - tPre + k]; if (Double.isNaN(t[k])) { tok = false; break; } }
            if (tok) beats.add(t);
        }
        r.beats = amps.size();
        if (amps.size() >= 3) {
            double[] a = new double[amps.size()]; for (int i = 0; i < a.length; i++) a[i] = amps.get(i);
            double[] srt = a.clone(); Arrays.sort(srt);
            r.ourMedianUv = ProDsp.median(a); r.ourMaxUv = srt[srt.length - 1]; r.ourMinUv = srt[0];
            r.maxRatio = r.ourMaxUv / r.ourMedianUv;
        }
        if (beats.size() >= 3) {
            int len = beats.get(0).length; double[] med = new double[len]; double[] col = new double[beats.size()];
            for (int k = 0; k < len; k++) { for (int i = 0; i < col.length; i++) col[i] = beats.get(i)[k]; med[k] = ProDsp.median(col); }
            int good = 0; for (double[] t : beats) if (ProDsp.corr(t, med) > 0.9) good++;
            r.templateShare = good / (double) beats.size();
        }
        double ss = 0, sh = 0; int cnt = 0;
        for (int k = Math.max(0, w0); k < w1; k++) { if (Double.isNaN(sig[k]) || Double.isNaN(hf[k])) continue; ss += sig[k] * sig[k]; sh += hf[k] * hf[k]; cnt++; }
        if (cnt >= FS && sh > 0) r.snrDb = 10 * Math.log10(ss / sh);
    }

    /** Zero-phase filter over each unbroken run of at least one second; NaN elsewhere. */
    static double[] runs(double[] x, double[][] chain, double[][] extra) {
        int n = x.length; double[] y = new double[n]; Arrays.fill(y, Double.NaN);
        int i = 0;
        while (i < n) {
            if (Double.isNaN(x[i])) { i++; continue; }
            int j = i; while (j < n && !Double.isNaN(x[j])) j++;
            if (j - i >= FS) {
                double[] seg = Arrays.copyOfRange(x, i, j);
                double[] z = extra == null ? ProDsp.filtfilt(seg, chain) : ProDsp.filtfilt(seg, chain, extra);
                System.arraycopy(z, 0, y, i, z.length);
            }
            i = j;
        }
        return y;
    }

    // ------------------------------------------------------------------ text (pure)

    static String resultName(int code) {
        switch (code) {
            case 0: return "no verdict yet";
            case 1: return "1 sinus rhythm";
            case 2: return "2 unreadable";
            case 6: return "6 inconclusive";
            case 3: case 4: case 5: return code + " (never seen; HeartKey classes 3 / 4 / 5 = bradycardia / AF / tachycardia, INFERRED)";
            default: return String.valueOf(code);
        }
    }

    static String templateWord(double share) {
        if (Double.isNaN(share)) return "";
        return share >= 0.85 ? "like readings the strap called sinus (about 0.9+)" : share >= 0.4 ? "like readings the strap called inconclusive (about 0.6)" : "like readings the strap called unreadable (about 0.2)";
    }

    /** Rows {label, value} for the report. */
    static List<String[]> rows(Result r) {
        List<String[]> o = new ArrayList<>();
        Locale L = Locale.UK;
        o.add(new String[] { "Strap result (byte 23)", resultName(r.strapResult) });
        if (r.e118 == null) o.add(new String[] { "Event 118", "not received (reading shorter than the strap's 30 s window, or the app was not connected when it arrived)" });
        else if (r.e118.noBeats) o.add(new String[] { "Event 118", "strap found no beats (A = B = FFFFFFFF, N = " + r.e118.n + ")" });
        else {
            o.add(new String[] { "Strap beats N / n1 / n2", r.e118.n + " / " + r.e118.n1 + " / " + r.e118.n2 + "  (n1, n2 acceptance rule OPEN)" });
            o.add(new String[] { "First accepted beat B", String.format(L, "%.2f s after the first sample (next A %.2f s)", r.e118.b / 1000.0, r.e118.a / 1000.0) });
            o.add(new String[] { "Strap amplitude E / C / D", r.e118.e + " / " + r.e118.c + " / " + r.e118.d + "  (median / minimum / maximum per-beat QRS)" });
        }
        if (!Double.isNaN(r.ourMedianUv))
            o.add(new String[] { "Ours, 0.5-100 Hz, -40..+30 ms", String.format(L, "median %.0f uV, min %.0f, max %.0f (%d beats, %s)%s", r.ourMedianUv, r.ourMinUv, r.ourMaxUv, r.beats,
                    r.windowFromB ? "B to B + 29.1 s" : "whole reading",
                    r.e118 != null && !r.e118.noBeats && r.e118.e > 0 ? String.format(L, "; E / ours = %.2f (1.02-1.03 on 34 readings)", r.e118.e / r.ourMedianUv) : "") });
        o.add(new String[] { "Strap HRV (bytes 29-30, RMSSD)", r.strapRmssd > 0 ? r.strapRmssd + " ms" : "not released (65535; it comes about 40 beats in)" });
        if (r.e119 != null) o.add(new String[] { "Event 119 quality 3 share", String.format(L, "%.1f %% of the first 30 s (normal about 67-73 %%)", r.e119.q3) });
        return o;
    }

    /** Rows {label, value} for the verdict check. */
    static List<String[]> checkRows(Result r) {
        List<String[]> o = new ArrayList<>();
        Locale L = Locale.UK;
        if (!Double.isNaN(r.snrDb)) o.add(new String[] { "Our SNR", String.format(L, "%.1f dB (5-25 Hz over 40-100 Hz, 50 Hz notched); lower = noisier", r.snrDb) });
        if (!Double.isNaN(r.maxRatio)) o.add(new String[] { "Max-amplitude ratio", String.format(L, "%.2f%s", r.maxRatio, r.maxRatio >= 1.25 ? "  FLAG: at or above about 1.25, where the strap tends to say inconclusive (6)" : "  (below the 1.25 flag)") });
        if (!Double.isNaN(r.templateShare)) o.add(new String[] { "Dominant-template share", String.format(L, "%.2f, %s", r.templateShare, templateWord(r.templateShare)) });
        if (o.isEmpty()) o.add(new String[] { "Verdict check", "too few clean beats to check" });
        return o;
    }
}
