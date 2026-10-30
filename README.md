# Intervall

Intervall-Timer für Android, macOS und Linux. Eine **Session** ist eine frei konfigurierbare Liste von
Intervallen (z. B. 15 s, 15 s, 30 s, 30 s, 1:00, 48 s). Bei jedem Wechsel gibt es ein kurzes Signal –
**ohne Ton**, damit Spotify & Co. ungestört weiterlaufen.

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

Das APK lokal bauen, damit es immer mit demselben Debug-Key signiert ist und sich über eine
installierte Version drüber installieren lässt.

## Android-Hinweise

- Beim ersten Start nach der Benachrichtigungs-Erlaubnis fragen lassen; fehlt die Erlaubnis für
  Vollbild-Hinweise (Android 14+), zeigt die App einen Knopf zu der Einstellung.
- Auf Samsung/Xiaomi die Akku-Optimierung für „Intervall“ ausschalten.
