package com.noop.mgecg;

// FILE VERSION 0.64.0 (16 Nov 2026): pure logic for the Wellbeing additions: mood and energy log (two taps), "your own pattern" with a sample gate, calm-minute before/after RMSSD from the live beat intervals,
// if-then plans and the week page. No Android here, so it is tested off the phone. Nothing here gives advice; every statement is a count or a mean of the person's own entries, and the pattern card never states a cause.
// Storage format (strings kept in the "wellbeing" preferences by WellbeingActivity): mind_log lines "ms,day,mood,energy"; calm_log lines "startMs,endMs,pre,post,nPre,nPost" (pre/post blank until computed).

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class Mind64 {
    private Mind64() {}

    static final int KEEP = 400, MIN_ENTRIES = 20, MIN_DAYS = 14, MIN_GROUP = 5;
    static final double SLEEP_CUT_H = 7.0, MIN_GAP = 0.3;

    static final class Entry { long ms, day; int mood, energy; }

    static List<Entry> parse(String s) {
        List<Entry> l = new ArrayList<>(); if (s == null) return l;
        for (String ln : s.split("\n")) {
            String[] p = ln.trim().split(","); if (p.length < 4) continue;
            try { Entry e = new Entry(); e.ms = Long.parseLong(p[0]); e.day = Long.parseLong(p[1]); e.mood = Integer.parseInt(p[2]); e.energy = Integer.parseInt(p[3]); if (e.mood >= 1 && e.mood <= 5 && e.energy >= 1 && e.energy <= 5) l.add(e); } catch (Throwable ignored) { }
        }
        return l;
    }
    static String add(String s, long ms, long day, int mood, int energy) {
        List<Entry> l = parse(s); StringBuilder sb = new StringBuilder(); int from = Math.max(0, l.size() - (KEEP - 1));
        for (int i = from; i < l.size(); i++) { Entry e = l.get(i); sb.append(e.ms).append(',').append(e.day).append(',').append(e.mood).append(',').append(e.energy).append('\n'); }
        sb.append(ms).append(',').append(day).append(',').append(mood).append(',').append(energy).append('\n'); return sb.toString();
    }

    /** Night hours by the local epoch day the night ENDED on (the morning of that day). */
    static Map<Long, Double> hoursByWakeDay(List<SleepRegularity.Night> nights, long baseEpochDay) {
        Map<Long, Double> m = new HashMap<>(); if (nights == null) return m;
        for (SleepRegularity.Night n : nights) { long d = baseEpochDay + n.day + (n.wakeMin >= 1440 ? 1 : 0); Double old = m.get(d); m.put(d, (old == null ? 0 : old) + n.asleepMin / 60.0); }
        return m;
    }

    static final class Pattern {
        int entries, days, paired, nShort, nLong; boolean enough;
        double moodShort = Double.NaN, moodLong = Double.NaN, enShort = Double.NaN, enLong = Double.NaN, rho = Double.NaN;
        String head = "", body = "";
    }

    /** Day means of mood and energy paired with the hours slept in the night before; the groups are under / at least SLEEP_CUT_H hours. */
    static Pattern pattern(List<Entry> es, Map<Long, Double> hours) {
        Pattern P = new Pattern(); P.entries = es.size();
        TreeMap<Long, double[]> byDay = new TreeMap<>(); for (Entry e : es) { double[] a = byDay.get(e.day); if (a == null) { a = new double[3]; byDay.put(e.day, a); } a[0] += e.mood; a[1] += e.energy; a[2] += 1; }
        P.days = byDay.size(); List<double[]> rows = new ArrayList<>();      // hours, mood, energy
        for (Map.Entry<Long, double[]> x : byDay.entrySet()) { Double h = hours.get(x.getKey()); if (h == null || h < 2.0) continue; double[] a = x.getValue(); rows.add(new double[] { h, a[0] / a[2], a[1] / a[2] }); }
        P.paired = rows.size(); double sm = 0, se = 0, lm = 0, le = 0;
        for (double[] r : rows) { if (r[0] < SLEEP_CUT_H) { P.nShort++; sm += r[1]; se += r[2]; } else { P.nLong++; lm += r[1]; le += r[2]; } }
        if (P.nShort > 0) { P.moodShort = sm / P.nShort; P.enShort = se / P.nShort; } if (P.nLong > 0) { P.moodLong = lm / P.nLong; P.enLong = le / P.nLong; }
        P.enough = P.entries >= MIN_ENTRIES && P.days >= MIN_DAYS && P.nShort >= MIN_GROUP && P.nLong >= MIN_GROUP;
        if (rows.size() >= 8) { double[] h = new double[rows.size()], m = new double[rows.size()]; for (int i = 0; i < h.length; i++) { h[i] = rows.get(i)[0]; m[i] = rows.get(i)[1]; } P.rho = spearman(h, m); }
        if (!P.enough) {
            P.head = "Still gathering"; P.body = String.format(Locale.UK, "%d of %d entries over %d of %d days so far, and at least %d days after a short night and %d after a longer one (now %d and %d). Nothing is shown until then, so that chance does not read as a pattern.", P.entries, MIN_ENTRIES, P.days, MIN_DAYS, MIN_GROUP, MIN_GROUP, P.nShort, P.nLong);
            return P;
        }
        double dm = P.moodLong - P.moodShort, de = P.enLong - P.enShort;
        P.head = Math.abs(dm) < MIN_GAP && Math.abs(de) < MIN_GAP ? "No clear gap in your own days" : "Your days after short and longer nights differ";
        P.body = String.format(Locale.UK, "After nights under %.0f h (%d days): mood %.1f, energy %.1f out of 5. After %.0f h or more (%d days): mood %.1f, energy %.1f. This describes your own days; it does not say that sleep caused the difference, and %d days can differ by chance.",
            SLEEP_CUT_H, P.nShort, P.moodShort, P.enShort, SLEEP_CUT_H, P.nLong, P.moodLong, P.enLong, P.paired);
        return P;
    }

    static double spearman(double[] x, double[] y) {
        int n = x.length; if (n < 3) return Double.NaN; double[] a = rank(x), b = rank(y); double ma = 0, mb = 0; for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; } ma /= n; mb /= n; double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb); } return saa <= 0 || sbb <= 0 ? Double.NaN : sab / Math.sqrt(saa * sbb);
    }
    private static double[] rank(double[] v) {
        int n = v.length; Integer[] ix = new Integer[n]; for (int i = 0; i < n; i++) ix[i] = i; Arrays.sort(ix, (p, q) -> Double.compare(v[p], v[q])); double[] r = new double[n];
        for (int i = 0; i < n; ) { int j = i; while (j + 1 < n && v[ix[j + 1]] == v[ix[i]]) j++; double rk = 0.5 * (i + j) + 1; for (int k = i; k <= j; k++) r[ix[k]] = rk; i = j + 1; } return r;
    }

    // ------------------------------------------------------------------ calm minute: RMSSD of the beat intervals in the 60 s before and the 60 s after
    static final class Row { long ms; int hr; int[] rr = new int[0]; }
    static final class Win { int beats, diffs; double rmssd = Double.NaN, hr = Double.NaN; boolean ok; String why = ""; }
    static final int MIN_BEATS = 35, MIN_DIFFS = 25;

    /** Beat intervals (ms) of the rows in [fromMs, toMs): an interval list that repeats the tail of the previous row's list is counted once. Intervals outside 300-2000 ms are dropped. */
    static Win window(List<Row> rows, long fromMs, long toMs) {
        Win W = new Win(); List<double[]> rr = new ArrayList<>(); int[] prev = new int[0]; double t = 0; List<Integer> hrs = new ArrayList<>();
        for (Row r : rows) {
            if (r.ms < fromMs || r.ms >= toMs) continue; if (r.hr > 0) hrs.add(r.hr);
            int skip = 0; for (int k = Math.min(prev.length, r.rr.length); k >= 1; k--) { boolean eq = true; for (int i = 0; i < k; i++) if (prev[prev.length - k + i] != r.rr[i]) { eq = false; break; } if (eq) { skip = k; break; } }
            for (int i = skip; i < r.rr.length; i++) { int v = r.rr[i]; if (v < 300 || v > 2000) continue; t += v / 1000.0; rr.add(new double[] { t, v }); }
            if (r.rr.length > 0) prev = r.rr;
        }
        W.beats = rr.size(); if (W.beats < MIN_BEATS) { W.why = "only " + W.beats + " beats were recorded (need " + MIN_BEATS + ")"; return W; }
        double mean = 0; for (double[] b : rr) mean += b[1]; mean /= rr.size(); W.hr = 60000.0 / mean;
        if (!hrs.isEmpty()) { java.util.Collections.sort(hrs); double mh = hrs.get(hrs.size() / 2); if (Math.abs(mh - W.hr) > 0.15 * mh) { W.why = "the beat intervals and the heart rate disagree (" + String.format(Locale.UK, "%.0f", W.hr) + " against " + String.format(Locale.UK, "%.0f", mh) + " a minute)"; return W; } }
        double s = 0; int c = 0; for (int q = 1; q < rr.size(); q++) { double d = rr.get(q)[1] - rr.get(q - 1)[1]; if (Math.abs(d) < 0.05 * rr.get(q)[1] && rr.get(q)[0] - rr.get(q - 1)[0] < 2.5) { s += d * d; c++; } }
        W.diffs = c; if (c < MIN_DIFFS) { W.why = "only " + c + " clean steps between beats (need " + MIN_DIFFS + ")"; return W; }
        W.rmssd = Math.sqrt(s / c); W.ok = true; return W;
    }

    static String calmLine(double pre, double post, int nPre, int nPost) {
        if (Double.isNaN(pre) || Double.isNaN(post)) return "not enough clean beats on both sides to compare";
        return String.format(Locale.UK, "%.0f ms before, %.0f ms after (%d and %d beats)", pre, post, nPre, nPost);
    }

    // ------------------------------------------------------------------ if-then plans
    static final String[] CUES = { "I finish work", "I sit down in the evening", "I wake in the night", "I feel wound up" };
    static final String[] ACTS = { "do one calm minute", "write one good thing", "put the phone away for ten minutes", "step outside for five minutes" };
    static String plan(int cue, int act) { return "If " + CUES[cue] + ", then I " + ACTS[act] + "."; }

    // ------------------------------------------------------------------ week page
    static final class Week { int days, nMood; double mood = Double.NaN, energy = Double.NaN; int calm, good; double avgH = Double.NaN; int ge7, nights; }
    static Week week(List<Entry> es, long today, double[] nightHours, int calmCount, int goodDays) {
        Week w = new Week(); double sm = 0, se = 0; java.util.HashSet<Long> d = new java.util.HashSet<>();
        for (Entry e : es) if (e.day > today - 7 && e.day <= today) { sm += e.mood; se += e.energy; w.nMood++; d.add(e.day); }
        w.days = d.size(); if (w.nMood > 0) { w.mood = sm / w.nMood; w.energy = se / w.nMood; }
        w.calm = calmCount; w.good = goodDays; double s = 0; if (nightHours != null) for (double h : nightHours) { s += h; w.nights++; if (h >= 7.0) w.ge7++; } if (w.nights > 0) w.avgH = s / w.nights; return w;
    }
    static String weekText(Week w) {
        StringBuilder b = new StringBuilder("Your week, from your own records\n");
        b.append(w.nights == 0 ? "Sleep: no nights recorded.\n" : String.format(Locale.UK, "Sleep: %d nights, average %.1f h, %d of them 7 h or more.\n", w.nights, w.avgH, w.ge7));
        b.append(w.nMood == 0 ? "Mood and energy: nothing logged.\n" : String.format(Locale.UK, "Mood and energy: %d entries on %d days, mood %.1f and energy %.1f out of 5 on average.\n", w.nMood, w.days, w.mood, w.energy));
        b.append(String.format(Locale.UK, "Calm minutes: %d.  Days with a good thing written: %d of 7.\n", w.calm, w.good));
        b.append("Counts and averages of what you entered and what the strap recorded; they are not a verdict.");
        return b.toString();
    }
}
