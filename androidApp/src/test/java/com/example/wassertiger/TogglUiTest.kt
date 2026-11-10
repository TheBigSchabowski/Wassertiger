package com.example.wassertiger

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A run counts in Toggl while it runs; stopped early it shows as aborted and can be deleted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TogglUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()

    @Before
    fun fakeToggl() {
        val settings = settingsStore(context).apply { togglToken = "test-token" }
        var running = false
        tracker = TogglTracker(settings, MainScope()) { token ->
            Toggl(token) { method, url, _, _, body ->
                val path = url.substringAfter("/api/v9/")
                calls += "$method $path"
                when {
                    path == "me" -> HttpResult(200, """{"fullname":"Test","default_workspace_id":7}""")
                    path == "me/time_entries/current" -> HttpResult(200, if (running) """{"id":1}""" else "null")
                    method == "POST" -> HttpResult(200, """{"id":1}""").also { running = body!!.contains("\"duration\":-1") }
                    method == "PUT" -> HttpResult(200, "{}").also { if (body!!.contains("stop")) running = false }
                    else -> HttpResult(200, "")
                }
            }
        }
        tracker!!.onReport = { notifyToggl(context, it) }
        TimerService.engine.listener = { event, run -> togglTracker(context).onEvent(event, run) }
    }

    @After
    fun cleanUp() {
        TimerService.engine.stopAll()
        TimerService.engine.listener = { _, _ -> }
        TimerService.engine.keepStopped = { false }
        settingsStore(context).togglToken = null
        tracker = null
    }

    @Test
    fun countsLiveThenShowsAbortedEntry() {
        val id = TimerService.engine.start(sessionStore(context).load().first().copy(toggl = true))
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("● Toggl zählt mit: Beispiel").performScrollTo().assertExists()
            // A real run would need a minute to stay; MainActivity set the real rule on start.
            TimerService.engine.keepStopped = { true }
            TimerService.engine.stop(id)
            rule.waitForIdle()
            rule.onNodeWithText("Gestoppt").assertExists()
            rule.onNodeWithText("abgebrochen", substring = true).performScrollTo().assertExists()
            rule.onNodeWithText("✓ In Toggl: Beispiel").performScrollTo().assertExists()
            assertEquals(listOf("GET me", "GET me/time_entries/current", "POST workspaces/7/time_entries", "PUT workspaces/7/time_entries/1"), calls)
            val posted = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications
                .map { it.extras.getString(android.app.Notification.EXTRA_TITLE) to it.extras.getString(android.app.Notification.EXTRA_TEXT) }
            assertTrue(posted.toString(), ("✓ In Toggl gespeichert" to "Beispiel · 0 s · abgebrochen") in posted)

            rule.onNodeWithText("Aus Toggl löschen").performScrollTo().performClick()
            rule.onNodeWithText("Nicht in Toggl", substring = true).performScrollTo().assertExists()
            assertEquals("DELETE workspaces/7/time_entries/1", calls.last())

            // Changed one's mind: back in, as it was.
            rule.onNodeWithText("Doch eintragen").performScrollTo().performClick()
            rule.onNodeWithText("✓ In Toggl: Beispiel").performScrollTo().assertExists()
            assertEquals("POST workspaces/7/time_entries", calls.last())
        }
    }

    @Test
    fun asksBeforeTheStart() {
        val ask = Session(
            name = "Üben", steps = listOf(Step(10 * 60)), toggl = true,
            togglAskDescription = true, togglAskProject = true,
        )
        sessionStore(context).save(listOf(ask))
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Start").performClick()
            rule.onNodeWithText("Üben – für Toggl").assertExists()
            rule.onNodeWithText("Ohne Toggl starten").assertExists()
            rule.onNodeWithText("Beschreibung").performTextReplacement("Tonleitern")
            rule.onNodeWithText("Starten").performClick()
            rule.onNodeWithText("Üben – für Toggl").assertDoesNotExist()
            // The service does not run in the test: started here as it would.
            TimerService.engine.start(ask)
            rule.onNodeWithText("● Toggl zählt mit: Tonleitern").performScrollTo().assertExists()
        }
    }

    @Test
    fun closingAnUnnamedRunAsksFirst() {
        val ask = Session(name = "Üben", steps = listOf(Step(10 * 60)), toggl = true, togglAskDescription = true, togglAskProject = true)
        val id = TimerService.engine.start(ask)
        ActivityScenario.launch(MainActivity::class.java).use {
            TimerService.engine.keepStopped = { true }
            TimerService.engine.stop(id)
            rule.waitForIdle()
            rule.onNodeWithText("Zurück").performScrollTo().performClick()
            rule.onNodeWithText("Ohne Toggl-Eintrag schließen?").assertExists()
            rule.onNodeWithText("Schließen").performClick()
            rule.onNodeWithText("Gestoppt").assertDoesNotExist()
            assertTrue(togglTracker(context).runs.value.getValue(id).deleted)
            assertTrue(calls.toString(), calls.none { it.startsWith("POST") })
            assertTrue("nothing owed to Toggl", settingsStore(context).togglPending().isEmpty())
        }
    }

    @Test
    fun editorOffersThreeWays() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.onNodeWithText("Beispiel").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Nicht erfassen"))
            assertEquals(false, sessionStore(context).load().single { it.name == "Beispiel" }.toggl)
            rule.onNodeWithText("Immer gleich").performClick()
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Ganze Session als ein Block"))
            rule.onNodeWithText("Wird automatisch erfasst als „Beispiel“.").assertExists()
            rule.onNodeWithText("Vor dem Start fragen").performClick()
            rule.onNodeWithText("Speichern").performClick()
            val saved = sessionStore(context).load().single { it.name == "Beispiel" }
            assertTrue(saved.toggl && saved.togglAsks)
            assertTrue("one block", saved.steps.all { it.track })
        }
    }
}
