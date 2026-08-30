package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Supabase Auth client, plain HttpURLConnection style to match the
 * rest of the app's HTTP layer (BackendApi, BackendClient) - zero new deps.
 *
 * Talks to Supabase's built-in Auth REST endpoints:
 *   POST /auth/v1/signup                             -> create user + session
 *   POST /auth/v1/token?grant_type=password          -> sign in existing user
 *   POST /auth/v1/logout                             -> invalidate session
 *
 * The "anon" (public) key is required as the `apikey` header on every call.
 * That key is safe to ship in the app - the security layer is Supabase's
 * Row Level Security, not key secrecy.
 */
object SupabaseAuth {

    // Supabase project URL. This matches the one in calmsense-backend/.env.
    private const val PROJECT_URL = "https://loatywpdqvbkaqxixsta.supabase.co"

    // Anon (public) key from Supabase -> Project Settings -> API. Safe to
    // embed in the app; anon key + Row Level Security is the intended
    // Supabase pattern. Rotate this if it ever leaks in a way you care
    // about; RLS still keeps rows isolated.
    const val ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxvYXR5d3BkcXZia2FxeGl4c3RhIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk2Mjk4NjUsImV4cCI6MjA5NTIwNTg2NX0." +
            "o3aKKEE__OXJKv6XySr6gfYlO8V1-NMzR5P0XnZb3bM"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * Refresh this far before the token actually expires.
     *
     * Two minutes rather than seconds because MonitorService posts from the
     * background on its own schedule: a token that is technically still valid
     * when we check can expire while the request is in flight.
     */
    const val REFRESH_SKEW_MS = 2 * 60 * 1000L

    /** Result of a successful signup, sign-in or refresh. */
    data class Session(
        val userId: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        /**
         * When [accessToken] stops being accepted, as epoch millis. 0 when the
         * server did not say - treated as "unknown", never as "expired", so a
         * missing field can't log anyone out.
         */
        val expiresAtMs: Long = 0L,
    ) {
        /**
         * Effective expiry: the stored value, or the token's own `exp` claim.
         *
         * The fallback matters for sessions saved before this field existed.
         * Those store 0, and treating 0 as "never expires" would mean such a
         * session is never refreshed - the token dies quietly an hour later
         * and every request 401s from then on, with no way back except a
         * manual sign-out. An access token is a JWT and already carries its
         * own expiry, so read it rather than depending on a stored field.
         */
        fun effectiveExpiryMs(): Long =
            if (expiresAtMs > 0L) expiresAtMs else jwtExpiryMs(accessToken)

        /** True when the token is past [skewMs] before its effective expiry. */
        fun needsRefresh(nowMs: Long = System.currentTimeMillis(),
                         skewMs: Long = REFRESH_SKEW_MS): Boolean {
            val exp = effectiveExpiryMs()
            return exp > 0L && nowMs >= exp - skewMs
        }
    }

    /** Wrapper so callers can pattern-match instead of catching exceptions. */
    sealed interface AuthResult {
        data class Success(val session: Session) : AuthResult
        data class Error(val message: String, val httpCode: Int? = null) : AuthResult
    }

    /**
     * Create a new account. Returns a session on success. If email
     * confirmation is required in the Supabase dashboard, the returned
     * session's accessToken may be empty and the user must confirm via
     * email before signing in.
     */
    suspend fun signUp(email: String, password: String): AuthResult =
        postAuth(path = "signup", email = email, password = password)

    /** Sign in with email + password. */
    suspend fun signIn(email: String, password: String): AuthResult =
        postAuth(
            path = "token?grant_type=password",
            email = email,
            password = password,
        )

    /**
     * Exchange a refresh token for a fresh access token.
     *
     * Supabase access tokens last about an hour. Nothing renewed them before,
     * so any long-lived caller - MonitorService in particular - would simply
     * start getting 401s an hour after sign-in and stop syncing, silently.
     *
     * A refresh token is single-use: Supabase returns a new one each time, so
     * the whole session must be persisted, not just the access token.
     */
    suspend fun refresh(refreshToken: String): AuthResult = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) {
            return@withContext AuthResult.Error("no refresh token", 400)
        }
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val url = URL("$PROJECT_URL/auth/v1/token?grant_type=refresh_token")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                return@withContext AuthResult.Error(parseErrorMessage(text) ?: "HTTP $code", code)
            }
            parseSession(text)?.let { AuthResult.Success(it) }
                ?: AuthResult.Error("unexpected refresh response")
        } catch (t: Throwable) {
            // Network failure, not a rejection. Reported with no httpCode so the
            // caller can tell "offline" from "this token is dead".
            AuthResult.Error(t.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Sign the current session out on the server. Best-effort - clearing
     * local session is what actually matters for the app.
     */
    suspend fun signOut(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val url = URL("$PROJECT_URL/auth/v1/logout")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun postAuth(
        path: String,
        email: String,
        password: String,
    ): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
        }.toString()

        val url = URL("$PROJECT_URL/auth/v1/$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = parseErrorMessage(text) ?: "HTTP $code"
                return@withContext AuthResult.Error(msg, code)
            }
            parseSession(text)?.let { AuthResult.Success(it) }
                ?: AuthResult.Error("unexpected response from server")
        } catch (t: Throwable) {
            AuthResult.Error(t.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The `exp` claim of a JWT, in millis, or 0 if it cannot be read.
     *
     * Only the payload is decoded and the signature is ignored - deliberately.
     * This is used to decide when to refresh our own token, not to trust
     * anything: the server verifies the signature on every request. A tampered
     * token would simply be rejected there.
     */
    fun jwtExpiryMs(token: String): Long = try {
        val payload = token.split(".").getOrNull(1)
        if (payload.isNullOrBlank()) {
            0L
        } else {
            val json = JSONObject(
                String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            )
            json.optLong("exp", 0L) * 1000L
        }
    } catch (_: Throwable) {
        0L
    }

    private fun parseSession(body: String): Session? = try {
        val json = JSONObject(body)
        // Signup responses put user at top level; sign-in wraps in "user".
        val user = json.optJSONObject("user") ?: json
        val id = user.optString("id", "")
        val email = user.optString("email", "")
        val accessToken = json.optString("access_token", "")
        val refreshToken = json.optString("refresh_token", "")
        // Supabase sends expires_at (epoch seconds) and/or expires_in
        // (seconds from now). Prefer the absolute value; fall back to the
        // relative one; 0 means "unknown", which never counts as expired.
        val expiresAtMs = when {
            json.has("expires_at") -> json.optLong("expires_at") * 1000L
            json.has("expires_in") -> System.currentTimeMillis() + json.optLong("expires_in") * 1000L
            else -> 0L
        }
        if (id.isBlank()) null
        else Session(id, email, accessToken, refreshToken, expiresAtMs)
    } catch (_: Throwable) {
        null
    }

    private fun parseErrorMessage(body: String): String? = try {
        val json = JSONObject(body)
        json.optString("error_description")
            .takeIf { it.isNotBlank() }
            ?: json.optString("msg").takeIf { it.isNotBlank() }
            ?: json.optString("message").takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
