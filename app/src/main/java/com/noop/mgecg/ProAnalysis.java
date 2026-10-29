package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): the 500 Hz analysis behind the professional report: beats, mean beat, intervals with bootstrap confidence ranges, variability,
// bandwidth study (does the part above 40 Hz carry signal?). A Java port of the Python reference (mglib.py, analyse.py), tested against it on real readings. Pure Java.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

final class ProAnalysis {
    private ProAnalysis() {}
    static final int FS = 500, PRE = 250, POST = 400, QW0 = PRE - 20, QW1 = PRE + 30;
    static final double UV = 0.0621;
    static final double[][] BANDS = {{0, 20}, {20, 40}, {40, 60}, {60, 80}, {80, 100}, {100, 130}, {130, 160}, {160, 200}, {200, 250}};

    static final class Iv {
        double qrsOn, qrsOff, qrs, base, rAmp, st = Double.NaN, tAmp = Double.NaN, tPeak = Double.NaN, tEnd = Double.NaN, pAmp = Double.NaN, pPeak = Double.NaN, pOn = Double.NaN, pOff = Double.NaN, pr = Double.NaN, pDur = Double.NaN;
        double qt = Double.NaN, qtcB = Double.NaN, qtcF = Double.NaN, qtcFr = Double.NaN; boolean qtValid;
    }
    static final class Hrv {
        int n, removed; double meanRr, hr, sdnn, sdnnDetr, rmssd, pnn50, sd1, sd2, lf, hf, vlf, fpk;
        double[] rrAll; boolean[] keep; double[] psdF, psdP;
    }
    static final class Result {
        double t0; int seconds, beatsUsed, beatsTotal, cleanSeconds; int[] qcount = new int[4];
        double[] rrMs; Hrv hrv; Iv iv; double[][] ci = new double[10][];       // lo, hi per key (see CI_KEYS)
        double[] meanFull, meanLp40, sd; double noiseSingle, noiseMean;
        double[] snrAvg = new double[9], snrSingle = new double[9]; double splitQrs, splitCtl;
        double rFull, rLp40, r100, widthFull, widthLp40; double[] diff, diffSem;
        double nrmssdMedian = Double.NaN; double rrMin, rrMax;
        double[] stripY; int[] stripPeaks; double stripStartS;
        double[] r17Mean; double r17Lag = Double.NaN, r17Height = Double.NaN, r17Width = Double.NaN;      // the filtered 100 Hz stream (R17) around the same beats, on a 2 ms grid from -300 ms
        double[] r16Grid; double[] beatTimes;        // beat times (s from the start of the reading) from the 40 Hz low-passed signal with parabolic peak refinement; used to pair with the H10
    }
    static final String[] CI_KEYS = {"qrs", "pr", "qt", "qtcF", "qtcB", "pAmp", "rAmp", "tAmp", "st", "pDur"};

    // ---------------------------------------------------------------- loading
    static List<byte[]> frames(byte[] b) {
        List<byte[]> out = new ArrayList<>(); int i = 0;
        while (i + 12 <= b.length) {
            if ((b[i] & 0xFF) != 0xAA || b[i + 1] != 1) { i++; continue; }
            int ln = (b[i + 2] & 0xFF) | ((b[i + 3] & 0xFF) << 8); int e = i + ln + 8;
            if (e > b.length) break;
            out.add(Arrays.copyOfRange(b, i, e)); i = e;
        }
        return out;
    }
    static boolean crcOk(byte[] f) {
        CRC32 c = new CRC32(); c.update(f, 8, f.length - 12);
        long want = (f[f.length - 4] & 0xFFL) | ((f[f.length - 3] & 0xFFL) << 8) | ((f[f.length - 2] & 0xFFL) << 16) | ((f[f.length - 1] & 0xFFL) << 24);
        return c.getValue() == want;
    }

    // ---------------------------------------------------------------- the analysis
    /** Why analyse() returned null (shown by the Pro PDF button). */
    static volatile String why = "";

