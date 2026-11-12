package de.auria.kipphintergrund;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 0.28: Sichern und Wiederherstellen als JSON-Datei. Enthält Seiten, Dock, Ordner, Widgets (nur Anbieter und Platz –
 * Widget-Nummern gelten nur auf einem Gerät), beide Einstellungs-Speicher und auf Wunsch das Foto.
 * Alles hier läuft im Hintergrund-Thread; Ansichten fasst diese Klasse nicht an.
 */
final class Backup {
    static final String FORMAT = "kipp-start-sicherung";
    static final int VERSION = 1;
    /** Größte Sicherungsdatei, die eingelesen wird (Foto mit bis zu 3200 px passt bequem hinein). */
    static final int MAX_BYTES = 40 * 1024 * 1024;

    /** Ein Widget aus der Sicherung, das neu angelegt werden muss. */
    static final class WidgetSpot {
        ComponentName provider;
        int page;
        int x;
        int y;
        int w;
        int h;
    }

    /** Eingelesene Sicherung. */
    static final class Data {
        HomeModel home;
        final ArrayList<WidgetSpot> widgets = new ArrayList<>();
        JSONObject launcherPrefs;
        JSONObject wallpaperPrefs;
        byte[] photo;
        String appVersion = "";
        String created = "";
    }

    private Backup() {}

    // ---------- Schreiben ----------

    static void write(Context context, Uri uri, HomeModel model, AppWidgetManager widgets, boolean withPhoto)
            throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("format", FORMAT);
        json.put("version", VERSION);
        json.put("app", appVersion(context));
        json.put("created", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT)
                .format(new java.util.Date()));

        // Startbildschirm ohne Widgets (deren Nummern taugen woanders nichts) …
        HomeModel copy = model.copy();
        JSONArray widgetList = new JSONArray();
        java.util.Iterator<HomeModel.Item> it = copy.items.iterator();
        while (it.hasNext()) {
            HomeModel.Item item = it.next();
            if (item.type != HomeModel.Item.WIDGET) continue;
            it.remove();
            AppWidgetProviderInfo info = widgets.getAppWidgetInfo(item.widgetId);
            if (info == null || info.provider == null) continue;
            // … die Widgets stehen getrennt mit Anbieter und Platz darin
            JSONObject o = new JSONObject();
            o.put("provider", info.provider.flattenToString());
            o.put("page", item.page);
            o.put("x", item.x);
            o.put("y", item.y);
            o.put("w", item.w);
            o.put("h", item.h);
            widgetList.put(o);
        }
        json.put("home", copy.toJson());
        json.put("widgets", widgetList);

        JSONObject prefs = new JSONObject();
        prefs.put("launcher", prefsToJson(context.getSharedPreferences("launcher", Context.MODE_PRIVATE)));
        prefs.put("wallpaper", prefsToJson(PhotoStore.preferences(context)));
        json.put("prefs", prefs);

        if (withPhoto) {
            byte[] photo = PhotoStore.readImageBytes(context);
            if (photo != null) json.put("photo", Base64.encodeToString(photo, Base64.NO_WRAP));
        }

        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Datei lässt sich nicht schreiben");
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Einstellungen mit Typ, damit sie genau so zurückkommen. Die Bild-Nummer wird beim Laden neu gesetzt. */
    static JSONObject prefsToJson(SharedPreferences preferences) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : preferences.getAll().entrySet()) {
            Object v = e.getValue();
            if (PhotoStore.IMAGE.equals(e.getKey()) || v == null) continue;
            JSONObject typed = new JSONObject();
            if (v instanceof Boolean) typed.put("t", "b");
            else if (v instanceof Float) typed.put("t", "f");
            else if (v instanceof Integer) typed.put("t", "i");
            else if (v instanceof Long) typed.put("t", "l");
            else if (v instanceof String) typed.put("t", "s");
            else continue; // String-Mengen kommen nicht vor
            typed.put("v", v instanceof Float ? (double) (Float) v : v);
            out.put(e.getKey(), typed);
        }
        return out;
    }

    // ---------- Lesen ----------

    static Data read(Context context, Uri uri) throws IOException, JSONException {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Datei lässt sich nicht öffnen");
            bytes = readLimited(in, MAX_BYTES);
        }
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!LauncherMath.backupReadable(json.optString("format", ""), json.optInt("version", 0), FORMAT, VERSION)) {
            throw new JSONException("Keine Kipp-Start-Sicherung");
        }
        Data data = new Data();
        data.appVersion = json.optString("app", "");
        data.created = json.optString("created", "");
        JSONObject home = json.optJSONObject("home");
        data.home = home != null ? HomeModel.fromJson(home) : new HomeModel();
        // Widget-Einträge im Startbildschirm-Teil gelten nicht (falls jemand die Datei von Hand baut)
        data.home.items.removeIf(item -> item.type == HomeModel.Item.WIDGET);
        JSONArray widgets = json.optJSONArray("widgets");
        for (int i = 0; widgets != null && i < widgets.length(); i++) {
            JSONObject o = widgets.optJSONObject(i);
            if (o == null) continue;
            WidgetSpot spot = new WidgetSpot();
            spot.provider = ComponentName.unflattenFromString(o.optString("provider", ""));
            if (spot.provider == null) continue;
            spot.page = o.optInt("page", 0);
            spot.x = o.optInt("x", 0);
            spot.y = o.optInt("y", 0);
            spot.w = Math.max(1, Math.min(LauncherMath.COLUMNS, o.optInt("w", 1)));
            spot.h = Math.max(1, Math.min(LauncherMath.ROWS, o.optInt("h", 1)));
            data.widgets.add(spot);
        }
        JSONObject prefs = json.optJSONObject("prefs");
        if (prefs != null) {
            data.launcherPrefs = prefs.optJSONObject("launcher");
            data.wallpaperPrefs = prefs.optJSONObject("wallpaper");
        }
        String photo = json.optString("photo", "");
        if (!photo.isEmpty()) {
            try {
                data.photo = Base64.decode(photo, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                data.photo = null; // kaputtes Foto: Rest trotzdem übernehmen
            }
        }
        return data;
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > limit) throw new IOException("Datei ist zu groß");
        }
        return out.toByteArray();
    }

    /** Gespeicherte Einstellungen zurückschreiben (nur bekannte Typen; unbekannte Einträge werden übersprungen). */
    static void applyPrefs(SharedPreferences preferences, JSONObject values) {
        if (values == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (PhotoStore.IMAGE.equals(key)) continue;
            JSONObject typed = values.optJSONObject(key);
            if (typed == null || !typed.has("v")) continue;
            switch (typed.optString("t", "")) {
                case "b": editor.putBoolean(key, typed.optBoolean("v")); break;
                case "f": editor.putFloat(key, (float) typed.optDouble("v")); break;
                case "i": editor.putInt(key, typed.optInt("v")); break;
                case "l": editor.putLong(key, typed.optLong("v")); break;
                case "s": editor.putString(key, typed.optString("v")); break;
                default: break;
            }
        }
        editor.commit();
    }

    static String appVersion(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Für den Dateinamen: Kipp-Start-Sicherung-2026-11-12.json */
    static String fileName() {
        return "Kipp-Start-Sicherung-"
                + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date()) + ".json";
    }

    static byte[] readFile(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readLimited(in, MAX_BYTES);
        }
    }
}
