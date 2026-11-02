package com.example.app.data

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.app.R
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant

/** Where the error screen's fix button takes the user. */
enum class ErrorFix { SIGN_IN, UPDATES, RELOAD_MODEL, NETWORK_SETTINGS }

/** One E-<n> code: what the user sees and what they can do about it. */
data class ErrorInfo(
    val code: Int,
    @StringRes val title: Int,
    @StringRes val cause: Int,
    @StringRes val solution: Int? = null,
    val fix: ErrorFix? = null,
)

/**
 * Every error code the app can show. Lives in the app, so it works offline.
 *
 * A number is never reused or renumbered, even after its error is gone: a
 * report sent months ago must still mean the same thing. Add new codes at
 * the end. ErrorCatalogTest checks the numbers and both languages.
 */
object ErrorCatalog {
    val NO_INTERNET = ErrorInfo(1, R.string.e1_title, R.string.e1_cause, R.string.e1_solution, ErrorFix.NETWORK_SETTINGS)
    val SERVER = ErrorInfo(2, R.string.e2_title, R.string.e2_cause, R.string.e2_solution)
    val SIGNED_OUT = ErrorInfo(3, R.string.e3_title, R.string.e3_cause, R.string.e3_solution, ErrorFix.SIGN_IN)
    val WRONG_CREDENTIALS = ErrorInfo(4, R.string.e4_title, R.string.e4_cause, R.string.e4_solution)
    val EMAIL_NOT_CONFIRMED = ErrorInfo(5, R.string.e5_title, R.string.e5_cause, R.string.e5_solution)
    val FINGERPRINT_OFF = ErrorInfo(6, R.string.e6_title, R.string.e6_cause, R.string.e6_solution)
    val UPDATE_FAILED = ErrorInfo(7, R.string.e7_title, R.string.e7_cause, R.string.e7_solution, ErrorFix.UPDATES)
    val UPDATE_DAMAGED = ErrorInfo(8, R.string.e8_title, R.string.e8_cause, R.string.e8_solution, ErrorFix.UPDATES)
    val MODEL_LOAD = ErrorInfo(9, R.string.e9_title, R.string.e9_cause, R.string.e9_solution, ErrorFix.RELOAD_MODEL)
    val THERAPIST_CODE = ErrorInfo(10, R.string.e10_title, R.string.e10_cause, R.string.e10_solution)
    val FEEDBACK_REFUSED = ErrorInfo(11, R.string.e11_title, R.string.e11_cause)
    val UNEXPECTED = ErrorInfo(12, R.string.e12_title, R.string.e12_cause, R.string.e12_solution)
    val TOO_MANY_TRIES = ErrorInfo(13, R.string.e13_title, R.string.e13_cause, R.string.e13_solution)
    val RESET_CODE = ErrorInfo(14, R.string.e14_title, R.string.e14_cause, R.string.e14_solution)
    // 15 and 16 are taken by the server (calmsense-backend/incidents.py): E-15 retrain
    // failed, E-16 auto-retrain pass missed. The next phone code is 17.

    val all = listOf(
        NO_INTERNET, SERVER, SIGNED_OUT, WRONG_CREDENTIALS, EMAIL_NOT_CONFIRMED, FINGERPRINT_OFF,
        UPDATE_FAILED, UPDATE_DAMAGED, MODEL_LOAD, THERAPIST_CODE, FEEDBACK_REFUSED, UNEXPECTED,
        TOO_MANY_TRIES, RESET_CODE,
    )

    /** A server answer (or none: [status] null means the request never got one). */
    fun fromHttp(status: Int?, otherwise: ErrorInfo = UNEXPECTED): AppError = when {
        status == null -> AppError(NO_INTERNET)
        status == 401 -> AppError(SIGNED_OUT, status)
        status == 429 -> AppError(TOO_MANY_TRIES, status)
        status >= 500 -> AppError(SERVER, status)
        else -> AppError(otherwise, status)
    }

    /** A failed Supabase sign-in, sign-up or password reset. */
    fun fromAuth(e: SupabaseAuth.AuthResult.Error): AppError = when {
        e.httpCode == 400 && e.message.contains("confirm", ignoreCase = true) ->
            AppError(EMAIL_NOT_CONFIRMED, 400)
        e.httpCode == 400 -> AppError(WRONG_CREDENTIALS, 400)
        else -> fromHttp(e.httpCode)
    }

    /** An exception from a network call; [otherwise] when it isn't a connection problem. */
    fun fromException(e: Throwable, otherwise: ErrorInfo): AppError = when (e) {
        is HttpStatusException -> fromHttp(e.status, otherwise)
        is UnknownHostException, is ConnectException -> AppError(NO_INTERNET)
        else -> AppError(otherwise)
    }
}

/** A non-2xx answer, thrown where a call has no result type to carry it. */
class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** One error as shown: "E-2 | 503" when a server answer was involved, else "E-2". */
data class AppError(
    val info: ErrorInfo,
    val httpStatus: Int? = null,
    val at: Instant = Instant.now(),
) {
    val label: String get() = if (httpStatus != null) "E-${info.code} | $httpStatus" else "E-${info.code}"
}

/** The error on screen right now; MainActivity shows it with ErrorDialog. */
object AppErrors {
    var current by mutableStateOf<AppError?>(null)

    /** [detail]: the server's own message, for the log only. Never pass anything with an email or token. */
    fun show(error: AppError, detail: String? = null) {
        AppLog.w("Error", "${error.label} shown" + detail?.let { ": ${it.take(200)}" }.orEmpty())
        AppLog.save()  // the user may report it after the app is gone
        current = error
    }
}
