package de.auria.kipphintergrund;

/**
 * Zusätzliche Effekte nach dem Referenzvideo (neu in 0.7). Reines Java, damit es geprüft werden kann.
 *
 * - Milchglas beim Kippen: mit der Neigung wird das Bild nicht nur weicher, sondern auch dunkler und grauer.
 * - Entsperr-Animation: das Bild startet vergrößert, etwas tiefer, unscharf und dunkel,
 *   hebt sich in seine Ruhelage und wird dabei scharf.
 * - Unschärfe bei Kontrollzentrum/Apps: stufenlos mit dem Zoom-Wert, den das System meldet.
 *
 * Neu in 0.8:
 * - Seitenwechsel: Beim Wischen zwischen Startbildschirm-Seiten wird das Bild im Takt des Fingers
 *   kurz weich und etwas dunkler und ist auf jeder Seite wieder scharf. Das Bild selbst bleibt stehen.
 *
 * Neu in 0.10 (nachgemessen an Standbildern des Videos):
 * - Unschärfe-Verlauf: Unscharf wird die Seite, die beim Kippen zum Betrachter hin kommt. Die Seite,
 *   die nach hinten geht, bleibt weitgehend scharf.
 * - Farben verblassen: Beim Kippen tritt der Hintergrund dunkel und farblos zurück (kein heller Schleier).
 *   Im Video fallen die hellen Stellen des Hintergrunds von etwa 145 auf 50–63 (von 255), die dunklen
 *   bleiben bei 10–24. Dadurch heben sich die hellen, farbigen Symbole ab.
 */
final class Effects {
    /** Dauer der Entsperr-Animation. */
    static final float UNLOCK_MS = 450f;
    /** Zusätzliche Vergrößerung zu Beginn der Entsperr-Animation (8 %). */
    static final float UNLOCK_SCALE = .08f;
    /** Start der Animation so weit unterhalb der Ruhelage (Anteil der Bildschirmhöhe), dann Anheben. */
    static final float UNLOCK_LIFT = .03f;
    /** Abdunklung zu Beginn der Entsperr-Animation. */
    static final float UNLOCK_DIM = .45f;
    /** Größte Abdunklung bei voller Neigung (Farben verblassen), geeicht an den Messwerten aus dem Video. */
    static final float FROST_DIM = .72f;
    /** Größte Abdunklung bei ganz geöffnetem Kontrollzentrum. */
    static final float ZOOM_DIM = .30f;
    /** Größte Deckkraft der weichen Version mitten im Seitenwechsel. */
    static final float PAGE_SOFT = .85f;
    /** Größte Abdunklung mitten im Seitenwechsel. */
    static final float PAGE_DIM = .22f;
    /** Unschärfe an der nach hinten gekippten Seite im Verhältnis zur Seite, die zum Betrachter kommt. */
    static final float AWAY_SOFT = .30f;
    /** Farbe der Abdunklung: dunkles, leicht warmes Grau, in das die Farben beim Kippen verblassen. */
    static final int FROST_RED = 27, FROST_GREEN = 26, FROST_BLUE = 26;

    private Effects() {}

    /** Weich auslaufen: schnell los, sanft ankommen. */
    static float ease(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        float q = 1f - p;
        return 1f - q * q * q;
    }

    /** Stärke der Entsperr-Animation: 1 am Anfang, 0 am Ende. */
    static float unlockAmount(float elapsedMs) {
        if (elapsedMs <= 0) return 1f;
        return 1f - ease(elapsedMs / UNLOCK_MS);
    }

    /** Abdunklung beim Kippen (Neigung 0 … 1): schon bei mittlerer Neigung deutlich, wie im Video. */
    static float frostDim(float movement) {
        float m = Math.min(1f, Math.abs(movement));
        if (m <= .025f) return 0;
        return FROST_DIM * (float) Math.pow(m, 1.2);
    }

    /**
     * Wie weit der Startbildschirm gerade zwischen zwei Seiten steht: 0 = genau auf einer Seite, 1 = mittendrin.
     * Der Launcher meldet die Seiten als Vielfache von step (z. B. 0, 0,5, 1 bei drei Seiten).
     */
    static float pageSwipe(float offset, float step) {
        if (!(step > 0f && step < 1f) || Float.isNaN(offset)) return 0f;
        float position = Math.max(0f, Math.min(1f, offset)) / step;
        float fraction = position - (float) Math.floor(position);
        float between = 2f * Math.min(fraction, 1f - fraction);
        if (between < .02f) return 0f;            // Rundungsreste auf einer Seite: scharf
        return ease(Math.min(1f, between * 1.6f)); // schon nach einem Drittel des Wegs fast ganz weich
    }

    /**
     * Richtung zur Bildschirmseite, die unscharf wird, in Bildschirm-Koordinaten (x nach rechts, y nach unten):
     * die Seite, die beim Kippen zum Betrachter hin kommt. Aus den Kippwerten wie TiltTracker sie liefert.
     * Kippwert x < 0 heißt: die rechte Kante geht nach hinten, also kommt die linke nach vorn;
     * y > 0: die obere Kante geht nach hinten, also kommt die untere nach vorn.
     * Ergebnis normiert, oder {0, 0} ohne klare Richtung.
     */
    static void blurSide(float tiltX, float tiltY, float[] out) {
        float length = (float) Math.hypot(tiltX, tiltY);
        if (length < .02f) { out[0] = out[1] = 0; return; }
        out[0] = tiltX / length;
        out[1] = tiltY / length;
    }

    /** Größte der Abdunklungen (sie überlagern sich nicht, die stärkste gewinnt). */
    static float dim(float frost, float unlock, float zoom) {
        return Math.max(frost, Math.max(UNLOCK_DIM * unlock, ZOOM_DIM * zoom));
    }

    static float dim(float frost, float unlock, float zoom, float page) {
        return Math.max(dim(frost, unlock, zoom), PAGE_DIM * page);
    }

    /** Deckkraft der weichen Version: die stärkste Quelle gewinnt. */
    static float softAlpha(float tilt, float unlock, float zoom) {
        return Math.min(1f, Math.max(tilt, Math.max(unlock, zoom)));
    }

    static float softAlpha(float tilt, float unlock, float zoom, float page) {
        return Math.min(1f, Math.max(softAlpha(tilt, unlock, zoom), PAGE_SOFT * page));
    }
}
