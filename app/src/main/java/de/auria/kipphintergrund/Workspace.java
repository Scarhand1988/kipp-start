package de.auria.kipphintergrund;

import android.app.WallpaperManager;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

/**
 * Die Startbildschirm-Seiten nebeneinander (neu in 0.12): Blättern, Wischen nach oben (App-Übersicht)
 * und unten (Benachrichtigungen), Zusammenziehen oder langes Drücken auf eine leere Stelle (Einstellungen).
 * Beim Blättern wird die hereinkommende Seite wie im Video durchscheinend und unscharf und erst in der
 * Mitte klar.
 *
 * Neu in 0.13 – Blättern mit Absatz wie im Video: Die Seite bleibt zuerst stehen, während der Finger
 * losgeht. Erst nach LauncherMath.DETENT der Breite rastet sie aus und blättert von selbst mit einer
 * gedämpften Feder auf die nächste Seite; der Finger zieht sie danach nicht mehr. Ausschaltbar.
 */
final class Workspace extends ViewGroup {
    interface Callbacks {
        void onOpenDrawer();

        void onOpenSheet();

        void onExpandNotifications();

        void onScrollPosition(float position);
    }

    private static final int NONE = 0;
    private static final int PAGE = 1;
    private static final int VERTICAL = 2;

    private final EffectCache effects;
    private final WallpaperManager wallpaper;
    private final float density;
    private final int touchSlop;
    private final int flingVelocity;
    private final int maxVelocity;
    private VelocityTracker velocity;
    private Callbacks callbacks;
    private int mode = NONE;
    private float downX;
    private float downY;
    private float lastX;
    private int startPage;
    private int currentPage;
    private float pinchStart;
    private boolean pinched;
    private boolean transitions = true;
    private boolean detent = true;
    /** Absatz überwunden: die Seite blättert von selbst, der Finger zählt bis zum Loslassen nicht mehr. */
    private boolean committed;
    private boolean longPressPending;
    private int padLeft;
    private int padTop;
    private int padRight;
    private int padBottom;
    // Feder für das Einrasten
    private boolean springing;
    private long springStart;
    private int springFrom;
    private int springTo;
    private float springVelocity;
    private final Runnable longPress = () -> {
        longPressPending = false;
        if (mode == NONE && !pinched && callbacks != null) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            callbacks.onOpenSheet();
        }
    };

    Workspace(Context context, EffectCache effects) {
        super(context);
        this.effects = effects;
        wallpaper = WallpaperManager.getInstance(context);
        density = context.getResources().getDisplayMetrics().density;
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        flingVelocity = Math.max(config.getScaledMinimumFlingVelocity() * 4, Math.round(400 * density));
        maxVelocity = config.getScaledMaximumFlingVelocity();
        setHapticFeedbackEnabled(true);
        setMotionEventSplittingEnabled(false);
    }

    void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    void setTransitions(boolean on) {
        transitions = on;
        applyPageEffects();
    }

    void setDetent(boolean on) {
        detent = on;
    }

    void setPagePadding(int left, int top, int right, int bottom) {
        padLeft = left;
        padTop = top;
        padRight = right;
        padBottom = bottom;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setPadding(left, top, right, bottom);
    }

    CellLayout addPage() {
        CellLayout page = new CellLayout(getContext());
        page.setPadding(padLeft, padTop, padRight, padBottom);
        addView(page);
        updateWallpaperSteps();
        return page;
    }

    int pageCount() {
        return getChildCount();
    }

    CellLayout page(int index) {
        return (CellLayout) getChildAt(index);
    }

    int currentPage() {
        return Math.max(0, Math.min(getChildCount() - 1, currentPage));
    }

    CellLayout currentLayout() {
        return getChildCount() == 0 ? null : page(currentPage());
    }

    void snapToPage(int page, boolean animate) {
        snapToPage(page, animate, 0f);
    }

    /** speed = Tempo Richtung Ziel in Pixel pro Sekunde (z. B. Fingertempo beim Ausrasten). */
    void snapToPage(int page, boolean animate, float speed) {
        if (getChildCount() == 0) return;
        page = Math.max(0, Math.min(getChildCount() - 1, page));
        currentPage = page;
        int target = page * getWidth();
        int distance = target - getScrollX();
        if (!animate || distance == 0 || getWidth() == 0) {
            springing = false;
            scrollTo(target, 0);
            applyPageEffects();
            return;
        }
        springing = true;
        springStart = AnimationUtils.currentAnimationTimeMillis();
        springFrom = getScrollX();
        springTo = target;
        springVelocity = Math.max(0f, speed) / Math.abs(distance);
        postInvalidateOnAnimation();
    }

    @Override public void computeScroll() {
        if (!springing) return;
        float t = (AnimationUtils.currentAnimationTimeMillis() - springStart) / 1000f;
        if (LauncherMath.springDone(t, springVelocity)) {
            springing = false;
            scrollTo(springTo, 0);
            return;
        }
        float p = LauncherMath.springProgress(t, springVelocity);
        scrollTo(Math.round(springFrom + (springTo - springFrom) * p), 0);
        postInvalidateOnAnimation();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int w = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int h = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(i * width, 0, (i + 1) * width, height);
        if (springing) springTo = currentPage() * width;
        else scrollTo(currentPage() * width, 0);
        applyPageEffects();
    }

    @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        applyPageEffects();
        if (getWidth() > 0) {
            if (callbacks != null) callbacks.onScrollPosition(getScrollX() / (float) getWidth());
            if (getWindowToken() != null) {
                try {
                    wallpaper.setWallpaperOffsets(getWindowToken(),
                            LauncherMath.wallpaperOffset(getScrollX(), getWidth(), getChildCount()), .5f);
                } catch (RuntimeException ignored) {}
            }
        }
    }

    private void updateWallpaperSteps() {
        int pages = Math.max(1, getChildCount());
        try {
            wallpaper.setWallpaperOffsetSteps(pages > 1 ? 1f / (pages - 1) : 1f, 1f);
        } catch (RuntimeException ignored) {}
    }

    /** Seitenübergang wie im Video: außerhalb der Mitte durchscheinend, unscharf, leicht vergrößert. */
    void applyPageEffects() {
        int width = getWidth();
        if (width == 0) return;
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            float offset = (i * width - getScrollX()) / (float) width;
            if (!transitions || Math.abs(offset) < .001f) {
                page.setAlpha(1f);
                page.setRenderEffect(null);
                page.setScaleX(1f);
                page.setScaleY(1f);
                continue;
            }
            if (!LauncherMath.pageVisible(offset)) {
                // Ganz außerhalb (ohnehin unsichtbar): kein Weichzeichnen in jedem Bild (neu in 0.15)
                page.setAlpha(0f);
                page.setRenderEffect(null);
                page.setScaleX(1f);
                page.setScaleY(1f);
                continue;
            }
            page.setAlpha(LauncherMath.pageAlpha(offset));
            // Gerade Pixelstufen: gleiche Stufe = gleiches Effekt-Objekt aus dem Zwischenspeicher, nichts neu weichzuzeichnen
            page.setRenderEffect(effects.get(LauncherMath.pageBlurPixels(offset, density), 0));
            float scale = LauncherMath.pageScale(offset);
            page.setScaleX(scale);
            page.setScaleY(scale);
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        boolean handled = super.dispatchTouchEvent(ev);
        int action = ev.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        return handled;
    }

    private float fingerVelocityX() {
        if (velocity == null) return 0f;
        velocity.computeCurrentVelocity(1000, maxVelocity);
        return velocity.getXVelocity();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(ev);
                if (springing) {
                    // Seite steht praktisch (neu in 0.15): Antippen trifft das Symbol, die Feder läuft weich zu Ende.
                    // Vorher war jedes Tippen bis zum völligen Stillstand (ca. 0,7 s) verschluckt.
                    if (LauncherMath.tapThrough(springTo - getScrollX(), getWidth())) return false;
                    // Seite noch deutlich in Bewegung: Antippen trifft keine Symbole, ein neues Wischen blättert weiter
                    mode = PAGE;
                    if (!detent) springing = false;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                return detect(ev);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelEmptyPress();
                mode = NONE;
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Niemand darunter hat das Antippen angenommen: leere Stelle
                CellLayout page = currentLayout();
                if (page != null && page.isEmptyAt(ev.getX(), ev.getY()) && mode == NONE) {
                    longPressPending = true;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == NONE) detect(ev);
                if (mode == PAGE) movePage(ev);
                return true;
            case MotionEvent.ACTION_UP:
                finish(ev, false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                finish(ev, true);
                return true;
            default:
                return true;
        }
    }

    /** Blättern während der Finger sich bewegt. */
    private void movePage(MotionEvent ev) {
        if (detent) {
            if (committed) return;
            // Absatz: Die Seite steht, bis der Finger weit genug ist – dann blättert sie selbst
            int step = LauncherMath.detentStep(ev.getX() - downX, getWidth());
            int target = currentPage() + step;
            if (step != 0 && target >= 0 && target < getChildCount()) {
                committed = true;
                float speed = Math.max(0f, -fingerVelocityX() * step);
                snapToPage(target, true, speed);
            }
            return;
        }
        // Ohne Absatz: Seite folgt dem Finger, am Rand mit Gummiband
        float delta = lastX - ev.getX();
        lastX = ev.getX();
        int max = Math.max(0, (getChildCount() - 1) * getWidth());
        float next = getScrollX() + delta;
        if (next < 0 || next > max) next = getScrollX() + delta * .35f;
        scrollTo(Math.round(next), 0);
    }

    private void down(MotionEvent ev) {
        downX = lastX = ev.getX();
        downY = ev.getY();
        mode = NONE;
        pinched = false;
        pinchStart = 0;
        committed = false;
        startPage = currentPage();
        cancelEmptyPress();
    }

    private void pointerDown(MotionEvent ev) {
        cancelEmptyPress();
        if (ev.getPointerCount() == 2) pinchStart = span(ev);
    }

    /** Erkennt Blättern, senkrechtes Wischen und Zusammenziehen. true = ab jetzt selbst übernehmen. */
    private boolean detect(MotionEvent ev) {
        if (ev.getPointerCount() >= 2) {
            if (!pinched && LauncherMath.pinchedIn(pinchStart, span(ev))) {
                pinched = true;
                cancelEmptyPress();
                if (callbacks != null) callbacks.onOpenSheet();
            }
            return pinched;
        }
        if (pinched) return true;
        if (mode == PAGE) return true;
        float dx = ev.getX() - downX;
        float dy = ev.getY() - downY;
        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
            mode = PAGE;
            // Ohne Absatz hält der Finger die Seite fest (auch wenn sie beim Aufsetzen noch auslief)
            if (!detent) springing = false;
            lastX = ev.getX();
            cancelEmptyPress();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
            mode = VERTICAL;
            cancelEmptyPress();
            return true;
        }
        return false;
    }

    private void finish(MotionEvent ev, boolean cancelled) {
        cancelEmptyPress();
        float vx = 0;
        float vy = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000, maxVelocity);
            vx = velocity.getXVelocity();
            vy = velocity.getYVelocity();
        }
        if (mode == PAGE) {
            if (detent) {
                // Schneller Wisch unter dem Absatz blättert ebenfalls; sonst ist die Seite nie losgegangen
                float dx = ev.getX() - downX;
                if (!committed && !cancelled && Math.abs(vx) > flingVelocity && Math.abs(dx) > touchSlop * 2) {
                    int step = vx < 0 ? 1 : -1;
                    int target = currentPage() + step;
                    if (target >= 0 && target < getChildCount()) snapToPage(target, true, Math.abs(vx));
                }
            } else {
                int target = cancelled ? Math.round(getScrollX() / (float) Math.max(1, getWidth()))
                        : LauncherMath.snapPage(getScrollX(), getWidth(), vx, flingVelocity, getChildCount(), startPage);
                snapToPage(target, true, Math.abs(vx));
            }
        } else if (mode == VERTICAL && !cancelled && callbacks != null) {
            float dy = ev.getY() - downY;
            if (LauncherMath.opensDrawer(dy, vy, 70 * density)) callbacks.onOpenDrawer();
            else if (dy > 70 * density) callbacks.onExpandNotifications();
        }
        mode = NONE;
        committed = false;
    }

    private void cancelEmptyPress() {
        if (longPressPending) {
            removeCallbacks(longPress);
            longPressPending = false;
        }
    }

    private static float span(MotionEvent ev) {
        if (ev.getPointerCount() < 2) return 0;
        return (float) Math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1));
    }

    @Override public boolean shouldDelayChildPressedState() {
        return true;
    }
}
