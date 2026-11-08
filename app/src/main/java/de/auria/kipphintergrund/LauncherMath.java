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

    /** Hintergrund-Versatz 0 … 1 für die Seitenposition (Android-Standard für Startbildschirme). */
    static float wallpaperOffset(float scrollX, float width, int pages) {
        if (pages <= 1 || width <= 0) return .5f;
        return clamp01(scrollX / (width * (pages - 1)));
    }
}
