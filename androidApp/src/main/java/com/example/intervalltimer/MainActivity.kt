package com.example.intervalltimer

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

fun sessionStore(context: Context): SessionStore {
    val prefs = context.getSharedPreferences("sessions", Context.MODE_PRIVATE)
    return SessionStore(object : SessionStorage {
        override fun read(): String? = prefs.getString("sessions_v1", null)
        override fun write(json: String) = prefs.edit().putString("sessions_v1", json).apply()
    })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val store = sessionStore(this)
        setContent {
            val context = LocalContext.current
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            IntervallTheme {
                IntervallApp(
                    store = store,
                    engine = TimerService.engine,
                    onStart = { session ->
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        TimerService.start(context, session)
                    },
                    runHint = "Du kannst das Handy sperren und Musik hören –\n" +
                        "bei jedem Wechsel vibriert es und das Display geht kurz an.",
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
