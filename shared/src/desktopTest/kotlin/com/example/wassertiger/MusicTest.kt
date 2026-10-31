package com.example.wassertiger

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MusicTest {

    private class FakeOutput : LiveOutput {
        var source: LiveSource? = null
        override fun start(source: LiveSource) { this.source = source }
        override fun stop() { source = null }
        override val isRunning get() = source != null
    }

    /** Loudness of [seconds] of output. */
    private fun MusicEngine.rms(seconds: Double): Double {
        val n = (MusicEngine.SAMPLE_RATE * seconds).toInt()
        val left = FloatArray(n)
        render(left, FloatArray(n), n)
        return sqrt(left.sumOf { it.toDouble() * it } / n)
    }

    private val drone = Session(name = "Drone", music = true, steps = listOf(Step(60, "Phrygisch", drone = 9), Step(60, "16tel", groove = "Straight 16")))

    @Test
    fun droneAndDrumsSoundAndFallSilent() {
        val engine = MusicEngine { DrumSamples.load(::bundledResource) }
        engine.prepare(MusicEngine.SAMPLE_RATE)
        assertEquals(0.0, engine.rms(0.2), "nothing switched on")
        engine.droneOn = true
        assertTrue(engine.rms(0.5) > 0.05, "drone")
        engine.droneOn = false
        engine.rms(0.5)
        assertTrue(engine.rms(0.2) < 0.001, "drone faded out")
        engine.settings = MusicSettings(groove = "Straight 16", bpm = 120)
        engine.drumsOn = true
        assertTrue(engine.rms(2.0) > 0.01, "drums")
        for (kit in DrumKit.entries) {
            engine.settings = engine.settings.copy(kit = kit)
            assertTrue(engine.rms(2.0) > 0.005, "kit $kit")
        }
    }

    @Test
    fun acousticSamplesAreBundled() {
        val samples = assertNotNull(DrumSamples.load(::bundledResource))
        assertTrue(samples.kick.samples.size > 1000 && samples.kick.rate == 48000)
    }

    @Test
    fun droneOnAIsA() {
        val s = MusicSettings(root = 9, octave = 2)
        assertEquals(listOf(45, 52), MusicEngine.dronePitches(s))
        assertEquals(110.0, MusicEngine.frequency(45, 440.0), 0.001)
        assertEquals(listOf(45), MusicEngine.dronePitches(s.copy(fifth = false)))
    }

    @Test
    fun playerFollowsTheRun() {
        val out = FakeOutput()
        val player = MusicPlayer(out)
        val first = RunState(drone, index = 0, stepEndAt = 0)

        player.follow(first)
        assertTrue(out.isRunning)
        assertTrue(player.engine.droneOn && !player.engine.drumsOn, "only drone in step 1")
        assertEquals(9, player.engine.settings.root)

        player.follow(first.copy(index = 1))
        assertTrue(!player.engine.droneOn && player.engine.drumsOn, "only drums in step 2")
        assertEquals("Straight 16", player.engine.settings.groove)

        player.follow(first.copy(pausedRemaining = 1000))
        assertFalse(player.engine.droneOn, "pause is quiet")
        assertTrue(out.isRunning)

        player.follow(first.copy(index = 2, finished = true))
        assertFalse(out.isRunning, "finished")

        player.follow(RunState(Session(name = "Timer", steps = listOf(Step(5))), index = 0, stepEndAt = 0))
        assertFalse(out.isRunning, "timer sessions stay silent")
    }

    @Test
    fun musicSessionsAreStoredAndFlashWhatToPlay() {
        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        val session = drone.copy(sound = DroneSound.TANPURA, fifth = false, kit = DrumKit.BRUSHES)
        store.save(musicPresets() + session)
        assertEquals(musicPresets().map { it.copy(id = "") }, store.load().dropLast(1).map { it.copy(id = "") })
        assertEquals(session, store.load().last())

        val (title, text) = RunState(drone, index = 0, stepEndAt = 0).flashText()
        assertEquals("Phrygisch", title)
        assertEquals("A-Drone · 1 / 2", text)
        assertEquals("Straight 16 · 90 BPM", drone.steps[1].soundLabel())
        assertEquals("Stille", Step(60).soundLabel())
    }

    @Test
    fun minutesForMusic() {
        assertEquals(1200, parseMinutes("20"))
        assertEquals(90, parseMinutes("1:30"))
        assertEquals(null, parseMinutes("0"))
        assertEquals("20", formatMinutes(1200))
        assertEquals("1:30", formatMinutes(90))
        assertEquals(3600, parseDuration("1:00:00"))
    }
}
