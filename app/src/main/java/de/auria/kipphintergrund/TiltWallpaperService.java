package de.auria.kipphintergrund;

import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TiltWallpaperService extends WallpaperService {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    @Override public Engine onCreateEngine() { return new TiltEngine(); }

    @Override public void onDestroy() {
        loader.shutdown();
        super.onDestroy();
    }

    private final class TiltEngine extends Engine implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler main = new Handler(Looper.getMainLooper());
        // 0.21: Zeichnen im eigenen Thread mit eigenem Bildtakt – der Startbildschirm im Haupt-Thread
        // wird beim Wischen nicht mehr vom Hintergrund ausgebremst
        private Choreographer frames;
        private HandlerThread drawThread;
        private Handler drawHandler;
        /** Zeichnen und Austausch/Freigabe der Bilder schließen sich gegenseitig aus. */
        private final Object drawLock = new Object();
        private final Choreographer.FrameCallback renderFrame = this::onFrame;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint gradientPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Matrix softMatrix = new Matrix();
        private final Matrix maskMatrix = new Matrix();
        private final RectF softSource = new RectF();
        private final float[] blurDir = new float[2];
        private BitmapShader softShader;
        private Bitmap softShaderBitmap;
        // Fester Verlauf (0 → voll deckend) und fertige Mischung, einmal angelegt statt bei jedem Bild
        private LinearGradient slopeMask;
        private ComposeShader slopeShader;
        // Abdunklung als Farbfilter, je Stufe einmal angelegt
        private final LightingColorFilter[] dimFilters = new LightingColorFilter[256];
        // Ersetzte Bilder: werden nach dem nächsten Zeichnen freigegeben
        private final ArrayList<Bitmap> retired = new ArrayList<>();
        // Weichzeichner-Vorlage in 1/4 Größe – nur im Lade-Thread benutzt
        private int[] softSourcePixels;
        private int softSourceWidth;
        private int softSourceHeight;
        // Messanzeige (neu in 0.11)
        private volatile boolean measure;
        private volatile long sensorNanos;
        private final DrawMath.Meter meter = new DrawMath.Meter();
        private final Paint meterText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint meterBox = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF destination = new RectF();
        private final float[] bounds = new float[4];
        private final TiltTracker tracker = new TiltTracker();
        private final AtomicBoolean framePending = new AtomicBoolean();
        private SharedPreferences preferences;
        private SensorManager sensors;
        private Sensor sensor;
        private HandlerThread sensorThread;
        private Handler sensorHandler;
        private volatile Bitmap photo;
        private volatile Bitmap softPhoto;
        private WallpaperColors colors;
        private volatile boolean visible;
        private volatile boolean surfaceReady;
        private volatile boolean listening;
        private volatile boolean destroyed;
        private volatile float blurStrength = SoftFocus.DEFAULT_STRENGTH;
        private int softGeneration;
        private volatile boolean enabled = true;
        private boolean frost = true;
        private volatile boolean shift;
        private boolean unlockAnimation = true;
        private boolean zoomBlur = true;
        private volatile boolean holdBlur;
        private boolean pageBlur = true;
        // Unschärfe bleibt: Neigung gegenüber der Entsperr-Haltung (Ziel aus dem Sensor-Thread, im Bildtakt geglättet)
        private volatile float heldTarget;
        private volatile float held;
        private volatile float heldDirX;
        private volatile float heldDirY;
        private boolean baseResetPending = true; // nur im Haupt-Thread
        // 0.17: „Jetzige Haltung als gerade“ – neue Ausgangslage für die gehaltene Unschärfe, Bild wird sofort scharf
        private final Runnable neutral = () -> {
            heldTarget = 0;
            if (listening) sensorHandler.post(() -> tracker.resetBase());
            else baseResetPending = true;
            scheduleFrame();
        };
        // Seitenwechsel: 0 = auf einer Seite, 1 = mitten zwischen zwei Seiten
        private volatile float pageSwipe;
        // Entsperr-Animation
        private boolean armed;            // Bildschirm war aus: beim nächsten Entsperren abspielen
        private long presentAt;           // Zeitpunkt des Entsperrens (0 = noch nicht gemeldet)
        private volatile boolean unlocking;
        private volatile long unlockStart;         // 0 = startet mit dem nächsten Bildtakt
        private volatile float unlockAmount;       // 1 = Anfang, 0 = fertig
        private boolean receiverRegistered;
        private KeyguardManager keyguard;
        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    armed = true;
                    presentAt = 0;
                    baseResetPending = true;
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    presentAt = SystemClock.uptimeMillis();
                    // Die Haltung beim Entsperren ist die Ausgangslage für „Unschärfe bleibt“
                    heldTarget = 0;
                    sensorHandler.post(tracker::resetBase);
                    scheduleFrame(); // eine stehende Unschärfe weich zurücknehmen
                    // Hintergrund ist auch auf dem Sperrbildschirm zu sehen: Animation beim Entsperren
                    if (armed && visible) startUnlock();
                }
            }
        };
        private boolean highFrameRate;
        private int imageGeneration;
        private long lastFrame;
        // Ziel kommt aus dem Sensor-Thread, Position wird im Bildtakt nachgezogen
        private volatile float targetX;
        private volatile float targetY;
        private volatile float x;
        private volatile float y;
        private float zoom;
        private float positionX;
        private float positionY;
        private float systemMaxScale = MotionMath.DEFAULT_SYSTEM_SCALE;
        private volatile float zoomOut;
        private float maxRefreshRate;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            // Seitenwechsel: der Launcher meldet beim Wischen, wie weit er zwischen zwei Seiten steht
            setOffsetNotificationsEnabled(true);
            preferences = PhotoStore.preferences(TiltWallpaperService.this);
            preferences.registerOnSharedPreferenceChangeListener(this);
            sensors = getSystemService(SensorManager.class);
            sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            // Eigener Thread für Sensorwerte, damit sie nicht hinter dem Zeichnen im Haupt-Thread warten
            sensorThread = new HandlerThread("kipp-sensor");
            sensorThread.start();
            sensorHandler = new Handler(sensorThread.getLooper());
            drawThread = new HandlerThread("kipp-draw");
            drawThread.start();
            drawHandler = new Handler(drawThread.getLooper());
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            drawHandler.post(() -> {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
                } catch (RuntimeException ignored) {}
                frames = Choreographer.getInstance(); // Bildtakt dieses Threads
                ready.countDown();
            });
            try {
                if (!ready.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) frames = Choreographer.getInstance();
            } catch (InterruptedException e) {
                frames = Choreographer.getInstance();
            }
            systemMaxScale = PhotoStore.systemWallpaperScale();
            maxRefreshRate = readMaxRefreshRate();
            keyguard = getSystemService(KeyguardManager.class);
            if (!isPreview()) {
                TiltBus.setNeutralHandler(neutral);
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                try {
                    registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
                    receiverRegistered = true;
                } catch (RuntimeException ignored) {}
            }
            meterText.setColor(Color.WHITE);
            meterText.setTypeface(Typeface.MONOSPACE);
            meterBox.setColor(Color.argb(170, 0, 0, 0));
            readOptions();
            loadPhoto();
        }

        private float readMaxRefreshRate() {
            try {
                Context display = getDisplayContext();
                Display d = display == null ? null : display.getDisplay();
                if (d == null) return 0;
                float max = 0;
                for (Display.Mode mode : d.getSupportedModes()) max = Math.max(max, mode.getRefreshRate());
                return max;
            } catch (RuntimeException e) {
                return 0;
            }
        }

        @Override public void onVisibilityChanged(boolean showing) {
            if (visible == showing) return;
            visible = showing;
            syncSensor();
            if (showing) {
                // Nach dem Entsperren direkt auf dem Startbildschirm: Animation abspielen.
                // Wer erst in eine App entsperrt und später zum Startbildschirm wechselt, bekommt keine verspätete Animation.
                if (armed && (keyguard == null || !keyguard.isKeyguardLocked())) {
                    if (presentAt == 0 || SystemClock.uptimeMillis() - presentAt < 1500) startUnlock();
                    else armed = false;
                }
                scheduleFrame();
            } else {
                stopFrames();
                if (!isPreview()) TiltBus.publish(0f, 0f, 0f);
            }
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = true;
            highFrameRate = false;
            syncSensor();
            draw();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) { draw(); }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            synchronized (drawLock) {
                surfaceReady = false; // wartet, bis ein laufendes Bild fertig ist
            }
            stopFrames();
            syncSensor();
            super.onSurfaceDestroyed(holder);
        }

        /** Wie beim normalen Hintergrund: in Ruhe vergrößert, beim Öffnen von Benachrichtigungen/Apps 1,0. */
        @Override public void onZoomChanged(float zoom) {
            zoomOut = zoom;
            scheduleFrame(); // höchstens einmal pro Bildtakt zeichnen, auch wenn das System öfter meldet
        }

        /** Startbildschirm wischt zwischen Seiten. Das Bild bleibt stehen, nur Unschärfe und Abdunklung folgen. */
        @Override public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep,
                                               int xPixelOffset, int yPixelOffset) {
            float swipe = enabled && pageBlur ? Effects.pageSwipe(xOffset, xOffsetStep) : 0f;
            if (Math.abs(swipe - pageSwipe) < .002f) return;
            pageSwipe = swipe;
            scheduleFrame();
        }

        @Override public void onDestroy() {
            destroyed = true;
            visible = false;
            if (!isPreview()) TiltBus.publish(0f, 0f, 0f);
            TiltBus.clearNeutralHandler(neutral);
            syncSensor();
            stopFrames();
            preferences.unregisterOnSharedPreferenceChangeListener(this);
            if (receiverRegistered) {
                try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) {}
                receiverRegistered = false;
            }
            sensorThread.quitSafely();
            synchronized (drawLock) {
                retire(photo);
                retire(softPhoto);
                photo = null;
                softPhoto = null;
                recycleRetired();
            }
            drawThread.quitSafely();
            super.onDestroy();
        }

        private void syncSensor() {
            boolean shouldListen = enabled && visible && surfaceReady && photo != null && sensor != null && !destroyed;
            if (shouldListen == listening) return;
            if (shouldListen) {
                // Nur das Ziel zurücksetzen, nicht die Position: das Bild gleitet weich zurück statt zu springen
                targetX = targetY = 0;
                // läuft vor den ersten neuen Sensorwerten; nach Bildschirm aus auch die Entsperr-Haltung neu
                if (baseResetPending) {
                    baseResetPending = false;
                    heldTarget = 0;
                    sensorHandler.post(() -> { tracker.reset(); tracker.resetBase(); });
                } else {
                    sensorHandler.post(tracker::reset);
                }
                synchronized (sensorLock) {
                    resting = false;
                    restRefValid = false;
                    listening = sensors.registerListener(this, sensor, MotionMath.ACTIVE_INTERVAL_US, 0, sensorHandler);
                }
            } else {
                synchronized (sensorLock) {
                    sensors.unregisterListener(this);
                    listening = false;
                }
            }
        }

        private void readOptions() {
            zoom = preferences.getFloat(PhotoStore.ZOOM, 1f);
            positionX = preferences.getFloat(PhotoStore.X, 0f);
            positionY = preferences.getFloat(PhotoStore.Y, 0f);
            blurStrength = PhotoStore.blurStrength(preferences);
            enabled = preferences.getBoolean(PhotoStore.ENABLED, true);
            frost = preferences.getBoolean(PhotoStore.FROST, true);
            shift = preferences.getBoolean(PhotoStore.SHIFT, false);
            unlockAnimation = preferences.getBoolean(PhotoStore.UNLOCK, true);
            zoomBlur = preferences.getBoolean(PhotoStore.ZOOM_BLUR, true);
            holdBlur = preferences.getBoolean(PhotoStore.HOLD_BLUR, false);
            pageBlur = preferences.getBoolean(PhotoStore.PAGE_BLUR, false);
            if (!pageBlur) pageSwipe = 0;
            measure = preferences.getBoolean(PhotoStore.MEASURE, false);
        }

        /** Die weiche Version wird gebraucht, wenn irgendein Effekt weichzeichnet. */
        private boolean softNeeded() {
            return blurStrength > 0 || unlockAnimation || zoomBlur || pageBlur;
        }

        private void startUnlock() {
            armed = false;
            if (!enabled || !unlockAnimation) return;
            unlocking = true;
            unlockStart = 0;
            unlockAmount = 1f;
            scheduleFrame();
        }

        @Override public void onSharedPreferenceChanged(SharedPreferences settings, String key) {
            readOptions();
            if (PhotoStore.IMAGE.equals(key)) loadPhoto();
            else if (PhotoStore.BLUR.equals(key) || PhotoStore.FOCUS.equals(key)
                    || PhotoStore.UNLOCK.equals(key) || PhotoStore.ZOOM_BLUR.equals(key)
                    || PhotoStore.PAGE_BLUR.equals(key)) rebuildSoft();
            syncSensor();
            if (!enabled) {
                // Effekt aus: Sensor ist abgemeldet, das Bild gleitet zurück in die Mitte und bleibt dort scharf
                targetX = targetY = 0;
                heldTarget = 0;
            }
            if (!holdBlur) heldTarget = 0;
            scheduleFrame();
        }

        @Override public WallpaperColors onComputeColors() { return colors; }

        private void loadPhoto() {
            int generation = ++imageGeneration;
            int softGen = ++softGeneration;
            float strength = blurStrength;
            boolean needed = softNeeded();
            loader.execute(() -> {
                PhotoStore.migrateLegacy(TiltWallpaperService.this);
                // Foto nur einmal dekodieren; die kleinen Fassungen werden daraus abgeleitet
                Bitmap sharp = PhotoStore.load(TiltWallpaperService.this, 1);
                WallpaperColors palette = null;
                if (sharp != null) {
                    int width = sharp.getWidth();
                    int height = sharp.getHeight();
                    Bitmap small = Bitmap.createScaledBitmap(sharp,
                            Math.max(1, width / SoftFocus.SCALE), Math.max(1, height / SoftFocus.SCALE), true);
                    softSourceWidth = small.getWidth();
                    softSourceHeight = small.getHeight();
                    softSourcePixels = new int[softSourceWidth * softSourceHeight];
                    small.getPixels(softSourcePixels, 0, softSourceWidth, 0, 0, softSourceWidth, softSourceHeight);
                    if (small != sharp) small.recycle();
                    Bitmap tiny = Bitmap.createScaledBitmap(sharp, Math.max(1, width / 16), Math.max(1, height / 16), true);
                    palette = WallpaperColors.fromBitmap(tiny);
                    if (tiny != sharp) tiny.recycle();
                } else {
                    softSourcePixels = null;
                }
                // Weiche Version einmalig berechnen: 1/4 Größe, echt weichgezeichnet, Stärke nach Einstellung
                Bitmap soft = sharp == null || !needed ? null : buildSoft(strength);
                // Foto einmalig in den Grafikspeicher legen: kein Hochladen beim ersten Kippen
                if (sharp != null) {
                    Bitmap gpu = null;
                    try {
                        gpu = sharp.copy(Bitmap.Config.HARDWARE, false);
                    } catch (RuntimeException ignored) {}
                    if (gpu != null) {
                        sharp.recycle();
                        sharp = gpu;
                    } else {
                        sharp.prepareToDraw();
                    }
                }
                Bitmap ready = sharp;
                Bitmap readySoft = soft;
                WallpaperColors readyColors = palette;
                drawHandler.post(() -> {
                    if (destroyed || generation != imageGeneration) {
                        // Inzwischen überholt: nie gezeichnet, sofort freigeben
                        if (ready != null) ready.recycle();
                        if (readySoft != null) readySoft.recycle();
                        return;
                    }
                    if (photo != ready) retire(photo);
                    photo = ready;
                    if (softGen == softGeneration) {
                        if (softPhoto != readySoft) retire(softPhoto);
                        softPhoto = readySoft;
                    } else if (readySoft != null) {
                        readySoft.recycle();
                    }
                    colors = readyColors;
                    main.post(() -> {
                        if (destroyed) return;
                        if (colors != null) notifyColorsChanged();
                        syncSensor();
                    });
                    scheduleFrame();
                });
            });
        }

        /** Neue Unschärfe-Stärke eingestellt: nur die weiche Version neu berechnen, das scharfe Foto bleibt. */
        private void rebuildSoft() {
            int gen = ++softGeneration;
            float strength = blurStrength;
            boolean needed = softNeeded();
            loader.execute(() -> {
                Bitmap soft = needed ? buildSoft(strength) : null;
                drawHandler.post(() -> {
                    if (destroyed || gen != softGeneration) {
                        if (soft != null) soft.recycle();
                        return;
                    }
                    if (softPhoto != soft) retire(softPhoto);
                    softPhoto = soft;
                    scheduleFrame();
                });
            });
        }

        /** Läuft im Lade-Thread: Vorlage in 1/4 Größe kopieren und mit SoftFocus weichzeichnen. */
        private Bitmap buildSoft(float strength) {
            // Regler auf 0: Kippen zeichnet nicht weich, Entsperren/Kontrollzentrum nutzen die Voreinstellung
            if (strength <= 0) strength = SoftFocus.DEFAULT_STRENGTH;
            if (softSourcePixels == null) {
                // Noch keine Vorlage (z. B. Foto fehlte beim Start): einmal in 1/4 Größe laden
                Bitmap small = PhotoStore.load(TiltWallpaperService.this, SoftFocus.SCALE);
                if (small == null) return null;
                softSourceWidth = small.getWidth();
                softSourceHeight = small.getHeight();
                softSourcePixels = new int[softSourceWidth * softSourceHeight];
                small.getPixels(softSourcePixels, 0, softSourceWidth, 0, 0, softSourceWidth, softSourceHeight);
                small.recycle();
            }
            int width = softSourceWidth;
            int height = softSourceHeight;
            int[] pixels = softSourcePixels.clone();
            SoftFocus.blur(pixels, width, height, SoftFocus.radius(strength), SoftFocus.PASSES);
            Bitmap soft = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            Bitmap gpu = null;
            try {
                gpu = soft.copy(Bitmap.Config.HARDWARE, false);
            } catch (RuntimeException ignored) {}
            if (gpu != null) {
                soft.recycle();
                return gpu;
            }
            soft.prepareToDraw();
            return soft;
        }

        /** Ersetztes Bild merken; freigegeben wird es erst, nachdem das neue einmal gezeichnet ist. */
        private void retire(Bitmap bitmap) {
            synchronized (drawLock) {
                if (bitmap != null && !retired.contains(bitmap)) retired.add(bitmap);
            }
        }

        private void recycleRetired() {
            synchronized (drawLock) {
                recycleRetiredLocked();
            }
        }

        private void recycleRetiredLocked() {
            if (retired.isEmpty()) return;
            for (Bitmap bitmap : retired) {
                if (bitmap != photo && bitmap != softPhoto) bitmap.recycle();
            }
            retired.clear();
        }

        // Läuft im Sensor-Thread
        // 0.22 – Ruhemodus (nur Sensor-Thread, An-/Abmelden unter sensorLock)
        private final Object sensorLock = new Object();
        private final float[] restRef = new float[4];
        private boolean restRefValid;
        private boolean resting;
        private long lastMoveNanos;

        /** Liegt das Handy ein paar Sekunden still, seltener messen; bei der ersten Drehung sofort wieder schnell. */
        private void checkRest(SensorEvent event) {
            float[] v = event.values;
            if (!restRefValid || MotionMath.moved(restRef, v)) {
                System.arraycopy(v, 0, restRef, 0, Math.min(4, v.length));
                if (v.length < 4) restRef[3] = (float) Math.sqrt(Math.max(0, 1 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]));
                restRefValid = true;
                lastMoveNanos = event.timestamp;
                if (resting) setSensorRate(false);
            } else if (!resting && event.timestamp - lastMoveNanos > MotionMath.REST_AFTER_MS * 1_000_000L) {
                setSensorRate(true);
            }
        }

        private void setSensorRate(boolean rest) {
            synchronized (sensorLock) {
                if (!listening) return;
                sensors.unregisterListener(this);
                resting = rest;
                listening = sensors.registerListener(this, sensor,
                        rest ? MotionMath.REST_INTERVAL_US : MotionMath.ACTIVE_INTERVAL_US, 0, sensorHandler);
            }
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (!listening) return;
            checkRest(event);
            tracker.update(event.values, event.timestamp);
            if (!listening) return; // während der Berechnung abgeschaltet
            sensorNanos = event.timestamp;
            float nx = tracker.x();
            float ny = tracker.y();
            targetX = nx;
            targetY = ny;
            float h = holdBlur ? tracker.held() : 0f;
            heldDirX = tracker.heldX();
            heldDirY = tracker.heldY();
            heldTarget = h;
            if (Math.abs(x - nx) >= .0015f || Math.abs(y - ny) >= .0015f || Math.abs(held - h) >= .0015f) scheduleFrame();
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        // Darf aus dem Haupt- und dem Sensor-Thread aufgerufen werden (Choreographer ist dafür gesperrt)
        private void scheduleFrame() {
            if (!visible || !surfaceReady) return;
            if (framePending.compareAndSet(false, true)) frames.postFrameCallback(renderFrame);
        }

        private void stopFrames() {
            frames.removeFrameCallback(renderFrame);
            framePending.set(false);
            lastFrame = 0;
            setHighFrameRate(false);
        }

        private void onFrame(long now) {
            framePending.set(false);
            if (!visible || !surfaceReady) return;
            float seconds = lastFrame == 0 ? .012f : Math.min(.04f, (now - lastFrame) / 1_000_000_000f);
            lastFrame = now;
            float tx = targetX;
            float ty = targetY;
            x = MotionMath.approach(x, tx, seconds);
            y = MotionMath.approach(y, ty, seconds);
            float th = heldTarget;
            held = MotionMath.approach(held, th, seconds);
            if (unlocking) {
                if (unlockStart == 0) unlockStart = now;
                unlockAmount = Effects.unlockAmount((now - unlockStart) / 1_000_000f);
                if (unlockAmount <= 0) {
                    unlockAmount = 0;
                    unlocking = false;
                }
            }
            boolean moving = x != tx || y != ty || held != th || unlocking;
            setHighFrameRate(moving);
            draw();
            if (measure && moving) {
                // Alter des Sensorwerts beim Zeichnen (gleiche Zeitbasis wie SensorEvent.timestamp)
                long sample = sensorNanos;
                meter.frame(now, sample == 0 ? 0 : SystemClock.elapsedRealtimeNanos() - sample);
            }
            if (moving) scheduleFrame();
            else lastFrame = 0;
        }

        /** Während der Bewegung die höchste Bildrate des Displays wünschen, in Ruhe freigeben. */
        private void setHighFrameRate(boolean high) {
            if (high == highFrameRate || maxRefreshRate <= 0) return;
            highFrameRate = high;
            try {
                Surface surface = getSurfaceHolder().getSurface();
                if (surface == null || !surface.isValid()) return;
                surface.setFrameRate(high ? maxRefreshRate : 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (RuntimeException ignored) {
                // Nur ein Wunsch an das System – ohne ihn läuft alles weiter
            }
        }

        private void draw() {
            synchronized (drawLock) {
                drawLocked();
            }
        }

        private void drawLocked() {
            if (!surfaceReady) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas;
            try {
                canvas = holder.lockHardwareCanvas();
            } catch (IllegalStateException | IllegalArgumentException e) {
                return; // The surface can disappear between lifecycle callbacks.
            }
            if (canvas == null) return;
            try {
                canvas.drawColor(0xff101418);
                if (photo == null) return;
                float px = x;
                float py = y;
                int width = canvas.getWidth();
                int height = canvas.getHeight();
                MotionMath.cover(width, height, photo.getWidth(), photo.getHeight(),
                        zoom, positionX, positionY, shift ? px : 0f, shift ? py : 0f,
                        MotionMath.systemScale(zoomOut, systemMaxScale), bounds);
                destination.set(bounds[0], bounds[1], bounds[2], bounds[3]);

                // Mit „Unschärfe bleibt“ zählt auch die Neigung gegenüber dem Entsperren (ohne Nachführen)
                float tiltMove = Math.max(Math.abs(px), Math.abs(py));
                boolean heldWins = held > tiltMove;
                float movement = Math.min(1f, Math.max(tiltMove, held));
                float unlock = enabled && unlocking ? unlockAmount : 0f;
                float zoomed = enabled && zoomBlur ? Math.max(0f, Math.min(1f, zoomOut)) : 0f;
                float page = enabled && pageBlur ? pageSwipe : 0f;
                float tiltSoft = SoftFocus.alpha(movement, blurStrength);
                // Gleichmäßige Unschärfe (Entsperren, Kontrollzentrum, Seitenwechsel) und Kipp-Unschärfe mit Verlauf
                float evenSoft = Effects.softAlpha(0f, unlock, zoomed, page);
                // Farben verblassen: der Hintergrund tritt dunkel und farblos zurück, die Symbole heben sich ab.
                // Als Farbfilter direkt auf dem Bild statt als eigene Vollbild-Ebene: ein Zeichendurchgang weniger.
                float dark = Effects.dim(enabled && frost ? Effects.frostDim(movement) : 0f, unlock, zoomed, page);
                LightingColorFilter dimFilter = dimFilter(DrawMath.dimLevel(dark));
                if (!isPreview()) {
                    // Eigener Startbildschirm (gleicher Prozess): Symbole und Widgets im selben Bildtakt mitnehmen
                    TiltBus.publish(tiltSoft, enabled && frost ? Effects.frostDim(movement) : 0f, unlock,
                            enabled ? px : 0f, enabled ? py : 0f);
                }
                paint.setColorFilter(dimFilter);
                gradientPaint.setColorFilter(dimFilter);
                if (heldWins) Effects.blurSide(heldDirX, heldDirY, blurDir);
                else Effects.blurSide(px, py, blurDir);

                canvas.save();
                if (unlock > 0) {
                    // Entsperren: von etwas tiefer und vergrößert in die Ruhelage „anheben“
                    canvas.translate(0, Effects.UNLOCK_LIFT * unlock * height);
                    float s = 1f + Effects.UNLOCK_SCALE * unlock;
                    canvas.scale(s, s, width / 2f, height / 2f);
                }
                paint.setAlpha(255);
                canvas.drawBitmap(photo, null, destination, paint);
                if (softPhoto != null) {
                    boolean slope = tiltSoft > evenSoft && (blurDir[0] != 0 || blurDir[1] != 0);
                    if (slope) {
                        // Die Seite, die zum Betrachter kommt, voll weich; die Seite, die nach hinten geht, nur zum Teil
                        drawSoftSlope(canvas, width, height,
                                Math.max(evenSoft, tiltSoft * Effects.AWAY_SOFT), tiltSoft);
                    } else {
                        float alpha = Math.min(1f, Math.max(evenSoft, tiltSoft));
                        if (alpha > 0) {
                            paint.setAlpha(Math.round(255 * alpha));
                            canvas.drawBitmap(softPhoto, null, destination, paint);
                        }
                    }
                }
                canvas.restore();
                if (measure) drawMeter(canvas, width, height);
            } finally {
                holder.unlockCanvasAndPost(canvas);
                recycleRetired();
            }
        }

        /** Farbfilter für eine Abdunkel-Stufe 0 … 255 (null = keine Abdunklung). Einmal angelegt, dann wiederverwendet. */
        private LightingColorFilter dimFilter(int level) {
            if (level <= 0) return null;
            LightingColorFilter filter = dimFilters[level];
            if (filter == null) {
                int keep = DrawMath.dimMultiply(level);
                filter = new LightingColorFilter(Color.rgb(keep, keep, keep), Color.rgb(
                        DrawMath.dimAdd(level, Effects.FROST_RED),
                        DrawMath.dimAdd(level, Effects.FROST_GREEN),
                        DrawMath.dimAdd(level, Effects.FROST_BLUE)));
                dimFilters[level] = filter;
            }
            return filter;
        }

        /** Messanzeige oben links: Bilder pro Sekunde beim Kippen und Alter des Sensorwerts beim Zeichnen. */
        private void drawMeter(Canvas canvas, int width, int height) {
            String line = meter.hasValue()
                    ? String.format(Locale.GERMANY, "%.0f Bilder/s · Sensor→Bild %.1f ms", meter.fps(), meter.latencyMs())
                    : "Messung: Handy kippen …";
            float size = Math.min(width, height) * .036f;
            meterText.setTextSize(size);
            float left = width * .05f;
            float top = height * .13f;
            float pad = size * .45f;
            canvas.drawRoundRect(left, top, left + meterText.measureText(line) + 2 * pad, top + size + 2 * pad,
                    pad, pad, meterBox);
            canvas.drawText(line, left + pad, top + pad + size * .82f, meterText);
        }

        /**
         * Weiche Version mit Verlauf: Seite gegenüber blurDir mit lowAlpha, Seite in Richtung blurDir mit highAlpha.
         * Zwei Schichten mit festem Verlauf, damit beim Kippen pro Bild keine neuen Objekte entstehen:
         * Schicht 1 überall lowAlpha, Schicht 2 mit Verlauf 0 → DrawMath.secondLayer (zusammen genau highAlpha).
         */
        private void drawSoftSlope(Canvas canvas, int width, int height, float lowAlpha, float highAlpha) {
            float low = Math.min(1f, lowAlpha);
            if (low > 0) {
                paint.setAlpha(Math.round(255 * low));
                canvas.drawBitmap(softPhoto, null, destination, paint);
            }
            float second = DrawMath.secondLayer(low, highAlpha);
            if (second <= 0) return;
            if (slopeShader == null || softShaderBitmap != softPhoto) {
                softShader = new BitmapShader(softPhoto, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (slopeMask == null) {
                    slopeMask = new LinearGradient(0, 0, 1, 0, 0x00ffffff, 0xffffffff, Shader.TileMode.CLAMP);
                }
                slopeShader = new ComposeShader(softShader, slopeMask, PorterDuff.Mode.DST_IN);
                softShaderBitmap = softPhoto;
                gradientPaint.setShader(slopeShader);
            }
            softSource.set(0, 0, softPhoto.getWidth(), softPhoto.getHeight());
            softMatrix.setRectToRect(softSource, destination, Matrix.ScaleToFit.FILL);
            softShader.setLocalMatrix(softMatrix);
            // Verlauf von der hinteren zur vorderen Seite legen: Länge, Richtung, Startpunkt
            float cx = width / 2f, cy = height / 2f;
            float reach = Math.max(1f, Math.abs(blurDir[0]) * width / 2f + Math.abs(blurDir[1]) * height / 2f);
            maskMatrix.setScale(2 * reach, 2 * reach);
            maskMatrix.postRotate((float) Math.toDegrees(Math.atan2(blurDir[1], blurDir[0])));
            maskMatrix.postTranslate(cx - blurDir[0] * reach, cy - blurDir[1] * reach);
            slopeMask.setLocalMatrix(maskMatrix);
            gradientPaint.setAlpha(Math.round(255 * second));
            canvas.drawRect(destination, gradientPaint);
        }
    }
}
