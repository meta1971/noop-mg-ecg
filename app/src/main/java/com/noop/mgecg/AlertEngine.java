package com.noop.mgecg;

// FILE VERSION 0.56.0 (14 Nov 2026): NEW. The HEART WATCH ring of the Today page, version 1. Pure Java, no Android (checked off the phone against MG_alert_drift.py, decode log 202).
// It looks at three things, each with its own three levels, and shows the highest: 0 usual, 1 WATCH, 2 CHECK. It never says what is wrong. It says what is unusual for THIS person and, for the pulse, which test can tell (the strap's ECG).
//   1 NIGHT SIGNS (drift)  the newest night against the earlier nights (up to 14, at least 7 per signal, nights of 3 h or more): resting heart rate (lowest 5 min), overnight HRV (log RMSSD), skin temperature,
//                          breathing rate, blood oxygen median. Robust z = (value - median) / max(1.4826 x MAD, floor), positive = worse (heart rate, skin, breathing up; HRV, SpO2 down).
//                          floors: 2 bpm, 0.15 log units, 0.3 degC, 0.7 per min, 1 %. Needs 3 signals with a baseline.
//                          WATCH = 2 or more signals at z 2 or more tonight, or one signal at z 3 or more on this night and the one before.
//                          CHECK = 3 or more signals at z 2 or more tonight, or 2 or more on this night AND the night before.
//   2 PULSE RHYTHM         the stored 5-minute pulse-regularity windows (IrregularWatch.rhythm_win) of the last 48 h: usable windows (bit 4), irregular windows (bit 2), ectopy-pattern windows (bit 16, not counted).
//                          Needs 12 usable windows (1 h). WATCH = 2 or more irregular windows; CHECK = 3 in a row (15 min, the IrregularWatch alert) or 6 or more.
//   3 HEART RATE AT REST   the median heart rate of the last 10 quiet minutes (strap says still or asleep, little movement), not older than 30 min: WATCH at 100 bpm or more or under 45, CHECK at 120 or more or under 40
//                          (Apple Watch defaults: 120 and 40 after 10 minutes of inactivity).
// RING FILL (0-100): per stage a raw value, mapped so that usual stays under 35, WATCH is 40-65 and CHECK is 70 or more; the ring shows the highest.
// Thresholds are choices made on 21 days of one person's data (zero alerts there) and on synthetic tests; they are NOT validated against illness or heart events (decode log 202).

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class AlertEngine {
    private AlertEngine() {}

    static final int VERSION = 1, NONE = -1, GREEN = 0, WATCH = 1, CHECK = 2;
    static final String[] SIG = { "Resting heart rate", "Overnight HRV", "Skin temperature", "Breathing", "Blood oxygen" };
    static final String[] UNIT = { "bpm", "ms", "°C", "per min", "%" };
    static final double[] FLOOR = { 2.0, 0.15, 0.30, 0.7, 1.0 };
    static final int[] SIGN = { 1, -1, 1, 1, -1 };
    static final int MIN_BASE = 7, MAX_BASE = 14, MIN_SIGNALS = 3;
    static final double MIN_TST = 3.0, Z_DEV = 2.0, Z_BIG = 3.0;
    static final long NIGHT_MAX_AGE_S = 60L * 3600L, PREV_MIN_GAP_S = 12L * 3600L, PREV_MAX_GAP_S = 4L * 86400L;
    static final long SPAN_S = 48L * 3600L; static final int WIN_S = 300, MIN_USABLE = 12, WATCH_IRR = 2, CHECK_RUN = 3, CHECK_IRR = 6;
    static final double HI_WATCH = 100, HI_CHECK = 120, LO_WATCH = 45, LO_CHECK = 40;
    static final long REST_MAX_AGE_S = 1800L;

    static final class Sig {
        String name, unit; double now = Double.NaN, usual = Double.NaN, z = Double.NaN; int nBase;
        boolean ok() { return !Double.isNaN(z); }
    }
    static final class Stage {
        String name; int tier = NONE; double raw = Double.NaN; String line = "";
        Stage(String n) { name = n; }
    }
    static final class Result {
        final Stage drift = new Stage("Night signs"), rhythm = new Stage("Pulse rhythm"), rest = new Stage("Heart rate at rest");
        final Sig[] sig = new Sig[5];
        long nightBed; double nightTst; int devNow = -1, devPrev = -1, bigNow = -1, bigPrev = -1, signals;
        int usable, irregular, ecto, longestRun;
        double restHr = Double.NaN;
        int tier = NONE; double score = Double.NaN; String cause = "";
        String headline = "", advice = "";
        final List<String> reasons = new ArrayList<String>();
        Result() { for (int i = 0; i < 5; i++) { sig[i] = new Sig(); sig[i].name = SIG[i]; sig[i].unit = UNIT[i]; } }
        float fill() { return Double.isNaN(score) ? 0f : (float) (score / 100.0); }
        String big() { return tier == CHECK ? "CHECK" : tier == WATCH ? "WATCH" : tier == GREEN ? "OK" : "—"; }
        String caption() { return tier == CHECK ? "see details" : tier == WATCH ? "worth a look" : tier == GREEN ? "all usual" : "learning"; }
    }

    static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
    private static double median(double[] a) {
        double[] b = Arrays.copyOf(a, a.length); Arrays.sort(b); int n = b.length;
        return n % 2 == 1 ? b[n / 2] : 0.5 * (b[n / 2 - 1] + b[n / 2]);
    }
    static double val(TodayModel.NightStat s, int k) {
        switch (k) {
            case 0: return s.hrLow5;
            case 1: return s.rmssd > 0 ? Math.log(s.rmssd) : Double.NaN;
            case 2: return s.skin;
            case 3: return s.br;
            default: return s.spo2Median > 0 ? (double) s.spo2Median : Double.NaN;
        }
    }

    /** Robust z per signal for one night against its earlier nights (sorted oldest first, any length). z is positive when worse; NaN when fewer than MIN_BASE earlier values. median[k] and nBase[k] are filled when not null. */
    static double[] zs(TodayModel.NightStat night, List<TodayModel.NightStat> sorted, double[] medOut, int[] nOut) {
        List<TodayModel.NightStat> base = new ArrayList<TodayModel.NightStat>();
        for (int i = sorted.size() - 1; i >= 0 && base.size() < MAX_BASE; i--) {
            TodayModel.NightStat s = sorted.get(i);
            if (s.bed < night.bed && !Double.isNaN(s.tstH) && s.tstH >= MIN_TST) base.add(s);
        }
        double[] z = new double[5];
        for (int k = 0; k < 5; k++) {
            double v = val(night, k); double[] xs = new double[base.size()]; int n = 0;
            for (TodayModel.NightStat s : base) { double x = val(s, k); if (!Double.isNaN(x)) xs[n++] = x; }
            if (nOut != null) nOut[k] = n;
            if (Double.isNaN(v) || n < MIN_BASE) { z[k] = Double.NaN; if (medOut != null) medOut[k] = Double.NaN; continue; }
            double[] a = Arrays.copyOf(xs, n); double m = median(a); double[] dev = new double[n];
            for (int i = 0; i < n; i++) dev[i] = Math.abs(a[i] - m);
            double sc = Math.max(median(dev) * 1.4826, FLOOR[k]);
            z[k] = SIGN[k] * (v - m) / sc;
            if (medOut != null) medOut[k] = m;
        }
        return z;
    }

    private static int count(double[] z, double thr) { int c = 0; for (double x : z) if (!Double.isNaN(x) && x >= thr) c++; return c; }
    private static int have(double[] z) { int c = 0; for (double x : z) if (!Double.isNaN(x)) c++; return c; }

    static double stageScore(int tier, double raw01) {
        double s = 100.0 * clamp01(raw01);
        if (tier == GREEN) return Math.min(s, 35.0);
        if (tier == WATCH) return Math.max(40.0, Math.min(s, 65.0));
        if (tier == CHECK) return Math.max(70.0, s);
        return Double.NaN;
    }

    private static String delta(int k, double now, double usual) {
        if (Double.isNaN(now) || Double.isNaN(usual)) return "";
        switch (k) {
            case 0: return String.format(Locale.UK, "%+.0f bpm", now - usual);
            case 1: return String.format(Locale.UK, "%+.0f %%", 100.0 * (now / usual - 1.0));
            case 2: return String.format(Locale.UK, "%+.1f °C", now - usual);
            case 3: return String.format(Locale.UK, "%+.1f per min", now - usual);
            default: return String.format(Locale.UK, "%+.0f %%", now - usual);
        }
    }

    /**
     * nights: every stored night (any order; the newest included); nowTs: unix seconds now; wins: rhythm_win rows {start, nr x1000, n, hr, flags} (any order, any span);
     * restHr: median heart rate of the last quiet 10 minutes, NaN when none; restHrTs: unix seconds of its newest record.
     */
    static Result compute(List<TodayModel.NightStat> nights, long nowTs, List<long[]> wins, double restHr, long restHrTs) {
        Result R = new Result();
        drift(R, nights, nowTs);
        rhythm(R, wins, nowTs);
        rest(R, restHr, restHrTs, nowTs);
        Stage[] st = { R.rhythm, R.rest, R.drift };                       // ties: rhythm, then heart rate, then night signs
        int best = NONE; double sc = Double.NaN; String cause = "";
        for (Stage s : st) {
            if (s.tier == NONE) continue;
            double v = stageScore(s.tier, s.raw);
            if (s.tier > best || (s.tier == best && v > sc)) { best = s.tier; sc = v; cause = s == R.rhythm ? "rhythm" : s == R.rest ? "hr" : "drift"; }
        }
        R.tier = best; R.score = best == NONE ? Double.NaN : sc; R.cause = best >= WATCH ? cause : "";
        for (Stage s : st) if (s.tier >= WATCH) R.reasons.add(s.line);
        R.headline = best == CHECK ? "Take a closer look" : best == WATCH ? "Worth watching" : best == GREEN ? "Nothing unusual" : "Still learning your usual";
        R.advice = adviceFor(R);
        return R;
    }

    private static void drift(Result R, List<TodayModel.NightStat> nights, long nowTs) {
        Stage S = R.drift;
        List<TodayModel.NightStat> ns = new ArrayList<TodayModel.NightStat>(nights == null ? new ArrayList<TodayModel.NightStat>() : nights);
        Collections.sort(ns, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });
        TodayModel.NightStat n0 = null, n1 = null;
        for (int i = ns.size() - 1; i >= 0; i--) { TodayModel.NightStat s = ns.get(i); if (!Double.isNaN(s.tstH) && s.tstH >= MIN_TST && s.bed <= nowTs) { n0 = s; break; } }
        if (n0 == null) { S.line = "No night of 3 hours or more is stored yet."; return; }
        R.nightBed = n0.bed; R.nightTst = n0.tstH;
        if (nowTs - n0.bed > NIGHT_MAX_AGE_S) { S.line = "The newest night is more than two days old: sync the strap."; return; }
        for (int i = ns.size() - 1; i >= 0; i--) {
            TodayModel.NightStat s = ns.get(i);
            if (!Double.isNaN(s.tstH) && s.tstH >= MIN_TST && n0.bed - s.bed >= PREV_MIN_GAP_S && n0.bed - s.bed <= PREV_MAX_GAP_S) { n1 = s; break; }
        }
        double[] med = new double[5]; int[] nb = new int[5];
        double[] z0 = zs(n0, ns, med, nb);
        double[] z1 = n1 == null ? null : zs(n1, ns, null, null);
        for (int k = 0; k < 5; k++) {
            Sig g = R.sig[k]; g.nBase = nb[k]; g.z = z0[k];
            double v = val(n0, k);
            g.now = k == 1 ? n0.rmssd : v;
            g.usual = Double.isNaN(med[k]) ? Double.NaN : (k == 1 ? Math.exp(med[k]) : med[k]);
        }
        R.signals = have(z0);
        if (R.signals < MIN_SIGNALS) { S.line = "Learning your usual: needs " + MIN_BASE + " earlier nights of 3 hours or more for at least " + MIN_SIGNALS + " of the 5 night signs."; return; }
        int d0 = count(z0, Z_DEV), b0 = count(z0, Z_BIG), d1 = z1 == null ? 0 : count(z1, Z_DEV), b1 = z1 == null ? 0 : count(z1, Z_BIG);
        R.devNow = d0; R.devPrev = d1; R.bigNow = b0; R.bigPrev = b1;
        double top = 0; { double[] p = new double[5]; int c = 0; for (double x : z0) if (!Double.isNaN(x)) p[c++] = Math.max(0.0, x); Arrays.sort(p, 0, c); for (int i = 0; i < Math.min(2, c); i++) top += p[c - 1 - i]; top /= 2.0; }
        S.raw = top / 3.0;
        if (d0 >= 3 || (d0 >= 2 && d1 >= 2)) S.tier = CHECK;
        else if (d0 >= 2 || (b0 >= 1 && b1 >= 1)) S.tier = WATCH;
        else S.tier = GREEN;
        if (S.tier == GREEN) { S.line = "Night signs are within your usual (" + R.signals + " of 5 compared)."; return; }
        StringBuilder sb = new StringBuilder("Away from your usual last night: ");
        boolean first = true;
        for (int k = 0; k < 5; k++) if (R.sig[k].ok() && R.sig[k].z >= Z_DEV) {
            if (!first) sb.append("; "); first = false;
            sb.append(SIG[k].toLowerCase(Locale.UK)).append(' ').append(delta(k, R.sig[k].now, R.sig[k].usual));
        }
        if (d1 >= 2 && S.tier == CHECK) sb.append(" (and again the night before)");
        S.line = sb.append('.').toString();
    }

    private static void rhythm(Result R, List<long[]> wins, long nowTs) {
        Stage S = R.rhythm;
        List<long[]> w = new ArrayList<long[]>();
        if (wins != null) for (long[] r : wins) if (r != null && r.length >= 5 && r[0] > nowTs - SPAN_S && r[0] <= nowTs) w.add(r);
        Collections.sort(w, new Comparator<long[]>() { @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); } });
        int usable = 0, irr = 0, ecto = 0, run = 0, best = 0; long prev = -1;
        for (long[] r : w) {
            int f = (int) r[4];
            if ((f & 4) != 0) usable++;
            if ((f & 16) != 0) ecto++;
            if ((f & 2) != 0) { irr++; run = (run > 0 && r[0] == prev + WIN_S) ? run + 1 : 1; if (run > best) best = run; } else run = 0;
            prev = r[0];
        }
        R.usable = usable; R.irregular = irr; R.ecto = ecto; R.longestRun = best;
        if (usable < MIN_USABLE) { S.line = "Not enough clean rest time in the last 48 hours to look at the rhythm (" + usable + " of " + MIN_USABLE + " five-minute windows)."; return; }
        S.raw = irr / (double) CHECK_IRR;
        if (best >= CHECK_RUN || irr >= CHECK_IRR) S.tier = CHECK;
        else if (irr >= WATCH_IRR) S.tier = WATCH;
        else S.tier = GREEN;
        if (S.tier == GREEN) S.line = "No irregular pulse at rest in the last 48 hours (" + usable + " clean windows" + (irr == 1 ? ", 1 borderline" : "") + ").";
        else S.line = "Irregular pulse at rest: " + irr + " of " + usable + " clean five-minute windows in the last 48 hours" + (best >= 2 ? ", " + best * 5 + " minutes in a row at the longest" : "") + ".";
    }

    private static void rest(Result R, double hr, long hrTs, long nowTs) {
        Stage S = R.rest; R.restHr = hr;
        if (Double.isNaN(hr) || hrTs <= 0 || nowTs - hrTs > REST_MAX_AGE_S) { S.line = "No quiet ten minutes in the last half hour."; return; }
        if (hr >= HI_CHECK || hr < LO_CHECK) S.tier = CHECK;
        else if (hr >= HI_WATCH || hr < LO_WATCH) S.tier = WATCH;
        else S.tier = GREEN;
        S.raw = hr >= 100 ? (hr - 80.0) / 60.0 : hr < 50 ? (60.0 - hr) / 40.0 : 0.0;
        S.line = String.format(Locale.UK, "Heart rate at rest %s: %.0f bpm over the last quiet ten minutes.", S.tier == GREEN ? "is as expected" : hr >= HI_WATCH ? "is high" : "is low", hr);
    }

    private static String adviceFor(Result R) {
        if (R.tier == NONE) return "Heart Watch needs more nights and some clean rest time before it can say anything. Keep wearing the strap, including at night.";
        if (R.tier == GREEN) return "Nothing to do. It keeps checking each time the strap syncs.";
        StringBuilder a = new StringBuilder();
        if (R.rhythm.tier >= WATCH) a.append("Take an ECG reading now (ECG tab, Start an ECG). Only the ECG can tell whether the irregular pulse is atrial fibrillation or something harmless; an irregular pulse alone is not a diagnosis. ");
        if (R.rest.tier >= WATCH) a.append(R.restHr >= HI_WATCH ? "A high heart rate while sitting still can come from caffeine, a fever, dehydration, stress or illness. Rest and drink some water, then look again in half an hour. " : "A low heart rate can be normal for you, but if you feel dizzy or faint, get checked. ");
        if (R.drift.tier >= WATCH) a.append("Several overnight signs are away from your own usual at once, which often comes with the start of an illness, poor sleep, alcohol, hard training or stress. Take it easy for a day or two and watch how you feel. ");
        a.append("This is a research estimate from one strap, not a medical device. If you have chest pain, fainting, severe breathlessness or a racing heart that does not settle, call your emergency number (999 in the UK) instead of waiting for the app.");
        return a.toString();
    }
}
