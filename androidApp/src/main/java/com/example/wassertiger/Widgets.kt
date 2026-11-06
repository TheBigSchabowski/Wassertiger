package com.example.wassertiger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat

/**
 * Home-screen widgets: [SessionsWidget] lists every session, [SessionWidget] is a tile for one.
 * A tap starts the session straight away through [TimerService], without opening the app.
 * They redraw whenever the sessions are saved or the timer changes step, pauses or stops.
 */
object Widgets {

    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val list = manager.getAppWidgetIds(ComponentName(context, SessionsWidget::class.java))
        val tiles = manager.getAppWidgetIds(ComponentName(context, SessionWidget::class.java))
        if (list.isEmpty() && tiles.isEmpty()) return
        val sessions = startable(context)
        list.forEach { manager.updateAppWidget(it, sessionsViews(context, it, sessions)) }
        tiles.forEach { id ->
            val session = tileSession(context, id)?.let { sid -> sessions.firstOrNull { it.id == sid } }
            manager.updateAppWidget(id, tileViews(context, session))
        }
    }

    /** What the app's lists show: own sessions, timer first, then music; no templates. */
    fun startable(context: Context): List<Session> =
        sessionStore(context).load().filter { !it.template }.sortedBy { it.music }

    /** The session a [SessionWidget] starts, picked in [SessionWidgetConfigActivity]. */
    fun tileSession(context: Context, widgetId: Int): String? = tilePrefs(context).getString(key(widgetId), null)

    fun setTileSession(context: Context, widgetId: Int, sessionId: String?) = tilePrefs(context).edit().apply {
        if (sessionId == null) remove(key(widgetId)) else putString(key(widgetId), sessionId)
    }.apply()

    private fun tilePrefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private fun key(widgetId: Int) = "tile_$widgetId"

    /** The runs going on now, oldest first; a finished one waiting for "Zurück" no longer counts. */
    private fun active(): List<RunState> = TimerService.engine.active.filter { it.step != null }

    /** "♪ " in front marks a music session, as both live side by side in the widget. */
    private fun title(session: Session) = if (session.music) "♪ ${session.name}" else session.name

    private fun sessionsViews(context: Context, widgetId: Int, sessions: List<Session>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sessions)
        views.setOnClickPendingIntent(R.id.title, openApp(context))

        // With sessions in parallel the oldest has the controls; the name says how many more run.
        val running = active()
        val run = running.firstOrNull()
        views.setViewVisibility(R.id.run, if (run != null) View.VISIBLE else View.GONE)
        if (run != null) {
            views.setOnClickPendingIntent(R.id.run, openApp(context))
            val more = if (running.size > 1) " (+${running.size - 1})" else ""
            views.setTextViewText(R.id.run_name, (if (run.paused) "Pausiert – ${title(run.session)}" else title(run.session)) + more)
            views.setTextViewText(R.id.run_step, stepLabel(run))
            showClock(views, R.id.run_clock, run)
            views.setTextViewText(R.id.run_pause, context.getString(if (run.paused) R.string.widget_resume else R.string.widget_pause))
            views.setOnClickPendingIntent(R.id.run_pause, TimerService.actionPendingIntent(context, TimerService.ACTION_TOGGLE_PAUSE, run.id))
            views.setOnClickPendingIntent(R.id.run_skip, TimerService.actionPendingIntent(context, TimerService.ACTION_SKIP, run.id))
            views.setOnClickPendingIntent(R.id.run_stop, TimerService.actionPendingIntent(context, TimerService.ACTION_STOP, run.id))
        }

        views.setViewVisibility(R.id.empty, if (sessions.isEmpty()) View.VISIBLE else View.GONE)
        views.setOnClickPendingIntent(R.id.empty, openApp(context))
        // One mutable template for the list; each row fills in its session id.
        views.setPendingIntentTemplate(
            R.id.list,
            PendingIntent.getForegroundService(
                context, 0, TimerService.startIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )
        val items = RemoteViewsCompat.RemoteCollectionItems.Builder().setHasStableIds(true)
        sessions.forEach { session ->
            val row = RemoteViews(context.packageName, R.layout.widget_session_item)
            row.setTextViewText(R.id.item_name, title(session))
            row.setTextViewText(R.id.item_summary, session.summary())
            row.setOnClickFillInIntent(R.id.item, Intent().putExtra(TimerService.EXTRA_SESSION_ID, session.id))
            items.addItem(session.id.hashCode().toLong(), row)
        }
        RemoteViewsCompat.setRemoteAdapter(context, views, widgetId, R.id.list, items.build())
        return views
    }

    private fun tileViews(context: Context, session: Session?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_session)
        if (session == null) {
            views.setTextViewText(R.id.tile_name, context.getString(R.string.app_name))
            views.setTextViewText(R.id.tile_summary, "Session nicht gefunden – Widget neu einrichten")
            views.setViewVisibility(R.id.tile_clock, View.GONE)
            views.setViewVisibility(R.id.tile_play, View.GONE)
            views.setOnClickPendingIntent(android.R.id.background, openApp(context))
            return views
        }
        views.setTextViewText(R.id.tile_name, title(session))
        val run = active().firstOrNull { it.session.id == session.id }
        if (run != null) {
            // Running: the countdown takes the summary's place and a tap opens the run screen.
            views.setTextViewText(R.id.tile_summary, listOfNotNull("Pausiert".takeIf { run.paused }, stepLabel(run).ifBlank { null }).joinToString(" · "))
            views.setViewVisibility(R.id.tile_clock, View.VISIBLE)
            showClock(views, R.id.tile_clock, run)
            views.setViewVisibility(R.id.tile_play, View.GONE)
            views.setOnClickPendingIntent(android.R.id.background, openApp(context))
        } else {
            views.setTextViewText(R.id.tile_summary, session.summary())
            views.setViewVisibility(R.id.tile_clock, View.GONE)
            views.setViewVisibility(R.id.tile_play, View.VISIBLE)
            views.setOnClickPendingIntent(android.R.id.background, TimerService.startPendingIntent(context, session.id))
        }
        return views
    }

    /** "Einatmen · 1 / 2 · Runde 3 / 30"; music shows what to play and the sound. */
    private fun stepLabel(run: RunState): String {
        val step = run.step ?: return ""
        val what = if (run.session.music) step.name.ifBlank { step.soundLabel() } else step.name
        val position = if (run.session.steps.size > 1) "${run.index + 1} / ${run.session.steps.size}" else ""
        return listOf(what, position, run.roundLabel).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * Counts the current interval down on the launcher's own clock, so the widget needs no
     * redraw every second. Both sides tick on [SystemClock.elapsedRealtime] ([monotonicMillis]).
     */
    private fun showClock(views: RemoteViews, id: Int, run: RunState) {
        // Chronometer rounds down; +999 makes it show whole seconds left like the app does.
        val base = SystemClock.elapsedRealtime() + run.remainingMs() + 999
        views.setChronometer(id, base, null, !run.paused)
        views.setChronometerCountDown(id, true)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** All sessions to start with one tap. */
class SessionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) =
        Widgets.update(context)
}

/** One session as a tile; which one is chosen when it is placed. */
class SessionWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.update(context)

    override fun onDeleted(context: Context, ids: IntArray) = ids.forEach { Widgets.setTileSession(context, it, null) }
}
