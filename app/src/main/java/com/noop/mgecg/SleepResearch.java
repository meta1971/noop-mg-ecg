package com.noop.mgecg;

// FILE VERSION 0.54.0 (13 Nov 2026): NEW. The sleep research screen: sleep regularity (SRI against the UK Biobank anchors), the timing of your nights, the 24 h rhythm of your heart rate, what the age studies say next to your
// own averages, why no single "sleep age" number is shown, and the twelve sources with what each one was used for and what limits it.
// Everything here is computed on the phone from the strap's own per-second records (RhythmData). Estimates, research only, not a medical device. One person, one strap: nothing here is a norm for anyone else.

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class SleepResearch {
    private SleepResearch() {}

    // ------------------------------------------------------------------ the sources (name, what it is, what was taken, what limits it, url)
    static final String[][] SOURCES = {
        {"DREAMT v2.2.0 (PhysioNet)", "100 patients, mean age 56.2. Empatica wrist BVP 64 Hz, 1 Hz heart rate, beat intervals, accelerometer, skin temperature, with sleep-lab stages every 30 s.",
            "The only set with beat-to-beat intervals and lab stages at your age, so the one that could test this strap's optical beat intervals and staging. Not yet used for that.",
            "Restricted: registered user and a signed data-use agreement. Patients with suspected sleep disorders, not healthy sleepers.", "https://physionet.org/content/dreamt/"},
        {"sleep-accel (Walch, PhysioNet)", "31 subjects, Apple Watch motion and heart rate with lab stages.",
            "Showed that motion plus heart rate alone can stage sleep at a useful level, which is what this strap's staging rests on.", "Small and young; a different watch.", "https://www.physionet.org/content/sleep-accel/"},
        {"BIDSleep (PhysioNet)", "47 healthy adults, 253 nights, Apple Watch heart rate about every 5 s plus accelerometer, Dreem headband stages.",
            "Night-to-night variation of one healthy person is large; it is why every figure here is read against your own nights.", "Heart rate every 5 s only; a headband, not a lab, gives the stages.", "https://www.physionet.org/content/bidsleep-dataset/1.0.1/"},
        {"MMASH (PhysioNet)", "22 young men, Polar beat intervals and actigraphy over 24 h, melatonin and cortisol.",
            "A reference for what a clean chest-strap beat-interval night looks like next to the day before it.", "No stage labels; young men only.", "https://physionet.org/content/mmash/"},
        {"SHHS on PhysioNet", "Sleep Heart Health Study: only one record is listed on PhysioNet (ECG at 125 or 250 Hz, age, sex and BMI in the header).",
            "Pointer to the large study: 9,736 nights are available from the National Sleep Research Resource on request.", "Not used in the app. Access to the full set needs an NSRR request.", "https://physionet.org/content/shhpsgdb/"},
        {"TILES-2018 sleep benchmark (arXiv 2507.03520)", "139 hospital staff, over 6,000 recordings of wrist accelerometer and heart rate.",
            "Shift workers' nights: the closest published set to an irregular working life like yours.", "Licence not confirmed; the benchmark paper was read, the data was not.", "https://arxiv.org/abs/2507.03520v1"},
        {"Sleep brain-age (npj Digit Med 2022)", "13,332 lab sleep studies; a model of brain age from the full set of EEG, eye, muscle, heart and breathing signals.",
            "Error of 5.8 years with the full lab signals, but about 14.6 to 14.9 years from plain sleep-summary numbers: nearly no age information. Each 10 years of age gap went with a hazard ratio of 1.29.",
            "Needs EEG. The reason no sleep-age number is shown.", "https://www.nature.com/articles/s41746-022-00630-9"},
        {"CosinorAge, abstract (npj Digit Med 2024, Crossref)", "About 80,000 UK and US adults. A biological age from the 24 h rhythm of wrist activity; mortality 8 to 12 % higher per year of CosinorAge.",
            "The idea behind the rhythm card: the shape of the day (level, swing, when it peaks) carries information.", "I could not retrieve its C-index. Built on other wrist devices.", "https://api.crossref.org/works/10.1038%2FS41746-024-01111-X"},
        {"CosinorAge package (arXiv 2509.01089)", "The open software: a one-wave cosine of minute-by-minute movement (ENMO), with pretrained coefficients.",
            "The cosine fit used here follows its convention (period 1,440 minutes, peak time from the phase).", "It needs a movement value for every minute. This strap gives raw movement only during ECG sessions, so the age itself is not computed.", "https://arxiv.org/html/2509.01089v1"},
        {"Sleep regularity and mortality (eLife 2023)", "UK Biobank, 88,975 adults with 7 days of wrist accelerometry. The index is the chance of being in the same sleep or wake state 24 h apart, scaled from -100 to 100.",
            "The regularity index and its anchors: 5th percentile 41, median about 60, 95th percentile 75. Hazard ratio 1.53 at 41 and 0.90 at 75, compared with the median.",
            "Their sleep and wake came from the accelerometer; here it comes from the strap's own sleep state, so the same number is not exactly the same measure.", "https://cdn.elifesciences.org/articles/88359/elife-88359-v1.pdf"},
        {"Ohayon 2004 meta-analysis (Sleep)", "65 studies, 3,577 healthy people aged 5 to 102.",
            "Per adult decade: total sleep time falls about 10 min, efficiency about 3 % (from about 40), time awake after sleep onset rises about 10 min (from about 30), slow-wave sleep falls about 2 %.",
            "These are slopes. I did not get absolute tables for age 53.", "https://www.med.upenn.edu/cbti/assets/user-content/documents/carskadonmeta-analysisresleepnorms.pdf"},
        {"Pini 2022 (Frontiers in Neuroscience)", "Sleep staging from beat intervals alone.",
            "Four-level accuracy of 0.73, falling by about 7.5 % over age 60: the ceiling to expect from staging a night by beat intervals.", "Chest-strap-quality beat intervals; this strap's are optical.", "https://www.frontiersin.org/articles/10.3389/fnins.2022.974192/pdf"},
    };

    // ------------------------------------------------------------------ screen
    static void show(final Activity a) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tt = new LinearLayout(a); tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(HeartGraph.label(a, "Sleep research", 26, INK, 300));
        tt.addView(HeartGraph.label(a, "Regularity, body clock and the studies behind them", 12.5f, MUT, 400));
        top.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a); sv.setVerticalScrollBarEnabled(false); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        final TextView status = HeartGraph.label(a, "Reading the last 14 days from the day log…", 13, MUT, 400);
        col.addView(status, HeartGraph.lp(a, -1, -2, 14));
        dlg.setContentView(root); dlg.show();
        new Thread(new Runnable() { @Override public void run() {
            final RhythmData.Bundle b = RhythmData.bundle(a.getApplicationContext());
            a.runOnUiThread(new Runnable() { @Override public void run() {
                try { col.removeView(status); fill(a, col, b); } catch (Throwable t) { status.setText("Could not draw this screen (" + t.getClass().getSimpleName() + ")."); }
            } });
        } }, "sleep-research").start();
    }

    private static LinearLayout card(Context c, String title) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(glass(c, 20));
        int p = Math.round(dp(c, 16)); l.setPadding(p, Math.round(dp(c, 14)), p, Math.round(dp(c, 16)));
        TextView t = HeartGraph.label(c, title, 11, MUT, 400); t.setLetterSpacing(0.16f); l.addView(t);
        return l;
    }

    private static TextView text(Context c, String s, float sp, int color) { TextView t = HeartGraph.label(c, s, sp, color, 400); t.setLineSpacing(0, 1.25f); return t; }

    private static void kv(Context c, LinearLayout l, String k, String v) {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(HeartGraph.label(c, k, 13, INK, 400), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(HeartGraph.label(c, v, 13, INK, 600));
        l.addView(r, HeartGraph.lp(c, -1, -2, 8));
    }

    static String clock(double min) { if (Double.isNaN(min)) return "-"; int t = ((int) Math.round(min) % 1440 + 1440) % 1440; return String.format(Locale.UK, "%02d:%02d", t / 60, t % 60); }
    static String mins(double m) { return Double.isNaN(m) ? "-" : Math.round(m) + " min"; }

    private static void fill(final Activity a, LinearLayout col, RhythmData.Bundle b) {
        if (b == null || b.s == null || !b.error.isEmpty() || b.sri == null) {
            col.addView(text(a, "The day log could not be read" + (b != null && !b.error.isEmpty() ? " (" + b.error + ")" : "") + ".", 14, MUT), HeartGraph.lp(a, -1, -2, 14));
            return;
        }
        SleepRegularity.Result r = b.sri;
        // ---- regularity
        LinearLayout rc = card(a, "SLEEP REGULARITY");
        if (Double.isNaN(r.sri)) {
            rc.addView(text(a, "Needs at least 24 hours of minutes that were logged on two days a day apart. " + r.validMinutes + " minutes are logged in the last 14 days.", 13, MUT), HeartGraph.lp(a, -1, -2, 8));
        } else {
            LinearLayout big = new LinearLayout(a); big.setOrientation(LinearLayout.HORIZONTAL);
            big.addView(HeartGraph.label(a, String.format(Locale.UK, "%.0f", r.sri), 40, INK, 200));
            double pc = SleepRegularity.approxPercentile(r.sri);
            big.addView(HeartGraph.label(a, "  of 100  ·  " + SleepRegularity.describe(r.sri), 13, MUT, 400), HeartGraph.lp(a, -2, -2, 22));
            rc.addView(big, HeartGraph.lp(a, -1, -2, 6));
            rc.addView(new SriBar(a, r.sri), HeartGraph.lp(a, -1, Math.round(dp(a, 46)), 8));
            rc.addView(text(a, "Grey marks: 41, 60 and 75, the 5th percentile, the median and the 95th percentile of 88,975 UK adults measured over 7 days. In that study 41 went with a mortality hazard ratio of 1.53 and 75 with 0.90, against the median. That is a group result, not a prediction for you.", 11.5f, MUT), HeartGraph.lp(a, -1, -2, 6));
            kv(a, rc, "Compared over", r.pairs + " minute pairs, " + String.format(Locale.UK, "%.1f", r.pairs / 1440.0) + " days");
        }
        if (!r.nights.isEmpty()) {
            kv(a, rc, "Nights found", String.valueOf(r.nights.size()));
            kv(a, rc, "Average sleep", String.format(Locale.UK, "%.1f h", r.meanSleepH));
            kv(a, rc, "Average bed to wake", clock(r.bedMean) + " to " + clock(r.wakeMean));
            kv(a, rc, "Bedtime spread (SD)", mins(r.bedSd));
            kv(a, rc, "Wake-up spread (SD)", mins(r.wakeSd));
            kv(a, rc, "Mid-sleep spread (SD)", Double.isNaN(r.midSd) ? "needs 4 nights" : mins(r.midSd));
        }
        rc.addView(text(a, "Asleep here is the strap's own sleep state in at least half of a minute. The UK Biobank index used an accelerometer to decide sleep, so this number is the same idea on a different input. Naps count, because the index counts every minute.", 11, MUT), HeartGraph.lp(a, -1, -2, 10));
        col.addView(rc, HeartGraph.lp(a, -1, -2, 14));

        // ---- nights list
        if (!r.nights.isEmpty()) {
            LinearLayout nc = card(a, "YOUR LAST NIGHTS");
            SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
            int from = Math.max(0, r.nights.size() - 10);
            for (int i = r.nights.size() - 1; i >= from; i--) {
                SleepRegularity.Night n = r.nights.get(i);
                long dayMid = b.s.baseLocalSec + n.day * 86400L;
                String d = f.format(new Date((dayMid - java.util.TimeZone.getDefault().getOffset(dayMid * 1000L) / 1000L + 12 * 3600L) * 1000L));
                kv(a, nc, d, clock(n.bedMin) + " to " + clock(n.wakeMin) + "  ·  " + String.format(Locale.UK, "%.1f h", n.asleepMin / 60.0));
            }
            nc.addView(text(a, "The main sleep of each evening: the longest run between 18:00 and 12:00 of at least 3 hours, awake gaps of up to 30 minutes kept inside it.", 11, MUT), HeartGraph.lp(a, -1, -2, 10));
            col.addView(nc, HeartGraph.lp(a, -1, -2, 12));
        }

        // ---- body clock
        LinearLayout bc = card(a, "YOUR HEART RATE OVER 24 HOURS");
        RhythmFit.Result h = b.hrFit;
        if (h == null || !h.ok) {
            bc.addView(text(a, "Needs about 6 hours of minutes with a heart rate over the last 7 days.", 13, MUT), HeartGraph.lp(a, -1, -2, 8));
        } else {
            bc.addView(new HrClock(a, b.s, h), HeartGraph.lp(a, -1, Math.round(dp(a, 130)), 10));
            bc.addView(text(a, "Average heart rate by hour of the day over the last " + RhythmData.HR_DAYS + " days (bars, from 04:00) and the 24 h wave fitted to the minutes (line).", 11, MUT), HeartGraph.lp(a, -1, -2, 4));
            kv(a, bc, "Peak of the wave", clock(h.peakH * 60.0));
            kv(a, bc, "Lowest point of the wave", clock(((h.peakH + 12.0) % 24.0) * 60.0));
            kv(a, bc, "Swing (amplitude)", String.format(Locale.UK, "%.1f bpm", h.amp));
            kv(a, bc, "Level (mesor)", String.format(Locale.UK, "%.1f bpm", h.mesor));
            kv(a, bc, "How well one wave fits", String.format(Locale.UK, "R² %.2f", h.r2));
            kv(a, bc, "Days with at least 12 h logged", b.hrDaysUsed + " of " + RhythmData.HR_DAYS);
            if (!r.nights.isEmpty()) {
                double sc = 0, ss = 0; int n = 0;
                for (SleepRegularity.Night x : r.nights) { double mid = ((x.bedMin + x.wakeMin) / 2.0) % 1440.0; double ang = mid / 1440.0 * 2 * Math.PI; sc += Math.cos(ang); ss += Math.sin(ang); n++; }
                double midMin = (Math.atan2(ss / n, sc / n) / (2 * Math.PI) * 1440.0 + 1440.0) % 1440.0;
                kv(a, bc, "Usual mid-sleep", clock(midMin));
            }
        }
        bc.addView(text(a, "Awake activity and sleep both shape this curve, so it is a summary of the whole day, not a clock gene. The same cosine method as CosinorAge, applied to heart rate.", 11, MUT), HeartGraph.lp(a, -1, -2, 10));
        col.addView(bc, HeartGraph.lp(a, -1, -2, 12));

        // ---- the age studies next to your averages
        LinearLayout ac = card(a, "THE AGE STUDIES NEXT TO YOUR NIGHTS");
        ac.addView(text(a, "Ohayon 2004 (65 studies, 3,577 healthy people): for each adult decade, total sleep time falls about 10 minutes, sleep efficiency about 3 % (from about 40), time awake after sleep onset rises about 10 minutes (from about 30), slow-wave sleep falls about 2 %.", 12.5f, INK), HeartGraph.lp(a, -1, -2, 8));
        if (!r.nights.isEmpty()) {
            double asleep = 0, tib = 0; int n = 0;
            for (SleepRegularity.Night x : r.nights) { asleep += x.asleepMin; tib += (x.wakeMin - x.bedMin); n++; }
            kv(a, ac, "Your average asleep", String.format(Locale.UK, "%.1f h over %d nights", asleep / n / 60.0, n));
            kv(a, ac, "Your average in bed (bed to wake)", String.format(Locale.UK, "%.1f h", tib / n / 60.0));
            kv(a, ac, "Asleep as a share of bed to wake", String.format(Locale.UK, "%.0f %%", 100.0 * asleep / Math.max(1.0, tib)));
        }
        ac.addView(text(a, "Those are slopes, not a table for your age, and this strap's asleep share is not a lab efficiency. They show the direction the studies expect, not a score. Stage shares are on the Rest screen, with the same warning.", 11, MUT), HeartGraph.lp(a, -1, -2, 8));
        col.addView(ac, HeartGraph.lp(a, -1, -2, 12));

        // ---- why no sleep-age number
        LinearLayout wn = card(a, "WHY THERE IS NO SLEEP-AGE NUMBER");
        String[] why = {
            "A sleep brain-age made from the full lab signals is wrong by 5.8 years on average. From plain sleep-summary numbers the error is about 14.6 to 14.9 years, which is nearly no age information. A strap gives only summary numbers.",
            "Each 10 years of gap between that brain age and real age went with a hazard ratio of 1.29. It needs EEG; a wrist band has none.",
            "CosinorAge needs a movement value for every minute (ENMO). This strap gives raw movement only during ECG sessions. Its per-second movement figure is the largest step between two acceleration samples, not ENMO, so the age is not computed rather than guessed.",
            "Staging a night from beat intervals alone reaches an accuracy of 0.73 over four levels, lower after 60. This strap's beat intervals are optical, and its REM share swings from night to night, so no model of age can be fitted on one person's nights.",
            "What is honest: your regularity, your timing, your heart-rate rhythm, each against your own earlier nights and against the published anchors."};
        for (String s : why) wn.addView(text(a, "•  " + s, 12.5f, INK), HeartGraph.lp(a, -1, -2, 8));
        col.addView(wn, HeartGraph.lp(a, -1, -2, 12));

        // ---- sources
        LinearLayout sc = card(a, "SOURCES");
        sc.addView(text(a, "Twelve sources. For each: what it is, what was taken from it, what limits it. Tap one to open it.", 12, MUT), HeartGraph.lp(a, -1, -2, 6));
        for (final String[] s : SOURCES) {
            LinearLayout e = new LinearLayout(a); e.setOrientation(LinearLayout.VERTICAL);
            e.setPadding(0, Math.round(dp(a, 6)), 0, Math.round(dp(a, 4)));
            e.addView(HeartGraph.label(a, s[0], 14, BLUE, 600));
            e.addView(text(a, s[1], 12, INK), HeartGraph.lp(a, -1, -2, 3));
            e.addView(text(a, "Taken: " + s[2], 12, MUT), HeartGraph.lp(a, -1, -2, 3));
            e.addView(text(a, "Limit: " + s[3], 12, MUT), HeartGraph.lp(a, -1, -2, 3));
            e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s[4]))); } catch (Throwable ignored) { }
            } });
            sc.addView(e, HeartGraph.lp(a, -1, -2, 12));
        }
        col.addView(sc, HeartGraph.lp(a, -1, -2, 12));
        col.addView(text(a, "Estimates from this strap's own records. Research only; not a medical device and not a diagnosis.", 11, MUT), HeartGraph.lp(a, -1, -2, 16));
    }

    // ------------------------------------------------------------------ views
    /** 0 to 100 scale with the three study anchors and your value. */
    static final class SriBar extends View {
        private final double v;
        SriBar(Context c, double v) { super(c); this.v = v; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), y = dp(c, 14), lo = -20f, hi = 100f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x1AFFFFFF); cv.drawRoundRect(new RectF(0, y - dp(c, 3), w, y + dp(c, 3)), dp(c, 3), dp(c, 3), p);
            p.setColor(0x3346D8A0); cv.drawRoundRect(new RectF(x(41, w, lo, hi), y - dp(c, 3), x(75, w, lo, hi), y + dp(c, 3)), dp(c, 3), dp(c, 3), p);
            Paint t = paint(MUT); t.setTextSize(dp(c, 10)); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 400));
            double[] an = {41, 60, 75};
            for (double a : an) {
                float x = x(a, w, lo, hi);
                p.setColor(0x66FFFFFF); cv.drawRect(x - dp(c, 0.5f), y - dp(c, 8), x + dp(c, 0.5f), y + dp(c, 8), p);
                cv.drawText(String.valueOf((int) a), x, y + dp(c, 24), t);
            }
            float x = x(Math.max(lo, Math.min(hi, v)), w, lo, hi);
            p.setColor(alpha(BLUE, 0.30f)); cv.drawCircle(x, y, dp(c, 9), p);
            p.setColor(BLUE); cv.drawCircle(x, y, dp(c, 5), p);
        }
        private static float x(double val, float w, float lo, float hi) { return (float) (w * (val - lo) / (hi - lo)); }
    }

    /** Mean heart rate by hour since 04:00 as bars, with the fitted wave drawn over them. */
    static final class HrClock extends View {
        private final double[] mean = new double[24]; private final RhythmFit.Result fit;
        HrClock(Context c, RhythmData.Series s, RhythmFit.Result fit) {
            super(c); this.fit = fit;
            double[] sum = new double[24]; int[] n = new int[24];
            int from = Math.max(0, (s.days - RhythmData.HR_DAYS) * 1440);
            for (int i = from; i < s.days * 1440; i++) { double v = s.hr[i]; if (Double.isNaN(v)) continue; int hClock = (i % 1440) / 60; sum[hClock] += v; n[hClock]++; }
            for (int h = 0; h < 24; h++) mean[h] = n[h] >= 30 ? sum[h] / n[h] : Double.NaN;
        }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            double lo = 1e9, hi = -1e9;
            for (double v : mean) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (lo > hi) return;
            lo = Math.floor(lo - 4); hi = Math.ceil(hi + 4);
            float w = getWidth(), h = getHeight(), labelH = dp(c, 14), plotH = h - labelH, gap = dp(c, 2);
            float bw = (w - gap * 23) / 24f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint lab = paint(MUT); lab.setTextSize(dp(c, 9.5f)); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400));
            for (int i = 0; i < 24; i++) {
                int hc = (i + 4) % 24; float x = i * (bw + gap);
                p.setColor(0x14FFFFFF); cv.drawRoundRect(new RectF(x, 0, x + bw, plotH), dp(c, 2), dp(c, 2), p);
                if (!Double.isNaN(mean[hc])) { float f = (float) ((mean[hc] - lo) / (hi - lo)); p.setColor(alpha(ROSE, 0.75f)); cv.drawRoundRect(new RectF(x, plotH - Math.max(dp(c, 2), plotH * f), x + bw, plotH), dp(c, 2), dp(c, 2), p); }
                if (i % 4 == 0) cv.drawText(String.format(Locale.UK, "%02d", hc), x + bw / 2f, h - dp(c, 2), lab);
            }
            Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(dp(c, 2)); ln.setColor(INK);
            Path path = new Path(); boolean first = true;
            double wv = 2.0 * Math.PI / 24.0;
            for (int k = 0; k <= 96; k++) {
                double t = 4.0 + k * 0.25;                                     // clock hours from 04:00 to 28:00
                double fy = fit.mesor + fit.amp * Math.cos(wv * t + fit.phi);
                float x = (float) ((k * 0.25) / 24.0 * w), y = (float) (plotH - plotH * (fy - lo) / (hi - lo));
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            cv.drawPath(path, ln);
        }
    }
}
