package com.example.wassertiger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Unterscheidet zwischen übergeordneten Haupt-Quests (Projekte/Ziele) und leichtgewichtigen
 * Side-Quests, damit beides in der Übersicht getrennt gefiltert und unterschiedlich markiert werden kann.
 */
enum class QuestKind(val label: String) {
    QUEST("Haupt-Quest"),
    SIDE_QUEST("Side-Quest"),
}

/** Status einer Quest: aktive Quests erscheinen in Planer und Filter; abgeschlossene bleiben für Berichte erhalten. */
enum class QuestStatus(val label: String) {
    ACTIVE("Aktiv"),
    COMPLETED("Abgeschlossen"),
}

/**
 * Signal-Modus für den Fokus-Timer:
 * - [POMODORO]: Klassischer Fokus-/Pausen-Rhythmus mit frei wählbaren Minuten und Runden.
 * - [SESSION]: Übernimmt eine bestehende Timer-Session (oder Vorlage) als Fokus-Ablauf.
 * - [PERIODIC]: Zählt hoch und gibt alle N Sekunden ein Wassertiger-Signal (Vibration, Leuchten, Klang).
 * - [MILESTONES]: Spielt definierte Intervall-Markierungen (z. B. 25 min Fokus → 5 min Pause) nacheinander ab.
 * - [NONE]: Zählt still hoch wie eine ruhige Stoppuhr.
 */
enum class FocusMarkerMode(val label: String) {
    POMODORO("Pomodoro-Stil"),
    SESSION("Timer wählen"),
    PERIODIC("Alle …"),
    MILESTONES("Markierungen"),
    NONE("Ohne Signal"),
}

/** Eine einzelne Zeit-Markierung innerhalb des Fokus-Modus (z. B. 25 min „Deep Work“). */
data class FocusMarker(
    val seconds: Int,
    val label: String = "",
)

/**
 * Bündelt die Wassertiger-typischen Signale (Vibration, Leuchtfarbe, Klang) sowie den Markierungs-Takt
 * für den Fokus-Modus – konfigurierbar pro Quest, pro Task oder frei im Fokus-Reiter.
 */
data class FocusSignalConfig(
    val mode: FocusMarkerMode = FocusMarkerMode.PERIODIC,
    val periodicSeconds: Int = 25 * 60,
    val focusMinutes: Int = 25,
    val breakMinutes: Int = 5,
    val pomodoroRounds: Int = 4,
    val linkedSessionId: String? = null,
    val milestones: List<FocusMarker> = listOf(FocusMarker(25 * 60, "Fokus"), FocusMarker(5 * 60, "Pause")),
    val vibration: Int = 1,
    val flashColor: Int = AMBER_RGB,
    val tone: ToneMode = ToneMode.END,
    val chime: Chime = Chime.BELL,
) {
    /**
     * Erzeugt aus dieser Fokus-Konfiguration eine ausführbare [Session] für [TimerEngine] / Android-[TimerService].
     * Übernimmt optional eine verknüpfte [pickedSession] sowie die Quest-Zuordnung ([quest]) inkl. Toggl.
     */
    fun toSession(
        sessionId: String,
        title: String,
        quest: QuestProject?,
        pickedSession: Session? = null,
    ): Session {
        val cleanTitle = title.ifBlank { quest?.title ?: "Fokus" }
        val togglActive = quest?.togglEnabled == true
        val togglDesc = quest?.togglDescription?.trim()?.ifBlank { cleanTitle } ?: cleanTitle
        val common = Session(
            id = sessionId,
            name = cleanTitle,
            steps = listOf(Step(25 * 60)),
            vibration = vibration,
            tone = tone,
            chime = chime,
            flashColor = flashColor,
            toggl = togglActive,
            togglDescription = togglDesc,
            togglProject = quest?.togglProject,
            questId = quest?.id,
        )
        return when (mode) {
            FocusMarkerMode.POMODORO -> {
                val focusSecs = focusMinutes.coerceAtLeast(1) * 60
                val breakSecs = breakMinutes.coerceAtLeast(0) * 60
                val pSteps = if (breakSecs > 0) {
                    listOf(Step(focusSecs, "Fokus"), Step(breakSecs, "Pause", track = false))
                } else {
                    listOf(Step(focusSecs, "Fokus"))
                }
                common.copy(
                    steps = pSteps,
                    rounds = pomodoroRounds.coerceAtLeast(0),
                    stopwatch = false,
                    signalEvery = 0,
                )
            }
            FocusMarkerMode.SESSION -> {
                if (pickedSession != null) {
                    pickedSession.copy(
                        id = sessionId,
                        name = cleanTitle,
                        template = false,
                        vibration = vibration,
                        tone = tone,
                        chime = chime,
                        flashColor = flashColor,
                        toggl = togglActive,
                        togglDescription = togglDesc,
                        togglProject = quest?.togglProject,
                        questId = quest?.id,
                    )
                } else {
                    common.copy(steps = listOf(Step(25 * 60, "Fokus")), rounds = 1)
                }
            }
            FocusMarkerMode.NONE -> common.copy(
                steps = stopwatchSteps(0),
                rounds = ENDLESS,
                stopwatch = true,
                signalEvery = 0,
            )
            FocusMarkerMode.PERIODIC -> {
                val interval = periodicSeconds.coerceAtLeast(1)
                common.copy(
                    steps = stopwatchSteps(interval),
                    rounds = ENDLESS,
                    stopwatch = true,
                    signalEvery = interval,
                )
            }
            FocusMarkerMode.MILESTONES -> {
                val steps = milestones
                    .filter { it.seconds > 0 }
                    .map { Step(seconds = it.seconds, name = it.label) }
                    .ifEmpty { listOf(Step(25 * 60, "Fokus")) }
                common.copy(
                    steps = steps,
                    rounds = 1,
                    stopwatch = false,
                    signalEvery = 0,
                )
            }
        }
    }
}

