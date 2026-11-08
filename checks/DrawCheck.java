package de.auria.kipphintergrund;

/** Prüfungen für DrawMath (neu in 0.11). */
public final class DrawCheck {
    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    public static void main(String[] args) {
        // Unschärfe-Verlauf in zwei Schichten: hinten low, vorne high, dazwischen linear
        float[] values = {0f, .1f, .255f, .3f, .5f, .85f, 1f};
        for (float low : values) {
            for (float high : values) {
                float second = DrawMath.secondLayer(low, high);
                require(second >= 0 && second <= 1, "Zweite Schicht bleibt zwischen 0 und 1");
                if (high > low) {
                    require(Math.abs(DrawMath.stacked(low, second) - high) < 1e-4,
                            "Vorne ergibt sich genau high (" + low + " / " + high + ")");
                    float middle = DrawMath.stacked(low, second * .5f);
                    require(Math.abs(middle - (low + high) / 2) < 1e-4, "Dazwischen linear");
                } else {
                    require(second == 0, "Ohne Verlauf keine zweite Schicht");
                }
                require(Math.abs(DrawMath.stacked(low, 0) - low) < 1e-6, "Hinten bleibt low");
            }
        }

        // Abdunklung als Farbfilter entspricht der bisherigen Abdunkel-Ebene
        int[] channels = {0, 19, 27, 145, 255};
        for (float dark : new float[] {0f, .1f, .3f, .45f, .72f, 1f}) {
            int level = DrawMath.dimLevel(dark);
            for (int channel : channels) {
                for (int color : new int[] {Effects.FROST_RED, Effects.FROST_GREEN, Effects.FROST_BLUE}) {
                    float before = channel * (1 - dark) + color * dark;
                    float after = channel * DrawMath.dimMultiply(level) / 255f + DrawMath.dimAdd(level, color);
                    require(Math.abs(before - after) <= 1.6f,
                            "Farbfilter wie Abdunkel-Ebene (" + dark + ", " + channel + ": " + before + " / " + after + ")");
                    require(after <= 255.5f, "Kein Überlauf");
                }
            }
        }
        require(DrawMath.dimLevel(0) == 0 && DrawMath.dimMultiply(0) == 255, "Ohne Abdunklung bleibt das Bild unverändert");

        // Finger-Verschieben: gleiche Reichweite wie MotionMath.cover
        float[] bounds = new float[4];
        float[][] cases = {{1080, 2400, 3200, 2400, 1f}, {1080, 2400, 1440, 3200, 1.3f}, {400, 890, 800, 600, 1.7f}};
        for (float[] c : cases) {
            for (boolean horizontal : new boolean[] {true, false}) {
                float range = DrawMath.range(c[0], c[1], c[2], c[3], c[4], 1.1f, horizontal);
                for (float position : new float[] {-.5f, 0f, .4f}) {
                    MotionMath.cover(c[0], c[1], c[2], c[3], c[4],
                            horizontal ? position : 0, horizontal ? 0 : position, 0, 0, 1.1f, bounds);
                    float center = horizontal ? (bounds[0] + bounds[2]) / 2 - c[0] / 2 : (bounds[1] + bounds[3]) / 2 - c[1] / 2;
                    require(Math.abs(center - range * position) < .05f, "Reichweite stimmt mit cover überein");
                }
                if (range > 0) {
                    float moved = DrawMath.drag(0, 25, range);
                    require(Math.abs(moved * range - 25) < 1e-3, "Bild folgt dem Finger pixelgenau");
                }
                require(DrawMath.drag(.9f, 1e6f, Math.max(range, 1)) == 1f, "Position bleibt innerhalb des Fotos");
                require(DrawMath.drag(.3f, 50, 0) == .3f, "Ohne Rand keine Verschiebung");
            }
        }
        require(DrawMath.pinch(1f, .5f) == 1f, "Nicht kleiner als bildfüllend");
        require(DrawMath.pinch(1.6f, 2f) == DrawMath.ZOOM_MAX, "Zoom begrenzt");
        require(Math.abs(DrawMath.pinch(1.2f, 1.1f) - 1.32f) < 1e-5, "Zoom folgt den Fingern");
        require(DrawMath.pinch(1.2f, Float.NaN) == 1.2f, "Ungültige Werte ignorieren");

        // Messanzeige: 90 Bilder pro Sekunde mit 8 ms Sensoralter
        DrawMath.Meter meter = new DrawMath.Meter();
        long t = 5_000_000_000L;
        for (int i = 0; i <= 100; i++) meter.frame(t + i * 11_111_111L, 8_000_000L);
        require(meter.hasValue(), "Nach einer Sekunde gibt es einen Messwert");
        require(Math.abs(meter.fps() - 90) < 1, "90 Bilder pro Sekunde erkannt (" + meter.fps() + ")");
        require(Math.abs(meter.latencyMs() - 8) < .01, "Sensoralter erkannt");
        DrawMath.Meter paused = new DrawMath.Meter();
        for (int i = 0; i < 50; i++) paused.frame(t + i * 16_666_667L, 0);
        for (int i = 0; i < 70; i++) paused.frame(t + 3_000_000_000L + i * 16_666_667L, 0);
        require(paused.hasValue() && Math.abs(paused.fps() - 60) < 1, "Pausen zählen nicht als langsame Bilder (" + paused.fps() + ")");

        System.out.println("OK: " + passed + " Prüfungen – Unschärfe-Verlauf in zwei Schichten, Farbfilter-Abdunklung, "
                + "Ausschnitt mit dem Finger, Zwei-Finger-Zoom, Messanzeige.");
    }
}
