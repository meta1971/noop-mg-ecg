package com.noop.mgecg;

// FILE VERSION 0.60.0 (15 Nov 2026): NEW. The numbers and wording behind the added wellbeing cards: bed-time scatter, night bars and personal bests, the ECG cards (clean-ECG ring, evening pulse spread,
// calmest evening), the WHO-5 check-in scoring, the three-good-things helpers, the public-domain lines and the wind-down time. Pure Java, no Android, tested off the phone against the real nights and readings.
// Rules: every figure is counted from the data handed in; where there is too little data the card says so; ECG pulse figures use readings graded A or B only; "evening" is 18:00 to midnight.

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class WellbeingExtra {
    private WellbeingExtra() {}

    static final int EVENING_FROM_MIN = 18 * 60;          // evening readings: 18:00 to midnight
    static final long ECG_WINDOW_MS = 30L * 86400000L;    // readings of the last 30 days
    static final int MIN_EVENING = 15, MIN_DAY = 3, MIN_CLEAN = 5, MIN_SCATTER = 3;
    static final int WIND_LEAD_MIN = 45;                  // the reminder comes 45 minutes before the plan time
    static final int GOOD_ENTRY_PARTS = 4;                // three good things and the why
    static final String US = "\u001f";

    static String dayName(long epochDay) {
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochDay * 86400000L));
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.UK, "%.1f", v);
    }

    private static int dow(long epochDay) { return (int) (((epochDay + 3L) % 7L + 7L) % 7L); }       // Monday = 0 (1 Jan 1970 was a Thursday)

    // ------------------------------------------------------------------ bed time against hours, on work nights
    static final class Scatter {
        int n; int[] bedMin = new int[0]; double[] hrs = new double[0]; boolean[] early = new boolean[0];
        int nEarly, nLate; double mEarly = Double.NaN, mLate = Double.NaN;
        String head = "", line = "";
    }

    static Scatter scatter(List<SleepRegularity.Night> nights) {
        Scatter s = new Scatter();
        if (nights == null) { s.head = "Not enough work nights yet."; s.line = "A work night ends with a wake-up before 07:00. The chart needs 3."; return s; }
        List<SleepRegularity.Night> w = new ArrayList<SleepRegularity.Night>();
        for (SleepRegularity.Night x : nights) { int wk = ((x.wakeMin % 1440) + 1440) % 1440; if (wk < WellbeingModel.WORK_WAKE_MIN) w.add(x); }
        s.n = w.size();
        s.bedMin = new int[s.n]; s.hrs = new double[s.n]; s.early = new boolean[s.n];
        double se = 0, sl = 0;
        for (int i = 0; i < s.n; i++) {
            SleepRegularity.Night x = w.get(i);
            s.bedMin[i] = x.bedMin; s.hrs[i] = x.asleepMin / 60.0; s.early[i] = x.bedMin < WellbeingModel.LATE_BED_MIN;
            if (s.early[i]) { s.nEarly++; se += s.hrs[i]; } else { s.nLate++; sl += s.hrs[i]; }
        }
        if (s.nEarly > 0) s.mEarly = se / s.nEarly;
        if (s.nLate > 0) s.mLate = sl / s.nLate;
        if (s.n < MIN_SCATTER) {
            s.head = "Not enough work nights yet.";
            s.line = "A work night ends with a wake-up before 07:00. You have " + s.n + " in this window; the chart needs " + MIN_SCATTER + ".";
            return s;
        }
        boolean both = s.nEarly >= WellbeingModel.MIN_SIGNAL && s.nLate >= WellbeingModel.MIN_SIGNAL;
        if (!both) {
            s.head = "Your work nights fall mostly on one side of 23:00.";
            s.line = s.nEarly + (s.nEarly == 1 ? " night" : " nights") + " in bed before 23:00 and " + s.nLate + (s.nLate == 1 ? " night" : " nights") + " after. A comparison needs " + WellbeingModel.MIN_SIGNAL + " on each side.";
            return s;
        }
        String nums = "Before 23:00: " + WellbeingModel.hm(s.mEarly) + " on average (" + s.nEarly + " nights). After 23:00: " + WellbeingModel.hm(s.mLate) + " (" + s.nLate + " nights).";
        boolean enough = s.nEarly >= WellbeingModel.MIN_EACH && s.nLate >= WellbeingModel.MIN_EACH;
        if (s.mEarly - s.mLate >= WellbeingModel.DIFF_H) s.head = "Earlier beds went with longer nights.";
        else s.head = "No clear difference between early and late beds yet.";
        s.line = nums + (enough ? "" : " Early signal: " + WellbeingModel.MIN_EACH + " nights on each side make it a pattern.");
        return s;
    }

    // ------------------------------------------------------------------ night bars
    static final class Bars {
        int n; double[] hrs = new double[0]; int[] dow = new int[0];
        int recentN, recentGe7, priorN, priorGe7; double avg = Double.NaN;
        String head = "", line = "";
    }

    static Bars bars(List<SleepRegularity.Night> nights, long baseEpochDay) {
        Bars b = new Bars();
        if (nights == null || nights.size() < 3) { b.head = "Not enough nights yet."; b.line = "The chart needs 3 nights."; return b; }
        int from = Math.max(0, nights.size() - 14);
        b.n = nights.size() - from;
        b.hrs = new double[b.n]; b.dow = new int[b.n];
        double sum = 0;
        int[] mins = new int[b.n];
        for (int i = 0; i < b.n; i++) {
            SleepRegularity.Night x = nights.get(from + i);
            mins[i] = x.asleepMin; b.hrs[i] = x.asleepMin / 60.0; sum += b.hrs[i];
            long wakeDay = baseEpochDay + x.day + Math.floorDiv(x.wakeMin, 1440);          // labelled by the morning you woke
            b.dow[i] = dow(wakeDay);
        }
        b.avg = sum / b.n;
        b.recentN = Math.min(7, b.n);
        b.priorN = Math.min(7, b.n - b.recentN);
        for (int i = b.n - b.recentN; i < b.n; i++) if (mins[i] >= 420) b.recentGe7++;
        for (int i = b.n - b.recentN - b.priorN; i < b.n - b.recentN; i++) if (mins[i] >= 420) b.priorGe7++;
        b.head = b.recentGe7 + " of your last " + b.recentN + " nights reached 7 h.";
        b.line = (b.priorN >= 4 ? "The " + b.priorN + " nights before: " + b.priorGe7 + " of " + b.priorN + ". " : "") + "Average over these " + b.n + " nights: " + WellbeingModel.hm(b.avg) + ".";
        return b;
    }

    // ------------------------------------------------------------------ personal bests in the window
    static final class Bests {
        double longestH = Double.NaN; long longestWake = Long.MIN_VALUE;
        int run; long runEndWake = Long.MIN_VALUE;
    }

    static Bests bests(List<SleepRegularity.Night> nights, long baseEpochDay) {
        Bests r = new Bests();
        if (nights == null) return r;
        int cur = 0, lastGoodDay = Integer.MIN_VALUE;
        for (SleepRegularity.Night x : nights) {
            double h = x.asleepMin / 60.0;
            long wakeDay = baseEpochDay + x.day + Math.floorDiv(x.wakeMin, 1440);
            if (Double.isNaN(r.longestH) || h > r.longestH) { r.longestH = h; r.longestWake = wakeDay; }
            if (x.asleepMin >= 420) {
                if (cur > 0 && x.day == lastGoodDay + 1) cur++; else cur = 1;
                lastGoodDay = x.day;
                if (cur > r.run) { r.run = cur; r.runEndWake = wakeDay; }
            } else cur = 0;
        }
        return r;
    }

    // ------------------------------------------------------------------ ECG readings
    static final class Rd {
        final long timeMs; final int hr; final char grade;
        Rd(long timeMs, int hr, char grade) { this.timeMs = timeMs; this.hr = hr; this.grade = grade; }
    }

    static final class Ecg {
        int nAll, nA, cleanPct = -1;
        int evN; double evMedian = Double.NaN; int evMin, evMax;
        int[] binLo = new int[0]; int[] binCnt = new int[0];
        int bandLo = -1, bandN;
        int daysWith3;
        boolean calm; long calmDay; int calmN; double calmMedian = Double.NaN; int calmMinHr, calmMaxHr;
        int[] calmAt = new int[0], calmHr = new int[0];
        String cleanHead = "", cleanLine = "", histHead = "", histLine = "", calmHead = "", calmLine = "";
    }

    static double median(int[] v, int n) {
        int[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2.0;
    }

    static Ecg ecg(List<Rd> all, long nowMs, TimeZone tz) {
        Ecg e = new Ecg();
        List<Rd> ev = new ArrayList<Rd>();
        if (all != null) for (Rd r : all) {
            if (r.grade < 'A' || r.grade > 'D') continue;
            if (nowMs - r.timeMs > ECG_WINDOW_MS || r.timeMs > nowMs + 86400000L) continue;
            e.nAll++;
            if (r.grade == 'A') e.nA++;
            long lt = r.timeMs + tz.getOffset(r.timeMs);
            int minOfDay = (int) (((lt % 86400000L) + 86400000L) % 86400000L / 60000L);
            if ((r.grade == 'A' || r.grade == 'B') && r.hr > 0 && minOfDay >= EVENING_FROM_MIN) ev.add(r);
        }
        // clean-ECG ring
        if (e.nAll >= MIN_CLEAN) {
            e.cleanPct = (int) Math.round(100.0 * e.nA / e.nAll);
            e.cleanHead = e.cleanPct + "% of your readings were grade A.";
            e.cleanLine = e.nA + " of " + e.nAll + " graded readings in the last 30 days. The grade is the quality of the recording, not a statement about your heart.";
        } else {
            e.cleanHead = "Not enough readings yet.";
            e.cleanLine = e.nAll + " saved in the last 30 days; this needs " + MIN_CLEAN + ".";
        }
        // evening pulse spread
        e.evN = ev.size();
        if (e.evN < MIN_EVENING) {
            e.histHead = "Not enough evening readings yet.";
            e.histLine = e.evN + " graded A or B between 18:00 and midnight in the last 30 days; this needs " + MIN_EVENING + ".";
            return e;
        }
        int[] hr = new int[e.evN];
        for (int i = 0; i < e.evN; i++) hr[i] = ev.get(i).hr;
        e.evMedian = median(hr, e.evN);
        e.evMin = hr[0]; e.evMax = hr[0];
        for (int v : hr) { if (v < e.evMin) e.evMin = v; if (v > e.evMax) e.evMax = v; }
        int lo = (e.evMin / 5) * 5, hi = (e.evMax / 5) * 5, nb = (hi - lo) / 5 + 1;
        e.binLo = new int[nb]; e.binCnt = new int[nb];
        for (int i = 0; i < nb; i++) e.binLo[i] = lo + 5 * i;
        for (int v : hr) e.binCnt[(v - lo) / 5]++;
        int bestI = 0, bestN = -1;
        for (int i = 0; i < nb; i++) {
            int c = e.binCnt[i] + (i + 1 < nb ? e.binCnt[i + 1] : 0);
            if (c > bestN) { bestN = c; bestI = i; }
        }
        e.bandLo = e.binLo[bestI]; e.bandN = bestN;
        boolean most = bestN * 2 >= e.evN;
        e.histHead = most ? "Most evenings your pulse sits at " + e.bandLo + " to " + (e.bandLo + 9) + " bpm."
                          : "Your evening pulse is spread out; the busiest band is " + e.bandLo + " to " + (e.bandLo + 9) + " bpm.";
        e.histLine = bestN + " of " + e.evN + " evening readings (grade A or B, 18:00 to midnight, last 30 days). Median " + num(e.evMedian) + " bpm, range " + e.evMin + " to " + e.evMax + ".";
        // calmest evening: the day (3 or more evening readings) with the lowest median
        List<Long> days = new ArrayList<Long>();
        for (Rd r : ev) { long d = Math.floorDiv(r.timeMs + tz.getOffset(r.timeMs), 86400000L); if (!days.contains(d)) days.add(d); }
        for (Long dObj : days) {
            long d = dObj;
            List<Rd> dr = new ArrayList<Rd>();
            for (Rd r : ev) if (Math.floorDiv(r.timeMs + tz.getOffset(r.timeMs), 86400000L) == d) dr.add(r);
            if (dr.size() < MIN_DAY) continue;
            e.daysWith3++;
            int[] h = new int[dr.size()];
            for (int i = 0; i < h.length; i++) h[i] = dr.get(i).hr;
            double md = median(h, h.length);
            boolean better = !e.calm || md < e.calmMedian || (md == e.calmMedian && (h.length > e.calmN || (h.length == e.calmN && d > e.calmDay)));
            if (!better) continue;
            e.calm = true; e.calmDay = d; e.calmN = h.length; e.calmMedian = md;
            e.calmMinHr = h[0]; e.calmMaxHr = h[0];
            for (int v : h) { if (v < e.calmMinHr) e.calmMinHr = v; if (v > e.calmMaxHr) e.calmMaxHr = v; }
            Rd[] sorted = dr.toArray(new Rd[0]);
            Arrays.sort(sorted, new java.util.Comparator<Rd>() { @Override public int compare(Rd a, Rd b) { return a.timeMs < b.timeMs ? -1 : a.timeMs > b.timeMs ? 1 : 0; } });
            e.calmAt = new int[sorted.length]; e.calmHr = new int[sorted.length];
            for (int i = 0; i < sorted.length; i++) {
                long lt = sorted[i].timeMs + tz.getOffset(sorted[i].timeMs);
                e.calmAt[i] = (int) (((lt % 86400000L) + 86400000L) % 86400000L / 60000L);
                e.calmHr[i] = sorted[i].hr;
            }
        }
        if (e.calm) {
            double diff = e.evMedian - e.calmMedian;
            e.calmHead = "Your calmest evening: " + dayName(e.calmDay) + ".";
            e.calmLine = "Median pulse " + num(e.calmMedian) + " bpm across " + e.calmN + " readings (" + e.calmMinHr + " to " + e.calmMaxHr + "), against " + num(e.evMedian) + " across all your evenings"
                    + (diff < 3 ? ", close to your usual." : ".") + " Readings were taken at different times of the evening, so this compares days; it cannot say why.";
        }
        return e;
    }

    // ------------------------------------------------------------------ WHO-5 (World Health Organization Well-Being Index)
    static final String[] WHO5_ITEMS = {
        "I have felt cheerful and in good spirits",
        "I have felt calm and relaxed",
        "I have felt active and vigorous",
        "I woke up feeling fresh and rested",
        "My daily life has been filled with things that interest me" };
    static final String[] WHO5_SCALE = { "All of the time", "Most of the time", "More than half of the time", "Less than half of the time", "Some of the time", "At no time" };   // scores 5 .. 0

    static int who5Score(int k) { return 5 - k; }
    static int who5Raw(int[] a) { int s = 0; for (int v : a) s += v; return s; }
    static int who5Pct(int[] a) { return who5Raw(a) * 4; }

    /** {head, body}. prevPct < 0 when there is no earlier check-in. */
    static String[] who5Text(int pct, int prevPct, long prevDay) {
        String head = pct + " of 100";
        StringBuilder b = new StringBuilder();
        if (pct > 50) b.append("Higher is better. In Danish general-population studies the average is about 70.");
        else b.append("A score of 50 or below is the usual point where this questionnaire suggests a closer look, so it is worth talking over how you have been feeling with your GP or someone you trust. It is a questionnaire, not a diagnosis.");
        if (prevPct >= 0) {
            int d = pct - prevPct;
            String since = dayName(prevDay);
            if (Math.abs(d) >= 10) b.append(" ").append(d > 0 ? "Up " : "Down ").append(Math.abs(d)).append(" points since ").append(since).append(" (").append(prevPct).append("). The review of this questionnaire treats a change of 10 points as clinically relevant.");
            else b.append(" Within 10 points of ").append(since).append(" (").append(prevPct).append("); the review of this questionnaire treats a change of 10 points as clinically relevant.");
        }
        return new String[] { head, b.toString() };
    }

    /** History text "day:pct;day:pct", newest last, the last 12. */
    static String who5Push(String hist, long day, int pct) {
        List<String> keep = new ArrayList<String>();
        if (hist != null && hist.length() > 0) for (String p : hist.split(";")) if (p.length() > 0 && !p.startsWith(day + ":")) keep.add(p);
        keep.add(day + ":" + pct);
        while (keep.size() > 12) keep.remove(0);
        StringBuilder s = new StringBuilder();
        for (String p : keep) { if (s.length() > 0) s.append(';'); s.append(p); }
        return s.toString();
    }

    /** {day, pct} of the entry n back from the newest (0 = newest), or null. */
    static long[] who5Entry(String hist, int back) {
        if (hist == null || hist.length() == 0) return null;
        String[] p = hist.split(";");
        int i = p.length - 1 - back;
        if (i < 0) return null;
        try { String[] kv = p[i].split(":"); return new long[] { Long.parseLong(kv[0]), Long.parseLong(kv[1]) }; } catch (Exception ex) { return null; }
    }

    // ------------------------------------------------------------------ three good things
    static List<String> goodSeeds(WellbeingModel.Out o, Ecg e) {
        List<String> s = new ArrayList<String>();
        if (o != null && o.enough) {
            if (o.ge7Last >= 3) s.add("I slept 7 hours or more on " + o.ge7Last + " of the last " + o.nLast + " nights.");
            if (o.wakeRun >= 3) s.add("I was up at " + WellbeingModel.clock(o.latestWakeMin) + " " + o.wakeRun + " mornings running.");
            if (o.bounce && o.hrs.length >= 2) s.add("After a short night of " + WellbeingModel.hm(o.hrs[o.hrs.length - 2]) + " I slept " + WellbeingModel.hm(o.hrs[o.hrs.length - 1]) + " the next night.");
            if (o.planOn && o.planKeptN >= 1) s.add("I kept my bed plan on " + o.planKeptN + " of " + o.planNightsN + (o.planNightsN == 1 ? " night." : " nights."));
        }
        if (e != null && e.calm) s.add("My calmest evening was " + dayName(e.calmDay) + ": median pulse " + num(e.calmMedian) + " bpm.");
        while (s.size() > 3) s.remove(s.size() - 1);
        return s;
    }

    static String packGood(String a, String b, String c, String why) { return clean(a) + US + clean(b) + US + clean(c) + US + clean(why); }
    private static String clean(String s) { return s == null ? "" : s.replace(US, " ").trim(); }
    static String[] unpackGood(String s) {
        String[] out = new String[GOOD_ENTRY_PARTS];
        Arrays.fill(out, "");
        if (s == null) return out;
        String[] p = s.split(US, -1);
        for (int i = 0; i < out.length && i < p.length; i++) out[i] = p[i];
        return out;
    }

    // ------------------------------------------------------------------ public-domain lines (George Long's 1862 Marcus Aurelius; Elizabeth Carter's 1758 Epictetus)
    static final String[][] LINES = {
        { "In the morning when thou risest unwillingly, let this thought be present—I am rising to the work of a human being.", "Marcus Aurelius, Meditations 5.1, tr. George Long" },
        { "If thou art pained by any external thing, it is not this thing that disturbs thee, but thy own judgment about it. And it is in thy power to wipe out this judgment now.", "Marcus Aurelius, Meditations 8.47, tr. George Long" },
        { "Men seek retreats for themselves, houses in the country, seashores, and mountains; and thou too art wont to desire such things very much. But this is altogether a mark of the most common sort of men, for it is in thy power whenever thou shalt choose to retire into thyself.", "Marcus Aurelius, Meditations 4.3, tr. George Long" },
        { "Men are disturbed, not by things, but by the principles and notions which they form concerning things.", "Epictetus, Enchiridion 5, tr. Elizabeth Carter" } };

    static String[] lineFor(long epochDay) { return LINES[(int) (((epochDay % LINES.length) + LINES.length) % LINES.length)]; }

    // ------------------------------------------------------------------ the wind-down reminder
    static int windMin(int planBedMin) { return (((planBedMin - WIND_LEAD_MIN) % 1440) + 1440) % 1440; }

    /** The next time (ms) at which the local clock shows minOfDay, strictly after nowMs. */
    static long nextFire(long nowMs, TimeZone tz, int minOfDay) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60);
        c.set(Calendar.MINUTE, minOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMs + 1000L) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }
}
