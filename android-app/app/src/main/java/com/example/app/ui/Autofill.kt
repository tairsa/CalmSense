package com.example.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Wires a text field into the system autofill service (Google Password
 * Manager, 1Password, Bitwarden, ...) so it offers saved credentials the way
 * any other app does.
 *
 * Compose 1.7 has no declarative autofill support - that arrived in 1.8 as
 * `Modifier.semantics { contentType = ... }`. Until the Compose BOM is bumped,
 * a field has to be registered with the autofill tree by hand, given its
 * on-screen bounds, and have fill requested when it takes focus. This wrapper
 * keeps that ceremony in one place; when the BOM moves to 1.8+ this file can
 * be deleted and each call site replaced with the one-line semantics modifier.
 *
 * Usage:
 * ```
 * Autofillable(listOf(AutofillType.EmailAddress), onFill = { email = it }) { m ->
 *     OutlinedTextField(value = email, onValueChange = { email = it }, modifier = m)
 * }
 * ```
 *
 * @param types what kind of value this field holds. The autofill service uses
 *   these to decide what to offer, so they must be accurate: labelling a
 *   password field as an email would leak the password into the wrong slot.
 * @param onFill invoked with the value the user picked.
 * @param content receives the Modifier to apply to the field it creates.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Autofillable(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val autofill = LocalAutofill.current
    val autofillNode = AutofillNode(autofillTypes = types, onFill = onFill)
    LocalAutofillTree.current += autofillNode

    Box(
        Modifier.onGloballyPositioned {
            // The service needs the field's position to anchor its dropdown.
            autofillNode.boundingBox = it.boundsInWindow()
        }
    ) {
        content(
            Modifier.onFocusChanged { state ->
                autofill?.run {
                    if (state.isFocused) requestAutofillForNode(autofillNode)
                    // Cancelling on blur stops a stale suggestion popup hanging
                    // around over whichever field the user moved to.
                    else cancelAutofillForNode(autofillNode)
                }
            }
        )
    }
}
