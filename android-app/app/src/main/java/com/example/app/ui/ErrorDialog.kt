package com.example.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.app.BuildConfig
import com.example.app.R
import com.example.app.data.AppError
import com.example.app.data.AppLog
import com.example.app.data.ErrorFix
import com.example.app.data.PostResult
import com.example.app.data.UploadQueue
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The body POST /api/v1/problem-reports expects. The user comes from the token, not from here. */
fun problemReportBody(error: AppError, note: String): JSONObject = JSONObject().apply {
    put("code", error.info.code)
    error.httpStatus?.let { put("http_status", it) }
    put("app_version", BuildConfig.VERSION_NAME)
    put("os_version", Build.VERSION.RELEASE)
    put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
    note.trim().takeIf { it.isNotEmpty() }?.let { put("note", it.take(2000)) }
    put("logs", AppLog.snapshot().takeLast(200_000))
    put("timestamp", error.at.toString())
}

private enum class ReportState { IDLE, WRITING, SENDING, SENT, QUEUED, REFUSED }

/**
 * The one error screen: code, what happened, why, what to do, a button to
 * the place that fixes it when there is one, and "Send report".
 *
 * [send] is UploadQueue.postProblemReport, so a report written offline waits
 * on the phone and goes out with the next upload.
 */
@Composable
fun ErrorDialog(
    error: AppError,
    onFix: (ErrorFix) -> Unit,
    onDismiss: () -> Unit,
    send: suspend (JSONObject) -> PostResult,
) {
    var state by remember(error) { mutableStateOf(ReportState.IDLE) }
    var note by remember(error) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val info = error.info

    AlertDialog(
        onDismissRequest = { if (state != ReportState.SENDING) onDismiss() },
        title = { Text(stringResource(info.title)) },
        text = {
            Column {
                Text(
                    error.label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(info.cause))
                info.solution?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state) {
                    ReportState.WRITING, ReportState.SENDING -> {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(2000) },
                            label = { Text(stringResource(R.string.error_report_note)) },
                            enabled = state == ReportState.WRITING,
                        )
                    }
                    ReportState.SENT, ReportState.QUEUED, ReportState.REFUSED -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(
                                when (state) {
                                    ReportState.SENT -> R.string.error_report_sent
                                    ReportState.QUEUED -> R.string.error_report_queued
                                    else -> R.string.error_report_refused
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ReportState.IDLE -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                ReportState.IDLE -> info.fix?.let { fix ->
                    TextButton(onClick = { onDismiss(); onFix(fix) }) { Text(stringResource(fixLabel(fix))) }
                }
                ReportState.WRITING, ReportState.SENDING -> TextButton(
                    enabled = state == ReportState.WRITING,
                    onClick = {
                        state = ReportState.SENDING
                        scope.launch {
                            val r = send(problemReportBody(error, note))
                            state = when {
                                r == PostResult.Success -> ReportState.SENT
                                UploadQueue.shouldRetry(r) -> ReportState.QUEUED
                                else -> ReportState.REFUSED
                            }
                        }
                    },
                ) { Text(stringResource(R.string.error_report_send)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            }
        },
        dismissButton = {
            when (state) {
                ReportState.IDLE -> Row {
                    TextButton(onClick = { state = ReportState.WRITING }) {
                        Text(stringResource(R.string.error_send_report))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                }
                ReportState.WRITING -> TextButton(onClick = { state = ReportState.IDLE }) {
                    Text(stringResource(R.string.action_cancel))
                }
                else -> Unit
            }
        },
    )
}

private fun fixLabel(fix: ErrorFix): Int = when (fix) {
    ErrorFix.SIGN_IN -> R.string.error_fix_sign_in
    ErrorFix.UPDATES -> R.string.error_fix_updates
    ErrorFix.RELOAD_MODEL -> R.string.error_fix_reload_model
    ErrorFix.NETWORK_SETTINGS -> R.string.error_fix_network
}
