package com.noop.mgecg;

// FILE VERSION 0.16.0 (27 Oct): the Daily backup screen: on or off, time, how long to keep snapshots, back up now, last result. Research only.

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import static com.noop.mgecg.HealthViews.*;

final class BackupScreen {
    private BackupScreen() {}

    static void show(final Activity a) {
        final android.content.Context app = a.getApplicationContext();
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
        top.addView(HeartGraph.label(a, "Daily backup", 22, INK, 300));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = HeartGraph.pill(a, "Close", 0x1AFFFFFF, INK);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dlg.dismiss(); } });
        top.addView(close);
        root.addView(top);

        ScrollView sv = new ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView intro = HeartGraph.label(a, "Every day at the time you choose, all the app's data is copied to Download/MGbackup, which stays on the phone if the app is removed. "
                + "It holds a compressed snapshot of the day-log database, the settings, and a mirror of every ECG, history, log and H10 file, copying only what is new or changed.", 13, MUT, 400);
        intro.setLineSpacing(0, 1.2f);
        body.addView(intro, HeartGraph.lp(a, -1, -2, 8));

        final TextView onOff = HeartGraph.pill(a, "", 0x1AFFFFFF, INK);
        body.addView(onOff, HeartGraph.lp(a, -2, -2, 14));
        final TextView time = HeartGraph.pill(a, "", 0x1AFFFFFF, INK);
        body.addView(time, HeartGraph.lp(a, -2, -2, 8));

        body.addView(HeartGraph.label(a, "KEEP DATABASE AND SETTINGS SNAPSHOTS FOR", 11, 0xFF6EC3FF, 600), HeartGraph.lp(a, -1, -2, 16));
        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(chips, HeartGraph.lp(a, -1, -2, 6));

        final TextView now = HeartGraph.pill(a, "Back up now", BLUE, 0xFF05131E);
        now.setGravity(Gravity.CENTER);
        body.addView(now, HeartGraph.lp(a, -1, -2, 16));

        final TextView status = HeartGraph.label(a, "", 12.5f, INK, 400);
        status.setLineSpacing(0, 1.25f);
        body.addView(status, HeartGraph.lp(a, -1, -2, 12));

        TextView note = HeartGraph.label(a, "The files mirror is never pruned, so nothing the app has ever saved is lost from it. Snapshots older than the chosen number of days are deleted. "
                + "A backup missed because the phone was off runs when the app is next opened. The first one copies everything and can take a few minutes. "
                + "To restore, send the newest db/daylog file (un-gzip it) and the files folder to Claude; an automatic restore button is not built yet.", 11.5f, MUT, 400);
        note.setLineSpacing(0, 1.2f);
        body.addView(note, HeartGraph.lp(a, -1, -2, 14));

        final Runnable[] draw = new Runnable[1];
        draw[0] = new Runnable() {
            @Override public void run() {
                boolean on = Backup.enabled(app);
                onOff.setText(on ? "\u2611  Daily backup is ON" : "\u2610  Daily backup is OFF");
                onOff.setTextColor(on ? BLUE : INK);
                time.setText("Backup time:  " + Backup.timeText(app) + "   (tap to change)");
                chips.removeAllViews();
                for (final int d : Backup.KEEP_CHOICES) {
                    boolean sel = Backup.keep(app) == d;
                    TextView ch = HeartGraph.pill(a, d + " days", sel ? BLUE : 0x1AFFFFFF, sel ? 0xFF05131E : INK);
                    ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Backup.setKeep(app, d); draw[0].run(); } });
                    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                    l.rightMargin = Math.round(dp(a, 8));
                    chips.addView(ch, l);
                }
                now.setAlpha(Backup.running() ? 0.5f : 1f);
                status.setText(Backup.statusText(app));
            }
        };
        onOff.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Backup.setEnabled(app, !Backup.enabled(app)); draw[0].run(); } });
        time.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker tp, int h, int m) { Backup.setTime(app, h, m); draw[0].run(); }
                }, Backup.hour(app), Backup.minute(app), true).show();
            }
        });
        now.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!Backup.running()) { Backup.startNow(app, "manual"); draw[0].run(); } } });

        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() { @Override public void run() { if (!dlg.isShowing()) return; draw[0].run(); h.postDelayed(this, 1500L); } };

        dlg.setContentView(root);
        dlg.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        dlg.show();
        draw[0].run();
        h.postDelayed(tick, 1500L);
    }
}
