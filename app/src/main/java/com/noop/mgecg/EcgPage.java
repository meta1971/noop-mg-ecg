package com.noop.mgecg;

// FILE VERSION 0.43.0 (6 Nov 2026): the reading page in the Home look, less busy (decode log 50.6 item 1b, 1c).
// At rest: START (pinned), the setup capsule (posture, strap position, where) + "Lab", the latest reading with its Send button, ONE "Next test" chip.
// Running: a slim cue pill (guided tests only), the live trace, ONE signal-light line, STOP (1 s hold). Noise bands, the strap's own check, the averaged beat, live stats and every result line sit under ONE "Details" fold, closed by default.
// Result card: strap verdict, heart rate, quality, "Send" (one button: prepares the whole pull by itself, SendFlow). Lab: runs A-F as a checklist with ticks from reading_notes (LabTests), one tap starts a test.
// The four graph rows moved to Settings (You tab). Every step is guarded: a failure is written to the page footer and the rest of the page still works (decode log 116.5).
// 0.42.4: late tail no longer overwrites the saved reading, refresh flag reset per reading, refresh_try notes, ReportActivity reload.

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
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
    static final String VERSION = "0.43.0-pro";
    private final NewLookActivity a;
    private final SharedPreferences pr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GuidedRun guide = new GuidedRun();

    // views
    private LinearLayout capsuleRow, setupBody, signalCard, liveCard, avgCard, resultCard, resultDetails, whyRow, detailsBody, latestCard, cuePill, bar;
    private TextView setupSummary, signalView, strapLine, bandsView, cueView, avgText, resultTitle, resultSummary, resultBody, sendNote, whyHint, detailsHeader, latestBody, footer;
    private Button targetBtn, sendBtn, latestSend, nextChip, labBtn;
    private AvgBeatView avgView;
    private ProgressLine cueLine;
    private View anchor;
    private Button start;
    private LinearLayout waveCardRef;
    private boolean holdFired, guidedThisRun, hasResult, detailsOpen; private Runnable holdRun;
    private ScrollView scrollRef;
    private int cueCount; private android.speech.tts.TextToSpeech tts; private volatile boolean ttsOk;
    private final TextView[] rowValues = new TextView[9];

    // state
    private boolean running, autoStopped;
    private long startMs; private String cueLog = "";
    private long cueUntil; private String lastCue = "";
    private LiveMeasure.Out lastOut; private double[] lastBands;
    private int stopReason;           // 0 by hand, 1 auto-stop, 2 guided run
    private String failures = "";
    private int runStopSec; private String nextId = ""; private long lastRestMs;
    private boolean readyCancelled; private int readyLeft;

    EcgPage(NewLookActivity act) { a = act; pr = act.getSharedPreferences("labrador_ecg_control", android.content.Context.MODE_PRIVATE); }

    // ------------------------------------------------------------------ helpers
    private void fail(String step, Throwable t) {
        failures += (failures.isEmpty() ? "" : "; ") + step + ": " + t.getClass().getSimpleName();
        try { pr.edit().putString("page_fail", failures).apply(); } catch (Throwable ignored) { }
        if (footer != null) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
    }
    private String pref(String k, String d) { return pr.getString(k, d); }
    private LinearLayout card() { LinearLayout l = new LinearLayout(a); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(a.round(NewLookActivity.CARD, 22)); l.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14)); return l; }
    private LinearLayout.LayoutParams lp(int w, int hgt, int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, hgt); p.topMargin = a.dp(top); return p; }
    private File dir() { return a.getExternalFilesDir(null); }

    /** A small bottom sheet in the Home look. The content view is taken back out when the sheet closes, so it can be shown again. */
    private Dialog sheet(String title, final View content) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(18)); box.setBackground(a.round(NewLookActivity.CARD, 26));
        LinearLayout head = new LinearLayout(a); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(a.tv(title, 20, NewLookActivity.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView done = a.tv("Done", 15, NewLookActivity.PRIMARY, true); done.setPadding(a.dp(12), a.dp(8), a.dp(4), a.dp(8)); done.setOnClickListener(v -> d.dismiss()); head.addView(done, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        final ScrollView sv = new ScrollView(a); sv.addView(content, new FrameLayout.LayoutParams(-1, -2));
        box.addView(sv, lp(-1, -2, 8));
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
        try { Live500.pro = pref("pro_display", "1").equals("1"); Live500View.windowMs = pr.getInt("graph_window", 6000); Live500View.sweep = pr.getBoolean("graph_sweep", false); Live500View.paper = pr.getBoolean("graph_paper", false); } catch (Throwable t) { fail("display options", t); }
    }

    // ------------------------------------------------------------------ build
    /** Called once from NewLookActivity.styleEcgColumn after the top part is styled. Moves START / STOP into a pinned bar and adds the new cards. */
    void build(FrameLayout container, LinearLayout col, View top, TextView status, TextView verdict, LinearLayout waveCard, LinearLayout stats, Button startBtn) {
        start = startBtn; waveCardRef = waveCard;
        // while a reading runs, STOP needs a 1 s press-and-hold (a thumb brushing it while scrolling stopped the 22:00 reading on 5 Nov). Timed, guided and auto stops click the button directly and are not affected.
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
        // order in the column: cue pill, setup capsule + Lab, graph, signal line, result card, Details, latest reading, Next test chip
        try { buildCue(col, col.indexOfChild(waveCard)); } catch (Throwable t) { fail("cue pill", t); }
        try { buildCapsules(col, col.indexOfChild(waveCard)); } catch (Throwable t) { fail("setup", t); }
        int at = col.indexOfChild(waveCard) + 1;
        try { buildSignal(col, at); at = col.indexOfChild(signalCard) + 1; } catch (Throwable t) { fail("signal line", t); }
        try { buildResult(col, at); at = col.indexOfChild(resultCard) + 1; } catch (Throwable t) { fail("result card", t); }
        try { buildDetails(col, at, stats); at = col.indexOfChild(detailsBody) + 1; } catch (Throwable t) { fail("details", t); }
        try { buildLatest(col, at); } catch (Throwable t) { fail("latest reading", t); }
        try {
            footer = a.tv("", 11, NewLookActivity.AMBER, false); footer.setGravity(Gravity.CENTER); footer.setVisibility(View.GONE);
            col.addView(footer, new LinearLayout.LayoutParams(-1, -2));
            String old = pr.getString("page_fail", ""); if (!failures.isEmpty()) { footer.setText("Page note: " + failures + " (the rest works)"); footer.setVisibility(View.VISIBLE); }
            else if (!old.isEmpty()) pr.edit().remove("page_fail").apply();
        } catch (Throwable t) { /* cosmetic */ }
        applyRunning(false);
    }

    /** Called at the end of NewLookActivity.styleEcgColumn: the old Markers, Packets and Research rows move under the Details fold. */
    void finishLayout(View... extras) {
        try {
            if (detailsBody == null) return;
            for (View v : extras) {
                if (v == null) continue;
                ViewGroup.LayoutParams old = v.getLayoutParams();
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                if (old instanceof LinearLayout.LayoutParams) detailsBody.addView(v, old); else detailsBody.addView(v, lp(-1, -2, 6));
            }
        } catch (Throwable t) { fail("details rows", t); }
    }

    // ------------------------------------------------------------------ cue pill (guided tests)
    private void buildCue(LinearLayout col, int index) {
        cuePill = new LinearLayout(a); cuePill.setOrientation(LinearLayout.VERTICAL); cuePill.setVisibility(View.GONE);
        cueView = a.tv("", 16, NewLookActivity.INK, true); cueView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE); cueView.setGravity(Gravity.CENTER); cueView.setMaxLines(2);
        cueView.setBackground(a.round(NewLookActivity.PRIMARY, 28)); cueView.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8)); cueView.setMinHeight(a.dp(56));
        cuePill.addView(cueView, new LinearLayout.LayoutParams(-1, -2));
        cueLine = new ProgressLine(a, NewLookActivity.PRIMARY, 0x22FFFFFF);
        cuePill.addView(cueLine, lp(-1, a.dp(3), 6));
        cuePill.setOnClickListener(v -> { if (!running && readyLeft > 0) { readyCancelled = true; pr.edit().putString("run_id", "").apply(); cuePill.setVisibility(View.GONE); a.toast("Test cancelled"); } });
        col.addView(cuePill, Math.max(0, index), lp(-1, -2, 6));
    }
    private void showPill(String text) { if (cuePill == null) return; cuePill.setVisibility(View.VISIBLE); cueView.setText(text); if (cueLine != null) cueLine.set(0f); }

    // ------------------------------------------------------------------ setup capsule, Lab
    private static final String[] POSTURE = { "No tag", "Table", "Couch", "Lying", "Standing", "After exercise" };
    private static final String[] POSITION = { "Wrist", "Chest V2", "Chest V4", "Chest V5", "Chest V6", "Bicep L", "Bicep R", "H10 in hands" };
    private static final String[] WHERE = { "Not set", "Home", "Work", "Truck", "Outside", "Other" };
    private static final String[] AUTO = { "Off", "3 uV", "4 uV", "5 uV" };

    private void buildCapsules(LinearLayout col, int index) {
        a.readingTag = pref("reading_tag", a.readingTag);      // the tag chosen last time stays shown
        a.positionTag = pref("position_tag", a.positionTag);
        capsuleRow = new LinearLayout(a); capsuleRow.setOrientation(LinearLayout.HORIZONTAL); capsuleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout cap = new LinearLayout(a); cap.setOrientation(LinearLayout.HORIZONTAL); cap.setGravity(Gravity.CENTER_VERTICAL); cap.setBackground(a.round(NewLookActivity.CARD, 24)); cap.setPadding(a.dp(16), 0, a.dp(14), 0);
        setupSummary = a.tv("", 14, NewLookActivity.TEXT, true); setupSummary.setSingleLine(true); setupSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cap.addView(setupSummary, new LinearLayout.LayoutParams(0, -2, 1f));
        cap.addView(a.tv("\u25be", 18, NewLookActivity.MUTED, true), new LinearLayout.LayoutParams(-2, -2));
        cap.setContentDescription("Reading setup, double tap to change"); cap.setOnClickListener(v -> { if (!running) openSetup(); });
        capsuleRow.addView(cap, new LinearLayout.LayoutParams(0, a.dp(48), 1f));
        labBtn = a.pillButton("Lab", NewLookActivity.CARD, NewLookActivity.TEXT); labBtn.setTextSize(14); labBtn.setOnClickListener(v -> { if (!running) openLab(); });
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(a.dp(84), a.dp(48)); lb.leftMargin = a.dp(8);
        capsuleRow.addView(labBtn, lb);
        col.addView(capsuleRow, Math.max(0, index), lp(-1, -2, 8));
        // the sheet content: four rows (the Run row, the cue test and the four graph rows moved to the Lab and to Settings)
        setupBody = new LinearLayout(a); setupBody.setOrientation(LinearLayout.VERTICAL);
        addRow(0, "Posture", POSTURE, () -> { String t = a.readingTag; return t.isEmpty() ? POSTURE[0] : t; }, i -> {
            String t = i == 0 ? "" : POSTURE[i]; a.readingTag = t;
            SharedPreferences.Editor e = pr.edit().putString("reading_tag", t); if (!t.isEmpty()) e.putString("reading_tag_last", t); e.apply(); });
        addRow(1, "Strap position", POSITION, () -> a.positionTag.isEmpty() ? POSITION[0] : a.positionTag, i -> { a.positionTag = i == 0 ? "" : POSITION[i]; pr.edit().putString("position_tag", a.positionTag).apply(); });
        addRow(2, "Where", WHERE, () -> pref("where_tag", "").isEmpty() ? WHERE[0] : pref("where_tag", ""), i -> pr.edit().putString("where_tag", i == 0 ? "" : WHERE[i]).apply());
        addRow(4, "Auto-stop", AUTO, () -> { if (!pr.getBoolean("auto_stop", true)) return AUTO[0]; double t = a.autoTargetValue(); return t < 3.5 ? AUTO[1] : t < 4.5 ? AUTO[2] : AUTO[3]; }, i -> {
            SharedPreferences.Editor e = pr.edit(); if (i == 0) e.putBoolean("auto_stop", false); else e.putBoolean("auto_stop", true).putFloat("auto_target", i == 1 ? 3f : i == 2 ? 4f : 5f); e.apply(); });
        a.positionTag = pref("position_tag", a.positionTag);
        refreshSummary();
    }

    private void openSetup() {
        try {
            if (setupBody.getParent() instanceof ViewGroup) ((ViewGroup) setupBody.getParent()).removeView(setupBody);
            sheet("Reading setup", setupBody).show();
        } catch (Throwable t) { fail("setup sheet", t); }
    }

    interface Get { String get(); }
    interface Pick { void pick(int i); }
    private void addRow(final int slot, final String label, final String[] options, final Get get, final Pick pick) {
        LinearLayout r = new LinearLayout(a); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setBackground(a.round(0x14FFFFFF, 16)); r.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
        r.addView(a.tv(label, 13, NewLookActivity.MUTED, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = a.tv(get.get() + "  \u25be", 14, NewLookActivity.TEXT, true); val.setGravity(Gravity.END); r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.4f));
        rowValues[slot] = val; r.setContentDescription(label + ", " + get.get() + ", double tap to change");
        r.setOnClickListener(v -> {
            if (running) return;
            new AlertDialog.Builder(a).setTitle(label).setItems(options, (d, which) -> { try { pick.pick(which); } catch (Throwable t) { fail("row " + label, t); } val.setText(get.get() + "  \u25be"); r.setContentDescription(label + ", " + get.get() + ", double tap to change"); refreshSummary(); }).show();
        });
        setupBody.addView(r, lp(-1, -2, 8));
    }

    void refreshSummary() {
        if (setupSummary == null) return;
        String t = a.readingTag.isEmpty() ? "No tag" : a.readingTag, p = a.positionTag.isEmpty() ? "Wrist" : a.positionTag, w = pref("where_tag", "");
        setupSummary.setText(t + "  \u00b7  " + p + (w.isEmpty() ? "" : "  \u00b7  " + w) + (!pr.getBoolean("auto_stop", true) ? "  \u00b7  no auto-stop" : ""));
    }

    private String ticks(int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < 2; i++) s.append(i < n ? "\u25cf " : "\u25cb "); return s.toString() + " " + n + " / 2"; }

    private void openLab() {
        try {
            LabTests.Result r = LabTests.done(dir()); int hour = LabTests.hourNow();
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
            TextView intro = a.tv("Each test is 45 s. It stops by itself and sends itself. The phone buzzes, beeps and speaks at each step. Sit with forearms on a table, left wrist with the MG facing up, two right-hand fingers on the clasp.", 12, NewLookActivity.MUTED, false);
            box.addView(intro, new LinearLayout.LayoutParams(-1, -2));
            final Dialog[] dd = new Dialog[1];
            for (int i = 0; i < 6; i++) {
                final String id = LabTests.IDS[i];
                LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(a.round(0x14FFFFFF, 16)); row.setPadding(a.dp(14), a.dp(10), a.dp(10), a.dp(10));
                LinearLayout mid = new LinearLayout(a); mid.setOrientation(LinearLayout.VERTICAL);
                String tk = i == 4 ? (r.eMorning ? "\u25cf" : "\u25cb") + " morning  " + (r.eEvening ? "\u25cf" : "\u25cb") + " evening" : ticks(r.n[i]);
                mid.addView(a.tv(id + "   " + LabTests.name(id), 15, NewLookActivity.TEXT, true), new LinearLayout.LayoutParams(-2, -2));
                mid.addView(a.tv(tk, 12, (i == 4 ? r.n[4] : r.n[i]) >= 2 ? NewLookActivity.GREEN : NewLookActivity.AMBER, true), lp(-2, -2, 2));
                mid.addView(a.tv(LabTests.plain(id), 12, NewLookActivity.MUTED, false), lp(-2, -2, 2));
                row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
                Button go = a.pillButton("Start", NewLookActivity.PRIMARY, NewLookActivity.INK); go.setTextSize(14); go.setMinWidth(0); go.setMinimumWidth(0); go.setPadding(a.dp(14), 0, a.dp(14), 0);
                boolean can = i != 4 || LabTests.canStartE(r, hour);
                if (!can) { go.setEnabled(false); go.setAlpha(0.35f); }
                go.setOnClickListener(v -> { if (dd[0] != null) dd[0].dismiss(); startRun(id); });
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, a.dp(42)); gp.leftMargin = a.dp(8);
                row.addView(go, gp);
                box.addView(row, lp(-1, -2, 8));
            }
            Button cue = a.pillButton("Test the cue (buzz, beep, voice)", 0x14FFFFFF, NewLookActivity.TEXT); cue.setTextSize(13);
            cue.setOnClickListener(v -> testCue());
            box.addView(cue, lp(-1, a.dp(46), 12));
            dd[0] = sheet("Lab", box); dd[0].show();
        } catch (Throwable t) { fail("lab sheet", t); }
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
            showPill("Get ready: 5 s  \u00b7  " + d.title + "  (tap to cancel)");
            h.removeCallbacks(readyTick); h.post(readyTick);
        } catch (Throwable t) { fail("start run", t); }
    }
    private final Runnable readyTick = new Runnable() { @Override public void run() {
        try {
            if (running) return;
            if (readyCancelled) { readyLeft = 0; return; }
            GuidedRun.Def d = GuidedRun.byId(pref("run_id", ""));
            if (readyLeft <= 0) { readyLeft = 0; if (start != null && start.getText().toString().startsWith("START")) start.performClick(); else if (cuePill != null) cuePill.setVisibility(View.GONE); return; }
            if (cueView != null) cueView.setText("Get ready: " + readyLeft + " s  \u00b7  " + d.title + "  (tap to cancel)");
            readyLeft--; h.postDelayed(this, 1000L);
        } catch (Throwable t) { fail("get ready", t); }
    } };

    // ------------------------------------------------------------------ signal line, result card, Details, latest reading
    /** A tap-to-open "How is this calculated?" line under a card (decode log 117: the plain-language rows of Explain). */
    private void addExplain(LinearLayout card, String[][] rows) {
        final TextView head = a.tv("How is this calculated?  \u25be", 12, NewLookActivity.MUTED, true); head.setPadding(0, a.dp(8), 0, a.dp(4));
        final TextView body = a.tv(Explain.asText(rows), 12, NewLookActivity.MUTED, false); body.setVisibility(View.GONE);
        head.setOnClickListener(v -> { boolean open = body.getVisibility() != View.VISIBLE; body.setVisibility(open ? View.VISIBLE : View.GONE); head.setText("How is this calculated?  " + (open ? "\u25b4" : "\u25be")); });
        head.setContentDescription("How is this calculated? Double tap to show or hide the explanation.");
        card.addView(head, new LinearLayout.LayoutParams(-1, -2)); card.addView(body, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildSignal(LinearLayout col, int index) {
        signalCard = card(); signalCard.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        signalView = a.tv("", 14, NewLookActivity.MUTED, true); signalView.setGravity(Gravity.CENTER); signalCard.addView(signalView, new LinearLayout.LayoutParams(-1, -2));
        signalCard.setVisibility(View.GONE);
        col.addView(signalCard, Math.max(0, index), lp(-1, -2, 8));
    }

    private static final String[] WHY = { "Tension", "Vibration", "Cold hands", "Charger near", "Not sure" };
    private void buildResult(LinearLayout col, int index) {
        resultCard = card();
        resultTitle = a.tv("", 16, NewLookActivity.TEXT, true); resultTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); resultCard.addView(resultTitle, new LinearLayout.LayoutParams(-1, -2));
        resultSummary = a.tv("", 14, NewLookActivity.TEXT, false); resultCard.addView(resultSummary, lp(-1, -2, 6));
        sendBtn = a.pillButton("Send", NewLookActivity.PRIMARY, NewLookActivity.INK); sendBtn.setTextSize(18);
        resultCard.addView(sendBtn, lp(-1, a.dp(54), 12));
        sendNote = a.tv("", 12, NewLookActivity.MUTED, false); sendNote.setGravity(Gravity.CENTER); resultCard.addView(sendNote, lp(-1, -2, 6));
        resultCard.setVisibility(View.GONE);
        col.addView(resultCard, Math.max(0, index), lp(-1, -2, 8));
    }

    private void buildDetails(LinearLayout col, int index, LinearLayout stats) {
        detailsHeader = a.pillButton("Details  \u25be", NewLookActivity.CARD, NewLookActivity.MUTED); detailsHeader.setTextSize(14);
        detailsHeader.setOnClickListener(v -> { detailsOpen = !detailsOpen; detailsBody.setVisibility(detailsOpen ? View.VISIBLE : View.GONE); detailsHeader.setText("Details  " + (detailsOpen ? "\u25b4" : "\u25be")); });
        detailsHeader.setVisibility(View.GONE);
        col.addView(detailsHeader, Math.max(0, index), lp(-1, a.dp(46), 8));
        detailsBody = new LinearLayout(a); detailsBody.setOrientation(LinearLayout.VERTICAL); detailsBody.setVisibility(View.GONE);
        col.addView(detailsBody, col.indexOfChild(detailsHeader) + 1, lp(-1, -2, 0));
        // after a reading: every result line (HRV, R, QRS, dip, QT, stop reason), the why-noisy tap
        resultDetails = card(); resultBody = a.tv("", 13, NewLookActivity.MUTED, false); resultDetails.addView(resultBody, new LinearLayout.LayoutParams(-1, -2));
        whyHint = a.tv("What made it noisy? One tap labels this reading.", 12, NewLookActivity.AMBER, false); resultDetails.addView(whyHint, lp(-1, -2, 8));
        whyRow = new LinearLayout(a); whyRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String w : WHY) {
            final Button b = a.pillButton(w, 0x14FFFFFF, NewLookActivity.TEXT); b.setTextSize(11); b.setMinHeight(a.dp(36)); b.setMinimumHeight(a.dp(36)); b.setPadding(a.dp(4), 0, a.dp(4), 0);
            b.setOnClickListener(v -> { ReadingNotes.put(dir(), startMs / 1000L, "why", w); for (int i = 0; i < whyRow.getChildCount(); i++) ((Button) whyRow.getChildAt(i)).setBackground(a.round(((Button) whyRow.getChildAt(i)).getText().toString().equals(w) ? NewLookActivity.PRIMARY : 0x14FFFFFF, 28)); a.toast("Noted: " + w); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, a.dp(36), 1f); p.rightMargin = a.dp(3); whyRow.addView(b, p);
        }
        resultDetails.addView(whyRow, lp(-1, -2, 4));
        addExplain(resultDetails, Explain.resultCard());
        resultDetails.setVisibility(View.GONE);
        detailsBody.addView(resultDetails, lp(-1, -2, 8));
        // while running: the strap's own check, noise bands, the 3 / 4 / 5 target
        liveCard = card();
        strapLine = a.tv("", 13, NewLookActivity.MUTED, true); strapLine.setGravity(Gravity.CENTER); liveCard.addView(strapLine, new LinearLayout.LayoutParams(-1, -2));
        bandsView = a.tv("", 12, NewLookActivity.MUTED, false); bandsView.setGravity(Gravity.CENTER); liveCard.addView(bandsView, lp(-1, -2, 4));
        targetBtn = a.pillButton("", 0x14FFFFFF, NewLookActivity.TEXT); targetBtn.setTextSize(12); targetBtn.setMinHeight(a.dp(40)); targetBtn.setMinimumHeight(a.dp(40));
        targetBtn.setOnClickListener(v -> { double t = a.autoTargetValue(); double nt = t < 3.5 ? 4.0 : t < 4.5 ? 5.0 : 3.0; pr.edit().putFloat("auto_target", (float) nt).putBoolean("auto_stop", true).apply(); refreshSummary(); if (rowValues[4] != null) rowValues[4].setText((nt < 3.5 ? AUTO[1] : nt < 4.5 ? AUTO[2] : AUTO[3]) + "  \u25be"); updateTarget(Double.NaN); });
        liveCard.addView(targetBtn, lp(-1, a.dp(40), 8));
        addExplain(liveCard, Explain.noiseBands());
        liveCard.setVisibility(View.GONE);
        detailsBody.addView(liveCard, lp(-1, -2, 8));
        // the averaged beat
        avgCard = card(); avgCard.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(10));
        avgView = new AvgBeatView(a); avgCard.addView(avgView, new LinearLayout.LayoutParams(-1, a.dp(190)));
        avgText = a.tv("", 12, NewLookActivity.MUTED, false); avgText.setGravity(Gravity.CENTER); avgCard.addView(avgText, lp(-1, -2, 6));
        addExplain(avgCard, Explain.avgBeatLive());
        avgCard.setVisibility(View.GONE);
        detailsBody.addView(avgCard, lp(-1, -2, 8));
        // the old BPM / elapsed numbers
        try { if (stats != null) { if (stats.getParent() instanceof ViewGroup) ((ViewGroup) stats.getParent()).removeView(stats); detailsBody.addView(stats, lp(-1, -2, 8)); } } catch (Throwable t) { fail("live numbers", t); }
    }

    private void buildLatest(LinearLayout col, int index) {
        latestCard = card();
        latestCard.addView(a.tv("LATEST READING  \u00b7  TAP TO OPEN", 11, NewLookActivity.MUTED, true), new LinearLayout.LayoutParams(-2, -2));
        latestBody = a.tv("", 15, NewLookActivity.TEXT, true); latestCard.addView(latestBody, lp(-1, -2, 6));
        latestSend = a.pillButton("Send", NewLookActivity.PRIMARY, NewLookActivity.INK); latestSend.setTextSize(16); latestSend.setVisibility(View.GONE);
        latestCard.addView(latestSend, lp(-1, a.dp(48), 10));
        col.addView(latestCard, Math.max(0, index), lp(-1, -2, 8));
        nextChip = a.pillButton("", 0x22FFFFFF, NewLookActivity.TEXT); nextChip.setTextSize(14); nextChip.setVisibility(View.GONE);
        nextChip.setOnClickListener(v -> { if (!nextId.isEmpty()) startRun(nextId); });
        col.addView(nextChip, col.indexOfChild(latestCard) + 1, lp(-1, a.dp(48), 8));
    }

    void refreshLatest() {
        try {
            if (latestCard == null) return;
            List<NewLookActivity.Rep> reps = a.loadReports();
            if (reps.isEmpty()) { latestBody.setText("No reading yet. Press START ECG."); latestSend.setVisibility(View.GONE); latestCard.setOnClickListener(null); return; }
            final NewLookActivity.Rep r = reps.get(0);
            latestBody.setText(r.dateText + "\n" + r.summary());
            latestSend.setVisibility(View.VISIBLE);
            SendFlow.attach(a, latestSend, ProReportMaker.unixOf(r.file), r.file);
            latestCard.setOnClickListener(v -> a.openReport(r.file));
        } catch (Throwable t) { fail("latest reading", t); }
    }

    void refreshNext() {
        try {
            nextId = LabTests.next(LabTests.done(dir()), LabTests.hourNow());
            if (nextChip == null) return;
            if (nextId.isEmpty() || running) { nextChip.setVisibility(View.GONE); return; }
            nextChip.setText("Next test:  " + nextId + "  \u00b7  " + LabTests.name(nextId) + "  \u203a"); nextChip.setVisibility(View.VISIBLE);
        } catch (Throwable t) { fail("next test", t); }
    }

    /** Called every 500 ms by NewLookActivity while the screen is at rest: the Next test chip follows the clock (E only inside its windows). */
    void tickRest() { if (running) return; long now = System.currentTimeMillis(); if (now - lastRestMs < 30000L) return; lastRestMs = now; refreshNext(); }

    // ------------------------------------------------------------------ running state
    /** Called by NewLookActivity when START / STOP flips. */
    void onRunning(boolean nowRunning) {
        if (nowRunning == running) return;
        boolean was = running; running = nowRunning;
        try {
            if (nowRunning && !was) {
                startMs = Live500.sessionMs > 0 && System.currentTimeMillis() - Live500.sessionMs < 8000 ? Live500.sessionMs : System.currentTimeMillis();
                autoStopped = false; stopReason = 0; cueLog = ""; lastOut = null; lastBands = null; LiveMeasure.clear(); hasResult = false; readyCancelled = true; readyLeft = 0;
                applyDisplayPrefs();
                if (avgView != null) avgView.set(null);
                String rid = pref("run_id", ""); if (!rid.isEmpty() && pr.getLong("run_armed_ms", 0L) + 90000L < System.currentTimeMillis()) { rid = ""; pr.edit().putString("run_id", "").apply(); }
                GuidedRun.Def d = GuidedRun.byId(rid);
                guidedThisRun = !d.id.isEmpty(); runStopSec = d.stopSec;
                if (!d.id.isEmpty()) { cueCount = 0; initTts(); guide.start(d, startMs, sink); showPill("Keep still and relaxed"); } else { guide.cancel(); if (cuePill != null) cuePill.setVisibility(View.GONE); }
                if (scrollRef != null) scrollRef.post(new Runnable() { @Override public void run() { try { scrollRef.scrollTo(0, 0); } catch (Throwable ignored) { } } });      // the cue pill and the graph are at the top
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
            if (capsuleRow != null) capsuleRow.setVisibility(r ? View.GONE : View.VISIBLE);
            if (waveCardRef != null) waveCardRef.setVisibility(r ? View.VISIBLE : View.GONE);
            if (signalCard != null) signalCard.setVisibility(r ? View.VISIBLE : View.GONE);
            if (liveCard != null) liveCard.setVisibility(r ? View.VISIBLE : View.GONE);
            if (avgCard != null) avgCard.setVisibility(r || lastOut != null ? View.VISIBLE : View.GONE);
            if (resultCard != null) resultCard.setVisibility(!r && hasResult ? View.VISIBLE : View.GONE);
            if (resultDetails != null) resultDetails.setVisibility(!r && hasResult ? View.VISIBLE : View.GONE);
            boolean det = r || hasResult;
            if (detailsHeader != null) detailsHeader.setVisibility(det ? View.VISIBLE : View.GONE);
            if (detailsBody != null) detailsBody.setVisibility(det && detailsOpen ? View.VISIBLE : View.GONE);
            if (latestCard != null) latestCard.setVisibility(!r && !hasResult ? View.VISIBLE : View.GONE);
            if (!r) { if (cuePill != null && readyLeft <= 0) cuePill.setVisibility(View.GONE); refreshNext(); if (latestCard != null && !hasResult) refreshLatest(); }
            else if (nextChip != null) nextChip.setVisibility(View.GONE);
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
            // signal light: ONE line on the card; the strap's own check goes under Details
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
            // averaged beat
            LiveMeasure.request();
            LiveMeasure.Out o = LiveMeasure.last();
            if (o != null && o != lastOut) { lastOut = o; avgView.set(o); avgCard.setVisibility(View.VISIBLE); avgText.setText(avgLine(o)); }
            // the cue pill's thin progress line
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
            if (cueView != null) { if (cuePill != null) cuePill.setVisibility(View.VISIBLE); cueView.setText("NOW  " + text); }
            alertUser(text, 1 + (cueCount++ % 2));      // buzz (own vibrator), beep on the alarm stream and a spoken cue
            if (mark != null) clickMark(mark);
            ReadingNotes.put(dir(), startMs / 1000L, "cue", sec + ":" + text);
        }
        @Override public void tick(String status) { if (cueView == null) return; if (cuePill != null) cuePill.setVisibility(View.VISIBLE); if (System.currentTimeMillis() > cueUntil) cueView.setText(status); }
        @Override public void stop() { stopReason = 2; if (start != null && start.getText().toString().startsWith("STOP")) { start.performClick(); } }
    };

    /** Vibration with the phone's vibrator, a beep on the ALARM stream and the cue spoken (also on the alarm stream). Each channel is tried on its own. */
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
        double dur = (endMs - startMs) / 1000.0;
        // FIRST: the one-button pull. Own guard, before anything that could throw (the 20:30 reading of 6 Nov never got its refresh notes).
        try { SendFlow.begin(a.getApplicationContext(), d, startSec, endMs, (long) dur, new Runnable() { @Override public void run() { pullSoon(); } }, new Runnable() { @Override public void run() { a.toast("Report updated with the strap's own beat counts."); } }); }
        catch (Throwable t) { fail("send prepare", t); }
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
        } catch (Throwable t) { fail("reading notes", t); }
        // result card: strap verdict, heart rate, quality, Send; every other line under Details
        try {
            if (b == null) b = new double[] { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN };
            if (vc != 0) resultTitle.setText("Strap verdict: " + StrapVerdict.describe(vc, Live500.verdictHr, Live500.lastMask)); else resultTitle.setText(dur < 42 ? "No strap verdict yet: the reading was " + String.format(Locale.UK, "%.0f", dur) + " s (the strap needs about 41 s from Start, 37 s of it in unbroken contact)" : "No strap verdict (contact was broken before it finished, or it was stopped too early)");
            StringBuilder sm = new StringBuilder();
            double rr = Live500.medianRrS(); if (!Double.isNaN(rr)) sm.append(String.format(Locale.UK, "HR %.0f", 60.0 / rr)); if (vc != 0) sm.append(String.format(Locale.UK, " (strap %d)", Live500.verdictHr));
            if (Live500.hrvMs != 0) sm.append(String.format(Locale.UK, "  \u00b7  strap HRV %d ms (from %.0f s)", Live500.hrvVal, (Live500.hrvMs - Live500.sessionMs) / 1000.0));
            if (!Double.isNaN(b[1])) sm.append(sm.length() > 0 ? "\n" : "").append(String.format(Locale.UK, "Noise in the QRS band %.0f uV (usual 15-27): %s", b[1], b[1] <= 27 ? "good" : b[1] <= 40 ? "fair" : "noisy"));
            resultSummary.setText(sm.toString());
            StringBuilder t = new StringBuilder();
            if (o != null && o.avg != null) { t.append(String.format(Locale.UK, "R %.0f uV", o.rUv)); if (!Double.isNaN(o.qrsMs)) t.append(String.format(Locale.UK, "  \u00b7  QRS %.0f ms", o.qrsMs)); if (!Double.isNaN(o.postDipUv)) t.append(String.format(Locale.UK, "  \u00b7  S/ST dip %.0f uV", o.postDipUv)); if (!Double.isNaN(o.qtMs)) t.append(String.format(Locale.UK, "  \u00b7  QT %.0f ms", o.qtMs)); t.append("\n"); }
            t.append("Stopped ").append(stopReason == 1 ? "by auto-stop" : stopReason == 2 ? "by the guided run" : "by you").append(String.format(Locale.UK, " after %.0f s.", dur));
            t.append("\nSend collects everything by itself: the strap's end events (118 / 119), the report written again with them, and the zip.");
            resultBody.setText(t.toString());
            boolean noisy = !Double.isNaN(b[1]) && b[1] > 27;
            whyHint.setVisibility(noisy ? View.VISIBLE : View.GONE); whyRow.setVisibility(noisy ? View.VISIBLE : View.GONE);
            for (int i = 0; i < whyRow.getChildCount(); i++) ((Button) whyRow.getChildAt(i)).setBackground(a.round(0x14FFFFFF, 28));
            hasResult = true;
            if (avgView != null && o != null) { avgView.set(o); avgText.setText(avgLine(o)); avgCard.setVisibility(View.VISIBLE); }
            SendFlow.attach(a, sendBtn, startSec, null);
            startNoteTick(startSec);
        } catch (Throwable t) { fail("result card", t); }
        if (guidedThisRun) {
            guidedThisRun = false; pr.edit().putString("run_id", "").apply();      // a guided run is one-shot; it kept stopping every later reading at 45 s (23:10 reading of 5 Nov)
            if (stopReason == 2) { try { SendFlow.autoSend(a, startSec); a.toast("Done: sending"); } catch (Throwable t) { fail("auto send", t); } }
        }
        try { refreshNext(); } catch (Throwable ignored) { }
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