    static Result analyse(byte[] bin) {
        why = "";
        List<byte[]> fr = new ArrayList<>();
        List<byte[]> fr17 = new ArrayList<>();
        for (byte[] f : frames(bin)) { if (f.length == 1584 && crcOk(f)) fr.add(f); else if (f.length == 240 && crcOk(f)) fr17.add(f); }
        if (fr.size() < 20) { why = "only " + fr.size() + " live frames in the frame file (20 seconds are needed)"; return null; }
        Result R = new Result(); int sec = fr.size(); R.seconds = sec;
        double[] x = new double[sec * 500]; int[] q = new int[sec]; double[] recT = new double[sec];
        for (int s = 0; s < sec; s++) {
            byte[] f = fr.get(s); int n = (f[32] & 0xFF) | ((f[33] & 0xFF) << 8); q[s] = f[21] & 0xFF; if (q[s] < 4) R.qcount[q[s]]++;
            recT[s] = u32(f, 15) + u16(f, 19) / 32768.0;
            if (s == 0) R.t0 = (f[15] & 0xFFL) | ((f[16] & 0xFFL) << 8) | ((f[17] & 0xFFL) << 16) | ((f[18] & 0xFFL) << 24);
            for (int k = 0; k < 500; k++) {
                double v = Double.NaN;
                if (k < n) {
                    int b0 = f[34 + 3 * k] & 0xFF, b1 = f[35 + 3 * k] & 0xFF, b2 = f[36 + 3 * k] & 0xFF; int r = ((b0 & 3) << 16) | (b1 << 8) | b2; if ((r & 0x20000) != 0) r -= 0x40000;
                    if ((b0 & 0x80) != 0 && (b0 & 0x40) == 0 && Math.abs(r) < 126900) v = r * UV;
                }
                x[s * 500 + k] = v;
            }
        }
        R.cleanSeconds = R.qcount[3];
        // clean segments
        List<int[]> segs = new ArrayList<>(); int cur = -1;
        for (int s = 0; s < sec; s++) {
            boolean ok = q[s] == 3; if (ok) for (int k = 0; k < 500; k++) if (Double.isNaN(x[s * 500 + k])) { ok = false; break; }
            if (ok && cur < 0) cur = s;
            if ((!ok || s == sec - 1) && cur >= 0) { int e = s + (ok && s == sec - 1 ? 1 : 0); if (e - cur >= 8) segs.add(new int[] {cur * 500 + 250, e * 500 - 250}); cur = -1; }
        }
        if (segs.isEmpty()) { why = "no stretch of 8 seconds with quality 3 and no flagged samples (" + R.qcount[3] + " seconds of quality 3 in " + sec + ")"; return null; }
        double[][] HP = ProDsp.butterHigh2(0.5, FS); double[][] NT = {ProDsp.notch(50, 35, FS)};
        List<Double> btAcc = new ArrayList<>(); List<double[]> beats = new ArrayList<>(); List<Integer> bpos = new ArrayList<>(); List<Double> rrAcc = new ArrayList<>();
        int bestSeg = 0; for (int i = 1; i < segs.size(); i++) if (segs.get(i)[1] - segs.get(i)[0] > segs.get(bestSeg)[1] - segs.get(bestSeg)[0]) bestSeg = i;
        for (int si = 0; si < segs.size(); si++) {
            int a = segs.get(si)[0], b = segs.get(si)[1];
            double[] y = ProDsp.filtfilt(ProDsp.filtfilt(Arrays.copyOfRange(x, a, b), HP), NT);
            int[] pk = detectR(y); if (pk.length < 10) continue;
            double[] ylp = ProDsp.filtfilt(y, ProDsp.butterLow(4, 40, FS));
            for (int p : pk) { int l = Math.max(1, p - 2), h = Math.min(ylp.length - 2, p + 2), bi = l; for (int i = l; i <= h; i++) if (ylp[i] > ylp[bi]) bi = i; double A = ylp[bi - 1], B = ylp[bi], C = ylp[bi + 1], D = A - 2 * B + C; btAcc.add((a + bi + (D != 0 ? 0.5 * (A - C) / D : 0)) / FS); }
            for (int i = 1; i < pk.length; i++) rrAcc.add((pk[i] - pk[i - 1]) * 1000.0 / FS);
            for (int p : pk) if (p - PRE >= 0 && p + POST <= y.length) { beats.add(Arrays.copyOfRange(y, p - PRE, p + POST)); bpos.add(a + p); }
        }
        R.beatsTotal = beats.size(); if (beats.size() < 30) { why = "only " + beats.size() + " beats found in the clean stretches (30 are needed)"; return null; }
        R.beatTimes = new double[btAcc.size()]; for (int i = 0; i < R.beatTimes.length; i++) R.beatTimes[i] = btAcc.get(i);
        R.rrMs = new double[rrAcc.size()]; for (int i = 0; i < R.rrMs.length; i++) R.rrMs[i] = rrAcc.get(i);
        R.rrMin = Arrays.stream(R.rrMs).min().orElse(0); R.rrMax = Arrays.stream(R.rrMs).max().orElse(0);
        // alignment from the 5 to 30 Hz band only (so noise above 30 Hz cannot steer it), shifts applied to the full-band beats
        int nb = beats.size(), L = PRE + POST; double[][] BP = ProDsp.bandPass(5, 30, FS);
        double[][] Lb = new double[nb][]; for (int k = 0; k < nb; k++) Lb[k] = ProDsp.filtfilt(beats.get(k), BP);
        double[] sh = new double[nb]; double[] T = med(Lb, nb, L); double[][] cur2 = new double[nb][];
        for (int it = 0; it < 2; it++) {
            for (int k = 0; k < nb; k++) { cur2[k] = shifted(Lb[k], sh[k]); sh[k] += lag(cur2[k], T); cur2[k] = shifted(Lb[k], sh[k]); }
            T = med(cur2, nb, L);
        }
        List<double[]> kept = new ArrayList<>(); List<Double> keptT = new ArrayList<>();
        for (int k = 0; k < nb; k++) if (corrWin(cur2[k], T, PRE - 40, PRE + 60) > 0.9) { kept.add(shifted(beats.get(k), sh[k])); int gp = bpos.get(k); keptT.add(recT[Math.min(sec - 1, gp / 500)] + ((gp % 500) + sh[k]) / 500.0); }
        int N = kept.size(); R.beatsUsed = N; if (N < 30) { why = "only " + N + " of " + nb + " beats matched the average beat (30 are needed)"; return null; }
        double[][] Bal = kept.toArray(new double[0][]);
        double[] avg = new double[L], sd = new double[L];
        for (double[] b : Bal) for (int i = 0; i < L; i++) avg[i] += b[i] / N;
        for (double[] b : Bal) for (int i = 0; i < L; i++) sd[i] += (b[i] - avg[i]) * (b[i] - avg[i]);
        for (int i = 0; i < L; i++) sd[i] = Math.sqrt(sd[i] / N);
        R.meanFull = avg; R.sd = sd; R.meanLp40 = ProDsp.filtfilt(avg, ProDsp.butterLow(4, 40, FS));
        // variability
        R.hrv = hrv(R.rrMs); double rrS = ProDsp.median(R.rrMs) / 1000.0;
        R.iv = intervals(avg, rrS);
        // bootstrap confidence ranges
        Random rnd = new Random(1); int NB = 300; double[][] bs = new double[CI_KEYS.length][NB]; int ok = 0;
        for (int b = 0; b < NB; b++) {
            double[] m = new double[L]; for (int i = 0; i < N; i++) { double[] bb = Bal[rnd.nextInt(N)]; for (int j = 0; j < L; j++) m[j] += bb[j]; }
            for (int j = 0; j < L; j++) m[j] /= N;
            Iv v = intervals(m, rrS); if (v == null) continue;
            double[] vals = {v.qrs, v.pr, v.qt, v.qtcF, v.qtcB, v.pAmp, v.rAmp, v.tAmp, v.st, v.pDur};
            for (int k = 0; k < vals.length; k++) bs[k][ok] = vals[k]; ok++;
        }
        for (int k = 0; k < CI_KEYS.length; k++) {
            double[] t = new double[ok], tt = new double[ok]; int c = 0; for (int i = 0; i < ok; i++) if (!Double.isNaN(bs[k][i])) tt[c++] = bs[k][i];
            if (c > 20) R.ci[k] = new double[] {ProDsp.percentile(Arrays.copyOf(tt, c), 2.5), ProDsp.percentile(Arrays.copyOf(tt, c), 97.5)};
        }
        // noise and band signal to noise
        double[] ns = new double[N]; for (int k = 0; k < N; k++) { double[] w = Arrays.copyOfRange(Bal[k], PRE + 225, PRE + 275); ns[k] = ProDsp.std(ProDsp.detrend(w), 1); }
        R.noiseSingle = ProDsp.median(ns); R.noiseMean = R.noiseSingle / Math.sqrt(N);
        double[] sig = ProDsp.bandPower(avg, QW0, QW1 - QW0, 256);
        double[] noiseAcc = new double[sig.length]; int NBO = 150;
        for (int b = 0; b < NBO; b++) {
            double[] m = new double[L]; for (int i = 0; i < N; i++) { double s = rnd.nextBoolean() ? 1 : -1; for (int j = QW0; j < QW1; j++) m[j] += s * (Bal[i][j] - avg[j]) / N; }
            double[] p = ProDsp.bandPower(m, QW0, QW1 - QW0, 256); for (int i = 0; i < p.length; i++) noiseAcc[i] += p[i] / NBO;
        }
        for (int bi = 0; bi < 9; bi++) R.snrAvg[bi] = 10 * Math.log10(bandSum(sig, BANDS[bi][0], BANDS[bi][1]) / bandSum(noiseAcc, BANDS[bi][0], BANDS[bi][1]));
        double[][] ratio = new double[9][N];
        for (int k = 0; k < N; k++) { double[] ps = ProDsp.bandPower(Bal[k], QW0, QW1 - QW0, 256), pt = ProDsp.bandPower(Bal[k], PRE + 225, QW1 - QW0, 256); for (int bi = 0; bi < 9; bi++) ratio[bi][k] = bandSum(ps, BANDS[bi][0], BANDS[bi][1]) / bandSum(pt, BANDS[bi][0], BANDS[bi][1]); }
        for (int bi = 0; bi < 9; bi++) R.snrSingle[bi] = 10 * Math.log10(ProDsp.median(ratio[bi]));
        // split-half reproducibility of the 40 to 150 Hz trace
        double[][] HFB = ProDsp.bandPass(40, 150, FS); double sq = 0, sc = 0; int NS = 20;
        for (int s = 0; s < NS; s++) {
            List<Integer> idx = new ArrayList<>(); for (int i = 0; i < N; i++) idx.add(i); java.util.Collections.shuffle(idx, new Random(s));
            double[] m1 = new double[L], m2 = new double[L]; int h = N / 2;
            for (int i = 0; i < h; i++) { double[] b = Bal[idx.get(i)]; for (int j = 0; j < L; j++) m1[j] += b[j] / h; }
            for (int i = h; i < 2 * h; i++) { double[] b = Bal[idx.get(i)]; for (int j = 0; j < L; j++) m2[j] += b[j] / h; }
            double[] f1 = ProDsp.filtfilt(m1, HFB), f2 = ProDsp.filtfilt(m2, HFB);
            sq += ProDsp.corr(Arrays.copyOfRange(f1, QW0, QW1), Arrays.copyOfRange(f2, QW0, QW1)) / NS;
            sc += ProDsp.corr(Arrays.copyOfRange(f1, PRE + 225, PRE + 275), Arrays.copyOfRange(f2, PRE + 225, PRE + 275)) / NS;
        }
        R.splitQrs = sq; R.splitCtl = sc;
        // R17-like comparison on the mean beat
        double base = R.iv.base; R.diff = new double[L]; R.diffSem = new double[L];
        for (int i = 0; i < L; i++) { R.diff[i] = avg[i] - R.meanLp40[i]; R.diffSem[i] = sd[i] / Math.sqrt(N); }
        R.rFull = avg[PRE] - base; R.rLp40 = R.meanLp40[PRE] - base;
        double best = -1e9; for (int i = PRE - 4; i <= PRE + 4; i++) if ((i - PRE) % 5 == 0 || true) { /* 100 Hz grid: every 5th sample of the 500 Hz beat, phase aligned on the R apex */ }
        double r100 = -1e9; for (int off = 0; off < 5; off++) { double m = -1e9; for (int i = PRE - 20 + off; i <= PRE + 20; i += 5) m = Math.max(m, R.meanLp40[i] - base); r100 = Math.max(r100 == -1e9 ? -1e9 : r100, m); if (off == 0) r100 = m; }
        R.r100 = r100;
        R.widthFull = fwhm(avg, PRE); R.widthLp40 = fwhm(R.meanLp40, PRE);
        // irregularity screen: normalised RMSSD over windows of 32 intervals
        List<Double> nr = new ArrayList<>(); double[] rr = R.hrv.rrAll;
        for (int s = 0; s + 32 <= rr.length; s += 16) { double[] w = Arrays.copyOfRange(rr, s, s + 32), d = new double[31]; double m = ProDsp.mean(w), ss = 0; for (int i = 0; i < 31; i++) { d[i] = w[i + 1] - w[i]; ss += d[i] * d[i]; } nr.add(Math.sqrt(ss / 31) / m); }
        if (!nr.isEmpty()) { double[] t = new double[nr.size()]; for (int i = 0; i < t.length; i++) t[i] = nr.get(i); R.nrmssdMedian = ProDsp.median(t); }
        r17Compare(R, fr17, keptT);
        // a 30 s strip from the longest clean segment (0.5 to 40 Hz)
        int[] sg = segs.get(bestSeg); int len = sg[1] - sg[0]; int want = 30 * 500; int a0 = len > want + 2000 ? sg[0] + (int) (len * 0.15) : sg[0]; int b0 = Math.min(sg[1], a0 + want);
        if (b0 - a0 >= 10 * 500) {
            int pa = Math.max(0, a0 - 500), pb = Math.min(x.length, b0 + 500);
            double[] pre = ProDsp.filtfilt(ProDsp.filtfilt(Arrays.copyOfRange(x, pa, pb), HP), NT); int[] pk = detectR(pre);
            double[] lp = ProDsp.filtfilt(pre, ProDsp.butterLow(4, 40, FS)); int o = a0 - pa; double[] sy = Arrays.copyOfRange(lp, o, o + (b0 - a0)); double md = ProDsp.median(sy); for (int i = 0; i < sy.length; i++) sy[i] -= md;
            List<Integer> pl = new ArrayList<>(); for (int p : pk) if (p >= o && p < o + sy.length) pl.add(p - o);
            R.stripY = sy; R.stripPeaks = new int[pl.size()]; for (int i = 0; i < pl.size(); i++) R.stripPeaks[i] = pl.get(i); R.stripStartS = a0 / 500.0;
        }
        return R;
    }

