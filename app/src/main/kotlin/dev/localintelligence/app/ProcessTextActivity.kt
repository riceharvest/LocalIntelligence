package dev.localintelligence.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Ask LocalIntelligence" in the text-selection toolbar.
 *
 * WHY IT HANDS OFF RATHER THAN OPENING THE CHAT ITSELF: the selected text has to
 * reach the same place a share does, so there is exactly one way text enters
 * the conversation. [MainActivity] already turns an ACTION_SEND into the first
 * user turn, on cold start (onCreate) and warm (onNewIntent). Sending the
 * selection through that path means this class carries no chat logic of its own.
 *
 * WHY NO UI: this activity is a pass-through. It reads the selection, starts
 * MainActivity, and finishes in the same frame. The translucent theme in the
 * manifest keeps the host app's text view visible underneath while that happens.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() }

        if (text != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
        finish()
    }
}
