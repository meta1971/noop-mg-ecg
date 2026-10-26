package com.noop.mgecg;

import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Decoder for the strap's per-second summary record (history record type 47, layout 18, 124 bytes) and a scanner
 * for the history capture files. Fields follow the public OpenStrap gen5 map and NOOP's R18 layout, re-checked on this project's own data (Oct 2026):
 * byte 10 = flags (bit 7 SET = optical front end at 25 Hz, clear = 50 Hz, which is why it clears while an ECG runs); bytes 19-20 = sub-second (1/32768 s);
 * bytes 24-31 = four R-R slots (u16, 1024 = 1 s; byte 31 is the high byte of slot 4); byte 63 = activity class (0 none, 1 walk, 2 run);
 * bytes 69-70 = cell temperature and 71-72 = ambient temperature (i16, tenths of a degree C; bytes 70 and 72 are their high bytes, 1 once the value reaches 25.6 C);
 * bytes 73-74 = skin temperature (i16, hundredths of a degree C; -5000 = unavailable); bytes 75/77/79 = packed per-channel AGC state words;
 * byte 81 bits 4-5 = strap sleep state, byte 82 = SpO2 estimate/status; byte 40 = optical quality score (255 clean); bytes 113-116 = a per-second signal-quality float32
 * (byte 116 is only its top byte, so its 192 to 191 changes mean nothing by themselves); bytes 78-80 = 11,114,7 (or 11,98,6) mark the sleep-only byte-82 window;
 * bytes 38-39 are extra HR estimates that went to zero after 26 Sep 2026.
 * Census of 48,745 records (18 Oct 2026): byte 35 bits 6:5 repeat the sleep state of byte 81 (100%), its bits 3:0 look random (0-15, not a counter), bit 4 never set;
 * byte 34 is a fixed 5.02 s cycle (0.1991 Hz on two runs two days apart; not physiological, not locked to the 1 s record clock); byte 105 is non-zero
 * almost only while pulse SNR (byte 108 as int8) sits at its floor of -128, i.e. pulse lock lost; AGC word 1 (75-76) is the only live one, words 2 and 3
 * (77-78, 79-80) are 3073 and 3074 except in the sleep-only window (78-80 = 11,114,7); constant bytes: 0-9, 14, 18, 21, 38, 39, 60-62, 64-68, 77, 83-104, 110-112, 117-119.
 * Pure Java on purpose, so it can be tested off the phone. RESEARCH ONLY.
 */
final class R18Codec {
    private R18Codec() {}

    static final String HEADER = "ts,sub_s,rec_idx,raw_active,hr,rr_n,rr1_ms,rr2_ms,rr3_ms,rr4_ms,alt_hr,b33,b36,"
            + "dyn_g,grav_x,grav_y,grav_z,steps,cadence,activity,therm1_c,therm2_c,skin_c,w75,onwrist,wake_q,state,"
            + "b81_hi,b82,spo2_window,b35_state,b34,b40,b105,opt106,opt107,opt108,opt109,f113,b32,hr38,hr39,"
            + "flags10,optical_hz,w77,w79,activity_name,skin_ok";

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
    /** Skin temperature in degrees C, read as a SIGNED 16-bit value (the older unsigned reading turned the 'unavailable' code -5000 into 605 C). Unavailable gives -50.0; test with skinOk(). */
    static double skinC(byte[] f) { return i16(f, 73) / 100.0; }
    static boolean skinOk(byte[] f) { return i16(f, 73) != -5000; }
    static String activityName(int code) { return code == 0 ? "none" : code == 1 ? "walk" : code == 2 ? "run" : "code" + code; }
    static boolean spo2Window(byte[] f) { return u8(f, 78) == 11 && u8(f, 79) == 114 && u8(f, 80) == 7; }
    static boolean rawActive(byte[] f) { return (u8(f, 10) & 0x80) == 0; }
    static int steps(byte[] f) { return u16(f, 57); }
    /** Extra heart-rate estimates (0 = none). On the 141 h of records examined they were present in most seconds until 26 Sep 2026 07:25 UTC and are zero in
     *  every record since (about 330,000), so this is NOT a per-second validity signal. Whenever present they were within about 1 bpm of the main HR (byte 22).
     *  They stopped around the time the 16-flag batch copied from the official app (hr_ch_switching, ir_hw_switching, make_hrfm_visible ...) was written
     *  to the strap on 26 Sep 09:28 UTC; the records from that window are missing, so which flag did it is not yet known. */
    static int hrAlt2(byte[] f) { return u8(f, 38); }
    static int hrAlt3(byte[] f) { return u8(f, 39); }
    /** Byte 32: 0 in 95.5% of seconds, 128 (bit 7) in 4.4%, only while the wrist is stationary (never with activity class above 0; median max accel delta 0.009 vs 0.036 g): a stillness flag candidate. Rare low values 1, 2, 4, 6, 16, 24 (59 records). Exported raw. */
    static int b32(byte[] f) { return u8(f, 32); }

    /**
     * The R-R words of one record in milliseconds (0 where absent). 0.11.1: the words ARE milliseconds. The old x1000/1024 (read as
     * 1/1024 s ticks) made every interval 2.3% short. Measured 25 Oct on this strap: 999.7 words per elapsed second over 4,804
     * five-minute windows with complete beat coverage (1,024 would be ticks), and 1,001.5 words per beat x HR / 60 over 62,564 windows.
     */
    static double rr(byte[] f, int k) {
        int n = Math.min(u8(f, 23), 4);
        return k < n ? u16(f, 24 + 2 * k) : 0.0;
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
                .append(',').append(skinOk(f) ? String.format(L, "%.2f", skinC(f)) : "")
                .append(',').append(u16(f, 75))
                .append(',').append(u8(f, 81) & 3).append(',').append((u8(f, 81) >> 2) & 3).append(',').append(state(f))
                .append(',').append((u8(f, 81) >> 6) & 3).append(',').append(u8(f, 82)).append(',').append(spo2Window(f) ? 1 : 0)
                .append(',').append((u8(f, 35) >> 5) & 3).append(',').append(u8(f, 34)).append(',').append(u8(f, 40)).append(',').append(u8(f, 105))
                .append(',').append(u8(f, 106)).append(',').append(u8(f, 107)).append(',').append(u8(f, 108)).append(',').append(u8(f, 109))
                .append(',').append(String.format(L, "%.4f", f32(f, 113)))
                .append(',').append(u8(f, 32)).append(',').append(u8(f, 38)).append(',').append(u8(f, 39))
                .append(',').append(u8(f, 10)).append(',').append((u8(f, 10) & 0x80) != 0 ? 25 : 50).append(',').append(u16(f, 77)).append(',').append(u16(f, 79))
                .append(',').append(activityName(u8(f, 63))).append(',').append(skinOk(f) ? 1 : 0);
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
