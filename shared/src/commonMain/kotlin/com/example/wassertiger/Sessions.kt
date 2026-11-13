package com.example.wassertiger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * One interval: after [seconds] the device signals the change. In a music session it also plays a
 * [drone] on that pitch class (0 = C) and/or the [groove] at [bpm]; null = that part is silent.
 * [name] is what to play over it ("Phrygisch", "16tel") and stands big on the screen.
 *
 * A [block] is a whole other session put in as one interval (the "Atemübung" in the Pomodoro break):
 * its intervals with every round written out, played one after the other. [seconds] is their sum and
 * [name] the session's name.
 *
 * [track]: counts in Toggl (off for the breaks of a Pomodoro).
 */
data class Step(
    val seconds: Int,
    val name: String = "",
    val drone: Int? = null,
    val groove: String? = null,
    val bpm: Int = 90,
    val block: List<Step> = emptyList(),
    val track: Boolean = true,
) {
    val isBlock: Boolean get() = block.isNotEmpty()

    /** "A-Drone · Straight 16 · 80 BPM", "Stille" for neither. */
    fun soundLabel(): String = listOfNotNull(
        drone?.let { "${NOTE_NAMES[it]}-Drone" },
        groove?.let { "$it · $bpm BPM" },
    ).joinToString(" · ").ifEmpty { "Stille" }
}

/** Marks a session that repeats its intervals until stopped. */
const val ENDLESS = 0

/**
 * One run ("Lauf"): an ordered list of intervals, played [rounds] times ([ENDLESS] = until stopped).
 * A [template] is a user-made preset: listed separately and only copied, never started itself.
 * A [music] session lives on the music page and sounds: its drone in [sound] (with the [fifth]),
 * its drums on [kit]. At every interval change the phone buzzes for [vibration] seconds (0 = never: only the
 * screen lights up, see [lightOnly]) and, if [tone] says so, sounds its [chime].
 */
