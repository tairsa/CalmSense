package com.example.app

import com.example.app.data.EventReportEntity
import com.example.app.data.EventType
import com.example.app.data.HrvSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReportTest {

    private val flashback = EventReportEntity(
        id = 3, userId = "u1", type = EventType.FLASHBACK, timestampMs = 1_700_000_000_000,
        emotions = listOf("Fear", "Shame"), feeling = "a sound", whatHelped = null, durationMinutes = 7,
        senses = listOf("Sounds"), nowness = "now", triggers = listOf("Sound"), bodyReactions = listOf("Froze"),
        latitude = 32.1, longitude = 34.8, locationAccuracyM = 12f,
        currentHr = 104, currentHrv = 21.5, currentMotionIntensity = 0.2f, hrvSource = HrvSource.REAL_IBI,
        duringSleep = false, draft = true, syncedToBackend = false,
    )

    @Test
    fun diskJsonRoundTripKeepsEveryField() {
        assertEquals(flashback, EventReportEntity.fromDiskJson(flashback.toDiskJson()))
        val dis = EventReportEntity(type = EventType.DISSOCIATION, timestampMs = 1L, experiences = listOf("Mind went blank"), memoryClarity = "partial")
        assertEquals(dis, EventReportEntity.fromDiskJson(dis.toDiskJson()))
    }

    @Test
    fun apiJsonHasOnlyServerFields() {
        val api = flashback.toApiJson()
        // Identity comes from the token; bookkeeping stays on the phone.
        for (k in listOf("user_id", "id", "draft", "synced_to_backend", "type", "timestamp_ms")) assertFalse(k, api.has(k))
        assertEquals("now", api.getString("nowness"))
        assertEquals("real_ibi", api.getString("hrv_source"))
        assertTrue(api.getString("timestamp").startsWith("2023-11-14T"))
        // Dissociation fields are not sent on a flashback, and vice versa.
        assertFalse(api.has("experiences"))
        assertFalse(EventReportEntity(type = EventType.DISSOCIATION, timestampMs = 1L).toApiJson().has("senses"))
    }
}
