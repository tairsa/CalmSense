package com.example.app.data

import com.example.app.BACKEND_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/**
 * Read-only client for the therapist views: the patient list and one
 * patient's panic reports.
 *
 * Both endpoints live under /api/v1/admin because the admin dashboard already
 * serves exactly this data; the backend accepts a Supabase access token there
 * when the account's role is therapist/developer (see backend auth.py,
 * get_clinical_viewer). Plain HttpURLConnection to match BackendApi and
 * SupabaseAuth - no new dependency.
 *
 * Every call needs the caller's Supabase access token. The role check is
 * server-side: a regular user's token gets a 403 here no matter what the app's
 * own UserRole gate believes.
 */
object TherapistApi {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** One row in the patient picker. */
    data class Patient(
        val userId: String,
        val reportCount: Int,
        val lastSeen: String?,
    )

    /** Outcome wrapper so the UI can show a real message instead of a spinner. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val message: String, val httpCode: Int? = null) : Result<Nothing>
    }

    /** GET /api/v1/admin/users - accounts that have any data on the backend. */
    suspend fun listPatients(accessToken: String): Result<List<Patient>> =
        get("/api/v1/admin/users", accessToken) { body ->
            val arr = JSONObject(body).optJSONArray("users")
            buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.getJSONObject(i)
                    add(
                        Patient(
                            userId = o.optString("user_id"),
                            reportCount = o.optInt("report_count"),
                            lastSeen = o.optString("last_seen").takeIf {
                                it.isNotBlank() && it != "null"
                            },
                        )
                    )
                }
            }.sortedByDescending { it.reportCount }
        }

    /**
     * GET /api/v1/admin/users/{id}/reports, mapped into the same entity the
     * local charts already render so StatsScreen needs no second code path.
     *
     * The backend rows have no local row id, so ids are synthesised from the
     * index. They are display-only here - nothing navigates to a report detail
     * from the therapist view.
     */
    suspend fun patientReports(
        accessToken: String,
        userId: String,
    ): Result<List<PanicReportEntity>> {
        val encoded = URLEncoder.encode(userId, "UTF-8")
        return get("/api/v1/admin/users/$encoded/reports", accessToken) { body ->
            val arr = JSONObject(body).optJSONArray("reports")
            buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    add(toEntity(arr!!.getJSONObject(i), syntheticId = i.toLong()))
                }
            }
        }
    }

    private fun toEntity(o: JSONObject, syntheticId: Long): PanicReportEntity {
        val symptoms = o.optJSONArray("symptoms")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        }.orEmpty()

        return PanicReportEntity(
            id = syntheticId,
            userId = o.optString("user_id"),
            timestampMs = parseIsoToMillis(o.optString("timestamp")),
            severity = o.optInt("severity"),
            detectedByModel = o.optBoolean("detected_by_model"),
            feeling = o.optStringOrNull("feeling"),
            symptoms = symptoms,
            activityBefore = o.optStringOrNull("activity_before"),
            whatHelped = o.optStringOrNull("what_helped"),
            durationMinutes = if (o.has("duration_minutes") && !o.isNull("duration_minutes")) {
                o.optInt("duration_minutes")
            } else null,
            latitude = o.optDoubleOrNull("latitude"),
            longitude = o.optDoubleOrNull("longitude"),
            locationAccuracyM = o.optDoubleOrNull("location_accuracy_m")?.toFloat(),
            // Backend stores HR as a float; the entity keeps it as Int BPM.
            currentHr = o.optDoubleOrNull("current_hr")?.roundToInt(),
            currentHrv = o.optDoubleOrNull("current_hrv"),
            currentMotionIntensity = o.optDoubleOrNull("current_motion_intensity")?.toFloat(),
            // during_sleep is not part of the backend PanicReport schema yet,
            // so therapist-side rows show it as unknown rather than false.
            duringSleep = if (o.has("during_sleep") && !o.isNull("during_sleep")) {
                o.optBoolean("during_sleep")
            } else null,
            syncedToBackend = true,
        )
    }

    /** Backend timestamps are ISO 8601; fall back to 0 so a bad row still renders. */
    private fun parseIsoToMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Postgres often hands back "…+00:00" without the trailing Z.
            try {
                Instant.parse(raw.replace(" ", "T").removeSuffix("+00:00") + "Z").toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private suspend fun <T> get(
        path: String,
        accessToken: String,
        parse: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext Result.Err("Not signed in")
        }
        val conn = (URL("$BACKEND_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.Err(
                    when (code) {
                        401 -> "Session expired - sign in again"
                        403 -> "This account is not allowed to view patient data"
                        else -> "Server error (HTTP $code)"
                    },
                    code,
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Result.Ok(parse(body))
        } catch (t: Throwable) {
            Result.Err(t.message ?: "Could not reach the backend")
        } finally {
            conn.disconnect()
        }
    }
}