data class Session(
    val id: String = newId(),
    val name: String,
    val steps: List<Step>,
    val rounds: Int = 1,
    val template: Boolean = false,
    val music: Boolean = false,
    val sound: DroneSound = DroneSound.ORGAN,
    val fifth: Boolean = true,
    val kit: DrumKit = DrumKit.ACOUSTIC,
    val vibration: Int = 1,
    val tone: ToneMode = ToneMode.OFF,
    val chime: Chime = Chime.BEEP,
    /** Colour of the flash at an interval change, 0xRRGGBB; the end always flashes green. */
    val flashColor: Int = AMBER_RGB,
    /** Counts in Toggl while it runs, if Toggl is connected (see [TogglTracker]); ticked per session. */
    val toggl: Boolean = false,
    /** What it is called in Toggl; blank = none with a project ("Musik" alone), else the session's name. */
    val togglDescription: String = "",
    /** Asks for the description at every run instead of using [togglDescription]. */
    val togglAskDescription: Boolean = false,
    /** Its Toggl project; null = none. */
    val togglProject: TogglProject? = null,
    /** Asks for the project at every run instead of using [togglProject]. */
    val togglAskProject: Boolean = false,
    /**
     * The pause button pauses Toggl too (ends the entry, Weiter starts a new one); off (the default):
     * Toggl counts on, so the whole session stays one block.
     */
    val togglPauseStops: Boolean = false,
    /**
     * Counts up until Stopp instead of down: its one endless interval is [signalEvery] long (see
     * [stopwatchSteps]) and only signals when that is set.
     */
    val stopwatch: Boolean = false,
    /** A stopwatch lights up / buzzes every this many seconds; 0 = never, a plain stopwatch. */
    val signalEvery: Int = 0,
    /** Put aside: out of the lists, widgets and "Session einfügen", but kept under "Archiv" to bring back. */
    val archived: Boolean = false,
) {
    /** Whether interval changes signal: a stopwatch without [signalEvery] never does. */
    val signals: Boolean get() = !stopwatch || signalEvery > 0

    /** Something to fill in at every run before (or after) it goes to Toggl. */
    val togglAsks: Boolean get() = togglAskDescription || togglAskProject

    /** The description a run goes to Toggl under, unless it asks. */
    val togglName: String get() = togglDescription.ifBlank { if (togglProject != null) "" else name }

    fun musicSettings(step: Step) = MusicSettings(
        root = step.drone ?: 9,
        sound = sound,
        fifth = fifth,
        kit = kit,
        bpm = step.bpm,
        groove = step.groove ?: Grooves.all.first().name,
    )

    val endless: Boolean get() = rounds == ENDLESS

    /** What a run plays: every [Step.block] replaced by its intervals. */
    fun expanded(): Session = if (steps.none { it.isBlock }) this else copy(
        // A block left out of Toggl leaves out all of its intervals.
        steps = steps.flatMap { b -> if (b.isBlock) b.block.map { if (b.track) it else it.copy(track = false) } else listOf(b) },
    )

    /** This session as one interval of another (see [Step.block]); endless ones go in with one round. */
    fun asBlock(): Step {
        val once = expanded().steps
        val all = List(if (endless) 1 else rounds) { once }.flatten()
        return Step(seconds = all.sumOf { it.seconds }, name = name, block = all)
    }
    /** A fresh session to edit and start, built from this one (a preset or template). */
    fun newSession(): Session = copy(id = newId(), template = false, archived = false)
    fun asTemplate(): Session = copy(id = newId(), template = true, archived = false)
    val roundSeconds: Int get() = steps.sumOf { it.seconds }
    /** Length of the whole run, null when [endless]. */
    val totalSeconds: Int? get() = if (endless) null else roundSeconds * rounds

    /** No vibration: the light is the whole signal, so it stays up longer (see [flashMillis]). */
    val lightOnly: Boolean get() = vibration == 0

    /** How long the screen lights up at a change. */
    val flashMillis: Long get() = if (lightOnly) 5000L else 2500L

    /** "vibriert es und das Display geht kurz an. Am Ende ein Gong." – what happens at a change. */
    fun signalText(): String {
        if (!signals) return "die Stoppuhr zählt still hoch."
        val light = if (lightOnly) "leuchtet nur das Display auf, ohne Vibration." else "vibriert es und das Display geht kurz an."
        val sound = when (tone) {
            ToneMode.OFF -> ""
            ToneMode.END -> " Am Ende ertönt ${chime.article()}."
            ToneMode.EVERY -> " Dazu ertönt ${chime.article()}."
        }
        return (if (stopwatch) "alle ${formatDuration(signalEvery)} " else "bei jedem Wechsel ") + light + sound
    }
}

private fun Chime.article(): String = when (this) {
    Chime.BEEP, Chime.GONG, Chime.WOOD -> "ein $label"
    Chime.BELL -> "eine $label"
}

const val AMBER_RGB = 0xFFB300

/** Flash colours offered in the editor next to a free #hex field. */
val FLASH_COLORS = listOf(
    "Bernstein" to AMBER_RGB, "Aquamarin" to 0x7FFFD4, "Weiß" to 0xFFFFFF,
    "Rot" to 0xFF3B30, "Pink" to 0xFF4FA3, "Blau" to 0x2F7BFF, "Lila" to 0x9B59FF,
)

/** "#7FFFD4", "7fffd4" or the short "#7FD" → 0x7FFFD4; null for anything else. */
fun parseHexColor(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    return full.toIntOrNull(16)
}

