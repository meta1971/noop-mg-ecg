package com.noop.mgecg;

// FILE VERSION 0.44.0 (7 Nov 2026): the slow-transient counter of decode log 50.6 item 1 (C), written to reading_notes.txt of the Full pull by ReadingPull (no UI).
// A slow transient is a contact event the 8-25 Hz noise measure cannot see (118.2): the 500 Hz trace of every unbroken stretch of at least 1 s is low-passed below 5 Hz
// (zero-phase 2nd-order Butterworth), taken at 50 Hz, the 1.5 s running median is subtracted, and every excursion above 800 uV after record second 15 is one transient
// (an excursion ends when the trace stays under 800 uV for 0.5 s). Record time 0 = the first sample of the first live record. Pure Java (no Android).
// This is a re-implementation of the analysis recipe in decode log 50.4; it has NOT been checked against the 7 Nov raw readings (they are not in this build's test data).

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SlowTransients {
    private SlowTransients() {}

    static final double STEP_UV = 800.0, AFTER_S = 15.0, MED_S = 1.5, END_GAP_S = 0.5, CUT_HZ = 5.0;
    static final int FS = 500, DEC = 10;

    static final class Out { int n; double peakMv; double[] timesS = new double[0], peaksMv = new double[0]; }

    /** Zero-phase 2nd-order Butterworth low-pass (Q 0.7071) at CUT_HZ for a 500 Hz trace; the state starts at the first sample, so a DC offset does not ring. */
    static double[] lowpass(double[] x) {
        int n = x.length; double[] y = new double[n]; if (n == 0) return y;
        double w0 = 2 * Math.PI * CUT_HZ / FS, al = Math.sin(w0) / (2 * Math.sqrt(0.5)), cw = Math.cos(w0), a0 = 1 + al;
        double b0 = (1 - cw) / 2 / a0, b1 = (1 - cw) / a0, b2 = b0, a1 = -2 * cw / a0, a2 = (1 - al) / a0;
        double z1 = (1 - b0) * x[0], z2 = (b2 - a2) * x[0];
        for (int i = 0; i < n; i++) { double o = b0 * x[i] + z1; z1 = b1 * x[i] - a1 * o + z2; z2 = b2 * x[i] - a2 * o; y[i] = o; }
        z1 = (1 - b0) * y[n - 1]; z2 = (b2 - a2) * y[n - 1];
        for (int i = n - 1; i >= 0; i--) { double o = b0 * y[i] + z1; z1 = b1 * y[i] - a1 * o + z2; z2 = b2 * y[i] - a2 * o; y[i] = o; }
        return y;
    }

    /** From the live layout-16 frames of one reading (any order). */
    static Out analyse(List<byte[]> frames) {
        Out out = new Out();
        List<byte[]> recs = new ArrayList<>();
        for (byte[] f : frames) if (f != null && f.length == 1584 && (f[9] & 0xFF) == 16) recs.add(f);
        if (recs.isEmpty()) return out;
        recs.sort((a, b) -> Long.compare(ProAnalysis.u32(a, 11), ProAnalysis.u32(b, 11)));
        long c0 = ProAnalysis.u32(recs.get(0), 11), c1 = ProAnalysis.u32(recs.get(recs.size() - 1), 11);
        int nRec = (int) Math.min(c1 - c0 + 1, 3600);
        if (nRec < 2) return out;
        double[] uv = new double[nRec * FS]; Arrays.fill(uv, Double.NaN);
        int origin = -1;
        for (byte[] f : recs) {
            long idx = ProAnalysis.u32(f, 11) - c0; if (idx < 0 || idx >= nRec) continue;
            int cnt = Math.min(EcgWhoopSpec.r16Count(f), FS), off = FS - cnt, base = (int) idx * FS + off;      // a short record is the end of its second
            if (cnt > 0 && origin < 0) origin = base;
            for (int k = 0; k < cnt; k++) {
                int v = EcgWhoopSpec.r16Sample(f, k);
                if (!EcgWhoopSpec.r16LeadOn(f, k) || EcgWhoopSpec.r16FastRecovery(f, k) || Math.abs(v) >= Live500.RAIL) continue;
                uv[base + k] = v * ScaleSetting.uv();
            }
        }
        if (origin < 0) return out;
        int n50 = uv.length / DEC; double[] lp50 = new double[n50]; Arrays.fill(lp50, Double.NaN);
        int i = 0;
        while (i < uv.length) {
            if (Double.isNaN(uv[i])) { i++; continue; }
            int j = i; while (j < uv.length && !Double.isNaN(uv[j])) j++;
            if (j - i >= FS) { double[] z = lowpass(Arrays.copyOfRange(uv, i, j)); for (int k = 0; k < z.length; k++) if ((i + k) % DEC == 0) lp50[(i + k) / DEC] = z[k]; }
            i = j;
        }
        int half = (int) Math.round(MED_S * FS / DEC / 2.0), minValid = half;       // 1.5 s = 75 samples at 50 Hz; at least 37 of them must hold signal
        double[] dev = new double[n50]; Arrays.fill(dev, Double.NaN);
        double[] w = new double[2 * half + 1];
        for (int g = 0; g < n50; g++) {
            if (Double.isNaN(lp50[g])) continue;
            int m = 0; for (int k = Math.max(0, g - half); k <= Math.min(n50 - 1, g + half); k++) if (!Double.isNaN(lp50[k])) w[m++] = lp50[k];
            if (m < minValid) continue;
            double[] c = Arrays.copyOf(w, m); Arrays.sort(c);
            dev[g] = lp50[g] - (m % 2 == 1 ? c[m / 2] : 0.5 * (c[m / 2 - 1] + c[m / 2]));
        }
        int gap = (int) Math.round(END_GAP_S * FS / DEC), startG = (int) Math.ceil((origin + AFTER_S * FS) / (double) DEC);
        List<double[]> ev = new ArrayList<>();                                         // {peak time s, peak uV signed}
        int g = Math.max(0, startG);
        while (g < n50) {
            if (Double.isNaN(dev[g]) || Math.abs(dev[g]) <= STEP_UV) { g++; continue; }
            double best = 0; int bestG = g, last = g, h = g;
            while (h < n50 && h - last <= gap) { if (!Double.isNaN(dev[h]) && Math.abs(dev[h]) > STEP_UV) { last = h; if (Math.abs(dev[h]) > Math.abs(best)) { best = dev[h]; bestG = h; } } h++; }
            ev.add(new double[] { (bestG * DEC - origin) / (double) FS, best });
            g = last + 1;
        }
        out.n = ev.size(); out.timesS = new double[out.n]; out.peaksMv = new double[out.n];
        for (int k = 0; k < out.n; k++) { out.timesS[k] = ev.get(k)[0]; out.peaksMv[k] = ev.get(k)[1] / 1000.0; if (Math.abs(out.peaksMv[k]) > out.peakMv) out.peakMv = Math.abs(out.peaksMv[k]); }
        return out;
    }

    /** The reading_notes.txt lines (start_unix_phone,key,value): slow_transients_n, slow_transient_peak_mV, slow_transient_times_s (peak time of each, seconds from the record start, ';' between). */
    static String noteLines(long startSec, Out o) {
        StringBuilder t = new StringBuilder();
        for (int k = 0; k < o.n; k++) t.append(k > 0 ? ";" : "").append(String.format(Locale.US, "%.1f", o.timesS[k]));
        return startSec + ",slow_transients_n," + o.n + "\n" + startSec + ",slow_transient_peak_mV," + String.format(Locale.US, "%.2f", o.peakMv) + "\n" + startSec + ",slow_transient_times_s," + t + "\n"
             + startSec + ",slow_transient_rule,lp5Hz_median1.5s_step800uV_after15s_v0.44.0\n";
    }
}
