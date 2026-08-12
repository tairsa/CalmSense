package com.example.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the currently signed-in user's session across app launches.
 *
 * Design: a plain object (singleton) backed by SharedPreferences, with a
 * StateFlow that the UI observes so the login gate re-renders when someone
 * signs in or out. No EncryptedSharedPreferences dependency - the access
 * token is short-lived and Supabase's threat model treats loss of it as a
 * "sign in again" event, not a catastrophic key leak.
 *
 * Call [init] once from Application/Activity onCreate before anyone reads
 * [session] or [userId], so persisted values are hydrated into the flow.
 */
object SessionManager {

    private const val PREFS_NAME = "calmsense_session"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    private val _session = MutableStateFlow<SupabaseAuth.Session?>(null)
    val session: StateFlow<SupabaseAuth.Session?> = _session.asStateFlow()

    /** Convenience shortcut used all over the app in place of the old USER_ID. */
    val userId: String?
        get() = _session.value?.userId

    fun isLoggedIn(): Boolean = _session.value != null

    /** Load any persisted session into memory. Safe to call multiple times. */
    fun init(context: Context) {
        val prefs = prefs(context)
        val id = prefs.getString(KEY_USER_ID, null)
        val email = prefs.getString(KEY_EMAIL, null)
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        _session.value = if (id.isNullOrBlank()) {
            null
        } else {
            SupabaseAuth.Session(
                userId = id,
                email = email.orEmpty(),
                accessToken = access.orEmpty(),
                refreshToken = refresh.orEmpty(),
            )
        }
    }

    /** Write a session to disk + notify observers. */
    fun save(context: Context, session: SupabaseAuth.Session) {
        prefs(context).edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
        _session.value = session
    }

    /** Local logout - wipe persisted session and notify observers. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _session.value = null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