    static long u32(byte[] f, int o) { return (f[o] & 0xFFL) | ((f[o + 1] & 0xFFL) << 8) | ((f[o + 2] & 0xFFL) << 16) | ((f[o + 3] & 0xFFL) << 24); }
    static int u16(byte[] f, int o) { return (f[o] & 0xFF) | ((f[o + 1] & 0xFF) << 8); }

    /** The filtered live stream (R17, 100 Hz, 1 uV per count) averaged around the same beats (phase correct, linear interpolation), its lag against the 500 Hz record, its R height and half-height width. */
    static void r17Compare(Result R, List<byte[]> fr17, List<Double> beatT) {
        List<double[]> pts = new ArrayList<>();
        for (byte[] f : fr17) { int n = u16(f, 32); if (n != 100) continue; double t = u32(f, 15) + u16(f, 19) / 32768.0; for (int k = 0; k < n; k++) pts.add(new double[] {t + k * 0.01, (short) ((f[34 + 2 * k] & 0xFF) | (f[35 + 2 * k] << 8))}); }
        if (pts.size() < 3000 || beatT.size() < 30) return;
        pts.sort((p, q) -> Double.compare(p[0], q[0])); int n = pts.size(); double[] tt = new double[n], vv = new double[n]; for (int i = 0; i < n; i++) { tt[i] = pts.get(i)[0]; vv[i] = pts.get(i)[1]; }
        double[] g16 = new double[411]; for (int i = 0; i < 411; i++) g16[i] = R.meanLp40[PRE - 150 + i] - R.iv.base; R.r16Grid = g16;         // 2 ms grid from -300 ms
        double bestL = 0, bestC = -2; double[] bestM = null;
        for (double lag = 0.10; lag <= 0.45; lag += 0.004) { double[] m = meanAt(tt, vv, beatT, lag); if (m == null) continue; double c = ProDsp.corr(Arrays.copyOfRange(m, 90, 230), Arrays.copyOfRange(g16, 90, 230)); if (c > bestC) { bestC = c; bestL = lag; bestM = m; } }
        if (bestM == null) return;
        for (double lag = bestL - 0.004; lag <= bestL + 0.004; lag += 0.001) { double[] m = meanAt(tt, vv, beatT, lag); if (m == null) continue; double c = ProDsp.corr(Arrays.copyOfRange(m, 90, 230), Arrays.copyOfRange(g16, 90, 230)); if (c > bestC) { bestC = c; bestL = lag; bestM = m; } }
        double bl = ProDsp.median(bestM, 100, 120); double[] m = new double[411]; for (int i = 0; i < 411; i++) m[i] = bestM[i] - bl;       // baseline: -100 to -60 ms
        R.r17Mean = m; R.r17Lag = bestL; int ap = 150; for (int i = 140; i <= 160; i++) if (m[i] > m[ap]) ap = i; R.r17Height = m[ap];
        double h = m[ap] / 2; int l = ap, r = ap; while (l > 0 && m[l] > h) l--; while (r < 410 && m[r] > h) r++;
        double xl = l + (h - m[l]) / (m[l + 1] - m[l]), xr = r - 1 + (m[r - 1] - h) / (m[r - 1] - m[r]); R.r17Width = (xr - xl) * 2.0;
    }
    static double[] meanAt(double[] tt, double[] vv, List<Double> bt, double lag) {
        double[] acc = new double[411]; int cnt = 0;
        for (double b : bt) {
            double t0 = b + lag - 0.30, t1 = b + lag + 0.52; if (t0 < tt[0] || t1 > tt[tt.length - 1]) continue;
            int j = Arrays.binarySearch(tt, t0); if (j < 0) j = -j - 2; j = Math.max(0, j);
            for (int i = 0; i < 411; i++) { double t = t0 + i * 0.002; while (j + 1 < tt.length - 1 && tt[j + 1] < t) j++; double f = (t - tt[j]) / (tt[j + 1] - tt[j]); acc[i] += vv[j] * (1 - f) + vv[j + 1] * f; }
            cnt++;
        }
        if (cnt < 20) return null; for (int i = 0; i < 411; i++) acc[i] /= cnt; return acc;
    }

