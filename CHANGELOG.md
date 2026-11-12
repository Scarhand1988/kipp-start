# Änderungsverlauf

## 0.25 – Rückgängig

- Nach jedem Verschieben oder Entfernen von Symbolen, Dock-Apps und Widgets erscheint unten 5 Sekunden lang „Verschoben / Entfernt · **Rückgängig**“. Antippen stellt den Stand davor wieder her.
- Entfernte Widgets werden erst endgültig gelöscht, wenn das Angebot verfallen ist – sonst ließen sie sich nicht zurückholen.
- `HomeModel` kann sich kopieren, vergleichen und wiederherstellen; Speichern läuft über eine gemeinsame `toJson()`.

## 0.24 – App-Übersicht und Startbildschirm-Blatt im Grafit-Kupfer-Look

- **App-Übersicht:** Grafit-Hintergrund, Überschrift „ALLE APPS“ in Kupfer, Suchfeld eingelassen mit Kupfer-Rand und -Punkt.
- **Startbildschirm-Blatt:** Grafit-Verlauf wie im Einstellungs-Bildschirm, „Alle Einstellungen“ als großer Kupfer-Knopf, die übrigen Aktionen als Kupfer-Zeilen mit Trennlinien.
- Neue gemeinsame Stil-Klasse `KippStyle` für Farben und Formen.

## 0.23 – Messanzeige auch für den Startbildschirm

- Ist die **Messanzeige** an, zeigt Kipp-Start oben rechts die Bilder des Startbildschirms selbst: Bilder pro Sekunde, wie viele davon ihren Anzeigetermin verpasst haben („ruckelig“) und die mittlere Zeit pro Bild – gemessen mit Androids Frame-Metriken, jeweils über eine Sekunde. Oben links bleibt die Anzeige des Hintergrunds.
- Damit lässt sich beim Blättern, Antippen und Entsperren mit Zahlen prüfen, ob es ruckelt.

## 0.22 – Sensor-Ruhemodus

- Liegt das Handy etwa 2,5 Sekunden still (Drehung unter ca. 0,75°), liest der Hintergrund den Lagesensor nur noch 25-mal statt 100-mal pro Sekunde. Bei der ersten spürbaren Drehung schaltet er sofort zurück. Spart Akku, wenn das Handy auf dem Tisch liegt.
- `MotionCheck` prüft die Ruhe-Erkennung (`MotionMath.quatDelta`, `moved`).

## 0.21 – Hintergrund zeichnet im eigenen Thread

- **Eigener Zeichen-Thread mit eigenem Bildtakt** für den Kipp-Hintergrund. Vorher zeichneten Hintergrund und Startbildschirm beide im Haupt-Thread; jedes Hintergrundbild kostete dem Startbildschirm Zeit beim Wischen und Antippen.
- Zeichnen und das Austauschen/Freigeben von Bildern sind gegeneinander gesperrt; Fotowechsel laufen im Zeichen-Thread, Farbmeldung und Sensor weiter im Haupt-Thread.
- `TiltBus` darf jetzt aus jedem Thread melden; der Startbildschirm bekommt die Werte gebündelt im Haupt-Thread.

## 0.20 – Live-Vorschau bewegt sich

- **Echte Live-Vorschau:** Das Foto oben im Einstellungs-Bildschirm bewegt sich jetzt beim Kippen genau wie der Hintergrund und verschwimmt mit der eingestellten Kipp-Unschärfe. Vorher war es ein stehendes Bild. Im Reiter Foto steht es still, damit sich der Ausschnitt genau einstellen lässt.
- Die Vorschau passt jetzt immer ganz über das Einstellungs-Blatt (war unten abgeschnitten).

## 0.19 – Icon passend zur Live-Vorschau

- **Neues Icon „Kupfer-Linien“:** dasselbe Motiv wie bisher (gekipptes Foto vor einer zweiten Ebene), jetzt als Kupfer-Linien auf Grafit – passend zum Einstellungs-Bildschirm aus 0.18.
- Einfarbige Ebene für „Designsymbole“ und das runde Vorschaubild in der Hintergrund-Auswahl zeigen dasselbe Motiv.

## 0.18 – neuer Einstellungs-Bildschirm „Live-Vorschau“

