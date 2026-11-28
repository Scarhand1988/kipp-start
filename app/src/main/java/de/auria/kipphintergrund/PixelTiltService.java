package de.auria.kipphintergrund;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Kipp-Effekt auf fremdem Startbildschirm, z. B. dem Pixel-Launcher (neu in 0.45). Android lässt keine App in das Fenster
 * einer anderen zeichnen; deshalb der Umweg über eine Bedienungshilfe:
 *
 * 1. Gerade gehalten und mit dem fremden Startbildschirm vorne macht sie ab und zu ein Bildschirmfoto (nur im Speicher).
 * 2. Beim Kippen legt sie das Foto als nicht berührbares Fenster darüber und zeichnet denselben Effekt wie Kipp-Start:
 *    die weggekippte Seite scharf, die andere unscharf, dunkler und farblos. Tipper und Wischen gehen zum Launcher durch.
 * 3. Bei Berührung, Wischen, Fensterwechsel oder Sperre verschwindet das Fenster sofort.
 *
 * Die Kippwerte kommen vom Kipp-Hintergrund (TiltBus); er muss als Hintergrund aktiv sein. Vom Fensterinhalt verwendet
 * wird nur der Paketname der App im Vordergrund. Kein Internet, nichts wird gespeichert.
 */
public final class PixelTiltService extends AccessibilityService implements TiltBus.Listener {
    private static final String TAG = "KippPixel";
    /** Wie oft nachgesehen wird, welche App vorne ist (Fallback zu den Fenster-Ereignissen). */
    private static final long POLL_MS = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private KeyguardManager keyguard;
    private SharedPreferences launcherPrefs;
    private PowerManager power;
    private FrameView view;
    /** Winziges, unsichtbares Fenster, das jede Berührung irgendwo auf dem Bildschirm meldet (ACTION_OUTSIDE). */
    private View touchWatch;
    private boolean touchAdded;
    private boolean viewAdded;
    private boolean shown;
    private String homePackage;
    private String frontPackage;
    private boolean capturing;
    private long lastCapture;
    private long lastDisturb;
    private long frameTime;
    /** Wann das Effekt-Fenster zuletzt verschwand – kurz danach kein Foto, sonst wäre es noch darauf. */
    private long hiddenAt;
    private static final long HIDE_SETTLE_MS = 250;
    private Bitmap frame;
    private float density;
    // zuletzt gesetzter Effekt
    private int appliedBlur = -1;
    private int appliedDim = -1;
    private float appliedDx = 2f;
    private float appliedDy = 2f;
    private final float[] ramp = new float[3];
    private final float[] rampVector = new float[2];

