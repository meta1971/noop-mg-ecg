package com.noop.mgecg;

// FILE VERSION 0.53.0 (11 Nov 2026): a daytime STRESS estimate for the home ring (decode log 160). Research only; not a medical measure.
// WHAT IT IS: how far the heart rate sits above the person's OWN still baseline while the body is not moving. Movement is not stress here: 5-minute stretches with steps, a walk / run class or
// arm movement are left out, and so are the 10 minutes after them (the heart rate is still coming down).
// WHERE THE NUMBERS COME FROM: PhysioNet "Non-EEG Dataset for Assessment of Neurological Status" (20 people, a wrist device with heart rate once a second, like this strap): against each person's
// own baseline the heart rate rose by 9 bpm (median) in the cognitive tasks in 20 of 20 people and did not rise in the emotional (film) stage; with the baseline taken as the 20th percentile of the
// person's own non-exercise minutes the score below separates the cognitive stage from relaxing with an AUC of 0.93 (relax: median 0, 77 % of minutes under 20; cognitive: median 69, 62 % at 50 or more).
// So this ring sees mental load that raises the heart rate; it does NOT see emotion without a heart-rate rise, and illness, heat, caffeine, alcohol or a meal raise it as well.
//   baseline = 20th percentile of the 5-minute median heart rates of the still, awake stretches of the last 48 hours (12 or more of them; else the nights' lowest heart rate x 1.15)
//   score of a still 5 minutes = 100 x clamp((percent above the baseline - 3) / 22, 0, 1): 3 % above reads 0, 25 % above reads 100. Low under 20, medium 20-49, high 50 and over.
// Pure Java apart from load(), which only reads the day logger's table.

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StressModel {
    private StressModel() {}

    static final int BIN_S = 300, MIN_HR_SECONDS = 150, MIN_BASE_BINS = 12, GUARD_BINS = 2;
    static final double PCT_ZERO = 3.0, PCT_SPAN = 22.0, STEP_G = 0.05;

    static final class Bin { long t; float hr = Float.NaN; boolean awake, still, guarded; int score = -1; }
    static final class Result {
        double baseline = Double.NaN; boolean baselineFromNights; int baseBins;
        int today = -1, now = -1;                      // mean score of today's scored stretches; the latest scored stretch of the last 20 minutes
        int lowMin, medMin, highMin, movingMin, guardedMin;
        long dayStart; final List<Bin> bins = new ArrayList<>();
        String level() { return today < 0 ? "" : today < 20 ? "low" : today < 50 ? "medium" : "high"; }
    }

    static int scoreOf(double hr, double base) { double pct = 100.0 * (hr / base - 1.0); return (int) Math.round(100.0 * Math.max(0.0, Math.min(1.0, (pct - PCT_ZERO) / PCT_SPAN))); }

    /** Arrays of the same length, one entry per stored second, ascending time. state: 2 = the strap's sleep state. act: 0 = none. steps: the running step counter. accStep: largest acceleration step of that second in g (NaN = unknown). */
    static Result compute(long[] ts, int[] hr, int[] state, int[] act, int[] steps, float[] accStep, long dayStart, long now, double nightsLowHr) {
        Result R = new Result(); R.dayStart = dayStart; int n = ts.length; if (n == 0) return R;
        long t0 = (ts[0] / BIN_S) * BIN_S; int nb = (int) ((ts[n - 1] - t0) / BIN_S) + 1; if (nb <= 0 || nb > 2000) return R;
        int i = 0; float[] tmp = new float[BIN_S]; float[] acc = new float[BIN_S];
        for (int b = 0; b < nb; b++) {
            long a = t0 + (long) b * BIN_S, e = a + BIN_S; Bin B = new Bin(); B.t = a; int k = 0, sec = 0, sleep = 0, actN = 0, na = 0, big = 0; int s0 = -1, s1 = -1;
            while (i < n && ts[i] < e) {
                if (ts[i] >= a) { sec++; if (hr[i] > 30 && hr[i] < 230 && k < BIN_S) tmp[k++] = hr[i]; if (state[i] == 2) sleep++; if (act[i] != 0) actN++; if (s0 < 0) s0 = steps[i]; s1 = steps[i];
                    if (accStep != null && !Float.isNaN(accStep[i]) && na < BIN_S) { acc[na++] = accStep[i]; if (accStep[i] >= STEP_G) big++; } }
                i++;
            }
            if (k >= MIN_HR_SECONDS) { Arrays.sort(tmp, 0, k); B.hr = tmp[k / 2]; }
            B.awake = sec > 0 && sleep < 0.1 * sec;
            int dSteps = s0 < 0 ? 0 : (s1 >= s0 ? s1 - s0 : s1 + 65536 - s0);
            B.still = B.awake && !Float.isNaN(B.hr) && actN < 0.1 * sec && dSteps <= 8 && (na == 0 || big < 0.2 * na);
            R.bins.add(B);
        }
        for (int b = 0; b < nb; b++) { Bin B = R.bins.get(b); if (!B.still) continue; for (int g = 1; g <= GUARD_BINS && b - g >= 0; g++) { Bin P = R.bins.get(b - g); if (P.awake && !P.still && !Float.isNaN(P.hr)) { B.guarded = true; break; } } }
        List<Float> base = new ArrayList<>(); for (Bin B : R.bins) if (B.still && !B.guarded) base.add(B.hr);
        if (base.size() >= MIN_BASE_BINS) { float[] v = new float[base.size()]; for (int j = 0; j < v.length; j++) v[j] = base.get(j); Arrays.sort(v); R.baseline = v[(int) Math.floor(0.20 * (v.length - 1))]; R.baseBins = v.length; }
        else if (!Double.isNaN(nightsLowHr) && nightsLowHr > 30) { R.baseline = nightsLowHr * 1.15; R.baselineFromNights = true; R.baseBins = base.size(); }
        if (Double.isNaN(R.baseline)) return R;
        long sum = 0; int cnt = 0;
        for (Bin B : R.bins) {
            if (B.t < dayStart) continue;
            if (B.awake && !B.still && !Float.isNaN(B.hr)) { R.movingMin += BIN_S / 60; continue; }
            if (!B.still) continue;
            if (B.guarded) { R.guardedMin += BIN_S / 60; continue; }
            B.score = scoreOf(B.hr, R.baseline); sum += B.score; cnt++;
            if (B.score < 20) R.lowMin += BIN_S / 60; else if (B.score < 50) R.medMin += BIN_S / 60; else R.highMin += BIN_S / 60;
            if (now - (B.t + BIN_S) <= 1200) R.now = B.score;
        }
        if (cnt > 0) R.today = (int) Math.round(sum / (double) cnt);
        return R;
    }

    /** Reads the last 48 hours of the day logger's per-second table (heart rate, sleep state, activity class, step counter, and the acceleration step from the record) and computes the result. Call off the main thread. */
    static Result load(SQLiteDatabase db, long newest, List<TodayModel.NightStat> nights) {
        List<long[]> rows = new ArrayList<>(); List<Float> accl = new ArrayList<>();
        Cursor q = db.rawQuery("SELECT ts, hr, state, act, steps, substr(rec, 42, 4) FROM r18 WHERE ts >= ? ORDER BY ts", new String[] { String.valueOf(newest - 172800L) });
        try {
            while (q.moveToNext()) { rows.add(new long[] { q.getLong(0), q.getInt(1), q.getInt(2), q.getInt(3), q.getInt(4) }); byte[] b = q.isNull(5) ? null : q.getBlob(5);
                accl.add(b != null && b.length == 4 ? Float.intBitsToFloat((b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24)) : Float.NaN); }
        } finally { q.close(); }
        int n = rows.size(); long[] ts = new long[n]; int[] hr = new int[n], st = new int[n], act = new int[n], steps = new int[n]; float[] acc = new float[n];
        for (int i = 0; i < n; i++) { long[] r = rows.get(i); ts[i] = r[0]; hr[i] = (int) r[1]; st[i] = (int) r[2]; act[i] = (int) r[3]; steps[i] = (int) r[4]; acc[i] = accl.get(i); }
        double low = Double.NaN; if (nights != null && !nights.isEmpty()) { List<Double> l = new ArrayList<>(); for (TodayModel.NightStat s : nights) if (!Double.isNaN(s.hrLow5)) l.add(s.hrLow5); if (!l.isEmpty()) { double[] v = new double[l.size()]; for (int i = 0; i < v.length; i++) v[i] = l.get(i); Arrays.sort(v); low = v[v.length / 2]; } }
        return compute(ts, hr, st, act, steps, acc, TodayModel.dayStartOf(newest), newest, low);
    }
}
