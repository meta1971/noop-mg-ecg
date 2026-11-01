package com.noop.mgecg;

// FILE VERSION 0.28.0 (31 Oct 2026): "Healthspan (research)": full screen. Age equivalent and pace of aging from the nights the app holds, fitness age, VO2 max (entered, from the Rockport walk test, or estimated),
// what moves the estimate, and the irregular rhythm watch. NOT the vendor's number; research only. Sources are listed under "How this is worked out".

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static com.noop.mgecg.HealthViews.*;

final class Healthspan {
    private Healthspan() {}
    static final double YPL = 8.0 / Math.log(2.0);       // years of age per unit of ln(hazard), mortality doubling time about 8 years (standard demographic constant)

    static final class Out {
        int nights; double rhr = Double.NaN, sleepH = Double.NaN, sri = Double.NaN, vo2 = Double.NaN; boolean vo2Manual;
        double yRhrLo, yRhrHi, yVo2Lo, yVo2Hi, ySri, totalLo, totalHi, pace = Double.NaN; String paceNote = "";
    }

    /** Maximal heart rate 211 - 0.64 x age (HUNT Fitness Study, Nes 2013, 3,320 healthy adults with verified maximal effort; SEE 10.8 bpm). */
    static double hrmax(double age) { return 211.0 - 0.64 * age; }

    /** Fitness age: the age at which the HUNT reference line equals this VO2 max (mean VO2peak at 20 to 29: men 54.0, women 42.9; about 6 % of that lower per decade). Clamped to 20 to 90. */
    static double fitnessAge(double vo2, boolean male) {
        double young = male ? 54.0 : 42.9;
        return Math.max(20, Math.min(90, 25 + 10 * (1 - vo2 / young) / 0.06));
    }

