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
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

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

private data class PracticeRequest(val seconds: Int, val title: String?)

/**
 * Receives practice timers from MetalRat on 127.0.0.1:[Practice.PORT] (POST /practice?seconds=&title=).
 * Requests from web pages carry an Origin header and are refused.
 */
private class PracticeServer {
    @Volatile var onPractice: (PracticeRequest) -> Unit = {}

    fun bind(): Boolean = try {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), Practice.PORT), 0)
        server.createContext("/practice") { exchange ->
            try {
                val params = queryParams(exchange.requestURI.rawQuery)
                val seconds = params["seconds"]?.toIntOrNull()
                val code = when {
                    exchange.requestHeaders.getFirst("Origin") != null -> 403
                    exchange.requestMethod != "POST" || seconds == null -> 400
                    else -> 204.also {
                        val request = PracticeRequest(seconds, params["title"])
                        SwingUtilities.invokeLater { onPractice(request) }
                    }
                }
                exchange.sendResponseHeaders(code, -1)
            } finally {
                exchange.close()
            }
        }
        server.start()
        true
    } catch (e: IOException) {
        false
    }

    companion object {
        fun queryParams(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

        /** Hands [request] to the Wassertiger that is already running. */
        fun forward(request: PracticeRequest): Boolean = runCatching {
            val query = "seconds=${request.seconds}" + (request.title?.let { "&title=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val conn = URI("http://127.0.0.1:${Practice.PORT}/practice?$query").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.responseCode.also { conn.disconnect() } == 204
        }.getOrDefault(false)
    }
}

private fun practiceFromArgs(args: Array<String>): PracticeRequest? {
    val seconds = args.indexOf(Practice.ARG_SECONDS).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        ?: return null
    val title = args.indexOf(Practice.ARG_TITLE).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    return PracticeRequest(seconds, title)
}

fun main(args: Array<String>) {
    val initialPractice = practiceFromArgs(args)
    val server = PracticeServer()
    // Port taken = Wassertiger already runs: pass the timer on instead of opening a second window.
    if (!server.bind() && initialPractice != null && PracticeServer.forward(initialPractice)) return
    runApp(server, initialPractice)
}

private fun runApp(server: PracticeServer, initialPractice: PracticeRequest?) = application {
    val store = remember { SessionStore(FileStorage) }
    val engine = remember { TimerEngine(MainScope()) }
    val stayAwake = remember { StayAwake() }
    val music = remember { MusicPlayer(LiveStream(::LineSink)) { DrumSamples.load(::bundledResource) } }
    var flash by remember { mutableStateOf<Flash?>(null) }
    val icon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")
            ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
    }

    LaunchedEffect(engine) {
        engine.listener = { event, state ->
            music.follow(state.takeIf { event != TimerEvent.Stopped })
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
        server.onPractice = { engine.start(Practice.session(it.seconds, it.title)) }
        initialPractice?.let { server.onPractice(it) }
    }

    Window(
        onCloseRequest = {
            music.stop()
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
                runHint = "Spotify & Co. laufen ungestört weiter – bei jedem Wechsel leuchtet\n" +
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
