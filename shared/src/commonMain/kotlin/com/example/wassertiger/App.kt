package com.example.wassertiger

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

val Amber = Color(0xFFFFB300)
val DoneGreen = Color(0xFF43A047)

/** Lets Android route the system back gesture to the editor; a no-op on desktop. */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)

@Composable
fun WassertigerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, onPrimary = Color.Black)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Two pages side by side: the silent timer and, one swipe (or → key) to the right, music sessions
 * with drone and drums.
 *
 * @param onStart starts [session] on the platform (Android goes through its foreground service).
 * @param runHint explains how the platform signals a step change.
 * @param header platform-specific notices above the session list.
 */
@Composable
fun WassertigerApp(
    store: SessionStore,
    engine: TimerEngine,
    onStart: (Session) -> Unit,
    runHint: String,
    header: @Composable () -> Unit = {},
) {
    val run by engine.state.collectAsState()
    var sessions by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Session?>(null) }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val onList = run == null && editing == null
    // Key events need a focused node; the lists have no text fields, so the whole page takes focus.
    LaunchedEffect(onList) { if (onList) runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().safeDrawingPadding()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val page = when {
                    !onList || e.type != KeyEventType.KeyDown -> null
                    e.key == Key.DirectionRight -> 1
                    e.key == Key.DirectionLeft -> 0
                    else -> null
                }
                if (page != null) scope.launch { pager.animateScrollToPage(page) }
                page != null
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val current = run
        val edit = editing
        Box(Modifier.widthIn(max = 720.dp)) {
            when {
                current != null -> RunScreen(current, engine, if (current.session.music) MUSIC_HINT else runHint)
                edit != null -> EditorScreen(
                    initial = edit,
                    isNew = sessions.none { it.id == edit.id },
                    onSaveAsTemplate = { template ->
                        sessions = sessions + template
                        store.save(sessions)
                    },
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
                else -> Column {
                    Text(
                        "Wassertiger",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                        listOf("Timer", "Musik").forEachIndexed { i, title ->
                            Tab(
                                selected = pager.currentPage == i,
                                onClick = { scope.launch { pager.animateScrollToPage(i) } },
                                text = { Text(title) },
                            )
                        }
                    }
                    HorizontalPager(pager, Modifier.weight(1f)) { page ->
                        val music = page == 1
                        ListScreen(
                            music = music,
                            sessions = sessions.filter { !it.template && it.music == music },
                            templates = sessions.filter { it.template && it.music == music },
                            header = header,
                            onStart = onStart,
                            onEdit = { editing = it },
                            onNew = {
                                editing = it?.newSession() ?: if (music) {
                                    Session(name = "Neue Übung", music = true, steps = listOf(Step(5 * 60, drone = 9)))
                                } else {
                                    Session(name = "Neue Session", steps = listOf(Step(30)))
                                }
                            },
                        )
                    }
                }
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
        // Music flashes the word to play ("Mixolydisch"), which needs room.
        Text(title, fontSize = if (title.length > 6) 56.sp else 96.sp, color = Color.Black, textAlign = TextAlign.Center)
        Text(text, fontSize = 28.sp, color = Color.Black, textAlign = TextAlign.Center)
    }
}

// --- list -------------------------------------------------------------------

/** [onNew] gets a preset or template to start from, or null for an empty session. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListScreen(
    music: Boolean,
    sessions: List<Session>,
    templates: List<Session>,
    header: @Composable () -> Unit,
    onStart: (Session) -> Unit,
    onEdit: (Session) -> Unit,
    onNew: (Session?) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { header() }
        if (music && sessions.isEmpty()) {
            item {
                Text(
                    "Jedes Intervall spielt eine Drone, Drums, beides oder nichts – und zeigt groß, was du " +
                        "darüber spielst (z. B. „Phrygisch“ über der A-Drone).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(sessions, key = { _, s -> s.id }) { _, session ->
            SessionCard(session, onEdit) {
                Button(onClick = { onStart(session) }) { Text("Start") }
            }
        }
        item {
            OutlinedButton(onClick = { onNew(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (music) "+ Neue Übung" else "+ Neue Session")
            }
        }
        item {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Aus Vorlage", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (music) musicPresets() else presets()).forEach { preset ->
                        FilledTonalButton(onClick = { onNew(preset) }) { Text(preset.name) }
                    }
                }
                if (templates.isEmpty()) {
                    Text(
                        "Eigene Vorlagen: im Editor „Als Vorlage speichern“.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (templates.isNotEmpty()) {
            item { Text("Meine Vorlagen", style = MaterialTheme.typography.labelLarge) }
            itemsIndexed(templates, key = { _, s -> s.id }) { _, template ->
                SessionCard(template, onEdit) {
                    FilledTonalButton(onClick = { onNew(template) }) { Text("Neu daraus") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Tapping the card edits [session]; [action] sits on the right. */
@Composable
private fun SessionCard(session: Session, onEdit: (Session) -> Unit, action: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onEdit(session) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleLarge)
                Text(session.summary(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    session.steps.joinToString("  ") { step ->
                        if (session.music) "${step.name.ifBlank { step.soundLabel() }} ${formatDuration(step.seconds)}"
                        else formatDuration(step.seconds)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            action()
        }
    }
}

// --- editor -----------------------------------------------------------------

private class EditRow(val key: Long, seconds: String, name: String, drone: Int? = null, groove: String? = null, bpm: Int = 90) {
    var seconds by mutableStateOf(seconds)
    var name by mutableStateOf(name)
    var drone by mutableStateOf(drone)
    var groove by mutableStateOf(groove)
    var bpm by mutableStateOf(bpm.toString())

    /** The tempo, null when out of range; only asked for with drums. */
    val parsedBpm: Int? get() = bpm.trim().toIntOrNull()?.takeIf { it in MusicSettings.MIN_BPM..MusicSettings.MAX_BPM }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorScreen(
    initial: Session,
    isNew: Boolean,
    onSaveAsTemplate: (Session) -> Unit,
    onSave: (Session) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    PlatformBackHandler(onBack = onCancel)
    var name by remember { mutableStateOf(initial.name) }
    var endless by remember { mutableStateOf(initial.endless) }
    var roundsText by remember { mutableStateOf(if (initial.endless) "1" else initial.rounds.toString()) }
    var sound by remember { mutableStateOf(initial.sound) }
    var fifth by remember { mutableStateOf(initial.fifth) }
    var kit by remember { mutableStateOf(initial.kit) }
    val music = initial.music
    // Music intervals run for minutes, so they are typed in minutes.
    fun durationText(seconds: Int) = if (music) formatMinutes(seconds) else seconds.toString()
    var nextKey by remember { mutableLongStateOf(initial.steps.size.toLong()) }
    val rows = remember {
        mutableStateListOf<EditRow>().apply {
            initial.steps.forEachIndexed { i, s ->
                add(EditRow(i.toLong(), durationText(s.seconds), s.name, s.drone, s.groove, s.bpm))
            }
        }
    }
    /** A new interval keeps the sound of the last one. */
    fun addRow(seconds: Int, name: String = "") {
        val last = rows.lastOrNull()
        rows.add(EditRow(nextKey++, durationText(seconds), name, last?.drone, last?.groove, last?.parsedBpm ?: 90))
    }

    val parsed = rows.map { if (music) parseMinutes(it.seconds) else parseDuration(it.seconds) }
    val rounds = if (endless) ENDLESS else roundsText.trim().toIntOrNull()?.takeIf { it in 1..999 }
    val valid = rows.isNotEmpty() && parsed.all { it != null } && name.isNotBlank() && rounds != null &&
        rows.all { it.groove == null || it.parsedBpm != null }
    fun edited() = initial.copy(
        name = name.trim(),
        steps = rows.mapIndexed { i, r ->
            if (music) Step(parsed[i]!!, r.name.trim(), r.drone, r.groove, r.parsedBpm ?: 90) else Step(parsed[i]!!, r.name.trim())
        },
        rounds = rounds!!,
        sound = sound,
        fifth = fifth,
        kit = kit,
    )
    var templateSaved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (initial.template) "Name der Vorlage" else "Name der Session") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
            itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(28.dp))
                        OutlinedTextField(
                            value = row.seconds,
                            onValueChange = { row.seconds = it },
                            label = { Text(if (music) "Min." else "Sek.") },
                            isError = parsed[i] == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(84.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = row.name,
                            onValueChange = { row.name = it },
                            label = { Text(if (music) "Was du spielst, z. B. Phrygisch" else "Übung (optional)") },
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
                    if (music) StepSound(row)
                }
            }
            item {
                Text("Intervall hinzufügen", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (music) listOf(60, 120, 180, 300, 600, 1200) else listOf(10, 15, 20, 30, 45, 60, 90, 120)).forEach { secs ->
                        FilledTonalButton(onClick = { addRow(secs) }) { Text(if (music) "${secs / 60} min" else formatDuration(secs)) }
                    }
                    OutlinedButton(
                        onClick = { parsed.lastOrNull()?.let { addRow(it, rows.last().name) } },
                        enabled = parsed.lastOrNull() != null,
                    ) { Text("Letztes wiederholen") }
                }
            }
            if (music) {
                item {
                    Text("Klang der Drone", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DroneSound.entries.forEach { s ->
                            FilterChip(selected = sound == s, onClick = { sound = s }, label = { Text(s.label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = fifth, onCheckedChange = { fifth = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Quinte dazu (aus für Lokrisch & Co.)")
                    }
                    Text("Drumset", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DrumKit.entries.forEach { k ->
                            FilterChip(selected = kit == k, onClick = { kit = k }, label = { Text(k.label) })
                        }
                    }
                }
            }
            item {
                Text("Wiederholen", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = roundsText,
                        onValueChange = { roundsText = it },
                        label = { Text("Runden") },
                        isError = !endless && rounds == null,
                        enabled = !endless,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(100.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = endless, onCheckedChange = { endless = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Endlos, bis Stopp")
                }
                val round = parsed.filterNotNull().sum()
                Text(
                    when {
                        endless -> "Eine Runde: ${formatDuration(round)}, wiederholt bis Stopp"
                        rounds != null && rounds > 1 -> "Gesamt: ${formatDuration(round * rounds)} ($rounds × ${formatDuration(round)})"
                        else -> "Gesamt: ${formatDuration(round)}"
                    } + if (music) " · Eingabe in Minuten oder m:ss" else " · Eingabe als Sekunden oder m:ss",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (!initial.template) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = valid,
                            onClick = {
                                onSaveAsTemplate(edited().asTemplate())
                                templateSaved = true
                            },
                        ) { Text("Als Vorlage speichern") }
                        if (templateSaved) Text("  ✓ gespeichert", color = MaterialTheme.colorScheme.primary)
                    }
                }
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
            Button(enabled = valid, onClick = { onSave(edited()) }) { Text("Speichern") }
        }
    }
}

/** Drone, drums and tempo of one music interval. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepSound(row: EditRow) {
    FlowRow(
        Modifier.padding(start = 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Picker("Drone", row.drone, listOf(null) + (0 until 12), { it?.let { NOTE_NAMES[it] } ?: "aus" }) { row.drone = it }
        Picker(
            "Drums", row.groove, listOf(null) + Grooves.all.map { it.name },
            { g -> g?.let { "$it (${Grooves.named(it).meter.label})" } ?: "aus" },
        ) { row.groove = it }
        if (row.groove != null) {
            OutlinedTextField(
                value = row.bpm,
                onValueChange = { row.bpm = it },
                label = { Text("BPM") },
                isError = row.parsedBpm == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(84.dp),
            )
        }
    }
}

/** A button showing "[label]: value" that opens a menu of [options]. */
@Composable
private fun <T> Picker(label: String, value: T, options: List<T>, text: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: ${text(value)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = {
                    onPick(option)
                    open = false
                })
            }
        }
    }
}

// --- run --------------------------------------------------------------------

private const val MUSIC_HINT = "Bei jedem Wechsel blendet der Ton weich über und es kommt das gewohnte Signal."

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
        Text(
            listOf("Intervall ${run.index + 1} / ${run.session.steps.size}", run.roundLabel)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        val music = run.session.music
        if (music) {
            // What to play is the headline; the clock steps back.
            if (step.name.isNotBlank()) {
                Text(step.name, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Amber, textAlign = TextAlign.Center)
            }
            Text(step.soundLabel(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        } else if (step.name.isNotBlank()) {
            Text(step.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Text(
            formatDuration(remaining).removeSuffix(" s"),
            fontSize = if (music && step.name.isNotBlank()) 72.sp else 120.sp,
            fontWeight = FontWeight.Bold,
            color = if (run.paused) MaterialTheme.colorScheme.onSurfaceVariant else Amber,
        )
        val next = run.nextStep
        Text(
            when {
                next == null -> "Danach: fertig"
                music -> "Danach: ${next.name.ifBlank { next.soundLabel() }} · ${formatDuration(next.seconds)}"
                else -> "Danach: ${formatDuration(next.seconds)}" + if (next.name.isNotBlank()) " · ${next.name}" else ""
            },
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
