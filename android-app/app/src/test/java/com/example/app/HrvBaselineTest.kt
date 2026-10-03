package com.example.app

import com.example.app.data.HrvSource
import com.example.app.data.LogBaseline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class HrvBaselineTest {

    private fun LogBaseline.feed(hrv: Double, seconds: Int, stepSec: Int, startMs: Long): Long {
        var t = startMs
        repeat(seconds / stepSec) { t += stepSec * 1000L; update(hrv, t) }
        return t
    }

    @Test
    fun `starts at the source's prior`() {
        assertEquals(0.0, LogBaseline.prior(HrvSource.REAL_IBI).relative(52.5), 1e-9)
        assertEquals(0.0, LogBaseline.prior(HrvSource.BPM_DERIVED).relative(11.4), 1e-9)
    }

    @Test
    fun `moves to the user's own normal within hours`() {
        val b = LogBaseline.prior(HrvSource.REAL_IBI)  // 52.5 ms prior
        b.feed(30.0, 4 * 3600, 1, 1_000L)
        assertEquals(30.0, b.baselineMs, 30.0 * 0.05)
    }

    @Test
    fun `cadence does not change the weight of a minute`() {
        val fast = LogBaseline.prior(HrvSource.REAL_IBI).apply { feed(30.0, 1800, 1, 1_000L) }
        val slow = LogBaseline.prior(HrvSource.REAL_IBI).apply { feed(30.0, 1800, 5, 1_000L) }
        assertEquals(fast.baselineMs, slow.baselineMs, 0.5)
    }

    @Test
    fun `a panic episode barely moves an established baseline`() {
        val b = LogBaseline.prior(HrvSource.REAL_IBI)
        val t = b.feed(50.0, 4 * 24 * 3600, 10, 1_000L)
        val before = b.baselineMs
        b.feed(10.0, 15 * 60, 1, t)  // 15 minutes at a fifth of normal
        assertTrue(abs(b.baselineMs - before) / before < 0.01)
    }

    @Test
    fun `a sample after a long gap carries capped weight`() {
        val b = LogBaseline.prior(HrvSource.REAL_IBI)
        b.update(52.5, 1_000L)
        b.update(5.0, 1_000L + 24 * 3600 * 1000L)  // a day later
        assertTrue(b.baselineMs > 50.0)
    }

    @Test
    fun `relative is the clamped log ratio`() {
        val b = LogBaseline(ln(40.0), 1.0)
        assertEquals(ln(0.5), b.relative(20.0), 1e-9)
        assertEquals(-3.0, b.relative(0.01), 0.0)
        assertEquals(1.0, b.relative(1000.0), 0.0)
        assertEquals(0.0, b.relative(0.0), 0.0)
    }
}
