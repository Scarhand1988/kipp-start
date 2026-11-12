package de.auria.kipphintergrund;

/**
 * Rechenhilfen für den eigenen Startbildschirm „Kipp-Start“ (neu in 0.12).
 * Reines Java ohne Android-Abhängigkeit, damit es in checks/LauncherCheck geprüft werden kann.
 */
final class LauncherMath {
    static final int COLUMNS = 4;
    static final int ROWS = 6;
    static final int DOCK = 4;
    /** Unschärfe von Symbolen, Widgets und Dock bei voller Kipp-Unschärfe (dp). */
    static final float TILT_BLUR_DP = 18f;
    /** Entsperren wie im Video: Symbole starten 35 % größer, unscharf und unsichtbar. */
    static final float UNLOCK_SCALE = .35f;
    static final float UNLOCK_BLUR_DP = 28f;
    /** Seitenwechsel wie im Video: hereinkommende Seite ist durchscheinend und unscharf. */
    static final float PAGE_BLUR_DP = 16f;
    static final float PAGE_SCALE = .06f;
    /** Größte Unschärfe, die als Effekt zwischengespeichert wird (Pixel). */
    static final int MAX_BLUR_PX = 160;
    /**
     * Absatz beim Blättern (neu in 0.13), im Video gemessen: Die Seite bleibt stehen, bis der Finger
     * 15–18 % der Bildschirmbreite zurückgelegt hat, dann blättert sie von selbst.
     */
    static final float DETENT = .16f;
    /** Federkonstante des Einrastens (1/s), kritisch gedämpft; im Video ca. 0,5 s bis zum Stillstand. */
    static final float SPRING_OMEGA = 12.5f;

    private LauncherMath() {}

    static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Deckkraft einer Seite; offset = Abstand von der Bildschirmmitte in Seitenbreiten. */
    static float pageAlpha(float offset) {
        float d = Math.min(1f, Math.abs(offset));
        return clamp01(1f - d * 1.3f);
    }

    /** Unschärfe einer Seite 0 … 1: schnell unscharf, sobald sie die Mitte verlässt. */
    static float pageBlur(float offset) {
        float d = Math.min(1f, Math.abs(offset));
        return 1f - (1f - d) * (1f - d);
    }

    static float pageScale(float offset) {
        return 1f + PAGE_SCALE * Math.min(1f, Math.abs(offset));
    }

    /** Entsperren: 1 = Anfang (groß), 0 = Ruhelage. */
    static float unlockScale(float amount) {
        return 1f + UNLOCK_SCALE * clamp01(amount);
    }

    static float unlockAlpha(float amount) {
        return clamp01(1f - clamp01(amount) * 1.15f);
    }

    /** Unschärfe in ganzen Pixeln aus Kipp-Unschärfe (0 … 1) und Entsperr-Anteil (0 … 1). */
    static int blurPixels(float tiltSoft, float unlock, float density) {
        float px = (clamp01(tiltSoft) * TILT_BLUR_DP + clamp01(unlock) * UNLOCK_BLUR_DP) * density;
        return Math.max(0, Math.min(MAX_BLUR_PX, Math.round(px)));
    }

    /** Zelle zu einer Position, begrenzt auf 0 … count-1. */
    static int cell(float position, float start, float size, int count) {
        if (!(size > 0)) return 0;
        int c = (int) Math.floor((position - start) / size);
        return Math.max(0, Math.min(count - 1, c));
    }

    /** Zellen für ein Widget: Vorgabe des Widgets, sonst aus der Mindestgröße. */
    static int span(int target, float minPixels, float cellPixels, int max) {
        int s = target > 0 ? target : (int) Math.ceil(minPixels / Math.max(1f, cellPixels) - .1f);
        return Math.max(1, Math.min(max, s));
    }

