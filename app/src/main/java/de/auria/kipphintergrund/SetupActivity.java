package de.auria.kipphintergrund;

import android.app.Activity;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public final class SetupActivity extends Activity {
    private static final int PICK_IMAGE = 42;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveCrop = this::saveCrop;
    private SharedPreferences preferences;
    private CropView image;
    private TextView status;
    private Button choose;
    private Button preview;
    private Button adjust;
    private LinearLayout cropOptions;
    private SeekBar horizontal;
    private SeekBar vertical;
    private SeekBar magnification;
    private Switch effect;
    private SeekBar blur;
    private TextView blurLabel;
    private float blurStrength;
    private volatile int previewGeneration;
    private final Runnable saveBlur = this::saveBlur;
    private final Runnable hidePreview = () -> {
        image.previewAlpha = 0;
        image.invalidate();
    };
    private boolean reading;
    private float x;
    private float y;
    private float zoom = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = PhotoStore.preferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(22), dp(24), dp(22));
        scroll.addView(content);

        TextView title = text("Kipp-Hintergrund", 30);
        title.setGravity(Gravity.START);
        content.addView(title);
        content.addView(text("Wähle das Foto, das du schon als Hintergrund nutzt. Beim Kippen bewegt sich nur das Bild hinter deinen Apps.", 16));
        Rect display = getWindowManager().getMaximumWindowMetrics().getBounds();
        float aspect = (float) Math.max(display.height(), display.width()) / Math.min(display.height(), display.width());
        image = new CropView();
        // Größer als bis 0.10, damit sich der Ausschnitt direkt mit dem Finger einstellen lässt
        LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(dp(180), dp(Math.min(400, Math.round(180 * aspect))));
        picture.gravity = Gravity.CENTER_HORIZONTAL;
        picture.setMargins(0, dp(14), 0, dp(10));
        content.addView(image, picture);

        status = text("Wähle dein bisheriges Hintergrundbild.", 14);
        content.addView(status);
        choose = button("Bild auswählen", this::chooseImage);
        content.addView(choose);
        preview = button("Android-Vorschau öffnen", this::openPreview);
        preview.setEnabled(false);
        content.addView(preview);
        adjust = button("Bildausschnitt anpassen", () -> {
            boolean open = cropOptions.getVisibility() != View.VISIBLE;
            cropOptions.setVisibility(open ? View.VISIBLE : View.GONE);
            adjust.setText(open ? "Bildausschnitt schließen" : "Bildausschnitt anpassen");
        });
        adjust.setEnabled(false);
        content.addView(adjust);

        cropOptions = new LinearLayout(this);
        cropOptions.setOrientation(LinearLayout.VERTICAL);
        cropOptions.setVisibility(View.GONE);
        horizontal = slider("Horizontal verschieben", 200, 100, value -> x = (value - 100) / 100f);
        vertical = slider("Vertikal verschieben", 200, 100, value -> y = (value - 100) / 100f);
        magnification = slider("Zoom", 70, 0, value -> zoom = 1f + value / 100f);
        cropOptions.addView(button("Ausschnitt zurücksetzen", () -> {
            horizontal.setProgress(100);
            vertical.setProgress(100);
            magnification.setProgress(0);
            x = y = 0;
            zoom = 1;
            image.invalidate();
            saveCrop();
        }));
        content.addView(cropOptions);

        // Unschärfe beim Kippen: Regler von „aus“ bis „stark“, mit Live-Vorschau bei voller Neigung
        blurStrength = PhotoStore.blurStrength(preferences);
        blurLabel = text(blurText(blurStrength), 16);
        content.addView(blurLabel);
        blur = new SeekBar(this);
        blur.setContentDescription("Unschärfe beim Kippen");
        blur.setMax(100);
        blur.setProgress(Math.round(blurStrength * 100f));
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (!fromUser) return;
                blurStrength = value / 100f;
                blurLabel.setText(blurText(blurStrength));
                showBlurPreview();
                main.removeCallbacks(saveBlur);
                main.postDelayed(saveBlur, 180);
            }
            @Override public void onStartTrackingTouch(SeekBar view) {
                main.removeCallbacks(hidePreview);
                showBlurPreview();
            }
            @Override public void onStopTrackingTouch(SeekBar view) {
                main.removeCallbacks(saveBlur);
                saveBlur();
                main.postDelayed(hidePreview, 1500);
            }
        });
        content.addView(blur);
        content.addView(text("Ganz links = aus. Beim Verschieben zeigt die Vorschau oben, wie verschwommen das Bild bei voller Neigung wird.", 14));
        content.addView(toggle("Unschärfe bleibt, solange geneigt",
                "Aus: Das Bild wird nach etwa einer Sekunde wieder scharf, auch wenn du geneigt bleibst. "
                        + "An: Es bleibt unscharf, bis du in die Haltung vom Entsperren zurückkippst.",
                PhotoStore.HOLD_BLUR, true));

        // Effekte nach dem Referenzvideo (je einzeln an/aus)
        content.addView(toggle("Farben verblassen beim Kippen",
                "Beim Neigen tritt das Bild dunkel und farblos zurück, so heben sich die Symbole ab wie im Video.",
                PhotoStore.FROST));
        content.addView(toggle("Entsperr-Animation",
                "Nach dem Entsperren hebt sich das Bild in seine Ruhelage und wird dabei scharf.", PhotoStore.UNLOCK));
        content.addView(toggle("Unschärfe bei Kontrollzentrum und Apps",
                "Beim Runterziehen und beim Öffnen von Apps verschwimmt das Bild stufenlos mit.", PhotoStore.ZOOM_BLUR));
        content.addView(toggle("Hintergrund beim Seitenwechsel unscharf",
                "Im Video bleibt der Hintergrund beim Blättern scharf (deshalb aus). An: Das Bild verschwimmt beim Wischen "
                        + "zwischen den Seiten kurz. Braucht mindestens zwei Seiten.", PhotoStore.PAGE_BLUR, false));
        content.addView(toggle("Messanzeige",
                "Zeigt oben auf dem Startbildschirm, wie viele Bilder pro Sekunde beim Kippen gezeichnet werden "
                        + "und wie alt der Sensorwert beim Zeichnen ist. Zum Testen – danach wieder ausschalten.",
                PhotoStore.MEASURE, false));

        // Eigener Startbildschirm (neu in 0.12)
        content.addView(text("Eigener Startbildschirm „Kipp-Start“", 18));
        content.addView(text("Damit verschwimmen beim Kippen – wie im Video – auch Symbole, Widgets und Dock, die Seiten "
                + "blenden beim Blättern unscharf ein und die Symbole fliegen nach dem Entsperren herein. Der Pixel-"
                + "Startbildschirm bleibt installiert und lässt sich jederzeit wieder wählen.", 14));
        content.addView(button("Kipp-Start als Startbildschirm wählen", () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException e) {
                status.setText("Android konnte die Auswahl nicht öffnen.");
            }
        }));

        // Kipp-Effekt an/aus – dasselbe wie der Schalter in den Schnelleinstellungen
        effect = new Switch(this);
        effect.setText("Kipp-Effekt an");
        effect.setTextSize(16);
        effect.setMinHeight(dp(48));
        effect.setChecked(preferences.getBoolean(PhotoStore.ENABLED, true));
        effect.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean(PhotoStore.ENABLED, checked).apply());
        content.addView(effect);
        content.addView(text("Schnell an- und ausschalten: Von oben zweimal runterwischen, Stift antippen und „Kipp-Effekt“ zu den aktiven Schaltern ziehen. Aus = das Bild steht still und scharf wie ein normaler Hintergrund.", 14));
        if (Build.VERSION.SDK_INT >= 33) {
            content.addView(button("Schalter zu den Schnelleinstellungen hinzufügen", this::requestTile));
        }
        content.addView(text("Kippe in der Android-Vorschau leicht nach links und rechts. Wähle beim Anwenden „Startbildschirm“.", 14));
        setContentView(scroll);
        readThumbnail();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(3), 0, dp(7));
        return view;
    }

    /** Schalter mit kurzer Erklärung darunter, gespeichert unter dem angegebenen Schlüssel (Standard: an). */
    private LinearLayout toggle(String label, String hint, String key) {
        return toggle(label, hint, key, true);
    }

    private LinearLayout toggle(String label, String hint, String key, boolean standard) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextSize(16);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(preferences.getBoolean(key, standard));
        toggle.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean(key, checked).apply());
        box.addView(toggle);
        box.addView(text(hint, 13));
        return box;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private SeekBar slider(String label, int max, int initial, IntConsumer update) {
        TextView name = text(label, 14);
        cropOptions.addView(name);
        SeekBar bar = new SeekBar(this);
        bar.setContentDescription(label);
        bar.setMax(max);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (!fromUser) return;
                update.accept(value);
                image.invalidate();
                main.removeCallbacks(saveCrop);
                main.postDelayed(saveCrop, 180);
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {
                main.removeCallbacks(saveCrop);
                saveCrop();
            }
        });
        cropOptions.addView(bar);
        return bar;
    }

    private void chooseImage() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), PICK_IMAGE);
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            status.setText("Android hat keine Bildauswahl gefunden.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        reading = true;
        choose.setEnabled(false);
        preview.setEnabled(false);
        adjust.setEnabled(false);
        status.setText("Bild wird geladen …");
        worker.execute(() -> {
            try {
                PhotoStore.importImage(getApplicationContext(), uri);
                Bitmap thumbnail = PhotoStore.load(getApplicationContext(), 4);
                main.post(() -> {
                    if (isDestroyed()) return;
                    reading = false;
                    showImage(thumbnail);
                });
            } catch (IOException | RuntimeException e) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    reading = false;
                    choose.setEnabled(true);
                    preview.setEnabled(image.photo != null);
                    adjust.setEnabled(image.photo != null);
                    status.setText("Dieses Bild konnte nicht geöffnet werden. Wähle eine andere Datei.");
                });
            }
        });
    }

    private void readThumbnail() {
        worker.execute(() -> {
            Bitmap thumbnail = PhotoStore.load(getApplicationContext(), 4);
            main.post(() -> { if (!isDestroyed() && !reading) showImage(thumbnail); });
        });
    }

    private void showImage(Bitmap thumbnail) {
        image.photo = thumbnail;
        image.soft = null;
        image.previewAlpha = 0;
        x = preferences.getFloat(PhotoStore.X, 0f);
        y = preferences.getFloat(PhotoStore.Y, 0f);
        zoom = preferences.getFloat(PhotoStore.ZOOM, 1f);
        horizontal.setProgress(Math.round(x * 100f) + 100);
        vertical.setProgress(Math.round(y * 100f) + 100);
        magnification.setProgress(Math.round((zoom - 1f) * 100f));
        image.invalidate();
        choose.setEnabled(true);
        preview.setEnabled(thumbnail != null);
        adjust.setEnabled(thumbnail != null);
        status.setText(thumbnail == null ? "Wähle dein bisheriges Hintergrundbild."
                : "Bild geladen. Verschiebe den Ausschnitt im Vorschaubild mit einem Finger, zoome mit zwei Fingern.");
    }

    /** Ausschnitt wurde im Vorschaubild mit dem Finger geändert: Regler nachziehen, verzögert speichern. */
    private void cropChanged() {
        horizontal.setProgress(Math.round(x * 100f) + 100);
        vertical.setProgress(Math.round(y * 100f) + 100);
        magnification.setProgress(Math.round((zoom - 1f) * 100f));
        image.invalidate();
        main.removeCallbacks(saveCrop);
        main.postDelayed(saveCrop, 180);
    }

    private void saveCrop() {
        if (reading || image.photo == null) return;
        preferences.edit().putFloat(PhotoStore.X, x).putFloat(PhotoStore.Y, y).putFloat(PhotoStore.ZOOM, zoom).apply();
    }

    private void openPreview() {
        saveCrop();
        try {
            startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            new ComponentName(this, TiltWallpaperService.class)));
        } catch (ActivityNotFoundException e) {
            status.setText("Android konnte die Live-Hintergrund-Vorschau nicht öffnen.");
        }
    }

    private static String blurText(float strength) {
        int percent = Math.round(strength * 100f);
        return percent == 0 ? "Unschärfe beim Kippen: aus" : "Unschärfe beim Kippen: " + percent + " %";
    }

    private void saveBlur() {
        preferences.edit().putFloat(PhotoStore.BLUR, blurStrength).apply();
    }

    /** Vorschau: Vorschaubild mit derselben Unschärfe wie der Hintergrund bei voller Neigung. */
    private void showBlurPreview() {
        Bitmap thumbnail = image.photo;
        if (thumbnail == null) return;
        main.removeCallbacks(hidePreview);
        float strength = blurStrength;
        int generation = ++previewGeneration;
        if (strength <= 0) {
            image.previewAlpha = 0;
            image.invalidate();
            return;
        }
        worker.execute(() -> {
            if (generation != previewGeneration) return; // inzwischen weitergeschoben
            int width = thumbnail.getWidth();
            int height = thumbnail.getHeight();
            int[] pixels = new int[width * height];
            thumbnail.getPixels(pixels, 0, width, 0, 0, width, height);
            // Vorschaubild hat dieselbe 1/4-Größe wie die weiche Version im Hintergrund, daher gleicher Radius
            SoftFocus.blur(pixels, width, height, SoftFocus.radius(strength), SoftFocus.PASSES);
            Bitmap soft = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            main.post(() -> {
                if (isDestroyed() || generation != previewGeneration) return;
                image.soft = soft;
                image.previewAlpha = SoftFocus.maxAlpha(strength);
                image.invalidate();
            });
        });
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            getSystemService(StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, ToggleTileService.class),
                    getString(R.string.tile_name),
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    getMainExecutor(),
                    result -> status.setText(
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                                    || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                                    ? "Der Schalter „Kipp-Effekt“ ist in den Schnelleinstellungen."
                                    : "Bitte den Schalter von Hand hinzufügen (siehe Text)."));
        } catch (RuntimeException e) {
            status.setText("Bitte den Schalter von Hand hinzufügen (siehe Text).");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // Der Schnellschalter kann den Zustand inzwischen geändert haben
        effect.setChecked(preferences.getBoolean(PhotoStore.ENABLED, true));
    }

    @Override protected void onPause() {
        main.removeCallbacks(saveCrop);
        saveCrop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private final class CropView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF destination = new RectF();
        private final float[] bounds = new float[4];
        private final float systemScale = PhotoStore.systemWallpaperScale();
        private Bitmap photo;
        private Bitmap soft;
        private float previewAlpha;
        private float lastX;
        private float lastY;
        private int activePointer = -1;
        private final ScaleGestureDetector pinch = new ScaleGestureDetector(SetupActivity.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        zoom = DrawMath.pinch(zoom, detector.getScaleFactor());
                        cropChanged();
                        return true;
                    }
                });

        CropView() {
            super(SetupActivity.this);
            setContentDescription("Vorschau für den Bildausschnitt: mit einem Finger verschieben, mit zwei Fingern zoomen");
        }

        /** Ausschnitt direkt im Vorschaubild: ein Finger verschiebt, zwei Finger zoomen. */
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (photo == null || reading) return false;
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Die Seite darf beim Verschieben im Bild nicht mitscrollen
                    getParent().requestDisallowInterceptTouchEvent(true);
                    activePointer = event.getPointerId(0);
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP: {
                    // Bezugspunkt neu setzen, damit das Bild beim Aufsetzen/Abheben eines Fingers nicht springt
                    int keep = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == 0 ? 1 : 0;
                    activePointer = event.getPointerId(keep);
                    lastX = event.getX(keep);
                    lastY = event.getY(keep);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int index = event.findPointerIndex(activePointer);
                    if (index < 0) return true;
                    float dx = event.getX(index) - lastX;
                    float dy = event.getY(index) - lastY;
                    lastX = event.getX(index);
                    lastY = event.getY(index);
                    if (!pinch.isInProgress()) {
                        x = DrawMath.drag(x, dx, DrawMath.range(getWidth(), getHeight(),
                                photo.getWidth(), photo.getHeight(), zoom, systemScale, true));
                        y = DrawMath.drag(y, dy, DrawMath.range(getWidth(), getHeight(),
                                photo.getWidth(), photo.getHeight(), zoom, systemScale, false));
                        cropChanged();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    activePointer = -1;
                    main.removeCallbacks(saveCrop);
                    saveCrop();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xff242c32);
            if (photo == null) return;
            // Gleiche Vergrößerung wie der Startbildschirm in Ruhe, damit die Vorschau dem Ergebnis entspricht
            MotionMath.cover(getWidth(), getHeight(), photo.getWidth(), photo.getHeight(),
                    zoom, x, y, 0, 0, systemScale, bounds);
            destination.set(bounds[0], bounds[1], bounds[2], bounds[3]);
            paint.setAlpha(255);
            canvas.drawBitmap(photo, null, destination, paint);
            if (soft != null && previewAlpha > 0) {
                paint.setAlpha(Math.round(255 * previewAlpha));
                canvas.drawBitmap(soft, null, destination, paint);
                paint.setAlpha(255);
            }
        }
    }
}
