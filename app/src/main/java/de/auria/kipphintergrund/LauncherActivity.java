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
    private static final int REQUEST_EXPORT = 43;
    private static final int REQUEST_IMPORT = 44;
    private static final int REQUEST_RESTORE_BIND = 45;

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
    private TextView meterView;
    private FrameLayout.LayoutParams meterParams;
    private LauncherMeter meter;
    private SharedPreferences wallpaperPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener measureListener = (shared, key) -> {
        if (PhotoStore.MEASURE.equals(key)) syncMeter();
    };
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
    // Zuletzt gesetzter Kipp-Effekt (0.15): nur bei sichtbarer Änderung neu setzen
    private int appliedBlur;
    private int appliedDim;

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
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
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

    // 0.18: Schalter werden im gemeinsamen Einstellungs-Bildschirm umgelegt – hier sofort übernehmen
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (shared, key) -> {
        if (key == null || content == null) return;
        switch (key) {
            case SettingsSheet.TILT:
            case SettingsSheet.TRANSITIONS:
            case SettingsSheet.LABELS:
            case SettingsSheet.GLOW:
            case SettingsSheet.UNLOCK:
            case SettingsSheet.DETENT:
                onLauncherSetting(key, shared.getBoolean(key, true));
                break;
            default:
                break; // Startbildschirm-Belegung u. a. – nicht hier
        }
    };

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

        buildUndoBar();

        // 0.23: Messanzeige des Startbildschirms (oben rechts; die des Hintergrunds steht oben links)
        meterView = new TextView(this);
        meterView.setVisibility(View.GONE);
        meterParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        root.addView(meterView, meterParams);
        meter = new LauncherMeter(this, meterView);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            insetTop = bars.top;
            meterParams.topMargin = bars.top + dp(6);
            meterParams.rightMargin = dp(8);
            if (undoParams != null) undoParams.bottomMargin = bars.bottom + dp(150);
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
            if (used.contains(app.component) || !Process.myUserHandle().equals(app.user)) continue;
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
        if (item.type == HomeModel.Item.FOLDER) {
            AppIndex.App folder = folderApp(item);
            if (folder == null) return null;
            IconView view = new IconView(this, iconSize);
            view.item = item;
            view.bind(folder, labelsOn);
            view.setOnClickListener(this::onIconClick);
            view.setOnLongClickListener(this::onIconLongClick);
            return view;
        }
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(item.widgetId);
        if (info == null) return missingWidget(item);
        AppWidgetHostView host = widgetHost.createView(this, item.widgetId, info);
        if (host instanceof WidgetView) {
            WidgetView view = (WidgetView) host;
            view.item = item;
            view.longPress = this;
        }
        return host;
    }

    /**
     * 0.17: Widget, dessen App Android nicht mehr meldet (deinstalliert oder gerade nicht verfügbar). Vorher blieb der
     * Platz unsichtbar belegt. Jetzt ein Platzhalter: lange drücken und auf „Entfernen“ ziehen gibt den Platz frei.
     */
    private View missingWidget(HomeModel.Item item) {
        WidgetView view = new WidgetView(this);
        view.item = item;
        view.longPress = this;
        view.setClickable(true);
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x66101418);
        box.setStroke(Math.round(density), 0x55FFFFFF);
        box.setCornerRadius(18 * density);
        view.setBackground(box);
        TextView text = new TextView(this);
        text.setText("Widget nicht verfügbar\nLange drücken und auf Entfernen ziehen");
        text.setTextColor(0xDDFFFFFF);
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        int pad = Math.round(10 * density);
        text.setPadding(pad, pad, pad, pad);
        view.addView(text, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return view;
    }

    // ---------- Starten ----------

    private void onIconClick(View v) {
        IconView icon = (IconView) v;
        if (icon.item != null && icon.item.type == HomeModel.Item.FOLDER) openFolder(icon.item);
        else if (icon.app != null && icon.app.component != null) startApp(icon);
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
        // 0.26: Menü mit Schnellaktionen; wer weiterzieht, verschiebt (Menü verschwindet)
        if (drag.kind != Drag.DRAWER) showAppMenu(icon, drag);
        return true;
    }

    // ---------- App-Menü (0.26) ----------

    private FrameLayout menuScrim;

    private void showAppMenu(IconView icon, Drag drag) {
        LinearLayout card = newMenuCard();

        boolean isFolder = icon.app.component == null;
        if (isFolder) card.addView(menuRow(null, "Ordner öffnen", KippStyle.TEXT, () -> openFolder(drag.item)));
        for (android.content.pm.ShortcutInfo shortcut : shortcutsFor(icon.app)) {
            android.graphics.drawable.Drawable image = null;
            try {
                image = launcherApps.getShortcutIconDrawable(shortcut, getResources().getDisplayMetrics().densityDpi);
            } catch (RuntimeException ignored) {}
            CharSequence label = shortcut.getShortLabel() != null ? shortcut.getShortLabel() : shortcut.getLongLabel();
            card.addView(menuRow(image, String.valueOf(label), KippStyle.TEXT, () -> {
                try {
                    launcherApps.startShortcut(shortcut, null, null);
                } catch (RuntimeException e) {
                    toast("Schnellaktion lässt sich nicht starten.");
                }
            }));
        }
        if (!isFolder) card.addView(menuRow(null, "App-Info", KippStyle.COPPER, () -> showInfo(drag)));
        card.addView(menuRow(null, "Entfernen", KippStyle.COPPER, () -> {
            HomeModel before = model.copy();
            removeDragged(drag);
            model.trimPages();
            model.save(this);
            bindAll();
            offerUndo(before, "Entfernt");
        }));
        TextView hint = new TextView(this);
        hint.setText("Zum Verschieben gedrückt halten und ziehen");
        hint.setTextSize(11);
        hint.setTextColor(KippStyle.SUB);
        hint.setPadding(dp(14), dp(4), dp(14), dp(6));
        card.addView(hint);
        showMenuCard(card, icon);
    }

    private LinearLayout newMenuCard() {
        closeAppMenu();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {KippStyle.SHEET_TOP, KippStyle.SHEET_BOTTOM});
        shape.setCornerRadius(dp(22));
        shape.setStroke(Math.max(1, dp(1)), 0x33D9A066);
        card.setBackground(shape);
        card.setElevation(dp(12));
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setClickable(true);
        return card;
    }

    /** Karte über (oder unter) dem Symbol bzw. Widget anzeigen; Tippen daneben schließt sie. */
    private void showMenuCard(LinearLayout card, View anchor) {
        menuScrim = new FrameLayout(this);
        menuScrim.setOnClickListener(v -> closeAppMenu());
        int width = dp(236);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = card.getMeasuredHeight();
        int x = Math.max(dp(12), Math.min(root.getWidth() - width - dp(12), at[0] + anchor.getWidth() / 2 - width / 2));
        int above = at[1] - height - dp(8);
        int y = above >= insetTop + dp(8) ? above : at[1] + anchor.getHeight() + dp(8);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = x;
        params.topMargin = Math.min(y, root.getHeight() - height - dp(12));
        menuScrim.addView(card, params);
        root.addView(menuScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setAlpha(0f);
        card.setScaleX(.92f);
        card.setScaleY(.92f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    private View menuRow(android.graphics.drawable.Drawable image, String label, int color, Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(15);
        row.setTextColor(color);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(48));
        row.setPadding(dp(14), 0, dp(14), 0);
        if (image != null) {
            image.setBounds(0, 0, dp(24), dp(24));
            row.setCompoundDrawablesRelative(image, null, null, null);
            row.setCompoundDrawablePadding(dp(12));
        }
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0x22D9A066);
        pressed.setCornerRadius(dp(14));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        row.setBackground(states);
        row.setOnClickListener(v -> {
            closeAppMenu();
            action.run();
        });
        return row;
    }

    /** Bis zu 4 Schnellaktionen der App (geht nur, solange Kipp-Start der Standard-Startbildschirm ist). */
    private List<android.content.pm.ShortcutInfo> shortcutsFor(AppIndex.App app) {
        ArrayList<android.content.pm.ShortcutInfo> result = new ArrayList<>();
        try {
            if (app == null || app.component == null || !launcherApps.hasShortcutHostPermission()) return result;
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(app.component.getPackageName());
            query.setActivity(app.component);
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<android.content.pm.ShortcutInfo> found = launcherApps.getShortcuts(query, app.user);
            if (found == null) return result;
            ArrayList<android.content.pm.ShortcutInfo> sorted = new ArrayList<>(found);
            // Feste (Manifest-)Aktionen zuerst, dann nach Rang – wie der Pixel-Startbildschirm
            sorted.sort((a, b) -> a.isDeclaredInManifest() != b.isDeclaredInManifest()
                    ? (a.isDeclaredInManifest() ? -1 : 1) : Integer.compare(a.getRank(), b.getRank()));
            for (android.content.pm.ShortcutInfo info : sorted) {
                if (!info.isEnabled()) continue;
                result.add(info);
                if (result.size() == 4) break;
            }
        } catch (RuntimeException ignored) {
            // z. B. kein Zugriff: dann nur App-Info und Entfernen
        }
        return result;
    }

    private boolean closeAppMenu() {
        if (menuScrim == null) return false;
        root.removeView(menuScrim);
        menuScrim = null;
        return true;
    }

    @Override public void onDrawerOpenChanged() {
        onTilt(TiltBus.soft(), TiltBus.dark(), TiltBus.unlock());
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
        showWidgetMenu(view, drag);
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
                } else if (target != item && item.type == HomeModel.Item.APP && target.type != HomeModel.Item.WIDGET) {
                    // 0.27: App auf App = neuer Ordner, App auf Ordner = hinzufügen
                    model.items.remove(item);
                    addToFolderAt(target, item.component);
                } else if (target != item && target.type != HomeModel.Item.WIDGET) {
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
                } else if (target.type != HomeModel.Item.WIDGET) {
                    model.dock[d.dockSlot] = null;
                    addToFolderAt(target, component);
                }
                break;
            }
            default: {
                if (target != null && target.type != HomeModel.Item.WIDGET) {
                    addToFolderAt(target, d.app.component);
                    break;
                }
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
                if (d.item.type != HomeModel.Item.APP) {
                    toast("Ordner passen nicht ins Dock.");
                    break;
                }
                model.dock[slot] = d.item.component;
                if (old != null) d.item.component = old;
                else model.items.remove(d.item);
                break;
            case Drag.DOCK:
                model.dock[slot] = model.dock[d.dockSlot];
                model.dock[d.dockSlot] = old;
                break;
            case Drag.DRAWER:
                if (old != null) {
                    // 0.17: Die verdrängte App braucht erst einen sicheren Platz (aktuelle Seite, dann die übrigen);
                    // sonst bleibt das Dock unverändert – vorher ging sie bei voller Seite verloren.
                    int[] spot = freeSpotFor(workspace.currentPage());
                    if (spot == null) {
                        toast("Kein freier Platz für " + label(old) + " – Dock bleibt unverändert");
                        break;
                    }
                    model.items.add(HomeModel.Item.app(old, spot[0], spot[1], spot[2]));
                }
                model.dock[slot] = d.app.component;
                break;
            default:
                break;
        }
    }

    /** Freier Platz für ein Symbol: zuerst auf der Wunschseite, dann auf den anderen Seiten. {Seite, x, y} oder null. */
    private int[] freeSpotFor(int preferred) {
        for (int i = 0; i < model.pages; i++) {
            int page = (preferred + i) % model.pages;
            int[] free = LauncherMath.firstFree(model.occupied(page, null), 1, 1, 0);
            if (free != null) return new int[] {page, free[0], free[1]};
        }
        return null;
    }

    private String label(ComponentName component) {
        AppIndex.App app = index.get(component);
        return app != null ? app.label : "die alte App";
    }

    private void removeDragged(Drag d) {
        if (d.kind == Drag.DOCK) {
            model.dock[d.dockSlot] = null;
        } else if (d.item != null) {
            model.items.remove(d.item);
            // 0.25: Widget erst endgültig löschen, wenn „Rückgängig“ verfallen ist
            if (d.item.type == HomeModel.Item.WIDGET) pendingWidgetDeletes.add(d.item.widgetId);
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

        private float startX;
        private float startY;
        private boolean moved;

        void startDrag(Drag d, Bitmap image, int width, int height, View source) {
            if (drag != null) endDrag();
            drag = d;
            startX = lastX;
            startY = lastY;
            moved = false;
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
            boolean noInfo = d.kind == Drag.WIDGET || (d.app != null && d.app.component == null);
            dropInfo.setVisibility(noInfo ? View.GONE : View.VISIBLE);
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
                    if (!moved && Math.hypot(ev.getX() - startX, ev.getY() - startY) > dp(10)) {
                        moved = true;
                        closeAppMenu(); // weitergezogen: verschieben statt Menü
                    }
                    floating.setX(ev.getX() - grabX);
                    floating.setY(ev.getY() - grabY);
                    highlight(ev.getX(), ev.getY());
                    trackEdge(ev.getX());
                    break;
                case MotionEvent.ACTION_UP:
                    // Nur lange gedrückt, nicht bewegt: Symbol bleibt, das Menü bleibt offen
                    if (!moved && menuScrim != null) cancel();
                    else drop(ev.getX(), ev.getY());
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
            // 0.25: Stand vorher merken – nach jeder Änderung lässt sie sich 5 s lang rückgängig machen
            HomeModel before = model.copy();
            String beforePrint = model.fingerprint();
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
            if (zone != 2 && !beforePrint.equals(model.fingerprint())) offerUndo(before, zone == 1 ? "Entfernt" : "Verschoben");
        }
    }

    // ---------- Rückgängig (0.25) ----------

    private TextView undoBar;
    private FrameLayout.LayoutParams undoParams;
    private HomeModel undoState;
    private final ArrayList<Integer> pendingWidgetDeletes = new ArrayList<>();
    private final Runnable undoExpired = () -> {
        undoState = null;
        hideUndo();
        for (int id : pendingWidgetDeletes) {
            try {
                widgetHost.deleteAppWidgetId(id);
            } catch (RuntimeException ignored) {}
        }
        pendingWidgetDeletes.clear();
    };

    private void buildUndoBar() {
        undoBar = new TextView(this);
        undoBar.setTextSize(15);
        undoBar.setTextColor(KippStyle.TEXT);
        undoBar.setGravity(Gravity.CENTER);
        undoBar.setMinHeight(dp(48));
        undoBar.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xF22C2F33);
        pill.setCornerRadius(dp(24));
        pill.setStroke(Math.max(1, dp(1)), 0x55D9A066);
        undoBar.setBackground(pill);
        undoBar.setElevation(dp(8));
        undoBar.setVisibility(View.GONE);
        undoBar.setOnClickListener(v -> undo());
        undoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        undoParams.bottomMargin = dp(150);
        root.addView(undoBar, undoParams);
    }

    private void offerUndo(HomeModel before, String what) {
        // Ein älteres Angebot verfällt jetzt (seine Widgets werden endgültig gelöscht)
        if (undoState != null) {
            main.removeCallbacks(undoExpired);
            undoExpired.run();
        }
        undoState = before;
        android.text.SpannableString text = new android.text.SpannableString(what + "   Rückgängig");
        text.setSpan(new android.text.style.ForegroundColorSpan(KippStyle.COPPER), what.length() + 3, text.length(), 0);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), what.length() + 3, text.length(), 0);
        undoBar.setText(text);
        undoBar.setContentDescription(what + ". Rückgängig machen");
        undoBar.setVisibility(View.VISIBLE);
        undoBar.setAlpha(0f);
        undoBar.setTranslationY(dp(16));
        undoBar.animate().alpha(1f).translationY(0).setDuration(180).start();
        main.postDelayed(undoExpired, 5000);
    }

    private void hideUndo() {
        if (undoBar == null || undoBar.getVisibility() != View.VISIBLE) return;
        undoBar.animate().alpha(0f).setDuration(150).withEndAction(() -> undoBar.setVisibility(View.GONE)).start();
    }

    private void undo() {
        if (undoState == null) return;
        main.removeCallbacks(undoExpired);
        model.restoreFrom(undoState);
        undoState = null;
        pendingWidgetDeletes.clear(); // entfernte Widgets sind wieder da
        model.save(this);
        bindAll();
        hideUndo();
        undoBar.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    // ---------- Ordner (0.27) ----------

    private FrameLayout folderScrim;
    private HomeModel.Item openFolderItem;
    private android.widget.EditText folderTitle;

    /** Ordner als „App“ ohne Komponente: Name und zusammengesetztes Symbol. Null, wenn keine App mehr da ist. */
    private AppIndex.App folderApp(HomeModel.Item item) {
        ArrayList<AppIndex.App> apps = folderApps(item);
        if (apps.isEmpty()) return null;
        String title = item.title == null || item.title.trim().isEmpty() ? "Ordner" : item.title.trim();
        return new AppIndex.App(null, Process.myUserHandle(), title, folderIcon(apps));
    }

    private ArrayList<AppIndex.App> folderApps(HomeModel.Item item) {
        ArrayList<AppIndex.App> apps = new ArrayList<>();
        for (ComponentName c : item.folder) {
            AppIndex.App app = index.get(c);
            if (app != null) apps.add(app);
        }
        return apps;
    }

    /** Grafit-Kachel mit Kupferrand und bis zu 4 Mini-Symbolen. */
    private Bitmap folderIcon(List<AppIndex.App> apps) {
        int size = iconSize;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.FILTER_BITMAP_FLAG);
        float stroke = Math.max(1f, density * 1.2f);
        android.graphics.RectF box = new android.graphics.RectF(stroke, stroke, size - stroke, size - stroke);
        float radius = size * .28f;
        paint.setColor(0xE62C2F33);
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(0xB3D9A066);
        canvas.drawRoundRect(box, radius, radius, paint);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        int count = LauncherMath.folderPreviewCount(apps.size());
        float pad = size * .17f;
        float gap = size * .06f;
        float mini = (size - 2 * pad - gap) / 2f;
        android.graphics.RectF dst = new android.graphics.RectF();
        for (int i = 0; i < count; i++) {
            Bitmap icon = apps.get(i).icon;
            if (icon == null) continue;
            float left = pad + (i % 2) * (mini + gap);
            float top = pad + (i / 2) * (mini + gap);
            dst.set(left, top, left + mini, top + mini);
            canvas.drawBitmap(icon, null, dst, paint);
        }
        return bitmap;
    }

    /** App auf ein Symbol gelegt: Ordner anlegen (bei einer App) oder hinzufügen (bei einem Ordner). */
    private void addToFolderAt(HomeModel.Item target, ComponentName component) {
        if (component == null) return;
        if (target.type == HomeModel.Item.FOLDER) {
            if (!target.folder.contains(component)) target.folder.add(component);
            return;
        }
        if (target.type != HomeModel.Item.APP || component.equals(target.component)) return;
        HomeModel.Item folder = HomeModel.Item.folder("Ordner", target.page, target.x, target.y);
        folder.folder.add(target.component);
        folder.folder.add(component);
        int at = model.items.indexOf(target);
        model.items.remove(target);
        model.items.add(Math.max(0, Math.min(at, model.items.size())), folder);
    }

    private void openFolder(HomeModel.Item item) {
        if (item == null || item.type != HomeModel.Item.FOLDER) return;
        closeAppMenu();
        closeFolder();
        openFolderItem = item;
        folderScrim = new FrameLayout(this);
        folderScrim.setBackgroundColor(0x99000000);
        folderScrim.setOnClickListener(v -> closeFolder());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(KippStyle.sheet(dp(26)));
        card.setElevation(dp(16));
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setClickable(true);

        folderTitle = new android.widget.EditText(this);
        folderTitle.setText(item.title == null || item.title.isEmpty() ? "Ordner" : item.title);
        folderTitle.setTextColor(KippStyle.TEXT);
        folderTitle.setTextSize(18);
        folderTitle.setSingleLine(true);
        folderTitle.setGravity(Gravity.CENTER);
        folderTitle.setBackground(null);
        folderTitle.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        folderTitle.setContentDescription("Ordnername");
        folderTitle.setOnEditorActionListener((v, action, event) -> {
            folderTitle.clearFocus();
            hideKeyboard(folderTitle);
            return false;
        });
        card.addView(folderTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        View line = new View(this);
        line.setBackgroundColor(0x55D9A066);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(40), Math.max(1, dp(2)));
        lineParams.gravity = Gravity.CENTER_HORIZONTAL;
        lineParams.bottomMargin = dp(10);
        card.addView(line, lineParams);

        ArrayList<AppIndex.App> apps = folderApps(item);
        int columns = LauncherMath.folderColumns(apps.size());
        int cellWidth = dp(76);
        int cellHeight = labelsOn ? dp(92) : dp(76);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row);
            }
            AppIndex.App app = apps.get(i);
            IconView view = new IconView(this, iconSize);
            view.bind(app, labelsOn);
            view.setOnClickListener(v -> {
                startApp((IconView) v);
                main.postDelayed(this::closeFolder, 300);
            });
            view.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                takeOutOfFolder(item, app.component);
                return true;
            });
            row.addView(view, new LinearLayout.LayoutParams(cellWidth, cellHeight));
        }
        TextView hint = new TextView(this);
        hint.setText("Lange drücken nimmt eine App heraus");
        hint.setTextSize(11);
        hint.setTextColor(KippStyle.SUB);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        card.setMinimumWidth(Math.min(dp(260), root.getWidth() - dp(32)));
        folderScrim.addView(card, params);
        root.addView(folderScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        folderScrim.setAlpha(0f);
        card.setScaleX(.9f);
        card.setScaleY(.9f);
        folderScrim.animate().alpha(1f).setDuration(160).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
    }

    /** Schließt den Ordner und übernimmt einen geänderten Namen. */
    private boolean closeFolder() {
        if (folderScrim == null) return false;
        HomeModel.Item item = openFolderItem;
        String title = folderTitle != null ? folderTitle.getText().toString().trim() : "";
        if (folderTitle != null) hideKeyboard(folderTitle);
        root.removeView(folderScrim);
        folderScrim = null;
        folderTitle = null;
        openFolderItem = null;
        if (item != null && model.items.contains(item)) {
            String clean = title.isEmpty() ? "Ordner" : title;
            if (!clean.equals(item.title)) {
                HomeModel before = model.copy();
                item.title = clean;
                model.save(this);
                bindAll();
                offerUndo(before, "Umbenannt");
            }
        }
        return true;
    }

    /** App aus dem Ordner auf den nächsten freien Platz legen; bleibt eine App übrig, wird der Ordner zu ihr. */
    private void takeOutOfFolder(HomeModel.Item folder, ComponentName component) {
        int[] spot = freeSpotFor(folder.page);
        if (spot == null) {
            toast("Kein freier Platz auf dem Startbildschirm.");
            return;
        }
        HomeModel before = model.copy();
        if (folderTitle != null) {
            String title = folderTitle.getText().toString().trim();
            folder.title = title.isEmpty() ? "Ordner" : title;
        }
        folder.folder.remove(component);
        if (folder.folder.size() == 1) {
            // Ordner löst sich auf: die letzte App nimmt seinen Platz ein
            ComponentName last = folder.folder.get(0);
            folder.folder.clear();
            folder.type = HomeModel.Item.APP;
            folder.component = last;
            folder.title = "";
        } else if (folder.folder.isEmpty()) {
            model.items.remove(folder);
        }
        model.items.add(HomeModel.Item.app(component, spot[0], spot[1], spot[2]));
        folderTitle = null;
        openFolderItem = null;
        if (folderScrim != null) {
            root.removeView(folderScrim);
            folderScrim = null;
        }
        model.save(this);
        bindAll();
        offerUndo(before, "Herausgenommen");
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // ---------- Widget-Größe (0.27) ----------

    private void showWidgetMenu(WidgetView view, Drag drag) {
        LinearLayout card = newMenuCard();
        card.addView(menuRow(null, "Größe ändern", KippStyle.TEXT, () -> showResize(drag.item)));
        card.addView(menuRow(null, "Entfernen", KippStyle.COPPER, () -> {
            HomeModel before = model.copy();
            removeDragged(drag);
            model.trimPages();
            model.save(this);
            bindAll();
            offerUndo(before, "Entfernt");
        }));
        TextView hint = new TextView(this);
        hint.setText("Zum Verschieben gedrückt halten und ziehen");
        hint.setTextSize(11);
        hint.setTextColor(KippStyle.SUB);
        hint.setPadding(dp(14), dp(4), dp(14), dp(6));
        card.addView(hint);
        showMenuCard(card, view);
    }

    private LinearLayout resizePanel;
    private HomeModel resizeBefore;
    private String resizeStartPrint;

    /** Grenzen {minW, minH, maxW, maxH} in Zellen aus den Angaben des Widgets. */
    private int[] resizeLimits(HomeModel.Item item) {
        CellLayout layout = workspace.page(item.page);
        float cw = layout == null || layout.cellWidth() <= 1 ? dp(90) : layout.cellWidth();
        float ch = layout == null || layout.cellHeight() <= 1 ? dp(110) : layout.cellHeight();
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(item.widgetId);
        if (info == null) return new int[] {1, 1, LauncherMath.COLUMNS, LauncherMath.ROWS};
        boolean horizontal = (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
        boolean vertical = (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
        int minW = horizontal ? LauncherMath.span(0, info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth, cw, LauncherMath.COLUMNS) : item.w;
        int minH = vertical ? LauncherMath.span(0, info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight, ch, LauncherMath.ROWS) : item.h;
        int maxW = item.w;
        int maxH = item.h;
        if (horizontal) maxW = Math.max(item.w, LauncherMath.maxSpan(info.maxResizeWidth, cw, LauncherMath.COLUMNS));
        if (vertical) maxH = Math.max(item.h, LauncherMath.maxSpan(info.maxResizeHeight, ch, LauncherMath.ROWS));
        return new int[] {Math.min(minW, item.w), Math.min(minH, item.h), maxW, maxH};
    }

    private void showResize(HomeModel.Item item) {
        if (item == null || item.type != HomeModel.Item.WIDGET) return;
        closeResize();
        // Ein offenes „Rückgängig“ würde sonst auf einen alten Stand zurückspringen
        if (undoState != null) {
            main.removeCallbacks(undoExpired);
            undoExpired.run();
        }
        int[] limits = resizeLimits(item);
        if (limits[0] == limits[2] && limits[1] == limits[3]) {
            toast("Dieses Widget hat eine feste Größe.");
            return;
        }
        resizeBefore = model.copy();
        resizeStartPrint = model.fingerprint();
        workspace.snapToPage(item.page, true);
        resizePanel = new LinearLayout(this);
        resizePanel.setOrientation(LinearLayout.VERTICAL);
        resizePanel.setBackground(KippStyle.sheet(dp(24)));
        resizePanel.setElevation(dp(14));
        resizePanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        resizePanel.setClickable(true);
        TextView size = new TextView(this);
        size.setTextColor(KippStyle.TEXT);
        size.setTextSize(15);
        size.setGravity(Gravity.CENTER);
        resizePanel.addView(size, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        Runnable label = () -> size.setText("Größe  " + item.w + " × " + item.h);
        label.run();
        resizePanel.addView(resizeRow("Breite", item, limits, 1, 0, label));
        resizePanel.addView(resizeRow("Höhe", item, limits, 0, 1, label));
        TextView done = new TextView(this);
        done.setText("Fertig");
        done.setTextSize(15);
        done.setTextColor(KippStyle.ON_COPPER);
        done.setGravity(Gravity.CENTER);
        done.setBackground(KippStyle.copper(dp(22)));
        done.setOnClickListener(v -> closeResize());
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        doneParams.topMargin = dp(8);
        resizePanel.addView(done, doneParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.min(dp(300), root.getWidth() - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = undoParams != null ? undoParams.bottomMargin : dp(150);
        root.addView(resizePanel, params);
    }

    private View resizeRow(String name, HomeModel.Item item, int[] limits, int dw, int dh, Runnable label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(KippStyle.SUB);
        title.setTextSize(14);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(resizeButton("−", name + " verkleinern", item, limits, -dw, -dh, label));
        row.addView(resizeButton("+", name + " vergrößern", item, limits, dw, dh, label));
        return row;
    }

    private View resizeButton(String symbol, String description, HomeModel.Item item, int[] limits, int dw, int dh, Runnable label) {
        TextView button = new TextView(this);
        button.setText(symbol);
        button.setTextSize(22);
        button.setTextColor(KippStyle.COPPER);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setBackground(KippStyle.inset(dp(20), density));
        button.setOnClickListener(v -> {
            int[] next = LauncherMath.resize(model.occupied(item.page, item), item.x, item.y, item.w, item.h, dw, dh,
                    limits[0], limits[1], limits[2], limits[3]);
            if (next == null) {
                v.performHapticFeedback(HapticFeedbackConstants.REJECT);
                return;
            }
            item.w = next[0];
            item.h = next[1];
            bindAll();
            label.run();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(56), dp(44));
        params.leftMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private boolean closeResize() {
        if (resizePanel == null) return false;
        root.removeView(resizePanel);
        resizePanel = null;
        HomeModel before = resizeBefore;
        resizeBefore = null;
        if (before != null && !model.fingerprint().equals(resizeStartPrint)) {
            model.save(this);
            offerUndo(before, "Größe geändert");
        }
        return true;
    }

    // ---------- Sichern und Wiederherstellen (0.28) ----------

    private boolean backupWithPhoto;
    private final java.util.ArrayDeque<Backup.WidgetSpot> restoreQueue = new java.util.ArrayDeque<>();
    private Backup.WidgetSpot restoreSpot;
    private int restoreId = -1;
    private int restoreMissingWidgets;
    private final ArrayList<String> restoreMissingApps = new ArrayList<>();
    private boolean restoring;

    @Override public void onBackupMenu() {
        String[] choices = {"Sicherung erstellen – mit Foto", "Sicherung erstellen – ohne Foto", "Aus Sicherung wiederherstellen"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sichern und Wiederherstellen")
                .setItems(choices, (dialog, which) -> {
                    if (which == 2) startImport();
                    else startExport(which == 0);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void startExport(boolean withPhoto) {
        backupWithPhoto = withPhoto;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, Backup.fileName());
        try {
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            toast("Android kann keinen Speicherort anbieten.");
        }
    }

    private void startImport() {
        if (restoring) {
            toast("Eine Wiederherstellung läuft noch.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "application/octet-stream", "text/plain"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            toast("Android kann keine Datei auswählen.");
        }
    }

    private void exportTo(Uri uri) {
        HomeModel snapshot = model.copy();
        boolean withPhoto = backupWithPhoto;
        Context context = getApplicationContext();
        worker.execute(() -> {
            String message;
            try {
                Backup.write(context, uri, snapshot, widgetManager, withPhoto);
                message = withPhoto ? "Gesichert (mit Foto)." : "Gesichert (ohne Foto).";
            } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                message = "Sichern hat nicht geklappt.";
            }
            String text = message;
            main.post(() -> toast(text));
        });
    }

    private void importFrom(Uri uri) {
        Context context = getApplicationContext();
        worker.execute(() -> {
            Backup.Data data;
            try {
                data = Backup.read(context, uri);
            } catch (org.json.JSONException e) {
                main.post(() -> toast("Das ist keine Kipp-Start-Sicherung."));
                return;
            } catch (java.io.IOException | RuntimeException e) {
                main.post(() -> toast("Die Datei lässt sich nicht lesen."));
                return;
            }
            main.post(() -> confirmRestore(data));
        });
    }

    private void confirmRestore(Backup.Data data) {
        if (isDestroyed()) return;
        int apps = 0;
        int folders = 0;
        for (HomeModel.Item item : data.home.items) {
            if (item.type == HomeModel.Item.APP) apps++;
            else if (item.type == HomeModel.Item.FOLDER) folders++;
        }
        StringBuilder text = new StringBuilder();
        if (!data.created.isEmpty()) text.append("Gesichert am ").append(data.created.length() >= 10 ? data.created.substring(0, 10) : data.created);
        if (!data.appVersion.isEmpty()) text.append(" mit Kipp-Start ").append(data.appVersion);
        if (text.length() > 0) text.append(".\n\n");
        text.append(data.home.pages).append(data.home.pages == 1 ? " Seite, " : " Seiten, ")
                .append(apps).append(" Apps, ").append(folders).append(folders == 1 ? " Ordner, " : " Ordner, ")
                .append(data.widgets.size()).append(" Widgets")
                .append(data.photo != null ? ", mit Foto" : ", ohne Foto").append(".\n\n")
                .append("Das ersetzt den jetzigen Startbildschirm und die Einstellungen")
                .append(data.photo != null ? " sowie das Foto" : "")
                .append(". Rückgängig geht hier nicht – vorher eine Sicherung zu erstellen schadet nicht.");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wiederherstellen?")
                .setMessage(text.toString())
                .setPositiveButton("Wiederherstellen", (dialog, which) -> applyRestore(data))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void applyRestore(Backup.Data data) {
        restoring = true;
        closeAppMenu();
        closeResize();
        closeFolder();
        if (root.isDragging()) root.cancel();
        if (undoState != null) {
            main.removeCallbacks(undoExpired);
            undoExpired.run();
        }
        // Alte Widgets freigeben (ihre Nummern gehören zum alten Stand)
        for (HomeModel.Item item : model.items) {
            if (item.type != HomeModel.Item.WIDGET) continue;
            try {
                widgetHost.deleteAppWidgetId(item.widgetId);
            } catch (RuntimeException ignored) {}
        }
        restoreMissingApps.clear();
        restoreMissingWidgets = 0;
        dropMissingApps(data.home);
        model.restoreFrom(data.home);
        model.fresh = false;
        model.save(this);
        bindAll();
        workspace.snapToPage(model.defaultPage, false);

        // Einstellungen und Foto im Hintergrund; danach die Widgets nacheinander
        Context context = getApplicationContext();
        worker.execute(() -> {
            boolean photo = data.photo != null && PhotoStore.importImageBytes(context, data.photo);
            Backup.applyPrefs(getSharedPreferences("launcher", MODE_PRIVATE), data.launcherPrefs);
            Backup.applyPrefs(PhotoStore.preferences(context), data.wallpaperPrefs);
            // Neue Bild-Nummer zuletzt: erst dann lädt der Hintergrund – mit dem Ausschnitt aus der Sicherung
            if (photo) PhotoStore.preferences(context).edit().putLong(PhotoStore.IMAGE, System.currentTimeMillis()).commit();
            boolean photoFailed = data.photo != null && !photo;
            main.post(() -> {
                if (photoFailed) toast("Das Foto aus der Sicherung ließ sich nicht lesen.");
                readSettings();
                loadApps();
                restoreQueue.clear();
                restoreQueue.addAll(data.widgets);
                restoreNext();
            });
        });
    }

    /** Apps, die es auf diesem Handy nicht gibt, aus der Sicherung nehmen und für die Meldung merken. */
    private void dropMissingApps(HomeModel home) {
        if (index.isEmpty()) return; // Apps noch nicht geladen: nichts voreilig wegwerfen
        java.util.Iterator<HomeModel.Item> it = home.items.iterator();
        while (it.hasNext()) {
            HomeModel.Item item = it.next();
            if (item.type == HomeModel.Item.APP && index.get(item.component) == null) {
                noteMissing(item.component);
                it.remove();
            } else if (item.type == HomeModel.Item.FOLDER) {
                java.util.Iterator<ComponentName> f = item.folder.iterator();
                while (f.hasNext()) {
                    ComponentName c = f.next();
                    if (index.get(c) == null) {
                        noteMissing(c);
                        f.remove();
                    }
                }
                if (item.folder.isEmpty()) it.remove();
                else if (item.folder.size() == 1) {
                    item.type = HomeModel.Item.APP;
                    item.component = item.folder.get(0);
                    item.folder.clear();
                    item.title = "";
                }
            }
        }
        for (int i = 0; i < home.dock.length; i++) {
            if (home.dock[i] != null && index.get(home.dock[i]) == null) {
                noteMissing(home.dock[i]);
                home.dock[i] = null;
            }
        }
    }

    private void noteMissing(ComponentName component) {
        String name = component.getPackageName();
        if (!restoreMissingApps.contains(name)) restoreMissingApps.add(name);
    }

    /** Nächstes Widget aus der Sicherung anlegen; fragt Android nach Erlaubnis, wenn nötig. */
    private void restoreNext() {
        while (!restoreQueue.isEmpty()) {
            Backup.WidgetSpot spot = restoreQueue.poll();
            AppWidgetProviderInfo info = providerFor(spot.provider);
            if (info == null) {
                restoreMissingWidgets++;
                continue;
            }
            int id = widgetHost.allocateAppWidgetId();
            boolean bound;
            try {
                bound = widgetManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null);
            } catch (RuntimeException e) {
                bound = false;
            }
            if (bound) {
                placeRestored(id, spot);
                continue;
            }
            Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try {
                restoreId = id;
                restoreSpot = spot;
                startActivityForResult(bind, REQUEST_RESTORE_BIND);
                return; // weiter in onRestoreBound
            } catch (ActivityNotFoundException e) {
                widgetHost.deleteAppWidgetId(id);
                restoreId = -1;
                restoreSpot = null;
                restoreMissingWidgets++;
            }
        }
        finishRestore();
    }

    private void onRestoreBound(boolean allowed) {
        int id = restoreId;
        Backup.WidgetSpot spot = restoreSpot;
        restoreId = -1;
        restoreSpot = null;
        if (id >= 0 && spot != null) {
            if (allowed) placeRestored(id, spot);
            else {
                widgetHost.deleteAppWidgetId(id);
                restoreMissingWidgets++;
            }
        }
        restoreNext();
    }

    private AppWidgetProviderInfo providerFor(ComponentName provider) {
        try {
            for (AppWidgetProviderInfo info : widgetManager.getInstalledProvidersForProfile(Process.myUserHandle())) {
                if (provider.equals(info.provider)) return info;
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private void placeRestored(int id, Backup.WidgetSpot spot) {
        int page = Math.max(0, Math.min(HomeModel.MAX_PAGES - 1, spot.page));
        while (model.pages <= page) model.pages++;
        int[] cell = LauncherMath.restoreSpot(model.occupied(page, null), spot.x, spot.y, spot.w, spot.h);
        for (int p = 0; cell == null && p < model.pages; p++) {
            cell = LauncherMath.firstFree(model.occupied(p, null), spot.w, spot.h, 0);
            if (cell != null) page = p;
        }
        if (cell == null) {
            widgetHost.deleteAppWidgetId(id);
            restoreMissingWidgets++;
            return;
        }
        model.items.add(HomeModel.Item.widget(id, page, cell[0], cell[1], spot.w, spot.h));
    }

    private void finishRestore() {
        restoring = false;
        model.trimPages();
        model.save(this);
        bindAll();
        if (restoreMissingApps.isEmpty() && restoreMissingWidgets == 0) {
            toast("Wiederhergestellt.");
            return;
        }
        StringBuilder text = new StringBuilder("Wiederhergestellt – bis auf:\n\n");
        if (!restoreMissingApps.isEmpty()) {
            text.append(restoreMissingApps.size()).append(restoreMissingApps.size() == 1 ? " App" : " Apps")
                    .append(" gibt es auf diesem Handy nicht:\n");
            for (int i = 0; i < restoreMissingApps.size() && i < 8; i++) text.append("• ").append(restoreMissingApps.get(i)).append('\n');
            if (restoreMissingApps.size() > 8) text.append("• … und ").append(restoreMissingApps.size() - 8).append(" weitere\n");
            text.append('\n');
        }
        if (restoreMissingWidgets > 0) {
            text.append(restoreMissingWidgets).append(restoreMissingWidgets == 1 ? " Widget konnte" : " Widgets konnten")
                    .append(" nicht angelegt werden (App fehlt, kein Platz oder nicht erlaubt).");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Wiederherstellen")
                .setMessage(text.toString().trim())
                .setPositiveButton("OK", null)
                .show();
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

    @Override public void onSetNeutral() {
        toast(TiltBus.requestNeutral() ? "Diese Haltung gilt jetzt als gerade"
                : "Geht nur, wenn Kipp-Hintergrund als Hintergrund eingestellt ist");
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
        if (request == REQUEST_EXPORT || request == REQUEST_IMPORT) {
            Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) return;
            if (request == REQUEST_EXPORT) exportTo(uri);
            else importFrom(uri);
            return;
        }
        if (request == REQUEST_RESTORE_BIND) {
            onRestoreBound(result == RESULT_OK);
            return;
        }
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
        // App-Übersicht offen (neu in 0.15): Liste bleibt beim Kippen scharf und lesbar; beim Scrollen muss die
        // Grafik nicht den ganzen Bildschirm in jedem Bild weichzeichnen. Entsperren wirkt weiter.
        if (!tiltOn || (drawer != null && drawer.isOpen())) {
            soft = 0f;
            dark = 0f;
        }
        if (!unlockOn) unlock = 0f;
        // Zittern der Hand nicht weitergeben: sonst zeichnet die Grafik den ganzen Bildschirm in fast jedem Bild neu weich
        int blur = LauncherMath.settle(appliedBlur, LauncherMath.blurPixels(soft, unlock, density), LauncherMath.BLUR_STEP);
        int dim = LauncherMath.settle(appliedDim, DrawMath.dimLevel(Math.max(dark, Effects.UNLOCK_DIM * unlock)), LauncherMath.DIM_STEP);
        if (blur != appliedBlur || dim != appliedDim) {
            appliedBlur = blur;
            appliedDim = dim;
            content.setRenderEffect(effects.get(blur, dim));
        }
        float scale = LauncherMath.unlockScale(unlock);
        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setAlpha(LauncherMath.unlockAlpha(unlock));
        main.removeCallbacks(unlockWatchdog);
        if (unlock > 0) main.postDelayed(unlockWatchdog, 600);
    }

    // ---------- Lebenszyklus ----------

    private void syncMeter() {
        if (meter == null) return;
        boolean on = wallpaperPrefs != null && wallpaperPrefs.getBoolean(PhotoStore.MEASURE, false) && started;
        if (on) meter.start();
        else meter.stop();
    }

    private boolean started;

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (wallpaperPrefs == null) {
            wallpaperPrefs = PhotoStore.preferences(this);
            wallpaperPrefs.registerOnSharedPreferenceChangeListener(measureListener);
        }
        syncMeter();
        try {
            widgetHost.startListening();
        } catch (RuntimeException ignored) {}
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        syncMeter();
        try {
            widgetHost.stopListening();
        } catch (RuntimeException ignored) {}
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        boolean closed = closeAppMenu();
        closed |= closeResize();
        closed |= closeFolder();
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
        if (closeAppMenu() || closeResize() || closeFolder()) return;
        if (root.isDragging()) root.cancel();
        else if (sheet.isOpen()) sheet.close();
        else if (drawer.isOpen()) drawer.close(true);
    }

    @Override protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (wallpaperPrefs != null) wallpaperPrefs.unregisterOnSharedPreferenceChangeListener(measureListener);
        if (meter != null) meter.stop();
        main.removeCallbacks(undoExpired);
        undoExpired.run(); // offene Widget-Löschungen abschließen
        TiltBus.clearListener(this);
        try {
            launcherApps.unregisterCallback(appsCallback);
        } catch (RuntimeException ignored) {}
        worker.shutdown();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
