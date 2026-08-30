package com.example.app

import com.example.app.data.SupabaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the expiry/refresh decision logic.
 *
 * Why this is worth testing: every failure here is silent. A token that is
 * never refreshed does not crash - MonitorService just stops syncing an hour
 * after sign-in and nobody notices until data is missing. And a session that
 * is wrongly cleared logs the user out for no reason they can see.
 *
 * SessionManager itself needs a Context and SharedPreferences, so these tests
 * target Session.needsRefresh (the decision) plus a faithful re-implementation
 * of validAccessToken's branching, which is the part with the interesting
 * behaviour.
 */
class SessionRefreshTest {

    private val hour = 60 * 60 * 1000L

    private fun session(expiresAtMs: Long, access: String = "tok", refresh: String = "rt") =
        SupabaseAuth.Session(
            userId = "u", email = "e@x.com",
            accessToken = access, refreshToken = refresh,
            expiresAtMs = expiresAtMs,
        )

    // ---- needsRefresh -----------------------------------------------------

    @Test
    fun `fresh token is not refreshed`() {
        val now = 1_000_000L
        assertFalse(session(now + hour).needsRefresh(now))
    }

    @Test
    fun `expired token is refreshed`() {
        val now = 1_000_000L
        assertTrue(session(now - 1).needsRefresh(now))
    }

    @Test
    fun `token inside the skew window is refreshed before it expires`() {
        val now = 1_000_000L
        // 60s left, skew is 120s: refresh now rather than mid-request.
        assertTrue(session(now + 60_000).needsRefresh(now))
    }

    @Test
    fun `token just outside the skew window is left alone`() {
        val now = 1_000_000L
        assertFalse(session(now + SupabaseAuth.REFRESH_SKEW_MS + 1_000).needsRefresh(now))
    }

    @Test
    fun `unknown expiry never counts as expired`() {
        // 0 means the server did not tell us. Treating that as expired would
        // refresh on every single request.
        assertFalse(session(0L).needsRefresh(1_000_000L))
    }

    // ---- validAccessToken's branching -------------------------------------

    /** Mirrors SessionManager.validAccessToken; `refresh` stands in for the network. */
    private fun resolve(
        current: SupabaseAuth.Session?,
        now: Long,
        refresh: (String) -> SupabaseAuth.AuthResult,
        onSave: (SupabaseAuth.Session) -> Unit = {},
        onClear: () -> Unit = {},
    ): String? {
        if (current == null) return null
        if (!current.needsRefresh(now)) return current.accessToken.takeIf { it.isNotBlank() }
        return when (val r = refresh(current.refreshToken)) {
            is SupabaseAuth.AuthResult.Success -> {
                onSave(r.session)
                r.session.accessToken.takeIf { it.isNotBlank() }
            }
            is SupabaseAuth.AuthResult.Error -> {
                if (r.httpCode != null && r.httpCode in 400..499) {
                    onClear(); null
                } else {
                    current.accessToken.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    @Test
    fun `no session yields no token`() {
        assertEquals(null, resolve(null, 0L, { error("must not refresh") }))
    }

    @Test
    fun `valid token is returned without touching the network`() {
        var called = false
        val now = 1_000_000L
        val out = resolve(session(now + hour, access = "good"), now,
            { called = true; SupabaseAuth.AuthResult.Error("nope") })
        assertEquals("good", out)
        assertFalse("must not refresh a valid token", called)
    }

    @Test
    fun `expiring token is refreshed and the new session persisted`() {
        val now = 1_000_000L
        var saved: SupabaseAuth.Session? = null
        val out = resolve(session(now + 10_000, access = "old", refresh = "rt1"), now,
            refresh = { rt ->
                assertEquals("rt1", rt)
                // Supabase rotates the refresh token; the whole session must be kept.
                SupabaseAuth.AuthResult.Success(
                    session(now + hour, access = "new", refresh = "rt2")
                )
            },
            onSave = { saved = it })
        assertEquals("new", out)
        assertEquals("rt2", saved?.refreshToken)
        assertEquals("the rotated refresh token must be persisted", "rt2", saved?.refreshToken)
    }

    @Test
    fun `network failure keeps the session and reuses the old token`() {
        val now = 1_000_000L
        var cleared = false
        // No httpCode = transport failure, not a rejection.
        val out = resolve(session(now + 10_000, access = "old"), now,
            refresh = { SupabaseAuth.AuthResult.Error("network error") },
            onClear = { cleared = true })
        assertEquals("offline must not lose the token", "old", out)
        assertFalse("offline must never sign the user out", cleared)
    }

    @Test
    fun `rejected refresh token signs the user out`() {
        val now = 1_000_000L
        var cleared = false
        val out = resolve(session(now + 10_000), now,
            refresh = { SupabaseAuth.AuthResult.Error("invalid grant", 400) },
            onClear = { cleared = true })
        assertEquals(null, out)
        assertTrue("a spent refresh token must clear the session", cleared)
    }

    @Test
    fun `server error does not sign the user out`() {
        val now = 1_000_000L
        var cleared = false
        // 5xx is Supabase having a bad day, not a verdict on this token.
        val out = resolve(session(now + 10_000, access = "old"), now,
            refresh = { SupabaseAuth.AuthResult.Error("bad gateway", 502) },
            onClear = { cleared = true })
        assertEquals("old", out)
        assertFalse(cleared)
    }

    @Test
    fun `blank access token yields null rather than an empty header`() {
        val now = 1_000_000L
        assertEquals(null, resolve(session(now + hour, access = ""), now,
            { error("must not refresh") }))
    }
}
