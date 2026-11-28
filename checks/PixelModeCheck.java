package de.auria.kipphintergrund;

/** Prüfungen für PixelMode (neu in 0.45): Kipp-Effekt auf fremdem Startbildschirm (Pixel-Launcher). */
public final class PixelModeCheck {
    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    public static void main(String[] args) {
        String pixel = "com.google.android.apps.nexuslauncher";
        String own = "de.auria.kipphintergrund";

        // Nur der fremde Startbildschirm; Kipp-Start selbst und Auswahl-Dialog nicht
        require(PixelMode.foreignHome(pixel, pixel, own), "Pixel-Launcher vorne");
        require(!PixelMode.foreignHome("com.whatsapp", pixel, own), "Andere App vorne");
        require(!PixelMode.foreignHome("com.android.systemui", pixel, own), "Benachrichtigungen offen");
        require(!PixelMode.foreignHome(own, own, own), "Kipp-Start ist Startbildschirm: kein Umweg");
        require(!PixelMode.foreignHome("android", "android", own), "Kein Standard gewählt");
        require(!PixelMode.foreignHome(null, pixel, own) && !PixelMode.foreignHome(pixel, null, own), "Unbekannt");

        // In Ruhe: zusätzlich Hintergrund sichtbar und nicht weggezoomt (Übersicht, App-Liste, Benachrichtigungen)
        require(PixelMode.homeAtRest(pixel, pixel, own, true, 0f), "Startbildschirm in Ruhe");
        require(!PixelMode.homeAtRest(pixel, pixel, own, false, 0f), "Hintergrund verdeckt (Übersicht)");
        require(!PixelMode.homeAtRest(pixel, pixel, own, true, .4f), "Hintergrund gezoomt");
        require(!PixelMode.homeAtRest("com.whatsapp", pixel, own, true, 0f), "App vorne");

        long t = 100_000;
        // Foto: nur gerade, vorne, entsperrt, Fenster weg, nach Ruhezeit, im Abstand
        require(PixelMode.shouldCapture(t, 0, 0, 0f, true, true, false), "Gerade und ruhig: Foto");
        require(!PixelMode.shouldCapture(t, 0, 0, .2f, true, true, false), "Gekippt: kein Foto (Effekt wäre drauf)");
        require(!PixelMode.shouldCapture(t, 0, 0, 0f, false, true, false), "Andere App: kein Foto");
        require(!PixelMode.shouldCapture(t, 0, 0, 0f, true, false, false), "Gesperrt: kein Foto");
        require(!PixelMode.shouldCapture(t, 0, 0, 0f, true, true, true), "Fenster sichtbar: kein Foto");
        require(!PixelMode.shouldCapture(t, 0, t - 100, 0f, true, true, false), "Gerade berührt: erst warten");
        require(PixelMode.shouldCapture(t, 0, t - PixelMode.SETTLE_MS, 0f, true, true, false), "Nach der Ruhezeit: Foto");
        long busy = t - 1000;
        require(!PixelMode.shouldCapture(t, t - 300, busy, 0f, true, true, false), "Höchstens alle 0,7 s");
        require(PixelMode.shouldCapture(t, t - PixelMode.CAPTURE_INTERVAL_MS, busy, 0f, true, true, false), "Kurz nach Änderung: alle 0,7 s");
        require(!PixelMode.shouldCapture(t, t - 2000, 0, 0f, true, true, false), "In Ruhe seltener");
        require(PixelMode.shouldCapture(t, t - PixelMode.IDLE_INTERVAL_MS, 0, 0f, true, true, false), "In Ruhe alle 5 s");
        require(PixelMode.CAPTURE_INTERVAL_MS >= 334, "Nicht schneller als Android erlaubt");

        // Fenster: nur mit frischem Foto, das nach der letzten Änderung entstand
        require(PixelMode.shouldShow(t, .5f, true, true, true, t - 1000, t - 5000), "Gekippt: Effekt");
        require(!PixelMode.shouldShow(t, .01f, true, true, true, t - 1000, 0), "Fast gerade: kein Fenster");
        require(!PixelMode.shouldShow(t, .5f, true, true, false, 0, 0), "Ohne Foto nichts");
        require(!PixelMode.shouldShow(t, .5f, true, true, true, t - 1000, t - 500), "Nach Wischen altes Foto verworfen");
        require(!PixelMode.shouldShow(t, .5f, false, true, true, t - 1000, 0), "App vorne: kein Fenster");
        require(!PixelMode.shouldShow(t, .5f, true, false, true, t - 1000, 0), "Gesperrt: kein Fenster");
        require(!PixelMode.shouldShow(t, .5f, true, true, true, t - PixelMode.MAX_FRAME_AGE_MS - 1, 0), "Altes Foto: kein Fenster");
        require(PixelMode.SHOW_SOFT > PixelMode.CAPTURE_MAX_SOFT, "Foto nur, wenn das Fenster sicher weg ist");

        System.out.println("PixelModeCheck: " + passed + " Prüfungen bestanden");
    }
}
