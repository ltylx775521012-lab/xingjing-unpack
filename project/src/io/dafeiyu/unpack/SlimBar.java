package io.dafeiyu.unpack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 细长圆角进度条（解压 / 转换共用） */
public class SlimBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int trackColor, fillColor;
    private float pct = 0f;

    public SlimBar(Context c, int track, int fill) {
        super(c);
        this.trackColor = track;
        this.fillColor = fill;
    }

    public void setColors(int track, int fill) {
        this.trackColor = track;
        this.fillColor = fill;
        invalidate();
    }

    public void set(float p) {
        pct = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = h / 2f;
        paint.setColor(trackColor);
        canvas.drawRoundRect(0, 0, w, h, r, r, paint);
        if (pct > 0.002f) {
            paint.setColor(fillColor);
            canvas.drawRoundRect(0, 0, Math.max(h, w * pct), h, r, r, paint);
        }
    }
}
