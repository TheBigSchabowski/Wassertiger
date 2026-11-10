package com.example.wassertiger

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
    fun stopRun() = TimerService.engine.stopAll()

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
        // The pager scrolls too; the list is the vertical one.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Meine Vorlagen"))
        rule.onNodeWithText("Neu daraus").assertExists()
    }

    @Test
    fun deletingAndDiscardingAskFirst() {
        rule.onNodeWithText("Beispiel").performClick()
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNodeWithText("Start").assertExists() // nothing changed: straight back to the list

        rule.onNodeWithText("Beispiel").performClick()
        rule.onNodeWithText("Name der Session").performTextReplacement("Anders")
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNodeWithText("Änderungen verwerfen?").assertExists()
        rule.onNodeWithText("Verwerfen").performClick()
        rule.onNodeWithText("Beispiel").assertExists()

        rule.onNodeWithText("Beispiel").performClick()
        rule.onNodeWithText("Löschen").performClick()
        rule.onNodeWithText("„Beispiel“ löschen?").assertExists()
        rule.onNodeWithText("Zurück").performClick()
        rule.onNodeWithText("Löschen").performClick()
        rule.onAllNodesWithText("Löschen").onLast().performClick()
        rule.onNodeWithText("Beispiel").assertDoesNotExist()
        rule.onNodeWithText("+ Neue Session").assertExists()
    }

    @Test
    fun swipesToMusic() {
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithText("A-Drone Phrygisch").assertExists()
        rule.onNodeWithText("A-Drone Phrygisch").performScrollTo().performClick()
        rule.onNodeWithText("Drone: A").assertExists()
        rule.onNodeWithText("Drums: aus").assertExists()
    }
}
