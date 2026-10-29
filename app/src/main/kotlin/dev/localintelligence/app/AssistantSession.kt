package dev.localintelligence.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

/**
 * One assistant invocation.
 *
 * WHY onShow STARTS AN ACTIVITY AND THEN HIDES: the session window has no
 * content of its own, so the visible UI is [AssistActivity], drawn over the
 * current app. The session is hidden rather than finished, so it stays alive for
 * the screenshot callback the system delivers for this invocation.
 *
 * WHY startAssistantActivity: the session's own launch call, which the system
 * accepts from an assistant session. Not verified on a device yet.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        hide()
    }

    /** Keeps the capture in memory only. See [AssistantScreenshot]. */
    override fun onHandleScreenshot(screenshot: Bitmap?) {
        AssistantScreenshot.put(screenshot)
    }

    override fun onDestroy() {
        AssistantScreenshot.clear()
        super.onDestroy()
    }
}
