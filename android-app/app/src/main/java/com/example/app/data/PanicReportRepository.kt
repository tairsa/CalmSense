package com.example.app.data

import android.content.Context
import android.util.Log
import com.example.app.BACKEND_URL
import com.example.app.USER_ID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /**
     * Reports newer than [sinceMs], sorted most-recent first, restricted to
     * the currently signed-in user. Re-emits when either the row set OR the
     * session changes, so signing in as another account instantly swaps the
     * visible history.
     */
    fun observeSince(sinceMs: Long): Flow<List<PanicReportEntity>> =
        combine(store.rows, SessionManager.session) { rows, session ->
            val uid = session?.userId ?: return@combine emptyList()
            rows.asSequence()
                .filter { it.userId == uid && it.timestampMs >= sinceMs }
                .sortedByDescending { it.timestampMs }
                .toList()
        }

    /** All reports for the currently signed-in user, most-recent first. */
    fun observeAll(): Flow<List<PanicReportEntity>> =
        combine(store.rows, SessionManager.session) { rows, session ->
            val uid = session?.userId ?: return@combine emptyList()
            rows.asSequence()
                .filter { it.userId == uid }
                .sortedByDescending { it.timestampMs }
                .toList()
        }

    /**
     * Only returns the row if it belongs to the current user - guards against
     * a stale nav argument pointing at another user's report id.
     */
    suspend fun findById(id: Long): PanicReportEntity? {
        val row = store.findById(id) ?: return null
        val uid = SessionManager.userId ?: return null
        return row.takeIf { it.userId == uid }
    }

    /**
     * Returns the inserted row's id (after the store assigns one). The row
     * is always tagged with the current session's user id here so callers
     * don't need to know about the auth layer.
     */
    suspend fun insertAndSync(report: PanicReportEntity): Long {
        val tagged = report.copy(userId = USER_ID)
        val stored = store.insert(tagged)
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
