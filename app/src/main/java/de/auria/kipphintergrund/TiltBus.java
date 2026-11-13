package de.auria.kipphintergrund;

import android.os.SystemClock;

/**
 * Verbindet Hintergrund und eigenen Startbildschirm (neu in 0.12). Beide laufen im selben Prozess und
 * im Haupt-Thread: Der Hintergrund meldet bei jedem gezeichneten Bild, wie stark gerade gekippt wird,
 * und der Startbildschirm lässt Symbole, Widgets und Dock im selben Bildtakt mit verschwimmen.
 * Ab 0.21 zeichnet der Hintergrund im eigenen Thread: publish() darf aus jedem Thread kommen,
 * der Listener wird immer im Haupt-Thread aufgerufen (gebündelt, höchstens einmal pro Nachricht).
 */
final class TiltBus {
    interface Listener {
        /** soft = Kipp-Unschärfe 0 … 1, dark = Abdunklung 0 … 1, unlock = Entsperr-Animation 1 → 0. */
        void onTilt(float soft, float dark, float unlock);
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile Listener listener;
    private static volatile float soft;
    private static volatile float dark;
    private static volatile float unlock;
    /** 0.30: Neigungsrichtung -1 … 1 (für den Tiefen-Effekt der Symbole). */
    private static volatile float dirX;
    private static volatile float dirY;
    private static volatile long lastPublish;
    private static final Runnable deliver = () -> {
        pending.set(false);
        Listener l = listener;
        if (l != null) l.onTilt(soft, dark, unlock);
    };

    private TiltBus() {}

    static synchronized void publish(float newSoft, float newDark, float newUnlock) {
        publish(newSoft, newDark, newUnlock, 0f, 0f);
    }

    static synchronized void publish(float newSoft, float newDark, float newUnlock, float newDirX, float newDirY) {
        lastPublish = SystemClock.uptimeMillis();
        if (Math.abs(newSoft - soft) < .002f && Math.abs(newDark - dark) < .002f && Math.abs(newUnlock - unlock) < .002f
                && Math.abs(newDirX - dirX) < .004f && Math.abs(newDirY - dirY) < .004f
                && !(newUnlock == 0 && unlock != 0) && !(newSoft == 0 && soft != 0)) {
            return;
        }
        soft = newSoft;
        dark = newDark;
        unlock = newUnlock;
        dirX = newDirX;
        dirY = newDirY;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Listener l = listener;
            if (l != null) l.onTilt(soft, dark, unlock);
        } else if (pending.compareAndSet(false, true)) {
            MAIN.post(deliver);
        }
    }

    private static Runnable neutral;

    /** 0.17: Der Hintergrund meldet hier, wie er die jetzige Haltung als „gerade“ übernimmt. */
    static void setNeutralHandler(Runnable handler) {
        neutral = handler;
    }

    static void clearNeutralHandler(Runnable handler) {
        if (neutral == handler) neutral = null;
    }

    /** Jetzige Haltung als gerade setzen; false, wenn der Kipp-Hintergrund gerade nicht läuft. */
    static boolean requestNeutral() {
        if (neutral == null) return false;
        neutral.run();
        return true;
    }

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onTilt(soft, dark, unlock);
    }

    static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** Wie lange der Hintergrund nichts mehr gemeldet hat (Millisekunden). */
    static long silence() {
        return SystemClock.uptimeMillis() - lastPublish;
    }

    static float soft() { return soft; }

    static float dark() { return dark; }

    static float unlock() { return unlock; }

    static float dirX() { return dirX; }

    static float dirY() { return dirY; }
}
