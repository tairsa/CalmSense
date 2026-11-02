package com.example.app.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.BACKEND_URL
import com.example.app.BuildConfig
import com.example.app.R
import com.example.app.data.BackendClient
import com.example.app.data.ErrorCatalog
import com.example.app.data.PanicModelCache
import com.example.app.data.PingResult
import com.example.app.data.SessionManager
import com.example.app.data.SettingsStore
import com.example.app.data.StatusLevel
import com.example.app.data.SystemStatus
import com.example.app.data.UploadQueue
import com.example.app.data.WatchVitalsRepository
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private class StatusRow(val title: Int, val text: String, val level: StatusLevel, val detail: String?)

/**
 * Settings → System status. Read-only on purpose (Alex, 2026-11-02): no fix links,
 * no report button. Plain dots by default; Advanced mode adds the raw values.
 * Refreshes on open, every 3 s while open (backend every 30 s), and on "Check now".
 */
@Composable
fun SystemStatusScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val advanced by SettingsStore.advancedMode.collectAsState()
    val pending by UploadQueue.pendingCount.collectAsState()
    val lastUploadMs by UploadQueue.lastSuccessMs.collectAsState()
    val session by SessionManager.session.collectAsState()
    val client = remember { BackendClient(BACKEND_URL) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ping by remember { mutableStateOf<PingResult?>(null) }
    var checkRequests by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(3_000) } }
    LaunchedEffect(checkRequests) {
        while (true) {
            ping = null
            now = System.currentTimeMillis()
            ping = client.ping()
            delay(30_000)
        }
    }

    // Cheap local reads, re-run on each 3 s tick.
    val trainedAt = remember(now) { PanicModelCache(context).load()?.trainedAt }
    val lastCheckMs = remember(now) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).getLong("last_check_ms", 0L)
    }
    val watchAt = WatchVitalsRepository.lastReceivedAt?.toEpochMilli()
    val onBody = WatchVitalsRepository.onBody

    fun age(ms: Long?): Long? = ms?.takeIf { it > 0 }?.let { now - it }
    fun ago(ms: Long?): String = if (age(ms) == null) context.getString(R.string.status_never)
        else DateUtils.getRelativeTimeSpanString(ms!!, now, DateUtils.SECOND_IN_MILLIS).toString()
    fun exact(ms: Long?): String? = ms?.takeIf { it > 0 }?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))
    }

    val watchLevel = SystemStatus.watch(age(watchAt), onBody)
    val rows = listOf(
        StatusRow(
            R.string.status_account,
            session?.email ?: context.getString(R.string.status_signed_out),
            if (session != null) StatusLevel.OK else StatusLevel.BAD,
            session?.userId,
        ),
        StatusRow(R.string.status_version, BuildConfig.VERSION_NAME, StatusLevel.OK, "versionCode ${BuildConfig.VERSION_CODE}"),
        StatusRow(R.string.status_update_check, ago(lastCheckMs), SystemStatus.updateCheck(age(lastCheckMs)), exact(lastCheckMs)),
        StatusRow(
            R.string.status_watch,
            when {
                watchAt == null -> context.getString(R.string.status_watch_none)
                watchLevel == StatusLevel.OK -> context.getString(R.string.status_watch_ok)
                !onBody && (age(watchAt) ?: Long.MAX_VALUE) < 120_000 -> context.getString(R.string.status_watch_off_wrist)
                else -> context.getString(R.string.status_watch_last, ago(watchAt))
            },
            watchLevel,
            watchAt?.let { "${exact(it)} · onBody=$onBody" },
        ),
        StatusRow(R.string.status_last_upload, ago(lastUploadMs), SystemStatus.lastUpload(age(lastUploadMs)), exact(lastUploadMs)),
        StatusRow(
            R.string.status_queue,
            if (pending == 0) context.getString(R.string.status_queue_empty)
            else context.resources.getQuantityString(R.plurals.status_queue_waiting, pending, pending),
            SystemStatus.queue(pending),
            UploadQueue.pendingByKind().entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { null },
        ),
        StatusRow(
            R.string.status_backend,
            context.getString(
                when (ping) {
                    null -> R.string.status_checking
                    PingResult.Connected -> R.string.status_backend_ok
                    else -> R.string.status_backend_bad
                },
            ),
            SystemStatus.backend(ping),
            when (val p = ping) {
                is PingResult.HttpError -> "${ErrorCatalog.fromHttp(p.code).label} · $BACKEND_URL"
                is PingResult.NetworkError -> "${ErrorCatalog.fromHttp(null).label} ${p.reason} · $BACKEND_URL"
                else -> BACKEND_URL
            },
        ),
        StatusRow(
            R.string.status_model,
            trainedAt?.let { context.getString(R.string.status_model_trained, it.take(10)) }
                ?: context.getString(R.string.status_model_none),
            if (trainedAt != null) StatusLevel.OK else StatusLevel.WARN,
            trainedAt,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.status_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                StatusRowView(row, advanced)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { checkRequests++ },
            enabled = ping != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.status_check_now)) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusRowView(row: StatusRow, advanced: Boolean) {
    val (color, word) = when (row.level) {
        StatusLevel.OK -> Color(0xFF2E7D32) to R.string.status_level_ok
        StatusLevel.WARN -> Color(0xFFF9A825) to R.string.status_level_warn
        StatusLevel.BAD -> MaterialTheme.colorScheme.error to R.string.status_level_bad
    }
    val levelWord = stringResource(word)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Colour is not the only signal: TalkBack reads the level word.
        Box(Modifier.size(12.dp).background(color, CircleShape).semantics { contentDescription = levelWord })
        Column(Modifier.weight(1f)) {
            Text(stringResource(row.title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(row.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (advanced && row.detail != null) {
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
