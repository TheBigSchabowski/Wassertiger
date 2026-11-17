package com.example.wassertiger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Unteransichten des Reiters „Aufgaben“. */
enum class TasksSubView(val label: String) {
    WEEK("Woche"),
    QUESTS("Quests"),
    CALENDAR("Kalender"),
    REVIEW("Rückblick"),
}

/** Ziel für den Hauptreiter „Fokus“: freie Session, eine Aufgabe oder eine ganze Quest. */
sealed interface FocusTarget {
    data object Free : FocusTarget
    data class Task(val taskId: String) : FocusTarget
    data class Quest(val questId: String) : FocusTarget
}

/**
 * Haupt-Container für den Aufgaben-, Wochen-, Quest-, Kalender- und Rückblick-Modus.
 *
 * Bleibt bewusst innerhalb von Wassertigers dunklem Material-3-Design und nutzt dessen Signale
 * (Vibration, Leuchtfarbe, Klang) sowie die optionale Toggl-Synchronisation pro Quest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(
    taskStore: TaskStore,
    availableSessions: List<Session>,
    engine: TimerEngine,
    toggl: TogglTracker,
    togglConnected: Boolean,
    onOpenFocus: (FocusTarget) -> Unit,
    onOpenRunningScreen: () -> Unit,
    onPreviewChime: (chime: Chime, finish: Boolean) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val snapshot by taskStore.state.collectAsState()
    val runs by engine.runs.collectAsState()
    var subView by remember { mutableStateOf(TasksSubView.WEEK) }

    val activeFocusRun = runs.firstOrNull { !it.finished && it.session.id.startsWith("focus_") }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TasksSubView.entries.forEach { view ->
                FilterChip(
                    selected = subView == view,
                    onClick = { subView = view },
                    label = { Text(view.label) },
                )
            }
            if (activeFocusRun != null) {
                FilledTonalButton(
                    onClick = {
                        val idPart = activeFocusRun.session.id.removePrefix("focus_")
                        val target = when {
                            idPart.startsWith("task_") -> FocusTarget.Task(idPart.removePrefix("task_"))
                            idPart.startsWith("quest_") -> FocusTarget.Quest(idPart.removePrefix("quest_"))
                            idPart == "free" -> FocusTarget.Free
                            else -> null
                        }
                        if (target != null) onOpenFocus(target) else onOpenRunningScreen()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("● Fokus: ${activeFocusRun.session.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        when (subView) {
            TasksSubView.WEEK -> WeekPlanSection(
                snapshot = snapshot,
                taskStore = taskStore,
                header = header,
                onFocusTask = { onOpenFocus(FocusTarget.Task(it.id)) },
                onJumpToQuests = { subView = TasksSubView.QUESTS },
            )
            TasksSubView.QUESTS -> QuestsSection(
                snapshot = snapshot,
                taskStore = taskStore,
                availableSessions = availableSessions,
                toggl = toggl,
                togglConnected = togglConnected,
                header = header,
                onPreviewChime = onPreviewChime,
                onFocusQuest = { onOpenFocus(FocusTarget.Quest(it.id)) },
            )
            TasksSubView.CALENDAR -> CalendarSection(
                snapshot = snapshot,
                taskStore = taskStore,
                header = header,
            )
            TasksSubView.REVIEW -> WeeklyReviewSection(
                snapshot = snapshot,
                taskStore = taskStore,
                header = header,
            )
        }
    }
}

// ============================================================================
// 1. Woche & Heute (Wochenplaner Mo–So + Tagesansicht + Aufgaben/Untertasks)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekPlanSection(
    snapshot: TaskDataSnapshot,
    taskStore: TaskStore,
    header: @Composable () -> Unit,
    onFocusTask: (TaskItem) -> Unit,
    onJumpToQuests: () -> Unit,
) {
    val today = remember { todayDate() }
    val thisWeekStart = remember(today) { startOfIsoWeek(today) }
    var weekStart by remember { mutableStateOf(thisWeekStart) }
    var todayOnly by remember { mutableStateOf(false) }
    var selectedQuestFilter by remember { mutableStateOf<String?>(null) }
    var showOther by remember { mutableStateOf(true) }

    var quickTitle by remember { mutableStateOf("") }
    var quickQuestId by remember { mutableStateOf<String?>(null) }
    var quickDate by remember { mutableStateOf(today) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }

    val activeQuests = remember(snapshot.quests) {
        snapshot.quests.filter { it.status == QuestStatus.ACTIVE }
    }
    val questsById = remember(snapshot.quests) { snapshot.quests.associateBy { it.id } }

    editingTask?.let { initial ->
        TaskEditorDialog(
            initial = initial,
            quests = activeQuests,
            onDismiss = { editingTask = null },
            onSave = { updated ->
                taskStore.saveTask(updated)
                editingTask = null
            },
            onDelete = {
                taskStore.deleteTask(initial.id)
                editingTask = null
            },
        )
    }

    val weekDays = remember(weekStart) { (0 until 7).map { addDaysToDate(weekStart, it) } }
    val dayEntriesMs = remember(snapshot.entries) {
        snapshot.entries.groupBy { dateOfMillis(it.startMillis) }
            .mapValues { (_, list) -> list.sumOf { it.durationMillis } }
    }

    fun visibleTasksForDate(date: String?): List<TaskItem> =
        snapshot.tasks
            .filter { it.scheduledDate == date }
            .filter { task ->
                val matchesFilter = selectedQuestFilter == null || task.questId == selectedQuestFilter
                val matchesOther = showOther || task.questId != null || selectedQuestFilter != null
                matchesFilter && matchesOther
            }
            .sortedWith(compareBy<TaskItem> { it.done }.thenBy { it.sortOrder }.thenBy { it.updatedAt })

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        // Wochen-Steuerung & Moduswechsel (Woche Mo–So vs. Heute)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { weekStart = addDaysToDate(weekStart, -7) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("←") }
            Text(
                formatWeekHeader(weekStart),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = { weekStart = addDaysToDate(weekStart, 7) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("→") }
            if (weekStart != thisWeekStart) {
                FilledTonalButton(onClick = { weekStart = thisWeekStart }) { Text("Diese Woche") }
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = !todayOnly, onClick = { todayOnly = false }, label = { Text("Woche (Mo–So)") })
            FilterChip(selected = todayOnly, onClick = { todayOnly = true }, label = { Text("Heute") })
        }

        // Quest-Filter & „Sonstiges“-Schalter
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selectedQuestFilter == null,
                onClick = { selectedQuestFilter = null },
                label = { Text("Alle Quests") },
            )
            activeQuests.forEach { q ->
                FilterChip(
                    selected = selectedQuestFilter == q.id,
                    onClick = { selectedQuestFilter = if (selectedQuestFilter == q.id) null else q.id },
                    label = { Text("${q.icon} ${q.title}") },
                )
            }
            FilterChip(
                selected = showOther,
                onClick = { showOther = !showOther },
                label = { Text("Sonstiges") },
            )
            if (activeQuests.isEmpty()) {
                TextButton(onClick = onJumpToQuests) { Text("+ Erste Quest anlegen") }
            }
        }

        // Schnell-Hinzufügen einer Aufgabe
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = quickTitle,
                        onValueChange = { quickTitle = it },
                        label = { Text("Neue Aufgabe …") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (quickTitle.isNotBlank()) {
                                taskStore.addTask(quickTitle, quickQuestId ?: selectedQuestFilter, quickDate)
                                quickTitle = ""
                            }
                        }),
                        modifier = Modifier.weight(1f).widthIn(min = 180.dp),
                    )
                    QuestSelectMenu(
                        selectedId = quickQuestId ?: selectedQuestFilter,
                        quests = activeQuests,
                        onPick = { quickQuestId = it },
                    )
                    DayPickMenu(
                        selectedDate = quickDate,
                        dates = weekDays,
                        today = today,
                        onPick = { quickDate = it },
                    )
                    Button(
                        enabled = quickTitle.isNotBlank(),
                        onClick = {
                            taskStore.addTask(quickTitle, quickQuestId ?: selectedQuestFilter, quickDate)
                            quickTitle = ""
                        },
                    ) { Text("+ Hinzufügen") }
                }
            }
        }

        if (todayOnly) {
            val todayTasks = visibleTasksForDate(today)
            DayColumnCard(
                date = today,
                isToday = true,
                trackedMs = dayEntriesMs[today] ?: 0L,
                tasks = todayTasks,
                questsById = questsById,
                today = today,
                taskStore = taskStore,
                onQuickAddForDay = { title -> taskStore.addTask(title, selectedQuestFilter, today) },
                onFocusTask = onFocusTask,
                onEditTask = { editingTask = it },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Auf breiten Fenstern horizontale 7-Tage-Spalten wie in Quest.app; auf Mobilgeräten vertikal gestapelt.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 720.dp) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        weekDays.forEach { date ->
                            DayColumnCard(
                                date = date,
                                isToday = date == today,
                                trackedMs = dayEntriesMs[date] ?: 0L,
                                tasks = visibleTasksForDate(date),
                                questsById = questsById,
                                today = today,
                                taskStore = taskStore,
                                onQuickAddForDay = { title -> taskStore.addTask(title, selectedQuestFilter, date) },
                                onFocusTask = onFocusTask,
                                onEditTask = { editingTask = it },
                                modifier = Modifier.width(248.dp),
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        weekDays.forEach { date ->
                            DayColumnCard(
                                date = date,
                                isToday = date == today,
                                trackedMs = dayEntriesMs[date] ?: 0L,
                                tasks = visibleTasksForDate(date),
                                questsById = questsById,
                                today = today,
                                taskStore = taskStore,
                                onQuickAddForDay = { title -> taskStore.addTask(title, selectedQuestFilter, date) },
                                onFocusTask = onFocusTask,
                                onEditTask = { editingTask = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        // Ungeplante Aufgaben (Backlog), um Aufgaben jederzeit einem Tag zuweisen zu können
        val backlog = visibleTasksForDate(null)
        if (backlog.isNotEmpty()) {
            Text("Ohne Datum (Backlog)", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                backlog.forEach { task ->
                    TaskItemCard(
                        task = task,
                        quest = task.questId?.let { questsById[it] },
                        today = today,
                        taskStore = taskStore,
                        onFocus = { onFocusTask(task) },
                        onEdit = { editingTask = task },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumnCard(
    date: String,
    isToday: Boolean,
    trackedMs: Long,
    tasks: List<TaskItem>,
    questsById: Map<String, QuestProject>,
    today: String,
    taskStore: TaskStore,
    onQuickAddForDay: (String) -> Unit,
    onFocusTask: (TaskItem) -> Unit,
    onEditTask: (TaskItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (weekday, dayLabel) = remember(date) { formatDayHeader(date) }
    var inlineAddOpen by remember(date) { mutableStateOf(false) }
    var inlineTitle by remember(date) { mutableStateOf("") }

    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isToday) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$weekday ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) Amber else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isToday) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (trackedMs > 0L) {
                    Text(
                        formatHoursShort(trackedMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { inlineAddOpen = !inlineAddOpen },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text(if (inlineAddOpen) "✕" else "+") }
            }
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(if (isToday) Amber else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )

            if (inlineAddOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = inlineTitle,
                        onValueChange = { inlineTitle = it },
                        placeholder = { Text("Aufgabe …") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        enabled = inlineTitle.isNotBlank(),
                        onClick = {
                            onQuickAddForDay(inlineTitle)
                            inlineTitle = ""
                            inlineAddOpen = false
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text("OK") }
                }
            }

            if (tasks.isEmpty()) {
                Text(
                    "Keine Aufgaben",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                tasks.forEach { task ->
                    TaskItemCard(
                        task = task,
                        quest = task.questId?.let { questsById[it] },
                        today = today,
                        taskStore = taskStore,
                        onFocus = { onFocusTask(task) },
                        onEdit = { onEditTask(task) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskItemCard(
    task: TaskItem,
    quest: QuestProject?,
    today: String,
    taskStore: TaskStore,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
) {
    var expandedSubs by remember(task.id) { mutableStateOf(false) }
    var newSubTitle by remember(task.id) { mutableStateOf("") }
    var moveMenuOpen by remember(task.id) { mutableStateOf(false) }
    val accent = quest?.let { rgbColor(it.color) } ?: MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (task.done) accent else Color.Transparent)
                        .border(2.dp, accent, CircleShape)
                        .clickable { taskStore.toggleTaskDone(task.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (task.done) {
                        Text(
                            "✓",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (quest != null && isLightColor(quest.color)) Color.Black else Color.White,
                        )
                    }
                }
                Column(Modifier.weight(1f).clickable { onEdit() }) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    if (quest != null) {
                        Row(
                            Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                            Text(
                                "${quest.icon} ${quest.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (task.subtasks.isNotEmpty() || expandedSubs) {
                val doneCount = task.subtasks.count { it.done }
                Row(
                    Modifier.fillMaxWidth().clickable { expandedSubs = !expandedSubs },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expandedSubs) "▾ Unteraufgaben ($doneCount/${task.subtasks.size})"
                        else "▸ Unteraufgaben ($doneCount/${task.subtasks.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expandedSubs) {
                    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        task.subtasks.forEach { sub ->
                            Row(
                                Modifier.fillMaxWidth().clickable { taskStore.toggleTaskSubtask(task.id, sub.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = sub.done,
                                    onCheckedChange = { taskStore.toggleTaskSubtask(task.id, sub.id) },
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    sub.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { taskStore.deleteTaskSubtask(task.id, sub.id) },
                                    contentPadding = PaddingValues(4.dp),
                                ) { Text("✕", fontSize = 11.sp) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = newSubTitle,
                                onValueChange = { newSubTitle = it },
                                placeholder = { Text("Unteraufgabe …") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = newSubTitle.isNotBlank(),
                                onClick = {
                                    taskStore.addTaskSubtask(task.id, newSubTitle)
                                    newSubTitle = ""
                                },
                            ) { Text("+") }
                        }
                    }
                }
            }

            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onFocus,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("▶ Fokus", style = MaterialTheme.typography.labelSmall) }
                if (task.subtasks.isEmpty() && !expandedSubs) {
                    TextButton(
                        onClick = { expandedSubs = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("+ Untertask", style = MaterialTheme.typography.labelSmall) }
                }
                Box {
                    TextButton(
                        onClick = { moveMenuOpen = true },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) { Text("•••", style = MaterialTheme.typography.labelSmall) }
                    DropdownMenu(expanded = moveMenuOpen, onDismissRequest = { moveMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { moveMenuOpen = false; onEdit() })
                        DropdownMenuItem(
                            text = { Text("Auf Heute verschieben") },
                            onClick = { moveMenuOpen = false; taskStore.rescheduleTask(task.id, today) },
                        )
                        DropdownMenuItem(
                            text = { Text("Auf Morgen verschieben") },
                            onClick = { moveMenuOpen = false; taskStore.rescheduleTask(task.id, addDaysToDate(today, 1)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Auf nächste Woche") },
                            onClick = {
                                moveMenuOpen = false
                                val target = addDaysToDate(task.scheduledDate ?: today, 7)
                                taskStore.rescheduleTask(task.id, target)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Löschen", color = MaterialTheme.colorScheme.error) },
                            onClick = { moveMenuOpen = false; taskStore.deleteTask(task.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestSelectMenu(selectedId: String?, quests: List<QuestProject>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = quests.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selected?.let { "${it.icon} ${it.title}" } ?: "Quest: Sonstiges")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Sonstiges (ohne Quest)") }, onClick = { onPick(null); open = false })
            quests.forEach { q ->
                DropdownMenuItem(
                    text = { Text("${q.icon} ${q.title}") },
                    onClick = { onPick(q.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun DayPickMenu(selectedDate: String?, dates: List<String>, today: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (selectedDate) {
        null -> "Ohne Datum"
        today -> "Heute"
        else -> formatDayHeader(selectedDate).let { (w, d) -> "$w $d" }
    }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Tag: $label") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            dates.forEach { d ->
                val (w, day) = formatDayHeader(d)
                DropdownMenuItem(
                    text = { Text(if (d == today) "$w $day (Heute)" else "$w $day") },
                    onClick = { onPick(d); open = false },
                )
            }
        }
    }
}

@Composable
private fun TaskEditorDialog(
    initial: TaskItem,
    quests: List<QuestProject>,
    onDismiss: () -> Unit,
    onSave: (TaskItem) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var questId by remember { mutableStateOf(initial.questId) }
    var dateText by remember { mutableStateOf(initial.scheduledDate.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aufgabe bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notizen (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuestSelectMenu(questId, quests) { questId = it }
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Datum (JJJJ-MM-TT, leer = Backlog)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    val cleanDate = dateText.trim().takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
                    onSave(initial.copy(title = title.trim(), notes = notes.trim(), questId = questId, scheduledDate = cleanDate))
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

// ============================================================================
// 2. Quests & Side-Quests (Projekte, Unteraufgaben, Toggl-Schalter, Sparkline)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestsSection(
    snapshot: TaskDataSnapshot,
    taskStore: TaskStore,
    availableSessions: List<Session>,
    toggl: TogglTracker,
    togglConnected: Boolean,
    header: @Composable () -> Unit,
    onPreviewChime: (Chime, Boolean) -> Unit,
    onFocusQuest: (QuestProject) -> Unit,
) {
    val today = remember { todayDate() }
    val weekStart = remember(today) { startOfIsoWeek(today) }
    val review = remember(snapshot, weekStart) { taskStore.computeWeekReview(weekStart, today, snapshot) }
    val statsByQuest = remember(review) { review.questStats.associateBy { it.quest.id } }

    var filter by remember { mutableStateOf("active") }
    var editingQuest by remember { mutableStateOf<QuestProject?>(null) }

    editingQuest?.let { initial ->
        QuestEditorDialog(
            initial = initial,
            isNew = snapshot.quests.none { it.id == initial.id },
            availableSessions = availableSessions,
            togglConnected = togglConnected,
            loadProjects = toggl::projects,
            onPreviewChime = onPreviewChime,
            onDismiss = { editingQuest = null },
            onSave = { saved ->
                taskStore.saveQuest(saved)
                editingQuest = null
            },
            onDelete = {
                taskStore.deleteQuest(initial.id)
                editingQuest = null
            },
        )
    }

    val activeCount = snapshot.quests.count { it.status == QuestStatus.ACTIVE }
    val completedCount = snapshot.quests.count { it.status == QuestStatus.COMPLETED }
    val totalAllTimeMs = snapshot.entries.filter { it.questId != null }.sumOf { it.durationMillis }

    val shownQuests = remember(snapshot.quests, filter) {
        when (filter) {
            "side" -> snapshot.quests.filter { it.status == QuestStatus.ACTIVE && it.kind == QuestKind.SIDE_QUEST }
            "completed" -> snapshot.quests.filter { it.status == QuestStatus.COMPLETED }
            else -> snapshot.quests.filter { it.status == QuestStatus.ACTIVE }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).widthIn(min = 180.dp)) {
                Text("Quests & Side-Quests", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Projekte mit Unteraufgaben, eigenen Signalen und wahlweiser Toggl-Erfassung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { editingQuest = QuestProject(title = "") }) { Text("+ Neue Quest") }
        }

        // Übersichts-Leiste (inspiriert von Quest.app)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QuestSummaryStat("Quest-Stunden", formatHoursShort(totalAllTimeMs))
                QuestSummaryStat("In Arbeit", activeCount.toString().padStart(2, '0'))
                QuestSummaryStat("Abgeschlossen", completedCount.toString().padStart(2, '0'))
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == "active", onClick = { filter = "active" }, label = { Text("Aktiv · $activeCount") })
            FilterChip(
                selected = filter == "side",
                onClick = { filter = "side" },
                label = { Text("Side-Quests") },
            )
            FilterChip(
                selected = filter == "completed",
                onClick = { filter = "completed" },
                label = { Text("Abgeschlossen · $completedCount") },
            )
        }

        if (snapshot.quests.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bestimme, was diese Woche zählt", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lege eine Haupt-Quest (z. B. ein Projekt) oder eine Side-Quest an. Du kannst direkt Schnellvorlagen übernehmen:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(onClick = {
                            taskStore.saveQuest(QuestProject(title = "Hauptprojekt", icon = "🚀", color = 0xFFB300, kind = QuestKind.QUEST))
                        }) { Text("🚀 Hauptprojekt") }
                        FilledTonalButton(onClick = {
                            taskStore.saveQuest(QuestProject(title = "Musik üben", icon = "🎸", color = 0x2DD4BF, kind = QuestKind.QUEST))
                        }) { Text("🎸 Musik üben") }
                        FilledTonalButton(onClick = {
                            taskStore.saveQuest(QuestProject(title = "Side-Quest: Lesen", icon = "📚", color = 0xA78BFA, kind = QuestKind.SIDE_QUEST))
                        }) { Text("📚 Side-Quest: Lesen") }
                    }
                }
            }
        } else if (shownQuests.isEmpty()) {
            Text(
                "Keine Quests in dieser Ansicht.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val twoColumns = maxWidth >= 680.dp
                if (twoColumns) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shownQuests.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { quest ->
                                    QuestCard(
                                        quest = quest,
                                        stats = statsByQuest[quest.id],
                                        taskStore = taskStore,
                                        onFocus = { onFocusQuest(quest) },
                                        onEdit = { editingQuest = quest },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shownQuests.forEach { quest ->
                            QuestCard(
                                quest = quest,
                                stats = statsByQuest[quest.id],
                                taskStore = taskStore,
                                onFocus = { onFocusQuest(quest) },
                                onEdit = { editingQuest = quest },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestSummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Amber)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestCard(
    quest: QuestProject,
    stats: QuestWeekStats?,
    taskStore: TaskStore,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rgbColor(quest.color)
    var newSubtask by remember(quest.id) { mutableStateOf("") }
    var showSubs by remember(quest.id) { mutableStateOf(quest.subtasks.isNotEmpty()) }

    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            // Farbiger Akzentstreifen oben wie bei Quest.app
            Box(Modifier.fillMaxWidth().height(4.dp).background(accent))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.18f))
                            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(quest.icon, fontSize = 20.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(quest.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = accent.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    quest.kind.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (quest.description.isNotBlank()) {
                            Text(
                                quest.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Toggl- & Signal-Status pro Quest/Side-Quest
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            if (quest.togglEnabled) {
                                "Toggl ✓" + (quest.togglProject?.name?.let { ": $it" } ?: "")
                            } else {
                                "Nur lokal"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        val sigLabel = when (quest.signalConfig.mode) {
                            FocusMarkerMode.POMODORO -> "Pomodoro ${quest.signalConfig.focusMinutes}/${quest.signalConfig.breakMinutes} min"
                            FocusMarkerMode.SESSION -> "Verknüpfter Timer"
                            FocusMarkerMode.NONE -> "Fokus: still"
                            FocusMarkerMode.PERIODIC -> "Signal alle ${formatDuration(quest.signalConfig.periodicSeconds)}"
                            FocusMarkerMode.MILESTONES -> "${quest.signalConfig.milestones.size} Markierungen"
                        }
                        Text(
                            sigLabel,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                // Unteraufgaben direkt auf der Quest-Karte
                if (showSubs) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val done = quest.subtasks.count { it.done }
                        Text(
                            "Unteraufgaben ($done/${quest.subtasks.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        quest.subtasks.forEach { sub ->
                            Row(
                                Modifier.fillMaxWidth().clickable { taskStore.toggleQuestSubtask(quest.id, sub.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = sub.done,
                                    onCheckedChange = { taskStore.toggleQuestSubtask(quest.id, sub.id) },
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    sub.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { taskStore.deleteQuestSubtask(quest.id, sub.id) },
                                    contentPadding = PaddingValues(4.dp),
                                ) { Text("✕", fontSize = 11.sp) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = newSubtask,
                                onValueChange = { newSubtask = it },
                                placeholder = { Text("Unteraufgabe hinzufügen …") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = newSubtask.isNotBlank(),
                                onClick = {
                                    taskStore.addQuestSubtask(quest.id, newSubtask)
                                    newSubtask = ""
                                },
                            ) { Text("+") }
                        }
                    }
                }

                // Wochenstunden, Gesamtstunden & 8-Wochen-Sparkline
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatHoursShort(stats?.thisWeekMs ?: 0L),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "diese Woche · ${formatHoursShort(stats?.cumulativeMs ?: 0L)} gesamt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SparklineChart(
                        values = stats?.weeklySeries ?: emptyList(),
                        color = accent,
                        modifier = Modifier.width(88.dp).height(32.dp),
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onFocus,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("▶ Fokus") }
                    OutlinedButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("Bearbeiten") }
                    if (!showSubs) {
                        TextButton(onClick = { showSubs = true }) { Text("+ Untertask") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SparklineChart(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxVal = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val ratio = (v.toFloat() / maxVal.toFloat()).coerceIn(0f, 1f)
            val y = size.height - 4f - ratio * (size.height - 8f)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestEditorDialog(
    initial: QuestProject,
    isNew: Boolean,
    availableSessions: List<Session>,
    togglConnected: Boolean,
    loadProjects: suspend () -> List<TogglProject>,
    onPreviewChime: (Chime, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (QuestProject) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var description by remember { mutableStateOf(initial.description) }
    var icon by remember { mutableStateOf(initial.icon) }
    var color by remember { mutableIntStateOf(initial.color) }
    var hexText by remember { mutableStateOf(formatHexColor(initial.color)) }
    var kind by remember { mutableStateOf(initial.kind) }
    var status by remember { mutableStateOf(initial.status) }
    var togglEnabled by remember { mutableStateOf(initial.togglEnabled) }
    var togglProject by remember { mutableStateOf(initial.togglProject) }
    var togglDescription by remember { mutableStateOf(initial.togglDescription) }
    var signalConfig by remember { mutableStateOf(initial.signalConfig) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neue Quest / Side-Quest" else "Quest bearbeiten") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung / Ziel (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Symbol / Emoji", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUEST_ICONS.forEach { emoji ->
                        FilterChip(selected = icon == emoji, onClick = { icon = emoji }, label = { Text(emoji) })
                    }
                }
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 4) icon = it },
                    label = { Text("Eigenes Emoji") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp),
                )

                Text("Farbe", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    QUEST_COLORS.forEach { (label, rgb) ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(rgbColor(rgb))
                                .border(
                                    if (color == rgb) 3.dp else 1.dp,
                                    if (color == rgb) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                                .semantics { contentDescription = label }
                                .clickable {
                                    color = rgb
                                    hexText = formatHexColor(rgb)
                                    signalConfig = signalConfig.copy(flashColor = rgb)
                                },
                        )
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = {
                            hexText = it
                            parseHexColor(it)?.let { c ->
                                color = c
                                signalConfig = signalConfig.copy(flashColor = c)
                            }
                        },
                        label = { Text("#Hex") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                    )
                }

                HorizontalDivider()
                Text("Toggl Track für diese ${kind.label}", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = togglEnabled, onCheckedChange = { togglEnabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (togglEnabled) "Fokus-Läufe in Toggl erfassen" else "Nur im lokalen Kalender speichern",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (togglEnabled) {
                    if (!togglConnected) {
                        Text(
                            "Hinweis: Verbinde oben rechts deinen Toggl-API-Token, damit die Synchronisation startet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                        )
                    }
                    TogglProjectMenu(togglProject, loadProjects) { togglProject = it }
                    OutlinedTextField(
                        value = togglDescription,
                        onValueChange = { togglDescription = it },
                        label = { Text("Toggl-Beschreibung (leer = Task-/Quest-Name)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()
                Text("Fokus-Markierungen & Signale", style = MaterialTheme.typography.titleSmall)
                FocusSignalEditor(
                    config = signalConfig,
                    availableSessions = availableSessions,
                    onChange = { signalConfig = it },
                    onPreviewChime = onPreviewChime,
                )

                if (!isNew) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            status = if (status == QuestStatus.ACTIVE) QuestStatus.COMPLETED else QuestStatus.ACTIVE
                        }) {
                            Text(if (status == QuestStatus.ACTIVE) "✓ Quest abschließen" else "Quest wieder öffnen")
                        }
                        TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            description = description.trim(),
                            icon = icon.ifBlank { "🎯" },
                            color = color,
                            kind = kind,
                            status = status,
                            togglEnabled = togglEnabled,
                            togglProject = togglProject,
                            togglDescription = togglDescription.trim(),
                            signalConfig = signalConfig,
                        ),
                    )
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ============================================================================
// 3. Eigener Hauptreiter „Fokus“ (Pomodoro / Timer-Wahl / Signale / Untertasks)
// ============================================================================

/**
 * Eigenständiger Fokus-Reiter: Der Nutzer kann hier direkt eine Quest, eine Aufgabe oder einen
 * freien Fokus wählen und flexibel bestimmen, ob der Fokus-Bildschirm im Pomodoro-Stil, mit einem
 * vorhandenen Timer aus dem Timer-Reiter, als Takt-Stoppuhr oder völlig ruhig läuft.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusTabScreen(
    target: FocusTarget,
    onSelectTarget: (FocusTarget) -> Unit,
    taskStore: TaskStore,
    availableSessions: List<Session>,
    engine: TimerEngine,
    togglConnected: Boolean,
    onStartFocus: (session: Session, taskId: String?, questId: String?) -> Unit,
    onPreviewChime: (Chime, Boolean) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val snapshot by taskStore.state.collectAsState()
    val runs by engine.runs.collectAsState()

    val task = (target as? FocusTarget.Task)?.let { t -> snapshot.tasks.firstOrNull { it.id == t.taskId } }
    val quest = when (target) {
        is FocusTarget.Task -> task?.questId?.let { qid -> snapshot.quests.firstOrNull { it.id == qid } }
        is FocusTarget.Quest -> snapshot.quests.firstOrNull { it.id == target.questId }
        FocusTarget.Free -> null
    }

    val title = task?.title ?: quest?.title ?: "Freier Fokus"
    val sessionId = when (target) {
        is FocusTarget.Task -> "focus_task_${target.taskId}"
        is FocusTarget.Quest -> "focus_quest_${target.questId}"
        FocusTarget.Free -> "focus_free"
    }
    var freeConfig by remember { mutableStateOf(FocusSignalConfig(mode = FocusMarkerMode.POMODORO)) }
    val initialConfig = task?.signalOverride ?: quest?.signalConfig ?: freeConfig
    var config by remember(sessionId) { mutableStateOf(initialConfig) }
    var showSignalSettings by remember(sessionId) { mutableStateOf(true) }
    var newSubtask by remember(sessionId) { mutableStateOf("") }

    val activeQuests = remember(snapshot.quests) { snapshot.quests.filter { it.status == QuestStatus.ACTIVE } }
    val openTasks = remember(snapshot.tasks, quest?.id) {
        snapshot.tasks
            .filter { !it.done && (quest == null || it.questId == quest.id) }
            .sortedBy { it.scheduledDate ?: "9999-99-99" }
    }

    val runningRun = runs.firstOrNull { it.session.id == sessionId && !it.finished }
        ?: runs.firstOrNull { !it.finished && it.session.id.startsWith("focus_") }

    val today = remember { todayDate() }
    val committedTodayMs = remember(snapshot.entries, target, today) {
        snapshot.entries.filter { e ->
            dateOfMillis(e.startMillis) == today && when (target) {
                is FocusTarget.Task -> e.taskId == target.taskId
                is FocusTarget.Quest -> e.questId == target.questId
                FocusTarget.Free -> e.kind == EntryKind.FOCUS && e.questId == null && e.taskId == null
            }
        }.sumOf { it.durationMillis }
    }

    var nowMono by remember { mutableLongStateOf(monotonicMillis()) }
    LaunchedEffect(runningRun != null) {
        while (runningRun != null) {
            nowMono = monotonicMillis()
            delay(200L)
        }
    }

    val activeElapsedMs = runningRun?.let {
        if (it.session.stopwatch) it.elapsedMs(nowMono) else it.activeMillis(now = nowMono)
    } ?: 0L
    val heroDisplay = if (runningRun != null && !runningRun.session.stopwatch) {
        formatDuration(runningRun.remainingSeconds(nowMono)).removeSuffix(" s")
    } else {
        formatClock(committedTodayMs + activeElapsedMs)
    }

    val pickedSession = remember(config.linkedSessionId, availableSessions) {
        availableSessions.firstOrNull { it.id == config.linkedSessionId }
            ?: availableSessions.firstOrNull { it.name == config.linkedSessionId }
    }
    val accent = quest?.let { rgbColor(it.color) } ?: Amber

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header()

        // Auswahl von Quest & Aufgabe direkt im Fokus-Reiter
        Card(
            Modifier.fillMaxWidth().widthIn(max = 640.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Worauf fokussierst du dich?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusQuestPickerMenu(
                        selectedQuest = quest,
                        quests = activeQuests,
                        onPick = { picked ->
                            onSelectTarget(if (picked == null) FocusTarget.Free else FocusTarget.Quest(picked.id))
                        },
                    )
                    FocusTaskPickerMenu(
                        selectedTask = task,
                        tasks = openTasks,
                        onPick = { picked ->
                            if (picked != null) onSelectTarget(FocusTarget.Task(picked.id))
                            else if (quest != null) onSelectTarget(FocusTarget.Quest(quest.id))
                            else onSelectTarget(FocusTarget.Free)
                        },
                    )
                }
            }
        }

        if (quest != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = accent.copy(alpha = 0.16f),
                modifier = Modifier.border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            ) {
                Text(
                    "${quest.icon} ${quest.title}",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (task != null && task.notes.isNotBlank()) {
            Text(
                task.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Große Fokus-Uhr + nächste Markierung / Intervall-Info
        Text(
            heroDisplay,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = if (runningRun != null && !runningRun.paused) Amber else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            when {
                runningRun == null -> "Heute bisher: ${formatClock(committedTodayMs)}" +
                    if (quest?.togglEnabled == true && togglConnected) " · Toggl aktiv" else " · Lokal im Kalender"
                runningRun.paused -> "Pausiert · Lokal im Kalender"
                runningRun.session.stopwatch && runningRun.session.signalEvery > 0 ->
                    "Nächste Markierung in ${formatDuration(runningRun.remainingSeconds(nowMono))}"
                !runningRun.session.stopwatch ->
                    listOfNotNull(
                        runningRun.step?.name?.takeIf { it.isNotBlank() },
                        "Schritt ${runningRun.index + 1}/${runningRun.session.steps.size}",
                        runningRun.roundLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                else -> "Fokus läuft · ohne Signal"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Fokus-Steuerung
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runningRun == null) {
                Button(
                    onClick = {
                        val session = config.toSession(sessionId, title, quest, pickedSession)
                        onStartFocus(session, task?.id, quest?.id)
                        showSignalSettings = false
                    },
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                ) { Text("▶ Fokus starten") }
            } else {
                OutlinedButton(onClick = { engine.stop(runningRun.id) }) { Text("⏹ Stopp & Speichern") }
                Button(onClick = { engine.togglePause(runningRun.id) }) {
                    Text(if (runningRun.paused) "▶ Weiter" else "⏸ Pause")
                }
                if (!runningRun.session.stopwatch) {
                    OutlinedButton(onClick = { engine.skip(runningRun.id) }) { Text("Überspringen") }
                }
            }
            if (task != null) {
                FilledTonalButton(onClick = {
                    if (runningRun != null) engine.stop(runningRun.id)
                    if (!task.done) taskStore.toggleTaskDone(task.id)
                    onSelectTarget(quest?.let { FocusTarget.Quest(it.id) } ?: FocusTarget.Free)
                }) { Text(if (task.done) "↺ Wieder öffnen" else "✓ Erledigt") }
            }
        }

        // Unteraufgaben direkt im Fokus-Modus abhackbar (wenn Task oder Quest gewählt)
        if (task != null || quest != null) {
            val subtasks = task?.subtasks ?: quest?.subtasks.orEmpty()
            Card(
                Modifier.fillMaxWidth().widthIn(max = 640.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (task != null) "Unteraufgaben dieser Aufgabe" else "Unteraufgaben dieser Quest",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (subtasks.isEmpty()) {
                        Text(
                            "Noch keine Unteraufgaben – teile deinen Fokus in kleine Schritte auf.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    subtasks.forEach { sub ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (task != null) taskStore.toggleTaskSubtask(task.id, sub.id)
                                else if (quest != null) taskStore.toggleQuestSubtask(quest.id, sub.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = sub.done,
                                onCheckedChange = {
                                    if (task != null) taskStore.toggleTaskSubtask(task.id, sub.id)
                                    else if (quest != null) taskStore.toggleQuestSubtask(quest.id, sub.id)
                                },
                            )
                            Text(
                                sub.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newSubtask,
                            onValueChange = { newSubtask = it },
                            label = { Text("Neuer Unterpunkt") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = newSubtask.isNotBlank(),
                            onClick = {
                                if (task != null) taskStore.addTaskSubtask(task.id, newSubtask)
                                else if (quest != null) taskStore.addQuestSubtask(quest.id, newSubtask)
                                newSubtask = ""
                            },
                        ) { Text("+") }
                    }
                }
            }
        }

        // Fokus-Stil (Pomodoro / Timer wählen / Takt / Markierungen / Ohne Signal) & Signale
        Card(
            Modifier.fillMaxWidth().widthIn(max = 640.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showSignalSettings = !showSignalSettings }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fokus-Timer & Signale", style = MaterialTheme.typography.titleSmall)
                        val summary = when (config.mode) {
                            FocusMarkerMode.POMODORO -> "Pomodoro: ${config.focusMinutes} min Fokus / ${config.breakMinutes} min Pause · ${if (config.pomodoroRounds == 0) "endlos" else "${config.pomodoroRounds} Runden"}"
                            FocusMarkerMode.SESSION -> "Timer: ${pickedSession?.name ?: "auswählen"} · ${config.chime.label}"
                            FocusMarkerMode.NONE -> "Ohne Signal (ruhige Stoppuhr)"
                            FocusMarkerMode.PERIODIC -> "Alle ${formatDuration(config.periodicSeconds)} · ${config.chime.label}"
                            FocusMarkerMode.MILESTONES -> "${config.milestones.size} Markierungen · ${config.chime.label}"
                        }
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { showSignalSettings = !showSignalSettings }) {
                        Text(if (showSignalSettings) "Zuklappen" else "Einstellen")
                    }
                }
                if (showSignalSettings) {
                    FocusSignalEditor(
                        config = config,
                        availableSessions = availableSessions,
                        onChange = { next ->
                            config = next
                            if (task != null) taskStore.saveTask(task.copy(signalOverride = next))
                            else if (quest != null) taskStore.saveQuest(quest.copy(signalConfig = next))
                            else freeConfig = next
                        },
                        onPreviewChime = onPreviewChime,
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusQuestPickerMenu(
    selectedQuest: QuestProject?,
    quests: List<QuestProject>,
    onPick: (QuestProject?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selectedQuest?.let { "Quest: ${it.icon} ${it.title}" } ?: "Quest: Freier Fokus")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Freier Fokus (ohne Quest)") }, onClick = { onPick(null); open = false })
            quests.forEach { q ->
                DropdownMenuItem(text = { Text("${q.icon} ${q.title}") }, onClick = { onPick(q); open = false })
            }
        }
    }
}

@Composable
private fun FocusTaskPickerMenu(
    selectedTask: TaskItem?,
    tasks: List<TaskItem>,
    onPick: (TaskItem?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selectedTask?.let { "Aufgabe: ${it.title}" } ?: "Aufgabe: optional")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Keine bestimmte Aufgabe") }, onClick = { onPick(null); open = false })
            tasks.forEach { t ->
                DropdownMenuItem(text = { Text(t.title) }, onClick = { onPick(t); open = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusSignalEditor(
    config: FocusSignalConfig,
    availableSessions: List<Session>,
    onChange: (FocusSignalConfig) -> Unit,
    onPreviewChime: (Chime, Boolean) -> Unit,
) {
    var periodText by remember(config.periodicSeconds) { mutableStateOf(formatDuration(config.periodicSeconds).removeSuffix(" s")) }
    var focusMinText by remember(config.focusMinutes) { mutableStateOf(config.focusMinutes.toString()) }
    var breakMinText by remember(config.breakMinutes) { mutableStateOf(config.breakMinutes.toString()) }
    var roundsText by remember(config.pomodoroRounds) { mutableStateOf(if (config.pomodoroRounds == 0) "4" else config.pomodoroRounds.toString()) }
    var hexText by remember(config.flashColor) { mutableStateOf(formatHexColor(config.flashColor)) }
    var newMarkerSecs by remember { mutableStateOf("15:00") }
    var newMarkerLabel by remember { mutableStateOf("") }
    var sessionMenuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FocusMarkerMode.entries.forEach { m ->
                FilterChip(selected = config.mode == m, onClick = { onChange(config.copy(mode = m)) }, label = { Text(m.label) })
            }
        }

        if (config.mode == FocusMarkerMode.POMODORO) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Triple(25, 5, "25/5"), Triple(50, 10, "50/10"), Triple(15, 3, "15/3")).forEach { (f, b, lbl) ->
                    FilterChip(
                        selected = config.focusMinutes == f && config.breakMinutes == b,
                        onClick = {
                            focusMinText = f.toString()
                            breakMinText = b.toString()
                            onChange(config.copy(focusMinutes = f, breakMinutes = b))
                        },
                        label = { Text("$lbl min") },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = focusMinText,
                    onValueChange = { txt ->
                        focusMinText = txt
                        txt.trim().toIntOrNull()?.takeIf { it in 1..240 }?.let { onChange(config.copy(focusMinutes = it)) }
                    },
                    label = { Text("Fokus (Min)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
                OutlinedTextField(
                    value = breakMinText,
                    onValueChange = { txt ->
                        breakMinText = txt
                        txt.trim().toIntOrNull()?.takeIf { it in 0..120 }?.let { onChange(config.copy(breakMinutes = it)) }
                    },
                    label = { Text("Pause (Min)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
                OutlinedTextField(
                    value = roundsText,
                    onValueChange = { txt ->
                        roundsText = txt
                        txt.trim().toIntOrNull()?.takeIf { it in 1..99 }?.let {
                            if (config.pomodoroRounds != 0) onChange(config.copy(pomodoroRounds = it))
                        }
                    },
                    enabled = config.pomodoroRounds != 0,
                    label = { Text("Runden") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(88.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.pomodoroRounds == 0,
                        onCheckedChange = { endless ->
                            onChange(config.copy(pomodoroRounds = if (endless) 0 else (roundsText.toIntOrNull() ?: 4)))
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Endlos", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (config.mode == FocusMarkerMode.SESSION) {
            val selectedSession = availableSessions.firstOrNull { it.id == config.linkedSessionId }
                ?: availableSessions.firstOrNull { it.name == config.linkedSessionId }
            Box {
                OutlinedButton(onClick = { sessionMenuOpen = true }) {
                    Text(selectedSession?.let { "${it.name} (${it.summary()})" } ?: "Timer / Vorlage wählen …")
                }
                DropdownMenu(expanded = sessionMenuOpen, onDismissRequest = { sessionMenuOpen = false }) {
                    availableSessions.forEach { s ->
                        DropdownMenuItem(
                            text = { Text("${s.name} · ${s.summary()}") },
                            onClick = {
                                onChange(config.copy(linkedSessionId = s.id))
                                sessionMenuOpen = false
                            },
                        )
                    }
                }
            }
            if (availableSessions.isEmpty()) {
                Text(
                    "Noch keine Timer vorhanden – erstelle einen im Reiter „Timer“.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (config.mode == FocusMarkerMode.PERIODIC) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(5 * 60, 15 * 60, 25 * 60, 30 * 60, 60 * 60).forEach { sec ->
                    FilterChip(
                        selected = config.periodicSeconds == sec,
                        onClick = {
                            periodText = formatDuration(sec)
                            onChange(config.copy(periodicSeconds = sec))
                        },
                        label = { Text("${sec / 60} min") },
                    )
                }
                OutlinedTextField(
                    value = periodText,
                    onValueChange = { txt ->
                        periodText = txt
                        parseDuration(txt)?.let { sec -> onChange(config.copy(periodicSeconds = sec)) }
                    },
                    label = { Text("Min:Sek / Sek") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
            }
        }

        if (config.mode == FocusMarkerMode.MILESTONES) {
            config.milestones.forEachIndexed { idx, marker ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${idx + 1}. ${formatDuration(marker.seconds)}", fontWeight = FontWeight.Medium)
                    Text(marker.label.ifBlank { "Markierung" }, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (config.milestones.size > 1) {
                        TextButton(onClick = {
                            onChange(config.copy(milestones = config.milestones.filterIndexed { i, _ -> i != idx }))
                        }) { Text("✕") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = newMarkerSecs,
                    onValueChange = { newMarkerSecs = it },
                    label = { Text("Dauer") },
                    singleLine = true,
                    modifier = Modifier.width(96.dp),
                )
                OutlinedTextField(
                    value = newMarkerLabel,
                    onValueChange = { newMarkerLabel = it },
                    label = { Text("Bezeichnung") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = parseDuration(newMarkerSecs) != null,
                    onClick = {
                        val secs = parseDuration(newMarkerSecs) ?: return@Button
                        onChange(config.copy(milestones = config.milestones + FocusMarker(secs, newMarkerLabel.trim())))
                        newMarkerLabel = ""
                    },
                ) { Text("+") }
            }
        }

        Text("Vibration", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VIBRATION_CHOICES.forEach { secs ->
                FilterChip(
                    selected = config.vibration == secs,
                    onClick = { onChange(config.copy(vibration = secs)) },
                    label = { Text(if (secs == 0) "Nur Licht" else "$secs s") },
                )
            }
        }

        Text("Leuchtfarbe", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            FLASH_COLORS.forEach { (label, rgb) ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(rgbColor(rgb))
                        .border(
                            if (config.flashColor == rgb) 3.dp else 1.dp,
                            if (config.flashColor == rgb) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .semantics { contentDescription = label }
                        .clickable {
                            hexText = formatHexColor(rgb)
                            onChange(config.copy(flashColor = rgb))
                        },
                )
            }
            OutlinedTextField(
                value = hexText,
                onValueChange = {
                    hexText = it
                    parseHexColor(it)?.let { rgb -> onChange(config.copy(flashColor = rgb)) }
                },
                label = { Text("#Hex") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }

        Text("Klang / Piepen", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToneMode.entries.forEach { mode ->
                FilterChip(
                    selected = config.tone == mode,
                    onClick = { onChange(config.copy(tone = mode)) },
                    label = { Text(mode.label) },
                )
            }
        }
        if (config.tone != ToneMode.OFF) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chime.entries.forEach { c ->
                    FilterChip(
                        selected = config.chime == c,
                        onClick = {
                            onChange(config.copy(chime = c))
                            onPreviewChime(c, config.tone == ToneMode.END)
                        },
                        label = { Text(c.label) },
                    )
                }
            }
        }
    }
}

// ============================================================================
// 4. Lokaler Kalender + JSON-Export & Multi-JSON-Zusammenführung
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarSection(
    snapshot: TaskDataSnapshot,
    taskStore: TaskStore,
    header: @Composable () -> Unit,
) {
    val today = remember { todayDate() }
    val thisWeekStart = remember(today) { startOfIsoWeek(today) }
    var weekStart by remember { mutableStateOf(thisWeekStart) }
    var showAllEntries by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var editingEntry by remember { mutableStateOf<CalendarEntry?>(null) }
    var showRawJsonDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }

    val fileActions = rememberJsonFileActions(
        onImported = { contents ->
            val report = taskStore.mergeJsonStrings(contents)
            statusBanner = report.summaryText()
        },
        onStatus = { statusBanner = it },
    )

    editingEntry?.let { initial ->
        CalendarEntryEditorDialog(
            initial = initial,
            quests = snapshot.quests,
            isNew = snapshot.entries.none { it.id == initial.id },
            onDismiss = { editingEntry = null },
            onSave = { saved ->
                taskStore.saveEntry(saved)
                editingEntry = null
            },
            onDelete = {
                taskStore.deleteEntry(initial.id)
                editingEntry = null
            },
        )
    }

    if (showRawJsonDialog) {
        val jsonText = remember(snapshot) { taskStore.exportJson() }
        AlertDialog(
            onDismissRequest = { showRawJsonDialog = false },
            title = { Text("Lokaler Kalender (JSON)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Dieser JSON-Snapshot enthält alle Quests, Aufgaben und lokalen Kalendereinträge.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = {},
                        readOnly = true,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                if (fileActions.canPickFiles) {
                    Button(onClick = {
                        fileActions.exportJson("wassertiger-kalender-$today.json", jsonText)
                        showRawJsonDialog = false
                    }) { Text("Als Datei speichern") }
                } else {
                    Button(onClick = { showRawJsonDialog = false }) { Text("Fertig") }
                }
            },
            dismissButton = { TextButton(onClick = { showRawJsonDialog = false }) { Text("Schließen") } },
        )
    }

    if (showMergeDialog) {
        JsonMergeDialog(
            canPickFiles = fileActions.canPickFiles,
            onPickFiles = {
                showMergeDialog = false
                fileActions.importJsons()
            },
            onMergeTextPayloads = { payloads ->
                val report = taskStore.mergeJsonStrings(payloads)
                statusBanner = report.summaryText()
                showMergeDialog = false
            },
            onDismiss = { showMergeDialog = false },
        )
    }

    val weekDays = remember(weekStart) { (0 until 7).map { addDaysToDate(weekStart, it) } }
    val filteredEntries = remember(snapshot.entries, weekStart, showAllEntries) {
        if (showAllEntries) snapshot.entries
        else snapshot.entries.filter { dateOfMillis(it.startMillis) in weekDays }
    }
    val totalMs = filteredEntries.sumOf { it.durationMillis }
    val grouped = remember(filteredEntries) { filteredEntries.groupBy { dateOfMillis(it.startMillis) } }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { header() }
        item {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!showAllEntries) {
                    OutlinedButton(
                        onClick = { weekStart = addDaysToDate(weekStart, -7) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("←") }
                    Text(formatWeekHeader(weekStart), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { weekStart = addDaysToDate(weekStart, 7) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("→") }
                    if (weekStart != thisWeekStart) {
                        FilledTonalButton(onClick = { weekStart = thisWeekStart }) { Text("Diese Woche") }
                    }
                } else {
                    Text("Alle lokalen Einträge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                FilterChip(selected = showAllEntries, onClick = { showAllEntries = !showAllEntries }, label = { Text("Alle Zeiten") })
                Text("Gesamt: ${formatHoursShort(totalMs)}", style = MaterialTheme.typography.labelLarge, color = Amber)
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val now = wallClockMillis()
                    editingEntry = CalendarEntry(
                        title = "Arbeitssession",
                        startMillis = now - 30 * 60_000L,
                        endMillis = now,
                        durationMillis = 30 * 60_000L,
                        kind = EntryKind.MANUAL,
                    )
                }) { Text("+ Zeit eintragen") }
                FilledTonalButton(onClick = {
                    if (fileActions.canPickFiles) {
                        fileActions.exportJson("wassertiger-kalender-$today.json", taskStore.exportJson())
                    } else {
                        showRawJsonDialog = true
                    }
                }) { Text("JSON exportieren") }
                OutlinedButton(onClick = { showMergeDialog = true }) { Text("JSONs zusammenfügen") }
                TextButton(onClick = { showRawJsonDialog = true }) { Text("JSON ansehen") }
            }
        }

        statusBanner?.let { msg ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2C00)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { statusBanner = null }) { Text("OK") }
                    }
                }
            }
        }

        if (filteredEntries.isEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        "Noch keine Einträge in diesem Zeitraum. Jeder Timer, jede Stoppuhr, jede Musik-Session und jeder Fokus-Lauf wird hier automatisch lokal gespeichert.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            val orderedDates = grouped.keys.sortedDescending()
            orderedDates.forEach { date ->
                val entries = grouped[date].orEmpty()
                val daySum = entries.sumOf { it.durationMillis }
                val (w, d) = formatDayHeader(date)
                item(key = "hdr_$date") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (date == today) "$w $d · Heute" else "$w $d",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (date == today) Amber else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatHoursShort(daySum), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    CalendarEntryRow(entry = entry, onClick = { editingEntry = entry })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarEntryRow(entry: CalendarEntry, onClick: () -> Unit) {
    val dotColor = entry.questColor?.let(::rgbColor) ?: MaterialTheme.colorScheme.outline
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${clockTime(entry.startMillis)}–${clockTime(entry.endMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(entry.kind.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    entry.questTitle?.let { qTitle ->
                        Surface(shape = RoundedCornerShape(4.dp), color = dotColor.copy(alpha = 0.16f)) {
                            Text(qTitle, style = MaterialTheme.typography.labelSmall, color = dotColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    if (entry.togglSynced) {
                        Text("Toggl ✓", style = MaterialTheme.typography.labelSmall, color = DoneGreen)
                    }
                    if (entry.aborted) {
                        Text("abgebrochen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                formatDuration((entry.durationMillis / 1000L).toInt().coerceAtLeast(1)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Amber,
            )
        }
    }
}

@Composable
private fun CalendarEntryEditorDialog(
    initial: CalendarEntry,
    quests: List<QuestProject>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CalendarEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(dateOfMillis(initial.startMillis)) }
    var startTime by remember { mutableStateOf(clockTime(initial.startMillis)) }
    var endTime by remember { mutableStateOf(clockTime(initial.endMillis)) }
    var questId by remember { mutableStateOf(initial.questId) }

    val startMs = combineDateAndTime(date, startTime)
    val endMs = combineDateAndTime(date, endTime)
    val valid = title.isNotBlank() && startMs != null && endMs != null && endMs > startMs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Zeit im Kalender eintragen" else "Kalendereintrag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Beschreibung / Titel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                QuestSelectMenu(questId, quests) { questId = it }
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Datum (JJJJ-MM-TT)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startTime,
                        onValueChange = { startTime = it },
                        label = { Text("Start (HH:MM)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endTime,
                        onValueChange = { endTime = it },
                        label = { Text("Ende (HH:MM)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val s = startMs!!
                    val e = endMs!!
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            startMillis = s,
                            endMillis = e,
                            durationMillis = e - s,
                            questId = questId,
                        ),
                    )
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isNew) TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun JsonMergeDialog(
    canPickFiles: Boolean,
    onPickFiles: () -> Unit,
    onMergeTextPayloads: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val snippets = remember { mutableStateListOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mehrere JSONs zusammenfügen") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Wähle mehrere exportierte Kalender-JSON-Dateien aus oder füge unten mehrere JSON-Inhalte ein. Duplikate werden automatisch erkannt und zusammengeführt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (canPickFiles) {
                    Button(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
                        Text("JSON-Dateien auswählen (Mehrfachauswahl)")
                    }
                    HorizontalDivider()
                }
                snippets.forEachIndexed { i, text ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = { snippets[i] = it },
                        label = { Text("JSON #${i + 1}") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { snippets.add("") }) { Text("+ Weiteres JSON-Feld hinzufügen") }
            }
        },
        confirmButton = {
            val nonEmpty = snippets.map { it.trim() }.filter { it.isNotEmpty() }
            Button(enabled = nonEmpty.isNotEmpty(), onClick = { onMergeTextPayloads(nonEmpty) }) {
                Text("Zusammenführen")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ============================================================================
// 5. Wochenauswertung (Rückblick, Tagesbalken, Quest-Split, 8-Wochen-Trend)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeeklyReviewSection(
    snapshot: TaskDataSnapshot,
    taskStore: TaskStore,
    header: @Composable () -> Unit,
) {
    val today = remember { todayDate() }
    val thisWeekStart = remember(today) { startOfIsoWeek(today) }
    var weekStart by remember { mutableStateOf(thisWeekStart) }
    var editingTarget by remember { mutableStateOf(false) }

    val review = remember(snapshot, weekStart) { taskStore.computeWeekReview(weekStart, today, snapshot) }
    val questSharePct = if (review.totalMs > 0L) ((review.questMs * 100) / review.totalMs).toInt() else 0

    if (editingTarget) {
        var targetInput by remember { mutableStateOf(review.targetHours.toString()) }
        AlertDialog(
            onDismissRequest = { editingTarget = false },
            title = { Text("Wochenziel (Quest-Stunden)") },
            text = {
                OutlinedTextField(
                    value = targetInput,
                    onValueChange = { targetInput = it },
                    label = { Text("Stunden pro Woche") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            },
            confirmButton = {
                val parsed = targetInput.trim().toIntOrNull()?.takeIf { it in 1..168 }
                Button(enabled = parsed != null, onClick = {
                    taskStore.setWeeklyTargetHours(parsed!!)
                    editingTarget = false
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editingTarget = false }) { Text("Abbrechen") } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).widthIn(min = 180.dp)) {
                Text("Wochenrückblick", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Wie viel deiner Woche deine Quests vorangebracht hat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { weekStart = addDaysToDate(weekStart, -7) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("←") }
            Text(formatWeekHeader(weekStart), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { weekStart = addDaysToDate(weekStart, 7) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("→") }
            if (weekStart != thisWeekStart) {
                FilledTonalButton(onClick = { weekStart = thisWeekStart }) { Text("Heute") }
            }
        }

        // Top 3 Kennzahlen-Karten
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReviewStatCard(
                        label = "Quest-Stunden",
                        value = formatHoursDecimal(review.questMs),
                        sub = "${review.targetPercent} % von ${review.targetHours} Std. Ziel",
                        accent = true,
                        onClick = { editingTarget = true },
                        modifier = Modifier.weight(1f),
                    )
                    ReviewStatCard(
                        label = "Tagesdurchschnitt",
                        value = formatHoursDecimal(review.dailyAverageMs),
                        sub = "Quest-Zeit pro Tag",
                        modifier = Modifier.weight(1f),
                    )
                    ReviewStatCard(
                        label = "Quest-Anteil",
                        value = "$questSharePct %",
                        sub = "Gesamt: ${formatHoursShort(review.totalMs)}",
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewStatCard(
                        label = "Quest-Stunden",
                        value = formatHoursDecimal(review.questMs),
                        sub = "${review.targetPercent} % von ${review.targetHours} Std. Ziel (tippen zum Ändern)",
                        accent = true,
                        onClick = { editingTarget = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewStatCard("Tagesdurchschnitt", formatHoursDecimal(review.dailyAverageMs), "pro Tag", modifier = Modifier.weight(1f))
                        ReviewStatCard("Quest-Anteil", "$questSharePct %", formatHoursShort(review.totalMs), modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Balkendiagramm: Quest-Stunden pro Tag (Mo–So)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Quest-Stunden · pro Tag", style = MaterialTheme.typography.titleSmall)
                val maxDayMs = review.days.maxOfOrNull { it.questMs }?.coerceAtLeast(1L) ?: 1L
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    review.days.forEach { d ->
                        val isToday = d.date == today
                        val fraction = (d.questMs.toFloat() / maxDayMs.toFloat()).coerceIn(0f, 1f)
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text(
                                if (d.questMs > 0L) formatHoursShort(d.questMs).removeSuffix(" Std.") else "–",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToday) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier
                                        .widthIn(max = 28.dp)
                                        .fillMaxWidth(0.75f)
                                        .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (isToday) Amber else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                d.weekday,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isToday) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Aufteilung nach Quest + Split-Balken (Quest-Arbeit vs. Sonstiges)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stunden nach Quest", style = MaterialTheme.typography.titleSmall)
                val activeStats = review.questStats.sortedByDescending { it.thisWeekMs }
                val maxQuestMs = activeStats.maxOfOrNull { it.thisWeekMs }?.coerceAtLeast(1L) ?: 1L
                if (activeStats.isEmpty()) {
                    Text("Noch keine Quests angelegt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    activeStats.forEach { qs ->
                        val ratio = (qs.thisWeekMs.toFloat() / maxQuestMs.toFloat()).coerceIn(0f, 1f)
                        val qColor = rgbColor(qs.quest.color)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${qs.quest.icon} ${qs.quest.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(formatHoursShort(qs.thisWeekMs), style = MaterialTheme.typography.labelMedium)
                            }
                            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                if (qs.thisWeekMs > 0L) {
                                    Box(Modifier.fillMaxWidth(ratio.coerceAtLeast(0.02f)).fillMaxHeight().background(qColor))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Split-Balken: Quest-Arbeit vs. Sonstiges (Timer/Stoppuhr/Musik)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quest-Arbeit vs. Sonstiges", style = MaterialTheme.typography.titleSmall)
                val questFrac = if (review.totalMs > 0L) (review.questMs.toFloat() / review.totalMs.toFloat()).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (questFrac > 0f) {
                        Box(Modifier.fillMaxWidth(questFrac).fillMaxHeight().background(Amber))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("● Quest-Arbeit: ${formatHoursShort(review.questMs)}", style = MaterialTheme.typography.labelMedium, color = Amber)
                    Text("● Sonstiges: ${formatHoursShort(review.otherMs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 8-Wochen-Verlauf
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val avgLatest = review.trend.lastOrNull()?.rollingAverageMs ?: 0L
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quest-Stunden · letzte 8 Wochen", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("Ø ${formatHoursDecimal(avgLatest)} / Woche", style = MaterialTheme.typography.labelMedium, color = Amber)
                }
                val maxTrend = review.trend.maxOfOrNull { it.questMs }?.coerceAtLeast(1L) ?: 1L
                Row(
                    Modifier.fillMaxWidth().height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    review.trend.forEach { pt ->
                        val isSelected = pt.weekStart == weekStart
                        val fraction = (pt.questMs.toFloat() / maxTrend.toFloat()).coerceIn(0f, 1f)
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier
                                        .widthIn(max = 26.dp)
                                        .fillMaxWidth(0.7f)
                                        .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (isSelected) Amber else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(pt.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewStatCard(
    label: String,
    value: String,
    sub: String,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (accent) Amber else MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
