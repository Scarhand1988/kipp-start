package de.auria.kipphintergrund;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * Zeichnet App-Symbole als abgerundetes Quadrat wie im Video (neu in 0.12).
 * Adaptive Symbole werden selbst zugeschnitten; „Glanzrand“ legt einen hellen Rand von oben auf.
 */
final class IconFactory {
    /** Eckenradius als Anteil der Symbolgröße. */
    static final float CORNER = .225f;

    private IconFactory() {}

    static Bitmap render(Drawable drawable, int size, boolean glow) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float radius = size * CORNER;
        if (drawable instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) drawable;
            Path shape = new Path();
            shape.addRoundRect(new RectF(0, 0, size, size), radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(shape);
            // Ebenen sind 108 dp groß, sichtbar sind die inneren 72 dp: auf jeder Seite 25 % überstehen lassen
            int extra = Math.round(size * .25f);
            Drawable background = adaptive.getBackground();
            Drawable foreground = adaptive.getForeground();
            if (background != null) {
                background.setBounds(-extra, -extra, size + extra, size + extra);
                background.draw(canvas);
            } else {
                canvas.drawColor(Color.WHITE);
            }
            if (foreground != null) {
                foreground.setBounds(-extra, -extra, size + extra, size + extra);
                foreground.draw(canvas);
            }
            canvas.restore();
            if (glow) {
                float stroke = Math.max(1f, size * .025f);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setShader(new LinearGradient(0, 0, 0, size,
                        Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
                RectF rim = new RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2);
                canvas.drawRoundRect(rim, radius - stroke / 2, radius - stroke / 2, paint);
            }
        } else if (drawable != null) {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        }
        return bitmap;
    }
}
