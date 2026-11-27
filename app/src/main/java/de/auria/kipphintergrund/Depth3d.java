package de.auria.kipphintergrund;

/**
 * Rechenhilfen für den 3D-Effekt (neu in 0.35): Motiv vor Hintergrund, Parallax und Schwebe-Schatten.
 * Reines Java, damit es in checks/Depth3dCheck geprüft werden kann.
 */
final class Depth3d {
    /** Größter Versatz des Motivs bei voller Neigung, als Anteil der kurzen Bildschirmseite. */
    static final float SHIFT = .018f;
    /** Größte Vergrößerung des Motivs, damit es seine alte Stelle beim Verschieben verdeckt. */
    static final float MAX_GROW = .08f;
    /** Anteil der Abdunklung, den das scharfe Motiv noch abbekommt. */
    static final float SUBJECT_DIM = .25f;
    /** Größte Deckkraft des Icon-Schattens (0 … 255). */
    static final int SHADOW_ALPHA = 150;
    /** Größter Versatz des Icon-Schattens in dp. */
    static final float SHADOW_DP = 7f;

    /** Größter Motiv-Anteil am Foto; darüber würde das Motiv die Unschärfe verdecken. */
    static final float MAX_COVER = .5f;
    /** So stark wird das hervorgehobene Motiv beim Kippen mit weich. */
    static final float SUBJECT_SOFTEN = .4f;

    private Depth3d() {}

    /**
     * Deckkraft des scharfen Motivs über dem Bild: Die Kipp-Unschärfe darunter muss durchscheinen,
     * ohne „hervorheben“ (nur Parallax) ganz im gleichen Maß wie der Hintergrund.
     */
    static float subjectAlpha(float tiltSoft, float evenSoft, boolean highlight) {
        float keep = 1f - clamp(tiltSoft, 0f, 1f) * (highlight ? SUBJECT_SOFTEN : 1f);
        return (1f - clamp(evenSoft, 0f, 1f)) * keep;
    }

    private static float clamp(float v, float low, float high) {
        return Math.max(low, Math.min(high, v));
    }

    /** Deckkraft 0 … 255 aus der Sicherheit der Erkennung (0 … 1), mit weichem Rand. */
    static int maskAlpha(float confidence) {
        return Math.round(255f * clamp((confidence - .3f) / .4f, 0f, 1f));
    }

    static boolean usable(float covered) {
        return covered >= .02f && covered <= MAX_COVER;
    }

    /** Rahmen {links, oben, rechts, unten} in Anteilen 0 … 1; Rückgabe: Anteil der deckenden Bildpunkte. */
    static float measure(int[] pixels, int width, int height, float[] box) {
        int left = width, top = height, right = -1, bottom = -1, count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((pixels[y * width + x] >>> 24) < 128) continue;
                count++;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        if (count == 0) {
            box[0] = box[1] = box[2] = box[3] = 0;
            return 0;
        }
        box[0] = left / (float) width;
        box[1] = top / (float) height;
        box[2] = (right + 1) / (float) width;
        box[3] = (bottom + 1) / (float) height;
        return count / (float) (width * height);
    }

    static float shift(float tilt, float shortSide) {
        return clamp(tilt, -1f, 1f) * SHIFT * shortSide;
    }

    /** Vergrößerung um die Motiv-Mitte, passend zum Versatz (halfWidth/halfHeight = halbe Motivgröße). */
    static float scale(float shiftX, float shiftY, float halfWidth, float halfHeight) {
        float grow = Math.max(Math.abs(shiftX) / Math.max(1f, halfWidth), Math.abs(shiftY) / Math.max(1f, halfHeight));
        return 1f + Math.min(MAX_GROW, grow);
    }

    static int shadowAlpha(float dirX, float dirY) {
        return Math.round(SHADOW_ALPHA * clamp((float) Math.hypot(dirX, dirY) * 1.8f, 0f, 1f));
    }

    static float shadowOffset(float dir, float max) {
        return -clamp(dir, -1f, 1f) * max;
    }
}
