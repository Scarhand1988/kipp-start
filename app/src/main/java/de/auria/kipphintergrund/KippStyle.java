package de.auria.kipphintergrund;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

/** Gemeinsamer Look „Grafit und Kupfer“ (seit 0.18 im Einstellungs-Bildschirm, ab 0.24 überall im Startbildschirm). */
final class KippStyle {
    static final int BG = 0xFF1E2023;
    static final int SHEET_TOP = 0xFF2C2F33;
    static final int SHEET_BOTTOM = 0xFF24272A;
    static final int INSET = 0xFF15171A;
    static final int LINE = 0xFF34373B;
    static final int TEXT = 0xFFEDE6DC;
    static final int SUB = 0xFF9A948B;
    static final int COPPER = 0xFFD9A066;
    static final int COPPER_LIGHT = 0xFFE7B37C;
    static final int COPPER_DARK = 0xFFB97E43;
    static final int ON_COPPER = 0xFF2B1B08;

    private KippStyle() {}

    static GradientDrawable copper(float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {COPPER_LIGHT, COPPER_DARK});
        d.setCornerRadius(radius);
        return d;
    }

    /** Blatt mit runden oberen Ecken, Grafit-Verlauf. */
    static GradientDrawable sheet(float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {SHEET_TOP, SHEET_BOTTOM});
        d.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        return d;
    }

    /** Eingelassenes Feld (Suche, Reiterleiste). */
    static GradientDrawable inset(float radius, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(INSET);
        d.setCornerRadius(radius);
        d.setStroke(Math.max(1, Math.round(density)), 0x33D9A066);
        return d;
    }

    /** Feine Trennlinie unten. */
    static Drawable divider(float density) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(LINE);
        LayerDrawable layer = new LayerDrawable(new Drawable[] {line});
        layer.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        layer.setLayerHeight(0, Math.max(1, Math.round(density)));
        return layer;
    }
}
