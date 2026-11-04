package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
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
import dev.localintelligence.app.settings.SettingText
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
                Text(SettingText.title(LocalContext.current, def), style = MaterialTheme.typography.bodyLarge)
                Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Switch(
                checked = on,
                onCheckedChange = { wantOn -> step = if (wantOn) PinStep.SetNew else PinStep.CheckToDisable },
            )
        }
        if (on) TextButton(onClick = { step = PinStep.CheckToChange }) { Text(stringResource(R.string.change_pin)) }
    }

    when (step) {
        PinStep.SetNew -> SetPinDialog(
            appLock = appLock,
            onDone = { step = null },
            onCancel = { step = null },
        )
        PinStep.CheckToDisable -> CheckPinDialog(
            title = stringResource(R.string.turn_off_app_lock),
            appLock = appLock,
            onCorrect = {
                appLock.disable()
                step = null
            },
            onCancel = { step = null },
        )
        PinStep.CheckToChange -> CheckPinDialog(
            title = stringResource(R.string.enter_your_current_pin),
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
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.set_a_pin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.n_4_to_12_digits_if_you_forget_it_the_2),
                    style = MaterialTheme.typography.bodySmall,
                )
                PinField(stringResource(R.string.pin), pin) { pin = it }
                PinField(stringResource(R.string.type_it_again), again) { again = it }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    when {
                        !PinCode.isValid(pin) -> error = context.getString(R.string.pin_use_4_to_12_digits)
                        pin != again -> error = context.getString(R.string.pins_do_not_match)
                        else -> {
                            saving = true
                            scope.launch {
                                val saved = appLock.enable(pin)
                                saving = false
                                if (saved) onDone() else error = context.getString(R.string.pin_keystore_refused_lock_off)
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
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
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(stringResource(R.string.current_pin), pin) { pin = it }
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
                            PinCheck.Wrong -> error = context.getString(R.string.that_is_not_the_pin)
                            is PinCheck.Waiting -> error = context.getString(
                                R.string.pin_too_many_wrong_wait,
                                (result.remainingMillis + 999L) / 1000L,
                            )
                        }
                        pin = ""
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.continue_label)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
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