    /** Langsamer nachsehen, wenn der Bildschirm aus ist oder der Kipp-Hintergrund nicht läuft (schont den Akku). */
    private static final long IDLE_POLL_MS = 2000;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean active = (power == null || power.isInteractive()) && TiltBus.wallpaperVisible();
            if (active) {
                readFront();
                update();
            } else {
                hide();
            }
            main.postDelayed(this, active ? POLL_MS : IDLE_POLL_MS);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows = getSystemService(WindowManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        power = getSystemService(PowerManager.class);
        launcherPrefs = getSharedPreferences("launcher", Context.MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        homePackage = findHomePackage();
        view = new FrameView(this);
        addOverlay();
        addTouchWatch();
        TiltBus.setOverlayListener(this);
        main.post(poll);
    }

    private void addOverlay() {
        if (viewAdded || windows == null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0);
        params.setTitle("Kipp-Effekt");
        view.setVisibility(View.INVISIBLE);
        try {
            windows.addView(view, params);
            viewAdded = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Effekt-Fenster nicht möglich: " + e.getMessage());
        }
    }

    /**
     * Jede Berührung (Tippen, Wischen, Leiste herunterziehen) blendet das Effekt-Fenster sofort aus. Das Fenster ist
     * 1 × 1 Pixel groß und durchsichtig; mit FLAG_WATCH_OUTSIDE_TOUCH meldet Android jedes Aufsetzen außerhalb davon
     * als ACTION_OUTSIDE (ohne Koordinaten fremder Fenster). Die Berührung selbst geht unverändert weiter.
     */
    private void addTouchWatch() {
        if (touchAdded || windows == null) return;
        touchWatch = new View(this);
        touchWatch.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                disturb();
            }
            return false;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT);
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        params.x = -1; // halb außerhalb: das eine Pixel fängt praktisch keinen Tipper ab
        params.y = -1;
        params.setTitle("Kipp-Effekt Berührung");
        try {
            windows.addView(touchWatch, params);
            touchAdded = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Berührungsmelder nicht möglich: " + e.getMessage());
        }
    }

    /** Paketname des Standard-Startbildschirms (z. B. Pixel-Launcher). */
    private String findHomePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null ? info.activityInfo.packageName : null;
    }

    private void readFront() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (pkg != null) frontPackage = pkg.toString();
            }
        } catch (RuntimeException ignored) {
            // Fenster wechselt gerade – beim nächsten Durchlauf erneut
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                CharSequence pkg = event.getPackageName();
                if (pkg != null && !getPackageName().contentEquals(pkg)) frontPackage = pkg.toString();
                homePackage = findHomePackage();
                disturb();
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
            case AccessibilityEvent.TYPE_VIEW_LONG_CLICKED:
                disturb();
                break;
            default:
                break;
        }
    }

    /** Etwas hat sich bewegt: Fenster sofort weg, altes Foto gilt nicht mehr. */
    private void disturb() {
        lastDisturb = SystemClock.uptimeMillis();
        update();
    }

    @Override public void onInterrupt() {
        hide();
    }

    @Override public void onTilt(float soft, float dark, float unlock) {
        update();
    }

    private boolean homeFront() {
        return PixelMode.homeAtRest(frontPackage, homePackage, getPackageName(),
                TiltBus.wallpaperVisible(), TiltBus.wallpaperZoom());
    }

    private boolean unlocked() {
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    private void update() {
        long now = SystemClock.uptimeMillis();
        // Seitenwechsel, Zoom oder Sichtbarkeit des Hintergrunds: Foto zeigt einen alten Stand
        long wallChange = TiltBus.wallpaperChange();
        if (wallChange > lastDisturb) lastDisturb = wallChange;
        float soft = TiltBus.soft();
        boolean home = homeFront();
        boolean open = unlocked();
        if (PixelMode.shouldShow(now, soft, home, open, frame != null, frameTime, lastDisturb)) {
            show(soft, TiltBus.dark(), TiltBus.dirX(), TiltBus.dirY());
            return;
        }
        hide();
        if (!capturing && now - hiddenAt >= HIDE_SETTLE_MS
                && PixelMode.shouldCapture(now, lastCapture, lastDisturb, soft, home, open, shown)) capture();
    }

    private void capture() {
        capturing = true;
        lastCapture = SystemClock.uptimeMillis();
        final long started = lastCapture;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    capturing = false;
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap bitmap;
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Bildschirmfoto unbrauchbar: " + e.getMessage());
                        return;
                    } finally {
                        buffer.close();
                    }
                    if (bitmap == null) return;
                    // Verwerfen: während des Fotos berührt, Fenster gewechselt, Effekt-Fenster war sichtbar
                    // oder das Foto passt nicht zur Fenstergröße (z. B. gedreht)
                    if (lastDisturb > started || TiltBus.wallpaperChange() > started || !homeFront()
                            || shown || hiddenAt > started - HIDE_SETTLE_MS || !fits(bitmap)) {
                        bitmap.recycle();
                        return;
                    }
                    // Altes Foto nicht selbst freigeben: es kann noch im letzten gezeichneten Bild stecken (GC räumt es)
                    frame = bitmap;
                    frameTime = started;
                    view.setFrame(bitmap);
                    update();
                }

                @Override public void onFailure(int errorCode) {
                    capturing = false; // z. B. zu schnell hintereinander – der nächste Durchlauf versucht es erneut
                }
            });
        } catch (RuntimeException e) {
            capturing = false;
            Log.e(TAG, "Bildschirmfoto nicht möglich: " + e.getMessage());
        }
    }

    /** Passt das Foto zum Effekt-Fenster? Ein anders geformtes Foto würde verzerrt. */
    private boolean fits(Bitmap bitmap) {
        int w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) return true; // Fenster noch nicht vermessen – beim Zeichnen wird gestreckt
        return Math.abs(bitmap.getWidth() - w) <= 2 && Math.abs(bitmap.getHeight() - h) <= 2;
    }

    private void show(float soft, float dark, float dirX, float dirY) {
        if (!viewAdded) return;
        applyEffect(soft, dark, dirX, dirY);
        if (!shown) {
            view.setVisibility(View.VISIBLE);
            shown = true;
        }
    }

    private void hide() {
        if (!shown) return;
        view.setVisibility(View.INVISIBLE);
        view.setRenderEffect(null);
        shown = false;
        hiddenAt = SystemClock.uptimeMillis();
        appliedBlur = -1;
        appliedDim = -1;
    }

    /** Gleicher Verlauf wie Kipp-Start (LauncherActivity.applySheetDepth): scharfe Seite oben drauf, darunter weich. */
    private void applyEffect(float soft, float dark, float dirX, float dirY) {
        int w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        boolean flip = launcherPrefs.getBoolean(SettingsSheet.DEPTH_FLIP, false);
        float bx = Depth3d.blurX(dirX, flip);
        float by = Depth3d.blurY(dirY, flip);
        int blur = LauncherMath.settle(Math.max(0, appliedBlur), LauncherMath.blurPixels(soft, 0f, density), LauncherMath.BLUR_STEP);
        int dim = LauncherMath.settle(Math.max(0, appliedDim), DrawMath.dimLevel(dark), LauncherMath.DIM_STEP);
        if (appliedBlur >= 0 && blur == appliedBlur && dim == appliedDim
                && Math.abs(bx - appliedDx) < .01f && Math.abs(by - appliedDy) < .01f) return;
        appliedBlur = blur;
        appliedDim = dim;
        appliedDx = bx;
        appliedDy = by;
        float m = (float) Math.hypot(bx, by);
        RenderEffect softPart = chain(dimEffect(dim), blur > 0 ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
        if (m < 1e-4f || blur <= 0) {
            view.setRenderEffect(softPart);
            return;
        }
        Depth3d.ramp(m, ramp);
        Depth3d.rampVector(bx / m, by / m, w, h, rampVector);
        float cx = w / 2f, cy = h / 2f;
        int keep = Math.round(255f * (1f - ramp[2]));
        LinearGradient mask = new LinearGradient(
                cx + rampVector[0] * ramp[0], cy + rampVector[1] * ramp[0],
                cx + rampVector[0] * ramp[1], cy + rampVector[1] * ramp[1],
                keep << 24, 0, Shader.TileMode.CLAMP);
        // Die scharfe Seite wird erst bei starker Neigung dunkler (wie die Symbole in Kipp-Start)
        int sharpDim = Math.round(dim * LauncherMath.depthShade(0f, m));
        RenderEffect sharpSource = dimEffect(sharpDim);
        if (sharpSource == null) sharpSource = RenderEffect.createOffsetEffect(0f, 0f);
        RenderEffect sharpPart = RenderEffect.createBlendModeEffect(
                RenderEffect.createShaderEffect(mask), sharpSource, BlendMode.SRC_IN);
        try {
            view.setRenderEffect(softPart == null ? sharpPart
                    : RenderEffect.createBlendModeEffect(softPart, sharpPart, BlendMode.SRC_OVER));
        } catch (RuntimeException e) {
            view.setRenderEffect(softPart);
        }
    }

    private static RenderEffect chain(RenderEffect outer, RenderEffect inner) {
        if (outer == null) return inner;
        if (inner == null) return outer;
        return RenderEffect.createChainEffect(outer, inner);
    }

    /** Abdunkeln mit dem Milchglas-Ton von Kipp-Start (wie EffectCache). */
    private static RenderEffect dimEffect(int dim) {
        dim = Math.max(0, Math.min(255, dim));
        if (dim == 0) return null;
        int keep = DrawMath.dimMultiply(dim);
        return RenderEffect.createColorFilterEffect(new LightingColorFilter(Color.rgb(keep, keep, keep), Color.rgb(
                DrawMath.dimAdd(dim, Effects.FROST_RED),
                DrawMath.dimAdd(dim, Effects.FROST_GREEN),
                DrawMath.dimAdd(dim, Effects.FROST_BLUE))));
    }

    @Override public boolean onUnbind(Intent intent) {
        cleanUp();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        cleanUp();
        super.onDestroy();
    }

    private void cleanUp() {
        main.removeCallbacksAndMessages(null);
        TiltBus.clearOverlayListener(this);
        if (viewAdded && windows != null) {
            try {
                windows.removeView(view);
            } catch (RuntimeException ignored) {}
            viewAdded = false;
        }
        if (touchAdded && windows != null) {
            try {
                windows.removeView(touchWatch);
            } catch (RuntimeException ignored) {}
            touchAdded = false;
        }
        shown = false;
        frame = null;
        if (view != null) view.setFrame(null);
    }

    /** Zeichnet das Bildschirmfoto bildschirmfüllend; der Effekt liegt als RenderEffect auf der Ansicht. */
    private static final class FrameView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF target = new RectF();
        private Bitmap bitmap;

        FrameView(Context context) {
            super(context);
        }

        void setFrame(Bitmap b) {
            bitmap = b;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            Bitmap b = bitmap;
            if (b == null || b.isRecycled()) return;
            target.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(b, null, target, paint);
        }
    }
}
