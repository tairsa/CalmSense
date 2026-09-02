package com.example.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.app.data.SupabaseAuth
import com.example.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

/* ---------------------------------------------------------------------------
 * LoginScreen
 *
 * Calm, single-page auth entry. Tab-toggle between "Sign in" and "Create
 * account". Calls SupabaseAuth directly; on success, hands the Session back
 * via [onAuthenticated] so the caller can persist it (SessionManager) and
 * navigate into the main app.
 * ------------------------------------------------------------------------- */

private enum class AuthMode { SIGN_IN, SIGN_UP }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginScreen(
    onAuthenticated: (SupabaseAuth.Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val canSubmit = email.isNotBlank() && password.length >= 6 && !loading

    fun submit() {
        error = null
        loading = true
        scope.launch {
            val result = when (mode) {
                AuthMode.SIGN_IN -> SupabaseAuth.signIn(email, password)
                AuthMode.SIGN_UP -> SupabaseAuth.signUp(email, password)
            }
            loading = false
            when (result) {
                is SupabaseAuth.AuthResult.Success -> {
                    // Supabase returns a user id but an EMPTY access token when
                    // the project requires email confirmation. Treating that as
                    // signed-in produces a session that cannot authenticate
                    // against the backend - the failure then surfaces much
                    // later, somewhere unrelated. Keep them on this screen.
                    if (result.session.accessToken.isBlank()) {
                        error = "Check your email to confirm your account, " +
                            "then sign in."
                        mode = AuthMode.SIGN_IN
                    } else {
                        onAuthenticated(result.session)
                    }
                }
                is SupabaseAuth.AuthResult.Error -> error = result.message
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Logo bubble
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "CalmSense",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (mode) {
                    AuthMode.SIGN_IN -> "Welcome back."
                    AuthMode.SIGN_UP -> "Let's get you set up."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            // Mode toggle
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == AuthMode.SIGN_IN,
                    onClick = { mode = AuthMode.SIGN_IN; error = null },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Sign in") }
                SegmentedButton(
                    selected = mode == AuthMode.SIGN_UP,
                    onClick = { mode = AuthMode.SIGN_UP; error = null },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Create account") }
            }
            Spacer(Modifier.height(20.dp))

            // EmailAddress and Username both listed: password managers key
            // saved logins on one or the other depending on how the entry was
            // created, and offering both means an existing CalmSense entry is
            // found either way.
            Autofillable(
                types = listOf(AutofillType.EmailAddress, AutofillType.Username),
                onFill = { email = it; error = null },
            ) { autofillModifier ->
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("Email") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = autofillModifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            // NewPassword on sign-up so the manager offers to generate and
            // save one; Password on sign-in so it offers the saved value.
            Autofillable(
                types = listOf(
                    if (mode == AuthMode.SIGN_UP) AutofillType.NewPassword else AutofillType.Password
                ),
                onFill = { password = it; error = null },
            ) { autofillModifier ->
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        if (mode == AuthMode.SIGN_UP) {
                            Text("At least 6 characters.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = autofillModifier.fillMaxWidth(),
                )
            }

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = when (mode) {
                            AuthMode.SIGN_IN -> "Sign in"
                            AuthMode.SIGN_UP -> "Create account"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Your data stays private. Only you and your therapist can see it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    AppTheme {
        LoginScreen(onAuthenticated = {})
    }
}
