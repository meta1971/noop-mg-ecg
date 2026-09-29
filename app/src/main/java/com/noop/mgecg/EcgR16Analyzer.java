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
 *
 * Beat-shape gate (added 28 Sep after a noisy session the strap called
 * Unreadable: 27% of beats misshapen, misplaced beats 13-18% off time slipped
 * past the 20% rule and pushed nRMSSD to 0.090). Each beat (+-100 ms) is
 * correlated with its run's median beat; below 0.8 = bad shape. Intervals
 * touching a bad-shape beat are left out of HR, HRV and the AF screen.
 * Inconclusive if > 15% of intervals fail the rhythm rule OR > 10% of beats
 * fail the shape test. On 25 sessions: all 16 the strap could read (codes 1
 * and 6) give a result (bad shapes 0-9.4%); 6 of 7 it called unreadable
 * (code 2) come out Inconclusive (5.9-82%); RMSSD agreement stays 13/13.
 * Not a diagnosis.
 *
 * Contact and units (added 28 Sep, from the official WHOOP app + this
 * project's data; constants in EcgWhoopSpec):
 *  - a second is also unusable if any sample carries tag bit 6 (the
 *    amplifier's 500 ms fast-recovery window after saturation) or if the
 *    electrode-impedance block @1534 reads lead-off (mean I > 200)
 *  - R-wave amplitude and beat-to-beat noise are reported in microvolts
 *    (R16 = 0.0621 uV/count, derived from R17 = 1 uV/count), measured on the
 *    zero-phase 0.67-40 Hz + 50 Hz-notch signal
 */
public final class EcgR16Analyzer {

    static final int FS = 500;
    static final int SETTLE_S = 3;
    static final int MIN_RUN_S = 10;

    private EcgR16Analyzer() {}

    public static final class Result {
        public int recordsIn, usableSeconds, intervals, excluded, early, rhythmOut;
        public int beatsChecked, beatsBadShape;
        public double noiseFraction;
        public double hr = Double.NaN, rmssd = Double.NaN;
        public int fastRecoverySeconds, leadOffSeconds;
        public double rAmpUv = Double.NaN, noiseUv = Double.NaN;
        public boolean inverted;
        public boolean screenRun, afLike, inconclusive;
        public double[] dash;
        public String note = "";

        public String toLog() {
            return String.format(Locale.US,
                    "R16_ANALYSIS records=%d usableS=%d intervals=%d excluded=%d rhythmOut=%d " +
                            "badShape=%d/%d early=%d hr=%.1f rmssd=%.1f inconclusive=%b " +
                            "screenRun=%b afLike=%b rAmpUv=%.0f noiseUv=%.0f inverted=%b " +
                            "fastRecoveryS=%d leadOffS=%d%s",
                    recordsIn, usableSeconds, intervals, excluded, rhythmOut,
                    beatsBadShape, beatsChecked, early, hr, rmssd,
                    inconclusive, screenRun, afLike, rAmpUv, noiseUv, inverted,
                    fastRecoverySeconds, leadOffSeconds,
                    dash == null ? "" : String.format(Locale.US,
                            " nrmssd=%.4f she=%.3f tp=%d tpRandom=%b",
                            dash[0], dash[1], (int) dash[2], dash[5] > 0.5));
        }

        public String toHtml() {
            StringBuilder b = new StringBuilder("<b>Stored 500 Hz ECG (from the strap's memory)</b><br>");
            if (leadOffSeconds > 0 || fastRecoverySeconds > 0) {
                b.append(String.format(Locale.UK, "<small>Contact: fingers off for %d s, " +
                        "amplifier recovering for %d s (both left out)</small><br>",
                        leadOffSeconds, fastRecoverySeconds));
            }
            if (usableSeconds == 0 || intervals < 10) {
                return b.append("<small>").append(note.isEmpty() ?
                        "Not enough usable stored ECG for an analysis." : note)
                        .append("</small>").toString();
            }
            b.append(String.format(Locale.UK, "<small>%d s usable, %d beat intervals, " +
                    "%d set aside, %d early beat(s)</small><br>", usableSeconds, intervals, excluded, early));
            if (!Double.isNaN(rAmpUv)) {
                b.append(String.format(Locale.UK, "<small>R wave %.2f mV%s &middot; " +
                                "beat-to-beat noise %.0f &micro;V (the chip itself adds ~0.6 " +
                                "&micro;V; the rest is muscle and contact)</small><br>",
                        rAmpUv / 1000.0, inverted ? " (inverted)" : "", noiseUv));
            }
            if (inconclusive) {
                String why = noiseFraction > 0.10
                        ? String.format(Locale.UK, "%.0f%% of beats did not look like a clean " +
                        "heartbeat (signal too noisy)", 100 * noiseFraction)
                        : String.format(Locale.UK, "%.0f%% of beat intervals were irregular or " +
                        "mis-detected", 100.0 * rhythmOut / intervals);
                return b.append("<b>Inconclusive</b> &mdash; ").append(why).append(", so no heart " +
                        "rate or rhythm verdict. Hold still with firm finger contact and try again.")
                        .toString();
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
        if ((f[21] & 0xff) < 3) return false;   // quality 3 only, matches the live path
        int contact = 0, maxAbs = 0;
        for (int k = 0; k < 500; k++) {
            int o = 34 + 3 * k;
            int t = f[o] & 0xff;
            contact += (t >> 7) & 1;
            int v = sample(f, k);
            maxAbs = Math.max(maxAbs, Math.abs(v));
        }
        return contact >= 450 && maxAbs < 125000
                && EcgWhoopSpec.r16FastRecoveryCount(f) == 0
                && EcgWhoopSpec.r16LeadOffMeanI(f) <= EcgWhoopSpec.LEAD_OFF_I_THRESHOLD;
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
        for (byte[] f : recs) {
            if (f.length != 1584 || EcgWhoopSpec.r16Count(f) == 0) continue;
            if (EcgWhoopSpec.r16FastRecoveryCount(f) > 0) r.fastRecoverySeconds++;
            if (EcgWhoopSpec.r16LeadOffCount(f) > 250
                    || EcgWhoopSpec.r16LeadOffMeanI(f) > EcgWhoopSpec.LEAD_OFF_I_THRESHOLD) r.leadOffSeconds++;
        }
        List<Double> ampAll = new ArrayList<>(), noiseAll = new ArrayList<>();
        int invRuns = 0, runsMeasured = 0;

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
        List<List<Boolean>> badRuns = new ArrayList<>();
        for (List<byte[]> run : runs) {
            if (run.size() - SETTLE_S < MIN_RUN_S) continue;
            List<byte[]> use = run.subList(SETTLE_S, run.size());
            r.usableSeconds += use.size();
            double[] x = new double[500 * use.size()];
            for (int s = 0; s < use.size(); s++)
                for (int k = 0; k < 500; k++) x[500 * s + k] = sample(use.get(s), k);
            List<Integer> pk = peaks(x);
            // same filtered, polarity-corrected signal peaks() used
            double[] y = filtfilt(x);
            int n = y.length;
            double hi = percentile(y, 0, n, 99.5), lo = -percentile(y, 0, n, 0.5);
            boolean inv = lo > hi;
            if (inv) for (int i = 0; i < n; i++) y[i] = -y[i];
            int h = 50;
            List<Integer> ok = new ArrayList<>();
            for (int p : pk) if (p - h >= 0 && p + h < n) ok.add(p);
            java.util.Set<Integer> bad = new java.util.HashSet<>();
            if (ok.size() >= 5) {
                double[] T = new double[2 * h];
                double[] col = new double[ok.size()];
                for (int j = 0; j < 2 * h; j++) {
                    for (int g = 0; g < ok.size(); g++) col[g] = y[ok.get(g) - h + j];
                    double[] srt = col.clone();
                    Arrays.sort(srt);
                    int m = srt.length / 2;
                    T[j] = srt.length % 2 == 1 ? srt[m] : (srt[m - 1] + srt[m]) / 2.0;
                }
                for (int p : ok) if (pearson(y, p - h, T) < 0.8) bad.add(p);
            }
            r.beatsChecked += ok.size();
            r.beatsBadShape += bad.size();
            if (measureAmplitude(x, pk, bad, inv, ampAll, noiseAll)) {
                runsMeasured++;
                if (inv) invRuns++;
            }
            List<Double> rr = new ArrayList<>();
            List<Boolean> bd = new ArrayList<>();
            for (int i = 1; i < pk.size(); i++) {
                rr.add((pk.get(i) - pk.get(i - 1)) * 1000.0 / FS);
                bd.add(bad.contains(pk.get(i - 1)) || bad.contains(pk.get(i)));
            }
            rrRuns.add(rr);
            badRuns.add(bd);
        }

        List<Double> clean = new ArrayList<>();
        List<Integer> allRr = new ArrayList<>();
        double sumSq = 0;
        int nDiff = 0;
        for (int ri = 0; ri < rrRuns.size(); ri++) {
            List<Double> rr = rrRuns.get(ri);
            List<Boolean> bd = badRuns.get(ri);
            boolean[] keep = new boolean[rr.size()];
            for (int i = 0; i < rr.size(); i++) {
                List<Double> nb = new ArrayList<>();
                for (int j = Math.max(0, i - 2); j < i; j++) nb.add(rr.get(j));
                for (int j = i + 1; j < Math.min(rr.size(), i + 3); j++) nb.add(rr.get(j));
                double med = nb.isEmpty() ? rr.get(i) : median(nb);
                boolean rhythmOk = Math.abs(rr.get(i) - med) <= 0.2 * med;
                if (!rhythmOk) r.rhythmOut++;
                keep[i] = rhythmOk && !bd.get(i);
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
        if (!ampAll.isEmpty()) r.rAmpUv = median(ampAll);
        if (!noiseAll.isEmpty()) r.noiseUv = median(noiseAll);
        r.inverted = runsMeasured > 0 && invRuns * 2 > runsMeasured;
        if (r.intervals < 10) {
            r.note = "Only " + r.usableSeconds + " s of settled, good-contact stored ECG.";
            return r;
        }
        r.noiseFraction = r.beatsBadShape / (double) Math.max(1, r.beatsChecked);
        r.inconclusive = r.rhythmOut > 0.15 * r.intervals || r.noiseFraction > 0.10;
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

    /*
     * R-wave amplitude (uV) and beat-to-beat noise (uV RMS of each beat minus
     * the run's median beat, -300..+550 ms) on the zero-phase 0.67-40 Hz +
     * 50 Hz-notch signal. Baseline = median of -200..-80 ms (PR segment).
     * Misshapen beats are skipped. Returns false if too few beats.
     */
    static boolean measureAmplitude(double[] counts, List<Integer> pk, java.util.Set<Integer> bad,
                                    boolean inv, List<Double> ampOut, List<Double> noiseOut) {
        double[] u = new double[counts.length];
        for (int i = 0; i < u.length; i++) u[i] = counts[i] * EcgWhoopSpec.R16_UV_PER_COUNT;
        double[] z = EcgWhoopSpec.improvedFilter(u);
        int pre = (int) (0.30 * FS), post = (int) (0.55 * FS), srch = (int) (0.03 * FS);
        int b0 = (int) (0.20 * FS), b1 = (int) (0.08 * FS);
        List<double[]> beats = new ArrayList<>();
        List<Double> amps = new ArrayList<>();
        for (int p : pk) {
            if (bad.contains(p) || p - pre < 0 || p + post >= z.length) continue;
            double base = median(sub(z, p - b0, p - b1));
            double pkv = inv ? Double.MAX_VALUE : -Double.MAX_VALUE;
            for (int i = p - srch; i <= p + srch; i++) pkv = inv ? Math.min(pkv, z[i]) : Math.max(pkv, z[i]);
            amps.add(Math.abs(pkv - base));
            double[] w = new double[pre + post];
            for (int j = 0; j < w.length; j++) w[j] = z[p - pre + j] - base;
            beats.add(w);
        }
        if (beats.size() < 5) return false;
        int len = pre + post;
        double[] tmpl = new double[len];
        double[] col = new double[beats.size()];
        for (int j = 0; j < len; j++) {
            for (int g = 0; g < beats.size(); g++) col[g] = beats.get(g)[j];
            Arrays.sort(col);
            int m = col.length / 2;
            tmpl[j] = col.length % 2 == 1 ? col[m] : (col[m - 1] + col[m]) / 2.0;
        }
        for (double[] w : beats) {
            double ss = 0;
            for (int j = 0; j < len; j++) { double d = w[j] - tmpl[j]; ss += d * d; }
            noiseOut.add(Math.sqrt(ss / len));
        }
        ampOut.addAll(amps);
        return true;
    }

    static List<Double> sub(double[] a, int from, int to) {
        List<Double> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(a[i]);
        return out;
    }

    /** Pearson correlation of y[from .. from+t.length) with t (numpy corrcoef) */
    static double pearson(double[] y, int from, double[] t) {
        int n = t.length;
        double my = 0, mt = 0;
        for (int i = 0; i < n; i++) { my += y[from + i]; mt += t[i]; }
        my /= n; mt /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double a = y[from + i] - my, c = t[i] - mt;
            sxy += a * c; sxx += a * a; syy += c * c;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }
}
