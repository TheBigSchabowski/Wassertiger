package com.example.wassertiger

import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.TextView
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The home-screen widgets render the sessions and follow the running timer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetTest {
    private val context = RuntimeEnvironment.getApplication()
    private val widgets = shadowOf(AppWidgetManager.getInstance(context))

    @After
    fun stopRun() = TimerService.engine.stopAll()

    @Test
    fun tileShowsItsSessionAndCountsDownWhileRunning() {
        val session = Widgets.startable(context).single { it.name == "Beispiel" }
        val id = widgets.createWidget(SessionWidget::class.java, R.layout.widget_session)
        Widgets.setTileSession(context, id, session.id)
        Widgets.update(context)
        val tile = widgets.getViewFor(id)
        assertEquals("Beispiel", tile.findViewById<TextView>(R.id.tile_name).text)
        assertEquals("6 Intervalle · 3:18", tile.findViewById<TextView>(R.id.tile_summary).text)
        assertEquals(View.GONE, tile.findViewById<View>(R.id.tile_clock).visibility)

        TimerService.engine.start(session)
        Widgets.update(context)
        val running = widgets.getViewFor(id)
        assertEquals("1 / 6", running.findViewById<TextView>(R.id.tile_summary).text)
        assertEquals(View.VISIBLE, running.findViewById<View>(R.id.tile_clock).visibility)
    }

    @Test
    fun listShowsControlsOnlyWhileRunning() {
        val id = widgets.createWidget(SessionsWidget::class.java, R.layout.widget_sessions)
        Widgets.update(context)
        assertEquals(View.GONE, widgets.getViewFor(id).findViewById<View>(R.id.run).visibility)

        val run = TimerService.engine.start(Widgets.startable(context).first())
        TimerService.engine.togglePause(run)
        Widgets.update(context)
        val view = widgets.getViewFor(id)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.run).visibility)
        assertEquals("Pausiert – Beispiel", view.findViewById<TextView>(R.id.run_name).text)
        assertEquals("Weiter", view.findViewById<TextView>(R.id.run_pause).text)
    }

    @Test
    fun savingSessionsRedrawsTheTile() {
        val id = widgets.createWidget(SessionWidget::class.java, R.layout.widget_session)
        val store = sessionStore(context)
        val session = store.load().single()
        Widgets.setTileSession(context, id, session.id)
        store.save(listOf(session.copy(name = "Umbenannt")))
        assertEquals("Umbenannt", widgets.getViewFor(id).findViewById<TextView>(R.id.tile_name).text)
    }
}
