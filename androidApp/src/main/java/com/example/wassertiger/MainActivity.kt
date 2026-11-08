package com.example.wassertiger

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.MainScope

fun sessionStore(context: Context): SessionStore {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    return SessionStore(object : SessionStorage {
        override fun read(): String? = prefs.getString("sessions_v1", null)
        override fun write(json: String) {
            prefs.edit().putString("sessions_v1", json).apply()
            // The home-screen widgets list the sessions, so they follow every edit.
            Widgets.update(app)
        }
    })
}

/** The Toggl token and what was booked last, beside the sessions. */
fun settingsStore(context: Context): SettingsStore {
    val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    return SettingsStore(object : SessionStorage {
        override fun read(): String? = prefs.getString("settings_v1", null)
        override fun write(json: String) = prefs.edit().putString("settings_v1", json).apply()
    })
}

/** Set by tests to talk to a stand-in for Toggl. */
internal var tracker: TogglTracker? = null

/**
 * Process-wide like [TimerService.engine]: the service feeds it, the screens show what it did. At
 * the end of a run a notification says it is saved in Toggl, or that it still waits for its name.
 */
fun togglTracker(context: Context): TogglTracker = tracker ?: TogglTracker(settingsStore(context), MainScope()).also {
    val app = context.applicationContext
    it.onReport = { run -> notifyToggl(app, run) }
    tracker = it
}

private const val CHANNEL_TOGGL = "toggl"

internal fun notifyToggl(context: Context, run: TogglRun) {
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
        android.app.NotificationChannel(CHANNEL_TOGGL, "Toggl", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        },
    )
    val time = formatDuration((run.millis() / 1000).toInt())
    val what = listOfNotNull(run.description.ifBlank { null }, run.project?.name, time, "abgebrochen".takeIf { run.aborted }).joinToString(" · ")
    val open = android.app.PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = android.app.Notification.Builder(context, CHANNEL_TOGGL)
        .setSmallIcon(R.drawable.ic_stat_timer)
        .setContentTitle(if (run.waiting) "Für Toggl noch eintragen" else "✓ In Toggl gespeichert")
        .setContentText(if (run.waiting) "${run.sessionName} · $time – tippen zum Eintragen" else what)
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    // One per run: "noch eintragen" turns into "gespeichert".
    nm.notify(1000 + (run.runId % 1000).toInt(), notification)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val store = sessionStore(this)
        val settings = settingsStore(this)
        TimerService.engine.keepStopped = keepForToggl(settings)
        setContent {
            val context = LocalContext.current
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            WassertigerTheme {
                WassertigerApp(
                    store = store,
                    engine = TimerService.engine,
                    settings = settings,
                    toggl = togglTracker(context),
                    onStart = { session ->
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        TimerService.start(context, session)
                    },
                    runHint = { session ->
                        "Du kannst das Handy sperren und Musik hören –\n" + session.signalText() +
                            "\nDie Power-Taste zeigt die Restzeit auch bei gesperrtem Handy."
                    },
                    onPreviewChime = TimerService.chimes::play,
                    header = { PermissionHints() },
                )
            }
        }
    }
}

/** Points out settings that would keep the screen from lighting up at step changes. */
@Composable
private fun PermissionHints() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableLongStateOf(0L) }
    // Re-check whenever the user comes back from the settings app.
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }

    val nm = context.getSystemService(NotificationManager::class.java)
    val notificationsOff = remember(tick) { !nm.areNotificationsEnabled() }
    val fullScreenOff = remember(tick) { Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() }
    if (!notificationsOff && !fullScreenOff) return

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4E342E))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Damit das Display bei jedem Wechsel kurz angeht, braucht die App diese Erlaubnis:",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (notificationsOff) {
                FilledTonalButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }) { Text("Benachrichtigungen erlauben") }
            }
            if (fullScreenOff && Build.VERSION.SDK_INT >= 34) {
                FilledTonalButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Vollbild-Hinweise erlauben") }
            }
        }
    }
}
