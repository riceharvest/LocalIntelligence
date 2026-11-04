package dev.localintelligence.app

import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * The system-facing half of "LocalIntelligence as the phone's digital assistant".
 *
 * WHY THIS EXISTS AS A SEPARATE CLASS: Android only lists an app under
 * Settings > Default apps > Digital assistant app when a VoiceInteractionService
 * declares itself with the `android.voice_interaction` meta-data.
 *
 * When the system binds the service ([onReady]), it also resumes [WakeWordService]
 * if the user had wake-word spotting enabled before a reboot, and exposes
 * [showHandsFreeSession] so a wake-word detection can open the assist overlay in
 * hands-free mode through `showSession(...)`.
 */
class AssistantInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        runningInstance = this
        val container = (application as LocalIntelligenceApp).container
        if (WakeWordService.wanted(this, container)) {
            WakeWordService.sync(this, container)
        }
    }

    override fun onShutdown() {
        if (runningInstance === this) {
            runningInstance = null
        }
        super.onShutdown()
    }

    companion object {
        const val EXTRA_HANDS_FREE = "dev.localintelligence.app.extra.HANDS_FREE"

        @Volatile
        private var runningInstance: AssistantInteractionService? = null

        fun isReady(): Boolean = runningInstance != null

        /**
         * Opens the assistant session in hands-free mode when this service is bound
         * as the phone's active digital assistant.
         */
        fun showHandsFreeSession(): Boolean {
            val service = runningInstance ?: return false
            return runCatching {
                val args = Bundle().apply { putBoolean(EXTRA_HANDS_FREE, true) }
                service.showSession(args, 0)
                true
            }.getOrDefault(false)
        }
    }
}
