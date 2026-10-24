package com.example.app

import com.example.app.data.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater decides "newer" by version code, so its formula must be the build's. */
class AppUpdaterTest {

    @Test
    fun `version code matches the one the build computed`() {
        assertEquals(BuildConfig.VERSION_CODE, AppUpdater.versionCode(BuildConfig.VERSION_NAME))
    }

    @Test
    fun `release tags parse and order numerically`() {
        assertEquals(10300, AppUpdater.versionCode("v1.3.0"))
        assertTrue(AppUpdater.versionCode("1.10.0")!! > AppUpdater.versionCode("1.9.9")!!)
        assertNull(AppUpdater.versionCode("1.3"))
        assertNull(AppUpdater.versionCode("1.3.0-beta"))
    }

    @Test
    fun `checksum is read from sha256sum output for the right file only`() {
        val sums = "ABC123  calmsense-phone-1.5.0.apk\n" +
            "def456 *calmsense-watch-1.5.0.apk\n"
        assertEquals("abc123", AppUpdater.expectedSha256(sums, "calmsense-phone-1.5.0.apk"))
        assertEquals("def456", AppUpdater.expectedSha256(sums, "calmsense-watch-1.5.0.apk"))
        assertNull(AppUpdater.expectedSha256(sums, "calmsense-phone-1.5.1.apk"))
        assertNull(AppUpdater.expectedSha256("", "calmsense-phone-1.5.0.apk"))
    }
}
