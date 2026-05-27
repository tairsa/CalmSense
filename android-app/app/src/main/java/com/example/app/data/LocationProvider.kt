package com.example.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * One-shot GPS fix at panic-event start. Uses FusedLocationProviderClient's
 * `getCurrentLocation` for a *fresh* reading (lastLocation can be minutes old).
 *
 * Returns null if the location permission isn't granted or the request times
 * out — the report is still saved with whatever vitals we had.
 */
object LocationProvider {

    private const val TAG = "LocationProvider"

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(60_000)   // accept a fix up to 60 s old
            .setDurationMillis(15_000)       // give up after 15 s
            .build()
        val cts = CancellationTokenSource()
        return suspendCoroutine { cont ->
            client.getCurrentLocation(req, cts.token)
                .addOnSuccessListener { loc -> cont.resume(loc) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "current location failed", e)
                    cont.resume(null)
                }
                .addOnCanceledListener { cont.resume(null) }
        }
    }
}
