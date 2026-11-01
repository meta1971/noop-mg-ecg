package com.noop.mgecg;

// FILE VERSION 0.33.0 (1 Nov 2026): "Your readings": one row per saved ECG reading with the measurements from its report (R, T, P, QRS, QTc, ST), the strap's own end-of-reading amplitude (event 118),
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

    static final class Row {
        long ts; String grade = "-"; double hr = Double.NaN, rmssd = Double.NaN, dur = Double.NaN, R = Double.NaN, T = Double.NaN, P = Double.NaN, QRS = Double.NaN, QT = Double.NaN, QTcF = Double.NaN, ST = Double.NaN;
        double E = Double.NaN, steps1h = Double.NaN, still30 = Double.NaN, skin = Double.NaN, gravZ = Double.NaN, h10R = Double.NaN, clock = Double.NaN;
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
            File best = null; long bs = 0; File[] all = dir.listFiles(); if (all == null) return Double.NaN;
            for (File f : all) { String n = f.getName(); if (!n.startsWith("h10_ecg_") || !n.endsWith(".csv")) continue; long st; try { st = Long.parseLong(n.substring(8, n.length() - 4)); } catch (Exception e) { continue; } if (st <= startSec + 5 && st > bs && f.lastModified() >= startSec * 1000L) { bs = st; best = f; } }
            if (best == null) return Double.NaN;
            long a = startSec * 1000L - 1500L, b = (long) ((startSec + durSec) * 1000L); double[] buf = new double[(int) (durSec * 140) + 400]; int c = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(best), 1 << 16)) {
                String ln = br.readLine();
                while ((ln = br.readLine()) != null) { int i1 = ln.indexOf(','); if (i1 < 0) continue; long ms; try { ms = Long.parseLong(ln.substring(0, i1)); } catch (Exception e) { continue; } if (ms < a) continue; if (ms > b) break; int i2 = ln.lastIndexOf(','); if (c < buf.length) buf[c++] = Double.parseDouble(ln.substring(i2 + 1)); }
            }
            return h10Amp(Arrays.copyOf(buf, c));
        } catch (Throwable t) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ context from the day logger
    private static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    private static void context(Context c, Row r) {
        try {
            Cursor cu = DayLog.db(c).rawQuery("SELECT ts, steps, state, skin, substr(rec,54,4) FROM r18 WHERE ts BETWEEN ? AND ? ORDER BY ts", new String[] { String.valueOf(r.ts - 3600L), String.valueOf(r.ts + (long) (Double.isNaN(r.dur) ? 120 : r.dur)) });
            long lastTs = -1; int lastSteps = 0; double st1 = 0; int still = 0, tot = 0; List<Double> sk = new ArrayList<Double>(), gz = new ArrayList<Double>();
            try {
                while (cu.moveToNext()) {
                    long t = cu.getLong(0); int sp = cu.getInt(1), s = cu.getInt(2); double skin = cu.getDouble(3); byte[] g = cu.getBlob(4);
                    if (t < r.ts) { if (lastTs >= 0 && t - lastTs == 1) { int d = sp - lastSteps; if (d > 0 && d < 50) st1 += d; } if (t >= r.ts - 1800L) { tot++; if (s >= 1) still++; } if (t >= r.ts - 600L && skin > 10) sk.add(skin); }
                    else if (g != null && g.length == 4) gz.add((double) ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN).getFloat());
                    lastTs = t; lastSteps = sp;
                }
            } finally { cu.close(); }
            r.steps1h = st1; r.still30 = tot > 300 ? (double) still / tot : Double.NaN; r.skin = median(sk); r.gravZ = median(gz);
            Cursor ec = DayLog.db(c).rawQuery("SELECT inner FROM strap_events WHERE id = 118 AND ts BETWEEN ? AND ? ORDER BY ts LIMIT 1", new String[] { String.valueOf(r.ts), String.valueOf(r.ts + 900L) });
            try { if (ec.moveToFirst()) { String hex = ec.getString(0); if (hex != null && hex.length() >= 80) { int e = Integer.parseInt(hex.substring(34, 36), 16) | (Integer.parseInt(hex.substring(36, 38), 16) << 8); if (e > 0 && e < 5000) r.E = e; } } } finally { ec.close(); }
        } catch (Throwable ignored) { }
    }

    /** One row per reading, newest first (call off the main thread). */
    static List<Row> load(Context c, HealthModel.Data d, int max) {
        List<Row> out = new ArrayList<Row>(); File dir = c.getExternalFilesDir(null); Calendar cal = Calendar.getInstance(); int cnt = 0;
        for (HealthModel.Reading rd : d.readings) {
            if (cnt++ >= max) break; if (rd.file == null) continue;
            String n = rd.file.getName(); long ts; try { ts = Long.parseLong(n.substring("ecg_report_".length(), n.length() - ".html".length())); } catch (Exception e) { continue; }
            Row r = new Row(); r.ts = ts; fromText(r, plain(rd.file)); if (Double.isNaN(r.hr)) { r.hr = parse(rd.hr); } if (Double.isNaN(r.rmssd)) { r.rmssd = parse(rd.hrv); } if (r.grade.equals("-")) r.grade = rd.grade;
            cal.setTimeInMillis(ts * 1000L); r.clock = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
            context(c, r); if (dir != null) r.h10R = h10R(dir, ts, Double.isNaN(r.dur) ? 120 : r.dur);
            out.add(r);
        }
        return out;
    }
    private static double parse(String s) { return grab(s == null ? "" : s, "(\\d+(?:\\.\\d+)?)"); }

    static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("time_local,grade,duration_s,hr,rmssd_ms,R_uV,T_uV,P_uV,QRS_ms,QT_ms,QTc_fridericia_ms,ST_uV,strap_E_uV,steps_previous_hour,still_share_previous_30min,skin_c,gravity_z,h10_R_uV,strap_over_h10\n");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK);
        for (Row r : rows) sb.append(f.format(new Date(r.ts * 1000L))).append(',').append(r.grade).append(',').append(c0(r.dur)).append(',').append(c0(r.hr)).append(',').append(c0(r.rmssd)).append(',').append(c0(r.R)).append(',').append(c0(r.T)).append(',').append(c0(r.P)).append(',').append(c0(r.QRS)).append(',').append(c0(r.QT)).append(',').append(c0(r.QTcF)).append(',').append(c0(r.ST)).append(',').append(c0(r.E)).append(',').append(c0(r.steps1h)).append(',').append(c2(r.still30)).append(',').append(c1(r.skin)).append(',').append(c2(r.gravZ)).append(',').append(c0(r.h10R)).append(',').append(Double.isNaN(r.R) || Double.isNaN(r.h10R) ? "" : String.format(Locale.US, "%.2f", r.R / r.h10R)).append('\n');
        return sb.toString();
    }
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
        TextView sum = HeartGraph.label(a, n + " readings. R is the report's R wave height, E the strap's own end-of-reading QRS amplitude, H10 R the chest-strap R when its ECG file covers the reading (a single-beat estimate that reads about 12 % below an averaged beat).", 13, MUT, 400); sum.setLineSpacing(0, 1.25f); col.addView(sum, HeartGraph.lp(a, -1, -2, 6));
        double[] x = new double[n], rr = new double[n], ee = new double[n], tt = new double[n]; for (int i = 0; i < n; i++) { Row r = rows.get(i); x[i] = r.clock; rr[i] = r.R; ee[i] = r.E; tt[i] = r.T; }
        Scatter s1 = new Scatter(a); s1.set(x, new double[][] { rr, ee, tt }, new int[] { ROSE, BLUE, GOLD }); col.addView(s1, HeartGraph.lp(a, -1, Math.round(dp(a, 150)), 12));
        col.addView(HeartGraph.label(a, "Rose: R (report). Blue: E (strap). Gold: T wave. By clock hour, uV.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 4));
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
            sb.append(f.format(new Date(r.ts * 1000L))).append("  ").append(r.grade).append("  HR ").append(fmt(r.hr, "%.0f")).append("  RMSSD ").append(fmt(r.rmssd, "%.0f")).append('\n')
              .append("   R ").append(fmt(r.R, "%.0f")).append("  T ").append(fmt(r.T, "%.0f")).append("  P ").append(fmt(r.P, "%.0f")).append("  QRS ").append(fmt(r.QRS, "%.0f")).append("  QTc ").append(fmt(r.QTcF, "%.0f")).append("  ST ").append(fmt(r.ST, "%+.0f"))
              .append("  E ").append(fmt(r.E, "%.0f")).append("  H10 R ").append(fmt(r.h10R, "%.0f")).append(Double.isNaN(r.R) || Double.isNaN(r.h10R) ? "" : String.format(Locale.UK, " (%.2f)", r.R / r.h10R)).append('\n')
              .append("   steps 1 h ").append(fmt(r.steps1h, "%.0f")).append("  still ").append(fmt(r.still30, "%.2f")).append("  skin ").append(fmt(r.skin, "%.1f")).append("  gz ").append(fmt(r.gravZ, "%.2f")).append("\n\n");
        }
        TextView tv = HeartGraph.label(a, sb.toString().trim(), 11, MUT, 400); tv.setTypeface(android.graphics.Typeface.MONOSPACE); tv.setLineSpacing(0, 1.1f); col.addView(tv, HeartGraph.lp(a, -1, -2, 6));
        TextView ex = HeartGraph.pill(a, "Export the table as a CSV (Download/MGreports)", BLUE, 0xFF05131E);
        ex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { export(a, rows); } }); col.addView(ex, HeartGraph.lp(a, -2, -2, 14));
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
