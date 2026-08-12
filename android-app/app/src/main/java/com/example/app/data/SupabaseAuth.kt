package com.example.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Result of a successful signup or sign-in. */
    data class Session(
        val userId: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        /** From `user_metadata.role`; [UserRole.USER] when absent or unknown. */
        val role: UserRole = UserRole.USER,
    )

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

    private fun parseSession(body: String): Session? = try {
        val json = JSONObject(body)
        // Signup responses put user at top level; sign-in wraps in "user".
        val user = json.optJSONObject("user") ?: json
        val id = user.optString("id", "")
        val email = user.optString("email", "")
        val accessToken = json.optString("access_token", "")
        val refreshToken = json.optString("refresh_token", "")
        // Role lives in user_metadata, set from the Supabase dashboard. Absent
        // for every normal signup, which is exactly the USER default.
        val role = UserRole.fromWire(
            user.optJSONObject("user_metadata")?.optString("role")
        )
        if (id.isBlank()) null else Session(id, email, accessToken, refreshToken, role)
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
