package com.example.wassertiger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A long press marks sessions; the marked ones are archived, brought back or deleted together. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MarkTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val list = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /** On the card's name: on a narrow screen its middle is under the button on the right. */
    private val MARK_SPOT = Offset(40f, 30f)

    /** The card of [name], scrolled into view: the list only builds what is on screen. */
    private fun card(name: String) = rule.onNode(list).performScrollToNode(hasText(name)).let { rule.onNodeWithText(name) }

    private fun names(archived: Boolean) = sessionStore(context).load().filter { it.archived == archived }.map { it.name }

    @Test
    fun markedSessionsAreArchivedBroughtBackAndDeleted() {
        val store = sessionStore(context)
        store.save(store.load() + listOf("Eins", "Zwei", "Drei").map { Session(name = it, steps = listOf(Step(10))) })
        ActivityScenario.launch(MainActivity::class.java).use {
            // A long press marks the first, a tap then marks the next and unmarks again.
            card("Eins").performTouchInput { longClick(MARK_SPOT) }
            rule.onNodeWithText("1 markiert").assertExists()
            card("Zwei").performClick()
            card("Drei").performClick()
            rule.onNodeWithText("3 markiert").assertExists()
            card("Drei").performClick()
            rule.onNodeWithText("2 markiert").assertExists()
            rule.onNodeWithText("Archivieren").performClick()
            assertEquals(listOf("Eins", "Zwei"), names(archived = true))
            assertEquals(listOf("Beispiel", "Drei"), names(archived = false))
            assertEquals(listOf("Beispiel", "Drei"), Widgets.startable(context).map { it.name })
            rule.onNodeWithText("Eins").assertDoesNotExist()
            rule.onNodeWithText("Wassertiger").assertExists()

            // The archive is folded up at the end of the list.
            rule.onNode(list).performScrollToNode(hasText("Archiv (2) ▾"))
            rule.onNodeWithText("Archiv (2) ▾").performClick()
            card("Zwei").performTouchInput { longClick(MARK_SPOT) }
            rule.onNodeWithText("Zurückholen").performClick()
            assertEquals(listOf("Eins"), names(archived = true))

            // Deleting asks first.
            card("Eins").performTouchInput { longClick(MARK_SPOT) }
            card("Drei").performClick()
            rule.onNodeWithText("Löschen").performClick()
            rule.onNodeWithText("2 Sessions löschen?").assertExists()
            rule.onNodeWithText("Zurück").performClick()
            assertEquals(4, store.load().size)
            rule.onNodeWithText("Löschen").performClick()
            rule.onAllNodesWithText("Löschen").onLast().performClick()
            assertEquals(listOf("Beispiel", "Zwei"), store.load().map { it.name })
            rule.onNodeWithText("Wassertiger").assertExists()
        }
    }
}
