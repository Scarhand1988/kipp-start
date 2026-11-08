package de.auria.kipphintergrund;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Schalter in den Schnelleinstellungen (wie „Automatisch drehen“): Kipp-Effekt an/aus. */
public final class ToggleTileService extends TileService {

    @Override public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override public void onClick() {
        super.onClick();
        SharedPreferences preferences = PhotoStore.preferences(this);
        boolean on = !preferences.getBoolean(PhotoStore.ENABLED, true);
        preferences.edit().putBoolean(PhotoStore.ENABLED, on).apply();
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = PhotoStore.preferences(this).getBoolean(PhotoStore.ENABLED, true);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_name));
        tile.setSubtitle(on ? "An" : "Aus");
        tile.updateTile();
    }
}
