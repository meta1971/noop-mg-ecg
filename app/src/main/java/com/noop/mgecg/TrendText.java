package com.noop.mgecg;

// FILE VERSION 0.94.0 (26 Nov 2026): week(): the one sentence "what changed this week" (Part list, so the screen can colour the changed figures).
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

    /** A piece of the week sentence: kind 0 plain, 1 moved the better way, 2 moved the other way (worth watching), 3 moved, this measure has no better direction. */
    static final class Part { final String text; final int kind; Part(String text, int kind) { this.text = text; this.kind = kind; } }

    private static String lowerFirst(String n) { return n.length() > 1 && Character.isLowerCase(n.charAt(1)) ? Character.toLowerCase(n.charAt(0)) + n.substring(1) : n; }

    /**
     * "Resting heart rate is 3 bpm lower than your usual and HRV is 9 ms higher. Your QTc is where it usually sits." Up to three measures that sit outside your usual (the furthest first), then up to two that sit inside it.
     * Only measures with a usual (5 earlier values) are named; the figure is the last three days against that usual (TrendStats recent, delta).
     */
    static List<Part> week(List<TrendSeries.Series> all, List<TrendSeries.Judged> js) {
        List<Part> out = new ArrayList<>();
        List<int[]> moved = new ArrayList<>(); List<Integer> same = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            TrendStats.Result r = js.get(i).r;
            if (r.n == 0 || !r.enough) continue;
            if (r.status != 0) moved.add(new int[] { i }); else same.add(i);
        }
        if (moved.isEmpty() && same.isEmpty()) { out.add(new Part("Not enough history yet to say what changed. Each measure needs 5 earlier values to have a usual.", 0)); return out; }
        java.util.Collections.sort(moved, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Double.compare(Math.abs(js.get(b[0]).r.z), Math.abs(js.get(a[0]).r.z)); }
        });
        int m = Math.min(3, moved.size());
        for (int k = 0; k < m; k++) {
            TrendSeries.Series s = all.get(moved.get(k)[0]); TrendStats.Result r = js.get(moved.get(k)[0]).r;
            int f = favour(s, r.delta);
            out.add(new Part((k == 0 ? s.name : lowerFirst(s.name)) + " is ", 0));
            out.add(new Part(fmtU(s, Math.abs(r.delta)) + (r.delta > 0 ? " higher" : " lower"), f > 0 ? 1 : f < 0 ? 2 : 3));
            out.add(new Part(k == 0 ? " than your usual" : "", 0));
            out.add(new Part(k < m - 2 ? ", " : k == m - 2 ? " and " : ".", 0));
        }
        if (moved.size() > m) out.add(new Part(" " + (moved.size() - m) + " more " + (moved.size() - m == 1 ? "measure is" : "measures are") + " outside your usual too.", 0));
        if (!same.isEmpty()) {
            int q = Math.min(2, same.size());
            StringBuilder b = new StringBuilder(moved.isEmpty() ? "" : " ");
            b.append("Your ");
            for (int k = 0; k < q; k++) { String nm = all.get(same.get(k)).name; b.append(k == 0 ? lowerFirst(nm) : lowerFirst(nm)); if (k < q - 2) b.append(", "); else if (k == q - 2) b.append(" and "); }
            b.append(q == 1 ? " is where it usually sits." : " are where they usually sit.");
            if (same.size() > q) b.append(" ").append(same.size() - q).append(" more ").append(same.size() - q == 1 ? "is" : "are").append(" inside your usual too.");
            out.add(new Part(b.toString(), 0));
        }
        return out;
    }

    /** The week sentence as plain text. */
    static String weekText(List<TrendSeries.Series> all, List<TrendSeries.Judged> js) {
        StringBuilder b = new StringBuilder(); for (Part p : week(all, js)) b.append(p.text); return b.toString();
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
