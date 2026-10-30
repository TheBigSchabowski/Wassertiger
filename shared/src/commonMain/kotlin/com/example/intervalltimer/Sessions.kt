package com.example.intervalltimer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** One interval: after [seconds] the device signals the change. */
data class Step(val seconds: Int, val name: String = "")

/** One run ("Lauf"): an ordered list of intervals. */
data class Session(
    val id: String = newId(),
    val name: String,
    val steps: List<Step>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

private fun newId(): String = Random.nextLong().toULong().toString(16)

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m == 0) "$s s" else "$m:${s.toString().padStart(2, '0')}"
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
            )
        }
    }

    fun save(sessions: List<Session>) {
        val arr: JsonArray = buildJsonArray {
            sessions.forEach { session ->
                add(buildJsonObject {
                    put("id", session.id)
                    put("name", session.name)
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
