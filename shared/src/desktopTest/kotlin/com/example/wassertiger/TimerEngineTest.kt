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
