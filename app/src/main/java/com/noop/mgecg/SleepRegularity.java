package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. Sleep regularity index and night timing from a per-minute asleep / awake series. Pure Java, no Android, tested off the phone.
// Definition (as in the UK Biobank study, eLife 88359): the chance of being in the same state (asleep or awake) at two moments 24 h apart, scaled so that
// 100 = perfectly regular and 0 = random timing: SRI = -100 + 200 x P(same state). Minutes with no data are left out of the pairs.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SleepRegularity {
    private SleepRegularity() {}

    /** UK Biobank, 88,975 adults with 7 days of wrist accelerometry (eLife 88359): 5th percentile 41, median about 60, 95th percentile 75. */
    static final double UKB_P5 = 41.0, UKB_MEDIAN = 60.0, UKB_P95 = 75.0;
    static final int MIN_PAIRS = 1440;                 // at least 24 h of valid minute pairs

    static final class Night {
        int day;                                        // index of the local day the night starts on (minute 0 of the series = local midnight of day 0)
        int bedMin, wakeMin;                            // minutes after that day's midnight; wake may be above 1440
        int asleepMin;
    }

    static final class Result {
        double sri = Double.NaN;
        int pairs, validMinutes;
        final List<Night> nights = new ArrayList<Night>();
        double bedSd = Double.NaN, wakeSd = Double.NaN, midSd = Double.NaN, meanSleepH = Double.NaN;
        double bedMean = Double.NaN, wakeMean = Double.NaN;
    }

    /** s: one byte per minute from local midnight of day 0: 1 asleep, 0 awake, -1 no data. */
    static Result analyse(byte[] s) {
        Result r = new Result();
        if (s == null) return r;
        int match = 0, pairs = 0;
        for (int i = 0; i + 1440 < s.length; i++) {
            byte a = s[i], b = s[i + 1440];
            if (a < 0 || b < 0) continue;
            pairs++;
            if (a == b) match++;
        }
        for (byte v : s) if (v >= 0) r.validMinutes++;
        r.pairs = pairs;
        if (pairs >= MIN_PAIRS) r.sri = -100.0 + 200.0 * match / pairs;
        findNights(s, r);
        return r;
    }

    /** The main sleep of each day: the longest run of sleep (awake gaps up to 30 min and missing gaps up to 10 min stay inside it) between 18:00 and 12:00 the next day, at least 3 h asleep. */
    private static void findNights(byte[] s, Result r) {
        int days = s.length / 1440;
        for (int d = 0; d < days; d++) {
            int w0 = d * 1440 + 18 * 60, w1 = Math.min(s.length, (d + 1) * 1440 + 12 * 60);
            if (w1 - w0 < 12 * 60) continue;
            int valid = 0;
            for (int i = w0; i < w1; i++) if (s[i] >= 0) valid++;
            if (valid < 0.7 * (w1 - w0)) continue;
            int bestA = 0, bestS = -1, bestE = -1;
            int i = w0;
            while (i < w1) {
                if (s[i] != 1) { i++; continue; }
                int st = i, last = i, asleep = 0, gap = 0, gapMiss = 0;
                int k = i;
                while (k < w1) {
                    if (s[k] == 1) { asleep++; last = k; gap = 0; gapMiss = 0; }
                    else if (s[k] == 0) { gap++; if (gap > 30) break; }
                    else { gapMiss++; if (gapMiss > 10) break; }
                    k++;
                }
                if (asleep > bestA) { bestA = asleep; bestS = st; bestE = last + 1; }
                i = Math.max(k, last + 1);
            }
            if (bestA >= 180) {
                Night n = new Night();
                n.day = d; n.bedMin = bestS - d * 1440; n.wakeMin = bestE - d * 1440; n.asleepMin = bestA;
                r.nights.add(n);
            }
        }
        int m = r.nights.size();
        if (m >= 1) {
            double sa = 0; for (Night n : r.nights) sa += n.asleepMin;
            r.meanSleepH = sa / m / 60.0;
            r.bedMean = mean(r.nights, 0); r.wakeMean = mean(r.nights, 1);
        }
        if (m >= 4) {
            r.bedSd = sd(r.nights, 0); r.wakeSd = sd(r.nights, 1); r.midSd = sd(r.nights, 2);
        }
    }

    private static double val(Night n, int what) { return what == 0 ? n.bedMin : what == 1 ? n.wakeMin : 0.5 * (n.bedMin + n.wakeMin); }
    private static double mean(List<Night> l, int what) { double s = 0; for (Night n : l) s += val(n, what); return s / l.size(); }
    private static double sd(List<Night> l, int what) {
        double m = mean(l, what), q = 0;
        for (Night n : l) q += (val(n, what) - m) * (val(n, what) - m);
        return Math.sqrt(q / (l.size() - 1));
    }

    /** Where the value sits against the UK Biobank figures: a rough percentile (piecewise linear through the three published points), or NaN outside 5-95 handled as "below" / "above". */
    static double approxPercentile(double sri) {
        if (Double.isNaN(sri)) return Double.NaN;
        if (sri <= UKB_P5) return 5.0 * Math.max(0.0, sri) / UKB_P5;
        if (sri <= UKB_MEDIAN) return 5.0 + 45.0 * (sri - UKB_P5) / (UKB_MEDIAN - UKB_P5);
        if (sri <= UKB_P95) return 50.0 + 45.0 * (sri - UKB_MEDIAN) / (UKB_P95 - UKB_MEDIAN);
        return 95.0 + 5.0 * Math.min(1.0, (sri - UKB_P95) / (100.0 - UKB_P95));
    }

    static String clock(double minutes) {
        if (Double.isNaN(minutes)) return "--:--";
        int m = (int) Math.round(minutes) % 1440; if (m < 0) m += 1440;
        return String.format(Locale.UK, "%02d:%02d", m / 60, m % 60);
    }

    /** One plain sentence on where the index sits. */
    static String describe(double sri) {
        if (Double.isNaN(sri)) return "Not enough days with the strap on yet (it needs 24 hours of minutes that can be compared one day apart).";
        String where = sri < UKB_P5 ? "below the 5th percentile (41)" : sri < UKB_MEDIAN ? "below the median (60)" : sri < UKB_P95 ? "above the median (60)" : "above the 95th percentile (75)";
        return String.format(Locale.UK, "%.0f: %s of 88,975 UK adults measured over 7 days.", sri, where);
    }
}