- **Ein gemeinsamer Bildschirm** für Hintergrund und Startbildschirm (Layout C): oben eine Live-Vorschau mit deinem Foto, Ausschnitt und Kipp-Unschärfe, leicht schräg wie ein gekipptes Handy; darunter ein Blatt mit den Reitern **Kippen**, **Blättern** und **Foto**.
- Dunkles Grafit mit Kupfer-Akzenten, eigene Schalter und Regler in Kupfer; der große Knopf unten wechselt je Reiter: „Jetzige Haltung als gerade“, „Kipp-Start als Startbildschirm wählen“, „Foto wählen“.
- Im Reiter Foto steht die Vorschau gerade, damit sich der Ausschnitt mit dem Finger einstellen lässt.
- Die Schalter aus dem Startbildschirm-Blatt (Kippen der Symbole, Übergänge, Blättern mit Absatz, Symbole fliegen herein, Beschriftungen, Glanzrand) sind in den gemeinsamen Bildschirm umgezogen und wirken sofort. Das Blatt im Startbildschirm hat nur noch „Alle Einstellungen“ und die Aktionen für diesen Startbildschirm.

## 0.17 – Kippen & Stabilität

- **Dock:** Ziehst du eine App aus der Übersicht auf einen belegten Dock-Platz, bekommt die bisherige App zuerst einen freien Platz (aktuelle Seite, dann die anderen). Gibt es keinen, bleibt das Dock unverändert und ein Hinweis erscheint. Vorher ging sie bei voller Seite verloren.
- **Verschwundene Widgets:** Meldet Android ein gespeichertes Widget nicht mehr, steht dort jetzt ein Platzhalter „Widget nicht verfügbar“. Lange drücken und auf Entfernen ziehen gibt den Platz frei. Vorher war der Platz unsichtbar belegt.
- **Unschärfe bleibt:** ab Werk aus (gilt, solange du die Option nie selbst umgestellt hast). Neu in den Startbildschirm-Einstellungen: „Jetzige Haltung als gerade“ – die Haltung in diesem Moment wird zur Ausgangslage, das Bild wird sofort scharf.
- **Arbeitsprofil:** Apps aus einem Arbeitsprofil erscheinen in der App-Übersicht, mit dem Profil-Abzeichen am Symbol. Ist eine App in beiden Profilen, nutzen Startbildschirm und Dock die aus dem eigenen Profil.

## 0.16 – neues App-Icon

- **Neues Icon „Ebenen“:** ein nach rechts gekipptes Foto (Himmel, Sonne, Berge) vor einer zweiten, nach links gekippten Ebene auf dunklem Verlauf – passend zum Kippeffekt.
- Als **Pixel-Icon** gebaut (`mipmap-anydpi-v26/ic_launcher.xml` mit Hintergrund-, Vordergrund- und einfarbiger Ebene): das Pixel schneidet es rund zu, bewegt die Ebenen leicht und färbt es bei „Designsymbolen“ passend zu deinen Farben ein.
- Das runde Vorschaubild in der Hintergrund-Auswahl zeigt dasselbe Motiv.

## 0.15 – weniger Ruckeln

- **Kipp-Effekt auf den Symbolen:** Unschärfe und Abdunklung werden erst bei einer sichtbaren Änderung neu gesetzt (Unschärfe in 2-px-, Abdunklung in 3/255-Schritten, zurück in Ruhe immer sofort ganz). Vorher änderte schon das Zittern der Hand die Stufe in fast jedem Bild, und die Grafik musste den ganzen Startbildschirm jedes Mal neu weichzeichnen – zusätzlich zum Hintergrund, der im selben Prozess zeichnet. Bei ruhiger Hand bleibt das weichgezeichnete Bild jetzt im Zwischenspeicher der Grafik.
- **Blättern:** Seiten ganz außerhalb des Bildschirms (ohnehin unsichtbar) bekommen keinen Weichzeichner mehr; die Unschärfe der Nachbarseite läuft in geraden Pixelstufen.
- **Antippen nach dem Blättern:** Sobald die einrastende Seite höchstens 6 % ihrer Breite vom Ziel entfernt ist (nach ca. 0,3 s), trifft ein Tippen das Symbol; die Seite rastet weich zu Ende ein. Vorher wurde jedes Tippen bis zum völligen Stillstand (ca. 0,7 s) verschluckt.
- **App-Übersicht:** Solange sie offen ist, wird die Liste beim Kippen nicht mehr unscharf und dunkel; Scrollen muss nicht in jedem Bild den ganzen Bildschirm weichzeichnen.
- `checks/LauncherCheck` prüft die neuen Regeln (`settle`, `pageBlurPixels`, `pageVisible`, `tapThrough`).

