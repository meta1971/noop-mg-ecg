package com.noop.mgecg;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 0.40.0 (4 Nov 2026): ECG segments from the average beat of ALL readings together, because one short reading has
 * too few clean beats for QRS, PR, ST and QT. Research only, not a diagnosis. Wrist lead (lead-I-like).
 *
 * Per reading: the clean beats (strap quality 3, contact unbroken from 225 ms before to 325 ms after), the trace
 * low-passed at 100 Hz (2nd order, zero phase, NO extra high-pass), polarity from the median deflection, beats aligned
 * on a median template (+-30 ms, two passes), beats with correlation below 0.9 dropped, per-beat baseline = median of
 * -100..-70 ms, mean beat scaled so R = 1000 (R in uV kept). Window -350..+550 ms at 500 Hz (450 samples, R at 175).
 * Stored in files/pooled_beats.csv, one line per reading: start|tag|strap result|beats|R uV|RR ms|450 values.
 * First use seeds the file with 44 readings of 28 Oct to 4 Nov (PooledSeed).
 *
 * Only readings with a similar R wave are pooled together (R within 25 % of the reading shown): on 4 Nov the S dip was
 * about -100 uV in readings with R near 540 uV (before 17:00) and about -8 uV in readings with R near 1,040 uV (after 17:00).
 * Pooled: every reading counts once; segments measured on the mean of the readings' mean beats; 95 % intervals from
 * 200 resamples of the readings. Definitions (section 106.7 / 108 of the decode log): QRS onset and J point where the
 * smoothed slope leaves / returns to 3 x the noise slope (noise from +400..+470 ms); QRS duration also by the energy
 * criterion (2-98 % of the cumulative absolute slope in -80..+120 ms); S notch = small peak (+28..+42 ms) minus the
 * nadir after R (+14..+30 ms); ST = mean level at +60, +80, +100 ms minus the PR level (-100..-70 ms); P onset where the
 * smoothed P falls to 12 % of its height; PR is a LOWER BOUND (the P wave is only about 4 % of R); T end by the
 * tangent method; QTc by Bazett and Fridericia on the median RR.
 */
public final class PooledSegments {
    private PooledSegments() {}

    static final int FS = 500, PRE = 175, POST = 275, LEN = PRE + POST;
    static final String FILE = "pooled_beats.csv";
    static final int MIN_BEATS = 12, MIN_READINGS = 8;

    public static final class Reading {
        public long unix;
        public String tag = "";
        public int res;
        public int n;
        public double rUv, rrMs;
        public double[] beat = new double[LEN];
    }

    public static final class Seg {
        public double qrsOn, j, qrsEnergy, qrsNoise, pAmp, pTime, pOnset, pr, st60, st80, st100;
        public double tAmp, tTime, tEnd, qt, qtcB, qtcF, nadir, notchPeak, notch;
        double[] arr() {
            return new double[] { qrsOn, j, qrsEnergy, qrsNoise, pAmp, pTime, pOnset, pr, st60, st80, st100, tAmp, tTime, tEnd, qt, qtcB, qtcF, nadir, notchPeak, notch };
        }
    }

    public static final class Summary {
        public int readings, beats;
        public double rUv, rrMs;
        public Seg mid = new Seg();
        public double[] lo = new double[20], hi = new double[20];
    }

    // ------------------------------------------------------------------ hook (called once per analysed reading)

