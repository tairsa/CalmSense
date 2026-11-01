package com.example.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's log: every line goes to logcat and to the last [MAX_LINES] kept
 * in memory (oldest dropped first), which "Report a problem" attaches.
 *
 * Memory is lost when the process dies, so the lines are also saved to a file
 * on a crash and whenever an error is shown, and read back at the next start.
 * Android killing the app in the background loses only the lines since the
 * last save.
 *
 * Reports leave the phone: never log heart rate or HRV values, tokens,
 * passwords or email addresses.
 */
object AppLog {
    internal const val MAX_LINES = 500
    private const val FILE_NAME = "app_log.txt"
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    private val lines = ArrayDeque<String>()
    private var file: File? = null

    /** Idempotent. Call from every process entry point, like UploadQueue.init. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        // Lines from the previous run go before anything logged since start.
        val saved = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())
        saved.asReversed().forEach { lines.addFirst(it) }
        trim()
        val previous = Thread.getDefaultUncaughtExceptionHandler()  // Sentry's, when it is set up
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            e("Crash", "Uncaught on ${thread.name}", e)
            save()
            previous?.uncaughtException(thread, e)
        }
    }

    /** Logcat only: debug lines fire every few seconds and would push out everything else. */
    fun d(tag: String, msg: String) { Log.d(tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); add("I", tag, msg, null) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); add("W", tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); add("E", tag, msg, t) }

    /** Everything kept, oldest first, for a problem report. */
    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun save() {
        val f = file ?: return
        runCatching { f.writeText(snapshot()) }.onFailure { Log.w("AppLog", "Could not save the log", it) }
    }

    @Synchronized
    internal fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val error = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        lines.addLast("${LocalDateTime.now().format(TIME)} $level/$tag: $msg$error")
        trim()
    }

    private fun trim() {
        while (lines.size > MAX_LINES) lines.removeFirst()
    }
}
