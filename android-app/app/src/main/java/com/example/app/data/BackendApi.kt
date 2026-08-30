package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal HTTP client for the CalmSense backend.
 *
 * Why HttpURLConnection and not Retrofit / Ktor: this keeps the dependency
 * footprint zero. Once the surface area grows (more endpoints, JWT, etc.)
 * swap in Retrofit — but for two endpoints that's overkill.
 *
 * Default base URL targets the Android emulator's host loopback.
 *   - Emulator:           http://10.0.2.2:8000   <-- the host machine
 *   - Real device, LAN:   http://192.168.x.x:8000   <-- whatever ipconfig says
 *   - Production:         https://api.calmsense...   (later)
 *
 * Cleartext HTTP requires `usesCleartextTraffic="true"` (or a network
 * security config) in AndroidManifest — see the manifest in this branch.
 */
class BackendApi(private val baseUrl: String = DEFAULT_BASE_URL) {

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
        // Generous because the backend scales to zero: a cold start has to
        // boot the container, import sklearn/supabase and open a Supabase
        // connection before it answers. 5s was fine against a Pi that was
        // always warm; against Cloud Run it turns a slow first request into a
        // spurious "server offline".
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    /** GET /api/v1/sensor-data?user_id=... — returns the panic-detection model. */
    suspend fun fetchWeights(userId: String): PanicModel = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(userId, "UTF-8")
        val url = URL("$baseUrl/api/v1/sensor-data?user_id=$encoded")
        val token = SessionManager.validAccessToken()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("GET weights failed: HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parsePanicModel(body)
        } finally {
            conn.disconnect()
        }
    }

    /** POST /api/v1/sensor-data — append a sensor reading. */
    suspend fun postSensorData(
        userId: String,
        hr: Double,
        hrv: Double,
        motion: Double,
        panicAttackDetection: Boolean,
        timestampIso: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("user_id", userId)
            put("panic_attack_detection", panicAttackDetection)
            put("current_hr", hr)
            put("current_hrv", hrv)
            put("current_motion_intensity", motion)
            put("timestamp", timestampIso ?: JSONObject.NULL)
        }
        val url = URL("$baseUrl/api/v1/sensor-data")
        val token = SessionManager.validAccessToken()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePanicModel(body: String): PanicModel {
        val json = JSONObject(body)
        val arr = json.getJSONArray("weights")
        if (arr.length() != 5) {
            throw RuntimeException("expected 5 weights, got ${arr.length()}")
        }
        val w = DoubleArray(5) { arr.getDouble(it) }
        val source = json.optString("source", "default")
        val meta = json.optJSONObject("model_meta")
        return PanicModel(
            weights = w,
            source = source,
            modelType = meta?.optString("model_type", null),
            trainedAt = meta?.optString("trained_at", null),
            testAccuracy = meta?.let {
                if (it.has("test_accuracy") && !it.isNull("test_accuracy"))
                    it.getDouble("test_accuracy") else null
            },
        )
    }
}
