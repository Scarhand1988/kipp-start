package de.auria.kipphintergrund;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Kleine Punkte für die Seiten (neu in 0.12). */
final class PageDots extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final float gap;
    private int count = 1;
    private float position;

    PageDots(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        radius = 3f * density;
        gap = 10f * density;
        paint.setColor(0xFFFFFFFF);
    }

    void set(int count, float position) {
        if (this.count == count && Math.abs(this.position - position) < .001f) return;
        this.count = count;
        this.position = position;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (count <= 1) return;
        float total = (count - 1) * gap;
        float startX = getWidth() / 2f - total / 2f;
        float cy = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float closeness = Math.max(0f, 1f - Math.abs(position - i));
            paint.setAlpha(Math.round(255 * (.35f + .65f * closeness)));
            canvas.drawCircle(startX + i * gap, cy, radius, paint);
        }
    }
}
