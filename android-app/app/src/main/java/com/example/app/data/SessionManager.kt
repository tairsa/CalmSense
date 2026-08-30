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

    /** Set once the pre-auth rows on this device have been handed to an owner. */
    private const val KEY_LEGACY_CLAIMED = "legacy_rows_claimed"

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
        // A persisted session with no access token cannot authenticate against
        // anything, so treat it as signed out rather than letting the user in
        // to a session that silently fails. This happens when a signup needed
        // email confirmation; it self-heals devices that stored one before
        // LoginScreen started rejecting them.
        _session.value = if (id.isNullOrBlank() || access.isNullOrBlank()) {
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

    /**
     * Hand any pre-auth reports on this device to [session] - once, ever.
     *
     * Runs on the first successful sign-in after upgrading to the multi-user
     * build: those rows have no owner and would otherwise be permanently
     * invisible (see [PanicReportStore.claimUntaggedRows]). Subsequent logins,
     * including a different account, find the flag already set and claim
     * nothing, so a second person signing in never inherits the first one's
     * history.
     *
     * Returns how many rows were claimed, for logging.
     */
    suspend fun claimLegacyRowsOnce(context: Context, session: SupabaseAuth.Session): Int {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_LEGACY_CLAIMED, false)) return 0
        val claimed = PanicReportStore.get(context).claimUntaggedRows(session.userId)
        // Set the flag even when nothing was claimed: a fresh install has no
        // legacy rows, and we do not want a later account to pick up rows the
        // first user creates after signing out.
        prefs.edit().putBoolean(KEY_LEGACY_CLAIMED, true).apply()
        return claimed
    }

    /**
     * Local logout - wipe persisted session and notify observers.
     *
     * Deliberately removes the session keys one by one instead of `clear()`:
     * the legacy-claim flag must survive a sign-out, otherwise the next
     * account to sign in on this device would re-run the migration and
     * inherit the previous user's panic history.
     */
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
        _session.value = null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
