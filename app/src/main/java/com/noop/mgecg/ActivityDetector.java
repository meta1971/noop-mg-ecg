package com.noop.mgecg;

// FILE VERSION 0.1.0 (23 Oct 2026): first version. Pure Java, no Android, so it can be tested off the phone.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finds sustained physical activity (including all-day manual work) in the strap's 1 Hz R18 stream.
 * RESEARCH ONLY. Not a medical device, not a calorie counter.
 *
 * Why it does not use the strap's own activity class (R18 byte 63): on the 23 Oct run a jog registered as
 * "walk" in 62% of seconds and never as "run", and the step counter saw about 63 steps a minute during it.
 * Manual work (arms and hands moving, few steps) would be missed even more. So this uses two things that did
 * separate rest from effort on that run:
 *
 *   heart rate as a share of heart-rate reserve ("pct"):  (HR - resting HR) / (HRmax - resting HR)
 *   wrist movement ("motion"): mean dynamic acceleration in g (R18 float32 at byte 41); about 0.01 seated,
 *       about 0.5 while jogging, 0.03-0.28 per minute walking around indoors
 *
 * Pipeline
 *   1. seconds -> 60 s epochs (needs >= 40 valid seconds): median HR, 90th percentile HR, mean motion, steps
 *   2. resting HR = 10th percentile of the median HR of "still" epochs, unless supplied (a multi-day or
 *      night-time figure is better than one afternoon; pass it in Params.restHr)
 *   3. each epoch gets a kind:
 *        ACTIVE            pct >= 0.10 with some movement, or clear movement (>= 0.12 g) with pct >= 0.05
 *        ELEVATED_STILL    pct >= 0.15 and almost no movement: stress, caffeine, heat, illness, posture.
 *                          Logged separately, never counted as activity.
 *        REST, NO_DATA
 *   4. bouts = runs of ACTIVE epochs, bridging pauses and short data gaps of up to mergeGapMin minutes (breaks on a job), kept only
 *      when they hold at least minBoutMin ACTIVE minutes. Anything shorter that still reached 30% of reserve or
 *      hard movement is listed as a short effort.
 *   5. every bout gets duration, mean and peak HR, mean %reserve, minutes per intensity level, steps,
 *      a plain label, and a training-load number (Banister TRIMP, men's weighting).
 *
 * The thresholds are defaults chosen from ONE evening and a 90 s jog. They are constants in Params so they
 * can be tuned on real work days; toTuningText() prints the distributions needed to do that.
 */
public final class ActivityDetector {

    private ActivityDetector() {}

    public static final int NO_DATA = 0, REST = 1, ACTIVE = 2, ELEVATED_STILL = 3;
    public static final int L_REST = 0, L_LIGHT = 1, L_MODERATE = 2, L_HARD = 3;

    public static final class Params {
        /** Used for HRmax = 208 - 0.7 * age (Tanaka 2001) when hrMax is NaN. */
        public int age = 53;
        public double restHr = Double.NaN;      // NaN = estimate from still epochs
        public double hrMax = Double.NaN;       // NaN = from age
        public double fallbackRestHr = 65;      // when there are too few still epochs to estimate
        public double stillMotion = 0.03;       // mean dynamic g below this = still
        public double clearMotion = 0.12;       // clearly moving
        public double hardMotion = 0.30;        // jogging or heavy swinging work (23 Oct: jog minutes 0.60 and 0.34, easy walking minutes 0.03-0.28)
        public double activePct = 0.10;
        public double elevatedStillPct = 0.15;
        public double levelLight = 0.10, levelModerate = 0.25, levelHard = 0.45;
        public int minEpochSeconds = 40;
        public int minBoutMin = 5;              // ACTIVE minutes a bout must hold
        public int mergeGapMin = 3;             // pauses up to this long stay inside a bout
        public int sustainedMin = 20;           // a bout this long is called sustained
        public double shortEffortPct = 0.30;
    }

    public static final class Epoch {
        public long start;                      // unix seconds, minute aligned
        public int kind = NO_DATA, level = L_REST, validSeconds, steps;
        public double hr = Double.NaN, hrP90 = Double.NaN, motion = Double.NaN, pct = Double.NaN;
    }

    public static final class Bout {
        public long start, end;                 // unix seconds, end exclusive
        public int durationMin, activeMin;
        public double avgHr, peakHr, avgPct, motion, stepsPerMin, load;
        public int[] levelMin = new int[4];     // minutes of ACTIVE epochs per level
        public String label = "";
        public boolean sustained;
        @Override public String toString() {
            return String.format(Locale.US, "%s-%s %3d min (active %3d)  HR avg %.0f peak %.0f  %%res %.0f  motion %.2f g  steps/min %.0f  load %.0f  [L%d M%d H%d]  %s%s",
                    hhmm(start), hhmm(end), durationMin, activeMin, avgHr, peakHr, avgPct * 100, motion, stepsPerMin, load,
                    levelMin[1], levelMin[2], levelMin[3], label, sustained ? "  (sustained)" : "");
        }
    }

    public static final class Result {
        public double restHr, hrMax;
        public boolean restEstimated;
        public List<Epoch> epochs = new ArrayList<Epoch>();
        public List<Bout> bouts = new ArrayList<Bout>();
        public List<Bout> shortEfforts = new ArrayList<Bout>();
        public int validMin, activeMin, elevatedStillMin, noDataMin, boutMin, longestBoutMin;
        public int[] levelMin = new int[4];
        public double load;

        public String toText() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "resting HR %.0f%s, HRmax %.0f, reserve %.0f\n", restHr, restEstimated ? " (estimated from still minutes)" : "", hrMax, hrMax - restHr));
            b.append(String.format(Locale.US, "minutes: valid %d, active %d (light %d, moderate %d, hard %d), elevated-but-still %d, no data %d\n",
                    validMin, activeMin, levelMin[1], levelMin[2], levelMin[3], elevatedStillMin, noDataMin));
            b.append(String.format(Locale.US, "bouts: %d totalling %d min, longest %d min, load %.0f\n", bouts.size(), boutMin, longestBoutMin, load));
            for (Bout x : bouts) b.append("  ").append(x).append('\n');
            for (Bout x : shortEfforts) b.append("  short effort: ").append(x).append('\n');
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- main entry

    /**
     * ts: unix seconds ascending (gaps allowed), hr: bpm (0 = none), dynG: R18 float at byte 41 (NaN = none),
     * steps: R18 u16 at byte 57 (the counter wraps at 65536; may be null).
     */
    public static Result analyse(long[] ts, int[] hr, double[] dynG, int[] steps, Params p) {
        if (p == null) p = new Params();
        Result r = new Result();
        r.hrMax = Double.isNaN(p.hrMax) ? 208.0 - 0.7 * p.age : p.hrMax;
        int n = ts.length;
        if (n == 0) { r.restHr = Double.isNaN(p.restHr) ? p.fallbackRestHr : p.restHr; return r; }

        long first = ts[0] - ts[0] % 60, last = ts[n - 1] - ts[n - 1] % 60;
        int ne = (int) ((last - first) / 60) + 1;
        Epoch[] ep = new Epoch[ne];
        for (int e = 0; e < ne; e++) { ep[e] = new Epoch(); ep[e].start = first + 60L * e; }

        // ---- 1. epochs
        int i = 0;
        double[] hbuf = new double[60];
        while (i < n) {
            int e = (int) ((ts[i] - first) / 60);
            int j = i, c = 0;
            double msum = 0; int mc = 0, stp = 0, prevSteps = -1;
            while (j < n && (int) ((ts[j] - first) / 60) == e) {
                if (hr[j] >= 35 && hr[j] <= 220 && c < 60) {
                    hbuf[c++] = hr[j];
                    double m = Double.isNaN(dynG[j]) ? Double.NaN : Math.min(2.0, Math.max(0.0, dynG[j]));
                    if (!Double.isNaN(m)) { msum += m; mc++; }
                }
                if (steps != null) {
                    if (prevSteps >= 0) { int d = steps[j] - prevSteps; if (d < 0) d += 65536; if (d >= 0 && d <= 30) stp += d; }
                    prevSteps = steps[j];
                }
                j++;
            }
            Epoch x = ep[e];
            x.validSeconds = c;
            if (c >= p.minEpochSeconds && mc >= p.minEpochSeconds) {
                double[] s = Arrays.copyOf(hbuf, c);
                Arrays.sort(s);
                x.hr = (c % 2 == 1) ? s[c / 2] : 0.5 * (s[c / 2 - 1] + s[c / 2]);
                x.hrP90 = s[(int) Math.min(c - 1, Math.round(0.9 * (c - 1)))];
                x.motion = msum / mc;
                x.steps = stp;
            }
            i = j;
        }

        // ---- 2. resting HR
        if (!Double.isNaN(p.restHr)) { r.restHr = p.restHr; r.restEstimated = false; }
        else {
            List<Double> still = new ArrayList<Double>();
            for (Epoch x : ep) if (!Double.isNaN(x.hr) && x.motion < p.stillMotion) still.add(x.hr);
            if (still.size() >= 20) {
                double[] a = new double[still.size()];
                for (int k = 0; k < a.length; k++) a[k] = still.get(k);
                Arrays.sort(a);
                r.restHr = a[(int) Math.round(0.10 * (a.length - 1))];
                r.restEstimated = true;
            } else r.restHr = p.fallbackRestHr;
        }
        double reserve = Math.max(20.0, r.hrMax - r.restHr);

        // ---- 3. epoch kinds
        for (Epoch x : ep) {
            if (Double.isNaN(x.hr)) { x.kind = NO_DATA; r.noDataMin++; continue; }
            r.validMin++;
            x.pct = (x.hr - r.restHr) / reserve;
            x.level = x.pct >= p.levelHard ? L_HARD : x.pct >= p.levelModerate ? L_MODERATE : x.pct >= p.levelLight ? L_LIGHT : L_REST;
            boolean active = (x.pct >= p.activePct && x.motion >= p.stillMotion) || (x.motion >= p.clearMotion && x.pct >= 0.05);
            if (active) { x.kind = ACTIVE; r.activeMin++; r.levelMin[x.level]++; }
            else if (x.pct >= p.elevatedStillPct && x.motion < p.stillMotion) { x.kind = ELEVATED_STILL; r.elevatedStillMin++; }
            else x.kind = REST;
            if (x.pct > 0) r.load += trimp(x.pct);
        }

        // ---- 4. bouts: runs of ACTIVE, bridging short non-active pauses (but never across missing data)
        boolean[] inBout = new boolean[ne];
        int k = 0;
        while (k < ne) {
            if (ep[k].kind != ACTIVE) { k++; continue; }
            int s = k, e = k, m = k + 1;
            while (m < ne) {
                if (ep[m].kind == ACTIVE) { e = m; m++; continue; }
                int g = m;
                while (g < ne && ep[g].kind != ACTIVE) g++;      // a short strap gap counts as a pause, like a break
                if (g < ne && (g - m) <= p.mergeGapMin) { m = g; continue; }
                break;
            }
            int activeEpochs = 0;
            for (int q = s; q <= e; q++) if (ep[q].kind == ACTIVE) activeEpochs++;
            if (activeEpochs >= p.minBoutMin) {
                Bout b = summarise(ep, s, e, r, p);
                r.bouts.add(b);
                for (int q = s; q <= e; q++) inBout[q] = true;
            }
            k = e + 1;
        }
        // short efforts: not in a bout, reached 30% of reserve or hard movement, 1+ consecutive epochs
        k = 0;
        while (k < ne) {
            boolean hard = !inBout[k] && ep[k].kind == ACTIVE && (ep[k].pct >= p.shortEffortPct || ep[k].motion >= p.hardMotion);
            if (!hard) { k++; continue; }
            int s = k, e = k;
            while (e + 1 < ne && !inBout[e + 1] && ep[e + 1].kind == ACTIVE && (ep[e + 1].pct >= p.shortEffortPct * 0.7 || ep[e + 1].motion >= p.hardMotion * 0.7)) e++;
            r.shortEfforts.add(summarise(ep, s, e, r, p));
            k = e + 1;
        }
        for (Bout b : r.bouts) { r.boutMin += b.durationMin; r.longestBoutMin = Math.max(r.longestBoutMin, b.durationMin); }
        r.epochs = new ArrayList<Epoch>(Arrays.asList(ep));
        return r;
    }

    // ---------------------------------------------------------------- helpers

    /** Banister TRIMP for one minute at fraction x of heart-rate reserve (men's weighting). */
    static double trimp(double x) { return 1.0 * x * 0.64 * Math.exp(1.92 * x); }

    private static Bout summarise(Epoch[] ep, int s, int e, Result r, Params p) {
        Bout b = new Bout();
        b.start = ep[s].start;
        b.end = ep[e].start + 60;
        b.durationMin = e - s + 1;
        double hsum = 0, psum = 0, msum = 0, peak = 0; int cnt = 0, stp = 0;
        for (int q = s; q <= e; q++) {
            Epoch x = ep[q];
            if (Double.isNaN(x.hr)) continue;
            cnt++; hsum += x.hr; psum += x.pct; msum += x.motion; stp += x.steps;
            peak = Math.max(peak, x.hrP90);
            if (x.kind == ACTIVE) { b.activeMin++; b.levelMin[x.level]++; }
            b.load += trimp(Math.max(0, x.pct));
        }
        if (cnt > 0) { b.avgHr = hsum / cnt; b.avgPct = psum / cnt; b.motion = msum / cnt; b.stepsPerMin = stp / (double) cnt; }
        b.peakHr = peak;
        b.sustained = b.durationMin >= p.sustainedMin;
        String lvl = b.avgPct >= p.levelHard ? "hard" : b.avgPct >= p.levelModerate ? "moderate" : "light";
        if (b.motion >= p.hardMotion) b.label = "Running or hard effort (" + lvl + ")";
        else if (b.stepsPerMin >= 40) b.label = "Walking or on your feet (" + lvl + ")";
        else b.label = "Physical work, arms and hands (" + lvl + ")";
        return b;
    }

    static String hhmm(long unix) {
        long sod = ((unix % 86400L) + 86400L) % 86400L;   // UTC; the caller shows local time
        return String.format(Locale.US, "%02d:%02d", sod / 3600, (sod % 3600) / 60);
    }

    /** What is needed to tune the thresholds on real work days. */
    public static String toTuningText(Result r) {
        List<Double> m = new ArrayList<Double>(), pc = new ArrayList<Double>();
        for (Epoch x : r.epochs) if (!Double.isNaN(x.hr)) { m.add(x.motion); pc.add(x.pct); }
        if (m.isEmpty()) return "no valid epochs";
        Collections_sort(m); Collections_sort(pc);
        return String.format(Locale.US, "epochs %d | motion p10 %.3f p50 %.3f p90 %.3f p99 %.3f | %%reserve p10 %.2f p50 %.2f p90 %.2f p99 %.2f",
                m.size(), q(m, 0.1), q(m, 0.5), q(m, 0.9), q(m, 0.99), q(pc, 0.1), q(pc, 0.5), q(pc, 0.9), q(pc, 0.99));
    }

    private static void Collections_sort(List<Double> l) { java.util.Collections.sort(l); }
    private static double q(List<Double> s, double f) { return s.get((int) Math.round(f * (s.size() - 1))); }
}
