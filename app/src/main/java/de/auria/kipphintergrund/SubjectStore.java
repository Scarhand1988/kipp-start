package de.auria.kipphintergrund;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 0.35: Schneidet das Motiv aus dem Foto (Erkennung von Google, läuft auf dem Gerät) und speichert es als PNG.
 * Pro Foto einmal; die Bild-Nummer in den Einstellungen sagt, zu welchem Foto der Ausschnitt gehört.
 */
final class SubjectStore {
    /** Zu diesem Foto gehört der gespeicherte Stand. */
    static final String STAMP = "subject_stamp";
    // 0.36: neuer Name, damit Fotos nach der strengeren Grenze neu geprüft werden
    static final String STATE = "subject_state2";
    /** Ändert sich, sobald ein neuer Ausschnitt fertig ist (der Hintergrund lädt dann neu). */
    static final String MADE = "subject_made";

    static final String OK = "ok";
    static final String NONE = "none";
    static final String FAILED = "failed";

    interface Done {
        void done(String state);
    }

    private static final String FILE = "subject.png";
    private static final int MAX_SIDE = 1600;
    /** Das Erkennungsmodul lädt Google beim ersten Mal nach; so lange wird gewartet und neu versucht. */
    private static final long[] RETRY_MS = {5_000, 15_000, 40_000};

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Done> waiting = new ArrayList<>(); // nur im Haupt-Thread
    private static boolean running;                                  // nur im Haupt-Thread

    private SubjectStore() {}

    /** Gehört der Schlüssel zum Erkennungs-Stand? Solche Werte kommen nicht in die Sicherung. */
    static boolean isStateKey(String key) {
        return STAMP.equals(key) || STATE.equals(key) || MADE.equals(key);
    }

    static String message(String state) {
        if (OK.equals(state)) return "Motiv erkannt – der 3D-Effekt ist bereit.";
        if (NONE.equals(state)) return "Kein passendes Motiv: keins erkannt oder es füllt mehr als das halbe Foto. Wähle eins mit Person, Tier oder Gegenstand im Vordergrund.";
        return "Erkennung noch nicht verfügbar. Google lädt das Modul nach – mit Internet in ein paar Minuten erneut versuchen.";
    }

    private static boolean current(Context context) {
        SharedPreferences p = PhotoStore.preferences(context);
        if (!p.contains(STATE) || p.getLong(STAMP, -1) != p.getLong(PhotoStore.IMAGE, 0)) return false;
        return !OK.equals(p.getString(STATE, "")) || new File(context.getFilesDir(), FILE).exists();
    }

    /** Ausschnitt zum jetzigen Foto, oder null. */
    static Bitmap load(Context context) {
        if (!current(context) || !OK.equals(PhotoStore.preferences(context).getString(STATE, ""))) return null;
        return BitmapFactory.decodeFile(new File(context.getFilesDir(), FILE).getPath());
    }

    /** Erkennung starten, falls zum jetzigen Foto noch nichts vorliegt. done kommt im Haupt-Thread. */
    static void ensure(Context context, Done done) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (current(app)) {
                if (done != null) done.done(PhotoStore.preferences(app).getString(STATE, NONE));
                return;
            }
            if (done != null) waiting.add(done);
            if (running) return;
            running = true;
            attempt(app, 0);
        });
    }

    private static void attempt(Context context, int tried) {
        WORKER.execute(() -> {
            long stamp = PhotoStore.preferences(context).getLong(PhotoStore.IMAGE, 0);
            try {
                Bitmap photo = PhotoStore.load(context, 1);
                if (photo == null) {
                    MAIN.post(() -> finish(FAILED));
                    return;
                }
                float ratio = Math.min(1f, MAX_SIDE / (float) Math.max(photo.getWidth(), photo.getHeight()));
                Bitmap small = ratio < 1f ? Bitmap.createScaledBitmap(photo,
                        Math.max(1, Math.round(photo.getWidth() * ratio)),
                        Math.max(1, Math.round(photo.getHeight() * ratio)), true) : photo;
                SubjectSegmenter segmenter = SubjectSegmentation.getClient(
                        new SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build());
                segmenter.process(InputImage.fromBitmap(small, 0))
                        .addOnSuccessListener(WORKER, result -> {
                            String state = save(context, small, result.getForegroundConfidenceMask(), stamp);
                            segmenter.close();
                            MAIN.post(() -> finish(state));
                        })
                        .addOnFailureListener(WORKER, error -> {
                            segmenter.close();
                            MAIN.post(() -> retry(context, tried));
                        });
            } catch (RuntimeException | OutOfMemoryError e) {
                MAIN.post(() -> retry(context, tried));
            }
        });
    }

    private static void retry(Context context, int tried) {
        if (tried >= RETRY_MS.length) finish(FAILED);
        else MAIN.postDelayed(() -> attempt(context, tried + 1), RETRY_MS[tried]);
    }

    private static void finish(String state) {
        running = false;
        ArrayList<Done> all = new ArrayList<>(waiting);
        waiting.clear();
        for (Done d : all) d.done(state);
    }

    private static String save(Context context, Bitmap photo, FloatBuffer mask, long stamp) {
        SharedPreferences p = PhotoStore.preferences(context);
        if (p.getLong(PhotoStore.IMAGE, 0) != stamp) return FAILED; // inzwischen anderes Foto
        int width = photo.getWidth();
        int height = photo.getHeight();
        String state = NONE;
        if (mask != null) {
            int[] pixels = new int[width * height];
            photo.getPixels(pixels, 0, width, 0, 0, width, height);
            mask.rewind();
            for (int i = 0; i < pixels.length; i++) {
                int alpha = Depth3d.maskAlpha(mask.get());
                pixels[i] = (pixels[i] & 0x00ffffff) | (alpha << 24);
            }
            if (Depth3d.usable(Depth3d.measure(pixels, width, height, new float[4]))) {
                Bitmap cut = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
                AtomicFile file = new AtomicFile(new File(context.getFilesDir(), FILE));
                FileOutputStream out = null;
                try {
                    out = file.startWrite();
                    if (!cut.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG");
                    file.finishWrite(out);
                    state = OK;
                } catch (IOException e) {
                    if (out != null) file.failWrite(out);
                    return FAILED;
                } finally {
                    cut.recycle();
                }
            }
        }
        p.edit().putLong(STAMP, stamp).putString(STATE, state).putLong(MADE, System.currentTimeMillis()).apply();
        return state;
    }
}
