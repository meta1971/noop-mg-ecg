package com.noop.mgecg;

// FILE VERSION 0.25.0 (29 Oct 2026): "Healthspan (research)": an open, literature-based age estimate and pace of aging from the nights the app already holds. NOT the vendor's number; research only. Constants and sources are listed in the text it shows.

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class Healthspan {
    private Healthspan() {}
    static final double YPL = 8.0 / Math.log(2.0);       // years of age per unit of ln(hazard), mortality doubling time about 8 years (standard demographic constant)

    static final class Out {
        int nights; double rhr = Double.NaN, sleepH = Double.NaN, sri = Double.NaN, vo2 = Double.NaN; boolean vo2Manual;
        double yRhrLo, yRhrHi, yVo2Lo, yVo2Hi, ySri, totalLo, totalHi, pace = Double.NaN; String paceNote = "";
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
        o.vo2Manual = !Double.isNaN(vo2Manual); o.vo2 = o.vo2Manual ? vo2Manual : 15.3 * (208 - 0.7 * age) / o.rhr;
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

    static void show(final Activity a, final HealthModel.Data d) {
        final SharedPreferences sp = a.getSharedPreferences("healthspan", Context.MODE_PRIVATE);
        final List<TodayModel.NightStat> all = Trends.allNights(d); final TimeZone tz = TimeZone.getDefault();
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); int pad = (int) (16 * a.getResources().getDisplayMetrics().density); col.setPadding(pad, pad / 2, pad, 0);
        final EditText age = new EditText(a); age.setHint("Your age in years"); age.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); age.setText(String.valueOf(sp.getFloat("age", 53f)));
        final EditText vo2 = new EditText(a); vo2.setHint("VO2 max if you know it (blank = estimate)"); vo2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); if (sp.getFloat("vo2", 0f) > 0) vo2.setText(String.valueOf(sp.getFloat("vo2", 0f)));
        final TextView res = new TextView(a); res.setTextSize(12); res.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(age); col.addView(vo2); col.addView(res);
        final Runnable calc = new Runnable() { @Override public void run() {
            double ag = 53, v = Double.NaN; try { ag = Double.parseDouble(age.getText().toString().trim()); } catch (Exception ignored) { } try { v = Double.parseDouble(vo2.getText().toString().trim()); } catch (Exception ignored) { }
            if (ag < 18 || ag > 110) ag = 53; sp.edit().putFloat("age", (float) ag).putFloat("vo2", Double.isNaN(v) ? 0f : (float) v).apply();
            Out o = compute(all, ag, v, tz); pace(o, all, ag, v, tz); res.setText(text(o, ag)); } };
        calc.run();
        ScrollView sv = new ScrollView(a); sv.addView(col);
        final AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Healthspan (research)").setView(sv).setPositiveButton("Calculate", null).setNegativeButton("Close", null).create();
        dlg.show(); dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { calc.run(); } });
    }
}
