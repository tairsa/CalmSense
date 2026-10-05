package com.example.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.app.R
import com.example.app.data.SupabaseAuth
import kotlinx.coroutines.launch

/**
 * Forgot password, in two steps: email -> "send code"; then the emailed code
 * plus a new password -> signed in. Ends with a session, so a successful reset
 * also signs the user in, the way they meant to arrive.
 */
@Composable
fun ForgotPasswordDialog(
    initialEmail: String,
    onDone: (SupabaseAuth.Session) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail.trim()) }
    var codeSent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Supabase codes are 6 digits by default, longer if the project says so.
    val canSubmit = !busy && if (!codeSent) email.contains('@')
                             else code.trim().length >= 6 && password.length >= 6

    fun submit() {
        busy = true
        error = null
        scope.launch {
            if (!codeSent) {
                val e = SupabaseAuth.sendPasswordReset(email)
                if (e == null) codeSent = true else error = e.message
            } else {
                when (val r = SupabaseAuth.verifyResetCode(email, code)) {
                    is SupabaseAuth.AuthResult.Error -> error = r.message
                    is SupabaseAuth.AuthResult.Success -> {
                        val e = SupabaseAuth.updatePassword(r.session.accessToken, password)
                        if (e == null) onDone(r.session) else error = e.message
                    }
                }
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.reset_title)) },
        text = {
            Column {
                Text(
                    if (codeSent) context.getString(R.string.reset_code_sent, email)
                    else stringResource(R.string.reset_email_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (!codeSent) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text(stringResource(R.string.login_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
                    )
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(10); error = null },
                        label = { Text(stringResource(R.string.reset_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text(stringResource(R.string.reset_new_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = { Text(stringResource(R.string.login_password_hint)) },
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
                    )
                    TextButton(onClick = { codeSent = false; code = ""; error = null }, enabled = !busy) {
                        Text(stringResource(R.string.reset_change_email))
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSubmit) {
                Text(stringResource(if (codeSent) R.string.reset_submit else R.string.reset_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