fun formatHexColor(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

/** Whether black text reads better than white on [rgb]. */
fun isLightColor(rgb: Int): Boolean {
    val r = rgb shr 16 and 0xFF
    val g = rgb shr 8 and 0xFF
    val b = rgb and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b > 140
}

/** The lengths the editor offers for [Session.vibration], in seconds; 0 = off. */
val VIBRATION_CHOICES = listOf(0, 1, 2, 3, 5, 8)

/** What happens a vibration is for: the run starts, the interval changes, the run is over. */
enum class Buzz { START, STEP, FINISH }

/**
 * Off/on waveform in ms (as Android's VibrationEffect takes it) for [buzz] when the session buzzes
 * for [seconds]: pulses rather than one long hum, since the start of each pulse is what you feel.
 * The end of the run buzzes a second longer in heavier pulses; the start is a short tap. Empty for 0.
 */
fun vibrationPattern(seconds: Int, buzz: Buzz): LongArray {
    if (seconds <= 0) return LongArray(0)
    val (on, off, total) = when (buzz) {
        Buzz.START -> return longArrayOf(0, 200)
        Buzz.STEP -> Triple(350L, 150L, seconds * 1000L)
        Buzz.FINISH -> Triple(600L, 200L, (seconds + 1) * 1000L)
    }
    val pattern = mutableListOf(0L)
    var t = 0L
    while (t < total) {
        if (pattern.size > 1) pattern += off
        pattern += on
        t += on + off
    }
    return pattern.toLongArray()
}

/** Ready-made sessions offered as templates; each call hands out fresh ids. */
fun presets(): List<Session> = listOf(
    Session(name = "Atemübung", steps = listOf(Step(5, "Einatmen"), Step(5, "Ausatmen")), rounds = 30),
    // What the Pomodoro was for is asked every time; it counts as a whole, breaks included.
    Session(
        name = "Pomodoro", steps = listOf(Step(25 * 60, "Fokus"), Step(5 * 60, "Pause")), rounds = 4,
        togglAskDescription = true, togglAskProject = true,
    ),
    Session(name = "Alle 5 Sekunden", steps = listOf(Step(5)), rounds = ENDLESS),
)

/** The music page's ready-made sessions; each call hands out fresh ids. */
fun musicPresets(): List<Session> = listOf(
    Session(name = "A-Drone Phrygisch", music = true, steps = listOf(Step(20 * 60, "Phrygisch", drone = 9))),
    // No fifth: Lokrisch has a diminished one.
    Session(
        name = "Modi über A", music = true, fifth = false,
        steps = listOf("Ionisch", "Dorisch", "Phrygisch", "Lydisch", "Mixolydisch", "Äolisch", "Lokrisch")
            .map { Step(3 * 60, it, drone = 9) },
    ),
    Session(name = "16tel-Groove", music = true, steps = listOf(Step(10 * 60, "16tel", groove = "Straight 16", bpm = 80))),
    Session(
        name = "Notenwerte", music = true,
        steps = listOf(
            Step(5 * 60, "Achtel", groove = "Rock", bpm = 80),
            Step(5 * 60, "16tel", groove = "Straight 16", bpm = 80),
            Step(5 * 60, "Triolen", groove = "Shuffle", bpm = 80),
        ),
    ),
    Session(name = "Drone + Groove", music = true, steps = listOf(Step(10 * 60, "Dorisch", drone = 2, groove = "Funk", bpm = 95))),
)

private fun newId(): String = Random.nextLong().toULong().toString(16)

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds / 60 % 60
    val s = (seconds % 60).toString().padStart(2, '0')
    return when {
        h > 0 -> "$h:${m.toString().padStart(2, '0')}:$s"
        m > 0 -> "$m:$s"
        else -> "$seconds s"
    }
}

/** A stopwatch's one interval: the signal's period, or a day when it never signals. */
fun stopwatchSteps(signalEvery: Int): List<Step> = listOf(Step(if (signalEvery > 0) signalEvery else STOPWATCH_QUIET_SECONDS))

private const val STOPWATCH_QUIET_SECONDS = 24 * 3600

/** A new stopwatch: counts up, no signal, Toggl off. */
fun newStopwatch(): Session = Session(name = "Stoppuhr", steps = stopwatchSteps(0), rounds = ENDLESS, stopwatch = true)

/** "2 Intervalle × 30 Runden · 5:00", for lists and summaries. */
fun Session.summary(): String {
    if (stopwatch) return "Stoppuhr" + if (signalEvery > 0) " · Signal alle ${formatDuration(signalEvery)}" else ""
    val intervals = if (steps.size == 1) "1 Intervall" else "${steps.size} Intervalle"
    return when {
        endless -> "$intervals · endlos wiederholt"
        rounds == 1 -> "$intervals · ${formatDuration(roundSeconds)}"
        else -> "$intervals × $rounds Runden · ${formatDuration(roundSeconds * rounds)}"
    }
}

