package com.noop.mgecg;

// FILE VERSION 0.28.0 (31 Oct 2026): Rockport one-mile walk test (Kline 1987): VO2 max from walk time, heart rate at the end, age, sex and weight.
// Valid for ages 30 to 69 in the original study; typical error about 4 to 5 ml/kg/min (Kline 1987; Pober 2002). Submaximal, but not for everyone: see the screen text.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class Rockport {
    private Rockport() {}

    /** Kline 1987: weight in pounds, time in minutes, heart rate at the end in beats per minute, sex 1 = male. */
    static double vo2(double kg, double age, boolean male, double minutes, double hr) {
        return 132.853 - 0.0769 * (kg * 2.2046226) - 0.3877 * age + (male ? 6.315 : 0.0) - 3.2649 * minutes - 0.1565 * hr;
    }

    private static String clock(long ms) { long s = ms / 1000L; return String.format(Locale.UK, "%d:%02d", s / 60, s % 60); }

    /** Heart rate now: the Polar H10 when it is on, otherwise the strap's own heart-rate notification if it is under 15 s old; 0 = none. */
    static int hrNow(String[] src) {
        int h = PolarH10.hrNow();
        if (PolarH10.wanted && h > 0) { src[0] = "H10"; return h; }
        LiveHr.Sample s = LiveHr.latest();
        if (s != null && s.hr > 0 && System.currentTimeMillis() - s.ms < 15000L) { src[0] = "strap"; return s.hr; }
        src[0] = ""; return 0;
    }

    private static EditText field(Context c, String hint, String val) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setTextColor(INK); e.setHintTextColor(MUT); e.setTextSize(18);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setBackground(glass(c, 12));
        int p = Math.round(dp(c, 12)); e.setPadding(p, p, p, p); return e;
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

        final boolean[] walking = { false };
        final long[] t0 = { 0L };
        final Runnable[] tick = new Runnable[1];
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { walking[0] = false; ui.removeCallbacksAndMessages(null); dlg.dismiss(); } });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface d) { walking[0] = false; ui.removeCallbacksAndMessages(null); } });

        final Runnable[] setup = new Runnable[1];
        setup[0] = new Runnable() { @Override public void run() {
            col.removeAllViews();
            TextView intro = HeartGraph.label(a,
                "Walk one mile (1.609 km) on flat ground as fast as you can WITHOUT jogging. The time and your heart rate at the end give a VO2 max estimate.\n\n"
                + "Before: warm up by walking gently for 5 to 10 minutes. Wear the Polar H10 (start it in the day logger) or the strap; the app takes the heart rate from whichever is live. Mark a measured mile, or use a route you know is a mile.\n\n"
                + "Stop at once with chest pain, dizziness or unusual breathlessness. Do not do it if a doctor has told you to avoid hard exercise. Heart-rate-lowering drugs (beta blockers) make the result read too high.", 13, MUT, 400);
            intro.setLineSpacing(0, 1.25f); col.addView(intro, HeartGraph.lp(a, -1, -2, 14));
            final EditText w = field(a, "Weight in kg", sp.getFloat("weight", 0f) > 0 ? String.valueOf(sp.getFloat("weight", 0f)) : "");
            final EditText ag = field(a, "Age in years", String.valueOf(sp.getFloat("age", 53f)));
            col.addView(HeartGraph.label(a, "Weight (kg)", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 14)); col.addView(w, HeartGraph.lp(a, -1, -2, 4));
            col.addView(HeartGraph.label(a, "Age", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 12)); col.addView(ag, HeartGraph.lp(a, -1, -2, 4));
            final TextView sex = HeartGraph.pill(a, sp.getBoolean("male", true) ? "Sex: male (tap to change)" : "Sex: female (tap to change)", 0x1AFFFFFF, INK);
            sex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean m = !sp.getBoolean("male", true); sp.edit().putBoolean("male", m).apply(); sex.setText(m ? "Sex: male (tap to change)" : "Sex: female (tap to change)"); } });
            col.addView(sex, HeartGraph.lp(a, -2, -2, 12));
            final TextView msg = HeartGraph.label(a, "", 13, ROSE, 400);
            TextView go = HeartGraph.pill(a, "START THE WALK", GREEN, 0xFF05131E); go.setTextSize(16);
            go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                double kg, age; try { kg = Double.parseDouble(w.getText().toString().trim()); age = Double.parseDouble(ag.getText().toString().trim()); } catch (Exception e) { msg.setText("Enter your weight and age first."); return; }
                if (kg < 30 || kg > 250 || age < 18 || age > 100) { msg.setText("Check the weight (30 to 250 kg) and age."); return; }
                sp.edit().putFloat("weight", (float) kg).putFloat("age", (float) age).apply();
                walk(a, sp, col, ui, walking, t0, tick, setup[0], kg, age); } });
            col.addView(go, HeartGraph.lp(a, -1, -2, 18)); col.addView(msg, HeartGraph.lp(a, -1, -2, 8));
            String last = sp.getString("vo2_hist", "");
            if (last.length() > 0) { TextView h = HeartGraph.label(a, "Earlier results\n" + last, 12, MUT, 400); h.setLineSpacing(0, 1.2f); col.addView(h, HeartGraph.lp(a, -1, -2, 18)); }
        } };
        setup[0].run();
        dlg.setContentView(root); dlg.show();
    }

    private static void walk(final Activity a, final SharedPreferences sp, final LinearLayout col, final Handler ui, final boolean[] walking, final long[] t0, final Runnable[] tick,
                             final Runnable back, final double kg, final double age) {
        col.removeAllViews(); walking[0] = true; t0[0] = System.currentTimeMillis();
        final TextView timer = HeartGraph.label(a, "0:00", 64, INK, 200); timer.setGravity(Gravity.CENTER);
        final TextView hr = HeartGraph.label(a, "", 22, GREEN, 300); hr.setGravity(Gravity.CENTER);
        final TextView tip = HeartGraph.label(a, "Walk briskly, no jogging. Tap FINISHED the moment you reach the mile mark.", 13, MUT, 400); tip.setGravity(Gravity.CENTER);
        col.addView(timer, HeartGraph.lp(a, -1, -2, 40)); col.addView(hr, HeartGraph.lp(a, -1, -2, 8)); col.addView(tip, HeartGraph.lp(a, -1, -2, 16));
        final List<int[]> samples = new ArrayList<int[]>();      // {second, hr}
        final String[] src = { "" };
        tick[0] = new Runnable() { @Override public void run() {
            if (!walking[0]) return;
            long el = System.currentTimeMillis() - t0[0]; timer.setText(clock(el));
            int h = hrNow(src); if (h > 0) samples.add(new int[] { (int) (el / 1000L), h });
            hr.setText(h > 0 ? src[0] + " heart rate " + h : "no live heart rate (you can type it at the end)");
            ui.postDelayed(this, 1000L); } };
        tick[0].run();
        TextView fin = HeartGraph.pill(a, "FINISHED (I reached the mile)", GREEN, 0xFF05131E); fin.setTextSize(16);
        TextView cancel = HeartGraph.pill(a, "Cancel", 0x1AFFFFFF, INK);
        fin.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            walking[0] = false; ui.removeCallbacksAndMessages(null);
            long el = System.currentTimeMillis() - t0[0]; int sec = (int) (el / 1000L);
            List<Integer> last = new ArrayList<Integer>(); for (int[] s : samples) if (sec - s[0] <= 8) last.add(s[1]);
            int h0 = 0; if (!last.isEmpty()) { Collections.sort(last); h0 = last.get(last.size() / 2); }
            result(a, sp, col, back, kg, age, el, h0, h0 > 0 ? src[0] : ""); } });
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { walking[0] = false; ui.removeCallbacksAndMessages(null); back.run(); } });
        col.addView(fin, HeartGraph.lp(a, -1, -2, 40)); col.addView(cancel, HeartGraph.lp(a, -2, -2, 14));
        ((LinearLayout.LayoutParams) cancel.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
    }

    private static void result(final Activity a, final SharedPreferences sp, final LinearLayout col, final Runnable back, final double kg, final double age, final long el, int h0, String src) {
        col.removeAllViews();
        final double min = el / 60000.0; final boolean male = sp.getBoolean("male", true);
        TextView t = HeartGraph.label(a, "Time " + clock(el) + (src.length() > 0 ? "   |   " + src + " heart rate at the end " + h0 : ""), 16, INK, 400); col.addView(t, HeartGraph.lp(a, -1, -2, 20));
        col.addView(HeartGraph.label(a, "Heart rate at the end (bpm)", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 14));
        final EditText hrE = field(a, "bpm", h0 > 0 ? String.valueOf(h0) : ""); col.addView(hrE, HeartGraph.lp(a, -1, -2, 4));
        final TextView big = HeartGraph.label(a, "", 48, GREEN, 200); big.setGravity(Gravity.CENTER);
        final TextView note = HeartGraph.label(a, "", 13, MUT, 400); note.setGravity(Gravity.CENTER); note.setLineSpacing(0, 1.2f);
        final double[] val = { Double.NaN };
        final Runnable calc = new Runnable() { @Override public void run() {
            double h = Double.NaN; try { h = Double.parseDouble(hrE.getText().toString().trim()); } catch (Exception ignored) { }
            if (Double.isNaN(h) || h < 60 || h > 220) { big.setText("--"); note.setText("Enter the heart rate at the end of the mile."); val[0] = Double.NaN; return; }
            double v = vo2(kg, age, male, min, h); val[0] = v;
            big.setText(String.format(Locale.UK, "%.1f", v));
            String warn = min < 10 ? "\nUnder 10 minutes is jogging pace, not a walk: do not use this number." : min > 25 ? "\nOver 25 minutes: a very slow walk gives a low, unreliable estimate." : "";
            note.setText("ml/kg/min. Typical error about 4 to 5. Valid for ages 30 to 69." + (age < 30 || age > 69 ? " You are outside that range." : "") + warn); } };
        hrE.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int i, int j, int k) { } public void onTextChanged(CharSequence s, int i, int j, int k) { } public void afterTextChanged(Editable e) { calc.run(); } });
        col.addView(big, HeartGraph.lp(a, -1, -2, 24)); col.addView(note, HeartGraph.lp(a, -1, -2, 4));
        calc.run();
        TextView save = HeartGraph.pill(a, "SAVE AS MY VO2 MAX", GREEN, 0xFF05131E); save.setTextSize(16);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (Double.isNaN(val[0])) return;
            String line = new java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(new java.util.Date()) + ": " + String.format(Locale.UK, "%.1f", val[0]) + " (" + clock(el) + ", HR " + hrE.getText().toString().trim() + ")";
            String old = sp.getString("vo2_hist", ""); String[] ls = old.length() == 0 ? new String[0] : old.split("\n"); StringBuilder sb = new StringBuilder(line);
            for (int i = 0; i < Math.min(5, ls.length); i++) sb.append('\n').append(ls[i]);
            sp.edit().putFloat("vo2", (float) val[0]).putString("vo2_src", "Rockport walk test").putLong("vo2_date", System.currentTimeMillis()).putString("vo2_hist", sb.toString()).apply();
            ((TextView) v).setText("Saved. Healthspan now uses it."); v.setEnabled(false); } });
        TextView again = HeartGraph.pill(a, "Back", 0x1AFFFFFF, INK); again.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { back.run(); } });
        col.addView(save, HeartGraph.lp(a, -1, -2, 24)); col.addView(again, HeartGraph.lp(a, -2, -2, 12));
    }
}
