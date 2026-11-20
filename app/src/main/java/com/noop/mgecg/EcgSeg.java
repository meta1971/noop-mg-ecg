package com.noop.mgecg;

// FILE VERSION 0.72.0 (20 Nov 2026): ECG segments day by day. One Pt per saved reading (P, PR, QRS, ST, T, Tp-Te, QTc, heart rate, HRV, R, noise) read from the report's parameter table,
// and the DAY GRID: for every measure, every day, how far that day's readings sit from YOUR usual of the same time of day (middle and spread of your own readings taken at least 3 days earlier), in your own spread units.
// Nothing here is compared with a population or another device: an offset that never changes (electrode place, strap, scale) cancels, a change from your usual shows. The watch line names what moved and whether it held for 2 or more measured days.
// Pure Java (no Android classes), so it can be tested off the phone. Single-lead, informal, not a diagnosis.

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EcgSeg {
    private EcgSeg() {}

    static final int QTC = 0, QRS = 1, PR = 2, P = 3, T = 4, ST = 5, TPE = 6, HR = 7, HRV = 8, R = 9, NOISE = 10, NM = 11;
    static final String[] NAME = { "QTc", "QRS", "PR", "P wave", "T wave", "ST 60 ms", "Tp-Te", "Heart rate", "HRV", "R height", "Noise" };
    static final String[] UNIT = { "ms", "ms", "ms", "\u00b5V", "\u00b5V", "\u00b5V", "ms", "bpm", "ms", "\u00b5V", "\u00b5V" };
    /** The smallest spread worth believing for each measure (its unit), so a very steady person does not get a hairline band. From the between-day spread of the first 21 days of this person's readings (addendum 340). */
    static final double[] FLOOR = { 8.0, 4.0, 8.0, 6.0, 20.0, 12.0, 5.0, 2.5, 4.0, 60.0, 2.0 };
    /** The segment measures the watch line speaks about (heart rate, HRV, R height and noise change with the day and the posture, so they show in the grid but do not raise the line). */
    static final boolean[] WATCHED = { true, true, true, true, true, true, true, false, false, false, false };
    /** Grid rows, top to bottom. */
    static final int[] ROWS = { QTC, QRS, PR, P, T, ST, TPE, HR, HRV, R };

    static final double Z_OUT = 2.0, Z_MID = 1.0;
    /** How far from your usual (in its own spreads) a segment must be before the line under the grid names it: zHold for a segment that holds 2 measured days in a row, zNow for a single day. */
    static double zHold = 2.0, zNow = 3.0;
    static final int MIN_BASE = 5, MIN_BUCKET = 6;
    static final double GAP_DAYS = 3.0, BASE_DAYS = 60.0;

    static final class Pt {
        long ts; int bucket; boolean good; String grade = "-";
        final double[] v = new double[NM];
        Pt() { Arrays.fill(v, Double.NaN); }
    }

    // ------------------------------------------------------------------ reading the report text
    private static final Pattern P_P = Pattern.compile("P wave ([+-]\\d+) \u00b5V \\((?:upright|inverted)\\)");
    private static final Pattern P_T = Pattern.compile("T wave ([+-]\\d+) \u00b5V \\((?:upright|inverted)\\)");
    private static final Pattern P_QRS = Pattern.compile("QRS duration (\\d+) ms");
    private static final Pattern P_PR = Pattern.compile("PR interval (\\d+) ms \\(\u00b1\\d+\\)");
    private static final Pattern P_QTCF = Pattern.compile("QTc Fridericia (\\d+) ms");
    private static final Pattern P_TPE = Pattern.compile("T peak to T end \\(Tp-Te\\) (\\d+) ms");
    private static final Pattern P_ST = Pattern.compile("ST at the J point / 60 ms / 80 ms \\(against the PR baseline\\) ([+-]\\d+) / ([+-]\\d+) / ([+-]\\d+) \u00b5V");

    private static double first(Pattern p, String t, int g) {
        Matcher m = p.matcher(t); if (!m.find()) return Double.NaN;
        try { return Double.parseDouble(m.group(g)); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /**
     * Fills the segment values of a point from the plain text of a report (ReadingsTable.plain). Only values the report gives are set; the rest stay NaN
     * (PR and QT are withheld by the report when they fail its own range check, and the whole table is absent under 25 beats).
     */
    static void fromText(Pt p, String t) {
        if (t == null) return;
        p.v[P] = first(P_P, t, 1); p.v[T] = first(P_T, t, 1); p.v[QRS] = first(P_QRS, t, 1); p.v[PR] = first(P_PR, t, 1);
        p.v[QTC] = first(P_QTCF, t, 1); p.v[TPE] = first(P_TPE, t, 1); p.v[ST] = first(P_ST, t, 2);
    }

    /** Multiplies the microvolt figures by a scale factor (older readings are re-based to the scale in use now, ScaleSetting.factorFrom). */
    static void rebase(Pt p, double f) {
        if (Math.abs(f - 1.0) < 1e-9) return;
        p.v[P] *= f; p.v[T] *= f; p.v[ST] *= f; p.v[R] *= f;
    }

    // ------------------------------------------------------------------ the usual and the z of one reading
    private static boolean eligible(Pt p, int m) { return !Double.isNaN(p.v[m]) && (m == NOISE || p.good); }

    /** z of reading i for measure m against the readings at least GAP_DAYS earlier (and no more than BASE_DAYS before that): the same time of day when it has MIN_BUCKET of them, else all. NaN without MIN_BASE earlier values. pts must be sorted by time. */
    static double zOf(List<Pt> pts, int i, int m) {
        Pt q = pts.get(i); if (!eligible(q, m)) return Double.NaN;
        double tMax = q.ts - GAP_DAYS * 86400.0, tMin = tMax - BASE_DAYS * 86400.0;
        double[] all = new double[i], bk = new double[i]; int na = 0, nb = 0;
        for (int j = 0; j < i; j++) {
            Pt o = pts.get(j); if (!eligible(o, m) || o.ts > tMax || o.ts < tMin) continue;
            all[na++] = o.v[m]; if (o.bucket == q.bucket) bk[nb++] = o.v[m];
        }
        double[] use = nb >= MIN_BUCKET ? bk : all; int n = nb >= MIN_BUCKET ? nb : na;
        if (n < MIN_BASE) return Double.NaN;
        double med = TrendStats.median(use, n); double[] dev = new double[n]; for (int k = 0; k < n; k++) dev[k] = Math.abs(use[k] - med);
        double spread = Math.max(1.4826 * TrendStats.median(dev, n), FLOOR[m]);
        return (q.v[m] - med) / spread;
    }

    // ------------------------------------------------------------------ colours of the grid (ARGB)
    private static int mix(int c0, int c1, float t) {
        int a = Math.round(((c0 >> 24) & 255) * (1 - t) + ((c1 >> 24) & 255) * t), r = Math.round(((c0 >> 16) & 255) * (1 - t) + ((c1 >> 16) & 255) * t), gg = Math.round(((c0 >> 8) & 255) * (1 - t) + ((c1 >> 8) & 255) * t), b = Math.round((c0 & 255) * (1 - t) + (c1 & 255) * t);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    static int colour(double z) {
        if (Double.isNaN(z)) return 0x14FFFFFF;
        double a = Math.abs(z); float t = (float) Math.min(1.0, Math.max(0.0, (a - 1.0) / 2.0));
        if (a < EcgSeg.Z_MID) return 0x334EE3CB;                       // within one spread: faint teal
        return z > 0 ? mix(0x66F2B35B, 0xFFF2B35B, t) : mix(0x662FA8F0, 0xFF2FA8F0, t);
    }

    // ------------------------------------------------------------------ the day grid
    static final class Grid {
        int days; long[] dayStart; TimeZone tz;
        double[][] z = new double[NM][]; double[][] val = new double[NM][]; int[][] cnt = new int[NM][]; int[] n;      // [measure][day]; n = readings that day
    }

    private static long midnight(Calendar c, int addDays) {
        Calendar x = (Calendar) c.clone(); x.set(Calendar.HOUR_OF_DAY, 0); x.set(Calendar.MINUTE, 0); x.set(Calendar.SECOND, 0); x.set(Calendar.MILLISECOND, 0); x.add(Calendar.DAY_OF_YEAR, addDays);
        return x.getTimeInMillis() / 1000L;
    }

    static Grid grid(List<Pt> in, int days, long nowSec, TimeZone tz) {
        List<Pt> pts = new ArrayList<Pt>(in); Collections.sort(pts, new Comparator<Pt>() { @Override public int compare(Pt a, Pt b) { return Long.compare(a.ts, b.ts); } });
        int N = pts.size(); Grid g = new Grid(); g.days = days; g.tz = tz; g.dayStart = new long[days + 1]; g.n = new int[days];
        Calendar c = Calendar.getInstance(tz); c.setTimeInMillis(nowSec * 1000L);
        for (int d = 0; d <= days; d++) g.dayStart[d] = midnight(c, d - (days - 1));
        for (int m = 0; m < NM; m++) { g.z[m] = new double[days]; g.val[m] = new double[days]; g.cnt[m] = new int[days]; Arrays.fill(g.z[m], Double.NaN); Arrays.fill(g.val[m], Double.NaN); }
        int[] dayOf = new int[N];
        for (int i = 0; i < N; i++) { long t = pts.get(i).ts; int d = -1; for (int k = 0; k < days; k++) if (t >= g.dayStart[k] && t < g.dayStart[k + 1]) { d = k; break; } dayOf[i] = d; if (d >= 0) g.n[d]++; }
        for (int m = 0; m < NM; m++) {
            double[][] zs = new double[days][N], vs = new double[days][N]; int[] nz = new int[days], nv = new int[days];
            for (int i = 0; i < N; i++) {
                int d = dayOf[i]; if (d < 0 || !eligible(pts.get(i), m)) continue;
                vs[d][nv[d]++] = pts.get(i).v[m];
                double z = zOf(pts, i, m); if (!Double.isNaN(z)) zs[d][nz[d]++] = z;
            }
            for (int d = 0; d < days; d++) { g.cnt[m][d] = nv[d]; if (nv[d] > 0) g.val[m][d] = TrendStats.median(vs[d], nv[d]); if (nz[d] > 0) g.z[m][d] = TrendStats.median(zs[d], nz[d]); }
        }
        return g;
    }

    static boolean hasRow(Grid g, int m) { for (int d = 0; d < g.days; d++) if (!Double.isNaN(g.val[m][d])) return true; return false; }

    private static String dayName(Grid g, int d) { SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK); f.setTimeZone(g.tz); return f.format(new Date(g.dayStart[d] * 1000L + 43200000L)); }

    private static String num(int m, double v) { return Double.isNaN(v) ? "\u2014" : String.format(Locale.UK, "%.0f", v); }

    private static String zs(double z) { return (z >= 0 ? "+" : "\u2212") + String.format(Locale.UK, "%.1f", Math.abs(z)); }

    /** Text for a tapped cell. */
    static String pick(Grid g, int m, int d) {
        if (d < 0 || d >= g.days || g.cnt[m][d] == 0) return NAME[m] + "  \u00b7  " + (d >= 0 && d < g.days ? dayName(g, d) : "") + "  \u00b7  no reading";
        String s = NAME[m] + "  \u00b7  " + dayName(g, d) + "  \u00b7  " + num(m, g.val[m][d]) + " " + UNIT[m] + "  \u00b7  " + g.cnt[m][d] + (g.cnt[m][d] == 1 ? " reading" : " readings");
        return Double.isNaN(g.z[m][d]) ? s + "  \u00b7  still learning your usual" : s + "  \u00b7  " + zs(g.z[m][d]) + " spreads from your usual";
    }

    /** The line under the grid: what moved on the newest measured day and whether it held on the one before. Never a diagnosis.
     *  A segment counts when it is zHold spreads or more from your usual; it is named alone on a single day only from zNow spreads; two measured days in a row at zHold or more is "held". */
    static String watch(Grid g) {
        int L = -1;
        for (int d = g.days - 1; d >= 0 && L < 0; d--) for (int m = 0; m < NM; m++) if (WATCHED[m] && !Double.isNaN(g.z[m][d])) { L = d; break; }
        if (L < 0) return "Still learning your usual: each measure needs 5 readings taken at least 3 days earlier. Keep taking readings at the same time of day.";
        StringBuilder out = new StringBuilder("Newest measured day: " + dayName(g, L) + " (" + g.n[L] + (g.n[L] == 1 ? " reading" : " readings") + ").\n");
        List<String> single = new ArrayList<String>(), held = new ArrayList<String>(), edge = new ArrayList<String>(); int nOut = 0; double nearest = 0; int nearM = -1;
        for (int m = 0; m < NM; m++) {
            if (!WATCHED[m] || Double.isNaN(g.z[m][L])) continue;
            double zl = g.z[m][L]; if (Math.abs(zl) > Math.abs(nearest)) { nearest = zl; nearM = m; }
            if (Math.abs(zl) < zHold) continue;
            nOut++; String w = NAME[m] + " " + num(m, g.val[m][L]) + " " + UNIT[m] + " (" + (zl > 0 ? "above" : "below") + " your usual, " + zs(zl) + ")";
            int run = 1; int back = L; while (true) { int pd = -1; for (int d = back - 1; d >= 0 && pd < 0; d--) if (!Double.isNaN(g.z[m][d])) pd = d; if (pd < 0 || Math.abs(g.z[m][pd]) < zHold || g.z[m][pd] * zl <= 0) break; run++; back = pd; }
            if (run >= 2) held.add(w + " for " + run + " measured days in a row"); else if (Math.abs(zl) >= zNow) single.add(w); else edge.add(w);
        }
        boolean noisy = !Double.isNaN(g.z[NOISE][L]) && g.z[NOISE][L] >= zHold;
        if (nOut >= 3) { List<String> all = new ArrayList<String>(held); all.addAll(single); all.addAll(edge); out.append("Several segments moved together: ").append(join(all, null)).append(". Moving many at once usually means contact, posture or noise, not the heart: check the electrodes and sit the same way, then repeat."); }
        else if (!held.isEmpty()) out.append("Held: ").append(join(held, null)).append(single.isEmpty() ? "" : ". Also today: " + join(single, null)).append(". Repeat at the same time of day, seated, same arm position, to see if it stays.");
        else if (!single.isEmpty()) out.append("One day well outside your usual: ").append(join(single, null)).append(". A single day often is not a change; repeat at the same time of day.");
        else out.append("Every segment is within your usual range").append(nearM >= 0 && Math.abs(nearest) >= zHold ? " (closest to the edge: " + NAME[nearM] + " " + zs(nearest) + ")." : ".");
        if (noisy) out.append("\nThe newest reading was noisier than your usual, so read its small segments (P, ST, T) with care.");
        return out.toString();
    }

    private static String join(List<String> a, List<String> b) {
        StringBuilder s = new StringBuilder(); List<String> all = new ArrayList<String>(a); if (b != null) all.addAll(b);
        for (int i = 0; i < all.size(); i++) { if (i > 0) s.append(i == all.size() - 1 ? " and " : ", "); s.append(all.get(i)); }
        return s.toString();
    }
}
