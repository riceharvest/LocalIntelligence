package dev.localintelligence.app

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.localintelligence.android.voice.WakeWordSpotter
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.voice.WakePhraseTokens
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Foreground microphone service that listens for the user's wake phrase using
 * the bundled `sherpa-onnx` Zipformer transducer KWS model.
 *
 * Audio is read at 16 kHz mono PCM, passed directly into [WakeWordSpotter] in
 * memory, and immediately discarded — nothing is written to disk or sent over
 * the network.
 *
 * Spotting pauses (releasing the [AudioRecord] so the microphone is free) while:
 *  - an assistant overlay or hands-free chat session is active,
 *  - a phone call is in progress, or
 *  - another app or component is recording audio.
 */
class WakeWordService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Default)
    private lateinit var container: AppContainer
    private lateinit var settings: SettingsStore
    private lateinit var audioManager: AudioManager
    private var listenJob: Job? = null
    private var foreground = false
    private var shownPhrase: String = ""

    private val otherRecorderActive = AtomicBoolean(false)
    @Volatile
    private var activeAudioSessionId: Int = NO_SESSION_ID

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val mySession = activeAudioSessionId
            val external = configs.any { it.clientAudioSessionId != mySession }
            otherRecorderActive.set(external)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as LocalIntelligenceApp).container
        settings = container.settings
        audioManager = getSystemService(AudioManager::class.java)
        createChannels(this)
        audioManager.registerAudioRecordingCallback(
            recordingCallback,
            Handler(Looper.getMainLooper()),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.set(CoreSettings.WAKE_WORD, false)
                cancelResumeAndDetectionNotifications(this)
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_DETECTION -> {
                awaitingSessionStartSinceMs = 0L
                if (!wanted(this, container)) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
            }
        }

        val phrase = activePhrase(container)
        if (!startForegroundNow(phrase)) {
            postResumeNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        cancelResumeNotification(this)

        if (!wanted(this, container)) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        if (listenJob?.isActive != true) {
            listenJob = scope.launch { listenLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        listenJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun listenLoop() {
        val spotter = try {
            WakeWordSpotter(applicationContext, activePhrase(container))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stopForegroundAndSelf()
            return
        }

        val pcmBuffer = ShortArray(FRAME_SAMPLES)
        try {
            while (coroutineContext.isActive && wanted(this@WakeWordService, container)) {
                val phrase = activePhrase(container)
                spotter.updatePhrase(phrase)
                if (phrase != shownPhrase && foreground) {
                    updateListeningNotification(phrase)
                }

                if (shouldPause(NO_SESSION_ID)) {
                    delay(PAUSE_POLL_MS)
                    continue
                }

                val record = createAudioRecord()
                if (record == null) {
                    delay(RETRY_OPEN_MS)
                    continue
                }

                try {
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        delay(RETRY_OPEN_MS)
                        continue
                    }
                    val mySessionId = record.audioSessionId
                    activeAudioSessionId = mySessionId
                    otherRecorderActive.set(hasExternalRecording(mySessionId))
                    spotter.reset()

                    while (coroutineContext.isActive && wanted(this@WakeWordService, container)) {
                        val currentPhrase = activePhrase(container)
                        if (currentPhrase != shownPhrase) {
                            spotter.updatePhrase(currentPhrase)
                            if (foreground) updateListeningNotification(currentPhrase)
                        }
                        if (shouldPause(mySessionId)) break

                        val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                        if (read < 0) break
                        if (read == 0) continue
                        if (shouldPause(mySessionId)) break

                        if (spotter.acceptPcm16(pcmBuffer, read)) {
                            markDetectionPending()
                            // Release the microphone before starting the assistant session
                            // so SpeechRecognizer / VoiceClipRecorder can claim it immediately.
                            stopAndRelease(record)
                            activeAudioSessionId = NO_SESSION_ID
                            onWakePhraseDetected(currentPhrase)
                            break
                        }
                    }
                } finally {
                    activeAudioSessionId = NO_SESSION_ID
                    stopAndRelease(record)
                }
            }
        } finally {
            spotter.close()
        }
        stopForegroundAndSelf()
    }

    private fun onWakePhraseDetected(phrase: String) {
        val openedOverlay = isDefaultAssistant(this) &&
            AssistantInteractionService.showHandsFreeSession()
        if (!openedOverlay) {
            postDetectionNotification(this, phrase)
        }
    }

    private fun shouldPause(mySessionId: Int): Boolean {
        if (isSessionOrHandsFreePaused()) return true
        if (isCallActive()) return true
        if (mySessionId != NO_SESSION_ID && otherRecorderActive.get()) return true
        return hasExternalRecording(mySessionId)
    }

    private fun isCallActive(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == MODE_CALL_SCREENING
    }

    private fun hasExternalRecording(mySessionId: Int): Boolean {
        val configs = runCatching { audioManager.activeRecordingConfigurations }.getOrNull()
            ?: return false
        return configs.any { it.clientAudioSessionId != mySessionId }
    }

    private fun createAudioRecord(): AudioRecord? {
        if (!hasRecordPermission(this)) return null
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordSpotter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSizeInBytes = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WakeWordSpotter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun stopAndRelease(record: AudioRecord) {
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        runCatching { record.release() }
    }

    private fun startForegroundNow(phrase: String): Boolean {
        shownPhrase = phrase
        val notification = listeningNotification(phrase)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            }
            foreground = true
            true
        } catch (e: RuntimeException) {
            foreground = false
            false
        }
    }

    private fun updateListeningNotification(phrase: String) {
        shownPhrase = phrase
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, listeningNotification(phrase))
    }

    private fun stopForegroundAndSelf() {
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun listeningNotification(phrase: String): Notification {
        val text = "Listening for “$phrase”"
        val openIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQ_STOP_SERVICE,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 47
        private const val RESUME_NOTIFICATION_ID = 48
        private const val DETECTED_NOTIFICATION_ID = 49

        private const val CHANNEL_LISTENING = "wake_word_listening"
        private const val CHANNEL_LISTENING_NAME = "Wake word listening"
        private const val CHANNEL_DETECTED = "wake_word_detected"
        private const val CHANNEL_DETECTED_NAME = "Wake word alerts"

        private const val REQ_OPEN_APP = 4701
        private const val REQ_STOP_SERVICE = 4702
        private const val REQ_RESUME_LISTENING = 4703
        private const val REQ_OPEN_HANDS_FREE = 4704
        private const val REQ_DISMISS_DETECTION = 4705

        const val ACTION_STOP = "dev.localintelligence.app.wakeword.STOP"
        const val ACTION_DISMISS_DETECTION = "dev.localintelligence.app.wakeword.DISMISS_DETECTION"

        private const val FRAME_SAMPLES = 1600 // 100 ms @ 16 kHz
        private const val PAUSE_POLL_MS = 250L
        private const val RETRY_OPEN_MS = 1_000L
        private const val DETECTION_HANDOFF_TIMEOUT_MS = 30_000L
        private const val NO_SESSION_ID = -1
        private const val MODE_CALL_SCREENING = 4

        @Volatile
        private var assistOverlayActive: Boolean = false

        @Volatile
        private var handsFreeRunActive: Boolean = false

        @Volatile
        private var awaitingSessionStartSinceMs: Long = 0L

        fun hasRecordPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        fun wanted(context: Context, container: AppContainer): Boolean =
            container.settings[CoreSettings.WAKE_WORD] && hasRecordPermission(context)

        fun activePhrase(container: AppContainer): String =
            WakePhraseTokens.effectivePhrase(
                configuredPhrase = container.settings[CoreSettings.WAKE_PHRASE],
                assistantName = container.settings[CoreSettings.ASSISTANT_NAME],
            )

        fun onAssistOverlayActive(active: Boolean) {
            assistOverlayActive = active
            if (active) {
                awaitingSessionStartSinceMs = 0L
            }
        }

        fun onHandsFreeActive(active: Boolean, context: Context? = null) {
            handsFreeRunActive = active
            if (active) {
                awaitingSessionStartSinceMs = 0L
                if (context != null) cancelDetectionNotification(context)
            }
        }

        private fun markDetectionPending() {
            awaitingSessionStartSinceMs = SystemClock.elapsedRealtime()
        }

        private fun isSessionOrHandsFreePaused(): Boolean {
            if (assistOverlayActive || handsFreeRunActive) return true
            val since = awaitingSessionStartSinceMs
            if (since == 0L) return false
            if (SystemClock.elapsedRealtime() - since < DETECTION_HANDOFF_TIMEOUT_MS) return true
            awaitingSessionStartSinceMs = 0L
            return false
        }

        /**
         * Starts or stops the foreground service to match current settings.
         */
        fun sync(context: Context, container: AppContainer) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WakeWordService::class.java)
            if (!wanted(appContext, container)) {
                appContext.stopService(intent)
                cancelResumeAndDetectionNotifications(appContext)
                return
            }
            // Android 14+ refuses a microphone foreground service created from the background,
            // and a service that was started but cannot call startForeground crashes the app.
            // So start only when a screen of ours is in front, or when the system bound our
            // VoiceInteractionService (that app is exempt); otherwise ask the user with a tap.
            if (!canStartMicrophoneServiceNow(appContext)) {
                postResumeNotification(appContext)
                return
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
                cancelResumeNotification(appContext)
            } catch (e: RuntimeException) {
                // Background start refused (for example on boot when not the default assistant).
                postResumeNotification(appContext)
            }
        }

        /**
         * Called after a reboot or package update:
         * - When the app is the default assistant, [AssistantInteractionService.onReady]
         *   starts the service directly.
         * - When the app is not the default assistant, Android 14+ forbids starting a
         *   `microphone` foreground service from `BOOT_COMPLETED`, so we post a
         *   `"Tap to resume listening"` notification instead.
         */
        private fun canStartMicrophoneServiceNow(context: Context): Boolean {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            val inFront = state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            return inFront || (isDefaultAssistant(context) && AssistantInteractionService.isReady())
        }

        fun onBootOrPackageReplaced(context: Context, container: AppContainer) {
            val appContext = context.applicationContext
            if (!wanted(appContext, container)) return
            if (isDefaultAssistant(appContext) && AssistantInteractionService.isReady()) {
                sync(appContext, container)
            } else if (!isDefaultAssistant(appContext)) {
                postResumeNotification(appContext)
            }
        }

        fun postResumeNotification(context: Context) {
            createChannels(context)
            val openIntent = PendingIntent.getActivity(
                context,
                REQ_RESUME_LISTENING,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DETECTED)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("Tap to resume listening")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openIntent)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(RESUME_NOTIFICATION_ID, notification)
        }

        private fun postDetectionNotification(context: Context, phrase: String) {
            createChannels(context)
            val openHandsFree = PendingIntent.getActivity(
                context,
                REQ_OPEN_HANDS_FREE,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_START_HANDS_FREE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val dismissIntent = PendingIntent.getService(
                context,
                REQ_DISMISS_DETECTION,
                Intent(context, WakeWordService::class.java).setAction(ACTION_DISMISS_DETECTION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DETECTED)
                .setContentTitle("Heard “$phrase”")
                .setContentText("Tap to open hands-free chat")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openHandsFree)
                .setDeleteIntent(dismissIntent)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(DETECTED_NOTIFICATION_ID, notification)
        }

        private fun cancelResumeNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(RESUME_NOTIFICATION_ID)
        }

        private fun cancelDetectionNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(DETECTED_NOTIFICATION_ID)
        }

        private fun cancelResumeAndDetectionNotifications(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(RESUME_NOTIFICATION_ID)
            nm.cancel(DETECTED_NOTIFICATION_ID)
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            val listening = NotificationChannel(
                CHANNEL_LISTENING,
                CHANNEL_LISTENING_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            val detected = NotificationChannel(
                CHANNEL_DETECTED,
                CHANNEL_DETECTED_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            )
            nm.createNotificationChannel(listening)
            nm.createNotificationChannel(detected)
        }
    }
}
