package com.example.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Wires a text field into the system autofill service (Samsung Pass, Google
 * Password Manager, 1Password, ...) so it offers saved credentials the way any
 * other app does.
 *
 * Compose 1.7 has no declarative autofill - that arrived in 1.8 as
 * `Modifier.semantics { contentType = ... }`. Until the Compose BOM is bumped,
 * a field must be registered with the autofill tree by hand, given its
 * on-screen bounds, and have fill requested when it takes focus. This keeps
 * that ceremony in one place; on 1.8+ this file can be deleted and each call
 * site becomes a one-line modifier.
 *
 * The node is deliberately [remember]ed and registered in a [DisposableEffect].
 * Creating it inline instead looks like it works and does not: every keystroke
 * recomposes, which would build a fresh node with a fresh id, append it to the
 * tree again, and leave the autofill service holding a node whose bounding box
 * belonged to an earlier instance. The symptom is a field that simply never
 * offers a suggestion.
 *
 * @param types what the field holds. The service uses these to decide what to
 *   offer, so they must be accurate - labelling a password field as an email
 *   would hand the password to the wrong slot.
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
    val autofillTree = LocalAutofillTree.current

    // Keeps the latest callback without re-creating the node, so the node's
    // identity survives recomposition while onFill can still close over
    // current state.
    val currentOnFill by rememberUpdatedState(onFill)

    // Keyed on the types: the password field legitimately switches between
    // Password and NewPassword when the user toggles sign-in/sign-up, and the
    // service must be told which it now is.
    val node = remember(types) {
        AutofillNode(autofillTypes = types, onFill = { currentOnFill(it) })
    }

    DisposableEffect(node) {
        autofillTree += node
        onDispose { autofillTree.children.remove(node.id) }
    }

    Box(
        Modifier.onGloballyPositioned {
            // The service anchors its dropdown to these bounds.
            node.boundingBox = it.boundsInWindow()
        }
    ) {
        content(
            Modifier.onFocusChanged { state ->
                autofill?.run {
                    if (state.isFocused) requestAutofillForNode(node)
                    // Cancel on blur so a stale popup doesn't hang over
                    // whichever field the user moved to.
                    else cancelAutofillForNode(node)
                }
            }
        )
    }
}
