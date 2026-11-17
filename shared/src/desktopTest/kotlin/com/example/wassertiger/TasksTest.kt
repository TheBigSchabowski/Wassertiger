package com.example.wassertiger

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {

    private class MemoryStorage(var raw: String? = null) : SessionStorage {
        var broken: String? = null
        override fun read(): String? = raw
        override fun write(json: String) {
            raw = json
        }

        override fun keepBroken(raw: String) {
            broken = raw
        }
    }

    @Test
    fun storeRoundTripForQuestsTasksAndSubtasks() {
        val storage = MemoryStorage()
        val store = TaskStore(storage)

        val quest = QuestProject(
            id = "q1",
            title = "Wassertiger v2",
            description = "Wochenansicht & Fokus",
            icon = "🚀",
            color = 0x2DD4BF,
            kind = QuestKind.QUEST,
            subtasks = listOf(SubTask("s1", "Datenmodell"), SubTask("s2", "UI bauen", done = true)),
            togglEnabled = true,
            togglProject = TogglProject(42L, "Dev", 7L),
            togglDescription = "Wassertiger Dev",
            signalConfig = FocusSignalConfig(
                mode = FocusMarkerMode.MILESTONES,
                milestones = listOf(FocusMarker(1500, "Deep Work"), FocusMarker(300, "Pause")),
                vibration = 3,
                flashColor = 0x7FFFD4,
                tone = ToneMode.EVERY,
                chime = Chime.GONG,
            ),
        )
        val sideQuest = QuestProject(
            id = "q2",
            title = "Gitarre",
            kind = QuestKind.SIDE_QUEST,
            togglEnabled = false,
        )
        store.saveQuest(quest)
        store.saveQuest(sideQuest)

        val task = store.addTask("Tests schreiben", questId = "q1", scheduledDate = "2026-11-17", notes = "KMP + Android")
        store.addTaskSubtask(task.id, "Timer-Test")
        store.addTaskSubtask(task.id, "Merge-Test")
        store.toggleTaskSubtask(task.id, store.state.value.tasks.single().subtasks.first().id)

        // Neu laden aus JSON-Speicher
        val reloaded = TaskStore(storage).state.value
        assertEquals(2, reloaded.quests.size)
        val rq1 = reloaded.quests.first { it.id == "q1" }
        assertEquals("Wassertiger v2", rq1.title)
        assertEquals(QuestKind.QUEST, rq1.kind)
        assertTrue(rq1.togglEnabled)
        assertEquals("Dev", rq1.togglProject?.name)
        assertEquals(FocusMarkerMode.MILESTONES, rq1.signalConfig.mode)
        assertEquals(2, rq1.signalConfig.milestones.size)
        assertEquals(3, rq1.signalConfig.vibration)
        assertEquals(ToneMode.EVERY, rq1.signalConfig.tone)
        assertEquals(Chime.GONG, rq1.signalConfig.chime)

        val rq2 = reloaded.quests.first { it.id == "q2" }
        assertEquals(QuestKind.SIDE_QUEST, rq2.kind)
        assertFalse(rq2.togglEnabled)

        val rt = reloaded.tasks.single()
        assertEquals("Tests schreiben", rt.title)
        assertEquals("KMP + Android", rt.notes)
        assertEquals("2026-11-17", rt.scheduledDate)
        assertEquals(listOf(true, false), rt.subtasks.map { it.done })
    }

    @Test
    fun focusSessionCarriesSignalsAndQuestTogglSetting() {
        val cfg = FocusSignalConfig(
            mode = FocusMarkerMode.PERIODIC,
            periodicSeconds = 600,
            vibration = 0,
            flashColor = 0xFF4FA3,
            tone = ToneMode.END,
            chime = Chime.WOOD,
        )
        val trackedQuest = QuestProject(
            id = "q_toggl",
            title = "Studium",
            togglEnabled = true,
            togglProject = TogglProject(9L, "Uni", 1L),
            togglDescription = "",
            signalConfig = cfg,
        )
        val localQuest = trackedQuest.copy(id = "q_local", togglEnabled = false)

        val trackedSession = cfg.toSession("focus_task_1", "Lernen", trackedQuest)
        assertTrue(trackedSession.stopwatch)
        assertEquals(600, trackedSession.signalEvery)
        assertEquals(0, trackedSession.vibration)
        assertEquals(0xFF4FA3, trackedSession.flashColor)
        assertEquals(ToneMode.END, trackedSession.tone)
        assertEquals(Chime.WOOD, trackedSession.chime)
        assertTrue(trackedSession.toggl)
        assertEquals("Lernen", trackedSession.togglDescription)
        assertEquals(9L, trackedSession.togglProject?.id)

        val localSession = cfg.toSession("focus_task_2", "Lesen", localQuest)
        assertFalse(localSession.toggl)

        val milestoneSession = cfg.copy(
            mode = FocusMarkerMode.MILESTONES,
            milestones = listOf(FocusMarker(20 * 60, "Block 1"), FocusMarker(5 * 60, "Pause")),
        ).toSession("focus_task_3", "Code", localQuest)
        assertFalse(milestoneSession.stopwatch)
        assertEquals(2, milestoneSession.steps.size)
        assertEquals(1, milestoneSession.rounds)

        val pomodoroSession = cfg.copy(
            mode = FocusMarkerMode.POMODORO,
            focusMinutes = 30,
            breakMinutes = 10,
            pomodoroRounds = 3,
        ).toSession("focus_quest_1", "Deep Work", trackedQuest)
        assertFalse(pomodoroSession.stopwatch)
        assertEquals(3, pomodoroSession.rounds)
        assertEquals(listOf(30 * 60, 10 * 60), pomodoroSession.steps.map { it.seconds })
        assertEquals(trackedQuest.id, pomodoroSession.questId)

        val pickedTimer = Session(
            id = "custom_1",
            name = "Atemübung",
            steps = listOf(Step(5, "Ein"), Step(5, "Aus")),
            rounds = 6,
        )
        val fromPicked = cfg.copy(
            mode = FocusMarkerMode.SESSION,
            linkedSessionId = "custom_1",
            tone = ToneMode.EVERY,
        ).toSession("focus_quest_2", "Atmen", localQuest, pickedSession = pickedTimer)
        assertEquals(6, fromPicked.rounds)
        assertEquals(listOf(5, 5), fromPicked.steps.map { it.seconds })
        assertEquals(localQuest.id, fromPicked.questId)
        assertEquals(ToneMode.EVERY, fromPicked.tone)
    }

    @Test
    fun timerEventsAutomaticallyRecordToLocalCalendar() = runBlocking {
        val store = TaskStore(MemoryStorage())
        val quest = QuestProject(id = "q1", title = "Projekt Alpha", color = 0x60A5FA)
        store.saveQuest(quest)
        val task = store.addTask("Feature bauen", questId = quest.id, scheduledDate = "2026-11-17")

        val engine = TimerEngine(this)
        engine.listener = { event, run ->
            store.onTimerEvent(event, run, togglSynced = run.session.toggl)
        }

        // 1. Fokus-Session (Stoppuhr)
        val focusSession = quest.signalConfig.copy(mode = FocusMarkerMode.NONE)
            .toSession("focus_task_${task.id}", task.title, quest)
        store.registerFocusSession(focusSession, task.id, quest.id)
        val fId = engine.start(focusSession)
        delay(1100)
        engine.stop(fId)

        // 2. Normale Timer-Session (endet regulär nach 1 s) – der Quest zugeordnet
        val sessionStorage = MemoryStorage()
        val sessionStore = SessionStore(sessionStorage)
        val questTimer = Session(name = "Kurz-Timer", steps = listOf(Step(1)), questId = quest.id)
        sessionStore.save(listOf(questTimer))
        assertEquals(quest.id, sessionStore.load().single().questId)

        val timerId = engine.start(questTimer)
        while (engine.run(timerId)?.finished != true) delay(25)

        // 3. Musik-Session (ohne Quest -> Sonstiges)
        val musicId = engine.start(Session(name = "Phrygisch", music = true, steps = listOf(Step(5, drone = 9))))
        delay(1100)
        engine.stop(musicId)

        val entries = store.state.value.entries
        assertEquals(3, entries.size)

        val focusEntry = entries.first { it.kind == EntryKind.FOCUS }
        assertEquals(task.id, focusEntry.taskId)
        assertEquals(quest.id, focusEntry.questId)
        assertEquals("Projekt Alpha", focusEntry.questTitle)
        assertEquals(0x60A5FA, focusEntry.questColor)
        assertTrue(focusEntry.durationMillis >= 1000L)

        val timerEntry = entries.first { it.kind == EntryKind.TIMER }
        assertEquals("Kurz-Timer", timerEntry.title)
        assertEquals(quest.id, timerEntry.questId)
        assertEquals("Projekt Alpha", timerEntry.questTitle)
        assertFalse(timerEntry.aborted)

        val musicEntry = entries.first { it.kind == EntryKind.MUSIC }
        assertEquals("Phrygisch", musicEntry.title)
        assertNull(musicEntry.questId)
        assertTrue(musicEntry.aborted)
    }

    @Test
    fun exportAndMergeMultipleJsonsDeduplicatesAndMergesSubtasks() {
        val storeA = TaskStore(MemoryStorage())
        val questA = QuestProject(
            id = "q1",
            title = "Thesis",
            subtasks = listOf(SubTask("s1", "Gliederung", done = true)),
            updatedAt = 1000L,
        )
        storeA.saveQuest(questA)
        val taskA = TaskItem(
            id = "t1",
            title = "Kapitel 1",
            questId = "q1",
            scheduledDate = "2026-11-17",
            subtasks = listOf(SubTask("ts1", "Einleitung")),
            updatedAt = 1000L,
        )
        storeA.saveTask(taskA)
        storeA.saveEntry(
            CalendarEntry(
                id = "e1",
                title = "Kapitel 1",
                startMillis = 1_794_900_000_000L,
                endMillis = 1_794_901_800_000L,
                durationMillis = 1_800_000L,
                kind = EntryKind.FOCUS,
                questId = "q1",
                updatedAt = 1000L,
            ),
        )
        val jsonA = storeA.exportJson()

        // Zweites Gerät mit neuerer Quest-Subtask, zweiter Task-Subtask, Duplikat-Eintrag (andere ID, gleiche Zeit) + neuem Eintrag
        val storeB = TaskStore(MemoryStorage())
        storeB.saveQuest(
            questA.copy(
                subtasks = listOf(SubTask("s2", "Literatur")),
                updatedAt = 2000L,
            ),
        )
        storeB.saveTask(
            taskA.copy(
                subtasks = listOf(SubTask("ts2", "Quellen")),
                updatedAt = 2000L,
            ),
        )
        storeB.saveEntry(
            CalendarEntry(
                id = "e1_other_device",
                title = "Kapitel 1",
                startMillis = 1_794_900_000_000L,
                endMillis = 1_794_901_800_000L,
                durationMillis = 1_800_000L,
                kind = EntryKind.FOCUS,
                questId = "q1",
                updatedAt = 1000L,
            ),
        )
        storeB.saveEntry(
            CalendarEntry(
                id = "e2",
                title = "Kapitel 2",
                startMillis = 1_794_910_000_000L,
                endMillis = 1_794_913_600_000L,
                durationMillis = 3_600_000L,
                kind = EntryKind.FOCUS,
                questId = "q1",
                updatedAt = 2000L,
            ),
        )
        val jsonB = storeB.exportJson()

        // Drittes JSON: reines Array mit einem weiteren Eintrag
        val jsonC = """[{"id":"e3","title":"Üben","startMillis":1794920000000,"endMillis":1794921200000,"durationMillis":1200000,"kind":"MUSIC"}]"""

        val target = TaskStore(MemoryStorage())
        val report = target.mergeJsonStrings(listOf(jsonA, jsonB, jsonC, "{invalid"))

        assertEquals(3, report.importedFiles)
        assertEquals(1, report.failedInputs)
        assertEquals(1, report.addedQuests)
        assertEquals(1, report.addedTasks)
        // E1, E2 und E3 -> insgesamt 3 eindeutige Einträge (e1_other_device wird als Duplikat erkannt)
        assertEquals(3, target.state.value.entries.size)
        assertEquals(2, target.state.value.quests.single().subtasks.size)
        assertEquals(2, target.state.value.tasks.single().subtasks.size)
        assertEquals(1, report.updatedEntries + report.skippedDuplicates)

        // Erneutes Zusammenführen desselben Exports überspringt alles als Duplikate
        val secondPass = target.mergeJsonStrings(listOf(jsonA))
        assertEquals(0, secondPass.addedEntries)
        assertEquals(3, secondPass.skippedDuplicates)
    }

    @Test
    fun weekReviewCalculatesDailyBarsQuestStatsAndTrend() {
        val store = TaskStore(MemoryStorage())
        val q1 = QuestProject(id = "q1", title = "App bauen", color = AMBER_RGB)
        val q2 = QuestProject(id = "q2", title = "Sport", kind = QuestKind.SIDE_QUEST, color = 0x4ADE80)
        store.saveQuest(q1)
        store.saveQuest(q2)
        store.setWeeklyTargetHours(10)

        // Woche ab Montag 2026-11-16
        val monStart = combineDateAndTime("2026-11-16", "09:00")!!
        val tueStart = combineDateAndTime("2026-11-17", "10:00")!!
        val prevWeekStart = combineDateAndTime("2026-11-09", "14:00")!!

        store.saveEntry(
            CalendarEntry(
                id = "c1",
                title = "Code",
                startMillis = monStart,
                endMillis = monStart + 2 * 3_600_000L,
                durationMillis = 2 * 3_600_000L,
                kind = EntryKind.FOCUS,
                questId = "q1",
            ),
        )
        store.saveEntry(
            CalendarEntry(
                id = "c2",
                title = "Laufen",
                startMillis = tueStart,
                endMillis = tueStart + 3_600_000L,
                durationMillis = 3_600_000L,
                kind = EntryKind.FOCUS,
                questId = "q2",
            ),
        )
        store.saveEntry(
            CalendarEntry(
                id = "c3",
                title = "Atemübung",
                startMillis = tueStart + 3_600_000L,
                endMillis = tueStart + 3_600_000L + 1_800_000L,
                durationMillis = 1_800_000L,
                kind = EntryKind.TIMER,
                questId = null,
            ),
        )
        store.saveEntry(
            CalendarEntry(
                id = "c4",
                title = "Vorwoche",
                startMillis = prevWeekStart,
                endMillis = prevWeekStart + 4 * 3_600_000L,
                durationMillis = 4 * 3_600_000L,
                kind = EntryKind.FOCUS,
                questId = "q1",
            ),
        )

        val review = store.computeWeekReview("2026-11-16", today = "2026-11-17")
        assertEquals(3 * 3_600_000L, review.questMs)
        assertEquals(1_800_000L, review.otherMs)
        assertEquals(3 * 3_600_000L + 1_800_000L, review.totalMs)
        assertEquals(30, review.targetPercent) // 3h / 10h = 30%
        assertEquals(7, review.days.size)
        assertEquals(2 * 3_600_000L, review.days[0].questMs)
        assertEquals(3_600_000L, review.days[1].questMs)
        assertEquals(1_800_000L, review.days[1].otherMs)
        // 2 verstrichene Tage (Mo+Di): 3h / 2 = 1,5h
        assertEquals(1.5 * 3_600_000L, review.dailyAverageMs.toDouble(), 100.0)

        val q1Stats = review.questStats.first { it.quest.id == "q1" }
        assertEquals(2 * 3_600_000L, q1Stats.thisWeekMs)
        assertEquals(6 * 3_600_000L, q1Stats.cumulativeMs)
        assertEquals(8, q1Stats.weeklySeries.size)
        assertEquals(4 * 3_600_000L, q1Stats.weeklySeries[6])
        assertEquals(2 * 3_600_000L, q1Stats.weeklySeries[7])
    }

    @Test
    fun dateHelpersWorkAcrossWeekBoundaries() {
        assertEquals("2026-11-16", startOfIsoWeek("2026-11-17"))
        assertEquals("2026-11-16", startOfIsoWeek("2026-11-22"))
        assertEquals("2026-11-23", addDaysToDate("2026-11-16", 7))
        assertEquals("Mo" to "16.11.", formatDayHeader("2026-11-16"))
        assertEquals("KW 47 · 16.11.–22.11.", formatWeekHeader("2026-11-16"))
        assertNotNull(combineDateAndTime("2026-11-17", "9:05"))
        assertNull(combineDateAndTime("2026-11-17", "99:99"))
    }
}
