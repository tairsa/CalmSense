package com.example.app

import com.example.app.data.PingResult
import com.example.app.data.StatusLevel.BAD
import com.example.app.data.StatusLevel.OK
import com.example.app.data.StatusLevel.WARN
import com.example.app.data.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemStatusTest {
    private val min = 60_000L

    @Test fun watch() {
        assertEquals(OK, SystemStatus.watch(30_000, onBody = true))
        assertEquals(WARN, SystemStatus.watch(30_000, onBody = false))
        assertEquals(WARN, SystemStatus.watch(5 * min, onBody = true))
        assertEquals(BAD, SystemStatus.watch(10 * min, onBody = true))
        assertEquals(BAD, SystemStatus.watch(null, onBody = true))
    }

    @Test fun queue() {
        assertEquals(OK, SystemStatus.queue(0))
        assertEquals(WARN, SystemStatus.queue(20))
        assertEquals(BAD, SystemStatus.queue(21))
    }

    @Test fun ages() {
        assertEquals(OK, SystemStatus.lastUpload(9 * min))
        assertEquals(WARN, SystemStatus.lastUpload(60 * min))
        assertEquals(BAD, SystemStatus.lastUpload(null))
        assertEquals(OK, SystemStatus.updateCheck(24 * 60 * min))
        assertEquals(BAD, SystemStatus.updateCheck(8 * 24 * 60 * min))
    }

    @Test fun backend() {
        assertEquals(WARN, SystemStatus.backend(null))
        assertEquals(OK, SystemStatus.backend(PingResult.Connected))
        assertEquals(BAD, SystemStatus.backend(PingResult.HttpError(503)))
        assertEquals(BAD, SystemStatus.backend(PingResult.NetworkError("x")))
    }
}
