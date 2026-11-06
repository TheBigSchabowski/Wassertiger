package com.example.wassertiger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Status code and body of an HTTP response. */
class HttpResult(val code: Int, val body: String)

/** Sends one HTTPS request with basic auth off the main thread; throws on network trouble. */
expect suspend fun httpRequest(method: String, url: String, user: String, password: String, body: String?): HttpResult

/** "2026-11-06T09:30:00Z" for [millis] since 1970. */
expect fun isoInstant(millis: Long): String

/** "09:30" in the device's time zone. */
expect fun clockTime(millis: Long): String

data class TogglProject(val id: Long, val name: String, val workspaceId: Long)

/** [retryAfterMs]: worth trying again after that long (no network, Toggl's hourly limit). */
class TogglException(message: String, val retryAfterMs: Long? = null) : Exception(message)

/** Tag on the entries of a session stopped before its end. */
const val TOGGL_ABORTED_TAG = "abgebrochen"

/** A run stopped before this much running time is dropped, in Toggl too: started by mistake. */
const val TOGGL_MIN_MILLIS = 60_000L

typealias Http = suspend (method: String, url: String, user: String, password: String, body: String?) -> HttpResult

/** Toggl Track's API v9 with a personal API token (track.toggl.com/profile → API Token). */
class Toggl(private val token: String, private val http: Http = ::httpRequest) {

    /** The account's name and default workspace, to show that the token works. */
    suspend fun account(): Pair<String, Long> {
        val me = me()
        val workspace = me["default_workspace_id"]?.jsonPrimitive?.longOrNull ?: throw TogglException("Kein Toggl-Workspace gefunden")
        return (me["fullname"]?.jsonPrimitive?.contentOrNull ?: "Toggl") to workspace
    }

    suspend fun defaultWorkspace(): Long = account().second

    /** Whether a timer runs in Toggl right now (Toggl allows one at a time). */
    suspend fun timerRunning(): Boolean = call("GET", "me/time_entries/current", null).let { it.isNotBlank() && it != "null" }

