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

    /** Fixed name, every interval counts. */
    private val pomodoro = Session(name = "Pomodoro", steps = listOf(Step(25 * 60, "Fokus"), Step(5 * 60, "Pause")), rounds = 4, toggl = true, togglPauseStops = true)

    /** Preset [n] with Toggl ticked (off by default). */
    private fun preset(n: Int) = presets()[n].copy(toggl = true)

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
    fun editRenamesWhileItRuns() = runBlocking {
        val toggl = tracker(this)
        val r = run(5, minutesAgo = 3)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        val uni = TogglProject(9, "Studium", 7)
        toggl.edit(5, "Bachelorarbeit", uni)
        toggl.idle()
        assertEquals("Bachelorarbeit", server.running!!.description)
        assertEquals(9L, server.running!!.project)
        toggl.edit(5, "Bachelorarbeit", null)
        toggl.idle()
        assertNull(server.running!!.project, "the project can be taken off again")
        toggl.delete(5)
        toggl.idle()
        assertNull(server.running)

        // A session with fixed settings keeps them; the rename was for that run only.
        toggl.onEvent(TimerEvent.Started, run(6, minutesAgo = 0))
        toggl.idle()
        assertEquals("Pomodoro", server.running!!.description)
    }

    @Test
    fun sessionSettingsDecideNameAndProject() = runBlocking {
        val musik = TogglProject(3, "Musik", 7)
        val toggl = tracker(this)
        val breathing = preset(0).copy(togglProject = musik, togglDescription = "Atmen")
        toggl.onEvent(TimerEvent.Started, run(12, minutesAgo = 0, session = breathing))
        toggl.idle()
        assertEquals("Atmen", server.running!!.description)
        assertEquals(3L, server.running!!.project)
    }

    @Test
    fun breaksLeftOutOnRequest() = runBlocking {
        val toggl = tracker(this)
        val preset = preset(1)
        assertTrue(preset.steps.all { it.track }, "by default the whole Pomodoro counts, breaks included")
        val session = preset.copy(
            togglAskDescription = false, togglAskProject = false,
            steps = listOf(preset.steps[0], preset.steps[1].copy(track = false)),
        ).expanded()
        val r = run(13, minutesAgo = 0, session = session)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(13).live, "Fokus counts")
        toggl.onEvent(TimerEvent.Step, r.copy(index = 1))
        toggl.idle()
        assertNull(server.running, "the break does not")
        toggl.onEvent(TimerEvent.Step, r.copy(index = 0, round = 1))
        toggl.idle()
        assertTrue(server.running != null, "the next Fokus counts again")
        toggl.onEvent(TimerEvent.Step, r.copy(index = 0, round = 2)) // skipped a break: nothing changes
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, index = 2, round = 3, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals(2, server.entries.size, "one entry per Fokus, none for the break")
        assertTrue(toggl.runs.value.getValue(13).synced)
    }

    @Test
    fun askingSessionWaitsForItsName() = runBlocking {
        val toggl = tracker(this)
        settings.rememberEntry("Pomodoro", "Gitarre", TogglProject(3, "Musik", 7))
        val ask = preset(1)
        val r = run(14, minutesAgo = 30, session = ask.expanded())
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        val waiting = toggl.runs.value.getValue(14)
        assertTrue(waiting.waiting && !waiting.synced)
        assertEquals("Gitarre" to "Musik", waiting.description to waiting.project?.name, "offers what was booked last time")
        assertTrue(server.calls.isEmpty(), "nothing goes to Toggl yet")

        toggl.onEvent(TimerEvent.Step, r.copy(index = 1))
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, index = 2, endedAt = wallClockMillis()))
        toggl.idle()
        assertTrue(server.calls.isEmpty())
        // Given at the end: the Fokus goes in as it was, the break stays out.
        toggl.edit(14, "Song schreiben", TogglProject(3, "Musik", 7))
        toggl.idle()
        val e = server.entries.values.single()
        assertEquals("Song schreiben", e.description)
        assertEquals(3L, e.project)
        assertTrue(toggl.runs.value.getValue(14).synced)
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

    @Test
    fun leftOutThisTimeAndTakenBackIn() = runBlocking {
        val toggl = tracker(this)
        val r = run(15, minutesAgo = 10)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        assertTrue(server.running != null)
        toggl.setTracked(15, false)
        toggl.idle()
        assertTrue(server.entries.isEmpty(), "out of Toggl, the live entry too")
        toggl.onEvent(TimerEvent.PauseChanged, r.copy(pausedRemaining = 1, pausedAt = monotonicMillis()))
        toggl.onEvent(TimerEvent.PauseChanged, r)
        toggl.idle()
        assertTrue(server.entries.isEmpty(), "stays out while it runs on")

        toggl.setTracked(15, true)
        toggl.idle()
        assertEquals(2, server.entries.size, "the time so far is booked, and it counts on live")
        assertTrue(toggl.runs.value.getValue(15).live)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(15).synced)
    }

    @Test
    fun pauseButtonCanLeaveTogglCounting() = runBlocking {
        val toggl = tracker(this)
        val r = run(16, minutesAgo = 5, session = pomodoro.copy(togglPauseStops = false))
        toggl.onEvent(TimerEvent.Started, r)
        toggl.onEvent(TimerEvent.PauseChanged, r.copy(pausedRemaining = 1, pausedAt = monotonicMillis()))
        toggl.onEvent(TimerEvent.PauseChanged, r)
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals(1, server.entries.size, "one entry straight through")
    }

    @Test
    fun tellsOnceWhenSavedOrStillWaiting() = runBlocking {
        val toggl = tracker(this)
        val told = mutableListOf<String>()
        toggl.onReport = { told += if (it.waiting) "warten" else "gespeichert" }
        val r = run(17, minutesAgo = 5)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.idle()
        assertTrue(told.isEmpty(), "nothing while it runs")
        toggl.onEvent(TimerEvent.Finished, r.copy(finished = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals(listOf("gespeichert"), told)
        toggl.edit(17, "Anders", null)
        toggl.idle()
        assertEquals(listOf("gespeichert"), told, "once")

        told.clear()
        val ask = run(18, minutesAgo = 5, session = preset(1).expanded())
        toggl.onEvent(TimerEvent.Started, ask)
        toggl.onEvent(TimerEvent.Finished, ask.copy(finished = true, index = 2, endedAt = wallClockMillis()))
        toggl.idle()
        assertEquals(listOf("warten"), told)
        toggl.edit(18, "Song", null)
        toggl.idle()
        assertEquals(listOf("warten", "gespeichert"), told)
    }

    @Test
    fun offByDefault() = runBlocking {
        val toggl = tracker(this)
        toggl.onEvent(TimerEvent.Started, run(19, minutesAgo = 0, session = presets()[0]))
        toggl.onEvent(TimerEvent.Started, run(20, minutesAgo = 0, session = Session(name = "Neu", steps = listOf(Step(30)))))
        toggl.idle()
        assertTrue(toggl.runs.value.isEmpty() && server.calls.isEmpty(), "only ticked sessions count")
    }

    @Test
    fun projectAloneHasNoDescription() = runBlocking {
        val toggl = tracker(this)
        val musik = TogglProject(3, "Musik", 7)
        toggl.onEvent(TimerEvent.Started, run(21, minutesAgo = 0, session = preset(0).copy(togglProject = musik)))
        toggl.idle()
        assertEquals("" to 3L, server.running!!.description to server.running!!.project, "just Musik")
    }

    @Test
    fun pickedBeforeTheStart() = runBlocking {
        val toggl = tracker(this)
        val ask = preset(1).expanded()
        val musik = TogglProject(3, "Musik", 7)
        assertEquals("Pomodoro" to null, toggl.suggestion(ask), "nothing picked yet: the session's name")
        toggl.choose(ask, "Gitarre", musik)
        toggl.onEvent(TimerEvent.Started, run(22, minutesAgo = 0, session = ask))
        toggl.idle()
        val t = toggl.runs.value.getValue(22)
        assertFalse(t.waiting, "nothing left to fill in")
        assertTrue(t.live)
        assertEquals("Gitarre" to 3L, server.running!!.description to server.running!!.project)
        assertEquals("Gitarre" to musik, toggl.suggestion(ask), "offered next time")

        // The choice is for one start only.
        toggl.onEvent(TimerEvent.Started, run(23, minutesAgo = 0, session = ask))
        assertTrue(toggl.runs.value.getValue(23).waiting)
    }

    @Test
    fun startedWithoutToggl() = runBlocking {
        val toggl = tracker(this)
        val ask = preset(1).expanded()
        toggl.choose(ask, null, null)
        toggl.onEvent(TimerEvent.Started, run(24, minutesAgo = 0, session = ask))
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(24).deleted)
        assertTrue(server.calls.isEmpty())
        // Changed one's mind while it runs: asks for the name then.
        toggl.setTracked(24, true)
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(24).waiting)
        toggl.edit(24, "Lesen", null)
        toggl.idle()
        assertEquals("Lesen", server.running!!.description)
    }

    @Test
    fun stopwatchStopIsItsEnd() = runBlocking {
        val toggl = tracker(this)
        val watch = newStopwatch().copy(toggl = true, togglDescription = "Lesen")
        val r = run(25, minutesAgo = 12, session = watch)
        toggl.onEvent(TimerEvent.Started, r)
        toggl.onEvent(TimerEvent.Stopped, r.copy(finished = true, stopped = true, endedAt = wallClockMillis()))
        toggl.idle()
        val t = toggl.runs.value.getValue(25)
        assertTrue(t.synced && t.ended && !t.aborted)
        val e = server.entries.values.single()
        assertEquals("Lesen", e.description)
        assertTrue(e.tags.isEmpty(), "not aborted")
        assertTrue(e.stop != null)

        // Stopped after moments: started by mistake, left out.
        val quick = run(26, minutesAgo = 0, session = watch)
        toggl.onEvent(TimerEvent.Started, quick)
        toggl.onEvent(TimerEvent.Stopped, quick.copy(finished = true, stopped = true, endedAt = wallClockMillis()))
        toggl.idle()
        assertTrue(toggl.runs.value.getValue(26).deleted)
        assertEquals(1, server.entries.size)
    }
}

