package de.auria.kipphintergrund;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/**
 * Startbildschirm-Einstellungen wie im Video (neu in 0.12, in 0.40 nach dem Video neu aufgebaut): schiebt sich von
 * unten über den Startbildschirm, dahinter wird alles unscharf. Oben „Abbrechen“ und „Fertig“, auf der Karte ein X.
 * Reihenfolge der Einträge wie im Video, alles auf Deutsch, im Grafit-Kupfer-Look. Die Schalter wirken sofort;
 * „Abbrechen“ setzt alles zurück, was seit dem Öffnen umgelegt wurde.
 */
final class SettingsSheet extends FrameLayout {
    interface Callbacks {
        void onLauncherSetting(String key, boolean value);

        void onSetDefaultPage();

        /** 0.17: jetzige Handy-Haltung als Ausgangslage für die gehaltene Kipp-Unschärfe. */
        void onSetNeutral();

        void onAddWidget();

        void onReloadIcons();

        void onOpenWallpaperSettings();

        void onChooseHomeApp();

        /** 0.28: Sichern und Wiederherstellen. */
        void onBackupMenu();

        /** 0.40: wie weit das Fenster offen ist (0 … 1) – der Startbildschirm dahinter verschwimmt im selben Maß. */
        void onSheetProgress(float progress);
    }

    static final String TILT = "tilt";
    static final String TRANSITIONS = "transitions";
    static final String LABELS = "labels";
    static final String GLOW = "glow";
    /** 0.39, wie im Video: keine Beschriftung unter den Symbolen, kein Glanzrand. */
    static final boolean LABELS_DEFAULT = false;
    static final boolean GLOW_DEFAULT = false;
    /** 0.39: Uhr einmalig oben links auf die Startseite gelegt (nur wenn dort Platz war). */
    static final String CLOCK_SEEDED = "clock_seeded";
    static final String UNLOCK = "unlock_icons";
    static final String DETENT = "detent";
    /** 0.30: Tiefen-Effekt – die Seite in Kipprichtung bleibt scharf und hebt sich an. */
    static final String DEPTH = "depth";
    /** 0.30: Richtung des Tiefen-Effekts umkehren (falls es sich falsch herum anfühlt). */
    static final String DEPTH_FLIP = "depth_flip";
    /** 0.35: Icons bekommen beim Kippen einen wandernden Schatten und wirken schwebend. */
    static final String SHADOW = "shadow3d";
    /** 0.40 „Startbildschirm v2“: neue Darstellung mit Kipp- und 3D-Effekt; aus = alles verschwimmt gleichmäßig. */
    static final String HOME_V2 = "home_v2";
    /** 0.40/0.41 „Flüssige Ordner“: Ordner wächst aus seinem Symbol heraus und federt nach. Im Video aus. */
    static final String LIQUID = "liquid_folder";
    /** 0.40/0.41 „Leuchteffekt“ (Hauptschalter). Im Video aus. */
    static final String GLOW_FX = "glow_fx";
    /** 0.41: Teil des Leuchteffekts – Lichtschein hinter den scharfen Symbolen beim Kippen. */
    static final String GLOW_TILT = "glow_tilt";
    /** 0.41: Teil des Leuchteffekts – kurzes Leuchten beim Antippen. */
    static final String GLOW_TAP = "glow_tap";
    /** 0.42: Blätter-Stil (0 Unschärfe, 1 Tiefe, 2 Würfel). */
    static final String PAGE_STYLE = "page_style";
    /** 0.42: Übergang beim Öffnen und Schließen von Apps. */
    static final String APP_TRANSITION = "app_transition";

