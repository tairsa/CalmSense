package com.example.app.data

/**
 * One journaled panic-attack entry.
 *
 * A row is created the moment severity is submitted. Free-text and symptom
 * fields are filled in by the post-event questionnaire; the user can skip
 * the questionnaire, in which case those fields remain null/empty.
 *
 * Persisted as JSON via [PanicReportStore]; no Room/SQLite — data volume is
 * a handful of entries per week at most.
 */
data class PanicReportEntity(
    val id: Long = 0L,

    /**
     * Supabase auth user id (UUID) of the account that logged this report.
     * Empty string on legacy rows created before per-user isolation shipped;
     * those rows are hidden from all authenticated users (see repository
     * observe* methods) and effectively become dead data. New inserts are
     * always tagged in [PanicReportRepository.insertAndSync].
     */
    val userId: String = "",

    /** Event start, ms since epoch. */
    val timestampMs: Long,

    /** User-reported severity, 1–10. */
    val severity: Int,

    /** True = model fired this; false = manually logged by the user. */
    val detectedByModel: Boolean,

    // -- Questionnaire (all optional) -----------------------------------
    val feeling: String? = null,
    val symptoms: List<String> = emptyList(),
    val activityBefore: String? = null,
    val whatHelped: String? = null,
    val durationMinutes: Int? = null,

    // -- Captured at detection ------------------------------------------
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,

    // -- Vitals snapshot for therapist context --------------------------
    val currentHr: Int? = null,
    val currentHrv: Double? = null,
    val currentMotionIntensity: Float? = null,

    /** False until the row has been mirrored to the backend. */
    val syncedToBackend: Boolean = false,
)