/** Music intervals: a plain number counts minutes ("20"), anything with a colon as in [parseDuration]. */
fun parseMinutes(text: String): Int? =
    if (':' in text) parseDuration(text) else text.trim().toIntOrNull()?.takeIf { it > 0 }?.times(60)

/** How [parseMinutes] input shows a duration: "20" for whole minutes, else "m:ss". */
fun formatMinutes(seconds: Int): String = if (seconds % 60 == 0) (seconds / 60).toString() else formatDuration(seconds)

/**
 * Accepts "45", "1:30", "1:00:00" or "90s", and "5:" for five minutes (the seconds are still to be
 * typed); returns null for anything else or <= 0.
 */
fun parseDuration(text: String): Int? {
    val t = text.trim().removeSuffix("s").trim()
    val parts = t.split(":").let { p -> if (p.size > 1 && p.last().isEmpty()) p.dropLast(1) + "0" else p }
    val secs = when (parts.size) {
        1 -> parts[0].toIntOrNull()
        2 -> {
            val m = parts[0].toIntOrNull()
            val s = parts[1].toIntOrNull()
            if (m == null || s == null || s >= 60) null else m * 60 + s
        }
        3 -> {
            val h = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val s = parts[2].toIntOrNull()
            if (h == null || m == null || s == null || m >= 60 || s >= 60) null else h * 3600 + m * 60 + s
        }
        else -> null
    }
    return secs?.takeIf { it > 0 }
}

/** Where the serialized session list lives: SharedPreferences on Android, a file on desktop. */
interface SessionStorage {
    fun read(): String?
    fun write(json: String)

    /** Puts aside what [read] returned when it could not be read in full, before it is written over. */
    fun keepBroken(raw: String) {}
}

class SessionStore(private val storage: SessionStorage) {

    /**
     * The stored sessions. One that cannot be read is left out rather than taking the app down with
     * it, and a list that cannot be read at all comes back empty; either way what was stored is put
     * aside first (see [SessionStorage.keepBroken]), since the next save writes over it.
     */
    fun load(): List<Session> {
        val raw = storage.read() ?: return listOf(EXAMPLE).also(::save)
        val elements = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
        val sessions = elements.orEmpty().mapNotNull { runCatching { readSession(it) }.getOrNull() }
        if (elements == null || sessions.size < elements.size) storage.keepBroken(raw)
        return sessions
    }

