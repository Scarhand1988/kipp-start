package de.auria.kipphintergrund;

/** Prüfungen für LauncherMath (neu in 0.12). */
public final class LauncherCheck {
    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    public static void main(String[] args) {
        // Seitenübergang wie im Video: Mitte klar, Rand durchscheinend und unscharf
        require(LauncherMath.pageAlpha(0) == 1f && LauncherMath.pageBlur(0) == 0f && LauncherMath.pageScale(0) == 1f,
                "Seite in der Mitte ist unverändert");
        require(LauncherMath.pageAlpha(1) == 0f && LauncherMath.pageBlur(1) == 1f, "Seite ganz außen unsichtbar und voll unscharf");
        require(LauncherMath.pageAlpha(-.5f) == LauncherMath.pageAlpha(.5f), "Beide Richtungen gleich");
        float previousAlpha = 2, previousBlur = -1;
        for (float o = 0; o <= 1.0001f; o += .05f) {
            float a = LauncherMath.pageAlpha(o), b = LauncherMath.pageBlur(o);
            require(a <= previousAlpha && b >= previousBlur, "Übergang verläuft gleichmäßig");
            previousAlpha = a;
            previousBlur = b;
        }
        require(LauncherMath.pageBlur(.25f) > .4f, "Schon ein Viertel weg deutlich unscharf (wie im Video)");

        // Entsperren: groß, unsichtbar, unscharf → normal
        require(LauncherMath.unlockScale(1) == 1.35f && LauncherMath.unlockAlpha(1) == 0f, "Entsperren beginnt groß und unsichtbar");
        require(LauncherMath.unlockScale(0) == 1f && LauncherMath.unlockAlpha(0) == 1f, "Entsperren endet in Ruhelage");
        require(LauncherMath.blurPixels(0, 0, 2.625f) == 0, "In Ruhe keine Unschärfe");
        require(LauncherMath.blurPixels(1, 0, 2.625f) == Math.round(18 * 2.625f), "Volle Kipp-Unschärfe");
        require(LauncherMath.blurPixels(5, 5, 10f) == LauncherMath.MAX_BLUR_PX, "Unschärfe begrenzt");

        // Raster
        require(LauncherMath.cell(-50, 0, 100, 4) == 0 && LauncherMath.cell(399, 0, 100, 4) == 3
                && LauncherMath.cell(9999, 0, 100, 4) == 3 && LauncherMath.cell(150, 0, 100, 4) == 1, "Zellen begrenzt");
        require(LauncherMath.cell(5, 0, 0, 4) == 0, "Ohne Größe keine Division durch null");
        require(LauncherMath.span(2, 999, 100, 4) == 2, "Vorgabe des Widgets zählt");
        require(LauncherMath.span(0, 250, 100, 4) == 3 && LauncherMath.span(0, 205, 100, 4) == 2
                && LauncherMath.span(0, 5000, 100, 4) == 4 && LauncherMath.span(0, 0, 100, 4) == 1, "Größe aus Mindestmaß");
        boolean[][] used = new boolean[LauncherMath.COLUMNS][LauncherMath.ROWS];
        require(LauncherMath.fits(used, 0, 0, 4, 6) && !LauncherMath.fits(used, 1, 0, 4, 1) && !LauncherMath.fits(used, 0, 5, 1, 2),
                "Ränder werden beachtet");
        used[0][0] = used[1][0] = used[0][1] = used[1][1] = true; // 2×2-Widget oben links
        require(!LauncherMath.fits(used, 1, 1, 1, 1) && LauncherMath.fits(used, 2, 0, 2, 2), "Belegte Zellen werden beachtet");
        int[] free = LauncherMath.firstFree(used, 1, 1, 0);
        require(free != null && free[0] == 2 && free[1] == 0, "Erster freier Platz in Lesereihenfolge");
        free = LauncherMath.firstFree(used, 1, 1, 2);
        require(free != null && free[0] == 0 && free[1] == 2, "Ab Zeile 2 (Platz für Widgets oben)");
        free = LauncherMath.nearestFree(used, 1, 1, 0, 0);
        require(free != null && (free[0] + free[1] == 2), "Nächster freier Platz neben dem Widget");
        for (int x = 0; x < LauncherMath.COLUMNS; x++) for (int y = 0; y < LauncherMath.ROWS; y++) used[x][y] = true;
        require(LauncherMath.nearestFree(used, 1, 1, 2, 2) == null && LauncherMath.firstFree(used, 1, 1, 0) == null, "Volle Seite erkannt");
        require(LauncherMath.dockSlot(10, 1080) == 0 && LauncherMath.dockSlot(1079, 1080) == 3 && LauncherMath.dockSlot(600, 1080) == 2,
                "Dock-Plätze");

        // Gesten
        require(LauncherMath.pinchedIn(500, 380) && !LauncherMath.pinchedIn(500, 420) && !LauncherMath.pinchedIn(0, 0),
                "Zusammenziehen erkannt");
        require(LauncherMath.opensDrawer(-200, 0, 150) && !LauncherMath.opensDrawer(-100, 0, 150)
                && LauncherMath.opensDrawer(-60, -2000, 150) && !LauncherMath.opensDrawer(200, -2000, 150), "Wischen nach oben");
        require(LauncherMath.snapPage(1080 * 1.6f, 1080, 0, 500, 3, 1) == 2, "Mehr als halb weiter: nächste Seite");
        require(LauncherMath.snapPage(1080 * 1.3f, 1080, 0, 500, 3, 1) == 1, "Weniger als halb: zurück");
        require(LauncherMath.snapPage(1080 * 1.1f, 1080, -2000, 500, 3, 1) == 2, "Schwung nach links blättert vor");
        require(LauncherMath.snapPage(1080 * .9f, 1080, 2000, 500, 3, 1) == 0, "Schwung nach rechts blättert zurück");
        require(LauncherMath.snapPage(0, 1080, 3000, 500, 3, 0) == 0 && LauncherMath.snapPage(2160, 1080, -3000, 500, 3, 2) == 2,
                "Nicht über die erste oder letzte Seite hinaus");
        require(LauncherMath.snapPage(1080 * 2.9f, 1080, 0, 500, 5, 1) == 2, "Höchstens eine Seite pro Wischen");
        require(LauncherMath.wallpaperOffset(0, 1080, 1) == .5f && LauncherMath.wallpaperOffset(1080, 1080, 3) == .5f
                && LauncherMath.wallpaperOffset(2160, 1080, 3) == 1f, "Hintergrund-Versatz");

        // Neu 0.13: Absatz beim Blättern, gemessen im Video (6,85–7,5 s und 21,4–22,0 s)
        float w = 1080;
        require(LauncherMath.detentStep(-.15f * w, w) == 0 && LauncherMath.detentStep(.15f * w, w) == 0,
                "Unter 15 % Fingerweg bleibt die Seite stehen (im Video bis 15–18 %)");
        require(LauncherMath.detentStep(-.18f * w, w) == 1 && LauncherMath.detentStep(.18f * w, w) == -1,
                "Ab 18 % rastet sie aus: Finger nach links = nächste Seite");
        require(LauncherMath.detentStep(-500, 0) == 0, "Ohne Breite kein Blättern");
        require(LauncherMath.springProgress(0, .8f) == 0 && LauncherMath.springProgress(-1, .8f) == 0, "Feder startet bei 0");
        float before = 0;
        for (float t = .01f; t < 1.5f; t += .01f) {
            for (float v : new float[] {0f, .8f, 12.5f, 99f}) {
                float p = LauncherMath.springProgress(t, v);
                require(p >= 0 && p <= 1, "Kein Überschießen über die Zielseite (" + v + ")");
            }
            float p = LauncherMath.springProgress(t, .8f);
            require(p >= before - 1e-6, "Feder läuft gleichmäßig ins Ziel");
            before = p;
        }
        // Messwerte aus dem Video (Anteil des Wegs ab Losblättern, Fingertempo ca. 0,8 Breiten/s)
        float[][] measured = {{.2f, .72f}, {.25f, .83f}, {.3f, .90f}, {.35f, .95f}, {.4f, .97f}, {.45f, .985f}};
        for (float[] m : measured) {
            float p = LauncherMath.springProgress(m[0], .8f);
            require(Math.abs(p - m[1]) < .05f, "Einrasten wie im Video bei " + m[0] + " s (" + p + " statt " + m[1] + ")");
        }
        require(Math.abs(LauncherMath.springProgress(.134f, .8f) - .43f) < .12f, "Schneller Anfang wie im Video");
        require(!LauncherMath.springDone(.4f, .8f) && LauncherMath.springDone(.8f, .8f), "Nach ca. 0,5–0,8 s steht die Seite");

        System.out.println("OK: " + passed + " Prüfungen – Seitenübergang, Entsperren, Raster, Widgets, Dock, Gesten, Blättern, Absatz.");
    }
}
