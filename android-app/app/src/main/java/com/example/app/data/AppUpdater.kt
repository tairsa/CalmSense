package com.example.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates the phone app from GitHub Releases: Settings > App updates, or the
 * daily notification from UpdateNotifier.
 *
 * A release tagged vX.Y.Z carries calmsense-phone-X.Y.Z.apk and SHA256SUMS
 * (.github/workflows/release.yml). Android only installs it over this app when
 * it is signed with the same key, which is what stops a swapped file; the
 * checksum catches a damaged download before the installer does.
 */
object AppUpdater {

    private const val LATEST = "https://api.github.com/repos/tairsa/CalmSense/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    data class Release(
        val version: String,
        val versionCode: Int,
        val apkName: String,
        val apkUrl: String,
        val sumsUrl: String,
        val notes: String,
    )

    /** The newest release check() found, so Settings opened from the notification shows it at once. */
    @Volatile var lastFound: Release? = null
        private set

    /** The hash SHA256SUMS (sha256sum output) lists for [fileName], lowercase, or null. */
    fun expectedSha256(sums: String, fileName: String): String? =
        sums.lineSequence()
            .map { it.trim().split(Regex("""\s+\*?"""), limit = 2) }
            .firstOrNull { it.size == 2 && it[1] == fileName }
            ?.get(0)?.lowercase()

    /** Same formula as android-app/build.gradle.kts: MAJOR*10000 + MINOR*100 + PATCH. */
    fun versionCode(version: String): Int? {
        val parts = version.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return parts[0] * 10000 + parts[1] * 100 + parts[2]
    }

    /** The newest published build when it is newer than this one, else null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            // 404 = nothing published yet, which just means "up to date".
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) error("GitHub answered HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val code = versionCode(version) ?: return@withContext null
            val assets = json.getJSONArray("assets")
            val files = (0 until assets.length()).map { assets.getJSONObject(it) }
            val apk = files.firstOrNull { it.getString("name").let { n -> n.startsWith("calmsense-phone") && n.endsWith(".apk") } }
                ?: return@withContext null
            // A release without checksums (before 1.5.0) is never offered.
            val sums = files.firstOrNull { it.getString("name") == "SHA256SUMS" } ?: return@withContext null
            (if (code <= BuildConfig.VERSION_CODE) null
            else Release(
                version, code, apk.getString("name"), apk.getString("browser_download_url"),
                sums.getString("browser_download_url"), json.optString("body").trim(),
            )).also { lastFound = it }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release's APK into the cache and checks it is the build it claims to be. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, release.apkName)
            val sha = MessageDigest.getInstance("SHA-256")
            // GitHub redirects to its file host; HttpURLConnection follows https -> https.
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode !in 200..299) error("Download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()  // leaving Settings cancels the download
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // A cut-off download or the wrong file would only fail later inside
            // the system installer, with a message that explains nothing.
            val expected = expectedSha256(getText(release.sumsUrl), release.apkName)
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            if (expected != actual) {
                file.delete()
                error("The download was damaged. Try again.")
            }
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            if (info?.packageName != context.packageName || info.longVersionCode != release.versionCode.toLong()) {
                file.delete()
                error("The downloaded file is not CalmSense ${release.version}")
            }
            file
        }

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode !in 200..299) error("Download failed (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Whether the user has let this app install apps (Settings > Install unknown apps). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens this app's "Install unknown apps" switch. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to Android's installer, which asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