## iPhone-Web-App 1.0

- Neue Web-App fürs iPhone im Ordner `web/`, veröffentlicht über GitHub Pages: https://scarhand1988.github.io/kipp-start/
- Rechenlogik 1:1 aus der Android-App übertragen (`web/kipp-math.js`: MotionMath, TiltTracker, SoftFocus, Effects, DrawMath). Die Lage kommt aus dem Browser-Ereignis `deviceorientation` und wird in dieselbe Quaternion-Form wie der Android-Rotationsvektor umgerechnet.
- Effekte wie auf Android: Mitbewegen beim Kippen, Unschärfe-Verlauf zur Seite, die zum Betrachter kommt, Farben verblassen, „Unschärfe bleibt“, Animation beim Öffnen (statt beim Entsperren). Bildausschnitt mit Finger und zwei Fingern.
- `web/tests/check.mjs` vergleicht 2.320 Werte mit Referenzwerten, die der Workflow bei jedem Lauf frisch aus den Android-Klassen erzeugt (`web/tests/Golden.java`); der Weichzeichner stimmt Byte für Byte.
- Der Android-Bau (`apk.yml`) läuft nicht mehr bei Änderungen, die nur `web/`, README oder Änderungsverlauf betreffen.

## 0.14 – erste öffentliche Version

- Veröffentlichung als Open Source unter der MIT-Lizenz in diesem Repo.
- Neuer Signierschlüssel (RSA 4096, starkes Passwort, nur in den GitHub-Secrets). Versionen aus dem früheren privaten Projekt lassen sich deshalb nicht überinstallieren: einmal deinstallieren, dann neu installieren.
- Download heißt jetzt `Kipp-Start.apk`; jede Version erscheint automatisch unter Releases.

## Technischer Überblick (Stand 0.13)

Android-Live-Hintergrund für das eigene Foto. Wähle das bereits verwendete Motiv in der Android-Bildauswahl. Prüfe den Ausschnitt und die Bewegung in der Systemvorschau und wähle beim Anwenden den Startbildschirm.

Der Android-Dienst übernimmt nur die Hintergrundfläche. Die Bilddatei liegt nach der Auswahl lokal im privaten App-Speicher. Der Sensor wird nur bei sichtbarem Hintergrund abonniert. Die Oberfläche zeichnet mit dem Android-Frame-Takt auf eine Hardware-Canvas. Bei ruhigem Bild bleibt der letzte Frame stehen.

## Änderungen in 0.13 – Blättern mit Absatz

Beide Seitenwechsel im Referenzvideo (6,85–7,5 s und 21,4–22,0 s) Bild für Bild mit 60 Bildern/s vermessen; Daumen, alte und neue Seite und ein Baum im Hintergrund wurden verfolgt, die Handbewegung über das Dock herausgerechnet:

- **Absatz:** Der Daumen bewegt sich, die Seite bleibt völlig stehen (kein Gummiband), bis der Finger 15–18 % der Bildschirmbreite zurückgelegt hat. Kipp-Start rastet bei 16 % aus (`LauncherMath.DETENT`).
- **Von selbst weiter:** Danach hängt die Seite nicht mehr am Finger, sie wird schneller als der Daumen und rastet wie eine kritisch gedämpfte Feder ein (`LauncherMath.springProgress`, ω = 12,5/s, Start mit dem Fingertempo). Abgleich mit dem Video: 72 % des Wegs nach 0,2 s, 90 % nach 0,3 s, 97 % nach 0,4 s; Stillstand nach etwa 0,5 s.
- Der Hintergrund bleibt dabei stehen (im Video innerhalb der Messgenauigkeit unbewegt).
- Ein schneller Wisch unter dem Absatz blättert ebenfalls (im Video nicht zu sehen, übliches Verhalten).
- Schalter „Blättern mit Absatz“ im Startbildschirm-Menü (Standard an). Aus = die Seite folgt dem Finger wie in 0.12.

## Änderungen in 0.12 – eigener Startbildschirm „Kipp-Start“

