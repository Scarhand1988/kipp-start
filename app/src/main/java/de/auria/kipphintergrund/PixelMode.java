package de.auria.kipphintergrund;

/**
 * Kipp-Effekt auf fremdem Startbildschirm, z. B. Pixel-Launcher (neu in 0.45). Reines Java, damit checks/PixelModeCheck
 * die Regeln ohne Handy prüfen kann.
 *
 * Ablauf: Liegt das Handy gerade und ist der fremde Startbildschirm vorne, macht die Bedienungshilfe ab und zu ein
 * Bildschirmfoto. Beim Kippen legt sie dieses Foto als nicht berührbares Fenster über den Bildschirm und zeichnet darauf
 * denselben Effekt wie Kipp-Start (eine Seite scharf, die andere unscharf und dunkler). Tipper gehen durch zum Launcher.
 * Während des Kippens ist der Startbildschirm deshalb ein Standbild.
 */
final class PixelMode {
    /** Abstand zwischen zwei Bildschirmfotos kurz nach einer Änderung. Android erlaubt höchstens etwa drei pro Sekunde. */
    static final long CAPTURE_INTERVAL_MS = 700;
    /** So lange nach einer Änderung gilt der kurze Abstand. */
    static final long BUSY_MS = 3000;
    /** Danach reicht ein Foto alle paar Sekunden (Uhrzeit im Widget) – schont den Akku. */
    static final long IDLE_INTERVAL_MS = 5000;
    /** So ruhig (gerade) muss das Handy für ein Bildschirmfoto sein: sonst wäre der Effekt selbst im Foto. */
    static final float CAPTURE_MAX_SOFT = .01f;
    /** Ab dieser Kipp-Unschärfe erscheint das Effekt-Fenster. */
    static final float SHOW_SOFT = .03f;
    /** Nach Tippen, Wischen oder Fensterwechsel so lange warten, bis das nächste Foto den neuen Stand zeigt. */
    static final long SETTLE_MS = 450;
    /** Älter als das ist ein Foto nicht mehr verlässlich (Uhr, Widgets ändern sich). */
    static final long MAX_FRAME_AGE_MS = 60_000;

    private PixelMode() {}

    /** Darüber zählt der Hintergrund als weggezoomt (Benachrichtigungen, Übersicht, App öffnet). */
    static final float MAX_ZOOM = .05f;

    /** Ist der fremde Startbildschirm vorne? Kipp-Start selbst braucht den Umweg nicht (es zeichnet den Effekt live). */
    static boolean foreignHome(String front, String home, String own) {
        if (front == null || home == null) return false;
        if (home.equals(own) || "android".equals(home)) return false;
        return front.equals(home);
    }

    /**
     * Startbildschirm in Ruhe sichtbar: fremder Launcher vorne UND Kipp-Hintergrund sichtbar und nicht weggezoomt.
     * Übersicht und App-Liste laufen im selben Paket wie der Launcher; dort ist der Hintergrund verdeckt oder gezoomt.
     */
    static boolean homeAtRest(String front, String home, String own, boolean wallpaperVisible, float wallpaperZoom) {
        return foreignHome(front, home, own) && wallpaperVisible && wallpaperZoom <= MAX_ZOOM;
    }

    /** Jetzt ein neues Bildschirmfoto machen? */
    static boolean shouldCapture(long now, long lastCapture, long lastDisturb, float soft,
                                 boolean homeFront, boolean unlocked, boolean overlayShown) {
        if (!homeFront || !unlocked || overlayShown) return false;
        if (soft > CAPTURE_MAX_SOFT) return false;
        if (now - lastDisturb < SETTLE_MS) return false;
        long interval = now - lastDisturb < BUSY_MS ? CAPTURE_INTERVAL_MS : IDLE_INTERVAL_MS;
        return now - lastCapture >= interval;
    }

    /** Effekt-Fenster zeigen? frameTime = Zeitpunkt des Fotos, lastDisturb = letzte Berührung/Änderung. */
    static boolean shouldShow(long now, float soft, boolean homeFront, boolean unlocked,
                              boolean hasFrame, long frameTime, long lastDisturb) {
        if (!homeFront || !unlocked || !hasFrame) return false;
        if (frameTime < lastDisturb) return false; // Foto zeigt einen alten Stand (z. B. andere Seite)
        if (now - frameTime > MAX_FRAME_AGE_MS) return false;
        return soft >= SHOW_SOFT;
    }
}
