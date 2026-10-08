package com.noop.mgecg;

// FILE VERSION 0.2.8 (3 Oct): contains lastResult and the report hook in strip(); QT per heart-rate band, wide-band detail beat

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
 *
 * RR-irregularity screen (added 2 Oct, EcgRhythm): the raw beat timing of the
 * same quality-3 runs, cut at bad-shape beats, with NO repair and NO premature-beat
 * removal, goes to EcgRhythm (five features, logistic model fitted on the
 * PhysioNet/CinC 2017 training set). It is shown and logged beside the AF screen
 * so the two can be compared on the same recording. Research only.
 */
public final class EcgR16Analyzer {

    static final int FS = 500;
    static final int SETTLE_S = 3;
    static final int MIN_RUN_S = 10;

    private EcgR16Analyzer() {}

    /** The most recent analyse() result, so strip() (called right after it on the same data) can write the report. */
    static volatile Result lastResult;

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
        /** 0.1.7 screen outcome: AfScreen.AF_LIKE / IRREGULAR / NOT_AF_LIKE / INCONCLUSIVE, and its detail. */
        public int afState = AfScreen.INCONCLUSIVE;
        public AfScreen.Outcome af;
        /** RR-irregularity screen (EcgRhythm) on the raw quality-3 beat timing; null if it did not run. */
        public EcgRhythm.Result rhythm;
        /** 0.3.2 breathing rate from R-wave height and beat timing (research only); null if it did not run. */
        public Breath.Result breath;
        /** EXPERIMENTAL QT from the averaged beat (EcgIntervals); null if it did not run. */
        public EcgIntervals.Result qt;
        /** QT measured separately in 2 or 3 heart-rate bands when the rate changed during the recording (else empty). */
        public List<EcgIntervals.Band> qtBands = new ArrayList<>();
        /** For the report: every beat-to-beat interval (ms), the time its second beat occurred (s from the first
         *  stored second), and whether it touches a beat that failed the shape check. */
        public double[] rrMs = new double[0], rrTimeS = new double[0];
        public boolean[] rrBad = new boolean[0];
        /** For the report: one byte per second of the recording: 0 signal too weak, 1 usable but settling or too
         *  short, 2 measured, 3 contact lost or amplifier recovering. */
        public byte[] secondStates = new byte[0];
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
                    (dash == null ? "" : String.format(Locale.US,
                            " nrmssd=%.4f she=%.3f tp=%d tpRandom=%b",
                            dash[0], dash[1], (int) dash[2], dash[5] > 0.5))
                            + (af == null ? " afState=4 why=not_run" : " " + af.toLog())
                            + (rhythm == null ? " RR_SCREEN not_run" : " RR_SCREEN " + rhythm)
                            + (breath == null ? " BREATH not_run" : " BREATH " + breath)
                            + (qt == null ? " QT_EXPERIMENTAL not_run" : " QT_EXPERIMENTAL " + qt)
                            + qtBandsLog());
        }

        String qtBandsLog() {
            if (qtBands == null || qtBands.isEmpty()) return "";
            StringBuilder b = new StringBuilder(" QT_BANDS");
            for (EcgIntervals.Band bd : qtBands) {
                b.append(String.format(Locale.US, " [hr=%.0f %s beats=%d qt=%.0f qtcF=%.0f ci=%.0f-%.0f]", bd.hr, bd.res.status,
                        bd.res.beatsUsed, bd.res.qtMs, bd.res.qtcFMs, bd.res.ciLoMs, bd.res.ciHiMs));
            }
            return b.toString();
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
            if (noiseFraction > 0.10) {
                return b.append("<b>Inconclusive</b> &mdash; ")
                        .append(String.format(Locale.UK, "%.0f%% of beats did not look like a clean " +
                                "heartbeat (signal too noisy)", 100 * noiseFraction))
                        .append(", so no heart rate or rhythm verdict. Hold still with firm, steady " +
                                "finger contact and try again.").toString();
            }
            int shareOut = intervals == 0 ? 0 : (int) Math.round(100.0 * rhythmOut / intervals);
            if (inconclusive) {
                // more than 15% of intervals differ by over 20% from their neighbours: no heart-rate
                // number, but say what the AF screen found
                if (afState == AfScreen.AF_LIKE) {
                    return b.append(afLikeHtml()).append(rhythmHtml()).append(qtHtml()).toString();
                }
                if (afState == AfScreen.IRREGULAR) {
                    return b.append(irregularHtml(shareOut)).append(rhythmHtml()).append(qtHtml()).toString();
                }
                return b.append("<b>Inconclusive</b> &mdash; ")
                        .append(String.format(Locale.UK, "%d%% of beat intervals were irregular or " +
                                "mis-detected", shareOut))
                        .append(", so no heart rate or rhythm verdict. Hold still with firm, steady " +
                                "finger contact and try again.").append(rhythmHtml()).append(qtHtml()).toString();
            }
            b.append(String.format(Locale.UK, "Heart rate <b>%.0f</b> bpm &middot; HRV (RMSSD) <b>%.0f</b> ms<br>",
                    hr, rmssd));
            if (afState == AfScreen.AF_LIKE) {
                b.append(afLikeHtml());
            } else if (afState == AfScreen.IRREGULAR) {
                b.append(irregularHtml(shareOut));
            } else if (afState == AfScreen.NOT_AF_LIKE && dash != null) {
                b.append(String.format(Locale.UK,
                        "<b><font color='#39FF6A'>AF screen (research): no AF-like pattern</font></b> " +
                                "<small>(%d beats; nRMSSD %.3f, entropy %.2f, turning points %s)</small><br>" +
                                "<small>A clean result does not rule anything out.</small>",
                        af.window, dash[0], dash[1], dash[5] > 0.5 ? "random-like" : "not random"));
            } else {
                String why = af == null || af.why.isEmpty() ? "not enough clean beats" : af.why;
                b.append("<small>AF screen not run: ").append(why).append(" (this session had ")
                        .append(af == null ? intervals - excluded : af.cleaned)
                        .append(" clean intervals; it needs ").append(AfScreen.MIN_WINDOW)
                        .append(", about a minute of steady hold).</small>");
            }
            b.append(rhythmHtml()).append(qtHtml());
            return b.toString();
        }

        /** RR-irregularity screen (EcgRhythm), research only. Empty if it did not run. */
        private String rhythmHtml() {
            if (rhythm == null) return "";
            String head;
            switch (rhythm.verdict) {
                case REGULAR:
                    head = "<font color='#39FF6A'>timing looks regular</font>";
                    break;
                case IRREGULAR:
                    head = rhythm.strong ? "<font color='#FF5555'>AF-like timing pattern</font>"
                            : "<font color='#FFB020'>timing looks irregular</font>";
                    break;
                case CANNOT_ANALYSE:
                    head = "not run (" + rhythm.reason + ")";
                    break;
                default:
                    head = "not calibrated";
                    break;
            }
            StringBuilder s = new StringBuilder("<br><small><b>RR screen (research):</b> ").append(head);
            if (rhythm.features != null) {
                s.append(String.format(Locale.UK, " &middot; %d intervals, nRMSSD %.3f, SampEn %.2f",
                        rhythm.intervals, rhythm.features[0], rhythm.features[4]));
                if (!Double.isNaN(rhythm.probability)) {
                    s.append(String.format(Locale.UK, ", score %.2f", rhythm.probability));
                }
            }
            s.append("<br>Beat timing only. Fitted on 109 PhysioNet AF-database Holter patients (v2), not checked on this strap. " +
                    "Regular does not mean normal. Not a diagnosis.</small>");
            return s.toString();
        }

        /** EXPERIMENTAL QT from the averaged beat. Empty if it did not run. ST is logged only, never shown. */
        private String qtHtml() {
            if (qt == null) return "";
            StringBuilder s = new StringBuilder("<br><small><b>QT (experimental):</b> ");
            if (qt.status == EcgIntervals.Status.OK) {
                s.append(String.format(Locale.UK,
                        "<b>%.0f ms</b> (95%% range %.0f-%.0f) &middot; QTc Fridericia <b>%.0f ms</b> &middot; %d averaged beats, T wave %.0f &micro;V",
                        qt.qtMs, qt.ciLoMs, qt.ciHiMs, qt.qtcFMs, qt.beatsUsed, qt.tAmpUv));
                s.append("<br>Wrist-to-finger single lead, not checked against a 12-lead ECG. Smartwatch QTc studies " +
                        "differ from 12-lead by up to about 60 ms. Not a diagnosis.</small>");
            } else {
                s.append("not measured (").append(qt.reason.isEmpty() ? "not enough clean data" : qt.reason)
                        .append("). It needs about 40 clean beats and a clear T wave.</small>");
            }
            return s.toString();
        }

        private String afLikeHtml() {
            return String.format(Locale.UK,
                    "<b><font color='#FF5555'>AF screen (research): AF-like pattern</font></b><br>" +
                            "<small>Last %d cleaned beats%s: nRMSSD %.3f, entropy %.2f, turning points %s. " +
                            "%d merged, %d split, %d premature beat(s) removed first. Frequent premature " +
                            "beats, poor contact or movement can also look like this. Not a diagnosis.</small>",
                    af.window, af.window == AfScreen.WINDOW ? " (both halves agree)" : "",
                    dash[0], dash[1], dash[5] > 0.5 ? "random-like" : "not random",
                    af.merged, af.split, af.premature);
        }

        private String irregularHtml(int shareOut) {
            return String.format(Locale.UK,
                    "<b><font color='#FFB020'>Irregular rhythm, cause unclear</font></b><br>" +
                            "<small>%d%% of beat intervals differ by more than 20%% from their neighbours%s. " +
                            "Frequent premature beats, mis-detected beats or an irregular rhythm can each do " +
                            "this and the screen cannot tell them apart here. This is not reassuring and not " +
                            "a diagnosis. Hold still with firm, steady finger contact and try again.</small>",
                    shareOut, af != null && af.rawAfLike ? ", and the uncleaned series is AF-like" : "");
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
        lastResult = r;
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
        List<Double> brT = new ArrayList<>(), brA = new ArrayList<>();   // 0.3.2: per-beat R-S height for breathing
        int invRuns = 0, runsMeasured = 0;

        // per-second map for the report
        final long firstSeq = recs.isEmpty() ? 0 : u32(recs.get(0), 11) & 0xffffffffL;
        final long lastSeq = recs.isEmpty() ? 0 : u32(recs.get(recs.size() - 1), 11) & 0xffffffffL;
        final int nSec = (int) Math.max(0, Math.min(lastSeq - firstSeq + 1, 7200));
        final byte[] states = new byte[nSec];
        for (byte[] f : recs) {
            if (f.length != 1584) continue;
            int si = (int) ((u32(f, 11) & 0xffffffffL) - firstSeq);
            if (si < 0 || si >= nSec) continue;
            boolean lost = EcgWhoopSpec.r16FastRecoveryCount(f) > 0 || EcgWhoopSpec.r16LeadOffCount(f) > 250
                    || EcgWhoopSpec.r16LeadOffMeanI(f) > EcgWhoopSpec.LEAD_OFF_I_THRESHOLD || (f[21] & 0xff) == 0;
            states[si] = (byte) (lost ? 3 : (usable(f) ? 1 : 0));
        }
        List<Double> rrAllMs = new ArrayList<>(), rrAllTime = new ArrayList<>();
        List<Boolean> rrAllBad = new ArrayList<>();

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
        List<double[]> qtWins = new ArrayList<>();
        List<Double> qtRrs = new ArrayList<>();
        List<double[]> qtWide = new ArrayList<>();
        for (List<byte[]> run : runs) {
            if (run.size() - SETTLE_S < MIN_RUN_S) continue;
            List<byte[]> use = run.subList(SETTLE_S, run.size());
            r.usableSeconds += use.size();
            final double runOffsetS = (u32(use.get(0), 11) & 0xffffffffL) - firstSeq;
            for (byte[] uf : use) {
                int si = (int) ((u32(uf, 11) & 0xffffffffL) - firstSeq);
                if (si >= 0 && si < nSec) states[si] = 2;
            }
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
            {   // beat windows (uV) for the averaged-beat QT measurement
                double[] uvRun = new double[x.length];
                for (int i = 0; i < uvRun.length; i++) uvRun[i] = x[i] * EcgWhoopSpec.R16_UV_PER_COUNT;
                qtWins.addAll(EcgIntervals.windows(uvRun, pk, bad, inv, qtRrs, qtWide));
            }
            if (measureAmplitude(x, pk, bad, inv, ampAll, noiseAll)) {
                runsMeasured++;
                if (inv) invRuns++;
            }
            for (int p : pk) {   // 0.3.2: R-S height of every accepted beat, for the breathing estimate
                if (bad.contains(p) || p < 20 || p + 30 >= n) continue;
                double mx = -1e18, mn = 1e18;
                for (int q = p - 10; q <= p + 10; q++) mx = Math.max(mx, y[q]);
                for (int q = p + 5; q <= p + 30; q++) mn = Math.min(mn, y[q]);
                brT.add(runOffsetS + p / (double) FS);
                brA.add(mx - mn);
            }
            List<Double> rr = new ArrayList<>();
            List<Boolean> bd = new ArrayList<>();
            for (int i = 1; i < pk.size(); i++) {
                rr.add((pk.get(i) - pk.get(i - 1)) * 1000.0 / FS);
                bd.add(bad.contains(pk.get(i - 1)) || bad.contains(pk.get(i)));
                rrAllMs.add((pk.get(i) - pk.get(i - 1)) * 1000.0 / FS);
                rrAllTime.add(runOffsetS + pk.get(i) / (double) FS);
                rrAllBad.add(bad.contains(pk.get(i - 1)) || bad.contains(pk.get(i)));
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
        r.secondStates = states;
        r.rrMs = new double[rrAllMs.size()];
        r.rrTimeS = new double[rrAllMs.size()];
        r.rrBad = new boolean[rrAllMs.size()];
        for (int i = 0; i < r.rrMs.length; i++) {
            r.rrMs[i] = rrAllMs.get(i);
            r.rrTimeS[i] = rrAllTime.get(i);
            r.rrBad[i] = rrAllBad.get(i);
        }
        {   // 0.3.2: breathing from R-wave height (EDR) and from interval timing (RSA)
            int gi = 0;
            for (boolean bdv : rrAllBad) if (!bdv) gi++;
            double[] gt = new double[gi], gv = new double[gi];
            int gk = 0;
            for (int i = 0; i < rrAllMs.size(); i++) {
                if (!rrAllBad.get(i)) { gt[gk] = rrAllTime.get(i); gv[gk] = rrAllMs.get(i); gk++; }
            }
            double[] bt = new double[brT.size()], ba = new double[brA.size()];
            for (int i = 0; i < bt.length; i++) { bt[i] = brT.get(i); ba[i] = brA.get(i); }
            try { r.breath = Breath.estimate(bt, ba, gt, gv); } catch (RuntimeException ex) { r.breath = null; }
        }
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
        if (r.noiseFraction <= 0.10) {
            // 0.1.7: repair detector errors, remove premature beats with their pauses, then the Dash
            // screen on 128 (or 64) cleaned beats. A beat that failed the shape check is a rejected
            // interval: runs are cut there, so no interval is ever joined across it.
            List<double[]> segs = new ArrayList<>();
            for (int ri = 0; ri < rrRuns.size(); ri++) {
                List<Double> rr = rrRuns.get(ri);
                List<Boolean> bd = badRuns.get(ri);
                List<Double> seg = new ArrayList<>();
                for (int i = 0; i <= rr.size(); i++) {
                    if (i == rr.size() || bd.get(i)) {
                        if (!seg.isEmpty()) {
                            double[] a = new double[seg.size()];
                            for (int k = 0; k < a.length; k++) a[k] = seg.get(k);
                            segs.add(a);
                            seg.clear();
                        }
                    } else {
                        seg.add(rr.get(i));
                    }
                }
            }
            r.af = AfScreen.screen(segs);
            r.afState = r.af.state;
            r.dash = r.af.dash;
            r.screenRun = r.af.dash != null;
            r.afLike = r.af.state == AfScreen.AF_LIKE;

            // RR-irregularity screen: the same segments, but as raw beat times (no repair, no
            // premature-beat removal). EcgRhythm splits at any interval outside 0.3-2.0 s and
            // uses the longest clean stretch.
            List<double[]> beatRuns = new ArrayList<>();
            for (double[] sg : segs) {
                double[] bt = new double[sg.length + 1];
                for (int k = 0; k < sg.length; k++) bt[k + 1] = bt[k] + sg[k] / 1000.0;
                beatRuns.add(bt);
            }
            r.rhythm = EcgRhythm.analyzeRuns(beatRuns);

            // EXPERIMENTAL QT: averaged beat, tangent method, bootstrap interval
            r.qt = EcgIntervals.analyse(qtWins, clean.isEmpty() ? 1.0 : median(clean) / 1000.0, qtWide);
            // QT depends on the beat before it, so averaging over an uneven rhythm is not meaningful
            boolean uneven = r.afState == AfScreen.IRREGULAR || r.afState == AfScreen.AF_LIKE
                    || (r.rhythm != null && r.rhythm.verdict == EcgRhythm.Verdict.IRREGULAR);
            if (uneven && r.qt.status == EcgIntervals.Status.OK) {
                r.qt.status = EcgIntervals.Status.UNCERTAIN;
                r.qt.reason = "the rhythm was too uneven for an averaged QT";
            }
            // heart rate changed during the recording (for example recovering from exercise): measure per band
            if (!uneven) r.qtBands = EcgIntervals.bands(qtWins, qtRrs);
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

    // ------------------------------------------------------------------ strip

    /**
     * The whole stored recording laid out for the saved ECG image. Pure Java
     * so it can be tested against real captures without a phone.
     */
    public static final class Strip {
        /** Display trace in mV (zero-phase 0.67-40 Hz + 50 Hz notch); NaN = no usable signal. */
        public double[] mv = new double[0];
        /** Strap quality byte (0-3) of the record each sample came from. */
        public byte[] quality = new byte[0];
        /** R-peak sample indices, found only inside quality-3 stretches of 5 s or more. */
        public int[] beats = new int[0];
        public long startUnix;
        /** Record sequence number of sample 0, so other streams can be laid on the same clock. */
        public long firstSeq;
        /**
         * Optical pulse (R20), six channels at 50 Hz, band-passed and inverted so the pulse peak
         * points up; NaN = none. Channel i sits at byte offset EcgAux.PULSE_OFFS[i]. See EcgAux.
         */
        public double[][] pulse = new double[0][];
        /** Per channel {beat-locked swing, random-time control, steepest rise ms, its SD, foot ms, its SD, peak ms, beats}. */
        public double[][] pulseStats = new double[0][];
        /** Indices of the (up to two) channels that lock best to the heartbeat, best first. */
        public int[] pulseShow = new int[0];
        /** Wrist motion, 100 Hz, mg of acceleration change within each second; NaN = none. See EcgAux. */
        public double[] motionMg = new double[0];
    }

    /** @param records stored R16 frames (1584 bytes each), any order */
    public static Strip strip(List<byte[]> records) {
        Strip s = new Strip();
        List<byte[]> recs = new ArrayList<>();
        for (byte[] f : records) if (f.length == 1584) recs.add(f);
        if (recs.isEmpty()) return s;
        recs.sort((a, b) -> Long.compare(u32(a, 11) & 0xffffffffL, u32(b, 11) & 0xffffffffL));
        long first = u32(recs.get(0), 11) & 0xffffffffL;
        long last = u32(recs.get(recs.size() - 1), 11) & 0xffffffffL;
        int nRec = (int) Math.min(last - first + 1, 3600);          // at most one hour
        int n = nRec * FS;
        double[] raw = new double[n];
        Arrays.fill(raw, Double.NaN);
        byte[] q = new byte[n];
        for (byte[] f : recs) {
            long idx = (u32(f, 11) & 0xffffffffL) - first;
            if (idx < 0 || idx >= nRec) continue;
            int base = (int) idx * FS, cnt = EcgWhoopSpec.r16Count(f);
            for (int k = 0; k < FS; k++) q[base + k] = f[21];
            for (int k = 0; k < cnt; k++) {
                int v = EcgWhoopSpec.r16Sample(f, k);
                // rail samples and the amplifier's fast-recovery window are not ECG
                if (Math.abs(v) >= EcgWhoopSpec.R16_RAIL || EcgWhoopSpec.r16FastRecovery(f, k)) continue;
                raw[base + k] = v;
            }
        }
        double[] mv = new double[n];
        Arrays.fill(mv, Double.NaN);
        int i = 0;
        while (i < n) {
            if (Double.isNaN(raw[i])) { i++; continue; }
            int j = i;
            while (j < n && !Double.isNaN(raw[j])) j++;
            if (j - i >= FS) {
                double[] uv = new double[j - i];
                for (int k = 0; k < uv.length; k++) uv[k] = raw[i + k] * EcgWhoopSpec.R16_UV_PER_COUNT;
                double[] z = EcgWhoopSpec.improvedFilter(uv);
                for (int k = 0; k < z.length; k++) mv[i + k] = z[k] / 1000.0;
            }
            i = j;
        }
        List<Integer> beats = new ArrayList<>();
        i = 0;
        while (i < n) {
            if (Double.isNaN(raw[i]) || q[i] != 3) { i++; continue; }
            int j = i;
            while (j < n && !Double.isNaN(raw[j]) && q[j] == 3) j++;
            if (j - i >= 5 * FS) {
                double[] seg = Arrays.copyOfRange(raw, i, j);
                for (int p : peaks(seg)) beats.add(i + p);
            }
            i = j;
        }
        s.mv = mv;
        s.quality = q;
        s.beats = new int[beats.size()];
        for (int k = 0; k < s.beats.length; k++) s.beats[k] = beats.get(k);
        s.startUnix = u32(recs.get(0), 15) & 0xffffffffL;
        s.firstSeq = first;
        Result analysed = lastResult;
        if (analysed != null && analysed.recordsIn == records.size()) EcgReport.saveReportFile(analysed, s);
        return s;
    }

    /*
     * Breathing rate from the ECG, research only (0.3.2). Two independent estimates from the same beats:
     * EDR (R-S height modulated by breathing: with paced breathing at 6 and 12 per minute the height swings
     * peaked at 6.0 and 12.3 per minute) and RSA (beat interval lengthens and shortens with breathing).
     * Both are a Lomb-Scargle peak between 6 and 30 per minute. When they agree within 1 per minute the
     * estimate is marked good. Not a medical measurement.
     */
    public static final class Breath {
    private Breath() {}

    public static final double MIN_PER_MIN = 6, MAX_PER_MIN = 30;
    public static final int MIN_BEATS = 40;
    public static final double MIN_SPAN_S = 40;

    public static final class Result {
        public int beats, intervals;
        public double spanS = Double.NaN;
        public double edrRate = Double.NaN, edrRatio = Double.NaN;
        public double rsaRate = Double.NaN, rsaRatio = Double.NaN;
        public double ampModPct = Double.NaN;
        public boolean enough, agree;
        public String note = "";

        /** Best single estimate: the EDR rate (it held at paced 6 and 12 per minute; the RSA peak did not at 6). */
        public double rate() { return enough ? edrRate : Double.NaN; }

        @Override
        public String toString() {
            if (!enough) return String.format(Locale.US, "not_enough beats=%d spanS=%.0f %s", beats, spanS, note).trim();
            return String.format(Locale.US,
                    "rate=%.1f ratio=%.1f rsa=%.1f rsaRatio=%.1f ampModPct=%.1f agree=%b beats=%d intervals=%d spanS=%.0f",
                    edrRate, edrRatio, rsaRate, rsaRatio, ampModPct, agree, beats, intervals, spanS);
        }
    }

    /**
     * @param beatT   time (s) of each accepted beat
     * @param beatAmp R-S height of that beat (any consistent unit)
     * @param rrT     time (s) of the beat that ends each interval
     * @param rrMs    interval (ms); intervals touching a rejected beat must already be left out
     */
    public static Result estimate(double[] beatT, double[] beatAmp, double[] rrT, double[] rrMs) {
        Result r = new Result();
        int n = Math.min(beatT.length, beatAmp.length);
        r.beats = n;
        if (n < MIN_BEATS) { r.note = "few_beats"; return r; }
        double[] med = new double[n];
        System.arraycopy(beatAmp, 0, med, 0, n);
        java.util.Arrays.sort(med);
        double m = med[n / 2];
        if (!(m > 0)) { r.note = "no_amplitude"; return r; }
        double[] t = new double[n], a = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (beatAmp[i] > 0.5 * m && beatAmp[i] < 1.8 * m) { t[k] = beatT[i]; a[k] = beatAmp[i]; k++; }
        }
        if (k < MIN_BEATS) { r.note = "few_beats_after_outliers"; return r; }
        t = java.util.Arrays.copyOf(t, k);
        a = java.util.Arrays.copyOf(a, k);
        r.beats = k;
        r.spanS = t[k - 1] - t[0];
        if (r.spanS < MIN_SPAN_S) { r.note = "short"; return r; }
        double mean = 0;
        for (double v : a) mean += v;
        mean /= k;
        double var = 0;
        for (double v : a) var += (v - mean) * (v - mean);
        r.ampModPct = 100.0 * Math.sqrt(var / k) / mean;
        double[] e = peak(t, a);
        r.edrRate = e[0];
        r.edrRatio = e[1];
        // interval series
        int cnt = 0;
        int rn = Math.min(rrT.length, rrMs.length);
        double[] rt = new double[rn], rv = new double[rn];
        for (int i = 0; i < rn; i++) {
            if (rrMs[i] > 500 && rrMs[i] < 1500) { rt[cnt] = rrT[i]; rv[cnt] = rrMs[i]; cnt++; }
        }
        r.intervals = cnt;
        if (cnt >= MIN_BEATS) {
            double[] s = peak(java.util.Arrays.copyOf(rt, cnt), java.util.Arrays.copyOf(rv, cnt));
            r.rsaRate = s[0];
            r.rsaRatio = s[1];
            r.agree = Math.abs(r.rsaRate - r.edrRate) <= 1.5;
        }
        r.enough = true;
        return r;
    }

    /** Lomb-Scargle over 6..30 per minute; returns {peak rate per minute, peak power / median power}. */
    static double[] peak(double[] t, double[] x) {
        int n = t.length;
        double mean = 0;
        for (double v : x) mean += v;
        mean /= n;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = x[i] - mean;
        int steps = 81;
        double lo = MIN_PER_MIN / 60.0, hi = MAX_PER_MIN / 60.0;
        double[] p = new double[steps];
        double bestP = -1;
        int best = 0;
        for (int s = 0; s < steps; s++) {
            double f = lo + (hi - lo) * s / (steps - 1);
            double w = 2 * Math.PI * f;
            double sn = 0, cs = 0;
            for (int i = 0; i < n; i++) { sn += Math.sin(2 * w * t[i]); cs += Math.cos(2 * w * t[i]); }
            double tau = Math.atan2(sn, cs) / (2 * w);
            double yc = 0, ys = 0, cc = 0, ss = 0;
            for (int i = 0; i < n; i++) {
                double c = Math.cos(w * (t[i] - tau)), si = Math.sin(w * (t[i] - tau));
                yc += y[i] * c; ys += y[i] * si; cc += c * c; ss += si * si;
            }
            p[s] = 0.5 * ((cc > 1e-12 ? yc * yc / cc : 0) + (ss > 1e-12 ? ys * ys / ss : 0));
            if (p[s] > bestP) { bestP = p[s]; best = s; }
        }
        double[] srt = p.clone();
        java.util.Arrays.sort(srt);
        double med = (srt[steps / 2 - 1] + srt[steps / 2]) / 2.0;
        double f = lo + (hi - lo) * best / (steps - 1);
        return new double[]{f * 60.0, med > 0 ? bestP / med : Double.NaN};
    }
}
}