    /** Alle Ein/Aus-Schalter dieses Fensters mit ihrer Grundeinstellung (für „Abbrechen“ und den Startbildschirm). */
    static boolean standard(String key) {
        switch (key) {
            case LABELS:
                return LABELS_DEFAULT;
            case GLOW:
                return GLOW_DEFAULT;
            case DEPTH_FLIP:
            case LIQUID:
            case GLOW_FX:
                return false;
            default:
                return true;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Callbacks callbacks;
    private final float density;
    private final LinearLayout topBar;
    private final ScrollView scroll;
    private final LinearLayout card;
    private final LinearLayout mainPage;
    private final LinearLayout transitionsPage;
    private final LinearLayout morePage;
    /** Stand beim Öffnen für „Abbrechen“: Schlüssel → {war gesetzt, Wert} bzw. Zahl. */
    private final Map<String, Object[]> before = new HashMap<>();
    private final Map<String, Runnable> refreshers = new HashMap<>();
    private ValueAnimator progressAnimator;
    private float progress;
    private boolean open;
    private boolean previewing;
    private final TextView previewExit;

    SettingsSheet(Context context, SharedPreferences prefs, Callbacks callbacks) {
        super(context);
        this.context = context;
        this.prefs = prefs;
        this.callbacks = callbacks;
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0x40000000);
        setVisibility(GONE);
        setOnClickListener(v -> { if (!previewing) close(); });

        // Oben über dem unscharfen Startbildschirm: „Abbrechen“ links, „Fertig“ rechts (wie im Video)
        topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int side = dp(16);
        topBar.setPadding(side, 0, side, 0);
        TextView cancel = pill("Abbrechen", false);
        cancel.setOnClickListener(v -> cancel());
        topBar.addView(cancel, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)));
        topBar.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView done = pill("Fertig", true);
        done.setOnClickListener(v -> close());
        topBar.addView(done, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)));
        addView(topBar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP));

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        scrollParams.topMargin = dp(60);
        addView(scroll, scrollParams);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {KippStyle.SHEET_TOP, KippStyle.SHEET_BOTTOM});
        shape.setCornerRadius(dp(30));
        card.setBackground(shape);
        card.setPadding(dp(16), dp(8), dp(16), dp(18));
        FrameLayout holder = new FrameLayout(context);
        holder.setPadding(dp(8), 0, dp(8), dp(8));
        holder.addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scroll.addView(holder, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        previewExit = pill("Zurück zu den Einstellungen", false);
        previewExit.setVisibility(GONE);
        previewExit.setOnClickListener(v -> exitPreview());
        LayoutParams exitParams = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        addView(previewExit, exitParams);

        mainPage = page();
        transitionsPage = page();
        morePage = page();
        buildMain();
        buildTransitions();
        buildMore();
        showPage(mainPage);
    }

    // ---------- Seiten ----------

    private void buildMain() {
        LinearLayout p = mainPage;
        p.addView(handle());
        p.addView(header("Startbildschirm-Einstellungen", true));

        LinearLayout group = group(p);
        switchRow(group, "Startbildschirm v2",
                "Neue Darstellung beim Kippen: eine Seite bleibt scharf, die andere verschwimmt (experimentell). "
                        + "Aus: alles verschwimmt gleichmäßig.", HOME_V2);
        navRow(group, "Übergangseffekte", () -> showPage(transitionsPage));
        navRow(group, "Startseite festlegen", () -> closeThen(callbacks::onSetDefaultPage));
        choiceRow(group, "Beschriftung", LABELS);
        switchRow(group, "Flüssige Ordner", "Ordner wachsen weich aus ihrem Symbol heraus.", LIQUID);
        switchRow(group, "Leuchteffekt", "Lichtschein beim Kippen und beim Antippen. Einzeln unter „Weitere Einstellungen“.", GLOW_FX);
        switchRow(group, "Kippen", null, TILT);

        LinearLayout preview = group(p);
        linkRow(preview, "Vorschau", () -> showPreview(true));

        LinearLayout tools = group(p);
        linkRow(tools, "Alle Symbole neu erzeugen", () -> closeThen(callbacks::onReloadIcons));
        linkRow(tools, "Weitere Einstellungen", () -> showPage(morePage)).append("  ›");
    }

    private void buildTransitions() {
        LinearLayout p = transitionsPage;
        p.addView(handle());
        p.addView(subHeader("Übergangseffekte"));
        LinearLayout group = group(p);
        switchRow(group, "Übergang beim Blättern", "Aus: Seiten schieben sich ohne Effekt.", TRANSITIONS);
        styleRow(group, "Blätter-Stil");
        switchRow(group, "Blättern mit Absatz", "Die Seite bleibt erst stehen und blättert dann weich weiter.", DETENT);
        LinearLayout apps = group(p);
        switchRow(apps, "Übergang beim Öffnen von Apps",
                "Die App wächst aus ihrem Symbol; zurück auf dem Startbildschirm setzt er sich weich auf seinen Platz.", APP_TRANSITION);
    }

    private void buildMore() {
        LinearLayout p = morePage;
        p.addView(handle());
        p.addView(subHeader("Weitere Einstellungen"));
        LinearLayout glow = group(p);
        switchRow(glow, "Leuchten beim Kippen", "Lichtschein hinter den scharfen Symbolen.", GLOW_TILT);
        switchRow(glow, "Leuchten beim Antippen", "Kurzes Aufleuchten des Symbols.", GLOW_TAP);
        switchRow(glow, "Glanzrand", "Heller Rand oben an den Symbolen.", GLOW);

        LinearLayout actions = group(p);
        linkRow(actions, "Alle Einstellungen (Kippen, Blättern, Foto)", () -> closeThen(callbacks::onOpenWallpaperSettings));
        linkRow(actions, "Jetzige Haltung als gerade", () -> closeThen(callbacks::onSetNeutral));
        linkRow(actions, "Widget hinzufügen", () -> closeThen(callbacks::onAddWidget));
        linkRow(actions, "Sichern und Wiederherstellen", () -> closeThen(callbacks::onBackupMenu));
        linkRow(actions, "Anderen Startbildschirm wählen", () -> closeThen(callbacks::onChooseHomeApp));
    }

    private LinearLayout page() {
        LinearLayout p = new LinearLayout(context);
        p.setOrientation(LinearLayout.VERTICAL);
        return p;
    }

    private void showPage(LinearLayout page) {
        if (page.getParent() == card) return;
        card.removeAllViews();
        card.addView(page, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (Runnable r : refreshers.values()) r.run();
        scroll.scrollTo(0, 0);
    }

    // ---------- Bausteine ----------

    private View handle() {
        View handle = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF4A4D52);
        shape.setCornerRadius(dp(2));
        handle.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(6);
        handle.setLayoutParams(params);
        return handle;
    }

    /** Kopfzeile: X links, Titel in der Mitte (wie im Video). */
    private View header(String title, boolean withClose) {
        FrameLayout row = new FrameLayout(context);
        row.setMinimumHeight(dp(48));
        TextView name = text(title, 17, KippStyle.TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        row.addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.CENTER));
        if (withClose) {
            TextView x = text("✕", 18, KippStyle.SUB);
            x.setGravity(Gravity.CENTER);
            x.setContentDescription("Schließen");
            x.setOnClickListener(v -> close());
            row.addView(x, new LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);
        return row;
    }

    private View subHeader(String title) {
        FrameLayout row = (FrameLayout) header(title, false);
        TextView back = text("‹ Zurück", 15, KippStyle.COPPER);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(dp(4), 0, dp(8), 0);
        back.setOnClickListener(v -> showPage(mainPage));
        row.addView(back, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        return row;
    }

    /** Abgesetzte Gruppe mit runden Ecken (wie die Kästen im Video). */
    private LinearLayout group(LinearLayout parent) {
        LinearLayout g = new LinearLayout(context);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0x40000000);
        shape.setCornerRadius(dp(20));
        g.setBackground(shape);
        g.setPadding(dp(16), dp(2), dp(12), dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        parent.addView(g, params);
        return g;
    }

    private LinearLayout row(LinearLayout group) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        if (group.getChildCount() > 0) {
            View line = new View(context);
            line.setBackgroundColor(KippStyle.LINE);
            group.addView(line, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density))));
        }
        group.addView(row, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    private LinearLayout titleBlock(LinearLayout row, String title, String subtitle) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), dp(8), dp(8));
        TextView name = text(title, 16, KippStyle.TEXT);
        block.addView(name);
        if (subtitle != null) {
            TextView sub = text(subtitle, 12, KippStyle.SUB);
            sub.setPadding(0, dp(2), 0, 0);
            block.addView(sub);
        }
        row.addView(block, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return block;
    }

    private void switchRow(LinearLayout group, String title, String subtitle, String key) {
        LinearLayout row = row(group);
        titleBlock(row, title, subtitle);
        Switch s = new Switch(context);
        tint(s);
        s.setShowText(false);
        s.setContentDescription(title);
        Runnable refresh = () -> s.setChecked(prefs.getBoolean(key, standard(key)));
        refresh.run();
        refreshers.put(key + "#" + System.identityHashCode(s), refresh);
        s.setOnCheckedChangeListener((view, checked) -> {
            if (checked == prefs.getBoolean(key, standard(key))) return;
            remember(key);
            prefs.edit().putBoolean(key, checked).apply();
        });
        row.setOnClickListener(v -> s.toggle());
        row.addView(s);
    }

    private void navRow(LinearLayout group, String title, Runnable run) {
        LinearLayout row = row(group);
        titleBlock(row, title, null);
        TextView arrow = text("›", 22, KippStyle.SUB);
        row.addView(arrow);
        row.setOnClickListener(v -> run.run());
    }

    /** „Beschriftung: Aus ↕“ wie „Show label: Off“ im Video – eine Auswahl, kein Schalter. */
    private void choiceRow(LinearLayout group, String title, String key) {
        LinearLayout row = row(group);
        titleBlock(row, title, null);
        TextView value = text("", 15, KippStyle.SUB);
        Runnable refresh = () -> value.setText((prefs.getBoolean(key, standard(key)) ? "An" : "Aus") + "  ⇅");
        refresh.run();
        refreshers.put(key + "#choice", refresh);
        row.addView(value);
        row.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, value, Gravity.END);
            menu.getMenu().add(0, 0, 0, "Aus");
            menu.getMenu().add(0, 1, 1, "An");
            menu.setOnMenuItemClickListener(item -> {
                boolean on = item.getItemId() == 1;
                if (on != prefs.getBoolean(key, standard(key))) {
                    remember(key);
                    prefs.edit().putBoolean(key, on).apply();
                }
                refresh.run();
                return true;
            });
            menu.show();
        });
    }

    /** 0.42: Blätter-Stil – Unschärfe, Tiefe oder Würfel. */
    private void styleRow(LinearLayout group, String title) {
        LinearLayout row = row(group);
        titleBlock(row, title, null);
        TextView value = text("", 15, KippStyle.SUB);
        Runnable refresh = () -> value.setText(
                LauncherMath.STYLE_NAMES[LauncherMath.pageStyle(prefs.getInt(PAGE_STYLE, LauncherMath.STYLE_BLUR))] + "  ⇅");
        refresh.run();
        refreshers.put(PAGE_STYLE + "#choice", refresh);
        row.addView(value);
        row.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, value, Gravity.END);
            for (int i = 0; i < LauncherMath.STYLE_NAMES.length; i++) menu.getMenu().add(0, i, i, LauncherMath.STYLE_NAMES[i]);
            menu.setOnMenuItemClickListener(item -> {
                int style = LauncherMath.pageStyle(item.getItemId());
                if (style != LauncherMath.pageStyle(prefs.getInt(PAGE_STYLE, LauncherMath.STYLE_BLUR))) {
                    remember(PAGE_STYLE);
                    prefs.edit().putInt(PAGE_STYLE, style).apply();
                }
                refresh.run();
                return true;
            });
            menu.show();
        });
    }

    private TextView linkRow(LinearLayout group, String title, Runnable run) {
        LinearLayout row = row(group);
        TextView view = text(title, 16, KippStyle.COPPER);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(view, new LinearLayout.LayoutParams(0, dp(52), 1f));
        if (run != null) row.setOnClickListener(v -> run.run());
        return view;
    }

    private TextView pill(String label, boolean primary) {
        TextView view = text(label, 15, primary ? KippStyle.ON_COPPER : KippStyle.TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(18), 0, dp(18), 0);
        if (primary) {
            view.setBackground(KippStyle.copper(dp(18)));
        } else {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(0x66202326);
            shape.setCornerRadius(dp(18));
            view.setBackground(shape);
        }
        return view;
    }

    private void tint(Switch s) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(states, new int[] {KippStyle.COPPER_LIGHT, 0xFFBDB7AE}));
        s.setTrackTintList(new android.content.res.ColorStateList(states, new int[] {KippStyle.COPPER_DARK, 0xFF4A4D52}));
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---------- Abbrechen ----------

    private void remember(String key) {
        if (before.containsKey(key)) return;
        boolean had = prefs.contains(key);
        Object value = null;
        if (had) value = prefs.getAll().get(key);
        before.put(key, new Object[] {had, value});
    }

    /** Alles, was seit dem Öffnen umgelegt wurde, zurücksetzen und schließen. */
    private void cancel() {
        if (!before.isEmpty()) {
            SharedPreferences.Editor edit = prefs.edit();
            for (Map.Entry<String, Object[]> e : before.entrySet()) {
                boolean had = (Boolean) e.getValue()[0];
                Object value = e.getValue()[1];
                if (!had) edit.remove(e.getKey());
                else if (value instanceof Boolean) edit.putBoolean(e.getKey(), (Boolean) value);
                else if (value instanceof Integer) edit.putInt(e.getKey(), (Integer) value);
            }
            edit.apply();
            before.clear();
        }
        close();
    }

    // ---------- Öffnen, Schließen, Vorschau ----------

    void setInsets(int top, int bottom) {
        LayoutParams bar = (LayoutParams) topBar.getLayoutParams();
        bar.topMargin = top + dp(6);
        topBar.setLayoutParams(bar);
        LayoutParams sp = (LayoutParams) scroll.getLayoutParams();
        sp.topMargin = top + dp(64);
        scroll.setLayoutParams(sp);
        scroll.setPadding(0, 0, 0, bottom);
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        open = true;
        before.clear();
        showPage(mainPage);
        for (Runnable r : refreshers.values()) r.run();
        setVisibility(VISIBLE);
        setAlpha(1f);
        scroll.setVisibility(VISIBLE);
        scroll.setAlpha(1f);
        topBar.setVisibility(VISIBLE);
        setBackgroundColor(0x40000000);
        topBar.setAlpha(0f);
        topBar.animate().alpha(1f).setDuration(LauncherMath.SHEET_OPEN_MS).start();
        scroll.setTranslationY(700 * density);
        scroll.animate().translationY(0f).setDuration(LauncherMath.SHEET_OPEN_MS)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        animateProgress(1f, LauncherMath.SHEET_OPEN_MS, null);
    }

    void close() {
        if (!open) return;
        open = false;
        previewing = false;
        previewExit.setVisibility(GONE);
        before.clear();
        topBar.animate().alpha(0f).setDuration(LauncherMath.SHEET_CLOSE_MS).start();
        float away = scroll.getHeight() > 0 ? scroll.getHeight() : 700 * density;
        scroll.animate().translationY(away).setDuration(LauncherMath.SHEET_CLOSE_MS).start();
        animateProgress(0f, LauncherMath.SHEET_CLOSE_MS, () -> setVisibility(GONE));
    }

    /** 0.43: sofort schließen ohne Animation (Startbildschirm wird verlassen, Bildschirm geht aus). */
    void closeNow() {
        if (!open && getVisibility() == GONE && progress == 0f) return;
        open = false;
        previewing = false;
        previewExit.setVisibility(GONE);
        before.clear();
        if (progressAnimator != null) progressAnimator.cancel();
        topBar.animate().cancel();
        scroll.animate().cancel();
        progress = 0f;
        callbacks.onSheetProgress(0f);
        setVisibility(GONE);
    }

    /** Erst schließen, dann die Aktion (öffnet z. B. einen Dialog über dem wieder scharfen Startbildschirm). */
    private void closeThen(Runnable run) {
        close();
        run.run();
    }

    boolean isPreviewing() { return previewing; }

    boolean exitPreview() {
        if (!previewing) return false;
        showPreview(false);
        return true;
    }

    private void showPreview(boolean show) {
        if (!open || previewing == show) return;
        previewing = show;
        scroll.animate().cancel();
        scroll.setTranslationY(0f);
        scroll.setVisibility(show ? INVISIBLE : VISIBLE);
        topBar.animate().cancel();
        topBar.setAlpha(1f);
        topBar.setVisibility(show ? INVISIBLE : VISIBLE);
        previewExit.setVisibility(show ? VISIBLE : GONE);
        if (show) previewExit.requestFocus();
        setBackgroundColor(show ? 0 : 0x40000000);
        callbacks.onSheetProgress(progress);
        animateProgress(show ? 0f : 1f, 150, null);
    }

    private void animateProgress(float target, long duration, Runnable end) {
        if (progressAnimator != null) progressAnimator.cancel();
        progressAnimator = ValueAnimator.ofFloat(progress, target);
        progressAnimator.setDuration(duration);
        progressAnimator.addUpdateListener(a -> {
            progress = (Float) a.getAnimatedValue();
            callbacks.onSheetProgress(progress);
        });
        progressAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                progress = target;
                callbacks.onSheetProgress(progress);
                if (end != null) end.run();
            }
        });
        progressAnimator.start();
    }
}
