package com.example.wassertiger

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val Amber = Color(0xFFFFB300)
const val DONE_GREEN_RGB = 0x43A047
val DoneGreen = Color(0xFF000000 or DONE_GREEN_RGB.toLong())

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
 * While sessions run, their countdowns fill the screen; "+ Parallel" goes back to the list to start
 * one more beside them.
 *
 * @param settings holds the Toggl token; with it, [toggl] follows the runs in Toggl Track.
 * @param onStart starts [session] on the platform (Android goes through its foreground service).
 * @param runHint explains how the platform signals a step change of the session.
 * @param onPreviewChime plays a chime when it is picked in the editor (finish: the end-of-run triad).
 * @param header platform-specific notices above the session list.
 */
@Composable
fun WassertigerApp(
    store: SessionStore,
    engine: TimerEngine,
    settings: SettingsStore,
    toggl: TogglTracker,
    onStart: (Session) -> Unit,
    runHint: (Session) -> String,
    onPreviewChime: (chime: Chime, finish: Boolean) -> Unit = { _, _ -> },
    header: @Composable () -> Unit = {},
) {
    val runs by engine.runs.collectAsState()
    var sessions by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Session?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    /** Sessions run, and the list is up to start one more beside them. */
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(runs.isEmpty()) { if (runs.isEmpty()) picking = false }
    var togglToken by remember { mutableStateOf(settings.togglToken) }
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val onList = (runs.isEmpty() || picking) && editing == null && !settingsOpen
    val start: (Session) -> Unit = {
        picking = false
        onStart(it)
    }
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
        val edit = editing
        Box(Modifier.widthIn(max = 720.dp)) {
            when {
                settingsOpen -> SettingsScreen(settings) {
                    togglToken = settings.togglToken
                    settingsOpen = false
                }
                edit != null -> EditorScreen(
                    initial = edit,
                    isNew = sessions.none { it.id == edit.id },
                    togglConnected = togglToken != null,
                    insertable = sessions.filter { it.music == edit.music && it.id != edit.id }.sortedBy { it.template } +
                        (if (edit.music) musicPresets() else presets()).filter { p -> sessions.none { it.name == p.name } },
                    onPreviewChime = onPreviewChime,
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
                runs.isNotEmpty() && !picking -> RunScreen(
                    runs = runs,
                    engine = engine,
                    hint = runs.first().session.let { if (it.music) MUSIC_HINT else runHint(it) },
                    onAddParallel = { picking = true },
                    runExtra = { run -> togglToken?.let { TogglPanel(run, it, toggl, settings) } },
                )
                else -> Column {
                    if (picking) PlatformBackHandler { picking = false }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Wassertiger",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                        TextButton(onClick = { settingsOpen = true }, modifier = Modifier.padding(top = 8.dp, end = 8.dp)) {
                            Text(if (togglToken != null) "Toggl ✓" else "Toggl")
                        }
                    }
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
                            header = {
                                if (picking && runs.isNotEmpty()) RunningBanner(runs) { picking = false }
                                header()
                            },
                            onStart = start,
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

/** Full-bleed signal in the session's [color] (0xRRGGBB); the end is always green. */
@Composable
fun FlashContent(title: String, text: String, finished: Boolean, color: Int = AMBER_RGB, modifier: Modifier = Modifier) {
    val rgb = if (finished) DONE_GREEN_RGB else color
    val ink = if (isLightColor(rgb)) Color.Black else Color.White
    Column(
        modifier.fillMaxSize().background(rgbColor(rgb)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Music flashes the word to play ("Mixolydisch"), which needs room.
        Text(title, fontSize = if (title.length > 6) 56.sp else 96.sp, color = ink, textAlign = TextAlign.Center)
        Text(text, fontSize = 28.sp, color = ink, textAlign = TextAlign.Center)
    }
}

fun rgbColor(rgb: Int) = Color(0xFF000000 or (rgb.toLong() and 0xFFFFFF))

/**
 * The flash for run [runId], counting its interval down while it stays up (a 5-second light flash
 * would otherwise freeze on "5 s"). [title] and [text] stand in while the run cannot be found.
 */
@Composable
fun RunFlash(
    engine: TimerEngine,
    runId: Long,
    title: String,
    text: String,
    finished: Boolean,
    color: Int,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow(200)
    val runs by engine.runs.collectAsState()
    val run = runs.firstOrNull { it.id == runId }
    val (t, s) = run?.flashText(now) ?: (title to text)
    FlashContent(t, s, finished || run?.finished == true, color, modifier)
}

/** [monotonicMillis], refreshed every [periodMs] while on screen. */
@Composable
private fun rememberNow(periodMs: Long): Long {
    var now by remember { mutableLongStateOf(monotonicMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = monotonicMillis()
            delay(periodMs)
        }
    }
    return now
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
                        when {
                            step.isBlock -> "[${step.name} ${formatDuration(step.seconds)}]"
                            session.music -> "${step.name.ifBlank { step.soundLabel() }} ${formatDuration(step.seconds)}"
                            else -> formatDuration(step.seconds)
                        }
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

/** What a duration field turns into a colon, since the number pad has none. */
private val MINUTE_SEPARATORS = Regex("[, ]")

/** Toggles a duration field's [EditRow.minuteMode]. */
private const val MINUTE_MODE_KEY = '.'

/**
 * The MM:SS mask of minute mode, filled from the left: "05" → "05", "053" → "05:3", "0530" → "05:30".
 * The colon appears with the third digit, so deleting never gets stuck on it.
 */
fun minuteMask(digits: String): String {
    val d = digits.filter(Char::isDigit).take(4)
    return if (d.length <= 2) d else d.take(2) + ":" + d.drop(2)
}

/** One interval in the editor; with a [block] it is a whole session put in (see [Step.block]). */
private class EditRow(
    val key: Long,
    seconds: String,
    name: String,
    drone: Int? = null,
    groove: String? = null,
    bpm: Int = 90,
    val block: List<Step> = emptyList(),
) {
    val isBlock: Boolean get() = block.isNotEmpty()
    /** The duration field with its cursor, which [typeDuration] keeps in place when it swaps characters. */
    var duration by mutableStateOf(TextFieldValue(seconds, TextRange(seconds.length)))
    val seconds: String get() = duration.text
    /** Digits fill an MM:SS mask from the left, see [minuteMask]. */
    var minuteMode by mutableStateOf(false)

    /** What gets parsed: in minute mode digits without a colon yet are whole minutes ("05" → 5:00). */
    val input: String get() = if (minuteMode && seconds.isNotEmpty() && ':' !in seconds) "$seconds:" else seconds
    var name by mutableStateOf(name)
    var drone by mutableStateOf(drone)
    var groove by mutableStateOf(groove)
    var bpm by mutableStateOf(bpm.toString())

    /**
     * The number pad has a comma but no colon: a comma (or space) becomes one, "5,30" → 5:30. Same
     * length, so the cursor stays right after it; dropping the composition tells the keyboard the
     * text changed under it, else it puts the cursor back where it thinks it was.
     *
     * A dot switches [minuteMode] on (with an empty field, ready for "0530" → 05:30) or back off; in it,
     * the field keeps only digits in an MM:SS mask with the cursor at the end.
     */
    fun typeDuration(value: TextFieldValue) {
        var text = value.text
        val toggled = MINUTE_MODE_KEY in text
        if (toggled) {
            text = text.replace(MINUTE_MODE_KEY.toString(), "")
            minuteMode = !minuteMode
        }
        if (minuteMode) {
            val mask = minuteMask(if (toggled) "" else text)
            duration = TextFieldValue(mask, TextRange(mask.length))
            return
        }
        // Leaving minute mode: "05" meant minutes, so it keeps meaning them.
        if (toggled && text.isNotEmpty() && ':' !in text) text += ":"
        text = text.replace(MINUTE_SEPARATORS, ":")
        duration = when {
            toggled -> TextFieldValue(text, TextRange(text.length))
            text == value.text -> value
            else -> TextFieldValue(text, value.selection, composition = null)
        }
    }

    /** The tempo, null when out of range; only asked for with drums. */
    val parsedBpm: Int? get() = bpm.trim().toIntOrNull()?.takeIf { it in MusicSettings.MIN_BPM..MusicSettings.MAX_BPM }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorScreen(
    initial: Session,
    isNew: Boolean,
    /** Offers "In Toggl erfassen". */
    togglConnected: Boolean,
    /** Sessions of the same kind that can go in as one interval ("Session einfügen"). */
    insertable: List<Session>,
    onPreviewChime: (Chime, Boolean) -> Unit,
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
    var vibration by remember { mutableStateOf(initial.vibration) }
    var tone by remember { mutableStateOf(initial.tone) }
    var chime by remember { mutableStateOf(initial.chime) }
    var flashColor by remember { mutableStateOf(initial.flashColor) }
    var toggl by remember { mutableStateOf(initial.toggl) }
    var hexText by remember { mutableStateOf(formatHexColor(initial.flashColor)) }
    val music = initial.music
    // Music intervals run for minutes, so they are typed in minutes.
    // Timer intervals are typed in seconds, but anything from a minute up shows as m:ss.
    fun durationText(seconds: Int) = when {
        music -> formatMinutes(seconds)
        seconds >= 60 -> formatDuration(seconds)
        else -> seconds.toString()
    }
    var nextKey by remember { mutableLongStateOf(initial.steps.size.toLong()) }
    val rows = remember {
        mutableStateListOf<EditRow>().apply {
            initial.steps.forEachIndexed { i, s ->
                add(EditRow(i.toLong(), durationText(s.seconds), s.name, s.drone, s.groove, s.bpm, s.block))
            }
        }
    }
    fun addBlock(step: Step) {
        rows.add(EditRow(nextKey++, "", step.name, block = step.block))
    }
    /** A new interval keeps the sound of the last one. */
    fun addRow(seconds: Int, name: String = "") {
        val last = rows.lastOrNull()
        rows.add(EditRow(nextKey++, durationText(seconds), name, last?.drone, last?.groove, last?.parsedBpm ?: 90))
    }

    val parsed = rows.map {
        when {
            it.isBlock -> it.block.sumOf { s -> s.seconds }
            music -> parseMinutes(it.seconds)
            else -> parseDuration(it.input)
        }
    }
    val rounds = if (endless) ENDLESS else roundsText.trim().toIntOrNull()?.takeIf { it in 1..999 }
    val valid = rows.isNotEmpty() && parsed.all { it != null } && name.isNotBlank() && rounds != null &&
        rows.all { it.groove == null || it.parsedBpm != null }
    fun edited() = initial.copy(
        name = name.trim(),
        steps = rows.mapIndexed { i, r ->
            when {
                r.isBlock -> Step(parsed[i]!!, r.name, block = r.block)
                music -> Step(parsed[i]!!, r.name.trim(), r.drone, r.groove, r.parsedBpm ?: 90)
                else -> Step(parsed[i]!!, r.name.trim())
            }
        },
        rounds = rounds!!,
        sound = sound,
        fifth = fifth,
        kit = kit,
        vibration = vibration,
        tone = tone,
        chime = chime,
        flashColor = flashColor,
        toggl = toggl,
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
                if (row.isBlock) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(28.dp))
                        Card(
                            Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Eingefügt: ${row.block.size} Intervalle · ${formatDuration(parsed[i] ?: 0)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column {
                            TextButton(onClick = { rows.add(i - 1, rows.removeAt(i)) }, enabled = i > 0) { Text("↑") }
                            TextButton(onClick = { rows.removeAt(i) }) { Text("✕") }
                        }
                    }
                    return@itemsIndexed
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(28.dp))
                        OutlinedTextField(
                            value = row.duration,
                            onValueChange = { row.typeDuration(it) },
                            label = {
                                Text(
                                    when {
                                        music -> "Min."
                                        row.minuteMode -> "⏱ Min:Sek"
                                        ':' in row.seconds -> "Min:Sek"
                                        else -> "Sek."
                                    },
                                )
                            },
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
                        onClick = {
                            val last = rows.last()
                            if (last.isBlock) addBlock(Step(0, last.name, block = last.block))
                            else parsed.lastOrNull()?.let { addRow(it, last.name) }
                        },
                        enabled = parsed.lastOrNull() != null,
                    ) { Text("Letztes wiederholen") }
                }
                if (insertable.isNotEmpty()) {
                    Picker<Session?>(
                        "Session einfügen", null, insertable,
                        { it?.let { s -> "${s.name} · ${s.totalSeconds?.let(::formatDuration) ?: "1 Runde"}" } ?: "wählen" },
                    ) { picked -> picked?.let { addBlock(it.asBlock()) } }
                    Text(
                        "Eine ganze Session als ein Intervall, z. B. die Atemübung in der Pomodoro-Pause.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    } + if (music) " · Eingabe in Minuten oder m:ss" else " · Eingabe in Sekunden; Komma trennt Minuten (5,30 = 5:30), Punkt schaltet auf Minuten-Eingabe (0530 = 5:30)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text("Signal beim Wechsel", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text("Vibration", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keep a length set some other way selectable instead of silently dropping it.
                    (VIBRATION_CHOICES + vibration).distinct().sorted().forEach { secs ->
                        FilterChip(
                            selected = vibration == secs,
                            onClick = { vibration = secs },
                            label = { Text(if (secs == 0) "Aus – nur Licht" else "$secs s") },
                        )
                    }
                }
                Text(
                    if (vibration == 0) {
                        "Kein Vibrieren – das Display leuchtet bei jedem Wechsel 5 Sekunden auf."
                    } else {
                        "Am Ende eine Sekunde länger. Das Display geht dazu kurz an."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Leuchtfarbe", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    FLASH_COLORS.forEach { (label, rgb) ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(rgbColor(rgb))
                                .border(
                                    if (flashColor == rgb) 3.dp else 1.dp,
                                    if (flashColor == rgb) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                                .semantics { contentDescription = label }
                                .clickable {
                                    flashColor = rgb
                                    hexText = formatHexColor(rgb)
                                },
                        )
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            parseHexColor(text)?.let { flashColor = it }
                        },
                        label = { Text("#Hex") },
                        isError = parseHexColor(hexText) == null,
                        singleLine = true,
                        leadingIcon = { Box(Modifier.size(20.dp).clip(CircleShape).background(rgbColor(flashColor))) },
                        modifier = Modifier.width(150.dp),
                    )
                }
                Text(
                    "Bei jedem Wechsel; am Ende leuchtet es grün.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Ton", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToneMode.entries.forEach { mode ->
                        FilterChip(selected = tone == mode, onClick = { tone = mode }, label = { Text(mode.label) })
                    }
                }
                if (tone != ToneMode.OFF) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chime.entries.forEach { c ->
                            FilterChip(
                                selected = chime == c,
                                onClick = {
                                    chime = c
                                    // Play what this session will sound like: the triad when it only sounds at the end.
                                    onPreviewChime(c, tone == ToneMode.END)
                                },
                                label = { Text(c.label) },
                            )
                        }
                    }
                }
                Text(
                    when (tone) {
                        ToneMode.OFF -> "Ohne Ton – Spotify & Co. laufen ungestört weiter."
                        ToneMode.END -> "Am Ende drei Töne aufsteigend. Tippen spielt sie vor."
                        ToneMode.EVERY -> "Ein Ton bei jedem Wechsel, am Ende drei aufsteigend. Tippen spielt vor."
                    } + if (tone != ToneMode.OFF) " Musik läuft dabei weiter." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (togglConnected) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Switch(checked = toggl, onCheckedChange = { toggl = it })
                        Spacer(Modifier.width(8.dp))
                        Text("In Toggl erfassen – zählt dort mit, solange die Session läuft")
                    }
                }
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

/**
 * The running sessions: one fills the screen with its big countdown; several stack up, each with
 * its own controls. [onAddParallel] (if given) offers to start one more beside them; a finished run
 * [runExtra] goes below each run's controls (above "Zurück" once it is over). [extra] goes below everything.
 */
@Composable
fun RunScreen(
    runs: List<RunState>,
    engine: TimerEngine,
    hint: String,
    onAddParallel: (() -> Unit)? = null,
    runExtra: @Composable (RunState) -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    val now = rememberNow(100)
    val single = runs.size == 1
    // Scrolls once the Toggl form or several runs need more room; centred while they fit.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (single) 0.dp else 16.dp, Alignment.CenterVertically),
        ) {
            if (single) {
                RunPanel(runs.single(), now, engine, big = true, runExtra)
            } else {
                runs.forEach { run ->
                    // Not surfaceVariant: the outlined buttons' border has that very colour.
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            RunPanel(run, now, engine, big = false, runExtra)
                        }
                    }
                }
            }
            if (onAddParallel != null && runs.any { !it.finished }) {
                TextButton(onClick = onAddParallel, modifier = Modifier.padding(top = 16.dp)) { Text("+ Parallel starten") }
            }
            extra()
            Spacer(Modifier.height(24.dp))
            if (hint.isNotEmpty()) Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Countdown and controls of one run; [big] when it has the screen to itself. */
@Composable
private fun RunPanel(run: RunState, now: Long, engine: TimerEngine, big: Boolean, runExtra: @Composable (RunState) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(run.session.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (big) 32.dp else 8.dp))

        if (run.finished) {
            Text(
                if (run.stopped) "Gestoppt" else "Fertig!",
                fontSize = if (big) 72.sp else 40.sp,
                fontWeight = FontWeight.Bold,
                color = Amber,
            )
            Spacer(Modifier.height(if (big) 32.dp else 8.dp))
            runExtra(run)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { engine.dismiss(run.id) }) { Text("Zurück") }
            return@Column
        }

        val step = run.step ?: return@Column
        Text(
            listOf("Intervall ${run.index + 1} / ${run.session.steps.size}", run.roundLabel)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        val music = run.session.music
        if (music) {
            // What to play is the headline; the clock steps back.
            if (step.name.isNotBlank()) {
                Text(step.name, fontSize = if (big) 56.sp else 32.sp, fontWeight = FontWeight.Bold, color = Amber, textAlign = TextAlign.Center)
            }
            Text(step.soundLabel(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        } else if (step.name.isNotBlank()) {
            Text(step.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Text(
            formatDuration(run.remainingSeconds(now)).removeSuffix(" s"),
            fontSize = when {
                !big -> 64.sp
                music && step.name.isNotBlank() -> 72.sp
                else -> 120.sp
            },
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
        Spacer(Modifier.height(if (big) 48.dp else 16.dp))
        // In a card the three buttons need to move closer to fit on one line.
        val padding = if (big) ButtonDefaults.ContentPadding else PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)) {
            OutlinedButton(onClick = { engine.stop(run.id) }, contentPadding = padding) { Text("Stopp") }
            Button(onClick = { engine.togglePause(run.id) }, contentPadding = padding) { Text(if (run.paused) "Weiter" else "Pause") }
            OutlinedButton(onClick = { engine.skip(run.id) }, contentPadding = padding) { Text("Überspringen") }
        }
        runExtra(run)
    }
}

/** Above the list while sessions run: what runs, and the way back to the countdowns. */
@Composable
private fun RunningBanner(runs: List<RunState>, onOpen: () -> Unit) {
    val now = rememberNow(500)
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2C00)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                runs.forEach { run ->
                    Text(
                        "${run.session.name} · " + when {
                            run.finished -> if (run.stopped) "gestoppt" else "fertig"
                            run.paused -> "pausiert"
                            else -> formatDuration(run.remainingSeconds(now))
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text("„Start“ lässt eine weitere Session parallel laufen.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onOpen) { Text("Zur Anzeige") }
        }
    }
}

// --- Toggl ------------------------------------------------------------------

/**
 * Toggl under a run: while it runs a line says under which name it counts there (a tap renames it);
 * once over, the booked time with name, project, "Aus Toggl löschen" – or "Eintragen" for a run
 * Toggl did not follow.
 */
@Composable
private fun TogglPanel(run: RunState, token: String, tracker: TogglTracker, settings: SettingsStore) {
    val all by tracker.runs.collectAsState()
    val t = all[run.id]
    var editing by remember(run.id) { mutableStateOf(false) }

    if (!run.finished) {
        if (t == null || t.deleted) return
        Column(Modifier.padding(top = 16.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { editing = !editing }) {
                Text(
                    (if (t.live) "● Toggl zählt mit: " else "Toggl: ") + t.label() +
                        if (!t.live && t.error == null) " (wird eingetragen, sobald es geht)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            t.error?.let { TogglError(it) { tracker.retry(run.id) } }
            if (editing) TogglForm(token, t.description, t.project, "Übernehmen") { d, p ->
                tracker.edit(run.id, d, p)
                editing = false
            }
        }
        return
    }

    Card(
        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Toggl", style = MaterialTheme.typography.titleSmall)
            if (t == null) {
                val last = remember(run.id) { settings.lastEntry(run.session.name) }
                val end = if (run.endedAt > 0) run.endedAt else run.startedAt + run.activeMillis()
                val millis = run.activeMillis()
                Text(
                    "Nicht erfasst · ${formatDuration((millis / 1000).toInt())} · ${clockTime(end - millis)}–${clockTime(end)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TogglForm(token, last?.first ?: run.session.name, last?.second, "Eintragen") { d, p -> tracker.book(run, d, p) }
                return@Column
            }
            if (t.deleted) {
                Text(if (t.error == null) "Aus Toggl gelöscht" else "Wird aus Toggl gelöscht …", style = MaterialTheme.typography.bodyMedium)
                t.error?.let { TogglError(it) { tracker.retry(run.id) } }
                return@Column
            }
            Text(
                "${formatDuration((t.millis() / 1000).toInt())} · ${clockTime(t.firstStart)}–${t.lastEnd?.let(::clockTime) ?: "…"}" +
                    (if (t.segments.size > 1) " (ohne Pausen)" else "") +
                    if (t.aborted) " · abgebrochen" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                t.error != null -> TogglError(t.error) { tracker.retry(run.id) }
                t.synced -> Text("✓ In Toggl: ${t.label()}", color = MaterialTheme.colorScheme.primary)
                else -> Text("Wird übertragen …", style = MaterialTheme.typography.bodySmall)
            }
            if (editing) {
                TogglForm(token, t.description, t.project, "Übernehmen") { d, p ->
                    tracker.edit(run.id, d, p)
                    editing = false
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editing) OutlinedButton(onClick = { editing = true }) { Text("Ändern") }
                TextButton(onClick = { tracker.delete(run.id) }) { Text("Aus Toggl löschen", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** "Bachelorarbeit · Studium" */
private fun TogglRun.label(): String = description + (project?.let { " · ${it.name}" } ?: "")

@Composable
private fun TogglError(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
        TextButton(onClick = onRetry) { Text("Nochmal") }
    }
}

/** Description and project for Toggl; the projects are fetched only when their menu opens. */
@Composable
private fun TogglForm(
    token: String,
    initialDescription: String,
    initialProject: TogglProject?,
    action: String,
    onDone: (String, TogglProject?) -> Unit,
) {
    var description by remember { mutableStateOf(initialDescription) }
    var project by remember { mutableStateOf(initialProject) }
    var projects by remember(token) { mutableStateOf<List<TogglProject>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuOpen) {
        if (menuOpen && projects == null) {
            try {
                projects = Toggl(token).projects()
            } catch (e: TogglException) {
                error = e.message
                menuOpen = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Beschreibung") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            OutlinedButton(onClick = { menuOpen = true }) { Text("Projekt: ${project?.name ?: "ohne"}") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val list = projects
                if (list == null) {
                    DropdownMenuItem(text = { Text("Lädt …") }, onClick = {}, enabled = false)
                } else {
                    (listOf<TogglProject?>(null) + list).forEach { p ->
                        DropdownMenuItem(text = { Text(p?.name ?: "ohne Projekt") }, onClick = {
                            project = p
                            menuOpen = false
                        })
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(enabled = description.isNotBlank(), onClick = { onDone(description.trim(), project) }) { Text(action) }
    }
}

/** The Toggl Track connection: API token, checked against the account. */
@Composable
private fun SettingsScreen(settings: SettingsStore, onClose: () -> Unit) {
    PlatformBackHandler(onBack = onClose)
    var token by remember { mutableStateOf(settings.togglToken.orEmpty()) }
    var connected by remember { mutableStateOf(settings.togglToken != null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Toggl Track", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mit Toggl verbunden, zählt jede Session in Toggl mit, solange sie läuft – unter dem Namen und Projekt, " +
                "die du ihr zuletzt gegeben hast (anfangs der Session-Name). Pausen zählen nicht; Stopp vor dem Ende " +
                "beendet den Eintrag dort und markiert ihn „abgebrochen“. Pro Session abschaltbar im Editor. " +
                "Den API-Token findest du in Toggl unter Profil → API Token (track.toggl.com/profile).",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                status = null
            },
            label = { Text("API-Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = token.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        status = try {
                            val name = Toggl(token.trim()).userName()
                            settings.togglToken = token
                            connected = true
                            "✓ Verbunden als $name"
                        } catch (e: TogglException) {
                            e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Prüfe …" else "Verbinden") }
            if (connected) {
                TextButton(onClick = {
                    settings.togglToken = null
                    token = ""
                    connected = false
                    status = "Getrennt"
                }) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
            }
        }
        status?.let { Text(it, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
        Text(
            "Toggl kennt nur einen laufenden Timer: Läuft dort schon einer (auch dein eigener), bleibt er unangetastet " +
                "und die Session wird eingetragen, sobald sie endet oder pausiert. Unter 1 Minute gestoppt verschwindet " +
                "sie wieder aus Toggl.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose) { Text("Fertig") }
        }
    }
}
