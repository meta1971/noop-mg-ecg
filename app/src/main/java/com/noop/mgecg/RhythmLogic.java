package com.noop.mgecg;

// FILE VERSION 0.94.0 (26 Nov 2026): NEW, pure Java. Beat timing for the Rhythm screen, from the beats marked on the strip (EcgR16Analyzer.Strip.beats): the gaps between neighbouring beats (300 to 2000 ms, as StripViewport),
// their median and middle 80 %, the longest run of beats whose gaps are within 20 % of the median, and the early beats by the SAME rule the report uses (AfScreen.earlyBeats: a gap under 80 % of the median of the up to four before it,
// followed by a gap longer than that median) but with the positions kept so the plot can mark them. Timing only: no rhythm verdict is made here, the report's own word is shown next to these numbers.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RhythmLogic {
    private RhythmLogic() { }

    static final double MIN_GAP_MS = 300, MAX_GAP_MS = 2000, EVEN_FRAC = 0.20;

    static final class Gaps {
        double[] t = new double[0];          // seconds of the beat that ends each gap
        double[] ms = new double[0];         // the gap, ms
        boolean[] adj = new boolean[0];      // adj[k]: gap k starts at the beat that ended gap k-1 (no break between them)
        boolean[] early = new boolean[0];    // gap k is an early beat's gap (report rule)
        int beats;                           // beats given
        int n;                               // gaps kept
        int earlyCount;
        int evenBeats;                       // longest unbroken run of beats whose gaps are all within EVEN_FRAC of the median (0 with fewer than 2 gaps)
        double med = Double.NaN, p10 = Double.NaN, p90 = Double.NaN, min = Double.NaN, max = Double.NaN;
        double totalS;                       // seconds covered, first beat to last
    }

    static Gaps of(int[] beats, double fs) {
        Gaps g = new Gaps();
        if (beats == null || fs <= 0) return g;
        g.beats = beats.length;
        List<Double> t = new ArrayList<>(), ms = new ArrayList<>();
        List<Boolean> adj = new ArrayList<>();
        int lastEnd = -2;                                                    // beat index that ended the previous kept gap
        for (int i = 1; i < beats.length; i++) {
            double gap = (beats[i] - beats[i - 1]) * 1000.0 / fs;
            if (!(gap >= MIN_GAP_MS && gap <= MAX_GAP_MS)) continue;
            t.add(beats[i] / fs); ms.add(gap); adj.add(lastEnd == i - 1);
            lastEnd = i;
        }
        int n = ms.size();
        g.n = n;
        g.t = new double[n]; g.ms = new double[n]; g.adj = new boolean[n]; g.early = new boolean[n];
        for (int k = 0; k < n; k++) { g.t[k] = t.get(k); g.ms[k] = ms.get(k); g.adj[k] = adj.get(k); }
        if (beats.length >= 2) g.totalS = (beats[beats.length - 1] - beats[0]) / fs;
        if (n == 0) return g;
        double[] s = g.ms.clone(); Arrays.sort(s);
        g.med = pct(s, 0.5); g.p10 = pct(s, 0.10); g.p90 = pct(s, 0.90); g.min = s[0]; g.max = s[n - 1];
        // early beats: the report's rule on the series of whole-millisecond gaps
        int[] rr = new int[n]; for (int k = 0; k < n; k++) rr[k] = (int) Math.round(g.ms[k]);
        for (int i = 2; i < n - 1; i++) {
            int from = Math.max(0, i - 4);
            double[] prev = new double[i - from];
            for (int k = from; k < i; k++) prev[k - from] = rr[k];
            Arrays.sort(prev);
            double m = prev.length % 2 == 1 ? prev[prev.length / 2] : (prev[prev.length / 2 - 1] + prev[prev.length / 2]) / 2.0;
            if (rr[i] < 0.8 * m && rr[i + 1] > m) { g.early[i] = true; g.earlyCount++; }
        }
        // longest unbroken run of even gaps
        int run = 0, best = 0;
        for (int k = 0; k < n; k++) {
            boolean even = Math.abs(g.ms[k] - g.med) <= EVEN_FRAC * g.med;
            if (even) run = (g.adj[k] && run > 0) ? run + 1 : 1; else run = 0;
            if (run > best) best = run;
        }
        g.evenBeats = best == 0 ? 0 : best + 1;                               // n gaps in a row = n + 1 beats
        return g;
    }

    /** Linear-interpolated percentile of a sorted array. */
    static double pct(double[] sorted, double q) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        double x = q * (n - 1); int a = (int) Math.floor(x), b = Math.min(n - 1, a + 1);
        return sorted[a] + (sorted[b] - sorted[a]) * (x - a);
    }

    /** {lo, hi} for a plot of the gaps: min to max padded by 8 %, never narrower than 100 ms. */
    static double[] axis(Gaps g) {
        if (g == null || g.n == 0) return new double[] { 600, 1000 };
        double lo = g.min, hi = g.max, pad = Math.max((hi - lo) * 0.08, 5.0);
        lo -= pad; hi += pad;
        if (hi - lo < 100) { double mid = (hi + lo) / 2; lo = mid - 50; hi = mid + 50; }
        return new double[] { lo, hi };
    }

    /** Sentence under the word: "118 beats in a row within 20 % of the median gap  ·  0 early beats". */
    static String line(Gaps g) {
        if (g == null || g.n < 3) return "Too few beats are marked on the strip to read the timing.";
        return g.evenBeats + " beats in a row within 20 % of the median gap  ·  " + g.earlyCount + (g.earlyCount == 1 ? " early beat" : " early beats");
    }

    /** "Range here: 898 to 954 ms." (the middle 80 % of the gaps). */
    static String rangeLine(Gaps g) {
        if (g == null || g.n < 3) return "";
        return "Middle 80 % of the gaps: " + Math.round(g.p10) + " to " + Math.round(g.p90) + " ms.";
    }
}