    /** Active projects of all workspaces, by name. */
    suspend fun projects(): List<TogglProject> {
        val body = call("GET", "me/projects", null)
        if (body.isBlank() || body == "null") return emptyList()
        return Json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val o = element.jsonObject
            if (o["active"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            TogglProject(
                id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                workspaceId = o["workspace_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** Creates an entry from [start]; without [stop] it runs on in Toggl. Returns its id. */
    suspend fun create(workspace: Long, description: String, project: TogglProject?, start: Long, stop: Long?, tags: List<String>): Long {
        val entry = buildJsonObject {
            put("created_with", "Wassertiger")
            put("description", description)
            put("workspace_id", workspace)
            project?.let { put("project_id", it.id) }
            // Whole seconds throughout: Toggl refuses a stop that does not match start + duration exactly.
            val from = start / 1000
            put("start", isoInstant(from * 1000))
            if (stop == null) {
                put("duration", -1)
            } else {
                val to = maxOf(stop / 1000, from + 1)
                put("stop", isoInstant(to * 1000))
                put("duration", to - from)
            }
            if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
        }
        val body = call("POST", "workspaces/$workspace/time_entries", entry.toString())
        return Json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.longOrNull
            ?: throw TogglException("Toggl hat keinen Eintrag angelegt")
    }

    /** Changes the given fields of entry [id]; a null [project] with [setProject] removes it. */
    suspend fun update(
        workspace: Long,
        id: Long,
        stop: Long? = null,
        description: String? = null,
        setProject: Boolean = false,
        project: TogglProject? = null,
        tags: List<String>? = null,
    ) {
        val change = buildJsonObject {
            stop?.let { put("stop", isoInstant(it)) }
            description?.let { put("description", it) }
            if (setProject) put("project_id", project?.id?.let(::JsonPrimitive) ?: JsonNull)
            tags?.let { put("tags", buildJsonArray { it.forEach { t -> add(JsonPrimitive(t)) } }) }
        }
        call("PUT", "workspaces/$workspace/time_entries/$id", change.toString())
    }

    suspend fun delete(workspace: Long, id: Long) {
        try {
            call("DELETE", "workspaces/$workspace/time_entries/$id", null)
        } catch (e: TogglException) {
            if (e.message?.contains("404") != true) throw e // gone already
        }
    }

    private suspend fun me(): JsonObject = Json.parseToJsonElement(call("GET", "me", null)).jsonObject

    private suspend fun call(method: String, path: String, body: String?): String {
        val result = try {
            http(method, "$BASE/$path", token, "api_token", body)
        } catch (e: Exception) {
            throw TogglException("Keine Verbindung zu Toggl", retryAfterMs = 60_000)
        }
        return when (result.code) {
            in 200..299 -> result.body
            401, 403 -> throw TogglException("Toggl lehnt den API-Token ab")
            // The free plan allows only so many requests an hour to the account (/me) endpoints.
            402 -> {
                val seconds = Regex("reset in (\\d+) seconds").find(result.body)?.groupValues?.get(1)?.toLongOrNull() ?: 600
                throw TogglException(
                    "Toggl-Stundenlimit (kostenloser Plan) erreicht – geht in ca. ${(seconds + 59) / 60} min wieder",
                    retryAfterMs = (seconds + 5) * 1000,
                )
            }
            429 -> throw TogglException("Toggl ist gerade überlastet – gleich nochmal versuchen", retryAfterMs = 60_000)
            else -> throw TogglException("Toggl-Fehler ${result.code}: ${result.body.take(120)}")
        }
    }

    private companion object {
        const val BASE = "https://api.track.toggl.com/api/v9"
    }
}

/** One stretch of a run in Toggl, from start or resume to pause or end ([end] null: still going). */
data class TogglSegment(
    val start: Long,
    val end: Long? = null,
    /** The Toggl entry, once created. */
    val entryId: Long? = null,
    /** Its entry runs on in Toggl and still needs its stop time. */
    val running: Boolean = false,
    /** Carries [TOGGL_ABORTED_TAG]. */
    val tagged: Boolean = false,
)

/** What Wassertiger keeps in Toggl for run [runId]. */
data class TogglRun(
    val runId: Long,
    val sessionName: String,
    val description: String,
    val project: TogglProject?,
    val segments: List<TogglSegment>,
    /** Stopped before its end. */
    val aborted: Boolean = false,
    /** Taken out of Toggl again. */
    val deleted: Boolean = false,
    val workspace: Long? = null,
    val error: String? = null,
) {
    /** Counting in Toggl right now. */
    val live: Boolean get() = segments.any { it.running && it.end == null }

    /** Everything that ended is in Toggl as it should be. */
    val synced: Boolean get() = segments.all { s ->
        if (s.end == null) true else s.entryId != null && !s.running && (!aborted || s.tagged)
    }

    fun millis(now: Long = wallClockMillis()): Long = segments.sumOf { (it.end ?: now) - it.start }
    val firstStart: Long get() = segments.firstOrNull()?.start ?: 0
    val lastEnd: Long? get() = segments.lastOrNull()?.end
}

/**
 * Keeps Toggl in step with the runs: a session counts in Toggl from its start, named as it was
 * booked last time (or after the session). A pause ends the entry, going on starts a new one; the
 * end stops it, and Stopp before the end stops it there and tags it [TOGGL_ABORTED_TAG]. Toggl
 * runs one timer at a time, so a run only counts live there while no other timer runs (the user's
 * own included, which is never touched); otherwise its stretches are booked as they end.
 *
 * Fed by the platform with every [TimerEvent]; network trouble leaves the run unsynced, see [retry].
 */
class TogglTracker(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val client: (String) -> Toggl = { Toggl(it) },
) {
    private val _runs = MutableStateFlow<Map<Long, TogglRun>>(emptyMap())
    val runs: StateFlow<Map<Long, TogglRun>> = _runs.asStateFlow()

    private val mutex = Mutex()
    /** Tries again on its own after network trouble or Toggl's hourly limit, one per run. */
    private val retries = mutableMapOf<Long, Job>()
    private var projectCache: Pair<Long, List<TogglProject>>? = null
    /** Requests under way; touched on the main thread only, like everything here. */
    private val jobs = mutableListOf<Job>()

    fun onEvent(event: TimerEvent, run: RunState) {
        val id = run.id
        val now = wallClockMillis()
        when (event) {
            TimerEvent.Started -> {
                if (settings.togglToken == null || !run.session.toggl) return
                val (description, project) = settings.lastEntry(run.session.name) ?: (run.session.name to null)
                change(id) { TogglRun(id, run.session.name, description, project, listOf(TogglSegment(run.startedAt))) }
            }
            TimerEvent.PauseChanged -> change(id) { t -> if (run.paused) t?.close(now) else t?.open(now) }
            TimerEvent.Finished -> change(id) { t -> t?.close(run.endedAt) }
            TimerEvent.Stopped -> change(id) { t ->
                when {
                    t == null -> null
                    // Dropped after moments (started by mistake): leaves no trace. One that stays on
                    // screen as "Gestoppt" (see keepForToggl) stays in Toggl.
                    !run.stopped && run.activeMillis(now) < TOGGL_MIN_MILLIS -> t.copy(deleted = true)
                    else -> t.close(if (run.endedAt > 0) run.endedAt else now).copy(aborted = true)
                }
            }
            TimerEvent.Step -> return
        }
        sync(id)
    }

    /** Renames run [runId] in Toggl (and remembers it for its session's next run). */
    fun edit(runId: Long, description: String, project: TogglProject?) {
        val t = runs.value[runId] ?: return
        settings.rememberEntry(t.sessionName, description, project)
        change(runId) { it?.copy(description = description, project = project) }
        launch {
            val token = settings.togglToken ?: return@launch
            val toggl = client(token)
            val current = runs.value[runId] ?: return@launch
            try {
                val workspace = workspaceOf(current, toggl)
                current.segments.mapNotNull { it.entryId }.forEach {
                    toggl.update(workspace, it, description = description, setProject = true, project = project)
                }
                change(runId) { it?.copy(error = null) }
            } catch (e: TogglException) {
                change(runId) { it?.copy(error = e.message) }
            }
        }
    }

    /** Takes run [runId] out of Toggl. */
    fun delete(runId: Long) {
        change(runId) { it?.copy(deleted = true) }
        sync(runId)
    }

    /** Books a finished run Toggl did not follow (connected later, or Toggl off for its session). */
    fun book(run: RunState, description: String, project: TogglProject?) {
        settings.rememberEntry(run.session.name, description, project)
        val end = if (run.endedAt > 0) run.endedAt else wallClockMillis()
        change(run.id) {
            TogglRun(run.id, run.session.name, description, project, listOf(TogglSegment(end - run.activeMillis(), end)), aborted = run.stopped)
        }
        sync(run.id)
    }

    /** Tries again what network trouble held up. */
    fun retry(runId: Long) {
        retries.remove(runId)?.cancel()
        sync(runId)
    }

    /** The projects for the menu, fetched at most every ten minutes (they count against Toggl's hourly limit). */
    suspend fun projects(): List<TogglProject> {
        val token = settings.togglToken ?: return emptyList()
        projectCache?.let { (at, list) -> if (wallClockMillis() - at < 10 * 60_000) return list }
        return client(token).projects().also { projectCache = wallClockMillis() to it }
    }

    /** Waits for every request so far (tests). */
    suspend fun idle() {
        while (true) {
            val pending = jobs.toList().also { jobs.clear() }
            if (pending.isEmpty()) return
            pending.joinAll()
        }
    }

    private fun TogglRun.close(at: Long): TogglRun =
        copy(segments = segments.map { if (it.end == null) it.copy(end = maxOf(at, it.start)) else it })

    private fun TogglRun.open(at: Long): TogglRun =
        if (segments.any { it.end == null }) this else copy(segments = segments + TogglSegment(at))

    private fun change(id: Long, transform: (TogglRun?) -> TogglRun?) {
        val next = transform(runs.value[id])
        _runs.value = if (next == null) runs.value - id else runs.value + (id to next)
    }

    private fun launch(block: suspend () -> Unit) {
        val job = scope.launch { mutex.withLock { block() } }
        jobs.removeAll { it.isCompleted }
        jobs += job
    }

    private suspend fun workspaceOf(t: TogglRun, toggl: Toggl): Long =
        t.workspace ?: t.project?.workspaceId ?: settings.togglWorkspace
            ?: toggl.defaultWorkspace().also { settings.togglWorkspace = it }

    /** Brings Toggl in line with what the run did; one request at a time, in order. */
    private fun sync(id: Long): Unit = launch {
        val token = settings.togglToken ?: return@launch
        val toggl = client(token)
        try {
            var t = runs.value[id] ?: return@launch
            val workspace = workspaceOf(t, toggl)
            if (t.workspace == null) change(id) { it?.copy(workspace = workspace) }
            if (t.deleted) {
                t.segments.mapNotNull { it.entryId }.forEach { toggl.delete(workspace, it) }
                change(id) { it?.copy(segments = it.segments.map { s -> s.copy(entryId = null, running = false) }, error = null) }
                return@launch
            }
            t.segments.forEachIndexed { i, s ->
                t = runs.value[id] ?: return@launch
                val tags = if (t.aborted) listOf(TOGGL_ABORTED_TAG) else emptyList()
                val synced = when {
                    // Still going: count live, unless another timer runs in Toggl (or that cannot be told).
                    s.end == null && s.entryId == null && noTimerRunning(toggl) ->
                        s.copy(entryId = toggl.create(workspace, t.description, t.project, s.start, null, emptyList()), running = true)
                    s.end == null -> s
                    s.entryId == null ->
                        s.copy(entryId = toggl.create(workspace, t.description, t.project, s.start, s.end, tags), tagged = t.aborted)
                    s.running || (t.aborted && !s.tagged) -> {
                        toggl.update(workspace, s.entryId, stop = s.end, tags = tags.takeIf { t.aborted })
                        s.copy(running = false, tagged = t.aborted)
                    }
                    else -> s
                }
                // The segment may have ended meanwhile (paused while the request ran): keep its end.
                if (synced != s) change(id) { r ->
                    r?.copy(segments = r.segments.mapIndexed { j, cur ->
                        if (j == i) synced.copy(end = cur.end ?: synced.end) else cur
                    })
                }
            }
            change(id) { it?.copy(error = null) }
            // A pause or the end may have come while this ran: their requests are queued behind.
        } catch (e: TogglException) {
            change(id) { it?.copy(error = e.message) }
            e.retryAfterMs?.let { wait ->
                retries.remove(id)?.cancel()
                retries[id] = scope.launch {
                    delay(wait)
                    retries.remove(id)
                    sync(id)
                }
            }
        }
    }

    private suspend fun noTimerRunning(toggl: Toggl): Boolean = try {
        !toggl.timerRunning()
    } catch (e: TogglException) {
        false // not live then: booked when it pauses or ends
    }
}

/**
 * App settings beside the sessions: the Toggl token and, per session name, what was booked last
 * (so the next "Pomodoro" counts in Toggl under the right name straight away).
 */
class SettingsStore(private val storage: SessionStorage) {

    private fun read(): JsonObject =
        storage.read()?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    private fun write(change: MutableMap<String, JsonElement>.() -> Unit) {
        storage.write(JsonObject(read().toMutableMap().apply(change)).toString())
    }

    var togglToken: String?
        get() = read()["togglToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        set(value) = write {
            remove("togglWorkspace")
            if (value.isNullOrBlank()) remove("togglToken") else put("togglToken", JsonPrimitive(value.trim()))
        }

    /** The account's default workspace, kept so it need not be asked for (see Toggl's hourly limit). */
    var togglWorkspace: Long?
        get() = read()["togglWorkspace"]?.jsonPrimitive?.longOrNull
        set(value) = write { if (value == null) remove("togglWorkspace") else put("togglWorkspace", JsonPrimitive(value)) }

    /** Description and project booked last for [sessionName]. */
    fun lastEntry(sessionName: String): Pair<String, TogglProject?>? {
        val o = read()["togglLast"]?.jsonObject?.get(sessionName)?.jsonObject ?: return null
        val description = o["description"]?.jsonPrimitive?.contentOrNull ?: return null
        val project = o["project"]?.jsonObject?.let { p ->
            TogglProject(
                id = p["id"]?.jsonPrimitive?.longOrNull ?: return@let null,
                name = p["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                workspaceId = p["workspace"]?.jsonPrimitive?.longOrNull ?: return@let null,
            )
        }
        return description to project
    }

    fun rememberEntry(sessionName: String, description: String, project: TogglProject?) = write {
        val last = (this["togglLast"] as? JsonObject).orEmpty().toMutableMap()
        last[sessionName] = buildJsonObject {
            put("description", description)
            project?.let {
                put("project", buildJsonObject {
                    put("id", it.id)
                    put("name", it.name)
                    put("workspace", it.workspaceId)
                })
            }
        }
        put("togglLast", JsonObject(last))
    }
}

/**
 * For [TimerEngine.keepStopped]: a run Toggl follows stays as "Gestoppt" when stopped after a
 * minute or more, so its entry can be renamed or deleted.
 */
fun keepForToggl(settings: SettingsStore): (RunState) -> Boolean =
    { settings.togglToken != null && it.session.toggl && it.activeMillis() >= TOGGL_MIN_MILLIS }
