package de.auria.kipphintergrund;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.UserHandle;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Alle startbaren Apps mit fertig gezeichneten Symbolen (neu in 0.12). Laden im Hintergrund-Thread. */
final class AppIndex {
    static final class App {
        final ComponentName component;
        final UserHandle user;
        final String label;
        final String search;
        final Bitmap icon;

        App(ComponentName component, UserHandle user, String label, Bitmap icon) {
            this.component = component;
            this.user = user;
            this.label = label;
            this.search = label.toLowerCase(Locale.ROOT);
            this.icon = icon;
        }
    }

    final List<App> apps;
    private final Map<ComponentName, App> byComponent;

    private AppIndex(List<App> apps) {
        this.apps = Collections.unmodifiableList(apps);
        byComponent = new HashMap<>();
        // Gleiche App in beiden Profilen: Startbildschirm und Dock meinen die aus dem eigenen Profil
        UserHandle own = Process.myUserHandle();
        for (App app : apps) {
            App known = byComponent.get(app.component);
            if (known == null || (!own.equals(known.user) && own.equals(app.user))) byComponent.put(app.component, app);
        }
    }

    static AppIndex empty() {
        return new AppIndex(new ArrayList<>());
    }

    App get(ComponentName component) {
        return component == null ? null : byComponent.get(component);
    }

    /** Erste App aus einem Paket (für Standard-Apps im Dock). */
    App firstOf(String packageName) {
        if (packageName == null) return null;
        App other = null;
        for (App app : apps) {
            if (!packageName.equals(app.component.getPackageName())) continue;
            if (Process.myUserHandle().equals(app.user)) return app;
            if (other == null) other = app;
        }
        return other;
    }

    boolean isEmpty() {
        return apps.isEmpty();
    }

    static AppIndex load(Context context, int iconSize, boolean glow) {
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        UserHandle user = Process.myUserHandle();
        int density = context.getResources().getDisplayMetrics().densityDpi;
        ArrayList<App> apps = new ArrayList<>();
        // 0.17: auch Apps aus einem Arbeitsprofil (mit Profil-Abzeichen am Symbol); das eigene Profil zuerst
        List<UserHandle> users = new ArrayList<>();
        users.add(user);
        try {
            for (UserHandle other : launcherApps.getProfiles()) if (!other.equals(user)) users.add(other);
        } catch (RuntimeException ignored) {}
        List<LauncherActivityInfo> list = new ArrayList<>();
        for (UserHandle profile : users) {
            try {
                list.addAll(launcherApps.getActivityList(null, profile));
            } catch (RuntimeException ignored) {
                // gesperrtes oder pausiertes Arbeitsprofil: einfach auslassen
            }
        }
        for (LauncherActivityInfo info : list) {
            try {
                boolean own = user.equals(info.getUser());
                Bitmap icon = IconFactory.render(own ? info.getIcon(density) : info.getBadgedIcon(density), iconSize, glow);
                apps.add(new App(info.getComponentName(), info.getUser(), String.valueOf(info.getLabel()), icon));
            } catch (RuntimeException ignored) {
                // Einzelne kaputte Symbole dürfen den Startbildschirm nicht verhindern
            }
        }
        Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        return new AppIndex(apps);
    }
}
