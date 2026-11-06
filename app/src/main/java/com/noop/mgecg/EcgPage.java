package com.noop.mgecg;

// FILE VERSION 0.41.0 (5 Nov 2026): the reading page: dropdown setup (posture, strap position, where, guided run, auto-stop, graph options), pinned START / STOP, live signal light with noise by band,
// the averaged beat with QRS / S-ST dip marked, guided cues, the instant result card and the why-noisy tap. Built inside the old ECG column by NewLookActivity.styleEcgColumn.
// Every step is guarded: a failure is written to the page footer and the rest of the page still works (decode log 116.5: one swallowed exception had stripped the page since 0.39.0).

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

final class EcgPage {
    static final String VERSION = "0.42.3-pro";
    private final NewLookActivity a;
    private final SharedPreferences pr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GuidedRun guide = new GuidedRun();

    // views
    private LinearLayout setupCard, setupBody, liveCard, avgCard, resultCard, whyRow, bar;
    private TextView setupSummary, setupChevron, signalView, bandsView, cueView, avgText, resultTitle, resultBody, whyHint, footer;
    private Button targetBtn;
    private AvgBeatView avgView;
    private View anchor;
    private Button start;
    private LinearLayout waveCardRef;
    private boolean holdFired, guidedThisRun; private Runnable holdRun;
    private android.widget.ScrollView scrollRef;
    private int cueCount; private android.speech.tts.TextToSpeech tts; private volatile boolean ttsOk;
    private TextView[] rowValues = new TextView[9];

    // state
    private boolean running, autoStopped;
    private long startMs; private String cueLog = "";
    private long cueUntil; private String lastCue = "";
    private LiveMeasure.Out lastOut; private double[] lastBands;
    private int stopReason;           // 0 by hand, 1 auto-stop, 2 guided run
    private String failures = "";

