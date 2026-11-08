package com.example.wassertiger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** Monotonic milliseconds that keep counting while the device sleeps. */
expect fun monotonicMillis(): Long

/** Milliseconds since 1970 (UTC), for the time entries sent to Toggl. */
expect fun wallClockMillis(): Long

/**
 * One run of a session. Several can go on at once (a breathing exercise inside the Pomodoro break);
 * [id] tells them apart. [session] has its blocks written out already (see [Session.expanded]).
 */
data class RunState(
    val session: Session,
    val index: Int,
    /** 0-based repetition of the session's interval list. */
    val round: Int = 0,
    /** [monotonicMillis] at which the current step ends; meaningless while paused. */
    val stepEndAt: Long,
    /** Remaining ms of the current step while paused, null while running. */
    val pausedRemaining: Long? = null,
    /** Over: the last interval ran out, or it was [stopped] and is kept for Toggl. */
    val finished: Boolean = false,
    val id: Long = 0,
    /** [wallClockMillis] at the start. */
    val startedAt: Long = 0,
    /** Time spent paused, not counting a pause still going on (see [pausedAt]). */
    val pausedMs: Long = 0,
    /** [monotonicMillis] when the current pause began. */
    val pausedAt: Long? = null,
    /** [wallClockMillis] when it finished or was stopped; 0 while it runs. */
    val endedAt: Long = 0,
    /** Ended with Stopp before the last interval ran out. */
    val stopped: Boolean = false,
    /** [monotonicMillis] at the start and at the end, for the stopwatch's reading (see [elapsedMs]). */
    val startedMono: Long? = null,
    val endedMono: Long? = null,
) {
    val step: Step? get() = session.steps.getOrNull(index)
    val lastRound: Boolean get() = !session.endless && round >= session.rounds - 1
    val nextStep: Step? get() = session.steps.getOrNull(index + 1)
        ?: session.steps.firstOrNull()?.takeIf { !lastRound }
    /** "Runde 3 / 30", "Runde 3" when endless, empty for a session that plays once. */
    val roundLabel: String get() = when {
        session.endless -> "Runde ${round + 1}"
        session.rounds > 1 -> "Runde ${round + 1} / ${session.rounds}"
        else -> ""
    }
    val paused: Boolean get() = pausedRemaining != null
    fun remainingMs(now: Long = monotonicMillis()): Long =
        pausedRemaining ?: (stepEndAt - now).coerceAtLeast(0)

    /** Whole seconds left of the current step, as the countdown shows them. */
    fun remainingSeconds(now: Long = monotonicMillis()): Int = ceil(remainingMs(now) / 1000.0).toInt()

    /** How long it has been running, pauses left out: what gets logged to Toggl. */
    fun activeMillis(wallNow: Long = wallClockMillis(), now: Long = monotonicMillis()): Long {
        val end = if (endedAt > 0) endedAt else wallNow
        val pause = pausedMs + (pausedAt?.let { now - it } ?: 0)
        return (end - startedAt - pause).coerceAtLeast(0)
    }

    /** A stopwatch's reading: running time, pauses left out, on the monotonic clock. */
    fun elapsedMs(now: Long = monotonicMillis()): Long {
        val origin = startedMono ?: return activeMillis(now = now)
        val end = if (finished) endedMono ?: now else now
        return (end - origin - pausedMs - (pausedAt?.let { end - it } ?: 0)).coerceAtLeast(0)
    }

    /** The current pause folded into [pausedMs], as when it ends. */
    internal fun pauseEnded(now: Long): RunState =
        if (pausedAt == null) this else copy(pausedMs = pausedMs + (now - pausedAt), pausedAt = null, pausedRemaining = null)
}

enum class TimerEvent { Started, Step, PauseChanged, Finished, Stopped }

/**
 * Steps through sessions, any number at once. The platform reacts to [TimerEvent]s (vibrate, light
 * up the screen, update notifications); the engine itself never touches audio.
 */
class TimerEngine(private val scope: CoroutineScope) {

    private val _runs = MutableStateFlow<List<RunState>>(emptyList())
    /** Every run, oldest first, including finished ones until they are [dismiss]ed. */
    val runs: StateFlow<List<RunState>> = _runs.asStateFlow()

    /** The runs still counting, oldest first. */
    val active: List<RunState> get() = runs.value.filter { !it.finished }

    var listener: (TimerEvent, RunState) -> Unit = { _, _ -> }

    /**
     * Whether a run that is stopped early stays on screen as "Gestoppt" (to log it to Toggl)
     * instead of vanishing.
     */
    var keepStopped: (RunState) -> Boolean = { false }

    private val loops = mutableMapOf<Long, Job>()
    private var nextId = 1L

    fun run(id: Long): RunState? = runs.value.firstOrNull { it.id == id }

