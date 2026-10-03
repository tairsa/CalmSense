package com.example.app.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The watch's half of the watch → phone contract. The same golden strings are
 *  parsed in the phone's ContractTest; change one side, change both. */
class WireFormatTest {

    @Test
    fun `formats the golden samples`() {
        assertEquals("78,0.412,42.3,1,2", HrMonitoringService.formatSample(78, 0.412f, 42.3f, true, 2))
        assertEquals("-1,0.000,-1.0,0,0", HrMonitoringService.formatSample(-1, 0f, null, false, 0))
    }

    @Test
    fun `decimal separator is a dot in every locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // uses ',' for decimals
            assertEquals("78,0.412,42.3,1,1", HrMonitoringService.formatSample(78, 0.412f, 42.3f, true, 1))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
