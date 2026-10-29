package dev.localintelligence.app

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates one [AssistantSession] per invocation (long-press Home, power button,
 * or the assist gesture). The system binds this service; nothing else can.
 */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}
