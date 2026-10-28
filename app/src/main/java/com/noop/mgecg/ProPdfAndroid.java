package com.noop.mgecg;

// FILE VERSION 0.23.0 (28 Oct 2026): the phone's drawing surface for the professional report (android.graphics.pdf.PdfDocument).

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.OutputStream;

final class ProPdfAndroid implements ProPdf.Pen {
    private final PdfDocument doc = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas c;
    private int n;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override public void newPage() {
        if (page != null) doc.finishPage(page);
        n++;
        page = doc.startPage(new PdfDocument.PageInfo.Builder(842, 595, n).create());
        c = page.getCanvas();
    }
    void write(OutputStream out) throws java.io.IOException { if (page != null) { doc.finishPage(page); page = null; } doc.writeTo(out); doc.close(); }
    int pages() { return n; }

    private void stroke(double w, int rgb) { p.reset(); p.setAntiAlias(true); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth((float) w); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(0xFF000000 | rgb); }
    private void fillStyle(int argb) { p.reset(); p.setAntiAlias(true); p.setStyle(Paint.Style.FILL); p.setColor(argb); }

    @Override public void line(double x1, double y1, double x2, double y2, double w, int rgb) { stroke(w, rgb); c.drawLine((float) x1, (float) y1, (float) x2, (float) y2, p); }
    @Override public void rect(double x, double y, double w, double h, int fill, int stroke, double sw) {
        if (fill >= 0) { fillStyle(0xFF000000 | fill); c.drawRect((float) x, (float) y, (float) (x + w), (float) (y + h), p); }
        if (stroke >= 0) { stroke(sw, stroke); c.drawRect((float) x, (float) y, (float) (x + w), (float) (y + h), p); }
    }
    @Override public void text(String s, double x, double y, double size, int rgb, boolean bold, int align) {
        fillStyle(0xFF000000 | rgb); p.setTextSize((float) size); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        float w = p.measureText(s); c.drawText(s, (float) (align == 1 ? x - w / 2 : align == 2 ? x - w : x), (float) y, p);
    }
    @Override public void poly(double[] xs, double[] ys, int n, double w, int rgb) {
        if (n < 2) return; stroke(w, rgb); Path path = new Path(); path.moveTo((float) xs[0], (float) ys[0]); for (int i = 1; i < n; i++) path.lineTo((float) xs[i], (float) ys[i]); c.drawPath(path, p);
    }
    @Override public void fill(double[] xs, double[] ys, int n, int rgb, int alpha) {
        if (n < 3) return; fillStyle((alpha << 24) | rgb); Path path = new Path(); path.moveTo((float) xs[0], (float) ys[0]); for (int i = 1; i < n; i++) path.lineTo((float) xs[i], (float) ys[i]); path.close(); c.drawPath(path, p);
    }
    @Override public void dot(double x, double y, double r, int fill, int stroke) {
        if (fill >= 0) { fillStyle(0xFF000000 | fill); c.drawCircle((float) x, (float) y, (float) r, p); }
        if (stroke >= 0) { stroke(0.8, stroke); c.drawCircle((float) x, (float) y, (float) r, p); }
    }
    @Override public double width(String s, double size, boolean bold) { p.reset(); p.setAntiAlias(true); p.setTextSize((float) size); p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); return p.measureText(s); }
}
