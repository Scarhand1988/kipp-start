package de.auria.kipphintergrund;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Eigener Startbildschirm „Kipp-Start“ (neu in 0.12). Zeigt den Kipp-Hintergrund dahinter und zeichnet
 * Symbole, Widgets, Dock und Einstellungen selbst. So können beim Kippen – wie im Video – alle Inhalte
 * zusammen mit dem Hintergrund verschwimmen und dunkler werden (Werte kommen per TiltBus vom Hintergrund).
 */
public final class LauncherActivity extends Activity implements Workspace.Callbacks, DrawerView.Callbacks,
        SettingsSheet.Callbacks, WidgetView.LongPress, TiltBus.Listener {
    private static final int HOST_ID = 2611;
    private static final int REQUEST_BIND = 41;
    private static final int REQUEST_CONFIGURE = 42;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final EffectCache effects = new EffectCache();
    private SharedPreferences prefs;
    private LauncherApps launcherApps;
    private AppWidgetManager widgetManager;
    private AppWidgetHost widgetHost;
    private HomeModel model;
    private AppIndex index = AppIndex.empty();
    private float density;
    private int iconSize;
    private int insetTop;
    private int insetBottom;

    private DragLayer root;
    private FrameLayout content;
    private Workspace workspace;
    private LinearLayout bottomBar;
    private PageDots dots;
    private LinearLayout dock;
    private final IconView[] dockViews = new IconView[LauncherMath.DOCK];
    private DrawerView drawer;
    private SettingsSheet sheet;
    private LinearLayout dropBar;
    private TextView dropRemove;
    private TextView dropInfo;

    private boolean tiltOn;
    private boolean transitionsOn;
    private boolean labelsOn;
    private boolean glowOn;
    private boolean unlockOn;
    private boolean detentOn;
    private int pendingWidgetId = -1;
    private int loadGeneration;

    /** Falls der Hintergrund mitten in der Entsperr-Animation verstummt: Symbole nicht unsichtbar lassen. */
    private final Runnable unlockWatchdog = new Runnable() {
        @Override public void run() {
            if (TiltBus.unlock() <= 0) return;
            if (TiltBus.silence() > 400) onTilt(TiltBus.soft(), TiltBus.dark(), 0f);
            else main.postDelayed(this, 300);
        }
    };

    private final LauncherApps.Callback appsCallback = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String packageName, UserHandle user) {
            model.removePackage(packageName);
            model.save(LauncherActivity.this);
            loadApps();
        }

        @Override public void onPackageAdded(String packageName, UserHandle user) {
            loadApps();
        }

        @Override public void onPackageChanged(String packageName, UserHandle user) {
            loadApps();
        }

        @Override public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {
            loadApps();
        }

        @Override public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) {
            loadApps();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        density = getResources().getDisplayMetrics().density;
        iconSize = dp(58);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        readSettings();
        launcherApps = getSystemService(LauncherApps.class);
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(getApplicationContext(), HOST_ID) {
            @Override protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
                return new WidgetView(context);
            }
        };
        model = HomeModel.load(this);
        buildViews();
        setContentView(root);
        launcherApps.registerCallback(appsCallback, main);
        TiltBus.setListener(this);
        loadApps();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void readSettings() {
        tiltOn = prefs.getBoolean(SettingsSheet.TILT, true);
        transitionsOn = prefs.getBoolean(SettingsSheet.TRANSITIONS, true);
        labelsOn = prefs.getBoolean(SettingsSheet.LABELS, true);
        glowOn = prefs.getBoolean(SettingsSheet.GLOW, true);
        unlockOn = prefs.getBoolean(SettingsSheet.UNLOCK, true);
        detentOn = prefs.getBoolean(SettingsSheet.DETENT, true);
    }

    private void buildViews() {
        root = new DragLayer(this);
        content = new FrameLayout(this);
        content.setClipChildren(false);
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        workspace = new Workspace(this, effects);
        workspace.setCallbacks(this);
        workspace.setTransitions(transitionsOn);
        workspace.setDetent(detentOn);
        content.addView(workspace, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setClipChildren(false);
        dots = new PageDots(this);
        bottomBar.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        // „Suche“ über dem Dock wie im Video
        TextView searchPill = new TextView(this);
        searchPill.setText("Suche");
        searchPill.setTextColor(0xE6FFFFFF);
        searchPill.setTextSize(13);
        searchPill.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x40000000);
        pill.setCornerRadius(dp(15));
        searchPill.setBackground(pill);
        searchPill.setPadding(dp(22), 0, dp(22), 0);
        searchPill.setOnClickListener(v -> openDrawer(true));
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        pillParams.gravity = Gravity.CENTER_HORIZONTAL;
        pillParams.topMargin = dp(2);
        pillParams.bottomMargin = dp(12);
        bottomBar.addView(searchPill, pillParams);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(10), 0, dp(10), 0);
        for (int i = 0; i < LauncherMath.DOCK; i++) {
            IconView view = new IconView(this, iconSize);
            view.dockSlot = i;
            view.setOnClickListener(this::onIconClick);
            view.setOnLongClickListener(this::onIconLongClick);
            dockViews[i] = view;
            dock.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        bottomBar.addView(dock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
        content.addView(bottomBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        drawer = new DrawerView(this, iconSize, this);
        content.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet = new SettingsSheet(this, prefs, this);
        content.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Ablagefelder beim Ziehen (nicht weichgezeichnet)
        dropBar = new LinearLayout(this);
        dropBar.setOrientation(LinearLayout.HORIZONTAL);
        dropBar.setVisibility(View.GONE);
        dropBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xCC000000, 0x00000000}));
        dropRemove = dropLabel("Entfernen");
        dropInfo = dropLabel("App-Info");
        dropBar.addView(dropRemove, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        dropBar.addView(dropInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(dropBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.TOP));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            applyInsets();
            return insets;
        });
    }

    private TextView dropLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void applyInsets() {
        bottomBar.setPadding(0, 0, 0, insetBottom + dp(6));
        int bottomHeight = dp(14) + dp(2) + dp(30) + dp(12) + dp(84) + insetBottom + dp(6);
        workspace.setPagePadding(dp(10), insetTop + dp(10), dp(10), bottomHeight);
        drawer.setInsets(insetTop, insetBottom);
        sheet.setInsets(insetBottom);
        dropBar.setPadding(0, insetTop, 0, 0);
        ViewGroup.LayoutParams params = dropBar.getLayoutParams();
        params.height = insetTop + dp(72);
        dropBar.setLayoutParams(params);
    }

    private void loadApps() {
        int generation = ++loadGeneration;
        boolean glow = glowOn;
        int size = iconSize;
        Context context = getApplicationContext();
        worker.execute(() -> {
            AppIndex loaded = AppIndex.load(context, size, glow);
            main.post(() -> {
                if (isDestroyed() || generation != loadGeneration) return;
                index = loaded;
                if (model.fresh && !index.isEmpty()) seedDefaults();
                drawer.setApps(index.apps);
                bindAll();
            });
        });
    }

    /** Erste Belegung: Telefon, Nachrichten, Browser, Kamera ins Dock, dazu bis zu 16 Apps (oben Platz für Widgets). */
    private void seedDefaults() {
        String[] defaults = {
                packageFor(new Intent(Intent.ACTION_DIAL)),
                Telephony.Sms.getDefaultSmsPackage(this),
                packageFor(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
                packageFor(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))};
        HashSet<ComponentName> used = new HashSet<>();
        for (int i = 0; i < LauncherMath.DOCK; i++) {
            AppIndex.App app = index.firstOf(defaults[i]);
            if (app != null && used.add(app.component)) model.dock[i] = app.component;
        }
        int placed = 0;
        for (AppIndex.App app : index.apps) {
            if (placed >= 16) break;
            if (used.contains(app.component)) continue;
            model.items.add(HomeModel.Item.app(app.component, 0, placed % LauncherMath.COLUMNS, 2 + placed / LauncherMath.COLUMNS));
            placed++;
        }
        model.pages = 1;
        model.defaultPage = 0;
        model.save(this);
    }

    private String packageFor(Intent intent) {
        try {
            ResolveInfo info = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) return null;
            String pkg = info.activityInfo.packageName;
            return "android".equals(pkg) ? null : pkg; // Auswahl-Dialog statt Standard-App
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void bindAll() {
        int page = workspace.pageCount() == 0 ? model.defaultPage : workspace.currentPage();
        workspace.removeAllViews();
        for (int p = 0; p < model.pages; p++) workspace.addPage();
        for (HomeModel.Item item : model.items) {
            if (item.page < 0 || item.page >= model.pages) continue;
            View view = viewFor(item);
            if (view != null) workspace.page(item.page).addView(view, new CellLayout.Params(item.x, item.y, item.w, item.h));
        }
        for (int i = 0; i < LauncherMath.DOCK; i++) dockViews[i].bind(index.get(model.dock[i]), false);
        workspace.snapToPage(Math.min(page, model.pages - 1), false);
        dots.set(model.pages, workspace.currentPage());
    }

    private View viewFor(HomeModel.Item item) {
        if (item.type == HomeModel.Item.APP) {
            AppIndex.App app = index.get(item.component);
            if (app == null) return null;
            IconView view = new IconView(this, iconSize);
            view.item = item;
            view.bind(app, labelsOn);
            view.setOnClickListener(this::onIconClick);
            view.setOnLongClickListener(this::onIconLongClick);
            return view;
        }
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(item.widgetId);
        if (info == null) return null;
        AppWidgetHostView host = widgetHost.createView(this, item.widgetId, info);
        if (host instanceof WidgetView) {
            WidgetView view = (WidgetView) host;
            view.item = item;
            view.longPress = this;
        }
        return host;
    }

    // ---------- Starten ----------

    private void onIconClick(View v) {
        IconView icon = (IconView) v;
        if (icon.app != null) startApp(icon);
    }

    private void startApp(IconView icon) {
        Rect bounds = new Rect();
        icon.getGlobalVisibleRect(bounds);
        Bundle options = ActivityOptions.makeScaleUpAnimation(icon, 0, 0, icon.getWidth(), icon.getHeight()).toBundle();
        try {
            launcherApps.startMainActivity(icon.app.component, icon.app.user, bounds, options);
        } catch (RuntimeException e) {
            toast("Die App lässt sich nicht starten.");
        }
    }

    @Override public void onDrawerAppClick(IconView view) {
        if (view.app == null) return;
        startApp(view);
        main.postDelayed(() -> drawer.close(false), 350);
    }

    // ---------- Ziehen ----------

    private boolean onIconLongClick(View v) {
        IconView icon = (IconView) v;
        if (icon.app == null || root.isDragging()) return false;
        Drag drag = new Drag();
        drag.app = icon.app;
        drag.item = icon.item;
        drag.dockSlot = icon.dockSlot;
        drag.kind = icon.item != null ? Drag.HOME : icon.dockSlot >= 0 ? Drag.DOCK : Drag.DRAWER;
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        root.startDrag(drag, icon.app.icon, iconSize, iconSize, v);
        return true;
    }

    @Override public boolean onDrawerAppLongClick(IconView view) {
        boolean started = onIconLongClick(view);
        if (started) drawer.close(true);
        return started;
    }

    @Override public void onWidgetLongPress(WidgetView view) {
        if (view.item == null || root.isDragging() || view.getWidth() == 0 || view.getHeight() == 0) return;
        Bitmap snapshot = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(snapshot));
        Drag drag = new Drag();
        drag.kind = Drag.WIDGET;
        drag.item = view.item;
        root.startDrag(drag, snapshot, view.getWidth(), view.getHeight(), view);
    }

    private void dropOnPage(Drag d, float x, float y, float grabX, float grabY) {
        CellLayout page = workspace.currentLayout();
        if (page == null) return;
        int pageIndex = workspace.currentPage();
        if (d.kind == Drag.WIDGET) {
            HomeModel.Item item = d.item;
            int[] cell = page.cellAt(x - grabX + page.cellWidth() / 2f, y - grabY + page.cellHeight() / 2f);
            if (LauncherMath.fits(model.occupied(pageIndex, item), cell[0], cell[1], item.w, item.h)) {
                item.page = pageIndex;
                item.x = cell[0];
                item.y = cell[1];
            }
            return;
        }
        int[] cell = page.cellAt(x, y);
        HomeModel.Item target = model.itemAt(pageIndex, cell[0], cell[1]);
        switch (d.kind) {
            case Drag.HOME: {
                HomeModel.Item item = d.item;
                if (target == null) {
                    item.page = pageIndex;
                    item.x = cell[0];
                    item.y = cell[1];
                } else if (target != item && target.type == HomeModel.Item.APP) {
                    int oldPage = item.page;
                    int oldX = item.x;
                    int oldY = item.y;
                    item.page = target.page;
                    item.x = target.x;
                    item.y = target.y;
                    target.page = oldPage;
                    target.x = oldX;
                    target.y = oldY;
                }
                break;
            }
            case Drag.DOCK: {
                ComponentName component = model.dock[d.dockSlot];
                if (component == null) break;
                if (target == null) {
                    model.dock[d.dockSlot] = null;
                    model.items.add(HomeModel.Item.app(component, pageIndex, cell[0], cell[1]));
                } else if (target.type == HomeModel.Item.APP) {
                    model.dock[d.dockSlot] = target.component;
                    target.component = component;
                }
                break;
            }
            default: {
                int[] free = target == null ? cell
                        : LauncherMath.nearestFree(model.occupied(pageIndex, null), 1, 1, cell[0], cell[1]);
                if (free == null) {
                    toast("Diese Seite ist voll.");
                    break;
                }
                model.items.add(HomeModel.Item.app(d.app.component, pageIndex, free[0], free[1]));
                break;
            }
        }
    }

    private void dropOnDock(Drag d, int slot) {
        ComponentName old = model.dock[slot];
        switch (d.kind) {
            case Drag.HOME:
                model.dock[slot] = d.item.component;
                if (old != null) d.item.component = old;
                else model.items.remove(d.item);
                break;
            case Drag.DOCK:
                model.dock[slot] = model.dock[d.dockSlot];
                model.dock[d.dockSlot] = old;
                break;
            case Drag.DRAWER:
                model.dock[slot] = d.app.component;
                if (old != null) {
                    int page = workspace.currentPage();
                    int[] free = LauncherMath.firstFree(model.occupied(page, null), 1, 1, 0);
                    if (free != null) model.items.add(HomeModel.Item.app(old, page, free[0], free[1]));
                }
                break;
            default:
                break;
        }
    }

    private void removeDragged(Drag d) {
        if (d.kind == Drag.DOCK) {
            model.dock[d.dockSlot] = null;
        } else if (d.item != null) {
            model.items.remove(d.item);
            if (d.item.type == HomeModel.Item.WIDGET) widgetHost.deleteAppWidgetId(d.item.widgetId);
        }
    }

    private void showInfo(Drag d) {
        ComponentName component = d.app != null ? d.app.component : d.item != null ? d.item.component : null;
        if (component == null) return;
        try {
            launcherApps.startAppDetailsActivity(component, Process.myUserHandle(), null, null);
        } catch (RuntimeException e) {
            toast("App-Info lässt sich nicht öffnen.");
        }
    }

    private static final class Drag {
        static final int HOME = 0;
        static final int DOCK = 1;
        static final int DRAWER = 2;
        static final int WIDGET = 3;
        int kind;
        AppIndex.App app;
        HomeModel.Item item;
        int dockSlot = -1;
    }

    /** Wurzel-Ansicht: übernimmt beim Ziehen alle Berührungen und bewegt das gezogene Symbol. */
    private final class DragLayer extends FrameLayout {
        private Drag drag;
        private ImageView floating;
        private View hiddenSource;
        private float lastX;
        private float lastY;
        private float grabX;
        private float grabY;
        private int edgeDirection;
        private final Runnable edgeFlip = this::flipAtEdge;

        DragLayer(Context context) {
            super(context);
        }

        boolean isDragging() {
            return drag != null;
        }

        void startDrag(Drag d, Bitmap image, int width, int height, View source) {
            if (drag != null) endDrag();
            drag = d;
            int[] location = new int[2];
            source.getLocationInWindow(location);
            if (d.kind == Drag.WIDGET) {
                grabX = lastX - location[0];
                grabY = lastY - location[1];
            } else {
                grabX = width / 2f;
                grabY = height / 2f;
            }
            floating = new ImageView(getContext());
            floating.setImageBitmap(image);
            floating.setScaleType(ImageView.ScaleType.FIT_XY);
            addView(floating, new FrameLayout.LayoutParams(width, height));
            floating.setX(lastX - grabX);
            floating.setY(lastY - grabY);
            floating.animate().scaleX(1.12f).scaleY(1.12f).alpha(.92f).setDuration(150).start();
            if (d.kind != Drag.DRAWER) {
                source.setVisibility(View.INVISIBLE);
                hiddenSource = source;
            } else {
                hiddenSource = null;
            }
            dropRemove.setVisibility(d.kind == Drag.DRAWER ? View.GONE : View.VISIBLE);
            dropInfo.setVisibility(d.kind == Drag.WIDGET ? View.GONE : View.VISIBLE);
            dropRemove.setBackgroundColor(Color.TRANSPARENT);
            dropInfo.setBackgroundColor(Color.TRANSPARENT);
            dropBar.setVisibility(View.VISIBLE);
            dropBar.setAlpha(0f);
            dropBar.animate().alpha(1f).setDuration(150).start();
            edgeDirection = 0;
        }

        void cancel() {
            if (drag == null) return;
            endDrag();
            bindAll();
        }

        private void endDrag() {
            drag = null;
            removeCallbacks(edgeFlip);
            if (floating != null) {
                removeView(floating);
                floating = null;
            }
            if (hiddenSource != null) {
                hiddenSource.setVisibility(View.VISIBLE);
                hiddenSource = null;
            }
            dropBar.setVisibility(View.GONE);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            lastX = ev.getX();
            lastY = ev.getY();
            return super.dispatchTouchEvent(ev);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            return drag != null;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (drag == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    floating.setX(ev.getX() - grabX);
                    floating.setY(ev.getY() - grabY);
                    highlight(ev.getX(), ev.getY());
                    trackEdge(ev.getX());
                    break;
                case MotionEvent.ACTION_UP:
                    drop(ev.getX(), ev.getY());
                    break;
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    break;
                default:
                    break;
            }
            return true;
        }

        /** 1 = Entfernen, 2 = App-Info, 0 = kein Ablagefeld. */
        private int zone(float x, float y) {
            if (y > dropBar.getHeight()) return 0;
            if (dropRemove.getVisibility() == View.VISIBLE && x >= dropRemove.getLeft() && x < dropRemove.getRight()) return 1;
            if (dropInfo.getVisibility() == View.VISIBLE && x >= dropInfo.getLeft() && x < dropInfo.getRight()) return 2;
            return 0;
        }

        private void highlight(float x, float y) {
            int zone = zone(x, y);
            dropRemove.setBackgroundColor(zone == 1 ? 0x66E53935 : Color.TRANSPARENT);
            dropInfo.setBackgroundColor(zone == 2 ? 0x66FFFFFF : Color.TRANSPARENT);
        }

        private void trackEdge(float x) {
            int edge = dp(28);
            int direction = x < edge ? -1 : x > getWidth() - edge ? 1 : 0;
            if (direction == edgeDirection) return;
            edgeDirection = direction;
            removeCallbacks(edgeFlip);
            if (direction != 0) postDelayed(edgeFlip, 650);
        }

        /** Am Rand verweilen blättert um; hinter der letzten Seite entsteht eine neue. */
        private void flipAtEdge() {
            if (drag == null || edgeDirection == 0) return;
            int target = workspace.currentPage() + edgeDirection;
            if (target < 0) return;
            if (target >= workspace.pageCount()) {
                if (workspace.pageCount() >= HomeModel.MAX_PAGES) return;
                workspace.addPage();
                model.pages = workspace.pageCount();
            }
            workspace.snapToPage(target, true);
            postDelayed(edgeFlip, 900);
        }

        private void drop(float x, float y) {
            Drag d = drag;
            int zone = zone(x, y);
            float gx = grabX;
            float gy = grabY;
            endDrag();
            if (zone == 1) {
                removeDragged(d);
            } else if (zone == 2) {
                showInfo(d);
            } else {
                int[] dockLocation = new int[2];
                dock.getLocationInWindow(dockLocation);
                if (d.kind != Drag.WIDGET && y >= dockLocation[1]) dropOnDock(d, LauncherMath.dockSlot(x, getWidth()));
                else dropOnPage(d, x, y, gx, gy);
            }
            model.trimPages();
            model.save(LauncherActivity.this);
            bindAll();
        }
    }

    // ---------- Gesten und Einstellungen ----------

    @Override public void onOpenDrawer() {
        openDrawer(false);
    }

    private void openDrawer(boolean keyboard) {
        if (sheet.isOpen() || root.isDragging()) return;
        drawer.open(keyboard);
    }

    @Override public void onOpenSheet() {
        if (drawer.isOpen() || root.isDragging()) return;
        sheet.open();
    }

    @Override public void onExpandNotifications() {
        try {
            Object service = getSystemService("statusbar");
            service.getClass().getMethod("expandNotificationsPanel").invoke(service);
        } catch (Exception ignored) {
            // Nicht überall erlaubt – dann bleibt das Wischen vom oberen Rand
        }
    }

    @Override public void onScrollPosition(float position) {
        dots.set(workspace.pageCount(), position);
    }

    @Override public void onLauncherSetting(String key, boolean value) {
        readSettings();
        switch (key) {
            case SettingsSheet.TRANSITIONS:
                workspace.setTransitions(transitionsOn);
                break;
            case SettingsSheet.DETENT:
                workspace.setDetent(detentOn);
                break;
            case SettingsSheet.LABELS:
                bindAll();
                break;
            case SettingsSheet.GLOW:
                loadApps();
                break;
            default:
                onTilt(TiltBus.soft(), TiltBus.dark(), TiltBus.unlock());
                break;
        }
    }

    @Override public void onSetDefaultPage() {
        model.defaultPage = workspace.currentPage();
        model.save(this);
        toast("Diese Seite ist jetzt die Startseite.");
    }

    @Override public void onReloadIcons() {
        loadApps();
        toast("Symbole werden neu geladen.");
    }

    @Override public void onOpenWallpaperSettings() {
        startActivity(new Intent(this, SetupActivity.class));
    }

    @Override public void onChooseHomeApp() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Android konnte die Auswahl nicht öffnen.");
        }
    }

    // ---------- Widgets ----------

    @Override public void onAddWidget() {
        List<AppWidgetProviderInfo> providers;
        try {
            providers = widgetManager.getInstalledProvidersForProfile(Process.myUserHandle());
        } catch (RuntimeException e) {
            providers = new ArrayList<>();
        }
        if (providers.isEmpty()) {
            toast("Keine Widgets gefunden.");
            return;
        }
        PackageManager pm = getPackageManager();
        CellLayout layout = workspace.currentLayout();
        float cellWidth = layout == null || layout.cellWidth() <= 1 ? dp(90) : layout.cellWidth();
        float cellHeight = layout == null || layout.cellHeight() <= 1 ? dp(110) : layout.cellHeight();
        ArrayList<Object[]> entries = new ArrayList<>();
        for (AppWidgetProviderInfo info : providers) {
            String app;
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                app = info.provider.getPackageName();
            }
            int w = LauncherMath.span(info.targetCellWidth, info.minWidth, cellWidth, LauncherMath.COLUMNS);
            int h = LauncherMath.span(info.targetCellHeight, info.minHeight, cellHeight, LauncherMath.ROWS);
            entries.add(new Object[] {app + " – " + info.loadLabel(pm) + "  (" + w + "×" + h + ")", info});
        }
        Collator collator = Collator.getInstance();
        Collections.sort(entries, (a, b) -> collator.compare((String) a[0], (String) b[0]));
        String[] labels = new String[entries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (String) entries.get(i)[0];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Widget hinzufügen")
                .setItems(labels, (dialog, which) -> addWidget((AppWidgetProviderInfo) entries.get(which)[1]))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void addWidget(AppWidgetProviderInfo info) {
        int id = widgetHost.allocateAppWidgetId();
        pendingWidgetId = id;
        boolean bound;
        try {
            bound = widgetManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null);
        } catch (RuntimeException e) {
            bound = false;
        }
        if (bound) {
            configureOrPlace(id);
            return;
        }
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
        try {
            startActivityForResult(bind, REQUEST_BIND);
        } catch (ActivityNotFoundException e) {
            widgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            toast("Android erlaubt das Widget nicht.");
        }
    }

    private void configureOrPlace(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) {
            widgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            return;
        }
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Ohne Einrichtung platzieren
            }
        }
        placeWidget(id);
    }

    private void placeWidget(int id) {
        pendingWidgetId = -1;
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) {
            widgetHost.deleteAppWidgetId(id);
            return;
        }
        CellLayout layout = workspace.currentLayout();
        float cellWidth = layout == null || layout.cellWidth() <= 1 ? dp(90) : layout.cellWidth();
        float cellHeight = layout == null || layout.cellHeight() <= 1 ? dp(110) : layout.cellHeight();
        int w = LauncherMath.span(info.targetCellWidth, info.minWidth, cellWidth, LauncherMath.COLUMNS);
        int h = LauncherMath.span(info.targetCellHeight, info.minHeight, cellHeight, LauncherMath.ROWS);
        int page = workspace.currentPage();
        int[] spot = null;
        for (int p = page; p < model.pages && spot == null; p++) {
            spot = LauncherMath.firstFree(model.occupied(p, null), w, h, 0);
            if (spot != null) page = p;
        }
        if (spot == null && model.pages < HomeModel.MAX_PAGES) {
            page = model.pages;
            model.pages++;
            spot = new int[] {0, 0};
        }
        if (spot == null) {
            widgetHost.deleteAppWidgetId(id);
            toast("Kein Platz für das Widget.");
            return;
        }
        model.items.add(HomeModel.Item.widget(id, page, spot[0], spot[1], w, h));
        model.save(this);
        bindAll();
        workspace.snapToPage(page, true);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_BIND && request != REQUEST_CONFIGURE) return;
        int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) : pendingWidgetId;
        if (id < 0) return;
        if (result != RESULT_OK) {
            widgetHost.deleteAppWidgetId(id);
            pendingWidgetId = -1;
            return;
        }
        if (request == REQUEST_BIND) configureOrPlace(id);
        else placeWidget(id);
    }

    // ---------- Kippen (vom Hintergrund) ----------

    @Override public void onTilt(float soft, float dark, float unlock) {
        if (content == null) return;
        if (!tiltOn) {
            soft = 0f;
            dark = 0f;
        }
        if (!unlockOn) unlock = 0f;
        int blur = LauncherMath.blurPixels(soft, unlock, density);
        int dim = DrawMath.dimLevel(Math.max(dark, Effects.UNLOCK_DIM * unlock));
        content.setRenderEffect(effects.get(blur, dim));
        float scale = LauncherMath.unlockScale(unlock);
        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setAlpha(LauncherMath.unlockAlpha(unlock));
        main.removeCallbacks(unlockWatchdog);
        if (unlock > 0) main.postDelayed(unlockWatchdog, 600);
    }

    // ---------- Lebenszyklus ----------

    @Override protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (RuntimeException ignored) {}
    }

    @Override protected void onStop() {
        super.onStop();
        try {
            widgetHost.stopListening();
        } catch (RuntimeException ignored) {}
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        boolean closed = false;
        if (root.isDragging()) {
            root.cancel();
            closed = true;
        }
        if (sheet.isOpen()) {
            sheet.close();
            closed = true;
        }
        if (drawer.isOpen()) {
            drawer.close(true);
            closed = true;
        }
        if (!closed && hasWindowFocus()) workspace.snapToPage(model.defaultPage, true);
    }

    @Override public void onBackPressed() {
        // Der Startbildschirm schließt sich nie; Zurück schließt nur Übersicht und Einstellungen
        if (root.isDragging()) root.cancel();
        else if (sheet.isOpen()) sheet.close();
        else if (drawer.isOpen()) drawer.close(true);
    }

    @Override protected void onDestroy() {
        TiltBus.clearListener(this);
        try {
            launcherApps.unregisterCallback(appsCallback);
        } catch (RuntimeException ignored) {}
        worker.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