    static double bandSum(double[] p, double lo, double hi) { double s = 0; for (int k = 0; k < p.length; k++) { double f = k * 500.0 / 256.0; if (f >= lo && f < hi && !(f > 47 && f < 53)) s += p[k]; } return s; }
    static double fwhm(double[] a, int r) { double base = Math.min(a[r - 60], a[r - 30]) * 0.0; double pk = a[r]; double h = pk / 2; int l = r; while (l > 0 && a[l] > h) l--; int rr = r; while (rr < a.length - 1 && a[rr] > h) rr++; return (rr - l) * 1000.0 / FS; }
    static double[] med(double[][] B, int n, int L) { double[] m = new double[L], t = new double[n]; for (int j = 0; j < L; j++) { for (int k = 0; k < n; k++) t[k] = B[k][j]; m[j] = ProDsp.median(t); } return m; }
    static double[] shifted(double[] a, double sh) { double[] o = new double[a.length]; for (int i = 0; i < a.length; i++) { double p = i + sh; int i0 = (int) Math.floor(p); double f = p - i0; if (i0 < 0) o[i] = a[0]; else if (i0 >= a.length - 1) o[i] = a[a.length - 1]; else o[i] = a[i0] * (1 - f) + a[i0 + 1] * f; } return o; }
    static double lag(double[] seg, double[] T) {
        int r0 = PRE - 60, r1 = PRE + 80; double ms = 0, mt = 0; for (int n = r0; n < r1; n++) { ms += seg[n]; mt += T[n]; } ms /= (r1 - r0); mt /= (r1 - r0);
        double[] c = new double[51]; for (int d = -25; d <= 25; d++) { double s = 0; for (int n = r0; n < r1; n++) s += (seg[n] - ms) * (T[n - d] - mt); c[d + 25] = s; }
        int bi = 0; for (int i = 1; i < 51; i++) if (c[i] > c[bi]) bi = i; double off = 0; if (bi > 0 && bi < 50) { double a = c[bi - 1], b = c[bi], d = c[bi + 1], den = a - 2 * b + d; if (den != 0) off = 0.5 * (a - d) / den; }
        return bi - 25 + off;
    }
    static double corrWin(double[] a, double[] b, int r0, int r1) { return ProDsp.corr(Arrays.copyOfRange(a, r0, r1), Arrays.copyOfRange(b, r0, r1)); }

