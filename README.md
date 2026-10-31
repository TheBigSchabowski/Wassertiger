# Wassertiger

Intervall-Timer für Android, macOS und Linux – mit einer zweiten Seite zum Üben mit Drone und
Schlagzeug (siehe [Musik](#musik)). Eine **Session** ist eine frei konfigurierbare Liste von
Intervallen (z. B. 15 s, 15 s, 30 s, 30 s, 1:00, 48 s). Bei jedem Wechsel gibt es ein kurzes Signal –
**ohne Ton**, damit Spotify & Co. ungestört weiterlaufen.

Eine Session kann sich wiederholen – eine feste Anzahl **Runden** oder **endlos** bis Stopp. Vorlagen:

| Vorlage | Ablauf |
|---|---|
| Atemübung | 5 s Einatmen, 5 s Ausatmen × 30 Runden = 5 min |
| Pomodoro | 25 min Fokus, 5 min Pause × 4 Runden = 2 h |
| Alle 5 Sekunden | ein Signal alle 5 s, endlos |

Eigene Vorlagen: im Editor „Als Vorlage speichern“. Sie stehen dann unter „Meine Vorlagen“; „Neu daraus“
legt eine Session davon an, ein Tipp auf die Vorlage bearbeitet oder löscht sie.

| Plattform | Signal beim Wechsel |
|---|---|
| Android | Vibration + Display geht für ~2,5 s an (auch bei gesperrtem Handy) |
| macOS / Linux | Hinweisfenster über allen Fenstern, ohne den Fokus zu stehlen; Bildschirm bleibt während der Session an |

## Musik

Nach links wischen (am Computer **→**, zurück mit **←**, oder die Reiter oben) führt zur Musik-Seite.
Musik-Sessions sind dieselben Intervalle, nur dass jedes Intervall etwas spielt:

- **Drone** auf einem der 12 Grundtöne (oder aus), Klang Sinus / Orgel / Fläche / Tanpura, Quinte dazu
- **Drums** mit einem der 17 Grooves (Rock, Straight 16, Funk, Shuffle, Bossa, Walzer, 6/8 …) im
  gewählten Tempo (oder aus), Drumset Akustik / Synth / 808 / Besen / Cajón / Nur Klick
- **Was du spielst** („Phrygisch“, „16tel“) – steht beim Üben groß auf dem Bildschirm und im Hinweis
  beim Wechsel

Nur Drone, nur Drums, beides oder Stille geht. Dauer in Minuten („20“) oder m:ss. Runden, Endlos und
eigene Vorlagen funktionieren wie beim Timer. Vorlagen: A-Drone Phrygisch (20 min), Modi über A
(7 × 3 min, ohne Quinte), 16tel-Groove, Notenwerte (Achtel → 16tel → Triolen), Drone + Groove.

Drone und Drum-Machine stammen aus [Widder](https://github.com/TheBigSchabowski/widder) (dort aus
NicheLooper). Die Akustik-Samples (`shared/src/jvmCommonMain/resources/drums`) sind aus dem
Hydrogen-Drumkit „The Black Pearl 1.0“ von Glen MacArthur und stehen unter der GPL, siehe
`drums/ATTRIBUTION.txt`.

## Aufbau

- `shared` – Kotlin Multiplatform: Datenmodell, `TimerEngine`, `MusicEngine` (Drone + Drums), komplette
  Compose-Oberfläche; `jvmCommonMain` hat den Audio-Thread und die Drum-Samples
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
