package com.example.app.data

import android.content.Context
import com.example.app.BACKEND_URL
import com.example.app.USER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Hidden category of a history entry; the user never sees the raw value,
 * only the filter chips. ANXIETY rows live in [PanicReportStore]; the other
 * two each have their own file and their own backend table, and neither ever
 * goes near panic_feedback, so they can't train the anxiety model.
 */
enum class EventType(val apiPath: String?, val fileName: String?) {
    ANXIETY(null, null),
    DISSOCIATION("/api/v1/dissociation-reports", "dissociation_reports.json"),
    FLASHBACK("/api/v1/flashback-reports", "flashback_reports.json"),
}

/**
 * One manual dissociation or flashback report. Chip lists store stable
 * English keys (see EventChips.kt), never display text. Vitals are a
 * snapshot at the moment the user tapped report; kept for later analysis.
 */
data class EventReportEntity(
    val id: Long = 0L,
    val userId: String = "",
    val type: EventType,
    val timestampMs: Long,
    val emotions: List<String> = emptyList(),
    val feeling: String? = null,
    val activityBefore: String? = null,
    val whatHelped: String? = null,
    val durationMinutes: Int? = null,
    // Dissociation only
    val experiences: List<String> = emptyList(),
    val memoryClarity: String? = null,      // clear | partial | none
    // Flashback only
    val senses: List<String> = emptyList(),
    val nowness: String? = null,            // memory | partial | now
    val triggers: List<String> = emptyList(),
    val bodyReactions: List<String> = emptyList(),
    // Context
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    val currentHr: Int? = null,
    val currentHrv: Double? = null,
    val currentMotionIntensity: Float? = null,
    val hrvSource: HrvSource? = null,
    val duringSleep: Boolean? = null,
    /** True while the user is still in grounding / the questionnaire, so a
     *  background sync doesn't post a half-filled row (see [syncPending]). */
    val draft: Boolean = false,
    val syncedToBackend: Boolean = false,
) {
    /** Request body for the backend. No user_id: the server takes it from the token. */
    fun toApiJson(): JSONObject = JSONObject().apply {
        put("timestamp", Instant.ofEpochMilli(timestampMs).toString())
        put("emotions", JSONArray(emotions))
        feeling?.let { put("feeling", it) }
        activityBefore?.let { put("activity_before", it) }
        whatHelped?.let { put("what_helped", it) }
        durationMinutes?.let { put("duration_minutes", it) }
        if (type == EventType.DISSOCIATION) {
            put("experiences", JSONArray(experiences))
            memoryClarity?.let { put("memory_clarity", it) }
        }
        if (type == EventType.FLASHBACK) {
            put("senses", JSONArray(senses))
            nowness?.let { put("nowness", it) }
            put("triggers", JSONArray(triggers))
            put("body_reactions", JSONArray(bodyReactions))
        }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        locationAccuracyM?.let { put("location_accuracy_m", it.toDouble()) }
        currentHr?.let { put("current_hr", it) }
        currentHrv?.let { put("current_hrv", it) }
        currentMotionIntensity?.let { put("current_motion_intensity", it.toDouble()) }
        hrvSource?.let { put("hrv_source", it.apiValue) }
        duringSleep?.let { put("during_sleep", it) }
    }

    /** On-disk form: the API body plus the local bookkeeping fields. */
    fun toDiskJson(): JSONObject = toApiJson().apply {
        put("id", id)
        put("user_id", userId)
        put("type", type.name)
        put("timestamp_ms", timestampMs)
        put("draft", draft)
        put("synced_to_backend", syncedToBackend)
    }

    companion object {
        fun fromDiskJson(o: JSONObject): EventReportEntity {
            fun list(k: String): List<String> =
                o.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
            fun dbl(k: String): Double? = if (o.isNull(k)) null else o.getDouble(k)
            return EventReportEntity(
                id = o.getLong("id"),
                userId = o.optString("user_id", ""),
                type = EventType.valueOf(o.getString("type")),
                timestampMs = o.getLong("timestamp_ms"),
                emotions = list("emotions"),
                feeling = str("feeling"),
                activityBefore = str("activity_before"),
                whatHelped = str("what_helped"),
                durationMinutes = if (o.isNull("duration_minutes")) null else o.getInt("duration_minutes"),
                experiences = list("experiences"),
                memoryClarity = str("memory_clarity"),
                senses = list("senses"),
                nowness = str("nowness"),
                triggers = list("triggers"),
                bodyReactions = list("body_reactions"),
                latitude = dbl("latitude"),
                longitude = dbl("longitude"),
                locationAccuracyM = dbl("location_accuracy_m")?.toFloat(),
                currentHr = if (o.isNull("current_hr")) null else o.getInt("current_hr"),
                currentHrv = dbl("current_hrv"),
                currentMotionIntensity = dbl("current_motion_intensity")?.toFloat(),
                hrvSource = HrvSource.fromApi(str("hrv_source")),
                duringSleep = if (o.isNull("during_sleep")) null else o.getBoolean("during_sleep"),
                draft = o.optBoolean("draft", false),
                syncedToBackend = o.optBoolean("synced_to_backend", false),
            )
        }
    }
}

