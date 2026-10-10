package com.noop.mgecg;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Control and context screen for the all-day logger: on/off, pull interval, markers, cuff readings, export. */
public class DayLogActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1220");
    private static final int FG = Color.parseColor("#E6EEF8");
    private static final int ACCENT = Color.parseColor("#22E6D6");
    private TextView status, out;
    private long lastSummaryMs = 0L;
    private boolean showSleep = false;
    private Button toggle;
    private EditText sys, dia, pulse, note;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 5000L); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        col.addView(text("MG DAY LOGGER", 20, true));
        status = text("", 13, false);
        col.addView(status);

        toggle = btn("", new View.OnClickListener() { @Override public void onClick(View v) { toggleLogger(); } });
        col.addView(toggle);

        col.addView(label("PULL THE STRAP'S HISTORY EVERY"));
        LinearLayout iv = row();
        for (final int m : new int[] {15, 30, 60}) {
            iv.addView(weight(btn(m + " min", new View.OnClickListener() {
                @Override public void onClick(View v) { DayLog.setIntervalMin(DayLogActivity.this, m); refresh(); }
            })));
        }
        col.addView(iv);

        LinearLayout r2 = row();
        r2.addView(weight(btn("PULL NOW", new View.OnClickListener() {
            @Override public void onClick(View v) { MainActivity.requestPull(); toast("Asked the main app to pull; it must be open and connected"); }
        })));
        r2.addView(weight(btn("EXPORT CSV", new View.OnClickListener() {
            @Override public void onClick(View v) { toast(DayLog.exportCsv(DayLogActivity.this)); }
        })));
        col.addView(r2);
        col.addView(btn("BATTERY SETTINGS (choose this app, then Don't optimise)", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        }));

        col.addView(label("MARKERS (time-stamped, for correlating later)"));
        String[][] marks = {{"CAFFEINE", "caffeine"}, {"MEAL", "meal"}, {"WORKOUT START", "workout_start"}, {"WORKOUT END", "workout_end"},
                {"STRESS", "stress"}, {"SYMPTOM", "symptom"}, {"GOING TO BED", "bed"}, {"WOKE UP", "woke"}};
        for (int i = 0; i < marks.length; i += 2) {
            LinearLayout r = row();
            for (int j = i; j < i + 2 && j < marks.length; j++) {
                final String name = marks[j][0], kind = marks[j][1];
                r.addView(weight(btn(name, new View.OnClickListener() {
                    @Override public void onClick(View v) { DayLog.addEvent(DayLogActivity.this, "marker", kind); toast(name + " saved"); DayLogService.refresh(DayLogActivity.this); }
                })));
            }
            col.addView(r);
        }

        col.addView(label("CUFF READING"));
        LinearLayout bp = row();
        sys = num("sys"); dia = num("dia"); pulse = num("pulse");
        bp.addView(weight(sys)); bp.addView(weight(dia)); bp.addView(weight(pulse));
        col.addView(bp);
        col.addView(btn("SAVE CUFF READING", new View.OnClickListener() { @Override public void onClick(View v) { saveBp(); } }));

        col.addView(label("NOTE"));
        note = new EditText(this);
        note.setTextColor(FG); note.setHintTextColor(Color.GRAY); note.setHint("free text");
        col.addView(note);
        col.addView(btn("SAVE NOTE", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = note.getText().toString().trim();
                if (t.length() == 0) return;
                DayLog.addEvent(DayLogActivity.this, "note", t); note.setText(""); toast("note saved");
            }
        }));

        col.addView(label("SUMMARY"));
        LinearLayout r3 = row();
        r3.addView(weight(btn("SLEEP REPORT", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSleep = true;
                out.setText("working...");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final String t = DayLog.sleepReport(DayLogActivity.this, 7);
                        runOnUiThread(new Runnable() { @Override public void run() { out.setText(t); } });
                    }
                }).start();
            }
        })));
        r3.addView(weight(btn("24 H SUMMARY", new View.OnClickListener() {
            @Override public void onClick(View v) { showSleep = false; lastSummaryMs = 0L; refresh(); }
        })));
        col.addView(r3);
        out = text("", 12, false);
        col.addView(out);
        setContentView(sv);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private void toggleLogger() {
        boolean on = !DayLog.enabled(this);
        DayLog.setEnabled(this, on);
        if (on) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 31);
            DayLogService.start(this);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(i);
            toast("Logger on. The main app opens so it can connect to the strap. Connect once there if it never has been.");
        } else {
            DayLogService.stop(this);
            toast("Logger off");
        }
        refresh();
    }

    private void saveBp() {
        try {
            int s = Integer.parseInt(sys.getText().toString().trim());
            int d = Integer.parseInt(dia.getText().toString().trim());
            int p = Integer.parseInt(pulse.getText().toString().trim());
            if (s < 60 || s > 260 || d < 30 || d > 160 || p < 30 || p > 220 || d >= s) { toast("Those numbers do not look right"); return; }
            DayLog.addEvent(this, "bp", s + "/" + d + " pulse " + p);
            sys.setText(""); dia.setText(""); pulse.setText("");
            toast("cuff reading saved");
        } catch (NumberFormatException e) { toast("Enter all three numbers"); }
    }

    private void refresh() {
        toggle.setText(DayLog.enabled(this) ? "LOGGER IS ON (tap to turn off)" : "LOGGER IS OFF (tap to turn on)");
        status.setText(DayLog.statusLine(this) + "\npull interval " + DayLog.intervalMin(this) + " min");
        long now = System.currentTimeMillis();
        if (!showSleep && now - lastSummaryMs > 60000L) { lastSummaryMs = now; out.setText(DayLog.summary(this)); }   // the summary reads a day of records, so not every 5 s
    }

    // ------------------------------------------------------------------ small view helpers
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(bold ? ACCENT : FG);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }
    private TextView label(String s) { TextView t = text(s, 12, true); t.setPadding(0, dp(12), 0, dp(2)); return t; }
    private Button btn(String s, View.OnClickListener l) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(l); return b; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private View weight(View v) { v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); return v; }
    private EditText num(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(Color.GRAY); e.setTextColor(FG); e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
