package com.example.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private const val KEY_EXPIRES_AT = "expires_at"

    /** Set once the pre-auth rows on this device have been handed to an owner. */
    private const val KEY_LEGACY_CLAIMED = "legacy_rows_claimed"

    /** Email to prefill on the sign-in form. Deliberately OUTLIVES sign-out -
     *  remembering it past a sign-out is the entire point of "Remember me" -
     *  so it is not touched by [clear]. Only ever an address, never a password. */
    private const val KEY_REMEMBERED_EMAIL = "remembered_email"

    /**
     * Application context, captured by [init]. Safe to hold: it is the
     * application instance, not an Activity, so there is nothing to leak.
     *
     * It exists so [validAccessToken] can be called from the HTTP layer, which
     * builds connections in places that have no Context to hand.
     */
    private var appContext: Context? = null

    private val _session = MutableStateFlow<SupabaseAuth.Session?>(null)
    val session: StateFlow<SupabaseAuth.Session?> = _session.asStateFlow()

    /** Convenience shortcut used all over the app in place of the old USER_ID. */
    val userId: String?
        get() = _session.value?.userId

    fun isLoggedIn(): Boolean = _session.value != null

    /** The email to prefill on the login form, or null when the user opted out. */
    fun rememberedEmail(context: Context): String? =
        prefs(context).getString(KEY_REMEMBERED_EMAIL, null)?.takeIf { it.isNotBlank() }

    /** Remember [email] for next time, or forget it when null. */
    fun setRememberedEmail(context: Context, email: String?) {
        val e = email?.trim()?.takeIf { it.isNotBlank() }
        prefs(context).edit().apply {
            if (e == null) remove(KEY_REMEMBERED_EMAIL) else putString(KEY_REMEMBERED_EMAIL, e)
        }.apply()
    }

    /** Load any persisted session into memory. Safe to call multiple times. */
    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs(context)
        val id = prefs.getString(KEY_USER_ID, null)
        val email = prefs.getString(KEY_EMAIL, null)
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
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
                expiresAtMs = expiresAt,
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
            .putLong(KEY_EXPIRES_AT, session.expiresAtMs)
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
            .remove(KEY_EXPIRES_AT)
            // Left behind by a removed role implementation; clear it so the
            // prefs file does not keep a stale value forever.
            .remove("role")
            .apply()
        _session.value = null
    }

    /**
     * Serialises refreshes. Without it, several coroutines waking together -
     * MonitorService posting, the dashboard polling, the upload queue draining -
     * would each spend the same single-use refresh token, and all but one would
     * be rejected.
     */
    private val refreshMutex = Mutex()

    /**
     * The access token to put on a request, refreshed first if it is close to
     * expiry. Call this at SEND time, never at enqueue time: UploadQueue can
     * replay a payload days after it was created, and a token captured then
     * would be long dead.
     *
     * Returns null only when there is no session at all, or when the refresh
     * token was definitively rejected (in which case the session is cleared and
     * the user is asked to sign in again).
     *
     * Deliberately tolerant of being offline: a network failure returns the
     * existing token rather than signing anyone out. The request it is attached
     * to will fail on its own and be retried, which is recoverable; wrongly
     * destroying a session because the wifi dropped is not.
     */
    /**
     * As [validAccessToken], for callers with no Context. Returns null if
     * [init] has not run yet, which cannot happen after Activity onCreate.
     */
    suspend fun validAccessToken(): String? {
        val ctx = appContext ?: return null
        return validAccessToken(ctx)
    }

    suspend fun validAccessToken(context: Context): String? {
        val current = _session.value ?: return null
        if (!current.needsRefresh()) return current.accessToken.takeIf { it.isNotBlank() }

        return refreshMutex.withLock {
            // Re-read inside the lock: another caller may have refreshed while
            // we waited, in which case there is nothing left to do.
            val latest = _session.value ?: return@withLock null
            if (!latest.needsRefresh()) {
                return@withLock latest.accessToken.takeIf { it.isNotBlank() }
            }
            when (val result = SupabaseAuth.refresh(latest.refreshToken)) {
                is SupabaseAuth.AuthResult.Success -> {
                    // Supabase rotates the refresh token, so persist the whole
                    // session, not just the access token.
                    save(context, result.session)
                    result.session.accessToken.takeIf { it.isNotBlank() }
                }
                is SupabaseAuth.AuthResult.Error -> {
                    val rejected = result.httpCode != null && result.httpCode in 400..499
                    if (rejected) {
                        // The refresh token is spent or revoked; nothing but a
                        // fresh sign-in will fix it.
                        clear(context)
                        null
                    } else {
                        // Offline or a server blip. Keep the session and let the
                        // caller try the old token.
                        latest.accessToken.takeIf { it.isNotBlank() }
                    }
                }
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
