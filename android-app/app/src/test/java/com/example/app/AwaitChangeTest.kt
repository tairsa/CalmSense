package com.example.app

import com.example.app.data.awaitChange
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MonitorService's wait: wake on the next watch sample, else on the timeout. */
class AwaitChangeTest {

    @Test
    fun `wakes as soon as a sample arrives`() = runBlocking {
        val count = MutableStateFlow(0L)
        val started = System.nanoTime()
        val woke = async { awaitChange(count, seen = 0L, timeoutMs = 30_000) }
        delay(50)
        count.value = 1
        assertTrue(woke.await())
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
    }

    @Test
    fun `a sample that landed while processing is not missed`() = runBlocking {
        // seen was read before handleVitals; the counter has moved since.
        assertTrue(awaitChange(MutableStateFlow(7L), seen = 6L, timeoutMs = 30_000))
    }

    @Test
    fun `times out when the watch is quiet`() = runBlocking {
        assertFalse(awaitChange(MutableStateFlow(3L), seen = 3L, timeoutMs = 100))
    }
}
