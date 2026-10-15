package com.rec.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class WaveformView extends View {

    public interface OnSeek {
        void onSeek(float fraction, boolean finished);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float barW;
    private final float gap;
    private final float minHalf;
    private final float vPad;

    private float[] peaks = new float[0];
    private float[] live = new float[1024];
    private int liveCount;
    private boolean liveMode;
    private boolean interactive;
    private float progress;
    private long durationMs;
    private final List<Long> markers = new ArrayList<>();
    private OnSeek seekListener;
    private final int accent;
    private final int idle;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        barW = Ui.dp(context, 3);
        gap = Ui.dp(context, 2);
        minHalf = Ui.dp(context, 1) + 0.5f;
        vPad = Ui.dp(context, 6);
        accent = Ui.attrColor(context, R.attr.cAccent);
        idle = Ui.attrColor(context, R.attr.cStroke);
        markPaint.setColor(context.getColor(R.color.warn));
        headPaint.setColor(Ui.attrColor(context, R.attr.cTextPrimary));
    }

    public void setLive(boolean live) {
        this.liveMode = live;
        invalidate();
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    public void setOnSeek(OnSeek l) {
        this.seekListener = l;
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

    public void addLevel(float v) {
        if (liveCount == live.length) {
            System.arraycopy(live, live.length / 2, live, 0, live.length / 2);
            liveCount = live.length / 2;
        }
        live[liveCount++] = Math.max(0f, Math.min(1f, v));
    }

    public void clearLevels() {
        liveCount = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float step = barW + gap;
        int n = (int) ((w + gap) / step);
        if (n <= 0) return;
        float cy = h / 2f;
        float maxHalf = Math.max(minHalf, h / 2f - vPad);
        float used = n * step - gap;
        float startX = (w - used) / 2f;

        if (liveMode) {
            for (int i = 0; i < n; i++) {
                int idx = liveCount - n + i;
                float v = idx >= 0 ? live[idx] : 0f;
                float half = Math.max(minHalf, v * maxHalf);
                barPaint.setColor(idx >= 0 ? accent : idle);
                float x = startX + i * step;
                rect.set(x, cy - half, x + barW, cy + half);
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
            }
            headPaint.setColor(getContext().getColor(R.color.bad));
            float hx = startX + used + gap / 2f;
            rect.set(hx - Ui.dp(getContext(), 1), vPad / 2f, hx + Ui.dp(getContext(), 1), h - vPad / 2f);
            canvas.drawRoundRect(rect, Ui.dp(getContext(), 1), Ui.dp(getContext(), 1), headPaint);
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
            barPaint.setColor(mid <= progress ? accent : idle);
            float x = startX + i * step;
            rect.set(x, cy - half, x + barW, cy + half);
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, barPaint);
        }

        if (durationMs > 0) {
            float r = Ui.dp(getContext(), 3);
            for (long m : markers) {
                float fx = startX + used * Math.max(0f, Math.min(1f, (float) m / durationMs));
                canvas.drawCircle(fx, r + 1, r, markPaint);
            }
        }

        if (interactive) {
            headPaint.setColor(Ui.attrColor(getContext(), R.attr.cTextPrimary));
            float px = startX + used * progress;
            float hw = Ui.dp(getContext(), 1);
            rect.set(px - hw, vPad / 2f, px + hw, h - vPad / 2f);
            canvas.drawRoundRect(rect, hw, hw, headPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!interactive || liveMode || seekListener == null) return super.onTouchEvent(e);
        int w = getWidth();
        if (w <= 0) return false;
        float f = Math.max(0f, Math.min(1f, e.getX() / w));
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

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