    static int[] detectR(double[] y) {
        double[] det = ProDsp.filtfilt(y, ProDsp.bandPass(5, 20, FS)); double[] z = new double[det.length]; for (int i = 0; i < z.length; i++) z[i] = Math.abs(det[i]);
        double thr; if (z.length > 3000) { List<Double> m = new ArrayList<>(); for (int i = 0; i + 1500 <= z.length; i += 750) { double mx = 0; for (int j = i; j < i + 1500; j++) mx = Math.max(mx, z[j]); m.add(mx); } double[] t = new double[m.size()]; for (int i = 0; i < t.length; i++) t[i] = m.get(i); thr = 0.45 * ProDsp.median(t); }
        else { double mx = 0; for (double v : z) mx = Math.max(mx, v); thr = 0.45 * mx; }
        int[] pk = ProDsp.findPeaks(z, thr, (int) (0.30 * FS)); java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (int p : pk) { int l = Math.max(0, p - 12), h = Math.min(y.length, p + 13), bi = l; for (int i = l; i < h; i++) if (y[i] > y[bi]) bi = i; out.add(bi); }
        int[] r = new int[out.size()]; int i = 0; for (int v : out) r[i++] = v; return r;
    }

    // ---------------------------------------------------------------- variability
    static Hrv hrv(double[] rrIn) {
        Hrv h = new Hrv(); int n = rrIn.length; double[] med = ProDsp.runMedian(rrIn, 11); boolean[] keep = new boolean[n]; int kn = 0;
        for (int i = 0; i < n; i++) { keep[i] = Math.abs(rrIn[i] - med[i]) < 0.2 * med[i]; if (keep[i]) kn++; }
        double[] rr = new double[kn]; int c = 0; for (int i = 0; i < n; i++) if (keep[i]) rr[c++] = rrIn[i];
        List<Double> dl = new ArrayList<>(); for (int i = 0; i + 1 < n; i++) if (keep[i] && keep[i + 1]) dl.add(rrIn[i + 1] - rrIn[i]);
        double[] d = new double[dl.size()]; for (int i = 0; i < d.length; i++) d[i] = dl.get(i);
        h.rrAll = rrIn; h.keep = keep; h.n = kn + 1; h.removed = n - kn; h.meanRr = ProDsp.mean(rr); h.hr = 60000 / h.meanRr; h.sdnn = ProDsp.std(rr, 1);
        double ss = 0; int p50 = 0; for (double v : d) { ss += v * v; if (Math.abs(v) > 50) p50++; } h.rmssd = Math.sqrt(ss / d.length); h.pnn50 = 100.0 * p50 / d.length;
        double vd = Math.pow(ProDsp.std(d, 1), 2), vr = Math.pow(h.sdnn, 2); h.sd1 = Math.sqrt(0.5) * ProDsp.std(d, 1); h.sd2 = Math.sqrt(Math.max(0, 2 * vr - 0.5 * vd));
        double[] md = ProDsp.runMedian(rrIn, 41); double[] dt = new double[kn]; c = 0; for (int i = 0; i < n; i++) if (keep[i]) dt[c++] = rrIn[i] - md[i]; h.sdnnDetr = ProDsp.std(dt, 1);
        double[] t = new double[kn]; double acc = 0; for (int i = 0; i < kn; i++) { acc += rr[i] / 1000.0; t[i] = acc; } for (int i = kn - 1; i >= 0; i--) t[i] -= t[0];
        int m = (int) Math.floor(t[kn - 1] / 0.25); double[] ti = new double[m]; for (int i = 0; i < m; i++) ti[i] = i * 0.25;
        double[] ri = ProDsp.detrend(ProDsp.spline(t, rr, ti)); double[][] w = ProDsp.welch(ri, 4.0, 256); h.psdF = w[0]; h.psdP = w[1]; double df = w[0][1] - w[0][0];
        for (int k = 0; k < w[0].length; k++) { double f = w[0][k]; if (f >= 0.04 && f < 0.15) h.lf += w[1][k] * df; if (f >= 0.15 && f < 0.40) h.hf += w[1][k] * df; if (f >= 0.003 && f < 0.04) h.vlf += w[1][k] * df; }
        double bp = -1; for (int k = 0; k < w[0].length; k++) if (w[0][k] >= 0.1 && w[0][k] <= 0.4 && w[1][k] > bp) { bp = w[1][k]; h.fpk = w[0][k]; }
        return h;
    }

