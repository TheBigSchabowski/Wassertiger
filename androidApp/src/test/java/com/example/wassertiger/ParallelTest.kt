package com.example.wassertiger

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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

/** Two sessions at once, a session put into another, and booking a run in Toggl. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ParallelTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() {
        TimerService.engine.stopAll()
        TimerService.engine.keepStopped = { false }
        settingsStore(context).togglToken = null
    }

    @Test
    fun secondSessionRunsBesideTheFirst() {
        TimerService.engine.start(sessionStore(context).load().first())
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("+ Parallel starten").performClick()
            rule.onNodeWithText("Zur Anzeige").assertExists()
            rule.onNodeWithText("„Start“ lässt eine weitere Session parallel laufen.").assertExists()
            TimerService.engine.start(presets()[0])
            rule.onNodeWithText("Zur Anzeige").performClick()
            rule.onAllNodesWithText("Stopp").assertCountEquals(2)
            rule.onNodeWithText("Atemübung").assertExists()
            rule.onNodeWithText("Einatmen").assertExists()

            rule.onAllNodesWithText("Stopp")[1].performScrollTo().performClick()
            rule.onAllNodesWithText("Stopp").assertCountEquals(1)
            assertEquals(listOf("Beispiel"), TimerService.engine.active.map { it.session.name })
        }
    }

    @Test
    fun editorPutsInAWholeSession() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Beispiel").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Session einfügen: wählen"))
            rule.onNodeWithText("Session einfügen: wählen").performClick()
            rule.onNodeWithText("Atemübung · 5:00").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Eingefügt: 60 Intervalle · 5:00"))
            rule.onNodeWithText("Speichern").performClick()

            val saved = sessionStore(context).load().single { it.name == "Beispiel" }
            assertEquals(7, saved.steps.size)
            assertTrue(saved.steps.last().isBlock)
            assertEquals(66, saved.expanded().steps.size)
        }
    }

    @Test
    fun stoppedRunOffersToggl() {
        settingsStore(context).togglToken = "test-token"
        TimerService.engine.keepStopped = { true }
        val id = TimerService.engine.start(sessionStore(context).load().first())
        TimerService.engine.stop(id)
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Gestoppt").assertExists()
            // Toggl did not follow this run (no service in the test): it offers to book it afterwards.
            rule.onNodeWithText("Nicht erfasst", substring = true).assertExists()
            rule.onNodeWithText("Projekt: ohne").performScrollTo().assertExists()
            rule.onNodeWithText("Eintragen").performScrollTo().assertExists()
            rule.onNodeWithText("Zurück").performScrollTo().performClick()
            assertTrue(TimerService.engine.runs.value.isEmpty())
            rule.onNodeWithText("Toggl ✓").assertExists()
        }
    }
}
