package com.noop.mgecg;

// FILE VERSION 0.45.0 (8 Nov 2026): the trace of every stretch of 2 s or more is the MCR v1 trace (median-cascade baseline, decode log 137.2) low-passed at 40 Hz, and the beats are the MCR-validated detections (rho under 0.6 rejected, split pairs merged; ProMcr); shorter stretches keep the old filter.
// FILE VERSION 0.44.0 (7 Nov 2026): the whole 500 Hz reading laid out for the replay on the result card (decode log 50.6 item 1, A3). Pure Java (no Android):
// the live raw frames of the reading (live_raw bin, CRC-valid layout-16 frames) become a display trace in mV (the stored-R16 zero-phase 0.67-40 Hz + 50 Hz
// notch filter of EcgWhoopSpec.improvedFilter, applied to every unbroken stretch of at least 1 s), the strap's quality byte (0-3) per sample, a class per
// second for the shading (0 good = quality 3, 1 fair = 2, 2 poor = 0-1, 3 no signal) and the beats (EcgR16Analyzer.peaks on every unbroken stretch of at
// least 4 s). It does NOT call EcgR16Analyzer.strip (that one pools the beats and rewrites the report as a side effect).

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ReplayData {
    static final int FS = 500;
    static final int MAX_RECORDS = 1800;                 // 30 minutes

    double[] mv = new double[0];                          // mV, NaN = no usable signal
    byte[] q = new byte[0];                               // strap quality byte of the record each sample came from
    byte[] secCls = new byte[0];                          // per record second: 0 good, 1 fair, 2 poor, 3 no signal
    int[] beats = new int[0];                             // R peak sample indices
    float[] beatAmp = new float[0];                       // signed height (mV) of the largest wave within 40 ms of each beat
    int first, last;                                      // first and last sample index that holds a valid sample
    double durS;                                          // (last - first) / 500
    long startUnix;                                       // strap time of the first record

    double timeOf(int idx) { return (idx - first) / (double) FS; }
    int indexOf(double t) { return (int) Math.round(first + t * FS); }
    int seconds() { return secCls.length; }

    /** Reads the live raw bin of a reading; null when it holds no usable frames. */
    static ReplayData load(File bin) {
        try {
            if (bin == null || !bin.exists() || bin.length() > 80L * 1024 * 1024) return null;
            byte[] b = Files.readAllBytes(bin.toPath());
            List<byte[]> fr = new ArrayList<>();
            for (byte[] f : ProAnalysis.frames(b)) if (f.length == 1584 && ProAnalysis.crcOk(f)) fr.add(f);
            return fromFrames(fr);
        } catch (Throwable t) { return null; }
    }

    static ReplayData fromFrames(List<byte[]> frames) {
        List<byte[]> recs = new ArrayList<>();
        for (byte[] f : frames) if (f != null && f.length == 1584 && (f[9] & 0xFF) == 16) recs.add(f);
        if (recs.isEmpty()) return null;
        recs.sort((a, b) -> Long.compare(ProAnalysis.u32(a, 11), ProAnalysis.u32(b, 11)));
        long c0 = ProAnalysis.u32(recs.get(0), 11), c1 = ProAnalysis.u32(recs.get(recs.size() - 1), 11);
        int nRec = (int) Math.min(c1 - c0 + 1, MAX_RECORDS);
        if (nRec < 2) return null;
        int n = nRec * FS;
        double[] uv = new double[n]; Arrays.fill(uv, Double.NaN);
        byte[] q = new byte[n];
        for (byte[] f : recs) {
            long idx = ProAnalysis.u32(f, 11) - c0;
            if (idx < 0 || idx >= nRec) continue;
            int base = (int) idx * FS, cnt = Math.min(EcgWhoopSpec.r16Count(f), FS), off = FS - cnt;      // a short record is the end of its second (as the live graph)
            for (int k = 0; k < FS; k++) q[base + k] = f[21];
            for (int k = 0; k < cnt; k++) {
                int v = EcgWhoopSpec.r16Sample(f, k);
                if (!EcgWhoopSpec.r16LeadOn(f, k) || EcgWhoopSpec.r16FastRecovery(f, k) || Math.abs(v) >= Live500.RAIL) continue;
                uv[base + off + k] = v * EcgWhoopSpec.R16_UV_PER_COUNT;
            }
        }
        int firstV = -1, lastV = -1;
        for (int i = 0; i < n; i++) if (!Double.isNaN(uv[i])) { if (firstV < 0) firstV = i; lastV = i; }
        if (firstV < 0 || lastV - firstV < 2 * FS) return null;
        ReplayData d = new ReplayData();
        d.q = q; d.first = firstV; d.last = lastV; d.durS = (lastV - firstV) / (double) FS;
        d.startUnix = ProAnalysis.u32(recs.get(0), 15);
        d.mv = new double[n]; Arrays.fill(d.mv, Double.NaN);
        List<Integer> bl = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (Double.isNaN(uv[i])) { i++; continue; }
            int j = i; while (j < n && !Double.isNaN(uv[j])) j++;
            if (j - i >= FS) {
                double[] seg = Arrays.copyOfRange(uv, i, j), z = null, tr = null;
                if (j - i >= 2 * FS) { try { tr = ProMcr.trace(seg); z = ProDsp.filtfilt(tr, ProDsp.butterLow(4, 40, FS)); } catch (Throwable e) { tr = null; z = null; } }
                if (z == null) z = EcgWhoopSpec.improvedFilter(seg);
                for (int k = 0; k < z.length; k++) d.mv[i + k] = z[k] / 1000.0;
                if (j - i >= 4 * FS) {
                    boolean done = false;
                    if (tr != null) { try { for (int p : mcrBeats(tr)) bl.add(i + p); done = true; } catch (Throwable e) { done = false; } }
                    if (!done) { try { for (int p : EcgR16Analyzer.peaks(seg)) bl.add(i + p); } catch (Throwable ignored) { } }
                }
            }
            i = j;
        }
        d.beats = new int[bl.size()]; d.beatAmp = new float[bl.size()];
        int w = (int) (0.04 * FS);
        for (int k = 0; k < d.beats.length; k++) {
            int p = bl.get(k); d.beats[k] = p; double best = 0;
            for (int g = Math.max(0, p - w); g <= Math.min(n - 1, p + w); g++) { double v = d.mv[g]; if (!Double.isNaN(v) && Math.abs(v) > Math.abs(best)) best = v; }
            d.beatAmp[k] = (float) best;
        }
        d.secCls = new byte[nRec];
        for (int s = 0; s < nRec; s++) {
            int sig = 0; for (int g = s * FS; g < (s + 1) * FS; g += 5) if (!Double.isNaN(d.mv[g])) sig++;
            int qq = q[s * FS] & 0xFF;
            d.secCls[s] = (byte) (sig < 20 ? 3 : qq >= 3 ? 0 : qq == 2 ? 1 : 2);
        }
        return d;
    }

    /** 0.45.0 (h): R detections on the MCR trace of one stretch (positive R, or the polarity flipped when the negative side is larger), scored against the stretch's own template; rho under 0.6 dropped, split pairs merged. */
    static int[] mcrBeats(double[] tr) {
        double hi = ProDsp.percentile(tr, 99.5), lo = -ProDsp.percentile(tr, 0.5); boolean inv = lo > hi;
        double[] sg = tr; if (inv) { sg = new double[tr.length]; for (int k = 0; k < sg.length; k++) sg[k] = -tr[k]; }
        int[] pk = ProAnalysis.detectR(sg);
        return ProMcr.validate(sg, pk, null).idx;
    }

    /** Fixed vertical range for the whole replay in mV: {low, high}. The 0.3 and 99.7 percentiles of the usable samples with the live graph's margins (a slow contact transient is clipped, not scaled to). */
    double[] yRange() {
        int k = 0; double[] t = new double[Math.max(1, (last - first) / 4 + 1)];
        for (int i = first; i <= last && k < t.length; i += 4) if (!Double.isNaN(mv[i])) t[k++] = mv[i];
        if (k < 100) return new double[] { -0.5, 1.0 };
        Arrays.sort(t, 0, k);
        double hi = t[Math.min(k - 1, (int) (k * 0.997))], lo = t[(int) (k * 0.003)];
        double mx = Math.max(0.8, hi * 1.25), mn = Math.min(-0.4, lo * 1.4);
        return new double[] { Math.max(mn, -3.0), Math.min(mx, 4.0) };
    }
}
