package de.auria.kipphintergrund;

import android.content.ComponentName;
import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Aufbau des eigenen Startbildschirms: Seiten mit Apps und Widgets, Dock, Startseite (neu in 0.12). */
final class HomeModel {
    static final class Item {
        static final int APP = 0;
        static final int WIDGET = 1;
        int type;
        ComponentName component;
        int widgetId = -1;
        int page;
        int x;
        int y;
        int w = 1;
        int h = 1;

        static Item app(ComponentName component, int page, int x, int y) {
            Item item = new Item();
            item.type = APP;
            item.component = component;
            item.page = page;
            item.x = x;
            item.y = y;
            return item;
        }

        static Item widget(int id, int page, int x, int y, int w, int h) {
            Item item = new Item();
            item.type = WIDGET;
            item.widgetId = id;
            item.page = page;
            item.x = x;
            item.y = y;
            item.w = w;
            item.h = h;
            return item;
        }

        Item copy() {
            Item c = new Item();
            c.type = type;
            c.component = component;
            c.widgetId = widgetId;
            c.page = page;
            c.x = x;
            c.y = y;
            c.w = w;
            c.h = h;
            return c;
        }

        boolean covers(int cx, int cy) {
            return cx >= x && cx < x + w && cy >= y && cy < y + h;
        }
    }

    static final int MAX_PAGES = 8;

    final ArrayList<Item> items = new ArrayList<>();
    final ComponentName[] dock = new ComponentName[LauncherMath.DOCK];
    int pages = 1;
    int defaultPage;
    /** Noch nie gespeichert: Startbelegung anlegen. */
    boolean fresh;

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "home.json"));
    }

    static HomeModel load(Context context) {
        HomeModel model = new HomeModel();
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) {
            model.fresh = true;
            return model;
        }
        try {
            JSONObject json = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            model.pages = Math.max(1, Math.min(MAX_PAGES, json.optInt("pages", 1)));
            model.defaultPage = Math.max(0, Math.min(model.pages - 1, json.optInt("defaultPage", 0)));
            JSONArray items = json.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.optJSONObject(i);
                    if (o == null) continue;
                    Item item = new Item();
                    item.type = o.optInt("type", Item.APP);
                    item.page = o.optInt("page", 0);
                    item.x = o.optInt("x", 0);
                    item.y = o.optInt("y", 0);
                    item.w = Math.max(1, o.optInt("w", 1));
                    item.h = Math.max(1, o.optInt("h", 1));
                    if (item.type == Item.APP) {
                        item.component = ComponentName.unflattenFromString(o.optString("component", ""));
                        if (item.component == null) continue;
                    } else {
                        item.widgetId = o.optInt("widget", -1);
                        if (item.widgetId < 0) continue;
                    }
                    if (item.page < 0 || item.page >= model.pages) continue;
                    model.items.add(item);
                }
            }
            JSONArray dock = json.optJSONArray("dock");
            if (dock != null) {
                for (int i = 0; i < LauncherMath.DOCK && i < dock.length(); i++) {
                    String value = dock.optString(i, "");
                    model.dock[i] = value.isEmpty() ? null : ComponentName.unflattenFromString(value);
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            model.items.clear();
            model.fresh = true;
        }
        return model;
    }

    /** 0.25: unabhängige Kopie für „Rückgängig“. */
    HomeModel copy() {
        HomeModel c = new HomeModel();
        for (Item item : items) c.items.add(item.copy());
        System.arraycopy(dock, 0, c.dock, 0, dock.length);
        c.pages = pages;
        c.defaultPage = defaultPage;
        return c;
    }

    /** Stand aus einer Kopie zurückholen (Rückgängig). */
    void restoreFrom(HomeModel other) {
        items.clear();
        for (Item item : other.items) items.add(item.copy());
        System.arraycopy(other.dock, 0, dock, 0, dock.length);
        pages = other.pages;
        defaultPage = other.defaultPage;
    }

    /** Vergleichbarer Stand als Text (gleich = nichts geändert). */
    String fingerprint() {
        try {
            return toJson().toString();
        } catch (JSONException e) {
            return "";
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("pages", pages);
        json.put("defaultPage", defaultPage);
        JSONArray list = new JSONArray();
        for (Item item : items) {
            JSONObject o = new JSONObject();
            o.put("type", item.type);
            o.put("page", item.page);
            o.put("x", item.x);
            o.put("y", item.y);
            o.put("w", item.w);
            o.put("h", item.h);
            if (item.type == Item.APP) o.put("component", item.component.flattenToString());
            else o.put("widget", item.widgetId);
            list.put(o);
        }
        json.put("items", list);
        JSONArray dockList = new JSONArray();
        for (ComponentName c : dock) dockList.put(c == null ? "" : c.flattenToString());
        json.put("dock", dockList);
        return json;
    }

    private static void writeJson(Context context, JSONObject json) {
        AtomicFile file = file(context);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException e) {
            if (out != null) file.failWrite(out);
        }
    }

    void save(Context context) {
        try {
            writeJson(context, toJson());
            fresh = false;
        } catch (JSONException ignored) {
            // Nächster Versuch beim nächsten Ändern
        }
    }

    boolean[][] occupied(int page, Item ignore) {
        boolean[][] used = new boolean[LauncherMath.COLUMNS][LauncherMath.ROWS];
        for (Item item : items) {
            if (item.page != page || item == ignore) continue;
            for (int x = Math.max(0, item.x); x < Math.min(LauncherMath.COLUMNS, item.x + item.w); x++) {
                for (int y = Math.max(0, item.y); y < Math.min(LauncherMath.ROWS, item.y + item.h); y++) used[x][y] = true;
            }
        }
        return used;
    }

    Item itemAt(int page, int x, int y) {
        for (Item item : items) {
            if (item.page == page && item.covers(x, y)) return item;
        }
        return null;
    }

    boolean pageEmpty(int page) {
        for (Item item : items) {
            if (item.page == page) return false;
        }
        return true;
    }

    /** Leere Seiten am Ende entfernen (mindestens eine bleibt, die Startseite bleibt). */
    void trimPages() {
        while (pages > 1 && pages - 1 > defaultPage && pageEmpty(pages - 1)) pages--;
    }

    /** Leere Seite dazwischen entfernen und die folgenden nachrücken lassen. */
    void removeEmptyPage(int page) {
        if (pages <= 1 || !pageEmpty(page)) return;
        for (Item item : items) {
            if (item.page > page) item.page--;
        }
        pages--;
        if (defaultPage >= pages) defaultPage = pages - 1;
        else if (defaultPage > page) defaultPage--;
    }

    /** Deinstallierte App überall entfernen. */
    void removePackage(String packageName) {
        Iterator<Item> it = items.iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (item.type == Item.APP && packageName.equals(item.component.getPackageName())) it.remove();
        }
        for (int i = 0; i < dock.length; i++) {
            if (dock[i] != null && packageName.equals(dock[i].getPackageName())) dock[i] = null;
        }
    }
}
