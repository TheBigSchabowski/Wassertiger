package com.example.wassertiger

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // Data written by 1.0.0 has no rounds and plays once.
        saved = """[{"id":"a","name":"Alt","steps":[{"seconds":10,"name":""}]}]"""
        assertEquals(1, store.load().single().rounds)
    }

    @Test
    fun repeatsRoundsThenFinishes() = runBlocking {
        val engine = TimerEngine(this)
        val seen = mutableListOf<Pair<TimerEvent, String>>()
        engine.listener = { event, s -> seen += event to "${s.round}/${s.index}" }
        engine.start(twoSteps.copy(rounds = 2))
        assertEquals("B", engine.state.value!!.nextStep!!.name)
        engine.skip()
        assertEquals("A", engine.state.value!!.nextStep!!.name, "round 1 ends, round 2 starts with A")
        engine.skip()
        assertEquals("Runde 2 / 2", engine.state.value!!.roundLabel)
        engine.skip()
        assertNull(engine.state.value!!.nextStep, "last step of last round")
        engine.skip()
        assertTrue(engine.state.value!!.finished)
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
        engine.start(Session(name = "Ping", steps = listOf(Step(5)), rounds = ENDLESS))
        repeat(100) { engine.skip() }
        val s = engine.state.value!!
        assertTrue(!s.finished)
        assertEquals("Runde 101", s.roundLabel)
        assertEquals("5 s", s.nextStep?.let { formatDuration(it.seconds) })
        assertEquals("5 s" to "Runde 101", s.flashText())
        engine.stop()
    }

    @Test
    fun signalsEveryStepAndFinishesOnTime() = runBlocking {
        val engine = TimerEngine(this)
        val events = mutableListOf<Pair<TimerEvent, Long>>()
        val t0 = monotonicMillis()
        engine.listener = { event, _ -> events += event to monotonicMillis() - t0 }
        engine.start(twoSteps)
        while (engine.state.value?.finished != true) delay(20)

        assertEquals(listOf(TimerEvent.Started, TimerEvent.Step, TimerEvent.Finished), events.map { it.first })
        assertTrue(events[1].second in 950..1200, "step change at ${events[1].second} ms")
        assertTrue(events[2].second in 1950..2200, "finish at ${events[2].second} ms")
        engine.dismissFinished()
        assertNull(engine.state.value)
    }

    @Test
    fun pauseHoldsTheClockAndSkipMovesOn() = runBlocking {
        val engine = TimerEngine(this)
        val events = mutableListOf<TimerEvent>()
        engine.listener = { event, _ -> events += event }
        engine.start(twoSteps)
        delay(300)
        engine.togglePause()
        val held = engine.state.value!!.remainingMs()
        delay(1200)
        assertEquals(0, engine.state.value!!.index, "paused step must not advance")
        assertEquals(held, engine.state.value!!.remainingMs())

        engine.togglePause()
        engine.skip()
        assertEquals("B", engine.state.value!!.step!!.name)
        engine.stop()
        assertNull(engine.state.value)
        assertEquals(
            listOf(TimerEvent.Started, TimerEvent.PauseChanged, TimerEvent.PauseChanged, TimerEvent.Step, TimerEvent.Stopped),
            events,
        )
    }
}