/** Abhakbarer Unterpunkt für eine Quest oder einen einzelnen Task. */
data class SubTask(
    val id: String = newTaskId(),
    val title: String,
    val done: Boolean = false,
)

/**
 * Projekt- bzw. Quest-Modell: bündelt Aufgaben, Untertasks, Farbe/Emoji, Fokus-Signale sowie die
 * eigene Toggl-Entscheidung (ob diese Quest bzw. Side-Quest in Toggl erfasst werden soll).
 */
data class QuestProject(
    val id: String = newTaskId(),
    val title: String,
    val description: String = "",
    val icon: String = "🎯",
    val color: Int = AMBER_RGB,
    val kind: QuestKind = QuestKind.QUEST,
    val status: QuestStatus = QuestStatus.ACTIVE,
    val subtasks: List<SubTask> = emptyList(),
    val togglEnabled: Boolean = false,
    val togglProject: TogglProject? = null,
    val togglDescription: String = "",
    val signalConfig: FocusSignalConfig = FocusSignalConfig(flashColor = color),
    val updatedAt: Long = wallClockMillis(),
)

/** Eine planbare Aufgabe für die Wochen-/Tagesansicht, optional einer [questId] und Unteraufgaben zugeordnet. */
data class TaskItem(
    val id: String = newTaskId(),
    val title: String,
    val notes: String = "",
    val questId: String? = null,
    /** Geplantes Datum als "YYYY-MM-DD", oder null für ungeplant / Backlog. */
    val scheduledDate: String? = null,
    val done: Boolean = false,
    val completedAt: Long? = null,
    val subtasks: List<SubTask> = emptyList(),
    val signalOverride: FocusSignalConfig? = null,
    val sortOrder: Int = 0,
    val updatedAt: Long = wallClockMillis(),
)

/** Herkunft eines Eintrags im lokalen Geräte-Kalender. */
enum class EntryKind(val label: String) {
    FOCUS("Fokus"),
    TIMER("Timer"),
    STOPWATCH("Stoppuhr"),
    MUSIC("Musik"),
    MANUAL("Manuell"),
}

/**
 * Ein Zeiteintrag im lokalen Kalender: wird automatisch für jeden beendeten/gestoppten Timer,
 * jede Stoppuhr, jede Musik-Session und jeden Fokus-Lauf gespeichert (unabhängig von Toggl)
 * oder manuell angelegt.
 */
data class CalendarEntry(
    val id: String = newTaskId(),
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val durationMillis: Long,
    val kind: EntryKind,
    val sessionId: String? = null,
    val taskId: String? = null,
    val questId: String? = null,
    val questTitle: String? = null,
    val questColor: Int? = null,
    val togglSynced: Boolean = false,
    val aborted: Boolean = false,
    val updatedAt: Long = wallClockMillis(),
)

/** Gesamtzustand des Aufgaben-, Quest- und Kalender-Moduls. */
data class TaskDataSnapshot(
    val quests: List<QuestProject> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val entries: List<CalendarEntry> = emptyList(),
    val weeklyTargetHours: Int = 20,
)

/** Ergebnisbericht beim Zusammenführen einer oder mehrerer JSON-Dateien. */
data class MergeReport(
    val importedFiles: Int,
    val addedQuests: Int,
    val updatedQuests: Int,
    val addedTasks: Int,
    val updatedTasks: Int,
    val addedEntries: Int,
    val updatedEntries: Int,
    val skippedDuplicates: Int,
    val failedInputs: Int,
) {
    fun summaryText(): String = buildString {
        append("$importedFiles JSON")
        if (importedFiles != 1) append("s")
        append(" geprüft: ")
        append("+$addedEntries Kalendereinträge")
        if (addedQuests > 0) append(", +$addedQuests Quests")
        if (addedTasks > 0) append(", +$addedTasks Aufgaben")
        val updates = updatedQuests + updatedTasks + updatedEntries
        if (updates > 0) append(", $updates aktualisiert")
        if (skippedDuplicates > 0) append(", $skippedDuplicates Duplikate übersprungen")
        if (failedInputs > 0) append(" ($failedInputs ungültig)")
    }
}

/** Tages-Summen (Mo–So) für die Wochenauswertung. */
data class DayHours(
    val date: String,
    val weekday: String,
    val dayLabel: String,
    val questMs: Long,
    val otherMs: Long,
) {
    val totalMs: Long get() = questMs + otherMs
}

/** Aggregierte Quest-Stunden für die Karten und den Wochenrückblick. */
data class QuestWeekStats(
    val quest: QuestProject,
    val thisWeekMs: Long,
    val cumulativeMs: Long,
    val weeklySeries: List<Long>,
)

/** Ein Punkt im 8-Wochen-Trend. */
data class WeekTrendPoint(
    val weekStart: String,
    val label: String,
    val questMs: Long,
    val totalMs: Long,
    val rollingAverageMs: Long,
)

