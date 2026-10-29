package dev.localintelligence.app

import android.service.voice.VoiceInteractionService

/**
 * The system-facing half of "LocalIntelligence as the phone's digital assistant".
 *
 * WHY THIS EXISTS AS A SEPARATE CLASS: Android only lists an app under
 * Settings > Default apps > Digital assistant app when a VoiceInteractionService
 * declares itself with the `android.voice_interaction` meta-data. The service
 * itself does nothing: the system binds it, and the work happens in the session
 * it hands out via [AssistantSessionService].
 */
class AssistantInteractionService : VoiceInteractionService()
