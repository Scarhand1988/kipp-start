package de.auria.kipphintergrund;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.os.Bundle;
import android.util.SizeF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import java.util.Collections;

/** Rahmen für ein Widget auf dem Startbildschirm: erkennt langes Drücken zum Verschieben (neu in 0.12). */
final class WidgetView extends AppWidgetHostView {
    interface LongPress {
        void onWidgetLongPress(WidgetView view);
    }

    HomeModel.Item item;
    LongPress longPress;
    private final int slop;
    private float downX;
    private float downY;
    private boolean fired;
    private float reportedWidth;
    private float reportedHeight;
    private final Runnable fire = () -> {
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (longPress != null) longPress.onWidgetLongPress(this);
    };

    WidgetView(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                fired = false;
                downX = ev.getX();
                downY = ev.getY();
                removeCallbacks(fire);
                postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop) removeCallbacks(fire);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(fire);
                break;
            default:
                break;
        }
        return fired;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(fire);
        }
        return fired || super.onTouchEvent(ev);
    }

    /** Größe an das Widget melden (in dp), nur bei Änderung. */
    void reportSize(float widthDp, float heightDp) {
        if (Math.abs(widthDp - reportedWidth) < .5f && Math.abs(heightDp - reportedHeight) < .5f) return;
        reportedWidth = widthDp;
        reportedHeight = heightDp;
        try {
            updateAppWidgetSize(new Bundle(), Collections.singletonList(new SizeF(widthDp, heightDp)));
        } catch (RuntimeException ignored) {
            // Manche Widgets kommen mit Größenangaben nicht zurecht
        }
    }
}
