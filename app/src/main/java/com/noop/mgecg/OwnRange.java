package com.noop.mgecg;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// FILE VERSION 0.94.0 (26 Nov 2026): noise (8 to 25 Hz, uV) and R wave (mV) join heart rate, HRV and QT: new reports carry the meta tags ecg-noise and ecg-r, the card shows a bar for each from 8 earlier readings that carry them, and qtSplit() gives the ECG card on the phone the QT of a reading and of the ones before it from the stored ECG history.
// FILE VERSION 0.91.0 (25 Nov 2026): "your numbers against your usual". The range is built only from the person's own earlier readings (grade A or B), never from a population table.
// Pure: no Android classes. HR and HRV come from the meta tags of the saved reports (ecg-hr, ecg-hrv, ecg-grade), QT from ReadingRules' stored list.
final class OwnRange {
    private OwnRange() { }

    static final int MIN = ReadingRules.BAND_MIN, MAX = ReadingRules.BAND_MAX, FILES = 120;
    /** 1.2816 = the point that leaves 10% of a bell-shaped spread on each side, so the band holds the middle 80% of readings. */
    static final double Z = 1.2816;

    /** A metric's own range: n readings behind it, median, robust spread, band edges. */
    static final class Band {
        int n; double med, sd, lo, hi;
        List<Double> pts = new ArrayList<>();
    }

    /**
     * Band from earlier values, oldest first. {@code logScale}: the band is made on ln(values) (HRV is skewed). {@code floor}: smallest spread allowed,
     * the resolution of the stored number (the values are whole numbers, so a run of identical readings must not give a zero-width band).
     * Null with fewer than MIN values.
     */
    static Band band(List<Double> vals, boolean logScale, double floor) {
        List<Double> v = new ArrayList<>();
        if (vals != null) for (Double d : vals) if (d != null && !Double.isNaN(d) && d > 0) v.add(logScale ? Math.log(d) : d);
        double[] bd = ReadingRules.band(v);
        if (bd == null) return null;
        Band b = new Band();
        b.n = (int) bd[0];
        double sd = Math.max(bd[2], logScale ? Math.log1p(floor / Math.max(1.0, Math.exp(bd[1]))) : floor);
        b.sd = sd;
        double lo = bd[1] - Z * sd, hi = bd[1] + Z * sd;
        b.med = logScale ? Math.exp(bd[1]) : bd[1];
        b.lo = logScale ? Math.exp(lo) : lo;
        b.hi = logScale ? Math.exp(hi) : hi;
        List<Double> last = new ArrayList<>(v.subList(Math.max(0, v.size() - MAX), v.size()));
        for (double d : last) b.pts.add(logScale ? Math.exp(d) : d);
        return b;
    }

    // ------------------------------------------------------------------ history from the saved reports

    /** HR (bpm) and HRV (ms) of earlier grade A/B readings, oldest first, from the report files in {@code dir}; {@code excludeName} (this reading's own file) is skipped. */
    static final class Hist { List<Double> hr = new ArrayList<>(), hrv = new ArrayList<>(), nz = new ArrayList<>(), rv = new ArrayList<>(); int files; }

