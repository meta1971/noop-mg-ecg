package com.noop.mgecg;

// FILE VERSION 0.19.2 (27 Oct 2026): shows one saved ECG report in a WebView, in its OWN PROCESS (":report" in the manifest).
//
// Why: Android ends every app that has the WebView loaded when Google updates "Android System WebView" (exit history line "stop com.google.android.webview
// due to installPackageLI", reason USER_REQUESTED, no restart of the sticky service). The main screen used to build the report WebView at every start, so the
// logger process held WebView all the time and the Play Store's WebView update at 00:06:47 on 27 Oct ended it. Now only this activity touches WebView, and it
// lives in a separate process that the logger does not depend on.

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ReportActivity extends Activity {
    private static final int BG = 0xFF0D1015, TEXT = 0xFFE8EEF8, CARD = 0xFF1A2230;
    private static String fontCss;
    private WebView webView;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView pill(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(22));
        b.setBackground(g);
        try { b.setTypeface(HealthViews.font(this, 600)); } catch (Throwable ignored) { b.setTypeface(Typeface.DEFAULT_BOLD); }
        return b;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("report"); } catch (Throwable ignored) { }   // set once per process; later calls throw and are ignored
        final String path = getIntent() == null ? null : getIntent().getStringExtra("path");
        String html = path == null ? null : readAll(new File(path));
        if (html == null) {
            Toast.makeText(this, "Could not read that report.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try { getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); } catch (Throwable ignored) { }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView arrow = new TextView(this);
        arrow.setText("\u2039");
        arrow.setTextColor(TEXT);
        arrow.setTextSize(30);
        arrow.setGravity(Gravity.CENTER);
        GradientDrawable ag = new GradientDrawable();
        ag.setColor(CARD);
        ag.setCornerRadius(dp(22));
        arrow.setBackground(ag);
        arrow.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        bar.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText(titleFor(path));
        title.setTextColor(TEXT);
        title.setTextSize(16);
        title.setPadding(dp(14), 0, 0, 0);
        try { title.setTypeface(HealthViews.font(this, 600)); } catch (Throwable ignored) { }
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView pro = pill("Pro PDF");                                   // 0.23.0: the professional report from the reading's own 500 Hz frames
        pro.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ProReportMaker.make(ReportActivity.this, new File(path)); } });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, dp(44)); plp.rightMargin = dp(8);
        bar.addView(pro, plp);
        TextView pull = pill("Full pull");                                  // 0.25.0: everything the app holds for this reading, one small zip
        pull.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ReadingPull.make(ReportActivity.this, new File(path)); } });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, dp(44)); ulp.rightMargin = dp(8);
        bar.addView(pull, ulp);
        TextView save = pill("Save PDF");
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { printReport(); } });
        bar.addView(save, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setAllowFileAccess(false);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        webView.loadDataWithBaseURL(null, withFont(html), "text/html", "UTF-8", null);
    }

    private void printReport() {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (pm == null || webView == null) return;
            String name = "ECG report";
            pm.print(name, webView.createPrintDocumentAdapter(name), new PrintAttributes.Builder().build());
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start printing: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** "ecg_report_1793054905.html" becomes the date of the reading. */
    private static String titleFor(String path) {
        try {
            String n = new File(path).getName().replaceAll("[^0-9]", "");
            if (n.length() >= 9) return new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK).format(new Date(Long.parseLong(n.substring(0, 10)) * 1000L));
        } catch (Throwable ignored) { }
        return "ECG report";
    }

    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try { int off = 0, k; while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k; } finally { in.close(); }
            return new String(b, "UTF-8");
        } catch (Throwable t) { return null; }
    }

    /** The report is plain HTML; give it the app's Outfit font (not stored in the saved file, which falls back to the system font). */
    private String withFont(String html) {
        try {
            if (fontCss == null) {
                File ff = OutfitData.file(this);
                byte[] b = new byte[(int) ff.length()];
                FileInputStream in = new FileInputStream(ff);
                try { int off = 0, k; while (off < b.length && (k = in.read(b, off, b.length - off)) > 0) off += k; } finally { in.close(); }
                fontCss = "<style>@font-face{font-family:'Outfit';font-weight:100 900;src:url(data:font/ttf;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP) + ")}</style>";
            }
            int i = html.indexOf("</head>");
            return i < 0 ? html : html.substring(0, i) + fontCss + html.substring(i);
        } catch (Throwable t) { return html; }
    }

    @Override protected void onDestroy() {
        try { if (webView != null) webView.destroy(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