    // ---------------------------------------------------------------- delineation
    static int sm(double ms) { return (int) Math.round(ms * FS / 1000.0); }
    static double ms(double n) { return n * 1000.0 / FS; }

    static Iv intervals(double[] avg, double rrS) {
        Iv o = delineate(avg, rrS); if (o == null) return null;
        if (!Double.isNaN(o.tEnd)) {
            o.qt = o.tEnd - o.qrsOn; o.qtValid = o.qt >= 280 && o.qt <= 520 && o.tAmp > 20;
            o.qtcB = o.qt / Math.sqrt(rrS); o.qtcF = o.qt / Math.cbrt(rrS); o.qtcFr = o.qt + 154 * (1 - rrS);
        }
        return o;
    }

    static Iv delineate(double[] avgIn, double rrS) {
        double[] a = ProDsp.filtfilt(avgIn, ProDsp.butterLow(4, 40, FS)); int r = PRE; double thrFrac = 0.05;
        double[] d = ProDsp.gradient(a); for (int i = 0; i < d.length; i++) d[i] *= FS;
        int lo = r - sm(40), hi = r + sm(40); if (lo < 2 || hi >= a.length - 2) return null;
        double dm = 0; for (int i = lo; i < hi; i++) dm = Math.max(dm, Math.abs(d[i])); double thr = thrFrac * dm;
        boolean[] act = new boolean[a.length]; for (int i = 0; i < a.length; i++) act[i] = Math.abs(d[i]) > thr;
        int gap = Math.max(1, sm(6)); int i = r, g = 0, on = r;
        while (i > r - sm(120)) { if (act[i]) { on = i; g = 0; } else { g++; if (g > gap) break; } i--; }
        i = r; g = 0; int off = r;
        while (i < r + sm(120)) { if (act[i]) { off = i; g = 0; } else { g++; if (g > gap) break; } i++; }
        int b0 = on - sm(30), b1 = on - sm(10); double base = (b1 > b0 && b0 >= 0) ? ProDsp.median(a, b0, b1) : ProDsp.median(a, Math.max(0, on - sm(30)), on);
        Iv o = new Iv(); o.qrsOn = ms(on - r); o.qrsOff = ms(off - r); o.qrs = ms(off - on); o.base = base; o.rAmp = a[r] - base;
        int j = off + sm(60); if (j + sm(10) < a.length) { double s = 0; int c = 0; for (int k = j - sm(10); k < j + sm(10); k++) { s += a[k]; c++; } o.st = s / c - base; }
        int t0 = off + sm(60), t1 = Math.min(a.length - 1, r + (int) (Math.min(0.55 * rrS, 0.5) * FS));
        double[][] L15 = ProDsp.butterLow(2, 15, FS);
        if (t1 - t0 > sm(60)) {
            double[] ab = new double[a.length]; for (int k = 0; k < a.length; k++) ab[k] = a[k] - base;
            double[] w = ProDsp.filtfilt(ab, L15); int tp = t0; double mx = -1; for (int k = t0; k < t1; k++) if (Math.abs(w[k]) > mx) { mx = Math.abs(w[k]); tp = k; }
            double sgn = (a[tp] - base) >= 0 ? 1 : -1; o.tAmp = a[tp] - base; o.tPeak = ms(tp - r);
            double[] ws = ProDsp.filtfilt(a, L15); double[] g1 = ProDsp.gradient(ws); for (int k = 0; k < g1.length; k++) g1[k] *= FS;
            int e = Math.min(a.length - 2, tp + sm(200)); if (e - tp > 5) {
                int kk = tp; double mn = 1e18; for (int k = tp; k < e; k++) { double v = g1[k] * sgn; if (v < mn) { mn = v; kk = k; } }
                if (mn < 0) { double tt = kk + (base - ws[kk]) / g1[kk] * FS; o.tEnd = ms(tt - r); }
            }
        }
        int p0 = on - sm(230), p1 = on - sm(40);
        if (p0 > sm(20) && p1 - p0 > sm(60)) {
            double[] ab = new double[a.length]; for (int k = 0; k < a.length; k++) ab[k] = a[k] - base;
            double[] f0 = ProDsp.filtfilt(ab, ProDsp.butterLow(2, 20, FS)); double[] w = Arrays.copyOfRange(f0, p0, p1);
            double m1 = ProDsp.median(w, 0, sm(15)), m2 = ProDsp.median(w, w.length - sm(15), w.length);
            for (int k = 0; k < w.length; k++) w[k] -= m1 + (m2 - m1) * k / (w.length - 1);
            int pp = 0; for (int k = 1; k < w.length; k++) if (w[k] > w[pp]) pp = k; double pamp = w[pp];
            if (pamp > 10.0) { double lim = 0.15 * pamp; int ps = pp, pe = pp; while (ps > 0 && w[ps] > lim) ps--; while (pe < w.length - 1 && w[pe] > lim) pe++;
                o.pAmp = pamp; o.pPeak = ms(p0 + pp - r); o.pOn = ms(p0 + ps - r); o.pOff = ms(p0 + pe - r); o.pr = ms(on - (p0 + ps)); o.pDur = ms(pe - ps); }
        }
        return o;
    }
}
