package com.example.wassertiger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [TogglTracker] against a stand-in for Toggl's API that keeps entries the way Toggl does. */
class TogglTest {

    private class Entry(val id: Long, var description: String, var project: Long?, val start: String, var stop: String?, var tags: List<String>)

    /** One running timer at most: starting one stops the other, as in Toggl. */
    private class FakeToggl {
        val entries = mutableMapOf<Long, Entry>()
        val calls = mutableListOf<String>()
        var offline = false
        /** Toggl's hourly limit of the free plan, which hits the account (/me) endpoints. */
        var meLimited = false
        private var nextId = 100L

        val running: Entry? get() = entries.values.firstOrNull { it.stop == null }

        val http: Http = { method, url, _, _, body ->
            if (offline) throw java.io.IOException("offline")
            val path = url.substringAfter("/api/v9/")
            calls += "$method $path"
            val o = body?.let { Json.parseToJsonElement(it).jsonObject }
            when {
                meLimited && path.startsWith("me") ->
                    HttpResult(402, "You have hit your hourly limit for API calls. Your quota will reset in 1190 seconds.")
                path == "me" -> HttpResult(200, """{"fullname":"Test","default_workspace_id":7}""")
                path == "me/time_entries/current" -> HttpResult(200, running?.let { """{"id":${it.id}}""" } ?: "null")
                // Like Toggl: start + duration must land exactly on stop.
                method == "POST" && o!!["stop"] != null && java.time.Instant.parse(o.getValue("start").jsonPrimitive.content)
                    .plusSeconds(o.getValue("duration").jsonPrimitive.long) != java.time.Instant.parse(o.getValue("stop").jsonPrimitive.content) ->
                    HttpResult(400, "\"Stop and duration mismatch\"")
                method == "POST" -> {
                    val stop = o!!["stop"]?.jsonPrimitive?.content
                    if (stop == null) running?.stop = "auto"
                    val e = Entry(
                        nextId++, o.getValue("description").jsonPrimitive.content, o["project_id"]?.jsonPrimitive?.long,
                        o.getValue("start").jsonPrimitive.content, stop,
                        o["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    )
                    entries[e.id] = e
                    HttpResult(200, """{"id":${e.id}}""")
                }
                method == "PUT" -> {
                    val e = entries.getValue(path.substringAfterLast('/').toLong())
                    o!!["stop"]?.let { e.stop = it.jsonPrimitive.content }
                    o["description"]?.let { e.description = it.jsonPrimitive.content }
                    if ("project_id" in o) e.project = (o["project_id"] as? JsonPrimitive)?.content?.toLongOrNull()
                    o["tags"]?.let { t -> e.tags = t.jsonArray.map { it.jsonPrimitive.content } }
                    HttpResult(200, "{}")
                }
                method == "DELETE" -> HttpResult(if (entries.remove(path.substringAfterLast('/').toLong()) != null) 200 else 404, "")
                else -> HttpResult(404, "")
            }
        }
    }

    private val server = FakeToggl()
    private var saved: String? = null
    private val settings = SettingsStore(object : SessionStorage {
        override fun read() = saved
        override fun write(json: String) { saved = json }
    }).apply { togglToken = "token" }

    private val pomodoro = presets()[1]

    /** Started [minutesAgo] and some odd milliseconds, as real runs are. */
    private fun run(id: Long, minutesAgo: Int, session: Session = pomodoro) =
        RunState(session, index = 0, stepEndAt = monotonicMillis() + 60_000, id = id, startedAt = wallClockMillis() - minutesAgo * 60_000L - 737)

    private fun tracker(scope: kotlinx.coroutines.CoroutineScope) = TogglTracker(settings, scope) { Toggl(it, server.http) }

    @Test
    fun countsLiveAndPausesEndTheEntry() = runBlocking {
        val toggl = tracker(this)
        val r = run(1, minutesAgo = 10)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(1).live)
        assertEquals("Pomodoro", server.running!!.description, "counts under the session name")

        toggl.onEvent(TimerEvent.PauseChanged, r.copy(pausedRemaining = 1000, pausedAt = monotonicMillis()))
        toggl.idle()
        assertNull(server.running, "pause stops the entry")
        toggl.onEvent(TimerEvent.PauseChanged, r)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()

        val t = toggl.runs.value.getValue(1)
        assertTrue(t.synced && !t.live && !t.aborted)
        assertEquals(2, server.entries.size, "one entry per stretch between pauses")
        assertTrue(server.entries.values.all { it.stop != null && it.tags.isEmpty() })
    }

    @Test
    fun stopBeforeTheEndIsAborted() = runBlocking {
        val toggl = tracker(this)
        val r = run(2, minutesAgo = 12)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        val end = wallClockMillis()
        toggl.onEvent(TimerEvent.Stopped, r.copy(finished = true, stopped = true, endedAt = end))
        toggl.idle()
        val e = server.entries.values.single()
        assertEquals(isoInstant(end), e.stop, "ends where Stopp was pressed, not at the planned end")
        assertEquals(listOf(TOGGL_ABORTED_TAG), e.tags)
        assertTrue(toggl.runs.value.getValue(2).let { it.aborted && it.synced })
    }

