package com.example.wassertiger

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A stopwatch is made from the list, counts up, and keeps its reading after Stopp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StopwatchUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    /** The page's own list; the pager scrolls sideways. */
    private val list = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @After
    fun stopRuns() = TimerService.engine.stopAll()

    @Test
    fun madeStartedAndStopped() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNode(list).performScrollToNode(hasText("+ Neue Stoppuhr"))
            rule.onNodeWithText("+ Neue Stoppuhr").performClick()
            rule.onNodeWithText("Name der Session").performTextReplacement("Lesen")
            rule.onNodeWithText("Zählt einfach hoch bis Stopp, ohne Leuchten oder Vibrieren.").assertExists()
            rule.onNodeWithText("Intervall hinzufügen").assertDoesNotExist()
            rule.onNodeWithText("Vibration").assertDoesNotExist()
            rule.onNodeWithText("Alle …").performClick()
            rule.onNode(list).performScrollToNode(hasText("Vibration"))
            rule.onNodeWithText("Speichern").performClick()

            val saved = sessionStore(context).load().single { it.name == "Lesen" }
            assertTrue(saved.stopwatch && saved.endless)
            assertEquals(2, saved.signalEvery)
            rule.onNode(list).performScrollToNode(hasText("Stoppuhr · Signal alle 2 s"))

            // The service does not run in the test: started here as it would.
            TimerService.engine.keepStopped = keepStopped(settingsStore(context))
            val id = TimerService.engine.start(saved)
            rule.onNodeWithText("0:00").assertExists()
            rule.onNodeWithText("Überspringen").assertDoesNotExist()
            rule.onNodeWithText("Stopp").performScrollTo().performClick()
            rule.onNodeWithText("Gestoppt").assertExists()
            assertTrue(TimerService.engine.run(id)!!.stopped)
        }
    }

    /** The third kind: a timer that counts on past its time until Stopp. */
    @Test
    fun timerCountsOnPastItsTime() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNode(list).performScrollToNode(hasText("+ Neuer Timer, der weiterzählt"))
            rule.onNodeWithText("+ Neuer Timer, der weiterzählt").performClick()
            rule.onNodeWithText("Name der Session").performTextReplacement("Mauro")
            rule.onNode(list).performScrollToNode(hasText("Nach dem Ende weiterzählen, bis Stopp"))
            rule.onNodeWithText("Nach dem Ende weiterzählen, bis Stopp").assertExists()
            rule.onNodeWithText("Speichern").performClick()

            val saved = sessionStore(context).load().single { it.name == "Mauro" }
            assertTrue(saved.overtime && !saved.endless)
            assertEquals(listOf(20 * 60), saved.steps.map { it.seconds })
            rule.onNode(list).performScrollToNode(hasText("1 Intervall · 20:00 · danach weiter bis Stopp"))

            // The service does not run in the test: started here as it would.
            TimerService.engine.keepStopped = keepStopped(settingsStore(context))
            val id = TimerService.engine.start(saved)
            rule.onNodeWithText("Danach: zählt weiter bis Stopp").assertExists()
            // The test's clock stands still: skipping the 20 minutes is how their end comes here.
            rule.onNodeWithText("Überspringen").performScrollTo().performClick()
            assertTrue(TimerService.engine.run(id)!!.overtime)
            rule.onNodeWithText("Zeit um").assertExists()
            rule.onNodeWithText("+0:00 · läuft weiter bis Stopp").assertExists()
            rule.onNodeWithText("Überspringen").assertDoesNotExist()
            rule.onNodeWithText("Stopp").performScrollTo().performClick()
            rule.onNodeWithText("Fertig!").assertExists()
            val done = TimerService.engine.run(id)!!
            assertTrue(done.finished && !done.stopped)
        }
    }
}
