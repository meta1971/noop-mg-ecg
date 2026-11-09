package com.noop.mgecg;

// FILE VERSION 0.49.0 (9 Nov 2026): the data of the Trends screen: one series per metric, the nights (from the strap's sleep records) and the ECG readings (from the saved reports, with the live notes for noise).
// Every series carries what the screen needs to judge it against the person's own usual: which direction is better (or neutral), the smallest spread worth believing, the smallest slope worth calling a trend.

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

final class TrendSeries {
    private TrendSeries() {}

    static final int MORNING = 0, AFTERNOON = 1, EVENING = 2;
    static final String[] BUCKET = { "morning", "afternoon", "evening" };

    static final class Series {
        String name, unit, group, key; int dec, color, good;          // good: +1 higher is better, -1 lower is better, 0 neither
        double minSpread, slopeUnit;
        double[] day = new double[0], v = new double[0]; long[] ts = new long[0]; int[] bucket = new int[0];     // day = unix days; ts = unix seconds; bucket only for ECG (-1 for nights)
        boolean ecg;
        Series(String name, String unit, String group, int dec, int color, int good, double minSpread, double slopeUnit, boolean ecg) { this.name = name; this.unit = unit; this.group = group; this.dec = dec; this.color = color; this.good = good; this.minSpread = minSpread; this.slopeUnit = slopeUnit; this.ecg = ecg; this.key = name; }
        int size() { return v.length; }
        /** The same series cut to one time of day (ECG only). */
        Series onlyBucket(int b) {
            Series s = new Series(name, unit, group, dec, color, good, minSpread, slopeUnit, ecg); int n = 0; for (int x : bucket) if (x == b) n++;
            s.day = new double[n]; s.v = new double[n]; s.ts = new long[n]; s.bucket = new int[n]; int k = 0;
            for (int i = 0; i < v.length; i++) if (bucket[i] == b) { s.day[k] = day[i]; s.v[k] = v[i]; s.ts[k] = ts[i]; s.bucket[k] = b; k++; }
            return s;
        }
    }

    static final class EcgPoint { long ts; String grade = "-"; double hr = Double.NaN, rmssd = Double.NaN, R = Double.NaN, QRS = Double.NaN, QTcF = Double.NaN, noise = Double.NaN; int bucket; boolean good() { return grade.startsWith("A") || grade.startsWith("B"); } }

