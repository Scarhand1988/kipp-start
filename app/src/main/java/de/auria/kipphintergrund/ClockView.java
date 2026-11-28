package de.auria.kipphintergrund;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;
import java.util.Calendar;

/**
 * 0.38: Uhr-Widget wie im Video – helles, abgerundetes Quadrat mit feinem Strichkranz am Rand und großer, schmaler,
 * schwarzer Uhrzeit. Eigene Zeichnung, keine fremden Grafiken; Schrift ist die schmale Android-Systemschrift.
 * Aktualisiert sich einmal pro Minute und nur, solange der Startbildschirm sichtbar ist.
 */
final class ClockView extends View {
    private static final int FACE = Color.rgb(244, 242, 238);
    private static final int TICK = Color.rgb(150, 147, 140);
    private static final int TICK_HOUR = Color.rgb(95, 93, 88);
    private static final int DIGITS = Color.rgb(17, 17, 17);

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float[] point = new float[4];
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final float density;
    private String time = "";
    private boolean running;
    private final Runnable minute = this::refresh;

    ClockView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        face.setColor(FACE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        digits.setColor(DIGITS);
        digits.setTextAlign(Paint.Align.CENTER);
        digits.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        digits.setLetterSpacing(-.02f);
        setContentDescription("Uhr");
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWindowVisibility() == VISIBLE) start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    /** Sparsam: Startbildschirm verdeckt → kein Takt; wieder sichtbar → sofort richtige Zeit. */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) start();
        else stop();
    }

    private void start() {
        running = true;
        refresh();
    }

    private void stop() {
        running = false;
        removeCallbacks(minute);
    }

    private void refresh() {
        removeCallbacks(minute);
        Calendar now = Calendar.getInstance();
        String text = LauncherMath.clockText(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(getContext()));
        if (!text.equals(time)) {
            time = text;
            setContentDescription("Uhr, " + text);
            invalidate();
        }
        if (running) postDelayed(minute, LauncherMath.clockDelay(System.currentTimeMillis()));
    }

    @Override protected void onDraw(Canvas canvas) {
        float inset = 6 * density;
        float side = Math.min(getWidth(), getHeight()) - 2 * inset;
        if (side <= 0) return;
        float left = (getWidth() - side) / 2f;
        float top = (getHeight() - side) / 2f;
        float radius = side * LauncherMath.CLOCK_CORNER;
        box.set(left, top, left + side, top + side);
        canvas.drawRoundRect(box, radius, radius, face);

        // Strichkranz: knapp innerhalb des Randes, Striche zeigen zur Mitte
        float ring = side * .045f;
        float ringSide = side - 2 * ring;
        float ringRadius = Math.max(0f, radius - ring);
        float shortTick = side * .028f;
        float longTick = side * .05f;
        float width = Math.max(1f, side * .007f);
        for (int i = 0; i < LauncherMath.CLOCK_TICKS; i++) {
            LauncherMath.clockTick(i, LauncherMath.CLOCK_TICKS, ringSide, ringRadius, point);
            boolean hour = i % 5 == 0;
            float len = hour ? longTick : shortTick;
            float x = left + ring + point[0];
            float y = top + ring + point[1];
            tick.setColor(hour ? TICK_HOUR : TICK);
            tick.setStrokeWidth(hour ? width * 1.4f : width);
            canvas.drawLine(x, y, x - point[2] * len, y - point[3] * len, tick);
        }

        // Uhrzeit: so groß, dass sie gut drei Viertel der Breite füllt
        digits.setTextSize(100f);
        float measured = digits.measureText(time.isEmpty() ? "00:00" : time);
        float size = measured <= 0 ? side * .4f : 100f * side * LauncherMath.CLOCK_TEXT_WIDTH / measured;
        digits.setTextSize(Math.min(size, side * .5f));
        digits.getFontMetrics(metrics);
        float baseline = top + side / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(time, left + side / 2f, baseline, digits);
    }
}
