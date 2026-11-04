package dev.localintelligence.app.ui

import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.localintelligence.app.security.AppLock
import dev.localintelligence.app.security.PinCheck
import dev.localintelligence.core.security.PinCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock screen. It replaces the app while the lock is on, in MainActivity and
 * in the assistant overlay, so nothing behind it is drawn.
 *
 * WHY FLAG_SECURE HERE AND NOT FOR THE WHOLE APP: the flag blocks screenshots and
 * the recent-apps thumbnail. It is set only while this screen is up, so the
 * thumbnail of the app is hidden while it is locked, and the rest of the app is
 * unaffected while it is open.
 *
 * WHY PIN ALWAYS WORKS: fingerprint or face is an option, and the PIN is the
 * fallback. The biometric prompt has a "Use PIN" button and no device-credential
 * fallback, so a phone lock screen cannot unlock the app.
 */
@Composable
fun AppLockScreen(appLock: AppLock) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val biometricUsable = remember(appLock, activity) {
        appLock.biometricEnabled() &&
            activity != null &&
            BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    // The wait after too many wrong PINs. [clock] is refreshed while it runs, so the
    // remaining time on screen counts down.
    var waitUntil by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(waitUntil) {
        while (true) {
            clock = System.currentTimeMillis()
            if (clock >= waitUntil) break
            delay(500)
        }
    }
    val waitLeftMillis = (waitUntil - clock).coerceAtLeast(0L)
    val canType = waitLeftMillis == 0L && !checking

    fun showBiometric(host: FragmentActivity) {
        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.unlock()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock LocalIntelligence")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN")
                .build(),
        )
    }

    // The prompt opens once when the screen appears; "Use fingerprint or face" opens it again.
    LaunchedEffect(biometricUsable) {
        if (biometricUsable) activity?.let(::showBiometric)
    }

    fun submit() {
        if (!canType || pin.length < PinCode.MIN_LENGTH) return
        checking = true
        scope.launch {
            when (val result = appLock.check(pin)) {
                PinCheck.Correct -> appLock.unlock()
                PinCheck.Wrong -> {
                    message = "That is not the PIN."
                    pin = ""
                }
                is PinCheck.Waiting -> {
                    clock = System.currentTimeMillis()
                    waitUntil = clock + result.remainingMillis
                    message = "Too many wrong PINs."
                    pin = ""
                }
            }
            checking = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("LocalIntelligence is locked", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(PinCode.MAX_LENGTH) },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = canType,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { submit() },
                    enabled = canType && pin.length >= PinCode.MIN_LENGTH,
                ) { Text("Unlock") }
                if (biometricUsable) {
                    OutlinedButton(onClick = { activity?.let(::showBiometric) }) {
                        Text("Use fingerprint or face")
                    }
                }
                if (waitLeftMillis > 0L) {
                    Text(
                        "Wait ${(waitLeftMillis + 999L) / 1000L} seconds before trying again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "Forgot your PIN? The only way back is to clear this app's data in Android Settings. " +
                        "That deletes every chat, model and setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
