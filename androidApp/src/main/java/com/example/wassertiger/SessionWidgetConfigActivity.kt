package com.example.wassertiger

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Opens when a [SessionWidget] is placed (or reconfigured): which session should the tile start? */
class SessionWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Backing out without a choice must not leave an empty tile behind.
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val sessions = Widgets.startable(this)
        val current = Widgets.tileSession(this, widgetId)
        setContent {
            WassertigerTheme {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text("Welche Session soll das Widget starten?", style = MaterialTheme.typography.headlineSmall)
                        }
                        if (sessions.isEmpty()) {
                            item { Text("Noch keine Sessions – lege zuerst eine in der App an.") }
                        }
                        items(sessions, key = { it.id }) { session ->
                            Card(
                                Modifier.fillMaxWidth().clickable {
                                    Widgets.setTileSession(this@SessionWidgetConfigActivity, widgetId, session.id)
                                    Widgets.update(this@SessionWidgetConfigActivity)
                                    setResult(RESULT_OK, result)
                                    finish()
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (session.id == current) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        (if (session.music) "♪ " else "") + session.name,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(session.summary(), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
