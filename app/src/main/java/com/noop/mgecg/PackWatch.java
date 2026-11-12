package com.noop.mgecg;

// FILE VERSION 0.53.6 (12 Nov 2026): is the battery pack on the strap? (decode log 169.1, 172). Byte 10 of the strap's per-second record (layout 18) has bit 1 set through every battery-pack session:
// 25,039 seconds with the bit, 100 % of them between the pack events 21 and 22 (raw history of 24 Oct to 12 Nov); during an ECG session with the pack on the byte reads 2 (229 seconds, the reading of 6 Nov).
// The one reading taken with the pack attached had amplifier FIFO overflow errors, 41 short frames and result 6 (decode log 138.8): readings should be taken with the pack off. Never a hard gate.
// The live ECG stream's filtered frames (type 43 layout 17) and motion frames (layout 21) carry the same byte 10 (0 in an ECG session, 128 outside); that bit 1 shows there too with the pack on is INFERRED
// from the stored records (no live reading with the pack is held), so pack_live_frames is a count to be checked, not yet a proven flag. The raw ECG frame (layout 16) uses byte 10 differently (3 / 131) and is not read.

import android.content.Context;
import android.database.Cursor;

import java.util.List;

final class PackWatch {
    private PackWatch() {}

    static boolean bit(byte[] record) { return record != null && record.length > 10 && (record[10] & 0x02) != 0; }

    /** {1 or 0, age in seconds} from the newest per-second record in the day log, or null when there is none. The record is as old as the last history pull (up to about 30 minutes). */
    static long[] lastRecord(Context c) {
        try {
            Cursor q = DayLog.db(c).rawQuery("SELECT ts, substr(rec, 11, 1) FROM r18 ORDER BY ts DESC LIMIT 1", null);
            try {
                if (!q.moveToFirst()) return null;
                byte[] b = q.isNull(1) ? null : q.getBlob(1); if (b == null || b.length < 1) return null;
                return new long[] { (b[0] & 0x02) != 0 ? 1 : 0, Math.max(0L, System.currentTimeMillis() / 1000L - q.getLong(0)) };
            } finally { q.close(); }
        } catch (Throwable t) { return null; }
    }

    /** Pure: {frames with bit 1 of byte 10 set, frames looked at} over the live filtered (layout 17) and motion (layout 21) frames of a reading's file. */
    static int[] inFrames(List<byte[]> frames) {
        int set = 0, n = 0;
        if (frames != null) for (byte[] f : frames) { if (f == null || f.length < 12 || (f[8] & 0xFF) != 43) continue; int lay = f[9] & 0xFF; if (lay != 17 && lay != 21) continue; n++; if ((f[10] & 0x02) != 0) set++; }
        return new int[] { set, n };
    }

    static String warning(long[] last) {
        if (last == null || last[0] != 1 || last[1] > 2700) return null;
        return "The battery pack was on the strap " + (last[1] < 90 ? "a moment ago" : Math.round(last[1] / 60.0) + " min ago") + ". Take it off for a clean reading: the one reading taken with it on had data errors.";
    }
}
