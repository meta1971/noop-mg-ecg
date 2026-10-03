package com.noop.mgecg;

// FILE VERSION 0.2.3 (3 Oct): smooth scrolling playout + auto-scale (tap the graph to switch to WHOOP's fixed window)

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

/**
 * A real-time, sweeping ECG trace view - the visual centerpiece of the
 * ECG screen. Styled after real clinical ECG paper: a fine pink grid
 * at small intervals with bolder red lines marking larger divisions,
 * matching the standard convention (small squares = time/amplitude
 * units, bold squares grouping five of them) rather than a generic
 * dark oscilloscope look.
 *
 * The trace itself is rendered as a smoothed curve through the sample
 * points (quadratic Bezier segments meeting at each midpoint) rather
 * than straight line-to-line segments, which reads as noticeably less
 * jagged/synthetic at this sample density without misrepresenting the
 * underlying data - the same points are used, just connected more
 * smoothly, exactly as a real chart-rendering library would.
 *
 * Motion (0.2.3): the strap delivers the live ECG as one frame of 100 samples per second, so drawing samples as they
 * arrive moved the trace a sixth of the screen at once, once a second. Samples now go through EcgLivePlayout, which
 * plays them out at a steady 100 per second (about 0.7 s behind live) while the view redraws every screen refresh, and
 * the trace scrolls smoothly from right to left with the newest sample at the right edge.
 *
 * Vertical scale (0.2.2): the window now follows the signal (EcgAutoScale) so R waves fill the box whatever the
 * contact; the grid stays calibrated (small box 0.1 mV, large box 0.5 mV, lines at fixed millivolt values) and
 * the label states the true window. Tap the graph to switch between auto and WHOOP's fixed -1 to +2 mV window.
 *
 * Purely a rendering component - knows nothing about BLE, opcodes, or
 * frame decoding. The host screen calls addSample(int) for every real,
 * decoded ECG sample as it arrives.
 */
public class EcgWaveformView extends View {

    private static final float WINDOW_SECONDS = 6f;
    private static final int SAMPLE_RATE_HZ = 100;
    private static final int BUFFER_SIZE =
            (int) (WINDOW_SECONDS * SAMPLE_RATE_HZ);

    private final float[] samples = new float[BUFFER_SIZE];   // the samples already played out, as a ring
    private int writeHead = 0;
    private int shown = 0;                                     // how many valid samples are in the ring (up to BUFFER_SIZE)
    private int totalSamplesReceived = 0;

    private final EcgLivePlayout playout = new EcgLivePlayout();
    private long lastFrameNanos = 0;

    /*
     * Default window - WHOOP's own: its report waveform is drawn from -1000 to
     * +2000 uV on 6 boxes of 500 uV, and its live screen plots raw R17 counts
     * (= uV) with no auto-scaling. Used when auto-scale is off and until two
     * seconds of signal exist. Samples outside the window are clamped to its
     * edge, as WHOOP does.
     */
    private static final float DISPLAY_MIN_UV = -1000f;
    private static final float DISPLAY_MAX_UV = 2000f;
    private float displayMin = DISPLAY_MIN_UV;
    private float displayMax = DISPLAY_MAX_UV;
    private boolean contactLost = false;
    private final Paint contactLostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final EcgAutoScale scale = new EcgAutoScale();
    private final float[] scaleBuf = new float[BUFFER_SIZE];
    private boolean autoScale = true;
    private int sinceScaleUpdate = 0;

    // ECG-paper grid: a fine minor grid, with a bolder major grid
    // every 5th line - the real clinical convention.
    private final Paint minorGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint traceGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint traceCoreHighlight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /*
     * Live R17 samples are microvolts: OpenStrap edge 0.10.0 documents them as
     * "input-referred integer microvolts, exactly as the band sends them"
     * (lib/ecg/ecg_models.dart). Consistent with this project's data: the
     * median R-wave peak of 1082 counts (177 clean beats) = 1.08 mV, inside the
     * usual 0.5-2.0 mV range. Replaces the earlier empirical 0.46 uV/count
     * estimate (28 Sep), which under-read by about half. Applies to the live
     * 100 Hz stream only; the stored 500 Hz R16 is on a different scale.
     */
    private static final double UV_PER_COUNT = 1.0;

    private final Paint scaleLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path tracePath = new Path();

    private boolean liveMode = false;
    private ValueAnimator idleSweepAnimator;
    private float idlePhase = 0f;

    public EcgWaveformView(Context context) {
        super(context);
        init();
    }

