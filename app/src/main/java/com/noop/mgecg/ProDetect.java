package com.noop.mgecg;

// FILE VERSION 0.25.0 (29 Oct 2026): pulse arrival time (ECG R wave to the wrist green-LED pulse peak) from the stored 50 Hz optical records of the reading. Pure Java. Research only.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ProDetect {
    private ProDetect() {}

    static final class Pat {
        int beats, withPulse; double medianMs = Double.NaN, sdMs = Double.NaN, madMs = Double.NaN, breathPerMin = Double.NaN; double[] ms = new double[0]; double[] t = new double[0]; String note = "";
    }

    static Pat pat(ProAnalysis.Result R, List<byte[]> r20) {
        Pat P = new Pat();
        if (R == null || R.beatTimes == null || R.recT == null || r20 == null || r20.size() < 20) { P.note = "No stored optical data was found for this reading (the strap sends it with the next history pull)."; return P; }
        int nf = r20.size(); double[] ft = new double[nf]; double[][] fv = new double[nf][];
        for (int i = 0; i < nf; i++) {
            byte[] f = r20.get(i); ft[i] = ProAnalysis.u32(f, 15) + ProAnalysis.u16(f, 19) / 32768.0; int n = f[26] & 0xFF; if (n < 1 || n > 50) n = 0;
            fv[i] = new double[n]; for (int k = 0; k < n; k++) { int o = 26 + 21 + 4 * k; int r = (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8) | ((f[o + 2] & 0xFF) << 16); if ((r & 0x800000) != 0) r -= 0x1000000; fv[i][k] = r; }
        }
        // contiguous chunks (a gap of more than 1.5 s starts a new chunk), filtered 0.6 to 8 Hz, systolic peaks
        List<double[]> peaks = new ArrayList<>();
        int i0 = 0;
        while (i0 < nf) {
            int i1 = i0 + 1; while (i1 < nf && ft[i1] - ft[i1 - 1] <= 1.5) i1++;
            int tot = 0; for (int i = i0; i < i1; i++) tot += fv[i].length;
            if (tot >= 150) {
                double[] x = new double[tot], tt = new double[tot]; int p = 0; double mean = 0;
                for (int i = i0; i < i1; i++) for (int k = 0; k < fv[i].length; k++) { x[p] = fv[i][k]; tt[p] = ft[i] + k / 50.0; mean += x[p]; p++; }
                mean /= tot; for (int i = 0; i < tot; i++) x[i] -= mean;
                double[] y = ProDsp.filtfilt(x, ProDsp.bandPass(0.6, 8, 50)); double sd = 0; for (double v : y) sd += v * v; sd = Math.sqrt(sd / tot);
                int last = -100;
                for (int i = 2; i < tot - 2; i++) {
                    if (!(y[i] >= y[i - 1] && y[i] > y[i + 1])) continue;
                    double lo = y[i]; for (int j = Math.max(0, i - 20); j < i; j++) lo = Math.min(lo, y[j]);
                    if (y[i] - lo < 0.3 * sd) continue;
                    if (i - last < 20) { if (y[i] > peaks.get(peaks.size() - 1)[1]) { peaks.set(peaks.size() - 1, new double[] {tt[i], y[i]}); last = i; } continue; }
                    peaks.add(new double[] {tt[i], y[i]}); last = i;
                }
            }
            i0 = i1;
        }
        if (peaks.size() < 20) { P.note = "Too few pulse peaks in the optical record."; return P; }
        double[] pt = new double[peaks.size()]; for (int i = 0; i < pt.length; i++) pt[i] = peaks.get(i)[0];
        P.beats = R.beatTimes.length; List<Double> pat = new ArrayList<>(), at = new ArrayList<>();
        for (double tb : R.beatTimes) {
            int s = (int) Math.floor(tb); if (s < 0 || s >= R.recT.length) continue; double abs = R.recT[s] + (tb - s);
            int j = Arrays.binarySearch(pt, abs + 0.10); if (j < 0) j = -j - 1;
            if (j < pt.length && pt[j] - abs < 0.7) { pat.add((pt[j] - abs) * 1000.0); at.add(abs); }
        }
        P.withPulse = pat.size(); if (pat.size() < 20) { P.note = "Pulse found for only " + pat.size() + " beats."; return P; }
        P.ms = new double[pat.size()]; P.t = new double[pat.size()]; for (int i = 0; i < P.ms.length; i++) { P.ms[i] = pat.get(i); P.t[i] = at.get(i); }
        P.medianMs = ProDsp.median(P.ms); double m = 0, q = 0; for (double v : P.ms) m += v; m /= P.ms.length; for (double v : P.ms) q += (v - m) * (v - m); P.sdMs = Math.sqrt(q / P.ms.length);
        double[] dev = new double[P.ms.length]; for (int i = 0; i < dev.length; i++) dev[i] = Math.abs(P.ms[i] - P.medianMs); P.madMs = ProDsp.median(dev);
        // breathing: spectral peak of the pulse arrival series between 0.1 and 0.4 Hz (4 Hz grid)
        if (P.ms.length >= 30) {
            double t0 = P.t[0], t1 = P.t[P.t.length - 1]; int G = (int) ((t1 - t0) * 4); if (G >= 64) {
                double[] g = new double[G]; int k = 0;
                for (int i = 0; i < G; i++) { double tg = t0 + i * 0.25; while (k < P.t.length - 2 && P.t[k + 1] < tg) k++; double a = P.t[k], b = P.t[k + 1]; double w = b > a ? (tg - a) / (b - a) : 0; w = Math.max(0, Math.min(1, w)); g[i] = P.ms[k] * (1 - w) + P.ms[k + 1] * w - P.medianMs; }
                double mx = 0, my = 0, sxx = 0, sxy = 0; for (int i = 0; i < G; i++) { mx += i; my += g[i]; } mx /= G; my /= G; for (int i = 0; i < G; i++) { sxx += (i - mx) * (i - mx); sxy += (i - mx) * (g[i] - my); } double slope = sxx > 0 ? sxy / sxx : 0; for (int i = 0; i < G; i++) g[i] -= my + slope * (i - mx);
                double best = -1, bf = Double.NaN;
                for (double fq = 0.12; fq <= 0.401; fq += 0.005) { double re = 0, im = 0; for (int i = 0; i < G; i++) { double hw = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (G - 1)); re += hw * g[i] * Math.cos(2 * Math.PI * fq * i * 0.25); im += hw * g[i] * Math.sin(2 * Math.PI * fq * i * 0.25); } double pw = re * re + im * im; if (pw > best) { best = pw; bf = fq; } }
                P.breathPerMin = bf * 60.0;
            }
        }
        return P;
    }
}