/** Komplettes Auswertungspaket für eine gewählte Woche. */
data class WeekReviewData(
    val weekStart: String,
    val questMs: Long,
    val otherMs: Long,
    val totalMs: Long,
    val dailyAverageMs: Long,
    val targetHours: Int,
    val targetPercent: Int,
    val days: List<DayHours>,
    val questStats: List<QuestWeekStats>,
    val trend: List<WeekTrendPoint>,
)

/** Vorauswahl an ausdrucksstarken Quest-Farben passend zum dunklen Wassertiger-Theme. */
val QUEST_COLORS = listOf(
    "Bernstein" to AMBER_RGB,
    "Aquamarin" to 0x2DD4BF,
    "Koralle" to 0xFB7185,
    "Kobalt" to 0x60A5FA,
    "Lavendel" to 0xA78BFA,
    "Smaragd" to 0x4ADE80,
    "Pfirsich" to 0xFB923C,
)

/** Voreingestellte Emojis für Quests und Side-Quests. */
val QUEST_ICONS = listOf("🎯", "🚀", "📚", "🎸", "💻", "🏃", "🧠", "🛠️", "✨", "🌿", "📝", "🔥")

/** Formatiert Millisekunden als dezimale Stunden (z. B. „2,5 Std.“) oder Minuten unter einer Stunde. */
fun formatHoursShort(ms: Long): String {
    if (ms <= 0L) return "0,0 Std."
    if (ms < 60_000L) return "${ms / 1000} s"
    if (ms < 3_600_000L) return "${ms / 60_000} min"
    val tenths = ((ms / 360_000.0) + 0.05).toInt()
    return "${tenths / 10},${tenths % 10} Std."
}

/** Formatiert Millisekunden immer als Stunden mit einer Nachkommastelle (z. B. „0,4 Std.“). */
fun formatHoursDecimal(ms: Long): String {
    val tenths = ((ms.coerceAtLeast(0L) / 360_000.0) + 0.05).toInt()
    return "${tenths / 10},${tenths % 10} Std."
}

/** Kontext für eine dynamisch aus Aufgabe/Quest gestartete Fokus-Session. */
data class FocusSessionContext(
    val session: Session,
    val taskId: String?,
    val questId: String?,
)

/**
 * Verwaltet Quests, Aufgaben, Unteraufgaben und den lokalen Kalender.
 *
 * Alle Timer-, Stoppuhr-, Musik- und Fokus-Läufe werden über [onTimerEvent] automatisch in
 * den lokalen Kalender eingetragen, sobald ein Lauf endet oder gestoppt wird.
 * Zusätzlich ermöglicht [exportJson] / [mergeJsonStrings] den verlustfreien Austausch zwischen Geräten.
 */
class TaskStore(private val storage: SessionStorage) {

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<TaskDataSnapshot> = _state.asStateFlow()

    /** Aktive und kürzlich gestartete Fokus-Sessions nach [Session.id] (wird auch von Android-TimerService genutzt). */
    private val focusSessions = mutableMapOf<String, FocusSessionContext>()

    /** Merkt sich bereits verbuchte [RunState.id]s, um Doppelbuchungen bei wiederholten Events zu verhindern. */
    private val recordedRunIds = mutableSetOf<Long>()

    private fun loadInitial(): TaskDataSnapshot {
        val raw = storage.read() ?: return TaskDataSnapshot()
        val parsed = parseSnapshot(raw)
        if (parsed == null) {
            storage.keepBroken(raw)
            return TaskDataSnapshot()
        }
        return parsed
    }

    private fun update(transform: (TaskDataSnapshot) -> TaskDataSnapshot) {
        val next = transform(_state.value)
        _state.value = next
        storage.write(serializeSnapshot(next))
    }

    // --- Fokus-Verknüpfung & automatische Kalender-Erfassung ----------------

    /** Registriert eine dynamisch erzeugte Fokus-[session], damit Plattform-Services und Kalender sie kennen. */
    fun registerFocusSession(session: Session, taskId: String?, questId: String?) {
        focusSessions[session.id] = FocusSessionContext(session, taskId, questId)
    }

    /** Liefert eine registrierte Fokus-[Session] anhand ihrer ID (z. B. für Android [TimerService]). */
    fun focusSession(sessionId: String): Session? = focusSessions[sessionId]?.session

    /**
     * Zeichnet jeden beendeten oder gestoppten Lauf im lokalen Geräte-Kalender auf –
     * unabhängig davon, ob Toggl aktiviert ist oder nicht.
     */
    fun onTimerEvent(event: TimerEvent, run: RunState, togglSynced: Boolean = false) {
        if (event != TimerEvent.Finished && event != TimerEvent.Stopped) return
        if (!recordedRunIds.add(run.id)) return

        val nowWall = wallClockMillis()
        val endMs = if (run.endedAt > 0) run.endedAt else nowWall
        val activeMs = run.activeMillis(wallNow = endMs).coerceAtLeast(0L)
        // Extrem kurze Fehlstarts (< 1 s) verstopfen den Kalender nicht.
        if (activeMs < 1000L) return

        val startMs = if (run.startedAt in 1..endMs) run.startedAt else (endMs - activeMs).coerceAtLeast(0L)
        val focusCtx = focusSessions[run.session.id]
        val quest = focusCtx?.questId?.let { qid -> _state.value.quests.firstOrNull { it.id == qid } }
            ?: focusCtx?.taskId?.let { tid ->
                _state.value.tasks.firstOrNull { it.id == tid }?.questId?.let { qid ->
                    _state.value.quests.firstOrNull { it.id == qid }
                }
            }
            ?: run.session.questId?.let { qid -> _state.value.quests.firstOrNull { it.id == qid } }

        val kind = when {
            focusCtx != null || run.session.id.startsWith("focus_") -> EntryKind.FOCUS
            run.session.music -> EntryKind.MUSIC
            run.session.stopwatch -> EntryKind.STOPWATCH
            else -> EntryKind.TIMER
        }

        val entry = CalendarEntry(
            id = newTaskId(),
            title = run.session.name.ifBlank { kind.label },
            startMillis = startMs,
            endMillis = maxOf(endMs, startMs + activeMs),
            durationMillis = activeMs,
            kind = kind,
            sessionId = run.session.id,
            taskId = focusCtx?.taskId,
            questId = quest?.id,
            questTitle = quest?.title,
            questColor = quest?.color,
            togglSynced = togglSynced,
            aborted = event == TimerEvent.Stopped && !run.session.stopwatch,
            updatedAt = nowWall,
        )
        update { snap -> snap.copy(entries = (listOf(entry) + snap.entries).sortedByDescending { it.startMillis }) }
    }

