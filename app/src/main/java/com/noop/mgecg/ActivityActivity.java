package com.noop.mgecg;

// FILE VERSION 0.2.0 (23 Oct 2026): the Activity screen, day by day. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity: when you were physically active, how hard and for how long, day by day. Built for a working day on your feet: long stretches at a
 * moderate heart rate show up as periods, not as nothing. Uses the strap's per-second heart rate and wrist movement from the day log (ActivityDetector).
 * Long views (last 7 days, workload, 13 weeks, your usual day) use the day summaries cached by ActivityStore. Every number is an estimate and says so.
 * Same look as Today: black, cards, canvas drawing, no libraries. RESEARCH ONLY, not a medical device.
 */
public class ActivityActivity extends Activity {

    // The same palette, type and components as the opening screen (HealthActivity / HealthViews), so every screen looks like one app.
    static final int BG = HealthViews.BG, CARD = HealthViews.CARD, LINE = 0x1AFFFFFF, TEXT = HealthViews.INK, MUTED = HealthViews.MUT, DIM = 0xFF6B7686,
            LIGHT = HealthViews.BLUE, MODERATE = HealthViews.GOLD, HARD = HealthViews.ROSE, STILL = HealthViews.VIOLET, GREEN = HealthViews.GREEN,
            HRC = HealthViews.ROSE, EFFORT = HealthViews.BLUE, AMBER = HealthViews.GOLD;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView subtitle, dateLabel, nextBtn;
    private HealthViews.NavBar nav;
    private FrameLayout shell;
    private long dayStart;
    private int generation;                         // newer loads win over older ones
    private ActivityStore.Settings settings;
    private ActivityDetector.Params params;
    private ActivityStore.DayData day;
    private ActivityStore.History hist;
    private ActivityStore.Summary todaySummary;
    private int selected = -1;
    private TimelineView timeline;
    private TextView callout;
    private final List<View> boutDetails = new ArrayList<View>();
    private boolean touched;                        // you opened something: do not redraw (and collapse it) while older days are still being read

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new HealthActivity.GlowBg(this), new FrameLayout.LayoutParams(-1, -1));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(40), dp(18), dp(120));

        // header: small grey line, big light title, Settings on the right (same pattern as the other tabs)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        subtitle = tv("Today on your feet", 13, MUTED, false);
        head.addView(subtitle);
        TextView title = tvw("Activity", 28, TEXT, 400);
        title.setPadding(0, dp(6), 0, 0);
        head.addView(title);
        top.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView gear = chipText("Settings");
        gear.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showSettings(); } });
        top.addView(gear, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // date navigator
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);
        navRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        navRow.setBackground(round(CARD, 22, LINE));
        TextView prev = tvw("\u2039", 26, TEXT, 400);
        prev.setGravity(Gravity.CENTER);
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(ActivityStore.shiftDay(dayStart, -1)); } });
        navRow.addView(prev, new LinearLayout.LayoutParams(dp(52), dp(44)));
        dateLabel = tvw("", 16, TEXT, 600);
        dateLabel.setGravity(Gravity.CENTER);
        dateLabel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(todayStart()); } });
        navRow.addView(dateLabel, new LinearLayout.LayoutParams(0, dp(44), 1f));
        nextBtn = tvw("\u203a", 26, TEXT, 400);
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            long t = ActivityStore.shiftDay(dayStart, 1);
            if (t <= todayStart()) go(t);
        } });
        navRow.addView(nextBtn, new LinearLayout.LayoutParams(dp(52), dp(44)));
        col.addView(navRow, lp(-1, -2, 16));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        col.addView(content, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        // the same bottom bar as the opening screen; Activity belongs to Today
        View scrim = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x0007090C, 0xFF07090C, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM));
        nav = new HealthViews.NavBar(this, 0, new HealthViews.NavBar.Listener() {
            @Override public void onTab(int t) { openTab(t); }
        });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM);
        np.setMargins(dp(14), 0, dp(14), dp(18));
        root.addView(nav, np);
        shell = root;
        setContentView(root);
        dayStart = todayStart();
    }

    /** Bottom bar: every tab lives in the opening screen, so go there with that tab selected. */
    private void openTab(int t) {
        startActivity(new Intent(this, HealthActivity.class).putExtra("tab", t).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    @Override protected void onDestroy() {
        generation++;
        super.onDestroy();
    }

    private static long todayStart() { return TodayModel.dayStartOf(System.currentTimeMillis() / 1000L); }

    private void go(long newDayStart) {
        dayStart = newDayStart;
        selected = -1;
        touched = false;
        reload();
    }

    // ------------------------------------------------------------------ load

    private void reload() {
        final Context app = getApplicationContext();
        settings = ActivityStore.settings(app);
        params = ActivityStore.params(app, settings);
        final long ds = dayStart;
        final int gen = ++generation;
        final ActivityDetector.Params pp = params;
        final ActivityStore.Settings ss = settings;
        dateLabel.setText(dayText(ds));
        nextBtn.setTextColor(ds >= todayStart() ? DIM : TEXT);
        subtitle.setText("loading\u2026");
        new Thread(new Runnable() {
            @Override public void run() {
                try { ActivityStore.maybeCompact(app, ss); } catch (Throwable ignored) { }
                ActivityStore.DayData d = null;
                try { d = ActivityStore.loadDay(app, ds, pp); } catch (Throwable ignored) { }
                final ActivityStore.DayData dd = d;
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || gen != generation) return;
                    day = dd;
                    render();
                } });
                // the long views are read behind the main screen, a few days at a time, and cached as each finished day is done
                try {
                    long ts = todayStart();
                    ActivityStore.DayData td = (ds == ts) ? dd : ActivityStore.glanceDay(app);
                    ActivityStore.Summary today = td == null ? null : ActivityStore.summaryOf(td);
                    for (int round = 0; round < 30; round++) {
                        ActivityStore.History h = ActivityStore.history(app, pp, ts, today, 91, 6);
                        final ActivityStore.History hf = h;
                        final ActivityStore.Summary tf = today;
                        ui.post(new Runnable() { @Override public void run() {
                            if (isFinishing() || gen != generation) return;
                            hist = hf;
                            todaySummary = tf;
                            if (touched && hf.missing > 0) return;      // the last round redraws once everything is read
                            render();
                        } });
                        if (h.missing == 0 || isFinishing() || gen != generation) break;
                    }
                } catch (Throwable ignored) { }
            }
        }, "activity-load").start();
    }

    // ------------------------------------------------------------------ render

    private void render() {
        try {
            renderInner();
        } catch (Throwable t) {
            content.removeAllViews();
            LinearLayout c = card("SOMETHING WENT WRONG");
            c.addView(tv("This screen could not be drawn (" + t.getClass().getSimpleName() + "). The recording and the other screens are not affected.", 13, MUTED, false));
            addCard(c);
        }
    }

    private void renderInner() {
        content.removeAllViews();
        boutDetails.clear();
        timeline = null;
        callout = null;
        addHealthCard();
        if (day == null || !day.hasData || day.r == null) {
            subtitle.setText("no per-second records for this day");
            LinearLayout c = card("NO DATA FOR THIS DAY");
            c.addView(tv("Turn the day logger on and pull the strap's history. Activity is worked out from the strap's per-second heart rate and wrist movement.", 14, MUTED, false));
            addCard(c);
            addLongViews(null);
            addExplanations(null);
            addFooter();
            return;
        }
        final ActivityDetector.Result r = day.r;
        subtitle.setText(String.format(Locale.UK, "resting %.0f bpm%s  \u00b7  max %.0f bpm (from age %d)", r.restHr,
                r.restEstimated ? " (estimated)" : "", r.hrMax, settings.age));

        // ---- the dial: active time today, in the same glowing style as BPM NOW
        double avgLoad = averageLoad();
        float frac = (float) Math.min(1.0, r.activeMin / (double) Math.max(30, settings.activeGoalMin));
        int ow = Math.min(getResources().getDisplayMetrics().widthPixels - dp(80), dp(300));
        View orb = new HealthViews.OrbView(this, hm(r.activeMin), "ACTIVE", frac, HealthViews.BLUE, 0xFF8FD8FF, 0xFF1FA0EE, 0xFF0B6FD0);
        LinearLayout.LayoutParams op = lp(ow, ow, 8);
        op.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(orb, op);
        TextView goalLine = tv("goal " + hm(settings.activeGoalMin) + "  \u00b7  " + r.bouts.size() + (r.bouts.size() == 1 ? " period" : " periods"), 13, MUTED, false);
        goalLine.setGravity(Gravity.CENTER);
        content.addView(goalLine, lp(-1, -2, 4));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        double loadFrac = avgLoad > 0 ? r.load / avgLoad : Double.NaN;
        String loadCap = avgLoad > 0 ? String.format(Locale.UK, "%+.0f%% vs your usual day", 100.0 * (loadFrac - 1.0)) : "a relative number";
        stats.addView(statCard("LONGEST", r.longestBoutMin == 0 ? "\u2014" : hm(r.longestBoutMin), "without a long break"), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        sp2.leftMargin = dp(12);
        stats.addView(statCard("LOAD", String.valueOf(Math.round(r.load)), loadCap), sp2);
        content.addView(stats, lp(-1, -2, 16));

        // ---- the day
        LinearLayout c = card("YOUR DAY");
        c.addView(tv(daySentence(r), 14, TEXT, false), lp(-1, -2, 6));
        timeline = new TimelineView(this, day, r.hrMax);
        timeline.pick = new TimelineView.Pick() { @Override public void on(int bout) { select(bout); } };
        c.addView(timeline, lp(-1, dp(190), 12));
        c.addView(legend(new String[] {"Light", "Moderate", "Hard", "Raised, still"}, new int[] {LIGHT, MODERATE, HARD, STILL}), lp(-1, -2, 8));
        callout = tv("Tap a shaded block to see that period.", 12, MUTED, false);
        c.addView(callout, lp(-1, -2, 8));
        addCard(c);

        // ---- intensity
        LinearLayout ic = card("INTENSITY");
        int[] lm = r.levelMin;
        ic.addView(new StackBar(this, new int[] {lm[1], lm[2], lm[3]}, new int[] {LIGHT, MODERATE, HARD}), lp(-1, dp(16), 12));
        ic.addView(statRow(new String[] {"LIGHT", "MODERATE", "HARD", "RAISED, STILL"}, new String[] {hm(lm[1]), hm(lm[2]), hm(lm[3]), hm(r.elevatedStillMin)}), lp(-1, -2, 12));
        ic.addView(tv(String.format(Locale.UK, "Light is 10\u201325%% of your heart-rate reserve (about %.0f\u2013%.0f bpm), moderate 25\u201345%% (%.0f\u2013%.0f), hard above that. "
                        + "Only minutes with wrist movement count as activity.", bpmAt(r, 0.10), bpmAt(r, 0.25), bpmAt(r, 0.25), bpmAt(r, 0.45)), 11, DIM, false), lp(-1, -2, 10));
        if (r.elevatedStillMin >= 10)
            ic.addView(tv("Raised but still: " + hm(r.elevatedStillMin) + " with a raised heart rate and almost no wrist movement. Not counted as activity.", 11, STILL, false), lp(-1, -2, 8));
        addCard(ic);

        // ---- periods
        LinearLayout pc = card("ACTIVITY PERIODS");
        if (r.bouts.isEmpty()) {
            pc.addView(tv("No sustained period found. A period needs at least " + params.minBoutMin + " active minutes; pauses up to " + params.mergeGapMin + " minutes stay inside it.", 13, MUTED, false), lp(-1, -2, 8));
        } else {
            pc.addView(tv("Tap a period for the details. You can name it, or remove it if it was not activity.", 11, DIM, false), lp(-1, -2, 6));
            for (int i = r.bouts.size() - 1; i >= 0; i--) pc.addView(boutRow(i, r.bouts.get(i)), lp(-1, -2, 10));
        }
        if (day.dismissed > 0) {
            TextView rs = tv(day.dismissed + (day.dismissed == 1 ? " period" : " periods") + " removed by you from this day.  Bring back", 12, STILL, true);
            rs.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                ActivityStore.restoreDismissed(getApplicationContext(), day.dayStart, day.dayEnd);
                reload();
            } });
            pc.addView(rs, lp(-1, -2, 14));
        }
        if (!r.shortEfforts.isEmpty()) {
            TextView h = caps("SHORT EFFORTS");
            pc.addView(h, lp(-2, -2, 18));
            for (int i = r.shortEfforts.size() - 1; i >= 0; i--) {
                ActivityDetector.Bout b = r.shortEfforts.get(i);
                pc.addView(tv(clock(b.start) + "\u2013" + clock(b.end) + "  \u00b7  " + dur(b.durationMin) + "  \u00b7  " + b.label
                        + String.format(Locale.UK, "  \u00b7  avg %.0f, peak %.0f bpm", b.avgHr, b.peakHr), 12, MUTED, false), lp(-1, -2, 6));
            }
        }
        addCard(pc);

        addLongViews(r);
        addExplanations(r);
        addFooter();
        if (selected >= 0) select(selected);
    }

    // ------------------------------------------------------------------ reliability and storage card

    private void addHealthCard() {
        final Context app = getApplicationContext();
        List<View> rows = new ArrayList<View>();
        try {
            if (DayLog.enabled(app)) {
                long m = ActivityStore.staleMinutes(app);
                if (m >= KeepAlive.STALE_MIN) {
                    TextView t = tv("No strap data for " + (m >= 120 ? (m / 60) + " h" : m + " min") + ". The logger may have stopped.  Tap to reconnect", 13, AMBER, true);
                    t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                        startActivity(new Intent(ActivityActivity.this, MainActivity.class).putExtra(KeepAlive.EXTRA_RESUME, true)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    } });
                    rows.add(t);
                }
                if (KeepAlive.shouldOfferExemption(app)) {
                    TextView t = tv("Android may stop the logger to save battery.  Allow it to keep running", 13, TEXT, true);
                    t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                        try { startActivity(KeepAlive.batteryIntent(ActivityActivity.this)); } catch (Throwable e) { toast("Open Settings > Battery and allow this app to run in the background."); }
                    } });
                    rows.add(t);
                }
            }
            long bytes = ActivityStore.dbBytes(app);
            if (bytes > 500L * 1024L * 1024L && !settings.autoCompact) {
                TextView t = tv("The day log is " + ActivityStore.mb(bytes) + " and grows every day.  Tap to set up compaction", 13, TEXT, true);
                t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showSettings(); } });
                rows.add(t);
            }
        } catch (Throwable ignored) { }
        if (rows.isEmpty()) return;
        LinearLayout c = card("KEEP IT RUNNING");
        for (View v : rows) c.addView(v, lp(-1, -2, 10));
        content.addView(c, lp(-1, -2, 14));
    }

    // ------------------------------------------------------------------ periods

    private View boutRow(final int index, final ActivityDetector.Bout b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(round(0x0DFFFFFF, 18, LINE));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(tv(clock(b.start) + " \u2013 " + clock(b.end), 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(tv(dur(b.durationMin), 16, levelColor(b.avgPct), true));
        row.addView(top, new LinearLayout.LayoutParams(-1, -2));
        row.addView(tv(b.label + (b.sustained ? "  \u00b7  sustained" : ""), 12, MUTED, false), lp(-1, -2, 2));
        row.addView(new StackBar(this, new int[] {b.levelMin[1], b.levelMin[2], b.levelMin[3]}, new int[] {LIGHT, MODERATE, HARD}), lp(-1, dp(7), 8));
        final LinearLayout det = new LinearLayout(this);
        det.setOrientation(LinearLayout.VERTICAL);
        det.addView(tv(String.format(Locale.UK,
                "Heart rate averaged %.0f bpm and peaked near %.0f \u2014 about %.0f%% of the way from your resting to your maximum rate.\n"
                        + "Wrist movement %.2f g on average, %.0f steps a minute. %d of the %d minutes were active; the rest were short pauses.\nLoad %.0f.",
                b.avgHr, b.peakHr, b.avgPct * 100, b.motion, b.stepsPerMin, b.activeMin, b.durationMin, b.load), 12, MUTED, false));
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = chipButton("Name it");
        name.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askName(b); } });
        TextView rm = chipButton("Not activity");
        rm.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmDismiss(b); } });
        acts.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = dp(8);
        acts.addView(rm, rp);
        det.addView(acts, lp(-1, -2, 10));
        det.setVisibility(View.GONE);
        boutDetails.add(det);
        row.addView(det, lp(-1, -2, 10));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            touched = true;
            boolean open = det.getVisibility() != View.VISIBLE;
            for (View d : boutDetails) d.setVisibility(View.GONE);
            det.setVisibility(open ? View.VISIBLE : View.GONE);
            select(open ? index : -1);
        } });
        return row;
    }

    private TextView chipButton(String s) {
        TextView t = tv(s, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(11), dp(10), dp(11));
        t.setBackground(round(0x14FFFFFF, 14, LINE));
        return t;
    }

    private void askName(final ActivityDetector.Bout b) {
        final EditText e = new EditText(this);
        e.setHint("For example: Site A, lifting");
        e.setText(b.label);
        e.setSelection(e.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Name this period").setView(box)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ActivityStore.annotate(getApplicationContext(), b.start, b.end, false, e.getText().toString());
                        reload();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDismiss(final ActivityDetector.Bout b) {
        new AlertDialog.Builder(this).setTitle("Not activity?")
                .setMessage("Take " + clock(b.start) + "\u2013" + clock(b.end) + " out of this day's totals. You can bring it back from the Activity periods card. "
                        + "This also tells you where the detector is too eager, which is useful when tuning it.")
                .setPositiveButton("Remove", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ActivityStore.annotate(getApplicationContext(), b.start, b.end, true, "");
                        selected = -1;
                        reload();
                    }
                }).setNegativeButton("Keep", null).show();
    }

    private void select(int bout) {
        selected = bout;
        if (timeline != null) timeline.setSelected(bout);
        if (callout == null || day == null || day.r == null) return;
        if (bout < 0 || bout >= day.r.bouts.size()) { callout.setTextColor(MUTED); callout.setText("Tap a shaded block to see that period."); return; }
        ActivityDetector.Bout b = day.r.bouts.get(bout);
        callout.setTextColor(TEXT);
        callout.setText(clock(b.start) + "\u2013" + clock(b.end) + "  \u00b7  " + dur(b.durationMin) + "  \u00b7  " + b.label
                + String.format(Locale.UK, "  \u00b7  avg %.0f bpm, peak %.0f", b.avgHr, b.peakHr));
    }

    private String daySentence(ActivityDetector.Result r) {
        if (r.validMin < 30) return "Not much logged for this day yet (" + hm(r.validMin) + ").";
        if (r.bouts.isEmpty()) return "No sustained activity found in " + hm(r.validMin) + " of logged time.";
        ActivityDetector.Bout longest = null;
        for (ActivityDetector.Bout b : r.bouts) if (longest == null || b.durationMin > longest.durationMin) longest = b;
        return "Active for " + hm(r.activeMin) + " in " + r.bouts.size() + (r.bouts.size() == 1 ? " period" : " periods") + " out of "
                + hm(r.validMin) + " logged. The longest ran " + dur(longest.durationMin) + " (" + clock(longest.start) + "\u2013" + clock(longest.end)
                + ") at about " + levelName(longest.avgPct) + " effort.";
    }

    // ------------------------------------------------------------------ the long views

    private List<ActivityStore.Summary> weekEndingViewed() {
        List<ActivityStore.Summary> w = new ArrayList<ActivityStore.Summary>();
        if (hist == null) return w;
        int idx = hist.days.size() - 1 - (int) Math.round((todayStart() - dayStart) / 86400.0);
        if (idx < 6 || idx >= hist.days.size()) return w;
        for (int i = idx - 6; i <= idx; i++) w.add(hist.days.get(i));
        return w;
    }

    private double averageLoad() {
        if (hist == null) return 0;
        double s = 0; int n = 0;
        for (int i = 0; i < hist.days.size() - 1; i++) {
            ActivityStore.Summary d = hist.days.get(i);
            if (d.ok && d.validMin >= 600 && d.dayStart != dayStart) { s += d.load; n++; }
        }
        return n >= 3 ? s / n : 0;
    }

    private void addLongViews(final ActivityDetector.Result r) {
        // ---- last 7 days
        LinearLayout wc = card("LAST 7 DAYS");
        List<ActivityStore.Summary> week = weekEndingViewed();
        if (week.size() == 7) {
            wc.addView(new WeekView(this, week, settings.activeGoalMin, new WeekView.Click() {
                @Override public void on(int index) { go(weekEndingViewed().get(index).dayStart); }
            }), lp(-1, dp(150), 10));
            int tot = 0, days = 0;
            for (ActivityStore.Summary s : week) if (s.ok) { tot += s.activeMin; days++; }
            wc.addView(tv(days == 0 ? "No logged days yet." : "Average " + hm(tot / days) + " active a day over " + days + " logged " + (days == 1 ? "day" : "days") + ". Tap a bar to open that day.", 12, MUTED, false), lp(-1, -2, 10));
        } else wc.addView(tv("Reading the last 7 days\u2026", 12, MUTED, false), lp(-1, -2, 8));
        addCard(wc);

        // ---- workload: last 7 days against your usual 6 weeks
        LinearLayout lc = card("WORKLOAD  \u00b7  THIS WEEK AGAINST YOUR USUAL");
        if (hist == null) lc.addView(tv("Reading your history\u2026", 12, MUTED, false), lp(-1, -2, 8));
        else {
            int n = hist.days.size() - 1;                                  // today is partial: left out
            double[] load = new double[Math.min(42, n)];
            for (int i = 0; i < load.length; i++) {
                ActivityStore.Summary s = hist.days.get(n - load.length + i);
                load[i] = (s.ok && s.validMin >= 600) ? s.load : Double.NaN;
            }
            ActivityTrends.LoadStatus ls = ActivityTrends.loadStatus(load);
            lc.addView(tv(ls.calibrating ? "Calibrating" : cap(ls.word), 20, ls.calibrating ? MUTED : (ls.ratio > 1.3 ? HARD : ls.ratio < 0.8 ? LIGHT : GREEN), true), lp(-2, -2, 8));
            lc.addView(new LoadGauge(this, ls.ratio, ls.calibrating), lp(-1, dp(56), 8));
            lc.addView(tv(ls.sentence, 12, MUTED, false), lp(-1, -2, 8));
            if (hist.missing > 0) lc.addView(tv("Still reading " + hist.missing + " older days; this sharpens as they are read.", 11, DIM, false), lp(-1, -2, 6));
            lc.addView(note("What does this mean?",
                    "Load adds up every logged minute, weighted by how hard your heart was working. This compares the average daily load of your last 7 logged days with the average over the last 6 weeks. "
                            + "Between 0.8 and 1.3 times your usual is your normal range. Well above it means a heavier week than your body is used to \u2014 worth noticing if you also feel tired or your resting heart rate is up. "
                            + "Well below means a lighter week. Only days with at least 10 hours logged count, and it needs about 14 such days before it says anything."), lp(-1, -2, 10));
        }
        addCard(lc);

        // ---- 13 weeks
        LinearLayout hc = card("LAST 13 WEEKS");
        if (hist == null) hc.addView(tv("Reading your history\u2026", 12, MUTED, false), lp(-1, -2, 8));
        else {
            long[] dsArr = new long[hist.days.size()];
            int[] amArr = new int[hist.days.size()];
            for (int i = 0; i < dsArr.length; i++) { ActivityStore.Summary s = hist.days.get(i); dsArr[i] = s.dayStart; amArr[i] = s.ok ? s.activeMin : -1; }
            ActivityTrends.Grid g = ActivityTrends.heatGrid(dsArr, amArr, todayStart(), 13);
            hc.addView(new HeatView(this, g, new HeatView.Click() { @Override public void on(long dayStartPicked) { go(dayStartPicked); } }), lp(-1, dp(138), 10));
            hc.addView(tv(String.format(Locale.UK, "Each square is a day, oldest at the left, this week at the right. Darkest green is %s or more of activity. Tap a square to open that day.", hm((int) Math.round(g.scale))), 12, MUTED, false), lp(-1, -2, 10));
            if (hist.missing > 0) hc.addView(tv("Still reading " + hist.missing + " older days; the grid fills in as they are read. Next time it opens instantly.", 11, DIM, false), lp(-1, -2, 6));
        }
        addCard(hc);

        // ---- your usual day
        LinearLayout sc = card("YOUR USUAL DAY");
        if (hist == null || todaySummary == null) sc.addView(tv("Reading your history\u2026", 12, MUTED, false), lp(-1, -2, 8));
        else {
            List<int[]> hourly = new ArrayList<int[]>();
            List<Integer> totals = new ArrayList<Integer>();
            for (int i = 0; i < hist.days.size() - 1; i++) {
                ActivityStore.Summary s = hist.days.get(i);
                if (s.ok && s.validMin >= 600) { hourly.add(s.hourly); totals.add(s.activeMin); }
            }
            double[] shape = ActivityTrends.usualShape(hourly, 5);
            if (shape == null) {
                sc.addView(tv("Needs 5 finished days with at least 10 hours logged and some activity. So far: " + hourly.size() + ". Then this shows how much of a normal day you have usually done by each hour, and whether today is running ahead or behind.", 12, MUTED, false), lp(-1, -2, 8));
            } else {
                Collections.sort(totals);
                double usualTotal = totals.get(totals.size() / 2);
                long now = System.currentTimeMillis() / 1000L;
                long ts = todayStart();
                int hourNow = (int) Math.min(23, Math.max(0, (now - ts) / 3600L));
                double frac = ((now - ts) % 3600L) / 3600.0;
                int[] cum = new int[24];
                int run = 0;
                for (int i = 0; i < 24; i++) { run += todaySummary.hourly[i]; cum[i] = run; }
                sc.addView(new ShapeView(this, shape, usualTotal, cum, (dayStart == ts) ? hourNow + frac : -1, ts), lp(-1, dp(150), 10));
                double proj = ActivityTrends.projectedTotal(shape, hourNow, frac, cum[hourNow]);
                String line;
                if (dayStart != ts) line = "This is your usual shape from the last " + hourly.size() + " finished days. Open today to see how today compares.";
                else if (Double.isNaN(proj)) line = "Too early in the day to compare yet. The grey line is the cumulative activity of a usual day (about " + hm((int) usualTotal) + " in all).";
                else line = String.format(Locale.UK, "So far today: %s. At this time of day you have usually done about %.0f%% of your day's activity, so today is heading for about %s, against %s on a usual day.",
                        hm(cum[hourNow]), 100.0 * (hourNow == 0 ? 0 : shape[hourNow - 1]) + 100.0 * frac * (shape[hourNow] - (hourNow == 0 ? 0 : shape[hourNow - 1])), hm((int) Math.round(proj)), hm((int) Math.round(usualTotal)));
                sc.addView(tv(line, 12, MUTED, false), lp(-1, -2, 10));
                sc.addView(note("How is this worked out?",
                        "For each finished day, the activity by each hour is turned into a share of that day's own total, and then the median of those shares is taken, so one huge day cannot change what a normal afternoon looks like. "
                                + "Today's projection divides what you have done so far by the share you usually have done by this time. It is a rough guide, and meaningless early in the day or on a day that is not like your others."), lp(-1, -2, 10));
            }
        }
        addCard(sc);
    }

    private void addExplanations(ActivityDetector.Result r) {
        TextView h = caps("HOW THIS WORKS");
        content.addView(h, lp(-2, -2, 22));
        String reserve = r == null ? "Your resting rate is learned from your quietest minutes over the last days, and your maximum is estimated from your age. The gap between them is your reserve."
                : String.format(Locale.UK, "Your resting rate is about %.0f bpm and your maximum about %.0f (estimated from age %d), so your reserve is %.0f beats. 10%% of it is about %.0f bpm, 25%% about %.0f, 45%% about %.0f.",
                r.restHr, r.hrMax, settings.age, r.hrMax - r.restHr, bpmAt(r, 0.10), bpmAt(r, 0.25), bpmAt(r, 0.45));
        content.addView(explain("What counts as activity",
                "A minute counts as active when your heart rate is at least 10% of the way from resting to maximum AND your wrist is moving, or when your wrist is clearly moving and your heart rate is a little raised.\n\n"
                        + "Activity is shown as periods. Pauses of up to " + params.mergeGapMin + " minutes (a tea break, a gap in the strap's data) stay inside the same period, so a morning's work is one block, not twenty. "
                        + "A period needs at least " + params.minBoutMin + " active minutes; anything shorter that was hard enough is listed as a short effort."), lp(-1, -2, 10));
        content.addView(explain("Heart-rate reserve in plain words", reserve + "\n\nWorking on your feet usually sits in the light to moderate range for hours. That is what this screen is built to show: a long, steady load rather than a short hard spike."), lp(-1, -2, 10));
        content.addView(explain("Why a raised heart rate alone is not counted",
                "Stress, caffeine, heat, illness, a big meal or poor sleep can lift your heart rate while you sit still. If your wrist is not moving, those minutes are shown separately as \"raised, still\" and are not counted as activity. "
                        + "If you see a lot of them on days when you were not working, that is worth noticing."), lp(-1, -2, 10));
        content.addView(explain("Why not the strap's own walk and run label",
                "On a test jog on 23 Oct the strap called it walking for most of the time and never called it running, and its step counter saw only about 63 steps a minute. "
                        + "Manual work, with arms and hands moving and few steps, would be missed even more. So this screen uses heart rate and wrist movement instead."), lp(-1, -2, 10));
        content.addView(explain("Correcting it",
                "Open a period and tap \"Name it\" to label it (for example the job or the site), or \"Not activity\" to take it out of the day's totals. Your corrections are kept on the phone. "
                        + "\"Export this day\" in Settings writes the minute-by-minute data and your labelled periods to the Download/MGactivity folder, which is what is needed to tune the detector to your work."), lp(-1, -2, 10));
        content.addView(explain("What the load number means",
                "Load adds up every minute, weighted by how hard your heart was working (Banister's training impulse, men's weighting). Long stretches at a moderate level add up to as much as short hard efforts. "
                        + "It is relative: compare one day with another, and with your own average shown under the ring. It is not a calorie count and not comparable with other people or other apps."), lp(-1, -2, 10));
        content.addView(explain("Storage and old days",
                "The day log keeps one row for every second, which adds up to about 14 MB a day. With compaction switched on (Settings), days older than the number you choose are first saved whole to a compressed file in the app's archive folder, "
                        + "and then reduced to one row a minute inside the database. This screen reads both kinds of day the same way, and the numbers do not change. Nothing is deleted before its archive file is complete, and ECG files are never touched."), lp(-1, -2, 10));
        content.addView(explain("Limits",
                "The thresholds were set from one evening and a short jog and can be tuned on real work days. Wrist heart rate drifts when the strap is loose or you grip tools hard, and wrist movement can be low when you work with the arm still (for example, holding a heavy load). "
                        + "Research use only. Not a medical device and not advice about your health."), lp(-1, -2, 10));
    }

    private void addFooter() {
        TextView f = tv("Estimates from this strap's own records. Research only; not a medical device.", 11, DIM, false);
        f.setGravity(Gravity.CENTER);
        content.addView(f, lp(-1, -2, 22));
    }

    private View explain(String title, String body) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        c.setBackground(round(CARD, 20, LINE));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(tv(title, 14, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView chev = tv("\u25be", 16, MUTED, true);
        head.addView(chev);
        c.addView(head, new LinearLayout.LayoutParams(-1, -2));
        final TextView b = tv(body, 13, MUTED, false);
        b.setLineSpacing(0f, 1.15f);
        b.setVisibility(View.GONE);
        c.addView(b, lp(-1, -2, 10));
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            touched = true;
            boolean open = b.getVisibility() != View.VISIBLE;
            b.setVisibility(open ? View.VISIBLE : View.GONE);
            chev.setText(open ? "\u25b4" : "\u25be");
        } });
        return c;
    }

    /** A small expandable explanation that sits inside a card. */
    private View note(String title, String body) {
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        final TextView head = tv(title + "  \u25be", 12, STILL, true);
        c.addView(head);
        final TextView b = tv(body, 12, MUTED, false);
        b.setLineSpacing(0f, 1.15f);
        b.setVisibility(View.GONE);
        c.addView(b, lp(-1, -2, 6));
        head.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            touched = true;
            boolean open = b.getVisibility() != View.VISIBLE;
            b.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setText(title + (open ? "  \u25b4" : "  \u25be"));
        } });
        return c;
    }

    private View legend(String[] names, int[] colors) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            TextView t = tv("\u25cf " + names[i], 11, colors[i], true);
            t.setPadding(0, 0, dp(14), 0);
            r.addView(t);
        }
        return r;
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        final Context app = getApplicationContext();
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText age = field("Age (sets your maximum heart rate)", String.valueOf(settings.age));
        final EditText rest = field("Resting heart rate (empty = automatic)", Double.isNaN(settings.restOverride) ? "" : String.valueOf(Math.round(settings.restOverride)));
        final EditText goal = field("Daily active-time goal (minutes)", String.valueOf(settings.activeGoalMin));
        box.addView(age);
        box.addView(rest);
        box.addView(goal);

        TextView st = caps("STORAGE");
        box.addView(st, lp(-2, -2, 18));
        box.addView(tv("Day log " + ActivityStore.mb(ActivityStore.dbBytes(app)) + "  \u00b7  archive " + ActivityStore.mb(ActivityStore.archiveBytes(app)), 13, TEXT, false), lp(-1, -2, 4));
        final CheckBox auto = new CheckBox(this);
        auto.setText("Compact old days automatically (after archiving them)");
        auto.setChecked(settings.autoCompact);
        box.addView(auto, lp(-1, -2, 4));
        final EditText keep = field("Keep this many days in full detail (at least 14)", String.valueOf(settings.keepDays));
        box.addView(keep);
        TextView now = chipButton("Compact old days now");
        now.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final int kd = clamp(parse(keep.getText().toString(), 60), 14, 3650);
            toast("Compacting in the background\u2026");
            new Thread(new Runnable() { @Override public void run() {
                final ActivityStore.CompactResult res = ActivityStore.compact(app, kd, 400, null);
                ui.post(new Runnable() { @Override public void run() {
                    toast(res.error.isEmpty() ? (res.days == 0 ? "Nothing older than " + kd + " days to compact." : "Compacted " + res.days + " days (" + res.secondsDeleted + " seconds archived, " + res.minuteRows + " minute rows kept).")
                            : "Stopped: " + res.error);
                    reload();
                } });
            } }, "activity-compact").start();
        } });
        box.addView(now, lp(-1, -2, 10));
        TextView vac = chipButton("Reclaim file space (slow, needs free space)");
        vac.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            toast("Working\u2026 this can take a few minutes. Keep the app open.");
            new Thread(new Runnable() { @Override public void run() {
                final String msg = ActivityStore.vacuum(app);
                ui.post(new Runnable() { @Override public void run() { toast(msg); } });
            } }, "activity-vacuum").start();
        } });
        box.addView(vac, lp(-1, -2, 8));
        TextView exp = chipButton("Export this day for tuning");
        exp.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final ActivityStore.DayData dd = day;
            new Thread(new Runnable() { @Override public void run() {
                final String msg = ActivityStore.exportDay(app, dd);
                ui.post(new Runnable() { @Override public void run() { toast(msg); } });
            } }, "activity-export").start();
        } });
        box.addView(exp, lp(-1, -2, 8));
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Activity settings").setView(sv)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ActivityStore.Settings s = new ActivityStore.Settings();
                        s.age = clamp(parse(age.getText().toString(), 53), 18, 90);
                        String rr = rest.getText().toString().trim();
                        s.restOverride = rr.isEmpty() ? Double.NaN : clamp(parse(rr, 65), 35, 100);
                        s.activeGoalMin = clamp(parse(goal.getText().toString(), 240), 30, 960);
                        s.autoCompact = auto.isChecked();
                        s.keepDays = clamp(parse(keep.getText().toString(), 60), 14, 3650);
                        ActivityStore.save(app, s);
                        reload();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private static int parse(String s, int dflt) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; } }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------ text helpers

    private static double bpmAt(ActivityDetector.Result r, double pct) { return r.restHr + pct * (r.hrMax - r.restHr); }
    private static int levelColor(double pct) { return pct >= 0.45 ? HARD : pct >= 0.25 ? MODERATE : LIGHT; }
    private static String levelName(double pct) { return pct >= 0.45 ? "hard" : pct >= 0.25 ? "moderate" : "light"; }
    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static String clock(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }
    private static String dayText(long dayStart) { return new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date((dayStart + 6 * 3600L) * 1000L)); }
    private static String hm(int min) { return min < 60 ? min + " m" : (min / 60) + ":" + String.format(Locale.UK, "%02d", min % 60); }
    private static String dur(int min) { return min < 60 ? min + " m" : (min / 60) + " h " + String.format(Locale.UK, "%02d", min % 60) + " m"; }

    // ------------------------------------------------------------------ layout helpers (same look as the opening screen)

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Text in the app's own font (Outfit) at a weight: 200 for big numbers, 400 for text, 600 for emphasis. */
    private TextView tvw(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(HealthViews.font(this, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView tv(String s, int sp, int color, boolean bold) { return tvw(s, sp, color, bold ? 600 : 400); }

    private TextView caps(String s) {
        TextView t = tvw(s, 11, MUTED, 400);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** The small rounded label the other screens use for STRAP, Settings and the like. */
    private TextView chipText(String s) {
        TextView t = tvw(s, 12, TEXT, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(8), dp(13), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x0DFFFFFF);
        g.setCornerRadius(dp(17));
        g.setStroke(dp(1), 0x0FFFFFFF);
        t.setBackground(g);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(topDp);
        return p;
    }

    /** The "glass" card: dark fill, hairline edge. */
    private GradientDrawable round(int color, int rDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        g.setStroke(dp(1), color == CARD ? 0x0FFFFFFF : stroke);
        return g;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(18));
        c.setBackground(round(CARD, 22, LINE));
        c.addView(caps(title), new LinearLayout.LayoutParams(-2, -2));
        return c;
    }

    private void addCard(LinearLayout c) { content.addView(c, lp(-1, -2, 12)); }

    private LinearLayout statCard(String label, String value, String caption) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 22, LINE));
        c.addView(caps(label));
        c.addView(tvw(value, 36, TEXT, 200), lp(-2, -2, 6));
        TextView cp = tvw(caption, 12, MUTED, 400);
        c.addView(cp, lp(-2, -2, 2));
        return c;
    }

    private LinearLayout statRow(String[] labels, String[] values) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setWeightSum(labels.length);
        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.addView(tvw(values[i], 20, TEXT, 300));
            TextView l = tvw(labels[i], 9, MUTED, 400);
            l.setLetterSpacing(0.14f);
            cell.addView(l, lp(-2, -2, 3));
            r.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return r;
    }

    // ------------------------------------------------------------------ canvas views

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    /** Minutes per intensity level as one rounded bar. */
    static final class StackBar extends View {
        private final int[] v, col;
        StackBar(Context c, int[] v, int[] col) { super(c); this.v = v; this.col = col; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, fill(LINE));
            int tot = 0;
            for (int x : v) tot += x;
            if (tot <= 0) return;
            cv.save();
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, Path.Direction.CW);
            cv.clipPath(clip);
            float x = 0;
            for (int i = 0; i < v.length; i++) {
                if (v[i] <= 0) continue;
                float wi = w * v[i] / tot;
                cv.drawRect(x, 0, x + wi, h, fill(col[i]));
                x += wi;
            }
            cv.restore();
        }
    }

    /** The day, 04:00 to 04:00: heart rate per minute, activity periods shaded by effort, raised-but-still minutes in violet. Tap a block to select it. */
    static final class TimelineView extends View {
        interface Pick { void on(int bout); }
        Pick pick;
        private final ActivityStore.DayData d;
        private final int n;
        private final float[] hr;
        private final byte[] kind;
        private final float rest;
        private float maxSeen;
        private int sel = -1;

        TimelineView(Context c, ActivityStore.DayData d, double hrMax) {
            super(c);
            this.d = d;
            this.n = Math.max(60, (int) ((d.dayEnd - d.dayStart) / 60));
            this.hr = new float[n];
            this.kind = new byte[n];
            Arrays.fill(hr, Float.NaN);
            this.rest = (float) d.r.restHr;
            for (ActivityDetector.Epoch e : d.r.epochs) {
                int m = (int) ((e.start - d.dayStart) / 60);
                if (m < 0 || m >= n || Double.isNaN(e.hr)) continue;
                hr[m] = (float) e.hr;
                kind[m] = (byte) e.kind;
                maxSeen = Math.max(maxSeen, (float) e.hr);
            }
        }

        void setSelected(int i) { sel = i; invalidate(); }

        private float[] plot() {
            float dn = getResources().getDisplayMetrics().density;
            return new float[] {34f * dn, 8f * dn, getWidth() - 8f * dn, getHeight() - 22f * dn};   // l, t, r, b
        }

        @Override protected void onDraw(Canvas cv) {
            try { draw2(cv); } catch (Throwable ignored) { }
        }

        private void draw2(Canvas cv) {
            float dn = getResources().getDisplayMetrics().density;
            float[] p = plot();
            float l = p[0], t = p[1], r = p[2], b = p[3], w = r - l, h = b - t;
            float yMin = Math.max(40f, (float) Math.floor((rest - 10f) / 10f) * 10f);
            float yMax = Math.max(rest + 40f, (float) Math.ceil((Math.max(maxSeen, rest) + 8f) / 10f) * 10f);
            Paint grid = stroke(LINE, 1f * dn);
            Paint lab = fill(DIM);
            lab.setTypeface(HealthViews.font(getContext(), 400)); lab.setTextSize(10f * dn);
            for (float y = yMin; y <= yMax; y += 10f) {
                float py = b - h * (y - yMin) / (yMax - yMin);
                cv.drawLine(l, py, r, py, grid);
                lab.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(String.valueOf((int) y), l - 6f * dn, py + 3.5f * dn, lab);
            }
            lab.setTextAlign(Paint.Align.CENTER);
            SimpleDateFormat f = new SimpleDateFormat("HH", Locale.UK);
            for (int hh = 0; hh <= 24; hh += 4) {
                float x = l + w * (hh * 60f) / n;
                if (hh > 0 && hh < 24) cv.drawLine(x, t, x, b, grid);
                if (hh < 24) cv.drawText(f.format(new Date((d.dayStart + hh * 3600L) * 1000L)), x + (hh == 0 ? 8f * dn : 0f), getHeight() - 6f * dn, lab);
            }
            Paint still = fill((STILL & 0x00FFFFFF) | 0x33000000);
            int s0 = -1;
            for (int m = 0; m <= n; m++) {
                boolean st = m < n && kind[m] == ActivityDetector.ELEVATED_STILL;
                if (st && s0 < 0) s0 = m;
                if (!st && s0 >= 0) {
                    if (m - s0 >= 3) cv.drawRect(l + w * s0 / n, t, l + w * m / n, b, still);
                    s0 = -1;
                }
            }
            for (int i = 0; i < d.r.bouts.size(); i++) {
                ActivityDetector.Bout bo = d.r.bouts.get(i);
                float x0 = l + w * ((bo.start - d.dayStart) / 60f) / n, x1 = l + w * ((bo.end - d.dayStart) / 60f) / n;
                int c = levelColor(bo.avgPct);
                cv.drawRect(x0, t, Math.max(x1, x0 + 2f * dn), b, fill((c & 0x00FFFFFF) | (i == sel ? 0x66000000 : 0x2E000000)));
                if (i == sel) {
                    Paint e = stroke(c, 2f * dn);
                    cv.drawLine(x0, t, x0, b, e);
                    cv.drawLine(x1, t, x1, b, e);
                }
            }
            Paint dash = stroke(DIM, 1.2f * dn);
            dash.setPathEffect(new DashPathEffect(new float[] {6f * dn, 5f * dn}, 0));
            float ry = b - h * (rest - yMin) / (yMax - yMin);
            cv.drawLine(l, ry, r, ry, dash);
            Paint line = stroke(HRC, 1.6f * dn);
            Path path = new Path();
            boolean pen = false;
            int last = -10;
            for (int m = 0; m < n; m++) {
                if (Float.isNaN(hr[m])) continue;
                float x = l + w * m / n, y = b - h * (Math.min(hr[m], yMax) - yMin) / (yMax - yMin);
                if (!pen || m - last > 3) path.moveTo(x, y); else path.lineTo(x, y);
                pen = true;
                last = m;
            }
            cv.drawPath(path, line);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_DOWN) {
                float[] p = plot();
                float minute = (ev.getX() - p[0]) / (p[2] - p[0]) * n;
                int hit = -1;
                for (int i = 0; i < d.r.bouts.size(); i++) {
                    ActivityDetector.Bout bo = d.r.bouts.get(i);
                    float s = (bo.start - d.dayStart) / 60f, e = (bo.end - d.dayStart) / 60f;
                    if (minute >= s - 8f && minute <= e + 8f) { hit = i; break; }
                }
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    int next = (hit == sel) ? -1 : hit;
                    sel = next;
                    invalidate();
                    if (pick != null) pick.on(next);
                    performClick();
                }
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override public boolean performClick() { return super.performClick(); }
    }

    /** Seven days of active minutes, stacked by effort, with the daily goal. Tap a bar to open that day. */
    static final class WeekView extends View {
        interface Click { void on(int index); }
        private final List<ActivityStore.Summary> days;
        private final int goal;
        private final Click click;

        WeekView(Context c, List<ActivityStore.Summary> days, int goal, Click click) {
            super(c);
            this.days = days;
            this.goal = goal;
            this.click = click;
        }

        @Override protected void onDraw(Canvas cv) {
            try {
                float dn = getResources().getDisplayMetrics().density;
                float w = getWidth(), h = getHeight(), top = 16f * dn, bot = h - 22f * dn;
                int max = goal;
                for (ActivityStore.Summary s : days) max = Math.max(max, s.activeMin);
                max = (int) Math.ceil(max / 60.0) * 60;
                float slot = w / days.size(), bw = slot * 0.5f;
                Paint lab = fill(DIM);
                lab.setTypeface(HealthViews.font(getContext(), 400)); lab.setTextSize(10f * dn);
                lab.setTextAlign(Paint.Align.CENTER);
                SimpleDateFormat f = new SimpleDateFormat("EEE", Locale.UK);
                for (int i = 0; i < days.size(); i++) {
                    ActivityStore.Summary s = days.get(i);
                    float cx = slot * i + slot / 2f;
                    cv.drawRoundRect(new RectF(cx - bw / 2, top, cx + bw / 2, bot), 6f * dn, 6f * dn, fill(0x0DFFFFFF));
                    if (s.ok) {
                        int[] v = {s.lightMin, s.moderateMin, s.hardMin};
                        int[] col = {LIGHT, MODERATE, HARD};
                        float y = bot;
                        for (int k = 0; k < 3; k++) {
                            if (v[k] <= 0) continue;
                            float hh = (bot - top) * v[k] / max;
                            cv.drawRect(cx - bw / 2, y - hh, cx + bw / 2, y, fill(col[k]));
                            y -= hh;
                        }
                        Paint tx = fill(i == days.size() - 1 ? TEXT : MUTED);
                        tx.setTypeface(HealthViews.font(getContext(), 400)); tx.setTextSize(10f * dn);
                        tx.setTextAlign(Paint.Align.CENTER);
                        cv.drawText(s.activeMin >= 60 ? (s.activeMin / 60) + "h" + String.format(Locale.UK, "%02d", s.activeMin % 60) : s.activeMin + "m", cx, y - 4f * dn, tx);
                    }
                    lab.setColor(i == days.size() - 1 ? TEXT : DIM);
                    cv.drawText(f.format(new Date((s.dayStart + 6 * 3600L) * 1000L)), cx, h - 6f * dn, lab);
                }
                Paint dash = stroke(MUTED, 1f * dn);
                dash.setPathEffect(new DashPathEffect(new float[] {6f * dn, 5f * dn}, 0));
                float gy = bot - (bot - top) * goal / max;
                cv.drawLine(0, gy, w, gy, dash);
            } catch (Throwable ignored) { }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (ev.getAction() == MotionEvent.ACTION_UP && days.size() > 0) {
                int i = (int) (ev.getX() / (getWidth() / (float) days.size()));
                if (i >= 0 && i < days.size() && click != null) click.on(i);
                performClick();
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override public boolean performClick() { return super.performClick(); }
    }

    /** Last 7 days against your usual 6 weeks as one bar: the usual range (0.8 to 1.3 times) is shaded, a marker shows where you are. */
    static final class LoadGauge extends View {
        private final double ratio;
        private final boolean calibrating;
        LoadGauge(Context c, double ratio, boolean calibrating) { super(c); this.ratio = ratio; this.calibrating = calibrating; }

        @Override protected void onDraw(Canvas cv) {
            try {
                float dn = getResources().getDisplayMetrics().density;
                float w = getWidth(), barT = 10f * dn, barB = 28f * dn, max = 1.6f;
                cv.drawRoundRect(new RectF(0, barT, w, barB), 9f * dn, 9f * dn, fill(LINE));
                if (!calibrating) {
                    cv.drawRect(w * 0.8f / max, barT, w * 1.3f / max, barB, fill((GREEN & 0x00FFFFFF) | 0x55000000));
                    float x = (float) (w * Math.min(max, Math.max(0.0, ratio)) / max);
                    int col = ratio > 1.3 ? HARD : ratio < 0.8 ? LIGHT : GREEN;
                    cv.drawRoundRect(new RectF(x - 3f * dn, barT - 5f * dn, x + 3f * dn, barB + 5f * dn), 3f * dn, 3f * dn, fill(col));
                }
                Paint lab = fill(DIM);
                lab.setTypeface(HealthViews.font(getContext(), 400)); lab.setTextSize(10f * dn);
                lab.setTextAlign(Paint.Align.CENTER);
                cv.drawText("0.8\u00d7", w * 0.8f / max, barB + 18f * dn, lab);
                cv.drawText("1.3\u00d7", w * 1.3f / max, barB + 18f * dn, lab);
                lab.setTextAlign(Paint.Align.LEFT);
                cv.drawText("lighter", 0, barB + 18f * dn, lab);
                lab.setTextAlign(Paint.Align.RIGHT);
                cv.drawText("heavier", w, barB + 18f * dn, lab);
            } catch (Throwable ignored) { }
        }
    }

    /** 13 weeks of days, one square each, Monday at the top, this week at the right. Tap a square to open that day. */
    static final class HeatView extends View {
        interface Click { void on(long dayStart); }
        private final ActivityTrends.Grid g;
        private final Click click;
        private static final int[] RAMP = {0x14FFFFFF, 0xFF123A55, 0xFF1A6FA3, 0xFF2FA8F0, 0xFF8FD8FF};
        HeatView(Context c, ActivityTrends.Grid g, Click click) { super(c); this.g = g; this.click = click; }

        private float[] geo() {
            float dn = getResources().getDisplayMetrics().density;
            float labelW = 14f * dn, gap = 3f * dn;
            float cell = Math.min((getWidth() - labelW) / g.weeks - gap, (getHeight() - 4f * dn) / 7f - gap);
            return new float[] {labelW, gap, cell};
        }

        @Override protected void onDraw(Canvas cv) {
            try {
                float dn = getResources().getDisplayMetrics().density;
                float[] gm = geo();
                float labelW = gm[0], gap = gm[1], cell = gm[2];
                Paint lab = fill(DIM);
                lab.setTypeface(HealthViews.font(getContext(), 400)); lab.setTextSize(9f * dn);
                String[] wd = {"M", "", "W", "", "F", "", "S"};
                for (int d = 0; d < 7; d++) if (!wd[d].isEmpty()) cv.drawText(wd[d], 0, 2f * dn + d * (cell + gap) + cell * 0.75f, lab);
                for (int wk = 0; wk < g.weeks; wk++) for (int d = 0; d < 7; d++) {
                    ActivityTrends.Cell c = g.cols[wk][d];
                    if (c.dayStart < 0) continue;
                    float x = labelW + wk * (cell + gap), y = 2f * dn + d * (cell + gap);
                    cv.drawRoundRect(new RectF(x, y, x + cell, y + cell), 3f * dn, 3f * dn, fill(RAMP[c.level]));
                }
            } catch (Throwable ignored) { }
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                float dn = getResources().getDisplayMetrics().density;
                float[] gm = geo();
                int wk = (int) ((ev.getX() - gm[0]) / (gm[2] + gm[1]));
                int d = (int) ((ev.getY() - 2f * dn) / (gm[2] + gm[1]));
                if (wk >= 0 && wk < g.weeks && d >= 0 && d < 7 && g.cols[wk][d].dayStart > 0 && click != null) click.on(g.cols[wk][d].dayStart);
                performClick();
                return true;
            }
            return super.onTouchEvent(ev);
        }

        @Override public boolean performClick() { return super.performClick(); }
    }

    /** Cumulative active minutes across the day: your usual day in grey, today in green up to now. */
    static final class ShapeView extends View {
        private final double[] shape;
        private final double usualTotal;
        private final int[] cum;
        private final double nowHour;                 // fractional hours since 04:00, or -1 when not showing today
        private final long dayStart;
        ShapeView(Context c, double[] shape, double usualTotal, int[] cum, double nowHour, long dayStart) {
            super(c); this.shape = shape; this.usualTotal = usualTotal; this.cum = cum; this.nowHour = nowHour; this.dayStart = dayStart;
        }

        @Override protected void onDraw(Canvas cv) {
            try {
                float dn = getResources().getDisplayMetrics().density;
                float l = 34f * dn, t = 8f * dn, r = getWidth() - 8f * dn, b = getHeight() - 22f * dn, w = r - l, h = b - t;
                double top = Math.max(usualTotal, cum[23]) * 1.1;
                top = Math.max(60, Math.ceil(top / 60.0) * 60);
                Paint grid = stroke(LINE, 1f * dn), lab = fill(DIM);
                lab.setTypeface(HealthViews.font(getContext(), 400)); lab.setTextSize(10f * dn);
                lab.setTextAlign(Paint.Align.RIGHT);
                for (double v = 0; v <= top + 1; v += top / 4) {
                    float y = b - (float) (h * v / top);
                    cv.drawLine(l, y, r, y, grid);
                    cv.drawText(String.format(Locale.UK, "%.0fh", v / 60.0), l - 6f * dn, y + 3.5f * dn, lab);
                }
                lab.setTextAlign(Paint.Align.CENTER);
                SimpleDateFormat f = new SimpleDateFormat("HH", Locale.UK);
                for (int hh = 0; hh < 24; hh += 4) cv.drawText(f.format(new Date((dayStart + hh * 3600L) * 1000L)), l + w * hh / 24f, getHeight() - 6f * dn, lab);
                Path usual = new Path();
                usual.moveTo(l, b);
                for (int i = 0; i < 24; i++) usual.lineTo(l + w * (i + 1) / 24f, b - (float) (h * shape[i] * usualTotal / top));
                cv.drawPath(usual, stroke(MUTED, 2f * dn));
                if (nowHour >= 0) {
                    Path today = new Path();
                    today.moveTo(l, b);
                    int full = (int) Math.floor(nowHour);
                    for (int i = 0; i < full && i < 24; i++) today.lineTo(l + w * (i + 1) / 24f, b - (float) (h * cum[i] / top));
                    if (full < 24) {
                        double prev = full == 0 ? 0 : cum[full - 1], here = cum[full];
                        double part = prev + (here - prev) * (nowHour - full);
                        today.lineTo(l + w * (float) nowHour / 24f, b - (float) (h * part / top));
                    }
                    cv.drawPath(today, stroke(GREEN, 2.6f * dn));
                }
            } catch (Throwable ignored) { }
        }
    }
}
