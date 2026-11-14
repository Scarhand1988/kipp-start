package de.auria.kipphintergrund;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class PhotoStore {
    static final String IMAGE = "image_revision";
    static final String ZOOM = "zoom";
    static final String X = "x";
    static final String Y = "y";
    static final String FOCUS = "focus"; // bis 0.5: weicher Fokus an/aus (wird für die Übernahme noch gelesen)
    /** Unschärfe-Stärke 0 … 1 (neu in 0.6). 0 = aus. */
    static final String BLUR = "blur_strength";
    /** Kipp-Effekt an/aus (Schnellschalter). Aus = Bild steht still und scharf wie ein normaler Hintergrund. */
    static final String ENABLED = "enabled";
    /** Neu in 0.7: Milchglas beim Kippen, Entsperr-Animation, Unschärfe bei Kontrollzentrum/Apps (je an/aus). */
    static final String FROST = "frost";
    static final String UNLOCK = "unlock_animation";
    static final String ZOOM_BLUR = "zoom_blur";
    /** Neu 0.8: Unschärfe bleibt, solange das Handy gegenüber dem Entsperren geneigt ist (Standard: aus). */
    static final String HOLD_BLUR = "hold_blur";
    /** Neu 0.8: Unschärfe beim Wischen zwischen Startbildschirm-Seiten (Standard: an). */
    static final String PAGE_BLUR = "page_blur";
    /** Neu 0.11: Messanzeige (Bilder pro Sekunde, Verzögerung) auf dem Hintergrund (Standard: aus). */
    static final String MEASURE = "measure";
    /** 0.33: Bild verschiebt sich beim Kippen mit (aus = Bild steht still wie im Video). */
    static final String SHIFT = "shift";
    /** Ab 0.11 wird das Foto als JPEG gespeichert: deutlich kleiner und schneller zu laden als PNG. */
    private static final String IMAGE_NAME = "photo.jpg";
    /** Bis 0.10 lag das Foto als PNG vor; wird beim ersten Laden einmalig umgewandelt. */
    private static final String LEGACY_NAME = "photo.png";
    private static final int JPEG_QUALITY = 95;

    private PhotoStore() {}

    /**
     * Vergrößerung, mit der Android normale Hintergründe in Ruhe zeigt (config_wallpaperMaxScale,
     * AOSP-Standard 1,10). Live-Hintergründe skaliert das System nicht selbst, daher übernimmt die App das.
     */
    static float systemWallpaperScale() {
        try {
            Resources system = Resources.getSystem();
            int id = system.getIdentifier("config_wallpaperMaxScale", "dimen", "android");
            if (id != 0) {
                float value = system.getFloat(id);
                if (value >= 1f && value <= 1.3f) return value;
            }
        } catch (RuntimeException ignored) {}
        return MotionMath.DEFAULT_SYSTEM_SCALE;
    }

    /** Unschärfe-Stärke; übernimmt den alten An/Aus-Schalter, falls noch kein Wert gesetzt ist. */
    static float blurStrength(SharedPreferences preferences) {
        if (preferences.contains(BLUR)) return SoftFocus.clamp01(preferences.getFloat(BLUR, SoftFocus.DEFAULT_STRENGTH));
        return preferences.getBoolean(FOCUS, true) ? SoftFocus.DEFAULT_STRENGTH : 0f;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
    }

    private static AtomicFile imageFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), IMAGE_NAME));
    }

    private static File legacyFile(Context context) {
        return new File(context.getFilesDir(), LEGACY_NAME);
    }

    static synchronized Bitmap load(Context context, int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        AtomicFile file = imageFile(context);
        if (file.getBaseFile().exists()) {
            try (FileInputStream stream = file.openRead()) {
                return BitmapFactory.decodeStream(stream, null, options);
            } catch (IOException e) {
                return null;
            }
        }
        // Noch nicht umgewandelt: altes PNG lesen
        try (FileInputStream stream = new FileInputStream(legacyFile(context))) {
            return BitmapFactory.decodeStream(stream, null, options);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Einmaliger Umzug: Foto aus 0.10 (PNG) als JPEG speichern und das PNG löschen.
     * Ändert keine Einstellung, damit der Hintergrund nicht neu lädt. Läuft im Hintergrund-Thread.
     */
    static synchronized void migrateLegacy(Context context) {
        File legacy = legacyFile(context);
        if (!legacy.exists()) return;
        AtomicFile file = imageFile(context);
        if (file.getBaseFile().exists()) {
            legacy.delete();
            return;
        }
        Bitmap photo;
        try (FileInputStream stream = new FileInputStream(legacy)) {
            photo = BitmapFactory.decodeStream(stream);
        } catch (IOException e) {
            return;
        }
        if (photo == null) return;
        try {
            write(file, photo);
            legacy.delete();
        } catch (IOException | RuntimeException ignored) {
            // Altes PNG bleibt erhalten und wird weiter gelesen
        } finally {
            photo.recycle();
        }
    }

    private static void write(AtomicFile file, Bitmap photo) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            if (!photo.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw new IOException("Das Bild konnte nicht gespeichert werden");
            }
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            file.failWrite(output);
            throw e;
        }
    }

    static synchronized void importImage(Context context, Uri uri) throws IOException {
        Bitmap photo = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float ratio = Math.min(1f, 3200f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                });
        try {
            write(imageFile(context), photo);
        } finally {
            photo.recycle();
        }
        legacyFile(context).delete();
        preferences(context).edit()
                .putLong(IMAGE, System.currentTimeMillis())
                .putFloat(ZOOM, 1f).putFloat(X, 0f).putFloat(Y, 0f)
                .apply();
    }

    /** 0.28: Foto als Bytes für die Sicherung (null, wenn keins da ist). */
    static synchronized byte[] readImageBytes(Context context) {
        AtomicFile file = imageFile(context);
        try {
            if (file.getBaseFile().exists()) return file.readFully();
            File legacy = legacyFile(context);
            return legacy.exists() ? Backup.readFile(legacy) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 0.28: Foto aus einer Sicherung übernehmen. Prüft vorher, ob es sich als Bild lesen lässt. Setzt die Bild-Nummer
     * noch nicht – das macht der Aufrufer, wenn auch die Einstellungen zurückgeschrieben sind.
     */
    static synchronized boolean importImageBytes(Context context, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
        AtomicFile file = imageFile(context);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
            return false;
        }
        legacyFile(context).delete();
        return true;
    }
}
