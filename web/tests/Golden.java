package de.auria.kipphintergrund;

import java.util.Locale;

/**
 * Erzeugt Referenzwerte der Android-Rechenlogik für web/tests/check.mjs.
 * Läuft im Workflow pages.yml zusammen mit den Android-Klassen MotionMath, TiltTracker, SoftFocus, Effects
 * und DrawMath, damit die iPhone-Web-App immer gegen den aktuellen Android-Stand geprüft wird.
 */
public final class Golden {
    static StringBuilder out = new StringBuilder();
    static String f(double v) { return String.format(Locale.ROOT, "%.7g", v); }

    static float[] quat(double alpha, double beta, double gamma) {
        double x = Math.toRadians(beta), y = Math.toRadians(gamma), z = Math.toRadians(alpha);
        double cX = Math.cos(x / 2), cY = Math.cos(y / 2), cZ = Math.cos(z / 2);
        double sX = Math.sin(x / 2), sY = Math.sin(y / 2), sZ = Math.sin(z / 2);
        return new float[] {
            (float) (sX * cY * cZ - cX * sY * sZ),
            (float) (cX * sY * cZ + sX * cY * sZ),
            (float) (cX * cY * sZ + sX * sY * cZ),
            (float) (cX * cY * cZ - sX * sY * sZ) };
    }

    public static void main(String[] args) {
        out.append("{\n\"tracker\": [\n");
        TiltTracker t = new TiltTracker();
        long nanos = 1_000_000_000L;
        int steps = 420;
        for (int i = 0; i < steps; i++) {
            double s = i / 60.0;
            // Handy aufrecht gehalten (beta ~ 70°), dazu seitlich und vor/zurück kippen, langsame Drehung
            double alpha = 15 * Math.sin(s * 0.7) + (i > 300 ? 80 : 0);
            double beta = 68 + 14 * Math.sin(s * 2.1) + (i > 200 && i < 230 ? 75 : 0);
            double gamma = 18 * Math.sin(s * 1.3 + 0.4);
            float[] q = quat(alpha, beta, gamma);
            nanos += 16_666_667L + (i % 7 == 0 ? 9_000_000L : 0);
            t.update(q, nanos);
            out.append(String.format(Locale.ROOT, "[%s,%s,%s,%s,%d,%s,%s,%s,%s,%s]%s\n",
                f(q[0]), f(q[1]), f(q[2]), f(q[3]), nanos,
                f(t.x()), f(t.y()), f(t.held()), f(t.heldX()), f(t.heldY()), i < steps - 1 ? "," : ""));
            if (i == 260) t.resetBase();
            if (i == 330) t.reset();
        }
        out.append("],\n\"cover\": [\n");
        float[] b = new float[4];
        float[][] cases = {
            {1179, 2556, 3024, 4032, 1f, 0f, 0f, 0f, 0f, 1.1f},
            {1179, 2556, 3024, 4032, 1.3f, .5f, -.4f, .8f, -1f, 1.1f},
            {1179, 2556, 4032, 3024, 1f, -1f, 1f, -1f, 1f, 1.1f},
            {828, 1792, 900, 1800, 1f, 0f, 0f, 1f, 1f, 1f},
            {828, 1792, 600, 600, 1.7f, 1f, 1f, -.3f, .6f, 1.1f}};
        for (int i = 0; i < cases.length; i++) {
            float[] c = cases[i];
            MotionMath.cover(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], b);
            out.append("[");
            for (float v : c) out.append(f(v)).append(',');
            out.append(f(b[0])).append(',').append(f(b[1])).append(',').append(f(b[2])).append(',').append(f(b[3]));
            out.append("]").append(i < cases.length - 1 ? "," : "").append("\n");
        }
        out.append("],\n\"curves\": [\n");
        for (int i = 0; i <= 20; i++) {
            float m = i / 20f;
            out.append(String.format(Locale.ROOT, "[%s,%s,%s,%s,%s,%s,%s,%s]%s\n", f(m),
                f(SoftFocus.alpha(m, .7f)), f(SoftFocus.alpha(m, .3f)), f(Effects.frostDim(m)),
                f(Effects.unlockAmount(m * 500)), f(DrawMath.secondLayer(m * .4f, m)),
                f(MotionMath.tilt((float) Math.toRadians(m * 30 - 5))), f(SoftFocus.radius(m)),
                i < 20 ? "," : ""));
        }
        out.append("],\n\"blur\": ");
        int w = 23, h = 17;
        int[] px = new int[w * h];
        long seed = 12345;
        for (int i = 0; i < px.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            px[i] = (int) (seed >>> 32) | 0xff000000;
        }
        StringBuilder src = new StringBuilder();
        for (int i = 0; i < px.length; i++) src.append(px[i]).append(i < px.length - 1 ? "," : "");
        SoftFocus.blur(px, w, h, 4, 3);
        StringBuilder dst = new StringBuilder();
        for (int i = 0; i < px.length; i++) dst.append(px[i]).append(i < px.length - 1 ? "," : "");
        out.append("{\"w\":").append(w).append(",\"h\":").append(h).append(",\"radius\":4,\"passes\":3,\"src\":[")
           .append(src).append("],\"dst\":[").append(dst).append("]}\n}\n");
        System.out.print(out);
    }
}
