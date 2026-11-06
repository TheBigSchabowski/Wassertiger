package com.example.wassertiger

import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
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

/** The editor's signal settings and the countdown over the lock screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SignalUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun stopRun() = TimerService.engine.stopAll()

    @Test
    fun editorSetsLightOnlyAndTone() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Beispiel").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Aus – nur Licht"))
            rule.onNodeWithText("Aus – nur Licht").performClick()
            rule.onNodeWithText("Kein Vibrieren", substring = true).assertExists()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Am Ende"))
            rule.onNodeWithText("Am Ende").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Gong"))
            rule.onNodeWithText("Gong").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Aquamarin"))
            rule.onNode(hasContentDescription("Aquamarin")).performClick()
            rule.onNode(hasText("#Hex") and hasSetTextAction()).assertTextContains("#7FFFD4")
            rule.onNodeWithText("Speichern").performClick()

            val saved = sessionStore(context).load().single { it.name == "Beispiel" }
            assertEquals(0, saved.vibration)
            assertEquals(ToneMode.END, saved.tone)
            assertEquals(Chime.GONG, saved.chime)
            assertEquals(0x7FFFD4, saved.flashColor)
        }
    }

    @Test
    fun commaSwitchesToMinutesAndCursorStaysAfterIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("+ Neue Session").performClick()
            rule.onNode(hasText("Sek.") and hasSetTextAction()).performTextClearance()
            rule.onNode(hasText("Sek.") and hasSetTextAction()).performTextInput("5,")
            val minutes = rule.onNode(hasText("Min:Sek") and hasSetTextAction())
            minutes.assertTextContains("5:")
            assertEquals(TextRange(2), minutes.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange))
            minutes.performTextInput("30")
            minutes.assertTextContains("5:30")
        }
    }

    @Test
    fun dotSwitchesToMinuteModeFilledFromTheLeft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("+ Neue Session").performClick()
            rule.onNode(hasText("Sek.") and hasSetTextAction()).performTextClearance()
            rule.onNode(hasText("Sek.") and hasSetTextAction()).performTextInput(".")
            val field = rule.onNode(hasText("⏱ Min:Sek") and hasSetTextAction())
            field.assertTextContains("")
            for (digit in "05") field.performTextInput(digit.toString())
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Gesamt: 5:00", substring = true))
            for (digit in "30") field.performTextInput(digit.toString())
            field.assertTextContains("05:30")
            assertEquals(TextRange(5), field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange))
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Gesamt: 5:30", substring = true))
        }
    }

    @Test
    fun lockScreenShowsCountdownAndLeavesWhenStopped() {
        val session = sessionStore(context).load().first()
        TimerService.engine.start(session)
        ActivityScenario.launch<FlashActivity>(Intent(context, FlashActivity::class.java)).use { scenario ->
            assertTrue(FlashActivity.alive)
            rule.onNodeWithText("Intervall 1 / 6").assertExists()
            rule.onNodeWithText("Entsperren").assertExists()

            TimerService.engine.stopAll()
            rule.waitForIdle()
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun flashTurnsIntoCountdown() {
        TimerService.engine.start(sessionStore(context).load().first())
        val flash = Intent(context, FlashActivity::class.java)
            .putExtra(FlashActivity.EXTRA_TITLE, "15 s")
            .putExtra(FlashActivity.EXTRA_TEXT, "2 / 6")
            .putExtra(FlashActivity.EXTRA_MILLIS, 300L)
        ActivityScenario.launch<FlashActivity>(flash).use {
            rule.onNodeWithText("2 / 6").assertExists()
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithText("Entsperren").assertExists()
        }
    }
}
