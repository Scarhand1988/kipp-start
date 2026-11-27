package de.auria.kipphintergrund;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

/** Ein App-Symbol mit Beschriftung (neu in 0.12). */
final class IconView extends View {
    AppIndex.App app;
    /** Gesetzt, wenn das Symbol auf einer Startbildschirm-Seite liegt. */
    HomeModel.Item item;
    /** 0 … 3, wenn das Symbol im Dock liegt, sonst -1. */
    int dockSlot = -1;
    private final int iconSize;
    private final float labelGap;
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private boolean showLabel = true;
    // 0.35: Schwebe-Schatten – einmal je Symbol berechnet, beim Kippen nur verschoben
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final int[] shadowOrigin = new int[2];
    private final RectF shadowArea = new RectF();
    private Bitmap shadow;
    private Bitmap shadowSource;
    private float shadowX;
    private float shadowY;
    private int shadowStrength;

    IconView(Context context, int iconSize) {
        super(context);
        this.iconSize = iconSize;
        float density = context.getResources().getDisplayMetrics().density;
        labelGap = 5 * density;
        text.setColor(Color.WHITE);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11.5f, context.getResources().getDisplayMetrics()));
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(3 * density, 0, density, 0x99000000);
        setClickable(true);
        setLongClickable(true);
        setHapticFeedbackEnabled(true);
    }

    void bind(AppIndex.App app, boolean showLabel) {
        this.app = app;
        this.showLabel = showLabel;
        setContentDescription(app == null ? null : app.label);
        invalidate();
    }

    /** Versatz in Pixeln und Deckkraft 0 … 255 (0 = kein Schatten). */
    void setShadow(float x, float y, int strength) {
        if (strength == shadowStrength && Math.abs(x - shadowX) < .5f && Math.abs(y - shadowY) < .5f) return;
        shadowX = x;
        shadowY = y;
        shadowStrength = strength;
        invalidate();
    }

    private void drawShadow(Canvas canvas, float left, float top) {
        if (shadowSource != app.icon) {
            shadowSource = app.icon;
            Paint blur = new Paint();
            blur.setMaskFilter(new BlurMaskFilter(Math.max(1f, app.icon.getWidth() * .08f), BlurMaskFilter.Blur.NORMAL));
            try {
                shadow = app.icon.extractAlpha(blur, shadowOrigin);
            } catch (RuntimeException e) {
                shadow = null;
            }
        }
        if (shadow == null) return;
        float f = iconSize / (float) app.icon.getWidth();
        float x = left + shadowOrigin[0] * f + shadowX;
        float y = top + shadowOrigin[1] * f + shadowY;
        shadowArea.set(x, y, x + shadow.getWidth() * f, y + shadow.getHeight() * f);
        shadowPaint.setColor(shadowStrength << 24);
        canvas.drawBitmap(shadow, null, shadowArea, shadowPaint);
    }

    Bitmap icon() {
        return app == null ? null : app.icon;
    }

    int iconSize() {
        return iconSize;
    }

    /** Oberkante des Symbols in dieser Ansicht. */
    float iconTop() {
        float content = iconSize + (showLabel ? labelGap + text.getTextSize() : 0);
        return Math.max(0, (getHeight() - content) / 2f);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (app == null) return;
        float top = iconTop();
        float left = (getWidth() - iconSize) / 2f;
        float scale = isPressed() ? .9f : 1f;
        canvas.save();
        canvas.scale(scale, scale, getWidth() / 2f, top + iconSize / 2f);
        destination.set(left, top, left + iconSize, top + iconSize);
        if (shadowStrength > 0) drawShadow(canvas, left, top);
        canvas.drawBitmap(app.icon, null, destination, iconPaint);
        canvas.restore();
        if (showLabel) {
            CharSequence label = TextUtils.ellipsize(app.label, text, Math.max(0, getWidth() - 6), TextUtils.TruncateAt.END);
            canvas.drawText(label, 0, label.length(), getWidth() / 2f,
                    top + iconSize + labelGap + text.getTextSize() * .85f, text);
        }
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
