package com.noop.mgecg;

// FILE VERSION 0.45.0 (8 Nov 2026): the personal beat bank (decode log 50.6 item 3 (d)). From every reading the 40 cleanest beats (ProAnalysis.Result.bankBeats: matched the reading's own template over 0.95, R height 60 to 140 %,
// outside the step windows, lowest local noise) are kept in a small file beatbank_<start>.bin beside the reading (int16, quarter microvolts, about 52 KB). The files are pooled on the phone and never leave it unless
// the person sends them. With 1,000 or more pooled beats the detail view unlocks, provided the app's own split-half test passes (ProAnalysis splitQrs / splitCtl: the 40-150 Hz trace of two random halves must
// agree in the QRS window and not in the quiet T-P window). Nothing above 100 Hz is claimed (decode log 134.1: that detail failed its control). Pure Java apart from File.

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

final class BeatBank {
    private BeatBank() {}
    static final int PRE = ProAnalysis.PRE, POST = ProAnalysis.POST, L = PRE + POST, PER_READING = 40, UNLOCK = 1000, MAX_POOL = 4000;
    static final double Q = 4.0;                       // counts per microvolt in the file
    static final int MAGIC = 0x4D474231;               // "MGB1"

    static File fileFor(File dir, long startSec) { return new File(dir, "beatbank_" + startSec + ".bin"); }

