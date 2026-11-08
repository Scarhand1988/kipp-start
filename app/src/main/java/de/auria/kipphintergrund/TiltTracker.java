package de.auria.kipphintergrund;

/**
 * Rechnet Sensorwerte (Rotationsvektor) in Kippwerte -1 … 1 um.
 * Reines Java ohne Android-Abhängigkeit, damit es in checks/MotionCheck geprüft werden kann.
 *
 * Neu in 0.3:
 * - Die Ruhestellung folgt der aktuellen Haltung langsam nach (Zeitkonstante FOLLOW_SECONDS).
 *   Hältst du das Handy anders als beim Entsperren, kehrt das Bild in den gewählten Ausschnitt zurück.
 *   Das fängt auch Körperdrehungen und die erlaubte Drift des Game-Rotation-Vectors ab.
 * - Weicht die Bildschirm-Ausrichtung mehr als 60° von der Ruhestellung ab, wird die Ruhestellung
 *   sofort neu gesetzt. So entsteht kein Rand-zu-Rand-Sprung jenseits von 90°.
 *
 * Neu in 0.8:
 * - held(): Neigung gegenüber der Haltung beim Entsperren, ohne Nachführen. Damit bleibt die Unschärfe
 *   auf Wunsch stehen, solange das Handy geneigt ist. Gemessen wird wie beim Kippen selbst (seitlich und
 *   vor/zurück). Eine Drehung des ganzen Körpers mit dem Handy zählt dabei wie seitliches Kippen.
 */
final class TiltTracker {
    static final float FOLLOW_SECONDS = 1.5f;
    /** cos(60°): kleinere Werte bedeuten mehr als 60° Abweichung der Bildschirmnormale. */
    private static final float SNAP_COS = .5f;

    private final float[] ref = new float[4];   // x, y, z, w
    private final float[] cur = new float[4];
    private final float[] refM = new float[9];
    private final float[] curM = new float[9];
    private final float[] rel = new float[9];
    private final float[] baseM = new float[9]; // Lage beim Entsperren
    private final float[] heldRel = new float[9];
    private boolean baseReady;
    private float held;
    private float heldX;
    private float heldY;
    private boolean ready;
    private long lastNanos;
    private float targetX;
    private float targetY;

    /** Neu ansetzen (z. B. wenn der Startbildschirm wieder erscheint). Die Entsperr-Haltung bleibt. */
    void reset() {
        ready = false;
        lastNanos = 0;
        targetX = targetY = 0;
    }

    /** Entsperr-Haltung mit dem nächsten Sensorwert neu festlegen. */
    void resetBase() {
        baseReady = false;
        held = heldX = heldY = 0;
    }

    /** Neigung gegenüber der Entsperr-Haltung, 0 … 1 (ohne Nachführen). */
    float held() { return held; }

    /** Richtung der stehenden Neigung wie x()/y() (-1 … 1), für den Unschärfe-Verlauf. */
    float heldX() { return heldX; }

    float heldY() { return heldY; }

    float x() { return targetX; }

    float y() { return targetY; }

