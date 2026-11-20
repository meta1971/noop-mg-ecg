package com.noop.mgecg;

// FILE VERSION 0.71.3 (20 Nov 2026): beat-shape gate in front of the AF screen, the rhythm word and the live breathing estimate (addendum 327.11, 331).
// Rule (thresholds from addendum 327.11, set after seeing four windows, NOT tuned further): over the last 129 evaluated beats, a beat is ODD when its correlation with the
// per-sample median beat of those beats is below 0.85; the gate BLOCKS when 3 or more odd beats follow each other, or when 5 % or more of the beats are odd.
// Blocked means "no rhythm word": nothing is repaired or dropped (327.6: repairing made the screen worse than blocking it).
// An evaluated beat is one with a full window around it; fewer than MIN_EVAL evaluated beats = the gate cannot judge and does not block (the screen has its own minimum).
// A beat that could not be evaluated (NaN) ends a run. MIN_EVAL = 20 and the use of the beats that feed the estimate for the live breathing guard are GUESSES, not measured.
// NOT measured on a real recording with this code: the 327 numbers came from the H10 finder (ecg46), which disagrees with the finder used here (330.4, Spearman 0.393).
// Pure Java (no Android), tested off the phone.

import java.util.List;

final class ShapeGate {
    private ShapeGate() {}

    static final int WINDOW = 129, RUN = 3, MIN_EVAL = 20;
    static final double ODD_CORR = 0.85;

    static final class Result {
        int evaluated, odd, longestRun;
        boolean evaluable, blocked;
        String why = "";
    }

    /** corr[0..n): correlation of each beat with the median beat, NaN = not evaluated; only the last WINDOW entries count. */
    static Result checkCorr(double[] corr, int n) {
        Result r = new Result();
        int from = Math.max(0, n - WINDOW), run = 0;
        for (int i = from; i < n; i++) {
            double c = corr[i];
            if (Double.isNaN(c)) { run = 0; continue; }
            r.evaluated++;
            if (c < ODD_CORR) { r.odd++; run++; if (run > r.longestRun) r.longestRun = run; } else run = 0;
        }
        r.evaluable = r.evaluated >= MIN_EVAL;
        if (!r.evaluable) return r;
        boolean byRun = r.longestRun >= RUN, byShare = r.odd * 20 >= r.evaluated;       // 5 % or more, in integers
        r.blocked = byRun || byShare;
        if (r.blocked) r.why = r.odd + " of the last " + r.evaluated + " beats had an odd shape"
                + (r.longestRun >= 2 ? " (longest run " + r.longestRun + ")" : "");
        return r;
    }

    /** Live use: beatSec[0..n) ascending accepted-beat times, corr[0..n) their correlations (NaN = not judged yet); only the beats of the last winS seconds count (the beats that feed the breathing estimate). */
    static Result checkLast(double[] beatSec, double[] corr, int n, double winS) {
        if (n <= 0) return new Result();
        int i0 = n;
        while (i0 > 0 && beatSec[i0 - 1] >= beatSec[n - 1] - winS) i0--;
        return checkCorr(java.util.Arrays.copyOfRange(corr, i0, n), n - i0);
    }

    /** beats: equal-length windows around each beat (any order = time order), polarity already corrected. Median beat is taken over the last WINDOW of them. */
    static Result checkBeats(List<double[]> beats) {
        int total = beats == null ? 0 : beats.size();
        int from = Math.max(0, total - WINDOW), m = total - from;
        if (m < MIN_EVAL) { Result r = new Result(); r.evaluated = m; return r; }
        int len = beats.get(from).length;
        double[] tpl = new double[len], col = new double[m];
        for (int j = 0; j < len; j++) {
            for (int g = 0; g < m; g++) col[g] = beats.get(from + g)[j];
            java.util.Arrays.sort(col);
            tpl[j] = m % 2 == 1 ? col[m / 2] : (col[m / 2 - 1] + col[m / 2]) / 2.0;
        }
        double[] c = new double[m];
        for (int g = 0; g < m; g++) {
            double[] w = beats.get(from + g);
            c[g] = w.length == len ? EcgR16Analyzer.pearson(w, 0, tpl) : Double.NaN;
        }
        return checkCorr(c, m);
    }
}
