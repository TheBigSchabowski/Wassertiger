package com.example.wassertiger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Monotonic milliseconds that keep counting while the device sleeps. */
expect fun monotonicMillis(): Long

data class RunState(
    val session: Session,
    val index: Int,
    /** 0-based repetition of the session's interval list. */
    val round: Int = 0,
    /** [monotonicMillis] at which the current step ends; meaningless while paused. */
    val stepEndAt: Long,
    /** Remaining ms of the current step while paused, null while running. */
    val pausedRemaining: Long? = null,
    val finished: Boolean = false,
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
}

enum class TimerEvent { Started, Step, PauseChanged, Finished, Stopped }

/**
 * Steps through a session. The platform reacts to [TimerEvent]s (vibrate, light up the
 * screen, update notifications); the engine itself never touches audio.
 */
class TimerEngine(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state.asStateFlow()

    var listener: (TimerEvent, RunState) -> Unit = { _, _ -> }

    private var loop: Job? = null

    fun start(session: Session) {
        if (session.steps.isEmpty()) return
        val s = RunState(session, index = 0, stepEndAt = monotonicMillis() + session.steps[0].seconds * 1000L)
        _state.value = s
        listener(TimerEvent.Started, s)
        runLoop()
    }

    fun togglePause() {
        val s = state.value?.takeIf { !it.finished } ?: return
        val next = if (s.pausedRemaining == null) {
            loop?.cancel()
            s.copy(pausedRemaining = s.remainingMs())
        } else {
            s.copy(stepEndAt = monotonicMillis() + s.pausedRemaining, pausedRemaining = null)
        }
        _state.value = next
        listener(TimerEvent.PauseChanged, next)
        if (!next.paused) runLoop()
    }

    fun skip() {
        val s = state.value?.takeIf { !it.finished } ?: return
        _state.value = s.copy(pausedRemaining = null)
        advance(monotonicMillis())
        runLoop()
    }

    fun stop() {
        val s = state.value ?: return
        loop?.cancel()
        _state.value = null
        listener(TimerEvent.Stopped, s)
    }

    /** Clears the "Fertig" screen once the user has seen it. */
    fun dismissFinished() {
        if (state.value?.finished == true) _state.value = null
    }

    private fun runLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (true) {
                val s = state.value ?: break
                if (s.paused || s.finished) break
                val remaining = s.stepEndAt - monotonicMillis()
                // Short sleeps, re-checked against the real clock, so a late wakeup never drifts.
                if (remaining <= 0) advance(s.stepEndAt) else delay(minOf(remaining, 500L))
            }
        }
    }

    /** Ends the current step at [endedAt] and moves on, into the next round if any (or finishes). */
    private fun advance(endedAt: Long) {
        val s = state.value ?: return
        val roundOver = s.index + 1 >= s.session.steps.size
        if (roundOver && s.lastRound) {
            val done = s.copy(index = s.session.steps.size, finished = true)
            _state.value = done
            listener(TimerEvent.Finished, done)
            return
        }
        val next = if (roundOver) 0 else s.index + 1
        val moved = s.copy(
            index = next,
            round = if (roundOver) s.round + 1 else s.round,
            stepEndAt = endedAt + s.session.steps[next].seconds * 1000L,
        )
        _state.value = moved
        listener(TimerEvent.Step, moved)
    }
}

/** Big title and subtitle for the flash shown at a step change. */
fun RunState.flashText(): Pair<String, String> {
    if (finished) return "Fertig!" to session.name
    val step = step ?: return "" to ""
    val position = if (session.steps.size > 1) "${index + 1} / ${session.steps.size}" else ""
    // Music: what to play now is the headline ("Phrygisch"), the sound underneath.
    if (session.music) {
        val sound = step.soundLabel()
        return step.name.ifBlank { sound } to
            listOf(sound.takeIf { step.name.isNotBlank() }.orEmpty(), position, roundLabel).filter { it.isNotBlank() }.joinToString(" · ")
    }
    return formatDuration(step.seconds) to
        listOf(step.name, position, roundLabel).filter { it.isNotBlank() }.joinToString(" · ")
}
