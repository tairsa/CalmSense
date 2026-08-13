package com.example.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
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
        // Wide window so we catch long-running HR sessions whose record startTime is older.
        // We then gate on per-sample timestamp for freshness.
        val wideWindow = TimeRangeFilter.between(now.minus(24, ChronoUnit.HOURS), now)
        val stepsWindow = TimeRangeFilter.between(now.minus(60, ChronoUnit.SECONDS), now)

        val latestHrSample = runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateRecord::class, wideWindow))
                .records.flatMap { it.samples }.maxByOrNull { it.time }
        }.getOrNull()
        val hrAgeMin = latestHrSample?.let { Duration.between(it.time, now).toMinutes() }
        val hr: Int? = if (latestHrSample != null && hrAgeMin != null && hrAgeMin < 30L) {
            latestHrSample.beatsPerMinute.toInt()
        } else null

        val latestHrv = runCatching {
            hc.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, wideWindow))
                .records.maxByOrNull { it.time }
        }.getOrNull()
        val hrv: Double? = latestHrv?.takeIf {
            Duration.between(it.time, now).toMinutes() < 60L
        }?.heartRateVariabilityMillis

        val moving: Boolean = runCatching {
            hc.readRecords(ReadRecordsRequest(StepsRecord::class, stepsWindow))
                .records.sumOf { it.count } > 0
        }.getOrDefault(false)

        return Vitals(
            heartRateBpm = hr,
            hrv = hrv,
            // Health Connect's RMSSD record is real beat-derived HRV.
            hrvSource = if (hrv != null) HrvSource.REAL_IBI else HrvSource.NONE,
            isMoving = moving,
            hrSampleAgeMinutes = hrAgeMin,
        )
    }
}
