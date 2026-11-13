package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): every microvolt figure is shown at the ECG scale in use now (ScaleSetting; each reading stores the scale it was made with, older ones are re-based), the scale tag, and the context of every reading without a tap (hours awake, previous 24 h asleep, steps 30 min, minutes since a carb meal, HR against resting) with the band of your own similar readings. 0.42.0 (5 Nov 2026): this week and the streak, and your own typical range with the latest reading beside it, at the top of the screen. 0.41.0 (5 Nov 2026): the CSV also carries the strap's verdict, average HR, HRV release, the full event 118 / 119 numbers, the live noise by band and the notes of the reading page (where, run, why noisy). 0.33.0 (1 Nov 2026): "Your readings": one row per saved ECG reading with the measurements from its report (R, T, P, QRS, QTc, ST), the strap's own end-of-reading amplitude (event 118),
// and the context the strap recorded around it (steps in the previous hour, still share, skin temperature, wrist orientation), plus the Polar H10 chest R when its ECG file covers the reading.
// Plotted against the clock hour, and exported as one CSV (Download/MGreports). Measurements are single-lead and informal; not a diagnosis.

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.noop.mgecg.HealthViews.*;

final class ReadingsTable {
    private ReadingsTable() {}

    /** Your own R line from 23 flat-wrist readings (1 Nov 2026): R (uV) = LINE_A + LINE_B x hours since waking; leave-one-out error 61 uV. Personal to this person and this strap. */
    static final double LINE_A = 450.0, LINE_B = 31.0;

    static final class Row {
        long ts; String grade = "-"; double hr = Double.NaN, rmssd = Double.NaN, dur = Double.NaN, R = Double.NaN, T = Double.NaN, P = Double.NaN, QRS = Double.NaN, QT = Double.NaN, QTcF = Double.NaN, ST = Double.NaN;
        double E = Double.NaN, steps1h = Double.NaN, still30 = Double.NaN, skin = Double.NaN, gravZ = Double.NaN, h10R = Double.NaN, clock = Double.NaN;
        double evB = Double.NaN, evC = Double.NaN, evD = Double.NaN, evN = Double.NaN, evN1 = Double.NaN, evN2 = Double.NaN, e119n2 = Double.NaN, e119q3 = Double.NaN;      // 0.41.0: event 118 / 119 numbers
        java.util.Map<String, String> nt = new java.util.HashMap<String, String>();                                                                              // 0.41.0: reading_notes.csv
        double uvThen = ScaleSetting.OLD, steps30 = Double.NaN, sleep24 = Double.NaN, carbMin = Double.NaN, rhrDev = Double.NaN;      // 0.54.0
        String tag = ""; double hoursAwake = Double.NaN, expectedR = Double.NaN, rVsLine = Double.NaN;      // 0.37.0: posture and strap position tag, hours since the last sleep second, your own line R = 450 + 31 x hours awake, the percent difference from it
    }

