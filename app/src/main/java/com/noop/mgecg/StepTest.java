package com.noop.mgecg;

// FILE VERSION 0.36.0 (1 Nov 2026): Queens College step test (McArdle 1972): 3 minutes of stepping on a 16.25 inch (41.3 cm) step to a metronome (men 24 steps a minute = 96 beats a minute,
// women 22 = 88), then the heart rate over the 15 seconds that start 5 seconds after you stop. Men: VO2 max = 111.33 - 0.42 x heart rate; women: 65.81 - 0.1847 x heart rate.
// Submaximal, quick to repeat; validated in young adults, so for you treat the result as rough (probably +/-10 to 15 %). The H10 gives the heart rate; without it you count your pulse.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
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
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class StepTest {
    private StepTest() {}

    static double vo2(boolean male, double hr) { return male ? 111.33 - 0.42 * hr : 65.81 - 0.1847 * hr; }

    /** Mean of the heart rates {second after stop, bpm} from second 5 up to (not including) 20: the 15-second count of the protocol; NaN with fewer than 8 samples. */
    static double recoveryHr(List<int[]> s) {
        double sum = 0; int n = 0; for (int[] x : s) if (x[0] >= 5 && x[0] < 20) { sum += x[1]; n++; } return n >= 8 ? sum / n : Double.NaN;
    }

    static void show(final Activity a) {
        final SharedPreferences sp = a.getSharedPreferences("healthspan", Context.MODE_PRIVATE);
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE); dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final Handler ui = new Handler(Looper.getMainLooper());
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Step test", 22, INK, 300)); top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final boolean[] running = { false };
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { running[0] = false; ui.removeCallbacksAndMessages(null); dlg.dismiss(); } });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface d) { running[0] = false; ui.removeCallbacksAndMessages(null); } });
        final Runnable[] setup = new Runnable[1];
        setup[0] = new Runnable() { @Override public void run() {
            col.removeAllViews(); final boolean male = sp.getBoolean("male", true);
            TextView intro = HeartGraph.label(a,
                "Step up and down a sturdy step 41 cm (16 inches) high for exactly 3 minutes to the beat: up, up, down, down. " + (male ? "Men: 24 steps a minute, 96 beats a minute." : "Women: 22 steps a minute, 88 beats a minute.") + "\n\n"
                + "Then stand still. The app takes your heart rate over the 15 seconds that start 5 seconds after you stop. Wear the Polar H10 with wet electrodes and start its recording first; without it you will count your pulse.\n\n"
                + "A step lower than 41 cm makes the work easier and the score too high. Stop at once with chest pain, dizziness or unusual breathlessness, and use a stable step. Rough estimate, probably within 10 to 15 %; best for repeating the test.", 13, MUT, 400);
            intro.setLineSpacing(0, 1.25f); col.addView(intro, HeartGraph.lp(a, -1, -2, 14));
            final TextView sex = HeartGraph.pill(a, male ? "Sex: male (tap to change)" : "Sex: female (tap to change)", 0x1AFFFFFF, INK);
            sex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sp.edit().putBoolean("male", !sp.getBoolean("male", true)).apply(); setup[0].run(); } }); col.addView(sex, HeartGraph.lp(a, -2, -2, 10));
            boolean live = PolarH10.wanted && PolarH10.hrNow() > 0;
            col.addView(HeartGraph.label(a, live ? "H10: live, heart rate " + PolarH10.hrNow() : "H10: not recording (you will count your pulse)", 13, live ? GREEN : ROSE, 400), HeartGraph.lp(a, -1, -2, 12));
            TextView go = HeartGraph.pill(a, "START THE 3 MINUTES", GREEN, 0xFF05131E); go.setTextSize(16);
            go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stepping(a, sp, col, ui, running, setup[0], male); } }); col.addView(go, HeartGraph.lp(a, -1, -2, 18));
            String last = sp.getString("vo2_hist", ""); if (last.length() > 0) { TextView h = HeartGraph.label(a, "Earlier results\n" + last, 12, MUT, 400); h.setLineSpacing(0, 1.2f); col.addView(h, HeartGraph.lp(a, -1, -2, 18)); }
        } };
        setup[0].run(); dlg.setContentView(root); dlg.show();
    }

    private static void stepping(final Activity a, final SharedPreferences sp, final LinearLayout col, final Handler ui, final boolean[] running, final Runnable back, final boolean male) {
        col.removeAllViews(); running[0] = true; final long t0 = System.currentTimeMillis(); final long beatMs = male ? 625L : 682L;
        final TextView word = HeartGraph.label(a, "GET READY", 44, INK, 200); word.setGravity(Gravity.CENTER); col.addView(word, HeartGraph.lp(a, -1, -2, 20));
        final View dot = new View(a); final GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(alpha(BLUE, 0.6f)); dot.setBackground(g);
        int sz = Math.round(dp(a, 150)); LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(sz, sz); dl.topMargin = Math.round(dp(a, 20)); dl.gravity = Gravity.CENTER_HORIZONTAL; col.addView(dot, dl);
        final TextView clk = HeartGraph.label(a, "3:00", 40, INK, 200); clk.setGravity(Gravity.CENTER); col.addView(clk, HeartGraph.lp(a, -1, -2, 20));
        final TextView hrv = HeartGraph.label(a, "", 16, GREEN, 400); hrv.setGravity(Gravity.CENTER); col.addView(hrv, HeartGraph.lp(a, -1, -2, 8));
        final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 85); final String[] cue = { "UP", "UP", "DOWN", "DOWN" }; final int[] beat = { 0 };
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() { @Override public void run() {
            if (!running[0]) return; long el = System.currentTimeMillis() - t0;
            if (el >= 180000L) { try { tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 700); } catch (Throwable ignored) { } measure(a, sp, col, ui, running, back, male); return; }
            word.setText(cue[beat[0] % 4]); g.setColor(beat[0] % 2 == 0 ? alpha(GREEN, 0.9f) : alpha(BLUE, 0.6f)); beat[0]++; try { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 70); } catch (Throwable ignored) { }
            long left = 180000L - el; clk.setText(String.format(Locale.UK, "%d:%02d", left / 60000L, (left / 1000L) % 60 + (left % 1000L > 0 && left < 60000L ? 0 : 0))); int h = PolarH10.wanted ? PolarH10.hrNow() : 0; hrv.setText(h > 0 ? "H10 heart rate " + h : "");
            ui.postDelayed(this, beatMs); } };
        tick[0].run();
        TextView stop = HeartGraph.pill(a, "Stop and cancel", 0x1AFFFFFF, INK); stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { running[0] = false; ui.removeCallbacksAndMessages(null); back.run(); } });
        col.addView(stop, HeartGraph.lp(a, -2, -2, 20)); ((LinearLayout.LayoutParams) stop.getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
    }

    private static void measure(final Activity a, final SharedPreferences sp, final LinearLayout col, final Handler ui, final boolean[] running, final Runnable back, final boolean male) {
        col.removeAllViews(); final long t1 = System.currentTimeMillis(); final List<int[]> s = new ArrayList<int[]>();
        final TextView word = HeartGraph.label(a, "STAND STILL", 40, INK, 200); word.setGravity(Gravity.CENTER); col.addView(word, HeartGraph.lp(a, -1, -2, 30));
        final TextView info = HeartGraph.label(a, "", 18, MUT, 400); info.setGravity(Gravity.CENTER); info.setLineSpacing(0, 1.25f); col.addView(info, HeartGraph.lp(a, -1, -2, 20));
        final boolean live = PolarH10.wanted && PolarH10.hrNow() > 0;
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() { @Override public void run() {
            if (!running[0]) return; int sec = (int) ((System.currentTimeMillis() - t1) / 1000L);
            if (live) { int h = PolarH10.hrNow(); if (h > 0) s.add(new int[] { sec, h }); info.setText(sec < 5 ? "Settling: " + (5 - sec) + " s" : sec < 20 ? "Measuring: " + (20 - sec) + " s   (heart rate " + h + ")" : ""); }
            else info.setText(sec < 5 ? "Find your pulse. Counting starts in " + (5 - sec) + " s" : sec < 20 ? "COUNT your pulse now: " + (20 - sec) + " s" : "");
            if (sec >= 21) { running[0] = false; result(a, sp, col, back, male, live ? recoveryHr(s) : Double.NaN); return; }
            ui.postDelayed(this, 1000L); } };
        tick[0].run();
    }

    private static void result(final Activity a, final SharedPreferences sp, final LinearLayout col, final Runnable back, final boolean male, double hr) {
        col.removeAllViews();
        col.addView(HeartGraph.label(a, Double.isNaN(hr) ? "Enter the pulse you counted in the 15 seconds (beats)" : "H10 heart rate over the 15 seconds: " + Math.round(hr) + " bpm", 14, INK, 400), HeartGraph.lp(a, -1, -2, 20));
        final EditText e = new EditText(a); e.setHint(Double.isNaN(hr) ? "beats in 15 s" : "bpm"); e.setText(Double.isNaN(hr) ? "" : String.valueOf(Math.round(hr))); e.setTextColor(INK); e.setHintTextColor(MUT); e.setTextSize(18); e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setBackground(glass(a, 12));
        int p = Math.round(dp(a, 12)); e.setPadding(p, p, p, p); col.addView(e, HeartGraph.lp(a, -1, -2, 8));
        final boolean counted = Double.isNaN(hr);
        final TextView big = HeartGraph.label(a, "", 48, GREEN, 200); big.setGravity(Gravity.CENTER); final TextView note = HeartGraph.label(a, "", 13, MUT, 400); note.setGravity(Gravity.CENTER); note.setLineSpacing(0, 1.2f);
        final double[] val = { Double.NaN }; final double[] hu = { Double.NaN };
        final Runnable calc = new Runnable() { @Override public void run() {
            double x = Double.NaN; try { x = Double.parseDouble(e.getText().toString().trim()); } catch (Exception ignored) { } if (counted && !Double.isNaN(x)) x *= 4.0;
            if (Double.isNaN(x) || x < 50 || x > 220) { big.setText("--"); note.setText(counted ? "Enter the beats counted in 15 seconds." : "Enter the heart rate."); val[0] = Double.NaN; return; }
            double v = vo2(male, x); val[0] = v; hu[0] = x; big.setText(String.format(Locale.UK, "%.1f", v));
            note.setText("ml/kg/min from a heart rate of " + Math.round(x) + " bpm. Rough: the equation comes from young adults, so expect +/-10 to 15 %. Use it to compare repeats, and prefer the Rockport walk for a number."); } };
        e.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int i, int j, int k) { } public void onTextChanged(CharSequence s, int i, int j, int k) { } public void afterTextChanged(Editable ed) { calc.run(); } });
        col.addView(big, HeartGraph.lp(a, -1, -2, 24)); col.addView(note, HeartGraph.lp(a, -1, -2, 4)); calc.run();
        TextView save = HeartGraph.pill(a, "SAVE AS MY VO2 MAX", GREEN, 0xFF05131E); save.setTextSize(16);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (Double.isNaN(val[0])) return;
            String line = new java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(new java.util.Date()) + ": " + String.format(Locale.UK, "%.1f", val[0]) + " (step test, HR " + Math.round(hu[0]) + ")";
            String old = sp.getString("vo2_hist", ""); String[] ls = old.length() == 0 ? new String[0] : old.split("\n"); StringBuilder sb = new StringBuilder(line); for (int i = 0; i < Math.min(5, ls.length); i++) sb.append('\n').append(ls[i]);
            sp.edit().putFloat("vo2", (float) val[0]).putString("vo2_src", "Queens College step test").putLong("vo2_date", System.currentTimeMillis()).putString("vo2_hist", sb.toString()).apply();
            ((TextView) v).setText("Saved. Healthspan now uses it."); v.setEnabled(false); } });
        TextView again = HeartGraph.pill(a, "Back", 0x1AFFFFFF, INK); again.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { back.run(); } });
        col.addView(save, HeartGraph.lp(a, -1, -2, 24)); col.addView(again, HeartGraph.lp(a, -2, -2, 12));
    }
}
