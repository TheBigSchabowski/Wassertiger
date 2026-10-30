package com.example.intervalltimer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.ceil

val Amber = Color(0xFFFFB300)
val DoneGreen = Color(0xFF43A047)

/** Lets Android route the system back gesture to the editor; a no-op on desktop. */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)

@Composable
fun IntervallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Color.Black)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * @param onStart starts [session] on the platform (Android goes through its foreground service).
 * @param runHint explains how the platform signals a step change.
 * @param header platform-specific notices above the session list.
 */
@Composable
fun IntervallApp(
    store: SessionStore,
    engine: TimerEngine,
    onStart: (Session) -> Unit,
    runHint: String,
    header: @Composable () -> Unit = {},
) {
    val run by engine.state.collectAsState()
    var sessions by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Session?>(null) }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        val current = run
        val edit = editing
        Box(Modifier.widthIn(max = 720.dp)) {
            when {
                current != null -> RunScreen(current, engine, runHint)
                edit != null -> EditorScreen(
                    initial = edit,
                    isNew = sessions.none { it.id == edit.id },
                    onSave = { saved ->
                        sessions = if (sessions.any { it.id == saved.id }) {
                            sessions.map { if (it.id == saved.id) saved else it }
                        } else {
                            sessions + saved
                        }
                        store.save(sessions)
                        editing = null
                    },
                    onDelete = {
                        sessions = sessions.filter { it.id != edit.id }
                        store.save(sessions)
                        editing = null
                    },
                    onCancel = { editing = null },
                )
                else -> ListScreen(
                    sessions = sessions,
                    header = header,
                    onStart = onStart,
                    onEdit = { editing = it },
                    onNew = { editing = Session(name = "Neue Session", steps = listOf(Step(30))) },
                )
            }
        }
    }
}

/** Full-bleed signal shown for a moment at every step change. */
@Composable
fun FlashContent(title: String, text: String, finished: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(if (finished) DoneGreen else Amber).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 96.sp, color = Color.Black, textAlign = TextAlign.Center)
        Text(text, fontSize = 28.sp, color = Color.Black, textAlign = TextAlign.Center)
    }
}

// --- list -------------------------------------------------------------------

@Composable
private fun ListScreen(
    sessions: List<Session>,
    header: @Composable () -> Unit,
    onStart: (Session) -> Unit,
    onEdit: (Session) -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Intervall",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item { header() }
        itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
            Card(
                Modifier.fillMaxWidth().clickable { onEdit(session) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${session.steps.size} Intervalle · ${formatDuration(session.totalSeconds)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            session.steps.joinToString("  ") { formatDuration(it.seconds) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    Button(onClick = { onStart(session) }) { Text("Start") }
                }
            }
        }
        item {
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text("+ Neue Session")
            }
        }
    }
}

// --- editor -----------------------------------------------------------------

private class EditRow(val key: Long, seconds: String, name: String) {
    var seconds by mutableStateOf(seconds)
    var name by mutableStateOf(name)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorScreen(
    initial: Session,
    isNew: Boolean,
    onSave: (Session) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    PlatformBackHandler(onBack = onCancel)
    var name by remember { mutableStateOf(initial.name) }
    var nextKey by remember { mutableLongStateOf(initial.steps.size.toLong()) }
    val rows = remember {
        mutableStateListOf<EditRow>().apply {
            initial.steps.forEachIndexed { i, s -> add(EditRow(i.toLong(), s.seconds.toString(), s.name)) }
        }
    }
    fun addRow(seconds: Int, name: String = "") {
        rows.add(EditRow(nextKey++, seconds.toString(), name))
    }

    val parsed = rows.map { parseDuration(it.seconds) }
    val valid = rows.isNotEmpty() && parsed.all { it != null } && name.isNotBlank()

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name der Session") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", Modifier.width(28.dp))
                    OutlinedTextField(
                        value = row.seconds,
                        onValueChange = { row.seconds = it },
                        label = { Text("Sek.") },
                        isError = parsed[i] == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(84.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = row.name,
                        onValueChange = { row.name = it },
                        label = { Text("Übung (optional)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Column {
                        TextButton(
                            onClick = { rows.add(i - 1, rows.removeAt(i)) },
                            enabled = i > 0,
                        ) { Text("↑") }
                        TextButton(onClick = { rows.removeAt(i) }) { Text("✕") }
                    }
                }
            }
            item {
                Text("Intervall hinzufügen", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 20, 30, 45, 60, 90, 120).forEach { secs ->
                        FilledTonalButton(onClick = { addRow(secs) }) { Text(formatDuration(secs)) }
                    }
                    OutlinedButton(
                        onClick = { parsed.lastOrNull()?.let { addRow(it, rows.last().name) } },
                        enabled = parsed.lastOrNull() != null,
                    ) { Text("Letztes wiederholen") }
                }
                Text(
                    "Gesamt: ${formatDuration(parsed.filterNotNull().sum())} · Eingabe als Sekunden oder m:ss",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!isNew) {
                TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            steps = rows.mapIndexed { i, r -> Step(parsed[i]!!, r.name.trim()) },
                        )
                    )
                },
            ) { Text("Speichern") }
        }
    }
}

// --- run --------------------------------------------------------------------

@Composable
private fun RunScreen(run: RunState, engine: TimerEngine, hint: String) {
    var now by remember { mutableLongStateOf(monotonicMillis()) }
    LaunchedEffect(run) {
        while (true) {
            now = monotonicMillis()
            delay(100)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(run.session.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(32.dp))

        if (run.finished) {
            Text("Fertig!", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Amber)
            Spacer(Modifier.height(48.dp))
            Button(onClick = engine::dismissFinished) { Text("Zurück") }
            return@Column
        }

        val step = run.step ?: return@Column
        val remaining = ceil(run.remainingMs(now) / 1000.0).toInt()
        Text("Intervall ${run.index + 1} / ${run.session.steps.size}", style = MaterialTheme.typography.titleMedium)
        if (step.name.isNotBlank()) {
            Text(step.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Text(
            formatDuration(remaining).removeSuffix(" s"),
            fontSize = 120.sp,
            fontWeight = FontWeight.Bold,
            color = if (run.paused) MaterialTheme.colorScheme.onSurfaceVariant else Amber,
        )
        val next = run.nextStep
        Text(
            if (next == null) "Danach: fertig"
            else "Danach: ${formatDuration(next.seconds)}" + if (next.name.isNotBlank()) " · ${next.name}" else "",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = engine::stop) { Text("Stopp") }
            Button(onClick = engine::togglePause) { Text(if (run.paused) "Weiter" else "Pause") }
            OutlinedButton(onClick = engine::skip) { Text("Überspringen") }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
