package com.example.app.wear

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Motion = deviation from the learned resting |a|, not from an assumed 9.81. */
class GravityTrackerTest {

    /** RMS of the last 16 deviations, as HrMonitoringService computes it (5 Hz, ~3 s). */
    private fun rms(magnitudes: List<Float>, tracker: GravityTracker = GravityTracker()): Float {
        val dev = magnitudes.map { tracker.deviation(it) }.takeLast(16)
        return sqrt(dev.sumOf { (it * it).toDouble() } / dev.size).toFloat()
    }

    @Test
    fun `a still wrist on an offset sensor reads as still`() {
        // A sensor reading 10.6 at rest, with a little noise: the old code
        // reported ~0.8 m/s² here, permanently "Active".
        val still = List(300) { i -> 10.6f + if (i % 2 == 0) 0.02f else -0.02f }
        assertTrue("still wrist must be under the 0.5 moving line", rms(still) < 0.1f)
    }

    @Test
    fun `walking still reads as motion`() {
        // ~1.8 Hz gait sampled at 5 Hz, ±2 m/s² around the same offset gravity.
        val walking = List(300) { i -> 10.6f + 2f * sin(2 * PI * 1.8 * i / 5.0).toFloat() }
        assertTrue("walking must stay over the 0.5 moving line", rms(walking) > 0.5f)
    }

    @Test
    fun `motion right after start is not swallowed`() {
        val tracker = GravityTracker()
        List(50) { 9.9f }.forEach { tracker.deviation(it) }   // 10 s settled at rest
        val shake = List(16) { i -> if (i % 2 == 0) 12.5f else 7.5f }
        assertTrue(rms(shake, tracker) > 0.5f)
    }
}
