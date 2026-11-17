package com.example.wassertiger

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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

/** Testet den neuen Aufgaben-Reiter: Quests, Wochenaufgaben mit Unteraufgaben, Fokus-Modus, Kalender & Rückblick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TasksUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun cleanUp() {
        TimerService.engine.stopAll()
        TimerService.engine.listener = { _, _ -> }
        TimerService.engine.keepStopped = { false }
    }

    @Test
    fun createsTaskSwitchesSubViewsAndOpensFocusMode() {
        val store = taskStore(context)
        val quest = QuestProject(
            id = "q_ui",
            title = "Masterarbeit",
            icon = "📚",
            color = 0x2DD4BF,
            subtasks = listOf(SubTask("qs1", "Gliederung")),
        )
        store.saveQuest(quest)

        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Timer-Editor bietet Quest-Zuordnung
            rule.onNodeWithText("Beispiel").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Quest: ohne Quest (Sonstiges)"))
            rule.onNodeWithText("Quest: ohne Quest (Sonstiges)").performClick()
            rule.onNodeWithText("📚 Masterarbeit").performClick()
            rule.onNodeWithText("Speichern").performClick()
            assertEquals("q_ui", sessionStore(context).load().single { it.name == "Beispiel" }.questId)

            // 2. Eigenen Haupt-Reiter „Fokus“ direkt öffnen
            rule.onNodeWithText("Fokus").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Worauf fokussierst du dich?").performScrollTo().assertExists()
            rule.onNodeWithText("Pomodoro-Stil").performScrollTo().assertExists()
            rule.onNodeWithText("Timer wählen").performScrollTo().assertExists()

            // 3. Wechsel zum Haupt-Reiter „Aufgaben“
            rule.onNodeWithText("Aufgaben").performClick()
            rule.waitForIdle()

            // Wochenplaner ist sichtbar
            rule.onNodeWithText("Woche (Mo–So)").assertExists()
            rule.onNodeWithText("📚 Masterarbeit").assertExists()

            // Aufgabe schnell hinzufügen
            rule.onNodeWithText("Neue Aufgabe …").performScrollTo().performTextInput("Quellen sortieren")
            rule.onNodeWithText("+ Hinzufügen").performScrollTo().performClick()
            rule.onAllNodesWithText("Quellen sortieren").onFirst().assertExists()

            // „▶ Fokus“ wechselt direkt in den Fokus-Reiter mit der ausgewählten Aufgabe
            rule.onAllNodesWithText("▶ Fokus").onFirst().performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Fokus-Timer & Signale").performScrollTo().assertExists()
            rule.onNodeWithText("▶ Fokus starten").performScrollTo().assertExists()

            // Zurück zu „Aufgaben“ für Quests, Kalender & Rückblick
            rule.onNodeWithText("Aufgaben").performClick()
            rule.waitForIdle()

            // Quests-Unteransicht öffnen
            rule.onNodeWithText("Quests").performClick()
            rule.onNodeWithText("Quests & Side-Quests").performScrollTo().assertExists()
            rule.onNodeWithText("Gliederung").performScrollTo().assertExists()

            // Kalender-Unteransicht & JSON-Merge-Dialog prüfen (LazyColumn benötigt performScrollToNode)
            val verticalList = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            rule.onNodeWithText("Kalender").performClick()
            rule.onNode(verticalList).performScrollToNode(hasText("JSONs zusammenfügen"))
            rule.onNodeWithText("JSONs zusammenfügen").performClick()
            rule.onNodeWithText("Mehrere JSONs zusammenfügen").assertExists()
            rule.onNodeWithText("Abbrechen").performClick()

            // Rückblick-Unteransicht öffnen
            rule.onNodeWithText("Rückblick").performClick()
            rule.onNodeWithText("Wochenrückblick").performScrollTo().assertExists()
            rule.onNodeWithText("Quest-Stunden · pro Tag").performScrollTo().assertExists()

            assertEquals(1, store.state.value.tasks.size)
            assertTrue(store.state.value.tasks.single().title == "Quellen sortieren")
        }
    }
}
