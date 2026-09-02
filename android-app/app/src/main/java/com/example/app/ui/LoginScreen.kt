package com.example.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.app.data.SessionManager
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

@Composable
fun LoginScreen(
    onAuthenticated: (SupabaseAuth.Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Prefill from the last "Remember me" sign-in. Read once: re-reading on
    // recomposition would fight the user as they edit the field.
    val savedEmail = remember { SessionManager.rememberedEmail(context) }

    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf(savedEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(savedEmail != null) }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    // Compose 1.8+. commit() tells the autofill service the form was submitted,
    // which is what makes it offer to SAVE the credentials. Without it a
    // manager can fill but never learns the login in the first place - the
    // gap that made autofill look unsupported on the old API.
    val autofillManager = LocalAutofillManager.current

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
                        // Only on success: remembering an address that failed
                        // to sign in would prefill a typo forever.
                        SessionManager.setRememberedEmail(
                            context, if (rememberMe) result.session.email.ifBlank { email } else null
                        )
                        // Offer to save these credentials. Only on success, so
                        // a manager is never asked to store a rejected password.
                        autofillManager?.commit()
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

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = { Text("Email") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                // Both types: password managers key saved logins on one or the
                // other depending on how the entry was created, so advertising
                // both finds an existing CalmSense entry either way.
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentType = ContentType.EmailAddress + ContentType.Username
                    },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text("Password") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showPassword) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            // Describes the ACTION, which is what a screen
                            // reader user needs, and it doubles as the
                            // tooltip.
                            contentDescription = if (showPassword) "Hide password"
                                                 else "Show password",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        }
                    },
                    supportingText = {
                        if (mode == AuthMode.SIGN_UP) {
                            Text("At least 6 characters.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    // NewPassword while signing up so a manager offers to
                    // generate and store one; Password while signing in so it
                    // offers the saved value.
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentType = if (mode == AuthMode.SIGN_UP) ContentType.NewPassword
                                          else ContentType.Password
                        },
                )

            // Only meaningful when signing in: on sign-up the address is being
            // typed for the first time, so there is nothing yet to remember.
            if (mode == AuthMode.SIGN_IN) {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // The whole row toggles, not just the 20dp box - a
                    // checkbox-sized tap target is an accessibility problem.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberMe = !rememberMe },
                ) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text(
                        "Remember my email",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
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