    public EcgWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.parseColor("#0D1015"));

        minorGridPaint.setColor(Color.parseColor("#2A1418"));
        minorGridPaint.setStrokeWidth(1f);

        majorGridPaint.setColor(Color.parseColor("#4A2028"));
        majorGridPaint.setStrokeWidth(1.6f);

        borderPaint.setColor(Color.parseColor("#1F2733"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);

        int traceColor = Color.parseColor("#2EE6A8");

        tracePaint.setColor(traceColor);
        tracePaint.setStyle(Paint.Style.STROKE);
        tracePaint.setStrokeWidth(2.6f);
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
        tracePaint.setStrokeCap(Paint.Cap.ROUND);

        traceGlowPaint.setColor(Color.parseColor("#282EE6A8"));
        traceGlowPaint.setStyle(Paint.Style.STROKE);
        traceGlowPaint.setStrokeWidth(6.5f);
        traceGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        traceGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        traceCoreHighlight.setColor(Color.parseColor("#C8FFF4E8"));
        traceCoreHighlight.setStyle(Paint.Style.STROKE);
        traceCoreHighlight.setStrokeWidth(0.9f);
        traceCoreHighlight.setStrokeJoin(Paint.Join.ROUND);
        traceCoreHighlight.setStrokeCap(Paint.Cap.ROUND);

        sweepPaint.setColor(Color.parseColor("#D0FFFFFF"));
        sweepPaint.setStrokeWidth(2f);

        contactLostPaint.setColor(Color.parseColor("#FFC947"));
        contactLostPaint.setTextSize(40f);
        contactLostPaint.setTextAlign(Paint.Align.CENTER);
        contactLostPaint.setFakeBoldText(true);

        scaleLabelPaint.setColor(Color.parseColor("#8FA1BD"));
        scaleLabelPaint.setTextSize(24f);
        scaleLabelPaint.setTypeface(
                android.graphics.Typeface.MONOSPACE);

        setOnClickListener(v -> setAutoScale(!autoScale));
    }

    /** Auto-scale on (default): the window follows the signal. Off: WHOOP's fixed -1 to +2 mV window. */
    public void setAutoScale(boolean on) {
        autoScale = on;
        scale.reset();
        displayMin = DISPLAY_MIN_UV;
        displayMax = DISPLAY_MAX_UV;
        sinceScaleUpdate = 0;
        if (on) refreshScale();
        postInvalidateOnAnimation();
    }

    private void refreshScale() {
        int n = Math.min(shown, BUFFER_SIZE);
        System.arraycopy(samples, 0, scaleBuf, 0, n);        // order does not matter for the window
        scale.update(scaleBuf, n);
        displayMin = scale.min;
        displayMax = scale.max;
    }

    /** A live sample arrived (called 100 times when a frame arrives, once a second). It is played out smoothly. */
    public void addSample(int value) {
        if (idleSweepAnimator != null) {
            idleSweepAnimator.cancel();
            idleSweepAnimator = null;
        }
        liveMode = true;
        totalSamplesReceived++;
        playout.push(value);
        postInvalidateOnAnimation();
    }

    /** One released sample goes into the display ring and, every 0.25 s, the auto-scale looks at the window. */
    private void showSample(float v) {
        samples[writeHead] = v;
        writeHead = (writeHead + 1) % BUFFER_SIZE;
        if (shown < BUFFER_SIZE) shown++;
        if (autoScale && ++sinceScaleUpdate >= 25) {
            sinceScaleUpdate = 0;
            refreshScale();
        }
    }

    /** Moves the playout on by dt seconds (the screen refresh interval). Package-private so tests can drive it. */
    void advanceBy(double dt) {
        playout.advance(dt, this::showSample);
    }

    public void reset() {
        writeHead = 0;
        shown = 0;
        totalSamplesReceived = 0;
        playout.reset();
        lastFrameNanos = 0;
        scale.reset();
        sinceScaleUpdate = 0;
        displayMin = DISPLAY_MIN_UV;
        displayMax = DISPLAY_MAX_UV;
        contactLost = false;
        liveMode = false;
        startIdleSweep();
        invalidate();
    }

    private void startIdleSweep() {
        if (idleSweepAnimator != null) {
            idleSweepAnimator.cancel();
        }
        idleSweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        idleSweepAnimator.setDuration(2200);
        idleSweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleSweepAnimator.addUpdateListener(a -> {
            idlePhase = (float) a.getAnimatedValue();
            invalidate();
        });
        idleSweepAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!liveMode) {
            startIdleSweep();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (idleSweepAnimator != null) {
            idleSweepAnimator.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        if (liveMode) {
            long now = System.nanoTime();
            double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9;
            lastFrameNanos = now;
            advanceBy(dt);
        }

        canvas.drawRect(0, 0, w, h, bgPaint);
        drawEcgPaperGrid(canvas, w, h);

        if (!liveMode) {
            drawIdleSweep(canvas, w, h);
        } else {
            drawLiveTrace(canvas, w, h);
        }

        canvas.drawRect(1, 1, w - 1, h - 1, borderPaint);

        if (liveMode) postInvalidateOnAnimation();       // keep redrawing every screen refresh while live
    }

    /** Shown when the strap zeroes the live samples (fingers off the clasp). */
    public void setContactLost(boolean lost) {
        if (lost != contactLost) {
            contactLost = lost;
            postInvalidateOnAnimation();
        }
    }

    /**
     * Calibrated ECG paper: small box 0.04 s x 0.1 mV, large box (every 5th
     * line) 0.2 s x 0.5 mV - the standard grid, and the one WHOOP's report
     * uses. Boxes are not square on a phone-shaped view (WHOOP's aren't
     * either); the values per box are exact. Horizontal lines sit at fixed
     * millivolt values (every 0.1 mV, bold at multiples of 0.5 mV, so 0 mV is
     * always a bold line) and move with the window.
     */
    private void drawEcgPaperGrid(Canvas canvas, int w, int h) {
        float minorSpacing = w / (WINDOW_SECONDS / 0.04f);

        int col = 0;
        for (float x = 0; x <= w; x += minorSpacing, col++) {
            Paint p = (col % 5 == 0) ? majorGridPaint : minorGridPaint;
            canvas.drawLine(x, 0, x, h, p);
        }

        float range = displayMax - displayMin;
        if (range < 1f) {
            return;
        }
        int first = (int) Math.ceil(displayMin / 100f);
        int last = (int) Math.floor(displayMax / 100f);
        for (int k = first; k <= last; k++) {
            float y = h - ((k * 100f - displayMin) / range) * h;
            Paint p = (k % 5 == 0) ? majorGridPaint : minorGridPaint;
            canvas.drawLine(0, y, w, y, p);
        }
    }

    private void drawIdleSweep(Canvas canvas, int w, int h) {
        Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idlePaint.setColor(Color.parseColor("#4A5A52"));
        idlePaint.setStyle(Paint.Style.STROKE);
        idlePaint.setStrokeWidth(3f);
        idlePaint.setStrokeCap(Paint.Cap.ROUND);

        float y = h / 2f;
        float sweepX = w * idlePhase;

        canvas.drawLine(0, y, w, y, idlePaint);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setShader(new RadialGradient(
                sweepX, y, 20f,
                Color.parseColor("#A02EE6A8"), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(sweepX, y, 20f, dotPaint);

        Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        corePaint.setColor(Color.parseColor("#2EE6A8"));
        canvas.drawCircle(sweepX, y, 4.5f, corePaint);
    }

    private void drawLiveTrace(Canvas canvas, int w, int h) {

        float range = (displayMax - displayMin);
        if (range < 1f) {
            range = 1f;
        }

        // newest sample at the right edge, older ones to the left, the whole strip shifted by the playout's
        // fractional progress so it moves continuously instead of in sample-sized steps
        float pxPerSample = w / (float) (BUFFER_SIZE - 1);
        float shift = (float) playout.fraction() * pxPerSample;

        int n = Math.min(shown, BUFFER_SIZE);
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            int idx = ((writeHead - n + i) % BUFFER_SIZE + BUFFER_SIZE) % BUFFER_SIZE;
            xs[i] = w - shift - (n - 1 - i) * pxPerSample;
            float v = Math.max(displayMin, Math.min(displayMax, samples[idx]));
            ys[i] = h - ((v - displayMin) / range) * h;
        }

        tracePath.reset();

        if (n > 2) {
            tracePath.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n - 1; i++) {
                float midX = (xs[i] + xs[i + 1]) / 2f;
                float midY = (ys[i] + ys[i + 1]) / 2f;
                tracePath.quadTo(xs[i], ys[i], midX, midY);
            }
            tracePath.lineTo(xs[n - 1], ys[n - 1]);
        } else if (n == 2) {
            tracePath.moveTo(xs[0], ys[0]);
            tracePath.lineTo(xs[1], ys[1]);
        }

        if (n > 0) {
            canvas.drawPath(tracePath, traceGlowPaint);
            canvas.drawPath(tracePath, tracePaint);
            canvas.drawPath(tracePath, traceCoreHighlight);
            // a bright point where "now" is
            Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
            head.setColor(Color.parseColor("#2EE6A8"));
            canvas.drawCircle(xs[n - 1], ys[n - 1], 6f, head);
        }

        canvas.drawText(
                String.format(java.util.Locale.UK,
                        "%+.1f to %+.1f mV \u00b7 small box 0.1 mV \u00b7 large 0.5 mV",
                        displayMin * UV_PER_COUNT / 1000.0,
                        displayMax * UV_PER_COUNT / 1000.0),
                12, 30, scaleLabelPaint);
        canvas.drawText((autoScale ? "auto scale \u00b7 tap for WHOOP's fixed window"
                        : "fixed scale \u00b7 tap for auto")
                        + String.format(java.util.Locale.UK, " \u00b7 %.1f s behind live", playout.lagSeconds()),
                12, 58, scaleLabelPaint);

        if (contactLost) {
            canvas.drawText("NO CONTACT \u2014 fingers off the clasp",
                    w / 2f, h / 2f, contactLostPaint);
        }
    }

    public boolean isLive() {
        return liveMode;
    }

    public int getTotalSamplesReceived() {
        return totalSamplesReceived;
    }
}
