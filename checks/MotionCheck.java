package de.auria.kipphintergrund;

public final class MotionCheck {
    private static int passed;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    // ---------- Hilfen: Handhaltungen als Drehmatrix (Gerät -> Welt) und Quaternion ----------
    static float[] mul(float[] a, float[] b) {
        float[] r = new float[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            float s = 0;
            for (int k = 0; k < 3; k++) s += a[i * 3 + k] * b[k * 3 + j];
            r[i * 3 + j] = s;
        }
        return r;
    }
    static float[] rx(double deg) { double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a); return new float[] {1,0,0, 0,c,-s, 0,s,c}; }
    static float[] ry(double deg) { double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a); return new float[] {c,0,s, 0,1,0, -s,0,c}; }
    static float[] rz(double deg) { double a = Math.toRadians(deg); float c = (float) Math.cos(a), s = (float) Math.sin(a); return new float[] {c,-s,0, s,c,0, 0,0,1}; }

    /** Matrix -> Rotationsvektor (x, y, z, w), wie ihn der Sensor liefert. */
    static float[] vector(float[] m) {
        double tr = m[0] + m[4] + m[8], w, x, y, z;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = .25 * s; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
            w = (m[7] - m[5]) / s; x = .25 * s; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
            w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = .25 * s; z = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
            w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = .25 * s;
        }
        return new float[] {(float) x, (float) y, (float) z, (float) w};
    }

    interface Pose { float[] at(double seconds); }

    /** Spielt eine Bewegung mit 100 Hz ab und liefert die Kippwerte pro Probe: [i][0]=x, [i][1]=y. */
    static float[][] play(TiltTracker tracker, Pose pose, double seconds) {
        int n = (int) Math.round(seconds * 100);
        float[][] out = new float[n + 1][2];
        for (int i = 0; i <= n; i++) {
            double t = i / 100.0;
            tracker.update(vector(pose.at(t)), Math.round(t * 1e9));
            out[i][0] = tracker.x();
            out[i][1] = tracker.y();
        }
        return out;
    }

    /** Verkleinern durch Mittelwert über factor x factor Pixel (Graustufen im Blaukanal gespiegelt). */
    static int[] downscale(int[] src, int w, int h, int factor) {
        int ow = w / factor, oh = h / factor;
        int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
            int s = 0;
            for (int dy = 0; dy < factor; dy++) for (int dx = 0; dx < factor; dx++) {
                s += src[(y * factor + dy) * w + x * factor + dx] & 255;
            }
            int v = (s + factor * factor / 2) / (factor * factor);
            out[y * ow + x] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        return out;
    }

    static int maxNeighbourStep(int[] px, int w, int h) {
        int max = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = px[y * w + x] & 255;
            if (x + 1 < w) max = Math.max(max, Math.abs(v - (px[y * w + x + 1] & 255)));
            if (y + 1 < h) max = Math.max(max, Math.abs(v - (px[(y + 1) * w + x] & 255)));
        }
        return max;
    }

    static double ramp(double t, double start, double end) {
        if (t <= start) return 0;
        if (t >= end) return 1;
        return (t - start) / (end - start);
    }

    public static void main(String[] args) {
        // ---------- Bestehende Prüfungen aus 0.2 ----------
        require(MotionMath.tilt(0) == 0, "Neutralstellung muss stabil sein");
        require(MotionMath.tilt((float) Math.toRadians(.4)) == 0, "Kleine Sensorabweichungen müssen ausgefiltert werden");
        require(MotionMath.tilt((float) Math.toRadians(14)) > .5, "Kippen darf erst bei großen Winkeln nicht anfangen");
        require(MotionMath.tilt((float) Math.toRadians(65)) == 1, "Starke Neigung muss begrenzt sein");

        float at60 = 0;
        float at90 = 0;
        for (int i = 0; i < 4; i++) at60 = MotionMath.approach(at60, 1, 1f / 60);
        for (int i = 0; i < 6; i++) at90 = MotionMath.approach(at90, 1, 1f / 90);
        require(Math.abs(at60 - at90) < .01, "Glättung muss mit 60 und 90 Hz gleich reagieren");
        require(at90 > .95f, "Nach etwa 67 ms muss die Bewegung fast vollständig angekommen sein");

        float[] bounds = new float[4];
        int[][] sizes = {{1080,2400,1080,2400}, {1080,2400,3000,2000}, {1080,2400,1700,2600}, {2400,1080,900,2000},
                         {1080,2400,3200,2409}, {1080,2400,2409,3200}};
        for (float extra : new float[] {1f, 1.10f}) {
            for (int[] size : sizes) {
                for (float zoom : new float[] {1, 1.5f}) {
                    for (float position : new float[] {-1, 0, 1}) {
                        for (float tilt : new float[] {-1, 0, 1}) {
                            MotionMath.cover(size[0], size[1], size[2], size[3], zoom, position, -position, tilt, -tilt, extra, bounds);
                            require(bounds[0] <= .001f && bounds[1] <= .001f
                                    && bounds[2] >= size[0] - .001f && bounds[3] >= size[1] - .001f,
                                    "Kein leerer Rand bei Neigung oder manueller Position");
                        }
                    }
                }
            }
        }
        MotionMath.cover(1080, 2400, 1080, 2400, 1, 0, 0, 0, 0, bounds);
        require(bounds[0] == 0 && bounds[1] == 0 && bounds[2] == 1080 && bounds[3] == 2400,
                "Ohne Kippbewegung darf kein zusätzlicher Zoom entstehen");
        MotionMath.cover(1080, 2400, 1080, 2400, 1, 0, 0, 1, 0, bounds);
        require((bounds[0] + bounds[2]) / 2 > 550, "Neigung muss das Motiv tatsächlich verschieben");

        // ---------- Neu 0.3: System-Vergrößerung wie beim normalen Hintergrund ----------
        require(Math.abs(MotionMath.systemScale(0, 1.10f) - 1.10f) < 1e-6, "In Ruhe 1,10 wie der normale Hintergrund");
        require(Math.abs(MotionMath.systemScale(1, 1.10f) - 1f) < 1e-6, "Bei geöffneten Benachrichtigungen 1,0");
        require(Math.abs(MotionMath.systemScale(.5f, 1.10f) - 1.05f) < 1e-6, "Dazwischen stufenlos");

        // ---------- Neu 0.3: Kippen ohne Zoom und in beiden Richtungen gleich (A4) ----------
        for (int[] size : sizes) {
            if (size[0] > size[1]) continue; // nur Hochformat-Bildschirm
            for (float zoom : new float[] {1, 1.5f}) {
                for (float position : new float[] {-1, -.5f, 0, .5f, 1}) {
                    MotionMath.cover(size[0], size[1], size[2], size[3], zoom, position, position, 0, 0, 1.10f, bounds);
                    float restWidth = bounds[2] - bounds[0];
                    float restCenterX = (bounds[0] + bounds[2]) / 2;
                    float[] shift = new float[2];
                    int i = 0;
                    for (float tilt : new float[] {-1, 1}) {
                        MotionMath.cover(size[0], size[1], size[2], size[3], zoom, position, position, tilt, tilt, 1.10f, bounds);
                        require(Math.abs((bounds[2] - bounds[0]) - restWidth) < .01f, "Beim Kippen darf nicht gezoomt werden");
                        shift[i++] = (bounds[0] + bounds[2]) / 2 - restCenterX;
                    }
                    require(Math.abs(shift[0] + shift[1]) < .01f && Math.abs(shift[1]) > 20,
                            "Kippen nach links und rechts muss gleich weit reichen");
                }
            }
        }

        // ---------- Neu 0.3: Sensorpfad (A1–A3) ----------
        // A1: Nach dem Entsperren bei 65° das Handy flacher halten (40°) und ruhig lassen
        TiltTracker tracker = new TiltTracker();
        float[][] r = play(tracker, t -> rx(65 - 25 * ramp(t, 1, 1.5)), 12);
        require(r[160][1] > .3f, "Die Änderung der Haltung muss zuerst sichtbar sein");
        require(r[r.length - 1][0] == 0 && r[r.length - 1][1] == 0,
                "Nach 10 s ruhiger neuer Haltung muss das Bild genau im Ausschnitt stehen");

        // A2: Nur der Körper dreht sich um 30° (Handy-Haltung gleich)
        tracker = new TiltTracker();
        r = play(tracker, t -> mul(rz(30 * ramp(t, 1, 2)), rx(65)), 13);
        require(r[r.length - 1][0] == 0 && r[r.length - 1][1] == 0,
                "Nach einer Körperdrehung muss das Bild wieder genau im Ausschnitt stehen");

        // A3: Flach entsperrt, dann zügig bis 120° aufrichten (3° schief) – kein Sprung von Rand zu Rand
        for (double duration : new double[] {1.3, 4, 10}) {
            tracker = new TiltTracker();
            final double d = duration;
            // Ausgangshaltung gerade, die 3° Schräglage kommt erst nach dem Entsperren dazu
            r = play(tracker, t -> mul(rx(120 * ramp(t, .5, .5 + d)), ry(3 * ramp(t, .2, .4))), d + 2);
            float maxStep = 0;
            for (int i = 1; i < r.length; i++) {
                maxStep = Math.max(maxStep, Math.abs(r[i][0] - r[i - 1][0]));
            }
            require(maxStep < .5f, "Kein Sprung über die 90°-Grenze (Dauer " + duration + " s, größter Schritt " + maxStep + ")");
        }

        // Reaktion: seitliches Kippen um 10° in 100 ms muss sofort deutlich ankommen
        tracker = new TiltTracker();
        r = play(tracker, t -> mul(rx(65), ry(10 * ramp(t, 1, 1.1))), 1.2);
        require(Math.abs(r[110][0]) > .35f, "Seitliches Kippen muss sofort ankommen (" + r[110][0] + ")");
        require(r[110][1] == 0, "Seitliches Kippen darf nicht vertikal verschieben");

        // Gehaltene Neigung bleibt zunächst sichtbar (kein zu schnelles Zurückgleiten)
        r = play(tracker, t -> mul(rx(65), ry(10)), .5);
        require(Math.abs(r[r.length - 1][0]) > .2f, "Eine kurz gehaltene Neigung darf nicht sofort verschwinden");

        // ---------- Neu 0.4: weicher Fokus ohne Klötzchen ----------
        // Gleichmäßige Fläche bleibt unverändert
        int[] flat = new int[50 * 40];
        java.util.Arrays.fill(flat, 0xFF336699);
        SoftFocus.blur(flat, 50, 40, SoftFocus.RADIUS, SoftFocus.PASSES);
        boolean same = true;
        for (int c : flat) same &= c == 0xFF336699;
        require(same, "Eine gleichmäßige Fläche darf sich durch den Weichzeichner nicht verändern");

        // Testfoto mit Kanten wie in echten Fotos: Schachbrett aus 40-Pixel-Feldern plus Rauschen, 1600 x 1200
        int pw = 1600, ph = 1200;
        int[] photo = new int[pw * ph];
        java.util.Random random = new java.util.Random(7);
        long sum = 0;
        for (int yy = 0; yy < ph; yy++) for (int xx = 0; xx < pw; xx++) {
            int v = (((xx / 40) + (yy / 40)) % 2 == 0 ? 40 : 215) + random.nextInt(31) - 15;
            photo[yy * pw + xx] = 0xFF000000 | v << 16 | v << 8 | v;
            sum += v;
        }
        // Alt (0.2/0.3): nur auf 1/16 verkleinert
        int[] old = downscale(photo, pw, ph, 16);
        // Neu (0.4): auf 1/4 verkleinert und weichgezeichnet
        int nw = pw / SoftFocus.SCALE, nh = ph / SoftFocus.SCALE;
        int[] soft = downscale(photo, pw, ph, SoftFocus.SCALE);
        SoftFocus.blur(soft, nw, nh, SoftFocus.RADIUS, SoftFocus.PASSES);
        int oldStep = maxNeighbourStep(old, pw / 16, ph / 16);
        int newStep = maxNeighbourStep(soft, nw, nh);
        System.out.println("Weicher Fokus: größter Helligkeitssprung zwischen Nachbarpunkten alt " + oldStep
                + " (je Block ca. 17 Bildschirmpixel), neu " + newStep + " (je Punkt ca. 4 Bildschirmpixel)");
        require(newStep <= 16, "Die weiche Version muss glatt sein, keine sichtbaren Stufen (" + newStep + ")");
        require(newStep * 5 < oldStep, "Die weiche Version muss deutlich glatter sein als in 0.3");
        long softSum = 0;
        for (int c : soft) softSum += c & 255;
        require(Math.abs(softSum / (double) soft.length - sum / (double) photo.length) < 1.5,
                "Der Weichzeichner darf das Bild nicht heller oder dunkler machen");

        // ---------- Neu 0.6: einstellbare Unschärfe ----------
        require(SoftFocus.alpha(1, 0) == 0 && SoftFocus.maxAlpha(0) == 0, "Stärke 0 = keine Unschärfe");
        require(SoftFocus.radius(.4f) == SoftFocus.RADIUS, "Stärke 40 % entspricht der Unschärfe von 0.4");
        require(SoftFocus.radius(SoftFocus.DEFAULT_STRENGTH) > SoftFocus.RADIUS
                && SoftFocus.maxAlpha(SoftFocus.DEFAULT_STRENGTH) > 176f / 255f,
                "Die Voreinstellung muss unschärfer sein als 0.4 (größerer Radius, mehr Deckkraft)");
        require(SoftFocus.maxAlpha(1) == 1f, "Bei Stärke 100 % ist das Bild bei voller Neigung ganz weich");
        float lastAlpha = 0;
        int lastRadius = 0;
        for (int p = 1; p <= 100; p++) {
            float s = p / 100f;
            require(SoftFocus.maxAlpha(s) >= lastAlpha && SoftFocus.radius(s) >= lastRadius,
                    "Mehr Stärke darf nie weniger Unschärfe ergeben");
            lastAlpha = SoftFocus.maxAlpha(s);
            lastRadius = SoftFocus.radius(s);
        }
        require(SoftFocus.alpha(.01f, 1) == 0, "In Ruhe (Totzone) bleibt das Bild scharf");
        require(SoftFocus.alpha(.5f, .7f) < SoftFocus.alpha(1, .7f), "Mehr Neigung ergibt mehr Unschärfe");
        // Auch die stärkste Stufe bleibt glatt (keine Klötzchen) und hält die Helligkeit
        int[] strong = downscale(photo, pw, ph, SoftFocus.SCALE);
        SoftFocus.blur(strong, nw, nh, SoftFocus.radius(1), SoftFocus.PASSES);
        int strongStep = maxNeighbourStep(strong, nw, nh);
        long strongSum = 0;
        for (int c : strong) strongSum += c & 255;
        require(strongStep <= newStep, "Stärkere Unschärfe muss mindestens so glatt sein (" + strongStep + ")");
        require(Math.abs(strongSum / (double) strong.length - sum / (double) photo.length) < 1.5,
                "Auch die stärkste Unschärfe darf die Helligkeit nicht ändern");

        // ---------- Neu 0.7: Effekte nach dem Referenzvideo ----------
        require(Effects.unlockAmount(0) == 1f, "Entsperr-Animation beginnt mit voller Wirkung");
        require(Effects.unlockAmount(Effects.UNLOCK_MS) == 0f, "Entsperr-Animation endet genau in der Ruhelage, scharf");
        float lastUnlock = 1f;
        for (int ms = 0; ms <= 500; ms += 5) {
            float a = Effects.unlockAmount(ms);
            require(a <= lastUnlock && a >= 0f, "Entsperr-Animation läuft nur in eine Richtung (ohne Zurückfedern)");
            lastUnlock = a;
            float scale = 1f + Effects.UNLOCK_SCALE * a;
            require(Effects.UNLOCK_LIFT * a <= (scale - 1f) / 2f + 1e-6f,
                    "Beim Anheben darf oben kein leerer Rand entstehen");
        }
        require(Effects.unlockAmount(Effects.UNLOCK_MS / 2) < .2f, "Nach der halben Zeit ist das Bild schon fast scharf");
        require(Effects.frostDim(0) == 0 && Effects.frostDim(.02f) == 0, "In Ruhe keine Abdunklung");
        require(Effects.frostDim(1) == Effects.FROST_DIM && Effects.frostDim(.5f) < Effects.frostDim(1),
                "Milchglas nimmt mit der Neigung zu");
        require(Effects.dim(0, 0, 0) == 0, "Ohne Effekt keine Abdunklung");
        require(Effects.dim(.1f, 1, 0) == Effects.UNLOCK_DIM && Effects.dim(.5f, 0, 0) == .5f,
                "Bei mehreren Effekten gewinnt die stärkste Abdunklung");
        require(Effects.softAlpha(.2f, 0, .6f) == .6f && Effects.softAlpha(0, 0, 0) == 0 && Effects.softAlpha(1, 1, 1) == 1,
                "Unschärfe: die stärkste Quelle gewinnt, höchstens 100 %");

        // ---------- Neu 0.8: Unschärfe bleibt, solange geneigt ----------
        // Entsperren bei 65°, dann 10° zur Seite kippen und 10 s so halten
        tracker = new TiltTracker();
        float[] heldAt = new float[1301];
        r = new float[1301][2];
        for (int i = 0; i <= 1300; i++) {
            double t = i / 100.0;
            tracker.update(vector(mul(rx(65), ry(10 * ramp(t, 1, 1.1)))), Math.round(t * 1e9));
            heldAt[i] = tracker.held();
            r[i][0] = tracker.x();
        }
        require(heldAt[50] == 0, "Vor dem Kippen keine stehende Unschärfe");
        require(r[1300][0] == 0, "Das Bild selbst kehrt wie bisher in die Mitte zurück");
        require(heldAt[1300] > .35f && Math.abs(heldAt[1300] - heldAt[200]) < .01f,
                "Die Neigung gegenüber dem Entsperren bleibt bestehen (" + heldAt[1300] + ")");
        // Zurück in die Haltung vom Entsperren: wieder scharf
        for (int i = 0; i <= 100; i++) {
            double t = 13 + i / 100.0;
            tracker.update(vector(mul(rx(65), ry(10 * (1 - ramp(t, 13, 13.3))))), Math.round(t * 1e9));
        }
        require(tracker.held() == 0, "Zurückgekippt: keine Unschärfe mehr");
        // Vorne/hinten: von 65° auf 40° flacher halten = volle Unschärfe, solange so gehalten
        tracker = new TiltTracker();
        play(tracker, t -> rx(65 - 25 * ramp(t, 1, 1.5)), 12);
        require(tracker.held() == 1f, "25° flacher gehalten: volle stehende Unschärfe");
        // Weit über 60° weg (Handy hingelegt): voll unscharf, kein Springen über die 90°-Grenze
        tracker = new TiltTracker();
        play(tracker, t -> rx(65 - 65 * ramp(t, 1, 2)), 4);
        require(tracker.held() == 1f, "Hingelegt: voll unscharf");
        // Kleine Sensor-Unruhe in der Entsperr-Haltung bleibt scharf
        tracker = new TiltTracker();
        {
            float maxHeld = 0;
            for (int i = 0; i <= 500; i++) {
                double t = i / 100.0;
                tracker.update(vector(mul(rx(65 + .3 * Math.sin(t * 7)), ry(.3 * Math.cos(t * 5)))), Math.round(t * 1e9));
                maxHeld = Math.max(maxHeld, tracker.held());
            }
            require(maxHeld == 0, "Leichtes Zittern in der Hand löst keine stehende Unschärfe aus (" + maxHeld + ")");
        }
        // Neue Entsperr-Haltung: resetBase übernimmt die aktuelle Lage als Ausgangslage
        tracker = new TiltTracker();
        play(tracker, t -> mul(rx(65), ry(10 * ramp(t, 1, 1.1))), 3);
        require(tracker.held() > .35f, "Vor dem neuen Entsperren noch geneigt");
        tracker.resetBase();
        play(tracker, t -> mul(rx(65), ry(10)), .5);
        require(tracker.held() == 0, "Nach dem Entsperren gilt die neue Haltung als Ausgangslage");

        // ---------- Neu 0.8: Seitenwechsel ----------
        for (float step : new float[] {1f / 3, .5f, 1f / 4}) {
            int pages = Math.round(1f / step) + 1;
            for (int k = 0; k < pages; k++) {
                require(Effects.pageSwipe(Math.min(1f, k * step), step) == 0f, "Auf jeder Seite scharf");
            }
            require(Effects.pageSwipe(step / 2, step) == 1f, "Mitten zwischen zwei Seiten voll weich");
            float last = 0;
            for (int i = 0; i <= 50; i++) {
                float v = Effects.pageSwipe(step * i / 100f, step);
                require(v >= last, "Bis zur Mitte nimmt die Unschärfe stetig zu");
                last = v;
            }
            require(Math.abs(Effects.pageSwipe(step * .9f, step) - Effects.pageSwipe(step * .1f, step)) < 1e-4f,
                    "Hin und zurück gleich");
        }
        require(Effects.pageSwipe(.37f, 0f) == 0 && Effects.pageSwipe(.37f, 1f) == 0
                && Effects.pageSwipe(Float.NaN, .5f) == 0, "Nur eine Seite oder keine Angabe: kein Effekt");
        require(Effects.softAlpha(0, 0, 0, 1) == Effects.PAGE_SOFT && Effects.dim(0, 0, 0, 1) == Effects.PAGE_DIM
                && Effects.softAlpha(.2f, 0, 0, 0) == .2f && Effects.dim(.1f, 0, 0, 0) == .1f,
                "Seitenwechsel fügt sich in die übrigen Effekte ein");

        // ---------- Neu 0.10: Unschärfe auf der Seite, die zum Betrachter kommt; Farben verblassen ----------
        // (nach Standbildern des Videos: die zur Kamera gekippte Seite ist unscharf, die abgewandte scharf)
        // Rechte Kante nach hinten (ry positiv, z zeigt zum Betrachter) = linke Kante kommt nach vorn
        tracker = new TiltTracker();
        play(tracker, t -> mul(rx(65), ry(10 * ramp(t, 1, 1.1))), 1.15);
        float[] side = new float[2];
        Effects.blurSide(tracker.x(), tracker.y(), side);
        require(side[0] < -.99f && Math.abs(side[1]) < .05f,
                "Linke Kante kommt nach vorn: die linke Seite wird unscharf (" + side[0] + ", " + side[1] + ")");
        // Flacher halten: obere Kante geht nach hinten, untere kommt nach vorn
        tracker = new TiltTracker();
        play(tracker, t -> rx(65 - 10 * ramp(t, 1, 1.1)), 1.15);
        Effects.blurSide(tracker.x(), tracker.y(), side);
        require(side[1] > .99f, "Untere Kante kommt nach vorn: die untere Seite wird unscharf (" + side[1] + ")");
        // Stehende Neigung behält ihre Richtung, auch wenn das Bild selbst schon zurückgeglitten ist
        tracker = new TiltTracker();
        play(tracker, t -> mul(rx(65), ry(10 * ramp(t, 1, 1.1))), 1.15);
        float startX = tracker.x();
        play(tracker, t -> mul(rx(65), ry(10)), 10);
        require(tracker.x() == 0 && Math.signum(tracker.heldX()) == Math.signum(startX) && Math.abs(tracker.heldY()) < .05f,
                "Die stehende Unschärfe kennt weiter die Seite, die nach vorn gekippt ist");
        Effects.blurSide(0, 0, side);
        require(side[0] == 0 && side[1] == 0, "Ohne Neigung kein Verlauf");
        Effects.blurSide(-.3f, .4f, side);
        require(Math.abs(Math.hypot(side[0], side[1]) - 1) < 1e-5, "Richtung ist normiert");
        require(Effects.AWAY_SOFT > 0 && Effects.AWAY_SOFT < 1, "Abgewandte Seite weniger unscharf");
        // Farben verblassen: dunkel statt hell, geeicht an den Messwerten aus dem Video
        require(Effects.FROST_RED < 60 && Effects.FROST_GREEN < 60 && Effects.FROST_BLUE < 60,
                "Beim Kippen wird es dunkel, nicht weiß");
        float full = Effects.frostDim(1);
        float bright = 145 * (1 - full) + Effects.FROST_GREEN * full;
        float darkPx = 19 * (1 - full) + Effects.FROST_GREEN * full;
        require(bright >= 45 && bright <= 65,
                "Helle Stellen fallen wie im Video von etwa 145 auf 50–63 (" + bright + ")");
        require(darkPx <= 26, "Dunkle Stellen bleiben wie im Video dunkel (" + darkPx + ")");
        require(Effects.frostDim(.5f) > .3f, "Schon bei halber Neigung deutlich dunkler, wie im Video");

        // 0.22 – Ruhemodus des Sensors
        float[] still = {0f, 0f, 0f, 1f};
        float half = (float) Math.sin(Math.toRadians(.25) / 2), whole = (float) Math.cos(Math.toRadians(.25) / 2);
        require(MotionMath.quatDelta(still, still) == 0f, "Keine Drehung = keine Bewegung");
        require(!MotionMath.moved(still, new float[] {half, 0f, 0f, whole}), "Zittern unter 0,5° weckt den Sensor nicht");
        float h2 = (float) Math.sin(Math.toRadians(1.5) / 2), w2 = (float) Math.cos(Math.toRadians(1.5) / 2);
        require(MotionMath.moved(still, new float[] {0f, h2, 0f, w2}), "1,5° Kippen weckt ihn sofort");
        require(MotionMath.moved(new float[] {0f, 0f, 0f, -1f}, new float[] {h2, 0f, 0f, w2}),
                "Gleiche Lage mit umgekehrtem Vorzeichen zählt nicht als Sprung, echte Drehung schon");
        require(!MotionMath.moved(new float[] {0f, 0f, 0f, -1f}, still), "q und −q sind dieselbe Lage");
        require(MotionMath.REST_AFTER_MS >= 2000 && MotionMath.REST_INTERVAL_US <= 50_000,
                "Ruhe erst nach ein paar Sekunden, dann noch mindestens 20 Messungen pro Sekunde");

        System.out.println("OK: " + passed + " Prüfungen – Totzone, Glättung, Bildausschnitte mit/ohne System-Vergrößerung, "
                + "kein Zoom beim Kippen, Nachführen der Ruhestellung, Körperdrehung, 90°-Grenze, Reaktionszeit, "
                + "weicher Fokus ohne Klötzchen, einstellbare Unschärfe, "
                + "Milchglas, Entsperr-Animation, Unschärfe bei Kontrollzentrum, "
                + "Unschärfe bleibt solange geneigt, Seitenwechsel, Unschärfe-Verlauf zur vorderen Seite, Farben verblassen.");
    }
}