    /** Adds the reading's mean beat to the store. Never throws. */
    public static synchronized void addReading(Context c, EcgR16Analyzer.Strip s) {
        if (c == null || s == null) return;
        try {
            Reading r = extract(s);
            if (r == null) return;
            r.tag = c.getSharedPreferences("labrador_ecg_control", Context.MODE_PRIVATE).getString("reading_tag", "");
            if (r.tag == null) r.tag = "";
            List<Reading> all = load(c);
            for (Reading o : all) if (o.unix == r.unix) return;                    // already pooled
            File f = new File(c.getFilesDir(), FILE);
            FileOutputStream out = new FileOutputStream(f, true);
            try { out.write((format(r) + "\n").getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) {
            // best effort only
        }
    }

    // ------------------------------------------------------------------ extraction (pure)

    static Reading extract(EcgR16Analyzer.Strip s) {
        if (s == null || s.rawUv == null || s.mv == null || s.beats == null || s.beats.length < 15) return null;
        double[] raw = s.rawUv, mv = s.mv;
        int n = raw.length;
        if (mv.length != n || n < 3 * FS) return null;
        double[] lp = StrapBeatCheck.runs(raw, ProDsp.butterLow(2, 100, FS), null);
        int[] nanBefore = new int[n + 1];
        for (int i = 0; i < n; i++) nanBefore[i + 1] = nanBefore[i] + (Double.isNaN(raw[i]) || Double.isNaN(lp[i]) ? 1 : 0);

        // refine each strap-quality-3 beat on the filtered trace, keep those with an unbroken window
        List<Integer> ap = new ArrayList<>();
        for (int b : s.beats) {
            int a = Math.max(0, b - 20), z = Math.min(n, b + 21);
            if (z - a < 10) continue;
            double[] seg = Arrays.copyOfRange(mv, a, z);
            boolean ok = true;
            for (double v : seg) if (Double.isNaN(v)) { ok = false; break; }
            if (!ok) continue;
            double med = ProDsp.median(seg), bv = -1;
            int best = a;
            for (int k = 0; k < seg.length; k++) { double v = Math.abs(seg[k] - med); if (v > bv) { bv = v; best = a + k; } }
            int w0 = best - PRE - 50, w1 = best + POST + 50;
            if (w0 < 0 || w1 >= n || nanBefore[w1 + 1] - nanBefore[w0] != 0) continue;
            ap.add(best);
        }
        if (ap.size() < MIN_BEATS) return null;
        // polarity
        double[] defl = new double[ap.size()];
        for (int k = 0; k < defl.length; k++) {
            int i = ap.get(k);
            defl[k] = mv[i] - ProDsp.median(mv, i - 100, i - 40);
        }
        double pol = ProDsp.median(defl) >= 0 ? 1.0 : -1.0;
        int nb = ap.size();
        double[][] seg = new double[nb][LEN + 30];
        for (int k = 0; k < nb; k++) {
            int i = ap.get(k);
            for (int t = 0; t < LEN + 30; t++) seg[k][t] = lp[i - PRE - 15 + t] * pol;
        }
        // template = per-sample median of the unshifted windows, then two alignment passes
        double[] tmpl = new double[LEN];
        double[] col = new double[nb];
        for (int t = 0; t < LEN; t++) {
            for (int k = 0; k < nb; k++) col[k] = seg[k][15 + t];
            tmpl[t] = ProDsp.median(col);
        }
        double[][] al = new double[nb][LEN];
        for (int pass = 0; pass < 2; pass++) {
            double[] tc = Arrays.copyOfRange(tmpl, PRE - 30, PRE + 40);
            double mt = ProDsp.mean(tc);
            for (int k = 0; k < nb; k++) {
                int bestSh = 0; double bestSc = Double.NEGATIVE_INFINITY;
                for (int sh = -15; sh <= 15; sh++) {
                    double mw = 0;
                    for (int t = PRE - 30; t < PRE + 40; t++) mw += seg[k][15 + sh + t];
                    mw /= 70.0;
                    double sc = 0;
                    for (int t = PRE - 30; t < PRE + 40; t++) sc += (seg[k][15 + sh + t] - mw) * (tmpl[t] - mt);
                    if (sc > bestSc) { bestSc = sc; bestSh = sh; }
                }
                for (int t = 0; t < LEN; t++) al[k][t] = seg[k][15 + bestSh + t];
            }
            for (int t = 0; t < LEN; t++) {
                for (int k = 0; k < nb; k++) col[k] = al[k][t];
                tmpl[t] = ProDsp.median(col);
            }
        }
        // keep well-correlated beats, remove each beat's PR level
        List<double[]> keep = new ArrayList<>();
        double[] tq = Arrays.copyOfRange(tmpl, PRE - 40, PRE + 60);
        for (int k = 0; k < nb; k++) {
            double[] w = Arrays.copyOfRange(al[k], PRE - 40, PRE + 60);
            if (ProDsp.corr(w, tq) > 0.9) {
                double base = ProDsp.median(al[k], PRE - 50, PRE - 35);
                double[] b = new double[LEN];
                for (int t = 0; t < LEN; t++) b[t] = al[k][t] - base;
                keep.add(b);
            }
        }
        if (keep.size() < MIN_BEATS) return null;
        double[] mean = new double[LEN];
        for (double[] b : keep) for (int t = 0; t < LEN; t++) mean[t] += b[t] / keep.size();
        double R = Double.NEGATIVE_INFINITY;
        for (int t = PRE - 4; t <= PRE + 4; t++) R = Math.max(R, mean[t]);
        if (!(R > 100)) return null;
        Reading r = new Reading();
        r.unix = s.startUnix;
        r.res = s.strapResult;
        r.n = keep.size();
        r.rUv = R;
        for (int t = 0; t < LEN; t++) r.beat[t] = mean[t] * 1000.0 / R;
        List<Double> rr = new ArrayList<>();
        for (int k = 1; k < s.beats.length; k++) {
            double d = (s.beats[k] - s.beats[k - 1]) * 1000.0 / FS;
            if (d > 400 && d < 1600) rr.add(d);
        }
        double[] ra = new double[rr.size()];
        for (int k = 0; k < ra.length; k++) ra[k] = rr.get(k);
        r.rrMs = ra.length > 0 ? ProDsp.median(ra) : 950.0;
        return r;
    }

    // ------------------------------------------------------------------ storage

    static String format(Reading r) {
        StringBuilder b = new StringBuilder(4000);
        b.append(r.unix).append('|').append(r.tag.replace('|', ' ').replace('\n', ' ')).append('|').append(r.res).append('|').append(r.n)
                .append('|').append(Math.round(r.rUv)).append('|').append(Math.round(r.rrMs)).append('|');
        for (int t = 0; t < LEN; t++) { if (t > 0) b.append(';'); b.append(String.format(Locale.US, "%.1f", r.beat[t])); }
        return b.toString();
    }

    static Reading parse(String line) {
        try {
            String[] p = line.split("\\|", -1);
            if (p.length < 7) return null;
            Reading r = new Reading();
            r.unix = Long.parseLong(p[0].trim());
            r.tag = p[1];
            r.res = Integer.parseInt(p[2].trim());
            r.n = Integer.parseInt(p[3].trim());
            r.rUv = Double.parseDouble(p[4].trim());
            r.rrMs = Double.parseDouble(p[5].trim());
            String[] v = p[6].split(";");
            if (v.length != LEN) return null;
            for (int t = 0; t < LEN; t++) r.beat[t] = Double.parseDouble(v[t]);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Seeds the store on first use, then returns every stored reading. */
    static List<Reading> load(Context c) {
        List<Reading> out = new ArrayList<>();
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists()) {
                FileOutputStream o = new FileOutputStream(f);
                try { for (String l : PooledSeed.LINES) o.write((l + "\n").getBytes("UTF-8")); } finally { o.close(); }
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = br.readLine()) != null) { Reading r = parse(l); if (r != null) out.add(r); }
            } finally { br.close(); }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ pooled measurement (pure)

    /** tag empty = all readings. Readings the strap called unreadable (result 2) and readings with few beats are left out. */
    static Summary summarize(List<Reading> all, String tag, double rRef) {
        List<Reading> use = new ArrayList<>();
        for (Reading r : all) {
            if (r.res == 2 || r.n < MIN_BEATS) continue;
            if (rRef > 0 && (r.rUv < 0.75 * rRef || r.rUv > 1.33 * rRef)) continue;
            if (tag != null && !tag.isEmpty() && !tag.equals(r.tag)) continue;
            use.add(r);
        }
        Summary s = new Summary();
        s.readings = use.size();
        if (use.size() < 1) return s;
        double[] rr = new double[use.size()];
        for (int k = 0; k < rr.length; k++) { rr[k] = use.get(k).rrMs; s.beats += use.get(k).n; s.rUv += use.get(k).rUv / rr.length; }
        s.rrMs = ProDsp.median(rr);
        s.mid = measure(meanOf(use, null), s.rrMs / 1000.0);
        if (use.size() >= 3) {
            Random rnd = new Random(7);
            int B = 200;
            double[][] vals = new double[20][B];
            for (int b = 0; b < B; b++) {
                int[] idx = new int[use.size()];
                for (int k = 0; k < idx.length; k++) idx[k] = rnd.nextInt(use.size());
                double[] rrb = new double[idx.length];
                for (int k = 0; k < idx.length; k++) rrb[k] = use.get(idx[k]).rrMs;
                double[] a = measure(meanOf(use, idx), ProDsp.median(rrb) / 1000.0).arr();
                for (int m = 0; m < 20; m++) vals[m][b] = a[m];
            }
            for (int m = 0; m < 20; m++) {
                double[] v = vals[m];
                int cnt = 0;
                for (double x : v) if (!Double.isNaN(x)) cnt++;
                if (cnt < 20) { s.lo[m] = Double.NaN; s.hi[m] = Double.NaN; continue; }
                double[] q = new double[cnt];
                int w = 0;
                for (double x : v) if (!Double.isNaN(x)) q[w++] = x;
                s.lo[m] = ProDsp.percentile(q, 2.5);
                s.hi[m] = ProDsp.percentile(q, 97.5);
            }
        }
        return s;
    }

    static double[] meanOf(List<Reading> use, int[] idx) {
        double[] m = new double[LEN];
        int n = idx == null ? use.size() : idx.length;
        for (int k = 0; k < n; k++) {
            Reading r = idx == null ? use.get(k) : use.get(idx[k]);
            for (int t = 0; t < LEN; t++) m[t] += r.beat[t] / n;
        }
        return m;
    }

    static int ms(double a) { return (int) Math.round(a * FS / 1000.0); }

    static double[] box(double[] x, int k) {
        int h = k / 2;
        double[] o = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double s = 0;
            for (int j = -h; j <= h; j++) { int q = i + j; if (q >= 0 && q < x.length) s += x[q]; }
            o[i] = s / k;
        }
        return o;
    }

    /** P: mean beat with R = 1000; rrS: median RR in seconds. */
    static Seg measure(double[] P, double rrS) {
        Seg o = new Seg();
        int k = ms(16) | 1;
        double[] Ps = box(P, k);
        // noise of the T-P baseline, -330..-250 ms, about a straight line
        int a0 = PRE - ms(330), a1 = PRE - ms(250), na = a1 - a0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < na; i++) { double y = P[a0 + i]; sx += i; sy += y; sxx += (double) i * i; sxy += i * y; }
        double slope = (na * sxy - sx * sy) / (na * sxx - sx * sx), icpt = (sy - slope * sx) / na, ss = 0;
        for (int i = 0; i < na; i++) { double r = P[a0 + i] - (icpt + slope * i); ss += r * r; }
        double sig = Math.sqrt(ss / na);
        // P wave
        int a = PRE - ms(260), b = PRE - ms(70), iP = a;
        for (int i = a; i < b; i++) if (Ps[i] > Ps[iP]) iP = i;
        double pamp = Ps[iP];
        o.pAmp = pamp; o.pTime = (iP - PRE) * 1000.0 / FS;
        double base = ProDsp.median(Ps, PRE - ms(100), PRE - ms(70));
        int i = iP;
        while (i > PRE - ms(300) && Ps[i] - base > Math.max(2 * sig * 0.6, 0.12 * (pamp - base))) i--;
        o.pOnset = (i - PRE) * 1000.0 / FS;
        // QRS onset and J by slope noise
        double[] vs = box(P, ms(6) | 1);
        double[] d = ProDsp.gradient(vs);
        for (int q = 0; q < d.length; q++) d[q] *= FS;
        double[] refd = Arrays.copyOfRange(d, PRE + ms(400), PRE + ms(470));
        double med = ProDsp.median(refd);
        double[] dev = new double[refd.length];
        for (int q = 0; q < dev.length; q++) dev[q] = Math.abs(refd[q] - med);
        double sd = 1.48 * ProDsp.median(dev), th = 3 * sd;
        int n = ms(6);
        i = PRE - ms(10);
        while (i > PRE - ms(150) && !allBelow(d, i - n, i, th)) i--;
        double on = (i - PRE) * 1000.0 / FS;
        int e = PRE + ms(10);
        while (e < PRE + ms(200) && !allBelow(d, e, e + n, th)) e++;
        double jp = (e - PRE) * 1000.0 / FS;
        o.qrsOn = on; o.j = jp; o.qrsNoise = jp - on;
        // energy criterion
        int s0 = PRE - ms(80), s1 = PRE + ms(120);
        double tot = 0;
        for (int q = s0; q < s1; q++) tot += Math.abs(d[q]);
        double cs = 0;
        int o2 = -1, e2 = -1;
        for (int q = s0; q < s1; q++) {
            cs += Math.abs(d[q]);
            double f = cs / tot;
            if (o2 < 0 && f >= 0.02) o2 = q;
            if (e2 < 0 && f >= 0.98) e2 = q;
        }
        o.qrsEnergy = (e2 - o2) * 1000.0 / FS;
        o.pr = on - o.pOnset;
        double pr = ProDsp.median(P, PRE - ms(100), PRE - ms(70));
        int[] tt = { 60, 80, 100 };
        double[] stv = new double[3];
        for (int q = 0; q < 3; q++) {
            double m = 0;
            for (int z = PRE + ms(tt[q]) - 2; z < PRE + ms(tt[q]) + 3; z++) m += P[z] / 5.0;
            stv[q] = m - pr;
        }
        o.st60 = stv[0]; o.st80 = stv[1]; o.st100 = stv[2];
        // T wave
        a = PRE + ms(150); b = PRE + ms(420);
        int iT = a;
        for (int q = a; q < b; q++) if (Ps[q] > Ps[iT]) iT = q;
        o.tAmp = Ps[iT]; o.tTime = (iT - PRE) * 1000.0 / FS;
        double[] dd = ProDsp.gradient(Ps);
        for (int q = 0; q < dd.length; q++) dd[q] *= FS;
        int j0 = iT, j1 = Math.min(Ps.length - 3, iT + ms(150)), iM = j0;
        for (int q = j0; q < j1; q++) if (dd[q] < dd[iM]) iM = q;
        double sl = dd[iM];
        double te = sl < 0 ? (iM + (0 - Ps[iM]) / sl * FS - PRE) * 1000.0 / FS : Double.NaN;
        o.tEnd = te; o.qt = te - on;
        o.qtcB = o.qt / Math.sqrt(rrS);
        o.qtcF = o.qt / Math.cbrt(rrS);
        // S notch
        double nad = Double.POSITIVE_INFINITY, pk = Double.NEGATIVE_INFINITY;
        for (int q = PRE + ms(14); q < PRE + ms(30); q++) nad = Math.min(nad, P[q]);
        for (int q = PRE + ms(28); q < PRE + ms(42); q++) pk = Math.max(pk, P[q]);
        o.nadir = nad; o.notchPeak = pk; o.notch = pk - nad;
        return o;
    }