Bild-für-Bild-Abgleich mit dem Referenzvideo (22 s, 60 Bilder/s): Der Effekt im Video ist ein Launcher-Effekt (Menü „Home screen settings“ mit „Tilt“, „Transition effects“, „Glow effect“). Beim Kippen verschwimmen und verdunkeln dort Hintergrund, Symbole, Widgets, Dock und sogar das Menü. Ein Live-Hintergrund allein kann das nicht, deshalb bringt die App jetzt einen eigenen Startbildschirm mit (`LauncherActivity`, Kategorie HOME). Der Pixel-Startbildschirm bleibt installiert und lässt sich jederzeit wieder wählen.

- **Kippen (Tilt):** Der Hintergrund meldet bei jedem Bild Kipp-Unschärfe, Abdunklung und Entsperr-Fortschritt (`TiltBus`, gleicher Prozess, gleicher Bildtakt). Kipp-Start legt Weichzeichner und Abdunklung (`RenderEffect`, je Stufe zwischengespeichert in `EffectCache`) über Symbole, Widgets, Dock und Menüs.
- **Übergangseffekte:** Beim Blättern gleitet die neue Seite herein, ist dabei durchscheinend, unscharf und leicht vergrößert und wird in der Mitte klar (`LauncherMath.pageAlpha/pageBlur/pageScale`). Der Hintergrund bleibt dabei scharf wie im Video.
- **Entsperr-Animation:** Die Symbole kommen 35 % größer, unscharf und unsichtbar herein und setzen sich im Takt der Hintergrund-Animation an ihren Platz.
- **Einstellungen wie im Video:** Zwei Finger zusammenziehen oder lange auf eine leere Stelle drücken. Schalter: Kippen, Übergangseffekte, Entsperr-Animation, Beschriftungen, Glanzrand; dazu Startseite festlegen, Widget hinzufügen, Symbole neu laden, Hintergrund-Einstellungen, anderen Startbildschirm wählen.
- **Startbildschirm-Grundfunktionen:** Seiten mit 4 × 6 Raster, Dock mit 4 Plätzen, „Suche“ über dem Dock, App-Übersicht mit Suche (nach oben wischen), Benachrichtigungen (nach unten wischen), Ziehen und Ablegen mit Tauschen, Ablagefelder „Entfernen“ und „App-Info“, am Rand verweilen blättert um und legt neue Seiten an, Widgets hinzufügen, verschieben und entfernen. Symbole als abgerundete Quadrate, optional mit Glanzrand. Aufbau in `home.json`.
- **Hintergrund an das Video angepasst:** „Unschärfe bleibt, solange geneigt“ ist jetzt Standard (im Video bleibt das Bild bis zu 2,5 s geneigt unscharf). „Hintergrund beim Seitenwechsel unscharf“ ist jetzt standardmäßig aus (im Video bleibt der Hintergrund beim Blättern scharf). Gilt nur, wenn die Schalter nie von Hand umgestellt wurden.
- Neue Prüfungen in `checks/LauncherCheck`.

Nicht nachgebaut (Systemfunktionen des Geräts im Video): Sperrbildschirm-Uhr hinter dem Berg, Kontrollzentrum. Ordner („Liquid folder“) folgen in einer späteren Etappe.

## Änderungen in 0.11

Flüssiger, schneller, sicherer – ohne neue Effekte:

