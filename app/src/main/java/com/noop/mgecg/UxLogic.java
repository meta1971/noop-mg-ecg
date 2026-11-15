package com.noop.mgecg;

// FILE VERSION 0.61.0 (15 Nov 2026): NEW. The pure part of the 0.61.0 screen work (no Android calls, tested against the real nights and readings):
//   slot(hour): morning / daytime / evening for the Today page; range(): "your usual range" of a nightly figure = the 10th to 90th percentile of the up to 30 nights before the one shown (needs 5);
//   the one-sentence answers for the Heart, Rest, ECG and You tabs (words first, the numbers inside the words); the status line and the list of things that need attention;
//   the marks drawn on a saved trace (phone buzz episodes and movement seconds from the reading notes); the text-size steps.
// Nothing here invents a figure: a sentence is built only from values it is given, and where there are too few nights it says nothing about a range.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class UxLogic {
    private UxLogic() {}

    // ------------------------------------------------------------------ time of day
    /** 0 morning (04:00 to 10:59), 1 daytime (11:00 to 17:59), 2 evening and night (18:00 to 03:59). */
    static int slot(int hour) {
        int h = ((hour % 24) + 24) % 24;
        return h >= 4 && h < 11 ? 0 : h >= 11 && h < 18 ? 1 : 2;
    }

    // ------------------------------------------------------------------ your usual range
    static final int BELOW = -1, INSIDE = 0, ABOVE = 1, NONE = 9;
    static final int RANGE_MIN_N = 5, RANGE_MAX_N = 30;

    static final class Range {
        int n;                                   // earlier nights used
        double lo = Double.NaN, med = Double.NaN, hi = Double.NaN;
        int pos = NONE;                          // BELOW, INSIDE, ABOVE, or NONE when there are too few nights or no value
        boolean ok() { return pos != NONE; }
    }

    /** Linear-interpolation percentile (p in 0..1) of an ascending array. */
    static double percentile(double[] s, double p) {
        if (s.length == 0) return Double.NaN;
        if (s.length == 1) return s[0];
        double x = p * (s.length - 1);
        int i = (int) Math.floor(x);
        int j = Math.min(s.length - 1, i + 1);
        return s[i] + (x - i) * (s[j] - s[i]);
    }

    /** [earlier] oldest first (NaN allowed); the last up to 30 finite values are used. [today] is the night being judged. */
    static Range range(double[] earlier, double today) {
        Range r = new Range();
        List<Double> v = new ArrayList<Double>();
        if (earlier != null) for (int i = earlier.length - 1; i >= 0 && v.size() < RANGE_MAX_N; i--) if (!Double.isNaN(earlier[i]) && !Double.isInfinite(earlier[i])) v.add(earlier[i]);
        r.n = v.size();
        if (r.n < RANGE_MIN_N || Double.isNaN(today)) return r;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        r.lo = percentile(a, 0.10);
        r.med = percentile(a, 0.50);
        r.hi = percentile(a, 0.90);
        r.pos = today < r.lo ? BELOW : today > r.hi ? ABOVE : INSIDE;
        return r;
    }

    static String hmx(double h) {
        int m = (int) Math.round(h * 60.0);
        return (m / 60) + "h " + String.format(Locale.UK, "%02d", m % 60) + "m";
    }

    static String num(double v) {
        return String.valueOf(Math.round(v));
    }

    /** "inside your usual 52 to 58" / "higher than your usual 52 to 58" / "lower than your usual 52 to 58". */
    static String vs(Range r, String lo, String hi, String below, String above) {
        String span = " your usual " + lo + " to " + hi;
        return r.pos == INSIDE ? "inside" + span : r.pos == BELOW ? below + span : above + span;
    }

    static String basis(int n) {
        return n >= RANGE_MIN_N ? "Your usual range is the middle 80 % of your last " + n + " nights before this one." : "";
    }

    // ------------------------------------------------------------------ the one-sentence answers
    /** Rest tab. Any argument that is NaN or without a usable range is left out of the sentence. */
    static String restAnswer(double tstH, Range rT, double lowHr, Range rH, double rmssd, Range rV) {
        if (Double.isNaN(tstH)) return null;
        StringBuilder s = new StringBuilder("You slept ").append(hmx(tstH));
        if (rT != null && rT.ok()) s.append(", ").append(vs(rT, hmx(rT.lo), hmx(rT.hi), "under", "over"));
        s.append(".");
        String hr = null, hv = null;
        if (!Double.isNaN(lowHr) && rH != null && rH.ok())
            hr = "Your lowest five-minute heart rate was " + num(lowHr) + " bpm, " + vs(rH, num(rH.lo), num(rH.hi), "lower than", "higher than") + ".";
        if (!Double.isNaN(rmssd) && rV != null && rV.ok())
            hv = "Your HRV (RMSSD) was " + num(rmssd) + " ms, " + vs(rV, num(rV.lo), num(rV.hi), "lower than", "higher than") + ".";
        boolean hrOut = rH != null && rH.ok() && rH.pos != INSIDE, hvOut = rV != null && rV.ok() && rV.pos != INSIDE;
        if (hrOut) s.append(" ").append(hr);
        if (hvOut) s.append(" ").append(hv);
        if (!hrOut && !hvOut) {
            if (hr != null) s.append(" ").append(hr);
            else if (hv != null) s.append(" ").append(hv);
        }
        return s.toString();
    }

    /** Heart tab. [when] = "last night" or "on the night of Mon 9 Nov". */
    static String heartAnswer(String when, double lowHr, Range r) {
        if (Double.isNaN(lowHr) || r == null || !r.ok()) return null;
        return "Lowest five-minute heart rate " + when + ": " + num(lowHr) + " bpm, " + vs(r, num(r.lo), num(r.hi), "lower than", "higher than") + " (last " + r.n + " nights).";
    }

    /** ECG tab. n7 = readings in the last 7 days, nAB of them graded A or B; the latest reading is described when [latestWhen] is given. */
    static String ecgAnswer(int n7, int nAB, String latestWhen, int latestHr, char latestGrade) {
        StringBuilder s = new StringBuilder();
        if (n7 <= 0) s.append("No reading in the last 7 days.");
        else s.append(n7).append(n7 == 1 ? " reading" : " readings").append(" in the last 7 days, ").append(nAB).append(" graded A or B.");
        if (latestWhen != null && latestWhen.length() > 0) {
            s.append(" Latest ").append(latestWhen);
            if (latestGrade >= 'A' && latestGrade <= 'D') s.append(", grade ").append(latestGrade);
            if (latestHr > 0) s.append(", ").append(latestHr).append(" bpm");
            s.append(".");
        } else if (n7 <= 0) s.append(" A free reading takes about 2\u00bd minutes.");
        return s.toString();
    }

    /** You tab. linkLevel is LinkState.GOOD / WARN / BAD. */
    static String youAnswer(boolean loggerOn, int linkLevel, double loggedH, int issues) {
        StringBuilder s = new StringBuilder();
        s.append(!loggerOn ? "The day logger is off." : linkLevel == LinkState.GOOD ? "Strap linked, day logger on." : "Day logger on, strap not fully linked.");
        if (!Double.isNaN(loggedH)) s.append(" ").append(String.format(Locale.UK, "%.1f", loggedH)).append(" h logged in the last 24.");
        s.append(issues <= 0 ? " Nothing needs your attention." : issues == 1 ? " One thing needs your attention (tap the status line at the top)." : " " + issues + " things need your attention (tap the status line at the top).");
        return s.toString();
    }

    // ------------------------------------------------------------------ status line and what needs attention
    static String statusLine(boolean loggerOn, int linkLevel, long dataAgeSec) {
        StringBuilder s = new StringBuilder();
        s.append(linkLevel == LinkState.GOOD ? "Strap linked" : linkLevel == LinkState.WARN ? "Strap link weak" : "Strap not linked");
        s.append("  ·  ").append(dataAgeSec < 0 ? "no records yet" : "last record " + LinkState.age(dataAgeSec * 1000L) + " ago");
        s.append("  ·  logger ").append(loggerOn ? "on" : "off");
        return s.toString();
    }

    static final class Issue {
        String code, title, detail;
        int level;                                // 1 attention, 2 problem
        Issue(String code, int level, String title, String detail) { this.code = code; this.level = level; this.title = title; this.detail = detail; }
    }

    static List<Issue> issues(boolean loggerOn, int linkLevel, String linkTitle, String linkDetail, boolean stale, boolean notifBlocked, boolean exactOff, boolean batteryLimited, boolean dndMissing) {
        List<Issue> out = new ArrayList<Issue>();
        if (!loggerOn) out.add(new Issue("logger", 2, "Day logger is off", "Nothing is being recorded and the app does not hold the strap link. Turn it on in the day logger."));
        else if (linkLevel != LinkState.GOOD) out.add(new Issue("link", linkLevel == LinkState.WARN ? 1 : 2, linkTitle == null ? "Strap link" : linkTitle, linkDetail == null ? "" : linkDetail));
        if (stale && loggerOn) out.add(new Issue("stale", 1, "Records are old", "No new records for a while. The strap stores them and sends them at each history pull: use Sync now on Today."));
        if (notifBlocked) out.add(new Issue("notif", 1, "Notifications are blocked", "The logger's status, reminders and Heart Watch alerts cannot show until notifications are allowed for this app."));
        if (exactOff) out.add(new Issue("exact", 1, "Alarms may arrive late", "Without Alarms & reminders access Android can delay the morning and evening alarms and the wind-down reminder by several minutes."));
        if (batteryLimited && loggerOn) out.add(new Issue("battery", 1, "Battery limits are on", "Android may stop the logger in the background. Exempting the app from battery optimisation keeps it running."));
        if (dndMissing) out.add(new Issue("dnd", 1, "Do Not Disturb access is missing", "Calls are meant to be silenced during a reading but the app has no access. Allow it once and it works from then on."));
        return out;
    }

    // ------------------------------------------------------------------ marks on a saved trace
    static final int KIND_BUZZ = 0, KIND_MOVE = 1;

    static final class Span {
        double a, b; int kind;
        Span(double a, double b, int kind) { this.a = a; this.b = b; this.kind = kind; }
    }

    private static double parse(String s) { try { return Double.parseDouble(s.trim()); } catch (RuntimeException e) { return Double.NaN; } }

    /**
     * [episodes] = the reading note ring_episodes ("121-152;170-175", seconds from the reading start); [moving] = motion_moving_times_s ("13;14;40").
     * Buzz episodes are widened by 1 s each side (the flags arrive about a second after their samples); movement seconds join into runs when 2 s or less apart.
     * Timing is from the start of the reading, so against the first valid sample of the trace it is good to about 3 s.
     */
    static List<Span> marks(String episodes, String moving, double durS) {
        List<Span> out = new ArrayList<Span>();
        if (!(durS > 0)) return out;
        if (episodes != null && episodes.length() > 0) {
            for (String e : episodes.split(";")) {
                int d = e.indexOf('-');
                if (d <= 0) continue;
                double a = parse(e.substring(0, d)), b = parse(e.substring(d + 1));
                if (Double.isNaN(a) || Double.isNaN(b) || b < a) continue;
                double x = Math.max(0.0, a - 1.0), y = Math.min(durS, b + 1.0);
                if (y > x) out.add(new Span(x, y, KIND_BUZZ));
            }
        }
        if (moving != null && moving.length() > 0) {
            List<Double> t = new ArrayList<Double>();
            for (String e : moving.split(";")) { double v = parse(e); if (!Double.isNaN(v) && v >= 0) t.add(v); }
            Collections.sort(t);
            int i = 0;
            while (i < t.size()) {
                int j = i;
                while (j + 1 < t.size() && t.get(j + 1) - t.get(j) <= 2.0) j++;
                double x = Math.max(0.0, t.get(i) - 0.5), y = Math.min(durS, t.get(j) + 1.0);
                if (y > x) out.add(new Span(x, y, KIND_MOVE));
                i = j + 1;
            }
        }
        Collections.sort(out, new Comparator<Span>() { @Override public int compare(Span p, Span q) { return Double.compare(p.a, q.a); } });
        return out;
    }

    // ------------------------------------------------------------------ the verdict after a reading
    /** One sentence from the app's own end rule (ReadingEnd: 60 s of the strap's best-quality seconds and 64 clean beats) and the buzz detector. */
    static String readingVerdict(int q3Sec, int cleanBeats, int needQ3, int needBeats, int buzzEpisodes, int movedSeconds) {
        StringBuilder s = new StringBuilder();
        if (q3Sec >= needQ3 && cleanBeats >= needBeats)
            s.append("Enough data: ").append(q3Sec).append(" s of good signal and ").append(cleanBeats).append(" clean beats (the app's bar is ").append(needQ3).append(" s and ").append(needBeats).append(").");
        else {
            s.append("Short of the bar: ")
             .append(q3Sec >= needQ3 ? q3Sec + " s of good signal (bar " + needQ3 + ")" : q3Sec + " of " + needQ3 + " s of good signal")
             .append(" and ")
             .append(cleanBeats >= needBeats ? cleanBeats + " clean beats (bar " + needBeats + ")" : cleanBeats + " of " + needBeats + " clean beats")
             .append(". Another reading with the arm still and the fingers flat on the clasp would give a cleaner one.");
        }
        if (buzzEpisodes > 0) s.append(" The strap felt the phone buzz ").append(buzzEpisodes).append(buzzEpisodes == 1 ? " time" : " times").append("; ").append(buzzEpisodes == 1 ? "it is" : "they are").append(" marked on the trace.");
        if (movedSeconds >= 10) s.append(" The arm moved in ").append(movedSeconds).append(" s.");
        return s.toString();
    }

    // ------------------------------------------------------------------ text size
    static final int STEPS = 3;
    static float factor(int step) { return step <= 0 ? 1.0f : step == 1 ? 1.15f : 1.3f; }
    static String stepName(int step) { return step <= 0 ? "Normal" : step == 1 ? "Large" : "Larger"; }
}