    // --- Quests & Projekte --------------------------------------------------

    fun saveQuest(quest: QuestProject) {
        val stamped = quest.copy(updatedAt = wallClockMillis())
        update { snap ->
            val exists = snap.quests.any { it.id == stamped.id }
            val nextQuests = if (exists) snap.quests.map { if (it.id == stamped.id) stamped else it } else snap.quests + stamped
            // Bestehende Kalendereinträge übernehmen umbenannte Quest-Titel/Farben konsistent.
            val nextEntries = snap.entries.map { entry ->
                if (entry.questId == stamped.id) entry.copy(questTitle = stamped.title, questColor = stamped.color)
                else entry
            }
            snap.copy(quests = nextQuests, entries = nextEntries)
        }
    }

    fun deleteQuest(questId: String) {
        update { snap ->
            snap.copy(
                quests = snap.quests.filterNot { it.id == questId },
                tasks = snap.tasks.map { if (it.questId == questId) it.copy(questId = null, updatedAt = wallClockMillis()) else it },
            )
        }
    }

    fun setQuestStatus(questId: String, status: QuestStatus) {
        update { snap ->
            snap.copy(quests = snap.quests.map {
                if (it.id == questId) it.copy(status = status, updatedAt = wallClockMillis()) else it
            })
        }
    }

    fun addQuestSubtask(questId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        update { snap ->
            snap.copy(quests = snap.quests.map { q ->
                if (q.id == questId) q.copy(subtasks = q.subtasks + SubTask(title = clean), updatedAt = wallClockMillis())
                else q
            })
        }
    }

    fun toggleQuestSubtask(questId: String, subtaskId: String) {
        update { snap ->
            snap.copy(quests = snap.quests.map { q ->
                if (q.id == questId) q.copy(
                    subtasks = q.subtasks.map { s -> if (s.id == subtaskId) s.copy(done = !s.done) else s },
                    updatedAt = wallClockMillis(),
                ) else q
            })
        }
    }

    fun deleteQuestSubtask(questId: String, subtaskId: String) {
        update { snap ->
            snap.copy(quests = snap.quests.map { q ->
                if (q.id == questId) q.copy(
                    subtasks = q.subtasks.filterNot { it.id == subtaskId },
                    updatedAt = wallClockMillis(),
                ) else q
            })
        }
    }

    // --- Tasks & Unteraufgaben ----------------------------------------------

    fun addTask(title: String, questId: String? = null, scheduledDate: String? = todayDate(), notes: String = ""): TaskItem {
        val clean = title.trim()
        val now = wallClockMillis()
        val maxOrder = _state.value.tasks.filter { it.scheduledDate == scheduledDate }.maxOfOrNull { it.sortOrder } ?: -1
        val item = TaskItem(
            id = newTaskId(),
            title = clean,
            notes = notes.trim(),
            questId = questId,
            scheduledDate = scheduledDate,
            sortOrder = maxOrder + 1,
            updatedAt = now,
        )
        if (clean.isNotEmpty()) {
            update { snap -> snap.copy(tasks = snap.tasks + item) }
        }
        return item
    }

    fun saveTask(task: TaskItem) {
        val stamped = task.copy(updatedAt = wallClockMillis())
        update { snap ->
            val exists = snap.tasks.any { it.id == stamped.id }
            val next = if (exists) snap.tasks.map { if (it.id == stamped.id) stamped else it } else snap.tasks + stamped
            snap.copy(tasks = next)
        }
    }

    fun toggleTaskDone(taskId: String) {
        val now = wallClockMillis()
        update { snap ->
            snap.copy(tasks = snap.tasks.map { t ->
                if (t.id == taskId) {
                    val nextDone = !t.done
                    t.copy(done = nextDone, completedAt = if (nextDone) now else null, updatedAt = now)
                } else t
            })
        }
    }

    fun rescheduleTask(taskId: String, newDate: String?) {
        update { snap ->
            snap.copy(tasks = snap.tasks.map { t ->
                if (t.id == taskId) t.copy(scheduledDate = newDate, updatedAt = wallClockMillis()) else t
            })
        }
    }

    fun deleteTask(taskId: String) {
        update { snap -> snap.copy(tasks = snap.tasks.filterNot { it.id == taskId }) }
    }

