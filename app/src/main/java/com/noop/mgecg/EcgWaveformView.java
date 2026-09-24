package com.noop.mgecg;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
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
 * new ECG screen. Modeled on how a real clinical monitor draws: a
 * bright cursor sweeps left-to-right across a fixed window, erasing
 * and overwriting the oldest data as it goes, rather than continuously
 * scrolling the whole trace (which is smoother-looking but less
 * "alive" than a genuine sweep display).
 *
 * Purely a rendering component - knows nothing about BLE, opcodes, or
 * frame decoding. The host screen calls addSample(int) for every
 * decoded ECG sample as it arrives (raw signed ADC counts, exactly
 * what decodeRealtimeEcg240() already extracts), and this view handles
 * buffering, auto-scaling and drawing.
 */
public class EcgWaveformView extends View {

    /** How many seconds of trace are visible on screen at once. */
    private static final float WINDOW_SECONDS = 6f;

    /** Sample rate of the live type=43/R17 stream, per docs/PROTOCOL_ECG.md. */
    private static final int SAMPLE_RATE_HZ = 100;

    private static final int BUFFER_SIZE =
            (int) (WINDOW_SECONDS * SAMPLE_RATE_HZ);

    private final int[] samples = new int[BUFFER_SIZE];
    private final boolean[] hasSample = new boolean[BUFFER_SIZE];
    private int writeHead = 0;
    private int totalSamplesReceived = 0;

    // Auto-scaling: tracks a slowly-adapting min/max so the trace
    // doesn't jump around on every single sample, but still responds
    // to genuine amplitude changes over a second or two.
    private float displayMin = -1000f;
    private float displayMax = 1000f;
    private static final float SCALE_SMOOTHING = 0.06f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint traceGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint centerLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        bgPaint.setColor(Color.parseColor("#0A0E14"));

        gridPaint.setColor(Color.parseColor("#1A2230"));
        gridPaint.setStrokeWidth(1.5f);

        centerLinePaint.setColor(Color.parseColor("#141B26"));
        centerLinePaint.setStrokeWidth(1.5f);

        tracePaint.setColor(Color.parseColor("#39FF6A"));
        tracePaint.setStyle(Paint.Style.STROKE);
        tracePaint.setStrokeWidth(4.5f);
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
        tracePaint.setStrokeCap(Paint.Cap.ROUND);

        traceGlowPaint.setColor(Color.parseColor("#3939FF6A"));
        traceGlowPaint.setStyle(Paint.Style.STROKE);
        traceGlowPaint.setStrokeWidth(11f);
        traceGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        traceGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        sweepPaint.setColor(Color.parseColor("#B0FFFFFF"));
        sweepPaint.setStrokeWidth(2.5f);

        Arrays.fill(hasSample, false);
    }

    /** Called by the host screen for every real, decoded ECG sample. */
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

        // gently widen/narrow the visible range toward the recent
        // sample's magnitude, so genuine amplitude changes are
        // followed without every single noisy sample causing a jump
        float target = Math.abs(value) * 1.35f + 200f;
        if (target > displayMax) {
            displayMax = displayMax + (target - displayMax) * 0.35f;
            displayMin = -displayMax;
        } else {
            displayMax = displayMax + (target - displayMax) * SCALE_SMOOTHING;
            displayMax = Math.max(displayMax, 400f);
            displayMin = -displayMax;
        }

        postInvalidateOnAnimation();
    }

    /** Clears the trace and returns to the idle placeholder sweep. */
    public void reset() {
        Arrays.fill(hasSample, false);
        writeHead = 0;
        totalSamplesReceived = 0;
        displayMin = -1000f;
        displayMax = 1000f;
        liveMode = false;
        startIdleSweep();
        invalidate();
    }

    /** A gentle, animated flat-line sweep shown before a session starts. */
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
        drawGrid(canvas, w, h);
        canvas.drawLine(0, h / 2f, w, h / 2f, centerLinePaint);

        if (!liveMode) {
            drawIdleSweep(canvas, w, h);
            return;
        }

        drawLiveTrace(canvas, w, h);
    }

    private void drawGrid(Canvas canvas, int w, int h) {
        int cols = 12;
        int rows = 6;
        for (int c = 1; c < cols; c++) {
            float x = w * c / (float) cols;
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (int r = 1; r < rows; r++) {
            float y = h * r / (float) rows;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
    }

    private void drawIdleSweep(Canvas canvas, int w, int h) {
        Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idlePaint.setColor(Color.parseColor("#3A4658"));
        idlePaint.setStyle(Paint.Style.STROKE);
        idlePaint.setStrokeWidth(3.5f);
        idlePaint.setStrokeCap(Paint.Cap.ROUND);

        float y = h / 2f;
        float sweepX = w * idlePhase;

        Path p = new Path();
        p.moveTo(0, y);
        p.lineTo(w, y);
        canvas.drawPath(p, idlePaint);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.parseColor("#39FF6A"));
        dotPaint.setShader(new RadialGradient(
                sweepX, y, 22f,
                Color.parseColor("#B039FF6A"), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(sweepX, y, 22f, dotPaint);

        Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        corePaint.setColor(Color.parseColor("#39FF6A"));
        canvas.drawCircle(sweepX, y, 5f, corePaint);
    }

    private void drawLiveTrace(Canvas canvas, int w, int h) {
        // the write head is the "sweep" position - draw a bright
        // vertical marker there, exactly like a real monitor's cursor
        float sweepFrac = writeHead / (float) BUFFER_SIZE;
        float sweepX = w * sweepFrac;

        tracePath.reset();
        boolean started = false;
        float range = (displayMax - displayMin);
        if (range < 1f) {
            range = 1f;
        }

        for (int i = 0; i < BUFFER_SIZE; i++) {
            // read the buffer starting one PAST the write head, so
            // the oldest sample is at screen-x=0 and the most recent
            // (just-written) sample sits right at the sweep cursor
            int idx = (writeHead + i) % BUFFER_SIZE;
            if (!hasSample[idx]) {
                continue;
            }
            float x = w * i / (float) BUFFER_SIZE;
            float norm = (samples[idx] - displayMin) / range;
            float y = h - (norm * h);

            if (!started) {
                tracePath.moveTo(x, y);
                started = true;
            } else {
                tracePath.lineTo(x, y);
            }
        }

        if (started) {
            canvas.drawPath(tracePath, traceGlowPaint);
            canvas.drawPath(tracePath, tracePaint);
        }

        // sweep cursor - a bright vertical line with a soft leading glow,
        // marking exactly where the next sample will be drawn
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new LinearGradient(
                sweepX - 26, 0, sweepX, 0,
                Color.TRANSPARENT, Color.parseColor("#40FFFFFF"),
                Shader.TileMode.CLAMP));
        canvas.drawRect(sweepX - 26, 0, sweepX, h, glow);
        canvas.drawLine(sweepX, 0, sweepX, h, sweepPaint);
    }

    public boolean isLive() {
        return liveMode;
    }

    public int getTotalSamplesReceived() {
        return totalSamplesReceived;
    }
}
