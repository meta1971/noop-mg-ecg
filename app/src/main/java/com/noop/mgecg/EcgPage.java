package com.noop.mgecg;

// FILE VERSION 0.43.4 (6 Nov 2026): the reading page rebuilt from the screenshot of the "Hi, Ame" page (HealthActivity): greeting + big title + LIVE chip, ONE status card like "Synced 17 min ago / Sync now" with a green pill, the same three rings
// (DailyViews.RingsView) with thin figures and caps labels, one plain sentence, caps-titled cards, a blue "More about this" link, the glow behind, the floating bottom bar. The rings are real measures, no countdown:
//   AT REST   the last reading: heart rate, HRV, grade.   RUNNING   live heart rate, noise in the QRS band, the strap's own check (its 0 to 100 %).   DONE   heart rate, strap HRV, noise.
// The status card is the latest reading with Send (rest), the signal light (running) or the strap verdict with Send (done). Lab: runs A-F as a checklist with ticks from reading_notes (LabTests), one tap starts a test.
// Every step is guarded: a failure is written to the page footer and the rest of the page still works (decode log 116.5).

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Locale;

final class EcgPage {
    static final String VERSION = "0.45.1-pro";
    static final int REST = 0, RUN = 1, DONE = 2;
    private static final int BLUE = HealthViews.BLUE, INK = HealthViews.INK, MUT = HealthViews.MUT, GREEN = HealthViews.GREEN, GOLD = HealthViews.GOLD, ROSE = HealthViews.ROSE, VIOLET = HealthViews.VIOLET, DARK = 0xFF05131E;

    private final NewLookActivity a;
    private final SharedPreferences pr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GuidedRun guide = new GuidedRun();

    // views
    private LinearLayout colRef, bar, headerRow, statusCard, waveHost, detailsBody, resultDetails, liveCard, avgCard, nextCard, setupCard, cuePill, whyRow, holder;
    private TextView greetView, titleView, linkChip, stTitle, stSub, stPill, planView, noteView, sendNote, detailsHeader, resultBody, whyHint, strapLine, bandsView, avgText, nextText, nextPlain, setupVal, labVal, cueView, footer, nextBtn, targetBtn, countPill;
    private DailyViews.RingsView rings;
    private final float[] rFrac = new float[3];
    private final int[] rCol = { ROSE, BLUE, VIOLET };
    private final String[] rBig = { "--", "--", "--" }, rLab = { "HEART RATE", "STRAP HRV", "NOISE" }, rCap = { "", "", "" };
    private File latestFile; private long latestStart;
    private AvgBeatView avgView;
    private ProgressLine cueLine;
    private Button start;
    private LinearLayout waveCardRef;
    private LinearLayout bankCard; private TextView bankText, bankNote; private ProgressLine bankLine; private BankView bankView; private volatile boolean bankBusy; private static volatile BeatBank.Pooled bankPooled; private static volatile String bankSig = "";      // 0.45.0 (d): the personal beat bank card
    private LinearLayout replayCard; private ReplayView replayView; private volatile boolean replayLoading; private long replayFor = -1L;      // 0.44.0: replay of the finished reading
    private LinearLayout setupBody;
    private final TextView[] rowValues = new TextView[9];
    private ScrollView scrollRef;
    private boolean guidedThisRun, detailsOpen, readyCancelled;
    // 0.45.0 (n): STOP has no hold. A tap starts a 3 s "Stopping, tap to cancel" bar; tapping the bar cancels. Guided, timed and auto stops click the button directly.
    private LinearLayout stopBar; private TextView stopBarText; private ProgressLine stopLine; private boolean stopPending; private long stopT0, stopTapMs; private Runnable stopTick;
    private static final long STOP_DELAY_MS = 3000L;
    // 0.45.0 (b), (c): a free reading with auto-stop on ends when the fingers have been off for LIFT_STOP_S seconds once LIFT_MIN_CLEAN clean beats are in (and 60 s have passed); the auto-stop also waits for CLEAN_MIN clean beats (the two-half rhythm test needs 128); CLEAN_TARGET is the counter's goal (the beat bank).
    private static final int LIFT_STOP_S = 3, LIFT_MIN_CLEAN = 64, CLEAN_MIN = 128, CLEAN_TARGET = 300;
    private int cueCount, mode = REST, runStopSec, readyLeft;
    private android.speech.tts.TextToSpeech tts; private volatile boolean ttsOk;

    // state
    private boolean running, autoStopped;
    private long startMs, lastRestMs; private String cueLog = "", lastHr = "", nextId = "";
    private long cueUntil; private String lastCue = "";
    private LiveMeasure.Out lastOut; private double[] lastBands;
    private int stopReason;           // 0 by hand, 1 auto-stop, 2 guided run, 3 fingers lifted (0.45.0)
    private String failures = "";
    private double lastDur;

