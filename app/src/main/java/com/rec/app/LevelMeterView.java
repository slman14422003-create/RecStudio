package com.rec.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public final class LevelMeterView extends View {

    private static final float WARN_AT = 0.78f;
    private static final float CLIP_AT = 0.92f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int track;
    private final int ok;
    private final int warn;
    private final int bad;
    private final int primary;

    private float level;
    private float peak;
    private long peakAt;

    public LevelMeterView(Context context) {
        this(context, null);
    }

    public LevelMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track = Ui.attrColor(context, R.attr.cField);
        ok = Ui.attrColor(context, R.attr.cAccent);
        warn = context.getColor(R.color.warn);
        bad = context.getColor(R.color.bad);
        primary = Ui.attrColor(context, R.attr.cTextHint);
    }

    public void setLevel(float v) {
        float target = Math.max(0f, Math.min(1f, v));
        level = target > level ? level + (target - level) * 0.6f : level + (target - level) * 0.18f;
        long now = SystemClock.uptimeMillis();
        if (level >= peak) {
            peak = level;
            peakAt = now;
        } else if (now - peakAt > 700) {
            peak = Math.max(level, peak - 0.012f);
        }
        invalidate();
    }

    public void reset() {
        level = 0f;
        peak = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        paint.setColor(track);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, paint);

        float fill = level * w;
        if (fill > 0f) {
            canvas.save();
            canvas.clipRect(0, 0, fill, h);
            paint.setColor(ok);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setColor(warn);
            canvas.drawRect(WARN_AT * w, 0, w - r, h, paint);
            paint.setColor(bad);
            canvas.drawRect(CLIP_AT * w, 0, w - r, h, paint);
            canvas.restore();
        }

        if (peak > 0.02f) {
            paint.setColor(peak >= CLIP_AT ? bad : primary);
            float px = Math.min(w - 2f, peak * w);
            rect.set(px - 1.5f, 0, px + 1.5f, h);
            canvas.drawRoundRect(rect, 1.5f, 1.5f, paint);
        }
    }

}
