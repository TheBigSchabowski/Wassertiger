# Wassertiger

Intervall-Timer für Android, macOS und Linux – mit einer zweiten Seite zum Üben mit Drone und
Schlagzeug (siehe [Musik](#musik)). Eine **Session** ist eine frei konfigurierbare Liste von
Intervallen (z. B. 15 s, 15 s, 30 s, 30 s, 1:00, 48 s). Bei jedem Wechsel gibt es ein kurzes Signal –
standardmäßig **ohne Ton**, damit Spotify & Co. ungestört weiterlaufen.

Eine Session kann sich wiederholen – eine feste Anzahl **Runden** oder **endlos** bis Stopp. Vorlagen:

| Vorlage | Ablauf |
|---|---|
| Atemübung | 5 s Einatmen, 5 s Ausatmen × 30 Runden = 5 min |
| Pomodoro | 25 min Fokus, 5 min Pause × 4 Runden = 2 h |
| Alle 5 Sekunden | ein Signal alle 5 s, endlos |

Intervalle tippt man in Sekunden. Da die Zahlentastatur keinen Doppelpunkt hat, trennt ein **Komma** die
Minuten ab (`5,` = 5:00, `5,30` = 5:30). Ein **Punkt** schaltet das Feld auf Minuten-Eingabe: Es wird geleert und
füllt sich von links als MM:SS (`0530` = 5:30, `05` allein = 5 Minuten); ein zweiter Punkt schaltet zurück.

**Session einfügen:** Im Editor unter „Intervall hinzufügen“ → „Session einfügen“ kommt eine ganze andere
Session (gleicher Art, mit allen Runden) als *ein* Intervall hinein – z. B. die Atemübung statt der
Pomodoro-Pause. Sie lässt sich wie jedes Intervall verschieben und löschen; beim Laufen zählt jedes ihrer
Intervalle einzeln.

**Parallel:** Während eine Session läuft, führt „+ Parallel starten“ zurück zur Liste; „Start“ lässt dort eine
weitere Session daneben laufen (oben steht, was gerade läuft, „Zur Anzeige“ geht zurück). Jede hat ihre
eigenen Knöpfe, auf Android ihre eigene Benachrichtigung. Musik spielt immer nur die älteste Musik-Session.

Eigene Vorlagen: im Editor „Als Vorlage speichern“. Sie stehen dann unter „Meine Vorlagen“; „Neu daraus“
legt eine Session davon an, ein Tipp auf die Vorlage bearbeitet oder löscht sie.

| Plattform | Signal beim Wechsel |
|---|---|
| Android | Vibration (Dauer pro Session: 1–8 s) + Display geht für ~2,5 s an (auch bei gesperrtem Handy) |
| macOS / Linux | Hinweisfenster über allen Fenstern, ohne den Fokus zu stehlen; Bildschirm bleibt während der Session an |

Pro Session einstellbar (Editor → „Signal beim Wechsel“):

- **Nur Licht** – Vibration „Aus – nur Licht“: kein Vibrieren, das Display leuchtet bei jedem Wechsel 5 s auf.
- **Leuchtfarbe** – Bernstein (Standard), Aquamarin, Weiß, Rot, Pink, Blau, Lila oder ein eigener `#hex`-Code;
  am Ende leuchtet es immer grün.
- **Ton** – Aus (Standard), *Am Ende* oder *Bei jedem Wechsel*; Klang Piep, Glocke, Gong oder Holzblock
  (Tippen spielt ihn vor). Am Ende kommen drei Töne aufsteigend. Der Ton mischt sich unter die Musik,
  ohne sie anzuhalten; auf Android gilt die Medienlautstärke.

Das Aufleuchten zählt die Restzeit des neuen Intervalls live herunter, ebenso der Hinweis oben, wenn das
Display schon an ist.

**Toggl Track:** Oben rechts „Toggl“ → API-Token eintragen (Toggl: Profil → API Token). Jede Session wählt im
Editor unter „Toggl“ eine von drei Möglichkeiten:

- **Nicht erfassen** – Standard; die Session benutzt Toggl gar nicht.
- **Immer gleich** – wird automatisch erfasst: ohne Angaben unter dem Session-Namen (z. B. „Dankbarkeit“), oder mit
  Projekt aus deiner Toggl-Liste und optionaler Beschreibung (z. B. nur „Musik“).
- **Vor dem Start fragen** – „Start“ fragt erst nach Projekt und Beschreibung (z. B. beim Pomodoro).

Erfasst wird die **ganze Session als ein Block** (ein Pomodoro = 2 Stunden). Wer nur einzelne Intervalle erfassen
will, schaltet „Nur diese Intervalle erfassen“ ein und wählt sie aus. **Pause-Knopf pausiert auch Toggl** ist
Standard; aus = Toggl zählt bei Pause einfach weiter.

Beim Laufen: *Immer gleich* → die Session zählt sofort **live** in Toggl mit. Bei *Vor dem Start fragen* ist
vorgeschlagen, was du für diese Session zuletzt gewählt hast; „Ohne Toggl starten“ lässt den Lauf weg. Vom Widget gestartet steht die Frage unter dem Countdown – gleich ausgefüllt zählt sie
ab Start mit, sonst trägst du am Ende ein. Per Tipp auf „● Toggl zählt mit“ lässt sich ein Lauf umbenennen oder
weglassen, „Doch erfassen“ holt ihn zurück.

Sessions von vor 1.9.0 behalten Toggl nur, wenn sie ein eigenes Projekt oder eine eigene Beschreibung hatten.

- **Pause** beendet den Eintrag, **Weiter** startet einen neuen (wenn so eingestellt).
- **Ende:** eine Benachrichtigung „✓ In Toggl gespeichert: … · Musik · 2:00:00“ – oder „Für Toggl noch eintragen“,
  wenn Beschreibung oder Projekt noch fehlen. Auf dem Fertig-Bildschirm: „Ändern“ und „Aus Toggl löschen“ / „Doch eintragen“.
- **Stopp vor dem Ende:** der Eintrag endet genau dort und bekommt den Tag *abgebrochen*; unter einer Minute
  gestoppt verschwindet er wieder.
- Toggl kennt nur einen laufenden Timer: Läuft dort schon einer – dein eigener oder eine parallele Session –,
  bleibt er unangetastet, und die Session wird eingetragen, sobald sie pausiert oder endet.
- Ohne Netz oder am Stundenlimit des kostenlosen Toggl-Plans holt die App das Eintragen von selbst nach
  (oder sofort mit „Nochmal“).

**Restzeit bei gesperrtem Handy (Android):** Während eine Session läuft, zeigt die Power-Taste statt des
Sperrbildschirms den großen Countdown mit Pause, Überspringen und Stopp. „Entsperren“ (oder Fingerabdruck)
führt wie gewohnt ins Handy; das Handy bleibt dabei gesperrt. Die Benachrichtigung mit der Restzeit steht
auch auf dem Sperrbildschirm.

## Widgets (Android)

Zwei Widgets für den Startbildschirm (lange auf den Startbildschirm tippen → Widgets → Wassertiger):

- **Wassertiger-Sessions** – alle Sessions (Timer und ♪ Musik) als Liste, ein Tipp startet sofort, ohne
  die App zu öffnen. Läuft eine Session, stehen oben Restzeit, Intervall sowie Pause/Weiter,
  Überspringen und Stopp.
- **Wassertiger-Session** – eine einzelne Session als Kachel; welche, wird beim Platzieren gewählt
  (lange drücken → bearbeiten wählt neu). Während sie läuft, zählt die Kachel herunter.

Änderungen in der App erscheinen sofort in den Widgets.

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
- `androidApp` – Foreground-Service, Vibration, Full-Screen-Intent zum Wecken des Displays, Widgets
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