    private static double med(List<Double> v) { if (v.isEmpty()) return Double.NaN; double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i); return ProDsp.median(a); }
    private static double circ(double a, double b) { double d = Math.abs(a - b) % 1440; return Math.min(d, 1440 - d); }

    /** Sleep regularity index proxy from bed times and sleep durations (wake = bed + 1.15 x time asleep) of nights one day apart. */
    static double sri(List<TodayModel.NightStat> ns, TimeZone tz) {
        List<Double> mis = new ArrayList<>();
        for (int i = 1; i < ns.size(); i++) {
            TodayModel.NightStat a = ns.get(i - 1), b = ns.get(i); long gap = b.bed - a.bed; if (gap < 20 * 3600L || gap > 28 * 3600L || Double.isNaN(a.tstH) || Double.isNaN(b.tstH)) continue;
            double ba = ((a.bed + tz.getOffset(a.bed * 1000L) / 1000L) % 86400) / 60.0, bb = ((b.bed + tz.getOffset(b.bed * 1000L) / 1000L) % 86400) / 60.0;
            double wa = (ba + 1.15 * a.tstH * 60) % 1440, wb = (bb + 1.15 * b.tstH * 60) % 1440;
            mis.add((circ(ba, bb) + circ(wa, wb)) / 60.0);
        }
        if (mis.size() < 2) return Double.NaN; double m = 0; for (double x : mis) m += x; m /= mis.size();
        return 100.0 * (1.0 - 2.0 * m / 24.0);
    }

    static Out compute(List<TodayModel.NightStat> all, double age, double vo2Manual, TimeZone tz) {
        Out o = new Out(); List<TodayModel.NightStat> ns = new ArrayList<>();
        int from = Math.max(0, all.size() - 30); for (int i = from; i < all.size(); i++) if (!Double.isNaN(all.get(i).tstH)) ns.add(all.get(i));
        o.nights = ns.size(); if (ns.size() < 3) return o;
        List<Double> r = new ArrayList<>(), sl = new ArrayList<>(); for (TodayModel.NightStat n : ns) { if (!Double.isNaN(n.hrLow5)) r.add(n.hrLow5); sl.add(n.tstH); }
        o.rhr = med(r); double m = 0; for (double x : sl) m += x; o.sleepH = m / sl.size(); o.sri = sri(ns, tz);
        o.vo2Manual = !Double.isNaN(vo2Manual); o.vo2 = o.vo2Manual ? vo2Manual : 15.3 * hrmax(age) / o.rhr;
        if (!Double.isNaN(o.rhr)) { o.yRhrLo = Math.log(1.09) * (o.rhr - 60) / 10 * YPL; o.yRhrHi = Math.log(1.17) * (o.rhr - 60) / 10 * YPL; }
        double v2lo = o.vo2Manual ? o.vo2 : 0.8 * o.vo2;       // an estimated VO2 max is allowed to be 20 % too high
        o.yVo2Lo = Math.log(0.89) * (v2lo - 36) / 3.5 * YPL; o.yVo2Hi = Math.log(0.83) * (o.vo2 - 36) / 3.5 * YPL;
        o.ySri = Double.isNaN(o.sri) ? 0 : Math.log(0.81) * (o.sri - 81) / 10.7 * YPL;
        double a = Math.min(o.yRhrLo, o.yRhrHi) + Math.min(o.yVo2Lo, o.yVo2Hi) + o.ySri, b = Math.max(o.yRhrLo, o.yRhrHi) + Math.max(o.yVo2Lo, o.yVo2Hi) + o.ySri; o.totalLo = a; o.totalHi = b;
        return o;
    }

    /** Pace of aging: needs 60 nights (the last 30 against the 30 before). 1x = ageing at the calendar rate, 0 = holding steady; limited to the range -1x to 3x. */
    static void pace(Out o, List<TodayModel.NightStat> all, double age, double vo2Manual, TimeZone tz) {
        if (all.size() < 60) { o.paceNote = "Pace of aging needs 60 nights (the last 30 against the 30 before); you have " + all.size() + "."; return; }
        Out a = compute(all.subList(all.size() - 30, all.size()), age, vo2Manual, tz), b = compute(all.subList(all.size() - 60, all.size() - 30), age, vo2Manual, tz);
        double ya = 0.5 * (a.totalLo + a.totalHi), yb = 0.5 * (b.totalLo + b.totalHi); o.pace = Math.max(-1, Math.min(3, 1 + (ya - yb) / (30.0 / 365.25)));
    }

    static String text(Out o, double age) {
        if (o.nights < 3) return "Needs at least 3 nights with sleep recorded; you have " + o.nights + ". Wear the strap at night and open this again.";
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.UK, "Chronological age %.0f. Estimated age equivalent %.1f to %.1f (about %.1f %s than the calendar).\n\n", age, age + o.totalLo, age + o.totalHi, Math.abs(0.5 * (o.totalLo + o.totalHi)), 0.5 * (o.totalLo + o.totalHi) <= 0 ? "younger" : "older"));
        s.append(String.format(Locale.UK, "Nights used: %d (the last 30 at most)\n", o.nights));
        s.append(String.format(Locale.UK, "Resting heart rate %.0f bpm (lowest 5-minute value of the nights, median): %+.1f to %+.1f years against 60 bpm\n", o.rhr, Math.min(o.yRhrLo, o.yRhrHi), Math.max(o.yRhrLo, o.yRhrHi)));
        s.append(String.format(Locale.UK, "VO2 max %.1f ml/kg/min (%s): %+.1f to %+.1f years against 36 (typical for men 50 to 59)\n", o.vo2, o.vo2Manual ? "entered by you" : "ESTIMATED from heart rate: crude", Math.min(o.yVo2Lo, o.yVo2Hi), Math.max(o.yVo2Lo, o.yVo2Hi)));
        s.append(Double.isNaN(o.sri) ? "Sleep regularity: needs 3 nights one day apart\n" : String.format(Locale.UK, "Sleep regularity index about %.0f (population median 81): %+.1f years\n", o.sri, o.ySri));
        s.append(String.format(Locale.UK, "Sleep duration %.1f h a night (7 to 9 h is the lowest-risk range): not scored\n\n", o.sleepH));
        s.append(Double.isNaN(o.pace) ? o.paceNote : String.format(Locale.UK, "Pace of aging %.1fx (1x = the calendar rate, 0 = holding steady)", o.pace)).append("\n\n");
        s.append("Not scored yet: steps, time in heart rate zones, strength time, lean body mass (no usable evidence value or no input). Evidence used: resting heart rate, relative risk 1.09 (CMAJ 2016) to 1.17 (Nutr Metab Cardiovasc Dis 2017) per 10 bpm; fitness, 11 to 17 % lower all-cause mortality per MET (Br J Sports Med 2024; Kodama, JAMA 2009); sleep regularity, about 19 % lower hazard per standard deviation (Windred, Sleep 2024). Risk is turned into years with a mortality doubling time of about 8 years. The VO2 max estimate (15.3 x HRmax / resting heart rate) is the weakest input: enter a measured value if you have one. This is a research estimate and not the vendor's WHOOP Age. Not medical advice.");
        return s.toString();
    }

    /** A horizontal bar on a scale of -6 to +6 years: left (green) = younger, right (rose) = older; the light part is the low-to-high range, the line the middle. */
    static final class YearsBar extends View {
        private double lo, hi; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        YearsBar(Context c) { super(c); }
        void set(double lo, double hi) { this.lo = lo; this.hi = hi; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), mid = w / 2f, sc = (w / 2f) / 6f, r = h / 2f;
            p.setStyle(Paint.Style.FILL); p.setColor(0x14FFFFFF); cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            double m = 0.5 * (lo + hi); int col = m <= 0 ? GREEN : ROSE;
            float x0 = mid + (float) Math.max(-6, Math.min(6, lo)) * sc, x1 = mid + (float) Math.max(-6, Math.min(6, hi)) * sc; if (x1 - x0 < h) { x0 -= h / 2f; x1 += h / 2f; }
            p.setColor(alpha(col, 0.35f)); cv.drawRoundRect(new RectF(x0, 0, x1, h), r, r, p);
            float xm = mid + (float) Math.max(-6, Math.min(6, m)) * sc; p.setColor(col); cv.drawRoundRect(new RectF(xm - dp(getContext(), 2), 0, xm + dp(getContext(), 2), h), 2, 2, p);
            p.setColor(0x55FFFFFF); cv.drawRect(mid - 1, 0, mid + 1, h, p);
        }
    }

    private static EditText field(Context c, String hint, String val) {
        EditText e = new EditText(c); e.setHint(hint); e.setText(val); e.setTextColor(INK); e.setHintTextColor(MUT); e.setTextSize(17);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setBackground(glass(c, 12));
        int pd = Math.round(dp(c, 10)); e.setPadding(pd, pd, pd, pd); return e;
    }

    private static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); l.setBackground(glass(c, 18)); int p = Math.round(dp(c, 16)); l.setPadding(p, p, p, p); return l;
    }

    private static String yrs(double lo, double hi) { return String.format(Locale.UK, "%+.1f to %+.1f years", Math.min(lo, hi), Math.max(lo, hi)); }

    static void show(final Activity a, final HealthModel.Data d) {
        final SharedPreferences sp = a.getSharedPreferences("healthspan", Context.MODE_PRIVATE);
        final List<TodayModel.NightStat> all = Trends.allNights(d); final TimeZone tz = TimeZone.getDefault();
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16)); root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));
        LinearLayout top = new LinearLayout(a); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Healthspan", 22, INK, 300));
        top.addView(HeartGraph.label(a, "  research", 12, MUT, 400));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK); close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } }); top.addView(close);
        root.addView(top);
        final LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a); sv.addView(col); root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // hero
        LinearLayout hero = card(a); hero.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView heroBig = HeartGraph.label(a, "--", 58, INK, 200), heroSub = HeartGraph.label(a, "", 13, MUT, 400), heroPace = HeartGraph.label(a, "", 14, TEAL, 400);
        hero.addView(HeartGraph.label(a, "AGE EQUIVALENT", 11, MUT, 400)); hero.addView(heroBig); heroSub.setGravity(Gravity.CENTER); heroSub.setLineSpacing(0, 1.2f); hero.addView(heroSub); heroPace.setGravity(Gravity.CENTER); hero.addView(heroPace, HeartGraph.lp(a, -2, -2, 8));
        col.addView(hero, HeartGraph.lp(a, -1, -2, 14));

        // fitness age and VO2 max
        LinearLayout fit = new LinearLayout(a); fit.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout c1 = card(a), c2 = card(a);
        final TextView fitBig = HeartGraph.label(a, "--", 34, GOLD, 200), fitSub = HeartGraph.label(a, "", 12, MUT, 400);
        final TextView voBig = HeartGraph.label(a, "--", 34, BLUE, 200), voSub = HeartGraph.label(a, "", 12, MUT, 400);
        c1.addView(HeartGraph.label(a, "FITNESS AGE", 11, MUT, 400)); c1.addView(fitBig); c1.addView(fitSub);
        c2.addView(HeartGraph.label(a, "VO2 MAX", 11, MUT, 400)); c2.addView(voBig); c2.addView(voSub);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1f), l2 = new LinearLayout.LayoutParams(0, -2, 1f); l2.leftMargin = Math.round(dp(a, 10));
        fit.addView(c1, l1); fit.addView(c2, l2); col.addView(fit, HeartGraph.lp(a, -1, -2, 10));
        LinearLayout btns = new LinearLayout(a); btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView enter = HeartGraph.pill(a, "Enter VO2 max", 0x1AFFFFFF, INK), rock = HeartGraph.pill(a, "Rockport walk test", BLUE, 0xFF05131E);
        btns.addView(enter); LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.leftMargin = Math.round(dp(a, 10)); btns.addView(rock, rp);
        TextView stepB = HeartGraph.pill(a, "Step test", 0x1AFFFFFF, INK); stepB.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { StepTest.show(a); } }); LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-2, -2); sp2.leftMargin = Math.round(dp(a, 10)); btns.addView(stepB, sp2);
        col.addView(btns, HeartGraph.lp(a, -2, -2, 10));

        // what moves it
        LinearLayout mv = card(a);
        mv.addView(HeartGraph.label(a, "WHAT MOVES IT   (left = younger, right = older, 6 years each way)", 11, MUT, 400));
        final TextView[] mt = new TextView[3]; final YearsBar[] mb = new YearsBar[3]; String[] names = { "Resting heart rate", "VO2 max", "Sleep regularity" };
        for (int i = 0; i < 3; i++) {
            mt[i] = HeartGraph.label(a, names[i], 13, INK, 400); mb[i] = new YearsBar(a);
            mv.addView(mt[i], HeartGraph.lp(a, -1, -2, 12)); mv.addView(mb[i], HeartGraph.lp(a, -1, Math.round(dp(a, 10)), 6));
        }
        col.addView(mv, HeartGraph.lp(a, -1, -2, 14));

        // inputs
        LinearLayout in = card(a);
        in.addView(HeartGraph.label(a, "YOUR DETAILS", 11, MUT, 400));
        final EditText age = field(a, "Age in years", String.valueOf(sp.getFloat("age", 53f)));
        final TextView sex = HeartGraph.pill(a, "", 0x1AFFFFFF, INK);
        in.addView(HeartGraph.label(a, "Age", 12, MUT, 400), HeartGraph.lp(a, -2, -2, 10)); in.addView(age, HeartGraph.lp(a, -1, -2, 4));
        in.addView(sex, HeartGraph.lp(a, -2, -2, 10));
        col.addView(in, HeartGraph.lp(a, -1, -2, 14));

        // irregular rhythm watch
        LinearLayout wc = card(a);
        final TextView ws = HeartGraph.label(a, "", 13, MUT, 400); ws.setLineSpacing(0, 1.2f);
        wc.addView(HeartGraph.label(a, "IRREGULAR RHYTHM WATCH", 11, MUT, 400)); wc.addView(ws, HeartGraph.lp(a, -1, -2, 6));
        wc.addView(HeartGraph.label(a, "Open", 13, BLUE, 400), HeartGraph.lp(a, -2, -2, 8));
        wc.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { IrregularWatch.show(a); } });
        col.addView(wc, HeartGraph.lp(a, -1, -2, 14));

        // details
        final TextView det = HeartGraph.label(a, "", 12, MUT, 400); det.setLineSpacing(0, 1.25f); det.setVisibility(View.GONE);
        final TextView detBtn = HeartGraph.pill(a, "How this is worked out", 0x1AFFFFFF, INK);
        detBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { det.setVisibility(det.getVisibility() == View.GONE ? View.VISIBLE : View.GONE); } });
        col.addView(detBtn, HeartGraph.lp(a, -2, -2, 14)); col.addView(det, HeartGraph.lp(a, -1, -2, 8));

        final Runnable refresh = new Runnable() { @Override public void run() {
            double ag = sp.getFloat("age", 53f), v = sp.getFloat("vo2", 0f) > 0 ? sp.getFloat("vo2", 0f) : Double.NaN; boolean male = sp.getBoolean("male", true);
            sex.setText(male ? "Sex: male (tap to change)" : "Sex: female (tap to change)");
            Out o = compute(all, ag, v, tz); pace(o, all, ag, v, tz);
            double vo = !Double.isNaN(v) ? v : o.vo2;
            if (o.nights < 3) { heroBig.setText("--"); heroSub.setText(text(o, ag)); heroPace.setText(""); for (int i = 0; i < 3; i++) mb[i].set(0, 0); }
            else {
                double mid = 0.5 * (o.totalLo + o.totalHi);
                heroBig.setText(String.format(Locale.UK, "%.1f", ag + mid));
                heroSub.setText(String.format(Locale.UK, "range %.1f to %.1f   |   %.1f years %s than your %.0f", ag + o.totalLo, ag + o.totalHi, Math.abs(mid), mid <= 0 ? "younger" : "older", ag));
                heroPace.setText(Double.isNaN(o.pace) ? o.paceNote : String.format(Locale.UK, "Pace of aging %.1fx   (1x = calendar rate)", o.pace));
                mt[0].setText(String.format(Locale.UK, "Resting heart rate %.0f bpm   %s", o.rhr, yrs(o.yRhrLo, o.yRhrHi))); mb[0].set(Math.min(o.yRhrLo, o.yRhrHi), Math.max(o.yRhrLo, o.yRhrHi));
                mt[1].setText(String.format(Locale.UK, "VO2 max %.1f   %s", o.vo2, yrs(o.yVo2Lo, o.yVo2Hi))); mb[1].set(Math.min(o.yVo2Lo, o.yVo2Hi), Math.max(o.yVo2Lo, o.yVo2Hi));
                mt[2].setText(Double.isNaN(o.sri) ? "Sleep regularity: needs 3 nights one day apart" : String.format(Locale.UK, "Sleep regularity %.0f   %+.1f years", o.sri, o.ySri)); mb[2].set(o.ySri, o.ySri);
            }
            if (Double.isNaN(vo)) { voBig.setText("--"); voSub.setText("Needs 3 nights, or enter it"); fitBig.setText("--"); fitSub.setText(""); }
            else {
                voBig.setText(String.format(Locale.UK, "%.1f", vo)); if (!Double.isNaN(v)) { fitBig.setText(String.format(Locale.UK, "%.0f", fitnessAge(vo, male))); fitSub.setText(String.format(Locale.UK, "years, against your %.0f", ag)); }
                else { fitBig.setText(String.format(Locale.UK, "%.0f-%.0f", fitnessAge(vo, male), fitnessAge(0.8 * vo, male))); fitSub.setText(String.format(Locale.UK, "years, crude: the heart-rate estimate may be 20 %% too high. Take the Rockport test (you are %.0f)", ag)); }
                String src = !Double.isNaN(v) ? sp.getString("vo2_src", "Entered by you") : "Estimated from resting heart rate (crude, about 20 % too high at most)";
                long when = sp.getLong("vo2_date", 0L); if (!Double.isNaN(v) && when > 0) src += ", " + new java.text.SimpleDateFormat("d MMM yyyy", Locale.UK).format(new java.util.Date(when));
                voSub.setText(src);
            }
            ws.setText(IrregularWatch.statusLine(a));
            StringBuilder s = new StringBuilder(text(o, ag));
            s.append("\n\nFitness age: the age at which the HUNT Fitness Study average equals your VO2 max (men 54.0 ml/kg/min at 20 to 29 years, women 42.9, about 6 % lower per decade; healthy Norwegians, so higher than the general-population value of 36 used for the years above).");
            s.append("\nCrude VO2 max when none is entered: 15.3 x maximal heart rate / resting heart rate (Uth 2004), maximal heart rate 211 - 0.64 x age (HUNT, Nes 2013, SEE 10.8 bpm). Rockport walk test: Kline 1987, typical error about 4 to 5 ml/kg/min.");
            det.setText(s.toString()); } };
        age.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int i, int j, int k) { } public void onTextChanged(CharSequence s, int i, int j, int k) { }
            public void afterTextChanged(Editable e) { try { double x = Double.parseDouble(e.toString().trim()); if (x >= 18 && x <= 110) { sp.edit().putFloat("age", (float) x).apply(); refresh.run(); } } catch (Exception ignored) { } } });
        sex.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sp.edit().putBoolean("male", !sp.getBoolean("male", true)).apply(); refresh.run(); } });
        enter.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final EditText e = field(a, "VO2 max in ml/kg/min (blank = estimate)", sp.getFloat("vo2", 0f) > 0 ? String.valueOf(sp.getFloat("vo2", 0f)) : "");
            new AlertDialog.Builder(a).setTitle("VO2 max").setMessage("From a lab or gym test, or a sports watch. Leave blank to use the estimate.").setView(e)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface di, int w) {
                    double x = Double.NaN; try { x = Double.parseDouble(e.getText().toString().trim()); } catch (Exception ignored) { }
                    if (Double.isNaN(x) || x <= 0) sp.edit().putFloat("vo2", 0f).putString("vo2_src", "").putLong("vo2_date", 0L).apply();
                    else if (x >= 15 && x <= 90) sp.edit().putFloat("vo2", (float) x).putString("vo2_src", "Entered by you").putLong("vo2_date", System.currentTimeMillis()).apply();
                    refresh.run(); } }).setNegativeButton("Cancel", null).show(); } });
        rock.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Rockport.show(a); } });
        dlg.setContentView(root);
        dlg.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() { @Override public void onWindowFocusChanged(boolean has) { if (has) refresh.run(); } });
        refresh.run(); dlg.show();
    }
}
