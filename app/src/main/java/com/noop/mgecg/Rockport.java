package com.noop.mgecg;

// FILE VERSION 0.36.0 (1 Nov 2026): Rockport one-mile walk test (Kline 1987): VO2 max from walk time, heart rate at the end, age, sex and weight.
// 0.36.0: GPS marks the mile at 1,609 m and ends the test by itself; the timer, heart rate and distance are saved second by second in rockport_<unix>.csv; a live pace warning (slower than 16 minutes a mile
// is not a valid test); heart rate only from the Polar H10 (the strap's optical heart rate follows the step rate in walking and reads about 24 bpm too high), otherwise you type it; if the distance walked
// differs from a mile the time is scaled to a mile. Valid for ages 30 to 69 in the original study; typical error about 4 to 5 ml/kg/min (Kline 1987; Pober 2002).

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class Rockport {
    private Rockport() {}

    static final double MILE = 1609.344;

    /** Kline 1987: weight in pounds, time in minutes, heart rate at the end in beats per minute, sex 1 = male. */
    static double vo2(double kg, double age, boolean male, double minutes, double hr) {
        return 132.853 - 0.0769 * (kg * 2.2046226) - 0.3877 * age + (male ? 6.315 : 0.0) - 3.2649 * minutes - 0.1565 * hr;
    }

    static String clock(long ms) { long s = ms / 1000L; return String.format(Locale.UK, "%d:%02d", s / 60, s % 60); }
    static String pace(double minutesPerMile) { int s = (int) Math.round(minutesPerMile * 60); return String.format(Locale.UK, "%d:%02d", s / 60, s % 60); }

    /** Median of the H10 heart rates in the samples {second, hr} whose second is within window seconds before t (inclusive); NaN when fewer than 3. */
    static double hrAt(List<int[]> samples, double t, int window) {
        List<Integer> v = new ArrayList<Integer>(); for (int[] s : samples) if (s[0] <= t + 0.5 && s[0] > t - window) v.add(s[1]);
        if (v.size() < 3) return Double.NaN; Collections.sort(v); return v.get(v.size() / 2);
    }

    /** Seconds at which the recorded distance first reaches the mile (linear between samples); NaN if it never does. samples {second, distance m}. */
    static double mileTime(List<double[]> dist) {
        double pt = 0, pd = 0; for (double[] s : dist) { if (s[1] >= MILE) { return s[1] == pd ? s[0] : pt + (s[0] - pt) * (MILE - pd) / (s[1] - pd); } pt = s[0]; pd = s[1]; } return Double.NaN;
    }

    private static android.widget.EditText field(Context c, String hint, String val) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setTextColor(INK); e.setHintTextColor(MUT); e.setTextSize(18);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setBackground(glass(c, 12));
        int p = Math.round(dp(c, 12)); e.setPadding(p, p, p, p); return e;
    }

    // ------------------------------------------------------------------ the walk itself
    private static final class Walk implements LocationListener {
        final long t0 = System.currentTimeMillis(); final List<int[]> hr = new ArrayList<int[]>(); final List<double[]> dist = new ArrayList<double[]>();
        double meters = 0; Location last; boolean gps; double tMile = Double.NaN; double hrMile = Double.NaN; boolean done;
        @Override public void onLocationChanged(Location l) {
            if (done || l == null || l.getAccuracy() > 25f) return; gps = true;
            if (last != null) { double d = last.distanceTo(l), dt = Math.max(0.5, (l.getTime() - last.getTime()) / 1000.0); if (d >= 2.0 && d / dt <= 5.0 && d / dt >= 0.4) meters += d; else if (d < 2.0 || d / dt < 0.4) { return; } }
            last = l;
        }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
    }

    static void show(final Activity a) {
        final SharedPreferences sp = a.getSharedPreferences("healthspan", Context.MODE_PRIVATE);
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Handler ui = new Handler(Looper.getMainLooper());
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Rockport walk test", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final Walk[] cur = new Walk[1]; final boolean[] walking = { false };
        final LocationManager lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
        final Runnable stopGps = new Runnable() { @Override public void run() { try { if (cur[0] != null) lm.removeUpdates(cur[0]); } catch (Throwable ignored) { } } };
        View.OnClickListener closer = new View.OnClickListener() { @Override public void onClick(View v) { walking[0] = false; ui.removeCallbacksAndMessages(null); stopGps.run(); dlg.dismiss(); } };
        close.setOnClickListener(closer);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface d) { walking[0] = false; ui.removeCallbacksAndMessages(null); stopGps.run(); } });

        final Runnable[] setup = new Runnable[1];
        setup[0] = new Runnable() { @Override public void run() {
            col.removeAllViews();
            TextView intro = HeartGraph.label(a,
                "Walk one mile (1.609 km) as fast as you can WITHOUT jogging. The time and your heart rate at the end give a VO2 max estimate. A walk slower than 16 minutes a mile is not a valid test.\n\n"
                + "Before: warm up by walking gently for 5 to 10 minutes. Put the Polar H10 on with wet electrodes and start its recording in the day logger: the app takes your heart rate only from the H10, because the strap's optical heart rate follows your step rate when you walk. "
                + "GPS marks the mile for you; switch location on first.\n\n"
                + "Stop at once with chest pain, dizziness or unusual breathlessness. Do not do it if a doctor has told you to avoid hard exercise. Heart-rate-lowering drugs (beta blockers) make the result read too high.", 13, MUT, 400);
            intro.setLineSpacing(0, 1.25f); col.addView(intro, HeartGraph.lp(a, -1, -2, 14));
            final EditText w = field(a, "Weight in kg", sp.getFloat("weight", 0f) > 0 ? String.valueOf(sp.getFloat("weight", 0f)) : "");
            final EditText ag = field(a, "Age in years", String.valueOf(sp.getFloat("age", 53f)));
            col.addView(HeartGraph.label(a, "Weight (kg)", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 14)); col.addView(w, HeartGraph.lp(a, -1, -2, 4));
            col.addView(HeartGraph.label(a, "Age", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 12)); col.addView(ag, HeartGraph.lp(a, -1, -2, 4));
            final TextView sex = HeartGraph.pill(a, sp.getBoolean("male", true) ? "Sex: male (tap to change)" : "Sex: female (tap to change)", 0x1AFFFFFF, INK);
            sex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean m = !sp.getBoolean("male", true); sp.edit().putBoolean("male", m).apply(); sex.setText(m ? "Sex: male (tap to change)" : "Sex: female (tap to change)"); } });
            col.addView(sex, HeartGraph.lp(a, -2, -2, 12));
            final TextView h10 = HeartGraph.label(a, PolarH10.wanted && PolarH10.hrNow() > 0 ? "H10: live, heart rate " + PolarH10.hrNow() : "H10: not recording (you will have to type your heart rate at the end)", 13, PolarH10.wanted && PolarH10.hrNow() > 0 ? GREEN : ROSE, 400);
            col.addView(h10, HeartGraph.lp(a, -1, -2, 12));
            final TextView msg = HeartGraph.label(a, "", 13, ROSE, 400);
            TextView go = HeartGraph.pill(a, "START THE WALK", GREEN, 0xFF05131E); go.setTextSize(16);
            go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                double kg, age; try { kg = Double.parseDouble(w.getText().toString().trim()); age = Double.parseDouble(ag.getText().toString().trim()); } catch (Exception e) { msg.setText("Enter your weight and age first."); return; }
                if (kg < 30 || kg > 250 || age < 18 || age > 100) { msg.setText("Check the weight (30 to 250 kg) and age."); return; }
                sp.edit().putFloat("weight", (float) kg).putFloat("age", (float) age).apply();
                if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { a.requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 7411); msg.setText("Allow location when asked, then press START again (GPS marks the mile)."); return; }
                walk(a, sp, col, ui, walking, cur, lm, setup[0], kg, age); } });
            col.addView(go, HeartGraph.lp(a, -1, -2, 18)); col.addView(msg, HeartGraph.lp(a, -1, -2, 8));
            String last = sp.getString("vo2_hist", "");
            if (last.length() > 0) { TextView h = HeartGraph.label(a, "Earlier results\n" + last, 12, MUT, 400); h.setLineSpacing(0, 1.2f); col.addView(h, HeartGraph.lp(a, -1, -2, 18)); }
        } };
        setup[0].run();
        dlg.setContentView(root); dlg.show();
    }

    private static void walk(final Activity a, final SharedPreferences sp, final LinearLayout col, final Handler ui, final boolean[] walking, final Walk[] cur, final LocationManager lm,
                             final Runnable back, final double kg, final double age) {
        col.removeAllViews(); walking[0] = true; final Walk w = new Walk(); cur[0] = w;
        boolean gpsOn = false;
        try { if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, w, Looper.getMainLooper()); gpsOn = true; } } catch (Throwable ignored) { }
        final boolean useGps = gpsOn;
        final TextView timer = HeartGraph.label(a, "0:00", 64, INK, 200); timer.setGravity(Gravity.CENTER);
        final TextView hrv = HeartGraph.label(a, "", 22, GREEN, 300); hrv.setGravity(Gravity.CENTER);
        final TextView dv = HeartGraph.label(a, useGps ? "Waiting for a GPS fix..." : "No GPS: tap FINISHED at the mile mark", 18, INK, 300); dv.setGravity(Gravity.CENTER);
        final TextView warn = HeartGraph.label(a, "", 14, ROSE, 400); warn.setGravity(Gravity.CENTER);
        final TextView tip = HeartGraph.label(a, "Walk briskly, no jogging. With GPS the test ends by itself at 1.609 km.", 13, MUT, 400); tip.setGravity(Gravity.CENTER);
        col.addView(timer, HeartGraph.lp(a, -1, -2, 30)); col.addView(hrv, HeartGraph.lp(a, -1, -2, 6)); col.addView(dv, HeartGraph.lp(a, -1, -2, 10)); col.addView(warn, HeartGraph.lp(a, -1, -2, 6)); col.addView(tip, HeartGraph.lp(a, -1, -2, 12));
        final Runnable[] tick = new Runnable[1]; final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        final Runnable finish = new Runnable() { @Override public void run() {
            if (w.done) return; w.done = true; walking[0] = false; ui.removeCallbacksAndMessages(null); try { lm.removeUpdates(w); } catch (Throwable ignored) { }
            long el = System.currentTimeMillis() - w.t0; result(a, sp, col, back, kg, age, w, el, useGps); } };
        tick[0] = new Runnable() { @Override public void run() {
            if (!walking[0] || w.done) return;
            long el = System.currentTimeMillis() - w.t0; double sec = el / 1000.0; timer.setText(clock(el));
            int h = PolarH10.wanted ? PolarH10.hrNow() : 0; if (h > 0) w.hr.add(new int[] { (int) sec, h });
            hrv.setText(h > 0 ? "H10 heart rate " + h : "no H10 heart rate (you will type it at the end)");
            w.dist.add(new double[] { sec, w.meters });
            if (useGps) {
                dv.setText(w.gps ? String.format(Locale.UK, "%.0f m of 1,609 m", w.meters) : "Waiting for a GPS fix...");
                if (w.gps && sec >= 90 && w.meters > 100) { double proj = (sec / 60.0) * MILE / w.meters; warn.setText(proj > 16.0 ? "Pace " + pace(proj) + " a mile: too slow for Rockport, aim for under 16:00" : "Pace " + pace(proj) + " a mile"); warn.setTextColor(proj > 16.0 ? ROSE : GREEN); }
                if (w.meters >= MILE) { w.tMile = mileTime(w.dist); w.hrMile = hrAt(w.hr, Double.isNaN(w.tMile) ? sec : w.tMile, 8); try { tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600); } catch (Throwable ignored) { } finish.run(); return; }
            }
            ui.postDelayed(this, 1000L); } };
        tick[0].run();
        TextView fin = HeartGraph.pill(a, "FINISHED (I reached the mile)", GREEN, 0xFF05131E); fin.setTextSize(16);
        TextView cancel = HeartGraph.pill(a, "Cancel", 0x1AFFFFFF, INK);
        fin.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish.run(); } });
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { w.done = true; walking[0] = false; ui.removeCallbacksAndMessages(null); try { lm.removeUpdates(w); } catch (Throwable ignored) { } back.run(); } });
        col.addView(fin, HeartGraph.lp(a, -1, -2, 30)); col.addView(cancel, HeartGraph.lp(a, -2, -2, 14));
        ((LinearLayout.LayoutParams) cancel.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
    }

    private static void result(final Activity a, final SharedPreferences sp, final LinearLayout col, final Runnable back, final double kg, final double age, final Walk w, final long el, boolean gps) {
        col.removeAllViews(); final boolean male = sp.getBoolean("male", true); final double sec = el / 1000.0;
        final boolean reachedMile = !Double.isNaN(w.tMile);
        final double secUsed = reachedMile ? w.tMile : sec;
        double h0 = reachedMile && !Double.isNaN(w.hrMile) ? w.hrMile : hrAt(w.hr, sec, 8);
        TextView t = HeartGraph.label(a, "Time " + clock((long) (secUsed * 1000)) + (reachedMile ? " to the mile mark (GPS)" : "") + (Double.isNaN(h0) ? "" : "   |   H10 heart rate at the end " + Math.round(h0)), 16, INK, 400); col.addView(t, HeartGraph.lp(a, -1, -2, 20));
        col.addView(HeartGraph.label(a, "Distance walked (m)  (1,609 m = one mile)", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 14));
        final EditText dE = field(a, "metres", String.valueOf(Math.round(reachedMile ? MILE : (gps && w.meters > 50 ? w.meters : MILE)))); col.addView(dE, HeartGraph.lp(a, -1, -2, 4));
        col.addView(HeartGraph.label(a, "Heart rate at the end (bpm)", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 14));
        final EditText hrE = field(a, "bpm", Double.isNaN(h0) ? "" : String.valueOf(Math.round(h0))); col.addView(hrE, HeartGraph.lp(a, -1, -2, 4));
        final TextView big = HeartGraph.label(a, "", 48, GREEN, 200); big.setGravity(Gravity.CENTER);
        final TextView note = HeartGraph.label(a, "", 13, MUT, 400); note.setGravity(Gravity.CENTER); note.setLineSpacing(0, 1.2f);
        final double[] val = { Double.NaN }; final double[] used = { Double.NaN, Double.NaN };
        final Runnable calc = new Runnable() { @Override public void run() {
            double d = Double.NaN, h = Double.NaN; try { d = Double.parseDouble(dE.getText().toString().trim()); } catch (Exception ignored) { } try { h = Double.parseDouble(hrE.getText().toString().trim()); } catch (Exception ignored) { }
            if (Double.isNaN(d) || d < 400 || d > 5000) { big.setText("--"); note.setText("Enter the distance walked in metres."); val[0] = Double.NaN; return; }
            if (Double.isNaN(h) || h < 60 || h > 220) { big.setText("--"); note.setText("Enter the heart rate at the end of the walk (H10 or a 15-second pulse count x 4)."); val[0] = Double.NaN; return; }
            double secScaled = secUsed, hrScaled = h; String scaled = "";
            if (!reachedMile && Math.abs(d - MILE) > 0.02 * MILE) { secScaled = secUsed * MILE / d; double hh = hrAt(w.hr, secUsed * MILE / d, 8); if (!Double.isNaN(hh) && Math.abs(h - hrAt(w.hr, secUsed, 8)) < 1.0) hrScaled = hh; scaled = String.format(Locale.UK, " Scaled to one mile from %.0f m: time %s, heart rate %.0f.", d, clock((long) (secScaled * 1000)), hrScaled); }
            double Tm = secScaled / 60.0; double v = vo2(kg, age, male, Tm, hrScaled); val[0] = v; used[0] = Tm; used[1] = hrScaled;
            big.setText(String.format(Locale.UK, "%.1f", v));
            String warnTxt = Tm > 16.0 ? "\nSlower than 16 minutes a mile: not a valid Rockport test. Treat this as a low estimate." : Tm < 8.0 ? "\nUnder 8 minutes is running pace, not a walk: do not use this number." : "";
            note.setText("ml/kg/min. Typical error about 4 to 5. Valid for ages 30 to 69." + (age < 30 || age > 69 ? " You are outside that range." : "") + scaled + warnTxt); } };
        TextWatcher tw = new TextWatcher() { public void beforeTextChanged(CharSequence s, int i, int j, int k) { } public void onTextChanged(CharSequence s, int i, int j, int k) { } public void afterTextChanged(Editable e) { calc.run(); } };
        hrE.addTextChangedListener(tw); dE.addTextChangedListener(tw);
        col.addView(big, HeartGraph.lp(a, -1, -2, 24)); col.addView(note, HeartGraph.lp(a, -1, -2, 4));
        calc.run();
        TextView save = HeartGraph.pill(a, "SAVE AS MY VO2 MAX", GREEN, 0xFF05131E); save.setTextSize(16);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (Double.isNaN(val[0])) return;
            String line = new java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(new java.util.Date()) + ": " + String.format(Locale.UK, "%.1f", val[0]) + " (Rockport, " + clock((long) (used[0] * 60000)) + ", HR " + Math.round(used[1]) + ")";
            String old = sp.getString("vo2_hist", ""); String[] ls = old.length() == 0 ? new String[0] : old.split("\n"); StringBuilder sb = new StringBuilder(line);
            for (int i = 0; i < Math.min(5, ls.length); i++) sb.append('\n').append(ls[i]);
            sp.edit().putFloat("vo2", (float) val[0]).putString("vo2_src", "Rockport walk test").putLong("vo2_date", System.currentTimeMillis()).putString("vo2_hist", sb.toString()).apply();
            record(a, w, kg, age, male, val[0], used[0], used[1]);
            ((TextView) v).setText("Saved. Healthspan now uses it."); v.setEnabled(false); } });
        TextView again = HeartGraph.pill(a, "Back", 0x1AFFFFFF, INK); again.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { back.run(); } });
        col.addView(save, HeartGraph.lp(a, -1, -2, 24)); col.addView(again, HeartGraph.lp(a, -2, -2, 12));
        record(a, w, kg, age, male, Double.NaN, Double.NaN, Double.NaN);       // the session is kept even if you do not save the result
    }

    /** rockport_<unix>.csv in the app folder: weight, age, sex, result, then second, H10 heart rate (blank when none), GPS distance in metres. Included in the backups. */
    private static void record(Context c, Walk w, double kg, double age, boolean male, double vo2, double tMin, double hr) {
        try {
            File dir = c.getExternalFilesDir(null); if (dir == null) return; File f = new File(dir, "rockport_" + (w.t0 / 1000L) + ".csv");
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write("# Rockport walk test, start unix " + (w.t0 / 1000L) + ", weight_kg " + kg + ", age " + age + ", sex " + (male ? "male" : "female") + ", gps " + w.gps + ", mile_time_s " + (Double.isNaN(w.tMile) ? "" : String.format(Locale.US, "%.1f", w.tMile)) + "\n");
                if (!Double.isNaN(vo2)) fw.write("# result " + String.format(Locale.US, "%.1f", vo2) + " ml/kg/min, time_min " + String.format(Locale.US, "%.2f", tMin) + ", hr " + String.format(Locale.US, "%.0f", hr) + "\n");
                fw.write("second,h10_hr,distance_m\n"); int hi = 0;
                for (double[] d : w.dist) { String hs = ""; while (hi < w.hr.size() && w.hr.get(hi)[0] < d[0]) hi++; if (hi < w.hr.size() && Math.abs(w.hr.get(hi)[0] - d[0]) <= 1) hs = String.valueOf(w.hr.get(hi)[1]); fw.write(String.format(Locale.US, "%.0f,%s,%.1f%n", d[0], hs, d[1])); }
            }
        } catch (Throwable ignored) { }
    }
}
