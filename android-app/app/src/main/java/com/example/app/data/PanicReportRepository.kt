package com.example.app.data

import android.content.Context
import android.util.Log
import com.example.app.BACKEND_URL
import com.example.app.USER_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Stitches together the local [PanicReportStore] and the backend mirror.
 *
 * Local write succeeds whether or not the backend is reachable. Unsynced
 * rows are retried opportunistically via [syncPending] so a flaky network
 * doesn't lose the user's report.
 */
class PanicReportRepository private constructor(
    private val store: PanicReportStore,
    private val backend: BackendClient,
) {

    /** Reports newer than [sinceMs], sorted most-recent first. */
    fun observeSince(sinceMs: Long): Flow<List<PanicReportEntity>> =
        store.rows.map { rows -> rows.filter { it.timestampMs >= sinceMs }.sortedByDescending { it.timestampMs } }

    fun observeAll(): Flow<List<PanicReportEntity>> =
        store.rows.map { rows -> rows.sortedByDescending { it.timestampMs } }

    suspend fun findById(id: Long): PanicReportEntity? = store.findById(id)

    /** Returns the inserted row's id (after the store assigns one). */
    suspend fun insertAndSync(report: PanicReportEntity): Long {
        val stored = store.insert(report)
        if (postToBackend(stored)) {
            store.update(stored.copy(syncedToBackend = true))
        }
        return stored.id
    }

    suspend fun updateAndSync(report: PanicReportEntity) {
        store.update(report.copy(syncedToBackend = false))
        if (postToBackend(report)) {
            store.update(report.copy(syncedToBackend = true))
        }
    }

    suspend fun syncPending() {
        for (row in store.unsynced()) {
            if (postToBackend(row)) {
                store.update(row.copy(syncedToBackend = true))
            }
        }
    }

    /** Local-only delete. The mirrored row stays on the backend so the
     *  training dataset isn't disturbed by occasional UI cleanup. */
    suspend fun delete(id: Long) = store.delete(id)

    private suspend fun postToBackend(row: PanicReportEntity): Boolean {
        val payload = PanicReportPayload(
            userId = USER_ID,
            timestamp = Instant.ofEpochMilli(row.timestampMs).toString(),
            severity = row.severity,
            detectedByModel = row.detectedByModel,
            feeling = row.feeling,
            symptoms = row.symptoms,
            activityBefore = row.activityBefore,
            whatHelped = row.whatHelped,
            durationMinutes = row.durationMinutes,
            latitude = row.latitude,
            longitude = row.longitude,
            locationAccuracyM = row.locationAccuracyM,
            currentHr = row.currentHr?.toFloat(),
            currentHrv = row.currentHrv,
            currentMotionIntensity = row.currentMotionIntensity,
        )
        return when (val r = backend.submitPanicReport(payload)) {
            PostResult.Success -> true
            is PostResult.HttpError -> {
                Log.w(TAG, "report sync HTTP ${r.code}")
                false
            }
            is PostResult.NetworkError -> {
                Log.w(TAG, "report sync offline (${r.reason})")
                false
            }
        }
    }

    companion object {
        private const val TAG = "PanicReportRepo"

        @Volatile private var INSTANCE: PanicReportRepository? = null

        fun get(context: Context): PanicReportRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PanicReportRepository(
                    store = PanicReportStore.get(context),
                    backend = BackendClient(BACKEND_URL),
                ).also { INSTANCE = it }
            }
        }
    }
}
