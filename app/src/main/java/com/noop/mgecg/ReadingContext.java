package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. The context every ECG reading carries without a tap, and the band of your own earlier readings taken in a similar context.
// Context: hours awake, last night's sleep, steps in the 30 minutes before, minutes since the last carb-meal marker (up to 12 h), and the resting-heart-rate deviation (this reading's HR minus your lowest-five-minute
// resting HR of the last 7 nights). A reading is "similar" when its hours awake are within 2.5 h and the carb state (within 3 h of a carb meal or not) is the same; the band is the middle half (quartiles) of those
// readings' values. With fewer than 5 similar readings the band widens to hours awake only; fewer than 5 then: no band. Pure Java.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ReadingContext {
    private ReadingContext() {}

    static final double CARB_WINDOW_MIN = 180.0, AWAKE_TOL_H = 2.5;
    static final int MIN_PEERS = 5;

    static final class Ctx {
        double hoursAwake = Double.NaN, sleepPrevH = Double.NaN, steps30 = Double.NaN, carbMin = Double.NaN, rhrDev = Double.NaN;
        boolean carbRecent() { return !Double.isNaN(carbMin) && carbMin <= CARB_WINDOW_MIN; }
        String text() {
            return "awake " + f(hoursAwake, "%.1f h") + "  ·  slept " + f(sleepPrevH, "%.1f h") + "  ·  steps 30 min " + f(steps30, "%.0f")
                    + "  ·  carb " + (Double.isNaN(carbMin) ? "none in 12 h" : String.format(Locale.UK, "%.0f min ago", carbMin)) + "  ·  HR vs rest " + f(rhrDev, "%+.0f bpm");
        }
    }

    static final class Band {
        double lo = Double.NaN, med = Double.NaN, hi = Double.NaN; int n; boolean wide;      // wide = carb state not matched
        boolean ok() { return n >= MIN_PEERS; }
    }

    private static String f(double v, String fmt) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, fmt, v); }

    /** values[i] and ctx[i] for earlier readings (the reading itself excluded by the caller); target is the context of the reading to place. */
    static Band band(double[] values, Ctx[] ctx, int count, Ctx target) {
        Band b = new Band();
        if (target == null || Double.isNaN(target.hoursAwake)) return b;
        List<Double> strict = new ArrayList<Double>(), loose = new ArrayList<Double>();
        for (int i = 0; i < count; i++) {
            if (Double.isNaN(values[i]) || ctx[i] == null || Double.isNaN(ctx[i].hoursAwake)) continue;
            if (Math.abs(ctx[i].hoursAwake - target.hoursAwake) > AWAKE_TOL_H) continue;
            loose.add(values[i]);
            if (ctx[i].carbRecent() == target.carbRecent()) strict.add(values[i]);
        }
        List<Double> use = strict.size() >= MIN_PEERS ? strict : loose;
        b.wide = use != strict;
        b.n = use.size();
        if (b.n < MIN_PEERS) return b;
        Collections.sort(use);
        b.lo = use.get((int) Math.floor(0.25 * (b.n - 1))); b.hi = use.get((int) Math.ceil(0.75 * (b.n - 1)));
        b.med = b.n % 2 == 1 ? use.get(b.n / 2) : 0.5 * (use.get(b.n / 2 - 1) + use.get(b.n / 2));
        return b;
    }

    static String place(String name, double v, Band b, String fmt) {
        if (Double.isNaN(v)) return name + ": no value.";
        if (b == null || !b.ok()) return name + " " + String.format(Locale.UK, fmt, v) + ": too few similar readings yet (" + (b == null ? 0 : b.n) + " of " + MIN_PEERS + ").";
        String where = v > b.hi ? "above" : v < b.lo ? "below" : "within";
        return name + " " + String.format(Locale.UK, fmt, v) + ": " + where + " your band " + String.format(Locale.UK, fmt, b.lo) + " to " + String.format(Locale.UK, fmt, b.hi)
                + " (" + b.n + " similar readings" + (b.wide ? ", carb state not matched" : "") + ").";
    }
}
