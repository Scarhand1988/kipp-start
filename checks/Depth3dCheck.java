package de.auria.kipphintergrund;

/** Prüfungen für Depth3d (neu in 0.35): Motiv-Maske, Parallax und Schwebe-Schatten. */
public final class Depth3dCheck {
    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    public static void main(String[] args) {
        // Maske: sicherer Hintergrund durchsichtig, sicheres Motiv deckend, dazwischen weich
        require(Depth3d.maskAlpha(0f) == 0 && Depth3d.maskAlpha(.3f) == 0, "Hintergrund ganz durchsichtig");
        require(Depth3d.maskAlpha(.7f) == 255 && Depth3d.maskAlpha(1f) == 255, "Motiv ganz deckend");
        require(Math.abs(Depth3d.maskAlpha(.5f) - 128) <= 1, "Mitte halb");
        // Motiv brauchbar: weder fast nichts noch fast alles
        require(!Depth3d.usable(0f) && !Depth3d.usable(.01f), "Kein Motiv");
        require(Depth3d.usable(.3f), "Klares Motiv");
        require(!Depth3d.usable(.95f), "Fast alles ist kein Motiv");
        // Motiv-Rahmen aus der Maske (Anteile 0 … 1)
        int[] pixels = new int[10 * 10];
        pixels[2 * 10 + 3] = 0xff000000;
        pixels[7 * 10 + 5] = 0xff000000;
        float[] box = new float[4];
        float covered = Depth3d.measure(pixels, 10, 10, box);
        require(near(covered, .02f), "Anteil gezählt");
        require(near(box[0], .3f) && near(box[1], .2f) && near(box[2], .6f) && near(box[3], .8f), "Rahmen");
        require(Depth3d.measure(new int[4], 2, 2, box) == 0 && box[2] == 0, "Leere Maske");
        // Parallax: in Ruhe unverändert, mit der Neigung höchstens SHIFT der kurzen Seite
        require(Depth3d.shift(0f, 1000f) == 0, "Gerade: kein Versatz");
        require(near(Depth3d.shift(1f, 1000f), Depth3d.SHIFT * 1000f), "Voller Versatz");
        require(near(Depth3d.shift(-5f, 1000f), -Depth3d.SHIFT * 1000f), "Begrenzt");
        require(Depth3d.scale(0f, 0f, 200f, 300f) == 1f, "Gerade: keine Vergrößerung");
        float s = Depth3d.scale(10f, 5f, 200f, 300f);
        require((s - 1f) * 200f >= 10f - 1e-3f && (s - 1f) * 300f >= 5f - 1e-3f, "Vergrößerung deckt den Versatz");
        require(Depth3d.scale(100f, 0f, 10f, 10f) <= 1f + Depth3d.MAX_GROW + 1e-6f, "Vergrößerung begrenzt");
        // Schatten: gerade unsichtbar, fällt gegen die Neigungsrichtung
        require(Depth3d.shadowAlpha(0f, 0f) == 0, "Gerade: kein Schatten");
        require(Depth3d.shadowAlpha(1f, 0f) == Depth3d.SHADOW_ALPHA, "Voll gekippt: voller Schatten");
        require(Depth3d.shadowAlpha(.2f, 0f) < Depth3d.shadowAlpha(.5f, 0f), "Wächst mit der Neigung");
        require(near(Depth3d.shadowOffset(1f, 20f), -20f) && near(Depth3d.shadowOffset(-.5f, 20f), 10f), "Gegenrichtung");
        // 0.36: Das Motiv darf die Unschärfe nicht verdecken
        require(!Depth3d.usable(.6f), "Großes Motiv wird nicht davorgelegt");
        require(Depth3d.subjectAlpha(0f, 0f, true) == 1f, "Gerade: Motiv deckt voll");
        float a = Depth3d.subjectAlpha(.85f, 0f, true);
        require(a < .75f && a > .5f, "Gekippt scheint die Unschärfe durch, Motiv bleibt klar");
        require(near(Depth3d.subjectAlpha(.85f, 0f, false), .15f), "Nur Parallax: Motiv wird mit weich");
        require(Depth3d.subjectAlpha(0f, 1f, true) == 0f, "Entsperren: ganz weich");
        // 0.37: Hintergrund und Symbole verschwimmen auf derselben Seite und mit demselben Verlauf
        float[] r = new float[3];
        float[] side = new float[2];
        float[] tilts = {-1f, -.6f, -.3f, -.1f, .1f, .3f, .6f, 1f};
        for (boolean flip : new boolean[] {false, true}) {
            for (float tx : tilts) {
                for (float ty : new float[] {0f, .4f, -.8f}) {
                    float bx = Depth3d.blurX(tx, flip);
                    float by = Depth3d.blurY(ty, flip);
                    float m = (float) Math.hypot(bx, by);
                    Effects.blurSide(bx, by, side);
                    Depth3d.ramp(m, r);
                    for (float u = -1f; u <= 1.001f; u += .25f) {
                        for (float v = -1f; v <= 1.001f; v += .5f) {
                            float icon = LauncherMath.depthAmount(u, v, -bx, -by);
                            float wall = Depth3d.rampAmount((u * bx + v * by) / m, r);
                            require(Math.abs(icon - wall) < 2e-3f, "Bild und Symbol gleich unscharf ("
                                    + flip + ", " + tx + ", " + ty + ", " + u + ", " + v + ")");
                        }
                    }
                    if (ty == 0f && Math.abs(tx) >= .3f) {
                        float edge = Math.signum(side[0]);
                        require(LauncherMath.depthAmount(edge, 0f, -bx, -by)
                                > LauncherMath.depthAmount(-edge, 0f, -bx, -by), "Unscharfe Seite des Bildes = unscharfe Symbole");
                    }
                }
            }
        }
        require(Depth3d.blurX(.5f, true) == -.5f && Depth3d.blurY(.5f, false) == .5f, "Umkehren dreht beides");
        float[] vector = new float[2];
        for (float dx : new float[] {-1f, 0f, .6f, 1f}) {
            for (float dy : new float[] {-.8f, 0f, .8f}) {
                float norm = (float) Math.hypot(dx, dy);
                if (norm == 0f) continue;
                float nx = dx / norm, ny = dy / norm;
                Depth3d.rampVector(nx, ny, 1080f, 2400f, vector);
                float lengthSquared = vector[0] * vector[0] + vector[1] * vector[1];
                for (float u : new float[] {-.75f, -.25f, .25f, .75f}) {
                    for (float v : new float[] {-.5f, 0f, .5f}) {
                        float pixelProjection = (u * 540f * vector[0] + v * 1200f * vector[1]) / lengthSquared;
                        require(Math.abs(pixelProjection - (u * nx + v * ny)) < 1e-5f,
                                "Diagonaler Verlauf deckt sich auf dem Pixelformat mit dem Symbolraster");
                    }
                }
            }
        }
        System.out.println(passed + " Prüfungen (Depth3d) bestanden");
    }
}
