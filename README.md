# Kipp-Start

**Startbildschirm und Live-Hintergrund für Android mit Kipp-Effekt.** Neigst du das Handy, bewegt sich dein Hintergrundbild mit. Hintergrund, Symbole, Widgets und Dock verschwimmen zusammen und werden dunkler. Hältst du das Handy wieder gerade, wird alles scharf.

➡️ **[Neueste Version herunterladen](https://github.com/Scarhand1988/kipp-start/releases/latest)** (Datei `Kipp-Start.apk`)

> Frühe Version (Beta). Entwickelt auf einem Google Pixel 7, auf anderen Geräten noch ungetestet. Rückmeldungen gern über [Issues](https://github.com/Scarhand1988/kipp-start/issues).

## Was die App kann

- **Kipp-Hintergrund:** dein eigenes Foto als Live-Hintergrund. Es bewegt sich beim Neigen flüssig mit und verschwimmt zur Seite hin, die zu dir kippt.
- **Kipp-Start (eigener Startbildschirm):** Symbole, Widgets und Dock verschwimmen im selben Takt mit dem Hintergrund.
- **Blättern mit Absatz:** Die Seite bleibt erst stehen, rastet dann aus und blättert von selbst weich weiter. Die neue Seite blendet dabei unscharf ein.
- **Entsperr-Animation:** Nach dem Entsperren kommen die Symbole groß und unscharf herein und setzen sich an ihren Platz.
- **Startbildschirm-Grundfunktionen:** Seiten, Dock, App-Übersicht mit Suche, Widgets, Ziehen und Ablegen.
- **Einstellungen:** zwei Finger zusammenziehen oder lange auf eine leere Stelle drücken. Jeder Effekt lässt sich einzeln ausschalten.
- **Schnellschalter** „Kipp-Effekt“ für die Schnelleinstellungen.

## Datenschutz

Die App fordert **keinen Internetzugriff** an. Dein Foto bleibt im privaten Speicher der App auf deinem Handy. Es gibt keine Werbung, kein Konto und keine Analyse.

## Installieren

Voraussetzung: **Android 12 oder neuer**.

1. Auf dem Handy die [neueste Version](https://github.com/Scarhand1988/kipp-start/releases/latest) öffnen und **`Kipp-Start.apk`** herunterladen.
2. Die Datei öffnen. Fragt Android nach, erlaubst du deinem Browser oder deiner Dateien-App, Apps zu installieren. Android kann bei Apps außerhalb des Play Store eine Warnung zeigen.
3. Die App **Kipp-Hintergrund** öffnen und dein Bild auswählen. Dann „Android-Vorschau öffnen“ tippen und als Hintergrund für den **Startbildschirm** festlegen.
4. In derselben App **„Kipp-Start als Startbildschirm wählen“** tippen und Kipp-Start auswählen.

**Zurück zum alten Startbildschirm:** Im Kipp-Start-Menü „Anderen Startbildschirm wählen“ tippen. Alternativ geht es in den Android-Einstellungen bei den Standard-Apps.

**Updates:** Neue Versionen erscheinen unter [Releases](https://github.com/Scarhand1988/kipp-start/releases). Die neue `Kipp-Start.apk` installiert sich einfach über die alte, deine Einstellungen bleiben erhalten.

## Bedienung

| Geste | Wirkung |
|---|---|
| Handy neigen | Hintergrund bewegt sich, alles verschwimmt und wird dunkler |
| Seitlich wischen | Blättern mit Absatz |
| Nach oben wischen oder „Suche“ antippen | App-Übersicht mit Suche |
| Nach unten wischen | Benachrichtigungen |
| Symbol lange drücken und ziehen | Verschieben; oben auf „Entfernen“ oder „App-Info“ ablegen |
| Zwei Finger zusammenziehen oder lange auf eine leere Stelle | Startbildschirm-Einstellungen |

## Grenzen

- **Nur Android.** Auf dem iPhone sind eigene Startbildschirme und Live-Hintergründe von anderen Apps nicht möglich.
- **Was Kipp-Start nicht hat:** den Google-Feed links, „Auf einen Blick“ und Ordner (noch nicht).

## Selbst bauen

Jeder Push auf `main` baut die App per GitHub Actions. Vorher laufen die Prüfungen in `checks/` (Bewegung, Zeichnen, Launcher-Logik), danach Lint. Danach wird die App signiert, der Zertifikat-Fingerabdruck geprüft und sie als Release veröffentlicht. Zum Signieren braucht ein eigener Fork die Secrets `KIPP_KEYSTORE_B64` und `KIPP_KEYSTORE_PASSWORD` mit eigenem Schlüssel, außerdem einen angepassten Fingerabdruck `KIPP_CERT_SHA256` im Workflow. Technische Details stehen im [Änderungsverlauf](CHANGELOG.md).

## Lizenz

[MIT](LICENSE)

---

## English

**Kipp-Start** is a home screen and live wallpaper for Android with a tilt effect. Tilt your phone and your wallpaper moves with it. Wallpaper, icons, widgets and dock blur and dim together, and everything turns sharp again when you hold the phone straight. Pages snap with a short detent, and icons fly in after unlocking.

- **Download:** [latest release](https://github.com/Scarhand1988/kipp-start/releases/latest) → `Kipp-Start.apk` (Android 12+). Early beta, developed on a Pixel 7.
- **Setup:** Install the APK. In the **Kipp-Hintergrund** app, pick your photo and set it as home screen wallpaper. Then tap “Kipp-Start als Startbildschirm wählen” to make Kipp-Start your home app. The app is in German for now.
- **Privacy:** no internet permission, no ads, no account. Your photo stays on your phone.
- **License:** MIT.
