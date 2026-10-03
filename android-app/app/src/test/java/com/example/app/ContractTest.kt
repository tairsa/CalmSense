package com.example.app

import com.example.app.data.HrvSource
import com.example.app.data.PanicDebouncer
import com.example.app.data.PanicModel
import com.example.app.data.PostResult
import com.example.app.data.UploadQueue
import com.example.app.data.decidePanic
import com.example.app.data.motionFeatureFor
import com.example.app.data.parseWatchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The seams between tiers, pinned from the phone's side:
 *  - watch → phone: the golden strings below are also asserted, byte for byte,
 *    against the watch's formatter in wear/src/test (WireFormatTest).
 *  - backend → phone: the shipped baseline weights are read straight from
 *    calmsense-backend/ml/model_weights.json and run through PanicModel.
 */
class ContractTest {

    // ---- watch → phone --------------------------------------------------

    @Test
    fun `parses a current watch sample`() {
        val s = parseWatchSample("78,0.412,42.3,1,2")
        assertEquals(78, s.bpm)
        assertEquals(0.412f, s.motion!!, 1e-6f)
        assertEquals(42.3f, s.hrvMs!!, 1e-6f)
        assertTrue(s.onBody)
        assertEquals(HrvSource.BPM_DERIVED, s.hrvSource)
        assertEquals(HrvSource.REAL_IBI, parseWatchSample("78,0.412,42.3,1,1").hrvSource)
    }

    @Test
    fun `sentinels mean absent, not zero`() {
        val s = parseWatchSample("-1,0.000,-1.0,0,0")
        assertNull(s.bpm)
        assertNull(s.hrvMs)
        assertFalse(s.onBody)
        assertEquals(HrvSource.NONE, s.hrvSource)
    }

    @Test
    fun `legacy three-field watch builds are understood`() {
        val s = parseWatchSample("78,0.412,42.3")
        assertTrue(s.onBody)
        assertEquals(HrvSource.BPM_DERIVED, s.hrvSource)
        assertEquals(HrvSource.NONE, parseWatchSample("78,0.412,-1.0").hrvSource)
    }

    @Test
    fun `garbage does not throw`() {
        for (junk in listOf("", ",,,,", "abc", "78,x,y,z,w", "1,2,3,4,5,6,7")) parseWatchSample(junk)
        assertNull(parseWatchSample("abc").bpm)
    }

    // ---- backend → phone ------------------------------------------------

    private fun baselineWeights(): DoubleArray {
        // Unit tests run with the module dir (android-app/app) as working dir.
        val json = File("../../calmsense-backend/ml/model_weights.json").readText()
        val arr = Regex("\"weights\"\\s*:\\s*\\[([^]]*)]").find(json)!!.groupValues[1]
        return arr.split(',').map { it.trim().toDouble() }.toDoubleArray()
    }

    @Test
    fun `backend baseline weights classify the canonical profiles on the phone`() {
        val m = PanicModel(baselineWeights(), "baseline", null, null, null)
        // Midpoints of the priors in calmsense-backend/ml/generate_data.py.
        assertFalse("resting", m.predict(70.0, 52.0, 0.05).isPanic)
        assertFalse("stress", m.predict(92.0, 35.0, 0.10).isPanic)
        assertTrue("panic", m.predict(145.0, 15.0, 0.15).isPanic)
        assertFalse("exercise", m.predict(145.0, 25.0, 0.75).isPanic)
    }

    // ---- shared decision ------------------------------------------------

    @Test
    fun `without a trained model the fixed rule applies`() {
        assertTrue(decidePanic(null, 130, 15.0, 0.05, moving = false, threshold = 0.5).isPanic)
        assertFalse(decidePanic(null, 130, 15.0, 0.05, moving = true, threshold = 0.5).isPanic)
        assertFalse(decidePanic(null, 110, 15.0, 0.05, moving = false, threshold = 0.5).isPanic)
        val zero = PanicModel(DoubleArray(5), "default", null, null, null)
        assertEquals(0.0, decidePanic(zero, 130, 15.0, 0.05, false, 0.5).probability, 0.0)
    }

    @Test
    fun `a trained model decides at the user's threshold`() {
        // z = 0 -> p = 0.5 exactly.
        val m = PanicModel(doubleArrayOf(0.0, 0.0, 0.0, 99.0, 0.0), "trained", null, null, null)
        assertEquals(0.5, decidePanic(m, 60, 50.0, 0.0, false, 0.4).probability, 1e-9)
        assertTrue(decidePanic(m, 60, 50.0, 0.0, false, 0.4).isPanic)
        assertFalse(decidePanic(m, 60, 50.0, 0.0, false, 0.6).isPanic)
    }

    @Test
    fun `motion feature passes intensity through clamped`() {
        assertEquals(0.3, motionFeatureFor(0.3f, false), 1e-6)
        assertEquals(1.0, motionFeatureFor(4.2f, false), 0.0)
        assertEquals(0.0, motionFeatureFor(-1f, true), 0.0)
        assertEquals(0.7, motionFeatureFor(null, true), 0.0)
        assertEquals(0.05, motionFeatureFor(null, false), 0.0)
    }

    // ---- debouncer --------------------------------------------------------

    @Test
    fun `a positive must persist before it is confirmed`() {
        val d = PanicDebouncer(sustainMillis = 20_000)
        val t0 = Instant.parse("2026-01-01T00:00:00Z")
        assertFalse(d.confirm(true, t0))
        assertFalse(d.confirm(true, t0.plusSeconds(19)))
        assertTrue(d.confirm(true, t0.plusSeconds(20)))
        // One negative resets the streak.
        assertFalse(d.confirm(false, t0.plusSeconds(21)))
        assertFalse(d.confirm(true, t0.plusSeconds(22)))
        assertTrue(d.confirm(true, t0.plusSeconds(42)))
    }

    // ---- upload retry policy ---------------------------------------------

    @Test
    fun `transient failures keep the row, rejections drop it`() {
        for (code in listOf(408, 429, 500, 502, 503)) assertTrue("$code", UploadQueue.shouldRetry(PostResult.HttpError(code)))
        for (code in listOf(400, 401, 403, 404, 422)) assertFalse("$code", UploadQueue.shouldRetry(PostResult.HttpError(code)))
        assertTrue(UploadQueue.shouldRetry(PostResult.NetworkError("timeout")))
        assertFalse(UploadQueue.shouldRetry(PostResult.Success))
    }
}
