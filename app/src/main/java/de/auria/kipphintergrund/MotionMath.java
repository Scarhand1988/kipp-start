package de.auria.kipphintergrund;

final class MotionMath {
    private static final float DEAD_ZONE = (float) Math.toRadians(.65);
    private static final float FULL_TILT = (float) Math.toRadians(23);
    /** Maximale Verschiebung beim Kippen als Anteil der kurzen Bildschirmseite. */
    static final float SHIFT_X = .024f;
    static final float SHIFT_Y = .018f;
    /** AOSP-Standard für config_wallpaperMaxScale, falls das System keinen Wert liefert. */
    static final float DEFAULT_SYSTEM_SCALE = 1.10f;

    private MotionMath() {}

    static float tilt(float radians) {
        float magnitude = Math.min(1f, Math.max(0f,
                (Math.abs(radians) - DEAD_ZONE) / (FULL_TILT - DEAD_ZONE)));
        return magnitude == 0 ? 0 : Math.copySign(magnitude, radians);
    }

    // 0.22 – Ruhemodus: liegt das Handy still, liest der Hintergrund den Lagesensor seltener (spart Akku)
    static final long REST_AFTER_MS = 2500;
    static final int REST_INTERVAL_US = 40_000;   // 25 Messungen pro Sekunde statt 100
    static final int ACTIVE_INTERVAL_US = 10_000;
    private static final float MOVE_DELTA = .0065f; // ≈ 0,75° Drehung

    /** Abstand zweier Lage-Quaternionen (x, y, z[, w]); q und −q gelten als gleich. */
    static float quatDelta(float[] a, float[] b) {
        float aw = a.length > 3 ? a[3] : (float) Math.sqrt(Math.max(0, 1 - a[0] * a[0] - a[1] * a[1] - a[2] * a[2]));
        float bw = b.length > 3 ? b[3] : (float) Math.sqrt(Math.max(0, 1 - b[0] * b[0] - b[1] * b[1] - b[2] * b[2]));
        float dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + aw * bw);
        return (float) Math.sqrt(Math.max(0, 1 - Math.min(1f, dot * dot)));
    }

    /** Hat sich das Handy seit der Bezugslage spürbar gedreht? */
    static boolean moved(float[] reference, float[] now) {
        return quatDelta(reference, now) > MOVE_DELTA;
    }

    static float approach(float current, float target, float seconds) {
        float blend = 1f - (float) Math.exp(-seconds / .019f);
        float next = current + (target - current) * blend;
        return Math.abs(next - target) < .0015f ? target : next;
    }

    /**
     * Vergrößerung wie beim normalen Android-Hintergrund: in Ruhe (zoomOut = 0) maxScale,
     * beim Öffnen von Benachrichtigungen oder Apps (zoomOut = 1) 1,0.
     */
    static float systemScale(float zoomOut, float maxScale) {
        float z = Math.min(1f, Math.max(0f, zoomOut));
        return 1f + (Math.max(1f, maxScale) - 1f) * (1f - z);
    }

    /** Ohne System-Vergrößerung (Verhalten wie Version 0.2, für Tests und Vergleiche). */
    static void cover(float width, float height, float imageWidth, float imageHeight,
                      float zoom, float positionX, float positionY, float tiltX, float tiltY,
                      float[] bounds) {
        cover(width, height, imageWidth, imageHeight, zoom, positionX, positionY, tiltX, tiltY, 1f, bounds);
    }

    /**
     * Bildausschnitt. Für die Kippbewegung wird zuerst eine feste Reserve vom verfügbaren Rand
     * abgezogen, damit beide Richtungen gleich weit reichen und das Bild beim Kippen nicht zoomt.
     * Nur wenn das Foto keinen Rand übrig hat, wird als Sicherheitsnetz minimal vergrößert.
     */
    static void cover(float width, float height, float imageWidth, float imageHeight,
                      float zoom, float positionX, float positionY, float tiltX, float tiltY,
                      float extraScale, float[] bounds) {
        float baseScale = Math.max(width / imageWidth, height / imageHeight) * zoom * Math.max(1f, extraScale);
        float shortSide = Math.min(width, height);
        float reserveX = shortSide * SHIFT_X;
        float reserveY = shortSide * SHIFT_Y;
        float rangeX = Math.max(0, (imageWidth * baseScale - width) * .5f - reserveX);
        float rangeY = Math.max(0, (imageHeight * baseScale - height) * .5f - reserveY);
        float x = rangeX * positionX + reserveX * tiltX;
        float y = rangeY * positionY + reserveY * tiltY;
        float scale = Math.max(baseScale, Math.max(
                (width + 2 * Math.abs(x)) / imageWidth,
                (height + 2 * Math.abs(y)) / imageHeight));
        float halfWidth = imageWidth * scale * .5f;
        float halfHeight = imageHeight * scale * .5f;
        float centerX = width * .5f + x;
        float centerY = height * .5f + y;
        bounds[0] = centerX - halfWidth;
        bounds[1] = centerY - halfHeight;
        bounds[2] = centerX + halfWidth;
        bounds[3] = centerY + halfHeight;
    }
}