    private fun readSession(element: JsonElement): Session {
        val o = element.jsonObject
        return Session(
            id = o.getValue("id").jsonPrimitive.content,
            name = o.getValue("name").jsonPrimitive.content,
            steps = o.getValue("steps").jsonArray.map(::readStep),
            // Sessions saved before rounds existed play once.
            rounds = o["rounds"]?.jsonPrimitive?.int ?: 1,
            template = o["template"]?.jsonPrimitive?.boolean ?: false,
            music = o["music"]?.jsonPrimitive?.boolean ?: false,
            sound = o["sound"]?.jsonPrimitive?.content.let { n -> DroneSound.entries.firstOrNull { it.name == n } } ?: DroneSound.ORGAN,
            fifth = o["fifth"]?.jsonPrimitive?.boolean ?: true,
            kit = o["kit"]?.jsonPrimitive?.content.let { n -> DrumKit.entries.firstOrNull { it.name == n } } ?: DrumKit.ACOUSTIC,
            vibration = o["vibration"]?.jsonPrimitive?.int ?: 1,
            // Sessions saved before 1.6.0 stay silent.
            tone = o["tone"]?.jsonPrimitive?.content.let { n -> ToneMode.entries.firstOrNull { it.name == n } } ?: ToneMode.OFF,
            chime = o["chime"]?.jsonPrimitive?.content.let { n -> Chime.entries.firstOrNull { it.name == n } } ?: Chime.BEEP,
            flashColor = o["flashColor"]?.jsonPrimitive?.content?.let(::parseHexColor) ?: AMBER_RGB,
            // Before 1.9.0 every session counted in Toggl ("toggl" was always true): only those
            // given a project or description of their own keep counting.
            toggl = o["togglOn"]?.jsonPrimitive?.boolean ?: (
                o["toggl"]?.jsonPrimitive?.boolean == true &&
                    (o["togglProject"] != null || !o["togglDescription"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
                ),
            togglDescription = o["togglDescription"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            togglAskDescription = o["togglAskDescription"]?.jsonPrimitive?.boolean ?: false,
            togglProject = o["togglProject"]?.let { readProject(it) },
            togglAskProject = o["togglAskProject"]?.jsonPrimitive?.boolean ?: false,
            // "togglPauseStops" (before 1.10.0) was true for nearly everyone as the old default: not carried over.
            togglPauseStops = o["togglPauseSplits"]?.jsonPrimitive?.boolean ?: false,
            stopwatch = o["stopwatch"]?.jsonPrimitive?.boolean ?: false,
            signalEvery = o["signalEvery"]?.jsonPrimitive?.intOrNull ?: 0,
            archived = o["archived"]?.jsonPrimitive?.boolean ?: false,
        ).also { require(it.steps.isNotEmpty() && it.steps.all { s -> s.seconds > 0 }) }
    }

    fun save(sessions: List<Session>) {
        val arr: JsonArray = buildJsonArray {
            sessions.forEach { session ->
                add(buildJsonObject {
                    put("id", session.id)
                    put("name", session.name)
                    put("rounds", session.rounds)
                    put("template", session.template)
                    put("music", session.music)
                    put("sound", session.sound.name)
                    put("fifth", session.fifth)
                    put("kit", session.kit.name)
                    put("vibration", session.vibration)
                    put("tone", session.tone.name)
                    put("chime", session.chime.name)
                    put("flashColor", formatHexColor(session.flashColor))
                    put("togglOn", session.toggl)
                    put("togglDescription", session.togglDescription)
                    put("togglAskDescription", session.togglAskDescription)
                    session.togglProject?.let { put("togglProject", writeProject(it)) }
                    put("togglAskProject", session.togglAskProject)
                    put("togglPauseSplits", session.togglPauseStops)
                    if (session.stopwatch) {
                        put("stopwatch", true)
                        put("signalEvery", session.signalEvery)
                    }
                    if (session.archived) put("archived", true)
                    put("steps", writeSteps(session.steps))
                })
            }
        }
        storage.write(arr.toString())
    }

    fun get(id: String): Session? = load().firstOrNull { it.id == id }

    private fun readStep(element: JsonElement): Step {
        val s = element.jsonObject
        return Step(
            seconds = s.getValue("seconds").jsonPrimitive.int,
            name = s["name"]?.jsonPrimitive?.content.orEmpty(),
            drone = s["drone"]?.jsonPrimitive?.intOrNull,
            groove = s["groove"]?.jsonPrimitive?.contentOrNull,
            bpm = s["bpm"]?.jsonPrimitive?.int ?: 90,
            block = s["block"]?.jsonArray?.map(::readStep).orEmpty(),
            track = s["track"]?.jsonPrimitive?.boolean ?: true,
        )
    }

    private fun writeSteps(steps: List<Step>): JsonArray = buildJsonArray {
        steps.forEach { step ->
            add(buildJsonObject {
                put("seconds", step.seconds)
                put("name", step.name)
                step.drone?.let { put("drone", it) }
                step.groove?.let { put("groove", it) }
                put("bpm", step.bpm)
                if (step.isBlock) put("block", writeSteps(step.block))
                if (!step.track) put("track", false)
            })
        }
    }

    private companion object {
        val EXAMPLE = Session(
            name = "Beispiel",
            steps = listOf(15, 15, 30, 30, 60, 48).map { Step(it) },
        )
    }
}

fun writeProject(p: TogglProject): JsonObject = buildJsonObject {
    put("id", p.id)
    put("name", p.name)
    put("workspace", p.workspaceId)
}

fun readProject(element: JsonElement): TogglProject? {
    val p = element as? JsonObject ?: return null
    return TogglProject(
        id = p["id"]?.jsonPrimitive?.longOrNull ?: return null,
        name = p["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        workspaceId = p["workspace"]?.jsonPrimitive?.longOrNull ?: return null,
    )
}
