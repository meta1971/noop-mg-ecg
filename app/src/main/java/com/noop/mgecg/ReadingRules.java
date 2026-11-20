package com.noop.mgecg;

// FILE VERSION 0.71.3 (20 Nov 2026): two reading rules from addendum 318.5 as display text: (1) a note when QT, ST and T rest on fewer than 60 averaged beats, with the expected QT error;
// (2) the person's own between-reading spread of QT and QTcF beside the in-reading 95 % range, which covers beat sampling only.
// (1) Expected QT error against the whole-reading value: 7.7 ms at 30 beats, 4.7 at 60, 3.1 at 80, 2.3 at 100 (318.5 / S23b, 37 readings with 200+ beats, ONE person); between the
//     measured points the value is interpolated linearly (arithmetic, not measured); below 30 beats it was not measured and the text says "more than 7.7 ms". For ST and T no error was measured.
// (2) Spread = 1.4826 x median absolute deviation of the last 40 readings that stored a QT, shown from 8 readings (the figure of 318.4 S26 was 13.6 ms for QT and 5.3 ms for QTcF in 129 readings of one person).
//     Nothing is stored for readings made before 0.71.3, so the spread appears only after 8 readings made with this build. The 8 readings and 40 readings are GUESSES.
// Pure Java (no Android), tested off the phone.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ReadingRules {
    private ReadingRules() {}

    static final int FEW_BEATS = 60, BAND_MIN = 8, BAND_MAX = 40;

    /** Expected QT error (ms) for an average of n beats; NaN below 30 (not measured). */
    static double expectedQtErrorMs(int n) {
        if (n < 30) return Double.NaN;
        final double[][] pt = { {30, 7.7}, {60, 4.7}, {80, 3.1}, {100, 2.3} };
        if (n >= 100) return 2.3;
        for (int i = 0; i < pt.length - 1; i++) {
            if (n <= pt[i + 1][0]) { double f = (n - pt[i][0]) / (pt[i + 1][0] - pt[i][0]); return pt[i][1] + f * (pt[i + 1][1] - pt[i][1]); }
        }
        return 2.3;
    }

    /** Empty when 60 beats or more (or no count); else one plain sentence group. */
    static String fewBeatsNote(int beats) {
        if (beats <= 0 || beats >= FEW_BEATS) return "";
        double e = expectedQtErrorMs(beats);
        String err = Double.isNaN(e) ? "more than 7.7 ms (not measured below 30 beats)" : String.format(Locale.US, "about %.1f ms", e);
        return "Only " + beats + " beats were averaged (fewer than 60), so QT, ST and T are rougher than usual. The QT of an average this short was " + err
                + " off the full-reading value in one person's earlier readings; the ST and T error was not measured.";
    }

    /** {n, median, robust SD} of the last BAND_MAX finite positive values, or null with fewer than BAND_MIN. */
    static double[] band(List<Double> all) {
        List<Double> v = new ArrayList<>();
        if (all != null) for (Double d : all) if (d != null && !Double.isNaN(d) && d > 0) v.add(d);
        if (v.size() < BAND_MIN) return null;
        v = new ArrayList<>(v.subList(Math.max(0, v.size() - BAND_MAX), v.size()));
        double med = median(v);
        List<Double> dev = new ArrayList<>();
        for (double d : v) dev.add(Math.abs(d - med));
        return new double[] { v.size(), med, 1.4826 * median(dev) };
    }

    private static double median(List<Double> v) {
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int m = c.size() / 2;
        return c.size() % 2 == 1 ? c.get(m) : (c.get(m - 1) + c.get(m)) / 2.0;
    }

    // The readings stored so far (set by MainActivity after each saved reading); n < 0 = never set.
    private static volatile List<Double> pastQt = null, pastQtcF = null;
    static void setPast(List<Double> qt, List<Double> qtcF) { pastQt = qt; pastQtcF = qtcF; }
    static void clearPast() { pastQt = null; pastQtcF = null; }

    /** One sentence for beside the in-reading range; empty when the history was never loaded. */
    static String betweenReadings() {
        List<Double> q = pastQt, c = pastQtcF;
        if (q == null) return "";
        double[] bq = band(q);
        if (bq == null) return "Your own reading-to-reading spread of QT shows after " + BAND_MIN + " readings made with this version (" + q.size() + " so far).";
        double[] bc = band(c);
        return String.format(Locale.US, "Your last %d readings: QT median %.0f ms, spread (SD) %.0f ms", (int) bq[0], bq[1], bq[2])
                + (bc == null ? "" : String.format(Locale.US, "; QTc Fridericia median %.0f ms, spread %.0f ms", bc[1], bc[2])) + ". Readings differ from each other by this much, which the range above does not include.";
    }
}
