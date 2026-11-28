package com.noop.mgecg;

// FILE VERSION 0.95.0 (28 Nov 2026): NEW. Dot-field rings (design canvas board 19): a ring is a ring-shaped speckle of small dots instead of a stroked arc. Lit dots glow, unlit dots stay as a faint track.
//   score ring    : dots whose place along the arc is under the fraction are lit in the ring's colour (Today's rings, the small gauges, the avatar)
//   segment ring  : each dot takes the colour of the segment (sleep stage, night) it falls in; past the last segment the dots are unlit
//   day ring      : ONE DOT PER MINUTE of the day (1,440), 24 hour sectors from the day start; moving / still / asleep / still to come
// The dots come from a fixed seed (never the clock), so a ring never shimmers between redraws. Layout and colours are pure (no Android) and are tested off the phone; only draw() and the bitmaps use Canvas.
// Bitmaps are made once per ring and size by the views that use them and dropped when the view leaves the screen.

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.LruCache;
import android.view.View;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;

import static com.noop.mgecg.HealthViews.*;

final class DotRing {
    private DotRing() {}

    /** The dot band runs from this share of the outer radius to the outer radius. */
    static final float INNER = 0.70f;
    static final int NONE = 0, SKIP = 1;                 // colour codes in the colour arrays: 0 = unlit track dot, 1 = draw nothing

    // ------------------------------------------------------------------ pure layout
    static final class Field {
        final int n;
        final float[] t;        // place along the arc, 0..1
        final float[] rad;      // radius as a share of the outer radius, INNER..1
        final float[] jit;      // 0..1, varies size and brightness a little
        Field(int n) { this.n = n; t = new float[n]; rad = new float[n]; jit = new float[n]; }
    }

