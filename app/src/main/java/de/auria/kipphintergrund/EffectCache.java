package de.auria.kipphintergrund;

import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.util.SparseArray;

/** Weichzeichnen und Abdunkeln für ganze Ansichten (Startbildschirm), je Stufe einmal angelegt (neu in 0.12). */
final class EffectCache {
    private final SparseArray<RenderEffect> effects = new SparseArray<>();

    /** blur in Pixeln (0 … LauncherMath.MAX_BLUR_PX), dim als Stufe 0 … 255. null = kein Effekt. */
    RenderEffect get(int blur, int dim) {
        blur = Math.max(0, Math.min(LauncherMath.MAX_BLUR_PX, blur));
        dim = Math.max(0, Math.min(255, dim));
        if (blur == 0 && dim == 0) return null;
        int key = blur * 256 + dim;
        RenderEffect effect = effects.get(key);
        if (effect != null) return effect;
        RenderEffect blurEffect = blur > 0 ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) : null;
        RenderEffect dimEffect = null;
        if (dim > 0) {
            int keep = DrawMath.dimMultiply(dim);
            dimEffect = RenderEffect.createColorFilterEffect(new LightingColorFilter(Color.rgb(keep, keep, keep), Color.rgb(
                    DrawMath.dimAdd(dim, Effects.FROST_RED),
                    DrawMath.dimAdd(dim, Effects.FROST_GREEN),
                    DrawMath.dimAdd(dim, Effects.FROST_BLUE))));
        }
        if (blurEffect != null && dimEffect != null) effect = RenderEffect.createChainEffect(dimEffect, blurEffect);
        else effect = blurEffect != null ? blurEffect : dimEffect;
        if (effects.size() > 800) effects.clear();
        effects.put(key, effect);
        return effect;
    }
}
