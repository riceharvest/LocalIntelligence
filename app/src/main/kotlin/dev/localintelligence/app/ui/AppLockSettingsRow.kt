package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.app.security.AppLock
import dev.localintelligence.app.security.PinCheck
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.security.PinCode
import dev.localintelligence.core.settings.SettingDef
import kotlinx.coroutines.launch

/** Which PIN dialog is open. Null means none. */
private enum class PinStep { SetNew, CheckToDisable, CheckToChange }

/**
 * The "App lock" row. It is a switch like any other, but turning it on needs a new
 * PIN and turning it off needs the current one, so the switch cannot be flipped
 * by someone holding an unlocked phone. The switch reads the stored value, and
 * only the dialogs write it, through [AppLock].
 *
 * WHY NOT THE GENERIC SWITCH: [SettingRow] would store the flag with no PIN behind
 * it, so the lock would be on with nothing to unlock it.
 */
@Composable
fun AppLockRow(store: SettingsStore, def: SettingDef.Toggle, help: String, note: String?) {
    val appLock = (LocalContext.current.applicationContext as LocalIntelligenceApp).container.appLock
    val on = store.stored(def)
    var step by remember { mutableStateOf<PinStep?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(def.title, style = MaterialTheme.typography.bodyLarge)
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(
                checked = on,
                onCheckedChange = { wantOn -> step = if (wantOn) PinStep.SetNew else PinStep.CheckToDisable },
            )
        }
        if (on) TextButton(onClick = { step = PinStep.CheckToChange }) { Text("Change PIN") }
    }

    when (step) {
        PinStep.SetNew -> SetPinDialog(
            appLock = appLock,
            onDone = { step = null },
            onCancel = { step = null },
        )
        PinStep.CheckToDisable -> CheckPinDialog(
            title = "Turn off App lock",
            appLock = appLock,
            onCorrect = {
                appLock.disable()
                step = null
            },
            onCancel = { step = null },
        )
        PinStep.CheckToChange -> CheckPinDialog(
            title = "Enter your current PIN",
            appLock = appLock,
            onCorrect = { step = PinStep.SetNew },
            onCancel = { step = null },
        )
        null -> Unit
    }
}

@Composable
private fun SetPinDialog(appLock: AppLock, onDone: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "4 to 12 digits. If you forget it, the only way back is to clear the app's data.",
                    style = MaterialTheme.typography.bodySmall,
                )
                PinField("PIN", pin) { pin = it }
                PinField("Type it again", again) { again = it }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    when {
                        !PinCode.isValid(pin) -> error = "Use 4 to 12 digits."
                        pin != again -> error = "The two PINs do not match."
                        else -> {
                            saving = true
                            scope.launch {
                                val saved = appLock.enable(pin)
                                saving = false
                                if (saved) onDone() else error = "The phone's keystore refused to store the PIN. The lock is still off."
                            }
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Asks for the current PIN before a change that needs it. [onCorrect] runs only after a correct PIN. */
@Composable
private fun CheckPinDialog(
    title: String,
    appLock: AppLock,
    onCorrect: suspend () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField("Current PIN", pin) { pin = it }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && pin.length >= PinCode.MIN_LENGTH,
                onClick = {
                    busy = true
                    scope.launch {
                        when (val result = appLock.check(pin)) {
                            PinCheck.Correct -> onCorrect()
                            PinCheck.Wrong -> error = "That is not the PIN."
                            is PinCheck.Waiting -> error =
                                "Too many wrong PINs. Wait ${(result.remainingMillis + 999L) / 1000L} seconds."
                        }
                        pin = ""
                        busy = false
                    }
                },
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(PinCode.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}