    fun addTaskSubtask(taskId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        update { snap ->
            snap.copy(tasks = snap.tasks.map { t ->
                if (t.id == taskId) t.copy(subtasks = t.subtasks + SubTask(title = clean), updatedAt = wallClockMillis())
                else t
            })
        }
    }

    fun toggleTaskSubtask(taskId: String, subtaskId: String) {
        update { snap ->
            snap.copy(tasks = snap.tasks.map { t ->
                if (t.id == taskId) t.copy(
                    subtasks = t.subtasks.map { s -> if (s.id == subtaskId) s.copy(done = !s.done) else s },
                    updatedAt = wallClockMillis(),
                ) else t
            })
        }
    }

    fun deleteTaskSubtask(taskId: String, subtaskId: String) {
        update { snap ->
            snap.copy(tasks = snap.tasks.map { t ->
                if (t.id == taskId) t.copy(
                    subtasks = t.subtasks.filterNot { it.id == subtaskId },
                    updatedAt = wallClockMillis(),
                ) else t
            })
        }
    }

    // --- Lokaler Kalender & Ziel --------------------------------------------

    fun saveEntry(entry: CalendarEntry) {
        val quest = entry.questId?.let { qid -> _state.value.quests.firstOrNull { it.id == qid } }
        val normalized = entry.copy(
            questTitle = quest?.title ?: entry.questTitle,
            questColor = quest?.color ?: entry.questColor,
            durationMillis = entry.durationMillis.coerceAtLeast(1000L),
            updatedAt = wallClockMillis(),
        )
        update { snap ->
            val exists = snap.entries.any { it.id == normalized.id }
            val next = if (exists) snap.entries.map { if (it.id == normalized.id) normalized else it } else listOf(normalized) + snap.entries
            snap.copy(entries = next.sortedByDescending { it.startMillis })
        }
    }

    fun deleteEntry(entryId: String) {
        update { snap -> snap.copy(entries = snap.entries.filterNot { it.id == entryId }) }
    }

    fun setWeeklyTargetHours(hours: Int) {
        update { snap -> snap.copy(weeklyTargetHours = hours.coerceIn(1, 168)) }
    }

    // --- JSON-Export & Multi-JSON-Zusammenführung ---------------------------

    /** Exportiert Quests, Aufgaben und den kompletten lokalen Kalender als JSON-String. */
    fun exportJson(): String = serializeSnapshot(_state.value)

    /**
     * Führt beliebig viele JSON-Strings (Export-Dateien von anderen Geräten, ältere Backups oder
     * reine Kalendereintrags-Arrays) verlustfrei zusammen.
     *
     * Duplikate werden sowohl über die [id] als auch bei Kalendereinträgen zusätzlich über ihren
     * sekundengenauen Zeitraum + Titel + Typ erkannt; bei Konflikten gewinnt der neuere [updatedAt]-Stand,
     * während Unteraufgaben nach ID vereinigt werden.
     */
    fun mergeJsonStrings(jsons: List<String>): MergeReport {
        var addedQuests = 0
        var updatedQuests = 0
        var addedTasks = 0
        var updatedTasks = 0
        var addedEntries = 0
        var updatedEntries = 0
        var skippedDuplicates = 0
        var failedInputs = 0
        var validFiles = 0

        val current = _state.value
        val questMap = LinkedHashMap<String, QuestProject>().apply {
            current.quests.forEach { put(it.id, it) }
        }
        val taskMap = LinkedHashMap<String, TaskItem>().apply {
            current.tasks.forEach { put(it.id, it) }
        }
        val entryList = current.entries.toMutableList()
        var targetHours = current.weeklyTargetHours

        jsons.forEach { raw ->
            val parsed = parseFlexibleImport(raw)
            if (parsed == null) {
                failedInputs++
                return@forEach
            }
            validFiles++
            if (parsed.weeklyTargetHours != 20 && targetHours == 20) {
                targetHours = parsed.weeklyTargetHours
            }

            parsed.quests.forEach { incoming ->
                val existing = questMap[incoming.id]
                if (existing == null) {
                    questMap[incoming.id] = incoming
                    addedQuests++
                } else {
                    val mergedSubs = mergeSubtasks(existing.subtasks, incoming.subtasks, incoming.updatedAt > existing.updatedAt)
                    if (incoming.updatedAt > existing.updatedAt) {
                        questMap[incoming.id] = incoming.copy(subtasks = mergedSubs)
                        updatedQuests++
                    } else if (mergedSubs.size > existing.subtasks.size) {
                        questMap[incoming.id] = existing.copy(subtasks = mergedSubs)
                        updatedQuests++
                    } else {
                        skippedDuplicates++
                    }
                }
            }

            parsed.tasks.forEach { incoming ->
                val existing = taskMap[incoming.id]
                if (existing == null) {
                    taskMap[incoming.id] = incoming
                    addedTasks++
                } else {
                    val mergedSubs = mergeSubtasks(existing.subtasks, incoming.subtasks, incoming.updatedAt > existing.updatedAt)
                    if (incoming.updatedAt > existing.updatedAt) {
                        taskMap[incoming.id] = incoming.copy(subtasks = mergedSubs)
                        updatedTasks++
                    } else if (mergedSubs.size > existing.subtasks.size) {
                        taskMap[incoming.id] = existing.copy(subtasks = mergedSubs)
                        updatedTasks++
                    } else {
                        skippedDuplicates++
                    }
                }
            }

            parsed.entries.forEach { incoming ->
                val byIdIdx = entryList.indexOfFirst { it.id == incoming.id }
                val idx = if (byIdIdx >= 0) byIdIdx else entryList.indexOfFirst { sameEntrySignature(it, incoming) }
                if (idx < 0) {
                    entryList += incoming
                    addedEntries++
                } else {
                    val existing = entryList[idx]
                    if (incoming.updatedAt > existing.updatedAt) {
                        entryList[idx] = incoming.copy(id = existing.id)
                        updatedEntries++
                    } else {
                        skippedDuplicates++
                    }
                }
            }
        }

        val mergedSnapshot = TaskDataSnapshot(
            quests = questMap.values.toList(),
            tasks = taskMap.values.toList(),
            entries = entryList.sortedByDescending { it.startMillis },
            weeklyTargetHours = targetHours,
        )
        _state.value = mergedSnapshot
        storage.write(serializeSnapshot(mergedSnapshot))

        return MergeReport(
            importedFiles = validFiles,
            addedQuests = addedQuests,
            updatedQuests = updatedQuests,
            addedTasks = addedTasks,
            updatedTasks = updatedTasks,
            addedEntries = addedEntries,
            updatedEntries = updatedEntries,
            skippedDuplicates = skippedDuplicates,
            failedInputs = failedInputs,
        )
    }

