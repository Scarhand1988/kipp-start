package de.auria.kipphintergrund;

/**
 * Weichzeichner für den weichen Fokus beim Kippen (neu in 0.4).
 * Reines Java ohne Android-Abhängigkeit, damit es in checks/MotionCheck geprüft werden kann.
 *
 * 0.2/0.3 haben das Foto nur auf 1/16 verkleinert und beim Zeichnen wieder hochgezogen.
 * Ein Bildpunkt wurde so zu einem Block von etwa 17 x 17 Bildschirmpixeln ("Lego").
 * Jetzt: 1/4 Größe und drei Durchgänge eines Rechteck-Weichzeichners, zusammen praktisch
 * eine Gauß-Unschärfe (Sigma etwa 6,5 Pixel in 1/4 Größe, also etwa 26 Pixel am Bildschirm).
 * Das Ergebnis ist so glatt, dass beim Hochziehen keine Kanten entstehen.
 */
final class SoftFocus {
    /** Die weiche Version wird in 1/SCALE der Fotogröße berechnet. */
    static final int SCALE = 4;
    /** Radius von Version 0.4 (entspricht Stärke 40 %). */
    static final int RADIUS = 6;
    static final int PASSES = 3;
    /** Voreinstellung für die Unschärfe-Stärke (0 = aus, 1 = stark). Neu in 0.6, stärker als 0.4. */
    static final float DEFAULT_STRENGTH = .7f;

    private SoftFocus() {}

    /**
     * Weichzeichner-Radius (in 1/4 Größe) für eine Stärke von 0 bis 1.
     * 0,4 -> 6 (wie 0.4, ca. 26 px am Bildschirm), 0,7 -> 9 (ca. 38 px), 1 -> 12 (ca. 48 px).
     */
    static int radius(float strength) {
        return Math.round(2 + 10 * clamp01(strength));
    }

    /** Größte Deckkraft der weichen Version bei voller Neigung: 0 = aus, 0,7 -> 85 %, 1 -> 100 %. */
    static float maxAlpha(float strength) {
        float s = clamp01(strength);
        return s <= 0 ? 0 : .5f + .5f * s;
    }

    /** Deckkraft der weichen Version für eine Neigung (0 … 1) und Stärke (0 … 1). */
    static float alpha(float movement, float strength) {
        float m = Math.min(1f, Math.abs(movement));
        if (strength <= 0 || m <= .025f) return 0;
        return maxAlpha(strength) * (float) Math.pow(m, 1.5);
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Zeichnet ARGB-Pixel an Ort und Stelle weich. */
    static void blur(int[] pixels, int width, int height, int radius, int passes) {
        if (radius < 1 || width < 1 || height < 1) return;
        int[] temp = new int[pixels.length];
        for (int p = 0; p < passes; p++) {
            box(pixels, temp, height, width, radius, width, 1);   // waagerecht
            box(temp, pixels, width, height, radius, 1, width);   // senkrecht
        }
    }

    /**
     * Gleitender Mittelwert entlang einer Achse. length = Pixel pro Linie, lines = Anzahl Linien,
     * step = Abstand zweier Pixel in einer Linie, lineStep = Abstand zweier Linien.
     */
    private static void box(int[] src, int[] dst, int lines, int length, int radius, int lineStep, int step) {
        // Aufruf waagerecht: lines = height, length = width, step = 1, lineStep = width
        // Aufruf senkrecht:  lines = width,  length = height, step = width, lineStep = 1
        int window = 2 * radius + 1;
        int half = window / 2;
        int last = length - 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int a = 0, r = 0, g = 0, b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = src[base + clamp(i, last) * step];
                a += c >>> 24; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            for (int i = 0; i < length; i++) {
                dst[base + i * step] = ((a + half) / window) << 24 | ((r + half) / window) << 16
                        | ((g + half) / window) << 8 | ((b + half) / window);
                int out = src[base + clamp(i - radius, last) * step];
                int in = src[base + clamp(i + radius + 1, last) * step];
                a += (in >>> 24) - (out >>> 24);
                r += ((in >> 16) & 255) - ((out >> 16) & 255);
                g += ((in >> 8) & 255) - ((out >> 8) & 255);
                b += (in & 255) - (out & 255);
            }
        }
    }

    private static int clamp(int i, int last) {
        return i < 0 ? 0 : (i > last ? last : i);
    }
}
