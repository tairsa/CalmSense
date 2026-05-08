package com.example.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectVitalsRepository(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
    }

    fun requiredPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun readVitals(): Vitals {
        val hc = client ?: return Vitals(null, null, false)

        val now = Instant.now()
        val hrWindow = TimeRangeFilter.between(now.minus(5, ChronoUnit.MINUTES), now)
        val stepsWindow = TimeRangeFilter.between(now.minus(60, ChronoUnit.SECONDS), now)

        val hr: Int? = runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, hrWindow))
                .records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
        }.getOrNull()

        val hrv: Double? = runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, hrWindow))
                .records.lastOrNull()?.heartRateVariabilityMillis
        }.getOrNull()

        val moving: Boolean = runCatching {
            hc.readRecords(ReadRecordsRequest(StepsRecord::class, stepsWindow))
                .records.sumOf { it.count } > 0
        }.getOrDefault(false)

        return Vitals(hr, hrv, moving)
    }
}