    // ------------------------------------------------------------------ report text
    static String plain(File f) {
        try {
            byte[] b; try (FileInputStream in = new FileInputStream(f)) { b = new byte[(int) f.length()]; int o = 0; while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) break; o += r; } }
            String s = new String(b, "UTF-8");
            s = s.replaceAll("(?s)<style.*?</style>", " ").replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<svg.*?</svg>", " ").replaceAll("<[^>]+>", " ");
            s = s.replace("&micro;", "\u00b5").replace("&plusmn;", "\u00b1").replace("&times;", "\u00d7").replace("&ndash;", "\u2013").replace("&middot;", "\u00b7").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
            return s.replaceAll("\\s+", " ");
        } catch (Throwable t) { return ""; }
    }

    static double grab(String t, String re) { Matcher m = Pattern.compile(re).matcher(t); if (!m.find()) return Double.NaN; try { return Double.parseDouble(m.group(1)); } catch (Exception e) { return Double.NaN; } }

    /** Fills the report measurements of a row from the report text. */
    static void fromText(Row r, String t) {
        Matcher g = Pattern.compile("Grade ([A-D])").matcher(t); if (g.find()) r.grade = g.group(1);
        double m = grab(t, "Your reading .*? \u00b7 (\\d+) min"), s = grab(t, "min (\\d+) s"); if (!Double.isNaN(m)) r.dur = m * 60 + (Double.isNaN(s) ? 0 : s);
        r.hr = grab(t, "Heart rate (\\d+) (?:bpm|per minute)"); r.rmssd = grab(t, "HRV \\(RMSSD\\) (\\d+) ms");
        double rs = grab(t, "R wave height ([\\d.]+) \u00b5V standard"); if (Double.isNaN(rs)) rs = grab(t, "R wave height ([\\d.]+) \u00b5V"); if (Double.isNaN(rs)) { double mv = grab(t, "R wave height ([\\d.]+) mV"); if (!Double.isNaN(mv)) rs = mv * 1000.0; }
        r.R = rs; r.QRS = grab(t, "QRS duration (\\d+) ms"); r.QT = grab(t, "QT interval Experimental (\\d+) ms"); r.QTcF = grab(t, "QTc Fridericia (\\d+) ms"); r.P = grab(t, "P wave seen, (\\d+) \u00b5V");
        r.ST = grab(t, "ST level, 60 ms after J ([+-]?\\d+) \u00b5V"); r.T = grab(t, "T wave (\\d+) \u00b5V");
    }

    // ------------------------------------------------------------------ H10 chest R amplitude
    private static double[] movAvg(double[] y, int w) {
        int n = y.length; double[] out = new double[n]; double[] ps = new double[n + 1]; for (int i = 0; i < n; i++) ps[i + 1] = ps[i] + y[i];
        for (int i = 0; i < n; i++) { int a = Math.max(0, i - w / 2), b = Math.min(n, i + w / 2 + 1); out[i] = (ps[b] - ps[a]) / (b - a); } return out;
    }

    /** Median single-beat R height (uV) of 130 Hz H10 ECG: 1 s baseline removed, 3-sample smoothing, derivative-energy beat finder; NaN with fewer than 30 beats. */
    static double h10Amp(double[] y) {
        int n = y.length; if (n < 130 * 30) return Double.NaN;
        double[] base = movAvg(y, 130), x = new double[n]; for (int i = 0; i < n; i++) x[i] = y[i] - base[i];
        double[] s = movAvg(x, 3), e = new double[n]; for (int i = 1; i < n; i++) { double d = s[i] - s[i - 1]; e[i] = d * d; }
        double[] es = movAvg(e, 9), srt = es.clone(); Arrays.sort(srt); double thr = 0.3 * srt[(int) (0.995 * (n - 1))];
        List<Double> amps = new ArrayList<Double>(); int last = -100;
        for (int i = 40; i < n - 40; i++) {
            if (es[i] < thr || i - last < 40) continue; boolean mx = true; for (int k = -15; k <= 15; k++) if (es[i + k] > es[i]) { mx = false; break; } if (!mx) continue;
            int pos = i; double best = -1e18; for (int k = -12; k <= 12; k++) if (s[i + k] > best) { best = s[i + k]; pos = i + k; }
            double[] bl = new double[7]; for (int k = 0; k < 7; k++) bl[k] = s[pos - 14 + k]; Arrays.sort(bl); amps.add(best - bl[3]); last = i;
        }
        if (amps.size() < 30) return Double.NaN; Collections.sort(amps); return amps.get(amps.size() / 2);
    }

    private static double h10R(File dir, long startSec, double durSec) {
        try {
            List<File> files = ProReportMaker.findH10All(dir, startSec, durSec); if (files.isEmpty()) return Double.NaN;
            long a = startSec * 1000L - 1500L, b = (long) ((startSec + durSec) * 1000L); double[] buf = new double[(int) (durSec * 140) + 400]; int c = 0;
            for (File best : files) {
                if (best.lastModified() < startSec * 1000L) continue;
                try (BufferedReader br = new BufferedReader(new FileReader(best), 1 << 16)) {
                    String ln = br.readLine();
                    while ((ln = br.readLine()) != null) { int i1 = ln.indexOf(','); if (i1 < 0) continue; long ms; try { ms = Long.parseLong(ln.substring(0, i1)); } catch (Exception e) { continue; } if (ms < a) continue; if (ms > b) break; int i2 = ln.lastIndexOf(','); if (c < buf.length) buf[c++] = Double.parseDouble(ln.substring(i2 + 1)); }
                }
            }
            return h10Amp(Arrays.copyOf(buf, c));
        } catch (Throwable t) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ context from the day logger
    private static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    private static void context(Context c, Row r) {
        try {
            Cursor cu = DayLog.db(c).rawQuery("SELECT ts, steps, state, skin, substr(rec,54,4) FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] { String.valueOf(r.ts - 3600L), String.valueOf(r.ts + (long) (Double.isNaN(r.dur) ? 120 : r.dur)) });
            long lastTs = -1; int lastSteps = 0; double st1 = 0, st30 = 0; int still = 0, tot = 0; List<Double> sk = new ArrayList<Double>(), gz = new ArrayList<Double>();
            try {
                while (cu.moveToNext()) {
                    long t = cu.getLong(0); int sp = cu.getInt(1), s = cu.getInt(2); double skin = cu.getDouble(3); byte[] g = cu.getBlob(4);
                    if (t < r.ts) { if (lastTs >= 0 && t - lastTs == 1) { int d = sp - lastSteps; if (d > 0 && d < 50) { st1 += d; if (t >= r.ts - 1800L) st30 += d; } } if (t >= r.ts - 1800L) { tot++; if (s >= 1) still++; } if (t >= r.ts - 600L && skin > 10) sk.add(skin); }
                    else if (g != null && g.length == 4) gz.add((double) ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN).getFloat());
                    lastTs = t; lastSteps = sp;
                }
            } finally { cu.close(); }
            r.steps1h = st1; r.steps30 = tot > 300 ? st30 : Double.NaN; r.still30 = tot > 300 ? (double) still / tot : Double.NaN; r.skin = median(sk); r.gravZ = median(gz);
            try { Cursor sc = DayLog.db(c).rawQuery("SELECT MAX(ts) FROM r18 WHERE state = 2 AND ts BETWEEN ? AND ?", new String[] { String.valueOf(r.ts - 30L * 3600L), String.valueOf(r.ts) });
                try { if (sc.moveToFirst() && !sc.isNull(0)) { double h = (r.ts - sc.getLong(0)) / 3600.0; if (h >= 0 && h <= 30) r.hoursAwake = h; } } finally { sc.close(); } } catch (Throwable ignored) { }
            if (!Double.isNaN(r.hoursAwake)) { r.expectedR = (LINE_A + LINE_B * r.hoursAwake) * ScaleSetting.uv() / ScaleSetting.OLD; if (!Double.isNaN(r.R)) r.rVsLine = 100.0 * (r.R / r.expectedR - 1.0); }
            Cursor ec = DayLog.db(c).rawQuery("SELECT ts, inner FROM strap_events WHERE id = 118 AND ts BETWEEN ? AND ? ORDER BY ts LIMIT 1", new String[] { String.valueOf(r.ts), String.valueOf(r.ts + 900L) });
            long evTs = -1;
            try { if (ec.moveToFirst()) { evTs = ec.getLong(0); byte[] b = hexBytes(ec.getString(1)); if (b != null && b.length >= 31 && b[0] == 2) {
                int e = StrapEvents.u16(b, 17); if (e > 0 && e < 5000) r.E = e;
                r.evB = StrapEvents.u32(b, 9); r.evC = StrapEvents.u16(b, 13); r.evD = StrapEvents.u16(b, 15); r.evN = StrapEvents.u32(b, 19); r.evN1 = StrapEvents.u32(b, 23); r.evN2 = StrapEvents.u32(b, 27); } } } finally { ec.close(); }
            if (evTs >= 0) {
                Cursor e9 = DayLog.db(c).rawQuery("SELECT inner FROM strap_events WHERE id = 119 AND ts BETWEEN ? AND ? ORDER BY ts LIMIT 1", new String[] { String.valueOf(evTs - 2L), String.valueOf(evTs + 2L) });
                try { if (e9.moveToFirst()) { byte[] b = hexBytes(e9.getString(0)); if (b != null && b.length >= 37 && b[0] == 1) { r.e119n2 = StrapEvents.u32(b, 9); r.e119q3 = StrapEvents.f32(b, 33); } } } finally { e9.close(); }
            }
        } catch (Throwable ignored) { }
    }

    /** 0.54.0: previous-24-h sleep, minutes since the last carb-meal marker (12 h), and the heart rate against your resting HR (median of the lowest five minutes of the last 8 nights, at least 3 nights). */
    private static void context2(Context c, Row r) {
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT COUNT(*), SUM(CASE WHEN state = 2 THEN 1 ELSE 0 END) FROM r18 WHERE ts BETWEEN ? AND ?", new String[] { String.valueOf(r.ts - 86400L), String.valueOf(r.ts) });
            try { if (q.moveToFirst() && q.getInt(0) >= 72000) r.sleep24 = q.getInt(1) / 3600.0; } finally { q.close(); }
        } catch (Throwable ignored) { }
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT MAX(ms) FROM events WHERE kind = 'marker' AND text = 'carb_meal' AND ms BETWEEN ? AND ?", new String[] { String.valueOf(r.ts * 1000L - 12L * 3600000L), String.valueOf(r.ts * 1000L) });
            try { if (q.moveToFirst() && !q.isNull(0)) r.carbMin = (r.ts * 1000L - q.getLong(0)) / 60000.0; } finally { q.close(); }
        } catch (Throwable ignored) { }
        try {
            if (!Double.isNaN(r.hr)) {
                List<Double> lows = new ArrayList<Double>();
                Cursor q = DayLog.db(c).rawQuery("SELECT hr_low5 FROM night_stats WHERE bed BETWEEN ? AND ? AND hr_low5 > 30", new String[] { String.valueOf(r.ts - 8L * 86400L), String.valueOf(r.ts) });
                try { while (q.moveToNext()) lows.add(q.getDouble(0)); } finally { q.close(); }
                if (lows.size() >= 3) r.rhrDev = r.hr - median(lows);
            }
        } catch (Throwable ignored) { }
    }

    /** The reading's context as the table and the trend bands use it. */
    static ReadingContext.Ctx ctxOf(Row r) {
        ReadingContext.Ctx x = new ReadingContext.Ctx();
        x.hoursAwake = r.hoursAwake; x.sleepPrevH = r.sleep24; x.steps30 = r.steps30; x.carbMin = r.carbMin; x.rhrDev = r.rhrDev;
        return x;
    }

    private static byte[] hexBytes(String h) { if (h == null || h.length() < 2) return null; int n = h.length() / 2; byte[] b = new byte[n]; try { for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16); } catch (NumberFormatException e) { return null; } return b; }

    /** One row per reading, newest first (call off the main thread). */
    static List<Row> load(Context c, HealthModel.Data d, int max) {
        List<Row> out = new ArrayList<Row>(); File dir = c.getExternalFilesDir(null); Calendar cal = Calendar.getInstance(); int cnt = 0;
        for (HealthModel.Reading rd : d.readings) {
            if (cnt++ >= max) break; if (rd.file == null) continue;
            String n = rd.file.getName(); long ts; try { ts = Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length())); } catch (Exception e) { continue; }
            Row r = new Row(); r.ts = ts; fromText(r, plain(rd.file)); if (Double.isNaN(r.hr)) { r.hr = parse(rd.hr); } if (Double.isNaN(r.rmssd)) { r.rmssd = parse(rd.hrv); } if (r.grade.equals("-")) r.grade = rd.grade;
            cal.setTimeInMillis(ts * 1000L); r.clock = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
            if (dir != null) { r.nt = ReadingNotes.forReading(dir, ts); }
            String un = r.nt == null ? null : r.nt.get("uv_per_count");      // 0.54.0: re-base the counts-linear figures (R, T, P, ST) to the scale in use now; E and the H10 are not made from counts
            try { if (un != null && un.length() > 0) { double t0 = Double.parseDouble(un.trim()); if (t0 > 0.02 && t0 < 0.2) r.uvThen = t0; } } catch (NumberFormatException ignored) { }
            double fct = ScaleSetting.factorFrom(un); if (Math.abs(fct - 1.0) > 1e-9) { r.R *= fct; r.T *= fct; r.P *= fct; r.ST *= fct; }
            context(c, r); context2(c, r); if (dir != null) { r.h10R = h10R(dir, ts, Double.isNaN(r.dur) ? 120 : r.dur); r.tag = ReadingTag.forReading(dir, ts); }
            out.add(r);
        }
        return out;
    }
    private static double parse(String s) { return grab(s == null ? "" : s, "(\\d+(?:\\.\\d+)?)"); }

    static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("time_local,grade,duration_s,hr,rmssd_ms,R_uV,T_uV,P_uV,QRS_ms,QT_ms,QTc_fridericia_ms,ST_uV,strap_E_uV,steps_previous_hour,still_share_previous_30min,skin_c,gravity_z,h10_R_uV,strap_over_h10,tag,hours_awake,expected_R_uV_your_line,R_vs_line_pct,result_code,strap_avg_hr,verdict_s,hrv_first_s,hrv_first_ms,ev_B_ms,ev_C,ev_D,ev_N,ev_n1,ev_n2,ev119_n2,ev119_q3_pct,where,position,run,why_noisy,stop,noise_0p5_5_uV,noise_8_25_uV,noise_25_48_uV,noise_mains_uV,noise_52_100_uV,contact_z,live_R_uV,live_QRS_ms,live_dip_uV,live_QT_ms,beats_live,app,scale_uv_per_count_reading,scale_uv_per_count_now,scale_provisional,asleep_previous_24h_h,steps_previous_30min,minutes_since_carb_meal,hr_minus_resting_bpm\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK);
        for (Row r : rows) { sb.append(f.format(new Date(r.ts * 1000L))).append(',').append(r.grade).append(',').append(c0(r.dur)).append(',').append(c0(r.hr)).append(',').append(c0(r.rmssd)).append(',').append(c0(r.R)).append(',').append(c0(r.T)).append(',').append(c0(r.P)).append(',').append(c0(r.QRS)).append(',').append(c0(r.QT)).append(',').append(c0(r.QTcF)).append(',').append(c0(r.ST)).append(',').append(c0(r.E)).append(',').append(c0(r.steps1h)).append(',').append(c2(r.still30)).append(',').append(c1(r.skin)).append(',').append(c2(r.gravZ)).append(',').append(c0(r.h10R)).append(',').append(Double.isNaN(r.R) || Double.isNaN(r.h10R) ? "" : String.format(Locale.US, "%.2f", r.R / r.h10R)).append(',').append(r.tag.replace(',', ' ')).append(',').append(c1(r.hoursAwake)).append(',').append(c0(r.expectedR)).append(',').append(c1(r.rVsLine));
            for (String k : NOTE_KEYS_A) sb.append(',').append(nt(r, k));
            sb.append(',').append(c0(r.evB)).append(',').append(c0(r.evC)).append(',').append(c0(r.evD)).append(',').append(c0(r.evN)).append(',').append(c0(r.evN1)).append(',').append(c0(r.evN2)).append(',').append(c0(r.e119n2)).append(',').append(c1(r.e119q3));
            for (String k : NOTE_KEYS_B) sb.append(',').append(nt(r, k));
            sb.append(',').append(String.format(Locale.US, "%.5f", r.uvThen)).append(',').append(String.format(Locale.US, "%.5f", ScaleSetting.uv())).append(',').append(ScaleSetting.verified() ? "no" : "yes").append(',').append(c1(r.sleep24)).append(',').append(c0(r.steps30)).append(',').append(c0(r.carbMin)).append(',').append(c1(r.rhrDev));
            sb.append('\n'); }
        return sb.toString();
    }
    private static final String[] NOTE_KEYS_A = { "result", "strap_hr", "verdict_s", "hrv_first_s", "hrv_first_ms" };
    private static final String[] NOTE_KEYS_B = { "where", "pos", "run", "why", "stop", "noise_0p5_5", "noise_8_25", "noise_25_48", "noise_mains", "noise_52_100", "contact_z", "live_R_uV", "live_QRS_ms", "live_dip_uV", "live_QT_ms", "beats_live", "app" };
    private static String nt(Row r, String k) { String v = r.nt == null ? null : r.nt.get(k); return v == null ? "" : v.replace(',', ';'); }
    private static String c0(double v) { return Double.isNaN(v) ? "" : String.format(Locale.US, "%.0f", v); }
    private static String c1(double v) { return Double.isNaN(v) ? "" : String.format(Locale.US, "%.1f", v); }
    private static String c2(double v) { return Double.isNaN(v) ? "" : String.format(Locale.US, "%.2f", v); }

    // ------------------------------------------------------------------ screen
    static final class Scatter extends View {
        private double[] xs; private double[][] ys; private int[] cols; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Scatter(Context c) { super(c); }
        void set(double[] x, double[][] y, int[] c) { xs = x; ys = y; cols = c; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            if (xs == null) return; Context c = getContext(); float w = getWidth(), h = getHeight(), lab = dp(c, 14), ch = h - lab; double mx = 1; for (double[] s : ys) for (double v : s) if (!Double.isNaN(v)) mx = Math.max(mx, v);
            p.setTextSize(dp(c, 9)); p.setTextAlign(Paint.Align.CENTER); p.setColor(0x22FFFFFF); for (int g = 1; g <= 3; g++) cv.drawLine(0, ch * g / 4f, w, ch * g / 4f, p);
            p.setColor(0x99FFFFFF); for (int i = 0; i <= 24; i += 3) cv.drawText(String.valueOf(i), w * i / 24f, h - 2, p); p.setTextAlign(Paint.Align.LEFT); cv.drawText(String.format(Locale.UK, "%.0f", mx), 2, dp(c, 9), p);
            for (int s = 0; s < ys.length; s++) { p.setColor(cols[s]); for (int i = 0; i < xs.length; i++) if (!Double.isNaN(ys[s][i]) && !Double.isNaN(xs[i])) cv.drawCircle((float) (w * xs[i] / 24.0), (float) (ch * (1 - ys[s][i] / mx)), dp(c, 3.2f), p); }
        }
    }

    private static String fmt(double v, String f) { return Double.isNaN(v) ? "-" : String.format(Locale.UK, f, v); }

    static void show(final Activity a, final HealthModel.Data d) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Your readings", 22, INK, 300)); top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final TextView status = HeartGraph.label(a, "Reading the reports and the strap's records for each reading...", 13, MUT, 400); col.addView(status, HeartGraph.lp(a, -1, -2, 14)); dlg.setContentView(root); dlg.show();
        new Thread(new Runnable() { @Override public void run() {
            List<Row> rows0 = null; try { rows0 = load(a, d, 150); } catch (Throwable ignored) { }
            final List<Row> rows = rows0;
            a.runOnUiThread(new Runnable() { @Override public void run() { try { fill(a, col, status, rows); } catch (Throwable ignored) { } } });
        } }, "readings-table").start();
    }

    private static void fill(final Activity a, LinearLayout col, TextView status, final List<Row> rows) {
        if (rows == null || rows.isEmpty()) { status.setText("No saved readings with a report yet."); return; }
        col.removeView(status); int n = rows.size();
        TextView scl = HeartGraph.label(a, "Microvolt figures (R, T, P, ST) are shown at " + String.format(Locale.UK, "%.5f", ScaleSetting.uv()) + " \u00b5V per count" + ScaleSetting.tag() + ". Each reading stores the scale it was made with; older readings are re-based from it. E (the strap's own value) and the H10 are not made from counts and are not re-based.", 11, MUT, 400); scl.setLineSpacing(0, 1.2f); col.addView(scl, HeartGraph.lp(a, -1, -2, 8));
        try {                                                                  // 0.42.0: this week, the streak and your typical range
            long[] st = new long[n]; for (int i = 0; i < n; i++) st[i] = rows.get(i).ts;
            EcgStreak.Out so = EcgStreak.compute(st, System.currentTimeMillis(), java.util.TimeZone.getDefault());
            TextView wk = HeartGraph.label(a, "THIS WEEK\n" + EcgStreak.strip(so) + "\n" + EcgStreak.summary(so), 13, INK, 400); wk.setLineSpacing(0, 1.2f); col.addView(wk, HeartGraph.lp(a, -1, -2, 10));
            TextView tp = HeartGraph.label(a, "YOUR TYPICAL RANGE (last " + Math.min(28, n) + " readings)\n" + typical(rows), 12, INK, 400); tp.setLineSpacing(0, 1.25f); col.addView(tp, HeartGraph.lp(a, -1, -2, 12));
            try {                                                           // 0.54.0: the latest reading against your own readings taken in a similar context
                ReadingContext.Ctx[] cx = new ReadingContext.Ctx[n]; for (int i = 0; i < n; i++) cx[i] = ctxOf(rows.get(i));
                ReadingContext.Ctx target = cx[0]; int m1 = n - 1; ReadingContext.Ctx[] peers = new ReadingContext.Ctx[m1]; double[][] vals = new double[4][m1];
                for (int i = 1; i < n; i++) { Row q = rows.get(i); peers[i - 1] = cx[i]; vals[0][i - 1] = q.R; vals[1][i - 1] = q.QRS; vals[2][i - 1] = q.hr; vals[3][i - 1] = q.rmssd; }
                Row L = rows.get(0); StringBuilder cb = new StringBuilder("LATEST READING IN CONTEXT\n" + target.text() + "\n");
                cb.append(ReadingContext.place("R (\u00b5V)", L.R, ReadingContext.band(vals[0], peers, m1, target), "%.0f")).append('\n');
                cb.append(ReadingContext.place("QRS (ms)", L.QRS, ReadingContext.band(vals[1], peers, m1, target), "%.0f")).append('\n');
                cb.append(ReadingContext.place("HR (bpm)", L.hr, ReadingContext.band(vals[2], peers, m1, target), "%.0f")).append('\n');
                cb.append(ReadingContext.place("HRV RMSSD (ms)", L.rmssd, ReadingContext.band(vals[3], peers, m1, target), "%.0f"));
                TextView cv = HeartGraph.label(a, cb.toString() + (L.R > 0 ? ScaleSetting.tag() : ""), 12, INK, 400); cv.setLineSpacing(0, 1.25f); col.addView(cv, HeartGraph.lp(a, -1, -2, 12));
            } catch (Throwable ignored) { }
            final TextView how = HeartGraph.label(a, "How is this calculated?  (tap)", 12, MUT, 400); final TextView why = HeartGraph.label(a, Explain.asText(Explain.streakCard()) + "\n\n" + Explain.asText(Explain.typicalRange()), 12, MUT, 400); why.setVisibility(View.GONE);
            how.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { why.setVisibility(why.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); } });
            col.addView(how, HeartGraph.lp(a, -1, -2, 6)); col.addView(why, HeartGraph.lp(a, -1, -2, 4));
        } catch (Throwable ignored) { }
        TextView sum = HeartGraph.label(a, n + " readings. R is the report's R wave height, E the strap's own end-of-reading QRS amplitude, H10 R the chest-strap R when its ECG file covers the reading (a single-beat estimate that reads about 12 % below an averaged beat).", 13, MUT, 400); sum.setLineSpacing(0, 1.25f); col.addView(sum, HeartGraph.lp(a, -1, -2, 6));
        double[] x = new double[n], rr = new double[n], ee = new double[n], tt = new double[n]; for (int i = 0; i < n; i++) { Row r = rows.get(i); x[i] = r.clock; rr[i] = r.R; ee[i] = r.E; tt[i] = r.T; }
        Scatter s1 = new Scatter(a); s1.set(x, new double[][] { rr, ee, tt }, new int[] { ROSE, BLUE, GOLD }); col.addView(s1, HeartGraph.lp(a, -1, Math.round(dp(a, 150)), 12));
        col.addView(HeartGraph.label(a, "Rose: R (report). Blue: E (strap). Gold: T wave. By clock hour, uV.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 4));
        double[] ha = new double[n]; for (int i = 0; i < n; i++) ha[i] = rows.get(i).hoursAwake; int nh2 = 0; for (double v : ha) if (!Double.isNaN(v)) nh2++;
        if (nh2 >= 5) { double[] line = new double[n]; for (int i = 0; i < n; i++) line[i] = Double.isNaN(ha[i]) ? Double.NaN : LINE_A + LINE_B * ha[i]; Scatter s4 = new Scatter(a); s4.set(ha, new double[][] { rr, line }, new int[] { ROSE, GREEN }); col.addView(s4, HeartGraph.lp(a, -1, Math.round(dp(a, 130)), 12));
            col.addView(HeartGraph.label(a, "Rose: R against hours since you last slept (x axis 0-24 h). Green: your line, R = 450 + 31 x hours awake.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 4)); }
        double[] hx = new double[n], hy = new double[n], hr = new double[n]; int nh = 0; for (int i = 0; i < n; i++) { Row r = rows.get(i); hx[i] = r.clock; hy[i] = r.h10R; hr[i] = (Double.isNaN(r.R) || Double.isNaN(r.h10R)) ? Double.NaN : 1000.0 * r.R / r.h10R; if (!Double.isNaN(r.h10R)) nh++; }
        if (nh >= 3) { Scatter s2 = new Scatter(a); s2.set(hx, new double[][] { hy, hr }, new int[] { GREEN, ROSE }); col.addView(s2, HeartGraph.lp(a, -1, Math.round(dp(a, 120)), 12));
            col.addView(HeartGraph.label(a, "Green: H10 chest R (uV). Rose: strap R divided by H10 R, x1000. " + nh + " readings with an H10 file.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 4)); }
        // relations with the clock
        StringBuilder rel = new StringBuilder("AGAINST THE CLOCK HOUR (rank correlation, readings with the value)\n");
        rel.append(corr("R (report)", rows, 0)).append(corr("T wave", rows, 1)).append(corr("P wave", rows, 2)).append(corr("QTc Fridericia", rows, 3)).append(corr("ST level", rows, 4)).append(corr("E (strap)", rows, 5)).append(corr("H10 R", rows, 6));
        TextView rt = HeartGraph.label(a, rel.toString().trim(), 12, INK, 400); rt.setLineSpacing(0, 1.25f); col.addView(rt, HeartGraph.lp(a, -1, -2, 12));
        // the rows
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK); StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            sb.append(f.format(new Date(r.ts * 1000L))).append("  ").append(r.grade).append(r.tag.length() > 0 ? "  [" + r.tag + "]" : "").append("  HR ").append(fmt(r.hr, "%.0f")).append("  RMSSD ").append(fmt(r.rmssd, "%.0f")).append('\n')
              .append("   R ").append(fmt(r.R, "%.0f")).append("  T ").append(fmt(r.T, "%.0f")).append("  P ").append(fmt(r.P, "%.0f")).append("  QRS ").append(fmt(r.QRS, "%.0f")).append("  QTc ").append(fmt(r.QTcF, "%.0f")).append("  ST ").append(fmt(r.ST, "%+.0f"))
              .append("  E ").append(fmt(r.E, "%.0f")).append("  H10 R ").append(fmt(r.h10R, "%.0f")).append(Double.isNaN(r.R) || Double.isNaN(r.h10R) ? "" : String.format(Locale.UK, " (%.2f)", r.R / r.h10R)).append('\n')
              .append("   steps 1 h ").append(fmt(r.steps1h, "%.0f")).append("  still ").append(fmt(r.still30, "%.2f")).append("  skin ").append(fmt(r.skin, "%.1f")).append("  gz ").append(fmt(r.gravZ, "%.2f")).append("\n   awake ").append(fmt(r.hoursAwake, "%.1f")).append(" h  your line ").append(fmt(r.expectedR, "%.0f")).append("  R vs line ").append(fmt(r.rVsLine, "%+.0f")).append(" %\n")
              .append("   asleep 24 h ").append(fmt(r.sleep24, "%.1f")).append(" h  steps 30 min ").append(fmt(r.steps30, "%.0f")).append("  carb ").append(Double.isNaN(r.carbMin) ? "-" : String.format(Locale.UK, "%.0f min ago", r.carbMin)).append("  HR vs rest ").append(fmt(r.rhrDev, "%+.0f")).append("\n\n");
        }
        TextView tv = HeartGraph.label(a, sb.toString().trim(), 11, MUT, 400); tv.setTypeface(android.graphics.Typeface.MONOSPACE); tv.setLineSpacing(0, 1.1f); col.addView(tv, HeartGraph.lp(a, -1, -2, 6));
        TextView ex = HeartGraph.pill(a, "Export the table as a CSV (Download/MGreports)", BLUE, 0xFF05131E);
        ex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { export(a, rows); } }); col.addView(ex, HeartGraph.lp(a, -2, -2, 14));
    }

    /** Median and middle half of each measure over the newest 28 readings, with the latest reading beside it. */
    private static String typical(List<Row> rows) {
        int m = Math.min(28, rows.size()); StringBuilder sb = new StringBuilder();
        String[] names = { "R wave (uV)", "QRS (ms)", "HR (bpm)", "HRV RMSSD (ms)", "Noise 8-25 Hz, live meter (uV)" };
        for (int k = 0; k < names.length; k++) {
            List<Double> v = new ArrayList<Double>(); double latest = Double.NaN;
            for (int i = 0; i < m; i++) {
                Row r = rows.get(i); double x = k == 0 ? r.R : k == 1 ? r.QRS : k == 2 ? r.hr : k == 3 ? r.rmssd : Double.NaN;
                if (k == 4) { try { String s = r.nt == null ? null : r.nt.get("noise_8_25"); if (s != null) x = Double.parseDouble(s); } catch (NumberFormatException e) { x = Double.NaN; } }
                if (!Double.isNaN(x)) { v.add(x); if (Double.isNaN(latest)) latest = x; }
            }
            if (v.size() < 5) { sb.append(names[k]).append(": too few readings\n"); continue; }
            List<Double> s = new ArrayList<Double>(v); Collections.sort(s); int n = s.size();
            double med = s.get(n / 2), q1 = s.get((int) Math.floor(0.25 * (n - 1))), q3 = s.get((int) Math.ceil(0.75 * (n - 1)));
            sb.append(String.format(Locale.UK, "%s: typical %.0f to %.0f (median %.0f); latest %.0f, %s%n", names[k], q1, q3, med, latest, latest > q3 ? "above" : latest < q1 ? "below" : "within"));
        }
        return sb.toString().trim();
    }

    private static String corr(String name, List<Row> rows, int which) {
        List<Double> a = new ArrayList<Double>(), b = new ArrayList<Double>();
        for (Row r : rows) { double v = which == 0 ? r.R : which == 1 ? r.T : which == 2 ? r.P : which == 3 ? r.QTcF : which == 4 ? r.ST : which == 5 ? r.E : r.h10R; if (!Double.isNaN(v) && !Double.isNaN(r.clock)) { a.add(r.clock); b.add(v); } }
        if (a.size() < 8) return name + ": too few\n"; double[] x = new double[a.size()], y = new double[a.size()]; for (int i = 0; i < x.length; i++) { x[i] = a.get(i); y[i] = b.get(i); }
        return String.format(Locale.UK, "%s: %+.2f (%d readings)%n", name, DayTimeline.spearman(x, y), x.length);
    }

    private static void export(final Activity a, final List<Row> rows) {
        new Thread(new Runnable() { @Override public void run() {
            String msg;
            try {
                byte[] b = csv(rows).getBytes("UTF-8"); String name = "MG_readings_table_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.UK).format(new Date()) + ".csv";
                ContentResolver cr = a.getContentResolver(); ContentValues cv = new ContentValues(); cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name); cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                if (Build.VERSION.SDK_INT >= 29) { cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MGreports"); cv.put(MediaStore.MediaColumns.IS_PENDING, 1); }
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) msg = "Could not save the table.";
                else { try (OutputStream os = cr.openOutputStream(u)) { os.write(b); } if (Build.VERSION.SDK_INT >= 29) { ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(u, done, null, null); } msg = "Saved Download/MGreports/" + name; }
            } catch (Throwable t) { msg = "Export failed: " + t; }
            final String m = msg; a.runOnUiThread(new Runnable() { @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); } });
        } }, "readings-export").start();
    }
}
