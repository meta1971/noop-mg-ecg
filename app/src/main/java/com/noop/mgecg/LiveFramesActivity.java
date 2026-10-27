package com.noop.mgecg;

// FILE VERSION 0.20.0 (27 Oct 2026): the "Live frames" screen: every packet the strap sends, counted and listed as it arrives.

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The old log screen printed a line for every frame, and with the near-live offload running it hid the live ones (channel 0005 is silent on that screen while a pull runs).
 * This screen reads FrameMonitor instead: a table with one row per frame kind (total, per second, seconds since the last one, the last frame's fields), green while frames
 * arrive, and the newest lines below, live or history. Freeze stops the refresh, Copy puts the table and the lines on the clipboard.
 */
public class LiveFramesActivity extends Activity {
    private static final int BG = 0xFF0D1015, INK = 0xFFE8EEF8, MUT = 0xFF8FA1BD, CARD = 0xFF1A2230, GREEN = 0xFF2EE6A8, AMBER = 0xFFFFB347;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView tableView, linesView, freezeBtn, modeBtn, sinceView;
    private boolean frozen, history;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView pill(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(INK);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        b.setBackground(g);
        return b;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!frozen) refresh();
            ui.postDelayed(this, 300L);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(4));
        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(INK);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(22));
        back.setBackground(bg);
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("Live frames");
        title.setTextColor(INK);
        title.setTextSize(20);
        title.setPadding(dp(14), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pills = new LinearLayout(this);
        pills.setPadding(dp(12), dp(4), dp(12), dp(6));
        freezeBtn = pill("Freeze");
        modeBtn = pill("Showing: live");
        TextView copy = pill("Copy");
        TextView clear = pill("Clear");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        pp.rightMargin = dp(6);
        pills.addView(freezeBtn, pp);
        pills.addView(modeBtn, pp);
        pills.addView(copy, pp);
        pills.addView(clear, new LinearLayout.LayoutParams(0, dp(38), 1f));
        root.addView(pills, new LinearLayout.LayoutParams(-1, -2));
        freezeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { frozen = !frozen; freezeBtn.setText(frozen ? "Frozen: tap to run" : "Freeze"); freezeBtn.setTextColor(frozen ? AMBER : INK); if (!frozen) refresh(); } });
        modeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { history = !history; modeBtn.setText(history ? "Showing: history" : "Showing: live"); refresh(); } });
        copy.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copyAll(); } });
        clear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { FrameMonitor.clear(); refresh(); } });

        sinceView = new TextView(this);
        sinceView.setTextColor(MUT);
        sinceView.setTextSize(11);
        sinceView.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(sinceView, new LinearLayout.LayoutParams(-1, -2));

        tableView = mono(11.5f);
        tableView.setPadding(dp(12), dp(6), dp(12), dp(6));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(tableView);
        root.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        linesView = mono(10.5f);
        linesView.setPadding(dp(12), dp(4), dp(12), dp(12));
        ScrollView sv = new ScrollView(this);
        android.widget.HorizontalScrollView hs2 = new android.widget.HorizontalScrollView(this);
        hs2.addView(linesView);
        sv.addView(hs2);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private TextView mono(float sp) {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextColor(INK);
        t.setHorizontallyScrolling(true);
        return t;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        SpannableStringBuilder t = new SpannableStringBuilder();
        List<FrameMonitor.RowInfo> rows = FrameMonitor.rows(now);
        if (rows.isEmpty()) {
            t.append("No frame yet. The strap link must be up; with the day logger on, a frame arrives every second.");
        } else {
            t.append(String.format(Locale.UK, "%-40s %7s %7s %7s\n", "kind", "total", "per s", "ago"));
            for (FrameMonitor.RowInfo i : rows) {
                int start = t.length();
                t.append(String.format(Locale.UK, "%s%-40s %7d %7.1f %6.1fs\n", i.active ? "\u25CF " : "\u25CB ", i.name.length() > 38 ? i.name.substring(0, 38) : i.name, i.total, i.perSecond, i.agoSeconds));
                if (i.last != null && !i.last.isEmpty()) {
                    int s2 = t.length();
                    t.append("    " + i.last + "\n");
                    t.setSpan(new ForegroundColorSpan(MUT), s2, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                t.setSpan(new ForegroundColorSpan(i.active ? GREEN : MUT), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        tableView.setText(t);
        StringBuilder l = new StringBuilder();
        for (String s : FrameMonitor.lines(history, 60)) l.append(s).append('\n');
        linesView.setText(l.length() == 0 ? (history ? "No history frame yet. They arrive in bursts while a pull runs." : "No live frame yet.") : l.toString());
        sinceView.setText("Counting since " + new java.text.SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK).format(new java.util.Date(FrameMonitor.sinceMs())) + ". Green dot: a frame in the last 2.5 s. Newest line first.");
    }

    private void copyAll() {
        try {
            long now = System.currentTimeMillis();
            StringBuilder s = new StringBuilder("Live frames " + new java.text.SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.UK).format(new java.util.Date(now)) + "\n");
            s.append(FrameMonitor.table(now)).append("\nLive lines (newest first):\n");
            for (String x : FrameMonitor.lines(false, 60)) s.append(x).append('\n');
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("live frames", s.toString()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) { Toast.makeText(this, "Could not copy", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() { super.onResume(); refresh(); ui.postDelayed(tick, 300L); }
    @Override protected void onPause() { ui.removeCallbacks(tick); super.onPause(); }
}
