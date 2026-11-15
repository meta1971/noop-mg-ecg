package com.noop.mgecg;

// FILE VERSION 0.58.0 (15 Nov 2026): NEW. The wellbeing page (opened from the Morning brief card on the Today tab). Everything on it is counted from the sleep log of the last 14 days (RhythmData -> SleepRegularity nights ->
// WellbeingModel); nothing is invented, and where there is not enough data it says so. Opinion first, the numbers as the small "Your data" line, with the study behind each opinion.
// Parts: the hero ring (one segment per night, the hours written on it, a word from the sleep regularity index), the main opinion, the plan check (a plan saved here is checked against the next night), the wake-up strip,
// three rings, one small win for tonight (a button saves it as a plan: in bed by 23:00, stored on the phone only), and a one-minute paced-breathing orb (6 breaths a minute, pacing only: no pulse is measured here).
// Writes: SharedPreferences "wellbeing" (plan_bed_min, plan_from_day) only. No new permission.

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.TimeZone;

public class WellbeingActivity extends Activity {
    static final String PREFS = "wellbeing";
    private static final int BG = HealthViews.BG, INK = HealthViews.INK, MUT = HealthViews.MUT;
    private static final int BLUE = HealthViews.BLUE, VIOLET = HealthViews.VIOLET, TEAL = HealthViews.TEAL, GOLD = HealthViews.GOLD, ROSE = HealthViews.ROSE;
    private static final int EVID = 0xFF7F8A9A;

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

    private static long todayLocalEpochDay() {
        long now = System.currentTimeMillis();
        return Math.floorDiv(now / 1000L + TimeZone.getDefault().getOffset(now) / 1000L, 86400L);
    }

    private float dp;
    private LinearLayout col;
    private BreathView breath;
    private boolean loadingNow;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new GlowView(this), new FrameLayout.LayoutParams(-1, -1));
        ScrollView sv = new ScrollView(this);
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
                final WellbeingModel.Out o = load(app);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loadingNow = false;
                        if (isFinishing()) return;
                        if (o == null) showMessage("Could not read your sleep log. Open the day logger on the Today tab and pull the strap's history first.");
                        else render(o);
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
    private void render(final WellbeingModel.Out o) {
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

        // three rings
        LinearLayout trio = new LinearLayout(this);
        trio.setOrientation(LinearLayout.HORIZONTAL);
        float f1 = o.nAll > 0 ? o.ge7All / (float) o.nAll : 0f;
        trio.addView(mini(f1, TEAL, String.valueOf(o.ge7All), "7 H+ NIGHTS", o.ge7All + " of " + o.nAll + " nights"), tl());
        trio.addView(mini(o.nWork > 0 ? o.nEarly / (float) o.nWork : 0f, BLUE, o.nWork > 0 ? String.valueOf(o.nEarly) : "—", "EARLY BEDS", o.nWork > 0 ? o.nEarly + " of " + o.nWork + " work nights before 23:00" : "no work mornings yet"), tl());
        boolean hasSri = !Double.isNaN(o.sri);
        trio.addView(mini(hasSri ? (float) Math.max(0.02, Math.min(1.0, o.sri / 100.0)) : 0f, VIOLET, hasSri ? String.valueOf(Math.round(o.sri)) : "—", "REGULARITY", hasSri ? "of 100, UK median 60" : "needs 24 h of days"), tl());
        col.addView(trio, lp(12));

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
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("plan_bed_min").remove("plan_from_day").commit();
                    reload();
                }
            });
            cwin.addView(clear);
        }
        col.addView(cwin, lp(12));

        // a calm minute
        LinearLayout cb2 = card();
        cb2.addView(caps("A CALM MINUTE", TEAL));
        cb2.addView(opinion("Breathe with the orb for one minute.", 19));
        breath = new BreathView(this);
        cb2.addView(breath, new LinearLayout.LayoutParams(-1, px(300)));
        final TextView startBtn = pill("Start the minute", TEAL, 0xFF05131E, null);
        breath.bind(startBtn);
        cb2.addView(startBtn, pl(8));
        TextView why = tv("Why this: about 6 slow breaths a minute is the pace used in heart-rate-variability biofeedback studies. In 14 trials of people with depressive symptoms it gave a small-to-medium benefit (g = 0.38), with wide uncertainty. Here it is the pacing only: the app does not measure your pulse during this minute.", 12, EVID, 400);
        why.setPadding(0, px(12), 0, 0);
        cb2.addView(why);
        col.addView(cb2, lp(12));

        addClose();
    }

    private void addClose() {
        TextView close = tv("Close", 14, MUT, 600);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, px(22), 0, px(8));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        col.addView(close, new LinearLayout.LayoutParams(-1, px(60)));
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
        String[] keys = {"Your data:", "Why it matters:", "Why this wording:"};
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
        private TextView btn;
        BreathView(Context c) { super(c); }
        void bind(TextView b) {
            btn = b;
            btn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (running) stop(false); else start(); } });
        }
        void start() {
            running = true; done = false; t0 = SystemClock.elapsedRealtime();
            try { ((Activity) getContext()).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
            if (btn != null) btn.setText("Stop");
            postInvalidateOnAnimation();
        }
        void stop(boolean finished) {
            running = false; done = finished;
            try { ((Activity) getContext()).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
            if (btn != null) btn.setText(finished ? "Do it again" : "Start the minute");
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
}
