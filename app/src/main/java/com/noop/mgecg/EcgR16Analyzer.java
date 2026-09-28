package com.noop.mgecg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Analyses the strap's STORED 500 Hz ECG (history record type 47, layout 16,
 * 1584 bytes) after a session. Pure Java (no Android) so it can be tested
 * off-device against the Python reference.
 *
 * Record: samples @34, 500 x 3 bytes (tag byte bits 1-0 + 2 bytes = 18-bit
 * signed); tag bit 7 = electrode-contact flag (0 in every quality-0 record,
 * 1 in quality 1-3); quality @21; word count @32-33; record index @11.
 *
 * Method (validated 27 Sep 2026 on this project's 24 stored sessions):
 *  - a second is usable if: 500 samples, strap quality >= 2, contact flag on
 *    >= 450 of 500 samples, no sample beyond +-125,000 (clipping guard)
 *  - consecutive usable seconds form runs; the first 3 s of each run are
 *    skipped (settling); runs need >= 10 s after that
 *  - beats: 2nd-order Butterworth high-pass 5 Hz + low-pass 20 Hz, run
 *    forward and backward; polarity from the larger tail; energy peaks above
 *    0.35 x the 99.5th percentile of a local 15 s window; >= 330 ms apart;
 *    then each beat is placed where it best matches the session's median
 *    beat shape (+-100 ms window, +-25 ms search). No beat is dropped for
 *    being irregular (that would hide AF).
 *  - intervals only within runs; a beat interval differing by > 20% from
 *    the median of its up-to-4 neighbours is set aside; RMSSD only between
 *    adjacent kept intervals
 *  - if > 15% of intervals are set aside the result is Inconclusive (no HR
 *    or rhythm verdict shown)
 *  - with >= 128 clean intervals, the published AF screen (AfScreen, Dash et
 *    al. 2009) runs on the last 128
 * Checked: over the 30 s before the strap's verdict, our RMSSD fell within
 * the strap's own RMSSD values (+-3 ms) in 13/13 readable sessions.
 * Not a diagnosis.
 */
public final class EcgR16Analyzer {

    static final int FS = 500;
    static final int SETTLE_S = 3;
    static final int MIN_RUN_S = 10;

    private EcgR16Analyzer() {}

    public static final class Result {
        public int recordsIn, usableSeconds, intervals, excluded, early;
        public double hr = Double.NaN, rmssd = Double.NaN;
        public boolean screenRun, afLike, inconclusive;
        public double[] dash;
        public String note = "";

        public String toLog() {
            return String.format(Locale.US,
                    "R16_ANALYSIS records=%d usableS=%d intervals=%d excluded=%d early=%d " +
                            "hr=%.1f rmssd=%.1f inconclusive=%b screenRun=%b afLike=%b%s",
                    recordsIn, usableSeconds, intervals, excluded, early, hr, rmssd,
                    inconclusive, screenRun, afLike,
                    dash == null ? "" : String.format(Locale.US,
                            " nrmssd=%.4f she=%.3f tp=%d tpRandom=%b",
                            dash[0], dash[1], (int) dash[2], dash[5] > 0.5));
        }

        public String toHtml() {
            StringBuilder b = new StringBuilder("<b>Stored 500 Hz ECG (from the strap's memory)</b><br>");
            if (usableSeconds == 0 || intervals < 10) {
                return b.append("<small>").append(note.isEmpty() ?
                        "Not enough usable stored ECG for an analysis." : note)
                        .append("</small>").toString();
            }
            b.append(String.format(Locale.UK, "<small>%d s usable, %d beat intervals, " +
                    "%d set aside, %d early beat(s)</small><br>", usableSeconds, intervals, excluded, early));
            if (inconclusive) {
                return b.append(String.format(Locale.UK,
                        "<b>Inconclusive</b> &mdash; %.0f%% of intervals set aside, so no heart " +
                                "rate or rhythm verdict. Hold still with firm finger contact.",
                        100.0 * excluded / intervals)).toString();
            }
            b.append(String.format(Locale.UK, "Heart rate <b>%.0f</b> bpm &middot; HRV (RMSSD) <b>%.0f</b> ms<br>",
                    hr, rmssd));
            if (screenRun) {
                b.append(String.format(Locale.UK,
                        "<b><font color='%s'>Published AF screen: %s</font></b> " +
                                "<small>(nRMSSD %.3f, entropy %.2f, turning points %s)</small>",
                        afLike ? "#FF5555" : "#39FF6A",
                        afLike ? "AF-like pattern" : "no AF-like pattern",
                        dash[0], dash[1], dash[5] > 0.5 ? "random-like" : "not random"));
            } else {
                b.append("<small>Published AF screen not run: it needs 128 clean beats " +
                        "(this session had ").append(intervals - excluded).append(").</small>");
            }
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- decode

    static int u32(byte[] f, int o) {
        return (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8) | ((f[o + 2] & 0xff) << 16) | ((f[o + 3] & 0xff) << 24);
    }

    static boolean usable(byte[] f) {
        if (f.length != 1584) return false;
        if (((f[32] & 0xff) | ((f[33] & 0xff) << 8)) != 500) return false;
        if ((f[21] & 0xff) < 2) return false;
        int contact = 0, maxAbs = 0;
        for (int k = 0; k < 500; k++) {
            int o = 34 + 3 * k;
            int t = f[o] & 0xff;
            contact += (t >> 7) & 1;
            int v = sample(f, k);
            maxAbs = Math.max(maxAbs, Math.abs(v));
        }
        return contact >= 450 && maxAbs < 125000;
    }

    static int sample(byte[] f, int k) {
        int o = 34 + 3 * k;
        int raw = ((f[o] & 0x03) << 16) | ((f[o + 1] & 0xff) << 8) | (f[o + 2] & 0xff);
        return raw >= 131072 ? raw - 262144 : raw;
    }

    // --------------------------------------------------------------- filters

    static double[][] coeffs(boolean low, double fc) {
        double K = Math.tan(Math.PI * fc / FS), q = 1 / Math.sqrt(2), n = 1 / (1 + K / q + K * K);
        double[] b = low ? new double[]{K * K * n, 2 * K * K * n, K * K * n} : new double[]{n, -2 * n, n};
        double[] a = {1, 2 * (K * K - 1) * n, (1 - K / q + K * K) * n};
        return new double[][]{b, a};
    }

    static double[] biquad(double[] x, double[] b, double[] a) {
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double o = b[0] * x[i] + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = o; y[i] = o;
        }
        return y;
    }

    static double[] reverse(double[] x) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = x[x.length - 1 - i];
        return r;
    }

    static double[] filtfilt(double[] x) {
        boolean[] kinds = {false, true};          // high-pass 5 Hz, then low-pass 20 Hz
        double[] fcs = {5.0, 20.0};
        for (int i = 0; i < 2; i++) {
            double[][] c = coeffs(kinds[i], fcs[i]);
            x = biquad(x, c[0], c[1]);
            x = reverse(biquad(reverse(x), c[0], c[1]));
        }
        return x;
    }

    static double percentile(double[] v, int from, int to, double p) {   // numpy 'linear'
        double[] a = Arrays.copyOfRange(v, from, to);
        Arrays.sort(a);
        double pos = p / 100.0 * (a.length - 1);
        int f = (int) Math.floor(pos), c = Math.min(f + 1, a.length - 1);
        return a[f] + (a[c] - a[f]) * (pos - f);
    }

    // ----------------------------------------------------------------- beats

    static List<Integer> peaks(double[] x) {
        int n = x.length;
        double[] y = filtfilt(x);
        double hi = percentile(y, 0, n, 99.5), lo = -percentile(y, 0, n, 0.5);
        if (lo > hi) for (int i = 0; i < n; i++) y[i] = -y[i];
        double[] e = new double[n];
        for (int i = 0; i < n; i++) e[i] = y[i] * y[i];
        int w = 5 * FS;
        double[] thr = new double[n];
        for (int s = 0; s < n; s += w) {
            double t = 0.35 * percentile(e, Math.max(0, s - w), Math.min(n, s + 2 * w), 99.5);
            for (int i = s; i < Math.min(n, s + w); i++) thr[i] = t;
        }
        List<Integer> pk = new ArrayList<>();
        int last = Integer.MIN_VALUE / 2, refr = (int) (0.33 * FS);
        for (int i = 1; i < n - 1; i++) {
            if (e[i] > thr[i] && e[i] >= e[i - 1] && e[i] >= e[i + 1] && i - last >= refr) {
                pk.add(i); last = i;
            }
        }
        // template alignment
        int h = (int) (0.10 * FS), L = (int) (0.025 * FS);
        List<Integer> good = new ArrayList<>();
        for (int p : pk) if (p - h - L >= 0 && p + h + L < n) good.add(p);
        if (good.size() < 5) return pk;
        double[] tmpl = new double[2 * h];
        double[] col = new double[good.size()];
        for (int j = 0; j < 2 * h; j++) {
            for (int g = 0; g < good.size(); g++) col[g] = y[good.get(g) - h + j];
            double[] s = col.clone();
            Arrays.sort(s);
            int m = s.length / 2;
            tmpl[j] = s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2.0;
        }
        double tm = 0;
        for (double v : tmpl) tm += v;
        tm /= tmpl.length;
        double tn = 0;
        for (int j = 0; j < tmpl.length; j++) { tmpl[j] -= tm; tn += tmpl[j] * tmpl[j]; }
        tn = Math.sqrt(tn) + 1e-9;
        List<Integer> ref = new ArrayList<>();
        for (int p : pk) {
            int q = p;
            if (!(p - h - L >= 0 && p + h + L < n)) { ref.add(p); continue; }
            {
                double best = -2;
                int bl = 0;
                for (int l = -L; l <= L; l++) {
                    int a0 = p + l - h;
                    double wm = 0;
                    for (int j = 0; j < 2 * h; j++) wm += y[a0 + j];
                    wm /= 2 * h;
                    double dot = 0, wn = 0;
                    for (int j = 0; j < 2 * h; j++) {
                        double d = y[a0 + j] - wm;
                        dot += d * tmpl[j];
                        wn += d * d;
                    }
                    double c = dot / (Math.sqrt(wn) * tn + 1e-9);
                    if (c > best) { best = c; bl = l; }
                }
                q = p + bl;
            }
            if (ref.isEmpty() || q - ref.get(ref.size() - 1) >= refr) ref.add(q);
        }
        return ref;
    }

    // --------------------------------------------------------------- analyse

    /** @param records stored R16 frames (1584 bytes each), any order */
    public static Result analyse(List<byte[]> records) {
        Result r = new Result();
        r.recordsIn = records.size();
        List<byte[]> recs = new ArrayList<>(records);
        recs.sort((a, b) -> Long.compare(u32(a, 11) & 0xffffffffL, u32(b, 11) & 0xffffffffL));

        List<List<byte[]>> runs = new ArrayList<>();
        List<byte[]> cur = new ArrayList<>();
        for (byte[] f : recs) {
            boolean ok = usable(f);
            if (ok && !cur.isEmpty() && (u32(f, 11) & 0xffffffffL) == (u32(cur.get(cur.size() - 1), 11) & 0xffffffffL) + 1) {
                cur.add(f);
            } else {
                if (cur.size() >= MIN_RUN_S) runs.add(cur);
                cur = new ArrayList<>();
                if (ok) cur.add(f);
            }
        }
        if (cur.size() >= MIN_RUN_S) runs.add(cur);

        List<List<Double>> rrRuns = new ArrayList<>();
        for (List<byte[]> run : runs) {
            if (run.size() - SETTLE_S < MIN_RUN_S) continue;
            List<byte[]> use = run.subList(SETTLE_S, run.size());
            r.usableSeconds += use.size();
            double[] x = new double[500 * use.size()];
            for (int s = 0; s < use.size(); s++)
                for (int k = 0; k < 500; k++) x[500 * s + k] = sample(use.get(s), k);
            List<Integer> pk = peaks(x);
            List<Double> rr = new ArrayList<>();
            for (int i = 1; i < pk.size(); i++) rr.add((pk.get(i) - pk.get(i - 1)) * 1000.0 / FS);
            rrRuns.add(rr);
        }

        List<Double> clean = new ArrayList<>();
        List<Integer> allRr = new ArrayList<>();
        double sumSq = 0;
        int nDiff = 0;
        for (List<Double> rr : rrRuns) {
            boolean[] keep = new boolean[rr.size()];
            for (int i = 0; i < rr.size(); i++) {
                List<Double> nb = new ArrayList<>();
                for (int j = Math.max(0, i - 2); j < i; j++) nb.add(rr.get(j));
                for (int j = i + 1; j < Math.min(rr.size(), i + 3); j++) nb.add(rr.get(j));
                double med = nb.isEmpty() ? rr.get(i) : median(nb);
                keep[i] = Math.abs(rr.get(i) - med) <= 0.2 * med;
                if (!keep[i]) r.excluded++;
                else clean.add(rr.get(i));
                allRr.add((int) Math.round(rr.get(i)));
            }
            for (int i = 0; i < rr.size() - 1; i++) {
                if (keep[i] && keep[i + 1]) {
                    double d = rr.get(i + 1) - rr.get(i);
                    sumSq += d * d;
                    nDiff++;
                }
            }
        }
        r.intervals = allRr.size();
        r.early = AfScreen.earlyBeats(allRr);
        if (r.intervals < 10) {
            r.note = "Only " + r.usableSeconds + " s of settled, good-contact stored ECG.";
            return r;
        }
        r.inconclusive = r.excluded > 0.15 * r.intervals;
        if (!clean.isEmpty()) r.hr = 60000.0 / median(clean);
        if (nDiff > 0) r.rmssd = Math.sqrt(sumSq / nDiff);
        if (!r.inconclusive && clean.size() >= AfScreen.WINDOW) {
            double[] w = new double[AfScreen.WINDOW];
            for (int i = 0; i < AfScreen.WINDOW; i++) w[i] = clean.get(clean.size() - AfScreen.WINDOW + i);
            r.dash = AfScreen.dash(w);
            r.screenRun = true;
            r.afLike = r.dash[6] > 0.5;
        }
        return r;
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }
}
