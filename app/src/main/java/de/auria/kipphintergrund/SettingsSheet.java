package de.auria.kipphintergrund;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Startbildschirm-Einstellungen wie im Video (neu in 0.12): öffnet sich mit Zusammenziehen oder langem
 * Drücken auf eine leere Stelle. Die Schalter wirken sofort.
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
    }

    static final String TILT = "tilt";
    static final String TRANSITIONS = "transitions";
    static final String LABELS = "labels";
    static final String GLOW = "glow";
    static final String UNLOCK = "unlock_icons";
    static final String DETENT = "detent";

    private final float density;
    private final LinearLayout card;
    private final ScrollView scroll;
    private boolean open;

    SettingsSheet(Context context, SharedPreferences prefs, Callbacks callbacks) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0x55000000);
        setVisibility(GONE);
        setOnClickListener(v -> close());

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(scroll, scrollParams);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF21C2024);
        float r = 28 * density;
        background.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        card.setBackground(background);
        int pad = Math.round(22 * density);
        card.setPadding(pad, Math.round(10 * density), pad, pad);
        scroll.addView(card, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View handle = new View(context);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(0x66FFFFFF);
        handleShape.setCornerRadius(2 * density);
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(Math.round(40 * density), Math.round(4 * density));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = Math.round(14 * density);
        card.addView(handle, handleParams);

        TextView title = text("Startbildschirm", 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, Math.round(10 * density));
        card.addView(title);

        // 0.18: Alle Schalter liegen jetzt im gemeinsamen Einstellungs-Bildschirm (Kippen / Blättern / Foto);
        // hier bleiben nur Dinge, die diesen Startbildschirm direkt betreffen.
        action("Alle Einstellungen", callbacks::onOpenWallpaperSettings);
        action("Jetzige Haltung als gerade", callbacks::onSetNeutral);
        action("Diese Seite als Startseite", callbacks::onSetDefaultPage);
        action("Widget hinzufügen", callbacks::onAddWidget);
        action("Symbole neu laden", callbacks::onReloadIcons);
        action("Anderen Startbildschirm wählen", callbacks::onChooseHomeApp);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void action(String label, Runnable run) {
        TextView view = text(label, 16, 0xFF8AB4F8);
        view.setMinHeight(Math.round(48 * density));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setOnClickListener(v -> {
            close();
            run.run();
        });
        card.addView(view);
    }

    void setInsets(int bottom) {
        card.setPadding(card.getPaddingLeft(), card.getPaddingTop(), card.getPaddingRight(), Math.round(22 * density) + bottom);
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().cancel();
        animate().alpha(1f).setDuration(200).withEndAction(null).start();
        scroll.setTranslationY(600 * density);
        scroll.animate().translationY(0f).setDuration(300).start();
    }

    void close() {
        if (!open) return;
        open = false;
        animate().cancel();
        scroll.animate().translationY(scroll.getHeight()).setDuration(220).start();
        animate().alpha(0f).setDuration(220).withEndAction(() -> setVisibility(GONE)).start();
    }
}
