package com.example.app.data

/**
 * Who the signed-in account is, which decides what the app exposes.
 *
 * The role travels in the Supabase auth user's `user_metadata.role` claim and
 * is set per-account from the Supabase dashboard (Auth > Users > User
 * Metadata: `{ "role": "therapist" }`). Anything unrecognised or absent is
 * [USER] - the safe default, since an unknown role must never be granted more
 * than a regular account.
 *
 * Note this is a *UI* gate only. `user_metadata` is user-writable in Supabase,
 * so a determined account holder could promote themselves here. The real
 * enforcement is server-side: the backend re-reads the same claim and rejects
 * non-therapists (see calmsense-backend/auth.py). Never rely on this enum
 * alone to protect another person's health data.
 */
enum class UserRole(val wireValue: String) {
    /** Regular person using CalmSense on themselves. Their own data only. */
    USER("user"),

    /** Clinician: may read the panic history of patients they select. */
    THERAPIST("therapist"),

    /** Us. Therapist access plus the debug controls on the profile screen. */
    DEVELOPER("developer");

    /** True for the roles allowed to open Stats / the patient picker. */
    val canViewStats: Boolean
        get() = this == THERAPIST || this == DEVELOPER

    /** True for the roles allowed to see the debug section. */
    val canViewDebug: Boolean
        get() = this == DEVELOPER

    companion object {
        /** Lenient parse: unknown, blank or null all fall back to [USER]. */
        fun fromWire(raw: String?): UserRole =
            entries.firstOrNull { it.wireValue.equals(raw?.trim(), ignoreCase = true) } ?: USER
    }
}