/**
 * Local JSON file + backend mirror for one [EventType], same model as the
 * anxiety reports: always written to the phone first, posted when online,
 * retried by [syncPending]. One instance (and one file) per type.
 */
class EventReportRepository private constructor(
    context: Context,
    val type: EventType,
    private val backend: BackendClient,
) {
    private val file = File(context.filesDir, type.fileName!!)
    private val mutex = Mutex()
    private val rows = MutableStateFlow(load())

    /** Current user's rows, newest first; swaps when the account changes. */
    fun observeAll(): Flow<List<EventReportEntity>> =
        combine(rows, SessionManager.session) { all, session ->
            val uid = session?.userId ?: return@combine emptyList()
            all.filter { it.userId == uid }.sortedByDescending { it.timestampMs }
        }

    fun findById(id: Long): EventReportEntity? {
        val uid = SessionManager.userId ?: return null
        return rows.value.firstOrNull { it.id == id && it.userId == uid }
    }

    /** Saves a draft locally (not posted yet) and returns its id. */
    suspend fun insertDraft(report: EventReportEntity): Long = mutex.withLock {
        val stored = report.copy(
            id = (rows.value.maxOfOrNull { it.id } ?: 0L) + 1L,
            userId = USER_ID, type = type, draft = true, syncedToBackend = false,
        )
        write(rows.value + stored)
        stored.id
    }

    /** Local update only (e.g. a late GPS fix on a draft). */
    suspend fun update(report: EventReportEntity) = mutex.withLock {
        write(rows.value.map { if (it.id == report.id) report else it })
    }

    /** The user finished (saved or skipped): stop being a draft and post it. */
    suspend fun finishAndSync(report: EventReportEntity) {
        val done = report.copy(draft = false, syncedToBackend = false)
        update(done)
        if (post(done)) update(done.copy(syncedToBackend = true))
    }

    suspend fun syncPending() {
        val staleDraft = System.currentTimeMillis() - DRAFT_ABANDONED_MS
        for (row in rows.value.filter { !it.syncedToBackend }) {
            // A draft older than this was abandoned (app killed mid-questionnaire):
            // send what we have rather than lose the event and its vitals.
            if (row.draft && row.timestampMs > staleDraft) continue
            if (post(row)) update(row.copy(draft = false, syncedToBackend = true))
        }
    }

    /** Local-only delete, like the anxiety reports. */
    suspend fun delete(id: Long) = mutex.withLock {
        write(rows.value.filterNot { it.id == id })
    }

    private suspend fun post(row: EventReportEntity): Boolean =
        when (val r = backend.postJson(type.apiPath!!, row.toApiJson())) {
            PostResult.Success -> true
            is PostResult.HttpError -> { AppLog.w(TAG, "${type.name} sync HTTP ${r.code}"); false }
            is PostResult.NetworkError -> { AppLog.w(TAG, "${type.name} sync offline (${r.reason})"); false }
        }

    private fun load(): List<EventReportEntity> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        List(arr.length()) { EventReportEntity.fromDiskJson(arr.getJSONObject(it)) }
    }.getOrElse { e ->
        // Keep the unreadable file instead of overwriting it on the next write.
        AppLog.w(TAG, "${type.name} store unreadable (${e.javaClass.simpleName})")
        file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        emptyList()
    }

    private suspend fun write(updated: List<EventReportEntity>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        updated.forEach { arr.put(it.toDiskJson()) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(), Charsets.UTF_8)
            tmp.delete()
        }
        rows.value = updated
    }

    companion object {
        private const val TAG = "EventReportRepo"
        private const val DRAFT_ABANDONED_MS = 6L * 60 * 60 * 1000

        private val instances = mutableMapOf<EventType, EventReportRepository>()

        fun get(context: Context, type: EventType): EventReportRepository = synchronized(instances) {
            require(type != EventType.ANXIETY) { "Anxiety reports use PanicReportRepository" }
            instances.getOrPut(type) {
                EventReportRepository(context.applicationContext, type, BackendClient(BACKEND_URL))
            }
        }
    }
}
