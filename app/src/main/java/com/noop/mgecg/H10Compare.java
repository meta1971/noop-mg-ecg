package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): compares a reading with a simultaneous Polar H10 recording (h10_ecg_<unix>.csv: phone_ms,sample_time_ns,uV; 130 Hz). Pure Java, tested against the Python reference.

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class H10Compare {
    private H10Compare() {}
    static final class Result {
        int nMg, nH10, nPaired; double offsetS, timingSdMs, rrDiffSdMs, rrDiffMedianMs; double hrMg, hrH10, rmssdMg, rmssdH10; boolean piecewise;
        double[] h10Mean, mgMean; double h10R, mgR;           // mean beats on a 2 ms grid from -300 ms, microvolts; their R heights
    }

    /** Reads the csv; returns {times s from the first sample, microvolts} or null. */
    static double[][] read(File f) {
        List<double[]> a = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l = r.readLine(); long ns0 = -1;
            while ((l = r.readLine()) != null) { int c1 = l.indexOf(','), c2 = l.indexOf(',', c1 + 1); if (c2 < 0) continue; long ns = Long.parseLong(l.substring(c1 + 1, c2)); if (ns0 < 0) ns0 = ns; a.add(new double[] {(ns - ns0) / 1e9, Double.parseDouble(l.substring(c2 + 1))}); }
        } catch (Exception e) { return null; }
        if (a.size() < 1300) return null;
        double[] t = new double[a.size()], v = new double[a.size()]; for (int i = 0; i < t.length; i++) { t[i] = a.get(i)[0]; v[i] = a.get(i)[1]; }
        return new double[][] {t, v};
    }

    static Result compare(ProAnalysis.Result mg, double[][] h) {
        if (mg == null || h == null || mg.beatTimes == null || mg.beatTimes.length < 30) return null;
        double[] t = h[0], v = h[1]; double fs = (t.length - 1) / (t[t.length - 1] - t[0]);
        double[][] bp = ProDsp.bandPass(5, 20, fs); double[] z = ProDsp.filtfilt(v, bp); for (int i = 0; i < z.length; i++) z[i] = Math.abs(z[i]);
        List<Double> m = new ArrayList<>(); for (int i = 0; i + 300 <= z.length; i += 150) { double mx = 0; for (int j = i; j < i + 300; j++) mx = Math.max(mx, z[j]); m.add(mx); }
        double[] ma = new double[m.size()]; for (int i = 0; i < ma.length; i++) ma[i] = m.get(i);
        int[] pk = ProDsp.findPeaks(z, 0.45 * ProDsp.median(ma), (int) (0.3 * fs)); double[] hp = ProDsp.filtfilt(v, ProDsp.butterHigh2(0.5, fs));
        double[] th = new double[pk.length];
        for (int k = 0; k < pk.length; k++) { int l = Math.max(1, pk[k] - 4), r = Math.min(hp.length - 2, pk[k] + 4), bi = l; for (int i = l; i <= r; i++) if (hp[i] > hp[bi]) bi = i; double A = hp[bi - 1], B = hp[bi], C = hp[bi + 1], D = A - 2 * B + C; th[k] = (bi + (D != 0 ? 0.5 * (A - C) / D : 0)) / fs; }
        double[] tm = mg.beatTimes; Result R = new Result(); R.nMg = tm.length; R.nH10 = th.length;
        double lo = th[0] - tm[tm.length - 1] - 2, hi = th[th.length - 1] - tm[0] + 2, bestOff = lo, best = -1;
        for (double off = lo; off <= hi; off += 0.004) { double s = score(tm, th, off); if (s > best) { best = s; bestOff = off; } }
        double fo = bestOff; best = -1; for (double off = bestOff - 0.008; off <= bestOff + 0.008; off += 0.0005) { double s = score(tm, th, off); if (s > best) { best = s; fo = off; } }
        R.offsetS = fo;
        double[] offAt = new double[tm.length]; Arrays.fill(offAt, fo);
        if (tm[tm.length - 1] - tm[0] > 120) {      // 0.25.1: on a long reading the two clocks drift by tens of milliseconds, so the offset is refitted for every 30 s block
            R.piecewise = true; int i0 = 0;
            while (i0 < tm.length) { int i1 = i0; while (i1 < tm.length && tm[i1] < tm[i0] + 30) i1++;
                if (i1 - i0 >= 15) { double[] sub = Arrays.copyOfRange(tm, i0, i1); double bo = fo, bs = -1; for (double off = fo - 0.08; off <= fo + 0.08; off += 0.0005) { double sc = score(sub, th, off); if (sc > bs) { bs = sc; bo = off; } } for (int k = i0; k < i1; k++) offAt[k] = bo; }
                i0 = i1; }
        }
        int[] ia = new int[tm.length], ja = new int[tm.length]; double[] dt = new double[tm.length]; int np = 0;
        for (int i = 0; i < tm.length; i++) { int j = nearest(th, tm[i] + offAt[i]); if (j >= 0 && Math.abs(th[j] - (tm[i] + offAt[i])) < 0.04) { ia[np] = i; ja[np] = j; dt[np] = (tm[i] + offAt[i] - th[j]) * 1000; np++; } }
        R.nPaired = np; if (np < 20) return R;
        double[] d2 = Arrays.copyOf(dt, np); double mean = ProDsp.mean(d2); double[] d0 = new double[np]; for (int i = 0; i < np; i++) d0[i] = d2[i] - mean; R.timingSdMs = ProDsp.std(d0, 1);
        List<Double> rrd = new ArrayList<>(), rm = new ArrayList<>(), rh = new ArrayList<>();
        for (int k = 0; k + 1 < np; k++) if (ia[k + 1] == ia[k] + 1 && ja[k + 1] == ja[k] + 1) { double a = (tm[ia[k + 1]] - tm[ia[k]]) * 1000, b = (th[ja[k + 1]] - th[ja[k]]) * 1000; rrd.add(a - b); rm.add(a); rh.add(b); }
        if (rrd.size() > 10) {
            double[] q = new double[rrd.size()], qm = new double[q.length], qh = new double[q.length]; for (int i = 0; i < q.length; i++) { q[i] = rrd.get(i); qm[i] = rm.get(i); qh[i] = rh.get(i); }
            R.rrDiffSdMs = ProDsp.std(q, 1); R.rrDiffMedianMs = ProDsp.median(q); R.hrMg = 60000 / ProDsp.mean(qm); R.hrH10 = 60000 / ProDsp.mean(qh);
            double s1 = 0, s2 = 0; int c = 0; for (int i = 0; i + 1 < q.length; i++) { s1 += Math.pow(qm[i + 1] - qm[i], 2); s2 += Math.pow(qh[i + 1] - qh[i], 2); c++; } R.rmssdMg = Math.sqrt(s1 / c); R.rmssdH10 = Math.sqrt(s2 / c);
        }
        // mean beats on a 2 ms grid, -300 to +520 ms, around the paired beat times (H10 clock)
        double[] gh = new double[411]; int cnt = 0;
        for (int k = 0; k < np; k++) { double c = th[ja[k]]; if (c - 0.3 < 0 || c + 0.52 > t[t.length - 1]) continue; for (int i = 0; i < 411; i++) gh[i] += interp(t, hp, c - 0.3 + i * 0.002, fs); cnt++; }
        if (cnt > 10) { for (int i = 0; i < 411; i++) gh[i] /= cnt; double bl = ProDsp.median(gh, 100, 120); for (int i = 0; i < 411; i++) gh[i] -= bl; R.h10Mean = gh; R.h10R = gh[150]; for (int i = 140; i <= 160; i++) R.h10R = Math.max(R.h10R, gh[i]); }
        R.mgMean = mg.r16Grid; if (mg.r16Grid != null) { R.mgR = 0; for (int i = 140; i <= 160; i++) R.mgR = Math.max(R.mgR, mg.r16Grid[i]); }
        return R;
    }
    static double interp(double[] t, double[] v, double x, double fs) { int i = (int) ((x - t[0]) * fs); i = Math.max(0, Math.min(t.length - 2, i)); while (i > 0 && t[i] > x) i--; while (i < t.length - 2 && t[i + 1] < x) i++; double f = (x - t[i]) / (t[i + 1] - t[i]); return v[i] * (1 - f) + v[i + 1] * f; }
    static int nearest(double[] a, double x) { int j = Arrays.binarySearch(a, x); if (j >= 0) return j; j = -j - 1; if (j == 0) return 0; if (j >= a.length) return a.length - 1; return Math.abs(a[j] - x) < Math.abs(a[j - 1] - x) ? j : j - 1; }
    static double score(double[] tm, double[] th, double off) { double s = 0; for (double t : tm) { int j = nearest(th, t + off); double d = th[j] - (t + off); s += Math.exp(-(d / 0.012) * (d / 0.012)); } return s; }
}
