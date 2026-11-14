package com.noop.mgecg;

// FILE VERSION 0.55.0 (14 Nov 2026): the Quick guide (the words are in GuideText): what the five tabs are for, what each ring colour means, and the three gestures (tap a ring, Sync now, the full-screen graphs), in plain words.
// Opened from the link under the rings on Today and from the You tab. Static text only: it reads nothing and writes nothing.

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class QuickGuide {
    private QuickGuide() {}

    private static final int INK = 0xFFE8ECF2, MUT = 0xFF9AA3B0;

    static void show(Activity a) {
        try {
            float dn = a.getResources().getDisplayMetrics().density;
            int pad = (int) (20 * dn);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(pad, (int) (8 * dn), pad, (int) (8 * dn));
            col.addView(head(a, "The five tabs"));
            for (String[] t : GuideText.TABS) col.addView(line(a, t[0], INK, t[1]));
            col.addView(head(a, "The six rings"));
            for (int i = 0; i < GuideText.RINGS.length; i++) col.addView(line(a, DailyViews.RING_NAMES[i], DailyViews.RING_COLORS[i], GuideText.RINGS[i]));
            col.addView(head(a, "Good to know"));
            col.addView(plain(a, "Tap a ring to see how it is worked out."));
            col.addView(plain(a, "Sync now copies the strap's latest records to the phone. \"Pages not copied yet\" is how much of the strap's stored history is still waiting."));
            col.addView(plain(a, "Graphs with an Expand button (the stress graphs, the Activity day graph) open full screen. Pinch to zoom, drag to scroll, tap a point for its values, double tap to zoom in."));
            col.addView(plain(a, "Every score here is an estimate from this strap's own records. Not WHOOP's scores and not a medical measure."));
            ScrollView sv = new ScrollView(a);
            sv.addView(col);
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Quick guide").setView(sv).setPositiveButton("Got it", null).show();
        } catch (Throwable ignored) { }
    }

    private static View head(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s.toUpperCase(java.util.Locale.UK));
        t.setTextSize(11);
        t.setLetterSpacing(0.12f);
        t.setTextColor(MUT);
        t.setPadding(0, (int) (14 * a.getResources().getDisplayMetrics().density), 0, (int) (4 * a.getResources().getDisplayMetrics().density));
        return t;
    }

    private static View line(Activity a, String name, int nameColor, String text) {
        TextView t = new TextView(a);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
        sb.setSpan(new android.text.style.ForegroundColorSpan(nameColor), 0, name.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, name.length(), 0);
        sb.append("  ").append(text);
        t.setText(sb);
        t.setTextSize(14);
        t.setTextColor(INK);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (4 * a.getResources().getDisplayMetrics().density), 0, (int) (4 * a.getResources().getDisplayMetrics().density));
        return t;
    }

    private static View plain(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(INK);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (4 * a.getResources().getDisplayMetrics().density), 0, (int) (4 * a.getResources().getDisplayMetrics().density));
        return t;
    }
}
