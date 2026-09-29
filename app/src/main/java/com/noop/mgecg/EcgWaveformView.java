package com.noop.mgecg;

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
 * Purely a rendering component - knows nothing about BLE, opcodes, or
 * frame decoding. The host screen calls addSample(int) for every real,
 * decoded ECG sample as it arrives.
 */
public class EcgWaveformView extends View {

    private static final float WINDOW_SECONDS = 6f;
    private static final int SAMPLE_RATE_HZ = 100;
    private static final int BUFFER_SIZE =
            (int) (WINDOW_SECONDS * SAMPLE_RATE_HZ);

    private final int[] samples = new int[BUFFER_SIZE];
    private final boolean[] hasSample = new boolean[BUFFER_SIZE];
    private int writeHead = 0;
    private int totalSamplesReceived = 0;

    /*
     * Calibrated, fixed vertical scale - WHOOP's own window: its report
     * waveform is drawn from -1000 to +2000 uV on 6 boxes of 500 uV, and its
     * live screen plots raw R17 counts (= uV) with no auto-scaling. Samples
     * outside the window are clamped to its edge, as WHOOP does.
     */
    private static final float DISPLAY_MIN_UV = -1000f;
    private static final float DISPLAY_MAX_UV = 2000f;
    private float displayMin = DISPLAY_MIN_UV;
    private float displayMax = DISPLAY_MAX_UV;
    private boolean contactLost = false;
    private final Paint contactLostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        tracePaint.setStrokeWidth(3.4f);
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
        tracePaint.setStrokeCap(Paint.Cap.ROUND);

        traceGlowPaint.setColor(Color.parseColor("#402EE6A8"));
        traceGlowPaint.setStyle(Paint.Style.STROKE);
        traceGlowPaint.setStrokeWidth(9f);
        traceGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        traceGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        traceCoreHighlight.setColor(Color.parseColor("#C8FFF4E8"));
        traceCoreHighlight.setStyle(Paint.Style.STROKE);
        traceCoreHighlight.setStrokeWidth(1.1f);
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

        Arrays.fill(hasSample, false);
    }

    public void addSample(int value) {
        if (idleSweepAnimator != null) {
            idleSweepAnimator.cancel();
            idleSweepAnimator = null;
        }
        liveMode = true;

        samples[writeHead] = value;
        hasSample[writeHead] = true;
        writeHead = (writeHead + 1) % BUFFER_SIZE;
        totalSamplesReceived++;

        postInvalidateOnAnimation();
    }

    public void reset() {
        Arrays.fill(hasSample, false);
        writeHead = 0;
        totalSamplesReceived = 0;
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

        canvas.drawRect(0, 0, w, h, bgPaint);
        drawEcgPaperGrid(canvas, w, h);

        if (!liveMode) {
            drawIdleSweep(canvas, w, h);
        } else {
            drawLiveTrace(canvas, w, h);
        }

        canvas.drawRect(1, 1, w - 1, h - 1, borderPaint);
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
     * either); the values per box are exact.
     */
    private void drawEcgPaperGrid(Canvas canvas, int w, int h) {
        float minorSpacing = w / (WINDOW_SECONDS / 0.04f);

        int col = 0;
        for (float x = 0; x <= w; x += minorSpacing, col++) {
            Paint p = (col % 5 == 0) ? majorGridPaint : minorGridPaint;
            canvas.drawLine(x, 0, x, h, p);
        }

        float minorSpacingY = h / ((DISPLAY_MAX_UV - DISPLAY_MIN_UV) / 100f);
        int row = 0;
        for (float y = 0; y <= h; y += minorSpacingY, row++) {
            Paint p = (row % 5 == 0) ? majorGridPaint : minorGridPaint;
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

        float sweepFrac = writeHead / (float) BUFFER_SIZE;
        float sweepX = w * sweepFrac;

        float range = (displayMax - displayMin);
        if (range < 1f) {
            range = 1f;
        }

        float[] xs = new float[BUFFER_SIZE];
        float[] ys = new float[BUFFER_SIZE];
        int n = 0;

        for (int i = 0; i < BUFFER_SIZE; i++) {
            int idx = (writeHead + i) % BUFFER_SIZE;
            if (!hasSample[idx]) {
                continue;
            }
            float x = w * i / (float) BUFFER_SIZE;
            float v = Math.max(displayMin, Math.min(displayMax, samples[idx]));
            float norm = (v - displayMin) / range;
            float y = h - (norm * h);
            xs[n] = x;
            ys[n] = y;
            n++;
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
        }

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new LinearGradient(
                sweepX - 24, 0, sweepX, 0,
                Color.TRANSPARENT, Color.parseColor("#382EE6A8"),
                Shader.TileMode.CLAMP));
        canvas.drawRect(sweepX - 24, 0, sweepX, h, glow);
        canvas.drawLine(sweepX, 0, sweepX, h, sweepPaint);

        canvas.drawText(
                String.format(java.util.Locale.UK,
                        "%.0f to +%.0f mV \u00b7 large box 0.2 s \u00d7 0.5 mV",
                        displayMin * UV_PER_COUNT / 1000.0,
                        displayMax * UV_PER_COUNT / 1000.0),
                12, 30, scaleLabelPaint);

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
