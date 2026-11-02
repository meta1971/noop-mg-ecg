package com.noop.mgecg;

// FILE VERSION 0.38.0 (1 Nov 2026): the strap's charging sessions from its own events: 7 CHARGING_ON and 8 CHARGING_OFF (they flip several times in one charge), 21 and 22 (session start and end), and 117 (charger state code: 0 stopped,
// 2 started, 3 a later phase, 4 complete; decoded from the WHOOP 5 record and checked on this strap). A session is a run of on/off events with no gap over 20 minutes.

import android.content.Context;
import android.database.Cursor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ChargeLog {
    private ChargeLog() {}

    static final class Session { long start, end; int toggles; boolean complete; }

    /** Pure: events {ts, id, code} sorted by time (code only for id 117). */
    static List<Session> sessions(List<long[]> ev) {
        List<Session> out = new ArrayList<Session>(); Session cur = null; long last = 0;
        for (long[] e : ev) {
            if (e[1] != 7 && e[1] != 8 && e[1] != 117) continue;
            if (cur == null || e[0] - last > 1200L) { cur = new Session(); cur.start = e[0]; out.add(cur); }
            cur.end = e[0]; last = e[0]; if (e[1] == 7) cur.toggles++; if (e[1] == 117 && e[2] == 4) cur.complete = true;
        }
        return out;
    }

    /** {headline, detail} for the Strap data screen: the last charges in the last 14 days. */
    static String[] summary(Context c) {
        List<long[]> ev = new ArrayList<long[]>();
        try {
            Cursor cu = DayLog.db(c).rawQuery("SELECT ts, id, inner FROM strap_events WHERE id IN (7, 8, 117) AND ts >= ? ORDER BY ts", new String[] { String.valueOf(System.currentTimeMillis() / 1000L - 14 * 86400L) });
            try { while (cu.moveToNext()) { int id = cu.getInt(1); long code = -1; if (id == 117) { String h = cu.getString(2); if (h != null && h.length() >= 4) { try { code = Integer.parseInt(h.substring(2, 4), 16); } catch (Exception ignored) { } } } ev.add(new long[] { cu.getLong(0), id, code }); } } finally { cu.close(); }
        } catch (Throwable ignored) { }
        List<Session> ss = sessions(ev); if (ss.isEmpty()) return new String[] { "no charge in 14 days", "The strap reports charging as events 7 and 8, with event 117 giving the charger state (0 stopped, 2 started, 3 later phase, 4 complete)." };
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.UK), g = new SimpleDateFormat("HH:mm", Locale.UK); StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, ss.size() - 4); i < ss.size(); i++) { Session s = ss.get(i); sb.append(f.format(new Date(s.start * 1000L))).append(" to ").append(g.format(new Date(s.end * 1000L))).append(" (").append((s.end - s.start) / 60).append(" min, ").append(s.toggles).append(" on/off flips").append(s.complete ? ", complete" : "").append(")\n"); }
        Session l = ss.get(ss.size() - 1); return new String[] { f.format(new Date(l.start * 1000L)) + (l.complete ? ", complete" : ""), sb.toString().trim() };
    }
}
