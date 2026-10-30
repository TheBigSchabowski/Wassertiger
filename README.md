# Wassertiger

Intervall-Timer für Android, macOS und Linux. Eine **Session** ist eine frei konfigurierbare Liste von
Intervallen (z. B. 15 s, 15 s, 30 s, 30 s, 1:00, 48 s). Bei jedem Wechsel gibt es ein kurzes Signal –
**ohne Ton**, damit Spotify & Co. ungestört weiterlaufen.

Eine Session kann sich wiederholen – eine feste Anzahl **Runden** oder **endlos** bis Stopp. Vorlagen:

| Vorlage | Ablauf |
|---|---|
| Atemübung | 5 s Einatmen, 5 s Ausatmen × 30 Runden = 5 min |
| Pomodoro | 25 min Fokus, 5 min Pause × 4 Runden = 2 h |
| Alle 5 Sekunden | ein Signal alle 5 s, endlos |

| Plattform | Signal beim Wechsel |
|---|---|
| Android | Vibration + Display geht für ~2,5 s an (auch bei gesperrtem Handy) |
| macOS / Linux | Hinweisfenster über allen Fenstern, ohne den Fokus zu stehlen; Bildschirm bleibt während der Session an |

## Aufbau

- `shared` – Kotlin Multiplatform: Datenmodell, `TimerEngine`, komplette Compose-Oberfläche
- `androidApp` – Foreground-Service, Vibration, Full-Screen-Intent zum Wecken des Displays
- `desktopApp` – Compose Desktop für macOS und Linux

## Bauen

Gradle braucht ein JDK 17–21 als Laufzeit, z. B. das von Android Studio:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :androidApp:assembleDebug   # androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :desktopApp:run             # Desktop-App direkt starten
./gradlew :desktopApp:packageDmg      # auf dem Mac
./gradlew :desktopApp:packageDeb      # auf Linux (macht auch die GitHub Action bei jedem Push)
./gradlew :shared:desktopTest         # Tests
```

Fertige Pakete (APK, .deb, Linux-tar.gz) hängen an den GitHub-Releases. Das Release-APK baut
`./gradlew :androidApp:assembleRelease` und signiert es mit `~/.android/wassertiger-release.jks`
(+ `.pass`); fehlt der Schlüssel, bleibt das APK unsigniert. Updates lassen sich nur mit demselben
Schlüssel über eine installierte Version spielen.

`./gradlew :androidApp:testDebugUnitTest` startet die App ohne Gerät (Robolectric) und spielt die
Beispiel-Session an.

## Android-Hinweise

- Beim ersten Start nach der Benachrichtigungs-Erlaubnis fragen lassen; fehlt die Erlaubnis für
  Vollbild-Hinweise (Android 14+), zeigt die App einen Knopf zu der Einstellung.
- Auf Samsung/Xiaomi die Akku-Optimierung für „Wassertiger“ ausschalten.
