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

public final class SetupActivity extends Activity implements android.hardware.SensorEventListener {
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
        image.previewAlpha = 0; // weiche Fassung bleibt für die Live-Unschärfe beim Kippen
        image.invalidate();
    };
    private boolean reading;
    private float x;
    private float y;
    private float zoom = 1;

    // ---------- Gestaltung „Live-Vorschau“ (Layout C, neu in 0.18) ----------
    private static final int BG = 0xFF1E2023;
    private static final int SHEET_TOP = 0xFF2C2F33;
    private static final int SHEET_BOTTOM = 0xFF24272A;
    private static final int INSET = 0xFF15171A;
    private static final int LINE = 0xFF34373B;
    private static final int TEXT = 0xFFEDE6DC;
    private static final int SUB = 0xFF9A948B;
    private static final int COPPER = 0xFFD9A066;
    private static final int COPPER_LIGHT = 0xFFE7B37C;
    private static final int COPPER_DARK = 0xFFB97E43;
    private static final int ON_COPPER = 0xFF2B1B08;
    static final String EXTRA_TAB = "tab";
    static final int TAB_TILT = 0;
    static final int TAB_PAGES = 1;
    static final int TAB_PHOTO = 2;

    private SharedPreferences launcherPrefs;
    // 0.20: echte Live-Vorschau – das Foto bewegt sich beim Kippen und verschwimmt wie der Hintergrund
    private final TiltTracker tracker = new TiltTracker();
    private android.hardware.SensorManager sensors;
    private android.hardware.Sensor sensor;
    private volatile float tiltTargetX;
    private volatile float tiltTargetY;
    private float tiltX;
    private float tiltY;
    private long lastFrame;
    private boolean running;
    private final android.view.Choreographer.FrameCallback liveFrame = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long now) {
            if (!running) return;
            float seconds = lastFrame == 0 ? .012f : Math.min(.04f, (now - lastFrame) / 1_000_000_000f);
            lastFrame = now;
            boolean live = tab != TAB_PHOTO; // beim Zuschneiden steht das Bild still
            float tx = live ? tiltTargetX : 0f;
            float ty = live ? tiltTargetY : 0f;
            float nx = MotionMath.approach(tiltX, tx, seconds);
            float ny = MotionMath.approach(tiltY, ty, seconds);
            if (nx != tiltX || ny != tiltY) {
                tiltX = nx;
                tiltY = ny;
                image.invalidate();
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private int sheetTop;
    private android.widget.FrameLayout previewFrame;
    private final LinearLayout[] panels = new LinearLayout[3];
    private final TextView[] tabs = new TextView[3];
    private TextView primary;
    private int tab = TAB_TILT;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = PhotoStore.preferences(this);
        launcherPrefs = getSharedPreferences("launcher", MODE_PRIVATE);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(SHEET_BOTTOM);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable backdrop = new android.graphics.drawable.GradientDrawable();
        backdrop.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        backdrop.setColors(new int[] {0xFF3A3D42, BG});
        backdrop.setGradientCenter(.5f, .22f);
        backdrop.setGradientRadius(getResources().getDisplayMetrics().widthPixels * .9f);
        root.setBackground(backdrop);

        // Oben: Live-Vorschau – das echte Foto mit Ausschnitt und Kipp-Unschärfe, leicht schräg wie ein gekipptes Handy
        Rect display = getWindowManager().getMaximumWindowMetrics().getBounds();
        float aspect = (float) Math.max(display.height(), display.width()) / Math.min(display.height(), display.width());
        int previewHeight = Math.round(display.height() * .40f);
        int frameHeight = previewHeight - dp(96);
        int frameWidth = Math.round(frameHeight / aspect);
        image = new CropView();
        previewFrame = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable rim = new android.graphics.drawable.GradientDrawable();
        rim.setColor(0xFF2A2D31);
        rim.setCornerRadius(dp(30));
        previewFrame.setBackground(rim);
        previewFrame.setPadding(dp(5), dp(5), dp(5), dp(5));
        previewFrame.setElevation(dp(18));
        previewFrame.setClipToOutline(true);
        previewFrame.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(25));
            }
        });
        previewFrame.addView(image, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        android.widget.FrameLayout.LayoutParams frameParams =
                new android.widget.FrameLayout.LayoutParams(frameWidth + dp(10), frameHeight + dp(10), Gravity.CENTER_HORIZONTAL);
        frameParams.topMargin = dp(64);
        root.addView(previewFrame, frameParams);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTextColor(0xFFE8E0D4);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Zurück");
        back.setBackground(raised(dp(14)));
        back.setElevation(dp(6));
        back.setOnClickListener(v -> finish());
        android.widget.FrameLayout.LayoutParams backParams = new android.widget.FrameLayout.LayoutParams(dp(46), dp(46));
        backParams.leftMargin = dp(18);
        backParams.topMargin = dp(14);
        root.addView(back, backParams);

        TextView live = new TextView(this);
        live.setText("LIVE-VORSCHAU");
        live.setTextSize(12);
        live.setLetterSpacing(.12f);
        live.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        live.setTextColor(COPPER);
        android.widget.FrameLayout.LayoutParams liveParams =
                new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        liveParams.rightMargin = dp(20);
        liveParams.topMargin = dp(28);
        root.addView(live, liveParams);

        // Unten: Blatt mit Reitern Kippen / Blättern / Foto
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable sheetShape = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[] {SHEET_TOP, SHEET_BOTTOM});
        float r = dp(32);
        sheetShape.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sheetShape);
        sheet.setElevation(dp(24));
        sheet.setPadding(dp(20), dp(10), dp(20), dp(16));
        android.widget.FrameLayout.LayoutParams sheetParams = new android.widget.FrameLayout.LayoutParams(-1, -1);
        sheetParams.topMargin = previewHeight - dp(20);
        sheetTop = sheetParams.topMargin;
        root.addView(sheet, sheetParams);

        View handle = new View(this);
        android.graphics.drawable.GradientDrawable handleShape = new android.graphics.drawable.GradientDrawable();
        handleShape.setColor(0xFF4A4D52);
        handleShape.setCornerRadius(dp(3));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(5));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(12);
        sheet.addView(handle, handleParams);

        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setPadding(dp(5), dp(5), dp(5), dp(5));
        android.graphics.drawable.GradientDrawable tabShape = new android.graphics.drawable.GradientDrawable();
        tabShape.setColor(INSET);
        tabShape.setCornerRadius(dp(24));
        tabBar.setBackground(tabShape);
        String[] names = {"Kippen", "Blättern", "Foto"};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(15);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            final int index = i;
            t.setOnClickListener(v -> selectTab(index));
            tabs[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(0, dp(42), 1f));
        }
        sheet.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(0, dp(8), 0, dp(8));
        scroll.addView(stack);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        for (int i = 0; i < 3; i++) {
            panels[i] = new LinearLayout(this);
            panels[i].setOrientation(LinearLayout.VERTICAL);
            stack.addView(panels[i], new LinearLayout.LayoutParams(-1, -2));
        }

        primary = new TextView(this);
        primary.setTextSize(16);
        primary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        primary.setTextColor(ON_COPPER);
        primary.setGravity(Gravity.CENTER);
        primary.setBackground(copper(dp(25)));
        primary.setElevation(dp(4));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, dp(52));
        primaryParams.topMargin = dp(10);
        sheet.addView(primary, primaryParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            backParams.topMargin = safe.top + dp(14);
            liveParams.topMargin = safe.top + dp(28);
            frameParams.topMargin = safe.top + dp(64);
            // 0.20: Vorschau passt immer ganz über das Blatt (vorher unten abgeschnitten)
            int room = sheetTop - frameParams.topMargin - dp(14);
            if (room > dp(80) && room < frameParams.height) {
                frameParams.height = room;
                frameParams.width = Math.round((room - dp(10)) / aspect) + dp(10);
            }
            sheet.setPadding(dp(20), dp(10), dp(20), dp(16) + safe.bottom);
            view.requestLayout();
            return insets;
        });

        buildTiltPanel(panels[TAB_TILT]);
        buildPagesPanel(panels[TAB_PAGES]);
        buildPhotoPanel(panels[TAB_PHOTO]);

        sensors = getSystemService(android.hardware.SensorManager.class);
        if (sensors != null) {
            sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) sensor = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR);
        }
        setContentView(root);
        selectTab(getIntent().getIntExtra(EXTRA_TAB, TAB_TILT));
        readThumbnail();
    }

    private void buildTiltPanel(LinearLayout p) {
        effect = copperSwitch();
        effect.setChecked(preferences.getBoolean(PhotoStore.ENABLED, true));
        effect.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean(PhotoStore.ENABLED, checked).apply());
        p.addView(row("Kipp-Effekt an", "Aus = das Bild steht still und scharf wie ein normaler Hintergrund.", effect));

        // Unschärfe beim Kippen: Regler von „aus“ bis „stark“, Live-Vorschau oben zeigt volle Neigung
        blurStrength = PhotoStore.blurStrength(preferences);
        blurLabel = badge(percent(blurStrength));
        blur = copperSlider(100, Math.round(blurStrength * 100f));
        blur.setContentDescription("Unschärfe beim Kippen");
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
                if (!fromUser) return;
                blurStrength = value / 100f;
                blurLabel.setText(percent(blurStrength));
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
        p.addView(sliderRow("Unschärfe beim Kippen", blurLabel, blur, "Ganz links = aus. Die Vorschau oben zeigt die volle Neigung."));

        p.addView(row("Unschärfe bleibt", "bis du in die Haltung vom Entsperren zurückkippst",
                prefSwitch(preferences, PhotoStore.HOLD_BLUR, false)));
        p.addView(row("Farben verblassen", "Das Bild tritt beim Neigen dunkel und farblos zurück.",
                prefSwitch(preferences, PhotoStore.FROST, true)));
        p.addView(row("Symbole kippen mit", "Symbole, Widgets und Dock verschwimmen mit (nur Kipp-Start).",
                prefSwitch(launcherPrefs, SettingsSheet.TILT, true)));
        p.addView(row("3D-Effekt: Symbole heben sich", "Die Seite, zu der du kippst, bleibt scharf und kommt nach vorn; die andere verschwimmt (nur Kipp-Start).",
                prefSwitch(launcherPrefs, SettingsSheet.DEPTH, true)));
        p.addView(row("3D-Effekt umkehren", "Falls die falsche Seite scharf wird.",
                prefSwitch(launcherPrefs, SettingsSheet.DEPTH_FLIP, false)));
        p.addView(row("Entsperr-Animation", "Das Bild hebt sich nach dem Entsperren in seine Ruhelage.",
                prefSwitch(preferences, PhotoStore.UNLOCK, true)));
        p.addView(row("Symbole fliegen herein", "Nach dem Entsperren kommen die Symbole groß und unscharf herein.",
                prefSwitch(launcherPrefs, SettingsSheet.UNLOCK, true)));
        p.addView(row("Unschärfe bei Kontrollzentrum und Apps", "Beim Runterziehen und App-Öffnen verschwimmt das Bild mit.",
                prefSwitch(preferences, PhotoStore.ZOOM_BLUR, true)));
        // 0.29: früher der große Knopf unten – jetzt eine Zeile in der Liste, der Knopf heißt „Speichern“
        p.addView(link("Jetzige Haltung als gerade", () -> android.widget.Toast.makeText(this, TiltBus.requestNeutral()
                ? "Diese Haltung gilt ab jetzt als gerade" : "Geht nur, wenn Kipp-Hintergrund als Hintergrund eingestellt ist",
                android.widget.Toast.LENGTH_SHORT).show()));
        if (Build.VERSION.SDK_INT >= 33) {
            p.addView(link("Schalter „Kipp-Effekt“ zu den Schnelleinstellungen", this::requestTile));
        }
    }

    private void buildPagesPanel(LinearLayout p) {
        p.addView(row("Blättern mit Absatz", "Die Seite bleibt erst stehen und blättert dann weich weiter. Aus: folgt dem Finger.",
                prefSwitch(launcherPrefs, SettingsSheet.DETENT, true)));
        p.addView(row("Übergangseffekte", "Die neue Seite blendet unscharf ein und wird in der Mitte klar.",
                prefSwitch(launcherPrefs, SettingsSheet.TRANSITIONS, true)));
        p.addView(row("Hintergrund beim Blättern unscharf", "Braucht mindestens zwei Seiten.",
                prefSwitch(preferences, PhotoStore.PAGE_BLUR, false)));
        p.addView(row("Beschriftungen anzeigen", null, prefSwitch(launcherPrefs, SettingsSheet.LABELS, true)));
        p.addView(row("Glanzrand", "Heller Rand oben an den Symbolen.", prefSwitch(launcherPrefs, SettingsSheet.GLOW, true)));
        p.addView(row("Messanzeige", "Oben links der Hintergrund, oben rechts der Startbildschirm: Bilder pro Sekunde und Ruckler. Zum Testen.", prefSwitch(preferences, PhotoStore.MEASURE, false)));
    }

    private void buildPhotoPanel(LinearLayout p) {
        status = note("Wähle dein bisheriges Hintergrundbild.");
        status.setPadding(0, dp(10), 0, dp(4));
        p.addView(status);
        choose = new Button(this); // unsichtbarer Halter für den alten Ablauf; bedient wird über den großen Knopf unten
        preview = new Button(this);
        adjust = new Button(this);
        p.addView(link("Android-Vorschau öffnen und anwenden", this::openPreview));
        cropOptions = new LinearLayout(this);
        cropOptions.setOrientation(LinearLayout.VERTICAL);
        horizontal = slider("Horizontal verschieben", 200, 100, value -> x = (value - 100) / 100f);
        vertical = slider("Vertikal verschieben", 200, 100, value -> y = (value - 100) / 100f);
        magnification = slider("Zoom", 70, 0, value -> zoom = 1f + value / 100f);
        cropOptions.addView(link("Ausschnitt zurücksetzen", () -> {
            horizontal.setProgress(100);
            vertical.setProgress(100);
            magnification.setProgress(0);
            x = y = 0;
            zoom = 1;
            image.invalidate();
            saveCrop();
        }));
        p.addView(cropOptions);
        p.addView(note("Tipp: Den Ausschnitt kannst du auch direkt im Bild oben verschieben (ein Finger) und zoomen (zwei Finger)."));
    }

    private void selectTab(int index) {
        tab = Math.max(0, Math.min(2, index));
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            panels[i].setVisibility(on ? View.VISIBLE : View.GONE);
            tabs[i].setBackground(on ? copper(dp(21)) : null);
            tabs[i].setTextColor(on ? ON_COPPER : 0xFFB9B3AA);
            tabs[i].setSelected(on);
        }
        // Zum Einstellen des Ausschnitts steht das Bild gerade, sonst leicht gekippt
        float tilt = tab == TAB_PHOTO ? 0f : 1f;
        previewFrame.animate().rotationY(-14f * tilt).rotationX(6f * tilt).setDuration(320).start();
        if (tab == TAB_TILT) {
            // 0.29: Speichern – schreibt alles sofort fest und schließt den Bildschirm
            primary.setText("Speichern");
            primary.setOnClickListener(v -> save());
        } else if (tab == TAB_PAGES) {
            primary.setText("Kipp-Start als Startbildschirm wählen");
            primary.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    android.widget.Toast.makeText(this, "Android konnte die Auswahl nicht öffnen.", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            primary.setText("Foto wählen");
            primary.setOnClickListener(v -> { if (!reading) chooseImage(); });
        }
    }

    /** Einstellungen sind schon beim Ändern gespeichert; hier zusätzlich sofort auf die Platte und zurück. */
    private void save() {
        preferences.edit().commit();
        launcherPrefs.edit().commit();
        android.widget.Toast.makeText(this, "Gespeichert", android.widget.Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.GradientDrawable copper(int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[] {COPPER_LIGHT, COPPER_DARK});
        d.setCornerRadius(radius);
        return d;
    }

    private android.graphics.drawable.GradientDrawable raised(int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[] {0xFF4A4D52, 0xFF34373B});
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(TEXT);
        return view;
    }

    private TextView note(String value) {
        TextView view = text(value, 13);
        view.setTextColor(SUB);
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private TextView badge(String value) {
        TextView view = text(value, 14);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setTextColor(COPPER);
        return view;
    }

    private static String percent(float strength) {
        int p = Math.round(strength * 100f);
        return p == 0 ? "aus" : p + " %";
    }

    /** Zeile: Titel (+ kleine Erklärung) links, Schalter rechts, Trennlinie unten. */
    private LinearLayout row(String label, String hint, View control) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinimumHeight(dp(58));
        box.setPadding(0, dp(8), 0, dp(8));
        box.setBackground(divider());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 15);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        texts.addView(title);
        if (hint != null) {
            TextView sub = text(hint, 12);
            sub.setTextColor(SUB);
            texts.addView(sub);
        }
        box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.leftMargin = dp(12);
        box.addView(control, cp);
        box.setOnClickListener(v -> { if (control instanceof Switch) ((Switch) control).toggle(); });
        return box;
    }

    private LinearLayout sliderRow(String label, TextView value, SeekBar bar, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(10));
        box.setBackground(divider());
        LinearLayout head = new LinearLayout(this);
        TextView title = text(label, 15);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(value);
        box.addView(head);
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(40)));
        if (hint != null) {
            TextView sub = text(hint, 12);
            sub.setTextColor(SUB);
            box.addView(sub);
        }
        return box;
    }

    private android.graphics.drawable.Drawable divider() {
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(LINE);
        android.graphics.drawable.LayerDrawable layer = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[] {line});
        layer.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        layer.setLayerHeight(0, Math.max(1, dp(1)));
        return layer;
    }

    private TextView link(String label, Runnable action) {
        TextView view = text(label, 15);
        view.setTextColor(COPPER);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setMinHeight(dp(52));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(divider());
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private Switch copperSwitch() {
        Switch s = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable on = copper(dp(15));
        on.setSize(dp(52), dp(30));
        android.graphics.drawable.GradientDrawable off = new android.graphics.drawable.GradientDrawable();
        off.setColor(INSET);
        off.setCornerRadius(dp(15));
        off.setSize(dp(52), dp(30));
        track.addState(states[0], on);
        track.addState(states[1], off);
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        thumb.addState(states[0], knob(ON_COPPER));
        thumb.addState(states[1], knob(0xFF6A6D72));
        s.setTrackDrawable(track);
        s.setThumbDrawable(thumb);
        s.setShowText(false);
        s.setText("");
        return s;
    }

    private android.graphics.drawable.Drawable knob(int color) {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(24), dp(24));
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(dot, dp(3));
        return inset;
    }

    private Switch prefSwitch(SharedPreferences prefs, String key, boolean standard) {
        Switch s = copperSwitch();
        s.setChecked(prefs.getBoolean(key, standard));
        s.setOnCheckedChangeListener((view, checked) -> prefs.edit().putBoolean(key, checked).apply());
        return s;
    }

    private SeekBar copperSlider(int max, int value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(COPPER));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(COPPER_LIGHT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4A4D52));
        return bar;
    }

    private SeekBar slider(String label, int max, int initial, IntConsumer update) {
        SeekBar bar = copperSlider(max, initial);
        bar.setContentDescription(label);
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
        cropOptions.addView(sliderRow(label, new TextView(this), bar, null));
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
        main.post(this::prepareSoft);
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

    /** Weiche Fassung für die Live-Unschärfe vorbereiten, ohne sie gleich zu zeigen. */
    private void prepareSoft() {
        Bitmap thumbnail = image.photo;
        float strength = blurStrength;
        if (thumbnail == null || strength <= 0) return;
        int generation = ++previewGeneration;
        worker.execute(() -> {
            if (generation != previewGeneration) return;
            int width = thumbnail.getWidth();
            int height = thumbnail.getHeight();
            int[] pixels = new int[width * height];
            thumbnail.getPixels(pixels, 0, width, 0, 0, width, height);
            SoftFocus.blur(pixels, width, height, SoftFocus.radius(strength), SoftFocus.PASSES);
            Bitmap soft = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            main.post(() -> {
                if (isDestroyed() || generation != previewGeneration) return;
                image.soft = soft;
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

    @Override public void onSensorChanged(android.hardware.SensorEvent event) {
        tracker.update(event.values, event.timestamp);
        tiltTargetX = tracker.x();
        tiltTargetY = tracker.y();
    }

    @Override public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {}

    @Override protected void onResume() {
        super.onResume();
        if (sensors != null && sensor != null && preferences.getBoolean(PhotoStore.ENABLED, true)) {
            tracker.reset();
            sensors.registerListener(this, sensor, 10_000);
        }
        running = true;
        lastFrame = 0;
        android.view.Choreographer.getInstance().postFrameCallback(liveFrame);
        // Der Schnellschalter kann den Zustand inzwischen geändert haben
        effect.setChecked(preferences.getBoolean(PhotoStore.ENABLED, true));
    }

    @Override protected void onPause() {
        running = false;
        if (sensors != null) sensors.unregisterListener(this);
        android.view.Choreographer.getInstance().removeFrameCallback(liveFrame);
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
                    zoom, x, y, tiltX, tiltY, systemScale, bounds);
            destination.set(bounds[0], bounds[1], bounds[2], bounds[3]);
            paint.setAlpha(255);
            canvas.drawBitmap(photo, null, destination, paint);
            // Kipp-Unschärfe wie im Hintergrund; beim Verschieben des Reglers volle Neigung zeigen
            float liveSoft = SoftFocus.alpha(Math.min(1f, Math.max(Math.abs(tiltX), Math.abs(tiltY))), blurStrength);
            float shown = Math.max(previewAlpha, liveSoft);
            if (soft != null && shown > 0) {
                paint.setAlpha(Math.round(255 * shown));
                canvas.drawBitmap(soft, null, destination, paint);
                paint.setAlpha(255);
            }
        }
    }
}
