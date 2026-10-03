package com.example.app.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Sends an uncaught crash's stack trace to the phone, which reports it to
 * crash reporting (the phone's WatchListenerService).
 *
 * Relayed rather than reported directly because the watch app has no INTERNET
 * permission and should keep it that way: nothing leaves the watch except over
 * the paired Data Layer, same as the sensor samples.
 */
object CrashRelay {
    const val MSG_PATH_CRASH = "/calmsense/crash"
    private const val MAX_CHARS = 16_000      // well under the Data Layer message cap
    private const val SEND_TIMEOUT_MS = 3_000L

    @Volatile private var installed = false

    /** Idempotent; call from every entry point (activity, service). */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val text = "${BuildConfig.VERSION_NAME}\n${e.stackTraceToString()}".take(MAX_CHARS)
            // Tasks.await refuses the main thread, where most crashes land, so
            // send from a helper thread and wait for it - bounded, because the
            // process is dying and must not hang if the phone is out of range.
            val sender = Thread {
                runCatching {
                    val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    for (node in nodes) {
                        Tasks.await(
                            Wearable.getMessageClient(app).sendMessage(node.id, MSG_PATH_CRASH, text.toByteArray(Charsets.UTF_8)),
                            SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS,
                        )
                    }
                }
            }
            sender.start()
            sender.join(SEND_TIMEOUT_MS)
            previous?.uncaughtException(thread, e)
        }
    }
}