    static Hist load(File dir, String excludeName) {
        Hist h = new Hist();
        if (dir == null) return h;
        File[] all = dir.listFiles();
        if (all == null) return h;
        List<File> fs = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith("ecg_report_") && n.endsWith(".html") && !n.equals(excludeName)) fs.add(f);
        }
        Collections.sort(fs, (a, b) -> Long.compare(unix(a.getName()), unix(b.getName())));
        for (File f : fs.subList(Math.max(0, fs.size() - FILES), fs.size())) {
            String head = head(f, 16000);
            if (head == null || fragment(head)) continue;
            String gr = meta(head, "ecg-grade");
            if (gr == null || !(gr.equals("A") || gr.equals("B"))) continue;
            h.files++;
            Double hr = num(meta(head, "ecg-hr")), hrv = num(meta(head, "ecg-hrv"));
            if (hr != null) h.hr.add(hr);
            if (hrv != null) h.hrv.add(hrv);
            Double nz = num(meta(head, "ecg-noise")), rr = num(meta(head, "ecg-r"));      // 0.94.0: only readings made with 0.94.0 or later carry them
            if (nz != null) h.nz.add(nz);
            if (rr != null) h.rv.add(rr / 1000.0);
        }
        return h;
    }

    private static long unix(String n) {
        try { return Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length())); } catch (Exception e) { return 0L; }
    }

    private static Double num(String s) {
        if (s == null) return null;
        try { double d = Double.parseDouble(s.trim()); return d > 0 ? d : null; } catch (Exception e) { return null; }
    }

    static String meta(String head, String name) {
        Matcher m = Pattern.compile("<meta name=\"" + Pattern.quote(name) + "\" content=\"([^\"]*)\"").matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /** The late tail of a reading ("0 of 3 seconds") is not a reading. Same rule as HealthModel.fragmentSeconds. */
    static boolean fragment(String head) {
        Matcher m = Pattern.compile("(\\d+) of (\\d+) seconds").matcher(head);
        if (!m.find()) return false;
        try { return Integer.parseInt(m.group(2)) < 20; } catch (Exception e) { return false; }
    }

    private static String head(File f, int max) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[max];
                int n = in.read(b);
                return n <= 0 ? null : new String(b, 0, n, "UTF-8");
            } finally { in.close(); }
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ the card

    private static final int H = 58, L = 6, R = 6;

    /** One range bar: grey dots = earlier readings, tinted band = the usual range, tick = median, big dot = this reading. */
    static String bar(String label, String unit, double now, Band b, int decimals) {
        double lo = Math.min(now, Math.min(b.lo, minOf(b.pts))), hi = Math.max(now, Math.max(b.hi, maxOf(b.pts)));
        double pad = Math.max((hi - lo) * 0.08, 0.5);
        lo -= pad; hi += pad;
        final int w = EcgReport.W;
        double px = w - L - R;
        StringBuilder s = new StringBuilder(EcgReport.svgOpen(H, label + ": this reading against your usual range"));
        double yBand = 36;
        s.append("<line x1=\"").append(L).append("\" y1=\"").append(EcgReport.f(yBand)).append("\" x2=\"").append(w - R).append("\" y2=\"").append(EcgReport.f(yBand)).append("\" class=\"gl\"/>");
        double x1 = L + (b.lo - lo) / (hi - lo) * px, x2 = L + (b.hi - lo) / (hi - lo) * px;
        s.append("<rect x=\"").append(EcgReport.f(x1)).append("\" y=\"").append(EcgReport.f(yBand - 9)).append("\" width=\"").append(EcgReport.f(Math.max(2, x2 - x1))).append("\" height=\"18\" rx=\"9\" style=\"fill:var(--blue);fill-opacity:.22\"/>");
        for (double p : b.pts) s.append("<circle cx=\"").append(EcgReport.f(L + (p - lo) / (hi - lo) * px)).append("\" cy=\"").append(EcgReport.f(yBand)).append("\" r=\"2.2\" style=\"fill:var(--grey)\"/>");
        double xm = L + (b.med - lo) / (hi - lo) * px;
        s.append("<line x1=\"").append(EcgReport.f(xm)).append("\" y1=\"").append(EcgReport.f(yBand - 12)).append("\" x2=\"").append(EcgReport.f(xm)).append("\" y2=\"").append(EcgReport.f(yBand + 12)).append("\" style=\"stroke:var(--blue);stroke-width:2\"/>");
        double xn = L + (now - lo) / (hi - lo) * px;
        boolean inside = now >= b.lo && now <= b.hi;
        s.append("<circle cx=\"").append(EcgReport.f(xn)).append("\" cy=\"").append(EcgReport.f(yBand)).append("\" r=\"7\" style=\"fill:").append(inside ? "var(--teal)" : "var(--violet)").append(";stroke:var(--bg);stroke-width:2\"/>");
        String fmt = "%." + decimals + "f";
        String t = String.format(Locale.US, fmt, now);
        s.append(EcgReport.text(Math.max(14, Math.min(w - 14, xn)), 14, t, "wave", "middle"));
        s.append(EcgReport.text(Math.max(L, x1), 56, String.format(Locale.US, fmt, b.lo), "axis", "middle"));
        s.append(EcgReport.text(Math.min(w - R, x2), 56, String.format(Locale.US, fmt, b.hi), "axis", "middle"));
        s.append("</svg>");
        return s.toString();
    }

    private static double minOf(List<Double> v) { double m = Double.POSITIVE_INFINITY; for (double d : v) if (d < m) m = d; return m; }
    private static double maxOf(List<Double> v) { double m = Double.NEGATIVE_INFINITY; for (double d : v) if (d > m) m = d; return m; }

    private static String where(double now, Band b, String unit, int decimals) {
        String fmt = "%." + decimals + "f";
        if (now < b.lo) return String.format(Locale.US, "below your usual " + fmt + " to " + fmt + " " + unit, b.lo, b.hi);
        if (now > b.hi) return String.format(Locale.US, "above your usual " + fmt + " to " + fmt + " " + unit, b.lo, b.hi);
        return String.format(Locale.US, "inside your usual " + fmt + " to " + fmt + " " + unit, b.lo, b.hi);
    }

    /**
     * The card for the report. NaN = this reading has no value for that metric. {@code pastQt}: QT of earlier readings (oldest first) or null when never loaded.
     * Never invents a range: with fewer than MIN earlier readings of a metric it says how many there are.
     */
    static String card(double hrNow, double hrvNow, double qtNow, File dir, String excludeName, List<Double> pastQt) { return card(hrNow, hrvNow, qtNow, Double.NaN, Double.NaN, dir, excludeName, pastQt); }

    /** 0.94.0: with noise (uV, 8 to 25 Hz) and R wave (mV) of this reading; NaN = none. */
    static String card(double hrNow, double hrvNow, double qtNow, double nzNow, double rNow, File dir, String excludeName, List<Double> pastQt) {
        Hist h = load(dir, excludeName);
        Band bh = band(h.hr, false, 1.0), bv = band(h.hrv, true, 1.0), bq = band(trimOwn(pastQt, qtNow), false, 1.0), bn = band(h.nz, false, 0.5), br = band(h.rv, false, 0.02);
        StringBuilder s = new StringBuilder("<section class=\"card\"><div class=\"k\">Your numbers vs your usual</div>");
        boolean any = false;
        StringBuilder rows = new StringBuilder();
        if (bn != null && !Double.isNaN(nzNow)) { any = true; rows.append(item("Noise, 8 to 25 Hz", "&micro;V", nzNow, bn, 0)); }
        if (bh != null && !Double.isNaN(hrNow)) { any = true; rows.append(item("Heart rate", "bpm", hrNow, bh, 0)); }
        if (bv != null && !Double.isNaN(hrvNow)) { any = true; rows.append(item("HRV (RMSSD)", "ms", hrvNow, bv, 0)); }
        if (bq != null && !Double.isNaN(qtNow)) { any = true; rows.append(item("QT", "ms", qtNow, bq, 0)); }
        if (br != null && !Double.isNaN(rNow)) { any = true; rows.append(item("R wave", "mV", rNow, br, 2)); }
        if (any) {
            s.append("<p class=\"hint\">Built only from your own earlier readings of grade A or B: the shaded band holds the middle 80% of them. Grey dots are those readings.</p>").append(rows);
            if ((bn != null && !Double.isNaN(nzNow)) || (br != null && !Double.isNaN(rNow)))
                s.append("<p class=\"cap\">R wave and noise are in &micro;V as the scale setting read them when each reading was made; the bars assume the scale did not change between these readings (the Trends screen re-bases for a change of scale).</p>");
            if ((bn == null && !Double.isNaN(nzNow)) || (br == null && !Double.isNaN(rNow)))
                s.append("<p class=\"cap\">Noise and R wave bars appear after ").append(MIN).append(" readings made with this version. So far: ").append(h.nz.size()).append(" for noise, ").append(h.rv.size()).append(" for R wave.</p>");
            s.append("<p class=\"cap\">Outside the band is not a warning. It only says this reading differs from your usual ones, and a single reading varies with posture, breathing and time of day.</p>");
        } else {
            s.append("<p class=\"cap\">Your usual range appears after ").append(MIN).append(" readings of grade A or B. So far: ")
                    .append(h.hr.size()).append(" for heart rate, ").append(h.hrv.size()).append(" for HRV, ").append(bq == null ? (pastQt == null ? 0 : pastQt.size()) : bq.n)
                    .append(" for QT, ").append(h.nz.size()).append(" for noise, ").append(h.rv.size()).append(" for R wave. No average of other people is used in its place.</p>");
        }
        return s.append("</section>").toString();
    }

    private static String item(String label, String unit, double now, Band b, int dec) {
        return "<h3>" + label + "</h3>" + bar(label, unit, now, b, dec)
                + "<p class=\"cap\" style=\"margin-top:2px\">" + String.format(Locale.US, "%." + dec + "f", now) + " " + unit + " is " + where(now, b, unit, dec)
                + String.format(Locale.US, " (median %." + dec + "f, from %d readings).", b.med, b.n) + "</p>";
    }

    /** 0.94.0: the stored QT (ms, as recorded) of one reading and of the readings before it, oldest first. {@code starts}/{@code qts}: the stored ECG history (start unix second and qtMs of each entry, any order, qtMs 0 or NaN = none). */
    static final class QtSplit { double now = Double.NaN; List<Double> before = new ArrayList<>(); }

    static QtSplit qtSplit(long startUnix, long[] starts, double[] qts) {
        QtSplit o = new QtSplit();
        if (starts == null || qts == null || startUnix <= 0) return o;
        int m = Math.min(starts.length, qts.length);
        List<long[]> idx = new ArrayList<>();
        for (int i = 0; i < m; i++) if (qts[i] > 0 && !Double.isNaN(qts[i]) && !Double.isInfinite(qts[i])) idx.add(new long[] { starts[i], i });
        Collections.sort(idx, (x, y) -> Long.compare(x[0], y[0]));
        for (long[] e : idx) {
            if (e[0] == startUnix) o.now = qts[(int) e[1]];
            else if (e[0] < startUnix) o.before.add(qts[(int) e[1]]);
        }
        return o;
    }

    /** The stored QT list may already hold this reading (a report written again): drop a last value equal to this reading's QT. */
    static List<Double> trimOwn(List<Double> past, double qtNow) {
        if (past == null) return null;
        List<Double> c = new ArrayList<>(past);
        if (!c.isEmpty() && !Double.isNaN(qtNow) && Math.abs(c.get(c.size() - 1) - qtNow) < 0.05) c.remove(c.size() - 1);
        return c;
    }

    static String describe(Band b) { return b == null ? "null" : Arrays.toString(new double[] { b.n, b.med, b.sd, b.lo, b.hi }); }
}
