package com.example.wassertiger

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

actual fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

actual fun wallClockMillis(): Long = System.currentTimeMillis()

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = BackHandler(onBack = onBack)

@Composable
actual fun rememberJsonFileActions(
    onImported: (List<String>) -> Unit,
    onStatus: (String) -> Unit,
): JsonFileActions {
    val context = LocalContext.current
    val latestImported = rememberUpdatedState(onImported)
    val latestStatus = rememberUpdatedState(onStatus)
    var pendingExportContent by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingExportContent
        pendingExportContent = null
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                } ?: error("Konnte Ziel-Datei nicht öffnen")
            }.onSuccess {
                latestStatus.value("✓ Kalender als JSON gespeichert")
            }.onFailure { err ->
                latestStatus.value("Export fehlgeschlagen: ${err.message ?: "Fehler"}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val contents = uris.mapNotNull { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            }
            if (contents.isNotEmpty()) {
                latestImported.value(contents)
            } else {
                latestStatus.value("Keine lesbaren JSON-Dateien ausgewählt")
            }
        }
    }

    return remember(exportLauncher, importLauncher) {
        JsonFileActions(
            canPickFiles = true,
            exportJson = { defaultFileName, content ->
                pendingExportContent = content
                exportLauncher.launch(defaultFileName)
            },
            importJsons = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
        )
    }
}
