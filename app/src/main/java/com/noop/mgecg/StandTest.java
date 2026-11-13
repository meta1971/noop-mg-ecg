package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. The morning lying-to-standing test (3 minutes: lie still 90 s, stand up at the cue, stand still 90 s), worked out from the R-R series of the ECG reading.
// Numbers: heart rate lying; heart rate at 15 s after standing (median of the beats 12-18 s) and the rise; the fastest 3-beat rate in the first 30 s; the 30:15 ratio (the longest interval around beat 30 divided by the
// shortest around beat 15 after standing); heart rate 60-90 s after standing; RMSSD lying (the last 60 s before the cue) and standing (60-150 s after). Standing is taken to start CUE_LAG_S after the cue.
// It is a trend for the same person at the same hour, not a diagnosis, and no limits are quoted. Pure Java.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class StandTest {
    private StandTest() {}

    static final double CUE_LAG_S = 2.0;

    static final class Result {
        boolean ok; String why = "";
        double hrLie = Double.NaN, hr15 = Double.NaN, rise15 = Double.NaN, hrPeak = Double.NaN, peakAtS = Double.NaN, ratio3015 = Double.NaN, hrLate = Double.NaN;
        double rmssdLie = Double.NaN, rmssdStand = Double.NaN;
        int beatsLie, beatsStand;
        String text() {
            if (!ok) return "Stand test not scored: " + why;
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.UK, "Lying %.0f bpm, at 15 s standing %.0f (%+.0f), fastest %.0f at %.0f s", hrLie, hr15, rise15, hrPeak, peakAtS));
            if (!Double.isNaN(ratio3015)) b.append(String.format(Locale.UK, "; 30:15 ratio %.2f", ratio3015));
            if (!Double.isNaN(hrLate)) b.append(String.format(Locale.UK, "; 60-90 s %.0f bpm", hrLate));
            if (!Double.isNaN(rmssdLie) && !Double.isNaN(rmssdStand)) b.append(String.format(Locale.UK, "; RMSSD %.0f lying, %.0f standing", rmssdLie, rmssdStand));
            return b.toString();
        }
    }

    /** beatS: times of accepted R beats in seconds from the reading start (ascending); standS: the cue time in seconds from the same start. */
    static Result analyse(double[] beatS, int nBeats, double standS) {
        Result res = new Result();
        double[] bt = Arrays.copyOf(beatS, nBeats);
        // intervals with the time of their second beat; missed beats (long) and doubled beats (short) are left out
        List<double[]> rr = new ArrayList<double[]>();
        for (int i = 1; i < bt.length; i++) { double d = bt[i] - bt[i - 1]; if (d >= 0.33 && d <= 2.0) rr.add(new double[] { bt[i], d }); }
        List<double[]> good = new ArrayList<double[]>();
        for (int i = 0; i < rr.size(); i++) {
            double[] w = new double[5]; int k = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(rr.size() - 1, i + 2); j++) w[k++] = rr.get(j)[1];
            double[] ws = Arrays.copyOf(w, k); Arrays.sort(ws);
            double med = (k % 2 == 1) ? ws[k / 2] : 0.5 * (ws[k / 2 - 1] + ws[k / 2]);
            if (k >= 3 && (rr.get(i)[1] > 1.35 * med || rr.get(i)[1] < 0.70 * med)) {
                // a real standing response changes the interval fast; keep it when its neighbours on one side agree with it
                continue;
            }
            good.add(rr.get(i));
        }
        double t0 = standS + CUE_LAG_S;
        List<Double> lie = new ArrayList<Double>(), lieT = new ArrayList<Double>();
        for (double[] x : good) if (x[0] >= standS - 60.0 && x[0] <= standS - 3.0) { lie.add(x[1]); lieT.add(x[0]); }
        res.beatsLie = lie.size();
        if (lie.size() < 30) { res.why = "fewer than 30 clean lying beats in the last minute before the cue (" + lie.size() + ")."; return res; }
        res.hrLie = 60.0 / median(lie);
        res.rmssdLie = rmssd(good, standS - 60.0, standS - 3.0);
        // beats after standing, counted from the first beat after t0
        List<double[]> after = new ArrayList<double[]>();
        for (double[] x : good) if (x[0] > t0 && x[0] <= t0 + 150.0) after.add(x);
        res.beatsStand = after.size();
        if (after.size() < 40) { res.why = "fewer than 40 clean beats in the 150 s after standing (" + after.size() + ")."; return res; }
        // heart rate at 15 s: beats whose time lies 12-18 s after the start of standing
        List<Double> h15 = new ArrayList<Double>(); for (double[] x : after) if (x[0] - t0 >= 12.0 && x[0] - t0 <= 18.0) h15.add(x[1]);
        if (h15.size() < 3) { res.why = "no beats between 12 and 18 s after standing."; return res; }
        res.hr15 = 60.0 / median(h15); res.rise15 = res.hr15 - res.hrLie;
        // fastest 3-beat rate in the first 30 s
        double bestHr = 0, bestAt = Double.NaN;
        for (int i = 0; i + 2 < after.size(); i++) {
            if (after.get(i + 2)[0] - t0 > 30.0) break;
            double m = (after.get(i)[1] + after.get(i + 1)[1] + after.get(i + 2)[1]) / 3.0; double hr = 60.0 / m;
            if (hr > bestHr) { bestHr = hr; bestAt = after.get(i + 1)[0] - t0; }
        }
        res.hrPeak = bestHr; res.peakAtS = bestAt;
        // 30:15 ratio by beat number after standing (beat 1 = first beat after t0): the shortest interval among beats 5-25 and the longest among beats 20-40
        if (after.size() >= 41) {
            double shortest = 1e9, longest = 0;
            for (int i = 4; i < 25 && i < after.size(); i++) shortest = Math.min(shortest, after.get(i)[1]);
            for (int i = 19; i < 40 && i < after.size(); i++) longest = Math.max(longest, after.get(i)[1]);
            if (shortest < 1e8 && longest > 0) res.ratio3015 = longest / shortest;
        }
        List<Double> late = new ArrayList<Double>(); for (double[] x : after) if (x[0] - t0 >= 60.0 && x[0] - t0 <= 90.0) late.add(x[1]);
        if (late.size() >= 10) res.hrLate = 60.0 / median(late);
        res.rmssdStand = rmssd(good, t0 + 60.0, t0 + 150.0);
        res.ok = true;
        return res;
    }

    private static double median(List<Double> v) {
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a); int k = a.length; return k % 2 == 1 ? a[k / 2] : 0.5 * (a[k / 2 - 1] + a[k / 2]);
    }

    /** RMSSD in ms of successive intervals whose second beats lie in [from, to] (each pair must be adjacent beats: 1.6 s apart at most). */
    private static double rmssd(List<double[]> rr, double from, double to) {
        double ss = 0; int n = 0; double[] prev = null;
        for (double[] x : rr) {
            if (x[0] < from || x[0] > to) { prev = null; continue; }
            if (prev != null && x[0] - prev[0] < 2.0) { double d = (x[1] - prev[1]) * 1000.0; ss += d * d; n++; }
            prev = x;
        }
        return n >= 20 ? Math.sqrt(ss / n) : Double.NaN;
    }
}
