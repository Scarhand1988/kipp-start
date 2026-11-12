package de.auria.kipphintergrund;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Gravity;
import android.view.Window;
import android.widget.TextView;
import java.util.Locale;

/**
 * Messanzeige für den Startbildschirm (neu in 0.23). Ergänzt die Anzeige des Hintergrunds um die Bilder
 * des Startbildschirms selbst: Bilder pro Sekunde, wie viele davon ihren Termin verpasst haben (ruckelig)
 * und wie lange ein Bild im Schnitt gebraucht hat. Läuft nur, solange die Messanzeige eingeschaltet ist.
 */
final class LauncherMeter implements Window.OnFrameMetricsAvailableListener {
    private final Activity activity;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    // nur im Mess-Thread
    private long windowStart;
    private int frames;
    private int janky;
    private long totalNanos;

    LauncherMeter(Activity activity, TextView view) {
        this.activity = activity;
        this.view = view;
        float density = activity.getResources().getDisplayMetrics().density;
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xAA101418);
        box.setCornerRadius(10 * density);
        view.setBackground(box);
        view.setTextColor(Color.WHITE);
        view.setTextSize(11);
        view.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(8 * density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        view.setText("Start: misst …");
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("kipp-meter");
        thread.start();
        windowStart = 0;
        activity.getWindow().addOnFrameMetricsAvailableListener(this, new Handler(thread.getLooper()));
        view.setVisibility(android.view.View.VISIBLE);
    }

    void stop() {
        if (thread == null) return;
        try {
            activity.getWindow().removeOnFrameMetricsAvailableListener(this);
        } catch (RuntimeException ignored) {}
        thread.quitSafely();
        thread = null;
        view.setVisibility(android.view.View.GONE);
    }

    @Override public void onFrameMetricsAvailable(Window window, FrameMetrics metrics, int dropped) {
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        long deadline = metrics.getMetric(FrameMetrics.DEADLINE);
        frames++;
        totalNanos += total;
        if (LauncherMath.janky(total, deadline)) janky++;
        janky += dropped; // vom System verworfene Messungen zählen als verpasst
        long span = now - windowStart;
        if (span >= 1_000_000_000L) {
            String text = String.format(Locale.GERMANY, "Start: %d B/s · %d ruckelig · Ø %.1f ms",
                    Math.round(frames * 1e9 / span), janky, frames == 0 ? 0f : totalNanos / 1e6f / frames);
            frames = 0;
            janky = 0;
            totalNanos = 0;
            windowStart = now;
            main.post(() -> view.setText(text));
        }
    }
}
