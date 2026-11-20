package com.noop.mgecg;

// FILE VERSION 0.71.3 (20 Nov 2026): wording only: the "counted" line now says the hours are the Sleep page's own figure. The nights passed in are RhythmData.Bundle.nights.
// FILE VERSION 0.58.0 (15 Nov 2026): NEW. The opinions of the wellbeing page, worked out from the nights the sleep screen already finds (SleepRegularity.Night: bed, wake, minutes asleep). Pure Java, no Android, tested off the phone
// against the real nights. Rules: a figure is only shown if it is counted from the data; a pattern needs 5 nights each way (fewer is labelled "early signal"); a low night is called information, never a grade.
// Work mornings are wake-ups before 07:00 (checked against his real nights: 14 of 20 in 22 Oct to 12 Nov). SRI bands (regular 71 or more, irregular 51 or less) are those of the UK Biobank study of 79,666 adults (Psychological Medicine 2025).

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WellbeingModel {
    private WellbeingModel() {}

    static final double SRI_REGULAR = 71.0, SRI_IRREGULAR = 51.0;
    static final int WAKE_TOL_MIN = 3;               // "the same wake-up": within 3 minutes of the latest
    static final int MIN_EACH = 5, MIN_SIGNAL = 2;   // nights each way before a pattern / an early signal
    static final int LATE_BED_MIN = 23 * 60, WORK_WAKE_MIN = 7 * 60;
    static final double GOAL_H = 7.0, DIFF_H = 0.5, SHORT_H = 6.0;
    static final String RULE = "wellbeing_v0.58.0";

    static final class Out {
        boolean enough;                     // at least 3 nights
        int n;                              // nights in the window
        double[] hrs = new double[0];       // last (up to) 7 nights, oldest first
        int ge7Last, nLast;                 // of those, nights of 7 h or more
        int ge7All, nAll;
        double sri = Double.NaN;
        String word = "Gathering", wordNote = "";
        String headline = "", body = "", evidence = "";
        int wakeRun, latestWakeMin = -1;
        int[] stripWake = new int[0];       // wake minute of day of the last (up to) 8 work mornings, oldest first
        int winKind;                        // 0 none, 1 early signal, 2 pattern
        String winHead = "", winBody = "", winEvidence = "";
        int nEarly, nLate, nWork; double mEarly = Double.NaN, mLate = Double.NaN;
        boolean bounce; String bounceHead = "", bounceEvidence = "";
        double longestH = Double.NaN; int longestDay = -1;
        boolean planOn, planChecked, planKept; String planHead = "", planBody = "", planEvidence = ""; int planKeptN, planNightsN;
    }

    static String hm(double h) {
        int m = (int) Math.round(h * 60.0);
        return (m / 60) + " h " + String.format(Locale.UK, "%02d", m % 60);
    }

    static String clock(int minOfDay) {
        int m = ((minOfDay % 1440) + 1440) % 1440;
        return String.format(Locale.UK, "%02d:%02d", m / 60, m % 60);
    }

    private static int circ(int a, int b) { int d = Math.abs(a - b) % 1440; return Math.min(d, 1440 - d); }

    /**
     * nights: oldest first, as SleepRegularity finds them. sri: the sleep regularity index or NaN. baseEpochDay: epoch day (local) of day 0 of the series.
     * planBedMin: the minutes after midnight the plan says to be in bed by (-1 no plan); planFromEpochDay: the local day the plan was set (its first night is that evening).
     */
    static Out compute(List<SleepRegularity.Night> nights, double sri, long baseEpochDay, int planBedMin, long planFromEpochDay) {
        Out o = new Out();
        o.n = nights == null ? 0 : nights.size();
        o.sri = sri;
        if (o.n < 3) {
            o.headline = "Your story starts here.";
            o.body = "Wear the strap for a few nights and this page fills in from your own sleep. Nothing here is guessed.";
            o.evidence = "Your data: " + o.n + (o.n == 1 ? " night" : " nights") + " found so far; the page needs 3.";
            return o;
        }
        o.enough = true;
        int from = Math.max(0, o.n - 7);
        o.nLast = o.n - from;
        o.hrs = new double[o.nLast];
        for (int i = from; i < o.n; i++) {
            double h = nights.get(i).asleepMin / 60.0;
            o.hrs[i - from] = h;
            if (h >= GOAL_H) o.ge7Last++;
        }
        o.nAll = o.n;
        for (SleepRegularity.Night x : nights) if (x.asleepMin / 60.0 >= GOAL_H) o.ge7All++;

        SleepRegularity.Night last = nights.get(o.n - 1), prev = nights.get(o.n - 2);
        double lastH = last.asleepMin / 60.0, prevH = prev.asleepMin / 60.0;

        // ---- the word on the ring
        if (!Double.isNaN(sri)) {
            o.word = sri >= SRI_REGULAR ? "Steady" : sri > SRI_IRREGULAR ? "Settling" : "Varied";
            o.wordNote = String.format(Locale.UK, "Sleep regularity %.0f of 100. In the UK Biobank study 71 or more counted as regular, 51 or less as irregular.", sri);
        } else {
            o.word = "Gathering";
            o.wordNote = "The regularity score needs 24 hours of minutes that can be compared one day apart.";
        }

        // ---- wake-up run
        int lastWake = ((last.wakeMin % 1440) + 1440) % 1440;
        o.latestWakeMin = lastWake;
        int run = 0;
        for (int i = o.n - 1; i >= 0; i--) {
            int w = ((nights.get(i).wakeMin % 1440) + 1440) % 1440;
            if (circ(w, lastWake) <= WAKE_TOL_MIN) run++; else break;
        }
        o.wakeRun = run;

        // ---- work mornings (woke before 07:00) and the strip
        List<Integer> wk = new ArrayList<Integer>();
        double sumE = 0, sumL = 0;
        for (SleepRegularity.Night x : nights) {
            int w = ((x.wakeMin % 1440) + 1440) % 1440;
            if (w >= WORK_WAKE_MIN) continue;
            wk.add(w);
            o.nWork++;
            double h = x.asleepMin / 60.0;
            // bedMin counts from midnight of the day the night starts on, so after-midnight bed times are above 1440: those are late (23:00 or later)
            if (x.bedMin < LATE_BED_MIN) { o.nEarly++; sumE += h; } else { o.nLate++; sumL += h; }
        }
        if (o.nEarly > 0) o.mEarly = sumE / o.nEarly;
        if (o.nLate > 0) o.mLate = sumL / o.nLate;
        int sk = Math.max(0, wk.size() - 8);
        o.stripWake = new int[wk.size() - sk];
        for (int i = sk; i < wk.size(); i++) o.stripWake[i - sk] = wk.get(i);

        // ---- personal best in the window
        for (SleepRegularity.Night x : nights) { double h = x.asleepMin / 60.0; if (Double.isNaN(o.longestH) || h > o.longestH) { o.longestH = h; o.longestDay = x.day; } }

        // ---- the main opinion
        String why = "Why it matters: in 79,666 UK adults, regular sleepers had 38% lower depression risk and 33% lower anxiety risk than irregular ones, whatever their hours. An association, not proof.";
        String counted = " Hours are the Sleep page's own figure for that night: the strap's sleep state plus short quiet stretches in bed.";
        if (run >= 3) {
            String[] nw = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
            o.headline = "Up at " + clock(lastWake) + " " + (run < nw.length ? nw[run] : String.valueOf(run)) + " mornings running.";
            o.body = "That regularity is the quiet win. In large studies, regular timing goes with better mood even when total hours are enough.";
            o.evidence = "Your data: asleep " + hm(lastH) + " last night; " + o.ge7Last + " of the last " + o.nLast + " nights reached 7 h." + counted + "\n" + why;
        } else if (lastH >= GOAL_H) {
            o.headline = "A solid night: " + hm(lastH) + ".";
            o.body = "That is a good base for today. Regular timing is what carries it, so the same wake-up tomorrow is the best follow-up.";
            o.evidence = "Your data: " + o.ge7Last + " of the last " + o.nLast + " nights reached 7 h." + counted + "\n" + why;
        } else if (lastH < SHORT_H) {
            o.headline = "A short night: " + hm(lastH) + ". It is information, not a grade.";
            o.body = "Go gently with the effort you ask of today. The evening is where you can help tomorrow.";
            o.evidence = "Your data: " + o.ge7Last + " of the last " + o.nLast + " nights reached 7 h." + counted;
        } else {
            o.headline = "A fair night: " + hm(lastH) + ".";
            o.body = "Close to your 7 h mark. A steady wake-up time helps more than chasing extra hours.";
            o.evidence = "Your data: " + o.ge7Last + " of the last " + o.nLast + " nights reached 7 h." + counted + "\n" + why;
        }

        // ---- bounce back
        if (prevH < SHORT_H && lastH >= GOAL_H) {
            o.bounce = true;
            o.bounceHead = "After the short night of " + hm(prevH) + " you slept " + hm(lastH) + " the very next night. A low night is information, not a grade.";
            o.bounceEvidence = "Your data: " + hm(prevH) + " then " + hm(lastH) + ". Why this wording: a meta-analysis of 21 trials found compassion-based training showed benefits on a range of outcomes.";
        }

        // ---- one small win for tonight
        boolean both = o.nEarly >= MIN_SIGNAL && o.nLate >= MIN_SIGNAL && !Double.isNaN(o.mEarly) && !Double.isNaN(o.mLate) && o.mEarly - o.mLate >= DIFF_H;
        if (both && o.nEarly >= MIN_EACH && o.nLate >= MIN_EACH) o.winKind = 2; else if (both) o.winKind = 1;
        if (o.winKind > 0) {
            o.winHead = "Lights out before 23:00.";
            o.winBody = o.winKind == 2 ? "Your wake-up is fixed on work days, so the evening is your only lever. Your own nights say it works."
                    : "Your wake-up is fixed on work days, so the evening is your only lever. Your own nights point the same way.";
            o.winEvidence = String.format(Locale.UK, "Your data: on work nights, bed before 23:00 gave %s (%d nights); after 23:00, %s (%d nights).", hm(o.mEarly), o.nEarly, hm(o.mLate), o.nLate)
                    + (o.winKind == 1 ? " Early signal: it needs " + MIN_EACH + " each way before it counts as a pattern." : "");
        } else {
            o.winHead = "Same wake-up time again tomorrow.";
            o.winBody = "Regular timing is the part you control most.";
            o.winEvidence = o.nWork >= 1 ? "Your data: " + o.nWork + " work mornings in this window." : "Your data: no work mornings (before 07:00) in this window yet.";
        }

        // ---- the plan check
        if (planBedMin >= 0) {
            o.planOn = true;
            int kept = 0, tot = 0;
            for (SleepRegularity.Night x : nights) {
                long nightDay = baseEpochDay + x.day;
                if (nightDay < planFromEpochDay) continue;
                tot++;
                if (x.bedMin <= planBedMin) kept++;
            }
            o.planKeptN = kept; o.planNightsN = tot;
            long lastDay = baseEpochDay + last.day;
            if (lastDay >= planFromEpochDay) {
                o.planChecked = true;
                o.planKept = last.bedMin <= planBedMin;
                String bedAt = clock(last.bedMin);
                if (o.planKept) {
                    o.planHead = "Plan kept: in bed at " + bedAt + ".";
                    o.planBody = "That is you doing what you said you would. It is the habit that counts, not any one night.";
                } else {
                    o.planHead = "Not this time: in bed at " + bedAt + ".";
                    o.planBody = "That is information, not a grade. One night does not undo a plan; tonight is a fresh start.";
                }
                o.planEvidence = "Your data: plan is in bed by " + clock(planBedMin) + ". Kept on " + kept + " of " + tot + (tot == 1 ? " night" : " nights") + " since you set it.";
            } else {
                o.planHead = "Your plan starts tonight: in bed by " + clock(planBedMin) + ".";
                o.planBody = "Tomorrow morning this card says how it went.";
                o.planEvidence = "";
            }
        }
        return o;
    }
}
