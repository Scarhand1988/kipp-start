package de.auria.kipphintergrund;

/**
 * Rechenhilfen fürs Zeichnen, die Einrichtung und die Messanzeige (neu in 0.11).
 * Reines Java ohne Android-Abhängigkeit, damit es in checks/DrawCheck geprüft werden kann.
 */
final class DrawMath {
    /** Größter Zoom in der Einrichtung (wie der Regler: 1,0 bis 1,7). */
    static final float ZOOM_MAX = 1.7f;

    private DrawMath() {}

    /**
     * Unschärfe-Verlauf in zwei Schichten statt mit einer neuen Verlaufsfarbe pro Bild:
     * Schicht 1 deckt überall mit low, Schicht 2 läuft mit einem festen Verlauf von 0 bis zum Ergebnis hier.
     * Zusammen ergibt das an der hinteren Seite low und an der vorderen Seite high, dazwischen linear.
     */
    static float secondLayer(float low, float high) {
        float l = clamp01(low);
        float h = clamp01(high);
        if (h <= l || l >= .999f) return 0f;
        return (h - l) / (1f - l);
    }

    /** Gesamtdeckkraft zweier Schichten übereinander (zum Nachprüfen von secondLayer). */
    static float stacked(float first, float second) {
        return first + (1f - first) * second;
    }

    /** Stufe der Abdunklung 0 … 255 (für einen zwischengespeicherten Farbfilter je Stufe). */
    static int dimLevel(float dark) {
        return Math.round(255f * clamp01(dark));
    }

    /** Faktor des Farbfilters je Farbkanal (0 … 255): so viel bleibt vom Bild. */
    static int dimMultiply(int level) {
        return 255 - level;
    }

    /** Anteil der Abdunkel-Farbe, der addiert wird (0 … 255). */
    static int dimAdd(int level, int color) {
        return Math.round(color * level / 255f);
    }

    /**
     * Wie weit der Ausschnitt in Bildschirmpixeln verschoben werden kann (Position -1 … 1),
     * dieselbe Rechnung wie in MotionMath.cover.
     */
    static float range(float width, float height, float imageWidth, float imageHeight,
                       float zoom, float extraScale, boolean horizontal) {
        float baseScale = Math.max(width / imageWidth, height / imageHeight) * zoom * Math.max(1f, extraScale);
        float shortSide = Math.min(width, height);
        if (horizontal) {
            return Math.max(0, (imageWidth * baseScale - width) * .5f - shortSide * MotionMath.SHIFT_X);
        }
        return Math.max(0, (imageHeight * baseScale - height) * .5f - shortSide * MotionMath.SHIFT_Y);
    }

    /** Ausschnitt mit dem Finger verschieben: das Bild folgt dem Finger, Position bleibt in -1 … 1. */
    static float drag(float position, float deltaPixels, float range) {
        if (range <= 0) return position;
        return Math.max(-1f, Math.min(1f, position + deltaPixels / range));
    }

    /** Zwei-Finger-Zoom, begrenzt auf 1,0 … ZOOM_MAX. */
    static float pinch(float zoom, float factor) {
        if (!(factor > 0)) return zoom;
        return Math.max(1f, Math.min(ZOOM_MAX, zoom * factor));
    }

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Messanzeige: Bilder pro Sekunde während der Bewegung und Alter des Sensorwerts beim Zeichnen.
     * Gezählt wird in Fenstern von einer Sekunde; eine Pause über 100 ms beginnt ein neues Fenster.
     */
    static final class Meter {
        private static final long WINDOW = 1_000_000_000L;
        private static final long GAP = 100_000_000L;
        private long windowStart;
        private long last;
        private int frames;
        private long latencySum;
        private float fps;
        private float latencyMs;
        private boolean hasValue;

        /** Ein gezeichnetes Bild. now und latency in Nanosekunden. */
        void frame(long now, long latency) {
            if (windowStart == 0 || now - last > GAP) {
                windowStart = now;
                frames = 0;
                latencySum = 0;
            }
            last = now;
            frames++;
            latencySum += Math.max(0, latency);
            long span = now - windowStart;
            if (span >= WINDOW && frames > 1) {
                fps = (frames - 1) * 1_000_000_000f / span;
                latencyMs = latencySum / (float) frames / 1_000_000f;
                hasValue = true;
                windowStart = now;
                frames = 1;
                latencySum = Math.max(0, latency);
            }
        }

        boolean hasValue() { return hasValue; }

        float fps() { return fps; }

        float latencyMs() { return latencyMs; }
    }
}
