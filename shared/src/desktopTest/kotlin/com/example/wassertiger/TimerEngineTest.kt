package com.example.wassertiger

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimerEngineTest {

    private val twoSteps = Session(name = "Test", steps = listOf(Step(1, "A"), Step(1, "B")))

    @Test
    fun durationsRoundTrip() {
        assertEquals(45, parseDuration("45"))
        assertEquals(90, parseDuration("1:30"))
        assertEquals(90, parseDuration(" 90s "))
        assertNull(parseDuration("0"))
        assertNull(parseDuration("1:75"))
        assertNull(parseDuration("abc"))
        assertEquals("48 s", formatDuration(48))
        assertEquals("1:00", formatDuration(60))
        assertEquals("2:05", formatDuration(125))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals(1500, parseDuration("25:00"))
        // A comma in the editor turns into a colon: "5 " is five minutes, "1:30:" an hour and a half.
        assertEquals(300, parseDuration("5:"))
        assertEquals(5400, parseDuration("1:30:"))
        assertNull(parseDuration(":"))
        assertNull(parseDuration("5::"))
        // Minute mode: digits fill MM:SS from the left.
        assertEquals(listOf("", "0", "05", "05:3", "05:30", "25:00", "12:34"), listOf("", "0", "05", "053", "0530", "2500", "123456").map(::minuteMask))
        assertEquals(330, parseDuration(minuteMask("0530")))
    }

    @Test
    fun vibrationLengthIsAdjustable() {
        // 1 s is the buzz from before the setting existed.
        assertTrue(longArrayOf(0, 350, 150, 350).contentEquals(vibrationPattern(1, Buzz.STEP)))
        assertTrue(longArrayOf(0, 600, 200, 600, 200, 600).contentEquals(vibrationPattern(1, Buzz.FINISH)))
        for (secs in VIBRATION_CHOICES.filter { it > 0 }) {
            val step = vibrationPattern(secs, Buzz.STEP).sum()
            assertTrue(step in secs * 1000L - 500..secs * 1000L, "$secs s lasts $step ms")
            assertTrue(vibrationPattern(secs, Buzz.FINISH).sum() > step)
        }
        assertEquals(0, vibrationPattern(0, Buzz.STEP).size)
        assertEquals(0, vibrationPattern(0, Buzz.START).size)

        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        store.save(listOf(twoSteps.copy(vibration = 5)))
        assertEquals(5, store.load().single().vibration)
        saved = saved!!.replace(",\"vibration\":5", "")
        assertEquals(1, store.load().single().vibration, "sessions saved before the setting buzz 1 s")
    }

    @Test
    fun brokenStoreDoesNotCrash() {
        var saved: String? = null
        var kept: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
            override fun keepBroken(raw: String) { kept = raw }
        })
        store.save(listOf(twoSteps, twoSteps.copy(id = "b", name = "Zwei")))
        assertEquals(2, store.load().size)
        assertNull(kept, "a list that reads fine is not put aside")

        // One session without its steps: the other one still loads.
        val oneBroken = saved!!.replaceFirst("\"steps\":", "\"stepz\":")
        saved = oneBroken
        assertEquals(listOf("Zwei"), store.load().map { it.name })
        assertEquals(oneBroken, kept)

        saved = "{ not json"
        assertEquals(emptyList(), store.load())
        assertEquals("{ not json", kept)
        saved = "{}"
        assertEquals(emptyList(), store.load(), "not a list at all")
    }

    @Test
    fun presetsAndSummaries() {
        val (breathing, pomodoro, every5) = presets()
        assertEquals(5 * 60, breathing.totalSeconds)
        assertEquals("2 Intervalle × 30 Runden · 5:00", breathing.summary())
        assertEquals("2 Intervalle × 4 Runden · 2:00:00", pomodoro.summary())
        assertTrue(every5.endless)
        assertNull(every5.totalSeconds)
        assertTrue(presets().first().id != breathing.id, "presets must hand out fresh ids")
    }

    @Test
    fun storeRoundTrip() {
        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        assertEquals(listOf(15, 15, 30, 30, 60, 48), store.load().single().steps.map { it.seconds })
        val session = Session(name = "Beine \"schwer\"", steps = listOf(Step(20, "Kniebeugen"), Step(10)))
        store.save(listOf(session))
        assertEquals(listOf(session), store.load())
        store.save(presets())
        assertEquals(listOf(30, 4, ENDLESS), store.load().map { it.rounds })

        val template = presets().first().asTemplate()
        store.save(listOf(session, template))
        assertEquals(listOf(false, true), store.load().map { it.template })
        val fromTemplate = template.newSession()
        assertTrue(!fromTemplate.template && fromTemplate.id != template.id && fromTemplate.steps == template.steps)

        // Archived survives saving; what is made from an archived session is not archived itself.
        store.save(listOf(session.copy(archived = true), template))
        assertEquals(listOf(true, false), store.load().map { it.archived })
        assertTrue(!session.copy(archived = true).newSession().archived && !session.copy(archived = true).asTemplate().archived)

        // Data written by 1.0.0 has no rounds and plays once.
        saved = """[{"id":"a","name":"Alt","steps":[{"seconds":10,"name":""}]}]"""
        assertEquals(1, store.load().single().rounds)
    }

    @Test
    fun repeatsRoundsThenFinishes() = runBlocking {
        val engine = TimerEngine(this)
        val seen = mutableListOf<Pair<TimerEvent, String>>()
        engine.listener = { event, s -> seen += event to "${s.round}/${s.index}" }
        val id = engine.start(twoSteps.copy(rounds = 2))
        assertEquals("B", engine.runs.value.single().nextStep!!.name)
        engine.skip(id)
        assertEquals("A", engine.runs.value.single().nextStep!!.name, "round 1 ends, round 2 starts with A")
        engine.skip(id)
        assertEquals("Runde 2 / 2", engine.runs.value.single().roundLabel)
        engine.skip(id)
        assertNull(engine.runs.value.single().nextStep, "last step of last round")
        engine.skip(id)
        assertTrue(engine.runs.value.single().finished)
        assertEquals(
            listOf(
                TimerEvent.Started to "0/0", TimerEvent.Step to "0/1", TimerEvent.Step to "1/0",
                TimerEvent.Step to "1/1", TimerEvent.Finished to "1/2",
            ),
            seen,
        )
    }

    @Test
    fun endlessKeepsGoing() = runBlocking {
        val engine = TimerEngine(this)
        val id = engine.start(Session(name = "Ping", steps = listOf(Step(5)), rounds = ENDLESS))
        repeat(100) { engine.skip(id) }
        val s = engine.runs.value.single()
        assertTrue(!s.finished)
        assertEquals("Runde 101", s.roundLabel)
        assertEquals("5 s", s.nextStep?.let { formatDuration(it.seconds) })
        assertEquals("5 s" to "Runde 101", s.flashText())
        engine.stop(id)
    }

    @Test
    fun signalsEveryStepAndFinishesOnTime() = runBlocking {
        val engine = TimerEngine(this)
        val events = mutableListOf<Pair<TimerEvent, Long>>()
        val t0 = monotonicMillis()
        engine.listener = { event, _ -> events += event to monotonicMillis() - t0 }
        val id = engine.start(twoSteps)
        while (engine.runs.value.single().finished != true) delay(20)

        assertEquals(listOf(TimerEvent.Started, TimerEvent.Step, TimerEvent.Finished), events.map { it.first })
        assertTrue(events[1].second in 950..1200, "step change at ${events[1].second} ms")
        assertTrue(events[2].second in 1950..2200, "finish at ${events[2].second} ms")
        engine.dismiss(id)
        assertTrue(engine.runs.value.isEmpty())
    }

    @Test
    fun pauseHoldsTheClockAndSkipMovesOn() = runBlocking {
        val engine = TimerEngine(this)
        val events = mutableListOf<TimerEvent>()
        engine.listener = { event, _ -> events += event }
        val id = engine.start(twoSteps)
        delay(300)
        engine.togglePause(id)
        val held = engine.runs.value.single().remainingMs()
        delay(1200)
        assertEquals(0, engine.runs.value.single().index, "paused step must not advance")
        assertEquals(held, engine.runs.value.single().remainingMs())

        engine.togglePause(id)
        engine.skip(id)
        assertEquals("B", engine.runs.value.single().step!!.name)
        engine.stop(id)
        assertTrue(engine.runs.value.isEmpty())
        assertEquals(
            listOf(TimerEvent.Started, TimerEvent.PauseChanged, TimerEvent.PauseChanged, TimerEvent.Step, TimerEvent.Stopped),
            events,
        )
    }

    @Test
    fun runsInParallel() = runBlocking {
        val engine = TimerEngine(this)
        val pomodoro = engine.start(presets()[1])
        val breathing = engine.start(presets()[0])
        assertEquals(listOf("Pomodoro", "Atemübung"), engine.active.map { it.session.name })
        engine.skip(breathing)
        assertEquals("Ausatmen", engine.run(breathing)!!.step!!.name)
        assertEquals("Fokus", engine.run(pomodoro)!!.step!!.name, "the other run is untouched")
        engine.togglePause(pomodoro)
        assertTrue(engine.run(pomodoro)!!.paused && !engine.run(breathing)!!.paused)
        engine.stop(pomodoro)
        assertEquals(listOf(breathing), engine.runs.value.map { it.id })
        engine.stopAll()
        assertTrue(engine.runs.value.isEmpty())
    }

    @Test
    fun startingARunningSessionStartsItOver() = runBlocking {
        val engine = TimerEngine(this)
        val session = presets()[0]
        val first = engine.start(session)
        engine.skip(first)
        val activeAtStop = mutableListOf<Int>()
        engine.listener = { event, _ -> if (event == TimerEvent.Stopped) activeAtStop += engine.active.size }
        val second = engine.start(session)
        assertEquals(listOf(1), activeAtStop, "the new run is in before the old one stops")
        assertEquals(listOf(second), engine.runs.value.map { it.id })
        assertEquals(0, engine.run(second)!!.index)
        engine.stopAll()
    }

    @Test
    fun stoppedRunStaysForTogglWhenWanted() = runBlocking {
        val engine = TimerEngine(this)
        engine.keepStopped = { true }
        val id = engine.start(twoSteps)
        engine.togglePause(id)
        delay(200)
        engine.stop(id)
        val stopped = engine.run(id)!!
        assertTrue(stopped.finished && stopped.stopped && !stopped.paused)
        assertTrue(stopped.pausedMs >= 150, "the pause is left out of the booked time")
        assertTrue(stopped.activeMillis() < 150)
        assertEquals("Gestoppt" to "Test", stopped.flashText())
        assertTrue(engine.active.isEmpty())
        engine.stop(id)
        assertTrue(engine.runs.value.isEmpty(), "stopping a stopped run clears it")
    }

    @Test
    fun blocksPlayTheirIntervals() = runBlocking {
        val breathing = presets()[0]
        val block = breathing.asBlock()
        assertEquals(5 * 60, block.seconds)
        assertEquals(60, block.block.size)
        val pomodoro = Session(name = "Pomodoro + Atmen", steps = listOf(Step(25 * 60, "Fokus"), block), rounds = 4)
        assertEquals("2 Intervalle × 4 Runden · 2:00:00", pomodoro.summary())
        assertEquals(61, pomodoro.expanded().steps.size)

        val engine = TimerEngine(this)
        val id = engine.start(pomodoro)
        engine.skip(id)
        assertEquals("Einatmen", engine.run(id)!!.step!!.name)
        assertEquals("Intervall 2 / 61", "Intervall ${engine.run(id)!!.index + 1} / ${engine.run(id)!!.session.steps.size}")
        engine.stopAll()

        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        store.save(listOf(pomodoro))
        assertEquals(pomodoro, store.load().single())
        // Endless sessions go in with one round.
        assertEquals(1, presets()[2].asBlock().block.size)

        // Toggl settings and which intervals count survive saving, in blocks too.
        val tracked = presets()[1].copy(togglProject = TogglProject(3, "Musik", 7), togglDescription = "Üben")
        val withBlock = tracked.copy(steps = tracked.steps + presets()[0].asBlock().copy(track = false))
        store.save(listOf(withBlock))
        assertEquals(withBlock, store.load().single())
        assertTrue(withBlock.expanded().steps.drop(2).none { it.track }, "a block left out leaves out its intervals")
        store.save(listOf(withBlock.copy(toggl = true)))
        assertTrue(store.load().single().toggl)

        // Saved before 1.9.0, when every session had "toggl": true: only those set up for Toggl keep it.
        saved = """[
            {"id":"a","name":"Atmen","steps":[{"seconds":5}],"toggl":true,"togglDescription":""},
            {"id":"b","name":"Üben","steps":[{"seconds":5}],"toggl":true,"togglProject":{"id":3,"name":"Musik","workspace":7}},
            {"id":"c","name":"Lesen","steps":[{"seconds":5}],"toggl":true,"togglDescription":"Buch"},
            {"id":"d","name":"Aus","steps":[{"seconds":5}],"toggl":false,"togglDescription":"Buch"}
        ]"""
        assertEquals(listOf(false, true, true, false), store.load().map { it.toggl })
    }

    @Test
    fun flashCountsDown() {
        val run = RunState(twoSteps.copy(steps = listOf(Step(5, "Einatmen"))), index = 0, stepEndAt = 10_000)
        assertEquals("5 s" to "Einatmen", run.flashText())
        assertEquals("5 s" to "Einatmen", run.flashText(now = 5_000))
        assertEquals("3 s" to "Einatmen", run.flashText(now = 7_500))
    }

    @Test
    fun settingsKeepTokenAndLastEntry() {
        var saved: String? = null
        val settings = SettingsStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        assertNull(settings.togglToken)
        settings.togglToken = " abc123 "
        assertEquals("abc123", settings.togglToken)
        val project = TogglProject(42, "Uni", 7)
        settings.rememberEntry("Pomodoro", "Bachelorarbeit", project)
        settings.rememberEntry("Atemübung", "Atmen", null)
        assertEquals("Bachelorarbeit" to project, settings.lastEntry("Pomodoro"))
        assertEquals("Atmen" to null, settings.lastEntry("Atemübung"))
        assertEquals("abc123", settings.togglToken)
        settings.togglToken = null
        assertNull(settings.togglToken)
        assertEquals("2026-11-06T09:30:00Z", isoInstant(1_793_957_400_000))
    }

    @Test
    fun stopwatchCountsUpAndStays() = runBlocking {
        val engine = TimerEngine(this)
        engine.keepStopped = { it.session.stopwatch }
        val watch = newStopwatch()
        assertFalse(watch.signals, "a plain stopwatch never signals")
        val id = engine.start(watch)
        kotlinx.coroutines.delay(1100)
        assertTrue(engine.run(id)!!.elapsedMs() in 1000..1900)
        engine.togglePause(id)
        val paused = engine.run(id)!!.elapsedMs()
        kotlinx.coroutines.delay(300)
        assertEquals(paused, engine.run(id)!!.elapsedMs(), "stands still while paused")
        engine.togglePause(id)
        engine.stop(id)
        val done = engine.run(id)!!
        assertTrue(done.finished && done.stopped, "the reading stays on screen")
        kotlinx.coroutines.delay(200)
        assertEquals(done.elapsedMs(), engine.run(id)!!.elapsedMs())
        assertEquals(formatClock(done.elapsedMs()) to "Stoppuhr", done.flashText())
        engine.stopAll()
    }

    @Test
    fun stopwatchSignalsAndSaves() {
        assertEquals("0:07", formatClock(7_400))
        assertEquals("12:34", formatClock((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatClock(3_723_000))
        val every2 = newStopwatch().copy(name = "Lesen", signalEvery = 2, steps = stopwatchSteps(2), toggl = true)
        assertTrue(every2.signals && every2.endless)
        assertEquals("Stoppuhr · Signal alle 2 s", every2.summary())
        assertEquals("Stoppuhr", newStopwatch().summary())

        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        store.save(listOf(every2, newStopwatch()))
        assertEquals(every2, store.load()[0])
        assertTrue(store.load()[1].stopwatch && !store.load()[1].signals)

        // Before 1.10.0 the pause button split the Toggl entry by default: now one block for everyone.
        saved = """[{"id":"a","name":"Pomodoro","steps":[{"seconds":5}],"togglPauseStops":true}]"""
        assertFalse(store.load().single().togglPauseStops)
        assertFalse(Session(name = "Neu", steps = listOf(Step(30))).togglPauseStops)
    }
}