    EcgPage(NewLookActivity act) { a = act; pr = act.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ helpers
    private void fail(String step, Throwable t) {
        failures += (failures.isEmpty() ? "" : "; ") + step + ": " + t.getClass().getSimpleName();
        try { pr.edit().putString("page_fail", failures).apply(); } catch (Throwable ignored) { }
        if (footer != null) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
    }
    private String pref(String k, String d) { return pr.getString(k, d); }
    private LinearLayout card() { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(a.round(NewLookActivity.CARD, 18)); l.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12)); return l; }
    private LinearLayout.LayoutParams lp(int w, int hgt, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hgt); p.topMargin = a.dp(top); return p; }
    private File dir() { return a.getExternalFilesDir(null); }

    // ------------------------------------------------------------------ build
    /** Called once from NewLookActivity.styleEcgColumn after the top part is styled. Moves START / STOP into a pinned bar and adds the new cards. */
    void build(FrameLayout container, LinearLayout col, View top, TextView status, TextView verdict, LinearLayout waveCard, LinearLayout stats, Button startBtn) {
        start = startBtn; waveCardRef = waveCard;
        // 0.42.3: while a reading runs, STOP needs a 1 s press-and-hold (a thumb brushing it while scrolling stopped the 22:00 reading). Timed, guided and auto stops click the button directly and are not affected.
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
        try { if (col.getParent() instanceof android.widget.ScrollView) scrollRef = (android.widget.ScrollView) col.getParent(); } catch (Throwable ignored) { }
        try { Live500.pro = pref("pro_display", "1").equals("1"); Live500View.windowMs = pr.getInt("graph_window", 6000); Live500View.sweep = pr.getBoolean("graph_sweep", false); Live500View.paper = pr.getBoolean("graph_paper", false); } catch (Throwable t) { fail("display options", t); }
        // the anchor takes the place of the button in the column; the button itself goes into a pinned bar
        try {
            int idx = col.indexOfChild(startBtn);
            anchor = new View(a); col.addView(anchor, idx < 0 ? col.getChildCount() : idx, new LinearLayout.LayoutParams(-1, a.dp(1)));
            if (startBtn.getParent() != null) ((ViewGroup) startBtn.getParent()).removeView(startBtn);
            bar = new LinearLayout(a); bar.setOrientation(LinearLayout.VERTICAL); bar.setBackgroundColor(NewLookActivity.BG); bar.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(10));
            bar.addView(startBtn, new LinearLayout.LayoutParams(-1, a.dp(64)));
            container.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            col.setPadding(col.getPaddingLeft(), col.getPaddingTop(), col.getPaddingRight(), a.dp(110));
        } catch (Throwable t) { fail("pinned bar", t); }
        // guided cue (above the graph while a run is going)
        try {
            cueView = a.tv("", 18, NewLookActivity.INK, true); cueView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE); cueView.setGravity(Gravity.CENTER); cueView.setBackground(a.round(NewLookActivity.PRIMARY, 18)); cueView.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14)); cueView.setVisibility(View.GONE);
            col.addView(cueView, col.indexOfChild(waveCard), lp(-1, -2, 6));
        } catch (Throwable t) { fail("cue card", t); }
        // setup card with dropdown rows, between the verdict line and the graph
        try { buildSetup(col, col.indexOfChild(cueView != null ? cueView : waveCard)); } catch (Throwable t) { fail("setup", t); }
        // live card, averaged beat, result card: after the BPM / elapsed cards
        int after = col.indexOfChild(stats) + 1;
        try { buildLive(col, col.indexOfChild(waveCard) + 1); } catch (Throwable t) { fail("live card", t); }
        try { after = col.indexOfChild(stats) + 1; buildAvg(col, after); after++; } catch (Throwable t) { fail("averaged beat", t); }
        try { buildResult(col, col.indexOfChild(anchor)); } catch (Throwable t) { fail("result card", t); }
        try {
            footer = a.tv("", 11, NewLookActivity.AMBER, false); footer.setGravity(Gravity.CENTER); footer.setVisibility(View.GONE);
            col.addView(footer, new LinearLayout.LayoutParams(-1, -2));
            String old = pr.getString("page_fail", ""); if (!failures.isEmpty()) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
            else if (!old.isEmpty()) pr.edit().remove("page_fail").apply();
        } catch (Throwable t) { /* cosmetic */ }
        applyRunning(false);
    }

    // ------------------------------------------------------------------ setup card
    private static final String[] POSTURE = { "No tag", "Table", "Couch", "Lying", "Standing", "After exercise" };
    private static final String[] POSITION = { "Wrist", "Chest V2", "Chest V4", "Chest V5", "Chest V6", "Bicep L", "Bicep R", "H10 in hands" };
    private static final String[] WHERE = { "Not set", "Home", "Work", "Truck", "Outside", "Other" };
    private static final String[] AUTO = { "Off", "3 uV", "4 uV", "5 uV" };
    private static final String[] WINDOW = { "6 s", "3.5 s (zoom)" };
    private static final String[] STYLE = { "Scroll", "Sweep with erase bar" };
    private static final String[] THEME = { "Dark", "Paper (light)" };
    private static final String[] PRO = { "On (spline baseline, markers)", "Off (0.05 Hz filter only)" };

    private void buildSetup(LinearLayout col, int index) {
        a.readingTag = pref("reading_tag", a.readingTag);      // the tag chosen last time stays shown
        a.positionTag = pref("position_tag", a.positionTag);
        setupCard = card();
        LinearLayout head = new LinearLayout(a); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        setupSummary = a.tv("", 14, NewLookActivity.TEXT, true); head.addView(setupSummary, new LinearLayout.LayoutParams(0, -2, 1f));
        setupChevron = a.tv("\u25be", 18, NewLookActivity.MUTED, true); head.addView(setupChevron, new LinearLayout.LayoutParams(-2, -2));
        setupCard.addView(head, new LinearLayout.LayoutParams(-1, -2));
        setupBody = new LinearLayout(a); setupBody.setOrientation(LinearLayout.VERTICAL); setupBody.setVisibility(View.GONE);
        setupCard.addView(setupBody, lp(-1, -2, 8));
        head.setOnClickListener(v -> { if (!running) { boolean open = setupBody.getVisibility() != View.VISIBLE; setupBody.setVisibility(open ? View.VISIBLE : View.GONE); setupChevron.setText(open ? "\u25b4" : "\u25be"); } });
        addRow(0, "Posture", POSTURE, () -> { String t = a.readingTag; return t.isEmpty() ? POSTURE[0] : t; }, i -> {
            String t = i == 0 ? "" : POSTURE[i]; a.readingTag = t;
            SharedPreferences.Editor e = pr.edit().putString("reading_tag", t); if (!t.isEmpty()) e.putString("reading_tag_last", t); e.apply(); });
        addRow(1, "Strap position", POSITION, () -> a.positionTag.isEmpty() ? POSITION[0] : a.positionTag, i -> { a.positionTag = i == 0 ? "" : POSITION[i]; pr.edit().putString("position_tag", a.positionTag).apply(); });
        addRow(2, "Where", WHERE, () -> pref("where_tag", "").isEmpty() ? WHERE[0] : pref("where_tag", ""), i -> pr.edit().putString("where_tag", i == 0 ? "" : WHERE[i]).apply());
        String[] runs = new String[GuidedRun.ALL.length]; for (int i = 0; i < runs.length; i++) runs[i] = GuidedRun.ALL[i].title;
        addRow(3, "Run", runs, () -> GuidedRun.byId(pref("run_id", "")).title, i -> { pr.edit().putString("run_id", GuidedRun.ALL[i].id).apply(); updateRunHint(); });
        addActionRow("Test the cue (buzz, beep, voice)", "Test", new Runnable() { @Override public void run() { testCue(); } });
        addRow(4, "Auto-stop", AUTO, () -> { if (!pr.getBoolean("auto_stop", true)) return AUTO[0]; double t = a.autoTargetValue(); return t < 3.5 ? AUTO[1] : t < 4.5 ? AUTO[2] : AUTO[3]; }, i -> {
            SharedPreferences.Editor e = pr.edit(); if (i == 0) e.putBoolean("auto_stop", false); else e.putBoolean("auto_stop", true).putFloat("auto_target", i == 1 ? 3f : i == 2 ? 4f : 5f); e.apply(); });
        TextView dl = a.tv("Display", 11, NewLookActivity.MUTED, true); setupBody.addView(dl, lp(-2, -2, 8));
        addRow(5, "Graph window", WINDOW, () -> pr.getInt("graph_window", 6000) == 3500 ? WINDOW[1] : WINDOW[0], i -> { int w = i == 1 ? 3500 : 6000; pr.edit().putInt("graph_window", w).apply(); Live500View.windowMs = w; });
        addRow(6, "Graph style", STYLE, () -> pr.getBoolean("graph_sweep", false) ? STYLE[1] : STYLE[0], i -> { pr.edit().putBoolean("graph_sweep", i == 1).apply(); Live500View.sweep = i == 1; });
        addRow(7, "Graph theme", THEME, () -> pr.getBoolean("graph_paper", false) ? THEME[1] : THEME[0], i -> { pr.edit().putBoolean("graph_paper", i == 1).apply(); Live500View.paper = i == 1; });
        addRow(8, "Pro display", PRO, () -> pref("pro_display", "1").equals("1") ? PRO[0] : PRO[1], i -> { pr.edit().putString("pro_display", i == 0 ? "1" : "0").apply(); Live500.pro = i == 0; a.toast("Pro display changes from the next reading."); });
        runHint = a.tv("", 12, NewLookActivity.MUTED, false); setupBody.addView(runHint, lp(-1, -2, 8));
        col.addView(setupCard, Math.max(0, index), lp(-1, -2, 8));
        // restore the position tag
        a.positionTag = pref("position_tag", a.positionTag);
        updateRunHint(); refreshSummary();
    }
    private TextView runHint;

    interface Get { String get(); }
    interface Pick { void pick(int i); }
    private void addRow(final int slot, final String label, final String[] options, final Get get, final Pick pick) {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(a.round(0x14FFFFFF, 14)); r.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        r.addView(a.tv(label, 13, NewLookActivity.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = a.tv(get.get() + "  \u25be", 14, NewLookActivity.TEXT, true); val.setGravity(Gravity.END); r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.4f));
        rowValues[slot] = val; r.setContentDescription(label + ", " + get.get() + ", double tap to change");
        r.setOnClickListener(v -> {
            if (running) return;
            new AlertDialog.Builder(a).setTitle(label).setItems(options, (d, which) -> { try { pick.pick(which); } catch (Throwable t) { fail("row " + label, t); } val.setText(get.get() + "  \u25be"); r.setContentDescription(label + ", " + get.get() + ", double tap to change"); refreshSummary(); }).show();
        });
        setupBody.addView(r, lp(-1, -2, 6));
    }
    private void addActionRow(final String label, String value, final Runnable action) {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(a.round(0x14FFFFFF, 14)); r.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10));
        r.addView(a.tv(label, 13, NewLookActivity.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView val = a.tv(value + "  \u25b6", 14, NewLookActivity.TEXT, true); val.setGravity(Gravity.END); r.addView(val, new LinearLayout.LayoutParams(-2, -2));
        r.setContentDescription(label + ", double tap to run"); r.setOnClickListener(v -> action.run());
        setupBody.addView(r, lp(-1, -2, 6));
    }
    private void idleCue() {
        if (cueView == null) return;
        GuidedRun.Def d = GuidedRun.byId(pref("run_id", ""));
        if (running || d.id.isEmpty()) { if (!running) cueView.setVisibility(View.GONE); return; }
        cueView.setVisibility(View.VISIBLE); cueView.setText(d.title + " is selected. During the run the phone buzzes, beeps and speaks at each step, and the run stops itself at " + d.stopSec + " s.");
    }
    private void updateRunHint() { idleCue(); if (runHint == null) return; GuidedRun.Def d = GuidedRun.byId(pref("run_id", "")); runHint.setText(d.id.isEmpty() ? "Free reading: auto-stop and the 3 / 4 / 5 uV target apply." : d.title + ". " + d.setup + " Buzz and beep at each step; it stops itself at " + d.stopSec + " s."); }

    void refreshSummary() {
        if (setupSummary == null) return;
        String t = a.readingTag.isEmpty() ? "No tag" : a.readingTag, p = a.positionTag.isEmpty() ? "wrist" : a.positionTag, w = pref("where_tag", "");
        GuidedRun.Def d = GuidedRun.byId(pref("run_id", ""));
        String auto = !pr.getBoolean("auto_stop", true) ? "no auto-stop" : String.format(Locale.UK, "auto-stop %.0f uV", a.autoTargetValue());
        setupSummary.setText(t + "  \u00b7  " + p + (w.isEmpty() ? "" : "  \u00b7  " + w) + (d.id.isEmpty() ? "" : "  \u00b7  Run " + d.id) + "  \u00b7  " + auto);
    }

    // ------------------------------------------------------------------ live card, averaged beat, result card
    /** A tap-to-open "How is this calculated?" line under a card (decode log 117: the plain-language rows of Explain). */
    private void addExplain(LinearLayout card, String[][] rows) {
        final TextView head = a.tv("How is this calculated?  \u25be", 12, NewLookActivity.MUTED, true); head.setPadding(0, a.dp(8), 0, a.dp(4));
        final TextView body = a.tv(Explain.asText(rows), 12, NewLookActivity.MUTED, false); body.setVisibility(View.GONE);
        head.setOnClickListener(v -> { boolean open = body.getVisibility() != View.VISIBLE; body.setVisibility(open ? View.VISIBLE : View.GONE); head.setText("How is this calculated?  " + (open ? "\u25b4" : "\u25be")); });
        head.setContentDescription("How is this calculated? Double tap to show or hide the explanation.");
        card.addView(head, new LinearLayout.LayoutParams(-1, -2)); card.addView(body, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildLive(LinearLayout col, int index) {
        liveCard = card();
        signalView = a.tv("", 14, NewLookActivity.MUTED, true); signalView.setGravity(Gravity.CENTER); liveCard.addView(signalView, new LinearLayout.LayoutParams(-1, -2));
        bandsView = a.tv("", 12, NewLookActivity.MUTED, false); bandsView.setGravity(Gravity.CENTER); liveCard.addView(bandsView, lp(-1, -2, 4));
        targetBtn = a.pillButton("", 0x14FFFFFF, NewLookActivity.TEXT); targetBtn.setTextSize(12); targetBtn.setMinHeight(a.dp(40)); targetBtn.setMinimumHeight(a.dp(40));
        targetBtn.setOnClickListener(v -> { double t = a.autoTargetValue(); double nt = t < 3.5 ? 4.0 : t < 4.5 ? 5.0 : 3.0; pr.edit().putFloat("auto_target", (float) nt).putBoolean("auto_stop", true).apply(); refreshSummary(); if (rowValues[4] != null) rowValues[4].setText((nt < 3.5 ? AUTO[1] : nt < 4.5 ? AUTO[2] : AUTO[3]) + "  \u25be"); updateTarget(Double.NaN); });
        liveCard.addView(targetBtn, lp(-1, a.dp(40), 8));
        addExplain(liveCard, Explain.noiseBands());
        liveCard.setVisibility(View.GONE);
        col.addView(liveCard, Math.max(0, index), lp(-1, -2, 8));
    }
    private void buildAvg(LinearLayout col, int index) {
        avgCard = card(); avgCard.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(10));
        avgView = new AvgBeatView(a); avgCard.addView(avgView, new LinearLayout.LayoutParams(-1, a.dp(190)));
        avgText = a.tv("", 12, NewLookActivity.MUTED, false); avgText.setGravity(Gravity.CENTER); avgCard.addView(avgText, lp(-1, -2, 6));
        addExplain(avgCard, Explain.avgBeatLive());
        avgCard.setVisibility(View.GONE);
        col.addView(avgCard, Math.max(0, index), lp(-1, -2, 8));
    }
    private static final String[] WHY = { "Tension", "Vibration", "Cold hands", "Charger near", "Not sure" };
    private void buildResult(LinearLayout col, int index) {
        resultCard = card();
        resultTitle = a.tv("", 16, NewLookActivity.TEXT, true); resultTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); resultCard.addView(resultTitle, new LinearLayout.LayoutParams(-1, -2));
        resultBody = a.tv("", 13, NewLookActivity.MUTED, false); resultCard.addView(resultBody, lp(-1, -2, 6));
        whyHint = a.tv("What made it noisy? One tap labels this reading.", 12, NewLookActivity.AMBER, false); resultCard.addView(whyHint, lp(-1, -2, 8));
        whyRow = new LinearLayout(a); whyRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String w : WHY) {
            final Button b = a.pillButton(w, 0x14FFFFFF, NewLookActivity.TEXT); b.setTextSize(11); b.setMinHeight(a.dp(36)); b.setMinimumHeight(a.dp(36)); b.setPadding(a.dp(4), 0, a.dp(4), 0);
            b.setOnClickListener(v -> { ReadingNotes.put(dir(), startMs / 1000L, "why", w); for (int i = 0; i < whyRow.getChildCount(); i++) ((Button) whyRow.getChildAt(i)).setBackground(a.round(((Button) whyRow.getChildAt(i)).getText().toString().equals(w) ? NewLookActivity.PRIMARY : 0x14FFFFFF, 28)); a.toast("Noted: " + w); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.dp(36), 1f); p.rightMargin = a.dp(3); whyRow.addView(b, p);
        }
        resultCard.addView(whyRow, lp(-1, -2, 4));
        addExplain(resultCard, Explain.resultCard());
        resultCard.setVisibility(View.GONE);
        col.addView(resultCard, Math.max(0, index), lp(-1, -2, 8));
    }

    // ------------------------------------------------------------------ running state
    /** Called by NewLookActivity when START / STOP flips. */
    void onRunning(boolean nowRunning) {
        if (nowRunning == running) return;
        boolean was = running; running = nowRunning;
        try {
            if (nowRunning && !was) {
                startMs = Live500.sessionMs > 0 && System.currentTimeMillis() - Live500.sessionMs < 8000 ? Live500.sessionMs : System.currentTimeMillis();
                autoStopped = false; stopReason = 0; cueLog = ""; lastOut = null; lastBands = null; LiveMeasure.clear();
                if (resultCard != null) resultCard.setVisibility(View.GONE);
                if (avgView != null) avgView.set(null);
                GuidedRun.Def d = GuidedRun.byId(pref("run_id", ""));
                guidedThisRun = !d.id.isEmpty();
                if (!d.id.isEmpty()) { cueCount = 0; initTts(); guide.start(d, startMs, sink); } else guide.cancel();
                if (scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });      // the cue card and the graph are at the top
                ReadingNotes.put(dir(), startMs / 1000L, "app", VERSION);
                ReadingNotes.put(dir(), startMs / 1000L, "where", pref("where_tag", ""));
                ReadingNotes.put(dir(), startMs / 1000L, "pos", a.positionTag);
                ReadingNotes.put(dir(), startMs / 1000L, "tag", a.readingTag);
                ReadingNotes.put(dir(), startMs / 1000L, "run", d.id);
                ReadingNotes.put(dir(), startMs / 1000L, "pro", Live500.pro ? "1" : "0");
            } else if (!nowRunning && was) {
                guide.cancel(); finishReading();
            }
        } catch (Throwable t) { fail("running change", t); }
        applyRunning(nowRunning);
    }

    private void applyRunning(boolean r) {
        running = r;
        try {
            if (setupBody != null && r) { setupBody.setVisibility(View.GONE); setupChevron.setText("\u25be"); }
            if (liveCard != null) liveCard.setVisibility(r ? View.VISIBLE : View.GONE);
            if (avgCard != null) avgCard.setVisibility(r || lastOut != null ? View.VISIBLE : View.GONE);
            if (!r) idleCue();
            if (setupChevron != null) setupChevron.setVisibility(r ? View.INVISIBLE : View.VISIBLE);
            if (start != null && r && !start.getText().toString().startsWith("STOP")) start.setText("STOP");
            if (start != null && !r) start.setContentDescription("Start ECG reading");
        } catch (Throwable t) { fail("apply running", t); }
    }

    // ------------------------------------------------------------------ per-second update (from NewLookActivity.contactTick)
    void update(boolean isRunning, boolean live) {
        try {
            if (isRunning != running) onRunning(isRunning);
            if (!isRunning) return;
            long now = System.currentTimeMillis(); long el = now - Live500.startMs;
            double[] b = LiveBands.rms(); lastBands = b;
            double est = Live500.meanBeatNoiseEstimate(), nz = Live500.recentNoise(3);
            // signal light
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
            int pg = Live500.lastProgress, cd = Live500.lastCode;
            if (cd != 0 && pg == 100) txt += "\nStrap's own check (research): " + StrapVerdict.describe(cd, Live500.lastAvgHr, Live500.lastMask);
            else if (pg <= 100) txt += "\nStrap's own check: analysing " + pg + " %" + (Live500.lastMask != 0 ? " (" + StrapVerdict.reasons(Live500.lastMask) + ")" : "");
            signalView.setText(txt); signalView.setTextColor(colr);
            if (!Double.isNaN(b[1])) {
                String bt = String.format(Locale.UK, "Noise (between beats):  QRS band 8-25 Hz %.0f uV (usual 15-27)  \u00b7  25-48 Hz %.0f  \u00b7  50 Hz %.0f  \u00b7  drift %.0f", b[1], b[2], b[3], b[0]);
                String hint = LiveBands.hint(b, lastOut != null && !Double.isNaN(lastOut.rUv) ? lastOut.rUv : Double.NaN);
                if (hint == null && b[1] > 40) hint = "Broadband noise (muscle or vibration): relax the hand, rest the forearm, keep still";
                bandsView.setText(bt + (hint != null && b[1] > 27 ? "\n" + hint : ""));
            } else bandsView.setText("Noise by band appears after about 10 s of beats");
            updateTarget(est);
            // averaged beat
            LiveMeasure.request();
            LiveMeasure.Out o = LiveMeasure.last();
            if (o != null && o != lastOut) { lastOut = o; avgView.set(o); avgCard.setVisibility(View.VISIBLE); avgText.setText(avgLine(o)); }
            // auto-stop (not during a guided run)
            boolean auto = pr.getBoolean("auto_stop", true) && !guide.active();
            if (auto && !autoStopped && el > NewLookActivity.AUTO_MIN_MS && ((!Double.isNaN(est) && est <= a.autoTargetValue()) || el > NewLookActivity.AUTO_MAX_MS)) {
                autoStopped = true; stopReason = 1;
                a.toast(el > NewLookActivity.AUTO_MAX_MS && (Double.isNaN(est) || est > a.autoTargetValue()) ? "Stopped at 5 minutes (report quality " + (Double.isNaN(est) ? "-" : String.format(Locale.UK, "%.1f", est)) + " uV)" : String.format(Locale.UK, "Stopped: report quality %.1f uV reached", est));
                if (start != null && start.getText().toString().startsWith("STOP")) start.performClick();
            }
            if (start != null && running) { start.setText(String.format(Locale.UK, "STOP (hold 1 s)  \u00b7  %d:%02d", el / 60000, (el / 1000) % 60)); start.setContentDescription(String.format(Locale.UK, "Stop the reading. Press and hold for one second. %d minutes %d seconds so far.", el / 60000, (el / 1000) % 60)); }
            try {                                                                      // a spoken summary of the live graph for a screen reader
                double rrS = Live500.medianRrS(); String say = txt.split("\n")[0].replace("\u25cf  ", "");
                String desc = "Live ECG trace, 500 hertz. " + (Double.isNaN(rrS) ? "" : String.format(Locale.UK, "Heart rate %.0f beats a minute. ", 60.0 / rrS)) + say + ".";
                if (waveCardRef != null) for (int i = 0; i < waveCardRef.getChildCount(); i++) { View vv = waveCardRef.getChildAt(i); if (vv instanceof Live500View) vv.setContentDescription(desc); }
            } catch (Throwable ignored) { }
        } catch (Throwable t) { fail("update", t); }
    }

    private void updateTarget(double est) {
        if (targetBtn == null) return;
        double t = a.autoTargetValue(); boolean auto = pr.getBoolean("auto_stop", true);
        String e = Double.isNaN(est) ? "settling" : String.format(Locale.UK, "%.1f uV", est);
        targetBtn.setText(auto ? "Report quality so far " + e + "  \u2192  target " + String.format(Locale.UK, "%.0f", t) + " uV  (tap: 3, 4, 5)" : "Auto-stop off  \u00b7  quality so far " + e + "  (tap: 3, 4, 5 turns it on)");
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
            if (cueView != null) { cueView.setVisibility(View.VISIBLE); cueView.setText("NOW  " + text); }
            alertUser(text, 1 + (cueCount++ % 2));      // 0.42.3: buzz (own vibrator), beep on the alarm stream and a spoken cue: the old haptic tap and media-stream beep were easy to miss
            if (mark != null) clickMark(mark);
            ReadingNotes.put(dir(), startMs / 1000L, "cue", sec + ":" + text);
        }
        @Override public void tick(String status) { if (cueView == null) return; cueView.setVisibility(View.VISIBLE); if (System.currentTimeMillis() > cueUntil) cueView.setText(status); }
        @Override public void stop() { stopReason = 2; if (start != null && start.getText().toString().startsWith("STOP")) { a.toast("Run finished"); start.performClick(); } }
    };

    /** 0.42.3: vibration with the phone's vibrator, a beep on the ALARM stream and the cue spoken (also on the alarm stream). Each channel is tried on its own. */
    private void initTts() {
        if (tts != null) return;
        try { tts = new android.speech.tts.TextToSpeech(a.getApplicationContext(), new android.speech.tts.TextToSpeech.OnInitListener() { @Override public void onInit(int s) { ttsOk = s == android.speech.tts.TextToSpeech.SUCCESS; } }); } catch (Throwable t) { tts = null; }
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
    /** The "Test the cue" row: buzz and beep at once, the voice about 1.5 s later (the speech engine starts on first use). */
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
        LiveMeasure.Out o = LiveMeasure.last(); if (o == null) { try { o = LiveMeasure.compute(Live500.windowsCopy(), Live500.medianRrS()); } catch (Throwable ignored) { } }
        lastOut = o; double[] b = lastBands != null ? lastBands : LiveBands.rms();
        double dur = (endMs - startMs) / 1000.0;
        ReadingNotes.put(d, startSec, "dur_s", String.format(Locale.US, "%.0f", dur));
        ReadingNotes.put(d, startSec, "stop", stopReason == 1 ? "auto" : stopReason == 2 ? "run" : "hand");
        String[] bn = { "noise_0p5_5", "noise_8_25", "noise_25_48", "noise_mains", "noise_52_100" };
        for (int i = 0; i < 5; i++) if (!Double.isNaN(b[i])) ReadingNotes.put(d, startSec, bn[i], String.format(Locale.US, "%.1f", b[i]));
        double z = Live500.medianZ(); if (!Double.isNaN(z)) ReadingNotes.put(d, startSec, "contact_z", String.format(Locale.US, "%.1f", z));
        ReadingNotes.put(d, startSec, "beats_live", String.valueOf(Live500.beatCount()));
        int vc = Live500.verdictCode; if (vc != 0) { ReadingNotes.put(d, startSec, "result", String.valueOf(vc)); ReadingNotes.put(d, startSec, "strap_hr", String.valueOf(Live500.verdictHr)); ReadingNotes.put(d, startSec, "verdict_s", String.format(Locale.US, "%.0f", (Live500.verdictMs - Live500.sessionMs) / 1000.0)); }
        if (Live500.hrvMs != 0) { ReadingNotes.put(d, startSec, "hrv_first_s", String.format(Locale.US, "%.0f", (Live500.hrvMs - Live500.sessionMs) / 1000.0)); ReadingNotes.put(d, startSec, "hrv_first_ms", String.valueOf(Live500.hrvVal)); }
        if (o != null && o.avg != null) {
            ReadingNotes.put(d, startSec, "live_R_uV", String.format(Locale.US, "%.0f", o.rUv)); if (!Double.isNaN(o.qrsMs)) ReadingNotes.put(d, startSec, "live_QRS_ms", String.format(Locale.US, "%.0f", o.qrsMs));
            if (!Double.isNaN(o.postDipUv)) ReadingNotes.put(d, startSec, "live_dip_uV", String.format(Locale.US, "%.0f", o.postDipUv)); if (!Double.isNaN(o.qtMs)) ReadingNotes.put(d, startSec, "live_QT_ms", String.format(Locale.US, "%.0f", o.qtMs));
        }
        // result card
        StringBuilder t = new StringBuilder();
        if (vc != 0) resultTitle.setText("Strap verdict: " + StrapVerdict.describe(vc, Live500.verdictHr, Live500.lastMask)); else resultTitle.setText(dur < 42 ? "No strap verdict yet: the reading was " + String.format(Locale.UK, "%.0f", dur) + " s (the strap needs about 41 s from Start, 37 s of it in unbroken contact)" : "No strap verdict (contact was broken before it finished, or it was stopped too early)");
        double rr = Live500.medianRrS(); if (!Double.isNaN(rr)) t.append(String.format(Locale.UK, "HR %.0f", 60.0 / rr)); if (vc != 0) t.append(String.format(Locale.UK, " (strap %d)", Live500.verdictHr));
        if (Live500.hrvMs != 0) t.append(String.format(Locale.UK, "  \u00b7  strap HRV %d ms (from %.0f s)", Live500.hrvVal, (Live500.hrvMs - Live500.sessionMs) / 1000.0));
        if (o != null && o.avg != null) { t.append(String.format(Locale.UK, "\nR %.0f uV", o.rUv)); if (!Double.isNaN(o.qrsMs)) t.append(String.format(Locale.UK, "  \u00b7  QRS %.0f ms", o.qrsMs)); if (!Double.isNaN(o.postDipUv)) t.append(String.format(Locale.UK, "  \u00b7  S/ST dip %.0f uV", o.postDipUv)); if (!Double.isNaN(o.qtMs)) t.append(String.format(Locale.UK, "  \u00b7  QT %.0f ms", o.qtMs)); }
        boolean noisy = !Double.isNaN(b[1]) && b[1] > 27;
        if (!Double.isNaN(b[1])) t.append(String.format(Locale.UK, "\nNoise in the QRS band %.0f uV (usual 15-27): %s", b[1], b[1] <= 27 ? "good" : b[1] <= 40 ? "fair" : "noisy"));
        t.append("\nStopped ").append(stopReason == 1 ? "by auto-stop" : stopReason == 2 ? "by the guided run" : "by you").append(String.format(Locale.UK, " after %.0f s.", dur));
        t.append("\nThe full report builds in 1 to 3 minutes and is written again by itself when the strap's own beat counts (events 118 / 119) arrive.");
        resultBody.setText(t.toString());
        whyHint.setVisibility(noisy ? View.VISIBLE : View.GONE); whyRow.setVisibility(noisy ? View.VISIBLE : View.GONE);
        for (int i = 0; i < whyRow.getChildCount(); i++) ((Button) whyRow.getChildAt(i)).setBackground(a.round(0x14FFFFFF, 28));
        resultCard.setVisibility(View.VISIBLE);
        if (avgView != null && o != null) { avgView.set(o); avgText.setText(avgLine(o)); avgCard.setVisibility(View.VISIBLE); }
        if (guidedThisRun) { guidedThisRun = false; pr.edit().putString("run_id", "").apply(); updateRunHint(); a.toast("Guided run over: the next reading is a free reading."); }      // 0.42.3: a guided run is one-shot; it kept stopping every later reading at 45 s (23:10 reading)
        // the next pull brings the strap's end events: ask for one 12 s and 70 s after the stop (blocked by the app while another pull runs)
        h.postDelayed(() -> pullSoon(), 12000L); h.postDelayed(() -> pullSoon(), 70000L);
        final long sinceUnix = startMs / 1000L; final int[] tries = { 0 };
        for (long at : new long[] { 45000L, 100000L, 190000L, 360000L }) h.postDelayed(() -> refreshReport(sinceUnix, tries), at);      // 0.41.1: write the report again once events 118 / 119 are stored
    }
    private volatile boolean refreshed;
    private void refreshReport(final long sinceUnix, final int[] tries) {
        if (refreshed) return;
        new Thread(new Runnable() { @Override public void run() { try { if (EcgReport.refreshLast(sinceUnix)) { refreshed = true; h.post(() -> a.toast("Report updated with the strap's own beat counts.")); } } catch (Throwable ignored) { } } }).start();
    }
    private void pullSoon() { try { if (!MainActivity.linkRunning()) MainActivity.startLinkHost(a); MainActivity.requestPull(); } catch (Throwable ignored) { } }
}