- **Zeichnen nur noch im Bildtakt:** Kontrollzentrum (`onZoomChanged`), Seitenwechsel (`onOffsetsChanged`), Einstellungen, Entsperr-Animation und neu geladene Bilder zeichnen nicht mehr sofort, sondern höchstens einmal pro Bildtakt. Vorher konnte beim Runterziehen mehrmals pro Bild gezeichnet werden.
- **Unschärfe-Verlauf ohne neue Objekte pro Bild:** Zwei Schichten mit einem festen, einmal angelegten Verlauf statt zwei neuer Shader bei jedem Bild (`DrawMath.secondLayer`, geprüft in `checks/DrawCheck`). Gleiches Ergebnis, weniger Arbeit für die Speicherbereinigung.
- **Abdunklung als Farbfilter:** Statt einer eigenen Vollbild-Ebene wird das Bild direkt beim Zeichnen abgedunkelt (`LightingColorFilter`, je Stufe einmal angelegt). Ein Zeichendurchgang weniger, gleiche Farben (Abweichung höchstens 1–2 von 255).
- **Schneller laden:** Das Foto wird als JPEG (Qualität 95) statt PNG gespeichert und beim Start nur noch einmal dekodiert; Weichzeichner-Vorlage und Systemfarben werden daraus abgeleitet. Ein Foto aus 0.10 wird beim ersten Start einmalig umgewandelt. Neue Unschärfe-Stärken rechnen mit der gespeicherten Vorlage, ohne die Datei erneut zu lesen.
- **Speicher freigeben:** Ersetzte Bilder werden nach dem nächsten Zeichnen freigegeben, überholte Ladevorgänge sofort.
- **Ausschnitt mit dem Finger:** Im (größeren) Vorschaubild mit einem Finger verschieben, mit zwei Fingern zoomen. Die Regler bleiben zum Feinjustieren.
- **Messanzeige** (Schalter in der Einrichtung, Standard aus): zeigt auf dem Startbildschirm Bilder pro Sekunde beim Kippen und das Alter des Sensorwerts beim Zeichnen. Hinzu kommt die feste Glättung (Zeitkonstante 19 ms).
- **Bau:** Der Signierschlüssel liegt nicht mehr im Repo, sondern in den GitHub-Secrets `KIPP_KEYSTORE_B64` und `KIPP_KEYSTORE_PASSWORD` (derselbe Schlüssel, Updates installieren sich weiter über die alte Version). Der Bau prüft den Zertifikat-Fingerabdruck und bricht bei einem anderen Schlüssel ab.
- **Versionsnummer automatisch:** `versionCode` = 1000 + GitHub-Laufnummer, Versionsname nur noch in `gradle.properties` (`kippVersion`), auch für den Release-Namen.
- **Android-Prüfung (Lint) an:** läuft bei jedem Bau, Ergebnis in der Bau-Zusammenfassung und als Artefakt „Lint-Bericht“. Bricht den Bau noch nicht ab.

## Änderungen in 0.10

Nachgemessen an Standbildern des Referenzvideos:
- Farben verblassen beim Kippen (Schalter „Farben verblassen beim Kippen“, vorher „Milchglas“): Der Hintergrund tritt dunkel und farblos zurück, statt einen hellen Schleier zu bekommen. Im Video fallen die hellen Stellen von etwa 145 auf 50–63 (von 255), die dunklen bleiben bei 10–24, die Sättigung sinkt von 0,29 auf 0,11–0,22. Abdunklung bei voller Neigung 72 % in dunkles Grau. Dadurch heben sich die Symbole ab.
- Unschärfe-Verlauf umgedreht: Unscharf wird die Seite, die beim Kippen zum Betrachter hin kommt; die abgewandte Seite bleibt zu 70 % scharf.
- Glasscheibe aus 0.9 entfernt (samt Erlaubnis „Über anderen Apps einblenden“), weil Google Play Protect die Installation blockiert hat.

## Änderungen in 0.8

- Schalter „Unschärfe bleibt, solange geneigt“ (Standard aus): Das Bild bleibt unscharf, solange das Handy gegenüber der Haltung beim Entsperren geneigt ist, und wird beim Zurückkippen wieder scharf. Aus = wie bisher, nach etwa einer Sekunde wieder scharf. Eine Drehung des ganzen Körpers mit dem Handy zählt wie seitliches Kippen.
- Schalter „Unschärfe beim Seitenwechsel“ (Standard an): Beim Wischen zwischen Startbildschirm-Seiten wird das Bild im Takt des Fingers weich und etwas dunkler und ist auf jeder Seite wieder scharf. Das Bild bleibt stehen wie im Referenzvideo. Nutzt die Seitenangaben des Launchers (`onOffsetsChanged`); braucht mindestens zwei Seiten.

## Änderungen in 0.7

Effekte nach dem Referenzvideo, nur für das Hintergrundbild, je mit eigenem Schalter in der Einrichtung (`Effects`):