    @Test
    fun stoppedWithinAMinuteLeavesNoTrace() = runBlocking {
        val toggl = tracker(this)
        val r = run(3, minutesAgo = 0)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        assertEquals(1, server.entries.size)
        toggl.onEvent(TimerEvent.Stopped, r)
        toggl.idle()
        assertTrue(server.entries.isEmpty())
    }

    @Test
    fun ownTimerAndParallelRunsAreLeftAlone() = runBlocking {
        server.entries[1] = Entry(1, "Eigener Timer", null, "2026-11-06T08:00:00Z", null, emptyList())
        val toggl = tracker(this)
        val a = run(4, minutesAgo = 5)
        toggl.onEvent(TimerEvent.Started, a)
        toggl.idle()
        assertEquals("Eigener Timer", server.running!!.description, "never stops the user's own timer")
        assertFalse(toggl.runs.value.getValue(4).live)
        toggl.onEvent(TimerEvent.Finished, a.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals("Eigener Timer", server.running!!.description)
        assertEquals(2, server.entries.size, "booked when it ended")
        assertTrue(toggl.runs.value.getValue(4).synced)
    }

    @Test
    fun editRenamesAndIsRemembered() = runBlocking {
        val toggl = tracker(this)
        val r = run(5, minutesAgo = 3)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        val uni = TogglProject(9, "Studium", 7)
        toggl.edit(5, "Bachelorarbeit", uni)
        toggl.idle()
        assertEquals("Bachelorarbeit", server.running!!.description)
        assertEquals(9L, server.running!!.project)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()

        // The next Pomodoro counts under that name from the start.
        toggl.onEvent(TimerEvent.Started, run(6, minutesAgo = 0))
        toggl.idle()
        assertEquals("Bachelorarbeit", server.running!!.description)
        assertEquals(9L, server.running!!.project)

        toggl.edit(6, "Bachelorarbeit", null)
        toggl.idle()
        assertNull(server.running!!.project, "the project can be taken off again")
        toggl.delete(6)
        toggl.idle()
        assertNull(server.running)
    }

    @Test
    fun offlineCatchesUpOnRetry() = runBlocking {
        val toggl = tracker(this)
        server.offline = true
        val r = run(7, minutesAgo = 20)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals("Keine Verbindung zu Toggl", toggl.runs.value.getValue(7).error)
        server.offline = false
        toggl.retry(7)
        toggl.idle()
        val t = toggl.runs.value.getValue(7)
        assertTrue(t.synced && t.error == null)
        assertEquals(1, server.entries.size)
        assertTrue(server.entries.values.single().stop != null)
    }

    @Test
    fun sessionsCanOptOut() = runBlocking {
        val toggl = tracker(this)
        toggl.onEvent(TimerEvent.Started, run(8, minutesAgo = 0, session = presets()[0].copy(toggl = false)))
        toggl.idle()
        assertTrue(toggl.runs.value.isEmpty() && server.calls.isEmpty())
        settings.togglToken = null
        toggl.onEvent(TimerEvent.Started, run(9, minutesAgo = 0))
        toggl.idle()
        assertTrue(toggl.runs.value.isEmpty(), "nothing without a token")
    }

    @Test
    fun bookAfterwards() = runBlocking {
        val toggl = tracker(this)
        val r = run(10, minutesAgo = 30).copy(finished = true, endedAt = wallClockMillis())
        toggl.book(r, "Nachgetragen", null)
        toggl.idle()
        val e = server.entries.values.single()
        assertEquals("Nachgetragen", e.description)
        assertTrue(e.stop != null)
    }

    @Test
    fun hourlyLimitStillBooksTheRun() = runBlocking {
        settings.togglWorkspace = 7
        server.meLimited = true
        val toggl = tracker(this)
        val r = run(11, minutesAgo = 25)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        val t = toggl.runs.value.getValue(11)
        assertFalse(t.live, "cannot tell whether a timer runs: not live")
        assertNull(t.error)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(11).synced)
        assertTrue(server.entries.values.single().stop != null, "booked at the end through the workspace endpoint")
        assertTrue(server.calls.none { it == "GET me" }, "the workspace is kept, not asked for")
    }

    @Test
    fun hourlyLimitSaysWhenItGoesOnAgain() = runBlocking {
        server.meLimited = true
        val e = runCatching { Toggl("token", server.http).projects() }.exceptionOrNull() as TogglException
        assertEquals("Toggl-Stundenlimit (kostenloser Plan) erreicht – geht in ca. 20 min wieder", e.message)
        assertEquals(1_195_000L, e.retryAfterMs)
    }
}
