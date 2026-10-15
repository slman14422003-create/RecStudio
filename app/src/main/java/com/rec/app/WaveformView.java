package com.rec.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class WaveformView extends View {

    public interface OnSeek {
        void onSeek(float fraction, boolean finished);
    }

    public interface OnSelection {
        void onSelection(float start, float end, boolean finished);
    }

    public static final int BAR_MS = 50;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float barW;
    private final float gap;
    private final float step;
    private final float minHalf;
    private final float vPad;

    private float[] peaks = new float[0];
    private float[] live = new float[1024];
    private int liveCount;
    private boolean liveMode;
    private boolean running = true;
    private long lastBarAt;
    private float frozenFrac;

    private boolean interactive;
    private boolean selectable;
    private float selA = 0f;
    private float selB = 1f;
    private float minGap = 0.02f;
    private int activeHandle = -1;

    private float progress;
    private long durationMs;
    private final List<Long> markers = new ArrayList<>();
    private OnSeek seekListener;
    private OnSelection selectionListener;
    private final int accent;
    private final int idle;
    private final int bad;
    private final int primary;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barW = Ui.dp(context, 3);
        gap = Ui.dp(context, 2);
        step = barW + gap;
        minHalf = Ui.dp(context, 1) + 0.5f;
        vPad = Ui.dp(context, 8);
        accent = Ui.attrColor(context, R.attr.cAccent);
        idle = Ui.attrColor(context, R.attr.cStroke);
        primary = Ui.attrColor(context, R.attr.cTextPrimary);
        bad = context.getColor(R.color.bad);
        markPaint.setColor(context.getColor(R.color.warn));
    }

    public void setLive(boolean live) {
        this.liveMode = live;
        this.lastBarAt = SystemClock.uptimeMillis();
        invalidate();
    }

    public void setRunning(boolean r) {
        if (r == running) return;
        long now = SystemClock.uptimeMillis();
        if (!r) {
            frozenFrac = Math.min(1f, (now - lastBarAt) / (float) BAR_MS);
            running = false;
        } else {
            running = true;
            lastBarAt = now - (long) (frozenFrac * BAR_MS);
        }
        invalidate();
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public void setSelectable(boolean selectable) {
        this.selectable = selectable;
        invalidate();
    }

    public void setMinGap(float g) {
        this.minGap = Math.max(0.001f, Math.min(0.4f, g));
    }

    public void setSelection(float a, float b) {
        this.selA = Math.max(0f, Math.min(1f, a));
        this.selB = Math.max(this.selA, Math.min(1f, b));
        invalidate();
    }

    public void setOnSeek(OnSeek l) {
        this.seekListener = l;
    }

    public void setOnSelection(OnSelection l) {
        this.selectionListener = l;
    }

    public void setPeaks(float[] p) {
        this.peaks = p == null ? new float[0] : p;
        invalidate();
    }

    public void setDuration(long ms) {
        this.durationMs = ms;
        invalidate();
    }

    public void setMarkers(List<Long> list) {
        markers.clear();
        if (list != null) markers.addAll(list);
        invalidate();
    }

    public void setProgress(float p) {
        this.progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    private void push(float v) {
        if (liveCount == live.length) {
            System.arraycopy(live, live.length / 2, live, 0, live.length / 2);
            liveCount = live.length / 2;
        }
        live[liveCount++] = Math.max(0f, Math.min(1f, v));
    }

    public void addLevel(float v) {
        float prev = liveCount > 0 ? live[liveCount - 1] : 0f;
        float smooth = v > prev ? prev + (v - prev) * 0.75f : prev + (v - prev) * 0.35f;
        push(smooth);
        long now = SystemClock.uptimeMillis();
        lastBarAt += BAR_MS;
        if (lastBarAt > now || now - lastBarAt > 2L * BAR_MS) lastBarAt = now;
    }

    public void preload(float v) {
        push(v);
    }

    public void syncTime() {
        lastBarAt = SystemClock.uptimeMillis();
    }

    public void clearLevels() {
        liveCount = 0;
        lastBarAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private int barCount(int w) {
        return (int) ((w + gap) / step);
    }

    private float startX(int w, int n) {
        float used = n * step - gap;
        return (w - used) / 2f;
    }

    private float usedWidth(int n) {
        return n * step - gap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int n = barCount(w);
        if (n <= 0) return;
        float cy = h / 2f;
        float maxHalf = Math.max(minHalf, h / 2f - vPad);
        float used = usedWidth(n);
        float sx = startX(w, n);

        if (liveMode) {
            drawLive(canvas, w, h, n, cy, maxHalf, used, sx);
            return;
        }

        for (int i = 0; i < n; i++) {
            float v = 0.04f;
            if (peaks.length > 0) {
                int from = (int) Math.floor((double) i * peaks.length / n);
                int to = (int) Math.ceil((double) (i + 1) * peaks.length / n);
                if (to <= from) to = from + 1;
                if (to > peaks.length) to = peaks.length;
                float m = 0f;
                for (int k = from; k < to; k++) m = Math.max(m, peaks[k]);
                v = Math.max(0.04f, m);
            }
            float half = Math.max(minHalf, v * maxHalf);
            float mid = (i + 0.5f) / n;
            int color;
            if (selectable) color = (mid >= selA && mid <= selB) ? accent : idle;
            else color = mid <= progress ? accent : idle;
            barPaint.setColor(color);
            float x = sx + i * step;
            rect.set(x, cy - half, x + barW, cy + half);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
        }

        if (!selectable && durationMs > 0) {
            float r = Ui.dp(getContext(), 3);
            for (long m : markers) {
                float fx = sx + used * Math.max(0f, Math.min(1f, (float) m / durationMs));
                canvas.drawCircle(fx, r + 1, r, markPaint);
            }
        }

        if (interactive || (selectable && progress > 0f)) {
            headPaint.setColor(primary);
            float px = sx + used * progress;
            float hw = Ui.dp(getContext(), 1);
            rect.set(px - hw, vPad / 2f, px + hw, h - vPad / 2f);
            canvas.drawRoundRect(rect, hw, hw, headPaint);
        }

        if (selectable) drawHandles(canvas, h, sx, used);
    }

    private void drawLive(Canvas canvas, int w, int h, int n, float cy, float maxHalf, float used, float sx) {
        float frac;
        if (running) {
            long now = SystemClock.uptimeMillis();
            frac = Math.max(0f, Math.min(1f, (now - lastBarAt) / (float) BAR_MS));
        } else {
            frac = frozenFrac;
        }
        float shift = frac * step;
        float fade = Math.max(1f, w * 0.18f);
        for (int j = 0; j < n; j++) {
            int idx = liveCount - 1 - j;
            float x = sx + (n - 1 - j) * step - shift;
            float a = Math.max(0f, Math.min(1f, (x + barW) / fade));
            float v = idx >= 0 ? live[idx] : 0f;
            float half = Math.max(minHalf, v * maxHalf);
            barPaint.setColor(idx >= 0 ? accent : idle);
            barPaint.setAlpha((int) (255 * a));
            rect.set(x, cy - half, x + barW, cy + half);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
        }
        barPaint.setAlpha(255);
        headPaint.setColor(bad);
        float hw = Ui.dp(getContext(), 1);
        float hx = sx + used + gap / 2f + hw;
        rect.set(hx - hw, vPad / 2f, hx + hw, h - vPad / 2f);
        canvas.drawRoundRect(rect, hw, hw, headPaint);
        if (running) postInvalidateOnAnimation();
    }

    private void drawHandles(Canvas canvas, int h, float sx, float used) {
        float hw = Ui.dp(getContext(), 3) / 2f;
        float knob = Ui.dp(getContext(), 7);
        headPaint.setColor(accent);
        float xa = sx + used * selA;
        float xb = sx + used * selB;
        rect.set(xa - hw, 0, xa + hw, h);
        canvas.drawRoundRect(rect, hw, hw, headPaint);
        rect.set(xb - hw, 0, xb + hw, h);
        canvas.drawRoundRect(rect, hw, hw, headPaint);
        canvas.drawCircle(xa, knob, knob, headPaint);
        canvas.drawCircle(xb, h - knob, knob, headPaint);
    }

    private float fractionAt(float x) {
        int w = getWidth();
        int n = barCount(w);
        if (n <= 0) return 0f;
        float sx = startX(w, n);
        float used = usedWidth(n);
        return Math.max(0f, Math.min(1f, (x - sx) / used));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (liveMode) return super.onTouchEvent(e);
        if (selectable) return handleSelection(e);
        if (!interactive || seekListener == null) return super.onTouchEvent(e);
        float f = fractionAt(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                progress = f;
                invalidate();
                seekListener.onSeek(f, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                progress = f;
                invalidate();
                seekListener.onSeek(f, false);
                return true;
            case MotionEvent.ACTION_UP:
                progress = f;
                invalidate();
                seekListener.onSeek(f, true);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                seekListener.onSeek(progress, true);
                return true;
            default:
                return true;
        }
    }

    private boolean handleSelection(MotionEvent e) {
        if (selectionListener == null) return false;
        int w = getWidth();
        int n = barCount(w);
        if (n <= 0) return false;
        float sx = startX(w, n);
        float used = usedWidth(n);
        float f = fractionAt(e.getX());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float da = Math.abs(e.getX() - (sx + used * selA));
                float db = Math.abs(e.getX() - (sx + used * selB));
                if (Math.min(da, db) > Ui.dp(getContext(), 36)) return false;
                activeHandle = da <= db ? 0 : 1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (activeHandle == 0) selA = Math.max(0f, Math.min(f, selB - minGap));
                else if (activeHandle == 1) selB = Math.min(1f, Math.max(f, selA + minGap));
                invalidate();
                selectionListener.onSelection(selA, selB, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = -1;
                selectionListener.onSelection(selA, selB, true);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