    static boolean fits(boolean[][] used, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > used.length || y + h > used[0].length) return false;
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                if (used[i][j]) return false;
            }
        }
        return true;
    }

    /** Nächster freier Platz für w × h möglichst nah an (px, py); null = kein Platz. */
    static int[] nearestFree(boolean[][] used, int w, int h, int px, int py) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int y = 0; y < used[0].length; y++) {
            for (int x = 0; x < used.length; x++) {
                if (!fits(used, x, y, w, h)) continue;
                int d = (x - px) * (x - px) + (y - py) * (y - py);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = new int[] {x, y};
                }
            }
        }
        return best;
    }

    /** Erster freier Platz in Lesereihenfolge ab Zeile fromRow; null = kein Platz. */
    static int[] firstFree(boolean[][] used, int w, int h, int fromRow) {
        for (int y = Math.max(0, fromRow); y < used[0].length; y++) {
            for (int x = 0; x < used.length; x++) {
                if (fits(used, x, y, w, h)) return new int[] {x, y};
            }
        }
        return null;
    }

    static int dockSlot(float x, float width) {
        return cell(x, 0, width / DOCK, DOCK);
    }

    /** Zwei Finger zusammengezogen (wie im Video zum Öffnen der Einstellungen). */
    static boolean pinchedIn(float startSpan, float span) {
        return startSpan > 0 && span < startSpan * .78f;
    }

    /** Wischen nach oben öffnet die App-Übersicht: weit genug oder mit Schwung. */
    static boolean opensDrawer(float dy, float velocityY, float threshold) {
        return dy < -threshold || (velocityY < -1200f && dy < -threshold / 3f);
    }

    /** Zielseite nach dem Loslassen: Schwung blättert eine Seite weiter, sonst die nähere Seite. */
    static int snapPage(float scrollX, float width, float velocityX, float flingVelocity, int pages, int startPage) {
        int last = Math.max(0, pages - 1);
        int page;
        if (Math.abs(velocityX) > flingVelocity) {
            page = velocityX < 0 ? startPage + 1 : startPage - 1;
        } else {
            page = width > 0 ? Math.round(scrollX / width) : startPage;
        }
        page = Math.max(startPage - 1, Math.min(startPage + 1, page));
        return Math.max(0, Math.min(last, page));
    }

    /** Absatz: Seitenschritt nach diesem Fingerweg (+1 = nächste Seite, -1 = vorige, 0 = noch nicht ausgerastet). */
    static int detentStep(float dx, float width) {
        if (!(width > 0) || Math.abs(dx) < DETENT * width) return 0;
        return dx < 0 ? 1 : -1;
    }

    /**
     * Fortschritt 0 → 1 einer kritisch gedämpften Feder nach seconds Sekunden.
     * v0 = Anfangsgeschwindigkeit in Strecken pro Sekunde (Fingertempo beim Ausrasten), begrenzt auf
     * 0 … SPRING_OMEGA, damit die Seite nie über das Ziel hinausschießt.
     */
    static float springProgress(float seconds, float v0) {
        if (!(seconds > 0)) return 0f;
        float w = SPRING_OMEGA;
        float v = Math.max(0f, Math.min(w, v0));
        double e = Math.exp(-w * seconds);
        return (float) Math.min(1.0, 1.0 - (1.0 + (w - v) * seconds) * e);
    }

    static boolean springDone(float seconds, float v0) {
        return seconds > .05f && 1f - springProgress(seconds, v0) < .002f;
    }

    /**
     * Neu in 0.15 – gegen Ruckeln: Kipp-Unschärfe und Abdunklung der Symbole erst bei einer sichtbaren
     * Änderung neu setzen. Jede neue Stufe zwingt die Grafik, den ganzen Bildschirm neu weichzuzeichnen;
     * das Zittern der Hand änderte die Stufe vorher in fast jedem Bild.
     */
    static final int BLUR_STEP = 2;   // Pixel
    static final int DIM_STEP = 3;    // von 255 (ca. 1 % Helligkeit)
    /** Antippen geht durch, sobald die einrastende Seite höchstens so viel ihrer Breite vom Ziel entfernt ist. */
    static final float TAP_SETTLED = .06f;

    /** Neuer Wert erst ab step Abstand zum gesetzten; zurück auf 0 immer sofort. */
    static int settle(int applied, int target, int step) {
        if (target <= 0) return 0;
        return Math.abs(target - applied) >= step ? target : applied;
    }

    /** Unschärfe einer Seite beim Blättern in geraden Pixeln (halb so viele verschiedene Effekte). */
    static int pageBlurPixels(float offset, float density) {
        return 2 * Math.round(pageBlur(offset) * PAGE_BLUR_DP * density / 2f);
    }

    /** Seite liegt (teilweise) im Bild; weiter außen ist sie ohnehin unsichtbar (pageAlpha = 0). */
    static boolean pageVisible(float offset) {
        return Math.abs(offset) < 1f;
    }

    /** Einrastende Seite steht praktisch: Antippen soll das Symbol treffen statt die Seite anzuhalten. */
    static boolean tapThrough(float remainingPixels, float width) {
        return width > 0 && Math.abs(remainingPixels) <= TAP_SETTLED * width;
    }

    /** 0.23: Bild hat seinen Termin verpasst (ruckelig). Ohne Termin (0) zählt ein Bild über 1/60 s. */
    static boolean janky(long totalNanos, long deadlineNanos) {
        long limit = deadlineNanos > 0 ? deadlineNanos : 16_666_667L;
        return totalNanos > limit;
    }

    /** Hintergrund-Versatz 0 … 1 für die Seitenposition (Android-Standard für Startbildschirme). */
    static float wallpaperOffset(float scrollX, float width, int pages) {
        if (pages <= 1 || width <= 0) return .5f;
        return clamp01(scrollX / (width * (pages - 1)));
    }

    // ---------- 0.27: Ordner und Widget-Größe ----------

    /** Spalten im geöffneten Ordner: bis 4 Apps 2, bis 9 Apps 3, sonst 4. */
    static int folderColumns(int count) {
        return count <= 4 ? 2 : count <= 9 ? 3 : 4;
    }

    /** Vorschau im Ordner-Symbol: höchstens 4 Mini-Symbole im 2×2-Raster. */
    static int folderPreviewCount(int count) {
        return Math.max(0, Math.min(4, count));
    }

    /** Größte Spanne aus einer Pixel-Obergrenze (0 = keine Grenze). */
    static int maxSpan(float maxPixels, float cellPixels, int max) {
        if (maxPixels <= 0) return max;
        int s = (int) Math.floor(maxPixels / Math.max(1f, cellPixels) + .1f);
        return Math.max(1, Math.min(max, s));
    }

    /**
     * Widget um einen Schritt größer oder kleiner machen (dw, dh je -1, 0 oder 1). Wächst nach rechts und unten.
     * Liefert {w, h} oder null, wenn der Schritt nicht geht (Grenze, Rand oder belegter Platz).
     * {@code used} darf das Widget selbst nicht enthalten.
     */
    static int[] resize(boolean[][] used, int x, int y, int w, int h, int dw, int dh,
            int minW, int minH, int maxW, int maxH) {
        int nw = w + dw;
        int nh = h + dh;
        if (nw == w && nh == h) return null;
        if (nw < Math.max(1, minW) || nh < Math.max(1, minH)) return null;
        if (nw > maxW || nh > maxH) return null;
        if (x + nw > COLUMNS || y + nh > ROWS) return null;
        if (!fits(used, x, y, nw, nh)) return null;
        return new int[] {nw, nh};
    }
}
