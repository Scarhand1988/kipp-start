package de.auria.kipphintergrund;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Eine Startbildschirm-Seite: Raster mit LauncherMath.COLUMNS × LauncherMath.ROWS Zellen (neu in 0.12). */
final class CellLayout extends ViewGroup {
    static final class Params extends ViewGroup.LayoutParams {
        final int x;
        final int y;
        final int w;
        final int h;

        Params(int x, int y, int w, int h) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private float cellWidth;
    private float cellHeight;

    CellLayout(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
    }

    float cellWidth() { return cellWidth; }

    float cellHeight() { return cellHeight; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        cellWidth = Math.max(1f, (width - getPaddingLeft() - getPaddingRight()) / (float) LauncherMath.COLUMNS);
        cellHeight = Math.max(1f, (height - getPaddingTop() - getPaddingBottom()) / (float) LauncherMath.ROWS);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Params p = (Params) child.getLayoutParams();
            child.measure(MeasureSpec.makeMeasureSpec(Math.round(cellWidth * p.w), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(cellHeight * p.h), MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Params p = (Params) child.getLayoutParams();
            int left = Math.round(getPaddingLeft() + p.x * cellWidth);
            int top = Math.round(getPaddingTop() + p.y * cellHeight);
            child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
            if (child instanceof WidgetView) {
                ((WidgetView) child).reportSize(child.getMeasuredWidth() / density, child.getMeasuredHeight() / density);
            }
        }
    }

    /** Zelle zu einer Position in dieser Seite: {Spalte, Zeile}. */
    int[] cellAt(float x, float y) {
        return new int[] {
                LauncherMath.cell(x, getPaddingLeft(), cellWidth, LauncherMath.COLUMNS),
                LauncherMath.cell(y, getPaddingTop(), cellHeight, LauncherMath.ROWS)};
    }

    View childAtCell(int cx, int cy) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            Params p = (Params) child.getLayoutParams();
            if (cx >= p.x && cx < p.x + p.w && cy >= p.y && cy < p.y + p.h) return child;
        }
        return null;
    }

    /** Liegt die Position in einer leeren Zelle (oder außerhalb des Rasters, z. B. Rand)? */
    boolean isEmptyAt(float x, float y) {
        if (y < getPaddingTop() || y > getHeight() - getPaddingBottom()) return true;
        int[] cell = cellAt(x, y);
        return childAtCell(cell[0], cell[1]) == null;
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof Params;
    }

    @Override public boolean shouldDelayChildPressedState() {
        return false;
    }
}
