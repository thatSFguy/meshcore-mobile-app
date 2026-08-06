package io.github.thatsfguy.meshcore.android.ui.screens

import io.github.thatsfguy.meshcore.presentation.AdminSession
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel

/**
 * Repeater/room sign-in, as a dialog in front of the hub — the
 * reference client's shape (`repeater_login_dialog` → `repeater_hub`).
 *
 * It replaced a password row welded to the top of a six-tab screen,
 * where it stayed visible and re-typable for the whole session and
 * carried a three-line explanation of what read-only meant. The nuance
 * lives here now, once, at the moment it is relevant
 * (REBUILD-PLAYBOOK §6.3: one line per control, nuance behind a tap).
 *
 * There is no access-level control on this dialog and there must never
 * be one: you present a password and the node decides. See
 * [AdminSession].
 */
@Composable
fun RepeaterLoginDialog(
    vm: MeshCoreViewModel,
    keyHex: String,
    nodeName: String,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(true) }
    var reveal by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }

    val inFlight by vm.loginInFlight.collectAsState()
    val errors by vm.loginError.collectAsState()
    val busy = keyHex in inFlight
    val error = errors[keyHex]

    // A sealed password fills the field so the common case is one tap.
    LaunchedEffect(keyHex) {
        val saved = vm.savedLoginPassword(keyHex)
        if (!saved.isNullOrEmpty()) {
            password = saved
            prefilled = true
        }
    }

    // The session becomes non-None only when the node has answered, so
    // this is the authoritative "we're done here" signal.
    val sessions by vm.adminSessions.collectAsState()
    val session = sessions[keyHex] ?: AdminSession.None
    LaunchedEffect(session) {
        if (session.signedIn) onDismiss()
    }

    fun submit() {
        if (!busy) vm.repeaterLogin(keyHex, password, savePassword)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign in to $nodeName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The node decides what this password unlocks and says so " +
                        "when it replies. Many nodes accept a blank password " +
                        "for read-only access.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (error != null) vm.clearLoginError(keyHex)
                    },
                    label = { Text(if (prefilled) "Password (saved)" else "Password") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                    // A word, not a glyph: material-icons-core has no
                    // eye icon and pulling in material-icons-extended
                    // for one control is the wrong trade
                    // (REBUILD-PLAYBOOK §0.5, budget per dependency).
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }) {
                            Text(if (reveal) "Hide" else "Show")
                        }
                    },
                    visualTransformation = if (reveal) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = savePassword,
                        onCheckedChange = { savePassword = it },
                        enabled = !busy,
                    )
                    Text(
                        "Keep in the keystore",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { submit() }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Signing in…")
                } else {
                    Text("Sign in")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
