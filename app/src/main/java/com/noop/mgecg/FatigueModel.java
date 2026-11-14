package com.noop.mgecg;

// FILE VERSION 0.54.4 (14 Nov 2026): NEW. The FATIGUE ring of the Today page, version 1. An ESTIMATE of how worn down you are: what has built up over the last days PLUS what today has added.
// It is a transparent composite of five things this strap can see, each turned into 0-100 (0 = nothing above your usual, 100 = a lot) and combined with fixed weights. There is NO ground truth for "fatigue"
// on this strap: nothing here was fitted to how anyone felt, the weights and the full-scale points are choices (below), and the ring says so on screen. Pure Java, no Android.
//
//   BUILT UP OVER DAYS (weight 0.70)
//     1 SLEEP DEBT   0.30  the shortfall of each of the last 7 nights against your sleep goal, recent nights counting more (weight 0.85 per day of age); a night longer than the goal does not cancel
//                          a short one (a deliberate simplification). 100 = an average shortfall of 2 h a night. Needs 3 nights in the 7 days.
//     2 BODY SIGNS   0.25  the last 3 nights' resting heart rate and overnight HRV against your own usual (the same z-scores the Recovery ring uses, TodayModel.recovery), newest weighted 3, 2, 1;
//                          only the part worse than your usual counts. 100 = 2.5 spreads worse. Needs 2 of the 3 nights to have a baseline (4 earlier nights).
//     3 DAY STRESS   0.15  the stress ring's daily average over the last 3 finished days against the average of the days before (up to 11), only the part above counts. 100 = 60 % above your usual.
//                          Needs 2 of the 3 days (24 scored 5-minute bins each) and 3 days before them.
//   ADDED TODAY (weight 0.30)
//     4 EFFORT       0.10  today's effort on its own 0-21 scale. 100 = 21.
//     5 TIME AWAKE   0.20  hours since the night ended, a stand-in for sleep pressure. 0 up to 6 h awake, 100 at 18 h. Skipped when the night ended more than 24 h ago.
//   A part that cannot be worked out is left out and the other weights are scaled up to 1 (the screen lists what is missing). It needs part 1 or 2 and at least two parts in all.
//   LEVELS: under 30 low, 30 to 59 moderate, 60 and over high.

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class FatigueModel {
    private FatigueModel() {}

    static final int VERSION = 1;
    static final String[] NAME = { "Sleep debt", "Body signs overnight", "Daytime stress", "Effort today", "Time awake" };
    static final double[] WEIGHT = { 0.30, 0.25, 0.15, 0.10, 0.20 };
    static final double NIGHT_DECAY_PER_DAY = 0.85, DEBT_FULL_H = 2.0, BODY_FULL_Z = 2.5, STRESS_FULL_UP = 0.6, AWAKE_ZERO_H = 6.0, AWAKE_FULL_H = 18.0, STRESS_USUAL_FLOOR = 8.0;
    static final int SLEEP_DAYS = 7, MIN_SLEEP_NIGHTS = 3, BODY_NIGHTS = 3, MIN_BODY_NIGHTS = 2, STRESS_RECENT_DAYS = 3, STRESS_USUAL_DAYS = 11, MIN_DAY_BINS = 24, MIN_RECENT_DAYS = 2, MIN_USUAL_DAYS = 3;
    static final double LOW_BELOW = 30, HIGH_FROM = 60;

    static final class Part {
        String name, detail = "";
        double score = Double.NaN;          // 0-100, NaN when it could not be worked out
        double weight;                      // after scaling to the parts present (0 when missing)
        double contrib;                     // score x weight, points of the final score
        boolean ok() { return !Double.isNaN(score); }
    }

    static final class Result {
        final Part[] parts = new Part[5];
        double score = Double.NaN;          // 0-100, NaN when not scored
        double builtUp = Double.NaN;        // parts 1-3 alone (0-100), NaN when none present
        double today = Double.NaN;          // parts 4-5 alone (0-100), NaN when none present
        String why = "";                    // when not scored: what is missing
        Result() { for (int i = 0; i < 5; i++) { parts[i] = new Part(); parts[i].name = NAME[i]; } }
        boolean scored() { return !Double.isNaN(score); }
        int rounded() { return (int) Math.round(score); }
        String level() { return levelOf(score); }
    }

    static String levelOf(double s) { return Double.isNaN(s) ? "" : s < LOW_BELOW ? "low" : s < HIGH_FROM ? "moderate" : "high"; }
    static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    /**
     * nights: every stored night (any order); goalH: the sleep goal in hours; stress: the stress result (or null); effort: today's effort 0-21 (NaN if none);
     * wakeTs: unix seconds when the latest night ended (0 if none); nowTs: unix seconds of the newest record.
     */
    static Result compute(List<TodayModel.NightStat> nights, double goalH, StressModel.Result stress, double effort, long wakeTs, long nowTs) {
        Result R = new Result();
        List<TodayModel.NightStat> ns = new ArrayList<>(nights == null ? new ArrayList<TodayModel.NightStat>() : nights);
        Collections.sort(ns, new Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });

        // 1 sleep debt
        {
            Part p = R.parts[0]; double sw = 0, ss = 0, sum = 0; int n = 0;
            for (TodayModel.NightStat s : ns) {
                if (Double.isNaN(s.tstH) || s.bed > nowTs || nowTs - s.bed > SLEEP_DAYS * 86400L) continue;
                double w = Math.pow(NIGHT_DECAY_PER_DAY, (nowTs - s.bed) / 86400.0), sf = Math.max(0.0, goalH - s.tstH);
                sw += w; ss += w * sf; sum += sf; n++;
            }
            if (n >= MIN_SLEEP_NIGHTS && sw > 0) {
                double mean = ss / sw; p.score = 100.0 * clamp01(mean / DEBT_FULL_H);
                p.detail = String.format(Locale.UK, "%d nights in the last %d days; recent-weighted shortfall %.1f h a night against your %.1f h goal (plain total %.1f h)", n, SLEEP_DAYS, mean, goalH, sum);
            } else p.detail = "needs " + MIN_SLEEP_NIGHTS + " nights in the last " + SLEEP_DAYS + " days; has " + n;
        }

        // 2 body signs: the last BODY_NIGHTS nights against the baseline before each (TodayModel.recovery)
        {
            Part p = R.parts[1]; double zw = 0, zs = 0; int used = 0, seen = 0;
            for (int i = ns.size() - 1; i >= 0 && seen < BODY_NIGHTS; i--) {
                TodayModel.NightStat s = ns.get(i);
                if (s.bed > nowTs || nowTs - s.bed > 4L * 86400L) continue;
                int rank = seen++; double w = BODY_NIGHTS - rank;
                double[] rec = TodayModel.recovery(s, ns);
                if (rec == null) continue;
                zs += w * 0.5 * (rec[1] + rec[2]); zw += w; used++;
            }
            if (used >= MIN_BODY_NIGHTS && zw > 0) {
                double z = zs / zw; p.score = 100.0 * clamp01(-z / BODY_FULL_Z);
                p.detail = String.format(Locale.UK, "last %d nights, resting heart rate and HRV together: %.1f spreads %s than your usual", used, Math.abs(z), z < 0 ? "worse" : "better");
            } else p.detail = "needs at least " + MIN_BODY_NIGHTS + " of the last " + BODY_NIGHTS + " nights with a baseline of " + TodayModel.MIN_BASELINE + " earlier nights";
        }

        // 3 daytime stress by finished day
        {
            Part p = R.parts[2];
            if (stress != null && stress.dayStart > 0 && !stress.all.isEmpty()) {
                int days = STRESS_RECENT_DAYS + STRESS_USUAL_DAYS;
                double[] sum = new double[days + 1]; int[] cnt = new int[days + 1];
                for (StressModel.Bin b : stress.all) {
                    if (b.score < 0) continue;
                    int k = (int) Math.floor((b.t - stress.dayStart) / 86400.0);       // -1 = yesterday
                    if (k >= 0 || k < -days) continue;
                    sum[-k] += b.score; cnt[-k]++;
                }
                double rs = 0, us = 0; int rn = 0, un = 0;
                for (int j = 1; j <= days; j++) {
                    if (cnt[j] < MIN_DAY_BINS) continue;
                    double m = sum[j] / cnt[j];
                    if (j <= STRESS_RECENT_DAYS) { rs += m; rn++; } else { us += m; un++; }
                }
                if (rn >= MIN_RECENT_DAYS && un >= MIN_USUAL_DAYS) {
                    double rec = rs / rn, usual = Math.max(STRESS_USUAL_FLOOR, us / un), up = rec / usual - 1.0;
                    p.score = 100.0 * clamp01(up / STRESS_FULL_UP);
                    p.detail = String.format(Locale.UK, "the last %d finished days averaged %.0f against your usual %.0f (%+.0f %%)", rn, rec, us / un, 100.0 * (rec / (us / un) - 1.0));
                } else p.detail = "needs " + MIN_RECENT_DAYS + " of the last " + STRESS_RECENT_DAYS + " finished days and " + MIN_USUAL_DAYS + " earlier ones with stress scored; has " + rn + " and " + un;
            } else p.detail = "the stress ring has no days scored yet";
        }

        // 4 effort today
        {
            Part p = R.parts[3];
            if (!Double.isNaN(effort)) { p.score = 100.0 * clamp01(effort / 21.0); p.detail = String.format(Locale.UK, "%.1f of 21 so far today", effort); }
            else p.detail = "no effort yet";
        }

        // 5 time awake
        {
            Part p = R.parts[4];
            double h = wakeTs > 0 ? (nowTs - wakeTs) / 3600.0 : Double.NaN;
            if (!Double.isNaN(h) && h >= 0 && h < 24.0) {
                p.score = 100.0 * clamp01((h - AWAKE_ZERO_H) / (AWAKE_FULL_H - AWAKE_ZERO_H));
                p.detail = String.format(Locale.UK, "%d h %02d min since the night ended", (int) h, (int) Math.round((h - (int) h) * 60.0) % 60);
            } else p.detail = "the last night ended more than a day ago, or none is known";
        }

        // combine
        double wsum = 0; int present = 0;
        for (int i = 0; i < 5; i++) if (R.parts[i].ok()) { wsum += WEIGHT[i]; present++; }
        if ((!R.parts[0].ok() && !R.parts[1].ok()) || present < 2 || wsum <= 0) {
            StringBuilder m = new StringBuilder();
            for (int i = 0; i < 5; i++) if (!R.parts[i].ok()) m.append(m.length() > 0 ? "; " : "").append(NAME[i].toLowerCase(Locale.UK)).append(": ").append(R.parts[i].detail);
            R.why = (!R.parts[0].ok() && !R.parts[1].ok() ? "Needs sleep debt or body signs from the nights. " : "") + m;
            return R;
        }
        double tot = 0;
        for (int i = 0; i < 5; i++) {
            Part p = R.parts[i];
            if (p.ok()) { p.weight = WEIGHT[i] / wsum; p.contrib = p.score * p.weight; tot += p.contrib; }
        }
        R.score = tot;
        R.builtUp = group(R, 0, 3); R.today = group(R, 3, 5);
        return R;
    }

    private static double group(Result R, int from, int to) {
        double w = 0, s = 0;
        for (int i = from; i < to; i++) if (R.parts[i].ok()) { w += WEIGHT[i]; s += WEIGHT[i] * R.parts[i].score; }
        return w > 0 ? s / w : Double.NaN;
    }
}
