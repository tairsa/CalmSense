package com.example.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Store-and-forward queue for backend uploads. When the server is
 * unreachable, payloads are held here (backed by a file in filesDir, so they
 * survive app restarts) and drained in order on the next successful contact.
 *
 * Payloads are stamped with their event time before queueing, so rows
 * uploaded late still land with the right timestamp on the server.
 *
 * Panic reports are NOT routed through here — PanicReportStore already
 * persists them locally with its own syncedToBackend flag.
 */
object UploadQueue {
    private const val TAG = "UploadQueue"
    private const val FILE_NAME = "upload_queue.jsonl"

    // Sensor readings stream every 5–30 s; cap them so days offline can't
    // grow the queue without bound. Feedback rows are never dropped.
    private const val MAX_SENSOR_ENTRIES = 1000

    private const val KIND_SENSOR = "sensor"
    private const val KIND_FEEDBACK = "feedback"

    private var file: File? = null
    private val mutex = Mutex()
    private val entries = ArrayDeque<JSONObject>() // {kind, body}

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /** Idempotent. Call from every process entry point (activity + services). */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        entries.clear()
        if (f.exists()) {
            f.forEachLine { line ->
                runCatching { entries.add(JSONObject(line)) }
                    .onFailure { Log.w(TAG, "Dropping unreadable queue line") }
            }
        }
        _pendingCount.value = entries.size
    }

    suspend fun postSensor(backend: BackendClient, payload: SensorPayload): PostResult {
        val stamped = if (payload.timestamp != null) payload
        else payload.copy(timestamp = Instant.now().toString())
        return post(backend, KIND_SENSOR, sensorToJson(stamped))
    }

    suspend fun postFeedback(backend: BackendClient, payload: PanicFeedbackPayload): PostResult {
        val stamped = if (payload.timestamp != null) payload
        else payload.copy(timestamp = Instant.now().toString())
        return post(backend, KIND_FEEDBACK, feedbackToJson(stamped))
    }

    /** Older queued rows go first so the server sees events in order; the
     *  new payload is queued (not lost) whenever the server is unreachable. */
    private suspend fun post(backend: BackendClient, kind: String, body: JSONObject): PostResult {
        flush(backend)
        mutex.withLock {
            if (entries.isNotEmpty()) { // still offline — queue behind the rest
                enqueueLocked(kind, body)
                return PostResult.NetworkError("offline (queued)")
            }
        }
        val r = send(backend, kind, body)
        if (r is PostResult.NetworkError) {
            mutex.withLock { enqueueLocked(kind, body) }
        }
        return r
    }

    /** Send queued rows oldest-first until empty or the network fails again.
     *  HTTP errors drop the row — the server got it and said no; retrying
     *  the same body forever would wedge the queue. */
    suspend fun flush(backend: BackendClient) {
        mutex.withLock {
            while (entries.isNotEmpty()) {
                val entry = entries.first()
                val r = send(backend, entry.getString("kind"), entry.getJSONObject("body"))
                if (r is PostResult.NetworkError) return
                if (r is PostResult.HttpError) {
                    Log.w(TAG, "Dropping queued ${entry.getString("kind")} row: HTTP ${r.code}")
                }
                entries.removeFirst()
                persistLocked()
            }
        }
    }

    private suspend fun send(backend: BackendClient, kind: String, body: JSONObject): PostResult =
        when (kind) {
            KIND_SENSOR -> backend.postSensorData(sensorFromJson(body))
            KIND_FEEDBACK -> backend.submitPanicFeedback(feedbackFromJson(body))
            else -> PostResult.HttpError(0) // unknown kind: drop it
        }

    private fun enqueueLocked(kind: String, body: JSONObject) {
        if (kind == KIND_SENSOR &&
            entries.count { it.getString("kind") == KIND_SENSOR } >= MAX_SENSOR_ENTRIES
        ) {
            val oldest = entries.firstOrNull { it.getString("kind") == KIND_SENSOR }
            if (oldest != null) entries.remove(oldest)
        }
        entries.add(JSONObject().put("kind", kind).put("body", body))
        persistLocked()
    }

    private fun persistLocked() {
        _pendingCount.value = entries.size
        val f = file ?: return
        runCatching {
            f.writeText(entries.joinToString("\n") { it.toString() })
        }.onFailure { Log.w(TAG, "Could not persist upload queue", it) }
    }

    // --- payload <-> JSON ----------------------------------------------------

    private fun sensorToJson(p: SensorPayload) = JSONObject().apply {
        put("user_id", p.userId)
        put("panic", p.panicAttackDetection)
        put("hr", p.currentHr.toDouble())
        put("hrv", p.currentHrv.toDouble())
        put("motion", p.currentMotionIntensity.toDouble())
        put("timestamp", p.timestamp)
    }

    private fun sensorFromJson(o: JSONObject) = SensorPayload(
        userId = o.getString("user_id"),
        panicAttackDetection = o.getBoolean("panic"),
        currentHr = o.getDouble("hr").toFloat(),
        currentHrv = o.getDouble("hrv").toFloat(),
        currentMotionIntensity = o.getDouble("motion").toFloat(),
        timestamp = o.optString("timestamp", null),
    )

    private fun feedbackToJson(p: PanicFeedbackPayload) = JSONObject().apply {
        put("user_id", p.userId)
        put("was_panic", p.wasPanic)
        if (p.severity != null) put("severity", p.severity)
        put("detected_by_model", p.detectedByModel)
        if (p.currentHr != null) put("hr", p.currentHr.toDouble())
        if (p.currentHrv != null) put("hrv", p.currentHrv.toDouble())
        if (p.currentMotionIntensity != null) put("motion", p.currentMotionIntensity.toDouble())
        if (p.modelProbability != null) put("probability", p.modelProbability)
        put("timestamp", p.timestamp)
    }

    private fun feedbackFromJson(o: JSONObject) = PanicFeedbackPayload(
        userId = o.getString("user_id"),
        wasPanic = o.getBoolean("was_panic"),
        severity = if (o.has("severity")) o.getInt("severity") else null,
        detectedByModel = o.getBoolean("detected_by_model"),
        currentHr = if (o.has("hr")) o.getDouble("hr").toFloat() else null,
        currentHrv = if (o.has("hrv")) o.getDouble("hrv").toFloat() else null,
        currentMotionIntensity = if (o.has("motion")) o.getDouble("motion").toFloat() else null,
        modelProbability = if (o.has("probability")) o.getDouble("probability") else null,
        timestamp = o.optString("timestamp", null),
    )
}
