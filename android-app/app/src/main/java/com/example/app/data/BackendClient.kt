package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class PingResult {
    data object Connected : PingResult()
    data class HttpError(val code: Int) : PingResult()
    data class NetworkError(val reason: String) : PingResult()
}

sealed class PostResult {
    data object Success : PostResult()
    data class HttpError(val code: Int) : PostResult()
    data class NetworkError(val reason: String) : PostResult()
}

data class SensorPayload(
    val userId: String,
    val panicAttackDetection: Boolean,
    val currentHr: Float,
    val currentHrv: Float,
    val currentMotionIntensity: Float,
    val timestamp: String? = null,
)

data class PanicFeedbackPayload(
    val userId: String,
    val wasPanic: Boolean,
    val severity: Int?,           // 1..10 when wasPanic=true; null otherwise
    val detectedByModel: Boolean, // true = detection path, false = manual log
    val currentHr: Float? = null,
    val currentHrv: Float? = null,
    val currentMotionIntensity: Float? = null,
    val modelProbability: Double? = null,
    val timestamp: String? = null,
)

data class PanicReportPayload(
    val userId: String,
    val timestamp: String?,
    val severity: Int,
    val detectedByModel: Boolean,
    val feeling: String?,
    val symptoms: List<String>,
    val activityBefore: String?,
    val whatHelped: String?,
    val durationMinutes: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyM: Float?,
    val currentHr: Float?,
    val currentHrv: Double?,
    val currentMotionIntensity: Float?,
)

class BackendClient(private val baseUrl: String) {

    suspend fun ping(): PingResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$baseUrl/health").openConnection() as HttpURLConnection
            // The status chip's reachability probe. Long enough to survive a
            // cold start, so "offline" means offline rather than "still waking".
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            try {
                val code = conn.responseCode
                if (code in 200..299) PingResult.Connected else PingResult.HttpError(code)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e -> PingResult.NetworkError(e.javaClass.simpleName) }
    }

    suspend fun postSensorData(payload: SensorPayload): PostResult = withContext(Dispatchers.IO) {
        runCatching {
            // Fetched before the connection is built: the call is suspending and
            // must happen at SEND time, so a queued payload gets a live token.
            val token = SessionManager.validAccessToken()
            val conn = URL("$baseUrl/api/v1/sensor-data").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("user_id", payload.userId)
                put("panic_attack_detection", payload.panicAttackDetection)
                put("current_hr", payload.currentHr.toDouble())
                put("current_hrv", payload.currentHrv.toDouble())
                put("current_motion_intensity", payload.currentMotionIntensity.toDouble())
                if (payload.timestamp != null) put("timestamp", payload.timestamp)
            }.toString()

            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) PostResult.Success else PostResult.HttpError(code)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e -> PostResult.NetworkError(e.javaClass.simpleName) }
    }

    suspend fun submitPanicReport(payload: PanicReportPayload): PostResult = withContext(Dispatchers.IO) {
        runCatching {
            // Fetched before the connection is built: the call is suspending and
            // must happen at SEND time, so a queued payload gets a live token.
            val token = SessionManager.validAccessToken()
            val conn = URL("$baseUrl/api/v1/panic-reports").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val symptomsArr = JSONArray()
            payload.symptoms.forEach { symptomsArr.put(it) }

            val body = JSONObject().apply {
                put("user_id", payload.userId)
                if (payload.timestamp != null) put("timestamp", payload.timestamp)
                put("severity", payload.severity)
                put("detected_by_model", payload.detectedByModel)
                if (payload.feeling != null) put("feeling", payload.feeling)
                put("symptoms", symptomsArr)
                if (payload.activityBefore != null) put("activity_before", payload.activityBefore)
                if (payload.whatHelped != null) put("what_helped", payload.whatHelped)
                if (payload.durationMinutes != null) put("duration_minutes", payload.durationMinutes)
                if (payload.latitude != null) put("latitude", payload.latitude)
                if (payload.longitude != null) put("longitude", payload.longitude)
                if (payload.locationAccuracyM != null)
                    put("location_accuracy_m", payload.locationAccuracyM.toDouble())
                if (payload.currentHr != null) put("current_hr", payload.currentHr.toDouble())
                if (payload.currentHrv != null) put("current_hrv", payload.currentHrv)
                if (payload.currentMotionIntensity != null)
                    put("current_motion_intensity", payload.currentMotionIntensity.toDouble())
            }.toString()

            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) PostResult.Success else PostResult.HttpError(code)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e -> PostResult.NetworkError(e.javaClass.simpleName) }
    }

    suspend fun submitPanicFeedback(payload: PanicFeedbackPayload): PostResult = withContext(Dispatchers.IO) {
        runCatching {
            // Fetched before the connection is built: the call is suspending and
            // must happen at SEND time, so a queued payload gets a live token.
            val token = SessionManager.validAccessToken()
            val conn = URL("$baseUrl/api/v1/panic-feedback").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("user_id", payload.userId)
                put("was_panic", payload.wasPanic)
                if (payload.severity != null) put("severity", payload.severity)
                put("detected_by_model", payload.detectedByModel)
                if (payload.currentHr != null) put("current_hr", payload.currentHr.toDouble())
                if (payload.currentHrv != null) put("current_hrv", payload.currentHrv.toDouble())
                if (payload.currentMotionIntensity != null)
                    put("current_motion_intensity", payload.currentMotionIntensity.toDouble())
                if (payload.modelProbability != null) put("model_probability", payload.modelProbability)
                if (payload.timestamp != null) put("timestamp", payload.timestamp)
            }.toString()

            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) PostResult.Success else PostResult.HttpError(code)
            } finally {
                conn.disconnect()
            }
        }.getOrElse { e -> PostResult.NetworkError(e.javaClass.simpleName) }
    }
}
