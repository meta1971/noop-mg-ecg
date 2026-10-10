package com.noop.mgecg;

import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Decoder for the strap's per-second summary record (history record type 47, layout 18, 124 bytes) and a scanner
 * for the history capture files. Fields follow NOOP's documented R18 layout plus what this project verified on its own
 * data: byte 10 bit 7 clears while raw ECG/optical collection runs, bytes 19-20 are the sub-second part of the strap
 * time, byte 35 bits 5-6 repeat the band state, byte 40 is an optical quality score (255 clean), byte 113 is a log
 * activity index, and bytes 78-80 = 11,114,7 mark the sleep-only byte-82 measurement window.
 * Pure Java on purpose, so it can be tested off the phone. RESEARCH ONLY.
 */
final class R18Codec {
    private R18Codec() {}

    static final String HEADER = "ts,sub_s,rec_idx,raw_active,hr,rr_n,rr1_ms,rr2_ms,rr3_ms,rr4_ms,alt_hr,b33,b36,"
            + "dyn_g,grav_x,grav_y,grav_z,steps,cadence,activity,therm1_c,therm2_c,skin_c,w75,onwrist,wake_q,state,"
            + "b81_hi,b82,spo2_window,b35_state,b34,b40,b105,opt106,opt107,opt108,opt109,f113";

    interface Visitor { void frame(byte[] f); }

    static int u8(byte[] f, int o) { return f[o] & 0xff; }
    static int u16(byte[] f, int o) { return (f[o] & 0xff) | ((f[o + 1] & 0xff) << 8); }
    static int i16(byte[] f, int o) { return (short) u16(f, o); }
    static long u32(byte[] f, int o) {
        return (f[o] & 0xffL) | ((f[o + 1] & 0xffL) << 8) | ((f[o + 2] & 0xffL) << 16) | ((f[o + 3] & 0xffL) << 24);
    }
    static float f32(byte[] f, int o) { return Float.intBitsToFloat((int) u32(f, o)); }

    static boolean isR18(byte[] f) { return f != null && f.length == 124 && u8(f, 8) == 47 && u8(f, 9) == 18; }
    static long ts(byte[] f) { return u32(f, 15); }
    static int hr(byte[] f) { return u8(f, 22); }
    static int state(byte[] f) { return (u8(f, 81) >> 4) & 3; }
    static double skinC(byte[] f) { return u16(f, 73) / 100.0; }
    static boolean spo2Window(byte[] f) { return u8(f, 78) == 11 && u8(f, 79) == 114 && u8(f, 80) == 7; }
    static boolean rawActive(byte[] f) { return (u8(f, 10) & 0x80) == 0; }
    static int steps(byte[] f) { return u16(f, 57); }

    /** The R-R words of one record in milliseconds (0 where absent). */
    static double rr(byte[] f, int k) {
        int n = Math.min(u8(f, 23), 4);
        return k < n ? u16(f, 24 + 2 * k) * 1000.0 / 1024.0 : 0.0;
    }

    static String csv(byte[] f) {
        StringBuilder b = new StringBuilder(200);
        Locale L = Locale.US;
        b.append(ts(f)).append(',').append(String.format(L, "%.4f", u16(f, 19) / 32768.0)).append(',').append(u32(f, 11)).append(',')
                .append(rawActive(f) ? 1 : 0).append(',').append(hr(f)).append(',').append(Math.min(u8(f, 23), 4));
        for (int k = 0; k < 4; k++) b.append(',').append(String.format(L, "%.1f", rr(f, k)));
        b.append(',').append(u8(f, 37)).append(',').append(u8(f, 33)).append(',').append(u8(f, 36))
                .append(',').append(String.format(L, "%.4f", f32(f, 41)))
                .append(',').append(String.format(L, "%.4f", f32(f, 45)))
                .append(',').append(String.format(L, "%.4f", f32(f, 49)))
                .append(',').append(String.format(L, "%.4f", f32(f, 53)))
                .append(',').append(u16(f, 57)).append(',').append(u16(f, 59)).append(',').append(u8(f, 63))
                .append(',').append(String.format(L, "%.1f", i16(f, 69) / 10.0))
                .append(',').append(String.format(L, "%.1f", i16(f, 71) / 10.0))
                .append(',').append(String.format(L, "%.2f", skinC(f)))
                .append(',').append(u16(f, 75))
                .append(',').append(u8(f, 81) & 3).append(',').append((u8(f, 81) >> 2) & 3).append(',').append(state(f))
                .append(',').append((u8(f, 81) >> 6) & 3).append(',').append(u8(f, 82)).append(',').append(spo2Window(f) ? 1 : 0)
                .append(',').append((u8(f, 35) >> 5) & 3).append(',').append(u8(f, 34)).append(',').append(u8(f, 40)).append(',').append(u8(f, 105))
                .append(',').append(u8(f, 106)).append(',').append(u8(f, 107)).append(',').append(u8(f, 108)).append(',').append(u8(f, 109))
                .append(',').append(String.format(L, "%.4f", f32(f, 113)));
        return b.toString();
    }

    /** Calls the visitor for every CRC-valid frame in a history capture; returns how many it found. */
    static int scan(byte[] d, Visitor v) {
        int i = 0, n = 0;
        while (i + 8 <= d.length) {
            if ((d[i] & 0xff) != 0xAA || d[i + 1] != 1) { i++; continue; }
            int len = (d[i + 2] & 0xff) | ((d[i + 3] & 0xff) << 8);
            int tot = 8 + len;
            if (len < 8 || i + tot > d.length) { i++; continue; }
            CRC32 c = new CRC32();
            c.update(d, i + 8, tot - 12);
            long want = (d[i + tot - 4] & 0xffL) | ((d[i + tot - 3] & 0xffL) << 8) | ((d[i + tot - 2] & 0xffL) << 16) | ((d[i + tot - 1] & 0xffL) << 24);
            if (c.getValue() != want) { i++; continue; }
            byte[] f = new byte[tot];
            System.arraycopy(d, i, f, 0, tot);
            v.frame(f);
            n++;
            i += tot;
        }
        return n;
    }
}
