package dev.localintelligence.app

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognizer the system requires an assistant to name.
 *
 * A voice-interaction service must declare a `recognitionService`, and the
 * system binds whatever is named there as a [RecognitionService]. Naming the
 * session service instead would hand the system a class of the wrong type.
 * Speech input is not done through this path, so every request is answered
 * with an error immediately rather than left hanging.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
