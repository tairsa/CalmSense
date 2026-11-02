package com.example.app.data

/** Traffic-light rules for Settings → System status. Pure, so they are unit-tested. */
enum class StatusLevel { OK, WARN, BAD }

object SystemStatus {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** null age = never happened. */
    fun byAge(ageMs: Long?, okUnderMs: Long, warnUnderMs: Long): StatusLevel = when {
        ageMs == null -> StatusLevel.BAD
        ageMs < okUnderMs -> StatusLevel.OK
        ageMs < warnUnderMs -> StatusLevel.WARN
        else -> StatusLevel.BAD
    }

    // Same 2-minute freshness the detector uses (WatchVitalsRepository.readVitals).
    fun watch(ageMs: Long?, onBody: Boolean): StatusLevel =
        if (ageMs != null && ageMs < 2 * MIN && !onBody) StatusLevel.WARN
        else byAge(ageMs, 2 * MIN, 10 * MIN)

    fun lastUpload(ageMs: Long?) = byAge(ageMs, 10 * MIN, DAY)

    // A few rows queue during any short gap; hundreds mean a long outage (cap is 1000).
    fun queue(pending: Int): StatusLevel = when {
        pending == 0 -> StatusLevel.OK
        pending <= 20 -> StatusLevel.WARN
        else -> StatusLevel.BAD
    }

    // The app checks once a day (UpdateNotifier), so up to 2 days is normal.
    fun updateCheck(ageMs: Long?) = byAge(ageMs, 2 * DAY, 7 * DAY)

    fun backend(r: PingResult?): StatusLevel = when (r) {
        null -> StatusLevel.WARN // still checking
        PingResult.Connected -> StatusLevel.OK
        else -> StatusLevel.BAD
    }
}
