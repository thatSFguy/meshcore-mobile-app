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
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }

    if (engineState != EngineState.Ready) {
        HintText("Connect to a radio to change its pairing PIN.")
        return
    }

    Text("Bluetooth pairing PIN", style = MaterialTheme.typography.titleSmall)
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
            if (pin.isNotEmpty() && !DevicePin.isValid(pin)) {
                Text("A PIN is exactly ${DevicePin.LENGTH} digits")
            } else if (DevicePin.isFactoryDefault(pin)) {
                Text("That is the factory default — it protects nothing")
            }
        },
        isError = pin.isNotEmpty() && !DevicePin.isValid(pin),
        modifier = Modifier.fillMaxWidth(),
    )
    ButtonFlowRow {
        TextButton(
            onClick = { confirm = true },
            enabled = DevicePin.isValid(pin),
        ) { Text("Change PIN") }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Change the pairing PIN?") },
            text = {
                Text(
                    "The radio will use $pin from now on.\n\n" +
                        "• This phone is already paired with the OLD pin, and Android " +
                        "remembers that pairing. You will have to forget the device in " +
                        "Bluetooth settings and pair again before you can reconnect.\n" +
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
                }) { Text("Change it") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}