- **Milchglas beim Kippen:** Mit der Neigung wird das Bild zusätzlich dunkler und grauer (bis 40 %).
- **Entsperr-Animation:** Nach dem Entsperren startet das Bild 8 % vergrößert, 3 % tiefer, weich und 45 % dunkler und hebt sich in 450 ms in seine Ruhelage. Auslöser: Bildschirm war aus und der Hintergrund wird ohne Sperre sichtbar, oder `ACTION_USER_PRESENT`, wenn der Hintergrund auch auf dem Sperrbildschirm liegt.
- **Unschärfe bei Kontrollzentrum/Apps:** stufenlos mit dem Zoom-Wert des Systems (`onZoomChanged`), dazu bis 30 % dunkler.
- Der Schnellschalter „Kipp-Effekt“ schaltet alle Effekte zusammen aus.

## Änderungen in 0.6

- **Einstellbare Unschärfe** (Regler „Unschärfe beim Kippen“ in der Einrichtung, 0–100 %, Voreinstellung 70 %): Die Stärke bestimmt Weichzeichner-Radius (ca. 26 px bei 40 % bis ca. 48 px bei 100 %) und Deckkraft bei voller Neigung (bis 100 %). Live-Vorschau beim Verschieben. Ersetzt den Schalter „Weicher Fokus“ (0 % = aus, der alte Schalterstand wird übernommen).

## Änderungen in 0.5

- **Schnellschalter „Kipp-Effekt“** (`ToggleTileService`) in den Schnelleinstellungen und als Schalter in der App. Aus = Sensor abgemeldet, das Bild gleitet in die Mitte und steht still und scharf wie ein normaler Hintergrund. Die System-Vergrößerung (1,10 / 1,0) bleibt erhalten.

## Änderungen in 0.4

- **Weicher Fokus ohne Klötzchen** (`SoftFocus`): Die weiche Version wird in 1/4 Größe echt weichgezeichnet (drei Box-Durchgänge ≈ Gauß) statt nur auf 1/16 verkleinert. Vorher wurde ein Bildpunkt zu einem Block von etwa 17 × 17 Bildschirmpixeln.

## Änderungen in 0.3 (gegenüber 0.2)

- **Ruhestellung wird nachgeführt** (`TiltTracker`, Zeitkonstante 1,5 s): Nach einer Haltungsänderung, einer Körperdrehung oder Sensordrift kehrt das Bild in den gewählten Ausschnitt zurück.
- **Kein Sprung an der 90°-Grenze:** Weicht die Haltung mehr als 60° von der Ruhestellung ab, wird sie sofort neu gesetzt.
- **Weiche Rückkehr** beim Zurückkommen auf den Startbildschirm statt Sprung in die Mitte.
- **Gleiche Vergrößerung wie der normale Hintergrund:** In Ruhe `config_wallpaperMaxScale` des Systems (AOSP-Standard 1,10), beim Öffnen von Benachrichtigungen/Apps stufenlos 1,0 (`onZoomChanged`). Die Einrichtungsvorschau nutzt denselben Wert.
- **Kein Zoom beim Kippen:** Die Kipp-Reserve wird vom Bildrand abgezogen, beide Richtungen reichen gleich weit.
- **Bildrate:** Während der Bewegung wird die höchste Bildrate des Displays gewünscht, in Ruhe freigegeben.
- **Foto im Grafikspeicher** (`Bitmap.Config.HARDWARE`): kein Hochladen beim ersten Kippen.
- **Eigener Sensor-Thread:** Sensorwerte warten nicht hinter dem Zeichnen.

## Bauen

Wird automatisch über GitHub Actions gebaut (`.github/workflows/apk.yml`), inklusive `checks/MotionCheck`. Die APK liegt danach unter *Releases*. Lokal: Android Studio, Android SDK 35, Android Gradle Plugin 8.7.3, Gradle 8.9 und JDK 17, `gradle :app:assembleRelease`.

## Prüfstatus

`checks/MotionCheck` (900 Prüfungen) deckt auch den Sensorpfad, den weichen Fokus und die Effekte aus 0.7 bis 0.10 ab: Nachführen nach Haltungsänderung, Körperdrehung, 90°-Grenze, Reaktion auf 10° in 100 ms, kein Zoom beim Kippen. Gegen die Logik von 0.2 schlagen 5 dieser Prüfungen fehl. Messungen auf dem Pixel 7 (Verzögerung, Bildrate, Ausschnitt im echten Launcher) stehen aus; seit 0.11 mit der eingebauten Messanzeige möglich. `checks/DrawCheck` prüft die Rechenhilfen aus 0.11.
