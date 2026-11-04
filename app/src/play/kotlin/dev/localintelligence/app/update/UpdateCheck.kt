package dev.localintelligence.app.update

import androidx.compose.runtime.Composable

/**
 * The Play build has no update check: Google Play installs its updates, so this draws nothing.
 * The full build's UpdateCheck.kt (app/src/full) has the same declaration and the real row.
 */
@Composable
fun UpdateCheckRow() {
}