    EcgPage(NewLookActivity act) { a = act; pr = act.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ helpers (the same looks as HealthActivity)
    private int dp(int v) { return a.dp(v); }
    /** Ring fraction of a heart rate: against the max heart rate of the Today page (default 167). */
    private float hrFrac(String hr) { try { int mx = a.getSharedPreferences("today", android.content.Context.MODE_PRIVATE).getInt("maxhr", 167); return Math.max(0f, Math.min(1f, Integer.parseInt(hr.trim()) / (float) Math.max(100, mx))); } catch (Throwable e) { return 0f; } }
    private static float clamp01(double v) { return Double.isNaN(v) ? 0f : (float) Math.max(0.0, Math.min(1.0, v)); }
    private TextView t(String s, float sp, int color, int weight) {
        TextView x = new TextView(a); x.setText(s); x.setTextSize(sp); x.setTextColor(color); x.setTypeface(HealthViews.font(a, weight)); x.setIncludeFontPadding(false); return x;
    }
    private TextView caps(String s) { TextView x = t(s, 11, MUT, 400); x.setLetterSpacing(0.16f); return x; }
    private LinearLayout glass(int pad) {
        LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(HealthViews.glass(a, 22)); c.setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); return c;
    }
    private LinearLayout hrow() { LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private View spacer() { View v = new View(a); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f)); return v; }
    private TextView chip(String s) {
        TextView x = t(s, 12, INK, 400); x.setGravity(Gravity.CENTER); x.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable g = new GradientDrawable(); g.setColor(0x0DFFFFFF); g.setCornerRadius(HealthViews.dp(a, 17)); g.setStroke(Math.max(1, dp(1)), 0x0FFFFFFF); x.setBackground(g); return x;
    }
    private TextView pill(String s, int bg, int fg) {
        TextView x = t(s, 14, fg, 600); x.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(HealthViews.dp(a, 22)); x.setBackground(g); return x;
    }
    private LinearLayout.LayoutParams lp(int w, int hgt, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hgt); p.topMargin = dp(top); return p; }
    private File dir() { return a.getExternalFilesDir(null); }
    private String pref(String k, String d) { return pr.getString(k, d); }

    private void fail(String step, Throwable e) {
        failures += (failures.isEmpty() ? "" : "; ") + step + ": " + e.getClass().getSimpleName();
        try { pr.edit().putString("page_fail", failures).apply(); } catch (Throwable ignored) { }
        if (footer != null) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
    }

    /** MainActivity's font pass must not flatten the weights of this page. */
    boolean skipFont(View v) { return v == colRef || v == bar; }

    // ------------------------------------------------------------------ build
    /** Called once from NewLookActivity.styleEcgColumn. The old rows are hidden; the live graph and the START / STOP button are reused. */
    void build(FrameLayout container, LinearLayout col, View top, TextView status, TextView verdict, LinearLayout waveCard, LinearLayout stats, Button startBtn) {
        start = startBtn; waveCardRef = waveCard; colRef = col;
        // 0.45.0 (n): while a reading runs a tap on STOP does not stop at once: it starts a 3 s "Stopping, tap to cancel" bar (a thumb brushing the button while scrolling stopped the 22:00 reading on 5 Nov; the 1 s hold of 0.42.3 is gone). Timed, guided and auto stops click the button directly.
        start.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(final View v, MotionEvent e) {
            if (!start.getText().toString().startsWith("STOP")) return false;
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) { v.setPressed(true); return true; }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                boolean inside = e.getX() >= 0 && e.getX() <= v.getWidth() && e.getY() >= 0 && e.getY() <= v.getHeight();      // a drag that ends outside the button is a scroll, not a tap
                if (act == MotionEvent.ACTION_UP && inside && !stopPending) beginStop();
            }
            return true;
        } });
        try { if (col.getParent() instanceof ScrollView) scrollRef = (ScrollView) col.getParent(); } catch (Throwable ignored) { }
        applyDisplayPrefs();
        try { if (pr.getLong("run_armed_ms", 0L) + 90000L < System.currentTimeMillis()) pr.edit().putString("run_id", "").apply(); } catch (Throwable ignored) { }      // a test selected in 0.42.x must never start a normal reading
        // the START / STOP button goes into a pinned bar that floats on a fade, like the bottom bar of the Home pages
        try {
            if (startBtn.getParent() != null) ((ViewGroup) startBtn.getParent()).removeView(startBtn);
            start.setAllCaps(false); start.setTextSize(17); start.setTypeface(HealthViews.font(a, 600)); start.setTextColor(DARK);
            bar = new LinearLayout(a); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x0007090C, 0xF007090C, 0xFF07090C })); bar.setPadding(dp(18), dp(30), dp(18), dp(18));
            stopBar = new LinearLayout(a); stopBar.setOrientation(LinearLayout.VERTICAL); stopBar.setBackground(HealthViews.glass(a, 18)); stopBar.setPadding(dp(16), dp(10), dp(16), dp(10)); stopBar.setVisibility(View.GONE);
            stopBarText = t("Stopping, tap to cancel", 15, INK, 500); stopBarText.setGravity(Gravity.CENTER); stopBar.addView(stopBarText, new LinearLayout.LayoutParams(-1, -2));
            stopLine = new ProgressLine(a, GOLD, 0x33FFFFFF); LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(3)); slp.topMargin = dp(8); stopBar.addView(stopLine, slp);
            stopBar.setOnClickListener(v -> cancelStop(true));
            LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2); sbp.bottomMargin = dp(10); bar.addView(stopBar, sbp);
            bar.addView(startBtn, new LinearLayout.LayoutParams(-1, dp(58)));
            container.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        } catch (Throwable e) { fail("pinned bar", e); }
        // the glow of the Home pages behind the page; every old row hidden; the live graph taken out to be reused
        try { container.addView(new HealthActivity.GlowBg(a), 0, new FrameLayout.LayoutParams(-1, -1)); } catch (Throwable e) { fail("glow", e); }
        try {
            if (waveCard.getParent() instanceof ViewGroup) ((ViewGroup) waveCard.getParent()).removeView(waveCard);
            for (int i = 0; i < col.getChildCount(); i++) col.getChildAt(i).setVisibility(View.GONE);
            col.setPadding(dp(18), dp(40), dp(18), dp(130));
        } catch (Throwable e) { fail("old rows", e); }
        holder = new LinearLayout(a); holder.setOrientation(LinearLayout.VERTICAL); holder.setVisibility(View.GONE);
        col.addView(holder, new LinearLayout.LayoutParams(-1, -2));
        a.readingTag = pref("reading_tag", a.readingTag); a.positionTag = pref("position_tag", a.positionTag);
        try { buildHeader(col); } catch (Throwable e) { fail("header", e); }
        try { buildStatus(col); } catch (Throwable e) { fail("status card", e); }
        try { buildRings(col); } catch (Throwable e) { fail("rings", e); }
        try { buildRunning(col, waveCard); } catch (Throwable e) { fail("live trace", e); }
        try { buildDetails(col); } catch (Throwable e) { fail("details", e); }
        try { buildCards(col); } catch (Throwable e) { fail("next test and setup", e); }
        try {
            footer = t("", 11, GOLD, 400); footer.setGravity(Gravity.CENTER); footer.setVisibility(View.GONE);
            col.addView(footer, lp(-1, -2, 12));
            String old = pr.getString("page_fail", ""); if (!failures.isEmpty()) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
            else if (!old.isEmpty()) pr.edit().remove("page_fail").apply();
        } catch (Throwable e) { /* cosmetic */ }
        applyMode(REST);
    }

    /** Called at the end of NewLookActivity.styleEcgColumn: the old Markers, Packets and Research rows move under "More about this"; the old "preparing" line is dropped. */
    void finishLayout(View... extras) {
        try {
            for (View v : extras) {
                if (v == null) continue;
                ViewGroup.LayoutParams old = v.getLayoutParams();
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                boolean drop = v instanceof TextView && ((TextView) v).getText().toString().startsWith("Preparing your report");
                if (drop || detailsBody == null) { holder.addView(v); continue; }
                if (old instanceof LinearLayout.LayoutParams) detailsBody.addView(v, old); else detailsBody.addView(v, lp(-1, -2, 6));
            }
        } catch (Throwable e) { fail("details rows", e); }
    }

    private void buildHeader(LinearLayout col) {
        headerRow = hrow(); headerRow.setGravity(Gravity.TOP);
        TextView back = t("\u2039", 28, INK, 300); back.setGravity(Gravity.CENTER); back.setBackground(HealthViews.glass(a, 20)); back.setContentDescription("Back");
        back.setOnClickListener(v -> { if (!running) a.onBackPressed(); else a.toast("A reading is running. Tap STOP to end it."); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(40), dp(40)); bp.rightMargin = dp(12); headerRow.addView(back, bp);
        LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL);
        greetView = t("", 13, MUT, 400); titleView = t("", 28, INK, 400); titleView.setPadding(0, dp(6), 0, 0);
        l.addView(greetView); l.addView(titleView);
        headerRow.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        linkChip = chip(""); headerRow.addView(linkChip);
        col.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
        updateLink();
    }

    private void updateLink() {
        try {
            if (linkChip == null) return;
            LinkState.Info li = MainActivity.linkState(a);
            linkChip.setText("\u25CF  " + li.chip); linkChip.setTextColor(li.level == LinkState.GOOD ? GREEN : li.level == LinkState.WARN ? GOLD : ROSE);
        } catch (Throwable ignored) { }
    }

    /** The "Synced 17 min ago / Sync now" card of the Home page, reused for the three states. */
    private void buildStatus(LinearLayout col) {
        statusCard = glass(12);
        LinearLayout r = hrow();
        LinearLayout left = new LinearLayout(a); left.setOrientation(LinearLayout.VERTICAL);
        stTitle = t("", 14, INK, 600); stTitle.setLineSpacing(0, 1.15f); stSub = t("", 11, MUT, 400); stSub.setLineSpacing(0, 1.15f);
        left.addView(stTitle); left.addView(stSub, lp(-2, -2, 2));
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        stPill = HeartGraph.pill(a, "Send", 0x1A2EE6A8, 0xFF2EE6A8);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.leftMargin = dp(10); r.addView(stPill, pp);
        statusCard.addView(r, new LinearLayout.LayoutParams(-1, -2));
        sendNote = t("", 11, MUT, 400); sendNote.setLineSpacing(0, 1.2f); sendNote.setVisibility(View.GONE); statusCard.addView(sendNote, lp(-1, -2, 8));
        col.addView(statusCard, lp(-1, -2, 14));
        // the 5 s auto-start countdown, shown inside the page
        countPill = pill("", BLUE, DARK); countPill.setTextSize(14); countPill.setMaxLines(2); countPill.setPadding(dp(18), dp(10), dp(18), dp(10)); countPill.setVisibility(View.GONE);
        countPill.setOnClickListener(v -> { a.cancelAutoCountdown(); a.toast("Not starting by itself. Press START ECG when you are ready."); });
        col.addView(countPill, lp(-1, -2, 10));
        // the cue pill of a guided test: one slim line
        cuePill = new LinearLayout(a); cuePill.setOrientation(LinearLayout.VERTICAL); cuePill.setVisibility(View.GONE);
        cueView = pill("", BLUE, DARK); cueView.setTextSize(15); cueView.setMaxLines(2); cueView.setPadding(dp(18), dp(10), dp(18), dp(10)); cueView.setMinHeight(dp(56));
        cueView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        cuePill.addView(cueView, new LinearLayout.LayoutParams(-1, -2));
        cueLine = new ProgressLine(a, BLUE, 0x22FFFFFF); cuePill.addView(cueLine, lp(-1, dp(3), 6));
        cuePill.setOnClickListener(v -> { if (!running && readyLeft > 0) { readyCancelled = true; pr.edit().putString("run_id", "").apply(); cuePill.setVisibility(View.GONE); a.toast("Test cancelled"); } });
        col.addView(cuePill, lp(-1, -2, 10));
    }

    /** The three rings of the Home page: the same view, real measures. */
    private void buildRings(LinearLayout col) {
        rings = new DailyViews.RingsView(a, rFrac, rCol, rBig, rLab, rCap);
        col.addView(rings, lp(-1, dp(196), 12));
        planView = t("", 14, INK, 400); planView.setLineSpacing(0, 1.25f);
        col.addView(planView, lp(-2, -2, 8));
        noteView = t("Research only: not a diagnosis. The strap's own check and your report show how reliable each reading was.", 11, MUT, 400); noteView.setLineSpacing(0, 1.2f);
        col.addView(noteView, lp(-2, -2, 6));
    }

    private void setRing(int i, float f, int col, String big, String lab, String cap) { rFrac[i] = f; rCol[i] = col; rBig[i] = big; rLab[i] = lab; rCap[i] = cap; }
    private void drawRings() { if (rings != null) rings.invalidate(); }

    private void buildRunning(LinearLayout col, LinearLayout waveCard) {
        waveHost = glass(12);
        LinearLayout wh = hrow(); wh.addView(caps("LIVE ECG"), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView full = t("tap the graph for full screen", 10.5f, MUT, 400); wh.addView(full, new LinearLayout.LayoutParams(-2, -2));
        waveHost.addView(wh, new LinearLayout.LayoutParams(-1, -2));
        waveCard.setBackground(null); waveCard.setPadding(0, 0, 0, 0);
        waveHost.addView(waveCard, lp(-1, -2, 8));
        waveHost.setVisibility(View.GONE);
        col.addView(waveHost, lp(-1, -2, 12));
        // 0.44.0: after the reading the same place holds the replay of the whole recording
        replayCard = glass(12);
        replayCard.addView(caps("REPLAY"), new LinearLayout.LayoutParams(-2, -2));
        replayView = new ReplayView(a); replayCard.addView(replayView, lp(-1, dp(230), 8));
        TextView rn = t("Drag to move, pinch to zoom, double-tap to fit the whole reading. Amber and grey shading is the strap's own quality for that second; triangles are the beats found.", 11, MUT, 400); rn.setLineSpacing(0, 1.2f);
        replayCard.addView(rn, lp(-1, -2, 6));
        replayCard.setVisibility(View.GONE);
        col.addView(replayCard, lp(-1, -2, 12));
        // 0.45.0 (d): the personal beat bank: the 40 cleanest beats of every reading, pooled on the phone; the detail view unlocks at 1,000 beats
        bankCard = glass(12);
        bankCard.addView(caps("YOUR BEAT BANK"), new LinearLayout.LayoutParams(-2, -2));
        bankText = t("", 14, INK, 400); bankText.setLineSpacing(0, 1.2f); bankCard.addView(bankText, lp(-1, -2, 6));
        bankLine = new ProgressLine(a, GREEN, 0x22FFFFFF); bankCard.addView(bankLine, lp(-1, dp(3), 8));
        bankView = new BankView(a, INK, 0x33FFFFFF, MUT); bankView.setVisibility(View.GONE); bankCard.addView(bankView, lp(-1, dp(170), 10));
        bankNote = t("", 11, MUT, 400); bankNote.setLineSpacing(0, 1.2f); bankCard.addView(bankNote, lp(-1, -2, 6));
        bankCard.setVisibility(View.GONE);
        col.addView(bankCard, lp(-1, -2, 12));
    }

    private static final String[] WHY = { "Tension", "Vibration", "Cold hands", "Charger near", "Not sure" };
    private void buildDetails(LinearLayout col) {
        detailsHeader = t("\u25be  More about this", 14, BLUE, 400); detailsHeader.setPadding(0, dp(14), 0, dp(6));
        detailsHeader.setOnClickListener(v -> { detailsOpen = !detailsOpen; applyDetails(); });
        detailsHeader.setVisibility(View.GONE);
        col.addView(detailsHeader, new LinearLayout.LayoutParams(-2, -2));
        detailsBody = new LinearLayout(a); detailsBody.setOrientation(LinearLayout.VERTICAL); detailsBody.setVisibility(View.GONE);
        col.addView(detailsBody, lp(-1, -2, 0));
        // after a reading: every result line, the why-noisy tap
        resultDetails = glass(16);
        resultDetails.addView(caps("THIS READING"), new LinearLayout.LayoutParams(-2, -2));
        resultBody = t("", 13, INK, 400); resultBody.setLineSpacing(0, 1.25f); resultDetails.addView(resultBody, lp(-1, -2, 8));
        whyHint = t("What made it noisy? One tap labels this reading.", 12, GOLD, 400); resultDetails.addView(whyHint, lp(-1, -2, 12));
        whyRow = new LinearLayout(a); whyRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String w : WHY) {
            final TextView b = chip(w); b.setTextSize(11); b.setPadding(dp(4), dp(9), dp(4), dp(9));
            b.setOnClickListener(v -> { ReadingNotes.put(dir(), startMs / 1000L, "why", w); for (int i = 0; i < whyRow.getChildCount(); i++) { TextView o = (TextView) whyRow.getChildAt(i); o.setTextColor(o.getText().toString().equals(w) ? BLUE : INK); } a.toast("Noted: " + w); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f); p.rightMargin = dp(4); whyRow.addView(b, p);
        }
        resultDetails.addView(whyRow, lp(-1, -2, 6));
        addExplain(resultDetails, Explain.resultCard());
        resultDetails.setVisibility(View.GONE);
        detailsBody.addView(resultDetails, lp(-1, -2, 8));
        // while running: the strap's own check, noise bands, the 3 / 4 / 5 target
        liveCard = glass(16);
        liveCard.addView(caps("SIGNAL"), new LinearLayout.LayoutParams(-2, -2));
        strapLine = t("", 13, INK, 400); strapLine.setLineSpacing(0, 1.2f); liveCard.addView(strapLine, lp(-1, -2, 8));
        bandsView = t("", 12, MUT, 400); bandsView.setLineSpacing(0, 1.2f); liveCard.addView(bandsView, lp(-1, -2, 6));
        targetBtn = chip(""); targetBtn.setTextSize(12);
        targetBtn.setOnClickListener(v -> { double tg = a.autoTargetValue(); double nt = tg < 3.5 ? 4.0 : tg < 4.5 ? 5.0 : 3.0; pr.edit().putFloat("auto_target", (float) nt).putBoolean("auto_stop", true).apply(); refreshSummary(); if (rowValues[4] != null) rowValues[4].setText((nt < 3.5 ? AUTO[1] : nt < 4.5 ? AUTO[2] : AUTO[3]) + "  \u25be"); updateTarget(Double.NaN); });
        liveCard.addView(targetBtn, lp(-1, -2, 10));
        addExplain(liveCard, Explain.noiseBands());
        liveCard.setVisibility(View.GONE);
        detailsBody.addView(liveCard, lp(-1, -2, 8));
        // the averaged beat
        avgCard = glass(12);
        avgCard.addView(caps("AVERAGED BEAT"), new LinearLayout.LayoutParams(-2, -2));
        avgView = new AvgBeatView(a); avgCard.addView(avgView, lp(-1, dp(190), 8));
        avgText = t("", 12, MUT, 400); avgText.setGravity(Gravity.CENTER); avgText.setLineSpacing(0, 1.2f); avgCard.addView(avgText, lp(-1, -2, 6));
        addExplain(avgCard, Explain.avgBeatLive());
        avgCard.setVisibility(View.GONE);
        detailsBody.addView(avgCard, lp(-1, -2, 8));
    }

    private void applyDetails() {
        boolean on = mode != REST;
        if (detailsHeader != null) { detailsHeader.setVisibility(on ? View.VISIBLE : View.GONE); detailsHeader.setText((detailsOpen ? "\u25b4  Hide" : "\u25be  More about this")); }
        if (detailsBody != null) detailsBody.setVisibility(on && detailsOpen ? View.VISIBLE : View.GONE);
    }

    /** A tap-to-open "How is this calculated?" line under a card (decode log 117: the plain-language rows of Explain). */
    private void addExplain(LinearLayout card, String[][] rows) {
        final TextView head = t("How is this calculated?  \u25be", 12, MUT, 400); head.setPadding(0, dp(12), 0, dp(4));
        final TextView body = t(Explain.asText(rows), 12, MUT, 400); body.setLineSpacing(0, 1.2f); body.setVisibility(View.GONE);
        head.setOnClickListener(v -> { boolean open = body.getVisibility() != View.VISIBLE; body.setVisibility(open ? View.VISIBLE : View.GONE); head.setText("How is this calculated?  " + (open ? "\u25b4" : "\u25be")); });
        head.setContentDescription("How is this calculated? Double tap to show or hide the explanation.");
        card.addView(head, new LinearLayout.LayoutParams(-1, -2)); card.addView(body, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Two caps-titled cards like ACTIVITY TODAY: the next test (one tap) and the setup with the Lab. */
    private void buildCards(LinearLayout col) {
        nextCard = glass(16);
        nextCard.addView(caps("NEXT TEST"), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout nr = hrow();
        LinearLayout nl = new LinearLayout(a); nl.setOrientation(LinearLayout.VERTICAL);
        nextText = t("", 14, INK, 400); nextPlain = t("", 12, MUT, 400); nextPlain.setLineSpacing(0, 1.2f);
        nl.addView(nextText); nl.addView(nextPlain, lp(-2, -2, 3));
        nr.addView(nl, new LinearLayout.LayoutParams(0, -2, 1f));
        nextBtn = HeartGraph.pill(a, "Start", 0x1A2FA8F0, BLUE);
        nextBtn.setOnClickListener(v -> { if (!nextId.isEmpty()) startRun(nextId); });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2); np.leftMargin = dp(10); nr.addView(nextBtn, np);
        nextCard.addView(nr, lp(-1, -2, 8));
        nextCard.setVisibility(View.GONE);
        col.addView(nextCard, lp(-1, -2, 12));
        setupCard = glass(16);
        setupCard.addView(caps("READING SETUP"), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout r1 = hrow(); r1.setPadding(0, dp(12), 0, dp(4));
        r1.addView(t("Posture, position, place", 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        setupVal = t("", 12, MUT, 400); setupVal.setGravity(Gravity.END); r1.addView(setupVal);
        r1.setOnClickListener(v -> { if (!running) openSetup(); });
        setupCard.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout r2 = hrow(); r2.setPadding(0, dp(10), 0, dp(2));
        r2.addView(t("Lab: tests A to F", 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        labVal = t("", 12, MUT, 400); labVal.setGravity(Gravity.END); r2.addView(labVal);
        r2.setOnClickListener(v -> { if (!running) openLab(); });
        setupCard.addView(r2, new LinearLayout.LayoutParams(-1, -2));
        col.addView(setupCard, lp(-1, -2, 12));
        buildSetupRows(); refreshSummary();
    }

    // ------------------------------------------------------------------ the three states of the page
    private void applyMode(int m) {
        mode = m;
        try {
            boolean rest = m == REST, run = m == RUN, done = m == DONE;
            if (waveHost != null) waveHost.setVisibility(run ? View.VISIBLE : View.GONE);
            if (waveCardRef != null) waveCardRef.setVisibility(View.VISIBLE);
            if (bankCard != null) { bankCard.setVisibility(run ? View.GONE : View.VISIBLE); if (!run) refreshBank(); }
            if (replayCard != null) { replayCard.setVisibility(done ? View.VISIBLE : View.GONE); if (done) startReplayLoad(); else if (replayView != null) { replayView.setData(null, ""); replayFor = -1L; } }
            if (resultDetails != null) resultDetails.setVisibility(done ? View.VISIBLE : View.GONE);
            if (liveCard != null) liveCard.setVisibility(run ? View.VISIBLE : View.GONE);
            if (avgCard != null) avgCard.setVisibility(run || (done && lastOut != null) ? View.VISIBLE : View.GONE);
            if (setupCard != null) setupCard.setVisibility(run ? View.GONE : View.VISIBLE);
            if (planView != null) planView.setVisibility(run ? View.GONE : View.VISIBLE);
            if (noteView != null) noteView.setVisibility(run ? View.GONE : View.VISIBLE);
            if (sendNote != null) sendNote.setVisibility(done ? View.VISIBLE : View.GONE);
            if (cuePill != null && !run && readyLeft <= 0) cuePill.setVisibility(View.GONE);
            applyDetails();
            if (!run) refreshNext(); else if (nextCard != null) nextCard.setVisibility(View.GONE);
            String name = a.getSharedPreferences("today", android.content.Context.MODE_PRIVATE).getString("name", "Ame");
            if (rest) {
                greetView.setText("Hi, " + name); titleView.setText("Ready for an ECG");
                planView.setText("Two fingers on the clasp, forearm on a table. Press START ECG when you are ready. A free reading runs about 2\u00bd minutes.");
                refreshLatest();
            } else if (run) {
                greetView.setText("ECG  \u00b7  0:00"); GuidedRun.Def d = GuidedRun.byId(guidedThisRun ? pref("run_id", "") : ""); titleView.setText(guidedThisRun && !d.id.isEmpty() ? d.title : "Reading");
                stPill.setVisibility(View.GONE); stSub.setVisibility(View.VISIBLE);
            } else {
                greetView.setText("Reading done"); titleView.setText(String.format(Locale.UK, "%d:%02d recorded", (long) lastDur / 60, (long) lastDur % 60));
                planView.setText("Send collects everything by itself. Your report is in All reports.");
            }
            updateLink();
            if (rest && scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });
        } catch (Throwable e) { fail("page state", e); }
    }

    /** Called each time the ECG screen is opened: a page left on the last result goes back to rest (the status card then carries the latest reading with its Send). */
    void onOpen() { try { if (!running) { detailsOpen = false; applyMode(REST); } } catch (Throwable e) { fail("open", e); } }

    /** The 5 s auto-start countdown, shown inside the page; null hides it. A tap cancels it. */
    void setCountdown(String s) {
        try { if (countPill == null) return; if (s == null || running) { countPill.setVisibility(View.GONE); return; } countPill.setText(s); countPill.setVisibility(View.VISIBLE); } catch (Throwable e) { fail("countdown", e); }
    }

    // ------------------------------------------------------------------ setup sheet and Lab sheet
    /** A small bottom sheet in the same glass look. The content is taken back out when the sheet closes, so it can be shown again. */
    private Dialog sheet(String title, final View content) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = glass(18); box.setBackground(HealthViews.glass(a, 26));
        LinearLayout head = hrow();
        head.addView(t(title, 22, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = t("Done", 15, BLUE, 600); done.setPadding(dp(12), dp(8), dp(4), dp(8)); done.setOnClickListener(v -> d.dismiss()); head.addView(done);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        final ScrollView sv = new ScrollView(a); sv.setVerticalScrollBarEnabled(false); sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        box.addView(sv, lp(-1, -2, 10));
        d.setContentView(box);
        d.setOnDismissListener(x -> { try { sv.removeAllViews(); refreshSummary(); refreshNext(); } catch (Throwable ignored) { } });
        Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(0)); w.setLayout(-1, -2); w.setGravity(Gravity.BOTTOM); }
        return d;
    }

    /** The thin progress line under the cue pill. */
    private static final class ProgressLine extends View {
        private float frac; private final int fg, track;
        ProgressLine(android.content.Context c, int fg, int track) { super(c); this.fg = fg; this.track = track; }
        void set(float f) { frac = Math.max(0f, Math.min(1f, f)); invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            float hh = getHeight(), w = getWidth();
            p.setColor(track); cv.drawRoundRect(0, 0, w, hh, hh / 2f, hh / 2f, p);
            p.setColor(fg); cv.drawRoundRect(0, 0, w * frac, hh, hh / 2f, hh / 2f, p);
        }
    }

    /** 0.45.0 (d): the pooled mean beat of the beat bank, 400 ms before to 750 ms after the R apex, in the glass look. */
    private static final class BankView extends View {
        private double[] mean; private final int fg, grid, mut;
        BankView(android.content.Context c, int fg, int grid, int mut) { super(c); this.fg = fg; this.grid = grid; this.mut = mut; }
        void set(double[] m) { mean = m; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            if (mean == null) return;
            float w = getWidth(), hh = getHeight(); int i0 = Math.max(0, ProAnalysis.PRE - 200), i1 = Math.min(mean.length - 1, ProAnalysis.PRE + 375);
            double mn = 1e18, mx = -1e18; for (int i = i0; i <= i1; i++) { mn = Math.min(mn, mean[i]); mx = Math.max(mx, mean[i]); }
            double span = Math.max(300.0, mx - mn); double lo = mn - 0.08 * span, hi = mx + 0.08 * span;
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setColor(grid); p.setStrokeWidth(1f); float yz = (float) (hh - (0 - lo) / (hi - lo) * hh); cv.drawLine(0, yz, w, yz, p);
            float xr = (float) ((ProAnalysis.PRE - i0) / (double) (i1 - i0) * w); cv.drawLine(xr, 0, xr, hh, p);
            p.setColor(fg); p.setStrokeWidth(3f); android.graphics.Path path = new android.graphics.Path();
            for (int i = i0; i <= i1; i++) { float x = (float) ((i - i0) / (double) (i1 - i0) * w), y = (float) (hh - (mean[i] - lo) / (hi - lo) * hh); if (i == i0) path.moveTo(x, y); else path.lineTo(x, y); }
            cv.drawPath(path, p);
            p.setStyle(android.graphics.Paint.Style.FILL); p.setColor(mut); p.setTextSize(24f); cv.drawText("R apex", Math.min(w - 90f, xr + 8f), 26f, p);
        }
    }

    /** 0.45.0 (d): fills the bank from the readings on the phone that have no bank file yet (two per call, newest first, background thread), then pools. Never while a reading runs. */
    private void refreshBank() {
        if (bankBusy || running) return;
        bankBusy = true;
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                File d = dir(); int did = BeatBank.catchUp(d, 2);
                final BeatBank.Summary sm = BeatBank.summary(d); BeatBank.Pooled P = null;
                if (sm.beats >= BeatBank.UNLOCK) { String sig = sm.readings + ":" + sm.beats; if (bankPooled == null || !sig.equals(bankSig)) { P = BeatBank.pool(d); bankPooled = P; bankSig = sig; } else P = bankPooled; }
                final BeatBank.Pooled PP = P; final boolean more = did > 0 && !BeatBank.pending(d).isEmpty();
                a.runOnUiThread(new Runnable() { @Override public void run() { try { showBank(sm, PP); if (more && !running) h.postDelayed(new Runnable() { @Override public void run() { refreshBank(); } }, 4000L); } catch (Throwable e) { fail("bank card", e); } } });
            } catch (Throwable e) { final Throwable ee = e; try { a.runOnUiThread(new Runnable() { @Override public void run() { fail("bank", ee); } }); } catch (Throwable ignored) { } }
            finally { bankBusy = false; }
        } }, "beat-bank");
        th.setPriority(Thread.MIN_PRIORITY); th.start();
    }

    private void showBank(BeatBank.Summary sm, BeatBank.Pooled P) {
        if (bankText == null) return;
        bankText.setText(String.format(Locale.UK, "%,d of %,d beats  \u00b7  %d readings", sm.beats, BeatBank.UNLOCK, sm.readings));
        bankLine.set(sm.beats / (float) BeatBank.UNLOCK);
        if (P != null && P.unlocked && P.mean != null && P.iv != null) {
            ProAnalysis.Iv v = P.iv;
            bankView.set(P.mean); bankView.setVisibility(View.VISIBLE);
            bankNote.setText(String.format(Locale.UK, "Your own average beat from %,d matching beats. QRS %.0f ms (4 %% slope rule on a 40 Hz low-pass), R wave %.0f / %.0f / %.0f ms wide at 50 / 25 / 10 %% of its height. R %.0f uV, Q %.0f uV, S %.0f uV, T %.0f uV%s. The two random halves agree in the QRS (%.2f) and not in the quiet stretch (%.2f). Nothing above 100 Hz is claimed (decode log 134.1). Kept on this phone; Send does not include it.",
                P.kept, v.qrs, v.rW50, v.rW25, v.rW10, v.rAmp, Double.isNaN(P.qTrough) ? Double.NaN : P.qTrough, v.sAmp, v.tAmp, v.qtValid ? String.format(Locale.UK, ", QT %.0f ms", v.qt) : "", P.splitQrs, P.splitCtl));
        } else {
            bankView.setVisibility(View.GONE);
            bankNote.setText(sm.beats >= BeatBank.UNLOCK && P != null && P.kept > 0 ? String.format(Locale.UK, "%,d beats pooled, but the split-half test did not pass (%.2f in the QRS, %.2f in the quiet stretch): no detail view yet.", P.kept, P.splitQrs, P.splitCtl)
                : "The 40 cleanest beats of each reading are kept on this phone and pooled. At 1,000 beats your own average beat appears here. Send does not include them.");
        }
    }

    /** 0.45.0 (b): true while a lift of the fingers would end the reading (free reading, auto-stop on, at least 60 s and LIFT_MIN_CLEAN clean beats in). */
    boolean liftStopArmed() {
        try { return running && !autoStopped && pr.getBoolean("auto_stop", true) && !guide.active() && System.currentTimeMillis() - Live500.startMs > 60000L && Live500.cleanBeats() >= LIFT_MIN_CLEAN; } catch (Throwable e) { return false; }
    }

    // ------------------------------------------------------------------ 0.45.0 (n): STOP with a 3 s cancel bar
    private void beginStop() {
        try {
            if (stopPending || !running || start == null || !start.getText().toString().startsWith("STOP")) return;
            stopPending = true; stopT0 = android.os.SystemClock.elapsedRealtime(); stopTapMs = System.currentTimeMillis();
            if (stopBar != null) stopBar.setVisibility(View.VISIBLE);
            try { start.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) { }
            stopTick = new Runnable() { @Override public void run() { stopStep(); } };
            stopStep();
        } catch (Throwable e) { stopPending = false; fail("stop bar", e); }
    }

    private void stopStep() {
        if (!stopPending) return;
        if (!running || start == null || !start.getText().toString().startsWith("STOP")) { cancelStop(false); return; }      // the reading ended another way
        long el = android.os.SystemClock.elapsedRealtime() - stopT0;
        if (el >= STOP_DELAY_MS) {
            stopPending = false; hideStopBar(); stopReason = 0;
            start.performClick();
            return;
        }
        int left = (int) ((STOP_DELAY_MS - el + 999L) / 1000L);
        if (stopBarText != null) { stopBarText.setText("Stopping in " + left + " s, tap to cancel"); stopBar.setContentDescription("Stopping the reading in " + left + " seconds. Tap to cancel."); }
        if (stopLine != null) stopLine.set(el / (float) STOP_DELAY_MS);
        h.postDelayed(stopTick, 100L);
    }

    private void cancelStop(boolean toast) {
        boolean was = stopPending;
        stopPending = false; stopTapMs = 0L;
        try { if (stopTick != null) h.removeCallbacks(stopTick); } catch (Throwable ignored) { }
        hideStopBar();
        if (was && toast) a.toast("Not stopping. The reading continues.");
    }

    private void hideStopBar() { try { if (stopBar != null) stopBar.setVisibility(View.GONE); if (stopLine != null) stopLine.set(0f); } catch (Throwable ignored) { } }

    private void applyDisplayPrefs() {
        try { Live500.pro = pref("pro_display", "1").equals("1"); Live500View.windowMs = pr.getInt("graph_window", 6000); Live500View.sweep = pr.getBoolean("graph_sweep", false); Live500View.paper = pr.getBoolean("graph_paper", false); Live500View.qualityColours = pr.getBoolean("ecg_quality_colours", true); Live500.setClean(pr.getBoolean("ecg_clean_view", false)); } catch (Throwable e) { fail("display options", e); }
    }

    private static final String[] POSTURE = { "No tag", "Table", "Couch", "Lying", "Standing", "After exercise" };
    private static final String[] POSITION = { "Wrist", "Chest V2", "Chest V4", "Chest V5", "Chest V6", "Bicep L", "Bicep R", "H10 in hands" };
    private static final String[] WHERE = { "Not set", "Home", "Work", "Truck", "Outside", "Other" };
    private static final String[] AUTO = { "Off", "3 uV", "4 uV", "5 uV" };

    private void buildSetupRows() {
        setupBody = new LinearLayout(a); setupBody.setOrientation(LinearLayout.VERTICAL);
        addRow(0, "Posture", POSTURE, () -> { String s = a.readingTag; return s.isEmpty() ? POSTURE[0] : s; }, i -> {
            String s = i == 0 ? "" : POSTURE[i]; a.readingTag = s;
            SharedPreferences.Editor e = pr.edit().putString("reading_tag", s); if (!s.isEmpty()) e.putString("reading_tag_last", s); e.apply(); });
        addRow(1, "Strap position", POSITION, () -> a.positionTag.isEmpty() ? POSITION[0] : a.positionTag, i -> { a.positionTag = i == 0 ? "" : POSITION[i]; pr.edit().putString("position_tag", a.positionTag).apply(); });
        addRow(2, "Where", WHERE, () -> pref("where_tag", "").isEmpty() ? WHERE[0] : pref("where_tag", ""), i -> pr.edit().putString("where_tag", i == 0 ? "" : WHERE[i]).apply());
        addRow(4, "Auto-stop", AUTO, () -> { if (!pr.getBoolean("auto_stop", true)) return AUTO[0]; double tg = a.autoTargetValue(); return tg < 3.5 ? AUTO[1] : tg < 4.5 ? AUTO[2] : AUTO[3]; }, i -> {
            SharedPreferences.Editor e = pr.edit(); if (i == 0) e.putBoolean("auto_stop", false); else e.putBoolean("auto_stop", true).putFloat("auto_target", i == 1 ? 3f : i == 2 ? 4f : 5f); e.apply(); });
    }

    private void openSetup() {
        try {
            if (setupBody.getParent() instanceof ViewGroup) ((ViewGroup) setupBody.getParent()).removeView(setupBody);
            sheet("Reading setup", setupBody).show();
        } catch (Throwable e) { fail("setup sheet", e); }
    }

    interface Get { String get(); }
    interface Pick { void pick(int i); }
    private void addRow(final int slot, final String label, final String[] options, final Get get, final Pick pick) {
        LinearLayout r = hrow(); r.setBackground(HealthViews.glass(a, 16)); r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.addView(t(label, 13, MUT, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = t(get.get() + "  \u25be", 14, INK, 400); val.setGravity(Gravity.END); r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.4f));
        rowValues[slot] = val; r.setContentDescription(label + ", " + get.get() + ", double tap to change");
        r.setOnClickListener(v -> {
            if (running) return;
            new AlertDialog.Builder(a).setTitle(label).setItems(options, (d, which) -> { try { pick.pick(which); } catch (Throwable e) { fail("row " + label, e); } val.setText(get.get() + "  \u25be"); r.setContentDescription(label + ", " + get.get() + ", double tap to change"); refreshSummary(); }).show();
        });
        setupBody.addView(r, lp(-1, -2, 8));
    }

    void refreshSummary() {
        if (setupVal == null) return;
        String s = a.readingTag.isEmpty() ? "No tag" : a.readingTag, p = a.positionTag.isEmpty() ? "Wrist" : a.positionTag, w = pref("where_tag", "");
        setupVal.setText(s + "  \u00b7  " + p + (w.isEmpty() ? "" : "  \u00b7  " + w) + (!pr.getBoolean("auto_stop", true) ? "  \u00b7  no auto-stop" : "") + "  \u25be");
    }

    private String ticks(int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < 2; i++) s.append(i < n ? "\u25cf " : "\u25cb "); return s.toString() + " " + n + " / 2"; }

    private void openLab() {
        try {
            LabTests.Result r = LabTests.done(dir()); int hour = LabTests.hourNow();
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
            TextView intro = t("Each test is 45 s. It stops by itself and sends itself. The phone buzzes, beeps and speaks at each step. Sit with forearms on a table, left wrist with the MG facing up, two right-hand fingers on the clasp.", 12, MUT, 400); intro.setLineSpacing(0, 1.2f);
            box.addView(intro, new LinearLayout.LayoutParams(-1, -2));
            final Dialog[] dd = new Dialog[1];
            for (int i = 0; i < 6; i++) {
                final String id = LabTests.IDS[i];
                LinearLayout row = hrow(); row.setBackground(HealthViews.glass(a, 16)); row.setPadding(dp(16), dp(12), dp(12), dp(12));
                LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL);
                String tk = i == 4 ? (r.eMorning ? "\u25cf" : "\u25cb") + " morning   " + (r.eEvening ? "\u25cf" : "\u25cb") + " evening" : ticks(r.n[i]);
                mid.addView(t(id + "   " + LabTests.name(id), 15, INK, 400), new LinearLayout.LayoutParams(-2, -2));
                mid.addView(t(tk, 12, (i == 4 ? r.n[4] : r.n[i]) >= 2 ? GREEN : GOLD, 600), lp(-2, -2, 4));
                TextView pl = t(LabTests.plain(id), 12, MUT, 400); pl.setLineSpacing(0, 1.2f); mid.addView(pl, lp(-2, -2, 3));
                row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView go = pill("Start", BLUE, DARK); go.setPadding(dp(18), 0, dp(18), 0);
                boolean can = i != 4 || LabTests.canStartE(r, hour);
                if (!can) { go.setAlpha(0.35f); go.setOnClickListener(v -> a.toast("Test E runs 08:00-10:00 and 19:00-21:00")); }
                else go.setOnClickListener(v -> { if (dd[0] != null) dd[0].dismiss(); startRun(id); });
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, dp(42)); gp.leftMargin = dp(10);
                row.addView(go, gp);
                box.addView(row, lp(-1, -2, 8));
            }
            TextView cue = chip("Test the cue (buzz, beep, voice)"); cue.setOnClickListener(v -> testCue());
            box.addView(cue, lp(-1, -2, 14));
            dd[0] = sheet("Lab", box); dd[0].show();
        } catch (Throwable e) { fail("lab sheet", e); }
    }

    /** ONE tap: arms the test (posture Table, strap position Wrist, the place stays as it was), cancels the 5 s auto-start and runs its own 5 s get-ready pill, then presses START. */
    void startRun(final String id) {
        try {
            if (running) return;
            GuidedRun.Def d = GuidedRun.byId(id); if (d.id.isEmpty()) return;
            pr.edit().putString("run_id", id).putLong("run_armed_ms", System.currentTimeMillis()).putString("reading_tag", "Table").putString("reading_tag_last", "Table").putString("position_tag", "").apply();
            a.readingTag = "Table"; a.positionTag = ""; refreshSummary();
            a.cancelAutoCountdown();
            readyCancelled = false; readyLeft = 5; initTts();
            cuePill.setVisibility(View.VISIBLE); cueView.setText("Get ready: 5 s  \u00b7  " + d.title + "  (tap to cancel)"); cueLine.set(0f);
            h.removeCallbacks(readyTick); h.post(readyTick);
        } catch (Throwable e) { fail("start run", e); }
    }
    private final Runnable readyTick = new Runnable() { @Override public void run() {
        try {
            if (running) return;
            if (readyCancelled) { readyLeft = 0; return; }
            GuidedRun.Def d = GuidedRun.byId(pref("run_id", ""));
            if (readyLeft <= 0) { readyLeft = 0; if (start != null && start.getText().toString().startsWith("START")) start.performClick(); else if (cuePill != null) cuePill.setVisibility(View.GONE); return; }
            if (cueView != null) cueView.setText("Get ready: " + readyLeft + " s  \u00b7  " + d.title + "  (tap to cancel)");
            readyLeft--; h.postDelayed(this, 1000L);
        } catch (Throwable e) { fail("get ready", e); }
    } };

    // ------------------------------------------------------------------ latest reading, next test
    private static float gradeFrac(String g) { switch (g == null || g.isEmpty() ? '?' : g.charAt(0)) { case 'A': return 1f; case 'B': return 0.75f; case 'C': return 0.5f; case 'D': return 0.25f; default: return 0f; } }
    private static double num(String s) { try { return Double.parseDouble(s.trim()); } catch (Throwable e) { return Double.NaN; } }

    void refreshLatest() {
        try {
            if (statusCard == null) return;
            List<NewLookActivity.Rep> reps = a.loadReports();
            stTitle.setTextColor(INK);
            if (reps.isEmpty()) {
                latestFile = null; lastHr = "";
                stTitle.setText("No reading yet"); stSub.setText("Press START ECG to take your first one."); stPill.setVisibility(View.GONE); statusCard.setOnClickListener(null);
                setRing(0, 0f, ROSE, "--", "HEART RATE", "last reading"); setRing(1, 0f, BLUE, "--", "HRV", "RMSSD, ms"); setRing(2, 0f, VIOLET, "--", "GRADE", "report quality"); drawRings();
                return;
            }
            final NewLookActivity.Rep r = reps.get(0);
            latestFile = r.file; latestStart = ProReportMaker.unixOf(r.file);
            lastHr = "-".equals(r.hr) ? "" : r.hr;
            if (mode != REST) return;
            stTitle.setText("Latest reading  \u00b7  " + r.dateText); stSub.setText(r.summary());
            stPill.setVisibility(View.VISIBLE); SendFlow.attach(a, stPill, latestStart, r.file);
            statusCard.setOnClickListener(v -> a.openReport(r.file));
            double hrv = num(r.hrv);
            setRing(0, hrFrac(lastHr), ROSE, lastHr.isEmpty() ? "--" : lastHr, "HEART RATE", "last reading");
            setRing(1, clamp01(hrv / 100.0), BLUE, "-".equals(r.hrv) ? "--" : r.hrv, "HRV", "RMSSD, ms");
            setRing(2, gradeFrac(r.grade), r.gradeColor(), "?".equals(r.grade) ? "--" : r.grade, "GRADE", "report quality");
            drawRings();
        } catch (Throwable e) { fail("latest reading", e); }
    }

    void refreshNext() {
        try {
            LabTests.Result res = LabTests.done(dir());
            nextId = LabTests.next(res, LabTests.hourNow());
            if (labVal != null) labVal.setText(String.format(Locale.UK, "A %d  B %d  C %d  D %d  of 2  \u203a", res.n[0], res.n[1], res.n[2], res.n[3]));
            if (nextCard == null) return;
            if (nextId.isEmpty() || running || mode == RUN) { nextCard.setVisibility(View.GONE); return; }
            nextText.setText(nextId + "   " + LabTests.name(nextId)); nextPlain.setText(LabTests.plain(nextId)); nextCard.setVisibility(View.VISIBLE);
        } catch (Throwable e) { fail("next test", e); }
    }

    /** Called every 500 ms by NewLookActivity while the screen is at rest. */
    void tickRest() {
        if (running) return;
        long now = System.currentTimeMillis(); if (now - lastRestMs < 30000L) return; lastRestMs = now;
        refreshNext(); updateLink();
    }

    // ------------------------------------------------------------------ running state
    /** Called by NewLookActivity when START / STOP flips. */
    void onRunning(boolean nowRunning) {
        if (nowRunning == running) return;
        boolean was = running; running = nowRunning;
        try {
            if (nowRunning && !was) {
                startMs = Live500.sessionMs > 0 && System.currentTimeMillis() - Live500.sessionMs < 8000 ? Live500.sessionMs : System.currentTimeMillis();
                cancelStop(false); autoStopped = false; stopReason = 0; cueLog = ""; lastOut = null; lastBands = null; LiveMeasure.clear(); readyCancelled = true; readyLeft = 0; detailsOpen = false;
                applyDisplayPrefs();
                if (rings != null) rings.setPulse(0);                                        // 0.44.0: the heart-rate number pulses at each detected R
                if (avgView != null) avgView.set(null);
                String rid = pref("run_id", ""); if (!rid.isEmpty() && pr.getLong("run_armed_ms", 0L) + 90000L < System.currentTimeMillis()) { rid = ""; pr.edit().putString("run_id", "").apply(); }
                GuidedRun.Def d = GuidedRun.byId(rid);
                guidedThisRun = !d.id.isEmpty(); runStopSec = d.stopSec;
                if (!d.id.isEmpty()) { cueCount = 0; initTts(); guide.start(d, startMs, sink); cuePill.setVisibility(View.VISIBLE); cueView.setText("Keep still and relaxed"); cueLine.set(0f); } else { guide.cancel(); cuePill.setVisibility(View.GONE); }
                setRing(0, 0f, ROSE, "--", "HEART RATE", "live"); setRing(1, 0f, BLUE, "--", "NOISE", "uV, usual 15-27"); setRing(2, 0f, VIOLET, "0%", "STRAP CHECK", "its own analysis"); drawRings();
                stTitle.setText("Settling"); stTitle.setTextColor(INK); stSub.setText("The strap needs about 12 s.");
                if (scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });
                ReadingNotes.put(dir(), startMs / 1000L, "app", VERSION);
                ReadingNotes.put(dir(), startMs / 1000L, "where", pref("where_tag", ""));
                ReadingNotes.put(dir(), startMs / 1000L, "pos", a.positionTag);
                ReadingNotes.put(dir(), startMs / 1000L, "tag", a.readingTag);
                ReadingNotes.put(dir(), startMs / 1000L, "run", d.id);
                ReadingNotes.put(dir(), startMs / 1000L, "pro", Live500.pro ? "1" : "0");
                applyMode(RUN);
            } else if (!nowRunning && was) {
                guide.cancel(); if (rings != null) rings.setPulse(-1); finishReading(); cancelStop(false);
            }
        } catch (Throwable e) { fail("running change", e); }
        if (start != null && !nowRunning) start.setContentDescription("Start ECG reading");
    }

    // ------------------------------------------------------------------ per-second update (from NewLookActivity.contactTick)
    void update(boolean isRunning, boolean live) {
        try {
            if (isRunning != running) onRunning(isRunning);
            if (!isRunning) return;
            long now = System.currentTimeMillis(); long el = now - Live500.startMs;
            double[] b = LiveBands.rms(); lastBands = b;
            double est = Live500.meanBeatNoiseEstimate(), nz = Live500.recentNoise(3);
            // the signal light is the status card; the strap's own check is its second line
            String txt; int colr; double n825 = b[1];
            boolean noContact = !live || !Live500.everOn || Live500.openRun >= 1;
            if (noContact) { txt = "\u25cf  Contact: two fingertips resting lightly on the clasp"; colr = 0xFFE5534B; }
            else if (Double.isNaN(n825) && Double.isNaN(nz)) { txt = "\u25cf  Settling (the strap needs about 12 s)"; colr = 0xFFD4A72C; }
            else {
                boolean good, fair;
                if (!Double.isNaN(n825)) { good = n825 <= 27; fair = n825 <= 40; } else { good = nz <= 25; fair = nz <= 38; }
                if (good) { txt = "\u25cf  Signal good: stay as you are"; colr = 0xFF3FB950; }
                else if (fair) { txt = "\u25cf  Signal fair: relax your hand, keep still"; colr = 0xFFD4A72C; }
                else { txt = "\u25cf  Signal noisy: rest your forearm on the table, keep still"; colr = 0xFFE5534B; }
            }
            int pg = Live500.lastProgress, cd = Live500.lastCode; String strap = "";
            if (cd != 0 && pg == 100) strap = "Strap's own check (research): " + StrapVerdict.describe(cd, Live500.lastAvgHr, Live500.lastMask);
            else if (pg <= 100) strap = "Strap's own check: analysing " + pg + " %" + (Live500.lastMask != 0 ? " (" + StrapVerdict.reasons(Live500.lastMask) + ")" : "");
            stTitle.setText(txt); stTitle.setTextColor(colr); stSub.setText(strap); strapLine.setText(strap);
            if (!Double.isNaN(b[1])) {
                String bt = String.format(Locale.UK, "Noise (between beats):  QRS band 8-25 Hz %.0f uV (usual 15-27)  \u00b7  25-48 Hz %.0f  \u00b7  50 Hz %.0f  \u00b7  drift %.0f", b[1], b[2], b[3], b[0]);
                String hint = LiveBands.hint(b, lastOut != null && !Double.isNaN(lastOut.rUv) ? lastOut.rUv : Double.NaN);
                if (hint == null && b[1] > 40) hint = "Broadband noise (muscle or vibration): relax the hand, rest the forearm, keep still";
                bandsView.setText(bt + (hint != null && b[1] > 27 ? "\n" + hint : ""));
            } else bandsView.setText("Noise by band appears after about 10 s of beats");
            updateTarget(est);
            // the three rings: live heart rate, noise in the QRS band, the strap's own check (0 to 100 %)
            double rrS = Live500.medianRrS();
            String hrNow = Double.isNaN(rrS) ? "--" : String.valueOf(Math.round(60.0 / rrS));
            double nv = !Double.isNaN(n825) ? n825 : nz;
            int ncol = Double.isNaN(nv) ? MUT : nv <= 27 ? GREEN : nv <= 40 ? GOLD : ROSE;
            setRing(0, hrFrac(hrNow), ROSE, hrNow, "HEART RATE", "live");
            setRing(1, Double.isNaN(nv) ? 0f : clamp01(1.0 - nv / 60.0), ncol, Double.isNaN(nv) ? "--" : String.format(Locale.UK, "%.0f", nv), "NOISE", "uV, usual 15-27");
            setRing(2, clamp01(Math.min(100, pg) / 100.0), VIOLET, Math.min(100, pg) + "%", "STRAP CHECK", cd != 0 && pg == 100 ? "result " + cd : "its own analysis");
            drawRings();
            greetView.setText(String.format(Locale.UK, "ECG  \u00b7  %d:%02d", el / 60000, (el / 1000) % 60));
            // averaged beat
            LiveMeasure.request();
            LiveMeasure.Out o = LiveMeasure.last();
            if (o != null && o != lastOut) { lastOut = o; avgView.set(o); avgCard.setVisibility(View.VISIBLE); avgText.setText(avgLine(o)); }
            if (guide.active() && runStopSec > 0 && cueLine != null) cueLine.set(el / (runStopSec * 1000f));
            // auto-stop (not during a guided run)
            boolean auto = pr.getBoolean("auto_stop", true) && !guide.active();
            int clean = Live500.cleanBeats();
            boolean quality = !Double.isNaN(est) && est <= a.autoTargetValue() && clean >= CLEAN_MIN;
            if (auto && !autoStopped && el > NewLookActivity.AUTO_MIN_MS && (quality || el > NewLookActivity.AUTO_MAX_MS)) {
                autoStopped = true; stopReason = 1;
                a.toast(quality ? String.format(Locale.UK, "Stopped: report quality %.1f uV, %d clean beats", est, clean) : "Stopped at 5 minutes (report quality " + (Double.isNaN(est) ? "-" : String.format(Locale.UK, "%.1f", est)) + " uV, " + clean + " clean beats)");
                if (start != null && start.getText().toString().startsWith("STOP")) start.performClick();
            }
            else if (!autoStopped && live && liftStopArmed() && Live500.openRun >= LIFT_STOP_S) {      // 0.45.0 (b): the fingers came off late in a reading: stop now instead of recording the lift
                autoStopped = true; stopReason = 3;
                a.toast(String.format(Locale.UK, "Stopped: fingers lifted (%d clean beats)", clean));
                if (start != null && start.getText().toString().startsWith("STOP")) start.performClick();
            }
            if (start != null && running) { start.setText(String.format(Locale.UK, "STOP  \u00b7  %d:%02d", el / 60000, (el / 1000) % 60)); start.setContentDescription(String.format(Locale.UK, "Stop the reading. Tap once; it stops after 3 seconds unless you tap Stopping to cancel. %d minutes %d seconds so far.", el / 60000, (el / 1000) % 60)); }
            try {                                                                      // a spoken summary of the live graph for a screen reader
                String say = txt.replace("\u25cf  ", "");
                String desc = "Live ECG trace, 500 hertz. Tap for full screen. " + (Double.isNaN(rrS) ? "" : String.format(Locale.UK, "Heart rate %.0f beats a minute. ", 60.0 / rrS)) + say + ".";
                if (waveCardRef != null) for (int i = 0; i < waveCardRef.getChildCount(); i++) { View vv = waveCardRef.getChildAt(i); if (vv instanceof Live500View) vv.setContentDescription(desc); }
            } catch (Throwable ignored) { }
        } catch (Throwable e) { fail("update", e); }
    }

    private void updateTarget(double est) {
        if (targetBtn == null) return;
        double tg = a.autoTargetValue(); boolean auto = pr.getBoolean("auto_stop", true);
        String e = Double.isNaN(est) ? "settling" : String.format(Locale.UK, "%.1f uV", est);
        int cb = Live500.cleanBeats();
        targetBtn.setText(auto ? "Report quality " + e + "  \u2192  " + String.format(Locale.UK, "%.0f", tg) + " uV  \u00b7  clean beats " + cb + " of " + CLEAN_TARGET + "  (tap: 3, 4, 5)" : "Auto-stop off  \u00b7  quality " + e + "  \u00b7  clean beats " + cb + " of " + CLEAN_TARGET + "  (tap: 3, 4, 5 turns it on)");
    }

    private String avgLine(LiveMeasure.Out o) {
        StringBuilder s = new StringBuilder();
        if (!Double.isNaN(o.qrsMs)) s.append(String.format(Locale.UK, "QRS %.0f ms", o.qrsMs)).append(o.qrsOk ? "" : " (settling)");
        if (!Double.isNaN(o.postDipUv)) s.append(s.length() > 0 ? "  \u00b7  " : "").append(String.format(Locale.UK, "S/ST dip %.0f uV at +%.0f ms", o.postDipUv, o.postDipMs));
        if (!Double.isNaN(o.sDipUv)) s.append(String.format(Locale.UK, "  \u00b7  at +%.0f ms %.0f uV", o.sDipMs, o.sDipUv));
        if (!Double.isNaN(o.expectedSDipUv)) s.append(String.format(Locale.UK, "\nR %.0f uV  \u00b7  the +14 to +28 ms dip your R height predicts: %.0f uV (decode log 109.1)", o.rUv, o.expectedSDipUv));
        if (!Double.isNaN(o.qtMs)) s.append(String.format(Locale.UK, "  \u00b7  QT %.0f ms", o.qtMs));
        return s.toString();
    }

    // ------------------------------------------------------------------ guided run sink
    private final GuidedRun.Sink sink = new GuidedRun.Sink() {
        @Override public void cue(String text, String mark, int sec) {
            lastCue = text; cueUntil = System.currentTimeMillis() + 4000L; cueLog += (cueLog.isEmpty() ? "" : ";") + sec + ":" + text;
            if (cueView != null) { cuePill.setVisibility(View.VISIBLE); cueView.setText("NOW  " + text); }
            alertUser(text, 1 + (cueCount++ % 2));      // buzz (own vibrator), beep on the alarm stream and a spoken cue
            if (mark != null) clickMark(mark);
            ReadingNotes.put(dir(), startMs / 1000L, "cue", sec + ":" + text);
        }
        @Override public void tick(String status) { if (cueView == null) return; cuePill.setVisibility(View.VISIBLE); if (System.currentTimeMillis() > cueUntil) cueView.setText(status); }
        @Override public void stop() { stopReason = 2; if (start != null && start.getText().toString().startsWith("STOP")) { start.performClick(); } }
    };

    /** Vibration with the phone's vibrator, a beep on the ALARM stream and the cue spoken (also on the alarm stream). Each channel is tried on its own. */
    private void initTts() {
        if (tts != null) return;
        try { tts = new android.speech.tts.TextToSpeech(a.getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() { @Override public void onInit(int s) { ttsOk = s == android.speech.tts.TextToSpeech.SUCCESS; } }); } catch (Throwable e) { tts = null; }
    }
    private void alertUser(String text, int pulses) {
        boolean vib = false;
        try {
            android.os.Vibrator v = (android.os.Vibrator) a.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] p = pulses >= 2 ? new long[] { 0, 350, 200, 350 } : new long[] { 0, 600 };
                v.vibrate(android.os.VibrationEffect.createWaveform(p, -1), new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                vib = true;
            }
        } catch (Throwable ignored) { }
        if (!vib) { try { if (start != null) start.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) { } }
        try { final android.media.ToneGenerator tg = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100); tg.startTone(pulses >= 2 ? android.media.ToneGenerator.TONE_PROP_BEEP2 : android.media.ToneGenerator.TONE_PROP_BEEP, 400); h.postDelayed(tg::release, 900); } catch (Throwable ignored) { }
        try { if (tts != null && ttsOk) { android.os.Bundle b = new android.os.Bundle(); b.putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM); tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, b, "cue"); } } catch (Throwable ignored) { }
    }
    /** The Lab's "Test the cue" row: buzz and beep at once, the voice about 1.5 s later (the speech engine starts on first use). */
    private void testCue() {
        initTts(); alertUser("Test. Clench your left fist now.", 1);
        h.postDelayed(new Runnable() { @Override public void run() { try { if (tts != null && ttsOk) { android.os.Bundle b = new android.os.Bundle(); b.putInt(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM); tts.speak("Test. Clench your left fist now.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, b, "cue"); } } catch (Throwable ignored) { } } }, 1500L);
        a.toast("Cue test: you should feel a buzz, hear a beep and a voice. If not, check the phone's vibration, alarm volume and Do Not Disturb.");
    }

    private void clickMark(String label) {
        try {
            if (a.markViews == null) return;
            for (View v : a.markViews) { Button b = findButton(v, label); if (b != null) { b.performClick(); return; } }
        } catch (Throwable ignored) { }
    }
    private static Button findButton(View v, String label) {
        if (v instanceof Button && label.equals(((Button) v).getText().toString())) return (Button) v;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { Button b = findButton(g.getChildAt(i), label); if (b != null) return b; } }
        return null;
    }

    // ------------------------------------------------------------------ the end of a reading
    private void finishReading() {
        long endMs = System.currentTimeMillis(); long startSec = startMs / 1000L; File d = dir();
        double dur = (endMs - startMs) / 1000.0; lastDur = dur;
        // FIRST: the one-button pull. Own guard, before anything that could throw (the 20:30 reading of 6 Nov never got its refresh notes).
        try { SendFlow.begin(a.getApplicationContext(), d, startSec, endMs, (long) dur, new Runnable() { @Override public void run() { pullSoon(); } }, new Runnable() { @Override public void run() { a.toast("Report updated with the strap's own beat counts."); } }); }
        catch (Throwable e) { fail("send prepare", e); }
        LiveMeasure.Out o = null; double[] b = null; int vc = 0;
        try {
            o = LiveMeasure.last(); if (o == null) { try { o = LiveMeasure.compute(Live500.windowsCopy(), Live500.medianRrS()); } catch (Throwable ignored) { } }
            lastOut = o; b = lastBands != null ? lastBands : LiveBands.rms();
            ReadingNotes.put(d, startSec, "dur_s", String.format(Locale.US, "%.0f", dur));
            ReadingNotes.put(d, startSec, "stop", stopReason == 1 ? "auto" : stopReason == 2 ? "run" : stopReason == 3 ? "lift" : "hand");
            ReadingNotes.put(d, startSec, "clean_beats_live", String.valueOf(Live500.cleanBeats()));
            if (stopReason == 0 && stopTapMs > 0L) ReadingNotes.put(d, startSec, "stop_tap_s", String.format(Locale.US, "%.1f", (stopTapMs - startMs) / 1000.0));      // 0.45.0 (n): the tap; the reading ends 3 s later
            String[] bn = { "noise_0p5_5", "noise_8_25", "noise_25_48", "noise_mains", "noise_52_100" };
            for (int i = 0; i < 5; i++) if (!Double.isNaN(b[i])) ReadingNotes.put(d, startSec, bn[i], String.format(Locale.US, "%.1f", b[i]));
            double z = Live500.medianZ(); if (!Double.isNaN(z)) ReadingNotes.put(d, startSec, "contact_z", String.format(Locale.US, "%.1f", z));
            ReadingNotes.put(d, startSec, "beats_live", String.valueOf(Live500.beatCount()));
            vc = Live500.verdictCode; if (vc != 0) { ReadingNotes.put(d, startSec, "result", String.valueOf(vc)); ReadingNotes.put(d, startSec, "strap_hr", String.valueOf(Live500.verdictHr)); ReadingNotes.put(d, startSec, "verdict_s", String.format(Locale.US, "%.0f", (Live500.verdictMs - Live500.sessionMs) / 1000.0)); }
            if (Live500.hrvMs != 0) { ReadingNotes.put(d, startSec, "hrv_first_s", String.format(Locale.US, "%.0f", (Live500.hrvMs - Live500.sessionMs) / 1000.0)); ReadingNotes.put(d, startSec, "hrv_first_ms", String.valueOf(Live500.hrvVal)); }
            if (o != null && o.avg != null) {
                ReadingNotes.put(d, startSec, "live_R_uV", String.format(Locale.US, "%.0f", o.rUv)); if (!Double.isNaN(o.qrsMs)) ReadingNotes.put(d, startSec, "live_QRS_ms", String.format(Locale.US, "%.0f", o.qrsMs));
                if (!Double.isNaN(o.postDipUv)) ReadingNotes.put(d, startSec, "live_dip_uV", String.format(Locale.US, "%.0f", o.postDipUv)); if (!Double.isNaN(o.qtMs)) ReadingNotes.put(d, startSec, "live_QT_ms", String.format(Locale.US, "%.0f", o.qtMs));
            }
        } catch (Throwable e) { fail("reading notes", e); }
        // the status card: the strap verdict and Send; the rings: heart rate, strap HRV, noise; every other line under "More about this"
        try {
            if (b == null) b = new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
            String verdict = vc != 0 ? "Strap verdict: " + StrapVerdict.describe(vc, Live500.verdictHr, Live500.lastMask) : (dur < 42 ? "No strap verdict yet: the reading was " + String.format(Locale.UK, "%.0f", dur) + " s (the strap needs about 41 s from Start, 37 s of it in unbroken contact)" : "No strap verdict (contact was broken before it finished, or it was stopped too early)");
            double rr = Live500.medianRrS();
            String hrTxt = Double.isNaN(rr) ? "--" : String.valueOf(Math.round(60.0 / rr));
            if (!Double.isNaN(rr)) lastHr = hrTxt;
            StringBuilder t = new StringBuilder();
            if (!Double.isNaN(rr)) { t.append(String.format(Locale.UK, "HR %.0f", 60.0 / rr)); if (vc != 0) t.append(String.format(Locale.UK, " (strap %d)", Live500.verdictHr)); }
            if (Live500.hrvMs != 0) t.append(String.format(Locale.UK, "  \u00b7  strap HRV %d ms (from %.0f s)", Live500.hrvVal, (Live500.hrvMs - Live500.sessionMs) / 1000.0));
            String line1 = t.toString();
            stTitle.setText(verdict); stTitle.setTextColor(INK); stSub.setText(line1); stSub.setVisibility(line1.isEmpty() ? View.GONE : View.VISIBLE);
            boolean haveNoise = !Double.isNaN(b[1]);
            String lvl = !haveNoise ? "" : b[1] <= 27 ? "good" : b[1] <= 40 ? "fair" : "noisy";
            setRing(0, hrFrac(hrTxt), ROSE, hrTxt, "HEART RATE", vc != 0 ? "strap " + Live500.verdictHr : "median beat gap");
            setRing(1, Live500.hrvMs != 0 ? clamp01(Live500.hrvVal / 100.0) : 0f, BLUE, Live500.hrvMs != 0 ? String.valueOf(Live500.hrvVal) : "--", "STRAP HRV", Live500.hrvMs != 0 ? String.format(Locale.UK, "ms, from %.0f s", (Live500.hrvMs - Live500.sessionMs) / 1000.0) : "not released");
            setRing(2, haveNoise ? clamp01(1.0 - b[1] / 60.0) : 0f, !haveNoise ? MUT : b[1] <= 27 ? GREEN : b[1] <= 40 ? GOLD : ROSE, haveNoise ? String.format(Locale.UK, "%.0f", b[1]) : "--", "NOISE", haveNoise ? "uV, " + lvl : "uV");
            drawRings();
            if (o != null && o.avg != null) { t.append(t.length() > 0 ? "\n" : "").append(String.format(Locale.UK, "R %.0f uV", o.rUv)); if (!Double.isNaN(o.qrsMs)) t.append(String.format(Locale.UK, "  \u00b7  QRS %.0f ms", o.qrsMs)); if (!Double.isNaN(o.postDipUv)) t.append(String.format(Locale.UK, "  \u00b7  S/ST dip %.0f uV", o.postDipUv)); if (!Double.isNaN(o.qtMs)) t.append(String.format(Locale.UK, "  \u00b7  QT %.0f ms", o.qtMs)); }
            if (haveNoise) t.append("\n").append(String.format(Locale.UK, "Noise in the QRS band %.0f uV (usual 15-27): %s", b[1], lvl));
            t.append("\nStopped ").append(stopReason == 1 ? "by auto-stop" : stopReason == 2 ? "by the guided run" : stopReason == 3 ? "when your fingers lifted" : "by you").append(String.format(Locale.UK, " after %.0f s.", dur));
            resultBody.setText(t.toString());
            boolean noisy = haveNoise && b[1] > 27;
            whyHint.setVisibility(noisy ? View.VISIBLE : View.GONE); whyRow.setVisibility(noisy ? View.VISIBLE : View.GONE);
            for (int i = 0; i < whyRow.getChildCount(); i++) ((TextView) whyRow.getChildAt(i)).setTextColor(INK);
            if (avgView != null && o != null) { avgView.set(o); avgText.setText(avgLine(o)); }
            stPill.setVisibility(View.VISIBLE); statusCard.setOnClickListener(null);
            SendFlow.attach(a, stPill, startSec, null);
            startNoteTick(startSec);
        } catch (Throwable e) { fail("result", e); }
        if (guidedThisRun) {
            guidedThisRun = false; pr.edit().putString("run_id", "").apply();      // a guided run is one-shot; it kept stopping every later reading at 45 s (23:10 reading of 5 Nov)
            if (stopReason == 2) { try { SendFlow.autoSend(a, startSec); a.toast("Done: sending"); } catch (Throwable e) { fail("auto send", e); } }
        }
        applyMode(DONE);
    }

    /** The one status line under the card: what the app is doing, or what is in the zip. Stops by itself when the page leaves the result. */
    private void startNoteTick(final long startSec) {
        final Runnable[] r = new Runnable[1];
        r[0] = new Runnable() { @Override public void run() {
            try {
                if (sendNote == null || mode != DONE || startMs / 1000L != startSec) return;
                SendFlow.St s = SendFlow.status(dir(), startSec);
                sendNote.setText(s.kind == 1 ? "Send is getting the strap's end events and the report ready (about 1 to 3 min). You can tap it now: it sends when ready." : s.kind == 3 ? "Saved: ready to upload." : s.noEvents ? "Ready. The strap sent no end events for this reading (events 118 / 119); the zip has everything else." : "Ready. The zip has the end events, the report with them and your notes.");
                h.postDelayed(r[0], 2000L);
            } catch (Throwable ignored) { }
        } };
        h.post(r[0]);
    }

    /** 0.44.0: loads the live raw bin of the finished reading (off the main thread) into the replay card. */
    private void startReplayLoad() {
        try {
            final long sec = startMs / 1000L;
            if (replayView == null || replayLoading || replayFor == sec) return;
            replayLoading = true; replayView.setData(null, "Loading the reading\u2026");
            final File d = dir();
            new Thread(new Runnable() { @Override public void run() {
                ReplayData rd = null;
                try { try { Thread.sleep(800L); } catch (InterruptedException ignored) { } rd = ReplayData.load(ProReportMaker.findBin(d, sec)); } catch (Throwable ignored) { }
                final ReplayData fr = rd;
                h.post(new Runnable() { @Override public void run() {
                    replayLoading = false;
                    if (mode == DONE && startMs / 1000L == sec && replayView != null) { replayView.setData(fr, fr == null ? "No 500 Hz data found for this reading" : null); replayFor = sec; }
                } });
            } }).start();
        } catch (Throwable e) { replayLoading = false; fail("replay", e); }
    }

    private void pullSoon() { try { if (!MainActivity.linkRunning()) MainActivity.startLinkHost(a); MainActivity.requestPull(); } catch (Throwable ignored) { } }
}
