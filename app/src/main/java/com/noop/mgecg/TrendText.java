package com.noop.mgecg;

// FILE VERSION 0.49.0 (9 Nov 2026): the words of the Trends screen (status chips, trend lines, the pattern summary), pure Java so they can be tested off the phone.

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TrendText {
    private TrendText() {}

    static final int LEARNING = 0, USUAL = 1, BETTER = 2, WATCH = 3, WARN = 4;

    static String fmt(TrendSeries.Series s, double x) { return Double.isNaN(x) ? "\u2014" : String.format(Locale.UK, "%." + s.dec + "f", x); }
    static String fmtU(TrendSeries.Series s, double x) { return Double.isNaN(x) ? "\u2014" : fmt(s, x) + (s.unit.isEmpty() ? "" : " " + s.unit); }
    static String signed(TrendSeries.Series s, double x) { return (x > 0 ? "+" : x < 0 ? "\u2212" : "") + fmtU(s, Math.abs(x)); }

    /** +1 when the movement is the better direction for this metric, -1 when the worse one, 0 when the metric has no better direction. */
    static int favour(TrendSeries.Series s, double sign) { return s.good == 0 ? 0 : (s.good * sign > 0 ? 1 : -1); }

    /** {text, kind}. */
    static Object[] chip(TrendSeries.Series s, TrendStats.Result r) {
        if (!r.enough) return new Object[] { "Learning your usual", LEARNING };
        if (r.status == 0) return new Object[] { "In your usual range", USUAL };
        int f = favour(s, r.z); boolean well = Math.abs(r.status) == 2;
        String t = well ? (r.status > 0 ? "Well above usual" : "Well below usual") : (r.status > 0 ? "Higher than usual" : "Lower than usual");
        int kind = f > 0 ? BETTER : f < 0 ? (well ? WARN : WATCH) : WATCH;
        return new Object[] { t, kind };
    }

    static String trendText(TrendSeries.Series s, TrendStats.Result r, double winDays) {
        String per = r.n + (s.ecg ? (r.n == 1 ? " reading" : " readings") : (r.n == 1 ? " night" : " nights"));
        if (r.trend != 0) return (r.trend > 0 ? "Rising " : "Falling ") + String.format(Locale.UK, "%." + Math.min(2, s.dec + 1) + "f", Math.abs(r.slopePerWeek)) + (s.unit.isEmpty() ? "" : " " + s.unit) + " a week  \u00b7  " + per;
        if (r.n >= 6) return "Steady over " + (int) winDays + " days  \u00b7  " + per;
        return per + " in " + (int) winDays + " days";
    }

    /** The pattern in a few sentences: what is better than your usual, what to watch, what is as usual. */
    static String summary(List<TrendSeries.Series> all, List<TrendSeries.Judged> js, double winDays) {
        List<String> good = new ArrayList<>(), watch = new ArrayList<>(), usual = new ArrayList<>(); int nn = 0, ne = 0, learning = 0;
        for (int i = 0; i < all.size(); i++) {
            TrendSeries.Series s = all.get(i); TrendStats.Result r = js.get(i).r; if (r.n == 0) continue;
            if (s.ecg) ne = Math.max(ne, js.get(i).nAll); else nn = Math.max(nn, js.get(i).nAll);
            if (!r.enough) { learning++; continue; }
            String nm = s.name;
            if (r.status != 0) {
                int f = favour(s, r.z); String how = signed(s, r.delta) + " " + (r.z > 0 ? "above" : "below") + " your usual";
                if (f > 0) good.add(nm + " is " + how); else watch.add(nm + " is " + how);
            } else if (r.trend != 0) {
                int f = favour(s, r.slopePerWeek); String how = (r.trend > 0 ? "rising " : "falling ") + String.format(Locale.UK, "%.1f", Math.abs(r.slopePerWeek)) + " a week";
                if (f > 0) good.add(nm + " is " + how); else if (f < 0) watch.add(nm + " is " + how); else usual.add(nm + " (" + how + ")");
            } else usual.add(nm);
        }
        StringBuilder b = new StringBuilder();
        if (!good.isEmpty()) b.append("Better than your usual: ").append(join(good, 3)).append(". ");
        if (!watch.isEmpty()) b.append("Worth watching: ").append(join(watch, 3)).append(". ");
        if (good.isEmpty() && watch.isEmpty()) b.append(usual.isEmpty() ? "Not enough history to say what is usual for you yet. " : "Everything is within your usual range. ");
        else if (!usual.isEmpty()) b.append("As usual: ").append(join(usual, 4)).append(". ");
        if (learning > 0) b.append(learning).append(learning == 1 ? " measure is" : " measures are").append(" still learning your usual (it needs 5 earlier values). ");
        b.append("Based on ").append(nn).append(" nights and ").append(ne).append(" ECG readings in the last ").append((int) winDays).append(" days.");
        return b.toString();
    }

    private static String join(List<String> l, int max) {
        StringBuilder b = new StringBuilder(); int m = Math.min(max, l.size());
        for (int i = 0; i < m; i++) { if (i > 0) b.append(i == m - 1 ? " and " : ", "); b.append(l.get(i)); }
        if (l.size() > max) b.append(" and ").append(l.size() - max).append(" more"); return b.toString();
    }
}
