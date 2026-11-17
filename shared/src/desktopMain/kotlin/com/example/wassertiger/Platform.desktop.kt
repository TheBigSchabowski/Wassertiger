package com.example.wassertiger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities

actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

actual fun wallClockMillis(): Long = System.currentTimeMillis()

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
}

@Composable
actual fun rememberJsonFileActions(
    onImported: (List<String>) -> Unit,
    onStatus: (String) -> Unit,
): JsonFileActions {
    val latestImported = rememberUpdatedState(onImported)
    val latestStatus = rememberUpdatedState(onStatus)
    return remember {
        JsonFileActions(
            canPickFiles = !GraphicsEnvironment.isHeadless(),
            exportJson = { defaultFileName, content ->
                if (GraphicsEnvironment.isHeadless()) {
                    latestStatus.value("Dateidialog im Headless-Modus nicht verfügbar")
                } else {
                    Thread({
                        runCatching {
                            val dialog = FileDialog(null as Frame?, "Kalender als JSON exportieren", FileDialog.SAVE).apply {
                                file = defaultFileName
                                isVisible = true
                            }
                            val dir = dialog.directory
                            val fileName = dialog.file
                            if (dir != null && fileName != null) {
                                val outFile = File(dir, if (fileName.endsWith(".json", ignoreCase = true)) fileName else "$fileName.json")
                                outFile.writeText(content, Charsets.UTF_8)
                                SwingUtilities.invokeLater {
                                    latestStatus.value("✓ Exportiert: ${outFile.name}")
                                }
                            }
                        }.onFailure { err ->
                            SwingUtilities.invokeLater {
                                latestStatus.value("Export fehlgeschlagen: ${err.message ?: "Unbekannter Fehler"}")
                            }
                        }
                    }, "wassertiger-json-export").apply { isDaemon = true }.start()
                }
            },
            importJsons = {
                if (GraphicsEnvironment.isHeadless()) {
                    latestStatus.value("Dateidialog im Headless-Modus nicht verfügbar")
                } else {
                    Thread({
                        runCatching {
                            val dialog = FileDialog(null as Frame?, "JSON-Dateien zum Zusammenfügen wählen", FileDialog.LOAD).apply {
                                isMultipleMode = true
                                setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
                                isVisible = true
                            }
                            val files = dialog.files.orEmpty()
                            if (files.isNotEmpty()) {
                                val contents = files.mapNotNull { file ->
                                    runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                                }
                                SwingUtilities.invokeLater {
                                    latestImported.value(contents)
                                }
                            }
                        }.onFailure { err ->
                            SwingUtilities.invokeLater {
                                latestStatus.value("Import fehlgeschlagen: ${err.message ?: "Unbekannter Fehler"}")
                            }
                        }
                    }, "wassertiger-json-import").apply { isDaemon = true }.start()
                }
            },
        )
    }
}
