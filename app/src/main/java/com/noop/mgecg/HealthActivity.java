package com.noop.mgecg;
// 0.71.0 (18 Nov 2026): Today: a tap on a ring opens that ring's OWN PAGE (back arrow, the ring's number, then how it is worked out) instead of a drop-down card under the rings; the system Back key returns to Today at the same scroll position. Rest: the SLEEP RESEARCH section (regularity, last nights, 24 h heart-rate rhythm, age studies, sources) sits on the page itself; the two cards above it jump to it. The You page entry stays.
// 0.64.0 (16 Nov 2026): You tab: FIND row and WELLBEING AND INSIGHTS shortcuts at the top; Heart Watch panel: IF THE PULSE FLAG SHOWS steps (no alert rule changed); sleep goal printed with one decimal.

// 0.63.0 (16 Nov 2026): Today: the seven equal rings are back as in 0.61.0 (0.62.0's hero row removed); a DAILY OUTLOOK card for the plan sentence; KEY STATISTICS (the night monitor) moved up under it, each line with its distance from your usual. Taps and panels unchanged.
// 0.61.0 (15 Nov 2026): answer line at the top of Heart, Rest, ECG and You (a sentence in words against the person's own earlier nights, UxLogic); one status strip on every tab with a sheet of what needs attention (logger, link, old records, notifications, exact alarms, battery, Do Not Disturb access); Rest card "against your own usual" with range bars; Today opens by the time of day (the wellbeing card is "This evening" from 18:00 and sits under the rings in the daytime); specific empty states; Display settings on You (text size, brighter secondary text, bigger tap targets, UxKit).
// 0.58.0 (15 Nov 2026): the MORNING BRIEF card at the top of Today (WellbeingActivity / WellbeingModel): the main opinion counted from the last 14 days of sleep, tap for the wellbeing page. 0.57.0: Phone-buzz guard and Silence-the-phone rows on You (RingGuard / QuietPhone).
// 0.56.0 (14 Nov 2026): the seventh ring, HEART WATCH (AlertEngine / AlertWatch): usual / WATCH / CHECK from the pulse rhythm at rest, the heart rate at rest and the night signs; detail panel with what to do.
// 0.55.0 (14 Nov 2026): each ring has its own colour (DailyViews.RING_COLORS); a Quick guide link under the rings and a Quick guide row on You; plainer strap line on the sync strip ("pages not copied yet", "Strap link is on/off").
// 0.51.0 (10 Nov 2026): You > Settings > RESEARCH fold: Read strap settings (read-only) and the Lab S switch.

// 0.42.1 (5 Nov 2026): the ECG tab shows this week and the streak, with a Reminders button under it (Sync now on the Today tab is the pull; no second button). 0.39.0 (3 Nov): Settings switches "Research: averaged beat, QT and HRV from 12.8 s" and "Write reconstructed_waveform files". FILE VERSION 0.9.0 (23 Oct): the heart-first personal health app. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

/**
 * Today, Heart, ECG, Rest and You, laid out like a glass-and-glow fitness app but about the heart: live heart rate, the ECG
 * readings the strap makes, sleep with its stages, SpO2, and how resting pulse moves over the nights. Everything is computed on
 * this phone from the strap's own records. Sleep stages, recovery-style comparisons and zones are estimates and say so.
 * RESEARCH ONLY, not a medical device and not a diagnosis.
 */