    /**
     * @param values Rotationsvektor wie von Sensor.TYPE_GAME_ROTATION_VECTOR (x, y, z[, w])
     * @param timestampNanos SensorEvent.timestamp
     */
    void update(float[] values, long timestampNanos) {
        float qx = values[0], qy = values[1], qz = values[2], qw;
        if (values.length >= 4) {
            qw = values[3];
        } else {
            qw = 1 - qx * qx - qy * qy - qz * qz;
            qw = qw > 0 ? (float) Math.sqrt(qw) : 0;
        }
        cur[0] = qx; cur[1] = qy; cur[2] = qz; cur[3] = qw;
        normalize(cur);
        matrix(cur, curM);
        updateHeld();

        if (!ready) {
            System.arraycopy(cur, 0, ref, 0, 4);
            ready = true;
            lastNanos = timestampNanos;
            targetX = targetY = 0;
            return;
        }
        float seconds = Math.min(.1f, Math.max(0f, (timestampNanos - lastNanos) / 1_000_000_000f));
        lastNanos = timestampNanos;

        // q und -q beschreiben dieselbe Drehung: auf die nähere Seite bringen, damit das Nachführen stimmt
        if (ref[0] * cur[0] + ref[1] * cur[1] + ref[2] * cur[2] + ref[3] * cur[3] < 0) {
            for (int i = 0; i < 4; i++) cur[i] = -cur[i];
        }

        matrix(ref, refM);
        matrix(cur, curM);  // gleiche Drehung wie vorher, nur mit gespiegeltem Vorzeichen des Quaternions
        // rel = refM^T * curM  (aktuelle Lage, ausgedrückt in der Ruhestellung)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                rel[row * 3 + col] = refM[row] * curM[col]
                        + refM[row + 3] * curM[col + 3]
                        + refM[row + 6] * curM[col + 6];
            }
        }

        if (rel[8] < SNAP_COS) {
            // Mehr als 60° weg von der Ruhestellung: neu ansetzen statt über die 90°-Grenze zu springen
            System.arraycopy(cur, 0, ref, 0, 4);
            targetX = targetY = 0;
            return;
        }

        // Wie SensorManager.getOrientation() für eine 3x3-Matrix
        float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, -rel[7])));
        float roll = (float) Math.atan2(-rel[6], rel[8]);
        targetX = MotionMath.tilt(-roll);
        targetY = MotionMath.tilt(pitch);

        // Ruhestellung langsam der Haltung folgen lassen
        float k = 1f - (float) Math.exp(-seconds / FOLLOW_SECONDS);
        for (int i = 0; i < 4; i++) ref[i] += (cur[i] - ref[i]) * k;
        normalize(ref);
    }

    /** Lage gegenüber der Entsperr-Haltung, wie beim Kippen in seitlich und vor/zurück zerlegt. */
    private void updateHeld() {
        if (!baseReady) {
            System.arraycopy(curM, 0, baseM, 0, 9);
            baseReady = true;
            held = heldX = heldY = 0;
            return;
        }
        relative(baseM, curM, heldRel);
        held = heldTilt(heldRel);
        if (heldRel[8] < SNAP_COS) {
            heldX = heldY = 0; // mehr als 60° weg: Richtung unklar, gleichmäßig unscharf
        } else {
            heldX = MotionMath.tilt(-(float) Math.atan2(-heldRel[6], heldRel[8]));
            heldY = MotionMath.tilt((float) Math.asin(Math.max(-1f, Math.min(1f, -heldRel[7]))));
        }
    }

    /** rel = from^T * to */
    static void relative(float[] from, float[] to, float[] rel) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                rel[row * 3 + col] = from[row] * to[col] + from[row + 3] * to[col + 3] + from[row + 6] * to[col + 6];
            }
        }
    }

    static float heldTilt(float[] rel) {
        if (rel[8] < SNAP_COS) return 1f; // mehr als 60° weg: voll unscharf
        float pitch = (float) Math.asin(Math.max(-1f, Math.min(1f, -rel[7])));
        float roll = (float) Math.atan2(-rel[6], rel[8]);
        return Math.max(Math.abs(MotionMath.tilt(roll)), Math.abs(MotionMath.tilt(pitch)));
    }

    private static void normalize(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-6f) { q[0] = q[1] = q[2] = 0; q[3] = 1; return; }
        for (int i = 0; i < 4; i++) q[i] /= n;
    }

    /** Wie SensorManager.getRotationMatrixFromVector() (3x3, zeilenweise). */
    static void matrix(float[] q, float[] r) {
        float q1 = q[0], q2 = q[1], q3 = q[2], q0 = q[3];
        float sqQ1 = 2 * q1 * q1, sqQ2 = 2 * q2 * q2, sqQ3 = 2 * q3 * q3;
        float q1q2 = 2 * q1 * q2, q3q0 = 2 * q3 * q0, q1q3 = 2 * q1 * q3;
        float q2q0 = 2 * q2 * q0, q2q3 = 2 * q2 * q3, q1q0 = 2 * q1 * q0;
        r[0] = 1 - sqQ2 - sqQ3; r[1] = q1q2 - q3q0;     r[2] = q1q3 + q2q0;
        r[3] = q1q2 + q3q0;     r[4] = 1 - sqQ1 - sqQ3; r[5] = q2q3 - q1q0;
        r[6] = q1q3 - q2q0;     r[7] = q2q3 + q1q0;     r[8] = 1 - sqQ1 - sqQ2;
    }
}
