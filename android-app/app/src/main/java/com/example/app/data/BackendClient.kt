package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class BackendClient(private val baseUrl: String) {

    suspend fun ping(): PingResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$baseUrl/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
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
            val conn = URL("$baseUrl/api/v1/sensor-data").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
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

    suspend fun submitPanicFeedback(payload: PanicFeedbackPayload): PostResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$baseUrl/api/v1/panic-feedback").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
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