    /** Writes the beats of one reading (an empty list writes a header only, which marks the reading as done). */
    static boolean save(File dir, long startSec, double rrS, double[][] beats) {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileFor(dir, startSec))))) {
            int n = beats == null ? 0 : beats.length;
            o.writeInt(MAGIC); o.writeLong(startSec); o.writeFloat((float) (Double.isNaN(rrS) ? 1.0 : rrS)); o.writeShort(n); o.writeShort(L);
            for (int k = 0; k < n; k++) for (int i = 0; i < L; i++) { long v = Math.round(beats[k][i] * Q); o.writeShort((int) Math.max(-32767, Math.min(32767, v))); }
            return true;
        } catch (Throwable e) { return false; }
    }

    static final class Rd { long start; float rrS; double[][] beats; }

    static Rd read(File f, boolean withBeats) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) return null;
            Rd r = new Rd(); r.start = in.readLong(); r.rrS = in.readFloat(); int n = in.readShort(), len = in.readShort();
            if (len != L || n < 0 || n > 400) return null;
            r.beats = new double[withBeats ? n : 0][];
            if (!withBeats) { r.beats = new double[n][]; return r; }
            for (int k = 0; k < n; k++) { double[] b = new double[L]; for (int i = 0; i < L; i++) b[i] = in.readShort() / Q; r.beats[k] = b; }
            return r;
        } catch (Throwable e) { return null; }
    }

    static List<File> files(File dir) {
        List<File> out = new ArrayList<>(); File[] fs = dir.listFiles(); if (fs == null) return out;
        for (File f : fs) { String n = f.getName(); if (n.startsWith("beatbank_") && n.endsWith(".bin")) out.add(f); }
        Collections.sort(out); return out;
    }

    static final class Summary { int readings, beats; }

    /** Counts only (headers). */
    static Summary summary(File dir) {
        Summary s = new Summary();
        for (File f : files(dir)) { Rd r = read(f, false); if (r != null && r.beats.length > 0) { s.readings++; s.beats += r.beats.length; } }
        return s;
    }

    /** Readings in dir that have a live raw bin of at least 20 s and no bank file yet (the bin's unix start is in its name). */
    static List<File> pending(File dir) {
        List<File> out = new ArrayList<>(); File[] fs = dir.listFiles(); if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName(); if (!n.startsWith("realtime_ecg_bin_") || !n.endsWith(".bin") || f.length() < 20L * 1584L) continue;
            long u; try { u = Long.parseLong(n.substring(17, n.length() - 4)); } catch (NumberFormatException e) { continue; }
            if (fileFor(dir, u).exists() || System.currentTimeMillis() - f.lastModified() < 90000L) continue;      // a reading still being written is left for later
            out.add(f);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));      // newest first
        return out;
    }

    /** Analyses up to max readings that have no bank file yet and writes theirs (an empty one when the analysis finds nothing, so it is not tried again). Slow: call from a background thread. Returns how many were done. */
    static int catchUp(File dir, int max) {
        int done = 0;
        for (File f : pending(dir)) {
            if (done >= max) break;
            long u = Long.parseLong(f.getName().substring(17, f.getName().length() - 4));
            try {
                ProAnalysis.Result r = ProAnalysis.analyse(Files.readAllBytes(f.toPath()));
                if (r == null || r.bankBeats.length == 0) save(dir, u, Double.NaN, null);
                else save(dir, u, ProDsp.median(r.rrMs) / 1000.0, r.bankBeats);
            } catch (Throwable e) { save(dir, u, Double.NaN, null); }
            done++;
        }
        return done;
    }

    /** 0.46.0: the median beat of the whole bank (121 samples, 60 before to 60 after the R apex, uV), for the live detector's template veto; null with fewer than 100 beats. n[0] = beats used. Cheap: no alignment, the beats of a reading are already apex-aligned. */
    static double[] template(File dir, int[] n) {
        List<double[]> all = new ArrayList<>();
        List<File> fs = files(dir);
        for (int i = fs.size() - 1; i >= 0 && all.size() < MAX_POOL; i--) { Rd r = read(fs.get(i), true); if (r == null) continue; Collections.addAll(all, r.beats); }
        if (all.size() < 100) return null;
        double[] t = new double[121], col = new double[all.size()];
        for (int j = 0; j < 121; j++) { for (int k = 0; k < col.length; k++) col[k] = all.get(k)[PRE - 60 + j]; t[j] = ProDsp.median(col); }
        if (n != null && n.length > 0) n[0] = all.size();
        return t;
    }

    static final class Pooled {
        int readings, loaded, kept; double rrS = 1.0;
        double[] mean; ProAnalysis.Iv iv;
        double splitQrs = Double.NaN, splitCtl = Double.NaN;
        boolean unlocked;
        double qTrough = Double.NaN, qTroughMs = Double.NaN;
    }

    /** Pools every bank file: global alignment (5-30 Hz, two passes against the median beat), template correlation over 0.95, mean beat, split-half test, measurements. Slow with thousands of beats: background thread. */
    static Pooled pool(File dir) {
        List<double[]> all = new ArrayList<>(); List<Double> rrs = new ArrayList<>(); int readings = 0;
        List<File> fs = files(dir);
        for (int i = fs.size() - 1; i >= 0 && all.size() < MAX_POOL; i--) {      // newest first, up to MAX_POOL beats
            Rd r = read(fs.get(i), true); if (r == null || r.beats.length == 0) continue;
            readings++; rrs.add((double) r.rrS); Collections.addAll(all, r.beats);
        }
        Pooled P = new Pooled(); P.readings = readings; P.loaded = all.size();
        if (all.size() < 30) return P;
        double[] ra = new double[rrs.size()]; for (int i = 0; i < ra.length; i++) ra[i] = rrs.get(i); P.rrS = ProDsp.median(ra);
        int nb = all.size(); double[][] BP = ProDsp.bandPass(5, 30, ProAnalysis.FS);
        double[][] Lb = new double[nb][]; for (int k = 0; k < nb; k++) Lb[k] = ProDsp.filtfilt(all.get(k), BP);
        double[] sh = new double[nb]; double[] T = ProAnalysis.med(Lb, nb, L); double[][] cur = new double[nb][];
        for (int it = 0; it < 2; it++) {
            for (int k = 0; k < nb; k++) { cur[k] = ProAnalysis.shifted(Lb[k], sh[k]); sh[k] += ProAnalysis.lag(cur[k], T); cur[k] = ProAnalysis.shifted(Lb[k], sh[k]); }
            T = ProAnalysis.med(cur, nb, L);
        }
        List<double[]> kept = new ArrayList<>();
        for (int k = 0; k < nb; k++) if (ProAnalysis.corrWin(cur[k], T, PRE - 40, PRE + 60) > 0.95) kept.add(ProAnalysis.shifted(all.get(k), sh[k]));
        int N = kept.size(); P.kept = N; if (N < 30) return P;
        double[] mean = new double[L]; for (double[] b : kept) for (int i = 0; i < L; i++) mean[i] += b[i] / N; P.mean = mean;
        P.iv = ProAnalysis.intervals(mean, P.rrS, N);
        if (P.iv != null) { double mn = 1e18; int mi = PRE - 40; for (int i = PRE - 40; i <= PRE - 4; i++) if (mean[i] < mn) { mn = mean[i]; mi = i; } P.qTrough = mn - P.iv.base; P.qTroughMs = (mi - PRE) * 1000.0 / ProAnalysis.FS; }
        // the app's split-half test (as ProAnalysis): 40-150 Hz trace of two random halves, QRS window against the quiet T-P window
        double[][] HFB = ProDsp.bandPass(40, 150, ProAnalysis.FS); double sq = 0, sc = 0; int NS = 20, h = N / 2;
        for (int s = 0; s < NS; s++) {
            List<Integer> idx = new ArrayList<>(); for (int i = 0; i < N; i++) idx.add(i); Collections.shuffle(idx, new Random(s));
            double[] m1 = new double[L], m2 = new double[L];
            for (int i = 0; i < h; i++) { double[] b = kept.get(idx.get(i)); for (int j = 0; j < L; j++) m1[j] += b[j] / h; }
            for (int i = h; i < 2 * h; i++) { double[] b = kept.get(idx.get(i)); for (int j = 0; j < L; j++) m2[j] += b[j] / h; }
            double[] f1 = ProDsp.filtfilt(m1, HFB), f2 = ProDsp.filtfilt(m2, HFB);
            sq += ProDsp.corr(Arrays.copyOfRange(f1, ProAnalysis.QW0, ProAnalysis.QW1), Arrays.copyOfRange(f2, ProAnalysis.QW0, ProAnalysis.QW1)) / NS;
            sc += ProDsp.corr(Arrays.copyOfRange(f1, PRE + 225, PRE + 275), Arrays.copyOfRange(f2, PRE + 225, PRE + 275)) / NS;
        }
        P.splitQrs = sq; P.splitCtl = sc;
        P.unlocked = N >= UNLOCK && sq >= 0.9 && sq - sc >= 0.1;
        return P;
    }
}
