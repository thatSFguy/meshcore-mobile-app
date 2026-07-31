package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState

/** One entry in a screen's overflow (⋮) menu. */
data class MenuAction(
    val label: String,
    val destructive: Boolean = false,
    /** Non-null renders a checkmark toggle state. */
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * The app-wide top bar contract: title on the left, the space between
 * the title and the menu icon RESERVED for important information
 * (connection state, plaintext warning), and the right side dedicated
 * to a per-screen overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    vm: MeshCoreViewModel,
    menuActions: List<MenuAction>,
    nav: NavController? = null,
    subtitle: String? = null,
    info: (@Composable () -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            androidx.compose.foundation.layout.Column {
                Text(title, maxLines = 1)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        navigationIcon = {
            if (nav != null) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            // Reserved info slot — sits between the title and the menu.
            if (info != null) info() else ConnectionInfoChip(vm)

            if (menuActions.isNotEmpty()) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (action in menuActions) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    action.label,
                                    color = if (action.destructive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        Color.Unspecified
                                    },
                                )
                            },
                            trailingIcon = {
                                if (action.checked == true) {
                                    Icon(Icons.Filled.Check, contentDescription = "Enabled")
                                }
                            },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Default content of the reserved info slot: connection state at a
 * glance — colored dot + node name, with the plaintext-TCP warning
 * taking priority in error color.
 */
@Composable
fun ConnectionInfoChip(vm: MeshCoreViewModel) {
    val engineState by vm.engineState.collectAsState()
    val plaintext by vm.plaintextLink.collectAsState()
    val self by vm.selfInfo.collectAsState()

    val (dotColor, text) = when {
        engineState == EngineState.Ready && plaintext ->
            MaterialTheme.colorScheme.error to "⚠ TCP unencrypted"
        engineState == EngineState.Ready ->
            Color(0xFF43A047) to (self?.name ?: "Connected")
        engineState == EngineState.Handshaking ->
            Color(0xFFFFB300) to "Handshake…"
        engineState == EngineState.Connecting ->
            Color(0xFFFFB300) to "Connecting…"
        else ->
            MaterialTheme.colorScheme.outline to "Offline"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (plaintext && engineState == EngineState.Ready) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
