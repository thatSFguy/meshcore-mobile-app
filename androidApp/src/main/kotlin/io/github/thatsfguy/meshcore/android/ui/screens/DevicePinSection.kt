package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.thatsfguy.meshcore.android.ui.MeshCoreViewModel
import io.github.thatsfguy.meshcore.engine.EngineState
import io.github.thatsfguy.meshcore.protocol.DevicePin

/**
 * Change the radio's Bluetooth pairing PIN.
 *
 * Worth its own place rather than a row among the radio parameters:
 * it is the only setting here that is a *credential*, the default is
 * public, and getting it wrong costs you Bluetooth access to the node.
 *
 * The current PIN is never shown, because the firmware offers no way
 * to read it. An app that displayed "123456" because that is the usual
 * default would be guessing at a security setting, which is the one
 * place guessing is least acceptable.
 */
@Composable
fun DevicePinSection(vm: MeshCoreViewModel) {
    val engineState by vm.engineState.collectAsState()
    val deviceInfo by vm.deviceInfo.collectAsState()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var bluetoothPrompt by remember { mutableStateOf(false) }
    var rebootPrompt by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (engineState != EngineState.Ready) {
        HintText("Connect to a radio to change its pairing PIN.")
        return
    }

    Text("Bluetooth pairing PIN", style = MaterialTheme.typography.titleSmall)
    // The node reports its configured PIN in DEVICE_INFO. 0.6.4 claimed
    // this could not be read and showed nothing — the four bytes were
    // simply being skipped by the parser.
    HintText("Configured: " + DevicePin.describe(deviceInfo?.blePin))
    ExpandableHint(
        "Nodes without a screen ship with ${DevicePin.FACTORY_DEFAULT}, which is public.",
    ) {
        DetailText(
            "Until it is changed, anyone in Bluetooth range can pair with this radio and " +
                "read its contacts, messages and settings — the default is the same on every " +
                "node that has no screen, so it is documentation rather than a secret. " +
                "The radio cannot be asked what its PIN currently is, so this screen can " +
                "only set a new one, never show the one in force.",
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = pin,
        onValueChange = { typed -> pin = typed.filter { it in '0'..'9' }.take(DevicePin.LENGTH) },
        label = { Text("New PIN (${DevicePin.LENGTH} digits)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        supportingText = {
            val why = DevicePin.rejection(pin)
            when {
                why != null -> Text(why)
                DevicePin.isUseDefault(pin) ->
                    Text("Clears the PIN — the node reverts to its built-in default")
                DevicePin.isFactoryDefault(pin) ->
                    Text("That is the factory default — it protects nothing")
            }
        },
        isError = DevicePin.rejection(pin) != null,
        modifier = Modifier.fillMaxWidth(),
    )
    ButtonFlowRow {
        TextButton(
            onClick = { confirm = true },
            enabled = DevicePin.isAcceptable(pin),
        ) { Text("Change PIN") }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Change the pairing PIN?") },
            text = {
                Text(
                    (
                        if (DevicePin.isUseDefault(pin)) {
                            "The radio will drop its stored PIN and go back to its " +
                                "built-in default.\n\n"
                        } else {
                            "The radio will use $pin from now on.\n\n"
                        }
                        ) +
                        "• It keeps pairing with the OLD pin until the node is " +
                        "restarted — the firmware picks the active PIN at startup " +
                        "and setting a new one does not change it. You will be " +
                        "offered a reboot.\n" +
                        "• This phone is already paired with the OLD pin, and only " +
                        "Android can drop that pairing — \"Forget device\" in the system " +
                        "Bluetooth settings, which is not the same as Remove under Saved " +
                        "nodes in this app. Until you do it, the phone reconnects on the " +
                        "old pairing and the change looks like it did nothing.\n" +
                        "• Every other phone paired with this radio has to do the same.\n" +
                        "• Nothing can read the PIN back off the radio. If you forget it, " +
                        "Bluetooth access is gone until you reach the node another way — " +
                        "USB, or a factory reset that takes its identity with it.\n\n" +
                        "Write it down before you continue.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDevicePin(pin)
                    confirm = false
                    pin = ""
                    rebootPrompt = true
                }) { Text("Change it") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }

    if (rebootPrompt) {
        AlertDialog(
            onDismissRequest = { rebootPrompt = false },
            title = { Text("Reboot to apply it?") },
            text = {
                Text(
                    "The new PIN is stored, but the radio goes on pairing with the old " +
                        "one until it restarts.\n\nAfter the reboot, your phone will " +
                        "still reconnect without asking for the new PIN — Android " +
                        "remembers the old pairing, and only Android can drop it. To " +
                        "test the PIN you have to remove the pairing in the system " +
                        "Bluetooth settings (\"Forget device\" there), which is NOT the " +
                        "same as Remove under Saved nodes here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rebootRadio()
                    rebootPrompt = false
                    bluetoothPrompt = true
                }) { Text("Reboot now") }
            },
            dismissButton = {
                TextButton(onClick = { rebootPrompt = false }) { Text("Later") }
            },
        )
    }

    if (bluetoothPrompt) {
        AlertDialog(
            onDismissRequest = { bluetoothPrompt = false },
            title = { Text("Remove the old pairing?") },
            text = {
                Text(
                    "Android is holding a pairing made with the OLD pin, and an app " +
                        "cannot delete it. Until you do, this phone reconnects without " +
                        "being asked for the new PIN — so the change looks like it did " +
                        "nothing.\n\nIn Bluetooth settings, find this radio and choose " +
                        "\"Forget device\". The next connection will ask for the new PIN.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bluetoothPrompt = false
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("Open Bluetooth settings") }
            },
            dismissButton = {
                TextButton(onClick = { bluetoothPrompt = false }) { Text("Not now") }
            },
        )
    }
}
