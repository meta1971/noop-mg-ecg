package com.noop.mgecg;
// 0.64.0 (16 Nov 2026): mood and energy two-tap check with YOUR OWN PATTERN (Mind64), calm minute log with before / after RMSSD from the strap's live beats, if-then plan, week on one page, preview cards WORK-DAY LOAD and OVERNIGHT GAP (Pair64, opt-in).

// FILE VERSION 0.61.0 (15 Nov 2026): text size setting (UxKit.scaled); the intent extra focus_evening scrolls to the three good things (from the wind-down reminder and the "This evening" card).
// FILE VERSION 0.60.0 (15 Nov 2026): the page grew. New cards: night-by-night bars with personal bests, work-night scatter (bed time against hours), three ECG cards (clean-reading ring, evening-pulse spread, calmest evening),
// a two-week WHO-5 check-in, three good things, a public-domain line for the day, and an opt-in wind-down reminder under the bed plan. Fixes: the "UK median 60" caption (study-specific, removed) and the calm-minute wording (now states Pizzoli 2021 as measured).
// 0.59.0 (15 Nov 2026): added the "How this is worked out" card at the bottom: every rule the page uses and the study behind each line, with its design (observational or trial). No number or claim above it changed.
// 0.58.0 (15 Nov 2026): NEW. The wellbeing page (opened from the Morning brief card on the Today tab). Everything on it is counted from the sleep log of the last 14 days (RhythmData -> SleepRegularity nights ->
// WellbeingModel); nothing is invented, and where there is not enough data it says so. Opinion first, the numbers as the small "Your data" line, with the study behind each opinion.
// Parts: the hero ring (one segment per night, the hours written on it, a word from the sleep regularity index), the main opinion, the plan check (a plan saved here is checked against the next night), the wake-up strip,
// three rings, one small win for tonight (a button saves it as a plan: in bed by 23:00, stored on the phone only), and a one-minute paced-breathing orb (6 breaths a minute, pacing only: no pulse is measured here).
// Writes: SharedPreferences "wellbeing": plan_bed_min, plan_from_day, wind_on, who5_hist, gt_<epochDay> (last 60 days kept). Reads the saved ECG report files through HealthModel. No new permission (POST_NOTIFICATIONS and the alarm permission were already declared).

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class WellbeingActivity extends Activity {
    static final String PREFS = "wellbeing";
    private static final int BG = HealthViews.BG, INK = HealthViews.INK, MUT = HealthViews.MUT;
    private static final int BLUE = HealthViews.BLUE, VIOLET = HealthViews.VIOLET, TEAL = HealthViews.TEAL, GOLD = HealthViews.GOLD, ROSE = HealthViews.ROSE;
    private static final int EVID = 0xFF7F8A9A;

    /** Every rule and study behind the page. Keep in step with WellbeingModel (constants) and the "Why" lines. */
    private static final String HOW =
            "All figures are counted from the strap's sleep minutes over your last 14 nights and from the ECG readings saved on this phone in the last 30 days. Nothing is estimated; with too little data a card says so.\n\n"
          + "Sleep rules: a work morning is a wake-up before 07:00. Early bed is in bed before 23:00. A pattern needs 5 nights each way; 2 to 4 is an early signal. Same wake-up: within 3 minutes of the latest one. "
          + "Bounce-back: a night under 6 h followed by 7 h or more. Ring word: Steady is a regularity score of 71 or more, Varied is 51 or less, Settling is between. "
          + "Those bands come from a study that measured sleep with wrist accelerometers; the strap measures it differently, so read the word as a rough guide.\n\n"
          + "Charts: bars are labelled by the morning you woke, and the dashed line is 7 h. The count of 7 h nights in the last 7 is set beside the up to 7 nights before it; no start date is picked. "
          + "Longest 7 h+ run counts nights in a row.\n\n"
          + "ECG cards: the clean share counts every graded reading, including C and D. Pulse figures use readings graded A or B only. Evening is 18:00 to midnight. "
          + "The calmest evening is the one with 3 or more such readings and the lowest median. The grade is the quality of the recording; it says nothing about your heart.\n\n"
          + "Check-in: the five WHO-5 answers are scored 0 to 5 and the total is multiplied by 4, giving 0 to 100. 50 or below is the usual point for a closer look. The review of the questionnaire treats a change of 10 points as clinically relevant. Answers stay on this phone.\n\n"
          + "Studies behind the wording:\n"
          + "- Sleep regularity and mood: UK Biobank, 79,666 adults, Psychological Medicine 2025. Observational: an association, not proof that regular sleep protects mood.\n"
          + "- Kind wording after a short night: a meta-analysis of 21 trials with 1,285 people of compassion-based training (Kirby 2017, Behavior Therapy) found benefits across a range of outcomes.\n"
          + "- Calm minute: a meta-analysis of 14 trials with 794 adults of heart-rate-variability biofeedback (Pizzoli 2021, Scientific Reports) found g = 0.38 on depressive symptoms (95% interval 0.16 to 0.60), with wide uncertainty between studies. Biofeedback shows you your own pulse; here the app only paces your breathing.\n"
          + "- WHO-5: Topp and colleagues 2015, Psychotherapy and Psychosomatics, a review of 213 articles. Free to use. The average in Danish general-population studies is about 70.\n"
          + "- Three good things: Seligman and colleagues 2005, American Psychologist, an online trial of volunteers; Sin and Lyubomirsky 2009, a review of 51 studies of positive-psychology exercises in general with 4,266 people, mean r = 0.29 on well-being. A 2014 replication in 163 women aged 50 to 79 (Gander and colleagues) found a gain in happiness.\n"
          + "- Lines: Marcus Aurelius, Meditations, translated by George Long (1862); Epictetus, Enchiridion, translated by Elizabeth Carter (1758). Both are in the public domain.\n\n"
          + "Research only. Not medical advice; nothing here diagnoses anything.";

    /** The page's numbers from the day log. Call off the main thread. Null if the log cannot be read. */
    static WellbeingModel.Out load(Context c) {
        try {
            RhythmData.Bundle b = RhythmData.bundleCached(c);
            if (b == null || b.sri == null || b.s == null) return null;
            SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
            int planBed = p.getInt("plan_bed_min", -1);
            long planFrom = p.getLong("plan_from_day", 0L);
            long baseEpochDay = Math.floorDiv(b.s.baseLocalSec, 86400L);
            return WellbeingModel.compute(b.sri.nights, b.sri.sri, baseEpochDay, planBed, planFrom);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Everything the page shows. Call off the main thread. Null if the sleep log cannot be read; the ECG part is empty when no readings can be read. */
    static final class Pack {
        WellbeingModel.Out o;
        WellbeingExtra.Scatter sc;
        WellbeingExtra.Bars bars;
        WellbeingExtra.Bests bests;
        WellbeingExtra.Ecg ecg;
        java.util.Map<Long, Double> wake = new java.util.HashMap<>();      // 0.64.0: hours slept by the day the night ended on
    }

    static Pack loadAll(Context c) {
        try {
            RhythmData.Bundle b = RhythmData.bundleCached(c);
            if (b == null || b.sri == null || b.s == null) return null;
            SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
            int planBed = p.getInt("plan_bed_min", -1);
            long planFrom = p.getLong("plan_from_day", 0L);
            long baseEpochDay = Math.floorDiv(b.s.baseLocalSec, 86400L);
            Pack k = new Pack();
            k.o = WellbeingModel.compute(b.sri.nights, b.sri.sri, baseEpochDay, planBed, planFrom);
            k.sc = WellbeingExtra.scatter(b.sri.nights);
            k.bars = WellbeingExtra.bars(b.sri.nights, baseEpochDay);
            k.bests = WellbeingExtra.bests(b.sri.nights, baseEpochDay);
            k.ecg = WellbeingExtra.ecg(ecgReadings(c), System.currentTimeMillis(), TimeZone.getDefault());
            try { k.wake = Mind64.hoursByWakeDay(b.sri.nights, baseEpochDay); } catch (Throwable ignored) { }
            return k;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The saved ECG readings as (time, heart rate, grade letter); a reading without a grade letter A to D is skipped later. */
    private static List<WellbeingExtra.Rd> ecgReadings(Context c) {
        List<WellbeingExtra.Rd> out = new ArrayList<WellbeingExtra.Rd>();
        try {
            for (HealthModel.Reading r : HealthModel.readings(c)) {
                char g = r.grade == null || r.grade.trim().length() == 0 ? '?' : Character.toUpperCase(r.grade.trim().charAt(0));
                int hr = -1;
                if (r.hr != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(r.hr);
                    if (m.find()) hr = Integer.parseInt(m.group(1));
                }
                out.add(new WellbeingExtra.Rd(r.timeMs, hr, g));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static long todayLocalEpochDay() {
        long now = System.currentTimeMillis();
        return Math.floorDiv(now / 1000L + TimeZone.getDefault().getOffset(now) / 1000L, 86400L);
    }

    private float dp;
    private LinearLayout col;
    private BreathView breath;
    private boolean loadingNow;
    private Pack lastPack;
    private LinearLayout remBox, whoCard, goodCard, moodCard, ifCard, weekCard, calmCard, workCard, gapCard;
    private int selMood = 0, selEnergy = 0, ifCue = -1, ifAct = -1; private long calmStartMs = 0L; private final Handler ui = new Handler(Looper.getMainLooper());
    private ScrollView scroll; private boolean focusEvening;      // 0.61.0: the evening reminder and the "This evening" card open straight at the three good things
    private EditText[] gtFields;
    private boolean editGood, notifDenied;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(UxKit.scaled(base)); }      // 0.61.0: text size setting

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); focusEvening = i != null && i.getBooleanExtra("focus_evening", false); scrollToFocus(); }

    private void scrollToFocus() {
        if (!focusEvening || scroll == null) return;
        scroll.postDelayed(new Runnable() { @Override public void run() {
            try { if (goodCard != null && goodCard.getParent() != null) { scroll.smoothScrollTo(0, Math.max(0, goodCard.getTop() - px(12))); focusEvening = false; } } catch (Throwable ignored) { }
        } }, 350L);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        focusEvening = getIntent() != null && getIntent().getBooleanExtra("focus_evening", false);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new GlowView(this), new FrameLayout.LayoutParams(-1, -1));
        ScrollView sv = new ScrollView(this);
        scroll = sv;
        sv.setVerticalScrollBarEnabled(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(18), px(22), px(18), px(28));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showMessage("Reading your last nights...");
        reload();
    }

    @Override protected void onPause() {
        super.onPause();
        if (breath != null) breath.stop(false);
    }

    private int px(float v) { return Math.round(v * dp); }

    private void reload() {
        if (loadingNow) return;
        loadingNow = true;
        final Context app = getApplicationContext();
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                final Pack pk = loadAll(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loadingNow = false;
                        if (isFinishing()) return;
                        if (pk == null) {
                            showMessage("Could not read your sleep log. Open the day logger on the Today tab and pull the strap's history first.");
                            addMindCards();
                            addClose();
                        } else render(pk);
                        scrollToFocus();
                    }
                });
            }
        });
        th.setDaemon(true);
        th.start();
    }

    private void showMessage(String s) {
        col.removeAllViews();
        TextView t = tv(s, 15, MUT, 400);
        t.setPadding(0, px(40), 0, 0);
        col.addView(t);
    }

    // ------------------------------------------------------------------ the page
    private void render(final Pack pk) {
        final WellbeingModel.Out o = pk.o;
        lastPack = pk;
        if (breath != null) breath.stop(false);
        breath = null;
        col.removeAllViews();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String name = getSharedPreferences("today", MODE_PRIVATE).getString("name", "Ame");
        String greet = (hour < 12 ? "Good morning, " : hour < 18 ? "Good afternoon, " : "Good evening, ") + name;
        col.addView(tv(greet, 13, MUT, 400));
        String title = !o.enough ? "Getting to know you." : "Gathering".equals(o.word) ? "Your week so far." : "A " + o.word.toLowerCase(Locale.UK) + " week.";
        TextView tt = tv(title, 28, INK, 400);
        tt.setPadding(0, px(6), 0, 0);
        col.addView(tt);

        if (o.enough && o.hrs.length >= 3) {
            col.addView(new HeroView(this, o.hrs, o.word, "LAST " + o.hrs.length + " NIGHTS"), new LinearLayout.LayoutParams(-1, -2));
            TextView wn = tv(o.wordNote, 12, EVID, 400);
            wn.setGravity(Gravity.CENTER);
            wn.setPadding(px(10), px(2), px(10), 0);
            col.addView(wn);
        }

        // main opinion
        LinearLayout c1 = card();
        c1.addView(caps(o.enough ? "HOW YOU SLEPT" : "YOUR STORY", TEAL));
        c1.addView(opinion(o.headline, 21));
        TextView b1 = tv(o.body, 14, MUT, 400);
        b1.setPadding(0, px(8), 0, 0);
        c1.addView(b1);
        addEvidence(c1, o.evidence);
        col.addView(c1, lp(12));
        if (!o.enough) {
            addMindCards();
            addLineCard();
            addClose();
            return;
        }

        // the plan check
        if (o.planOn) {
            LinearLayout cp = card();
            cp.setBackground(outline(GOLD));
            cp.addView(caps("YOUR PLAN", GOLD));
            cp.addView(opinion(o.planHead, 19));
            TextView pb = tv(o.planBody, 14, MUT, 400);
            pb.setPadding(0, px(8), 0, 0);
            cp.addView(pb);
            addEvidence(cp, o.planEvidence);
            col.addView(cp, lp(12));
        }

        // bounce back
        if (o.bounce) {
            LinearLayout cb = card();
            cb.addView(caps("YOU BOUNCED BACK", GOLD));
            cb.addView(opinion(o.bounceHead, 19));
            addEvidence(cb, o.bounceEvidence);
            col.addView(cb, lp(12));
        }

        // wake-up strip
        if (o.stripWake.length >= 3) {
            int lo = o.stripWake[0], hi = o.stripWake[0];
            for (int w : o.stripWake) { if (w < lo) lo = w; if (w > hi) hi = w; }
            LinearLayout cw = card();
            cw.addView(caps("YOUR " + o.stripWake.length + " WORK MORNINGS", MUT));
            cw.addView(new WakeStripView(this, o.stripWake), new LinearLayout.LayoutParams(-1, px(128)));
            int spread = hi - lo;
            String line = spread <= 30
                    ? "All " + o.stripWake.length + " within " + spread + " minutes of each other (" + WellbeingModel.clock(lo) + " to " + WellbeingModel.clock(hi) + ")."
                    : "From " + WellbeingModel.clock(lo) + " to " + WellbeingModel.clock(hi) + ", a spread of " + spread + " minutes.";
            cw.addView(tv(line, 14, MUT, 400));
            col.addView(cw, lp(12));
        }

        addBarsCard(pk);

        // three rings
        LinearLayout trio = new LinearLayout(this);
        trio.setOrientation(LinearLayout.HORIZONTAL);
        float f1 = o.nAll > 0 ? o.ge7All / (float) o.nAll : 0f;
        trio.addView(mini(f1, TEAL, String.valueOf(o.ge7All), "7 H+ NIGHTS", o.ge7All + " of " + o.nAll + " nights"), tl());
        trio.addView(mini(o.nWork > 0 ? o.nEarly / (float) o.nWork : 0f, BLUE, o.nWork > 0 ? String.valueOf(o.nEarly) : "—", "EARLY BEDS", o.nWork > 0 ? o.nEarly + " of " + o.nWork + " work nights before 23:00" : "no work mornings yet"), tl());
        boolean hasSri = !Double.isNaN(o.sri);
        trio.addView(mini(hasSri ? (float) Math.max(0.02, Math.min(1.0, o.sri / 100.0)) : 0f, VIOLET, hasSri ? String.valueOf(Math.round(o.sri)) : "—", "REGULARITY", hasSri ? "of 100" : "needs 24 h of days"), tl());
        col.addView(trio, lp(12));

        addScatterCard(pk);

        // one small win
        LinearLayout cwin = card();
        cwin.setBackground(outline(GOLD));
        cwin.addView(caps("ONE SMALL WIN FOR TONIGHT", GOLD));
        cwin.addView(opinion(o.winHead, 21));
        TextView wb = tv(o.winBody, 14, MUT, 400);
        wb.setPadding(0, px(8), 0, 0);
        cwin.addView(wb);
        addEvidence(cwin, o.winEvidence);
        if (o.winKind > 0 && !o.planOn) {
            cwin.addView(pill("Make it a plan", GOLD, 0xFF1A1305, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("plan_bed_min", WellbeingModel.LATE_BED_MIN).putLong("plan_from_day", todayLocalEpochDay()).commit();
                    reload();
                }
            }), pl(14));
            TextView note = tv("Saved on this phone only. Tomorrow morning the page tells you how it went.", 12, EVID, 400);
            note.setGravity(Gravity.CENTER);
            note.setPadding(0, px(8), 0, 0);
            cwin.addView(note);
        } else if (o.planOn) {
            TextView clear = tv("Clear my plan", 14, MUT, 600);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(0, px(14), 0, 0);
            clear.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    WellbeingReminder.setOn(WellbeingActivity.this, false);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("plan_bed_min").remove("plan_from_day").commit();
                    reload();
                }
            });
            cwin.addView(clear);
        }
        if (o.planOn) {
            remBox = new LinearLayout(this);
            remBox.setOrientation(LinearLayout.VERTICAL);
            cwin.addView(remBox);
            fillReminder();
        }
        col.addView(cwin, lp(12));

        addEcgCards(pk);
        addMindCards();

        // a calm minute
        LinearLayout cb2 = card();
        cb2.addView(caps("A CALM MINUTE", TEAL));
        cb2.addView(opinion("Breathe with the orb for one minute.", 19));
        breath = new BreathView(this);
        breath.onStartCb = new Runnable() { @Override public void run() { calmStartMs = System.currentTimeMillis(); } };
        breath.onFinishCb = new Runnable() { @Override public void run() { logCalm(calmStartMs, System.currentTimeMillis()); } };
        cb2.addView(breath, new LinearLayout.LayoutParams(-1, px(300)));
        final TextView startBtn = pill("Start the minute", TEAL, 0xFF05131E, null);
        breath.bind(startBtn);
        cb2.addView(startBtn, pl(8));
        TextView why = tv("", 12, EVID, 400);
        why.setText(rich("Why this: about 6 slow breaths a minute is the pace used in heart-rate-variability biofeedback. A meta-analysis of 14 trials with 794 adults (Pizzoli and colleagues 2021, Scientific Reports) found g = 0.38 on depressive symptoms, 95% interval 0.16 to 0.60, with wide uncertainty between studies. In biofeedback you watch your own pulse as you breathe; here the app only paces your breathing and does not measure your pulse, so this minute is not that treatment."));
        why.setPadding(0, px(12), 0, 0);
        cb2.addView(why);
        col.addView(cb2, lp(12));
        calmCard = card(); fillCalm(); col.addView(calmCard, lp(8));      // 0.64.0: before / after RMSSD

        addLineCard();

        // how this is worked out
        LinearLayout hw = card();
        hw.addView(caps("HOW THIS IS WORKED OUT", MUT));
        TextView hwt = tv(HOW, 13, MUT, 400);
        hwt.setPadding(0, px(10), 0, 0);
        hw.addView(hwt);
        col.addView(hw, lp(12));

        addClose();
    }

    private void addClose() {
        TextView close = tv("Close", 14, MUT, 600);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, px(22), 0, px(8));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        col.addView(close, new LinearLayout.LayoutParams(-1, px(60)));
    }

    // ------------------------------------------------------------------ 0.60.0: charts, ECG cards, check-in, three good things, line of the day, reminder
    private LinearLayout.LayoutParams lpH(float hDp, float topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, px(hDp));
        l.topMargin = px(topDp);
        return l;
    }

    private TextView note(String s) {
        TextView t = tv(s, 13, MUT, 400);
        t.setPadding(0, px(10), 0, 0);
        return t;
    }

    private GradientDrawable fill(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(radiusDp));
        return g;
    }

    private View tile(String big, String label, String cap) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(fill(0x0FFFFFFF, 16));
        t.setPadding(px(12), px(12), px(12), px(12));
        t.addView(tv(big, 21, INK, 200));
        TextView l = tv(label, 10.5f, MUT, 400);
        l.setLetterSpacing(0.14f);
        l.setPadding(0, px(6), 0, 0);
        t.addView(l);
        TextView k = tv(cap, 11.5f, EVID, 400);
        k.setPadding(0, px(3), 0, 0);
        t.addView(k);
        return t;
    }

    private LinearLayout.LayoutParams half(boolean first) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        if (first) l.rightMargin = px(5); else l.leftMargin = px(5);
        return l;
    }

    private void addScatterCard(Pack pk) {
        WellbeingExtra.Scatter s = pk.sc;
        LinearLayout c = card();
        c.addView(caps("WORK NIGHTS: BED TIME AND SLEEP", BLUE));
        c.addView(opinion(s.head, 19));
        if (s.n >= WellbeingExtra.MIN_SCATTER) c.addView(new ScatterView(this, s.bedMin, s.hrs, s.early), lpH(220, 10));
        c.addView(note(s.line));
        if (s.n >= WellbeingExtra.MIN_SCATTER)
            addEvidence(c, "Your data: " + s.n + " work nights (a wake-up before 07:00) in the last 14 days. Each dot is one night: teal in bed before 23:00, amber after. The dashed lines are the 7 h mark, 23:00 and each side's average. A few nights show a link, not a cause.");
        col.addView(c, lp(12));
    }

    private void addBarsCard(Pack pk) {
        WellbeingExtra.Bars b = pk.bars;
        LinearLayout c = card();
        c.addView(caps("NIGHT BY NIGHT", TEAL));
        c.addView(opinion(b.head, 19));
        if (b.n >= 3) {
            c.addView(new BarsView(this, b.hrs, b.dow), lpH(190, 10));
            c.addView(note(b.line));
            WellbeingExtra.Bests t = pk.bests;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(tile(WellbeingModel.hm(t.longestH), "LONGEST NIGHT", WellbeingExtra.dayName(t.longestWake)), half(true));
            row.addView(tile(t.run > 0 ? t.run + (t.run == 1 ? " night" : " nights") : "—", "LONGEST 7 H+ RUN", t.run > 0 ? "to " + WellbeingExtra.dayName(t.runEndWake) : "no 7 h night yet"), half(false));
            c.addView(row, lp(14));
            addEvidence(c, "Your data: each bar is the strap's asleep time for one night, labelled by the morning you woke; the dashed line is 7 h. The last 7 nights are set beside the up to 7 before them. The bests are from these same nights.");
        }
        col.addView(c, lp(12));
    }

    private void addEcgCards(Pack pk) {
        WellbeingExtra.Ecg e = pk.ecg;
        if (e == null) return;
        if (e.nAll == 0) {
            LinearLayout c = card();
            c.addView(caps("YOUR ECG READINGS", VIOLET));
            c.addView(opinion("No graded readings in the last 30 days.", 19));
            c.addView(note("Readings you save fill this in: how many were clean, how your evening pulse is spread, and your calmest evening."));
            col.addView(c, lp(12));
            return;
        }
        LinearLayout cc = card();
        cc.addView(caps("CLEAN ECG READINGS", VIOLET));
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(new MiniRingView(this, e.cleanPct >= 0 ? e.cleanPct / 100f : 0f, VIOLET, e.cleanPct >= 0 ? e.cleanPct + "%" : "—"), new LinearLayout.LayoutParams(px(88), px(88)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(tv(e.cleanHead, 18, INK, 400));
        TextView l = tv(e.cleanLine, 13, MUT, 400);
        l.setPadding(0, px(6), 0, 0);
        tx.addView(l);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = px(14);
        r.addView(tx, tp);
        cc.addView(r, lp(10));
        col.addView(cc, lp(12));

        LinearLayout ch = card();
        ch.addView(caps("EVENING PULSE", BLUE));
        ch.addView(opinion(e.histHead, 19));
        if (e.evN >= WellbeingExtra.MIN_EVENING) ch.addView(new HistView(this, e.binLo, e.binCnt, e.evMedian), lpH(190, 10));
        ch.addView(note(e.histLine));
        if (e.evN >= WellbeingExtra.MIN_EVENING)
            addEvidence(ch, "Your data: only readings graded A or B with a heart rate count; evening is 18:00 to midnight. Each bar is a 5 bpm step and the gold line is the median.");
        col.addView(ch, lp(12));

        if (e.calm) {
            LinearLayout ck = card();
            ck.addView(caps("YOUR CALMEST EVENING", TEAL));
            ck.addView(opinion(e.calmHead, 19));
            ck.addView(new CalmLineView(this, e.calmAt, e.calmHr, e.evMedian), lpH(170, 10));
            ck.addView(note(e.calmLine));
            addEvidence(ck, "Your data: the evening (18:00 to midnight) with the lowest median pulse among evenings with 3 or more readings graded A or B. The dashed line is your median across all evenings.");
            col.addView(ck, lp(12));
        }
    }

    // ---- the line of the day
    private void addLineCard() {
        String[] ln = WellbeingExtra.lineFor(todayLocalEpochDay());
        LinearLayout c = card();
        c.addView(caps("A LINE FOR TODAY", GOLD));
        TextView q = tv("“" + ln[0] + "”", 17, INK, 300);
        q.setPadding(0, px(10), 0, 0);
        c.addView(q);
        TextView a = tv(ln[1], 12, EVID, 400);
        a.setPadding(0, px(10), 0, 0);
        c.addView(a);
        TextView n = tv("A line to think about, not advice. Public-domain translation.", 12, EVID, 400);
        n.setPadding(0, px(4), 0, 0);
        c.addView(n);
        col.addView(c, lp(12));
    }

    // ---- the wind-down reminder (inside the "one small win" card, while a plan exists)
    private void fillReminder() {
        if (remBox == null) return;
        remBox.removeAllViews();
        int bed = WellbeingReminder.planBed(this);
        if (bed < 0) return;
        final boolean wOn = WellbeingReminder.on(this);
        String at = WellbeingModel.clock(WellbeingExtra.windMin(bed));
        remBox.addView(pill(wOn ? "Reminder on: " + at + " every evening" : "Remind me at " + at, wOn ? 0x26FFFFFF : TEAL, wOn ? INK : 0xFF05131E, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleReminder(); }
        }), pl(14));
        StringBuilder n = new StringBuilder(wOn ? "Tap to turn it off. " : "A notification 45 minutes before your plan time, every evening. ");
        n.append("It stays on this phone.");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean blocked = nm != null && !nm.areNotificationsEnabled();
        if ((wOn && blocked) || (!wOn && notifDenied)) n.append(" Notifications are blocked for this app, so nothing can show: allow them in Android settings.");
        if (wOn && !EcgReminder.exactOk(this)) n.append(" Android may show it a few minutes late; allow Alarms & reminders for this app to make it exact.");
        TextView t = tv(n.toString(), 12, EVID, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(8), 0, 0);
        remBox.addView(t);
        if (wOn) {
            TextView test = tv("Send a test now", 13, MUT, 600);
            test.setGravity(Gravity.CENTER);
            test.setPadding(0, px(12), 0, 0);
            test.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { WellbeingReminder.post(getApplicationContext()); }
            });
            remBox.addView(test);
        }
    }

    private void toggleReminder() {
        if (WellbeingReminder.on(this)) {
            WellbeingReminder.setOn(this, false);
            fillReminder();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, WellbeingReminder.PERM_REQ); } catch (Throwable ignored) { }
            return;
        }
        notifDenied = false;
        WellbeingReminder.setOn(this, true);
        fillReminder();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != WellbeingReminder.PERM_REQ) return;
        boolean ok = res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        notifDenied = !ok;
        if (ok) WellbeingReminder.setOn(this, true);
        fillReminder();
    }

    // ---- the two-week WHO-5 check-in
    private void fillWho() {
        whoCard.removeAllViews();
        whoCard.addView(caps("A TWO-WEEK CHECK-IN", VIOLET));
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String hist = p.getString("who5_hist", "");
        long[] last = WellbeingExtra.who5Entry(hist, 0), prev = WellbeingExtra.who5Entry(hist, 1);
        long today = todayLocalEpochDay();
        if (last == null) {
            whoCard.addView(opinion("Five questions about how you have felt.", 19));
            whoCard.addView(note("The WHO-5 is a short well-being questionnaire from the World Health Organization. You answer for the last two weeks. Your answers stay on this phone."));
        } else {
            String[] tx = WellbeingExtra.who5Text((int) last[1], prev == null ? -1 : (int) prev[1], prev == null ? 0L : prev[0]);
            whoCard.addView(opinion(tx[0], 26));
            long ago = today - last[0];
            TextView when = tv("Done " + (ago <= 0 ? "today" : ago == 1 ? "yesterday" : ago + " days ago") + ". Every two weeks is enough.", 12, EVID, 400);
            when.setPadding(0, px(4), 0, 0);
            whoCard.addView(when);
            whoCard.addView(note(tx[1]));
            if (last[1] <= 50) {
                TextView sam = tv("If things feel heavy, Samaritans are free to call on 116 123, any time. Tap to call.", 14, GOLD, 600);
                sam.setPadding(0, px(12), 0, 0);
                sam.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:116123"))); } catch (Throwable ignored) { } }
                });
                whoCard.addView(sam);
            }
        }
        whoCard.addView(pill(last == null ? "Start the 5 questions" : "Take it again", VIOLET, 0xFF0E0A1F, new View.OnClickListener() {
            @Override public void onClick(View v) { askWho(0, new int[5]); }
        }), pl(14));
        addEvidence(whoCard, "Why this: the WHO-5 is a five-question well-being questionnaire (World Health Organization, 1998). A review of 213 articles (Topp and colleagues 2015, Psychotherapy and Psychosomatics) found it valid both as a well-being measure and as a screen. Each answer scores 0 to 5 and the total is multiplied by 4. It is free to use.");
    }

    private void askWho(final int idx, final int[] ans) {
        if (idx >= 5) { finishWho(ans); return; }
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(outline(VIOLET));
        box.setPadding(px(20), px(20), px(20), px(14));
        box.addView(caps("QUESTION " + (idx + 1) + " OF 5", VIOLET));
        TextView lead = tv("Over the last two weeks", 12, EVID, 400);
        lead.setPadding(0, px(10), 0, 0);
        box.addView(lead);
        TextView q = tv(WellbeingExtra.WHO5_ITEMS[idx], 19, INK, 400);
        q.setPadding(0, px(4), 0, px(8));
        box.addView(q);
        for (int k = 0; k < WellbeingExtra.WHO5_SCALE.length; k++) {
            final int kk = k;
            TextView o = tv(WellbeingExtra.WHO5_SCALE[k], 15, INK, 400);
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setBackground(fill(0x12FFFFFF, 14));
            o.setPadding(px(14), 0, px(14), 0);
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, px(46));
            ol.topMargin = px(7);
            o.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { ans[idx] = WellbeingExtra.who5Score(kk); d.dismiss(); askWho(idx + 1, ans); }
            });
            box.addView(o, ol);
        }
        TextView cancel = tv("Cancel", 14, MUT, 600);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, px(14), 0, px(4));
        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        box.addView(cancel);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void finishWho(int[] ans) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit().putString("who5_hist", WellbeingExtra.who5Push(p.getString("who5_hist", ""), todayLocalEpochDay(), WellbeingExtra.who5Pct(ans))).commit();
        fillWho();
    }

    // ---- three good things
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF5E6877);
        e.setTextColor(INK);
        e.setTextSize(15);
        e.setTypeface(HealthViews.font(this, 400));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setMaxLines(4);
        e.setBackground(fill(0x14FFFFFF, 14));
        e.setPadding(px(14), px(12), px(14), px(12));
        return e;
    }

    private void fillGood() {
        goodCard.removeAllViews();
        goodCard.addView(caps("THREE GOOD THINGS", GOLD));
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final long today = todayLocalEpochDay();
        String saved = p.getString("gt_" + today, null);
        int written = 0;
        for (int d = 0; d < 14; d++) if (p.contains("gt_" + (today - d))) written++;
        if (saved != null && !editGood) {
            String[] parts = WellbeingExtra.unpackGood(saved);
            goodCard.addView(opinion("Today's good things, in your words.", 19));
            for (int i = 0; i < 3; i++) {
                if (parts[i].length() == 0) continue;
                TextView t = tv((i + 1) + ".  " + parts[i], 15, INK, 400);
                t.setPadding(0, px(10), 0, 0);
                goodCard.addView(t);
            }
            if (parts[3].length() > 0) {
                TextView w = tv("Why they happened: " + parts[3], 14, MUT, 400);
                w.setPadding(0, px(12), 0, 0);
                goodCard.addView(w);
            }
            TextView edit = tv("Edit", 14, MUT, 600);
            edit.setPadding(0, px(14), 0, 0);
            edit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { editGood = true; fillGood(); } });
            goodCard.addView(edit);
        } else {
            goodCard.addView(opinion("What went well today, and why?", 19));
            goodCard.addView(note("Three things that went well, small ones count, and a line on why they happened. It takes about two minutes."));
            List<String> seeds = lastPack == null ? new ArrayList<String>() : WellbeingExtra.goodSeeds(lastPack.o, lastPack.ecg);
            if (seeds.size() > 0) {
                TextView hdr = caps("FROM YOUR DATA, TAP ONE TO USE IT", MUT);
                hdr.setPadding(0, px(14), 0, 0);
                goodCard.addView(hdr);
                for (final String s : seeds) {
                    TextView t = tv(s, 14, TEAL, 400);
                    t.setPadding(0, px(8), 0, 0);
                    t.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (gtFields == null) return;
                            for (int i = 0; i < 3; i++) if (gtFields[i].getText().toString().trim().length() == 0) { gtFields[i].setText(s); return; }
                        }
                    });
                    goodCard.addView(t);
                }
            }
            String[] parts = saved != null ? WellbeingExtra.unpackGood(saved) : new String[] { "", "", "", "" };
            String[] hints = { "1. Something that went well", "2. Another", "3. And another", "Why did these happen?" };
            gtFields = new EditText[4];
            for (int i = 0; i < 4; i++) {
                EditText e = field(hints[i]);
                e.setText(parts[i]);
                gtFields[i] = e;
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
                fp.topMargin = px(i == 3 ? 14 : 8);
                goodCard.addView(e, fp);
            }
            goodCard.addView(pill("Save today's three", GOLD, 0xFF1A1305, new View.OnClickListener() {
                @Override public void onClick(View v) { saveGood(p, today); }
            }), pl(14));
        }
        TextView meta = tv("Written on " + written + " of the last 14 days. Stays on this phone.", 12, EVID, 400);
        meta.setPadding(0, px(12), 0, 0);
        goodCard.addView(meta);
        addEvidence(goodCard, "Why this: in an online trial (Seligman and colleagues 2005, American Psychologist), people who wrote down three good things each day for a week, and why each happened, were happier up to six months later than people given a control exercise. A review of 51 studies of positive-psychology exercises in general (Sin and Lyubomirsky 2009, 4,266 people) found a mean effect of r = 0.29 on well-being. The people were volunteers and the measures self-reported, so this is encouraging, not proof.");
    }

    private void saveGood(SharedPreferences p, long today) {
        if (gtFields == null) return;
        String a = gtFields[0].getText().toString(), b = gtFields[1].getText().toString(), c = gtFields[2].getText().toString(), w = gtFields[3].getText().toString();
        if (a.trim().length() == 0 && b.trim().length() == 0 && c.trim().length() == 0) {
            gtFields[0].setHint("Write at least one thing, then save");
            return;
        }
        SharedPreferences.Editor ed = p.edit();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("gt_")) continue;
            try { if (Long.parseLong(k.substring(3)) < today - 60) ed.remove(k); } catch (Throwable ignored) { }
        }
        ed.putString("gt_" + today, WellbeingExtra.packGood(a, b, c, w)).commit();
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            View f = getCurrentFocus();
            if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        } catch (Throwable ignored) { }
        editGood = false;
        fillGood();
    }
    private void addMindCards() {
        whoCard = card();
        fillWho();
        col.addView(whoCard, lp(12));
        goodCard = card();
        fillGood();
        col.addView(goodCard, lp(12));
        moodCard = card(); fillMood(); col.addView(moodCard, lp(12));      // 0.64.0
        ifCard = card(); fillIf(); col.addView(ifCard, lp(12));
        weekCard = card(); fillWeek(); col.addView(weekCard, lp(12));
        workCard = card(); fillWork(); col.addView(workCard, lp(12));      // 0.64.0 preview cards
        gapCard = card(); fillGap(); col.addView(gapCard, lp(12));
    }


    // ------------------------------------------------------------------ 0.64.0: mood and energy, your own pattern, calm minute RMSSD, if-then, the week
    private long today() { return todayLocalEpochDay(); }

    private void fillMood() {
        moodCard.removeAllViews();
        moodCard.addView(caps("HOW ARE YOU RIGHT NOW", ROSE));
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        List<Mind64.Entry> es = Mind64.parse(p.getString("mind_log", ""));
        moodCard.addView(opinion("Two taps: mood, then energy.", 19));
        moodCard.addView(tv("MOOD   1 low  to  5 good", 12, EVID, 600), pl(10));
        moodCard.addView(chipRow(true), pl(6));
        moodCard.addView(tv("ENERGY   1 flat  to  5 full", 12, EVID, 600), pl(10));
        moodCard.addView(chipRow(false), pl(6));
        int todayN = 0; long t = today(); for (Mind64.Entry e : es) if (e.day == t) todayN++;
        moodCard.addView(note(todayN == 0 ? "Nothing logged today. It stays on this phone." : "Logged " + todayN + (todayN == 1 ? " time" : " times") + " today. It stays on this phone."));
        Mind64.Pattern P = Mind64.pattern(es, lastPack == null ? new java.util.HashMap<Long, Double>() : lastPack.wake);
        moodCard.addView(caps("YOUR OWN PATTERN", MUT), pl(16));
        moodCard.addView(opinion(P.head, 17));
        moodCard.addView(note(P.body));
        addEvidence(moodCard, "How this is worked out: each day's entries are averaged, then days are split by the hours slept in the night before (under 7 h, or 7 h and over). It needs at least " + Mind64.MIN_ENTRIES + " entries on " + Mind64.MIN_DAYS + " days and " + Mind64.MIN_GROUP + " days in each group before it shows anything. It reports your own averages only; it makes no claim about cause and gives no advice. The 7 h line is a common reference point, not your personal need.");
    }

    private LinearLayout chipRow(final boolean mood) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        for (int v = 1; v <= 5; v++) {
            final int val = v; boolean sel = (mood ? selMood : selEnergy) == v;
            TextView c = pill(String.valueOf(v), sel ? ROSE : 0x1AFFFFFF, sel ? 0xFF1A0A10 : INK, new View.OnClickListener() { @Override public void onClick(View x) { if (mood) selMood = val; else selEnergy = val; chosen(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(46), 1f); lp.rightMargin = v < 5 ? px(8) : 0; r.addView(c, lp);
        }
        return r;
    }

    private void chosen() {
        if (selMood > 0 && selEnergy > 0) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            p.edit().putString("mind_log", Mind64.add(p.getString("mind_log", ""), System.currentTimeMillis(), today(), selMood, selEnergy)).apply();
            selMood = 0; selEnergy = 0; showMessageShort("Saved");
        }
        fillMood(); fillWeek();
    }
    private void showMessageShort(String s) { try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { } }

    private void fillIf() {
        ifCard.removeAllViews();
        ifCard.addView(caps("YOUR IF-THEN", BLUE));
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int cue = p.getInt("if_cue", -1), act = p.getInt("if_act", -1);
        if (cue >= 0 && act >= 0 && !editIf) {
            ifCard.addView(opinion(Mind64.plan(cue, act), 19));
            ifCard.addView(pill("Change it", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { editIf = true; ifCue = p.getInt("if_cue", -1); ifAct = p.getInt("if_act", -1); fillIf(); } }), pl(12));
        } else {
            ifCard.addView(opinion("Pick when, then what.", 19));
            ifCard.addView(tv("IF", 12, EVID, 600), pl(8));
            for (int i = 0; i < Mind64.CUES.length; i++) { final int k = i; ifCard.addView(pill(Mind64.CUES[i], ifCue == i ? BLUE : 0x1AFFFFFF, ifCue == i ? 0xFF05131E : INK, new View.OnClickListener() { @Override public void onClick(View v) { ifCue = k; fillIf(); } }), pl(6)); }
            ifCard.addView(tv("THEN I", 12, EVID, 600), pl(12));
            for (int i = 0; i < Mind64.ACTS.length; i++) { final int k = i; ifCard.addView(pill(Mind64.ACTS[i], ifAct == i ? BLUE : 0x1AFFFFFF, ifAct == i ? 0xFF05131E : INK, new View.OnClickListener() { @Override public void onClick(View v) { ifAct = k; fillIf(); } }), pl(6)); }
            if (ifCue >= 0 && ifAct >= 0) ifCard.addView(pill("Save: " + Mind64.plan(ifCue, ifAct), BLUE, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { p.edit().putInt("if_cue", ifCue).putInt("if_act", ifAct).apply(); editIf = false; fillIf(); } }), pl(12));
        }
        addEvidence(ifCard, "Why this: planning a specific if-then response in advance (an \"implementation intention\") is a studied way of following through on an intention; see the meta-analysis by Gollwitzer and Sheeran (2006). The studies mostly measure goals people set themselves, not mood or heart signs, and effects vary. Your plan stays on this phone; nothing here checks whether you did it.");
    }
    private boolean editIf = false;

    private void logCalm(long startMs, long endMs) {
        if (startMs <= 0L) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String old = p.getString("calm_log", ""); String[] ls = old.isEmpty() ? new String[0] : old.split("\n"); StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ls.length - 59); i < ls.length; i++) sb.append(ls[i]).append('\n');
        sb.append(startMs).append(',').append(endMs).append(",,,0,0\n"); p.edit().putString("calm_log", sb.toString()).apply();
        fillCalm(); ui.postDelayed(new Runnable() { @Override public void run() { computeCalm(); } }, 63000L);
    }

    /** Fills the before / after RMSSD of every calm minute that has its 60 s after-window recorded (live beat intervals of the strap; off the main thread). */
    private void computeCalm() {
        new Thread(new Runnable() { @Override public void run() {
            try {
                SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE); String old = p.getString("calm_log", ""); if (old.isEmpty()) return;
                String[] ls = old.split("\n"); boolean ch = false; StringBuilder sb = new StringBuilder(); long now = System.currentTimeMillis();
                for (String ln : ls) {
                    String[] f = ln.split(",", -1);
                    if (f.length >= 6 && f[2].isEmpty() && f[3].isEmpty() && Long.parseLong(f[1]) + 62000L < now) {
                        long s0 = Long.parseLong(f[0]), e0 = Long.parseLong(f[1]); List<Mind64.Row> rows = liveRows(s0 - 61000L, e0 + 61000L);
                        Mind64.Win a = Mind64.window(rows, s0 - 60000L, s0), b = Mind64.window(rows, e0, e0 + 60000L);
                        ln = f[0] + "," + f[1] + "," + (a.ok ? String.format(Locale.US, "%.1f", a.rmssd) : "na") + "," + (b.ok ? String.format(Locale.US, "%.1f", b.rmssd) : "na") + "," + a.beats + "," + b.beats; ch = true;
                    }
                    sb.append(ln).append('\n');
                }
                if (ch) { p.edit().putString("calm_log", sb.toString()).apply(); runOnUiThread(new Runnable() { @Override public void run() { if (calmCard != null) fillCalm(); if (weekCard != null) fillWeek(); } }); }
            } catch (Throwable ignored) { }
        } }, "calm-rmssd").start();
    }

    private List<Mind64.Row> liveRows(long fromMs, long toMs) {
        List<Mind64.Row> out = new ArrayList<>();
        try {
            android.database.Cursor q = DayLog.db(this).rawQuery("SELECT ms, hr, rr FROM live_hr WHERE ms >= ? AND ms < ? ORDER BY ms", new String[] { String.valueOf(fromMs), String.valueOf(toMs) });
            try {
                while (q.moveToNext()) {
                    Mind64.Row r = new Mind64.Row(); r.ms = q.getLong(0); r.hr = q.getInt(1); String t = q.getString(2);
                    if (t != null && !t.isEmpty()) { String[] w = t.split(","); r.rr = new int[w.length]; for (int i = 0; i < w.length; i++) { try { r.rr[i] = Integer.parseInt(w[i].trim()); } catch (Throwable e) { r.rr[i] = 0; } } }
                    out.add(r);
                }
            } finally { q.close(); }
        } catch (Throwable ignored) { }
        return out;
    }

    private void fillCalm() {
        if (calmCard == null) return;
        calmCard.removeAllViews();
        calmCard.addView(caps("YOUR CALM MINUTES", TEAL));
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE); String log = p.getString("calm_log", "");
        String[] ls = log.isEmpty() ? new String[0] : log.split("\n");
        if (ls.length == 0) { calmCard.addView(note("After each calm minute this lists the pulse variability (RMSSD) of the 60 seconds before and the 60 seconds after, from the strap's live beat intervals. The strap must be connected and on your wrist during and after the minute.")); computeCalmLater(); return; }
        int shown = 0; java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("d MMM HH:mm", Locale.UK);
        for (int i = ls.length - 1; i >= 0 && shown < 5; i--, shown++) {
            String[] f = ls[i].split(",", -1); if (f.length < 6) continue; String when = df.format(new java.util.Date(Long.parseLong(f[0])));
            String res;
            if (f[2].isEmpty()) res = System.currentTimeMillis() < Long.parseLong(f[1]) + 62000L ? "counting the minute after... stay on this page" : "waiting for the beat intervals";
            else { double a = parseD(f[2]), b = parseD(f[3]); res = Mind64.calmLine(a, b, parseI(f[4]), parseI(f[5])); }
            calmCard.addView(tv(when + "   " + res, 13, INK, 400), pl(8));
        }
        calmCard.addView(note("One minute is a short window and the strap's optical beat intervals read higher than a chest strap's, so use this to compare one minute with another on the same wrist, not as a measure of calm. It does not tell you the breathing worked."));
        computeCalmLater();
    }
    private void computeCalmLater() { ui.postDelayed(new Runnable() { @Override public void run() { computeCalm(); } }, 800L); }
    private static double parseD(String s) { try { return Double.parseDouble(s); } catch (Throwable e) { return Double.NaN; } }
    private static int parseI(String s) { try { return Integer.parseInt(s); } catch (Throwable e) { return 0; } }

    private void fillWeek() {
        if (weekCard == null) return;
        weekCard.removeAllViews();
        weekCard.addView(caps("YOUR WEEK ON ONE PAGE", GOLD));
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE); long t = today();
        int good = 0; for (int d = 0; d < 7; d++) if (p.contains("gt_" + (t - d))) good++;
        String log = p.getString("calm_log", ""); int calm = 0; long cut = System.currentTimeMillis() - 7L * 86400000L; if (!log.isEmpty()) for (String ln : log.split("\n")) { try { if (Long.parseLong(ln.split(",")[0]) >= cut) calm++; } catch (Throwable ignored) { } }
        double[] hrs = lastPack == null || lastPack.o == null ? new double[0] : lastPack.o.hrs;
        final Mind64.Week w = Mind64.week(Mind64.parse(p.getString("mind_log", "")), t, hrs, calm, good);
        final String text = Mind64.weekText(w);
        weekCard.addView(tv(text, 14, INK, 400), pl(8));
        weekCard.addView(pill("Copy as text", 0x1AFFFFFF, INK, new View.OnClickListener() { @Override public void onClick(View v) { try { ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("week", text)); showMessageShort("Copied"); } catch (Throwable ignored) { } } }), pl(12));
        weekCard.addView(note("Everything here is a count or an average of what you entered or what the strap recorded. It stays on this phone unless you copy it."));
    }


    // ------------------------------------------------------------------ 0.64.0 preview cards (Pair64): descriptive, sample-gated, no link claimed, no advice
    private void fillWork() {
        if (workCard == null) return;
        workCard.removeAllViews();
        workCard.addView(caps("WORK-DAY LOAD (PREVIEW)", MUT));
        workCard.addView(note("Weekday steps from 06:00 to 18:00 beside the RMSSD of the night that followed. Gathering..."));
        new Thread(new Runnable() { @Override public void run() {
            final double[][] d = Pair64.workDays(WellbeingActivity.this, 70); final Pair64.Split S = Pair64.split(d[0], d[1], "Lower-step", "Higher-step", " thousand steps", 0.001);
            runOnUiThread(new Runnable() { @Override public void run() {
                if (workCard == null) return; workCard.removeAllViews(); workCard.addView(caps("WORK-DAY LOAD (PREVIEW)", MUT)); workCard.addView(opinion(S.head, 17)); workCard.addView(note(S.body));
                addEvidence(workCard, "How this is worked out: each weekday with at least 8 of the 12 hours recorded gets its steps (counted inside the recorded minutes, so a few per cent short) and the RMSSD of the night after it; days are split at the middle step count. Needs " + Pair64.MIN_N + " days and " + Pair64.MIN_HALF + " on each side. On your own earlier evenings no link between steps and the evening RMSSD was established (11 to 12 evenings), so this card shows two middle values and claims nothing.");
            } });
        } }, "work-load").start();
    }

    private void fillGap() {
        if (gapCard == null) return;
        gapCard.removeAllViews();
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        gapCard.setBackground(outline(EVID));
        gapCard.addView(caps("OVERNIGHT GAP (PREVIEW, OFF UNLESS YOU TURN IT ON)", MUT));
        final boolean on = p.getBoolean("gap_on", false);
        gapCard.addView(pill(on ? "On: tap to turn off" : "Off: tap to turn on", on ? 0x1AFFFFFF : BLUE, on ? INK : 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) { p.edit().putBoolean("gap_on", !on).apply(); fillGap(); } }), pl(10));
        if (!on) { gapCard.addView(note("When on, you log your last meal with one tap and this describes the hours between it and bed beside that night's RMSSD, once there are enough nights. It never suggests when to eat. Meal times stay on this phone.")); return; }
        gapCard.addView(pill("Log my last meal now", TEAL, 0xFF05131E, new View.OnClickListener() { @Override public void onClick(View v) {
            String old = p.getString("meal_log", ""); String[] ls = old.isEmpty() ? new String[0] : old.split("\n"); StringBuilder sb = new StringBuilder(); for (int i = Math.max(0, ls.length - 199); i < ls.length; i++) sb.append(ls[i]).append('\n');
            sb.append(System.currentTimeMillis()).append('\n'); p.edit().putString("meal_log", sb.toString()).apply(); showMessageShort("Logged"); fillGap(); } }), pl(8));
        List<Long> meals = new ArrayList<>(); String ml = p.getString("meal_log", ""); if (!ml.isEmpty()) for (String ln : ml.split("\n")) { try { meals.add(Long.parseLong(ln.trim())); } catch (Throwable ignored) { } }
        final List<Long> fm = meals; final TextView st = note("Gathering..."); gapCard.addView(st);
        new Thread(new Runnable() { @Override public void run() {
            final double[][] d = Pair64.mealGaps(WellbeingActivity.this, fm); final Pair64.Split S = Pair64.split(d[0], d[1], "Shorter-gap", "Longer-gap", " h", 1.0);
            runOnUiThread(new Runnable() { @Override public void run() { st.setText(S.head + ". " + S.body); } });
        } }, "meal-gap").start();
        addEvidence(gapCard, "How this is worked out: for each night with a logged meal 1 to 8 hours before bed, the hours from the last meal to bed beside that night's RMSSD; nights are split at the middle gap. Needs " + Pair64.MIN_N + " nights and " + Pair64.MIN_HALF + " on each side. Descriptive only: no cause is claimed and nothing is recommended.");
    }

    // ------------------------------------------------------------------ small builders
    private TextView tv(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(HealthViews.font(this, weight));
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0f, 1.18f);
        return t;
    }

    private TextView caps(String s, int color) {
        TextView t = tv(s, 11, color, 400);
        t.setLetterSpacing(0.16f);
        return t;
    }

    private TextView opinion(String s, float sp) {
        TextView t = tv(s, sp, INK, 400);
        t.setPadding(0, px(8), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(HealthViews.glass(this, 22));
        c.setPadding(px(18), px(18), px(18), px(18));
        return c;
    }

    private GradientDrawable outline(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(HealthViews.CARD);
        g.setCornerRadius(px(22));
        g.setStroke(Math.max(1, px(1)), HealthViews.alpha(color, 0.35f));
        return g;
    }

    private LinearLayout.LayoutParams lp(float topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = px(topDp);
        return l;
    }

    private LinearLayout.LayoutParams pl(float topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, px(52));
        l.topMargin = px(topDp);
        return l;
    }

    private LinearLayout.LayoutParams tl() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.leftMargin = px(5);
        l.rightMargin = px(5);
        return l;
    }

    private TextView pill(String text, int color, int fg, View.OnClickListener l) {
        TextView t = tv(text, 16, fg, 600);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(26));
        t.setBackground(g);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    private void addEvidence(LinearLayout card, String ev) {
        if (ev == null || ev.length() == 0) return;
        View line = new View(this);
        line.setBackgroundColor(0x12FFFFFF);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        ll.topMargin = px(12);
        card.addView(line, ll);
        TextView t = tv("", 12, EVID, 400);
        t.setText(rich(ev));
        t.setPadding(0, px(12), 0, 0);
        card.addView(t);
    }

    private CharSequence rich(String s) {
        SpannableString sp = new SpannableString(s);
        String[] keys = {"Your data:", "Why it matters:", "Why this wording:", "Why this:"};
        for (String k : keys) {
            int i = s.indexOf(k);
            while (i >= 0) {
                sp.setSpan(new StyleSpan(Typeface.BOLD), i, i + k.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sp.setSpan(new ForegroundColorSpan(0xFFB9C3D1), i, i + k.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = s.indexOf(k, i + k.length());
            }
        }
        return sp;
    }

    private View mini(float frac, int color, String big, String label, String cap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setBackground(HealthViews.glass(this, 22));
        c.setPadding(px(6), px(14), px(6), px(12));
        c.addView(new MiniRingView(this, frac, color, big), new LinearLayout.LayoutParams(px(64), px(64)));
        TextView l = tv(label, 10.5f, MUT, 400);
        l.setLetterSpacing(0.14f);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, px(8), 0, 0);
        c.addView(l);
        TextView t = tv(cap, 11, EVID, 400);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(4), 0, 0);
        c.addView(t);
        return c;
    }

    // ------------------------------------------------------------------ views
    /** A soft blue glow at the top of the page. */
    static final class GlowView extends View {
        GlowView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(w / 2f, HealthViews.dp(getContext(), 120), w * 0.9f, new int[] {HealthViews.alpha(BLUE, 0.20f), HealthViews.alpha(BLUE, 0f)}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, getHeight(), p);
        }
    }

    /** The 270-degree ring: one segment per night, teal at 7 h or more and amber under, the hours written on each, the word in the middle. */
    static final class HeroView extends View {
        private final double[] hrs;
        private final String word, sub;
        HeroView(Context c, double[] hrs, String word, String sub) { super(c); this.hrs = hrs; this.word = word; this.sub = sub; }
        @Override protected void onMeasure(int w, int h) {
            int s = MeasureSpec.getSize(w);
            setMeasuredDimension(s, Math.round(s * 0.88f));
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float size = getWidth(), cx = size / 2f, cy = size / 2f, r = size * 0.38f, sw = HealthViews.dp(c, 15f);
            RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(0x11FFFFFF);
            cv.drawArc(box, 135f, 270f, false, p);
            int n = hrs.length;
            float seg = 270f / n, capDeg = (float) Math.toDegrees((sw / 2f) / r), gap = 1.6f;
            Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setTextAlign(Paint.Align.CENTER);
            tx.setTypeface(HealthViews.font(c, 600));
            tx.setTextSize(HealthViews.dp(c, 10));
            tx.setLetterSpacing(0.08f);
            tx.setColor(0xFF7B8696);
            for (int i = 0; i < n; i++) {
                float a0 = 135f + seg * i + capDeg + gap, a1 = 135f + seg * (i + 1) - capDeg - gap;
                if (a1 - a0 < 0.8f) continue;
                boolean ok = hrs[i] >= WellbeingModel.GOAL_H;
                int col = ok ? TEAL : GOLD;
                float[] ws = {sw + HealthViews.dp(c, 14), sw + HealthViews.dp(c, 9), sw + HealthViews.dp(c, 4)};
                float[] al = {0.05f, 0.09f, 0.14f};
                for (int k = 0; k < 3; k++) {
                    p.setStrokeWidth(ws[k]);
                    p.setColor(HealthViews.alpha(col, al[k]));
                    cv.drawArc(box, a0, a1 - a0, false, p);
                }
                p.setStrokeWidth(sw);
                p.setColor(HealthViews.alpha(col, ok ? 1f : 0.62f));
                cv.drawArc(box, a0, a1 - a0, false, p);
                double mid = Math.toRadians((a0 + a1) / 2f);
                float lr = r + sw / 2f + HealthViews.dp(c, 14);
                cv.drawText(String.format(Locale.UK, "%.1f", hrs[i]), cx + (float) (lr * Math.cos(mid)), cy + (float) (lr * Math.sin(mid)) + HealthViews.dp(c, 3.5f), tx);
            }
            double ea = Math.toRadians(405f - capDeg - gap);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFFFFFFFF);
            cv.drawCircle(cx + (float) (r * Math.cos(ea)), cy + (float) (r * Math.sin(ea)), HealthViews.dp(c, 3.8f), dot);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(0xFFFFFFFF);
            t.setTypeface(HealthViews.font(c, 200));
            float ts = size * 0.17f;
            t.setTextSize(ts);
            while (t.measureText(word) > size * 0.58f && ts > 14) { ts -= 2; t.setTextSize(ts); }
            cv.drawText(word, cx, cy + ts * 0.28f, t);
            t.setTypeface(HealthViews.font(c, 600));
            t.setTextSize(HealthViews.dp(c, 11.5f));
            t.setLetterSpacing(0.26f);
            t.setColor(MUT);
            cv.drawText(sub, cx, cy + ts * 0.28f + HealthViews.dp(c, 30), t);
        }
    }

    /** Wake-up times of the work mornings on a time line; dots that fall together stack upwards. */
    static final class WakeStripView extends View {
        private final int[] mins;
        WakeStripView(Context c, int[] mins) { super(c); this.mins = mins; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x0 = HealthViews.dp(c, 12), x1 = w - HealthViews.dp(c, 12), base = h - HealthViews.dp(c, 36);
            int lo = mins[0], hi = mins[0];
            for (int m : mins) { if (m < lo) lo = m; if (m > hi) hi = m; }
            int t0 = (int) Math.floor((lo - 15) / 30.0) * 30, t1 = (int) Math.ceil((hi + 15) / 30.0) * 30;
            if (t1 - t0 < 60) t1 = t0 + 60;
            int step = (t1 - t0) > 180 ? 60 : 30;
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setColor(0x1FFFFFFF);
            ln.setStrokeWidth(Math.max(1f, HealthViews.dp(c, 1)));
            cv.drawLine(x0, base, x1, base, ln);
            Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
            tx.setTextAlign(Paint.Align.CENTER);
            tx.setTypeface(HealthViews.font(c, 400));
            tx.setTextSize(HealthViews.dp(c, 10.5f));
            tx.setColor(0xFF7B8696);
            for (int t = t0; t <= t1; t += step) {
                float x = x0 + (t - t0) / (float) (t1 - t0) * (x1 - x0);
                cv.drawLine(x, base - HealthViews.dp(c, 4), x, base + HealthViews.dp(c, 4), ln);
                cv.drawText(WellbeingModel.clock(t), x, base + HealthViews.dp(c, 22), tx);
            }
            float xl = x0 + (lo - t0) / (float) (t1 - t0) * (x1 - x0), xh = x0 + (hi - t0) / (float) (t1 - t0) * (x1 - x0);
            Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
            band.setColor(HealthViews.alpha(TEAL, 0.08f));
            cv.drawRoundRect(new RectF(xl - HealthViews.dp(c, 10), HealthViews.dp(c, 8), xh + HealthViews.dp(c, 10), base - HealthViews.dp(c, 4)), HealthViews.dp(c, 14), HealthViews.dp(c, 14), band);
            Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
            float[] placedX = new float[mins.length];
            int[] lvl = new int[mins.length];
            for (int i = 0; i < mins.length; i++) {
                float x = x0 + (mins[i] - t0) / (float) (t1 - t0) * (x1 - x0);
                int l = 0;
                for (int j = 0; j < i; j++) if (Math.abs(placedX[j] - x) < HealthViews.dp(c, 11)) l++;
                placedX[i] = x;
                lvl[i] = l;
                float y = Math.max(HealthViews.dp(c, 14), base - HealthViews.dp(c, 14) - l * HealthViews.dp(c, 12));
                d.setColor(HealthViews.alpha(TEAL, 0.16f));
                cv.drawCircle(x, y, HealthViews.dp(c, 8.5f), d);
                d.setColor(TEAL);
                cv.drawCircle(x, y, HealthViews.dp(c, 5f), d);
            }
        }
    }

    /** A small 270-degree ring with a number in the middle. */
    static final class MiniRingView extends View {
        private final float frac; private final int color; private final String big;
        MiniRingView(Context c, float frac, int color, String big) { super(c); this.frac = Math.max(0f, Math.min(1f, frac)); this.color = color; this.big = big; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float size = getWidth(), cx = size / 2f, cy = size / 2f, sw = HealthViews.dp(c, 7), r = size / 2f - sw / 2f - HealthViews.dp(c, 5);
            RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(sw);
            p.setColor(0x12FFFFFF);
            cv.drawArc(box, 135f, 270f, false, p);
            if (frac > 0f) {
                p.setStrokeWidth(sw + HealthViews.dp(c, 5));
                p.setColor(HealthViews.alpha(color, 0.12f));
                cv.drawArc(box, 135f, 270f * frac, false, p);
                p.setStrokeWidth(sw);
                p.setColor(color);
                cv.drawArc(box, 135f, 270f * frac, false, p);
            }
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(0xFFFFFFFF);
            t.setTypeface(HealthViews.font(c, 200));
            t.setTextSize(HealthViews.dp(c, 22));
            cv.drawText(big, cx, cy + HealthViews.dp(c, 7.5f), t);
        }
    }

    /** One minute of paced breathing: 4 s in, 6 s out, 6 breaths. The orb grows and shrinks with the breath; the ticks light up as the minute passes. Pacing only. */
    static final class BreathView extends View {
        static final long TOTAL_MS = 60000L, IN_MS = 4000L, CYCLE_MS = 10000L;
        private boolean running, done;
        private long t0;
        private TextView btn; Runnable onStartCb, onFinishCb;
        BreathView(Context c) { super(c); }
        void bind(TextView b) {
            btn = b;
            btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (running) stop(false); else start(); } });
        }
        void start() {
            running = true; done = false; t0 = SystemClock.elapsedRealtime(); if (onStartCb != null) { try { onStartCb.run(); } catch (Throwable ignored) { } }
            try { ((Activity) getContext()).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
            if (btn != null) btn.setText("Stop");
            postInvalidateOnAnimation();
        }
        void stop(boolean finished) {
            running = false; done = finished;
            try { ((Activity) getContext()).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
            if (btn != null) btn.setText(finished ? "Do it again" : "Start the minute");
            if (finished && onFinishCb != null) { try { onFinishCb.run(); } catch (Throwable ignored) { } }
            invalidate();
        }
        private static float ease(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), size = Math.min(w, h), cx = w / 2f, cy = h / 2f;
            long el = running ? SystemClock.elapsedRealtime() - t0 : 0L;
            if (running && el >= TOTAL_MS) { running = false; done = true; el = 0; post(new Runnable() { @Override public void run() { stop(true); } }); }
            float scale, frac = running ? Math.min(1f, el / (float) TOTAL_MS) : (done ? 1f : 0f);
            String big, sub;
            if (running) {
                long ph = el % CYCLE_MS;
                if (ph < IN_MS) { scale = 0.55f + 0.45f * ease(ph / (float) IN_MS); sub = "BREATHE IN"; big = String.valueOf((int) Math.ceil((IN_MS - ph) / 1000.0)); }
                else { scale = 1f - 0.45f * ease((ph - IN_MS) / (float) (CYCLE_MS - IN_MS)); sub = "BREATHE OUT"; big = String.valueOf((int) Math.ceil((CYCLE_MS - ph) / 1000.0)); }
            } else { scale = 0.78f; sub = done ? "WELL DONE" : "READY"; big = done ? "6" : "1:00"; }
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(cx, cy, size * 0.47f, new int[] {HealthViews.alpha(TEAL, 0f), HealthViews.alpha(TEAL, 0.30f), HealthViews.alpha(TEAL, 0f)}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, size * 0.47f, p);
            p.setShader(null);
            Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
            tick.setStrokeCap(Paint.Cap.ROUND);
            for (int k = 0; k < 64; k++) {
                double a = -Math.PI / 2 + 2 * Math.PI * k / 64;
                boolean major = k % 8 == 0, on = k / 64f <= frac && (running || done);
                float r0 = size * 0.405f, r1 = size * (major ? 0.475f : 0.455f);
                tick.setColor(on ? TEAL : 0x34FFFFFF);
                tick.setStrokeWidth(HealthViews.dp(c, major ? 2.2f : 1.5f));
                cv.drawLine(cx + (float) (r0 * Math.cos(a)), cy + (float) (r0 * Math.sin(a)), cx + (float) (r1 * Math.cos(a)), cy + (float) (r1 * Math.sin(a)), tick);
            }
            float R = size * 0.30f * scale;
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setShader(new RadialGradient(cx - R * 0.28f, cy - R * 0.4f, R * 1.55f, new int[] {0xFF9FF7EA, 0xFF27C7AE, 0xFF0B5A57}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, R, s);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFFFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTypeface(HealthViews.font(c, 200));
            t.setTextSize(size * 0.16f);
            cv.drawText(big, cx, cy + size * 0.045f, t);
            t.setTypeface(HealthViews.font(c, 600));
            t.setTextSize(HealthViews.dp(c, 11));
            t.setLetterSpacing(0.22f);
            t.setColor(0xEBFFFFFF);
            cv.drawText(sub, cx, cy + size * 0.045f + HealthViews.dp(c, 24), t);
            if (running) postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ 0.60.0 charts
    private static Paint textPaint(Context c, float sp, int color, Paint.Align al) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(al);
        t.setTypeface(HealthViews.font(c, 400));
        t.setTextSize(HealthViews.dp(c, sp));
        t.setColor(color);
        return t;
    }

    /** Work nights: bed time across, hours asleep up. Teal = in bed before 23:00, amber = after. Dashed: the 7 h mark, 23:00 and each side's average. */
    static final class ScatterView extends View {
        private final int[] bed; private final double[] hrs; private final boolean[] early;
        ScatterView(Context c, int[] bed, double[] hrs, boolean[] early) { super(c); this.bed = bed; this.hrs = hrs; this.early = early; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x0 = HealthViews.dp(c, 34), x1 = w - HealthViews.dp(c, 10), yTop = HealthViews.dp(c, 14), yBase = h - HealthViews.dp(c, 28);
            int lo = bed[0], hi = bed[0];
            double hMin = hrs[0], hMax = hrs[0], se = 0, sl = 0; int ne = 0, nl = 0;
            for (int i = 0; i < bed.length; i++) {
                lo = Math.min(lo, bed[i]); hi = Math.max(hi, bed[i]); hMin = Math.min(hMin, hrs[i]); hMax = Math.max(hMax, hrs[i]);
                if (early[i]) { se += hrs[i]; ne++; } else { sl += hrs[i]; nl++; }
            }
            int t0 = (int) Math.floor((Math.min(lo, WellbeingModel.LATE_BED_MIN) - 20) / 30.0) * 30, t1 = (int) Math.ceil((Math.max(hi, WellbeingModel.LATE_BED_MIN) + 20) / 30.0) * 30;
            if (t1 - t0 < 120) t1 = t0 + 120;
            double y0 = Math.floor(Math.min(hMin, 7.0) - 0.5), y1 = Math.ceil(Math.max(hMax, 7.0) + 0.5);
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStrokeWidth(Math.max(1f, HealthViews.dp(c, 1)));
            Paint lab = textPaint(c, 10.5f, 0xFF7B8696, Paint.Align.RIGHT);
            for (int g = (int) Math.ceil(y0); g <= y1; g++) {
                float y = (float) (yBase - (g - y0) / (y1 - y0) * (yBase - yTop));
                ln.setColor(0x14FFFFFF);
                cv.drawLine(x0, y, x1, y, ln);
                cv.drawText(g + " h", x0 - HealthViews.dp(c, 6), y + HealthViews.dp(c, 3.5f), lab);
            }
            Paint xl = textPaint(c, 10.5f, 0xFF7B8696, Paint.Align.CENTER);
            int step = (t1 - t0) > 240 ? 60 : 30;
            for (int t = t0; t <= t1; t += step) {
                float x = x0 + (t - t0) / (float) (t1 - t0) * (x1 - x0);
                ln.setColor(0x1FFFFFFF);
                cv.drawLine(x, yBase, x, yBase + HealthViews.dp(c, 4), ln);
                cv.drawText(WellbeingModel.clock(t), x, yBase + HealthViews.dp(c, 20), xl);
            }
            DashPathEffect dash = new DashPathEffect(new float[] { HealthViews.dp(c, 5), HealthViews.dp(c, 4) }, 0f);
            float yS = (float) (yBase - (7.0 - y0) / (y1 - y0) * (yBase - yTop));
            ln.setPathEffect(dash);
            ln.setColor(HealthViews.alpha(TEAL, 0.55f));
            ln.setStrokeWidth(HealthViews.dp(c, 1.4f));
            cv.drawLine(x0, yS, x1, yS, ln);
            float xB = x0 + (WellbeingModel.LATE_BED_MIN - t0) / (float) (t1 - t0) * (x1 - x0);
            ln.setColor(HealthViews.alpha(GOLD, 0.6f));
            cv.drawLine(xB, yTop, xB, yBase, ln);
            Paint top = textPaint(c, 10.5f, GOLD, Paint.Align.CENTER);
            cv.drawText("23:00", xB, yTop - HealthViews.dp(c, 3), top);
            ln.setStrokeWidth(HealthViews.dp(c, 2f));
            if (ne > 0) {
                float y = (float) (yBase - (se / ne - y0) / (y1 - y0) * (yBase - yTop));
                ln.setColor(HealthViews.alpha(TEAL, 0.85f));
                cv.drawLine(x0, y, xB, y, ln);
            }
            if (nl > 0) {
                float y = (float) (yBase - (sl / nl - y0) / (y1 - y0) * (yBase - yTop));
                ln.setColor(HealthViews.alpha(GOLD, 0.85f));
                cv.drawLine(xB, y, x1, y, ln);
            }
            ln.setPathEffect(null);
            Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < bed.length; i++) {
                float x = x0 + (bed[i] - t0) / (float) (t1 - t0) * (x1 - x0), y = (float) (yBase - (hrs[i] - y0) / (y1 - y0) * (yBase - yTop));
                int col = early[i] ? TEAL : GOLD;
                d.setColor(HealthViews.alpha(col, 0.18f));
                cv.drawCircle(x, y, HealthViews.dp(c, 8.5f), d);
                d.setColor(col);
                cv.drawCircle(x, y, HealthViews.dp(c, 5f), d);
            }
        }
    }

    /** Hours asleep for each of the last nights as pills, the hours written above and the weekday of the morning you woke below; dashed line at 7 h. */
    static final class BarsView extends View {
        private final double[] hrs; private final int[] dow;
        BarsView(Context c, double[] hrs, int[] dow) { super(c); this.hrs = hrs; this.dow = dow; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x0 = HealthViews.dp(c, 8), x1 = w - HealthViews.dp(c, 8), yTop = HealthViews.dp(c, 18), yBase = h - HealthViews.dp(c, 24);
            int n = hrs.length;
            double mx = 9.0;
            for (double v : hrs) mx = Math.max(mx, Math.ceil(v) + 0.5);
            float slot = (x1 - x0) / n, bw = Math.min(HealthViews.dp(c, 15), slot * 0.62f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint hl = textPaint(c, 9.5f, 0xFFB9C3D1, Paint.Align.CENTER), dl = textPaint(c, 10.5f, 0xFF7B8696, Paint.Align.CENTER);
            float yS = (float) (yBase - 7.0 / mx * (yBase - yTop));
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStyle(Paint.Style.STROKE);
            ln.setStrokeWidth(HealthViews.dp(c, 1.4f));
            ln.setColor(HealthViews.alpha(TEAL, 0.55f));
            ln.setPathEffect(new DashPathEffect(new float[] { HealthViews.dp(c, 5), HealthViews.dp(c, 4) }, 0f));
            cv.drawLine(x0, yS, x1, yS, ln);
            String letters = "MTWTFSS";
            for (int i = 0; i < n; i++) {
                float cx = x0 + slot * (i + 0.5f), top = (float) (yBase - hrs[i] / mx * (yBase - yTop));
                boolean ok = hrs[i] >= WellbeingModel.GOAL_H;
                int col = ok ? TEAL : GOLD;
                p.setColor(HealthViews.alpha(col, 0.14f));
                cv.drawRoundRect(new RectF(cx - bw / 2 - HealthViews.dp(c, 3), top - HealthViews.dp(c, 3), cx + bw / 2 + HealthViews.dp(c, 3), yBase + HealthViews.dp(c, 3)), bw, bw, p);
                p.setColor(HealthViews.alpha(col, ok ? 1f : 0.62f));
                cv.drawRoundRect(new RectF(cx - bw / 2, top, cx + bw / 2, yBase), bw / 2, bw / 2, p);
                cv.drawText(String.format(Locale.UK, "%.1f", hrs[i]), cx, top - HealthViews.dp(c, 6), hl);
                cv.drawText(String.valueOf(letters.charAt(Math.max(0, Math.min(6, dow[i])))), cx, yBase + HealthViews.dp(c, 18), dl);
            }
        }
    }

    /** Evening pulse spread: one bar per 5 bpm step, the count above, the median as a gold line. */
    static final class HistView extends View {
        private final int[] lo, cnt; private final double med;
        HistView(Context c, int[] lo, int[] cnt, double med) { super(c); this.lo = lo; this.cnt = cnt; this.med = med; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x0 = HealthViews.dp(c, 8), x1 = w - HealthViews.dp(c, 8), yTop = HealthViews.dp(c, 26), yBase = h - HealthViews.dp(c, 24);
            int n = lo.length, mx = 1;
            for (int v : cnt) mx = Math.max(mx, v);
            float slot = (x1 - x0) / n, bw = Math.min(HealthViews.dp(c, 30), slot * 0.7f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint cl = textPaint(c, 10, 0xFFB9C3D1, Paint.Align.CENTER), xl = textPaint(c, 10, 0xFF7B8696, Paint.Align.CENTER);
            for (int i = 0; i < n; i++) {
                float cx = x0 + slot * (i + 0.5f), top = yBase - cnt[i] / (float) mx * (yBase - yTop);
                if (cnt[i] > 0) {
                    p.setColor(HealthViews.alpha(BLUE, 0.16f));
                    cv.drawRoundRect(new RectF(cx - bw / 2 - HealthViews.dp(c, 3), top - HealthViews.dp(c, 3), cx + bw / 2 + HealthViews.dp(c, 3), yBase + HealthViews.dp(c, 3)), HealthViews.dp(c, 9), HealthViews.dp(c, 9), p);
                    p.setColor(BLUE);
                    cv.drawRoundRect(new RectF(cx - bw / 2, top, cx + bw / 2, yBase), HealthViews.dp(c, 7), HealthViews.dp(c, 7), p);
                    cv.drawText(String.valueOf(cnt[i]), cx, top - HealthViews.dp(c, 6), cl);
                }
                cv.drawText(lo[i] + "-" + (lo[i] + 4), cx, yBase + HealthViews.dp(c, 18), xl);
            }
            float mxp = x0 + (float) ((med - lo[0]) / 5.0) * slot;
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStrokeWidth(HealthViews.dp(c, 2f));
            ln.setColor(GOLD);
            cv.drawLine(mxp, HealthViews.dp(c, 16), mxp, yBase, ln);
            Paint t = textPaint(c, 10.5f, GOLD, Paint.Align.CENTER);
            cv.drawText("median " + WellbeingExtra.num(med), Math.max(HealthViews.dp(c, 40), Math.min(w - HealthViews.dp(c, 40), mxp)), HealthViews.dp(c, 11), t);
        }
    }

    /** The calmest evening: each reading as a dot at its time (18:00 to midnight) joined by a line; the dashed line is the median of all evenings. */
    static final class CalmLineView extends View {
        private final int[] at, hr; private final double base;
        CalmLineView(Context c, int[] at, int[] hr, double base) { super(c); this.at = at; this.hr = hr; this.base = base; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), x0 = HealthViews.dp(c, 30), x1 = w - HealthViews.dp(c, 14), yTop = HealthViews.dp(c, 22), yBase = h - HealthViews.dp(c, 26);
            double lo = base, hi = base;
            for (int v : hr) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            lo = Math.floor((lo - 4) / 5.0) * 5; hi = Math.ceil((hi + 4) / 5.0) * 5;
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
            ln.setStrokeWidth(Math.max(1f, HealthViews.dp(c, 1)));
            Paint yl = textPaint(c, 10.5f, 0xFF7B8696, Paint.Align.RIGHT), xl = textPaint(c, 10.5f, 0xFF7B8696, Paint.Align.CENTER);
            for (double g = lo; g <= hi + 0.01; g += 5) {
                float y = (float) (yBase - (g - lo) / (hi - lo) * (yBase - yTop));
                ln.setColor(0x14FFFFFF);
                cv.drawLine(x0, y, x1, y, ln);
                cv.drawText(String.valueOf((int) g), x0 - HealthViews.dp(c, 6), y + HealthViews.dp(c, 3.5f), yl);
            }
            int[] ticks = { 1080, 1200, 1320, 1440 };
            for (int t : ticks) {
                float x = x0 + (t - 1080) / 360f * (x1 - x0);
                ln.setColor(0x1FFFFFFF);
                cv.drawLine(x, yBase, x, yBase + HealthViews.dp(c, 4), ln);
                cv.drawText(WellbeingModel.clock(t), x, yBase + HealthViews.dp(c, 20), xl);
            }
            float yb = (float) (yBase - (base - lo) / (hi - lo) * (yBase - yTop));
            ln.setStyle(Paint.Style.STROKE);
            ln.setPathEffect(new DashPathEffect(new float[] { HealthViews.dp(c, 5), HealthViews.dp(c, 4) }, 0f));
            ln.setColor(HealthViews.alpha(GOLD, 0.7f));
            ln.setStrokeWidth(HealthViews.dp(c, 1.4f));
            cv.drawLine(x0, yb, x1, yb, ln);
            cv.drawText("all evenings " + WellbeingExtra.num(base), x1, yb - HealthViews.dp(c, 5), textPaint(c, 10.5f, GOLD, Paint.Align.RIGHT));
            ln.setPathEffect(null);
            ln.setColor(HealthViews.alpha(TEAL, 0.75f));
            ln.setStrokeWidth(HealthViews.dp(c, 2f));
            float px0 = 0, py0 = 0;
            Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint vl = textPaint(c, 10, 0xFFB9C3D1, Paint.Align.CENTER);
            for (int i = 0; i < at.length; i++) {
                float x = x0 + (Math.max(1080, Math.min(1440, at[i])) - 1080) / 360f * (x1 - x0), y = (float) (yBase - (hr[i] - lo) / (hi - lo) * (yBase - yTop));
                if (i > 0) cv.drawLine(px0, py0, x, y, ln);
                px0 = x; py0 = y;
            }
            for (int i = 0; i < at.length; i++) {
                float x = x0 + (Math.max(1080, Math.min(1440, at[i])) - 1080) / 360f * (x1 - x0), y = (float) (yBase - (hr[i] - lo) / (hi - lo) * (yBase - yTop));
                d.setColor(HealthViews.alpha(TEAL, 0.18f));
                cv.drawCircle(x, y, HealthViews.dp(c, 8.5f), d);
                d.setColor(TEAL);
                cv.drawCircle(x, y, HealthViews.dp(c, 5f), d);
                cv.drawText(String.valueOf(hr[i]), x, y - HealthViews.dp(c, 11), vl);
            }
        }
    }
}