    /** cols x rows cells over the arc and the band, each dot moved a little inside its cell. */
    static Field field(int cols, int rows, long seed, float inner) {
        cols = Math.max(8, cols); rows = Math.max(2, rows);
        Random rnd = new Random(seed);
        Field f = new Field(cols * rows);
        int k = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                f.t[k] = (c + 0.5f + (rnd.nextFloat() - 0.5f) * 0.9f) / cols;
                f.rad[k] = inner + (r + 0.5f + (rnd.nextFloat() - 0.5f) * 0.8f) / rows * (1f - inner);
                f.jit[k] = rnd.nextFloat();
                k++;
            }
        }
        return f;
    }

    /** Dot spacing in px for an outer radius: about 2.6 % of it, never under 3 px. */
    static float spacing(float outerR) { return Math.max(3f, outerR * 0.026f); }

    /** A field whose dot density follows the ring's size and sweep (360 for a full ring, 270 for an open one). */
    static Field forSize(float outerR, float sweepDeg, long seed, float inner) {
        float sp = spacing(outerR);
        int rows = Math.max(4, Math.round(outerR * (1f - inner) / sp));
        int cols = Math.max(24, Math.round(sweepDeg / 360f * (float) (2.0 * Math.PI) * outerR * (1f + inner) / 2f / sp));
        return field(cols, rows, seed, inner);
    }

    static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Score ring: dots with t under frac are lit in one colour. col[i] = colour or NONE; al[i] = brightness 0.72..1. */
    static void score(Field f, float frac, int color, int[] col, float[] al) {
        frac = clamp01(frac);
        for (int i = 0; i < f.n; i++) { col[i] = f.t[i] < frac ? color : NONE; al[i] = 0.72f + 0.28f * f.jit[i]; }
    }

    static int lerp(int a, int b, float u) {
        u = clamp01(u);
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * u);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * u);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Score ring whose lit dots change colour along the arc (the avatar). */
    static void gradient(Field f, float frac, int c0, int c1, int[] col, float[] al) {
        frac = clamp01(frac);
        for (int i = 0; i < f.n; i++) {
            boolean lit = f.t[i] < frac;
            col[i] = lit ? lerp(c0, c1, frac > 0f ? f.t[i] / frac : 0f) : NONE;
            al[i] = 0.72f + 0.28f * f.jit[i];
        }
    }

    /** Segment ring: from[k]..to[k] (0..1 along the arc) in colour segCol[k] at segAl[k]; dots outside every segment stay unlit. */
    static void segments(Field f, float[] from, float[] to, int[] segCol, float[] segAl, int[] col, float[] al) {
        for (int i = 0; i < f.n; i++) {
            col[i] = NONE; al[i] = 0.72f + 0.28f * f.jit[i];
            for (int k = 0; k < from.length; k++) {
                if (f.t[i] >= from[k] && f.t[i] < to[k]) { col[i] = segCol[k]; al[i] *= segAl[k]; break; }
            }
        }
    }

    // ------------------------------------------------------------------ the day ring (one dot per minute)
    static final byte S_NONE = 0, S_SLEEP = 1, S_STILL = 2, S_MOVE = 3;
    static final int DAY_ROWS = 10, DAY_COLS = 6;                 // 60 slots per hour sector

    /** 1,440 dots: minute m (after the day start) sits in hour sector m / 60 at a slot of that sector, the slots shuffled per hour by a fixed seed. */
    static Field dayField(long seed) {
        Field f = new Field(1440);
        final float gap = 0.10f;                                  // share of an hour sector left empty between sectors
        for (int h = 0; h < 24; h++) {
            Random rnd = new Random(seed * 31L + h);
            int[] perm = new int[60];
            for (int i = 0; i < 60; i++) perm[i] = i;
            for (int i = 59; i > 0; i--) { int j = rnd.nextInt(i + 1); int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp; }
            for (int i = 0; i < 60; i++) {
                int slot = perm[i], r = slot / DAY_COLS, c = slot % DAY_COLS;
                int m = h * 60 + i;
                float u = (c + 0.5f + (rnd.nextFloat() - 0.5f) * 0.8f) / DAY_COLS;
                f.t[m] = (h + gap / 2f + u * (1f - gap)) / 24f;
                f.rad[m] = INNER + (r + 0.5f + (rnd.nextFloat() - 0.5f) * 0.8f) / DAY_ROWS * (1f - INNER);
                f.jit[m] = rnd.nextFloat();
            }
        }
        return f;
    }

    /** state[m] per minute after the day start; nowMin = minutes elapsed (minutes from nowMin on are still to come). Past minutes with no data are not drawn. */
    static void dayColors(Field f, byte[] state, int nowMin, int colMove, int colSleep, int colStill, int[] col, float[] al) {
        int light = lerp(colMove, 0xFFFFFFFF, 0.40f);
        for (int m = 0; m < f.n; m++) {
            int s = state != null && m < state.length ? state[m] : S_NONE;
            al[m] = 0.78f + 0.22f * f.jit[m];
            if (s == S_MOVE) col[m] = f.jit[m] > 0.62f ? light : colMove;
            else if (s == S_SLEEP) { col[m] = colSleep; al[m] = 0.80f; }
            else if (s == S_STILL) { col[m] = colStill; al[m] = 1f; }
            else col[m] = m >= nowMin ? NONE : SKIP;
        }
    }

    // ------------------------------------------------------------------ drawing
    static int withA(int c, float a) { return (Math.max(0, Math.min(255, Math.round(a * 255f))) << 24) | (c & 0xFFFFFF); }

    /** One batch of dots that share a colour, a brightness step and a size step: drawn with ONE drawPoints call. */
    private static final class Grp {
        int color; float alpha, rad; float[] xy = new float[64]; int n;
        void add(float x, float y) { if (n + 2 > xy.length) xy = Arrays.copyOf(xy, xy.length * 2); xy[n++] = x; xy[n++] = y; }
    }

    private static Grp grp(HashMap<Long, Grp> m, int color, int ab, int sb, float alpha, float rad) {
        long key = ((color & 0xFFFFFFFFL) << 8) | (ab << 4) | sb;
        Grp g = m.get(key);
        if (g == null) { g = new Grp(); g.color = color; g.alpha = alpha; g.rad = rad; m.put(key, g); }
        return g;
    }

    private static void flush(Canvas cv, Paint p, HashMap<Long, Grp> m) {
        for (Grp g : m.values()) {
            p.setStrokeWidth(g.rad * 2f);
            p.setColor(withA(g.color, g.alpha));
            cv.drawPoints(g.xy, 0, g.n, p);
        }
    }

    /** Draws the dots of f. Angles are Android degrees (0 = 3 o'clock, clockwise); the arc starts at startDeg and runs sweepDeg. Dots are batched by colour, brightness and size (a few drawPoints calls, not thousands of circles). */
    static void draw(Canvas cv, Field f, float cx, float cy, float outerR, float sp, float startDeg, float sweepDeg, int[] col, float[] al) {
        float base = sp * 0.34f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        HashMap<Long, Grp> track = new HashMap<>(), halo = new HashMap<>(), core = new HashMap<>();
        for (int i = 0; i < f.n; i++) {
            if (col[i] == SKIP) continue;
            double a = Math.toRadians(startDeg + sweepDeg * f.t[i]);
            float r = outerR * f.rad[i];
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            if (col[i] == NONE) {
                int jb = f.jit[i] < 0.5f ? 0 : 1;
                grp(track, 0xFFFFFFFF, jb, 0, 0.15f + 0.09f * jb, Math.max(0.6f, base * 0.62f)).add(x, y);
            } else {
                int ab = Math.max(1, Math.min(8, Math.round(al[i] * 8f)));
                float aq = ab / 8f;
                int sb = Math.min(2, (int) (f.jit[i] * 3f));
                float rq = Math.max(0.8f, base * (0.85f + 0.4f * (sb + 0.5f) / 3f));
                grp(halo, col[i], ab, 0, 0.13f * aq, base * 2.3f).add(x, y);
                grp(core, col[i], ab, sb, aq, rq).add(x, y);
            }
        }
        flush(cv, p, track); flush(cv, p, halo); flush(cv, p, core);
    }

    /** Finished ring bitmaps are kept (a few MB at most) so a screen that is rebuilt does not draw its rings again. Never recycled: views share them. */
    private static final class Cache {
        static final LruCache<String, Bitmap> C = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
    }
    private static Bitmap cached(String key) { return Cache.C.get(key); }
    private static Bitmap keep(String key, Bitmap b) { Cache.C.put(key, b); return b; }

    private static Bitmap blank(int px) { return Bitmap.createBitmap(Math.max(8, px), Math.max(8, px), Bitmap.Config.ARGB_8888); }

    /** A score ring in a px x px bitmap; the band's outer edge sits at 97 % of the half size so the halo is not cut. */
    static Bitmap scoreBitmap(int px, float frac, int color, long seed, float startDeg, float sweepDeg) {
        String key = "s|" + px + "|" + Math.round(clamp01(frac) * 720f) + "|" + color + "|" + seed + "|" + startDeg + "|" + sweepDeg;
        Bitmap hit = cached(key); if (hit != null) return hit;
        Bitmap b = blank(px);
        float cx = b.getWidth() / 2f, outer = b.getWidth() * 0.485f;
        Field f = forSize(outer, sweepDeg, seed, INNER);
        int[] col = new int[f.n]; float[] al = new float[f.n];
        score(f, frac, color, col, al);
        draw(new Canvas(b), f, cx, cx, outer, spacing(outer), startDeg, sweepDeg, col, al);
        return keep(key, b);
    }

    static Bitmap gradientBitmap(int px, float frac, int c0, int c1, long seed, float startDeg, float sweepDeg) {
        String key = "g|" + px + "|" + Math.round(clamp01(frac) * 720f) + "|" + c0 + "|" + c1 + "|" + seed + "|" + startDeg + "|" + sweepDeg;
        Bitmap hit = cached(key); if (hit != null) return hit;
        Bitmap b = blank(px);
        float cx = b.getWidth() / 2f, outer = b.getWidth() * 0.485f;
        Field f = forSize(outer, sweepDeg, seed, INNER);
        int[] col = new int[f.n]; float[] al = new float[f.n];
        gradient(f, frac, c0, c1, col, al);
        draw(new Canvas(b), f, cx, cx, outer, spacing(outer), startDeg, sweepDeg, col, al);
        return keep(key, b);
    }

    /** Segment ring in a bitmap of px; outerShare = outer band edge as a share of the half size (the caller keeps room for labels outside). */
    static Bitmap segmentBitmap(int px, float outerShare, float[] from, float[] to, int[] segCol, float[] segAl, long seed, float startDeg, float sweepDeg) {
        String key = "m|" + px + "|" + outerShare + "|" + Arrays.hashCode(from) + "|" + Arrays.hashCode(to) + "|" + Arrays.hashCode(segCol) + "|" + Arrays.hashCode(segAl) + "|" + seed + "|" + startDeg + "|" + sweepDeg;
        Bitmap hit = cached(key); if (hit != null) return hit;
        Bitmap b = blank(px);
        float cx = b.getWidth() / 2f, outer = b.getWidth() / 2f * outerShare;
        Field f = forSize(outer, sweepDeg, seed, INNER);
        int[] col = new int[f.n]; float[] al = new float[f.n];
        segments(f, from, to, segCol, segAl, col, al);
        draw(new Canvas(b), f, cx, cx, outer, spacing(outer), startDeg, sweepDeg, col, al);
        return keep(key, b);
    }

    static Bitmap dayBitmap(int px, float outerShare, byte[] state, int nowMin, int colMove, int colSleep, int colStill, long seed) {
        String key = "d|" + px + "|" + outerShare + "|" + nowMin + "|" + Arrays.hashCode(state) + "|" + colMove + "|" + colSleep + "|" + colStill + "|" + seed;
        Bitmap hit = cached(key); if (hit != null) return hit;
        Bitmap b = blank(px);
        float cx = b.getWidth() / 2f, outer = b.getWidth() / 2f * outerShare;
        Field f = dayField(seed);
        int[] col = new int[f.n]; float[] al = new float[f.n];
        dayColors(f, state, nowMin, colMove, colSleep, colStill, col, al);
        draw(new Canvas(b), f, cx, cx, outer, outer * (1f - INNER) / DAY_ROWS, -90f, 360f, col, al);
        return keep(key, b);
    }

    // ------------------------------------------------------------------ the day ring as a view (Active ring page)
    /** The day as 1,440 dots with the moving time in the middle. Day start at the top; four hour labels; a white dot for now. */
    static final class DotDayView extends View {
        private final byte[] state; private final int nowMin, startHour;
        private final String big, caption, sub;
        private Bitmap bm; private int bmPx;
        DotDayView(Context c, byte[] state, int nowMin, int startHour, String big, String caption, String sub) {
            super(c);
            this.state = state; this.nowMin = nowMin; this.startHour = startHour; this.big = big; this.caption = caption; this.sub = sub;
        }
        @Override protected void onMeasure(int w, int h) { int s = MeasureSpec.getSize(w); setMeasuredDimension(s, s); }
        @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); bm = null; bmPx = 0; }
        @Override protected void onDraw(Canvas cv) {
            Context c = getContext();
            int px = getWidth(); if (px <= 0) return;
            if (bm == null || bmPx != px) {
                bm = dayBitmap(px, 0.80f, state, nowMin, DailyViews.RING_ACTIVE, DailyViews.RING_SLEEP, 0xFF3A4A5C, 28L);
                bmPx = px;
            }
            cv.drawBitmap(bm, 0, 0, null);
            float cx = px / 2f, cy = px / 2f, outer = px / 2f * 0.80f;
            Paint lab = paint(MUT); lab.setTextAlign(Paint.Align.CENTER); lab.setTypeface(font(c, 400)); lab.setTextSize(dp(c, 11));
            for (int k = 0; k < 4; k++) {
                double a = Math.toRadians(-90 + 90 * k);
                float lr = outer + dp(c, 16);
                cv.drawText(String.format(java.util.Locale.UK, "%02d", (startHour + 6 * k) % 24), cx + (float) (lr * Math.cos(a)), cy + (float) (lr * Math.sin(a)) + dp(c, 4), lab);
            }
            if (nowMin >= 0 && nowMin < 1440) {
                double a = Math.toRadians(-90 + 360.0 * nowMin / 1440.0);
                float nr = outer + dp(c, 4);
                cv.drawCircle(cx + (float) (nr * Math.cos(a)), cy + (float) (nr * Math.sin(a)), dp(c, 4), paint(0xFFFFFFFF));
            }
            Paint t = paint(INK); t.setTextAlign(Paint.Align.CENTER); t.setTypeface(font(c, 200)); t.setTextSize(dp(c, 46));
            cv.drawText(big, cx, cy + dp(c, 14), t);
            Paint cap = paint(MUT); cap.setTextAlign(Paint.Align.CENTER); cap.setTypeface(font(c, 600)); cap.setTextSize(dp(c, 10.5f)); cap.setLetterSpacing(0.18f);
            cv.drawText(caption, cx, cy - dp(c, 32), cap);
            Paint sb = paint(MUT); sb.setTextAlign(Paint.Align.CENTER); sb.setTypeface(font(c, 400)); sb.setTextSize(dp(c, 12));
            cv.drawText(sub, cx, cy + dp(c, 38), sb);
        }
    }
}