    private fun mergeSubtasks(existing: List<SubTask>, incoming: List<SubTask>, preferIncoming: Boolean): List<SubTask> {
        val map = LinkedHashMap<String, SubTask>()
        existing.forEach { map[it.id] = it }
        incoming.forEach { inc ->
            val cur = map[inc.id]
            map[inc.id] = when {
                cur == null -> inc
                preferIncoming -> inc
                else -> cur.copy(done = cur.done || inc.done)
            }
        }
        return map.values.toList()
    }

    private fun sameEntrySignature(a: CalendarEntry, b: CalendarEntry): Boolean =
        a.startMillis / 1000L == b.startMillis / 1000L &&
            a.endMillis / 1000L == b.endMillis / 1000L &&
            a.title.trim() == b.title.trim() &&
            a.kind == b.kind

    // --- Auswertung (Wochenrückblick & Quest-Verlauf) -----------------------

    /**
     * Berechnet alle Kennzahlen, Tagesbalken (Mo–So), Quest-Aufteilungen und den 8-Wochen-Trend
     * für die Woche ab [weekStart] ("YYYY-MM-DD").
     */
    fun computeWeekReview(
        weekStart: String,
        today: String = todayDate(),
        snapshot: TaskDataSnapshot = _state.value,
    ): WeekReviewData {
        val weekDates = (0 until 7).map { addDaysToDate(weekStart, it) }
        val entriesByDate = snapshot.entries.groupBy { dateOfMillis(it.startMillis) }

        val days = weekDates.map { date ->
            val dayEntries = entriesByDate[date].orEmpty()
            val qMs = dayEntries.filter { it.questId != null }.sumOf { it.durationMillis }
            val oMs = dayEntries.filter { it.questId == null }.sumOf { it.durationMillis }
            val (weekday, dayLabel) = formatDayHeader(date)
            DayHours(date = date, weekday = weekday, dayLabel = dayLabel, questMs = qMs, otherMs = oMs)
        }

        val questMs = days.sumOf { it.questMs }
        val otherMs = days.sumOf { it.otherMs }
        val totalMs = questMs + otherMs

        val elapsedDays = weekDates.count { it <= today }.coerceAtLeast(1)
        val dailyAverageMs = questMs / elapsedDays
        val targetMs = snapshot.weeklyTargetHours * 3_600_000L
        val targetPercent = if (targetMs > 0L) ((questMs.toDouble() / targetMs) * 100.0).roundToInt() else 0

        // 8-Wochen-Fenster (7 Vorwochen + aktuelle weekStart) für Sparklines & Wochen-Trend
        val trendStarts = (7 downTo 0).map { addDaysToDate(weekStart, -7 * it) }
        val entriesByWeek = snapshot.entries.groupBy { startOfIsoWeek(dateOfMillis(it.startMillis)) }

        val questStats = snapshot.quests.map { q ->
            val allForQuest = snapshot.entries.filter { it.questId == q.id }
            val thisWeek = entriesByWeek[weekStart].orEmpty().filter { it.questId == q.id }.sumOf { it.durationMillis }
            val series = trendStarts.map { ws ->
                entriesByWeek[ws].orEmpty().filter { it.questId == q.id }.sumOf { it.durationMillis }
            }
            QuestWeekStats(
                quest = q,
                thisWeekMs = thisWeek,
                cumulativeMs = allForQuest.sumOf { it.durationMillis },
                weeklySeries = series,
            )
        }

        val rawTrend = trendStarts.map { ws ->
            val list = entriesByWeek[ws].orEmpty()
            val qMs = list.filter { it.questId != null }.sumOf { it.durationMillis }
            val tMs = list.sumOf { it.durationMillis }
            Triple(ws, qMs, tMs)
        }
        val trend = rawTrend.mapIndexed { idx, (ws, qMs, tMs) ->
            val window = rawTrend.subList(maxOf(0, idx - 3), idx + 1)
            val avg = window.sumOf { it.second } / window.size.coerceAtLeast(1)
            val (_, label) = formatDayHeader(ws)
            WeekTrendPoint(
                weekStart = ws,
                label = label,
                questMs = qMs,
                totalMs = tMs,
                rollingAverageMs = avg,
            )
        }

        return WeekReviewData(
            weekStart = weekStart,
            questMs = questMs,
            otherMs = otherMs,
            totalMs = totalMs,
            dailyAverageMs = dailyAverageMs,
            targetHours = snapshot.weeklyTargetHours,
            targetPercent = targetPercent,
            days = days,
            questStats = questStats,
            trend = trend,
        )
    }

