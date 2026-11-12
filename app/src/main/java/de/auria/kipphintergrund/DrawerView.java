package de.auria.kipphintergrund;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** App-Übersicht mit Suche (neu in 0.12): Wischen nach oben oder Tippen auf „Suche“ öffnet sie. */
final class DrawerView extends FrameLayout {
    interface Callbacks {
        void onDrawerAppClick(IconView view);

        boolean onDrawerAppLongClick(IconView view);

        /** Übersicht geöffnet oder geschlossen (0.15: Kipp-Effekt der Symbole dabei aussetzen). */
        void onDrawerOpenChanged();
    }

    private final Callbacks callbacks;
    private final int iconSize;
    private final float density;
    private final int slop;
    private final EditText search;
    private final GridView grid;
    private final AppAdapter adapter = new AppAdapter();
    private final LinearLayout column;
    private List<AppIndex.App> all = new ArrayList<>();
    private final ArrayList<AppIndex.App> shown = new ArrayList<>();
    private boolean open;
    private float downY;
    private boolean closing;

    DrawerView(Context context, int iconSize, Callbacks callbacks) {
        super(context);
        this.callbacks = callbacks;
        this.iconSize = iconSize;
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(0xF01E2023); // 0.24: Grafit wie der Einstellungs-Bildschirm
        setClickable(true);
        setVisibility(GONE);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        search = new EditText(context);
        search.setHint("Apps suchen");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setTextColor(KippStyle.TEXT);
        search.setHintTextColor(KippStyle.SUB);
        search.setTextSize(16);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(KippStyle.inset(24 * density, density));
        search.setCompoundDrawablePadding(Math.round(10 * density));
        android.graphics.drawable.ShapeDrawable dot = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.OvalShape());
        dot.getPaint().setColor(KippStyle.COPPER);
        dot.setIntrinsicWidth(Math.round(8 * density));
        dot.setIntrinsicHeight(Math.round(8 * density));
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        int pad = Math.round(18 * density);
        search.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Math.round(48 * density));
        searchParams.setMargins(Math.round(20 * density), Math.round(12 * density), Math.round(20 * density), Math.round(12 * density));
        TextView heading = new TextView(context);
        heading.setText("ALLE APPS");
        heading.setTextSize(12);
        heading.setLetterSpacing(.12f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setTextColor(KippStyle.COPPER);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        headingParams.setMargins(Math.round(24 * density), Math.round(14 * density), 0, 0);
        column.addView(heading, headingParams);
        column.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });
        search.setOnEditorActionListener((view, action, event) -> action == EditorInfo.IME_ACTION_GO && launchFirst());

        grid = new GridView(context);
        grid.setNumColumns(LauncherMath.COLUMNS);
        grid.setVerticalSpacing(Math.round(8 * density));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setClipToPadding(false);
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> callbacks.onDrawerAppClick((IconView) view));
        grid.setOnItemLongClickListener((parent, view, position, id) -> callbacks.onDrawerAppLongClick((IconView) view));
        column.addView(grid, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void setInsets(int top, int bottom) {
        column.setPadding(0, top, 0, 0);
        grid.setPadding(Math.round(12 * density), 0, Math.round(12 * density), bottom + Math.round(16 * density));
    }

    void setApps(List<AppIndex.App> apps) {
        all = apps;
        filter(search.getText().toString());
    }

    boolean isOpen() {
        return open;
    }

    void open(boolean keyboard) {
        if (open) return;
        open = true;
        closing = false;
        callbacks.onDrawerOpenChanged();
        setVisibility(VISIBLE);
        animate().cancel();
        setAlpha(0f);
        setTranslationY(getHeight() > 0 ? getHeight() * .25f : 400 * density);
        animate().alpha(1f).translationY(0f).setDuration(260).withEndAction(null).start();
        grid.setSelection(0);
        if (keyboard) {
            search.requestFocus();
            search.postDelayed(() -> {
                InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
                if (ime != null) ime.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        }
    }

    void close(boolean animate) {
        if (!open) return;
        open = false;
        callbacks.onDrawerOpenChanged();
        InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
        if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
        animate().cancel();
        if (animate) {
            closing = true;
            animate().alpha(0f).translationY(getHeight() * .25f).setDuration(200).withEndAction(() -> {
                closing = false;
                setVisibility(GONE);
                search.setText("");
            }).start();
        } else {
            setVisibility(GONE);
            search.setText("");
        }
    }

    /** Ganz oben in der Liste nach unten wischen schließt die Übersicht. */
    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) downY = ev.getY();
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && !closing) {
            float dy = ev.getY() - downY;
            if (dy > slop * 3 && !grid.canScrollVertically(-1)) return true;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP && ev.getY() - downY > 60 * density) {
            close(true);
        }
        return true;
    }

    /** Tastatur „Los“: erstes Suchergebnis starten. */
    private boolean launchFirst() {
        if (shown.isEmpty()) return false;
        View first = grid.getChildAt(0);
        if (first instanceof IconView) callbacks.onDrawerAppClick((IconView) first);
        return true;
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppIndex.App app : all) {
            if (q.isEmpty() || app.search.contains(q)) shown.add(app);
        }
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() {
            return shown.size();
        }

        @Override public Object getItem(int position) {
            return shown.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            IconView view = convertView instanceof IconView ? (IconView) convertView : new IconView(getContext(), iconSize);
            view.setClickable(false);
            view.setLongClickable(false);
            view.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Math.round(98 * density)));
            view.bind(shown.get(position), true);
            return view;
        }
    }
}
