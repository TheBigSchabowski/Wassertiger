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
import androidx.compose.material3.AlertDialog
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
    /** A session that wants its Toggl project and description before it starts. */
    var choosing by remember { mutableStateOf<Session?>(null) }
    val start: (Session) -> Unit = {
        if (togglToken != null && it.toggl && it.togglAsks) {
            choosing = it
        } else {
            picking = false
            onStart(it)
        }
    }
    choosing?.let { session ->
        TogglStartDialog(session, toggl, onDismiss = { choosing = null }) { description, project ->
            toggl.choose(session, description, project)
            choosing = null
            picking = false
            onStart(session)
        }
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
                    loadProjects = toggl::projects,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNew(null) }, modifier = Modifier.weight(1f)) {
                    Text(if (music) "+ Neue Übung" else "+ Neue Session")
                }
                if (!music) OutlinedButton(onClick = { onNew(newStopwatch()) }, modifier = Modifier.weight(1f)) { Text("+ Neue Stoppuhr") }
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
                if (!session.stopwatch) Text(
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
    track: Boolean = true,
) {
    val isBlock: Boolean get() = block.isNotEmpty()
    /** Counts in Toggl ([Step.track]). */
    var track by mutableStateOf(track)
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
    /** Offers the Toggl settings. */
    togglConnected: Boolean,
    /** The Toggl projects for the project menu. */
    loadProjects: suspend () -> List<TogglProject>,
    /** Sessions of the same kind that can go in as one interval ("Session einfügen"). */
    insertable: List<Session>,
    onPreviewChime: (Chime, Boolean) -> Unit,
    onSaveAsTemplate: (Session) -> Unit,
    onSave: (Session) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
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
    var togglDescription by remember { mutableStateOf(initial.togglDescription) }
    var togglProject by remember { mutableStateOf(initial.togglProject) }
    /** Project and description picked before every start instead of fixed. */
    var togglAsk by remember { mutableStateOf(initial.togglAsks) }
    var togglPauseStops by remember { mutableStateOf(initial.togglPauseStops) }
    var hexText by remember { mutableStateOf(formatHexColor(initial.flashColor)) }
    val music = initial.music
    // Music intervals run for minutes, so they are typed in minutes.
    // Timer intervals are typed in seconds, but anything from a minute up shows as m:ss.
    fun durationText(seconds: Int) = when {
        music -> formatMinutes(seconds)
        seconds >= 60 -> formatDuration(seconds)
        else -> seconds.toString()
    }
    val stopwatch = initial.stopwatch
    /** A stopwatch's optional signal every [signalText]. */
    var signalOn by remember { mutableStateOf(initial.signalEvery > 0) }
    var signalText by remember { mutableStateOf(if (initial.signalEvery > 0) durationText(initial.signalEvery) else "2") }
    val signalEvery = if (signalOn) parseDuration(signalText) else 0
    var nextKey by remember { mutableLongStateOf(initial.steps.size.toLong()) }
    val rows = remember {
        mutableStateListOf<EditRow>().apply {
            initial.steps.forEachIndexed { i, s ->
                add(EditRow(i.toLong(), durationText(s.seconds), s.name, s.drone, s.groove, s.bpm, s.block, s.track))
            }
        }
    }
    fun addBlock(step: Step) {
        rows.add(EditRow(nextKey++, "", step.name, block = step.block, track = step.track))
    }
    /** A new interval keeps the sound of the last one. */
    fun addRow(seconds: Int, name: String = "", track: Boolean = true) {
        val last = rows.lastOrNull()
        rows.add(EditRow(nextKey++, durationText(seconds), name, last?.drone, last?.groove, last?.parsedBpm ?: 90, track = track))
    }

    val parsed = rows.map {
        when {
            it.isBlock -> it.block.sumOf { s -> s.seconds }
            music -> parseMinutes(it.seconds)
            else -> parseDuration(it.input)
        }
    }
    val rounds = if (endless) ENDLESS else roundsText.trim().toIntOrNull()?.takeIf { it in 1..999 }
    val valid = if (stopwatch) {
        name.isNotBlank() && signalEvery != null
    } else {
        rows.isNotEmpty() && parsed.all { it != null } && name.isNotBlank() && rounds != null &&
            rows.all { it.groove == null || it.parsedBpm != null }
    }
    fun edits() = initial.copy(
        name = name.trim(),
        steps = rows.mapIndexed { i, r ->
            when {
                r.isBlock -> Step(parsed[i]!!, r.name, block = r.block, track = r.track)
                music -> Step(parsed[i]!!, r.name.trim(), r.drone, r.groove, r.parsedBpm ?: 90, track = r.track)
                else -> Step(parsed[i]!!, r.name.trim(), track = r.track)
            }
        },
        rounds = rounds ?: ENDLESS,
        sound = sound,
        fifth = fifth,
        kit = kit,
        vibration = vibration,
        tone = tone,
        chime = chime,
        flashColor = flashColor,
        toggl = toggl,
        togglDescription = togglDescription.trim(),
        togglAskDescription = togglAsk,
        togglProject = togglProject,
        togglAskProject = togglAsk,
        togglPauseStops = togglPauseStops,
    )
    fun edited() = if (stopwatch) edits().copy(steps = stopwatchSteps(signalEvery!!), rounds = ENDLESS, signalEvery = signalEvery) else edits()
    var templateSaved by remember { mutableStateOf(false) }
    /** A question before something is lost for good: deleting, or leaving with changes. */
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Anything that cannot be saved as it stands was typed in, so it counts as a change too.
    val cancel = { if (!valid || edited() != initial) confirmDiscard = true else onCancel() }
    PlatformBackHandler(onBack = cancel)
    if (confirmDelete) Confirm(
        "„${initial.name}“ löschen?",
        if (initial.template) "Die Vorlage ist dann weg." else "Die Session ist dann weg.",
        "Löschen", onDelete,
    ) { confirmDelete = false }
    if (confirmDiscard) Confirm("Änderungen verwerfen?", "Was du geändert hast, wird nicht gespeichert.", "Verwerfen", onCancel) {
        confirmDiscard = false
    }

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
            if (!stopwatch) itemsIndexed(rows, key = { _, r -> r.key }) { i, row ->
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
            if (!stopwatch) item {
                Text("Intervall hinzufügen", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (music) listOf(60, 120, 180, 300, 600, 1200) else listOf(10, 15, 20, 30, 45, 60, 90, 120)).forEach { secs ->
                        FilledTonalButton(onClick = { addRow(secs) }) { Text(if (music) "${secs / 60} min" else formatDuration(secs)) }
                    }
                    OutlinedButton(
                        onClick = {
                            val last = rows.last()
                            if (last.isBlock) addBlock(Step(0, last.name, block = last.block, track = last.track))
                            else parsed.lastOrNull()?.let { addRow(it, last.name, last.track) }
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
                if (stopwatch) {
                    Text("Signal", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(selected = !signalOn, onClick = { signalOn = false }, label = { Text("Kein Signal") })
                        FilterChip(selected = signalOn, onClick = { signalOn = true }, label = { Text("Alle …") })
                        if (signalOn) OutlinedTextField(
                            value = signalText,
                            onValueChange = { signalText = it },
                            label = { Text(if (':' in signalText) "Min:Sek" else "Sek.") },
                            isError = signalEvery == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(100.dp),
                        )
                    }
                    Text(
                        if (signalOn) "Zählt hoch bis Stopp und gibt alle ${signalEvery?.let(::formatDuration) ?: "…"} ein Signal – wie unten eingestellt."
                        else "Zählt einfach hoch bis Stopp, ohne Leuchten oder Vibrieren.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (!stopwatch) {
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
                }
                if (!stopwatch || signalOn) {
                if (!stopwatch) Text("Signal beim Wechsel", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
                    when {
                        vibration == 0 -> "Kein Vibrieren – das Display leuchtet bei jedem ${if (stopwatch) "Signal" else "Wechsel"} 5 Sekunden auf."
                        stopwatch -> "Das Display geht dazu kurz an."
                        else -> "Am Ende eine Sekunde länger. Das Display geht dazu kurz an."
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
                    if (stopwatch) "Bei jedem Signal." else "Bei jedem Wechsel; am Ende leuchtet es grün.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Ton", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A stopwatch has no end to sound at.
                    ToneMode.entries.filter { !stopwatch || it != ToneMode.END }.forEach { mode ->
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
                        ToneMode.EVERY -> if (stopwatch) "Ein Ton bei jedem Signal. Tippen spielt vor." else "Ein Ton bei jedem Wechsel, am Ende drei aufsteigend. Tippen spielt vor."
                    } + if (tone != ToneMode.OFF) " Musik läuft dabei weiter." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                }
                if (togglConnected) {
                    Text("Toggl", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    // Three ways: not at all (the default), always the same, or asked before every start.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !toggl, onClick = { toggl = false }, label = { Text("Nicht erfassen") })
                        FilterChip(selected = toggl && !togglAsk, onClick = { toggl = true; togglAsk = false }, label = { Text("Immer gleich") })
                        FilterChip(selected = toggl && togglAsk, onClick = { toggl = true; togglAsk = true }, label = { Text("Vor dem Start fragen") })
                    }
                    if (toggl) {
                        if (!togglAsk) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                TogglProjectMenu(togglProject, loadProjects) { togglProject = it }
                                OutlinedTextField(
                                    value = togglDescription,
                                    onValueChange = { togglDescription = it },
                                    label = { Text("Beschreibung") },
                                    placeholder = { Text(if (togglProject != null) "ohne" else name.ifBlank { "Name der Session" }) },
                                    singleLine = true,
                                    modifier = Modifier.widthIn(min = 180.dp, max = 320.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Switch(checked = togglPauseStops, onCheckedChange = { togglPauseStops = it })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (togglPauseStops) "Pause-Knopf pausiert auch Toggl" else "Toggl zählt bei Pause weiter",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // One block for the whole session unless some intervals are picked out on purpose.
                        var someIntervals by remember { mutableStateOf(rows.any { !it.track }) }
                        if (rows.size > 1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Switch(checked = someIntervals, onCheckedChange = {
                                someIntervals = it
                                if (!it) rows.forEach { r -> r.track = true }
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (someIntervals) "Nur diese Intervalle erfassen:" else "Ganze Session als ein Block",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (rows.size > 1 && someIntervals) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rows.forEachIndexed { i, row ->
                                    FilterChip(
                                        selected = row.track,
                                        onClick = { row.track = !row.track },
                                        label = {
                                            val what = if (row.isBlock) row.name else row.name.ifBlank { parsed[i]?.let(::formatDuration) ?: "?" }
                                            Text("${i + 1}. $what")
                                        },
                                    )
                                }
                            }
                        }
                        val counted = rows.indices.filter { rows[it].track }.sumOf { parsed[it] ?: 0 }
                        Text(
                            buildString {
                                append(
                                    if (togglAsk) {
                                        "Beim Start fragt die App, was du machst – Projekt und Beschreibung (vorgeschlagen: was du zuletzt gewählt hast)."
                                    } else {
                                        "Wird automatisch erfasst als „" + listOfNotNull(
                                            togglDescription.trim().ifBlank { if (togglProject != null) null else name.trim() },
                                            togglProject?.name,
                                        ).joinToString(" · ") + "“."
                                    },
                                )
                                if (rows.any { !it.track }) {
                                    append(" Erfasst: ${formatDuration(counted)} pro Runde")
                                    if (rounds != null && rounds > 1) append(", ${formatDuration(counted * rounds)} gesamt")
                                    append(".")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
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
                TextButton(onClick = { confirmDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = cancel) { Text("Abbrechen") }
            Button(enabled = valid, onClick = { onSave(edited()) }) { Text("Speichern") }
        }
    }
}

/** Asks before [onConfirm] does what cannot be undone; [action] names it on the button. */
@Composable
private fun Confirm(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(action, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zurück") } },
    )
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
            if (run.session.stopwatch) Text(formatClock(run.elapsedMs()), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(if (big) 32.dp else 8.dp))
            runExtra(run)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { engine.dismiss(run.id) }) { Text("Zurück") }
            return@Column
        }

        // In a card the buttons need to move closer to fit on one line.
        val padding = if (big) ButtonDefaults.ContentPadding else PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        if (run.session.stopwatch) {
            // Counts up; the signal (if any) is just a note underneath.
            Text(
                formatClock(run.elapsedMs(now)),
                fontSize = if (big) 96.sp else 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (run.paused) MaterialTheme.colorScheme.onSurfaceVariant else Amber,
            )
            Text(
                if (run.session.signalEvery > 0) "Signal alle ${formatDuration(run.session.signalEvery)} · nächstes in ${formatDuration(run.remainingSeconds(now))}"
                else "Stoppuhr",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(if (big) 48.dp else 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (big) 12.dp else 8.dp)) {
                OutlinedButton(onClick = { engine.stop(run.id) }, contentPadding = padding) { Text("Stopp") }
                Button(onClick = { engine.togglePause(run.id) }, contentPadding = padding) { Text(if (run.paused) "Weiter" else "Pause") }
            }
            runExtra(run)
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
                            run.session.stopwatch -> formatClock(run.elapsedMs(now))
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
        if (t == null) return
        if (t.deleted) {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Toggl: diesmal nicht erfasst", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { tracker.setTracked(run.id, true) }) { Text("Doch erfassen") }
            }
            return
        }
        if (t.waiting) {
            // The session asks every time: fill in now and it counts from the start, or at the end.
            Card(
                Modifier.padding(top = 16.dp).widthIn(max = 420.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Für Toggl", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Jetzt angeben – dann zählt es ab dem Start mit. Oder am Ende.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TogglForm(
                        tracker, t.description, t.project, "Übernehmen",
                        skip = "Diesmal nicht erfassen" to { tracker.setTracked(run.id, false) },
                    ) { d, p -> tracker.edit(run.id, d, p) }
                }
            }
            return
        }
        Column(Modifier.padding(top = 16.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { editing = !editing }) {
                Text(
                    when {
                        run.paused -> "Toggl pausiert: ${t.label()}"
                        run.step?.track == false -> "Toggl: dieses Intervall zählt nicht (${t.label()})"
                        t.live -> "● Toggl zählt mit: ${t.label()}"
                        // Another timer runs there, or Toggl could not be asked: booked at a pause or the end.
                        else -> "Toggl: ${t.label()} (wird am Ende eingetragen)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            t.error?.let { TogglError(it) { tracker.retry(run.id) } }
            if (editing) TogglForm(
                tracker, t.description, t.project, "Übernehmen",
                skip = "Diesmal nicht erfassen" to {
                    tracker.setTracked(run.id, false)
                    editing = false
                },
            ) { d, p ->
                tracker.edit(run.id, d, p)
                editing = false
            }
        }
        return
    }

    // Toggl switched off for this session: nothing to offer.
    if (t == null && !run.session.toggl) return
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
                TogglForm(tracker, last?.first ?: run.session.name, last?.second, "Eintragen") { d, p -> tracker.book(run, d, p) }
                return@Column
            }
            if (t.waiting && !t.deleted) {
                Text(
                    if (t.segments.isEmpty()) "Nichts erfasst" else "Noch nicht in Toggl · ${t.span()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (t.segments.isNotEmpty()) TogglForm(
                    tracker, t.description, t.project, "Eintragen",
                    skip = "Nicht eintragen" to { tracker.setTracked(run.id, false) },
                ) { d, p -> tracker.edit(run.id, d, p) }
                return@Column
            }
            if (t.deleted) {
                Text(
                    when {
                        t.error != null -> "Wird aus Toggl genommen …"
                        t.segments.isEmpty() -> "Nichts erfasst"
                        else -> "Nicht in Toggl · ${t.span()}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                t.error?.let { TogglError(it) { tracker.retry(run.id) } }
                if (t.segments.isNotEmpty() && t.error == null) {
                    OutlinedButton(onClick = { tracker.setTracked(run.id, true) }) { Text(if (t.waiting) "Doch eintragen …" else "Doch eintragen") }
                }
                return@Column
            }
            Text(t.span() + if (t.aborted) " · abgebrochen" else "", style = MaterialTheme.typography.bodyMedium)
            when {
                t.error != null -> TogglError(t.error) { tracker.retry(run.id) }
                t.synced -> Text("✓ In Toggl: ${t.label()}", color = MaterialTheme.colorScheme.primary)
                else -> Text("Wird übertragen …", style = MaterialTheme.typography.bodySmall)
            }
            if (editing) {
                TogglForm(tracker, t.description, t.project, "Übernehmen") { d, p ->
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

/** "Bachelorarbeit · Studium", "Musik" without a description. */
private fun TogglRun.label(): String = listOfNotNull(description.ifBlank { null }, project?.name).joinToString(" · ").ifEmpty { "ohne Beschreibung" }

/**
 * Before a session that asks for them starts: its Toggl project and description, offered as last
 * time. "Ohne Toggl starten" starts it all the same, left out of Toggl.
 */
@Composable
private fun TogglStartDialog(session: Session, tracker: TogglTracker, onDismiss: () -> Unit, onStart: (String?, TogglProject?) -> Unit) {
    val (description, project) = remember(session.id) { tracker.suggestion(session) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${session.name} – für Toggl") },
        text = {
            TogglForm(
                tracker, description, project, "Starten",
                skip = "Ohne Toggl starten" to { onStart(null, null) },
            ) { d, p -> onStart(d, p) }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** "1:40:00 · 10:00–12:00 (ohne Pausen)": the counted time and when. */
private fun TogglRun.span(): String =
    "${formatDuration((millis() / 1000).toInt())} · ${clockTime(firstStart)}–${lastEnd?.let(::clockTime) ?: "…"}" +
        if (segments.size > 1) " (ohne Pausen)" else ""

@Composable
private fun TogglError(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
        TextButton(onClick = onRetry) { Text("Nochmal") }
    }
}

/** Description and project for Toggl; the projects are fetched only when their menu opens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TogglForm(
    tracker: TogglTracker,
    initialDescription: String,
    initialProject: TogglProject?,
    action: String,
    /** A second, quieter button: its label and what it does ("Diesmal nicht erfassen"). */
    skip: Pair<String, () -> Unit>? = null,
    onDone: (String, TogglProject?) -> Unit,
) {
    var description by remember { mutableStateOf(initialDescription) }
    var project by remember { mutableStateOf(initialProject) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TogglProjectMenu(project, tracker::projects) { project = it }
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Beschreibung") },
            // With a project, none is fine: "Musik" alone.
            placeholder = if (project != null) ({ Text("ohne") }) else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Button(enabled = description.isNotBlank() || project != null, onClick = { onDone(description.trim(), project) }) { Text(action) }
            skip?.let { (label, onSkip) -> TextButton(onClick = onSkip) { Text(label) } }
        }
    }
}

/** "Projekt: Musik" opening the Toggl projects, fetched when it first opens. */
@Composable
fun TogglProjectMenu(project: TogglProject?, load: suspend () -> List<TogglProject>, enabled: Boolean = true, onPick: (TogglProject?) -> Unit) {
    var projects by remember { mutableStateOf<List<TogglProject>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuOpen) {
        if (menuOpen && projects == null) {
            try {
                projects = load()
            } catch (e: TogglException) {
                error = e.message
                projects = emptyList()
            }
        }
    }
    Column {
        Box {
            OutlinedButton(onClick = { menuOpen = true }, enabled = enabled) { Text("Projekt: ${project?.name ?: "ohne"}") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // "ohne Projekt" works even when the list cannot be loaded.
                (listOf<TogglProject?>(null) + projects.orEmpty()).forEach { p ->
                    DropdownMenuItem(text = { Text(p?.name ?: "ohne Projekt") }, onClick = {
                        onPick(p)
                        menuOpen = false
                    })
                }
                if (projects == null) DropdownMenuItem(text = { Text("Projekte laden …") }, onClick = {}, enabled = false)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
            "Im Editor jeder Session unter „Toggl“: Nicht erfassen (Standard), Immer gleich (z. B. als „Dankbarkeit“ " +
                "oder nur Projekt „Musik“) oder Vor dem Start fragen (z. B. Pomodoro). Erfasst wird die ganze Session als " +
                "ein Block. Stopp vor dem Ende beendet den Eintrag dort und markiert ihn „abgebrochen“. " +
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
                            val (name, workspace) = Toggl(token.trim()).account()
                            settings.togglToken = token
                            settings.togglWorkspace = workspace
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
