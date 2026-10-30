package com.example.wassertiger

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import java.io.File
import javax.imageio.ImageIO

private val isMac = System.getProperty("os.name").startsWith("Mac")

private fun dataFile(): File {
    val home = System.getProperty("user.home")
    val dir = if (isMac) {
        File(home, "Library/Application Support/Wassertiger")
    } else {
        File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "wassertiger")
    }
    return File(dir, "sessions.json")
}

private object FileStorage : SessionStorage {
    override fun read(): String? = dataFile().takeIf { it.exists() }?.readText()
    override fun write(json: String) {
        val file = dataFile()
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }
}

/**
 * Keeps the machine and its display awake while a session runs, so the timer does not stall
 * in system sleep and the flash is visible. The helper exits on its own if this app dies.
 */
private class StayAwake {
    private var process: Process? = null

    fun start() {
        stop()
        val pid = ProcessHandle.current().pid().toString()
        val command = if (isMac) {
            listOf("caffeinate", "-d", "-i", "-w", pid)
        } else {
            listOf(
                "systemd-inhibit", "--what=idle:sleep", "--who=Wassertiger", "--why=Wassertiger-Session läuft",
                "tail", "--pid=$pid", "-f", "/dev/null",
            )
        }
        process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrNull()
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}

private data class Flash(val title: String, val text: String, val finished: Boolean)

fun main() = application {
    val store = remember { SessionStore(FileStorage) }
    val engine = remember { TimerEngine(MainScope()) }
    val stayAwake = remember { StayAwake() }
    var flash by remember { mutableStateOf<Flash?>(null) }
    val icon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }

    LaunchedEffect(engine) {
        engine.listener = { event, state ->
            when (event) {
                TimerEvent.Started -> stayAwake.start()
                TimerEvent.Step -> state.flashText().let { (t, s) -> flash = Flash(t, s, false) }
                TimerEvent.Finished -> {
                    state.flashText().let { (t, s) -> flash = Flash(t, s, true) }
                    stayAwake.stop()
                }
                TimerEvent.Stopped -> stayAwake.stop()
                TimerEvent.PauseChanged -> {}
            }
        }
    }

    Window(
        onCloseRequest = {
            stayAwake.stop()
            exitApplication()
        },
        title = "Wassertiger",
        icon = icon,
        state = rememberWindowState(size = DpSize(560.dp, 820.dp)),
    ) {
        WassertigerTheme {
            WassertigerApp(
                store = store,
                engine = engine,
                onStart = engine::start,
                runHint = "Musik läuft ungestört weiter – bei jedem Wechsel leuchtet\n" +
                    "ein Hinweis über allen Fenstern auf. Der Bildschirm bleibt an.",
            )
        }
    }

    // Always-on-top signal that never takes keyboard focus, so whatever you are doing keeps going.
    flash?.let { f ->
        Window(
            onCloseRequest = { flash = null },
            title = "Wassertiger",
            undecorated = true,
            alwaysOnTop = true,
            focusable = false,
            resizable = false,
            state = rememberWindowState(position = WindowPosition(Alignment.Center), size = DpSize(560.dp, 340.dp)),
        ) {
            LaunchedEffect(f) {
                delay(2500)
                flash = null
            }
            FlashContent(f.title, f.text, f.finished)
        }
    }
}
