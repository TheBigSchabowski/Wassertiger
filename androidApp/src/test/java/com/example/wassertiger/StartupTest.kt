package com.example.wassertiger

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Launches the real MainActivity and starts the example session, no device needed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class StartupTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun stopRun() = TimerService.engine.stop()

    @Test
    fun startsAndRunsExampleSession() {
        rule.onNodeWithText("Wassertiger").assertExists()
        rule.onNodeWithText("Beispiel").assertExists()
        rule.onNodeWithText("Start").performClick()

        val intent = shadowOf(rule.activity.application).nextStartedService
        Robolectric.buildService(TimerService::class.java, intent).create().startCommand(0, 1)
        rule.waitForIdle()
        rule.onNodeWithText("Intervall 1 / 6").assertExists()
    }

    @Test
    fun savesOwnTemplate() {
        rule.onNodeWithText("Atemübung").performScrollTo().performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Als Vorlage speichern"))
        rule.onNodeWithText("Als Vorlage speichern").performClick()
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Meine Vorlagen"))
        rule.onNodeWithText("Neu daraus").assertExists()
    }
}
