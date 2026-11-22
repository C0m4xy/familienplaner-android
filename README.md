# Familienplaner für Android-Tablets

Native Android-App für den Familienplaner – das Gegenstück zu den Apps in `../Familienplaner-macOS`
und `../Familienplaner-iOS`. Läuft ab **Android 6.0**, ausgelegt für Tablets (quer und hoch).

### Wichtig bei älteren Tablets (Android 6/7)
Die App zeigt den Planer mit dem eingebauten Browser-Baustein **„Android System WebView“**. Auf alten Geräten
ist der oft seit Jahren nicht aktualisiert (Android 6 wurde mit Version 44 ausgeliefert). Dann:
**Play Store → „Android System WebView“ aktualisieren** (unter Android 7–9 stattdessen „Chrome“), danach App neu starten.
- unter Version 80 bleibt der Planer leer → die App warnt beim Start und bietet den Play-Store-Link an
- Android 6 bekommt höchstens Version 106 → der Planer läuft, einige Farbtöne (CSS `color-mix`, erst ab 111) fehlen
- die installierte Version steht im **Info**-Dialog

## Server wählen
| Auswahl | Was passiert |
|---|---|
| **Cloud-Server** | Öffnet `https://famplan.comaxy.de`. Keine Cloudflare-Logik – die App zeigt direkt die Anmeldung der Webseite. |
| **Eigener Server → Cloudflare Access** | Adresse + E-Mail-Adresse → **Code anfordern**. Die App meldet sich im Hintergrund bei Cloudflare Access an und schickt den Code aus der E-Mail weiter (sechs Ziffern → automatische Anmeldung). |
| **Eigener Server → Lokal** | Server (z. B. `192.168.1.20` oder `http://nas.local`) + Port (z. B. `3000`) → **Verbinden**. Ohne Angabe wird `http://` verwendet. |

Klappt die automatische Cloudflare-Anmeldung einmal nicht (z. B. Captcha), öffnet **„Anmeldeseite anzeigen“**
die Cloudflare-Seite direkt in der App. Läuft die Anmeldung ab, erscheint wieder die Anmeldemaske.

Der **ⓘ-Knopf** (Anmeldemaske oben rechts bzw. ⋯ → Info) zeigt App-Version und verwendete Adresse.
Ist der Server nicht erreichbar, erscheint eine eigene Fehlerseite mit „Erneut versuchen“ und „Verbindung ändern“.

## Bedienung im Planer
- **⋯-Knopf** unten rechts → Zurück, Neu laden, Im Browser öffnen, **Bildschirm anlassen**, **Vollbild**, Info, Abmelden
  („Bildschirm anlassen“ + „Vollbild“ eignen sich für ein Tablet an der Wand.)
- **Zurück-Taste/-Geste** → zurück im Planer
- Datensicherung (Download) → Android fragt, wo die Datei gespeichert werden soll
- `webcal:`-, `mailto:`- und fremde Links öffnen die passende App bzw. den Browser
- Abmelden löscht Cookies und gespeicherte Daten der Webseite in der App

## Bauen
Benötigt JDK 21 und das Android SDK (per Homebrew: `openjdk@21`, `android-commandlinetools`) – kein Android Studio nötig.

```bash
./build-apk.sh
```

Ergebnis: `build/Familienplaner.apk`. Beim ersten Lauf wird ein Signierschlüssel in `signing/` angelegt –
**aufheben**, sonst lassen sich spätere Versionen nicht über die installierte App installieren.
Alternativ lässt sich der Ordner direkt in Android Studio öffnen.

## Automatische Updates
Die App sucht beim Start (und danach alle 12 Stunden) im öffentlichen Repository
[C0m4xy/familienplaner-android](https://github.com/C0m4xy/familienplaner-android/releases) nach einer neueren Version.
Gibt es eine, bietet sie das Update an, lädt die APK, prüft die SHA-256-Prüfsumme aus `update.json` und installiert sie.
Beim ersten Mal fragt Android nach der Erlaubnis „Aus dieser Quelle zulassen“; ab Android 12 laufen spätere Updates
ohne weitere Rückfrage. Manuell: **ⓘ Info → Nach Updates suchen**.

Neue Version veröffentlichen:
1. In `app/build.gradle.kts` `versionCode` erhöhen und `versionName` anpassen, Änderungen committen
2. `./release.sh "Was ist neu"` – baut, signiert, erzeugt `update.json` und legt die GitHub-Release an

Wichtig: Updates müssen mit **demselben Signierschlüssel** (`signing/`, nicht im Repository) gebaut werden.

## Auf das Tablet bringen
- **Per Kabel:** Entwickleroptionen + USB-Debugging am Tablet einschalten, dann
  `adb install -r build/Familienplaner.apk`
- **Ohne Kabel:** APK aufs Tablet kopieren (z. B. per Mail oder Cloud-Speicher), antippen und
  „Installation aus unbekannten Quellen“ für diese App erlauben.

## Aufbau
| Datei | Inhalt |
|---|---|
| `app/src/main/java/…/MainActivity.kt` | Ablauf (wie `AppModel` der macOS-App), Anmeldemaske, Menü, Vollbild |
| `app/src/main/java/…/ServerConfig.kt` | Cloud / Cloudflare Access / Lokal, Adresse + Port |
| `app/src/main/java/…/AccessLogin.kt` | Anmeldung bei Cloudflare Access im verdeckten Browser (wie macOS) |
| `app/src/main/java/…/PlannerBrowser.kt` | Anzeige des Planers: Links, Datei-Auswahl, Downloads, Brücke zur Seite |
| `app/src/main/java/…/PushService.kt` | Hintergrund-Verbindung für Mitteilungen |
| `app/src/main/java/…/Push.kt` | Geräte-Kennung, Mitteilungs-Kanäle, Anzeige |
| `app/src/main/java/…/BootReceiver.kt` | Start nach Geräte-Neustart/App-Update |
| `app/src/main/java/…/Updater.kt` | Automatische Updates aus den GitHub-Releases |
| `app/src/main/res/layout/activity_main.xml` | Oberfläche |

## Mitteilungen (ab App-Version 1.2)
Der eingebettete Browser kann kein Web-Push. Die App empfängt Mitteilungen deshalb selbst – ohne Google/Firebase:
1. Im Planer unter **Einstellungen → 🔔 Mitteilungen aufs Handy → Aktivieren** tippen
2. Android fragt nach der Erlaubnis für Mitteilungen (ab Android 13) → **Zulassen**
3. Die App bietet an, das **Akku-Sparen** für sie auszuschalten – auf Handys empfohlen, auf einem Tablet am Ladegerät nicht nötig

Danach hält die App im Hintergrund eine Verbindung zum Planer (`/api/push-stream`) und zeigt dazu dauerhaft die kleine
Mitteilung „Familienplaner verbunden“ (in den Android-Einstellungen unter Kanal „Verbindung zum Planer“ ausblendbar).
Sie startet nach einem Neustart des Geräts und nach App-Updates automatisch. Ist das Gerät kurz offline, liefert der Server
Mitteilungen der letzten Stunde beim nächsten Verbinden nach. Antippen öffnet den Planer (Türklingel: direkt das Kamerabild).
Abmelden in der App oder „Ausschalten“ im Planer beendet die Verbindung. Voraussetzung: Planer-Server ab Version 2026-11-22-19-25.

## Einschränkungen
- **Passkeys** der Cloud-Anmeldung werden im eingebetteten Browser nicht angeboten –
  dort mit Passwort + Authenticator-Code (TOTP) anmelden.