    /**
     * Starts [session] next to whatever runs already; returns the new run's id. Starting a session
     * that is running already starts it over.
     */
    fun start(session: Session): Long {
        val expanded = session.expanded()
        if (expanded.steps.isEmpty()) return -1
        val now = monotonicMillis()
        val s = RunState(
            session = expanded,
            index = 0,
            stepEndAt = now + expanded.steps[0].seconds * 1000L,
            id = nextId++,
            startedAt = wallClockMillis(),
            startedMono = now,
        )
        // The new run is in before the old one goes, so nothing ever sees "no run left" and shuts down.
        val replaced = runs.value.filter { it.session.id == session.id }
        replaced.forEach { loops.remove(it.id)?.cancel() }
        _runs.value = runs.value.filter { it.session.id != session.id } + s
        listener(TimerEvent.Started, s)
        replaced.forEach { listener(TimerEvent.Stopped, it) }
        runLoop(s.id)
        return s.id
    }

    fun togglePause(id: Long) {
        val s = run(id)?.takeIf { !it.finished } ?: return
        val now = monotonicMillis()
        val next = if (s.pausedRemaining == null) {
            loops.remove(id)?.cancel()
            s.copy(pausedRemaining = s.remainingMs(now), pausedAt = now)
        } else {
            s.pauseEnded(now).copy(stepEndAt = now + s.pausedRemaining)
        }
        replace(next)
        listener(TimerEvent.PauseChanged, next)
        if (!next.paused) runLoop(id)
    }

    fun skip(id: Long) {
        val s = run(id)?.takeIf { !it.finished } ?: return
        val now = monotonicMillis()
        replace(s.pauseEnded(now))
        advance(id, now)
        runLoop(id)
    }

    /** Ends the run; a finished one is just cleared. See [keepStopped]. */
    fun stop(id: Long) {
        val s = run(id) ?: return
        if (s.finished) return dismiss(id)
        if (!keepStopped(s)) return drop(id)
        loops.remove(id)?.cancel()
        val now = monotonicMillis()
        val ended = s.pauseEnded(now).copy(finished = true, stopped = true, endedAt = wallClockMillis(), endedMono = now)
        replace(ended)
        listener(TimerEvent.Stopped, ended)
    }

    /** Ends every run and clears them all. */
    fun stopAll() {
        runs.value.forEach { if (it.finished) dismiss(it.id) else drop(it.id) }
    }

    /** Clears a finished run once the user has seen it. */
    fun dismiss(id: Long) {
        if (run(id)?.finished == true) _runs.value = runs.value.filter { it.id != id }
    }

    /** Removes a run without keeping anything of it. */
    private fun drop(id: Long) {
        val s = run(id) ?: return
        loops.remove(id)?.cancel()
        _runs.value = runs.value.filter { it.id != id }
        listener(TimerEvent.Stopped, s)
    }

    private fun replace(s: RunState) {
        _runs.value = runs.value.map { if (it.id == s.id) s else it }
    }

    private fun runLoop(id: Long) {
        loops.remove(id)?.cancel()
        loops[id] = scope.launch {
            while (true) {
                val s = run(id) ?: break
                if (s.paused || s.finished) break
                val remaining = s.stepEndAt - monotonicMillis()
                // Short sleeps, re-checked against the real clock, so a late wakeup never drifts.
                if (remaining <= 0) advance(id, s.stepEndAt) else delay(minOf(remaining, 500L))
            }
        }
    }

    /** Ends the current step at [endedAt] and moves on, into the next round if any (or finishes). */
    private fun advance(id: Long, endedAt: Long) {
        val s = run(id) ?: return
        val roundOver = s.index + 1 >= s.session.steps.size
        if (roundOver && s.lastRound) {
            val done = s.copy(index = s.session.steps.size, finished = true, endedAt = wallClockMillis())
            replace(done)
            listener(TimerEvent.Finished, done)
            return
        }
        val next = if (roundOver) 0 else s.index + 1
        val moved = s.copy(
            index = next,
            round = if (roundOver) s.round + 1 else s.round,
            stepEndAt = endedAt + s.session.steps[next].seconds * 1000L,
        )
        replace(moved)
        listener(TimerEvent.Step, moved)
    }
}

/**
 * Big title and subtitle for the flash shown at a step change. With [now] the title counts the
 * current step down ("4 s" … ) instead of naming its length, so a flash that stays up keeps up.
 */
fun RunState.flashText(now: Long? = null): Pair<String, String> {
    if (session.stopwatch) return formatClock(elapsedMs(now ?: monotonicMillis())) to session.name
    if (finished) return (if (stopped) "Gestoppt" else "Fertig!") to session.name
    val step = step ?: return "" to ""
    val position = if (session.steps.size > 1) "${index + 1} / ${session.steps.size}" else ""
    val clock = now?.let { formatDuration(remainingSeconds(it).coerceAtLeast(1)) }
    // Music: what to play now is the headline ("Phrygisch"), the sound underneath.
    if (session.music) {
        val sound = step.soundLabel()
        return step.name.ifBlank { sound } to
            listOf(clock.orEmpty(), sound.takeIf { step.name.isNotBlank() }.orEmpty(), position, roundLabel)
                .filter { it.isNotBlank() }.joinToString(" · ")
    }
    return (clock ?: formatDuration(step.seconds)) to
        listOf(step.name, position, roundLabel).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A stopwatch reading: "0:07", "12:34", "1:02:03". */
fun formatClock(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val sec = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}
