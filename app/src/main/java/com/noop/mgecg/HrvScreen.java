package com.noop.mgecg;

// FILE VERSION 0.29.0 (31 Oct 2026): "HRV (research)": what the app measures on you, how it compares with published values, what moves HRV, the evening and alcohol question with a log, the guided tests,
// and the sources. Every number in the fixed texts is either measured on this strap (dated) or from the cited paper. Research only, not a diagnosis.

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.noop.mgecg.HealthViews.*;

final class HrvScreen {
    private HrvScreen() {}

    private static double num(String s) { if (s == null) return Double.NaN; Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s); return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN; }

    private static double med(List<Double> v) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); int n = b.size(); return n % 2 == 1 ? b.get(n / 2) : 0.5 * (b.get(n / 2 - 1) + b.get(n / 2)); }

    private static double pct(List<Double> v, double p) { if (v.isEmpty()) return Double.NaN; List<Double> b = new ArrayList<Double>(v); Collections.sort(b); return b.get((int) Math.min(b.size() - 1, Math.round(p * (b.size() - 1)))); }

    /** Age-adjusted lowest normal values for the deep-breathing test at 6 cycles a minute, sitting (J Intern Med 1999;245:287): E/I difference in bpm and E/I ratio. */
    static double eiLimit(double age) { double x = 4.39 - 0.033 * age; return x * x; }
    static double ratioLimit(double age) { return 1.0 + Math.exp(-1.12 - 0.0198 * age); }

    /** 24 bars, one per local hour: height = median RMSSD of that hour (last 48 h, strap optical). */
    static final class HourBars extends View {
        private double[][] v = new double[24][2]; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        HourBars(Context c) { super(c); }
        void set(double[][] d) { v = d; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), lab = dp(getContext(), 14), bw = w / 24f; double mx = 20; for (double[] x : v) if (!Double.isNaN(x[0]) && x[1] >= 2) mx = Math.max(mx, x[0]);
            p.setTextSize(dp(getContext(), 9)); p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 24; i++) {
                if (!Double.isNaN(v[i][0]) && v[i][1] >= 2) { float bh = (float) ((h - lab) * Math.min(1.0, v[i][0] / mx)); p.setColor(i >= 18 || i < 6 ? alpha(BLUE, 0.85f) : alpha(TEAL, 0.85f)); cv.drawRoundRect(new RectF(i * bw + bw * 0.15f, h - lab - bh, i * bw + bw * 0.85f, h - lab), 3, 3, p); }
                if (i % 3 == 0) { p.setColor(0x99FFFFFF); cv.drawText(String.valueOf(i), i * bw + bw / 2f, h - 2, p); }
            }
        }
    }

    private static LinearLayout card(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(glass(c, 18)); int p = Math.round(dp(c, 16)); l.setPadding(p, p, p, p); return l; }
    private static TextView head(Context a, String t) { return HeartGraph.label(a, t, 11, MUT, 400); }
    private static TextView body(Context a, String t) { TextView x = HeartGraph.label(a, t, 13, INK, 400); x.setLineSpacing(0, 1.25f); return x; }
    private static TextView small(Context a, String t) { TextView x = HeartGraph.label(a, t, 12, MUT, 400); x.setLineSpacing(0, 1.25f); return x; }
    private static EditText field(Context c, String hint, String val) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setTextColor(INK); e.setHintTextColor(MUT); e.setTextSize(17);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setBackground(glass(c, 12)); int pd = Math.round(dp(c, 10)); e.setPadding(pd, pd, pd, pd); return e;
    }

    static void show(final Activity a, final HealthModel.Data d) {
        final SharedPreferences hs = a.getSharedPreferences("healthspan", Context.MODE_PRIVATE), hp = a.getSharedPreferences("hrv", Context.MODE_PRIVATE);
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "HRV", 22, INK, 300)); top.addView(HeartGraph.label(a, "  research", 12, MUT, 400)); top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close); root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        dlg.setContentView(root);
        final Runnable[] build = new Runnable[1];
        build[0] = new Runnable() { @Override public void run() { col.removeAllViews(); fill(a, d, col, hs, hp, build[0]); } };
        build[0].run(); dlg.show();
    }

    private static void fill(final Activity a, HealthModel.Data d, LinearLayout col, final SharedPreferences hs, final SharedPreferences hp, final Runnable rebuild) {
        final double age = hs.getFloat("age", 53f);
        // ---- seated ECG readings (grade A or B), local time
        List<Double> all = new ArrayList<Double>(), early = new ArrayList<Double>(), late = new ArrayList<Double>(), hrs = new ArrayList<Double>(); Calendar cal = Calendar.getInstance();
        for (HealthModel.Reading r : d.readings) {
            double v = num(r.hrv), h = num(r.hr); if (Double.isNaN(v) || v <= 0 || v > 120 || !(r.grade.startsWith("A") || r.grade.startsWith("B"))) continue;
            all.add(v); if (!Double.isNaN(h)) hrs.add(h); cal.setTimeInMillis(r.timeMs); int hr = cal.get(Calendar.HOUR_OF_DAY); if (hr < 18) early.add(v); else if (hr >= 20) late.add(v);
        }
        List<Double> nr = new ArrayList<Double>(), nl = new ArrayList<Double>(), nb = new ArrayList<Double>(); int ni = 0;
        for (TodayModel.NightStat n : d.nights) { if (ni++ >= 14) break; if (!Double.isNaN(n.rmssd)) nr.add(n.rmssd); if (!Double.isNaN(n.hrLow5)) nl.add(n.hrLow5); if (!Double.isNaN(n.br)) nb.add(n.br); }

        LinearLayout c1 = card(a); c1.addView(head(a, "YOUR NUMBERS"));
        String s1 = all.isEmpty() ? "No graded ECG readings yet. Take one (Table tag, Auto-stop) to get a seated RMSSD."
            : String.format(Locale.UK, "Seated ECG readings (grade A or B, %d): RMSSD median %.0f ms, most between %.0f and %.0f, heart rate about %.0f.", all.size(), med(all), pct(all, 0.1), pct(all, 0.9), med(hrs));
        c1.addView(body(a, s1), HeartGraph.lp(a, -1, -2, 8));
        if (early.size() >= 2 && late.size() >= 2) c1.addView(body(a, String.format(Locale.UK, "Evening against day: before 18:00 the median is %.0f ms (%d readings); from 20:00 it is %.0f ms (%d readings).", med(early), early.size(), med(late), late.size())), HeartGraph.lp(a, -1, -2, 8));
        if (!nr.isEmpty()) c1.addView(body(a, String.format(Locale.UK, "Nights (strap, last %d): RMSSD median %.0f ms, lowest 5-minute heart rate %.0f, breathing %.1f a minute. The strap's pulse intervals read about 1.5 to 2 times higher than an ECG in matched daytime windows (18 windows, 26 to 29 Oct), so treat night values as an upper estimate.", nr.size(), med(nr), med(nl), med(nb))), HeartGraph.lp(a, -1, -2, 8));
        col.addView(c1, HeartGraph.lp(a, -1, -2, 14));

        // ---- your day, hour by hour (computed from the day logger, off the main thread)
        final LinearLayout dayCard = card(a); dayCard.addView(head(a, "YOUR DAY, HOUR BY HOUR (median of the last 10 days, local time)"));
        final TextView cdStatus = body(a, "Reading the last 10 days..."); dayCard.addView(cdStatus, HeartGraph.lp(a, -1, -2, 8)); col.addView(dayCard, HeartGraph.lp(a, -1, -2, 12));
        final List<TodayModel.NightStat> nightsCopy = new ArrayList<TodayModel.NightStat>(d.nights);
        new Thread(new Runnable() { @Override public void run() {
            DayTimeline.Result r0 = null; try { r0 = DayTimeline.load(a, nightsCopy, 10); } catch (Throwable ignored) { }
            final DayTimeline.Result r = r0;
            a.runOnUiThread(new Runnable() { @Override public void run() { try { fillDay(a, dayCard, cdStatus, r); } catch (Throwable ignored) { } } });
        } }, "day-timeline").start();

        // ---- by hour, last 48 h
        LinearLayout c2 = card(a); c2.addView(head(a, "BY CLOCK HOUR, LAST 48 HOURS (strap, still windows only, relative)"));
        HourBars hb = new HourBars(a); double[][] hv = IrregularWatch.hourly(a); hb.set(hv); c2.addView(hb, HeartGraph.lp(a, -1, Math.round(dp(a, 96)), 8));
        c2.addView(small(a, "Each bar is the median RMSSD of that hour; evening and night in blue. Hours with fewer than 2 windows are left empty (the strap is only read while still). On your 20 to 31 Oct data: about 37 ms at 18:00 falling to about 21 at 23:00, 13 to 14 from midnight to 03:00, about 22 at 05:00 and 31 at 06:00, with heart rate 66 at 18:00 to 21:00 and 59 to 62 overnight."), HeartGraph.lp(a, -1, -2, 6));
        col.addView(c2, HeartGraph.lp(a, -1, -2, 12));

        // ---- how you compare
        double lim = eiLimit(age), rl = ratioLimit(age); float ei = hp.getFloat("ei", 5.2f), er = hp.getFloat("ratio", 1.08f); String when = hp.getString("eidate", "31 Oct 2026, seated, evening");
        LinearLayout c3 = card(a); c3.addView(head(a, "HOW YOU COMPARE"));
        c3.addView(body(a, "Healthy adults, 5-minute recordings: RMSSD 42 ms on average, spread 15 (Nunan 2010, 44 studies, 21,438 people), lower with age: 24-hour RMSSD falls to about 47 % of its 20s value by the 50s and then levels off (Umetani 1998). Men 45 to 54: SDNN about 37 ms, pNN50 about 6 % (Voss 2015). Short seated readings in the evening read lower than these supine 5-minute values."), HeartGraph.lp(a, -1, -2, 8));
        c3.addView(body(a, String.format(Locale.UK, "Deep-breathing test (6 breaths a minute, sitting): at age %.0f the lowest normal heart-rate swing is %.1f bpm and the lowest normal E/I ratio %.2f (a Danish general-population study, J Intern Med 1999). A clinical age table puts the lowest normal E/I at 1.07 for 50 to 54 and 1.06 for 55 to 59.", age, lim, rl)), HeartGraph.lp(a, -1, -2, 10));
        String verdict = ei >= lim && er >= rl ? "inside the age-adjusted limits" : ei >= lim * 0.95 || er >= 1.07 ? "at or just under the age-adjusted limits (borderline low)" : "under the age-adjusted limits";
        c3.addView(body(a, String.format(Locale.UK, "Your last test (%s): swing %.1f bpm, E/I %.2f: %s. One seated evening test with unmeasured breath depth cannot say more; repeat it supine in the morning. If it stays low, tell your doctor; heart medicines and some conditions lower it.", when, ei, er, verdict)), HeartGraph.lp(a, -1, -2, 10));
        TextView upd = HeartGraph.pill(a, "Enter a new test result", 0x1AFFFFFF, INK);
        upd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); int q = Math.round(dp(a, 16)); box.setPadding(q, q, q, q);
            final EditText e1 = field(a, "Heart-rate swing in bpm (E-I)", ""), e2 = field(a, "E/I ratio (longest / shortest R-R)", ""); box.addView(e1); box.addView(e2, HeartGraph.lp(a, -1, -2, 10));
            new AlertDialog.Builder(a).setTitle("Deep-breathing test").setMessage("From the Full pull analysis, or your own count: mean over the breath cycles of highest minus lowest heart rate.").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) {
                    double x = num(e1.getText().toString()), y = num(e2.getText().toString()); if (Double.isNaN(x) || Double.isNaN(y) || x <= 0 || y < 1) return;
                    hp.edit().putFloat("ei", (float) x).putFloat("ratio", (float) y).putString("eidate", new SimpleDateFormat("d MMM yyyy", Locale.UK).format(new Date())).apply(); rebuild.run(); } }).setNegativeButton("Cancel", null).show(); } });
        c3.addView(upd, HeartGraph.lp(a, -2, -2, 12));
        col.addView(c3, HeartGraph.lp(a, -1, -2, 12));

        // ---- what moves HRV
        LinearLayout c4 = card(a); c4.addView(head(a, "WHAT MOVES HRV"));
        c4.addView(body(a,
            "HEART RATE. HRV has a strong inverse relationship with heart rate (van den Berg 2018, 13,943 ECGs). On you: 9 to 10 ms at 70 bpm or more, 30 ms at 58 bpm (rank correlation -0.61, 9 readings).\n\n"
            + "BREATHING RATE. The swing with each breath is largest at about 6 a minute and shrinks as breathing speeds up (Shields 2009). On you: RMSSD falls as breathing quickens, rank correlation -0.70 (p 0.036, 9 readings); 9 to 10 ms at 15 to 17 breaths a minute in the evening.\n\n"
            + "BREATH DEPTH AND POSTURE. The deep-breathing response is largest lying down at 5 to 6 breaths a minute with a large tidal volume (Shields 2009). A quiet seated reading shows less.\n\n"
            + "AGE. Falls steadily to the 50s, then levels (Umetani 1998; Voss 2015). Heart rate also matters, so values are better compared after correcting for it (van den Berg 2018).\n\n"
            + "MEDICATION AND DISEASE. Cardiac medication and some conditions lower the deep-breathing response (J Intern Med 1999).\n\n"
            + "MEASUREMENT. ECG R-R intervals are the reference. The strap's optical intervals match an H10 ECG within 40 ms for 94 % of beats, but a small jitter on each interval inflates RMSSD 1.5 to 2 times in 3-minute windows. Use the ECG readings for absolute values and the strap for trends."), HeartGraph.lp(a, -1, -2, 8));
        col.addView(c4, HeartGraph.lp(a, -1, -2, 12));

        // ---- evening and alcohol
        LinearLayout c5 = card(a); c5.addView(head(a, "EVENING AND ALCOHOL"));
        c5.addView(body(a,
            "In 4,098 Finnish employees recorded in everyday life, alcohol lowered HRV-derived recovery in the first 3 hours of sleep in a dose-dependent way: by 9.3 points at up to 0.25 g/kg, 24.0 at 0.25 to 0.75 and 39.2 above 0.75 g/kg, all p < 0.001 (Pietilä 2018). The effect was seen even at low intake, was weaker in older people and did not depend on how active people were. One drink of about 14 g of alcohol is roughly 0.15 to 0.2 g/kg for most adults."), HeartGraph.lp(a, -1, -2, 8));
        c5.addView(body(a, "Your evening values fall from about 37 ms at 18:00 to about 13 ms in the first hours of the night and recover towards morning. That shape is expected after an evening drink, but it is also how the evening looks without one (food, posture, time of day), and the app cannot tell them apart without a log. Only 1 drink has been logged so far."), HeartGraph.lp(a, -1, -2, 10));
        // personal comparison from markers
        final long[] bed = new long[d.nights.size()]; final double[] rm = new double[bed.length], lo = new double[bed.length]; int k = 0;
        for (TodayModel.NightStat n : d.nights) { bed[k] = n.bed; rm[k] = n.rmssd; lo[k] = n.hrLow5; k++; }
        List<Long> drinks = new ArrayList<Long>(), dry = new ArrayList<Long>();
        try { SQLiteHelper.markers(a, "alcohol", drinks); SQLiteHelper.markers(a, "no_alcohol", dry); } catch (Throwable ignored) { }
        List<Double> wd = new ArrayList<Double>(), wl = new ArrayList<Double>(), nd = new ArrayList<Double>(), nlw = new ArrayList<Double>(); int cd = 0, cn = 0;
        for (int i = 0; i < bed.length; i++) {
            boolean dr = false, no = false; for (long t : drinks) if (t / 1000L <= bed[i] + 3600L && t / 1000L >= bed[i] - 10L * 3600L) dr = true; for (long t : dry) if (t / 1000L <= bed[i] + 3600L && t / 1000L >= bed[i] - 10L * 3600L) no = true;
            if (Double.isNaN(rm[i])) continue; if (dr) { wd.add(rm[i]); if (!Double.isNaN(lo[i])) wl.add(lo[i]); cd++; } else if (no) { nd.add(rm[i]); if (!Double.isNaN(lo[i])) nlw.add(lo[i]); cn++; }
        }
        String cmp = (cd >= 2 && cn >= 2) ? String.format(Locale.UK, "Your nights: after drinks %.0f ms RMSSD, lowest heart rate %.0f (%d nights); alcohol-free %.0f ms, %.0f (%d nights). Strap values, relative.", med(wd), med(wl), cd, med(nd), med(nlw), cn)
            : String.format(Locale.UK, "Your own comparison needs at least 2 nights of each. Logged so far: %d with drinks, %d alcohol-free. Tap a pill each evening.", cd, cn);
        c5.addView(body(a, cmp), HeartGraph.lp(a, -1, -2, 10));
        LinearLayout pr = new LinearLayout(a); pr.setOrientation(LinearLayout.HORIZONTAL);
        TextView pa = HeartGraph.pill(a, "A drink", 0x1AFFFFFF, INK), pn = HeartGraph.pill(a, "No alcohol today", 0x1AFFFFFF, INK);
        pa.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DayLog.addEvent(a, "marker", "alcohol"); Toast.makeText(a, "Drink logged", Toast.LENGTH_SHORT).show(); rebuild.run(); } });
        pn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DayLog.addEvent(a, "marker", "no_alcohol"); Toast.makeText(a, "Alcohol-free day logged", Toast.LENGTH_SHORT).show(); rebuild.run(); } });
        pr.addView(pa); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2); pp.leftMargin = Math.round(dp(a, 10)); pr.addView(pn, pp); c5.addView(pr, HeartGraph.lp(a, -2, -2, 12));
        c5.addView(small(a, "A fair two-week check: alternate evenings with and without, log each one, and read the comparison above. This is your own data, not medical advice."), HeartGraph.lp(a, -1, -2, 8));
        col.addView(c5, HeartGraph.lp(a, -1, -2, 12));

        // ---- nights and workload (dated analysis)
        LinearLayout c5b = card(a); c5b.addView(head(a, "YOUR NIGHTS AND WORKLOAD (20 to 31 Oct analysis, 8 nights)"));
        c5b.addView(body(a,
            "HEART RATE. In the first 30 minutes of sleep it is 50 to 63 bpm. Three to four hours in it is on average 8 bpm higher (7 of 8 nights, range -1 to +16) and stays higher.\n\n"
            + "HRV. Strap RMSSD is lowest in the first 3 hours (about 18 ms on average) and roughly doubles by the last 3 hours (about 34 ms). Relative values; the strap reads high.\n\n"
            + "EVENING BEFORE BED. RMSSD 82 and 71 ms on 21 and 22 Oct against 28 to 47 ms from 24 Oct; heart rate 61 against 62 to 70.\n\n"
            + "WORKLOAD. Steps went from 8 to 12 thousand a day (24 to 26 Oct) to 24 to 27 thousand (27 to 30 Oct). Over the 6 days with step counts, more steps went with a lower mid-night RMSSD (rank correlation -0.89, p 0.02, 6 days only) and a shorter night."), HeartGraph.lp(a, -1, -2, 8));
        c5b.addView(body(a,
            "WHAT THE LITERATURE SAYS. Evening alcohol lowers HRV in the first hours of sleep in a dose-dependent way, even at low intake (Pietilä 2018). A higher heart rate during manual work goes with lower HRV the following night, day by day, after adjusting for alcohol, in 959 blue-collar workers (Korshøj 2021); long work hours without enough recovery are a proposed reason manual work lacks the benefit of exercise (Holtermann 2018). In 8 trained runners a 31-day ketogenic diet lowered morning RMSSD by about 10 ms (randomised crossover, 2021).\n\n"
            + "WHAT THIS CANNOT SPLIT. Heavy workdays, evening beer and a low-carbohydrate day overlap on your data, and no alcohol-free evening has been logged. The heart-rate rise and low early-night HRV fit an evening drink, but they also fit a heavy day or a late meal. A fair test: 3 alcohol-free evenings in a row with a similar workload, logged with the No alcohol pill, then compare the first 3 hours of the night (heart rate and RMSSD) with your usual."), HeartGraph.lp(a, -1, -2, 10));
        col.addView(c5b, HeartGraph.lp(a, -1, -2, 12));

        // ---- tests
        LinearLayout c6 = card(a); c6.addView(head(a, "TESTS TO TRY"));
        c6.addView(body(a,
            "1. Morning supine test (06:30, before food or caffeine): lie flat 3 minutes with the H10 on. ECG tab, tag Table, Auto-stop OFF, Start. Breathe normally for 60 s, then 5 s in and 5 s out, as deep as is comfortable and without straining, for 90 s. Stop, Full pull.\n\n"
            + "2. Evening seated deep-breathing test: the same steps sitting. Compare with the age limits above.\n\n"
            + "Stop at once if you feel dizzy or tingly. Do not hold your breath."), HeartGraph.lp(a, -1, -2, 8));
        TextView pace = HeartGraph.pill(a, "Breathing pacer: 5 s in, 5 s out", BLUE, 0xFF05131E); pace.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pacer(a); } });
        c6.addView(pace, HeartGraph.lp(a, -2, -2, 12)); col.addView(c6, HeartGraph.lp(a, -1, -2, 12));

        // ---- sources
        LinearLayout c7 = card(a); c7.addView(head(a, "SOURCES"));
        c7.addView(small(a,
            "Nunan D, Sandercock GRH, Brodie DA. A quantitative systematic review of normal values for short-term heart rate variability in healthy adults. Pacing Clin Electrophysiol 2010;33:1407-17.\n"
            + "Umetani K, Singer DH, McCraty R, Atkinson M. Twenty-four hour time domain heart rate variability and heart rate: relations to age and gender over nine decades. J Am Coll Cardiol 1998;31:593-601.\n"
            + "Voss A, Schroeder R, Heitmann A, Peters A, Perz S. Short-term heart rate variability: influence of gender and age in healthy subjects. PLoS ONE 2015;10:e0118308.\n"
            + "van den Berg ME et al. Normal values of corrected heart-rate variability in 10-second electrocardiograms for all ages. Front Physiol 2018;9:424.\n"
            + "Shields RW. Heart rate variability with deep breathing as a clinical test of cardiovagal function. Cleve Clin J Med 2009;76(Suppl 2):S37-40.\n"
            + "Parasympathetic function during deep breathing in the general population: relation to coronary risk factors and normal range. J Intern Med 1999;245:287-94.\n"
            + "O'Brien IAD, O'Hare P, Corrall RJ. Heart rate variability in healthy subjects: effect of age and the derivation of normal ranges for tests of autonomic function. Br Heart J 1986;55:348-54.\n"
            + "Pietilä J et al. Acute effect of alcohol intake on cardiovascular autonomic regulation during the first hours of sleep in a large real-world sample of Finnish employees. JMIR Ment Health 2018;5:e23.\n"
            + "Korshøj M, Lund Rasmussen C, de Oliveira Sato T, Holtermann A, Hallman D. Heart rate during work and heart rate variability during the following night: a day-by-day investigation on the physical activity paradox among blue-collar workers. Scand J Work Environ Health 2021, doi:10.5271/sjweh.3965.\n"
            + "Holtermann A et al. The physical activity paradox: six reasons why occupational physical activity does not confer the cardiovascular health benefits that leisure time physical activity does. Br J Sports Med 2018;52:149-50.\n"
            + "Autonomic and perceptual responses to induction of a ketogenic diet in free-living endurance athletes: a randomized, crossover trial. Int J Sports Physiol Perform 2021;16:1603-9.\n"
            + "On this strap: 31 Oct 2026 analysis of 24 saved ECG readings, 9 raw readings, 10 days of per-second records and 5 H10 recordings; see the decode log, section 101."), HeartGraph.lp(a, -1, -2, 8));
        col.addView(c7, HeartGraph.lp(a, -1, -2, 12));
    }

    private static void fillDay(Activity a, LinearLayout cd, TextView status, DayTimeline.Result r) {
        if (r == null || r.days < 2) { status.setText("Needs at least 2 days of day-logger data."); return; }
        cd.removeView(status);
        DayTimeline.HourChart c1 = new DayTimeline.HourChart(a); c1.set(r.steps, r.hr, ""); cd.addView(c1, HeartGraph.lp(a, -1, Math.round(dp(a, 130)), 6));
        cd.addView(small(a, "Blue bars: steps an hour. Rose line: heart rate in seconds the strap calls STILL, pulse locked (axis shows its highest and lowest hour)."), HeartGraph.lp(a, -1, -2, 4));
        DayTimeline.HourChart c2 = new DayTimeline.HourChart(a); c2.set(null, r.skin, ""); cd.addView(c2, HeartGraph.lp(a, -1, Math.round(dp(a, 80)), 10));
        cd.addView(small(a, "Skin temperature by hour."), HeartGraph.lp(a, -1, -2, 4));
        StringBuilder sb = new StringBuilder(String.format(Locale.UK, "%d days of data. ", r.days));
        sb.append(r.workWindow.length() == 0 ? "No hour averages 800 steps or more. " : "Active hours (800 or more steps an hour): " + r.workWindow + ". ");
        if (!Double.isNaN(r.lockWork)) sb.append(String.format(Locale.UK, "The optical pulse was locked %.0f %% of the time then, so daytime optical heart rate and HRV understate hard work.", r.lockWork));
        cd.addView(body(a, sb.toString()), HeartGraph.lp(a, -1, -2, 10));
        if (!Double.isNaN(r.hrDay) && !Double.isNaN(r.hrEve) && !Double.isNaN(r.hrNight))
            cd.addView(body(a, String.format(Locale.UK, "Still heart rate: day (07 to 17) %.0f, evening (19 to 22) %.0f, night (00 to 05) %.0f. The evening is %+.0f bpm against the night and %+.0f against the day.", r.hrDay, r.hrEve, r.hrNight, r.hrEve - r.hrNight, r.hrEve - r.hrDay)), HeartGraph.lp(a, -1, -2, 8));
        if (!Double.isNaN(r.skEve) && !Double.isNaN(r.skNight))
            cd.addView(body(a, String.format(Locale.UK, "Skin: %s evening %.1f, night %.1f degrees.", Double.isNaN(r.skWork) ? "" : String.format(Locale.UK, "active hours %.1f, ", r.skWork), r.skEve, r.skNight)), HeartGraph.lp(a, -1, -2, 8));
        if (!r.nights.isEmpty()) {
            StringBuilder nl = new StringBuilder("EVENING TO NIGHT (still heart rate 19:00 to 30 minutes before bed, first 3 hours of the night, lowest 5 minutes)\n"); SimpleDateFormat f = new SimpleDateFormat("EEE d MMM", Locale.UK);
            for (double[] x : r.nights) nl.append(f.format(new Date((long) x[0] * 1000L))).append(":  ").append(Double.isNaN(x[1]) ? "-" : String.format(Locale.UK, "%.0f", x[1])).append("  ").append(Double.isNaN(x[2]) ? "-" : String.format(Locale.UK, "%.0f", x[2])).append("  ").append(Double.isNaN(x[3]) ? "-" : String.format(Locale.UK, "%.0f", x[3])).append('\n');
            cd.addView(small(a, nl.toString().trim()), HeartGraph.lp(a, -1, -2, 10));
            if (r.rhoN >= 6 && !Double.isNaN(r.rho)) cd.addView(body(a, String.format(Locale.UK, "Across %d nights, a higher evening still heart rate %s the first 3 hours of the night (rank correlation %+.2f). Small numbers, so a pointer rather than a proof. Evening heart rate reflects food, drink, stress and tiredness; the strap cannot tell which, so log the A drink and No alcohol today buttons below.", r.rhoN, r.rho >= 0.5 ? "went with a higher heart rate in" : r.rho <= -0.5 ? "went with a lower heart rate in" : "showed no clear link with", r.rho)), HeartGraph.lp(a, -1, -2, 8));
        }
    }

    /** Reads marker times (phone ms) of one kind from the day logger database. */
    static final class SQLiteHelper {
        static void markers(Context c, String kind, List<Long> out) {
            Cursor cu = DayLog.db(c).rawQuery("SELECT ms FROM events WHERE kind='marker' AND text=? ORDER BY ms", new String[] { kind });
            try { while (cu.moveToNext()) out.add(cu.getLong(0)); } finally { cu.close(); }
        }
    }

    private static void pacer(final Activity a) {
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.requestWindowFeature(Window.FEATURE_NO_TITLE); dlg.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setGravity(Gravity.CENTER_HORIZONTAL); int p = Math.round(dp(a, 20)); root.setPadding(p, Math.round(dp(a, 40)), p, p);
        final TextView word = HeartGraph.label(a, "Ready", 40, INK, 200); word.setGravity(Gravity.CENTER); root.addView(word, HeartGraph.lp(a, -1, -2, 10));
        final View circle = new View(a); GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(alpha(BLUE, 0.55f)); circle.setBackground(g);
        int sz = Math.round(dp(a, 240)); LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(sz, sz); cl.topMargin = Math.round(dp(a, 40)); root.addView(circle, cl); circle.setScaleX(0.45f); circle.setScaleY(0.45f);
        final TextView clock = HeartGraph.label(a, "", 16, MUT, 400); clock.setGravity(Gravity.CENTER); root.addView(clock, HeartGraph.lp(a, -1, -2, 30));
        root.addView(HeartGraph.label(a, "5 s in, 5 s out is 6 breaths a minute. Breathe deeply but without straining. Stop if dizzy.", 12, MUT, 400), HeartGraph.lp(a, -1, -2, 10));
        final Handler ui = new Handler(Looper.getMainLooper()); final boolean[] on = { false }; final long[] t0 = { 0 };
        final TextView go = HeartGraph.pill(a, "Start", GREEN, 0xFF05131E), cl2 = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() { @Override public void run() {
            if (!on[0]) return; long el = System.currentTimeMillis() - t0[0]; int ph = (int) (el / 5000L); boolean in = ph % 2 == 0;
            word.setText(in ? "IN" : "OUT"); clock.setText(String.format(Locale.UK, "%d:%02d   breath %d", el / 60000L, (el / 1000L) % 60, ph / 2 + 1));
            circle.animate().cancel(); circle.animate().scaleX(in ? 1.0f : 0.45f).scaleY(in ? 1.0f : 0.45f).setDuration(5000L).start();
            ui.postDelayed(this, 5000L - (el % 5000L) + 20L); } };
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!on[0]) { on[0] = true; t0[0] = System.currentTimeMillis(); go.setText("Stop"); step[0].run(); } else { on[0] = false; ui.removeCallbacksAndMessages(null); circle.animate().cancel(); go.setText("Start"); word.setText("Stopped"); } } });
        cl2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { on[0] = false; ui.removeCallbacksAndMessages(null); dlg.dismiss(); } });
        root.addView(go, HeartGraph.lp(a, -2, -2, 20)); root.addView(cl2, HeartGraph.lp(a, -2, -2, 12));
        dlg.setContentView(root); dlg.show();
    }
}
