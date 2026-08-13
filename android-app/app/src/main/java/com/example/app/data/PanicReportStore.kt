package com.example.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON-file store for [PanicReportEntity] rows.
 *
 * Single file in the app's private files dir. Loaded into memory at first
 * access (volume is trivially small — under a few KB per year of typical
 * use). All mutations atomically rewrite the file and update a hot
 * [StateFlow] that the UI observes.
 */
class PanicReportStore private constructor(context: Context) {

    private val file: File = File(context.filesDir, "panic_reports.json")
    private val writeMutex = Mutex()

    private val _rows = MutableStateFlow<List<PanicReportEntity>>(emptyList())
    val rows: StateFlow<List<PanicReportEntity>> = _rows.asStateFlow()

    init {
        _rows.value = loadFromDisk()
    }

    suspend fun insert(report: PanicReportEntity): PanicReportEntity = writeMutex.withLock {
        val current = _rows.value
        val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        val stored = report.copy(id = nextId)
        val updated = current + stored
        persist(updated)
        _rows.value = updated
        stored
    }

    suspend fun update(report: PanicReportEntity) = writeMutex.withLock {
        val updated = _rows.value.map { if (it.id == report.id) report else it }
        persist(updated)
        _rows.value = updated
    }

    suspend fun delete(id: Long) = writeMutex.withLock {
        val updated = _rows.value.filterNot { it.id == id }
        if (updated.size == _rows.value.size) return@withLock
        persist(updated)
        _rows.value = updated
    }

    suspend fun findById(id: Long): PanicReportEntity? = _rows.value.firstOrNull { it.id == id }

    suspend fun unsynced(): List<PanicReportEntity> = _rows.value.filter { !it.syncedToBackend }

    private fun loadFromDisk(): List<PanicReportEntity> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            List(arr.length()) { i -> deserialize(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private suspend fun persist(rows: List<PanicReportEntity>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        rows.forEach { arr.put(serialize(it)) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Atomic rename failed (rare on Android); fall back to overwrite.
            file.writeText(tmp.readText(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun serialize(r: PanicReportEntity): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        o.put("user_id", r.userId)
        o.put("timestamp_ms", r.timestampMs)
        o.put("severity", r.severity)
        o.put("detected_by_model", r.detectedByModel)
        if (r.feeling != null) o.put("feeling", r.feeling)
        val sa = JSONArray()
        r.symptoms.forEach { sa.put(it) }
        o.put("symptoms", sa)
        if (r.activityBefore != null) o.put("activity_before", r.activityBefore)
        if (r.whatHelped != null) o.put("what_helped", r.whatHelped)
        if (r.durationMinutes != null) o.put("duration_minutes", r.durationMinutes)
        if (r.latitude != null) o.put("latitude", r.latitude)
        if (r.longitude != null) o.put("longitude", r.longitude)
        if (r.locationAccuracyM != null) o.put("location_accuracy_m", r.locationAccuracyM.toDouble())
        if (r.currentHr != null) o.put("current_hr", r.currentHr)
        if (r.currentHrv != null) o.put("current_hrv", r.currentHrv)
        if (r.currentMotionIntensity != null)
            o.put("current_motion_intensity", r.currentMotionIntensity.toDouble())
        if (r.duringSleep != null) o.put("during_sleep", r.duringSleep)
        o.put("synced_to_backend", r.syncedToBackend)
        return o
    }

    private fun deserialize(o: JSONObject): PanicReportEntity {
        val symptomsArr = o.optJSONArray("symptoms")
        val symptoms = if (symptomsArr != null) {
            List(symptomsArr.length()) { symptomsArr.getString(it) }
        } else emptyList()
        return PanicReportEntity(
            id = o.optLong("id"),
            // Legacy rows written before per-user isolation shipped have no
            // "user_id" key; treat as empty (they'll be invisible to any
            // authenticated user via the repository's filtered observers).
            userId = o.optString("user_id", ""),
            timestampMs = o.getLong("timestamp_ms"),
            severity = o.getInt("severity"),
            detectedByModel = o.getBoolean("detected_by_model"),
            feeling = o.optString("feeling", null).takeIf { !it.isNullOrEmpty() },
            symptoms = symptoms,
            activityBefore = o.optString("activity_before", null).takeIf { !it.isNullOrEmpty() },
            whatHelped = o.optString("what_helped", null).takeIf { !it.isNullOrEmpty() },
            durationMinutes = if (o.has("duration_minutes") && !o.isNull("duration_minutes"))
                o.getInt("duration_minutes") else null,
            latitude = if (o.has("latitude") && !o.isNull("latitude")) o.getDouble("latitude") else null,
            longitude = if (o.has("longitude") && !o.isNull("longitude")) o.getDouble("longitude") else null,
            locationAccuracyM = if (o.has("location_accuracy_m") && !o.isNull("location_accuracy_m"))
                o.getDouble("location_accuracy_m").toFloat() else null,
            currentHr = if (o.has("current_hr") && !o.isNull("current_hr")) o.getInt("current_hr") else null,
            currentHrv = if (o.has("current_hrv") && !o.isNull("current_hrv")) o.getDouble("current_hrv") else null,
            currentMotionIntensity = if (o.has("current_motion_intensity") && !o.isNull("current_motion_intensity"))
                o.getDouble("current_motion_intensity").toFloat() else null,
            duringSleep = if (o.has("during_sleep") && !o.isNull("during_sleep"))
                o.getBoolean("during_sleep") else null,
            syncedToBackend = o.optBoolean("synced_to_backend", false),
        )
    }

    companion object {
        @Volatile private var INSTANCE: PanicReportStore? = null

        fun get(context: Context): PanicReportStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PanicReportStore(context).also { INSTANCE = it }
            }
        }
    }
}