    // --- JSON-Serialisierung & Deserialisierung -----------------------------

    private fun parseSnapshot(raw: String): TaskDataSnapshot? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        parseSnapshotObject(root)
    }.getOrNull()

    private fun parseFlexibleImport(raw: String): TaskDataSnapshot? = runCatching {
        val el = Json.parseToJsonElement(raw.trim())
        when (el) {
            is JsonObject -> parseSnapshotObject(el)
            is JsonArray -> {
                val entries = el.mapNotNull { runCatching { readEntry(it) }.getOrNull() }
                if (entries.isEmpty() && el.isNotEmpty()) null else TaskDataSnapshot(entries = entries)
            }
            else -> null
        }
    }.getOrNull()

    private fun parseSnapshotObject(o: JsonObject): TaskDataSnapshot {
        val quests = (o["quests"] as? JsonArray).orEmpty().mapNotNull { runCatching { readQuest(it) }.getOrNull() }
        val tasks = (o["tasks"] as? JsonArray).orEmpty().mapNotNull { runCatching { readTask(it) }.getOrNull() }
        val entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { runCatching { readEntry(it) }.getOrNull() }
        val target = o["weeklyTargetHours"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 168) ?: 20
        return TaskDataSnapshot(
            quests = quests,
            tasks = tasks,
            entries = entries.sortedByDescending { it.startMillis },
            weeklyTargetHours = target,
        )
    }

    private fun serializeSnapshot(snap: TaskDataSnapshot): String = buildJsonObject {
        put("format", "wassertiger-tasks-v1")
        put("exportedAt", wallClockMillis())
        put("weeklyTargetHours", snap.weeklyTargetHours)
        put("quests", buildJsonArray { snap.quests.forEach { add(writeQuest(it)) } })
        put("tasks", buildJsonArray { snap.tasks.forEach { add(writeTask(it)) } })
        put("entries", buildJsonArray { snap.entries.forEach { add(writeEntry(it)) } })
    }.toString()

    private fun readSubtasks(el: JsonElement?): List<SubTask> =
        (el as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SubTask(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: newTaskId(),
                title = title,
                done = o["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

    private fun writeSubtasks(list: List<SubTask>): JsonArray = buildJsonArray {
        list.forEach { s ->
            add(buildJsonObject {
                put("id", s.id)
                put("title", s.title)
                put("done", s.done)
            })
        }
    }

    private fun readFocusSignal(el: JsonElement?, fallbackColor: Int): FocusSignalConfig {
        val o = el as? JsonObject ?: return FocusSignalConfig(flashColor = fallbackColor)
        val mode = o["mode"]?.jsonPrimitive?.contentOrNull.let { n -> FocusMarkerMode.entries.firstOrNull { it.name == n } }
            ?: FocusMarkerMode.PERIODIC
        val periodic = o["periodicSeconds"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: (25 * 60)
        val milestones = (o["milestones"] as? JsonArray)?.mapNotNull { m ->
            val mo = m as? JsonObject ?: return@mapNotNull null
            val sec = mo["seconds"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            FocusMarker(sec, mo["label"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.orEmpty().ifEmpty { listOf(FocusMarker(25 * 60, "Fokus"), FocusMarker(5 * 60, "Pause")) }
        return FocusSignalConfig(
            mode = mode,
            periodicSeconds = periodic,
            focusMinutes = o["focusMinutes"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 25,
            breakMinutes = o["breakMinutes"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 5,
            pomodoroRounds = o["pomodoroRounds"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 4,
            linkedSessionId = o["linkedSessionId"]?.jsonPrimitive?.contentOrNull,
            milestones = milestones,
            vibration = o["vibration"]?.jsonPrimitive?.intOrNull ?: 1,
            flashColor = o["flashColor"]?.jsonPrimitive?.contentOrNull?.let(::parseHexColor) ?: fallbackColor,
            tone = o["tone"]?.jsonPrimitive?.contentOrNull.let { n -> ToneMode.entries.firstOrNull { it.name == n } } ?: ToneMode.END,
            chime = o["chime"]?.jsonPrimitive?.contentOrNull.let { n -> Chime.entries.firstOrNull { it.name == n } } ?: Chime.BELL,
        )
    }

    private fun writeFocusSignal(cfg: FocusSignalConfig): JsonObject = buildJsonObject {
        put("mode", cfg.mode.name)
        put("periodicSeconds", cfg.periodicSeconds)
        put("focusMinutes", cfg.focusMinutes)
        put("breakMinutes", cfg.breakMinutes)
        put("pomodoroRounds", cfg.pomodoroRounds)
        cfg.linkedSessionId?.let { put("linkedSessionId", it) }
        put("milestones", buildJsonArray {
            cfg.milestones.forEach { m ->
                add(buildJsonObject {
                    put("seconds", m.seconds)
                    put("label", m.label)
                })
            }
        })
        put("vibration", cfg.vibration)
        put("flashColor", formatHexColor(cfg.flashColor))
        put("tone", cfg.tone.name)
        put("chime", cfg.chime.name)
    }

    private fun readQuest(el: JsonElement): QuestProject {
        val o = el.jsonObject
        val color = o["color"]?.jsonPrimitive?.contentOrNull?.let(::parseHexColor) ?: AMBER_RGB
        return QuestProject(
            id = o.getValue("id").jsonPrimitive.content,
            title = o.getValue("title").jsonPrimitive.content,
            description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            icon = o["icon"]?.jsonPrimitive?.contentOrNull?.ifBlank { "🎯" } ?: "🎯",
            color = color,
            kind = o["kind"]?.jsonPrimitive?.contentOrNull.let { n -> QuestKind.entries.firstOrNull { it.name == n } } ?: QuestKind.QUEST,
            status = o["status"]?.jsonPrimitive?.contentOrNull.let { n -> QuestStatus.entries.firstOrNull { it.name == n } } ?: QuestStatus.ACTIVE,
            subtasks = readSubtasks(o["subtasks"]),
            togglEnabled = o["togglEnabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            togglProject = o["togglProject"]?.let(::readProject),
            togglDescription = o["togglDescription"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            signalConfig = readFocusSignal(o["signalConfig"], color),
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private fun writeQuest(q: QuestProject): JsonObject = buildJsonObject {
        put("id", q.id)
        put("title", q.title)
        put("description", q.description)
        put("icon", q.icon)
        put("color", formatHexColor(q.color))
        put("kind", q.kind.name)
        put("status", q.status.name)
        put("subtasks", writeSubtasks(q.subtasks))
        put("togglEnabled", q.togglEnabled)
        q.togglProject?.let { put("togglProject", writeProject(it)) }
        put("togglDescription", q.togglDescription)
        put("signalConfig", writeFocusSignal(q.signalConfig))
        put("updatedAt", q.updatedAt)
    }

    private fun readTask(el: JsonElement): TaskItem {
        val o = el.jsonObject
        return TaskItem(
            id = o.getValue("id").jsonPrimitive.content,
            title = o.getValue("title").jsonPrimitive.content,
            notes = o["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            questId = o["questId"]?.jsonPrimitive?.contentOrNull,
            scheduledDate = o["scheduledDate"]?.jsonPrimitive?.contentOrNull,
            done = o["done"]?.jsonPrimitive?.booleanOrNull ?: false,
            completedAt = o["completedAt"]?.jsonPrimitive?.longOrNull,
            subtasks = readSubtasks(o["subtasks"]),
            signalOverride = o["signalOverride"]?.let { readFocusSignal(it, AMBER_RGB) },
            sortOrder = o["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private fun writeTask(t: TaskItem): JsonObject = buildJsonObject {
        put("id", t.id)
        put("title", t.title)
        put("notes", t.notes)
        t.questId?.let { put("questId", it) }
        t.scheduledDate?.let { put("scheduledDate", it) }
        put("done", t.done)
        t.completedAt?.let { put("completedAt", it) }
        put("subtasks", writeSubtasks(t.subtasks))
        t.signalOverride?.let { put("signalOverride", writeFocusSignal(it)) }
        put("sortOrder", t.sortOrder)
        put("updatedAt", t.updatedAt)
    }

    private fun readEntry(el: JsonElement): CalendarEntry {
        val o = el.jsonObject
        val start = o.getValue("startMillis").jsonPrimitive.longOrNull ?: error("missing startMillis")
        val end = o["endMillis"]?.jsonPrimitive?.longOrNull ?: start
        val dur = o["durationMillis"]?.jsonPrimitive?.longOrNull ?: (end - start).coerceAtLeast(1000L)
        return CalendarEntry(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: newTaskId(),
            title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Sitzung" },
            startMillis = start,
            endMillis = maxOf(end, start),
            durationMillis = dur.coerceAtLeast(1000L),
            kind = o["kind"]?.jsonPrimitive?.contentOrNull.let { n -> EntryKind.entries.firstOrNull { it.name == n } } ?: EntryKind.TIMER,
            sessionId = o["sessionId"]?.jsonPrimitive?.contentOrNull,
            taskId = o["taskId"]?.jsonPrimitive?.contentOrNull,
            questId = o["questId"]?.jsonPrimitive?.contentOrNull,
            questTitle = o["questTitle"]?.jsonPrimitive?.contentOrNull,
            questColor = o["questColor"]?.jsonPrimitive?.contentOrNull?.let(::parseHexColor),
            togglSynced = o["togglSynced"]?.jsonPrimitive?.booleanOrNull ?: false,
            aborted = o["aborted"]?.jsonPrimitive?.booleanOrNull ?: false,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: start,
        )
    }

    private fun writeEntry(e: CalendarEntry): JsonObject = buildJsonObject {
        put("id", e.id)
        put("title", e.title)
        put("startMillis", e.startMillis)
        put("endMillis", e.endMillis)
        put("durationMillis", e.durationMillis)
        put("kind", e.kind.name)
        e.sessionId?.let { put("sessionId", it) }
        e.taskId?.let { put("taskId", it) }
        e.questId?.let { put("questId", it) }
        e.questTitle?.let { put("questTitle", it) }
        e.questColor?.let { put("questColor", formatHexColor(it)) }
        put("togglSynced", e.togglSynced)
        put("aborted", e.aborted)
        put("updatedAt", e.updatedAt)
    }
}

private fun newTaskId(): String = Random.nextLong().toULong().toString(16)
