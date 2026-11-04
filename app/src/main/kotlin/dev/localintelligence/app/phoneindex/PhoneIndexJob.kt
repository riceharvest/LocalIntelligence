package dev.localintelligence.app.phoneindex

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import dev.localintelligence.android.phoneindex.PhoneIndexStore
import dev.localintelligence.android.phoneindex.phoneIndexEnabled
import dev.localintelligence.app.BuildConfig
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.phoneindex.PhoneIndexSource
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The charger refresh of Search my phone. Runs every six hours, and only while the phone is charging,
 * so reading texts, events and files never drains a battery.
 *
 * ## WHY JOBSCHEDULER, AND NOT THE APP'S OTHER BACKGROUND WORK
 *
 * The app has no charger-aware background work: scheduled tasks use AlarmManager (they must fire at
 * a time) and downloads use a foreground service (they must not stop). Neither waits for a charger.
 * JobScheduler is the platform API for "when charging, when there is time", and it needs no new
 * dependency. Notifications are not refreshed here: the listener records them as they arrive.
 *
 * Scheduled only while a source that this job reads is on. A job the system runs after the switches
 * go off reads nothing and stops.
 */
object PhoneIndexJob {

    /** Unique within this app's JobScheduler jobs. */
    private const val JOB_ID: Int = 4117
    private val PERIOD_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

    /** Schedules the refresh while a source it reads is on, and cancels it otherwise. Safe to call often. */
    fun sync(context: Context, settings: SettingsReader) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val wanted = phoneIndexEnabled(settings).any { it in READ_IN_FULL }
        if (!wanted) {
            scheduler.cancel(JOB_ID)
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, PhoneIndexJobService::class.java))
            .setRequiresCharging(true)
            .setPeriodic(PERIOD_MILLIS)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** The sources this job reads. Notifications and photo captions are not read here. */
    private val READ_IN_FULL: Set<PhoneIndexSource> = setOf(
        PhoneIndexSource.MESSAGES,
        PhoneIndexSource.CALENDAR,
        PhoneIndexSource.CONTACTS,
        PhoneIndexSource.FILES,
    )
}

/** Runs one refresh. Everything it reads comes from the settings at the moment it starts. */
class PhoneIndexJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as LocalIntelligenceApp).container
        running = scope.launch {
            var failed = false
            try {
                val settings = container.settings
                val folder = settings[CoreSettings.PHONE_INDEX_FILES_FOLDER].takeIf { it.isNotBlank() }?.let(Uri::parse)
                PhoneIndexStore.get(this@PhoneIndexJobService).refresh(
                    enabled = phoneIndexEnabled(settings),
                    folder = folder,
                    // Texts are for attended use in the Play build, so its background refresh leaves them out.
                    messagesAllowed = BuildConfig.FULL_BUILD,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "phone index refresh failed", e)
            } finally {
                jobFinished(params, failed)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    private companion object {
        const val TAG = "PhoneIndexJob"
    }
}
