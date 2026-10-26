package com.noop.mgecg;

// FILE VERSION 0.14.0 (26 Oct): the Export screen: choose a period, make one dated export, see every earlier export, send or delete it. Research only.

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.noop.mgecg.HealthViews.*;

final class ExportScreen {
    private ExportScreen() {}

    static final int[] DAYS = {1, 7, 0};
    static final String[] LABEL = {"Last 24 hours", "Last 7 days", "All data"};
    private static int scopeIdx = 0;
    private static boolean raw = false, making = false;

    static void show(final Activity a) {
        final Context app = a.getApplicationContext();
        final Dialog dlg = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = Math.round(dp(a, 16));
        root.setPadding(pad, Math.round(dp(a, 30)), pad, Math.round(dp(a, 14)));

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(HeartGraph.label(a, "Export data", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close);
        root.addView(top);

        TextView intro = HeartGraph.label(a, "Each export is saved in Download/MGexports in its own folder, named with the date and time it was made and the period it covers, and also as one zip. Send the zip: it holds every file and an index that says what each file is and which dates it covers.", 12, MUT, 400);
        intro.setLineSpacing(0, 1.2f);
        root.addView(intro, HeartGraph.lp(a, -1, -2, 8));

        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(chips, HeartGraph.lp(a, -1, -2, 12));
        final TextView rawChip = HeartGraph.pill(a, "", 0x1AFFFFFF, INK);
        root.addView(rawChip, HeartGraph.lp(a, -2, -2, 8));
        final TextView make = HeartGraph.pill(a, "Make export", BLUE, 0xFF05131E);
        make.setGravity(Gravity.CENTER);
        root.addView(make, HeartGraph.lp(a, -1, -2, 12));
        final TextView status = HeartGraph.label(a, "", 12, MUT, 400);
        status.setLineSpacing(0, 1.2f);
        root.addView(status, HeartGraph.lp(a, -1, -2, 8));
        root.addView(HeartGraph.label(a, "EARLIER EXPORTS", 11, 0xFF6EC3FF, 600), HeartGraph.lp(a, -1, -2, 16));

        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final Runnable[] refresh = new Runnable[1];
        final Runnable[] drawChips = new Runnable[1];
        drawChips[0] = new Runnable() {
            @Override public void run() {
                chips.removeAllViews();
                for (int i = 0; i < LABEL.length; i++) {
                    final int ri = i;
                    TextView ch = HeartGraph.pill(a, LABEL[i], i == scopeIdx ? BLUE : 0x1AFFFFFF, i == scopeIdx ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scopeIdx = ri; drawChips[0].run(); } });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(a, 8));
                    chips.addView(ch, l);
                }
                rawChip.setText(raw ? "\u2611  Include the raw history files (large)" : "\u2610  Include the raw history files (large)");
                rawChip.setTextColor(raw ? BLUE : INK);
            }
        };
        rawChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { raw = !raw; drawChips[0].run(); } });

        refresh[0] = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        final List<ExportCatalog.Entry> es = ExportCatalog.list(app);
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                list.removeAllViews();
                                if (es.isEmpty()) { list.addView(HeartGraph.label(a, "No exports yet. Choose a period and tap Make export.", 13, MUT, 400)); return; }
                                for (ExportCatalog.Entry e : es) list.addView(row(a, app, e, refresh), HeartGraph.lp(a, -1, -2, 10));
                            }
                        });
                    }
                }, "export-list").start();
            }
        };

        make.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (making) return;
                making = true;
                final int days = DAYS[scopeIdx];
                final boolean withRaw = raw;
                make.setAlpha(0.5f);
                status.setText("Making the export (" + ExportCatalog.scopeText(days) + (withRaw ? ", with raw history" : "") + "). This takes up to a minute, keep the app open\u2026");
                new Thread(new Runnable() {
                    @Override public void run() {
                        SummaryExport.Result r;
                        try { r = SummaryExport.run(app, days, withRaw); }
                        catch (Throwable t) { r = new SummaryExport.Result(); r.note = "Export stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()); }
                        final SummaryExport.Result res = r;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                making = false;
                                make.setAlpha(1f);
                                status.setText(res.note);
                                refresh[0].run();
                            }
                        });
                    }
                }, "export-make").start();
            }
        });

        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        drawChips[0].run();
        refresh[0].run();
    }

    private static View row(final Activity a, final Context app, final ExportCatalog.Entry e, final Runnable[] refresh) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x0DFFFFFF); g.setCornerRadius(dp(a, 16)); g.setStroke(Math.round(dp(a, 1)), 0x0FFFFFFF);
        c.setBackground(g);
        int p = Math.round(dp(a, 14));
        c.setPadding(p, p, p, p);
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM HH:mm", Locale.UK);
        c.addView(HeartGraph.label(a, f.format(new Date(e.madeMs)) + "  \u00b7  " + e.scope, 14, INK, 600));
        if (e.dataFrom > 0) c.addView(HeartGraph.label(a, "Data " + f.format(new Date(e.dataFrom * 1000L)) + "  \u2192  " + f.format(new Date(e.dataTo * 1000L)), 12, MUT, 400), HeartGraph.lp(a, -1, -2, 3));
        c.addView(HeartGraph.label(a, e.files + " files  \u00b7  " + ExportCatalog.sizeText(e.bytes) + "  \u00b7  " + e.id, 11, MUT, 400), HeartGraph.lp(a, -1, -2, 3));
        LinearLayout btn = new LinearLayout(a);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        TextView sendZip = HeartGraph.pill(a, "Send zip", BLUE, 0xFF05131E);
        sendZip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ExportCatalog.send(a, e.zipUri, "application/zip", e.id + ".zip"); } });
        btn.addView(sendZip);
        if (e.indexUri != null) {
            TextView sendIdx = HeartGraph.pill(a, "Index", 0x1AFFFFFF, INK);
            sendIdx.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ExportCatalog.send(a, e.indexUri, "text/plain", e.id + "_00_INDEX.txt"); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.leftMargin = Math.round(dp(a, 8));
            btn.addView(sendIdx, l);
        }
        if (e.rawUri != null) {
            TextView sendRaw = HeartGraph.pill(a, "Raw", 0x1AFFFFFF, INK);
            sendRaw.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { ExportCatalog.send(a, e.rawUri, "application/zip", e.id + "_raw_history.zip"); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.leftMargin = Math.round(dp(a, 8));
            btn.addView(sendRaw, l);
        }
        btn.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView del = HeartGraph.pill(a, "Delete", 0x1AFFFFFF, ROSE);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Delete this export?")
                        .setMessage(e.id + " and all its files in Download/" + ExportCatalog.ROOT + " will be removed from this phone.")
                        .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                new Thread(new Runnable() { @Override public void run() { ExportCatalog.delete(app, e); a.runOnUiThread(refresh[0]); } }, "export-del").start();
                            }
                        }).setNegativeButton("Keep", null).show();
            }
        });
        btn.addView(del);
        c.addView(btn, HeartGraph.lp(a, -1, -2, 10));
        return c;
    }
}