    static int bucketOf(long tsSec) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(tsSec * 1000L); int h = c.get(Calendar.HOUR_OF_DAY); return h < 12 ? MORNING : h < 18 ? AFTERNOON : EVENING;
    }

    // ------------------------------------------------------------------ nights
    static List<Series> nights(HealthModel.Data d) {
        List<TodayModel.NightStat> all = Trends.allNights(d);
        // name, unit, decimals, colour, good direction, min spread, slope unit (per week)
        Object[][] def = {
            { "Recovery", "%", 0, 0xFF5CE5A0, 1, 6.0, 2.0 }, { "Resting HR", "bpm", 0, 0xFFFF7A93, -1, 1.5, 0.7 }, { "HRV", "ms", 0, 0xFF4CB4FF, 1, 3.0, 1.5 },
            { "Sleep", "h", 1, 0xFF9AA0F0, 1, 0.4, 0.25 }, { "Breathing", "per min", 1, 0xFF2EE0B8, 0, 0.4, 0.2 }, { "Skin temp", "\u00b0C", 1, 0xFFF2B35B, 0, 0.25, 0.1 }, { "Blood oxygen", "%", 0, 0xFF8E93E8, 1, 0.6, 0.3 } };
        List<Series> out = new ArrayList<>();
        for (int m = 0; m < def.length; m++) {
            Series s = new Series((String) def[m][0], (String) def[m][1], "NIGHTS", (Integer) def[m][2], (Integer) def[m][3], (Integer) def[m][4], (Double) def[m][5], (Double) def[m][6], false);
            double[] v = Trends.series(all, m); int n = 0; for (double x : v) if (!Double.isNaN(x)) n++;
            s.day = new double[n]; s.v = new double[n]; s.ts = new long[n]; s.bucket = new int[n]; int k = 0;
            for (int i = 0; i < v.length; i++) if (!Double.isNaN(v[i])) { s.day[k] = all.get(i).bed / 86400.0; s.v[k] = v[i]; s.ts[k] = all.get(i).bed; s.bucket[k] = -1; k++; }
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ ECG readings
    /** One point per saved reading, oldest first. Reads the report files: call off the main thread. */
    static List<EcgPoint> loadEcg(Context c, HealthModel.Data d, int max) {
        List<EcgPoint> out = new ArrayList<>(); File dir = c.getExternalFilesDir(null);
        Map<Long, Map<String, String>> notes = ReadingNotes.all(dir); Long[] keys = notes.keySet().toArray(new Long[0]);
        int cnt = 0;
        for (HealthModel.Reading rd : d.readings) {
            if (cnt++ >= max) break; if (rd.file == null) continue;
            String n = rd.file.getName(); long ts; try { ts = Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length())); } catch (Exception e) { continue; }
            ReadingsTable.Row r = new ReadingsTable.Row(); ReadingsTable.fromText(r, ReadingsTable.plain(rd.file));
            if (Double.isNaN(r.hr)) r.hr = ReadingsTable.grab(rd.hr == null ? "" : rd.hr, "(\\d+(?:\\.\\d+)?)");
            if (Double.isNaN(r.rmssd)) r.rmssd = ReadingsTable.grab(rd.hrv == null ? "" : rd.hrv, "(\\d+(?:\\.\\d+)?)");
            if (r.grade.equals("-")) r.grade = rd.grade == null ? "-" : rd.grade;
            EcgPoint p = new EcgPoint(); p.ts = ts; p.grade = r.grade; p.hr = r.hr; p.rmssd = r.rmssd; p.R = r.R; p.QRS = r.QRS; p.QTcF = r.QTcF; p.bucket = bucketOf(ts);
            Map<String, String> nt = null; long bd = 31; for (Long k : keys) { long dd = Math.abs(k - ts); if (dd < bd) { bd = dd; nt = notes.get(k); } }
            if (nt != null) {
                p.noise = ReadingsTable.grab(nt.get("noise_8_25") == null ? "" : nt.get("noise_8_25"), "([\\d.]+)");
                if (Double.isNaN(p.R)) p.R = ReadingsTable.grab(nt.get("live_R_uV") == null ? "" : nt.get("live_R_uV"), "([\\d.]+)");
                if (Double.isNaN(p.QRS)) p.QRS = ReadingsTable.grab(nt.get("live_QRS_ms") == null ? "" : nt.get("live_QRS_ms"), "([\\d.]+)");
            }
            out.add(p);
        }
        java.util.Collections.sort(out, new java.util.Comparator<EcgPoint>() { @Override public int compare(EcgPoint a, EcgPoint b) { return Long.compare(a.ts, b.ts); } });
        return out;
    }

    static List<Series> ecg(List<EcgPoint> pts) {
        // name, unit, decimals, colour, good direction, min spread, slope unit
        Object[][] def = {
            { "ECG HRV", "ms", 0, 0xFF4CB4FF, 1, 4.0, 1.5 }, { "ECG heart rate", "bpm", 0, 0xFFFF7A93, -1, 2.5, 1.0 }, { "QRS", "ms", 0, 0xFF2EE0B8, 0, 4.0, 1.5 },
            { "QTc", "ms", 0, 0xFF9AA0F0, 0, 8.0, 3.0 }, { "R height", "\u00b5V", 0, 0xFFF2B35B, 0, 60.0, 25.0 }, { "Signal noise", "\u00b5V", 0, 0xFF8E93E8, -1, 4.0, 2.0 } };
        List<Series> out = new ArrayList<>();
        for (int m = 0; m < def.length; m++) {
            Series s = new Series((String) def[m][0], (String) def[m][1], "ECG READINGS", (Integer) def[m][2], (Integer) def[m][3], (Integer) def[m][4], (Double) def[m][5], (Double) def[m][6], true);
            List<EcgPoint> use = new ArrayList<>();
            for (EcgPoint p : pts) { double x = valueOf(p, m); if (Double.isNaN(x) || x <= 0) continue; if (m != 5 && !p.good()) continue; use.add(p); }      // measures need a grade A or B reading; the noise of any reading counts
            int n = use.size(); s.day = new double[n]; s.v = new double[n]; s.ts = new long[n]; s.bucket = new int[n];
            for (int i = 0; i < n; i++) { EcgPoint p = use.get(i); s.day[i] = p.ts / 86400.0; s.v[i] = valueOf(p, m); s.ts[i] = p.ts; s.bucket[i] = p.bucket; }
            out.add(s);
        }
        return out;
    }

    private static double valueOf(EcgPoint p, int m) { switch (m) { case 0: return p.rmssd; case 1: return p.hr; case 2: return p.QRS; case 3: return p.QTcF; case 4: return p.R; default: return p.noise; } }

    /** The personal result for one series: for ECG metrics against the usual of the SAME time of day as the newest reading when that time of day has at least 8 readings (the amplitude and the HRV both change with the clock). ctx names it ("evening readings") or is empty. */
    static final class Judged { TrendStats.Result r; Series used; String ctx = ""; int nAll; }

    static Judged judge(Series s, double winDays) {
        Judged j = new Judged(); j.used = s;
        if (s.ecg && s.size() > 0) {
            int b = s.bucket[s.size() - 1]; Series sb = s.onlyBucket(b);
            if (sb.size() >= 8) { j.used = sb; j.ctx = BUCKET[b] + " readings"; }
        }
        j.r = TrendStats.analyse(j.used.day, j.used.v, winDays, 60.0, s.minSpread, s.slopeUnit);
        if (s.size() > 0) { double tEnd = s.day[s.size() - 1]; for (int i = 0; i < s.size(); i++) if (s.day[i] >= tEnd - winDays) j.nAll++; }      // every point of the series in the window, whatever time of day the usual was taken from
        return j;
    }
}
