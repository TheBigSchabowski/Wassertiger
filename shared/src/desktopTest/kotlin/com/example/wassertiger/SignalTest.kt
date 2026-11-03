package com.example.wassertiger

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalTest {

    private val session = Session(name = "Test", steps = listOf(Step(10)))

    @Test
    fun chimesAreShortAndNeverClip() {
        for (chime in Chime.entries) {
            for (finish in listOf(false, true)) {
                val samples = chimeSamples(chime, finish, 44100)
                val ms = samples.size * 1000L / 44100
                assertEquals(chimeMillis(chime, finish), ms, "$chime finish=$finish", )
                assertTrue(ms in 100..3000, "$chime finish=$finish lasts $ms ms")
                val peak = samples.maxOf { abs(it) }
                assertTrue(abs(peak - CHIME_PEAK) < 1e-4, "$chime peaks at $peak")
                // Starts and ends at (near) silence, so it never clicks.
                assertTrue(abs(samples.first()) < 0.01 && abs(samples.last()) < 0.01, "$chime clicks")
            }
            assertTrue(chimeMillis(chime, true) > chimeMillis(chime, false), "the end sounds longer: $chime")
        }
    }

    @Test
    fun toneModes() {
        assertTrue(!ToneMode.OFF.sounds(false) && !ToneMode.OFF.sounds(true))
        assertTrue(!ToneMode.END.sounds(false) && ToneMode.END.sounds(true))
        assertTrue(ToneMode.EVERY.sounds(false) && ToneMode.EVERY.sounds(true))
    }

    @Test
    fun lightOnlyFlashesLonger() {
        assertTrue(!session.lightOnly)
        val light = session.copy(vibration = 0)
        assertTrue(light.lightOnly && light.flashMillis > session.flashMillis)
        assertEquals("bei jedem Wechsel vibriert es und das Display geht kurz an.", session.signalText())
        assertEquals(
            "bei jedem Wechsel leuchtet nur das Display auf, ohne Vibration. Am Ende ertönt eine Glocke.",
            light.copy(tone = ToneMode.END, chime = Chime.BELL).signalText(),
        )
    }

    @Test
    fun flashColours() {
        assertEquals(0x7FFFD4, parseHexColor("#7FFFD4"))
        assertEquals(0x7FFFD4, parseHexColor(" 7fffd4 "))
        assertEquals(0x77FFDD, parseHexColor("#7FD"))
        assertEquals(null, parseHexColor("#7FFFD"))
        assertEquals(null, parseHexColor("aquamarin"))
        assertEquals("#00A0FF", formatHexColor(0x00A0FF))
        assertTrue(isLightColor(0x7FFFD4) && isLightColor(AMBER_RGB) && !isLightColor(0x2F7BFF))
        assertEquals(AMBER_RGB, session.flashColor)
    }

    @Test
    fun toneIsStoredAndOffForOldSessions() {
        var saved: String? = null
        val store = SessionStore(object : SessionStorage {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        })
        val loud = session.copy(tone = ToneMode.EVERY, chime = Chime.GONG, vibration = 0, flashColor = 0x7FFFD4)
        store.save(listOf(loud))
        assertEquals(listOf(loud), store.load())
        // Data written before 1.6.0 has neither field and stays silent.
        saved = """[{"id":"a","name":"Alt","steps":[{"seconds":10,"name":""}]}]"""
        val old = store.load().single()
        assertEquals(ToneMode.OFF, old.tone)
        assertEquals(Chime.BEEP, old.chime)
        assertEquals(AMBER_RGB, old.flashColor)
    }
}