    static boolean allBelow(double[] d, int a, int b, double th) {
        if (a < 0) return true;
        for (int q = a; q < b; q++) if (Math.abs(d[q]) >= th) return false;
        return true;
    }

    // ------------------------------------------------------------------ report rows

    private static String rng(double v, double lo, double hi, String fmt) {
        if (Double.isNaN(v)) return "not measured";
        String s = String.format(Locale.US, fmt, v);
        if (!Double.isNaN(lo) && !Double.isNaN(hi) && (lo != 0 || hi != 0)) s += String.format(Locale.US, " (" + fmt + " to " + fmt + ")", lo, hi);
        return s;
    }

    /** {label, value} rows for the report card; empty when fewer than MIN_READINGS readings. */
    static String[][] rows(Summary s) {
        if (s == null || s.readings < MIN_READINGS) return new String[0][];
        Seg m = s.mid;
        double k = s.rUv / 1000.0;
        List<String[]> r = new ArrayList<>();
        r.add(new String[] { "Readings pooled", s.readings + " readings with a similar R wave, " + s.beats + " clean beats; median heart rate " + Math.round(60000.0 / s.rrMs) + "; R wave " + Math.round(s.rUv) + " uV" });
        r.add(new String[] { "QRS duration", rng(m.qrsEnergy, s.lo[2], s.hi[2], "%.0f") + " ms (energy criterion); " + rng(m.qrsNoise, s.lo[3], s.hi[3], "%.0f") + " ms (slope criterion)" });
        r.add(new String[] { "QRS onset / J point", rng(m.qrsOn, s.lo[0], s.hi[0], "%.0f") + " ms / " + rng(m.j, s.lo[1], s.hi[1], "%.0f") + " ms from the R peak" });
        double sDip = m.nadir * k;
        r.add(new String[] { "S dip after R", String.format(Locale.US, "%.0f uV against the PR level at about +24 ms (%s); notch %.0f uV deep", sDip, sDip < -30 ? "a clear dip" : "small or none", m.notch * k) });
        r.add(new String[] { "ST level", String.format(Locale.US, "%.0f / %.0f / %.0f uV at +60 / +80 / +100 ms against the PR level", m.st60 * k, m.st80 * k, m.st100 * k) });
        r.add(new String[] { "P wave", String.format(Locale.US, "%.0f uV at %.0f ms, onset %.0f ms", m.pAmp * k, m.pTime, m.pOnset) });
        r.add(new String[] { "PR interval", "at least " + rng(m.pr, s.lo[7], s.hi[7], "%.0f") + " ms (lower bound: the P wave is small)" });
        r.add(new String[] { "T wave", String.format(Locale.US, "%.0f uV, peak %s ms, end %s ms", m.tAmp * k, rng(m.tTime, s.lo[12], s.hi[12], "%.0f"), rng(m.tEnd, s.lo[13], s.hi[13], "%.0f")) });
        r.add(new String[] { "QT interval", rng(m.qt, s.lo[14], s.hi[14], "%.0f") + " ms; corrected Fridericia " + rng(m.qtcF, s.lo[16], s.hi[16], "%.0f") + ", Bazett " + rng(m.qtcB, s.lo[15], s.hi[15], "%.0f") });
        return r.toArray(new String[0][]);
    }
}
