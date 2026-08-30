package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP client for the therapist-mode endpoints on the CalmSense backend.
 *
 * Kept separate from [BackendApi] and [BackendClient] so the therapist
 * feature can evolve without touching the patient hot path. Same plain
 * HttpURLConnection style - no new dependencies.
 */
class TherapistApi(private val baseUrl: String) {

    companion object {
        // See BackendApi: sized for a scale-to-zero cold start.
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    /* ---------- Types --------------------------------------------------- */

    data class ProfileDto(
        val userId: String,
        val role: String,               // "patient" | "therapist"
        val displayName: String?,
    )

    data class ConsentCodeResponse(
        val code: String,
        val expiresAt: String,          // ISO 8601 UTC
    )

    sealed interface RedeemResult {
        data object Success : RedeemResult
        data class Failure(val message: String, val httpCode: Int?) : RedeemResult
    }

    data class PatientSummary(
        val userId: String,
        val displayName: String?,
    )

    /** Lightweight view used only by the therapist's "patient detail" screen. */
    data class PatientReport(
        val severity: Int,
        val timestamp: String?,
        val detectedByModel: Boolean,
        val feeling: String?,
        val activityBefore: String?,
        val whatHelped: String?,
        val currentHr: Double?,
        val currentHrv: Double?,
        val currentMotionIntensity: Double?,
        val symptoms: List<String>,
    )

    /* ---------- Profile ------------------------------------------------- */

    /** POST /api/v1/profile - create or update. Returns true on 2xx. */
    suspend fun setProfile(userId: String, role: String, displayName: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("user_id", userId)
                put("role", role)
                put("display_name", displayName ?: JSONObject.NULL)
            }
            val code = postJson("$baseUrl/api/v1/profile", payload.toString(), SessionManager.validAccessToken())
            code in 200..299
        }

    /** GET /api/v1/profile?user_id=... - null if no row (or on network error). */
    suspend fun getProfile(userId: String): ProfileDto? = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl/api/v1/profile?user_id=${URLEncoder.encode(userId, "UTF-8")}")
        val body = simpleGet(url, SessionManager.validAccessToken()) ?: return@withContext null
        runCatching {
            val json = JSONObject(body)
            val p = json.optJSONObject("profile") ?: return@runCatching null
            ProfileDto(
                userId = p.optString("user_id"),
                role = p.optString("role"),
                displayName = p.optString("display_name").takeIf { !p.isNull("display_name") },
            )
        }.getOrNull()
    }

    /* ---------- Consent codes ------------------------------------------ */

    /** POST /api/v1/consent-codes - therapist generates a code to hand out. */
    suspend fun createConsentCode(therapistId: String): ConsentCodeResponse? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply { put("therapist_id", therapistId) }
            val body = postJsonForBody("$baseUrl/api/v1/consent-codes", payload.toString(), SessionManager.validAccessToken())
                ?: return@withContext null
            runCatching {
                val json = JSONObject(body)
                ConsentCodeResponse(
                    code = json.getString("code"),
                    expiresAt = json.getString("expires_at"),
                )
            }.getOrNull()
        }

    /** POST /api/v1/consent-codes/redeem - patient submits a code. */
    suspend fun redeemConsentCode(code: String, patientId: String): RedeemResult =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("code", code)
                put("patient_id", patientId)
            }
            val url = URL("$baseUrl/api/v1/consent-codes/redeem")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code2 = conn.responseCode
                if (code2 in 200..299) {
                    RedeemResult.Success
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val msg = runCatching { JSONObject(err).optString("detail") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "HTTP $code2"
                    RedeemResult.Failure(msg, code2)
                }
            } catch (t: Throwable) {
                RedeemResult.Failure(t.message ?: "network error", null)
            } finally {
                conn.disconnect()
            }
        }

    /* ---------- Therapist read views ----------------------------------- */

    /** GET /api/v1/therapist/{id}/patients - who's granted this therapist access. */
    suspend fun listPatients(therapistId: String): List<PatientSummary> =
        withContext(Dispatchers.IO) {
            val url = URL("$baseUrl/api/v1/therapist/${URLEncoder.encode(therapistId, "UTF-8")}/patients")
            val body = simpleGet(url, SessionManager.validAccessToken()) ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(body).getJSONArray("patients")
                List(arr.length()) { i ->
                    val p = arr.getJSONObject(i)
                    PatientSummary(
                        userId = p.getString("user_id"),
                        displayName = p.optString("display_name").takeIf { !p.isNull("display_name") },
                    )
                }
            }.getOrDefault(emptyList())
        }

    /** GET /api/v1/therapist/{id}/patients/{pid}/reports - server enforces the link. */
    suspend fun getPatientReports(therapistId: String, patientId: String): List<PatientReport> =
        withContext(Dispatchers.IO) {
            val t = URLEncoder.encode(therapistId, "UTF-8")
            val p = URLEncoder.encode(patientId, "UTF-8")
            val url = URL("$baseUrl/api/v1/therapist/$t/patients/$p/reports")
            val body = simpleGet(url, SessionManager.validAccessToken()) ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(body).getJSONArray("reports")
                List(arr.length()) { i ->
                    val r = arr.getJSONObject(i)
                    val symptoms = r.optJSONArray("symptoms")
                    PatientReport(
                        severity = r.optInt("severity", 0),
                        timestamp = r.optString("timestamp").takeIf { !r.isNull("timestamp") },
                        detectedByModel = r.optBoolean("detected_by_model", false),
                        feeling = r.optString("feeling").takeIf { !r.isNull("feeling") },
                        activityBefore = r.optString("activity_before").takeIf { !r.isNull("activity_before") },
                        whatHelped = r.optString("what_helped").takeIf { !r.isNull("what_helped") },
                        currentHr = if (r.has("current_hr") && !r.isNull("current_hr")) r.getDouble("current_hr") else null,
                        currentHrv = if (r.has("current_hrv") && !r.isNull("current_hrv")) r.getDouble("current_hrv") else null,
                        currentMotionIntensity = if (r.has("current_motion_intensity") && !r.isNull("current_motion_intensity"))
                            r.getDouble("current_motion_intensity") else null,
                        symptoms = if (symptoms != null) List(symptoms.length()) { symptoms.getString(it) } else emptyList(),
                    )
                }
            }.getOrDefault(emptyList())
        }

    /* ---------- Internals ---------------------------------------------- */

    private fun postJson(url: String, body: String, token: String?): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Throwable) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    private fun postJsonForBody(url: String, body: String, token: String?): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun simpleGet(url: URL, token: String?): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
