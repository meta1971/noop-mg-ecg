package com.noop.mgecg;

// FILE VERSION 0.43.2 (6 Nov 2026): the 5 s countdown is a slim pill in the page (it was a card over the bottom that covered START and Send), the pinned bar floats on a fade like the Home navigation bar, the "report ready" banner is not shown over this page. 0.43.1 (6 Nov 2026): the reading page rebuilt in the style of the "Hi, Ame" pages (HealthActivity / HealthViews): Outfit thin figures, small caps labels, glass cards, the glowing tick dial, blue pills. Nothing of the old page is stacked any more:
// every old row is hidden, only the live graph is reused.
//   AT REST   header, dial (last heart rate), one line of status, the latest reading (heart rate, HRV, grade) with its Send button, the next test (one tap), two chips (setup, Lab).
//   RUNNING   header, dial (live heart rate, m:ss, ticks fill to 60 s), the cue pill (guided tests), ONE signal-light line, the live trace, "Details" (closed), STOP (1 s hold).
//   DONE      header, dial, the strap verdict with heart rate, HRV and noise, Send (one button: prepares the whole pull by itself, SendFlow), "Details" (closed), next test, chips.
// Lab: runs A-F as a checklist with ticks from reading_notes (LabTests), one tap starts a test. The four graph rows are under You > Settings.
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
    static final String VERSION = "0.43.2-pro";
    static final int REST = 0, RUN = 1, DONE = 2;
    private static final int BLUE = HealthViews.BLUE, INK = HealthViews.INK, MUT = HealthViews.MUT, GREEN = HealthViews.GREEN, GOLD = HealthViews.GOLD, ROSE = HealthViews.ROSE, DARK = 0xFF05131E;

    private final NewLookActivity a;
    private final SharedPreferences pr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GuidedRun guide = new GuidedRun();

    // views
    private LinearLayout colRef, bar, headerRow, signalCard, waveHost, resultCard, detailsBody, resultDetails, liveCard, avgCard, latestCard, nextRow, chipsRow, cuePill, whyRow, holder;
    private TextView greetView, titleView, linkChip, statusLine, signalView, resultTitle, sendNote, detailsHeader, resultBody, whyHint, strapLine, bandsView, avgText, latestDate, nextText, setupChip, labChip, cueView, footer, sendBtn, latestSend, nextBtn, targetBtn;
    private final TextView[] rVal = new TextView[3], rCap = new TextView[3], lVal = new TextView[3], lCap = new TextView[3];
    private EcgOrb orb;
    private TextView countPill;
    private AvgBeatView avgView;
    private ProgressLine cueLine;
    private Button start;
    private LinearLayout waveCardRef;
    private TextView statusRef;
    private LinearLayout setupBody;
    private final TextView[] rowValues = new TextView[9];
    private ScrollView scrollRef;
    private boolean holdFired, guidedThisRun, detailsOpen, readyCancelled;
    private Runnable holdRun;
    private int cueCount, mode = REST, runStopSec, readyLeft;
    private android.speech.tts.TextToSpeech tts; private volatile boolean ttsOk;

    // state
    private boolean running, autoStopped;
    private long startMs, lastRestMs; private String cueLog = "", lastHr = "", nextId = "", lastStatus = "";
    private long cueUntil; private String lastCue = "";
    private LiveMeasure.Out lastOut; private double[] lastBands;
    private int stopReason;           // 0 by hand, 1 auto-stop, 2 guided run
    private String failures = "";
    private double lastDur;

    EcgPage(NewLookActivity act) { a = act; pr = act.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ helpers (the same looks as HealthActivity)
    private int dp(int v) { return a.dp(v); }
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

    private void applyDisplayPrefs() {
        try { Live500.pro = pref("pro_display", "1").equals("1"); Live500View.windowMs = pr.getInt("graph_window", 6000); Live500View.sweep = pr.getBoolean("graph_sweep", false); Live500View.paper = pr.getBoolean("graph_paper", false); } catch (Throwable e) { fail("display options", e); }
    }

    /** Three thin figures with small caps under them (the look of the Home tiles). */
    private LinearLayout tiles(TextView[] vals, TextView[] caps, String[] labels) {
        LinearLayout row = hrow();
        for (int i = 0; i < 3; i++) {
            LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL);
            vals[i] = t("\u2014", 30, INK, 300); caps[i] = caps(labels[i]); caps[i].setTextSize(10); caps[i].setLetterSpacing(0.12f);
            c.addView(vals[i], new LinearLayout.LayoutParams(-2, -2)); c.addView(caps[i], lp(-2, -2, 6));
            row.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return row;
    }

    // ------------------------------------------------------------------ build
    /** Called once from NewLookActivity.styleEcgColumn. The old rows are hidden; the live graph and the START / STOP button are reused. */
    void build(FrameLayout container, LinearLayout col, View top, TextView status, TextView verdict, LinearLayout waveCard, LinearLayout stats, Button startBtn) {
        start = startBtn; waveCardRef = waveCard; statusRef = status; colRef = col;
        // while a reading runs, STOP needs a 1 s press-and-hold (a thumb brushing it while scrolling stopped the 22:00 reading on 5 Nov). Timed, guided and auto stops click the button directly.
        start.setOnTouchListener(new View.OnTouchListener() { @Override public boolean onTouch(final View v, MotionEvent e) {
            if (!start.getText().toString().startsWith("STOP")) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdFired = false; v.setPressed(true);
                    holdRun = new Runnable() { @Override public void run() { holdFired = true; v.setPressed(false); try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignored) { } v.performClick(); } };
                    h.postDelayed(holdRun, 1000L); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (holdRun != null) h.removeCallbacks(holdRun); v.setPressed(false);
                    if (!holdFired && e.getActionMasked() == MotionEvent.ACTION_UP) a.toast("Hold STOP for 1 second to end the reading");
                    return true;
                default: return true;
            }
        } });
        try { if (col.getParent() instanceof ScrollView) scrollRef = (ScrollView) col.getParent(); } catch (Throwable ignored) { }
        applyDisplayPrefs();
        try { if (pr.getLong("run_armed_ms", 0L) + 90000L < System.currentTimeMillis()) pr.edit().putString("run_id", "").apply(); } catch (Throwable ignored) { }      // a test selected in 0.42.x must never start a normal reading
        // the START / STOP button goes into a pinned bar, in the Home pill look
        try {
            if (startBtn.getParent() != null) ((ViewGroup) startBtn.getParent()).removeView(startBtn);
            start.setAllCaps(false); start.setTextSize(17); start.setTypeface(HealthViews.font(a, 600)); start.setTextColor(DARK);
            bar = new LinearLayout(a); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x0007090C, 0xF007090C, 0xFF07090C })); bar.setPadding(dp(18), dp(30), dp(18), dp(18));
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
        try { buildHero(col); } catch (Throwable e) { fail("dial", e); }
        try { buildRunning(col, waveCard); } catch (Throwable e) { fail("running", e); }
        try { buildResult(col); } catch (Throwable e) { fail("result card", e); }
        try { buildDetails(col); } catch (Throwable e) { fail("details", e); }
        try { buildLatest(col); } catch (Throwable e) { fail("latest reading", e); }
        try { buildBottom(col); } catch (Throwable e) { fail("setup and Lab", e); }
        try {
            footer = t("", 11, GOLD, 400); footer.setGravity(Gravity.CENTER); footer.setVisibility(View.GONE);
            col.addView(footer, lp(-1, -2, 12));
            String old = pr.getString("page_fail", ""); if (!failures.isEmpty()) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
            else if (!old.isEmpty()) pr.edit().remove("page_fail").apply();
        } catch (Throwable e) { /* cosmetic */ }
        applyMode(REST);
    }

    /** Called at the end of NewLookActivity.styleEcgColumn: the old Markers, Packets and Research rows move under Details; the old "preparing" line is dropped. */
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
        TextView back = t("\u2039", 30, INK, 300); back.setGravity(Gravity.CENTER); back.setBackground(HealthViews.glass(a, 22)); back.setContentDescription("Back");
        back.setOnClickListener(v -> { if (!running) a.onBackPressed(); else a.toast("Hold STOP for 1 second to end the reading"); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(44), dp(44)); bp.rightMargin = dp(14); headerRow.addView(back, bp);
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

    private void buildHero(LinearLayout col) {
        orb = new EcgOrb(a);
        LinearLayout.LayoutParams op = lp(dp(220), dp(220), 14); op.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(orb, op);
        statusLine = t("", 13, MUT, 400); statusLine.setGravity(Gravity.CENTER); statusLine.setLineSpacing(0, 1.2f);
        col.addView(statusLine, lp(-1, -2, 4));
        countPill = pill("", BLUE, DARK); countPill.setTextSize(14); countPill.setMaxLines(2); countPill.setPadding(dp(18), dp(10), dp(18), dp(10)); countPill.setVisibility(View.GONE);
        countPill.setOnClickListener(v -> { a.cancelAutoCountdown(); a.toast("Not starting by itself. Press START ECG when you are ready."); });
        col.addView(countPill, lp(-1, -2, 12));
        // the cue pill of a guided test: one slim line
        cuePill = new LinearLayout(a); cuePill.setOrientation(LinearLayout.VERTICAL); cuePill.setVisibility(View.GONE);
        cueView = pill("", BLUE, DARK); cueView.setTextSize(15); cueView.setMaxLines(2); cueView.setPadding(dp(18), dp(10), dp(18), dp(10)); cueView.setMinHeight(dp(56));
        cueView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        cuePill.addView(cueView, new LinearLayout.LayoutParams(-1, -2));
        cueLine = new ProgressLine(a, BLUE, 0x22FFFFFF); cuePill.addView(cueLine, lp(-1, dp(3), 6));
        cuePill.setOnClickListener(v -> { if (!running && readyLeft > 0) { readyCancelled = true; pr.edit().putString("run_id", "").apply(); cuePill.setVisibility(View.GONE); a.toast("Test cancelled"); } });
        col.addView(cuePill, lp(-1, -2, 12));
    }

    private void buildRunning(LinearLayout col, LinearLayout waveCard) {
        signalCard = glass(14);
        signalView = t("", 14, MUT, 400); signalView.setGravity(Gravity.CENTER); signalCard.addView(signalView, new LinearLayout.LayoutParams(-1, -2));
        signalCard.setVisibility(View.GONE);
        col.addView(signalCard, lp(-1, -2, 12));
        waveHost = glass(6);
        waveCard.setBackground(null); waveCard.setPadding(0, 0, 0, 0);
        waveHost.addView(waveCard, new LinearLayout.LayoutParams(-1, -2));
        waveHost.setVisibility(View.GONE);
        col.addView(waveHost, lp(-1, -2, 12));
    }

    private static final String[] WHY = { "Tension", "Vibration", "Cold hands", "Charger near", "Not sure" };
    private void buildResult(LinearLayout col) {
        resultCard = glass(18);
        resultCard.addView(caps("STRAP VERDICT"));
        resultTitle = t("", 15, INK, 400); resultTitle.setLineSpacing(0, 1.2f); resultTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        resultCard.addView(resultTitle, lp(-1, -2, 6));
        resultCard.addView(tiles(rVal, rCap, new String[] { "HEART RATE", "STRAP HRV MS", "NOISE UV" }), lp(-1, -2, 16));
        sendBtn = pill("Send", BLUE, DARK); sendBtn.setTextSize(16);
        resultCard.addView(sendBtn, lp(-1, dp(52), 18));
        sendNote = t("", 12, MUT, 400); sendNote.setGravity(Gravity.CENTER); sendNote.setLineSpacing(0, 1.2f);
        resultCard.addView(sendNote, lp(-1, -2, 8));
        resultCard.setVisibility(View.GONE);
        col.addView(resultCard, lp(-1, -2, 12));
    }

    private void buildDetails(LinearLayout col) {
        detailsHeader = chip("Details  \u25be"); detailsHeader.setTextColor(MUT);
        detailsHeader.setOnClickListener(v -> { detailsOpen = !detailsOpen; applyDetails(); });
        detailsHeader.setVisibility(View.GONE);
        col.addView(detailsHeader, lp(-1, -2, 12));
        detailsBody = new LinearLayout(a); detailsBody.setOrientation(LinearLayout.VERTICAL); detailsBody.setVisibility(View.GONE);
        col.addView(detailsBody, lp(-1, -2, 0));
        // after a reading: every result line, the why-noisy tap
        resultDetails = glass(16);
        resultBody = t("", 13, MUT, 400); resultBody.setLineSpacing(0, 1.2f); resultDetails.addView(resultBody, new LinearLayout.LayoutParams(-1, -2));
        whyHint = t("What made it noisy? One tap labels this reading.", 12, GOLD, 400); resultDetails.addView(whyHint, lp(-1, -2, 10));
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
        strapLine = t("", 13, MUT, 400); strapLine.setGravity(Gravity.CENTER); liveCard.addView(strapLine, new LinearLayout.LayoutParams(-1, -2));
        bandsView = t("", 12, MUT, 400); bandsView.setGravity(Gravity.CENTER); bandsView.setLineSpacing(0, 1.2f); liveCard.addView(bandsView, lp(-1, -2, 6));
        targetBtn = chip(""); targetBtn.setTextSize(12);
        targetBtn.setOnClickListener(v -> { double tg = a.autoTargetValue(); double nt = tg < 3.5 ? 4.0 : tg < 4.5 ? 5.0 : 3.0; pr.edit().putFloat("auto_target", (float) nt).putBoolean("auto_stop", true).apply(); refreshSummary(); if (rowValues[4] != null) rowValues[4].setText((nt < 3.5 ? AUTO[1] : nt < 4.5 ? AUTO[2] : AUTO[3]) + "  \u25be"); updateTarget(Double.NaN); });
        liveCard.addView(targetBtn, lp(-1, -2, 10));
        addExplain(liveCard, Explain.noiseBands());
        liveCard.setVisibility(View.GONE);
        detailsBody.addView(liveCard, lp(-1, -2, 8));
        // the averaged beat
        avgCard = glass(10);
        avgView = new AvgBeatView(a); avgCard.addView(avgView, new LinearLayout.LayoutParams(-1, dp(190)));
        avgText = t("", 12, MUT, 400); avgText.setGravity(Gravity.CENTER); avgText.setLineSpacing(0, 1.2f); avgCard.addView(avgText, lp(-1, -2, 6));
        addExplain(avgCard, Explain.avgBeatLive());
        avgCard.setVisibility(View.GONE);
        detailsBody.addView(avgCard, lp(-1, -2, 8));
    }

    private void applyDetails() {
        boolean on = mode != REST;
        if (detailsHeader != null) { detailsHeader.setVisibility(on ? View.VISIBLE : View.GONE); detailsHeader.setText("Details  " + (detailsOpen ? "\u25b4" : "\u25be")); }
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

    private void buildLatest(LinearLayout col) {
        latestCard = glass(18);
        LinearLayout hd = hrow(); hd.addView(caps("LATEST READING")); hd.addView(spacer()); latestDate = t("", 12, MUT, 400); hd.addView(latestDate);
        latestCard.addView(hd, new LinearLayout.LayoutParams(-1, -2));
        latestCard.addView(tiles(lVal, lCap, new String[] { "HEART RATE", "HRV MS", "GRADE" }), lp(-1, -2, 14));
        latestSend = pill("Send", BLUE, DARK); latestSend.setTextSize(15); latestSend.setVisibility(View.GONE);
        latestCard.addView(latestSend, lp(-1, dp(48), 16));
        col.addView(latestCard, lp(-1, -2, 18));
    }

    private void buildBottom(LinearLayout col) {
        nextRow = glass(14);
        LinearLayout nr = hrow();
        LinearLayout nl = new LinearLayout(a); nl.setOrientation(LinearLayout.VERTICAL);
        nl.addView(caps("NEXT TEST")); nextText = t("", 15, INK, 400); nl.addView(nextText, lp(-2, -2, 6));
        nr.addView(nl, new LinearLayout.LayoutParams(0, -2, 1f));
        nextBtn = pill("Start", BLUE, DARK); nextBtn.setPadding(dp(22), 0, dp(22), 0);
        nextBtn.setOnClickListener(v -> { if (!nextId.isEmpty()) startRun(nextId); });
        nr.addView(nextBtn, new LinearLayout.LayoutParams(-2, dp(42)));
        nextRow.addView(nr, new LinearLayout.LayoutParams(-1, -2));
        nextRow.setVisibility(View.GONE);
        col.addView(nextRow, lp(-1, -2, 12));
        chipsRow = hrow();
        setupChip = chip(""); setupChip.setSingleLine(true); setupChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        setupChip.setOnClickListener(v -> { if (!running) openSetup(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f); sp.rightMargin = dp(8);
        chipsRow.addView(setupChip, sp);
        labChip = chip("Lab  \u203a"); labChip.setOnClickListener(v -> { if (!running) openLab(); });
        chipsRow.addView(labChip, new LinearLayout.LayoutParams(-2, -2));
        col.addView(chipsRow, lp(-1, -2, 12));
        buildSetupRows(); refreshSummary();
    }

    // ------------------------------------------------------------------ the three states of the page
    private void applyMode(int m) {
        mode = m;
        try {
            boolean rest = m == REST, run = m == RUN, done = m == DONE;
            if (statusLine != null) statusLine.setVisibility(rest || done ? View.VISIBLE : View.GONE);
            if (signalCard != null) signalCard.setVisibility(run ? View.VISIBLE : View.GONE);
            if (waveHost != null) waveHost.setVisibility(run ? View.VISIBLE : View.GONE);
            if (waveCardRef != null) waveCardRef.setVisibility(View.VISIBLE);
            if (resultCard != null) resultCard.setVisibility(done ? View.VISIBLE : View.GONE);
            if (resultDetails != null) resultDetails.setVisibility(done ? View.VISIBLE : View.GONE);
            if (liveCard != null) liveCard.setVisibility(run ? View.VISIBLE : View.GONE);
            if (avgCard != null) avgCard.setVisibility(run || (done && lastOut != null) ? View.VISIBLE : View.GONE);
            if (latestCard != null) latestCard.setVisibility(rest ? View.VISIBLE : View.GONE);
            if (chipsRow != null) chipsRow.setVisibility(run ? View.GONE : View.VISIBLE);
            if (cuePill != null && !run && readyLeft <= 0) cuePill.setVisibility(View.GONE);
            applyDetails();
            if (!run) refreshNext(); else if (nextRow != null) nextRow.setVisibility(View.GONE);
            String name = a.getSharedPreferences("today", android.content.Context.MODE_PRIVATE).getString("name", "Ame");
            if (rest) { greetView.setText("Hi, " + name); titleView.setText("Take a reading"); }
            else if (run) { greetView.setText("ECG"); GuidedRun.Def d = GuidedRun.byId(guidedThisRun ? pref("run_id", "") : ""); titleView.setText(guidedThisRun && !d.id.isEmpty() ? d.title : "Reading"); }
            else { greetView.setText("Reading done"); titleView.setText(String.format(Locale.UK, "%d:%02d recorded", (long) lastDur / 60, (long) lastDur % 60)); }
            if (rest) refreshLatest();
            if (orb != null) { if (rest) orb.set(lastHr.isEmpty() ? "ECG" : lastHr, lastHr.isEmpty() ? "READY" : "LAST BPM", 0f); }
            updateLink(); syncStatus();
            if (rest && scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });
        } catch (Throwable e) { fail("page state", e); }
    }

    /** Called each time the ECG screen is opened: a page left on the last result goes back to rest (the latest reading card then carries its Send). */
    void onOpen() { try { if (!running) { detailsOpen = false; applyMode(REST); } } catch (Throwable e) { fail("open", e); } }

    /** The 5 s auto-start countdown, shown inside the page; null hides it. A tap cancels it. */
    void setCountdown(String s) {
        try { if (countPill == null) return; if (s == null || running) { countPill.setVisibility(View.GONE); return; } countPill.setText(s); countPill.setVisibility(View.VISIBLE); } catch (Throwable e) { fail("countdown", e); }
    }

    private void syncStatus() {
        try { if (statusLine == null || statusRef == null) return; String s = statusRef.getText() == null ? "" : statusRef.getText().toString(); if (!s.equals(lastStatus)) { lastStatus = s; statusLine.setText(s); } } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ setup sheet and Lab sheet
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
        if (setupChip == null) return;
        String s = a.readingTag.isEmpty() ? "No tag" : a.readingTag, p = a.positionTag.isEmpty() ? "Wrist" : a.positionTag, w = pref("where_tag", "");
        setupChip.setText(s + "  \u00b7  " + p + (w.isEmpty() ? "" : "  \u00b7  " + w) + (!pr.getBoolean("auto_stop", true) ? "  \u00b7  no auto-stop" : "") + "  \u25be");
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
    void refreshLatest() {
        try {
            if (latestCard == null) return;
            List<NewLookActivity.Rep> reps = a.loadReports();
            if (reps.isEmpty()) { latestCard.setVisibility(View.GONE); lastHr = ""; return; }
            final NewLookActivity.Rep r = reps.get(0);
            lastHr = "-".equals(r.hr) ? "" : r.hr;
            latestDate.setText(r.dateText);
            lVal[0].setText(lastHr.isEmpty() ? "\u2014" : lastHr); lCap[0].setText("HEART RATE");
            lVal[1].setText("-".equals(r.hrv) ? "\u2014" : r.hrv); lCap[1].setText("HRV MS");
            lVal[2].setText("?".equals(r.grade) ? "\u2014" : r.grade); lCap[2].setText("GRADE");
            lVal[2].setTextColor(r.gradeColor());
            latestSend.setVisibility(View.VISIBLE);
            SendFlow.attach(a, latestSend, ProReportMaker.unixOf(r.file), r.file);
            latestCard.setOnClickListener(v -> a.openReport(r.file));
            if (mode == REST && orb != null) orb.set(lastHr.isEmpty() ? "ECG" : lastHr, lastHr.isEmpty() ? "READY" : "LAST BPM", 0f);
        } catch (Throwable e) { fail("latest reading", e); }
    }

    void refreshNext() {
        try {
            nextId = LabTests.next(LabTests.done(dir()), LabTests.hourNow());
            if (nextRow == null) return;
            if (nextId.isEmpty() || running || mode == RUN) { nextRow.setVisibility(View.GONE); return; }
            nextText.setText(nextId + "   " + LabTests.name(nextId)); nextRow.setVisibility(View.VISIBLE);
        } catch (Throwable e) { fail("next test", e); }
    }

    /** Called every 500 ms by NewLookActivity while the screen is at rest. */
    void tickRest() {
        if (running) return;
        syncStatus();
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
                autoStopped = false; stopReason = 0; cueLog = ""; lastOut = null; lastBands = null; LiveMeasure.clear(); readyCancelled = true; readyLeft = 0; detailsOpen = false;
                applyDisplayPrefs();
                if (avgView != null) avgView.set(null);
                String rid = pref("run_id", ""); if (!rid.isEmpty() && pr.getLong("run_armed_ms", 0L) + 90000L < System.currentTimeMillis()) { rid = ""; pr.edit().putString("run_id", "").apply(); }
                GuidedRun.Def d = GuidedRun.byId(rid);
                guidedThisRun = !d.id.isEmpty(); runStopSec = d.stopSec;
                if (!d.id.isEmpty()) { cueCount = 0; initTts(); guide.start(d, startMs, sink); cuePill.setVisibility(View.VISIBLE); cueView.setText("Keep still and relaxed"); cueLine.set(0f); } else { guide.cancel(); cuePill.setVisibility(View.GONE); }
                if (orb != null) orb.set("--", "0:00", 0f);
                if (scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });
                ReadingNotes.put(dir(), startMs / 1000L, "app", VERSION);
                ReadingNotes.put(dir(), startMs / 1000L, "where", pref("where_tag", ""));
                ReadingNotes.put(dir(), startMs / 1000L, "pos", a.positionTag);
                ReadingNotes.put(dir(), startMs / 1000L, "tag", a.readingTag);
                ReadingNotes.put(dir(), startMs / 1000L, "run", d.id);
                ReadingNotes.put(dir(), startMs / 1000L, "pro", Live500.pro ? "1" : "0");
                applyMode(RUN);
            } else if (!nowRunning && was) {
                guide.cancel(); finishReading();
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
            // signal light: ONE line on its card; the strap's own check goes under Details
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
            signalView.setText(txt); signalView.setTextColor(colr); strapLine.setText(strap);
            if (!Double.isNaN(b[1])) {
                String bt = String.format(Locale.UK, "Noise (between beats):  QRS band 8-25 Hz %.0f uV (usual 15-27)  \u00b7  25-48 Hz %.0f  \u00b7  50 Hz %.0f  \u00b7  drift %.0f", b[1], b[2], b[3], b[0]);
                String hint = LiveBands.hint(b, lastOut != null && !Double.isNaN(lastOut.rUv) ? lastOut.rUv : Double.NaN);
                if (hint == null && b[1] > 40) hint = "Broadband noise (muscle or vibration): relax the hand, rest the forearm, keep still";
                bandsView.setText(bt + (hint != null && b[1] > 27 ? "\n" + hint : ""));
            } else bandsView.setText("Noise by band appears after about 10 s of beats");
            updateTarget(est);
            // the dial: live heart rate, m:ss, ticks fill towards 60 s (a guided test: towards its end)
            double rrS = Live500.medianRrS();
            orb.set(Double.isNaN(rrS) ? "--" : String.valueOf(Math.round(60.0 / rrS)), String.format(Locale.UK, "%d:%02d", el / 60000, (el / 1000) % 60), guide.active() && runStopSec > 0 ? el / (runStopSec * 1000f) : el / 60000f);
            // averaged beat
            LiveMeasure.request();
            LiveMeasure.Out o = LiveMeasure.last();
            if (o != null && o != lastOut) { lastOut = o; avgView.set(o); avgCard.setVisibility(View.VISIBLE); avgText.setText(avgLine(o)); }
            if (guide.active() && runStopSec > 0 && cueLine != null) cueLine.set(el / (runStopSec * 1000f));
            // auto-stop (not during a guided run)
            boolean auto = pr.getBoolean("auto_stop", true) && !guide.active();
            if (auto && !autoStopped && el > NewLookActivity.AUTO_MIN_MS && ((!Double.isNaN(est) && est <= a.autoTargetValue()) || el > NewLookActivity.AUTO_MAX_MS)) {
                autoStopped = true; stopReason = 1;
                a.toast(el > NewLookActivity.AUTO_MAX_MS && (Double.isNaN(est) || est > a.autoTargetValue()) ? "Stopped at 5 minutes (report quality " + (Double.isNaN(est) ? "-" : String.format(Locale.UK, "%.1f", est)) + " uV)" : String.format(Locale.UK, "Stopped: report quality %.1f uV reached", est));
                if (start != null && start.getText().toString().startsWith("STOP")) start.performClick();
            }
            if (start != null && running) { start.setText(String.format(Locale.UK, "STOP (hold 1 s)  \u00b7  %d:%02d", el / 60000, (el / 1000) % 60)); start.setContentDescription(String.format(Locale.UK, "Stop the reading. Press and hold for one second. %d minutes %d seconds so far.", el / 60000, (el / 1000) % 60)); }
            try {                                                                      // a spoken summary of the live graph for a screen reader
                String say = txt.replace("\u25cf  ", "");
                String desc = "Live ECG trace, 500 hertz. " + (Double.isNaN(rrS) ? "" : String.format(Locale.UK, "Heart rate %.0f beats a minute. ", 60.0 / rrS)) + say + ".";
                if (waveCardRef != null) for (int i = 0; i < waveCardRef.getChildCount(); i++) { View vv = waveCardRef.getChildAt(i); if (vv instanceof Live500View) vv.setContentDescription(desc); }
            } catch (Throwable ignored) { }
        } catch (Throwable e) { fail("update", e); }
    }

    private void updateTarget(double est) {
        if (targetBtn == null) return;
        double tg = a.autoTargetValue(); boolean auto = pr.getBoolean("auto_stop", true);
        String e = Double.isNaN(est) ? "settling" : String.format(Locale.UK, "%.1f uV", est);
        targetBtn.setText(auto ? "Report quality so far " + e + "  \u2192  target " + String.format(Locale.UK, "%.0f", tg) + " uV  (tap: 3, 4, 5)" : "Auto-stop off  \u00b7  quality so far " + e + "  (tap: 3, 4, 5 turns it on)");
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
            ReadingNotes.put(d, startSec, "stop", stopReason == 1 ? "auto" : stopReason == 2 ? "run" : "hand");
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
        // result card: strap verdict, heart rate, HRV, noise, Send; every other line under Details
        try {
            if (b == null) b = new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
            if (vc != 0) resultTitle.setText("Strap verdict: " + StrapVerdict.describe(vc, Live500.verdictHr, Live500.lastMask)); else resultTitle.setText(dur < 42 ? "No strap verdict yet: the reading was " + String.format(Locale.UK, "%.0f", dur) + " s (the strap needs about 41 s from Start, 37 s of it in unbroken contact)" : "No strap verdict (contact was broken before it finished, or it was stopped too early)");
            double rr = Live500.medianRrS();
            String hrTxt = Double.isNaN(rr) ? "\u2014" : String.valueOf(Math.round(60.0 / rr));
            rVal[0].setText(hrTxt); rCap[0].setText(vc != 0 ? "HEART RATE \u00b7 STRAP " + Live500.verdictHr : "HEART RATE");
            rVal[1].setText(Live500.hrvMs != 0 ? String.valueOf(Live500.hrvVal) : "\u2014"); rCap[1].setText(Live500.hrvMs != 0 ? String.format(Locale.UK, "STRAP HRV MS \u00b7 FROM %.0f S", (Live500.hrvMs - Live500.sessionMs) / 1000.0) : "STRAP HRV MS");
            if (!Double.isNaN(b[1])) { rVal[2].setText(String.format(Locale.UK, "%.0f", b[1])); rVal[2].setTextColor(b[1] <= 27 ? GREEN : b[1] <= 40 ? GOLD : ROSE); rCap[2].setText("NOISE UV \u00b7 " + (b[1] <= 27 ? "GOOD" : b[1] <= 40 ? "FAIR" : "NOISY")); }
            else { rVal[2].setText("\u2014"); rVal[2].setTextColor(INK); rCap[2].setText("NOISE UV"); }
            if (!Double.isNaN(rr)) lastHr = hrTxt;
            if (orb != null) orb.set(hrTxt.equals("\u2014") ? "--" : hrTxt, "BPM", 1f);
            StringBuilder t = new StringBuilder();
            if (!Double.isNaN(rr)) { t.append(String.format(Locale.UK, "HR %.0f", 60.0 / rr)); if (vc != 0) t.append(String.format(Locale.UK, " (strap %d)", Live500.verdictHr)); }
            if (Live500.hrvMs != 0) t.append(String.format(Locale.UK, "  \u00b7  strap HRV %d ms (from %.0f s)", Live500.hrvVal, (Live500.hrvMs - Live500.sessionMs) / 1000.0));
            if (o != null && o.avg != null) { t.append(t.length() > 0 ? "\n" : "").append(String.format(Locale.UK, "R %.0f uV", o.rUv)); if (!Double.isNaN(o.qrsMs)) t.append(String.format(Locale.UK, "  \u00b7  QRS %.0f ms", o.qrsMs)); if (!Double.isNaN(o.postDipUv)) t.append(String.format(Locale.UK, "  \u00b7  S/ST dip %.0f uV", o.postDipUv)); if (!Double.isNaN(o.qtMs)) t.append(String.format(Locale.UK, "  \u00b7  QT %.0f ms", o.qtMs)); }
            if (!Double.isNaN(b[1])) t.append("\n").append(String.format(Locale.UK, "Noise in the QRS band %.0f uV (usual 15-27): %s", b[1], b[1] <= 27 ? "good" : b[1] <= 40 ? "fair" : "noisy"));
            t.append("\nStopped ").append(stopReason == 1 ? "by auto-stop" : stopReason == 2 ? "by the guided run" : "by you").append(String.format(Locale.UK, " after %.0f s.", dur));
            t.append("\nSend collects everything by itself: the strap's end events (118 / 119), the report written again with them, and the zip.");
            resultBody.setText(t.toString());
            boolean noisy = !Double.isNaN(b[1]) && b[1] > 27;
            whyHint.setVisibility(noisy ? View.VISIBLE : View.GONE); whyRow.setVisibility(noisy ? View.VISIBLE : View.GONE);
            for (int i = 0; i < whyRow.getChildCount(); i++) ((TextView) whyRow.getChildAt(i)).setTextColor(INK);
            if (avgView != null && o != null) { avgView.set(o); avgText.setText(avgLine(o)); }
            SendFlow.attach(a, sendBtn, startSec, null);
            startNoteTick(startSec);
        } catch (Throwable e) { fail("result card", e); }
        if (guidedThisRun) {
            guidedThisRun = false; pr.edit().putString("run_id", "").apply();      // a guided run is one-shot; it kept stopping every later reading at 45 s (23:10 reading of 5 Nov)
            if (stopReason == 2) { try { SendFlow.autoSend(a, startSec); a.toast("Done: sending"); } catch (Throwable e) { fail("auto send", e); } }
        }
        applyMode(DONE);
    }

    /** The one status line under Send: what the app is doing, or what is in the zip. Stops by itself when the card is hidden. */
    private void startNoteTick(final long startSec) {
        final Runnable[] r = new Runnable[1];
        r[0] = new Runnable() { @Override public void run() {
            try {
                if (sendNote == null || resultCard == null || resultCard.getVisibility() != View.VISIBLE || startMs / 1000L != startSec) return;
                SendFlow.St s = SendFlow.status(dir(), startSec);
                sendNote.setText(s.kind == 1 ? "Getting the strap's end events and the report ready (about 1 to 3 min). You can tap Send now: it sends when ready." : s.kind == 3 ? "Saved: ready to upload." : s.noEvents ? "Ready. The strap sent no end events for this reading (events 118 / 119); the zip has everything else." : "Ready. The zip has the end events, the report with them and your notes.");
                h.postDelayed(r[0], 2000L);
            } catch (Throwable ignored) { }
        } };
        h.post(r[0]);
    }

    private void pullSoon() { try { if (!MainActivity.linkRunning()) MainActivity.startLinkHost(a); MainActivity.requestPull(); } catch (Throwable ignored) { } }
}
