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
}