public class HealthActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout content;
    private NavBar nav;
    private int tab = 0;
    private HealthModel.Data data;
    private volatile boolean loading;
    private boolean loadedOnce = false;                  // 0.13.4: the first load has finished (so "no records" is true, not just "not loaded yet")
    private TextView todayHrNum, todayHrSub; private String todayHrSubTail = "";               // 0.13.4: the Today heart-rate card follows the live frames
    private volatile List<ActivityDetector.Bout> actBouts = new ArrayList<ActivityDetector.Bout>();
    private volatile String actLine = "";          // today's activity in one line, read off the main thread
    private volatile int actMin = -1, actGoal = 240;           // 0.54.0: moving minutes today and the goal, for the ACTIVE ring (-1 = no data)
    private volatile ActivityPlus.DayStats actToday; private volatile long actStatsAt;      // 0.54.0: today's all-day figures (moving, still stretches, by the hour)
    private volatile List<TrustData.Day> trustDays = new ArrayList<TrustData.Day>(); private volatile List<String> trustIngests = new ArrayList<String>(); private volatile long trustAt;      // 0.54.0: what to trust in the last two days
    private volatile String syncRange = "", syncBattery = "";     // 0.21.0: for the sync card's explainer, read off the main thread
    private final java.util.HashSet<String> openCards = new java.util.HashSet<>();     // 0.21.0: which explainers are open (they stay open when the screen refreshes)
    static volatile int pendingTab = -1;               // 0.22.0: set by the old screens when they hand back to a tab (onNewIntent is not always called)
    private volatile String syncSub = "";           // 0.20.0: the strap line of the sync strip (pages behind, battery), read off the main thread
    private TextView syncTitleTv, syncSubTv;
    private int ringPage = -1;                       // 0.71.0: which ring has its own page open on Today (0 recovery, 1 effort, 2 active, 3 sleep, 4 stress, 5 fatigue, 6 heart watch), -1 = the Today page itself
    private int ringBackY;                            // 0.71.0: Today's scroll position, put back when the ring page closes
    private final String[] ringBig = new String[7], ringLab = new String[7], ringCapT = new String[7]; private final int[] ringCol = new int[7];      // 0.71.0: what each ring shows, kept for its page
    private LinearLayout srBox; private long srBoxAt;      // 0.71.0: the SLEEP RESEARCH section of the Rest page, kept between redraws so the scroll position survives
    private TextView liveNum, liveUnit;
    private HealthModel.Data liveData;
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (tab == 1 && liveNum != null && liveUnit != null) {
                int h = shownHr(liveData);
                liveNum.setText(h > 0 ? String.valueOf(h) : "\u2014");
                liveUnit.setText("  " + shownLabel(liveData));
            }
            applyLink();
            if (tab == 0) updateSyncStrip();                      // 0.20.0
            if (tab == 0 && todayHrNum != null && todayHrSub != null) {
                int hh = shownHr(data);
                todayHrNum.setText(hh > 0 ? hh + " bpm" : "\u2014");
                todayHrSub.setText(shownLabel(data).replace("bpm, ", "") + todayHrSubTail);
            }
            ui.postDelayed(this, 2000L);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { load(); ui.postDelayed(this, 60000L); }
    };

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        StartupClock.healthStart();              // 0.67.0
        FrameMonitor.captureTo(getExternalFilesDir(null));          // 0.25.0: raw copies of undecoded frames
        ScaleSetting.load(this);                                    // 0.54.0
        EcgScenario.load(this);                                     // 0.79.0
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new GlowBg(this), new FrameLayout.LayoutParams(-1, -1));
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        tab = Math.max(0, Math.min(4, getIntent().getIntExtra("tab", 0)));      // other screens open a given tab
        nav = new NavBar(this, tab, new NavBar.Listener() {
            @Override public void onTab(int t) { if (t == 0) ringPage = -1; tab = t; render(); }
        });
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, Math.round(dp(this, 68)), Gravity.BOTTOM);
        np.setMargins(Math.round(dp(this, 14)), 0, Math.round(dp(this, 14)), Math.round(dp(this, 18)));
        View scrim = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Paint p = new Paint();
                p.setShader(new LinearGradient(0, 0, 0, getHeight(), 0x0007090C, 0xFF07090C, Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, getWidth(), getHeight(), p);
            }
        };
        root.addView(scrim, new FrameLayout.LayoutParams(-1, Math.round(dp(this, 130)), Gravity.BOTTOM));
        root.addView(nav, np);
        setContentView(root);
        HealthSnap hs = HealthSnap.get(System.currentTimeMillis(), HealthSnap.MAX_AGE_MS);      // 0.67.0: paint from the last load at once, refresh behind it
        if (hs != null) {
            data = hs.data; loadedOnce = true;
            actBouts = hs.actBouts; actLine = hs.actLine; actMin = hs.actMin; actGoal = hs.actGoal; actToday = hs.actToday; actStatsAt = hs.actStatsAt;
            trustDays = hs.trustDays; trustIngests = hs.trustIngests; trustAt = hs.trustAt;
            syncRange = hs.syncRange; syncBattery = hs.syncBattery; syncSub = hs.syncSub;
        }
        render();
        ui.post(new Runnable() { @Override public void run() { StartupClock.healthPaint(); } });      // after the first layout pass
    }

    /** Another screen's bottom bar asks for a tab while this screen is already open. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        tab = Math.max(0, Math.min(4, i.getIntExtra("tab", tab)));
        if (nav != null) nav.setActive(tab);
        render();
    }

    @Override protected void onResume() { super.onResume(); if (pendingTab >= 0) { int pt = pendingTab; pendingTab = -1; tab = Math.max(0, Math.min(4, pt)); nav.setActive(tab); render(); } ui.removeCallbacks(tick); ui.post(tick); ui.removeCallbacks(liveTick); ui.post(liveTick); if (DayLog.enabled(this)) { DayLogService.ensure(this); MainActivity.startLinkHost(this); } }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); ui.removeCallbacks(liveTick); }

    /** The heart rate to show: a live notification from the last 90 seconds, otherwise the newest logged second. */
    private int shownHr(HealthModel.Data d) {
        LiveHr.Sample s = LiveHr.latest();
        if (s != null && System.currentTimeMillis() - s.ms < 90000L) return s.hr;
        return d == null ? 0 : d.nowHr;
    }

    private String shownLabel(HealthModel.Data d) {
        LiveHr.Sample s = LiveHr.latest();
        if (s != null && System.currentTimeMillis() - s.ms < 90000L) return "bpm, " + LiveHr.ageText(s);
        if (d != null && d.nowHr > 0 && d.nowTs > 0) return "bpm, logged " + ago(d.nowTs);
        return "bpm";
    }

    private long[] ecgSecs() {
        List<HealthModel.Reading> rs = data == null ? null : data.readings;
        if (rs == null) return new long[0];
        long[] out = new long[rs.size()];
        for (int i = 0; i < out.length; i++) out[i] = rs.get(i).timeMs / 1000L;
        return out;
    }

    private void openGraph(int rangeIdx) { HeartGraph.show(this, rangeIdx, maxHr(), ecgSecs()); }

    private SharedPreferences sp() { return getSharedPreferences("today", MODE_PRIVATE); }
    private int maxHr() { return sp().getInt("maxhr", 167); }
    private float goal() { return sp().getFloat("goal", 8.0f); }
    private String name() { return sp().getString("name", "Ame"); }

    private void load() {
        if (loading) return;
        loading = true;
        final Context app = getApplicationContext();
        final int mh = maxHr();
        final double g = goal();
        new Thread(new Runnable() {
            @Override public void run() {
                HealthModel.Data d = null;
                try { d = HealthModel.load(app, mh, g); } catch (Throwable ignored) { }
                try {
                    ActivityStore.DayData ad = ActivityStore.glanceDay(app);
                    if (ad != null && ad.r != null && ad.r.bouts != null) actBouts = new ArrayList<ActivityDetector.Bout>(ad.r.bouts);
                    actLine = (ad != null && ad.hasData && ad.r != null)
                            ? "Active " + HealthModel.hmShort(ad.r.activeMin * 60L) + (ad.r.longestBoutMin > 0 ? " \u00b7 longest " + HealthModel.hmShort(ad.r.longestBoutMin * 60L) : "") + " \u00b7 load " + Math.round(ad.r.load)
                            : "No logged data yet today";
                } catch (Throwable ignored) { }
                try {                                                    // 0.54.0: all-day figures for the ACTIVE ring and card (at most every 2 minutes)
                    ActivityStore.DayData ad2 = ActivityStore.glanceDay(app);
                    if (ad2 != null && ad2.hasData && ad2.r != null) {
                        actMin = ad2.r.activeMin; actGoal = ActivityStore.settings(app).activeGoalMin;
                        long nowMs2 = System.currentTimeMillis();
                        if (actToday == null || nowMs2 - actStatsAt > 120000L) { actToday = ActivityPlus.stats(ad2.r.epochs, ad2.dayStart, ActivityPlus.asleepMinutes(app, ad2.dayStart, ad2.dayEnd)); actStatsAt = nowMs2; }
                    } else { actMin = -1; actToday = null; }
                } catch (Throwable ignored) { }
                try {                                                    // 0.54.0: what to trust in the last two days (at most every 5 minutes)
                    long nowMs3 = System.currentTimeMillis();
                    if (nowMs3 - trustAt > 300000L) { trustDays = TrustData.days(app, 2); trustIngests = TrustData.ingests(app, 4); trustAt = nowMs3; }
                } catch (Throwable ignored) { }
                try {                                                    // 0.20.0: strap line of the sync strip, from the last condition report (every 10 minutes)
                    String cond = StrapEvents.lastDec(app, 29), pages = "", bat = "";
                    if (cond != null) {
                        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("backlog (\\d+) pages").matcher(cond), mb = java.util.regex.Pattern.compile("battery ([0-9.]+) %").matcher(cond);
                        if (mp.find()) pages = "0".equals(mp.group(1)) ? "all copied" : mp.group(1) + " pages not copied yet";      // 0.55.0: plainer than "pages behind"
                        if (mb.find()) bat = "battery " + Math.round(Double.parseDouble(mb.group(1))) + " %";
                    }
                    syncRange = StrapData.rangeLine(app);
                    String bl = StrapData.batteryLine(app);
                    syncBattery = bl == null ? "" : bl;
                    syncSub = cond == null ? "" : "Strap: " + pages + (pages.isEmpty() || bat.isEmpty() ? "" : "  \u00b7  ") + bat;
                } catch (Throwable ignored) { }
                final HealthModel.Data dd = d;
                if (dd != null) {                                          // 0.67.0: keep it for the next HealthActivity
                    HealthSnap hs = new HealthSnap();
                    hs.data = dd; hs.actBouts = actBouts; hs.actLine = actLine; hs.actMin = actMin; hs.actGoal = actGoal; hs.actToday = actToday; hs.actStatsAt = actStatsAt;
                    hs.trustDays = trustDays; hs.trustIngests = trustIngests; hs.trustAt = trustAt;
                    hs.syncRange = syncRange; hs.syncBattery = syncBattery; hs.syncSub = syncSub;
                    HealthSnap.put(hs);
                }
                ui.post(new Runnable() { @Override public void run() { loading = false; loadedOnce = true; StartupClock.healthLoaded(); if (!isFinishing()) { data = dd; if (ringPage >= 0 || tab == 3) renderKeepScroll(); else render(); } } });
            }
        }, "health-load").start();
    }

    // ------------------------------------------------------------------ 0.61.0: answer lines, status strip, own range, display settings
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(UxKit.scaled(base)); }

    private int tapMin() { return UxKit.bigTaps(this) ? dpi(54) : 0; }

    /** One sentence in words that answers the tab's question; the numbers sit inside the sentence. */
    private View answerLine(String s) {
        TextView t = tv(s, 15, INK, 400);
        t.setLineSpacing(0, 1.22f);
        t.setPadding(dpi(14), dpi(12), dpi(14), dpi(12));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x14FFFFFF); g.setCornerRadius(dp(this, 14)); g.setStroke(dpi(1), 0x1F2FA8F0);
        t.setBackground(g);
        return t;
    }

    private static int nightIdx(List<TodayModel.NightStat> all, long bedTs) {
        for (int i = all.size() - 1; i >= 0; i--) if (Math.abs(all.get(i).bed - bedTs) < 3600L) return i;
        return -1;
    }

    /** m: 0 sleep hours, 1 lowest five-minute heart rate, 2 RMSSD (same rule as Trends). The first [upto] nights, oldest first. */
    private static double[] nightVals(List<TodayModel.NightStat> all, int upto, int m) {
        double[] o = new double[Math.max(0, upto)];
        for (int i = 0; i < o.length; i++) { TodayModel.NightStat q = all.get(i); o[i] = m == 0 ? q.tstH : m == 1 ? q.hrLow5 : (q.rmssd > 0 ? q.rmssd : Double.NaN); }
        return o;
    }

    private static String nightWhen(TodayModel.NightStat q) {
        long end = q.bed + (long) (q.tstH * 3600.0);
        if (System.currentTimeMillis() / 1000L - end < 36L * 3600L) return "last night";
        return "on the night of " + new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(q.bed * 1000L));
    }

    /** The neutral range bar: the band is the middle 80 % of the earlier nights, the tick is their median, the dot is this night. */
    static final class RangeBarView extends View {
        private final double lo, hi, med, val; private final int col;
        RangeBarView(Context c, UxLogic.Range r, double val, int col) { super(c); this.lo = r.lo; this.hi = r.hi; this.med = r.med; this.val = val; this.col = col; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), pad = dp(c, 8), cy = h / 2f;
            double span = Math.max(hi - lo, Math.max(1e-6, Math.abs(hi) * 0.05));
            double a = Math.min(lo, val) - span * 0.35, b = Math.max(hi, val) + span * 0.35;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x1FFFFFFF); cv.drawRoundRect(new RectF(pad, cy - dp(c, 2), w - pad, cy + dp(c, 2)), dp(c, 2), dp(c, 2), p);
            float xl = (float) (pad + (w - 2 * pad) * (lo - a) / (b - a)), xh = (float) (pad + (w - 2 * pad) * (hi - a) / (b - a)), xm = (float) (pad + (w - 2 * pad) * (med - a) / (b - a)), xv = (float) (pad + (w - 2 * pad) * (val - a) / (b - a));
            p.setColor(0x402FA8F0); cv.drawRoundRect(new RectF(xl, cy - dp(c, 7), xh, cy + dp(c, 7)), dp(c, 7), dp(c, 7), p);
            p.setColor(0x80FFFFFF); cv.drawRect(xm - dp(c, 1), cy - dp(c, 7), xm + dp(c, 1), cy + dp(c, 7), p);
            p.setColor(0x40000000); cv.drawCircle(xv, cy, dp(c, 8), p);
            p.setColor(col); cv.drawCircle(xv, cy, dp(c, 6), p);
        }
    }

    private void rangeRow(LinearLayout c, String label, String valTxt, String rangeTxt, UxLogic.Range r, double val) {
        LinearLayout row = hrow();
        row.addView(tv(label, 13.5f, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(tv(valTxt, 14, INK, 600));
        c.addView(row, lp(-1, -2, 14));
        c.addView(new RangeBarView(this, r, val, r.pos == UxLogic.INSIDE ? TEAL : GOLD), lp(-1, dpi(24), 4));
        c.addView(tv("usual " + rangeTxt + "  ·  " + (r.pos == UxLogic.INSIDE ? "inside" : r.pos == UxLogic.BELOW ? "below" : "above") + "  ·  last " + r.n + " nights", 11.5f, MUT, 400), lp(-2, -2, 2));
    }

    /** The Rest card: this night against the same person's earlier nights. Says what is missing when there are fewer than five. */
    private View rangeCard(double tst, UxLogic.Range rT, double hr, UxLogic.Range rH, double hv, UxLogic.Range rV) {
        LinearLayout c = card(16);
        c.addView(caps("AGAINST YOUR OWN USUAL"));
        if (!rT.ok() && !rH.ok() && !rV.ok()) {
            int n = Math.max(rT.n, Math.max(rH.n, rV.n));
            c.addView(tv("Each figure is compared with your own earlier nights once there are " + UxLogic.RANGE_MIN_N + " of them with data. " + (n == 0 ? "None are logged yet." : "There " + (n == 1 ? "is " : "are ") + n + " so far.") + " Until then nothing is compared.", 12.5f, MUT, 400), lp(-2, -2, 6));
            return c;
        }
        if (rT.ok()) rangeRow(c, "Sleep", HealthModel.hm(tst), HealthModel.hm(rT.lo) + " to " + HealthModel.hm(rT.hi), rT, tst);
        if (rH.ok()) rangeRow(c, "Lowest 5-minute heart rate", UxLogic.num(hr) + " bpm", UxLogic.num(rH.lo) + " to " + UxLogic.num(rH.hi), rH, hr);
        if (rV.ok()) rangeRow(c, "HRV (RMSSD, wrist)", UxLogic.num(hv) + " ms", UxLogic.num(rV.lo) + " to " + UxLogic.num(rV.hi), rV, hv);
        TextView cap = tv("The band is the middle 80 % of your own earlier nights (the 10th to the 90th percentile of up to 30), the tick is their median. Outside the band means unusual for you, not good or bad.", 11, MUT, 400);
        cap.setLineSpacing(0, 1.2f);
        c.addView(cap, lp(-2, -2, 12));
        return c;
    }

    // ---- status strip
    private List<UxLogic.Issue> uxIssues(LinkState.Info li, long ageSec) {
        boolean on = DayLog.enabled(this);
        boolean remindersOn = false;
        try { remindersOn = WellbeingReminder.on(this) || EcgReminder.load(this).on; } catch (Throwable ignored) { }
        boolean notifBlocked = false;
        try { android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE); notifBlocked = (on || remindersOn) && nm != null && !nm.areNotificationsEnabled(); } catch (Throwable ignored) { }
        boolean exactOff = false;
        try { exactOff = remindersOn && !EcgReminder.exactOk(this); } catch (Throwable ignored) { }
        boolean battery = false;
        try { battery = !KeepAlive.batteryExempt(this); } catch (Throwable ignored) { }
        boolean dnd = false;
        try { SharedPreferences ctl = getSharedPreferences("labrador_ecg_control", MODE_PRIVATE); dnd = QuietPhone.wanted(ctl) && !QuietPhone.granted(this); } catch (Throwable ignored) { }
        boolean stale = ageSec >= 0 && LinkState.staleBanner(li.level, ageSec);
        return UxLogic.issues(on, li.level, li.title, li.detail, stale, notifBlocked, exactOff, battery, dnd);
    }

    private View statusStrip() {
        final boolean on = DayLog.enabled(this);
        final LinkState.Info li = MainActivity.linkState(this);
        long ageSec = data != null && data.day != null && data.day.newestTs > 0 ? Math.max(0L, System.currentTimeMillis() / 1000L - data.day.newestTs) : -1L;
        final List<UxLogic.Issue> is = uxIssues(li, ageSec);
        LinearLayout r = hrow();
        r.setPadding(0, dpi(2), 0, dpi(12));
        TextView t = tv(UxLogic.statusLine(on, li.level, ageSec), 11.5f, MUT, 400);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!is.isEmpty()) r.addView(tv(is.size() + " to fix ›", 11.5f, GOLD, 600));
        r.setMinimumHeight(Math.max(dpi(30), tapMin()));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showIssues(is, on); } });
        return r;
    }

    private void showIssues(List<UxLogic.Issue> is, boolean loggerOn) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        if (is.isEmpty()) {
            b.setTitle("Nothing needs your attention").setMessage("The strap link, the day logger, notifications, alarms and battery settings are all as the app needs them. The strap stores its records and sends them at each history pull, so the last record is normally a few minutes to half an hour old.");
        } else {
            StringBuilder m = new StringBuilder();
            boolean needLogger = false, needNotif = false;
            for (UxLogic.Issue q : is) {
                m.append("• ").append(q.title).append("\n  ").append(q.detail).append("\n\n");
                if ("logger".equals(q.code)) needLogger = true;
                if ("notif".equals(q.code)) needNotif = true;
            }
            b.setTitle(is.size() == 1 ? "One thing to fix" : is.size() + " things to fix").setMessage(m.toString().trim());
            if (needLogger) b.setPositiveButton("Turn on the day logger", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { turnOnLogger(); } });
            else if (needNotif) b.setPositiveButton("Allow notifications", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { askNotify(); } });
            b.setNeutralButton("App settings", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { }
            } });
        }
        b.setNegativeButton("Close", null);
        b.show();
    }

    /** Rebuilds the tab and puts the scroll position back (the You tab is long; a setting must not throw the person to the top). */
    private void renderKeepScroll() {
        int y = 0;
        try { View c0 = content.getChildAt(0); if (c0 instanceof ScrollView) y = c0.getScrollY(); } catch (Throwable ignored) { }
        render();
        final int fy = y;
        content.post(new Runnable() { @Override public void run() { try { View c0 = content.getChildAt(0); if (c0 instanceof ScrollView) c0.scrollTo(0, fy); } catch (Throwable ignored) { } } });
    }

    // ---- display settings (You tab)
    private View uxToggle(String label, final String key) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        final SharedPreferences up = UxKit.sp(this);
        final Toggle t = new Toggle(this, up.getBoolean(key, false));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !up.getBoolean(key, false); up.edit().putBoolean(key, on).apply(); t.set(on); renderKeepScroll(); } });
        r.addView(t, new LinearLayout.LayoutParams(dpi(46), dpi(28)));
        r.setMinimumHeight(dpi(52));
        return r;
    }

    // ------------------------------------------------------------------ 0.67.0: headers and drop-down groups on the You page
    private static final class NavG { String section, key; LinearLayout card, body; TextView mark; boolean open; }
    private final java.util.ArrayList<NavG> navGs = new java.util.ArrayList<>();

    private String navCsv() { return sp().getString("nav_open", ""); }

    private void applyNav(NavG g) {
        g.body.setVisibility(g.open ? View.VISIBLE : View.GONE);
        g.mark.setText(g.open ? "\u25B4" : "\u25BE");
    }

    private void setNav(NavG g, boolean open) {
        g.open = open;
        applyNav(g);
        sp().edit().putString("nav_open", NavGroups.set(navCsv(), g.key, open)).apply();
    }

    private void setAllNav(String section, boolean open) {
        java.util.ArrayList<String> ks = new java.util.ArrayList<>();
        for (NavG g : navGs) if (g.section.equals(section)) { g.open = open; applyNav(g); ks.add(g.key); }
        sp().edit().putString("nav_open", NavGroups.setAll(navCsv(), ks, open)).apply();
    }

    /** A card whose header (title, one-line hint, arrow) opens and closes its body; the state is remembered per key. Add rows to g.body. */
    private NavG navGroup(String section, final String key, String title, String hint) {
        final NavG g = new NavG();
        g.section = section; g.key = key;
        g.card = card(0);
        LinearLayout hd = hrow();
        hd.setPadding(dpi(16), dpi(14), dpi(16), dpi(14));
        hd.setMinimumHeight(Math.max(dpi(56), tapMin()));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(tv(title, 15, INK, 600));
        if (hint != null && !hint.isEmpty()) { TextView h = tv(hint, 11.5f, MUT, 400); h.setPadding(0, dpi(3), 0, 0); tx.addView(h); }
        hd.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        g.mark = tv("\u25BE", 16, BLUE, 600);
        g.mark.setPadding(dpi(10), 0, 0, 0);
        hd.addView(g.mark);
        g.body = new LinearLayout(this);
        g.body.setOrientation(LinearLayout.VERTICAL);
        g.body.setPadding(dpi(16), 0, dpi(16), dpi(14));
        g.card.addView(hd);
        g.card.addView(g.body);
        g.open = NavGroups.isOpen(navCsv(), key);
        applyNav(g);
        hd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setNav(g, !g.open); } });
        navGs.add(g);
        return g;
    }

    /** Opens a group (if closed) and scrolls the page to it. */
    private void goToNav(final NavG g) {
        if (!g.open) setNav(g, true);
        if (lastScroll != null) lastScroll.post(new Runnable() { @Override public void run() { lastScroll.smoothScrollTo(0, Math.max(0, g.card.getTop() - dpi(8))); } });
    }

    /** A section title; with [section] it also carries "Open all" and "Close all" for that section's groups. */
    private View sectionHead(String title, final String section) {
        LinearLayout r = hrow();
        TextView t = caps(title);
        t.setTextColor(BLUE);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (section != null) {
            TextView o = tv("Open all", 12, BLUE, 600);
            o.setPadding(dpi(10), dpi(8), dpi(10), dpi(8));
            o.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setAllNav(section, true); } });
            TextView c = tv("Close all", 12, BLUE, 600);
            c.setPadding(dpi(10), dpi(8), 0, dpi(8));
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setAllNav(section, false); } });
            r.addView(o);
            r.addView(c);
        }
        return r;
    }

    private View displayCard() {
        NavG dg = navGroup("settings", "display", "Display", "Text size, brighter text, bigger tap targets");      // 0.67.0: a drop-down group like the rest
        LinearLayout c = dg.body;
        final SharedPreferences up = UxKit.sp(this);
        final TextView sv = tv(UxLogic.stepName(UxKit.textStep(this)), 14, BLUE, 600);
        c.addView(valueRow("Text size", sv, new View.OnClickListener() { @Override public void onClick(View v) {
            int st = (UxKit.textStep(HealthActivity.this) + 1) % UxLogic.STEPS;
            up.edit().putInt("text_step", st).commit();
            pendingTab = 4;                                   // come back to this tab after the screen is rebuilt at the new size
            recreate();
        } }), lp(-1, dpi(52), 6));
        c.addView(uxToggle("Brighter secondary text", "contrast"), lp(-1, dpi(52), 0));
        c.addView(uxToggle("Bigger tap targets", "big_taps"), lp(-1, dpi(52), 0));
        TextView n = tv("Text size applies to Today, Heart, ECG list, Rest, You and the wellbeing page. The live ECG reading screen keeps its own sizes.", 11, MUT, 400);
        n.setLineSpacing(0, 1.2f);
        c.addView(n, lp(-2, -2, 6));
        return dg.card;
    }

    // ------------------------------------------------------------------ small builders
    private int dpi(float v) { return Math.round(dp(this, v)); }

    private TextView tv(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(UxKit.muted(this, color));
        t.setTypeface(font(this, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caps(String s) {
        TextView t = tv(s, 11, MUT, 400);
        t.setLetterSpacing(0.16f);
        return t;
    }

    /** 0.75.1: a caps heading with space above it (caps() alone sits hard against the paragraph before it). */
    private void head(LinearLayout c, String s) { c.addView(caps(s), lp(-2, -2, 16)); }

    private LinearLayout.LayoutParams lp(int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dpi(topDp);
        return p;
    }

    private LinearLayout card(float padDp) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(glass(this, 22));
        c.setPadding(dpi(padDp), dpi(padDp), dpi(padDp), dpi(padDp));
        return c;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private View spacer() { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f)); return v; }

    private TextView chip(String s, int dot) {
        TextView t = tv(s, 12, INK, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(13), dpi(UxKit.bigTaps(this) ? 14 : 8), dpi(13), dpi(UxKit.bigTaps(this) ? 14 : 8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 17)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        if (dot != 0) {
            t.setText("\u25CF  " + s);
        }
        return t;
    }

    private LinearLayout screen() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(18), dpi(40), dpi(18), dpi(120));
        return col;
    }

    private ScrollView lastScroll;      // 0.64.0: the You tab's Find row scrolls to a card

    private View wrap(LinearLayout col) {
        ScrollView sv = new ScrollView(this); lastScroll = sv;
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private View head(String greet, String title, View right) {
        LinearLayout r = hrow();
        r.setGravity(Gravity.TOP);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(tv(greet, 13, MUT, 400));
        TextView t = tv(title, 28, INK, 400);
        t.setPadding(0, dpi(6), 0, 0);
        l.addView(t);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(right != null ? right : linkChip());   // 0.13.3: the strap link state is on every tab
        return r;
    }

    private View rowCard(String title, String sub, View.OnClickListener click) {
        LinearLayout c = card(15);
        c.addView(tv(title, 13.5f, INK, 400));
        TextView s = tv(sub, 12.5f, MUT, 400);
        s.setPadding(0, dpi(3), 0, 0);
        c.addView(s);
        if (click != null) c.setOnClickListener(click);
        return c;
    }

    /** 0.58.0: the Morning brief card at the top of Today: the page's main opinion (counted from the last 14 days of sleep) and the ring word; a tap opens the wellbeing page. */
    private View wellbeingCard(final int slot) {
        LinearLayout c = card(16);
        LinearLayout row = hrow();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(caps(slot == 0 ? "MORNING BRIEF" : slot == 1 ? "WELLBEING" : "THIS EVENING"));
        final TextView head = tv("Reading your last nights...", 17, INK, 400);
        head.setPadding(0, dpi(8), 0, 0);
        l.addView(head);
        final String dflt = slot == 2 ? "Tap for your three good things and the wind-down" : "Tap for your morning page";
        final TextView sub = tv(dflt, 12, MUT, 400);
        sub.setPadding(0, dpi(6), 0, 0);
        l.addView(sub);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView word = tv("", 26, TEAL, 200);
        word.setPadding(dpi(10), 0, 0, 0);
        row.addView(word);
        c.addView(row);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { startActivity(new Intent(HealthActivity.this, WellbeingActivity.class).putExtra("focus_evening", slot == 2)); } catch (Throwable ignored) { } }
        });
        final Context app = getApplicationContext();
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                final WellbeingModel.Out o = WellbeingActivity.load(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (o == null) { head.setText("Your morning page"); sub.setText("Tap to open"); return; }
                        head.setText(o.headline);
                        word.setText(o.enough && !"Gathering".equals(o.word) ? o.word : "");
                        sub.setText(o.enough ? (slot != 2 && o.planOn && o.planChecked ? o.planHead : dflt) : o.body);
                    }
                });
            }
        });
        th.setDaemon(true);
        th.start();
        return c;
    }

    private View pillButton(String text, int color, int fg, View.OnClickListener l) {
        TextView t = tv(text, 14, fg, 600);
        t.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(this, 21));
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    // ------------------------------------------------------------------ import a pull zip
    private String importNote = "A reinstall empties the app. Import the zip you exported (history, ECG reports, strips) to bring those nights back.";
    private static final int REQ_ZIP = 77;

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, REQ_ZIP);
    }

    @Override protected void onActivityResult(int req, int res, final Intent data2) {
        super.onActivityResult(req, res, data2);
        if (req == StorageScreen.REQ_RESTORE) { StorageScreen.onPicked(this, res, data2); return; }      // 0.36.0
        if (req != REQ_ZIP || res != RESULT_OK || data2 == null || data2.getData() == null) return;
        final android.net.Uri uri = data2.getData();
        final Context app = getApplicationContext();
        importNote = "Importing\u2026";
        render();
        new Thread(new Runnable() {
            @Override public void run() {
                final String msg = importZip(app, uri);
                ui.post(new Runnable() { @Override public void run() { importNote = msg; render(); } });
                long[] waits = {3000L, 7000L, 12000L, 20000L, 30000L};          // the history is read in the background
                for (long w : waits) {
                    try { Thread.sleep(w); } catch (InterruptedException ignored) { }
                    ui.post(new Runnable() { @Override public void run() { load(); } });
                }
            }
        }, "health-import").start();
    }

    // ------------------------------------------------------------------ export summary (small CSV files, the raw files stay on the phone)
    private volatile boolean exporting;

    private void exportSummary(final int days) {
        if (exporting) return;
        exporting = true;
        importNote = "Writing the summary files\u2026 (about a minute)";
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                SummaryExport.Result r;
                try {
                    r = SummaryExport.run(app, days);
                } catch (Throwable t) {
                    r = new SummaryExport.Result();
                    r.note = "Export stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                final SummaryExport.Result res = r;
                ui.post(new Runnable() {
                    @Override public void run() {
                        exporting = false;
                        importNote = res.note;
                        render();
                        if (!res.uris.isEmpty()) {
                            try {
                                Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                                i.setType("text/csv");
                                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, res.uris);
                                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(i, "Send the summary files"));
                            } catch (Throwable ignored) { }
                        }
                    }
                });
            }
        }, "summary-export").start();
    }

    /** Copy the history files, ECG reports and strips out of a pull zip into the app's folder, then let the day logger read them. */
    private static String importZip(Context app, android.net.Uri uri) {
        java.io.File dir = app.getExternalFilesDir(null);
        if (dir == null) return "No storage folder available.";
        int bins = 0, reports = 0, other = 0, skipped = 0;
        try {
            java.io.InputStream raw = app.getContentResolver().openInputStream(uri);
            if (raw == null) return "Could not open that file.";
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw));
            try {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    n = n.substring(n.lastIndexOf('/') + 1);
                    boolean bin = n.startsWith("historical_bin_") && n.endsWith(".bin");
                    boolean rep = n.startsWith("ecg_report_") && n.endsWith(".html");
                    boolean oth = (n.startsWith("ecg_strip_") && n.endsWith(".png")) || (n.startsWith("labrador_analysis_") && n.endsWith(".txt")) || (n.startsWith("realtime_ecg_bin_") && n.endsWith(".bin"))
                            || (n.startsWith("labrador_bin_") && n.endsWith(".bin")) || (n.startsWith("reconstructed_waveform_") && n.endsWith(".csv"));
                    if (!bin && !rep && !oth) continue;
                    java.io.File out = new java.io.File(dir, n);
                    if (out.exists() && out.length() > 0) { skipped++; continue; }
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                    try {
                        int k;
                        while ((k = zin.read(buf)) > 0) fo.write(buf, 0, k);
                    } finally {
                        fo.close();
                    }
                    // the day logger skips files touched in the last 90 s ("still being written"), so date these as old
                    //noinspection ResultOfMethodCallIgnored
                    out.setLastModified(System.currentTimeMillis() - 5L * 60L * 1000L);
                    if (bin) bins++; else if (rep) reports++; else other++;
                }
            } finally {
                zin.close();
            }
        } catch (Throwable t) {
            return "Import stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        DayLog.scanNewFiles(app);
        return "Imported " + bins + " history files, " + reports + " ECG reports and " + other + " other files" + (skipped > 0 ? " (" + skipped + " were already here)" : "") + ". Reading the history now; the screens fill in over the next minute.";
    }

    /** Bring the already-running main screen forward (never a second copy: it owns the strap link). */
    private Intent oldScreens() { Intent i = new Intent(this, MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); i.putExtra("stay", true); return i; }

    private void startEcg() { NewLookActivity.pendingEcg = true; Intent i = oldScreens(); i.putExtra("start_ecg", true); startActivity(i); }

    private void openReport(java.io.File f) { startActivity(new Intent(this, ReportActivity.class).putExtra("path", f.getAbsolutePath())); }        // 0.19.2: its own process, so the logger process never loads WebView

    @Override public void onBackPressed() { if (tab == 0 && ringPage >= 0) { closeRingPage(); return; } moveTaskToBack(true); }

    private void go(int t) { tab = t; nav.setActive(t); render(); }

    // ------------------------------------------------------------------ render
    private void render() {
        if (tab != 0) ringPage = -1;
        content.removeAllViews();
        View v;
        switch (tab) {
            case 1: v = heartTab(); break;
            case 2: v = ecgTab(); break;
            case 3: v = restTab(); break;
            case 4: v = youTab(); break;
            default: v = ringPage >= 0 ? ringPageView(ringPage) : todayTab();
        }
        try {                                                                  // 0.61.0: one status strip at the top of every tab
            if (v instanceof ScrollView && ((ScrollView) v).getChildCount() > 0 && ((ScrollView) v).getChildAt(0) instanceof LinearLayout)
                ((LinearLayout) ((ScrollView) v).getChildAt(0)).addView(statusStrip(), 0, new LinearLayout.LayoutParams(-1, -2));
        } catch (Throwable ignored) { }
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private static String ago(long unix) {
        long s = System.currentTimeMillis() / 1000L - unix;
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        if (s < 172800) return String.format(Locale.UK, "%.0f h ago", s / 3600.0);
        return (s / 86400) + " days ago";
    }

    private static String hhmm(long unix) { return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(unix * 1000L)); }

    private int screenW() { return getResources().getDisplayMetrics().widthPixels - dpi(36); }

    // ------------------------------------------------------------------ TODAY
    // ---- 0.13.3: the strap link, always visible
    private TextView linkChipRef;

    private View linkChip() {
        LinkState.Info li = MainActivity.linkState(this);
        TextView t = chip(li.chip, 1);
        t.setTextColor(li.level == LinkState.GOOD ? GREEN : li.level == LinkState.WARN ? GOLD : ROSE);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showLinkDialog(); } });
        linkChipRef = t;
        return t;
    }

    private void applyLink() {
        TextView t = linkChipRef;
        if (t == null) return;
        LinkState.Info li = MainActivity.linkState(this);
        t.setText("\u25CF  " + li.chip);
        t.setTextColor(li.level == LinkState.GOOD ? GREEN : li.level == LinkState.WARN ? GOLD : ROSE);
    }

    private void showLinkDialog() {
        final LinkState.Info li = MainActivity.linkState(this);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle(li.title).setMessage(li.detail);
        if (li.action == LinkState.ACT_TURN_ON || li.action == LinkState.ACT_START)
            b.setPositiveButton(li.action == LinkState.ACT_TURN_ON ? "Turn on the day logger" : "Start the link", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { turnOnLogger(); }
            });
        else if (li.action == LinkState.ACT_RECONNECT)
            b.setPositiveButton("Reconnect now", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { MainActivity.requestReconnect(HealthActivity.this); }
            });
        if (CopyB.B)
            b.setNeutralButton("Change strap", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) { StrapLock.choose(HealthActivity.this, new Runnable() { @Override public void run() { render(); } }); }
            });
        b.setNegativeButton("Close", null);
        b.show();
    }

    /** The two ways the all-day log silently stops: the day logger is switched off, or the strap has gone quiet. Null when all is well. */
    private View loggerBanner(long ageSec) {
        if (CopyB.B && !StrapLock.has(this)) {      // 0.70.0: copy B has no strap until the owner picks one
            LinearLayout c = card(16);
            c.addView(caps("NO STRAP CHOSEN"));
            TextView t = tv("This copy of the app has no strap yet. Put the strap next to the phone and tap Choose strap; pick it from the list (the strongest signal is the nearest).", 13, INK, 400);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dpi(8), 0, dpi(10));
            c.addView(t);
            c.addView(pillButton("Choose strap", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { StrapLock.choose(HealthActivity.this, new Runnable() { @Override public void run() { render(); } }); } }), lp(-1, dpi(42), 0));
            return c;
        }
        if (!DayLog.enabled(this)) {
            LinearLayout c = card(16);
            c.addView(caps("DAY LOGGER IS OFF"));
            TextView t = tv("Nothing is being recorded, so recovery, sleep, trends and the morning summary stop updating. Switch it on and the app connects to the strap in the background.", 13, INK, 400);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dpi(8), 0, dpi(10));
            c.addView(t);
            c.addView(pillButton("Turn on the day logger", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { turnOnLogger(); } }), lp(-1, dpi(42), 0));
            return c;
        }
        LinkState.Info li = MainActivity.linkState(this);
        if (LinkState.staleBanner(li.level, ageSec)) {
            LinearLayout c = card(16);
            c.addView(caps(li.level == LinkState.GOOD ? "NO HISTORY PULLED FOR A WHILE" : "NO NEW DATA FROM THE STRAP"));
            TextView t = tv(li.level == LinkState.GOOD
                    ? "The strap is connected, but the newest stored second is " + StrapData.ageText(ageSec) + " old. Use \"Pull now\" in the day-logger notification."
                    : "The newest second is " + StrapData.ageText(ageSec) + " old and the strap is not connected (" + li.chip.toLowerCase(java.util.Locale.UK) + "). Check that the strap is on your wrist and not on the charger, and that it has battery. The app keeps trying on its own. Tap the chip at the top for details.", 13, INK, 400);
            t.setLineSpacing(0, 1.25f);
            t.setPadding(0, dpi(8), 0, 0);
            c.addView(t);
            return c;
        }
        return null;
    }

    private void turnOnLogger() {
        DayLog.setEnabled(this, true);
        DayLogService.start(this);
        MainActivity.startLinkHost(this);      // 0.15.0: invisible, no screen change
        android.widget.Toast.makeText(this, "Day logger on. It connects to the strap in the background.", android.widget.Toast.LENGTH_LONG).show();
        render();
    }

    // ------------------------------------------------------------------ 0.20.0: sync strip and the opened rings on Today
    // ------------------------------------------------------------------ 0.21.0: explainers on every card, graph and ring
    interface Filler { void fill(LinearLayout panel); }

    /** Adds a "More about this" strip to a card; tapping opens a panel built on demand. The open state survives a refresh. */
    private void expandable(final String key, final LinearLayout card, final Filler f) { expandable(key, card, f, "More about this"); }

    private void expandable(final String key, final LinearLayout card, final Filler f, final String label) {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        final TextView bar = tv("\u25BE  " + label, 12, BLUE, 600);
        bar.setPadding(0, dpi(12), 0, dpi(2));
        card.addView(bar);
        card.addView(panel, lp(-1, -2, 4));
        final Runnable open = new Runnable() { @Override public void run() {
            panel.removeAllViews();
            View sep = new View(HealthActivity.this);
            sep.setBackgroundColor(0x14FFFFFF);
            panel.addView(sep, new LinearLayout.LayoutParams(-1, 1));
            try { f.fill(panel); } catch (Throwable t) { panel.addView(tv("Could not build this panel.", 12, MUT, 400)); }
            panel.setVisibility(View.VISIBLE);
            bar.setText("\u25B4  Hide");
        } };
        bar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (openCards.contains(key)) { openCards.remove(key); panel.removeAllViews(); panel.setVisibility(View.GONE); bar.setText("\u25BE  " + label); }
            else { openCards.add(key); open.run(); }
        } });
        if (openCards.contains(key)) open.run();
    }

    /** Heading and paragraph rows from Explain. */
    private void rows(LinearLayout p, String[][] r) {
        for (String[] x : r) {
            p.addView(caps(x[0]), lp(-2, -2, 12));
            TextView t = tv(x[1], 12.5f, INK, 400);
            t.setLineSpacing(0, 1.25f);
            p.addView(t, lp(-2, -2, 3));
        }
    }

    private void yours(LinearLayout p) { p.addView(caps("YOUR NUMBERS"), lp(-2, -2, 12)); }

    private static final String[] ZONE_NAME = {"Resting", "Easy", "Steady", "Hard", "All out"};

    private void fillHrNow(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null) return;
        TodayModel.Day day = d.day;
        int mh = maxHr(), hr = shownHr(d);
        yours(p);
        kv(p, "Now", hr > 0 ? hr + " bpm  (" + ZONE_NAME[HealthModel.zoneOfHr(hr, mh)] + ")" : "\u2014");
        if (d.hr24Max > 0) kv(p, "Last 24 hours", d.hr24Min + " low, " + Math.round(d.hr24Mean) + " average, " + d.hr24Max + " high");
        if (!Double.isNaN(day.restingHr)) kv(p, "Lowest five minutes", Math.round(day.restingHr) + " bpm");
        int[] ze = HealthModel.zoneEdges(mh);
        kv(p, "Zone edges (maximum " + mh + ")", ze[0] + " / " + ze[1] + " / " + ze[2] + " / " + ze[3] + " bpm");
        rows(p, Explain.heartNow(mh));
    }

    private void fillSyncPanel(LinearLayout p) {
        yours(p);
        kv(p, "Last pull", syncTitleText(data == null ? null : data.day));
        if (!syncSub.isEmpty()) kv(p, "Strap", syncSub.replace("Strap: ", ""));
        if (!syncBattery.isEmpty()) kv(p, "Battery", syncBattery);
        if (!syncRange.isEmpty()) kv(p, "Out of range (24 h)", syncRange);
        kv(p, "Logger", StrapData.loggerLine(this));
        rows(p, Explain.sync());
    }

    private void fillActivityPanel(LinearLayout p) {
        HealthModel.Data d = data;
        yours(p);
        ActivityPlus.DayStats s = actToday;
        if (s != null && s.awakeMin >= 30) {
            kv(p, "Moving", ActivityPlus.hm(s.movingMin));
            kv(p, "Still while awake", ActivityPlus.hm(s.stillMin));
            kv(p, "Longest still stretch", s.longestStillMin == 0 ? "none" : ActivityPlus.hm(s.longestStillMin));
            kv(p, "Still stretches of an hour or more", String.valueOf(s.stillStretches60));
            kv(p, "Active hours (10 min or more moving)", String.valueOf(s.activeHours));
        }
        if (d != null && d.day != null) {
            kv(p, "Steps today", String.valueOf(d.day.steps));
            kv(p, "Walking / running (strap's class)", d.day.walkMin + " / " + d.day.runMin + " min");
        }
        note(p, "Tap the card for the whole Activity screen: the hour-by-day map, your usual days and the rhythm of your active minutes.");
        rows(p, Explain.activity());
    }

    /** 0.54.0: the ACTIVE ring opened. */
    private void fillActiveRing(LinearLayout c) {
        c.addView(caps("ACTIVE: HOW IT IS WORKED OUT"));
        ActivityPlus.DayStats s = actToday;
        if (actMin < 0 || s == null) { note(c, "Not shown yet. It needs today's per-second records from the day logger."); return; }
        kv(c, "Moving today", ActivityPlus.hm(actMin));
        kv(c, "Goal (set on the Activity screen)", ActivityPlus.hm(Math.max(30, actGoal)));
        kv(c, "Still while awake", ActivityPlus.hm(s.stillMin) + (Double.isNaN(s.stillShare()) ? "" : String.format(Locale.UK, " (%.0f %%)", 100.0 * s.stillShare())));
        kv(c, "Longest still stretch", s.longestStillMin == 0 ? "none" : ActivityPlus.hm(s.longestStillMin));
        kv(c, "Active hours (10 min or more moving)", String.valueOf(s.activeHours));
        c.addView(new ActivityPlus.HourBars(this, s.movingByHour, 60, TEAL), lp(-1, dpi(90), 10));
        note(c, "Moving minutes by hour, from 04:00.");
        note(c, "A minute counts as moving when your heart rate is above 10 % of the way from resting to maximum and your wrist is moving, or when your wrist is clearly moving. Sleep is left out. The ring fills towards the goal and counts the whole day, not workouts.");
        c.addView(pillButton("Open Activity", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, ActivityActivity.class)); } }), lp(-1, dpi(42), 12));
    }

    private void fillJournalPanel(LinearLayout p) {
        yours(p);
        int n = 0;
        try {
            long from = TodayModel.dayStartOf(System.currentTimeMillis() / 1000L) * 1000L;
            android.database.Cursor q = DayLog.db(this).rawQuery("SELECT ms, text FROM events WHERE kind = 'marker' AND ms >= ? ORDER BY ms DESC LIMIT 20", new String[] {String.valueOf(from)});
            while (q.moveToNext()) { kv(p, new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(q.getLong(0))), String.valueOf(q.getString(1))); n++; }
            q.close();
        } catch (Throwable ignored) { }
        if (n == 0) note(p, "No notes logged since 04:00.");
        rows(p, Explain.journal());
    }

    private void fillEcgPanel(LinearLayout p) {
        HealthModel.Data d = data;
        yours(p);
        int k = 0;
        if (d != null) for (HealthModel.Reading r : d.readings) {
            kv(p, r.when, ("-".equals(r.hr) ? "" : r.hr + " bpm") + ("-".equals(r.hrv) ? "" : "  \u00b7  HRV " + r.hrv + " ms") + ("-".equals(r.rhythm) ? "" : "  \u00b7  " + r.rhythm) + ("-".equals(r.grade) ? "" : "  \u00b7  grade " + r.grade));
            if (++k >= 5) break;
        }
        if (k == 0) note(p, "No reading saved yet.");
        rows(p, Explain.ecgReadings());
    }

    private void fillH24Panel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null) return;
        yours(p);
        if (d.hr24Max > 0) { kv(p, "Lowest", d.hr24Min + " bpm"); kv(p, "Average", Math.round(d.hr24Mean) + " bpm"); kv(p, "Highest", d.hr24Max + " bpm"); }
        if (!Double.isNaN(d.day.restingHr)) kv(p, "Lowest five minutes", Math.round(d.day.restingHr) + " bpm");
        for (int i = 0; i < 5; i++) if (d.zoneSec[i] > 0) kv(p, ZONE_NAME[i], HealthModel.hm(d.zoneSec[i] / 3600.0));
        rows(p, Explain.hrChart());
    }

    private void fillZonesPanel(LinearLayout p) {
        int mh = maxHr();
        int[] ze = HealthModel.zoneEdges(mh);
        yours(p);
        kv(p, "Maximum heart rate", mh + " bpm");
        kv(p, "Resting", "under " + ze[0] + " bpm");
        kv(p, "Easy", ze[0] + " to " + ze[1] + " bpm");
        kv(p, "Steady", ze[1] + " to " + ze[2] + " bpm");
        kv(p, "Hard", ze[2] + " to " + ze[3] + " bpm");
        kv(p, "All out", ze[3] + " bpm and over");
        rows(p, Explain.zones());
    }

    private void fillB2BPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        yours(p);
        for (HealthModel.Reading r : d.readings) if (!"-".equals(r.hrv)) { kv(p, "Latest ECG HRV", r.hrv + " ms  (" + r.when + ")"); break; }
        double lastOpt = Double.NaN;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) lastOpt = ns.rmssd;
        if (!Double.isNaN(lastOpt)) kv(p, "Last night, optical", Math.round(lastOpt) + " ms");
        rows(p, Explain.beatToBeat());
    }

    private void fillOverTimePanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        yours(p);
        int shown = 0;
        for (int i = d.nights.size() - 1; i >= 0 && shown < 7; i--) {
            TodayModel.NightStat ns = d.nights.get(i);
            if (Double.isNaN(ns.hrLow5)) continue;
            kv(p, new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(ns.bed * 1000L)), Math.round(ns.hrLow5) + " bpm" + (Double.isNaN(ns.rmssd) ? "" : "  \u00b7  HRV " + Math.round(ns.rmssd) + " ms"));
            shown++;
        }
        if (shown == 0) note(p, "No night with a resting heart rate yet.");
        rows(p, Explain.heartOverTime());
    }

    private void fillSleepWeekPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        double goal = goal();
        yours(p);
        for (int i = d.lastSeven.length - 1; i >= 0; i--) {
            if (Double.isNaN(d.lastSeven[i])) continue;
            double diff = d.lastSeven[i] - goal;
            kv(p, new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(d.lastSevenBed[i] * 1000L)), HealthModel.hm(d.lastSeven[i]) + "  (" + (diff < 0 ? HealthModel.hm(-diff) + " under" : HealthModel.hm(diff) + " over") + ")");
        }
        rows(p, Explain.sleepWeek());
    }

    private void fillShapePanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        long tot = 0;
        for (long s : d.stageSecs) tot += s;
        yours(p);
        if (tot > 0) {
            String[] nm = {"Awake", "REM", "Light", "Deep"};
            int[] idx = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            for (int i = 0; i < 4; i++) kv(p, nm[i], HealthModel.hmShort(d.stageSecs[idx[i]]) + "  (" + Math.round(100.0 * d.stageSecs[idx[i]] / tot) + " %)");
        }
        rows(p, Explain.sleepShape());
    }

    private void fillWholeNightPanel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null) return;
        int sure = 0, all = 0;
        for (int i = 0; i < d.binStage.length; i++) if (d.binStage[i] >= 0) { all++; if (d.binSure[i]) sure++; }
        yours(p);
        kv(p, "Five-minute blocks", String.valueOf(all));
        kv(p, "Where two methods agree", all == 0 ? "\u2014" : Math.round(100.0 * sure / all) + " %");
        rows(p, Explain.sleepNight());
    }

    private void fillSpo2Panel(LinearLayout p) {
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.spo2 == null) return;
        yours(p);
        kv(p, "Night value", d.day.spo2.median + " %");
        kv(p, "Windows used", String.valueOf(d.day.spo2.validWindows));
        kv(p, "Lowest / highest window", d.day.spo2.min + " / " + d.day.spo2.max + " %");
        kv(p, "Windows below 94 %", String.valueOf(d.day.spo2.below94));
        rows(p, Explain.spo2());
    }

    private void fillMonitorPanel(LinearLayout p, DailyViews.Metric mt) {
        int st = mt.status();
        kv(p, "Last night", mt.valueText());
        kv(p, "Your usual", mt.rangeText());
        kv(p, "Against your usual", st < 0 ? "not enough nights yet" : st == 0 ? "inside the usual range" : st == 1 ? "a little outside (worth noting)" : "well outside");
        rows(p, Explain.monitor(mt.name));
    }

    private LinearLayout explainerCard(String title, String sub) {
        LinearLayout c = card(14);
        c.addView(caps(title));
        c.addView(tv(sub, 11.5f, MUT, 400), lp(-2, -2, 2));
        return c;
    }

    private String syncTitleText(TodayModel.Day day) {
        String ps = MainActivity.pullStatus();
        if (ps != null) return "Syncing\u2026 " + ps;
        long lp = DayLog.lastPullMs(this);
        if (lp > 0) {
            long s = Math.max(0L, (System.currentTimeMillis() - lp) / 1000L);
            return "Synced " + (s < 90 ? s + " s" : s < 5400 ? (s / 60) + " min" : String.format(Locale.UK, "%.1f h", s / 3600.0)) + " ago";
        }
        return day != null && day.newestTs > 0 ? "Latest record " + ago(day.newestTs) : "Not synced yet";
    }

    private View syncStrip(final TodayModel.Day day) {
        LinearLayout c = card(12);
        LinearLayout r = hrow();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        syncTitleTv = tv(syncTitleText(day), 14, INK, 600);
        syncSubTv = tv(syncSub.isEmpty() ? (MainActivity.linkRunning() ? "Strap link is on" : "Strap link is off: Sync now turns it on") : syncSub, 11, MUT, 400);
        left.addView(syncTitleTv);
        left.addView(syncSubTv, lp(-2, -2, 2));
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView btn = HeartGraph.pill(this, "Sync now", 0x1A2EE6A8, 0xFF2EE6A8);
        btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            PullNow.request(HealthActivity.this);      // 0.42.2: opens the link first if needed and says what happened
            if (syncTitleTv != null) syncTitleTv.setText("Syncing\u2026");
        } });
        r.addView(btn);
        c.addView(r);
        expandable("t_sync", c, new Filler() { @Override public void fill(LinearLayout p) { fillSyncPanel(p); } });
        return c;
    }

    private void updateSyncStrip() {
        try {
            if (syncTitleTv == null || syncSubTv == null) return;
            syncTitleTv.setText(syncTitleText(data == null ? null : data.day));
            if (!syncSub.isEmpty()) syncSubTv.setText(syncSub);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ 0.71.0: a ring has its own page
    private static final String[] RING_NAMES = {"Recovery", "Effort", "Active", "Sleep", "Stress", "Fatigue", "Heart Watch"};

    private void openRingPage(int i) {
        try { View c0 = content.getChildAt(0); ringBackY = c0 instanceof ScrollView ? c0.getScrollY() : 0; } catch (Throwable e) { ringBackY = 0; }
        ringPage = Math.max(0, Math.min(6, i));
        render();
    }

    /** Opens a ring's own page from anywhere: the Heart Watch row on Today, the Find row on You. */
    private void showRing(int i) {
        if (tab != 0) { tab = 0; if (nav != null) nav.setActive(0); ringBackY = 0; ringPage = Math.max(0, Math.min(6, i)); render(); }
        else openRingPage(i);
    }

    private void closeRingPage() {
        if (ringPage < 0) return;
        ringPage = -1;
        render();
        final int y = ringBackY;
        content.post(new Runnable() { @Override public void run() { try { View c0 = content.getChildAt(0); if (c0 instanceof ScrollView) c0.scrollTo(0, y); } catch (Throwable ignored) { } } });
    }

    /** The page of one ring: back arrow, the ring's number and caption as Today showed them, then the same "how it is worked out" content the drop-down used to hold. */
    private View ringPageView(final int i) {
        LinearLayout col = screen();
        TextView back = tv("\u2039  Today", 15, BLUE, 400);
        back.setPadding(0, dpi(4), dpi(24), dpi(12));
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeRingPage(); } });
        col.addView(back, lp(-2, -2, 0));
        col.addView(head("How it is worked out", RING_NAMES[i], null));
        final HealthModel.Data d = data;
        if (d == null || d.day == null) { col.addView(tv("Reading your records\u2026", 14, MUT, 400), lp(-2, -2, 16)); return wrap(col); }
        LinearLayout top = card(18);
        LinearLayout row = hrow();
        row.addView(tv(ringBig[i] == null ? "\u2014" : ringBig[i], 46, ringCol[i] == 0 ? INK : ringCol[i], 200));
        LinearLayout side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); side.setPadding(dpi(16), 0, 0, 0);
        side.addView(caps(ringLab[i] == null ? RING_NAMES[i].toUpperCase(Locale.UK) : ringLab[i]));
        TextView sc = tv(ringCapT[i] == null ? "" : ringCapT[i], 13, MUT, 400); sc.setPadding(0, dpi(6), 0, 0);
        side.addView(sc);
        row.addView(side, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(row);
        col.addView(top, lp(-1, -2, 14));
        LinearLayout c = card(16);
        try {
            if (i == 0) fillRecovery(c, d.day); else if (i == 1) fillEffort(c, d.day); else if (i == 2) fillActiveRing(c); else if (i == 4) fillStress(c, d); else if (i == 5) fillFatigue(c, d); else if (i == 6) fillHeartWatch(c, d); else fillSleep(c, d.day);      // 0 recovery, 1 effort, 2 active, 3 sleep, 4 stress, 5 fatigue, 6 heart watch (as in 0.56.0)
        } catch (Throwable e) { note(c, "Could not draw this page (" + e.getClass().getSimpleName() + ")."); }
        col.addView(c, lp(-1, -2, 12));
        if (i == 3) col.addView(pillButton("Open the Rest page", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { go(3); } }), lp(-1, dpi(46), 14));
        if (i == 6) col.addView(pillButton("Open the Heart page", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { go(1); } }), lp(-1, dpi(46), 14));
        col.addView(pillButton("Back to Today", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { closeRingPage(); } }), lp(-1, dpi(46), i == 3 || i == 6 ? 10 : 14));
        return wrap(col);
    }

    private void kv(LinearLayout c, String k, String v) {
        if (v.length() > 22) {      // 0.75.1: a long value goes under its label (side by side it squeezed the label to one letter per line, seen on the 0.74.0 recovery page)
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            col.addView(tv(k, 13, INK, 400)); col.addView(tv(v, 13, INK, 600));
            c.addView(col, lp(-1, -2, 8)); return;
        }
        LinearLayout r = hrow();
        r.addView(tv(k, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(tv(v, 13, INK, 600));
        c.addView(r, lp(-1, -2, 8));
    }

    private void note(LinearLayout c, String s) {
        TextView t = tv(s, 11, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        c.addView(t, lp(-2, -2, 8));
    }

    /** "Lower than your usual by 0.8 spreads" for a z-score where positive means better. Pure text. */
    static String zText(String what, double z, String betterWord, String worseWord) {
        if (Double.isNaN(z)) return "";
        if (Math.abs(z) < 0.3) return what + ": right at your usual.";
        return what + ": " + String.format(Locale.UK, "%.1f", Math.abs(z)) + " spreads " + (z > 0 ? betterWord : worseWord) + " (" + (z > 0 ? "good" : "worth watching") + ").";
    }

    private void fillRecovery(LinearLayout c, TodayModel.Day day) {
        SleepNight.Night n = day.night;
        c.addView(caps("RECOVERY: HOW IT IS WORKED OUT"));
        if (Double.isNaN(day.recovery)) {
            note(c, "Not shown yet. It needs " + TodayModel.MIN_BASELINE + " earlier nights that each have a resting heart rate and an HRV; this strap has " + day.baselineNights + ".");
        } else {
            kv(c, "Estimate", Math.round(day.recovery) + " %");
            kv(c, "Compared with", "your last " + day.baselineNights + " nights");
            if (n != null) {
                kv(c, "Resting heart rate (lowest 5 min of the night)", Math.round(n.hrLow5) + " bpm");
                note(c, zText("Against your usual", day.zRhr, "lower", "higher"));
                kv(c, "Overnight HRV (RMSSD, optical)", Math.round(n.rmssd) + " ms");
                note(c, zText("Against your usual", day.zHrv, "higher", "lower"));
            }
            note(c, "Score = 100 x the normal curve of the average of the two z-scores. One spread is your own usual night-to-night change. A lower resting rate and a higher HRV both raise it.");
            rows(c, Explain.recoveryBands());
            try { recoveryDetail(c, day); } catch (Throwable ignored) { }      // 0.74.0
        }
        if (n != null) {
            head(c, "THE NIGHT");      // 0.75.0
            kv(c, "In bed", hhmm(n.bedTs) + " to " + hhmm(n.endTs) + " (" + HealthModel.hm(n.tibH) + ")");      // 0.74.0
            kv(c, "Slept", HealthModel.hm(n.tstH) + (n.effPct > 0 ? ", " + Math.round(n.effPct) + " % of the time in bed" : ""));
            if (n.settleMin > 0) kv(c, "Settled after", Math.round(n.settleMin) + " min");
            kv(c, "Records present", Math.round(n.coveragePct) + " % of the seconds in bed" + (n.coveragePct < 90 ? " (gaps lower the confidence)" : ""));
            if (!Double.isNaN(n.brMedian)) kv(c, "Breathing", String.format(Locale.UK, "%.1f a minute", n.brMedian));
            if (!Double.isNaN(n.skinMedian)) kv(c, "Skin temperature (night median)", String.format(Locale.UK, "%.1f \u00b0C", n.skinMedian));
            kv(c, "Not in the score", "sleep, breathing, skin, SpO2");
            StringBuilder miss = new StringBuilder();      // 0.74.0: inputs this night did not have
            if (Double.isNaN(n.brMedian)) miss.append("breathing; ");
            if (Double.isNaN(n.skinMedian)) miss.append("skin temperature; ");
            if (day.spo2 == null) miss.append("SpO2; ");
            if (Double.isNaN(n.hrLow5)) miss.append("resting rate; ");
            if (Double.isNaN(n.rmssd)) miss.append("overnight HRV; ");
            if (miss.length() > 0) kv(c, "Missing for this night", miss.substring(0, miss.length() - 2));
            try { recoveryNight(c, day); } catch (Throwable ignored) { }      // 0.75.0
        }
        note(c, "An estimate from this strap's own records. Optical HRV reads higher than an ECG strap's; your own nights absorb that. Not WHOOP's recovery and not a medical measure.");
    }

    /** 0.74.0: what stands behind the recovery number: the baseline (mean and spread of resting rate, geometric mean and spread of HRV), what each part adds, what would move it by 10, the baseline nights and the last 7 scores. All from the nights the app already holds. */
    private void recoveryDetail(LinearLayout c, TodayModel.Day day) {
        if (day.nowStat == null || day.earlierNights == null) return;
        TodayModel.RecDetail r = TodayModel.recoveryDetail(day.nowStat, day.earlierNights);
        if (r == null) return;
        Locale L = Locale.UK;
        head(c, "BEHIND THE NUMBER");
        kv(c, "Your usual resting rate", String.format(L, "%.1f bpm, spread %.1f bpm", r.mr, r.sr) + (r.srFloor ? " (the 2 bpm floor)" : ""));
        kv(c, "Your usual HRV", String.format(L, "%.0f ms, spread %.0f %%", Math.exp(r.ml), 100.0 * (Math.exp(r.sl) - 1.0)) + (r.slFloor ? " (at the floor, about 16 %)" : ""));
        kv(c, "What each part adds", String.format(L, "resting rate %+.2f, HRV %+.2f, together %+.2f", 0.5 * r.zr, 0.5 * r.zh, r.z));
        note(c, String.format(L, "Score = 100 x the normal curve at %+.2f = %.0f %%. Each part is half of its z-score.", r.z, r.score));
        for (int dir = 1; dir >= -1; dir -= 2) {
            double target = r.score + 10.0 * dir;
            if (target < 2.0 || target > 98.0) continue;
            double dz = TodayModel.phiInv(target / 100.0) - r.z;
            double dRhr = -2.0 * dz * r.sr, hrvPct = 100.0 * (Math.exp(2.0 * dz * r.sl) - 1.0);
            note(c, String.format(L, "To reach %.0f %% (%s10): resting rate %.1f bpm %s, or HRV %.0f %% %s, the other part unchanged.", target, dir > 0 ? "+" : "\u2212", Math.abs(dRhr), dRhr < 0 ? "lower" : "higher", Math.abs(hrvPct), hrvPct > 0 ? "higher" : "lower"));
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("EEE d MMM", L);
        StringBuilder sb = new StringBuilder();
        for (TodayModel.NightStat s : r.use) {      // newest first
            double[] rc = TodayModel.recovery(s, day.earlierNights);
            sb.append(df.format(new java.util.Date((s.bed + 43200L) * 1000L))).append(": ").append(Math.round(s.hrLow5)).append(" bpm, ").append(Math.round(s.rmssd)).append(" ms").append(rc == null ? "" : ", " + Math.round(rc[0]) + " %" + String.format(L, " (rate %+.1f, HRV %+.1f)", rc[1], rc[2])).append("\n");      // 0.75.0: the two z-scores
        }
        head(c, "THE " + r.use.size() + " NIGHTS COMPARED WITH");
        note(c, sb.toString().trim());
        note(c, "Each line: that night's resting rate, HRV, its own recovery against the nights before it, and its two z-scores (plus means better than its usual).");      // 0.75.0
        java.util.List<TodayModel.NightStat> all = new java.util.ArrayList<TodayModel.NightStat>(day.earlierNights);
        all.add(day.nowStat);
        java.util.Collections.sort(all, new java.util.Comparator<TodayModel.NightStat>() { @Override public int compare(TodayModel.NightStat a, TodayModel.NightStat b) { return Long.compare(a.bed, b.bed); } });
        StringBuilder st = new StringBuilder();
        for (int i = Math.max(0, all.size() - 7); i < all.size(); i++) {
            TodayModel.NightStat s = all.get(i); double[] rc = TodayModel.recovery(s, all);
            if (st.length() > 0) st.append("  \u00b7  ");
            st.append(new java.text.SimpleDateFormat("EEE d", L).format(new java.util.Date((s.bed + 43200L) * 1000L))).append(" ").append(rc == null ? "\u2014" : Math.round(rc[0]) + " %");
        }
        head(c, "LAST 7 NIGHTS");
        note(c, st.toString());
    }

    private static String rcN0(double v) { return Double.isNaN(v) ? "-" : String.valueOf(Math.round(v)); }

    private static String rcByHour(double[] v) {
        int last = v == null ? 0 : v.length;
        while (last > 0 && Double.isNaN(v[last - 1])) last--;
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < last; i++) { if (i > 0) s.append("  "); s.append(Double.isNaN(v[i]) ? "\u2014" : String.valueOf(Math.round(v[i]))); }
        return s.toString();
    }

    /** 0.75.0: the night behind the two numbers: when the lowest 5 minutes were, the first and last 3 hours, hour by hour in bed, how much of the beat-to-beat timing the HRV used, and where this night sits among the nights compared with. All from fields SleepNight and TodayModel already hold; nothing new is measured. */
    private void recoveryNight(LinearLayout c, TodayModel.Day day) {
        SleepNight.Night n = day.night;
        if (n == null) return;
        head(c, "HEART RATE AND HRV THROUGH THE NIGHT");
        if (!Double.isNaN(n.hrLow5)) kv(c, "Lowest 5-minute heart rate", Math.round(n.hrLow5) + " bpm" + (n.hrLow5Ts > 0 ? " at " + hhmm(n.hrLow5Ts) + ", " + HealthModel.hmShort(Math.max(0L, n.hrLow5Ts - n.bedTs)) + " in" : ""));
        if (!Double.isNaN(n.hrMean)) kv(c, "Average heart rate in bed", Math.round(n.hrMean) + " bpm");
        if (!Double.isNaN(n.hrFirst3) || !Double.isNaN(n.hrLast3)) kv(c, "Heart rate, first 3 h / last 3 h", rcN0(n.hrFirst3) + " / " + rcN0(n.hrLast3) + " bpm");
        if (!Double.isNaN(n.rmFirst3) || !Double.isNaN(n.rmLast3)) kv(c, "HRV, first 3 h / last 3 h", rcN0(n.rmFirst3) + " / " + rcN0(n.rmLast3) + " ms");
        String hh = rcByHour(n.hrByHour), hv = rcByHour(n.rmssdByHour);
        if (hh.length() > 0) note(c, "Heart rate by hour in bed, first hour first (bpm): " + hh);
        if (hv.length() > 0) note(c, "HRV by hour in bed, first hour first (ms; a dash means too little clean timing in that hour): " + hv);
        if (!Double.isNaN(n.rrKeptPct)) kv(c, "HRV counted from", Math.round(n.rrKeptPct) + " % of the beat intervals");
        if (!Double.isNaN(n.rmssdAll) && !Double.isNaN(n.rmssd)) kv(c, "HRV with no filter", Math.round(n.rmssdAll) + " ms (the score used " + Math.round(n.rmssd) + " ms)");
        note(c, "The score's HRV uses only the seconds the strap does not mark as degraded or as having lost the pulse lock; the rest are left out.");
        kv(c, "In bed but still / awake / up", Math.round(n.minStill) + " / " + Math.round(n.minWake) + " / " + Math.round(n.minUp) + " min");
        kv(c, "Restless spells", String.valueOf(n.restless.size()));
        if (n.partialStart || n.partialEnd) note(c, "The log starts or ends while the band is in sleep, so these numbers cover only the logged part of the night.");
        if (day.nowStat == null || day.earlierNights == null) return;
        TodayModel.RecDetail r = TodayModel.recoveryDetail(day.nowStat, day.earlierNights);
        if (r == null || r.use.isEmpty()) return;
        double loR = 1e9, hiR = -1e9, loH = 1e9, hiH = -1e9; int rateLower = 0, hrvHigher = 0;
        for (TodayModel.NightStat s : r.use) {
            loR = Math.min(loR, s.hrLow5); hiR = Math.max(hiR, s.hrLow5); loH = Math.min(loH, s.rmssd); hiH = Math.max(hiH, s.rmssd);
            if (s.hrLow5 < day.nowStat.hrLow5) rateLower++;
            if (s.rmssd > day.nowStat.rmssd) hrvHigher++;
        }
        head(c, "WHERE THIS NIGHT SITS");
        kv(c, "Resting rate this night", Math.round(day.nowStat.hrLow5) + " bpm");
        note(c, "The " + r.use.size() + " nights compared with ran " + Math.round(loR) + " to " + Math.round(hiR) + " bpm; " + rateLower + " of them were lower than this night.");
        kv(c, "HRV this night", Math.round(day.nowStat.rmssd) + " ms");
        note(c, "Those nights ran " + Math.round(loH) + " to " + Math.round(hiH) + " ms; " + hrvHigher + " of them were higher than this night.");
    }

    private void fillEffort(LinearLayout c, TodayModel.Day day) {
        c.addView(caps("EFFORT: HOW IT IS WORKED OUT"));
        kv(c, "Effort", String.format(Locale.UK, "%.1f of 21", day.effort));
        kv(c, "Counted from", hhmm(day.dayStart) + " (wake-up or 04:00)");
        int[] lo = {0, 50, 60, 70, 80, 90};
        String[] nm = {"Zone 0", "Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5"};
        for (int z = 0; z < 6; z++) {
            int a = Math.round(lo[z] * day.maxHr / 100f), b = z == 5 ? day.maxHr : Math.round(lo[z + 1] * day.maxHr / 100f) - 1;
            kv(c, nm[z] + "  " + (z == 0 ? "under " + (Math.round(50 * day.maxHr / 100f)) : a + " to " + b) + " bpm  (x" + z + ")", day.zoneMin[z] + " min");
        }
        kv(c, "Load (minutes x zone weight)", String.format(Locale.UK, "%.0f", day.trimp));
        note(c, "Effort = 21 x (1 - e^(-load / 150)). A minute counts when the strap logged at least 20 heart-rate seconds in it; its median sets the zone from your maximum heart rate of " + day.maxHr + " bpm.");
        kv(c, "Steps", String.valueOf(day.steps));
        kv(c, "Walking / running (strap's class)", day.walkMin + " / " + day.runMin + " min");
        rows(c, Explain.effortScale());
    }

    /** 0.53.5: the stress panel (StressModel version 2): headline, the day in bars, heart rate against what movement and the clock explain, time by level, highest stretches, your baselines, the method. */
    private void fillStress(LinearLayout c, HealthModel.Data d) {
        StressModel.Result r = d.stress;
        c.addView(caps("STRESS: TODAY"));
        if (r == null || Double.isNaN(r.baseline)) { note(c, "Not enough awake time with the strap on to set your baseline yet. It needs about two days of heart rate with your steps."); return; }
        LinearLayout head = hrow();
        head.addView(tv(r.today >= 0 ? String.valueOf(r.today) : "\u2014", 46, r.today >= 0 ? StressViews.levelColor(r.today) : MUT, 600));
        LinearLayout side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); side.setPadding(dpi(14), 0, 0, 0);
        side.addView(tv(r.today >= 0 ? r.level() + " so far today" : "not scored yet", 16, INK, 600));
        side.addView(tv(r.today >= 0 ? "out of 100, from " + HealthModel.hmShort((r.lowMin + r.medMin + r.highMin) * 60L) + " of scored time" : "needs about 30 minutes after your first hour awake", 11.5f, MUT, 400));
        head.addView(side);
        c.addView(head, lp(-1, -2, 10));
        StringBuilder cmp = new StringBuilder();
        if (!Double.isNaN(r.yesterday)) cmp.append("yesterday ").append(Math.round(r.yesterday));
        if (!Double.isNaN(r.week)) { if (cmp.length() > 0) cmp.append("  \u00b7  "); cmp.append("your last week ").append(Math.round(r.week)); }
        if (r.today >= 0 && !Double.isNaN(r.week)) { long df = Math.round(r.today - r.week); cmp.append("  \u00b7  ").append(Math.abs(df) <= 3 ? "about your usual" : df > 0 ? df + " above your usual" : (-df) + " below your usual"); }
        if (cmp.length() > 0) c.addView(tv(cmp.toString(), 12, MUT, 400), lp(-2, -2, 6));
        if (r.today >= 0) {
            kv(c, "Last 20 minutes", r.now >= 0 ? String.valueOf(r.now) : "\u2014");
            kv(c, "Highest 5 minutes", r.peak >= 0 ? r.peak + " at " + hhmm(r.peakT) : "\u2014");
        }
        if (!r.bins.isEmpty()) {
            TextView h1 = caps("THE DAY"); c.addView(h1, lp(-2, -2, 18));
            View strip = new StressViews.DayStrip(this, r.bins, r.dayStart, r.nowT); strip.setClickable(true);
            strip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { StressFullActivity.open(HealthActivity.this, StressFullActivity.MODE_TIME, 24); } });      // 0.53.8: full screen, zoom and scroll
            c.addView(strip, lp(-1, dpi(130), 8));
            note(c, "Each bar is 5 minutes: green under 20, amber 20 to 49, red 50 and over. Bars are dimmer while you were moving. The blue line is now. Tap the graph to open it full screen: pinch to zoom, drag to scroll back through 14 days.");
            TextView h2 = caps("HEART RATE AGAINST WHAT YOU WOULD EXPECT"); c.addView(h2, lp(-2, -2, 14));
            View hrc = new StressViews.HrAgainstExpected(this, r.bins, r.nowT, 12); hrc.setClickable(true);
            hrc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { StressFullActivity.open(HealthActivity.this, StressFullActivity.MODE_TIME, 12); } });
            c.addView(hrc, lp(-1, dpi(150), 8));
            note(c, "Blue: your heart rate. Grey: what your steps and the time of day explain. Amber between them: the part the score counts. Last 12 hours. Tap the graph for the full screen version with zoom and scroll.");
            TextView h3 = caps("TIME BY LEVEL"); c.addView(h3, lp(-2, -2, 14));
            View lvl = new StressViews.LevelBar(this, r.lowMin, r.medMin, r.highMin); lvl.setClickable(true);
            lvl.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { StressFullActivity.open(HealthActivity.this, StressFullActivity.MODE_DAYS, 24); } });      // tap: every day's minutes at each level
            c.addView(lvl, lp(-1, dpi(14), 8));
            kv(c, "Low (under 20)", HealthModel.hmShort(r.lowMin * 60L));
            kv(c, "Medium (20 to 49)", HealthModel.hmShort(r.medMin * 60L));
            kv(c, "High (50 and over)", HealthModel.hmShort(r.highMin * 60L));
            kv(c, "While still", r.stillScore >= 0 ? r.stillScore + "  (" + HealthModel.hmShort(r.stillMin * 60L) + ")" : "\u2014");
            kv(c, "While moving", r.movingScore >= 0 ? r.movingScore + "  (" + HealthModel.hmShort(r.movingMin * 60L) + ")" : "\u2014");
            // the hours of today with the highest mean
            double[] hs = new double[24]; int[] hn = new int[24]; int[] hp = new int[24];
            for (StressModel.Bin b : r.bins) { if (b.score < 0) continue; int hh = Integer.parseInt(new SimpleDateFormat("H", Locale.UK).format(new Date(b.t * 1000L))); hs[hh] += b.score; hn[hh]++; hp[hh] = Math.max(hp[hh], b.score); }
            List<Integer> order = new ArrayList<>(); for (int hh = 0; hh < 24; hh++) if (hn[hh] >= 6 && hs[hh] / hn[hh] >= 30) order.add(hh);
            java.util.Collections.sort(order, new java.util.Comparator<Integer>() { @Override public int compare(Integer a, Integer b) { return Double.compare(hs[b] / hn[b], hs[a] / hn[a]); } });
            if (!order.isEmpty()) { TextView h4 = caps("YOUR HIGHEST HOURS TODAY"); c.addView(h4, lp(-2, -2, 14)); for (int k = 0; k < Math.min(3, order.size()); k++) { int hh = order.get(k); kv(c, String.format(Locale.UK, "%02d:00 to %02d:00", hh, (hh + 1) % 24), "mean " + Math.round(hs[hh] / hn[hh]) + ", peak " + hp[hh]); } }
        }
        TextView h5 = caps("YOUR BASELINE (LAST 14 DAYS)"); c.addView(h5, lp(-2, -2, 16));
        String[] bl = {"Still, under 0.5 steps a minute", "Light, 0.5 to 5", "Steady, 5 to 20", "Brisk, 20 to 40", "Fast, over 40"};
        if (r.fallback) kv(c, "Still", Math.round(r.baseline) + " bpm  (provisional)");
        else for (int j = 0; j < 5; j++) if (!Double.isNaN(r.bandBase[j])) kv(c, bl[j], Math.round(r.bandBase[j]) + " bpm  (" + r.bandN[j] + ")");
        double mx = -99, mn = 99; int hmx = 0, hmn = 0; for (int hh = 0; hh < 24; hh++) { if (r.hourOffset[hh] > mx) { mx = r.hourOffset[hh]; hmx = hh; } if (r.hourOffset[hh] < mn) { mn = r.hourOffset[hh]; hmn = hh; } }
        if (mx - mn >= 1.0) kv(c, "The clock adds", String.format(Locale.UK, "%+.0f bpm at %02d:00, %+.0f at %02d:00", mx, hmx, mn, hmn));
        note(c, "The expected heart rate is your own lowest fifth at that many steps a minute, over the last 14 days, plus what the hour of the day usually adds for you; the heart rate lags movement, so it falls over the 10 minutes after you stop. The score is how far you sit above it: 3 % above reads 0, 25 % above reads 100. The first hour after you wake is not scored (the waking surge). In a public wrist-sensor data set of 20 people this separated mental tasks from relaxing in every person; emotion without a heart-rate rise is not seen, and illness, heat, caffeine, alcohol, a meal or heavy lifting raise it too. An estimate, not a medical measure.");
    }

    /** 0.54.4: the colour of the fatigue ring: under 30 green, 30 to 59 amber, 60 and over red. */
    static int fatigueColor(double s) { return Double.isNaN(s) ? 0xFF8A93A0 : s < FatigueModel.LOW_BELOW ? DailyViews.OK : s < FatigueModel.HIGH_FROM ? DailyViews.AMBER : DailyViews.RED; }

    /** 0.54.4: the fatigue panel (FatigueModel version 1): headline, what built up and what today added, each part with its own number and weight, then the long explanation. */
    private void fillFatigue(LinearLayout c, HealthModel.Data d) {
        c.addView(caps("FATIGUE: HOW IT IS WORKED OUT"));
        FatigueModel.Result f = null;
        try { f = FatigueModel.compute(d.nights, d.day.sleepGoalH, d.stress, d.day.effort, d.day.night == null ? 0L : d.day.night.endTs, d.day.newestTs); } catch (Throwable ignored) { }
        if (f == null || !f.scored()) {
            note(c, "Not scored yet. " + (f == null ? "" : f.why));
            rows(c, Explain.fatigue());
            return;
        }
        LinearLayout head = hrow();
        head.addView(tv(String.valueOf(f.rounded()), 46, fatigueColor(f.score), 600));
        LinearLayout side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); side.setPadding(dpi(14), 0, 0, 0);
        side.addView(tv(f.level() + " fatigue", 16, INK, 600));
        side.addView(tv("out of 100: 0 is nothing above your usual", 11.5f, MUT, 400));
        head.addView(side);
        c.addView(head, lp(-1, -2, 10));
        kv(c, "Built up over days (sleep debt, body signs, stress)", Double.isNaN(f.builtUp) ? "\u2014" : String.valueOf(Math.round(f.builtUp)));
        kv(c, "Added today (effort, time awake)", Double.isNaN(f.today) ? "\u2014" : String.valueOf(Math.round(f.today)));
        TextView h = caps("THE FIVE PARTS"); c.addView(h, lp(-2, -2, 16));
        for (int i = 0; i < 5; i++) {
            FatigueModel.Part p = f.parts[i];
            if (p.ok()) {
                kv(c, p.name + "  (" + Math.round(p.weight * 100) + " % of the score)", Math.round(p.score) + " \u2192 " + String.format(Locale.UK, "%.0f", p.contrib) + " points");
                note(c, p.detail + ".");
            } else {
                kv(c, p.name, "left out");
                note(c, p.detail + ".");
            }
        }
        note(c, "Points add up to the score. A part that is left out hands its share to the others.");
        rows(c, Explain.fatigue());
    }

    private static String tierWord(int t) { return t == AlertEngine.CHECK ? "CHECK" : t == AlertEngine.WATCH ? "WATCH" : t == AlertEngine.GREEN ? "usual" : "not enough data"; }

    /** 0.56.0: the Heart Watch ring (AlertEngine version 1): what it looked at, what it found, what to do. Research only, not a diagnosis. */
    private void fillHeartWatch(LinearLayout c, HealthModel.Data d) {
        c.addView(caps("HEART WATCH: WHAT IT LOOKS AT"));
        final AlertEngine.Result r = d == null ? null : d.alert;
        if (r == null) { note(c, "Not worked out yet. Press Sync now and open Today again."); return; }
        LinearLayout head = hrow();
        head.addView(tv(r.big(), 30, DailyViews.heartColor(r.tier), 600));
        LinearLayout side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); side.setPadding(dpi(14), 0, 0, 0);
        side.addView(tv(r.headline, 16, INK, 600));
        side.addView(tv("Three things are checked; the ring shows the highest level", 11.5f, MUT, 400));
        head.addView(side);
        c.addView(head, lp(-1, -2, 10));
        kv(c, "Pulse rhythm at rest, last 48 hours", tierWord(r.rhythm.tier)); note(c, r.rhythm.line);
        kv(c, "Heart rate at rest, now", tierWord(r.rest.tier)); note(c, r.rest.line);
        kv(c, "Night signs against your usual", tierWord(r.drift.tier)); note(c, r.drift.line);
        if (r.nightBed > 0 && r.signals > 0) {
            c.addView(caps("THE NIGHT THAT BEGAN " + hhmm(r.nightBed) + ", AGAINST YOUR LAST NIGHTS"), lp(-2, -2, 16));
            for (int k = 0; k < 5; k++) {
                AlertEngine.Sig g = r.sig[k];
                if (!g.ok()) { kv(c, g.name, "needs " + AlertEngine.MIN_BASE + " earlier nights (has " + g.nBase + ")"); continue; }
                String now = k == 1 || k == 0 || k == 4 ? String.format(Locale.UK, "%.0f", g.now) : String.format(Locale.UK, "%.1f", g.now);
                String usual = k == 1 || k == 0 || k == 4 ? String.format(Locale.UK, "%.0f", g.usual) : String.format(Locale.UK, "%.1f", g.usual);
                String how = Math.abs(g.z) < 0.5 ? "as usual" : String.format(Locale.UK, "%.1f spreads %s", Math.abs(g.z), g.z > 0 ? "worse" : "better");
                kv(c, g.name, now + " " + g.unit + "  (usual " + usual + ", " + how + ")");
            }
            note(c, "A spread is how much that sign normally varies from night to night for you (the median absolute deviation, with a floor). WATCH needs two signs at 2 spreads worse or more; CHECK needs three, or two on two nights running.");
        }
        c.addView(caps("WHAT TO DO"), lp(-2, -2, 16));
        note(c, r.advice);
        if (r.rhythm.tier >= AlertEngine.WATCH) c.addView(pillButton("Start an ECG now", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), lp(-1, dpi(46), 12));
        c.addView(caps("IF THE PULSE FLAG SHOWS"), lp(-2, -2, 16));      // 0.64.0: wording per rule 50.1 (h): no disease is named; no alert rule changed
        note(c, "1. Sit down, rest the wrist on a table and keep still for a minute.\n2. Take an ECG reading of at least 30 seconds (ECG tab, START ECG) and stay still the whole time.\n3. Open that reading's report and show it, with this page, to a doctor or nurse. They read the ECG; this app only counts and times beats and cannot say what a rhythm is.\nChest pain, fainting or being unable to get your breath: do not wait for a reading, call 999.");
        final boolean on = AlertWatch.on(this);
        c.addView(pillButton("Notify me on a CHECK of the heart rate or night signs: " + (on ? "On" : "Off"), 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { AlertWatch.setOn(HealthActivity.this, !on); renderKeepScroll(); } }), lp(-1, dpi(44), 8));
        note(c, "How it works: each time the strap syncs, this compares you with your own recent record, never with other people. The pulse check uses only still, clean five-minute windows and ignores single early beats. Limits: the levels were chosen on one person's 21 days (no alert in them) and on synthetic rhythms; they are NOT validated against illness or heart events, and no wearable method is proven to predict a heart attack. The strap's ECG, read by a doctor, is what confirms a rhythm. A quiet ring does not rule anything out.");
    }

    private void fillSleep(LinearLayout c, TodayModel.Day day) {
        SleepNight.Night n = day.night;
        c.addView(caps("SLEEP: LAST NIGHT"));
        if (n == null) { note(c, "No complete night found yet."); return; }
        kv(c, "In bed", hhmm(n.bedTs) + " to " + hhmm(n.endTs) + "  (" + HealthModel.hm(n.tibH) + ")");
        kv(c, "Asleep (the night)", HealthModel.hm(n.tstH) + "  (" + Math.round(100.0 * n.tstH / day.sleepGoalH) + " % of your " + String.format(java.util.Locale.UK, "%.1f", day.sleepGoalH) + " h goal)");
        if (day.napH > 0) {                                                                   // 0.44.0: naps since waking; the night's efficiency, HRV and recovery stay night-only
            for (long[] nb : day.naps) kv(c, "Nap " + hhmm(nb[0]) + " to " + hhmm(nb[1]), HealthModel.hmShort(nb[2]));
            kv(c, "Night plus naps (the sleep ring)", HealthModel.hm(n.tstH + day.napH) + (Double.isNaN(day.sleepPct) ? "" : "  (" + Math.round(day.sleepPct) + " %)"));
            note(c, "A nap is a stretch of the band's SLEEP state of at least 20 minutes (gaps up to 10 minutes merged) that starts more than 30 minutes after the night ended. Efficiency, heart rate, HRV and recovery use the night only. The 20 minute limit is a first guess.");
        }
        kv(c, "Efficiency", Math.round(n.effPct) + " %");
        kv(c, "Awake in bed (not counted as sleep)", HealthModel.hm(Math.max(0.0, n.tibH - n.tstH)) + "  (settling " + Math.round(n.settleMin) + " min, " + n.restless.size() + " restless spells)");        // 0.33.0
        if (!Double.isNaN(n.hrFirst3) || !Double.isNaN(n.hrLast3)) kv(c, "Heart rate, first 3 h / last 3 h", (Double.isNaN(n.hrFirst3) ? "-" : String.valueOf(Math.round(n.hrFirst3))) + " / " + (Double.isNaN(n.hrLast3) ? "-" : String.valueOf(Math.round(n.hrLast3))) + " bpm");
        if (!Double.isNaN(n.rmFirst3) || !Double.isNaN(n.rmLast3)) kv(c, "HRV (optical RMSSD), first 3 h / last 3 h", (Double.isNaN(n.rmFirst3) ? "-" : String.valueOf(Math.round(n.rmFirst3))) + " / " + (Double.isNaN(n.rmLast3) ? "-" : String.valueOf(Math.round(n.rmLast3))) + " ms");
        note(c, "Asleep counts the strap's SLEEP state plus quiet STILL or UP stretches of 30 minutes or less inside the night (wrist still, heart rate within 6 bpm of your sleep rate). The stages below are a separate estimate and can differ by 20 to 30 minutes.");
        kv(c, "Time to settle", Math.round(n.settleMin) + " min");
        kv(c, "In bed but still / awake / up", Math.round(n.minStill) + " / " + Math.round(n.minWake) + " / " + Math.round(n.minUp) + " min");
        kv(c, "Restless spells", String.valueOf(n.restless.size()));
        if (!Double.isNaN(n.hrLow5)) kv(c, "Lowest 5-minute heart rate", Math.round(n.hrLow5) + " bpm" + (n.hrLow5Ts > 0 ? " at " + hhmm(n.hrLow5Ts) : ""));
        if (!Double.isNaN(n.hrMean)) kv(c, "Average heart rate", Math.round(n.hrMean) + " bpm");
        if (!Double.isNaN(n.rmssd)) kv(c, "HRV (RMSSD, optical)", Math.round(n.rmssd) + " ms");
        if (!Double.isNaN(n.brMedian)) kv(c, "Breathing", String.format(Locale.UK, "%.1f a minute", n.brMedian));
        if (!Double.isNaN(n.skinMedian)) kv(c, "Skin temperature", String.format(Locale.UK, "%.1f \u00b0C (%.1f to %.1f)", n.skinMedian, n.skinMin, n.skinMax));
        if (day.spo2 != null && day.spo2.validWindows > 0) kv(c, "SpO2 (sleep-only windows)", day.spo2.median + " %  (" + day.spo2.validWindows + " windows)");
        kv(c, "Records present", Math.round(n.coveragePct) + " %" + (n.partialStart || n.partialEnd ? "  (partial night)" : ""));
        note(c, "From the strap's own sleep state (awake, still, sleep, up): an envelope, not a stage split. The Rest tab shows the estimated stages.");
        rows(c, Explain.sleepRing(day.sleepGoalH));
    }

    private View todayTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (!loadedOnce) {
            col.addView(head("Hi, " + name(), "Today", null));
            TextView lt = tv("Reading your records\u2026", 14, MUT, 400);
            lt.setPadding(0, dpi(18), 0, 0);
            col.addView(lt);
            return wrap(col);
        }
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Hi, " + name(), "Today", null));
            View emptyBanner = loggerBanner(0L);      // 0.74.0: copy B with no strap chosen shows its Choose strap card here too (it used to be hidden behind this early return)
            if (emptyBanner != null) col.addView(emptyBanner, lp(-1, -2, 12));
            LinearLayout c = card(18);
            c.addView(caps("NO RECORDS YET"));
            TextView t = tv("Turn on the day logger and pull the strap's history. This screen fills in from the strap's own per-second records.", 14, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            c.addView(t);
            col.addView(c, lp(-1, -2, 18));
            col.addView(pillButton("Open the day logger", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(46), 14));
            col.addView(rowCard("Strap data", "Every value the strap sends while worn: link, clock, storage, firmware, events (works before the first record)",
                    new View.OnClickListener() { @Override public void onClick(View v) { StrapData.show(HealthActivity.this); } }), lp(-1, -2, 12));      // 0.74.0
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        SleepNight.Night n = day.night;
        long age = System.currentTimeMillis() / 1000L - day.newestTs;
        col.addView(head("Hi, " + name(), n != null ? "You slept " + HealthModel.hmShort((long) ((n.tstH + day.napH) * 3600)) : "Good morning", null));
        final int slot = UxLogic.slot(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));      // 0.61.0: morning, daytime, evening
        if (slot != 1) col.addView(wellbeingCard(slot), lp(-1, -2, 12));                // 0.58.0: the Morning brief card (0.61.0: "This evening" after 18:00, below the rings in the daytime), opens the wellbeing page
        View banner = loggerBanner(age);                       // 0.13.2: say so when the all-day log is not running
        if (banner != null) col.addView(banner, lp(-1, -2, 12));
        col.addView(syncStrip(day), lp(-1, -2, 10));              // 0.20.0: when it last synced, how far behind the strap is, and a Sync now button
        // ---- the three rings and the day's plan (0.12.0)
        double rec = day.recovery;
        float[] rf = {Double.isNaN(rec) ? 0f : (float) (rec / 100.0), (float) (day.effort / 21.0), Double.isNaN(day.sleepPct) ? 0f : (float) Math.min(1.0, day.sleepPct / 100.0)};
        int[] rc = {DailyViews.recoveryColor(rec), BLUE, VIOLET};
        String[] rb = {Double.isNaN(rec) ? "\u2014" : Math.round(rec) + "%", String.format(Locale.UK, "%.1f", day.effort), Double.isNaN(day.sleepPct) ? "\u2014" : Math.round(day.sleepPct) + "%"};
        final StressModel.Result stx = d.stress;                    // 0.53.0: stress ring (heart rate above your own still baseline while not moving)
        final boolean hasStress = stx != null && stx.today >= 0;
        rf = new float[] {rf[0], rf[1], rf[2], hasStress ? Math.max(0.02f, stx.today / 100f) : 0f};
        rc = new int[] {rc[0], rc[1], rc[2], !hasStress ? 0xFF8A93A0 : StressViews.levelColor(stx.today)};
        rb = new String[] {rb[0], rb[1], rb[2], hasStress ? String.valueOf(stx.today) : "\u2014"};
        String[] rl = {"RECOVERY", "EFFORT", "SLEEP", "STRESS"};
        final boolean hasAct = actMin >= 0; final int goalMin = Math.max(30, actGoal);      // 0.54.0: ACTIVE, all-day moving time against the goal set on the Activity screen
        String[] rcap = {Double.isNaN(rec) ? "needs " + TodayModel.MIN_BASELINE + " nights" : "vs your last " + day.baselineNights + " nights", "of 21 today",
                n == null ? "no night yet" : HealthModel.hmShort((long) ((n.tstH + day.napH) * 3600)) + " of " + String.format(java.util.Locale.UK, "%.1f", day.sleepGoalH) + " h goal",
                hasStress ? (Double.isNaN(stx.week) ? stx.level() + " today" : "usual " + Math.round(stx.week)) : "not scored"};      // 0.53.5: against your own usual of the last week
        rcap[0] = Double.isNaN(rec) ? "needs nights" : "vs " + day.baselineNights + " nights"; rcap[2] = n == null ? "no night yet" : HealthModel.hmShort((long) ((n.tstH + day.napH) * 3600)) + " of " + String.format(java.util.Locale.UK, "%.1f", day.sleepGoalH) + " h";      // shorter captions: four rings share the row
        rf = new float[] {rf[0], rf[1], hasAct ? Math.max(0.02f, Math.min(1f, actMin / (float) goalMin)) : 0f, rf[2], rf[3]};
        rc = new int[] {rc[0], rc[1], hasAct ? TEAL : 0xFF8A93A0, rc[2], rc[3]};
        rb = new String[] {rb[0], rb[1], hasAct ? ActivityPlus.hmClock(actMin) : "\u2014", rb[2], rb[3]};
        rl = new String[] {"RECOVERY", "EFFORT", "ACTIVE", "SLEEP", "STRESS"};
        rcap = new String[] {rcap[0], rcap[1], hasAct ? "goal " + (goalMin % 60 == 0 ? (goalMin / 60) + " h" : ActivityPlus.hmClock(goalMin)) : "no data", rcap[2], rcap[3]};
        // 0.54.4: the FATIGUE ring (FatigueModel version 1): built up over days plus what today added; six rings go four to a row
        FatigueModel.Result fat = null;
        try { fat = FatigueModel.compute(d.nights, day.sleepGoalH, d.stress, day.effort, n == null ? 0L : n.endTs, day.newestTs); } catch (Throwable ignored) { }
        final boolean hasFat = fat != null && fat.scored();
        rf = new float[] {rf[0], rf[1], rf[2], rf[3], rf[4], hasFat ? Math.max(0.02f, (float) (fat.score / 100.0)) : 0f};
        rc = new int[] {rc[0], rc[1], rc[2], rc[3], rc[4], hasFat ? fatigueColor(fat.score) : 0xFF8A93A0};
        rb = new String[] {rb[0], rb[1], rb[2], rb[3], rb[4], hasFat ? String.valueOf(fat.rounded()) : "\u2014"};
        rl = new String[] {rl[0], rl[1], rl[2], rl[3], rl[4], "FATIGUE"};
        rcap = new String[] {rcap[0], rcap[1], rcap[2], rcap[3], rcap[4], hasFat ? fat.level() : "not scored"};
        rc = DailyViews.RING_COLORS.clone();      // 0.55.0: each ring keeps its own colour (green, blue, orange, violet, pink, yellow); the level is in the number and the caption
        final AlertEngine.Result hw = d.alert;      // 0.56.0: the seventh ring, HEART WATCH (AlertEngine): usual / WATCH / CHECK, its colour follows the level; seven rings go four, then three
        rf = java.util.Arrays.copyOf(rf, 7); rf[6] = hw == null || hw.tier == AlertEngine.NONE ? 0f : Math.max(0.02f, hw.fill());
        rc[6] = DailyViews.heartColor(hw == null ? AlertEngine.NONE : hw.tier);
        rb = java.util.Arrays.copyOf(rb, 7); rb[6] = hw == null ? "\u2014" : hw.big();
        rl = java.util.Arrays.copyOf(rl, 7); rl[6] = "HEART WATCH";
        rcap = java.util.Arrays.copyOf(rcap, 7); rcap[6] = hw == null ? "not scored" : hw.caption();
        for (int q = 0; q < 7; q++) { ringBig[q] = q < rb.length ? rb[q] : null; ringLab[q] = q < rl.length ? rl[q] : null; ringCapT[q] = q < rcap.length ? rcap[q] : null; ringCol[q] = q < rc.length ? rc[q] : 0; }      // 0.71.0: kept for the ring's own page
        DailyViews.RingsView rings = new DailyViews.RingsView(this, rf, rc, rb, rl, rcap);
        final android.view.GestureDetector ringTap = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(android.view.MotionEvent e) { return true; }
        });
        rings.setClickable(true);
        rings.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {                // 0.20.0: tap a ring to open how it is worked out
                if (ringTap.onTouchEvent(e) && e.getAction() == android.view.MotionEvent.ACTION_UP) { int hit = ((DailyViews.RingsView) v).ringAt(e.getX(), e.getY()); if (hit >= 0) openRingPage(hit); }      // 0.54.4: six rings, four to a row
                return false;
            }
        });
        col.addView(rings, lp(-1, -2, 6));      // 0.54.4: the height follows the rows
        TextView ringHint = tv("Tap a ring to open its page. Active is your moving time all day, not workouts. Fatigue is an estimate of what has built up over days. Heart Watch flags anything unusual for you.", 11, MUT, 400);
        ringHint.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(ringHint, lp(-1, -2, 0));
        TextView guideLink = tv("Quick guide: what the colours and tabs mean \u203a", 12, BLUE, 400);      // 0.55.0
        guideLink.setGravity(Gravity.CENTER_HORIZONTAL);
        guideLink.setPadding(0, dpi(8), 0, dpi(8));
        guideLink.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { QuickGuide.show(HealthActivity.this); } });
        col.addView(guideLink, lp(-1, -2, 2));
        LinearLayout olc = card(16);      // 0.63.0: the plan sentence in a card with its heading
        olc.addView(caps("DAILY OUTLOOK"));
        TextView plan = tv(DailyViews.plan(rec), 14, INK, 400);
        plan.setLineSpacing(0, 1.25f);
        olc.addView(plan, lp(-1, -2, 8));
        col.addView(olc, lp(-1, -2, 10));
        // ---- key statistics (0.63.0: was HEALTH MONITOR, moved up under the daily outlook): last night against your own usual range
        List<DailyViews.Metric> mon = DailyViews.monitor(d);
        if (!mon.isEmpty()) {
            LinearLayout hmc = card(16);
            LinearLayout hmh = hrow();
            hmh.addView(caps("KEY STATISTICS"));
            hmh.addView(spacer());
            hmh.addView(tv("last night against your usual", 12, MUT, 400));
            hmc.addView(hmh);
            for (DailyViews.Metric mt : mon) {
                LinearLayout row = hrow();
                row.addView(tv(mt.name, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
                String dtx = mt.deltaText();      // 0.63.0: how far last night sits from your usual
                if (!dtx.isEmpty()) { TextView dv = tv(dtx, 11, mt.color(), 400); dv.setPadding(0, 0, dpi(8), 0); row.addView(dv); }
                row.addView(tv(mt.valueText(), 15, INK, 600));
                hmc.addView(row, lp(-1, -2, 12));
                hmc.addView(new DailyViews.BandView(this, mt), lp(-1, dpi(18), 2));
                hmc.addView(tv(mt.rangeText(), 11, MUT, 400));
                final LinearLayout mp = new LinearLayout(this);
                mp.setOrientation(LinearLayout.VERTICAL);
                mp.setVisibility(View.GONE);
                hmc.addView(mp, lp(-1, -2, 4));
                final String mkey = "mon_" + mt.name;
                final DailyViews.Metric mtf = mt;
                row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    if (openCards.contains(mkey)) { openCards.remove(mkey); mp.removeAllViews(); mp.setVisibility(View.GONE); }
                    else { openCards.add(mkey); mp.removeAllViews(); fillMonitorPanel(mp, mtf); mp.setVisibility(View.VISIBLE); }
                } });
                if (openCards.contains(mkey)) { fillMonitorPanel(mp, mtf); mp.setVisibility(View.VISIBLE); }
            }
            TextView hn = tv("Tap a line to see what it means. Green is inside your usual range, amber about two spreads out, red further. HRV here comes from the wrist's optical intervals. A guide, not a diagnosis.", 11, MUT, 400);
            hn.setLineSpacing(0, 1.2f);
            hmc.addView(hn, lp(-2, -2, 10));
            col.addView(hmc, lp(-1, -2, 12));
        }

        col.addView(tv("Estimates from this strap's own records, built on your own recent nights. Not WHOOP's scores and not a medical measure.", 11, MUT, 400), lp(-2, -2, 8));
        if (slot == 1) col.addView(wellbeingCard(slot), lp(-1, -2, 16));
        try { col.addView(alarmCard(), lp(-1, -2, 16)); } catch (Throwable ignored) { }      // 0.43.5: a morning and an evening buzz alarm

        // ---- 0.54.0: all-day activity (not workouts): moving time, still stretches and the hours of the day
        LinearLayout wkc = card(16);
        LinearLayout wkh = hrow();
        wkh.addView(caps("ON YOUR FEET TODAY"));
        wkh.addView(spacer());
        if (actMin >= 0) wkh.addView(tv(ActivityPlus.hm(actMin) + " moving", 12, MUT, 400));
        wkc.addView(wkh);
        final ActivityPlus.DayStats ast = actToday;
        if (ast != null && ast.awakeMin >= 30) {
            wkc.addView(tv("Still while awake " + ActivityPlus.hm(ast.stillMin) + "  \u00b7  longest still stretch " + (ast.longestStillMin == 0 ? "none" : ActivityPlus.hm(ast.longestStillMin)) + (day.steps > 0 ? "  \u00b7  " + day.steps + " steps" : ""), 12, INK, 400), lp(-2, -2, 6));
            wkc.addView(new ActivityPlus.HourBars(this, ast.movingByHour, 60, TEAL), lp(-1, dpi(80), 10));
            wkc.addView(tv("Moving minutes by hour, from 04:00", 11, MUT, 400), lp(-2, -2, 3));
        } else {
            wkc.addView(tv(actLine.isEmpty() ? "Moving time, still stretches and steps through the day" : actLine + (day.steps > 0 ? "  \u00b7  " + day.steps + " steps" : ""), 12, INK, 400), lp(-2, -2, 6));
        }
        wkc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, ActivityActivity.class)); } });
        expandable("t_act", wkc, new Filler() { @Override public void fill(LinearLayout p) { fillActivityPanel(p); } });
        col.addView(wkc, lp(-1, -2, 12));

        // ---- 0.54.0: what to trust in today's data
        final List<TrustData.Day> tdays = trustDays;
        if (!tdays.isEmpty()) {
            LinearLayout tc = card(16);
            LinearLayout th = hrow();
            th.addView(caps("DATA TRUST"));
            th.addView(spacer());
            th.addView(tv(tdays.get(0).liveHours + " h in range today", 12, MUT, 400));
            tc.addView(th);
            tc.addView(new ActivityPlus.TrustStrip(this, tdays.get(0)), lp(-1, dpi(36), 8));
            tc.addView(tv(tdays.get(0).summary(), 11, MUT, 400), lp(-2, -2, 4));
            expandable("t_trust", tc, new Filler() { @Override public void fill(LinearLayout p) { ActivityPlus.trustBody(HealthActivity.this, p, trustDays, trustIngests); } }, "Yesterday, gaps and history pulls");
            col.addView(tc, lp(-1, -2, 12));
        }

        // ---- heart rate now
        LinearLayout nowCard = card(14);
        LinearLayout nowRow = hrow();
        nowRow.addView(caps("HEART RATE NOW"));
        nowRow.addView(spacer());
        todayHrNum = tv(shownHr(d) > 0 ? shownHr(d) + " bpm" : "\u2014", 20, INK, 300);
        nowRow.addView(todayHrNum);
        nowCard.addView(nowRow);
        todayHrSub = tv(shownLabel(d).replace("bpm, ", "") + (n != null && !Double.isNaN(day.restingHr) ? "  \u00b7  lowest five minutes " + Math.round(day.restingHr) : ""), 11, MUT, 400);
        todayHrSubTail = (n != null && !Double.isNaN(day.restingHr) ? "  \u00b7  lowest five minutes " + Math.round(day.restingHr) : "");
        nowCard.addView(todayHrSub, lp(-2, -2, 4));
        nowCard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go(1); } });
        expandable("t_hr", nowCard, new Filler() { @Override public void fill(LinearLayout p) { fillHrNow(p); } });
        col.addView(nowCard, lp(-1, -2, 12));

        col.addView(caps("INSIGHTS"), lp(-2, -2, 18));      // 0.65.0: wellbeing and insights on the home page (they were a card in You in 0.64.0)
        col.addView(rowCard("Wellbeing", "Sleep rhythm, calm minute, good things, if-then plans, your week, work-day load",
                new View.OnClickListener() { @Override public void onClick(View v) { try { startActivity(new Intent(HealthActivity.this, WellbeingActivity.class)); } catch (Throwable ignored) { } } }), lp(-1, -2, 8));
        col.addView(rowCard("Heart Watch", "Night signs against your own usual, and the pulse check",
                new View.OnClickListener() { @Override public void onClick(View v) { showRing(6); } }), lp(-1, -2, 12));
        col.addView(rowCard("Trends", "Recovery, resting heart rate, HRV, sleep, breathing and skin temperature over 7, 30 or 90 nights",
                new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Trends.show(HealthActivity.this, data); } }), lp(-1, -2, 12));
        col.addView(rowCard("HRV (research)", "Your HRV in context: seated ECG readings, nights, evening and alcohol, age limits, guided breathing tests and the sources. Not a diagnosis",
                new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) HrvScreen.show(HealthActivity.this, data); } }), lp(-1, -2, 12));
        col.addView(rowCard("Healthspan (research)", "Age estimate and pace of aging from your nights: resting heart rate, sleep regularity, fitness. A research estimate, not WHOOP's number",
                new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Healthspan.show(HealthActivity.this, data); } }), lp(-1, -2, 12));
        col.addView(rowCard("Strap data", "Every value the strap sends while worn: heart, motion, temperatures, state, events",
                new View.OnClickListener() { @Override public void onClick(View v) { StrapData.show(HealthActivity.this); } }), lp(-1, -2, 10));

        // ---- effort today
        LinearLayout efc = card(16);
        LinearLayout efh = hrow();
        efh.addView(caps("EFFORT"));
        efh.addView(spacer());
        efh.addView(tv(String.format(Locale.UK, "%.1f of 21", day.effort), 13, INK, 600));
        efc.addView(efh);
        efc.addView(new ZoneBarView(this, d.zoneSec), lp(-1, dpi(10), 10));
        efc.addView(tv("Time in heart-rate zones over the last 24 hours, weighted into a 0 to 21 effort." + (day.steps > 0 ? " " + day.steps + " steps." : "") + " This is not WHOOP's strain.", 11, MUT, 400), lp(-2, -2, 8));
        expandable("t_effort", efc, new Filler() { @Override public void fill(LinearLayout p) { TodayModel.Day dd = data == null ? null : data.day; if (dd != null) fillEffort(p, dd); } });
        col.addView(efc, lp(-1, -2, 12));

        // ---- journal: one tap puts a time-stamped marker in the day log
        LinearLayout jc = card(16);
        jc.addView(caps("JOURNAL"));
        jc.addView(tv("Tap to log it now. The log lines it up with the heart and sleep numbers later.", 11, MUT, 400), lp(-2, -2, 4));
        String[][] marks = {{"Caffeine", "caffeine"}, {"Meal", "meal"}, {"Alcohol", "alcohol"}, {"Stress", "stress"}, {"Workout start", "workout_start"}, {"Workout end", "workout_end"}, {"Symptom", "symptom"}, {"Going to bed", "bed"}};
        LinearLayout jr = null;
        for (int mi = 0; mi < marks.length; mi++) {
            if (mi % 2 == 0) { jr = hrow(); jc.addView(jr, lp(-1, -2, 8)); }
            final String mName = marks[mi][0], mKind = marks[mi][1];
            TextView pill = HeartGraph.pill(this, mName, 0x1AFFFFFF, INK);
            pill.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DayLog.addEvent(HealthActivity.this, "marker", mKind); android.widget.Toast.makeText(HealthActivity.this, mName + " logged at " + hhmm(System.currentTimeMillis() / 1000L), android.widget.Toast.LENGTH_SHORT).show(); } });
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, -2, 1f);
            pl.rightMargin = dpi(8);
            jr.addView(pill, pl);
        }
        expandable("t_journal", jc, new Filler() { @Override public void fill(LinearLayout p) { fillJournalPanel(p); } });
        col.addView(jc, lp(-1, -2, 12));

        String ecgTitle = "No ECG reading yet", ecgSub = "Start one from the ECG tab";
        if (!d.readings.isEmpty()) {
            HealthModel.Reading r = d.readings.get(0);
            for (HealthModel.Reading q : d.readings) if (!"-".equals(q.hr)) { r = q; break; }
            ecgTitle = "Last ECG \u00b7 " + r.when;
            ecgSub = ("-".equals(r.hr) ? "" : "HR " + r.hr + " bpm") + ("-".equals(r.rhythm) ? "" : (("-".equals(r.hr) ? "" : " \u00b7 ") + r.rhythm));
            if (ecgSub.isEmpty()) ecgSub = "Open to see why";
        }
        col.addView(rowCard(ecgTitle, ecgSub, new View.OnClickListener() { @Override public void onClick(View v) { go(2); } }), lp(-1, -2, 16));
        LinearLayout egc = explainerCard("ABOUT YOUR ECG READINGS", "What the grades mean and how to get a strong reading");
        expandable("t_ecg", egc, new Filler() { @Override public void fill(LinearLayout p) { fillEcgPanel(p); } });
        col.addView(egc, lp(-1, -2, 10));
        if (n != null) {
            String sub = (Double.isNaN(n.brMedian) ? "" : "Breathing " + String.format(Locale.UK, "%.1f", n.brMedian) + " a minute") + (Double.isNaN(n.skinMedian) ? "" : ", skin " + String.format(Locale.UK, "%.1f", n.skinMedian) + " \u00b0C");
            String napLine = day.napH > 0 ? "Nap " + HealthModel.hmShort((long) (day.napH * 3600)) + "\n" : "";
            col.addView(rowCard("Last night \u00b7 " + HealthModel.hm(n.tstH) + " asleep" + (day.spo2 != null && day.spo2.validWindows > 0 ? " \u00b7 SpO2 " + day.spo2.median + "%" : ""), napLine + (sub.isEmpty() ? "Open Rest for the whole night" : sub),
                    new View.OnClickListener() { @Override public void onClick(View v) { go(3); } }), lp(-1, -2, 10));
        }
        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("THE LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv("data to " + ago(day.newestTs) + "  \u00b7  Full graph \u203a", 12, MUT, 400));
        c.addView(h);
        View last24 = new HrChartView(this, day.hrT, day.hrV, n == null ? 0 : n.bedTs, n == null ? 0 : n.endTs, false);
        last24.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        c.addView(last24, lp(-1, dpi(110), 8));
        TextView hint24 = tv("Tap to open the full graph: pinch to zoom, drag to move, expand to fill the screen.", 11, MUT, 400);
        hint24.setPadding(0, dpi(6), 0, 0);
        c.addView(hint24);
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        expandable("t_h24", c, new Filler() { @Override public void fill(LinearLayout p) { fillH24Panel(p); } });
        col.addView(c, lp(-1, -2, 10));
        return wrap(col);
    }

    // ------------------------------------------------------------------ HEART
    private View heartTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        if (d == null || d.day == null || d.day.newestTs == 0) {
            col.addView(head("Heart", "Heart", null));
            col.addView(tv("No records yet. The strap stores its heart-rate seconds and the app collects them at each history pull, about every 30 minutes while the day logger is on and the strap is in range. Turn the day logger on (Today tab) and the first records arrive within the hour.", 14, MUT, 400), lp(-2, -2, 16));
            return wrap(col);
        }
        TodayModel.Day day = d.day;
        col.addView(tv("Heart", 13, MUT, 400));
        LinearLayout big = hrow();
        big.setGravity(Gravity.BOTTOM);
        TextView num = tv(shownHr(d) > 0 ? String.valueOf(shownHr(d)) : "\u2014", 64, INK, 200);
        num.setTypeface(font(this, 200));
        big.addView(num);
        TextView unit = tv("  " + shownLabel(d), 14, MUT, 400);
        liveNum = num; liveUnit = unit; liveData = d;
        unit.setPadding(0, 0, 0, dpi(8));
        big.addView(unit);
        big.addView(spacer());
        LinearLayout rt = new LinearLayout(this);
        rt.setOrientation(LinearLayout.VERTICAL);
        rt.setGravity(Gravity.END);
        rt.addView(caps("BEAT TO BEAT"));
        String hrv = "\u2014";
        String hrvSrc = "No ECG reading yet";
        for (HealthModel.Reading r : d.readings) if (!"-".equals(r.hrv)) { hrv = r.hrv + " ms"; hrvSrc = "From the " + r.when + " ECG"; break; }
        rt.addView(tv(hrv, 24, INK, 300));
        big.addView(rt);
        col.addView(big, lp(-1, -2, 8));
        col.addView(tv(shownHr(d) > 0 && !Double.isNaN(day.restingHr) ? (shownHr(d) > day.restingHr + 8 ? "Above your lowest 5-minute rate of " : "Close to your lowest 5-minute rate of ") + Math.round(day.restingHr) + ". " + hrvSrc + "." : hrvSrc + ".", 12, MUT, 400), lp(-2, -2, 6));

        try {                                                                  // 0.61.0: the answer first, against the person's own earlier nights
            List<TodayModel.NightStat> an = restNights();
            if (!an.isEmpty()) {
                TodayModel.NightStat lt = an.get(an.size() - 1);
                UxLogic.Range r5 = UxLogic.range(nightVals(an, an.size() - 1, 1), lt.hrLow5);
                String ans = UxLogic.heartAnswer(nightWhen(lt), lt.hrLow5, r5);
                if (ans != null) col.addView(answerLine(ans), lp(-1, -2, 12));
                else if (!Double.isNaN(lt.hrLow5)) col.addView(tv("No comparison yet: your usual range needs " + UxLogic.RANGE_MIN_N + " earlier nights with a heart-rate low, and there " + (r5.n == 1 ? "is " : "are ") + r5.n + ".", 12, MUT, 400), lp(-2, -2, 10));
            }
        } catch (Throwable ignored) { }
        LinearLayout hrc = explainerCard("ABOUT THE HEART RATE", "How it is measured, what the zones mean and when to trust it");
        expandable("h_hr", hrc, new Filler() { @Override public void fill(LinearLayout p) { fillHrNow(p); } });
        col.addView(hrc, lp(-1, -2, 10));
        LinearLayout c = card(16);
        LinearLayout h = hrow();
        h.addView(caps("TODAY \u00b7 LAST 24 HOURS"));
        h.addView(spacer());
        h.addView(tv(d.hr24Max > 0 ? d.hr24Min + " low \u00b7 " + Math.round(d.hr24Mean) + " avg \u00b7 " + d.hr24Max + " high" : "", 12, MUT, 400));
        c.addView(h);
        View chartView = new HrChartView(this, day.hrT, day.hrV, day.night == null ? 0 : day.night.bedTs, day.night == null ? 0 : day.night.endTs, true);
        chartView.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(2); } });
        c.addView(chartView, lp(-1, dpi(170), 8));
        LinearLayout chipsRow = hrow();
        for (int ri = 0; ri < HeartGraph.RANGE_LABEL.length; ri++) {
            final int rIdx = ri;
            TextView chp = HeartGraph.pill(this, HeartGraph.RANGE_LABEL[ri], 0x1AFFFFFF, INK);
            chp.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openGraph(rIdx); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.rightMargin = dpi(8);
            chipsRow.addView(chp, cl);
        }
        chipsRow.addView(spacer());
        chipsRow.addView(tv("Full graph \u203a", 12, BLUE, 400));
        c.addView(chipsRow, lp(-1, -2, 10));
        expandable("h_chart", c, new Filler() { @Override public void fill(LinearLayout p) { fillH24Panel(p); } });
        col.addView(c, lp(-1, -2, 14));

        LinearLayout z = card(16);
        z.addView(caps("WHERE THE TIME WAS SPENT"));
        z.addView(new ZoneBarView(this, d.zoneSec), lp(-1, dpi(10), 12));
        int mh = maxHr();
        String[] nm = {"Resting", "Easy", "Steady", "Hard", "All out"};
        int[] colz = {0xFF6A73F2, 0xFF4CB4FF, 0xFF2EE0B8, 0xFFF2B35B, 0xFFFF6B6B};
        int[] ze = HealthModel.zoneEdges(mh);
        int[] edge = {0, ze[0], ze[1], ze[2], ze[3]};
        for (int i = 0; i < 5; i++) {
            if (i > 2 && d.zoneSec[i] == 0 && i == 4) continue;
            LinearLayout r = hrow();
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(colz[i]); dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackground(dg);
            r.addView(dot, new LinearLayout.LayoutParams(dpi(8), dpi(8)));
            TextView a = tv("  " + nm[i], 13, INK, 400);
            r.addView(a, new LinearLayout.LayoutParams(dpi(96), -2));
            r.addView(tv(i == 0 ? "under " + edge[1] : edge[i] + (i < 4 ? " to " + edge[i + 1] : " and over"), 12, MUT, 400));
            r.addView(spacer());
            r.addView(tv(d.zoneSec[i] == 0 ? "\u2014" : HealthModel.hm(d.zoneSec[i] / 3600.0), 13, INK, 600));
            z.addView(r, lp(-1, dpi(26), i == 0 ? 10 : 0));
        }
        expandable("h_zones", z, new Filler() { @Override public void fill(LinearLayout p) { fillZonesPanel(p); } });
        col.addView(z, lp(-1, -2, 12));

        LinearLayout bb = card(16);
        LinearLayout bh = hrow();
        bh.addView(caps("BEAT TO BEAT"));
        bh.addView(spacer());
        double lastOpt = Double.NaN;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) lastOpt = ns.rmssd;
        bh.addView(tv(Double.isNaN(lastOpt) ? "" : "last night " + Math.round(lastOpt) + " ms, optical", 12, MUT, 400));
        bb.addView(bh);
        LinearLayout two = hrow();
        LinearLayout lcol = new LinearLayout(this);
        lcol.setOrientation(LinearLayout.VERTICAL);
        lcol.addView(caps("FROM THE ECG"));
        lcol.addView(tv(hrv, 26, INK, 300));
        lcol.addView(tv(hrvSrc, 11, MUT, 400));
        two.addView(lcol, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout rcol = new LinearLayout(this);
        rcol.setOrientation(LinearLayout.VERTICAL);
        rcol.addView(caps("FROM THE WRIST (OPTICAL)"));
        rcol.addView(tv(Double.isNaN(lastOpt) ? "\u2014" : Math.round(lastOpt) + " ms", 26, INK, 300));
        rcol.addView(tv("overnight RMSSD; runs about 1.8 times the chest strap's", 11, MUT, 400));
        two.addView(rcol, new LinearLayout.LayoutParams(0, -2, 1f));
        bb.addView(two, lp(-1, -2, 10));
        int withRm = 0;
        for (TodayModel.NightStat ns : d.nights) if (!Double.isNaN(ns.rmssd)) withRm++;
        if (withRm >= 2) bb.addView(new NightsLineView(this, d.nights, true), lp(-1, dpi(90), 10));
        expandable("h_b2b", bb, new Filler() { @Override public void fill(LinearLayout p) { fillB2BPanel(p); } });
        col.addView(bb, lp(-1, -2, 12));

        LinearLayout rd = card(16);
        rd.addView(tv("A reading, now", 16, INK, 600));
        TextView t = tv("About three minutes with two fingers on the clasp. An ECG reading gives beat-to-beat figures you can set beside the overnight ones.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dpi(6), 0, 0);
        rd.addView(t);
        rd.addView(pillButton("Start an ECG reading", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), lp(-1, dpi(42), 12));
        expandable("h_read", rd, new Filler() { @Override public void fill(LinearLayout p) { fillEcgPanel(p); } });
        col.addView(rd, lp(-1, -2, 12));

        // heart over time
        LinearLayout ov = card(16);
        LinearLayout oh = hrow();
        oh.addView(caps("HEART OVER TIME"));
        oh.addView(spacer());
        int nn = d.nights.size();
        oh.addView(tv(nn + (nn == 1 ? " night" : " nights") + " logged", 12, nn >= 14 ? MUT : GOLD, 400));
        ov.addView(oh);
        if (nn >= 2) ov.addView(new NightsLineView(this, d.nights, false), lp(-1, dpi(110), 10));
        TextView note = tv(nn >= 2 ? "Lowest five-minute heart rate each night. Optical HRV is in the Rest tab." : "The line draws itself from the second night on. Last night's lowest five-minute heart rate was " + (Double.isNaN(day.restingHr) ? "\u2014" : String.valueOf(Math.round(day.restingHr))) + ".", 12, MUT, 400);
        note.setPadding(0, dpi(8), 0, 0);
        ov.addView(note);
        expandable("h_over", ov, new Filler() { @Override public void fill(LinearLayout p) { fillOverTimePanel(p); } });
        col.addView(ov, lp(-1, -2, 12));
        return wrap(col);
    }

    /** Lowest five-minute heart rate per logged night, newest on the right. */
    static final class NightsLineView extends View {
        private final List<TodayModel.NightStat> nights;
        private final boolean rm;
        NightsLineView(Context c, List<TodayModel.NightStat> nights, boolean rmssd) { super(c); this.nights = nights; this.rm = rmssd; }
        private double val(TodayModel.NightStat s) { return rm ? s.rmssd : s.hrLow5; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            List<TodayModel.NightStat> use = new ArrayList<>();
            for (TodayModel.NightStat s : nights) if (!Double.isNaN(val(s))) use.add(s);
            if (use.size() > 14) use = use.subList(use.size() - 14, use.size());
            if (use.size() < 2) return;
            float w = getWidth(), h = getHeight() - dp(c, 14);
            double lo = 1e9, hi = -1e9;
            for (TodayModel.NightStat s : use) { lo = Math.min(lo, val(s)); hi = Math.max(hi, val(s)); }
            lo = Math.floor(lo - 2); hi = Math.ceil(hi + 2);
            if (hi - lo < 8) { hi = lo + 8; }
            List<float[]> pts = new ArrayList<>();
            for (int i = 0; i < use.size(); i++) pts.add(new float[] {dp(c, 8) + (w - dp(c, 16)) * i / (use.size() - 1f), (float) (h * (1 - (val(use.get(i)) - lo) / (hi - lo)))});
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            g.setColor(0x12FFFFFF); g.setStrokeWidth(dp(c, 1));
            for (int k = 0; k < 4; k++) cv.drawLine(0, h * k / 3f, w, h * k / 3f, g);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(dp(c, 2.2f)); ln.setStrokeCap(Paint.Cap.ROUND);
            ln.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF7A93, 0xFFFFC36B, Shader.TileMode.CLAMP));
            cv.drawPath(smooth(pts), ln);
            for (float[] p : pts) { cv.drawCircle(p[0], p[1], dp(c, 5), paint(0x40FF7A93)); cv.drawCircle(p[0], p[1], dp(c, 2.6f), paint(0xFFFFFFFF)); }
            Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setColor(MUT); tx.setTextSize(dp(c, 10)); tx.setTypeface(font(c, 400));
            SimpleDateFormat f = new SimpleDateFormat("d MMM", Locale.UK);
            tx.setTextAlign(Paint.Align.LEFT);
            cv.drawText(f.format(new Date(use.get(0).bed * 1000L)), 0, getHeight() - dp(c, 2), tx);
            tx.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(f.format(new Date(use.get(use.size() - 1).bed * 1000L)), w, getHeight() - dp(c, 2), tx);
        }
    }

    // ------------------------------------------------------------------ alarms (0.43.6)
    /** One small row on the Today page; everything is set inside the sheet it opens. The alarm buzzes the MG, not the phone. */
    private View alarmCard() {
        LinearLayout c = card(12);
        LinearLayout r = hrow();
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        left.addView(tv("Alarms", 14, INK, 600));
        ReminderTime.Cfg g = EcgReminder.load(this);
        String sub;
        if (!g.on) sub = "Off. Set a morning and an evening buzz for your MG.";
        else {
            long[] nx = ReminderTime.next(System.currentTimeMillis(), java.util.TimeZone.getDefault(), g);
            sub = nx == null ? "On" : "Next: " + new java.text.SimpleDateFormat("EEE HH:mm", Locale.UK).format(new java.util.Date(nx[0])) + "  \u00b7  buzzes your MG";
        }
        left.addView(tv(sub, 11, MUT, 400), lp(-2, -2, 2));
        r.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView btn = HeartGraph.pill(this, "Set", 0x1A2FA8F0, BLUE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.leftMargin = dpi(10); r.addView(btn, bp);
        c.addView(r);
        View.OnClickListener open = new View.OnClickListener() { @Override public void onClick(View v) { openAlarmSheet(); } };
        c.setOnClickListener(open); btn.setOnClickListener(open);
        return c;
    }

    private android.app.Dialog alarmDlg;

    private void openAlarmSheet() {
        try {
            final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
            fillAlarmSheet(box);
            final android.app.Dialog d = new android.app.Dialog(this);
            alarmDlg = d;
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout outer = card(18); outer.setBackground(glass(this, 26));
            LinearLayout hd = hrow();
            hd.addView(tv("Alarms", 22, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView done = tv("Done", 15, BLUE, 600); done.setPadding(dpi(12), dpi(8), dpi(4), dpi(8));
            done.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
            hd.addView(done);
            outer.addView(hd);
            ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(box, new ViewGroup.LayoutParams(-1, -2));
            outer.addView(sv, lp(-1, -2, 8));
            d.setContentView(outer);
            d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface x) { alarmDlg = null; try { refreshAlarmCardSoon(); } catch (Throwable ignored) { } } });
            android.view.Window w = d.getWindow();
            if (w != null) { w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); w.setLayout(-1, -2); w.setGravity(Gravity.BOTTOM); }
            d.show();
        } catch (Throwable t) { android.widget.Toast.makeText(this, "Could not open the alarms: " + t.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
    }

    /** The Today page is rebuilt so the small row shows the new next alarm. */
    private void refreshAlarmCardSoon() { try { if (tab == 0) render(); } catch (Throwable ignored) { } }

    private void fillAlarmSheet(final LinearLayout box) {
        box.removeAllViews();
        final ReminderTime.Cfg g = EcgReminder.load(this);
        box.addView(caps("MONDAY TO FRIDAY"), lp(-2, -2, 14));
        alarmRow(box, g, "Morning", 0); alarmRow(box, g, "Evening", 1);
        box.addView(caps("SATURDAY AND SUNDAY"), lp(-2, -2, 18));
        alarmRow(box, g, "Morning", 2); alarmRow(box, g, "Evening", 3);
        box.addView(caps("THE BUZZ"), lp(-2, -2, 18));
        final android.content.SharedPreferences ep = getSharedPreferences("ecg_reminder", MODE_PRIVATE);
        switchRow(box, "Buzz the MG", ep.getBoolean("buzz_mg", true), "buzz_mg", ep, true);
        LinearLayout lr = hrow(); lr.setPadding(0, dpi(12), 0, dpi(2));
        LinearLayout lleft = new LinearLayout(this); lleft.setOrientation(LinearLayout.VERTICAL);
        lleft.addView(tv("Keep buzzing for up to", 14, INK, 400)); lleft.addView(tv("It stops when you double-tap the MG", 11, MUT, 400), lp(-2, -2, 2));
        lr.addView(lleft, new LinearLayout.LayoutParams(0, -2, 1f));
        int mins = ep.getInt("buzz_max_min", 5);
        TextView lv = HeartGraph.pill(this, mins + " min  \u25be", 0x0DFFFFFF, INK);
        lv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            new android.app.AlertDialog.Builder(HealthActivity.this).setTitle("Keep buzzing for up to").setItems(new String[] { "1 min", "3 min", "5 min", "10 min" }, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface dd, int w) {
                ep.edit().putInt("buzz_max_min", w == 0 ? 1 : w == 1 ? 3 : w == 2 ? 5 : 10).apply(); fillAlarmSheet(box); } }).show(); } });
        lr.addView(lv); box.addView(lr);
        switchRow(box, "Also buzz this phone", ep.getBoolean("buzz_phone", false), "buzz_phone", ep, false);
        boolean linked = MainActivity.linkRunning();
        TextView lk = tv("MG link: " + (linked ? "connected" : "not connected now. At alarm time the app reconnects first (up to 30 s)."), 11, linked ? GREEN : MUT, 400);
        lk.setPadding(0, dpi(10), 0, 0); box.addView(lk);
        TextView t1 = tv("\u25b8  Test the MG buzz", 13, BLUE, 600); t1.setPadding(0, dpi(14), 0, dpi(2));
        t1.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { testMgBuzz(); } });
        box.addView(t1);
        TextView t2 = tv("\u25b8  Send a test alarm now", 13, BLUE, 600); t2.setPadding(0, dpi(10), 0, dpi(2));
        t2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askNotify(); EcgReminder.post(getApplicationContext(), true); } });
        box.addView(t2);
        long[] nx = ReminderTime.next(System.currentTimeMillis(), java.util.TimeZone.getDefault(), EcgReminder.load(this));
        TextView note = tv((nx == null ? "No alarm is on." : "Next: " + new java.text.SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new java.util.Date(nx[0])) + ".") + " An alarm always goes off at its time. Double-tap the MG to stop the buzz, or use Stop buzz or Snooze 30 min in the note.", 11, MUT, 400);
        note.setLineSpacing(0, 1.2f); note.setPadding(0, dpi(14), 0, 0); box.addView(note);
        if (!EcgReminder.exactOk(this)) {
            TextView ex = tv("Exact times need \"Alarms & reminders\" allowed for this app: tap here.", 11, GOLD, 400); ex.setPadding(0, dpi(8), 0, 0);
            ex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { } } });
            box.addView(ex);
        }
    }

    private void alarmRow(final LinearLayout box, ReminderTime.Cfg g, String label, final int slot) {
        final int m = g.t[slot]; final boolean on = m >= 0; final int def = new ReminderTime.Cfg().t[slot];
        LinearLayout r = hrow(); r.setPadding(0, dpi(10), 0, dpi(2));
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView time = tv(ReminderTime.hhmm(on ? m : def), 26, on ? INK : MUT, 300);
        time.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            int cur = on ? m : def;
            new android.app.TimePickerDialog(HealthActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() { @Override public void onTimeSet(android.widget.TimePicker tp, int hh, int mm) {
                EcgReminder.setSlot(HealthActivity.this, slot, hh * 60 + mm); askNotify(); fillAlarmSheet(box); } }, cur / 60, cur % 60, true).show(); } });
        r.addView(time);
        TextView sw = HeartGraph.pill(this, on ? "On" : "Off", on ? 0x1A2EE6A8 : 0x0DFFFFFF, on ? 0xFF2EE6A8 : MUT);
        sw.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { EcgReminder.setSlot(HealthActivity.this, slot, on ? -1 : def); if (!on) askNotify(); fillAlarmSheet(box); } });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.leftMargin = dpi(12); r.addView(sw, sp);
        box.addView(r);
    }

    private void switchRow(final LinearLayout box, String label, final boolean on, final String key, final android.content.SharedPreferences ep, final boolean dflt) {
        LinearLayout r = hrow(); r.setPadding(0, dpi(12), 0, dpi(2));
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sw = HeartGraph.pill(this, on ? "On" : "Off", on ? 0x1A2EE6A8 : 0x0DFFFFFF, on ? 0xFF2EE6A8 : MUT);
        sw.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ep.edit().putBoolean(key, !on).apply(); fillAlarmSheet(box); } });
        r.addView(sw); box.addView(r);
    }

    /** Sends one short buzz to the MG (byte-verified command, OpenStrap) and asks whether it buzzed. */
    private void testMgBuzz() {
        if (!MainActivity.buzzStrap(1)) { android.widget.Toast.makeText(this, "The MG is not connected now. Press Sync now on the Today page, wait a few seconds and try again.", android.widget.Toast.LENGTH_LONG).show(); return; }
        android.widget.Toast.makeText(this, "Buzz sent. Watch your MG.", android.widget.Toast.LENGTH_SHORT).show();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
            if (isFinishing()) return;
            new android.app.AlertDialog.Builder(HealthActivity.this).setTitle("Did your MG buzz?")
                .setPositiveButton("Yes", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { android.widget.Toast.makeText(HealthActivity.this, "Good. The alarms use this buzz.", android.widget.Toast.LENGTH_LONG).show(); } })
                .setNegativeButton("No", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { android.widget.Toast.makeText(HealthActivity.this, "No buzz. Export the data and send it to me: the strap's reply is in the log.", android.widget.Toast.LENGTH_LONG).show(); } })
                .show();
        } }, 3000L);
    }

    private void askNotify() { if (android.os.Build.VERSION.SDK_INT >= 33) { try { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 4108); } catch (Throwable ignored) { } } }

    // ------------------------------------------------------------------ ECG
    // 0.43.5 (6 Nov 2026): tidied in the style of the Today page. No count of readings any more (it said "90 readings"); one Start button, the latest reading as the status card with Send and the three rings, This week, the readings by day, and every tool under one "More tools" link.
    private View ecgTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        int n = d == null ? 0 : d.readings.size();
        col.addView(head("ECG", n == 0 ? "Take your first reading" : "Ready for an ECG", null));
        col.addView(pillButton("Start an ECG", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { startEcg(); } }), lp(-1, Math.max(dpi(54), tapMin()), 16));
        try {                                                                  // 0.61.0: the answer first
            long nowMs = System.currentTimeMillis(); int n7 = 0, ab = 0;
            if (d != null) for (HealthModel.Reading rr : d.readings) {
                if (rr.timeMs < nowMs - 7L * 86400000L) continue;
                n7++;
                String gg = rr.grade == null ? "-" : rr.grade.toUpperCase(Locale.UK);
                if (gg.startsWith("A") || gg.startsWith("B")) ab++;
            }
            if (n > 0) {
                HealthModel.Reading l0 = d.readings.get(0);
                int hr0 = 0; try { hr0 = Integer.parseInt(l0.hr.trim()); } catch (Throwable ignored) { }
                String g0 = l0.grade == null ? "-" : l0.grade.toUpperCase(Locale.UK);
                col.addView(answerLine(UxLogic.ecgAnswer(n7, ab, l0.when, hr0, g0.length() > 0 ? g0.charAt(0) : ' ')), lp(-1, -2, 12));
            }
        } catch (Throwable ignored) { }
        if (n == 0) col.addView(tv("Two fingers on the clasp, forearm on a table. A free reading runs about two and a half minutes: enough for a rhythm check.", 14, INK, 400), lp(-2, -2, 14));
        try {                                                              // the latest reading: the status card with Send, then the three rings (like the Today page)
            if (n > 0 && d.readings.get(0).file != null) {
                final HealthModel.Reading r0 = d.readings.get(0);
                LinearLayout sc = card(12); LinearLayout row = hrow();
                LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
                left.addView(tv("Latest reading  \u00b7  " + r0.when, 14, INK, 600));
                StringBuilder sub = new StringBuilder();
                if (!"-".equals(r0.hr)) sub.append("HR ").append(r0.hr).append(" bpm");
                if (!"-".equals(r0.hrv)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append("HRV ").append(r0.hrv).append(" ms");
                if (!"-".equals(r0.rhythm)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append(r0.rhythm);
                left.addView(tv(sub.length() == 0 ? "Tap to open the report" : sub.toString(), 11, MUT, 400), lp(-2, -2, 2));
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView send = HeartGraph.pill(this, "Send", 0x1A2EE6A8, 0xFF2EE6A8);
                SendFlow.attach(this, send, ProReportMaker.unixOf(r0.file), r0.file);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2); sp.leftMargin = dpi(10); row.addView(send, sp);
                sc.addView(row);
                sc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openReport(r0.file); } });
                col.addView(sc, lp(-1, -2, 14));
                double hr = Double.NaN, hv = Double.NaN; try { hr = Double.parseDouble(r0.hr); } catch (Throwable ignored) { } try { hv = Double.parseDouble(r0.hrv); } catch (Throwable ignored) { }
                String g = r0.grade == null ? "-" : r0.grade.toUpperCase(Locale.UK); float gf = g.startsWith("A") ? 1f : g.startsWith("B") ? 0.75f : g.startsWith("C") ? 0.5f : g.startsWith("D") ? 0.25f : 0f;
                int gc = g.startsWith("A") || g.startsWith("B") ? GREEN : g.startsWith("C") ? GOLD : MUT;
                float[] rf = { Double.isNaN(hr) ? 0f : (float) Math.min(1.0, hr / 167.0), Double.isNaN(hv) ? 0f : (float) Math.min(1.0, hv / 100.0), gf };
                int[] rc = { HealthViews.ROSE, BLUE, gc };
                String[] rb = { Double.isNaN(hr) ? "\u2014" : String.valueOf(Math.round(hr)), Double.isNaN(hv) ? "\u2014" : String.valueOf(Math.round(hv)), "-".equals(g) ? "\u2014" : g };
                String[] rl = { "HEART RATE", "HRV", "GRADE" };
                String[] rcap = { "bpm", "RMSSD, ms", "report quality" };
                col.addView(new DailyViews.RingsView(this, rf, rc, rb, rl, rcap), lp(-1, dpi(196), 6));
            }
        } catch (Throwable ignored) { }
        try {                                                              // this week and the streak
            long[] st = new long[n]; for (int i = 0; i < n; i++) st[i] = d.readings.get(i).timeMs / 1000L;
            EcgStreak.Out so = EcgStreak.compute(st, System.currentTimeMillis(), java.util.TimeZone.getDefault());
            col.addView(rowCard("This week", EcgStreak.strip(so) + "\n" + EcgStreak.summary(so) + "\nTap for how this is worked out.", new View.OnClickListener() { @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(HealthActivity.this).setTitle("This week and the streak").setMessage(Explain.asText(Explain.streakCard())).setPositiveButton("OK", null).show(); } }), lp(-1, -2, 12));
        } catch (Throwable ignored) { }
        long lastEnd = NewLookActivity.lastReadingEndMs;                   // a reading just ended: the report is being made
        if (lastEnd > 0 && System.currentTimeMillis() - lastEnd < 6 * 60000L && (n == 0 || d.readings.get(0).timeMs < lastEnd - 300000L)) {
            col.addView(rowCard("Preparing your report\u2026", "This list fills in by itself.", null), lp(-1, -2, 12));
        }
        if (d != null) {
            // 0.27.0: readings grouped by day, newest first. Today and yesterday open; older days folded (tap a day to open or close it).
            java.text.SimpleDateFormat dk = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.UK), dl = new java.text.SimpleDateFormat("EEE d MMM", Locale.UK);
            String today = dk.format(new java.util.Date()), yest = dk.format(new java.util.Date(System.currentTimeMillis() - 86400000L));
            java.util.LinkedHashMap<String, java.util.List<HealthModel.Reading>> days = new java.util.LinkedHashMap<>();
            for (HealthModel.Reading r : d.readings) { long u = readingUnix(r); String k = u > 0 ? dk.format(new java.util.Date(u * 1000L)) : "undated"; java.util.List<HealthModel.Reading> l = days.get(k); if (l == null) { l = new java.util.ArrayList<>(); days.put(k, l); } l.add(r); }
            for (java.util.Map.Entry<String, java.util.List<HealthModel.Reading>> e : days.entrySet()) {
                final String k = e.getKey(); java.util.List<HealthModel.Reading> rs = e.getValue();
                String label = k.equals(today) ? "Today" : k.equals(yest) ? "Yesterday" : k.equals("undated") ? "Undated" : k;
                if (!k.equals(today) && !k.equals(yest) && !k.equals("undated")) try { label = dl.format(dk.parse(k)); } catch (Exception ignored) { }
                String best = null; for (HealthModel.Reading r : rs) if (r.grade != null && !"-".equals(r.grade)) { String g = r.grade.toUpperCase(Locale.UK); if (best == null || g.compareTo(best) < 0) best = g; }
                boolean open = openDays.contains(k) || (!closedDays.contains(k) && (k.equals(today) || k.equals(yest)));
                final String head = label + (best != null ? "  \u00b7  best grade " + best : "");
                final TextView ht = tv(head + (open ? "   \u25be" : "   \u25b8"), 13, INK, 600); ht.setPadding(dpi(4), dpi(10), dpi(4), dpi(6));
                final LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setVisibility(open ? View.VISIBLE : View.GONE);
                for (final HealthModel.Reading r : rs) box.addView(readingCard(r), lp(-1, -2, 12));
                ht.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean nowOpen = box.getVisibility() != View.VISIBLE; box.setVisibility(nowOpen ? View.VISIBLE : View.GONE); ht.setText(head + (nowOpen ? "   \u25be" : "   \u25b8")); if (nowOpen) { openDays.add(k); closedDays.remove(k); } else { closedDays.add(k); openDays.remove(k); } } });
                col.addView(ht, lp(-1, -2, 2)); col.addView(box, lp(-1, -2, 4));
            }
        }
        // every tool under one link
        LinearLayout tc = card(16);
        tc.addView(caps("TOOLS"));
        tc.addView(tv("Reminders, the professional PDF, the readings table, export and the strap's stored data", 12, MUT, 400), lp(-2, -2, 6));
        final HealthModel.Data dd = d;
        expandable("e_tools", tc, new Filler() { @Override public void fill(LinearLayout p) {
            p.addView(pillButton("Reminders: set the times for work days and days off", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { EcgReminder.showDialog(HealthActivity.this); } }), lp(-1, dpi(44), 10));
            if (dd != null && !dd.readings.isEmpty() && dd.readings.get(0).file != null) {
                final java.io.File newest = dd.readings.get(0).file;
                p.addView(pillButton("Professional PDF of the newest reading", 0x1A2EE6A8, 0xFF2EE6A8, new View.OnClickListener() { @Override public void onClick(View v) { ProReportMaker.make(HealthActivity.this, newest); } }), lp(-1, dpi(44), 8));
            }
            p.addView(pillButton("Readings table: R, T, QTc and context", 0x1A2FA8F0, BLUE, new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) ReadingsTable.show(HealthActivity.this, data); } }), lp(-1, dpi(44), 8));
            p.addView(pillButton("Save the analysis code (Python and Java) to Downloads", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { ProReportMaker.saveCode(HealthActivity.this); } }), lp(-1, dpi(44), 8));
            p.addView(pillButton("Recover readings from the strap's stored data", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { StoredReading.show(HealthActivity.this); } }), lp(-1, dpi(44), 8));
            p.addView(pillButton("Blood pressure research capture", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { BpCapture.show(HealthActivity.this); } }), lp(-1, dpi(44), 8));
            p.addView(pillButton("Read strap settings (read-only)", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) {
                if (!MainActivity.requestDeviceSettings()) { android.widget.Toast.makeText(HealthActivity.this, "The strap is not connected.", android.widget.Toast.LENGTH_SHORT).show(); return; }
                android.widget.Toast.makeText(HealthActivity.this, "Reading 8 strap settings (about 6 s)...", android.widget.Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    new android.app.AlertDialog.Builder(HealthActivity.this).setTitle("Strap settings").setMessage(MainActivity.deviceSettingsText()).setPositiveButton("OK", null).show();
                } }, 6500L);
            } }), lp(-1, dpi(44), 8));
            p.addView(pillButton("Export ECG data for analysis", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { EcgExport.ask(HealthActivity.this); } }), lp(-1, dpi(44), 8));
            LinearLayout mc = explainerCard("HOW THE REPORT IS MADE AND CHECKED", "Method, agreement with the Python reference, and the database tests");
            expandable("e_method", mc, new Filler() { @Override public void fill(LinearLayout q) { rows(q, Explain.reportMethod()); } });
            p.addView(mc, lp(-1, -2, 12));
            LinearLayout egt = explainerCard("HOW TO GET A STRONG READING", "What the grades mean, finger placement, what the QRS height tells you");
            expandable("e_ecg", egt, new Filler() { @Override public void fill(LinearLayout q) { fillEcgPanel(q); } });
            p.addView(egt, lp(-1, -2, 10));
        } }, "More tools");
        col.addView(tc, lp(-1, -2, 16));
        col.addView(tv("Research only: not a diagnosis. Reports show how reliable each measurement was.", 11, MUT, 400), lp(-2, -2, 16));
        return wrap(col);
    }


    // 0.27.0: which days the reader opened or closed in this session
    private static final java.util.Set<String> openDays = new java.util.HashSet<>(), closedDays = new java.util.HashSet<>();
    private static long readingUnix(HealthModel.Reading r) { try { String n = r.file.getName(); java.util.regex.Matcher m = java.util.regex.Pattern.compile("(1[6-9][0-9]{8})").matcher(n); if (m.find()) return Long.parseLong(m.group(1)); return r.file.lastModified() / 1000L; } catch (Throwable t) { return 0; } }
    private View readingCard(final HealthModel.Reading r) {
                LinearLayout c = card(16);
                LinearLayout h = hrow();
                h.addView(tv(r.when, 15, INK, 600));
                h.addView(spacer());
                if (!"-".equals(r.grade)) {
                    String gr = r.grade.toUpperCase(Locale.UK);
                    h.addView(chipSmall("GRADE " + gr, gr.startsWith("A") || gr.startsWith("B") ? GREEN : (gr.startsWith("C") ? GOLD : MUT)));
                }
                c.addView(h);
                StringBuilder sub = new StringBuilder();
                if (!"-".equals(r.hr)) sub.append("HR ").append(r.hr).append(" bpm");
                if (!"-".equals(r.hrv)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append("HRV ").append(r.hrv).append(" ms");
                if (!"-".equals(r.rhythm)) sub.append(sub.length() > 0 ? " \u00b7 " : "").append(r.rhythm);
                TextView s = tv(sub.length() == 0 ? "Open to see why" : sub.toString(), 12.5f, MUT, 400);
                s.setPadding(0, dpi(4), 0, 0);
                c.addView(s);
                c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openReport(r.file); } });
                return c;
    }

    private View chipSmall(String s, int color) {
        TextView t = tv(s, 9.5f, color, 600);
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dpi(11), dpi(5), dpi(11), dpi(5));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(this, 13)); g.setStroke(dpi(1), 0x0FFFFFFF);
        t.setBackground(g);
        return t;
    }

    // ------------------------------------------------------------------ REST
    // 0.53.1: the Rest page shows ONE night at a time: the latest (restSel 0) or an earlier one; swipe left for the previous night, right for the next; the ‹ › buttons and the date do the same
    private int restSel = 0; private HealthModel.Data nightOwner; private final java.util.HashMap<Long, HealthModel.Data> nightCache = new java.util.HashMap<>(); private final java.util.HashSet<Long> nightMiss = new java.util.HashSet<>(); private long nightLoading = 0;

    private List<TodayModel.NightStat> restNights() {
        List<TodayModel.NightStat> out = new ArrayList<>();
        if (data == null) return out;
        for (TodayModel.NightStat s : Trends.allNights(data)) if (!Double.isNaN(s.tstH)) out.add(s);
        return out;                                                   // oldest first; the latest night is last
    }

    private View restTab() {
        HealthModel.Data base = data;
        if (base == null || base.day == null || base.day.night == null) {
            LinearLayout col = screen();
            col.addView(head("Last night", "Rest", null));
            col.addView(tv("No complete night in the records yet. A night needs the strap worn through sleep with the day logger on; it shows here after the morning history pull.", 14, MUT, 400), lp(-2, -2, 16));
            addRestResearch(col, new View[1]);      // 0.71.0: the sleep research does not need a complete night
            return wrap(col);
        }
        if (nightOwner != base) { nightCache.clear(); nightMiss.clear(); nightOwner = base; nightLoading = 0; }
        List<TodayModel.NightStat> all = restNights();
        if (restSel >= all.size()) restSel = Math.max(0, all.size() - 1);
        if (restSel > 0) {
            final TodayModel.NightStat tgt = all.get(all.size() - 1 - restSel);
            HealthModel.Data nd = nightCache.get(tgt.bed);
            if (nd != null) return restPage(nd, false, all);
            if (!nightMiss.contains(tgt.bed)) loadNight(tgt.bed);
            LinearLayout col = screen();
            col.addView(head(dayName(tgt.bed), "Rest", null));
            col.addView(restNav(all, tgt.bed), lp(-1, -2, 14));
            col.addView(tv(nightMiss.contains(tgt.bed) ? "That night could not be read from the day log." : "Reading that night\u2026", 14, MUT, 400), lp(-2, -2, 16));
            return swipeWrap(col);
        }
        return restPage(base, true, all);
    }

    /** 0.71.0: the SLEEP RESEARCH section at the bottom of the Rest page: regularity, your last nights, the 24 h heart-rate rhythm, the age studies, why no sleep-age number, the twelve sources (SleepResearch.fill, the same content as the You page screen). srHead[0] gets the heading, for the cards that jump to it. */
    private void addRestResearch(LinearLayout col, View[] srHead) {
        LinearLayout srHd = new LinearLayout(this); srHd.setOrientation(LinearLayout.VERTICAL);
        srHd.addView(caps("SLEEP RESEARCH"));
        srHd.addView(tv("Regularity, body clock and the studies behind them", 12.5f, MUT, 400), lp(-2, -2, 4));
        col.addView(srHd, lp(-1, -2, 28));
        srHead[0] = srHd;
        final long nowSr = System.currentTimeMillis();
        final LinearLayout box;
        if (srBox != null && nowSr - srBoxAt < 600000L) {
            box = srBox;
            if (box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
        } else {
            box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
            final TextView srStat = tv("Reading the last 14 days from the day log\u2026", 13, MUT, 400);
            box.addView(srStat, lp(-2, -2, 12));
            srBox = box; srBoxAt = nowSr;
            final Context appSr = getApplicationContext();
            new Thread(new Runnable() { @Override public void run() {
                RhythmData.Bundle bs = null;
                try { bs = RhythmData.bundleCached(appSr); } catch (Throwable ignored) { }
                final RhythmData.Bundle bb = bs;
                runOnUiThread(new Runnable() { @Override public void run() {
                    try { box.removeView(srStat); SleepResearch.fill(HealthActivity.this, box, bb); }
                    catch (Throwable t) { srStat.setText("Could not draw the sleep research (" + t.getClass().getSimpleName() + ")."); if (srBox == box) srBox = null; }
                } });
            } }, "rest-sleep-research").start();
        }
        col.addView(box, lp(-1, -2, 0));
    }

    /** 0.71.0: scrolls the Rest page so the given heading is near the top. */
    private void scrollRestTo(View target) {
        try {
            View c0 = content.getChildAt(0);
            if (target != null && c0 instanceof ScrollView) ((ScrollView) c0).smoothScrollTo(0, Math.max(0, target.getTop() - dpi(12)));
        } catch (Throwable ignored) { }
    }

    private static String dayName(long bed) { return new SimpleDateFormat("EEEE d MMMM", Locale.UK).format(new Date(bed * 1000L)); }

    private void loadNight(final long bed) {
        if (nightLoading == bed) return;
        nightLoading = bed; final HealthModel.Data base = data; final double g = goal();
        new Thread(new Runnable() { @Override public void run() {
            final HealthModel.Data nd = HealthModel.forNight(getApplicationContext(), base, bed, g);
            runOnUiThread(new Runnable() { @Override public void run() {
                if (nightOwner != base) return;
                if (nd != null) nightCache.put(bed, nd); else nightMiss.add(bed);
                if (nightLoading == bed) nightLoading = 0;
                if (!isFinishing() && tab == 3) render();
            } });
        } }, "night-load").start();
    }

    private void restShift(int olderBy) {
        List<TodayModel.NightStat> all = restNights(); int ns = Math.max(0, Math.min(all.size() - 1, restSel + olderBy));
        if (ns == restSel) return;
        restSel = ns; if (tab == 3) render();
    }

    /** The date strip of the Rest page: previous night, the date (tap = latest night), next night. */
    private View restNav(final List<TodayModel.NightStat> all, long bed) {
        LinearLayout nav = hrow(); nav.setGravity(Gravity.CENTER_VERTICAL); nav.setPadding(dpi(6), dpi(4), dpi(6), dpi(4)); nav.setBackground(glass(this, 22));
        TextView prev = tv("\u2039", 26, restSel < all.size() - 1 ? INK : 0xFF4A5362, 400); prev.setGravity(Gravity.CENTER);
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { restShift(1); } });
        nav.addView(prev, new LinearLayout.LayoutParams(dpi(52), dpi(44)));
        LinearLayout mid = new LinearLayout(this); mid.setOrientation(LinearLayout.VERTICAL); mid.setGravity(Gravity.CENTER);
        TextView lab = tv(new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(bed * 1000L)), 16, INK, 600); lab.setGravity(Gravity.CENTER); mid.addView(lab);
        TextView sub = tv(restSel == 0 ? "latest night" : "night " + (all.size() - restSel) + " of " + all.size() + "  \u00b7  tap for the latest", 11, MUT, 400); sub.setGravity(Gravity.CENTER); mid.addView(sub);
        mid.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { restShift(-restSel); } });
        nav.addView(mid, new LinearLayout.LayoutParams(0, dpi(44), 1f));
        TextView next = tv("\u203a", 26, restSel > 0 ? INK : 0xFF4A5362, 400); next.setGravity(Gravity.CENTER);
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { restShift(-1); } });
        nav.addView(next, new LinearLayout.LayoutParams(dpi(52), dpi(44)));
        return nav;
    }

    /** A scroll view that turns a quick horizontal swipe into night navigation: left = the previous (older) night, right = the next (newer) one. */
    private View swipeWrap(LinearLayout col) {
        ScrollView sv = new ScrollView(this) {
            private float x0, y0; private long t0;
            @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) { x0 = ev.getX(); y0 = ev.getY(); t0 = ev.getEventTime(); }
                else if (a == MotionEvent.ACTION_UP && ev.getEventTime() - t0 < 800) {
                    float dx = ev.getX() - x0, dy = ev.getY() - y0;
                    if (Math.abs(dx) > dpi(90) && Math.abs(dx) > 1.7f * Math.abs(dy)) { if (dx < 0) restShift(1); else restShift(-1); return true; }
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        sv.setFillViewport(true); sv.setVerticalScrollBarEnabled(false); sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private View restPage(final HealthModel.Data d, boolean latest, List<TodayModel.NightStat> all) {
        LinearLayout col = screen();
        final View[] srHead = new View[1];      // 0.71.0: the SLEEP RESEARCH heading, for the two cards that jump to it
        SleepNight.Night n = d.day.night;
        double goal = goal();
        col.addView(head(latest ? "Last night" : dayName(n.bedTs), "Rest", chip(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 0)));
        col.addView(restNav(all, n.bedTs), lp(-1, -2, 14));
        View rangeC = null;
        try {                                                                  // 0.61.0: this night against the earlier ones (up to 30 before it)
            int k = nightIdx(all, n.bedTs);
            if (k >= 0) {
                TodayModel.NightStat q = all.get(k);
                double rm = q.rmssd > 0 ? q.rmssd : Double.NaN;
                UxLogic.Range rT = UxLogic.range(nightVals(all, k, 0), n.tstH), rH = UxLogic.range(nightVals(all, k, 1), q.hrLow5), rV = UxLogic.range(nightVals(all, k, 2), rm);
                String ans = UxLogic.restAnswer(n.tstH, rT, q.hrLow5, rH, rm, rV);
                if (ans != null) col.addView(answerLine(ans), lp(-1, -2, 12));
                rangeC = rangeCard(n.tstH, rT, q.hrLow5, rH, rm, rV);
            }
        } catch (Throwable ignored) { }
        int rs = Math.min(screenW(), dpi(290));
        double need = Math.min(1.0, n.tstH / goal);
        View ring = new StageRingView(this, ringSegments(d, need), HealthModel.hm(n.tstH).replace(" ", ""), "ASLEEP");
        LinearLayout.LayoutParams rp = lp(rs, rs, 0);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(ring, rp);
        double diff = n.tstH - goal;
        TextView chipT = chip((diff < 0 ? HealthModel.hm(-diff) + " under" : HealthModel.hm(diff) + " over"), 0);
        chipT.setTextColor(0xFFBFE6FF);
        LinearLayout.LayoutParams cp = lp(-2, -2, -64);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(chipT, cp);
        TextView capt = tv("the ring closes at your need \u00b7 " + HealthModel.hm(goal), 12, MUT, 400);
        capt.setGravity(Gravity.CENTER);
        col.addView(capt, lp(-1, -2, d.day.napH > 0 ? 4 : 28));
        if (d.day.napH > 0) { TextView napT = tv("Nap " + HealthModel.hmShort((long) (d.day.napH * 3600)), 13, BLUE, 600); napT.setGravity(Gravity.CENTER); col.addView(napT, lp(-1, -2, 20)); }
        if (rangeC != null) col.addView(rangeC, lp(-1, -2, 12));
        long tot = 0;
        for (long s : d.stageSecs) tot += s;
        LinearLayout sh = card(16);
        LinearLayout hh = hrow();
        hh.addView(caps("THE SHAPE OF IT"));
        hh.addView(spacer());
        hh.addView(tv(hhmm(n.bedTs) + " to " + hhmm(n.endTs), 12, MUT, 400));
        sh.addView(hh);
        if (tot > 0) {
            sh.addView(stageBar(d.stageSecs), lp(-1, dpi(10), 12));
            TextView lg = tv("Deep " + HealthModel.hmShort(d.stageSecs[HealthModel.DEEP]) + " \u00b7 REM " + HealthModel.hmShort(d.stageSecs[HealthModel.REM]) + " \u00b7 Light " + HealthModel.hmShort(d.stageSecs[HealthModel.LIGHT]) + " \u00b7 estimate", 12, MUT, 400);
            lg.setPadding(0, dpi(8), 0, 0);
            sh.addView(lg);
            TextView sn = tv("Stages are an estimate from heart rate, movement and beat intervals. The asleep time above uses the strap's sleep state with quiet stretches credited, so the two can differ by 20 to 30 minutes.", 11.5f, MUT, 400);
            sh.addView(sn, lp(-1, -2, 8));
        } else {
            sh.addView(tv("The night was too short to stage.", 13, MUT, 400), lp(-2, -2, 10));
        }
        expandable("r_shape", sh, new Filler() { @Override public void fill(LinearLayout p) { fillShapePanel(p); } });
        col.addView(sh, lp(-1, -2, 12));

        if (tot > 0) {
            LinearLayout wn = card(16);
            LinearLayout wh = hrow();
            wh.addView(caps("THE WHOLE NIGHT"));
            wh.addView(spacer());
            wh.addView(chipSmall("ESTIMATE", GOLD));
            wn.addView(wh);
            wn.addView(new StageFlowView(this, d.binStage, d.binSure, d.binStart), lp(-1, dpi(140), 10));
            long[] s = d.stageSecs;
            String[] nm = {"AWAKE", "REM", "LIGHT", "DEEP"};
            int[] idx = {HealthModel.WAKE, HealthModel.REM, HealthModel.LIGHT, HealthModel.DEEP};
            for (int i = 0; i < 4; i++) {
                LinearLayout r = hrow();
                TextView a = tv(nm[i], 11, MUT, 600);
                a.setLetterSpacing(0.14f);
                r.addView(a, new LinearLayout.LayoutParams(dpi(58), -2));
                r.addView(pips((float) s[idx[i]] / tot, STAGE[idx[i]]), new LinearLayout.LayoutParams(0, dpi(6), 1f));
                TextView pc = tv(Math.round(100.0 * s[idx[i]] / tot) + "%", 12, STAGE[idx[i]], 400);
                pc.setGravity(Gravity.END);
                r.addView(pc, new LinearLayout.LayoutParams(dpi(44), -2));
                TextView tm = tv(HealthModel.hmShort(s[idx[i]]), 15, INK, 300);
                tm.setGravity(Gravity.END);
                r.addView(tm, new LinearLayout.LayoutParams(dpi(48), -2));
                wn.addView(r, lp(-1, dpi(30), i == 0 ? 8 : 0));
            }
            int sure = 0, allBins = 0;
            for (int i = 0; i < d.binStage.length; i++) if (d.binStage[i] >= 0) { allBins++; if (d.binSure[i]) sure++; }
            TextView note = tv("Solid blocks are where two staging methods agree (" + (allBins == 0 ? 0 : Math.round(100.0 * sure / allBins)) + "% of the night). Dashed blocks are where they differ. Nothing in a wrist band sees brain activity, so these are estimates, not a sleep study.", 11, MUT, 400);
            note.setLineSpacing(0, 1.2f);
            note.setPadding(0, dpi(10), 0, 0);
            wn.addView(note);
            expandable("r_night", wn, new Filler() { @Override public void fill(LinearLayout p) { fillWholeNightPanel(p); } });
            col.addView(wn, lp(-1, -2, 12));
        }
        // ---- 0.54.0: the strap's beat intervals for this night (500 ms filler, repeated beats, coverage) and the sleep-regularity card
        {
            final LinearLayout rrc = card(16);
            LinearLayout rrh = hrow();
            rrh.addView(caps("OVERNIGHT BEAT INTERVALS"));
            rrh.addView(spacer());
            final TextView rrBadge = tv("checking\u2026", 12, MUT, 600);
            rrh.addView(rrBadge);
            rrc.addView(rrh);
            final TextView rrBody = tv("Checking the strap's beat intervals for this night.", 12.5f, MUT, 400);
            rrBody.setLineSpacing(0, 1.25f);
            rrc.addView(rrBody, lp(-2, -2, 8));
            col.addView(rrc, lp(-1, -2, 12));
            final long bedF = n.bedTs, endF = n.endTs; final double storedRm = n.rmssd, allRm = n.rmssdAll, keptPct = n.rrKeptPct; final Context appc = getApplicationContext();
            new Thread(new Runnable() { @Override public void run() {
                final RrHygiene.Result rr = TrustData.nightRr(appc, bedF, endF);
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    rrBadge.setText(rr.badge()); rrBadge.setTextColor(rr.grade == 1 ? GREEN : rr.grade == 2 ? BLUE : rr.grade == 3 ? GOLD : MUT);
                    rrBody.setText(rr.line() + "\nOvernight RMSSD (optical): stored figure " + (Double.isNaN(storedRm) ? "-" : Math.round(storedRm) + " ms")
                            + ", from seconds the strap does not mark as degraded or pulse-lock-lost" + (Double.isNaN(keptPct) ? "" : " (" + Math.round(keptPct) + " % of the intervals)")
                            + (Double.isNaN(allRm) ? "" : "; with every second " + Math.round(allRm) + " ms")
                            + ". The 500 ms filler does not change the stored figure (checked on 23 sleeps of this strap). Recovery uses the stored figure.");
                } });
            } }, "night-rr").start();
            rrc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scrollRestTo(srHead[0]); } });      // 0.71.0: the research is on this page

            final LinearLayout src = card(16);
            LinearLayout srh = hrow();
            srh.addView(caps("SLEEP REGULARITY"));
            srh.addView(spacer());
            final TextView srVal = tv("\u2026", 20, INK, 300);
            srh.addView(srVal);
            src.addView(srh);
            final TextView srBody = tv("Reading the last 14 days.", 12.5f, MUT, 400);
            srBody.setLineSpacing(0, 1.25f);
            src.addView(srBody, lp(-2, -2, 6));
            src.addView(tv("Tap to jump to the sleep research at the bottom of this page", 12, BLUE, 400), lp(-2, -2, 8));
            col.addView(src, lp(-1, -2, 12));
            src.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scrollRestTo(srHead[0]); } });
            new Thread(new Runnable() { @Override public void run() {
                final RhythmData.Bundle bb = RhythmData.bundleCached(appc);
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    if (bb == null || bb.sri == null || Double.isNaN(bb.sri.sri)) { srVal.setText("\u2014"); srBody.setText("Needs two days with minutes logged a day apart. " + (bb == null || bb.sri == null ? 0 : bb.sri.validMinutes) + " minutes logged in the last 14 days."); return; }
                    srVal.setText(String.format(Locale.UK, "%.0f", bb.sri.sri));
                    srBody.setText(SleepRegularity.describe(bb.sri.sri) + (Double.isNaN(bb.sri.midSd) ? "" : ". Mid-sleep moves by " + Math.round(bb.sri.midSd) + " min (SD) over " + bb.sri.nights.size() + " nights") + ". UK Biobank: 5th percentile 41, median 60, 95th percentile 75.");
                } });
            } }, "sleep-reg").start();
        }
        col.addView(rowCard("The ring is " + Math.round(100 * need) + "% closed. See what the night was made of", "Stages are estimates from heart rate and movement", null), lp(-1, -2, 14));
        LinearLayout ringc = explainerCard("ABOUT THE RING", "What it fills towards and how the stages are estimated");
        final double goalF = goal;
        expandable("r_ring", ringc, new Filler() { @Override public void fill(LinearLayout p) { rows(p, Explain.sleepRing(goalF)); } });
        col.addView(ringc, lp(-1, -2, 10));

        LinearLayout w = card(16);
        w.addView(caps("YOUR LAST SEVEN"));
        w.addView(new WeekBarsView(this, d.lastSeven, d.lastSevenBed, goal), lp(-1, dpi(140), 12));
        expandable("r_week", w, new Filler() { @Override public void fill(LinearLayout p) { fillSleepWeekPanel(p); } });
        col.addView(w, lp(-1, -2, 12));

        if (tot > 0) {
            // 0.34.0: stage confidence and age check, architecture, and the by-night screen
            final double ageF = getSharedPreferences("healthspan", MODE_PRIVATE).getFloat("age", 53f);
            LinearLayout cf = card(16); cf.addView(caps("STAGE CONFIDENCE AND AGE CHECK"));
            for (String[] row : SleepArch.confidenceRows(d, ageF)) { if (row[0].equals("Reading")) note(cf, row[1]); else kv(cf, row[0], row[1]); }
            note(cf, "Totals are the app's estimate from heart rate and movement. Sure minutes are where a second pass using the strap's beat intervals agrees. Usual ranges: Ohayon 2004 (slow-wave share falls about 3 points a decade from 40; the age values are from a secondary summary) and Ebrahim 2013 for REM. On this strap the staging tends to over-call deep and REM, so read changes between nights rather than totals.");
            col.addView(cf, lp(-1, -2, 12));
            LinearLayout ar = card(16); ar.addView(caps(latest ? "ARCHITECTURE: LAST NIGHT" : "ARCHITECTURE: " + new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(n.bedTs * 1000L)).toUpperCase(Locale.UK)));
            for (String[] row : SleepArch.architectureRows(d, n.bedTs, n.endTs)) { if (row[0].equals("Read with care")) note(ar, row[1]); else kv(ar, row[0], row[1]); }
            col.addView(ar, lp(-1, -2, 12));
            col.addView(pillButton("Sleep architecture by night and alcohol check", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) SleepArch.show(HealthActivity.this, data); } }), lp(-1, dpi(44), 8));
        }
        if (d.day.spo2 != null && d.day.spo2.validWindows > 0) {
            LinearLayout so = card(16);
            LinearLayout sh2 = hrow();
            sh2.addView(caps("SPO2 \u00b7 STRAP ESTIMATE"));
            sh2.addView(spacer());
            sh2.addView(tv(d.day.spo2.median + "%", 20, 0xFF46E0C8, 300));
            so.addView(sh2);
            so.addView(new SpoView(this, d.spo2Windows), lp(-1, dpi(96), 10));
            TextView t = tv(d.day.spo2.validWindows + " windows, " + d.day.spo2.min + " to " + d.day.spo2.max + "%. " + (d.day.spo2.below94 == 0 ? "None below 94. " : d.day.spo2.below94 + " below 94. ") + "Not calibrated against an oximeter.", 12, MUT, 400);
            t.setPadding(0, dpi(8), 0, 0);
            so.addView(t);
            expandable("r_spo2", so, new Filler() { @Override public void fill(LinearLayout p) { fillSpo2Panel(p); } });
            col.addView(so, lp(-1, -2, 12));
        }
        // ---- this week: average sleep, sleep debt and bedtime spread
        List<TodayModel.NightStat> wk = Trends.allNights(d);
        if (wk.size() >= 2) {
            LinearLayout wc = card(16);
            wc.addView(caps("THIS WEEK"));
            TextView wt = tv(Trends.weekSummary(wk, goal()), 13, INK, 400);
            wt.setLineSpacing(0, 1.25f);
            wt.setPadding(0, dpi(8), 0, 0);
            wc.addView(wt);
            wc.addView(tv("Tap for the trends", 12, BLUE, 400), lp(-2, -2, 8));
            wc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (data != null) Trends.show(HealthActivity.this, data); } });
            col.addView(wc, lp(-1, -2, 12));
        }
        addRestResearch(col, srHead);      // 0.71.0
        View pv = swipeWrap(col);
        if (restSel + 1 < all.size()) { TodayModel.NightStat nx = all.get(all.size() - 2 - restSel); if (!nightCache.containsKey(nx.bed) && !nightMiss.contains(nx.bed) && nightLoading == 0) prefetchNight(nx.bed); }
        return pv;
    }

    /** Reads the next older night in the background so the next swipe is instant (no redraw when it arrives). */
    private void prefetchNight(final long bed) {
        nightLoading = bed; final HealthModel.Data base = data; final double g = goal();
        new Thread(new Runnable() { @Override public void run() {
            final HealthModel.Data nd = HealthModel.forNight(getApplicationContext(), base, bed, g);
            runOnUiThread(new Runnable() { @Override public void run() { if (nightOwner != base) return; if (nd != null) nightCache.put(bed, nd); else nightMiss.add(bed); if (nightLoading == bed) nightLoading = 0; } });
        } }, "night-prefetch").start();
    }

    /** Segments for the ring: {stage, from, to, sure}. Fractions of the closed part of the ring, merged and de-confettied. */
    private List<float[]> ringSegments(HealthModel.Data d, double need) {
        List<float[]> out = new ArrayList<>();
        int n = d.binStage.length;
        if (n == 0) return out;
        int[] sm = new int[n];
        for (int i = 0; i < n; i++) {
            int[] c = new int[4];
            for (int k = Math.max(0, i - 1); k <= Math.min(n - 1, i + 1); k++) if (d.binStage[k] >= 0) c[d.binStage[k]]++;
            int best = d.binStage[i] >= 0 ? d.binStage[i] : HealthModel.LIGHT, bc = c[best];
            for (int k = 0; k < 4; k++) if (c[k] > bc) { bc = c[k]; best = k; }
            sm[i] = best;
        }
        int i = 0;
        while (i < n) {
            int j = i;
            double sure = 0;
            while (j + 1 < n && sm[j + 1] == sm[i]) j++;
            for (int k = i; k <= j; k++) sure += d.binSure[k] ? 1 : 0;
            out.add(new float[] {sm[i], (float) (need * i / n), (float) (need * (j + 1) / n), sure / (j - i + 1) >= 0.5 ? 1f : 0f});
            i = j + 1;
        }
        return out;
    }

    private View stageBar(final long[] s) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3), x = 0;
                long tot = s[HealthModel.DEEP] + s[HealthModel.REM] + s[HealthModel.LIGHT];
                if (tot == 0) return;
                int[] order = {HealthModel.DEEP, HealthModel.REM, HealthModel.LIGHT};
                float usable = w - 2 * gap;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int k : order) {
                    float wi = usable * s[k] / tot;
                    p.setColor(STAGE[k]);
                    cv.drawRoundRect(new RectF(x, 0, x + wi, h), dp(getContext(), 3), dp(getContext(), 3), p);
                    x += wi + gap;
                }
            }
        };
    }

    private View pips(final float frac, final int color) {
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight(), gap = dp(getContext(), 3);
                int n = 14, on = Math.round(frac * n);
                float pw = (w - gap * (n - 1)) / n;
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                for (int i = 0; i < n; i++) {
                    p.setColor(alpha(color, i < on ? 1f : 0.14f));
                    cv.drawRoundRect(new RectF(i * (pw + gap), 0, i * (pw + gap) + pw, h), h / 2f, h / 2f, p);
                }
            }
        };
    }

    // ------------------------------------------------------------------ YOU
    private View youTab() {
        LinearLayout col = screen();
        HealthModel.Data d = data;
        col.addView(head("Strap, logger and settings", "You", null));
        try {                                                                  // 0.61.0
            LinkState.Info yli = MainActivity.linkState(this);
            long yAge = d != null && d.day != null && d.day.newestTs > 0 ? Math.max(0L, System.currentTimeMillis() / 1000L - d.day.newestTs) : -1L;
            col.addView(answerLine(UxLogic.youAnswer(DayLog.enabled(this), yli.level, d == null || d.day == null ? Double.NaN : d.day.loggedH, uxIssues(yli, yAge).size())), lp(-1, -2, 12));
        } catch (Throwable ignored) { }
        float logged = d == null || d.day == null ? 0f : (float) d.day.loggedH;
        View av = avatar(Math.min(1f, logged / 24f));
        LinearLayout.LayoutParams ap = lp(dpi(120), dpi(120), 6);      // 0.67.0: smaller, so the menus start higher up
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(av, ap);
        TextView nm = tv(name(), 22, INK, 400);
        nm.setGravity(Gravity.CENTER);
        col.addView(nm, lp(-1, -2, 4));
        TextView cap = tv(String.format(Locale.UK, "%.1f h logged in the last 24", logged), 12.5f, MUT, 400);
        cap.setGravity(Gravity.CENTER);
        col.addView(cap, lp(-1, -2, 6));
        navGs.clear();                                                        // 0.67.0: this page's drop-down groups are rebuilt with it
        col.addView(sectionHead("HELP AND SEARCH", null), lp(-1, -2, 14));
        col.addView(rowCard("Quick guide", "What the tabs and the ring colours mean, in plain words", new View.OnClickListener() { @Override public void onClick(View v) { QuickGuide.show(HealthActivity.this); } }), lp(-1, -2, 8));      // 0.55.0

        final LinearLayout findHolder = new LinearLayout(this); findHolder.setOrientation(LinearLayout.VERTICAL); col.addView(findHolder, lp(-1, -2, 12));      // 0.64.0: Find and the wellbeing / insight shortcuts, filled at the end
        col.addView(sectionHead("SETTINGS", "settings"), lp(-1, -2, 22));      // 0.67.0: headers and drop-down groups
        col.addView(displayCard(), lp(-1, -2, 4));                           // 0.61.0
        final NavG gE = navGroup("settings", "ecg", "ECG readings", "Countdown, near-live 500 Hz, clock, H10, live raw");
        final NavG gG = navGroup("settings", "graph", "ECG graph", "Window, style, theme, smoothing, quality colours");
        final NavG gP = navGroup("settings", "phone", "During a reading", "Phone-buzz guard, silence the phone");
        final NavG gA = navGroup("settings", "alerts", "Alerts and strap link", "Morning summary, battery alert, restart the link");
        final NavG gN = navGroup("settings", "numbers", "Your numbers", "Sleep goal, maximum heart rate, ECG scale, ECG strap model");
        final NavG gR = navGroup("settings", "research", "Research and tests", "Scale lab, strap settings reader, test switches");
        NavG[] sg = { gE, gG, gP, gA, gN, gR };
        for (NavG g : sg) col.addView(g.card, lp(-1, -2, 8));
        final SharedPreferences ctl = getSharedPreferences("labrador_ecg_control", MODE_PRIVATE);
        gE.body.addView(toggleRow("Start the ECG reading by itself after a 5 second countdown", ctl, "ecg_autostart"), lp(-1, dpi(52), 6));
        gE.body.addView(toggleRow("Show the packet counters on the screen during a reading", ctl, "ecg_packets_open"), lp(-1, dpi(52), 0));
        gE.body.addView(toggleRow("Record the H10 with each ECG reading (adds a PDF page)", ctl, "h10_with_reading", false), lp(-1, dpi(52), 0));
        gE.body.addView(toggleRow("Research: averaged beat, QT and HRV from 12.8 s (leave out the strap's settling)", ctl, "ecg_settle128", false), lp(-1, -2, 0));      // 0.39.0
        gR.body.addView(toggleRow("Write reconstructed_waveform files (old pulse-packet rebuild, unused)", ctl, "write_reconstructed", false), lp(-1, dpi(52), 0));      // 0.39.0
        gE.body.addView(toggleRow("Near-live 500 Hz during readings", ctl, "ecg_tail"), lp(-1, dpi(52), 0));
        gE.body.addView(toggleRow("Auto-restart at the 10-minute cap", ctl, "ecg_autorestart"), lp(-1, dpi(52), 0));
        gE.body.addView(toggleRow("Set the strap clock to the sub-second (as the official app)", ctl, "clock_subsec"), lp(-1, dpi(52), 0));
        final TextView lrv = tv(liveRawModeText(ctl), 14, BLUE, 600);
        gE.body.addView(valueRow("Live raw 500 Hz during readings (command 126)", lrv, new View.OnClickListener() {
            @Override public void onClick(View v) { int md = (ctl.getInt("ecg_liveraw_mode", 1) + 1) % 3; ctl.edit().putInt("ecg_liveraw_mode", md).apply(); lrv.setText(liveRawModeText(ctl)); }
        }), lp(-1, dpi(52), 0));
        TextView lrNote = tv("Tap to change: Off, On at start (the default: the live 500 Hz stream from the first second, about 0.1 s behind), Staged (126 on at about 16 s and off at about 41 s of one reading, a test).", 11.5f, MUT, 400);
        lrNote.setPadding(0, 0, 0, dpi(6));
        gE.body.addView(lrNote);
        // 0.43.0: the four graph options moved here from the ECG screen; they apply from the next reading
        final TextView gwv = tv(ctl.getInt("graph_window", 6000) == 3500 ? "3.5 s (zoom)" : "6 s", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG graph window", gwv, new View.OnClickListener() { @Override public void onClick(View v) { int w = ctl.getInt("graph_window", 6000) == 3500 ? 6000 : 3500; ctl.edit().putInt("graph_window", w).apply(); Live500View.windowMs = w; gwv.setText(w == 3500 ? "3.5 s (zoom)" : "6 s"); } }), lp(-1, dpi(52), 0));
        final TextView gsv = tv(ctl.getBoolean("graph_sweep", false) ? "Sweep with erase bar" : "Scroll", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG graph style", gsv, new View.OnClickListener() { @Override public void onClick(View v) { boolean w = !ctl.getBoolean("graph_sweep", false); ctl.edit().putBoolean("graph_sweep", w).apply(); Live500View.sweep = w; gsv.setText(w ? "Sweep with erase bar" : "Scroll"); } }), lp(-1, dpi(52), 0));
        final TextView gtv = tv(ctl.getBoolean("graph_paper", false) ? "Paper (light)" : "Dark", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG graph theme", gtv, new View.OnClickListener() { @Override public void onClick(View v) { boolean w = !ctl.getBoolean("graph_paper", false); ctl.edit().putBoolean("graph_paper", w).apply(); Live500View.paper = w; gtv.setText(w ? "Paper (light)" : "Dark"); } }), lp(-1, dpi(52), 0));
        final TextView gpv = tv(ctl.getString("pro_display", "1").equals("1") ? "On (spline baseline, markers)" : "Off (0.05 Hz filter only)", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG pro display", gpv, new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !ctl.getString("pro_display", "1").equals("1"); ctl.edit().putString("pro_display", on ? "1" : "0").apply(); Live500.pro = on; gpv.setText(on ? "On (spline baseline, markers)" : "Off (0.05 Hz filter only)"); } }), lp(-1, dpi(52), 0));
        // 0.44.0: quality colours of the live trace and the clean view
        final TextView gqv = tv(ctl.getBoolean("ecg_quality_colours", true) ? "On" : "Off", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG quality colours", gqv, new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !ctl.getBoolean("ecg_quality_colours", true); ctl.edit().putBoolean("ecg_quality_colours", on).apply(); Live500View.qualityColours = on; gqv.setText(on ? "On" : "Off"); } }), lp(-1, dpi(52), 0));
        TextView gqNote = tv("The live trace is coloured by the strap's own quality for each second: normal colour for quality 3, amber for 2, grey for 0 or 1, a gap when the fingers are off.", 11.5f, MUT, 400);
        gqNote.setPadding(0, 0, 0, dpi(6));
        gG.body.addView(gqNote);
        final TextView gcv = tv(ctl.getBoolean("ecg_clean_view", false) ? "On (clean view)" : "Off", 14, BLUE, 600);
        gG.body.addView(valueRow("ECG display smoothing", gcv, new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !ctl.getBoolean("ecg_clean_view", false); ctl.edit().putBoolean("ecg_clean_view", on).apply(); Live500.setClean(on); gcv.setText(on ? "On (clean view)" : "Off"); } }), lp(-1, dpi(52), 0));
        TextView gcNote = tv("On: heavier smoothing between the beats (about 20 ms) for a calmer picture; the QRS keeps its shape and the graph says smoothed. Display only: reports and stored data are not changed.", 11.5f, MUT, 400);
        gcNote.setPadding(0, 0, 0, dpi(6));
        gG.body.addView(gcNote);
        gR.body.addView(toggleRow("Live motion capture (test)", ctl, "imu_live_test", false), lp(-1, dpi(52), 0));
        TextView imuNote = tv("Test, off by default. During an ECG reading it asks the strap for its live accelerometer and gyro stream (START_RAW_DATA, then TOGGLE_IMU_MODE, as the NOOP app does) and shows a motion warning on the reading page when your arm moves (0.46.1; the sensor cannot see finger tremor). The frames are kept in the live-frame file. It could disturb the ECG: turn it off if a reading ever looks different, and tell me.", 11.5f, MUT, 400);
        imuNote.setPadding(0, 0, 0, dpi(6));
        gR.body.addView(imuNote);
        gP.body.addView(toggleRow("Phone-buzz guard", ctl, "ring_guard", true), lp(-1, dpi(52), 0));      // 0.57.0
        TextView rgNote = tv("On by default. During an ECG reading the strap's motion stream is watched for a ringing or buzzing phone (a narrow line between 5 and 49 Hz, not a hand movement). While it happens a banner shows, and the seconds are written to the reading notes (ring_flag_times_s). It asks the strap for the same motion stream as the test switch above. Detection only: the recording is not changed.", 11.5f, MUT, 400);
        rgNote.setPadding(0, 0, 0, dpi(6));
        gP.body.addView(rgNote);
        final TextView qpv = tv(quietText(ctl), 14, BLUE, 600);
        gP.body.addView(valueRow("Silence the phone during readings", qpv, new View.OnClickListener() { @Override public void onClick(View v) {
            boolean on = !ctl.getBoolean("quiet_phone", false);
            if (on && !QuietPhone.granted(HealthActivity.this)) { android.widget.Toast.makeText(HealthActivity.this, "Allow Do Not Disturb access for this app, then come back and tap this row again.", android.widget.Toast.LENGTH_LONG).show(); try { startActivity(QuietPhone.grantIntent()); } catch (RuntimeException e) { } return; }
            ctl.edit().putBoolean("quiet_phone", on).apply(); qpv.setText(quietText(ctl));
        } }));
        TextView qpNote = tv("Off by default. On: when a reading starts the phone goes to Do Not Disturb, Alarms only, so a call or message cannot ring or buzz it; when the reading ends the earlier setting is put back (and at the next start of the app if the app was killed in between). Calls from everyone are silenced, including favourites. Needs the one-time Do Not Disturb access in Android settings.", 11.5f, MUT, 400);
        qpNote.setPadding(0, 0, 0, dpi(6));
        gP.body.addView(qpNote);
        LinearLayout rf = explainerCard("RESEARCH", "Strap settings reader and the scale lab");      // 0.51.0: build brief items 1 and 4
        expandable("e_research", rf, new Filler() { @Override public void fill(LinearLayout q) {
            q.addView(pillButton("Read strap settings (read-only)", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) {
                int r = MainActivity.requestStrapConfigScan();
                if (r != 0) { android.widget.Toast.makeText(HealthActivity.this, r == 1 ? "The strap is not connected." : r == 2 ? "A reading is running: try after it." : "A scan is already running.", android.widget.Toast.LENGTH_SHORT).show(); return; }
                android.widget.Toast.makeText(HealthActivity.this, "Asking the strap for its setting names and values (read-only, about 10-30 s)...", android.widget.Toast.LENGTH_LONG).show();
                final android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper()); final long t0 = System.currentTimeMillis();
                hh.postDelayed(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    if (!StrapConfigScan.lastDone && System.currentTimeMillis() - t0 < 180000L) { hh.postDelayed(this, 1000L); return; }
                    new android.app.AlertDialog.Builder(HealthActivity.this).setTitle("Strap settings").setMessage(StrapConfigScan.lastDone ? StrapConfigScan.lastSummary : "No answer within 3 minutes.").setPositiveButton("OK", null).show();
                } }, 1500L);
            } }), lp(-1, dpi(44), 10));
            TextView sn = tv("Sends only the strap's own name lists and value reads (115 / 116 / 121 and 117 / 118 / 128). Nothing is written. The newest scan goes into the Full pull as strap_config.csv.", 11.5f, MUT, 400); sn.setLineSpacing(0, 1.2f); q.addView(sn, lp(-1, -2, 6));
            q.addView(toggleRow("Scale lab (S) in the ECG Lab", ctl, "research_lab_s", false), lp(-1, dpi(52), 6));
            TextView ln = tv("Adds \"S: Scale and corner (needs the divider)\" to ECG > Lab. Wiring: MG_simulator_test_v3.pdf (Part 2A, 2B, 3).", 11.5f, MUT, 400); ln.setLineSpacing(0, 1.2f); q.addView(ln, lp(-1, -2, 2));
        } }, "Open");
        gR.body.addView(rf, lp(-1, -2, 10));
        gG.body.addView(toggleRow("Show the 500 Hz graph when live raw frames arrive", ctl, "ecg_view500"), lp(-1, dpi(52), 0));
        final TextView smv = tv(smoothText(ctl), 14, BLUE, 600);
        gG.body.addView(valueRow("500 Hz graph smoothing (display only)", smv, new View.OnClickListener() {
            @Override public void onClick(View v) { int md = ctl.getInt("ecg_smooth500", 2); md = md == 2 ? 1 : md == 1 ? 0 : 2; ctl.edit().putInt("ecg_smooth500", md).apply(); smv.setText(smoothText(ctl)); }
        }), lp(-1, dpi(52), 0));
        TextView smNote = tv("Tap to change: Smooth (default), Light, Off. Smooths the noise between the beats and keeps the QRS sharp; the R wave, the P and T waves are kept (T and P about 90 %). Display only: the reports and the stored data are not changed. Takes effect at the next reading.", 11.5f, MUT, 400);
        smNote.setPadding(0, 0, 0, dpi(6));
        gG.body.addView(smNote);
        gR.body.addView(toggleRow("TEST: save filtered ECG (command 127)", ctl, "ecg_savefiltered", false), lp(-1, dpi(52), 0));
        gA.body.addView(toggleRow("Morning summary notification", sp(), "n_morning"), lp(-1, dpi(52), 0));
        gA.body.addView(toggleRow("Strap battery alert (15% or less)", sp(), "n_battery"), lp(-1, dpi(52), 0));
        final boolean ovl = android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
        gA.body.addView(pillButton(ovl ? "Restart the strap link by itself: allowed" : "Allow the app to restart the strap link by itself", ovl ? 0x1AFFFFFF : BLUE, ovl ? INK : 0xFF05131E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { }
            }
        }), lp(-1, dpi(44), 10));
        TextView ovNote = tv("If Android ends the app, only a screen start can bring the strap link back, and Android allows that from the background only with \"Display over other apps\". Allow it and the logger restores the link by itself, invisibly. Nothing is drawn over other apps.", 11.5f, MUT, 400);
        ovNote.setLineSpacing(0, 1.2f);
        ovNote.setPadding(0, dpi(6), 0, 0);
        gA.body.addView(ovNote);
        final TextView goalV = tv(String.format(Locale.UK, "%.1f h", goal()), 14, INK, 600);
        gN.body.addView(valueRow("Sleep goal", goalV, new View.OnClickListener() {
            @Override public void onClick(View v) { float g = goal() + 0.5f; if (g > 9.5f) g = 6.5f; sp().edit().putFloat("goal", g).apply(); goalV.setText(String.format(Locale.UK, "%.1f h", g)); load(); }
        }), lp(-1, dpi(52), 0));
        final TextView mxV = tv(maxHr() + " bpm", 14, INK, 600);
        gN.body.addView(valueRow("Maximum heart rate", mxV, new View.OnClickListener() { @Override public void onClick(View v) { askMaxHr(mxV); } }), lp(-1, dpi(52), 0));
        // 0.54.0: the ECG scale as one versioned setting, shown on every microvolt figure until a known voltage has decided it
        final TextView scV = tv(String.format(Locale.UK, "%.5f", ScaleSetting.uv()), 14, BLUE, 600);
        final TextView scNote = tv(ScaleSetting.status(), 11.5f, MUT, 400);
        gN.body.addView(valueRow("ECG scale, microvolts per count", scV, new View.OnClickListener() { @Override public void onClick(View v) { askScale(scV, scNote); } }), lp(-1, dpi(52), 0));
        scNote.setLineSpacing(0, 1.2f); scNote.setPadding(0, 0, 0, dpi(6));
        gN.body.addView(scNote);
        TextView chainLink = tv("What is known about the ECG chain  \u203a", 12, BLUE, 600);      // 0.54.4: gain table, output limit, high-pass, range, noise (decode log 189-191)
        chainLink.setPadding(0, dpi(2), 0, dpi(10));
        chainLink.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            new AlertDialog.Builder(HealthActivity.this).setTitle("ECG chain").setMessage(AfeDecode.chainSummary()).setPositiveButton("OK", null).show(); } });
        gN.body.addView(chainLink);
        // 0.79.0: the strap high-pass model behind the headline QT / T / ST (every report lists all models), a custom corner for that list, and the roll-off correction of the detail beat
        final TextView strapModelV = tv(EcgScenario.headlineShort(), 14, BLUE, 600);
        final TextView strapModelNote = tv(EcgScenario.status(), 11.5f, MUT, 400);
        gN.body.addView(valueRow("ECG strap model (headline)", strapModelV, new View.OnClickListener() {
            @Override public void onClick(View v) { EcgScenario.next(HealthActivity.this); strapModelV.setText(EcgScenario.headlineShort()); strapModelNote.setText(EcgScenario.status()); }
        }), lp(-1, dpi(52), 0));
        strapModelNote.setLineSpacing(0, 1.2f); strapModelNote.setPadding(0, 0, 0, dpi(6));
        gN.body.addView(strapModelNote);
        final TextView strapCustomV = tv(EcgScenario.customShort(), 14, BLUE, 600);
        gN.body.addView(valueRow("Custom corner in the scenario list", strapCustomV, new View.OnClickListener() { @Override public void onClick(View v) { askCustomCorner(strapCustomV, strapModelV, strapModelNote); } }), lp(-1, dpi(52), 0));
        final TextView strapHfV = tv(EcgScenario.hf() ? "On" : "Off", 14, BLUE, 600);
        gN.body.addView(valueRow("Detail beat: undo roll-off above 40 Hz", strapHfV, new View.OnClickListener() {
            @Override public void onClick(View v) { EcgScenario.toggleHf(HealthActivity.this); strapHfV.setText(EcgScenario.hf() ? "On" : "Off"); }
        }), lp(-1, dpi(52), 0));
        col.addView(sectionHead("DATA AND TOOLS", "data"), lp(-1, -2, 22));
        final NavG gD = navGroup("data", "data", "Your data", "Import, export and send, daily backup, storage");
        final NavG gT = navGroup("data", "tools", "Tools and research screens", "Sleep research, day logger, ECG export, live frames, older screens");
        col.addView(gD.card, lp(-1, -2, 8));
        col.addView(gT.card, lp(-1, -2, 8));
        LinearLayout dt = gD.body;
        LinearLayout dt2 = gT.body;
        TextView t = tv("Everything stays on this phone. The day logger holds the raw history and exports it as one zip.", 12.5f, MUT, 400);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, 0, 0, 0);
        dt.addView(t);
        dt.addView(pillButton("Import a pull (zip)", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { pickZip(); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Export and send data", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { ExportScreen.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));
        TextView exNote = tv("Pick a period, make one dated zip, send it. Earlier exports are listed with their date and time.", 11.5f, MUT, 400);
        exNote.setPadding(0, dpi(6), 0, 0);
        dt.addView(exNote);
        dt.addView(pillButton("Daily backup", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { BackupScreen.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));
        dt.addView(pillButton("Storage: sizes, trim, one backup zip, restore", BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { StorageScreen.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));      // 0.36.0
        TextView bkNote = tv("Copies all the app's data every day at a time you choose into Download/" + Backup.ROOT + ".  Backup: " + Backup.shortStatus(this), 11.5f, MUT, 400);
        bkNote.setPadding(0, dpi(6), 0, 0);
        dt.addView(bkNote);
        dt2.addView(pillButton("Sleep research: regularity, body clock, sources", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { SleepResearch.show(HealthActivity.this); } }), lp(-1, dpi(44), 12));      // 0.54.0
        dt2.addView(pillButton("Day logger and export", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } }), lp(-1, dpi(44), 12));
        dt2.addView(pillButton("Export ECG data for analysis", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { EcgExport.ask(HealthActivity.this); } }), lp(-1, dpi(44), 8));
        dt2.addView(pillButton("Live frames (watch the strap's packets arrive)", 0x1A2EE6A8, 0xFF2EE6A8, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(HealthActivity.this, LiveFramesActivity.class)); } }), lp(-1, dpi(44), 8));
        dt2.addView(pillButton("Older screens (ECG tools)", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { startActivity(oldScreens()); } }), lp(-1, dpi(44), 8));
        TextView imp = tv(importNote, 11.5f, MUT, 400);
        imp.setPadding(0, dpi(10), 0, 0);
        dt.addView(imp);
        try { findHolder.addView(findCard(gE, gD, gR), lp(-1, -2, 0)); } catch (Throwable ignored) { }      // 0.64.0 (0.65.0: the wellbeing and insight shortcuts moved to Today; You is settings and data)
        try {
            String sc = StartupClock.line();
            if (!sc.isEmpty()) { TextView sct = tv(sc, 10.5f, MUT, 400); sct.setPadding(dpi(4), dpi(14), dpi(4), 0); col.addView(sct); }      // 0.67.0: where the start-up time goes (this run of the app)
        } catch (Throwable ignored) { }
        return wrap(col);
    }

    /** 0.64.0: a search row. Type a word (backup, scale, wellbeing, trends...) and tap the result. */
    private LinearLayout findCard(final NavG stG, final NavG dtG, final NavG resG) {
        final LinearLayout c = card(16);
        c.addView(caps("FIND"));
        final android.widget.EditText q = new android.widget.EditText(this); q.setHint("Type: backup, scale, wellbeing, trends, export..."); q.setTextColor(INK); q.setHintTextColor(MUT); q.setSingleLine(true); q.setTextSize(15);
        c.addView(q, lp(-1, -2, 6));
        final LinearLayout res = new LinearLayout(this); res.setOrientation(LinearLayout.VERTICAL); c.addView(res, lp(-1, -2, 4));
        final String[] lab = { "Wellbeing page", "Heart Watch", "Trends", "Quick guide", "Export and send data", "Daily backup", "Storage: sizes, trim, restore", "Import a pull (zip)", "Sleep research", "Day logger and export", "Export ECG data for analysis", "Live frames", "Older screens (ECG tools)", "Settings, ECG and lab options", "Your data", "Scale lab (Lab S) steps" };
        final String[] kw = { "mood energy calm minute breathing if then week good things who-5 sleep plan", "rhythm pulse night signs alert notify", "graphs 7 30 90 nights recovery", "help rings colours tabs", "share zip pull send", "copy restore", "space trim delete", "pull zip load", "regularity body clock", "logger csv", "ecg csv analysis", "packets stream", "ecg tools lab report start reading", "toggles switches notifications strap link battery text size", "export backup import storage", "calibration simulator high-pass corner ladder scale tones old phone" };
        final Runnable[] go = new Runnable[lab.length];
        go[0] = new Runnable() { @Override public void run() { try { startActivity(new Intent(HealthActivity.this, WellbeingActivity.class)); } catch (Throwable ignored) { } } };
        go[1] = new Runnable() { @Override public void run() { showRing(6); } };
        go[2] = new Runnable() { @Override public void run() { if (data != null) Trends.show(HealthActivity.this, data); } };
        go[3] = new Runnable() { @Override public void run() { QuickGuide.show(HealthActivity.this); } };
        go[4] = new Runnable() { @Override public void run() { ExportScreen.show(HealthActivity.this); } };
        go[5] = new Runnable() { @Override public void run() { BackupScreen.show(HealthActivity.this); } };
        go[6] = new Runnable() { @Override public void run() { StorageScreen.show(HealthActivity.this); } };
        go[7] = new Runnable() { @Override public void run() { pickZip(); } };
        go[8] = new Runnable() { @Override public void run() { SleepResearch.show(HealthActivity.this); } };
        go[9] = new Runnable() { @Override public void run() { startActivity(new Intent(HealthActivity.this, DayLogActivity.class)); } };
        go[10] = new Runnable() { @Override public void run() { EcgExport.ask(HealthActivity.this); } };
        go[11] = new Runnable() { @Override public void run() { startActivity(new Intent(HealthActivity.this, LiveFramesActivity.class)); } };
        go[12] = new Runnable() { @Override public void run() { startActivity(oldScreens()); } };
        go[13] = new Runnable() { @Override public void run() { goToNav(stG); } };
        go[14] = new Runnable() { @Override public void run() { goToNav(dtG); } };
        go[15] = new Runnable() { @Override public void run() { android.widget.Toast.makeText(HealthActivity.this, "Switch on 'Scale lab (S) in the ECG Lab' under Research and tests > Research, then ECG tab > Lab > S > Open: the seven steps are at the top.", android.widget.Toast.LENGTH_LONG).show(); goToNav(resG); } };
        final Runnable refresh = new Runnable() { @Override public void run() {
            res.removeAllViews(); String[] tok = q.getText().toString().toLowerCase(Locale.UK).trim().split("\\s+"); int shown = 0; boolean any = !q.getText().toString().trim().isEmpty();
            final int[] dflt = { 13, 14, 4, 5, 15 };      // 0.65.0: with nothing typed the row lists settings and data, not wellbeing
            for (int ii = 0; ii < lab.length && shown < 6; ii++) {
                if (!any && ii >= dflt.length) break; final int i = any ? ii : dflt[ii];
                String hay = (lab[i] + " " + kw[i]).toLowerCase(Locale.UK); boolean ok = true; if (any) for (String t : tok) if (!t.isEmpty() && !hay.contains(t)) { ok = false; break; }
                if (!ok) continue; final int k = i;
                res.addView(pillButton(lab[i], 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { try { go[k].run(); } catch (Throwable ignored) { } } }), lp(-1, dpi(44), 6)); shown++;
            }
            if (any && shown == 0) res.addView(tv("Nothing matches. Try one word.", 12, MUT, 400));
        } };
        q.addTextChangedListener(new android.text.TextWatcher() { @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { } @Override public void onTextChanged(CharSequence s, int a, int b, int d) { refresh.run(); } @Override public void afterTextChanged(android.text.Editable e) { } });
        refresh.run();
        return c;
    }

    private View avatar(final float frac) {
        final String letter = name().isEmpty() ? "A" : name().substring(0, 1).toUpperCase(Locale.UK);
        return new View(this) {
            @Override protected void onDraw(Canvas cv) {
                Context c = getContext();
                float size = getWidth(), cx = size / 2f, cy = size / 2f, r = size * 0.40f, sw = size * 0.059f, k = size / 170f;      // 0.67.0: scales with its size
                RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(sw);
                p.setColor(0x12FFFFFF);
                cv.drawArc(box, 0, 360, false, p);
                for (int pass = 0; pass < 3; pass++) {
                    p.setShader(new LinearGradient(0, 0, size, size, 0xFFF2B35B, 0xFFFF8CA2, Shader.TileMode.CLAMP));
                    p.setStrokeWidth(sw + dp(c, 3 * (2 - pass)) * k);
                    p.setAlpha(pass < 2 ? 50 + 40 * pass : 255);
                    cv.drawArc(box, -90, 360f * frac, false, p);
                }
                p.setShader(null);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 200)); t.setTextSize(dp(c, 62) * k);
                cv.drawText(letter, cx, cy + dp(c, 22) * k, t);
            }
        };
    }

    private View toggleRow(String label, final SharedPreferences prefs, final String key) { return toggleRow(label, prefs, key, true); }

    private String quietText(SharedPreferences p) {      // 0.57.0
        if (!p.getBoolean("quiet_phone", false)) return "Off";
        return QuietPhone.granted(this) ? "On (Alarms only during a reading)" : "On, needs access";
    }

    private static String smoothText(SharedPreferences p) {
        int md = p.getInt("ecg_smooth500", 2);
        return md == 1 ? "Light" : md == 0 ? "Off" : "Smooth";
    }

    private static String liveRawModeText(SharedPreferences p) {
        int md = p.getInt("ecg_liveraw_mode", 1);
        return md == 1 ? "On at start" : md == 2 ? "Staged" : "Off";
    }

    private View toggleRow(String label, final SharedPreferences prefs, final String key, final boolean def) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        final Toggle t = new Toggle(this, prefs.getBoolean(key, def));
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { boolean on = !prefs.getBoolean(key, def); prefs.edit().putBoolean(key, on).apply(); t.set(on); } });
        r.addView(t, new LinearLayout.LayoutParams(dpi(46), dpi(28)));
        r.setBackground(null);
        r.setMinimumHeight(tapMin());
        return r;
    }

    private View valueRow(String label, TextView value, View.OnClickListener l) {
        LinearLayout r = hrow();
        r.addView(tv(label, 14, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(value);
        r.setOnClickListener(l);
        r.setMinimumHeight(tapMin());
        return r;
    }

    /** The small pill switch. */
    static final class Toggle extends View {
        private boolean on;
        Toggle(Context c, boolean on) { super(c); this.on = on; }
        void set(boolean v) { on = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (on) p.setShader(new LinearGradient(0, 0, w, h, 0xFF37B6F6, 0xFF1788E8, Shader.TileMode.CLAMP)); else p.setColor(0x1FFFFFFF);
            cv.drawRoundRect(new RectF(0, 0, w, h), h / 2f, h / 2f, p);
            p.setShader(null);
            p.setColor(0xFFFFFFFF);
            float r = h / 2f - dp(c, 3);
            cv.drawCircle(on ? w - h / 2f : h / 2f, h / 2f, r, p);
        }
    }

    /** 0.54.0: change the ECG scale. Each reading keeps the scale it was made with (note uv_per_count); the readings table shows older ones at the scale in use now. */
    private void askScale(final TextView target, final TextView note) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.5f", ScaleSetting.uv()));
        new AlertDialog.Builder(this).setTitle("ECG scale, microvolts per count")
                .setMessage("0.06358 is inferred: 1 V / (2^17 x gain 120). The formula and the 1.000 V reference are documented for the sister chips (MAX30003, MAX86178); this chip's reference is not published, and the strap's output limit of 62 x 2048 counts fits the same family. Another contributor measured 0.0763 from WHOOP's printed report; no gain setting gives that. Until a known-voltage run decides, every microvolt figure carries \"" + ScaleSetting.SCALE_TAG + "\". "
                        + "Only mark it verified after that run. Heart rate, HRV and timing do not depend on it.")
                .setView(e)
                .setPositiveButton("Set (provisional)", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) { applyScale(e, false, target, note); }
                })
                .setNeutralButton("Set and mark verified", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) { applyScale(e, true, target, note); }
                })
                .setNegativeButton("Reset", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) { ScaleSetting.reset(HealthActivity.this); target.setText(String.format(Locale.UK, "%.5f", ScaleSetting.uv())); note.setText(ScaleSetting.status()); }
                }).show();
    }

    /** 0.79.0: one extra high-pass corner for the scenario list of every report (0 removes it). */
    private void askCustomCorner(final TextView target, final TextView head, final TextView note) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.2f", EcgIntervals.SCEN_CUSTOM_HZ));
        new AlertDialog.Builder(this).setTitle("Custom corner, Hz")
                .setMessage("Adds one more strap high-pass corner (0.01 to 3 Hz) to the scenario list of every new report. 0 removes it. To make it the headline, tap \"ECG strap model\" until it shows.")
                .setView(e)
                .setPositiveButton("Set", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            double v = Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
                            if (EcgScenario.setCustom(HealthActivity.this, v)) { target.setText(EcgScenario.customShort()); head.setText(EcgScenario.headlineShort()); note.setText(EcgScenario.status()); }
                            else android.widget.Toast.makeText(HealthActivity.this, "0, or a corner between 0.01 and 3 Hz.", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (NumberFormatException ignored) { }
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void applyScale(EditText e, boolean verified, TextView target, TextView note) {
        try {
            double v = Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
            if (v > 0.02 && v < 0.2) { ScaleSetting.set(this, v, verified); target.setText(String.format(Locale.UK, "%.5f", ScaleSetting.uv())); note.setText(ScaleSetting.status()); }
            else android.widget.Toast.makeText(this, "A scale between 0.02 and 0.2 only.", android.widget.Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException ignored) { }
    }

    private void askMaxHr(final TextView target) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(maxHr()));
        new AlertDialog.Builder(this).setTitle("Maximum heart rate (bpm)")
                .setMessage("Used only for the heart-rate zones. 220 minus your age is a rough guess; use a measured value if you have one.")
                .setView(e)
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        try {
                            int v = Integer.parseInt(e.getText().toString().trim());
                            if (v >= 120 && v <= 230) { sp().edit().putInt("maxhr", v).apply(); target.setText(v + " bpm"); load(); }
                        } catch (NumberFormatException ignored) { }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Soft colour behind everything: a faint teal wash at the top and a blue glow low down, as in the mock-ups. */
    static final class GlowBg extends View {
        GlowBg(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new android.graphics.RadialGradient(w / 2f, 0f, w * 0.9f, new int[] {0x3328788F, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
            p.setShader(new android.graphics.RadialGradient(w / 2f, h * 0.78f, w * 0.7f, new int[] {0x2A2FA8F0, 0x00000000}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }
    }
}
