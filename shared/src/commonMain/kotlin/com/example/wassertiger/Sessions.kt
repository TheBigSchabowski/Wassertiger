package com.example.wassertiger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** One interval: after [seconds] the device signals the change. */
data class Step(val seconds: Int, val name: String = "")

/** Marks a session that repeats its intervals until stopped. */
const val ENDLESS = 0

/**
 * One run ("Lauf"): an ordered list of intervals, played [rounds] times ([ENDLESS] = until stopped).
 * A [template] is a user-made preset: listed separately and only copied, never started itself.
 */
data class Session(
    val id: String = newId(),
    val name: String,
    val steps: List<Step>,
    val rounds: Int = 1,
    val template: Boolean = false,
) {
    val endless: Boolean get() = rounds == ENDLESS
    /** A fresh session to edit and start, built from this one (a preset or template). */
    fun newSession(): Session = copy(id = newId(), template = false)
    fun asTemplate(): Session = copy(id = newId(), template = true)
    val roundSeconds: Int get() = steps.sumOf { it.seconds }
    /** Length of the whole run, null when [endless]. */
    val totalSeconds: Int? get() = if (endless) null else roundSeconds * rounds
}

/** Ready-made sessions offered as templates; each call hands out fresh ids. */
fun presets(): List<Session> = listOf(
    Session(name = "Atemübung", steps = listOf(Step(5, "Einatmen"), Step(5, "Ausatmen")), rounds = 30),
    Session(name = "Pomodoro", steps = listOf(Step(25 * 60, "Fokus"), Step(5 * 60, "Pause")), rounds = 4),
    Session(name = "Alle 5 Sekunden", steps = listOf(Step(5)), rounds = ENDLESS),
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

/** "2 Intervalle × 30 Runden · 5:00", for lists and summaries. */
fun Session.summary(): String {
    val intervals = if (steps.size == 1) "1 Intervall" else "${steps.size} Intervalle"
    return when {
        endless -> "$intervals · endlos wiederholt"
        rounds == 1 -> "$intervals · ${formatDuration(roundSeconds)}"
        else -> "$intervals × $rounds Runden · ${formatDuration(roundSeconds * rounds)}"
    }
}

/** Accepts "45", "1:30" or "90s"; returns null for anything else or <= 0. */
fun parseDuration(text: String): Int? {
    val t = text.trim().removeSuffix("s").trim()
    val parts = t.split(":")
    val secs = when (parts.size) {
        1 -> parts[0].toIntOrNull()
        2 -> {
            val m = parts[0].toIntOrNull()
            val s = parts[1].toIntOrNull()
            if (m == null || s == null || s >= 60) null else m * 60 + s
        }
        else -> null
    }
    return secs?.takeIf { it > 0 }
}

/** Where the serialized session list lives: SharedPreferences on Android, a file on desktop. */
interface SessionStorage {
    fun read(): String?
    fun write(json: String)
}

class SessionStore(private val storage: SessionStorage) {

    fun load(): List<Session> {
        val raw = storage.read() ?: return listOf(EXAMPLE).also(::save)
        return Json.parseToJsonElement(raw).jsonArray.map { element ->
            val o = element.jsonObject
            Session(
                id = o.getValue("id").jsonPrimitive.content,
                name = o.getValue("name").jsonPrimitive.content,
                steps = o.getValue("steps").jsonArray.map {
                    val s = it.jsonObject
                    Step(s.getValue("seconds").jsonPrimitive.int, s["name"]?.jsonPrimitive?.content.orEmpty())
                },
                // Sessions saved before rounds existed play once.
                rounds = o["rounds"]?.jsonPrimitive?.int ?: 1,
                template = o["template"]?.jsonPrimitive?.boolean ?: false,
            )
        }
    }

    fun save(sessions: List<Session>) {
        val arr: JsonArray = buildJsonArray {
            sessions.forEach { session ->
                add(buildJsonObject {
                    put("id", session.id)
                    put("name", session.name)
                    put("rounds", session.rounds)
                    put("template", session.template)
                    put("steps", buildJsonArray {
                        session.steps.forEach { step ->
                            add(buildJsonObject {
                                put("seconds", step.seconds)
                                put("name", step.name)
                            })
                        }
                    })
                })
            }
        }
        storage.write(arr.toString())
    }

    fun get(id: String): Session? = load().firstOrNull { it.id == id }

    private companion object {
        val EXAMPLE = Session(
            name = "Beispiel",
            steps = listOf(15, 15, 30, 30, 60, 48).map { Step(it) },
        )
    }
}
