package dev.localintelligence.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.app.ui.ChatScreen
import dev.localintelligence.app.ui.ChatViewModel
import dev.localintelligence.app.ui.LocalIntelligenceTheme
import dev.localintelligence.app.ui.HubScreen
import dev.localintelligence.app.ui.HubViewModel
import dev.localintelligence.app.ui.DownloadedModelRegistrar
import dev.localintelligence.app.ui.ModelManagerScreen
import dev.localintelligence.app.ui.TraceScreen
import dev.localintelligence.app.ui.components.ChatModelIdentity
import dev.localintelligence.app.ui.trace.ScheduleScreen
import dev.localintelligence.app.ui.trace.ScheduleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The nav host and the composition root. Two responsibilities, both unavoidable
 * at this level:
 *
 *  1. **Composition root.** The [AppContainer] arrives as a constructor
 *     parameter and everything below it is passed down explicitly. There is no
 *     service locator, so a screen cannot acquire a dependency that is missing
 *     from its signature — which is what makes the `:app` graph readable.
 *  2. **The SEND entry point.** The manifest already declares an `ACTION_SEND`
 *     filter ("share this to LocalIntelligence"), and an intent filter that goes
 *     nowhere is a bug a user finds. A shared payload becomes the first task.
 *
 * Note what is *not* here: no permission requests on launch, and no model
 * loading. Both are gated on a tool actually being invoked
 * ([dev.localintelligence.app.ui.PermissionScreen]) or an explicit user action
 * ([dev.localintelligence.app.ui.ModelManagerScreen]).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LocalIntelligenceApp).container

        setContent {
            LocalIntelligenceTheme {
                val nav = rememberNavController()
                // The gateway is the screen's whole handle on the agent. It holds
                // no state of its own — it reads the process-scoped RunSinks the
                // service writes to — so a rotation cannot desync UI from run.
                val gateway = remember { ServiceAgentGateway(this, container.runSinks) }
                val chat: ChatViewModel = viewModel(
                    factory = remember(gateway, container) {
                        ChatViewModel.Factory(
                            gateway = gateway,
                            modelAvailability = container.modelAvailability,
                        )
                    },
                )

                // Survives rotation. The model list is a UI concern: `:android`
                // has no model table, and adding one is a schema change that
                // belongs to the Room workstream, not to this screen.
                val models = remember { mutableStateListOf<ImportedModel>() }
                val scope = rememberCoroutineScope()

                // A SEND intent arrives before the first frame on a cold start.
                // `LaunchedEffect(Unit)` makes this run exactly once after
                // composition rather than on every recomposition.
                LaunchedEffect(Unit) {
                    sharedText(intent)?.let { chat.send(it) }
                    // The launcher shortcut's destination. Also read on a warm
                    // launch via onNewIntent, so long-pressing the icon while
                    // the app is already open does not land the user back on
                    // the chat screen they left.
                    destination(intent)?.let { nav.navigate(it) }
                }

                // One hub ViewModel per navigation, created here rather than
                // in the container, so popping the screen cancels its download
                // with its ViewModel scope instead of leaving it running.
                var hub: HubViewModel? = null

                NavHost(navController = nav, startDestination = ROUTE_CHAT) {
                    composable(ROUTE_CHAT) {
                        // The same process-wide holder the service writes and the
                        // screen reads, collected here for one value only: the
                        // name of the model that is actually resident. Reading
                        // `container.selectedModel` instead would be a lie on
                        // a failed load — the model manager sets that field the
                        // moment a row is tapped, before anything is loaded, so
                        // it keeps naming the last-tried file rather than the
                        // one in memory.
                        val availability by container.modelAvailability.state
                            .collectAsStateWithLifecycle()
                        val resident = (availability as? ModelAvailability.Ready)?.let { ready ->
                            ChatModelIdentity(
                                displayName = ready.displayName,
                                quantType = ready.quantType,
                            )
                        }
                        // The consent banner lives here rather than inside
                        // ChatScreen because the chat screen belongs to the UI
                        // workstream, and because the banner is a property of the
                        // *app* — it is about a background capability the whole
                        // assistant depends on, not about this one transcript.
                        Column(modifier = Modifier.fillMaxSize()) {
                            NotificationAccessBanner()
                            ChatScreen(
                                viewModel = chat,
                                onOpenTrace = { nav.navigate(ROUTE_TRACE) },
                                onOpenModels = { nav.navigate(ROUTE_MODELS) },
                                modifier = Modifier.fillMaxSize(),
                                activeModel = resident,
                            )
                        }
                    }

                    composable(ROUTE_TRACE) {
                        // `runState` is forwarded so the trace's empty state can
                        // distinguish "nothing has run" from "the run you just
                        // watched ended without recording a step".
                        val traceState by chat.runState.collectAsStateWithLifecycle()
                        TraceScreen(
                            trace = chat.trace.value(),
                            runState = traceState,
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(ROUTE_MODELS) {
                        ModelManagerScreen(
                            models = models,
                            onImport = { uri ->
                                // Suspending off the composition: the header read
                                // is a few hundred bytes over SAF, which on a cloud
                                // provider is a network round trip and must not
                                // block a frame.
                                //
                                // Failures are rethrown rather than swallowed. The
                                // screen catches them and shows a banner; the
                                // previous `.onSuccess{}`-only chain dropped them,
                                // so a non-GGUF file produced no feedback at all.
                                withContext(Dispatchers.IO) { container.importer.inspect(uri) }
                                    .also { model ->
                                        models.removeAll { it.uri == model.uri }
                                        models.add(model)
                                        // NOT `selectedModel = model` here. It
                                        // used to be set on this line, one step
                                        // before the load, which meant a file
                                        // that then failed to load left the
                                        // selection naming a model the backend
                                        // never opened: the model screen showed
                                        // B as chosen while the chat was still
                                        // answering from A. "Would not load" and
                                        // "loaded something else" are different
                                        // problems and the two screens were
                                        // rendering them as one.
                                        //
                                        // `loadModel` assigns `selectedModel`
                                        // itself, after the backend has the
                                        // file, so dropping the line here makes
                                        // selection mean "resident" everywhere
                                        // it is read.
                                        //
                                        // It has to be LOADED here, not just
                                        // selected. ModelAvailability is
                                        // None|Ready|Failed and the chat composer
                                        // reads that holder, not selectedModel, so
                                        // setting the field alone left the chat
                                        // still saying "import a model" with a
                                        // perfectly good model on disk. The scan
                                        // path below already did this; import did
                                        // not, which is why picking a file from
                                        // Files did nothing visible.
                                        //
                                        // loadModel is suspend and does the native
                                        // load plus the RAM gate, so it is awaited
                                        // off the main thread here rather than
                                        // deferred to the service - and a model too
                                        // big for this device comes back as Failed
                                        // with the reason, which the screen shows,
                                        // instead of failing later as an OOM kill.
                                        container.loadModel(model)
                                    }
                            },
                            onScanLocal = {
                                // Adopt GGUF files already in app storage. A model
                                // can be there because it was downloaded, pushed
                                // over adb, or restored from a backup, and none of
                                // those need the SAF picker — the app already owns
                                // the bytes, so it can just read the header.
                                withContext(Dispatchers.IO) {
                                    container.modelsDir.listFiles()
                                        ?.filter { file ->
                                            file.isFile && (
                                                file.name.endsWith(".gguf", ignoreCase = true) ||
                                                    file.name.endsWith(".litertlm", ignoreCase = true)
                                                )
                                        }
                                        ?.forEach { file ->
                                            val uri = android.net.Uri.fromFile(file)
                                            val model = container.importer.inspect(uri)
                                            models.removeAll { it.uri == model.uri }
                                            models.add(model)
                                            // Same as onImport: no
                                            // `selectedModel` write before the
                                            // load. A scan that adopts ten
                                            // files would otherwise leave the
                                            // selection on the last one in
                                            // directory order whether or not
                                            // it loaded — and with a bad file
                                            // in that directory, on a model
                                            // that is not the one resident.
                                            // `loadModel` sets it on success.
                                            //
                                            // Load it for real rather than pretending
                                            // a "selected" state exists. ModelAvailability
                                            // only has None/Ready/Failed, and Ready
                                            // means RESIDENT, so anything less is a
                                            // lie — and the chat composer reads this
                                            // holder, so a model on disk that was never
                                            // loaded still reads as "import a model".
                                            // The load is 2 GB of native work, hence
                                            // the IO dispatcher and the honest failure
                                            // path: a bad model surfaces as Failed with
                                            // the reason, not as a silent no-op.
                                            container.loadModel(model)
                                        }
                                }
                            },
                            onDelete = { model ->
                                models.remove(model)
                                // Drop the selection too, or the chat keeps
                                // offering to send with a model the user has just
                                // told us to forget.
                                if (container.selectedModel?.uri == model.uri) {
                                    container.selectedModel = null
                                }
                            },
                            onOpenHub = { nav.navigate(ROUTE_HUB) },
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(ROUTE_SCHEDULE) {
                        // One ViewModel per navigation, created here rather than
                        // held in the container, so popping the screen cancels
                        // whatever it had in flight. The readiness half is read
                        // from the SAME ModelAvailability holder the chat screen
                        // reads: two independent readiness signals would give
                        // the user two different answers about one phone.
                        val vm: ScheduleViewModel = viewModel(
                            factory = remember(container) {
                                ScheduleViewModel.Factory(
                                    context = this@MainActivity,
                                    modelAvailability = { container.modelAvailability.current },
                                    hasSelectedModel = { container.selectedModel != null },
                                )
                            },
                        )
                        ScheduleScreen(
                            viewModel = vm,
                            onBack = { nav.popBackStack() },
                            onOpenModels = { nav.navigate(ROUTE_MODELS) },
                        )
                    }

                    composable(ROUTE_HUB) {
                        // The registrar is what makes a download usable: the
                        // default adopts nothing, so the finished file would sit
                        // in files/models while the app still said "import a
                        // model". adoptDownloaded reads the header, loads it
                        // through the RAM gate, and returns a note the Hub screen
                        // shows - or null, which the screen surfaces as a
                        // failure rather than a success.
                        val vm = remember {
                            container.newHubViewModel(
                                DownloadedModelRegistrar { file ->
                                    container.adoptDownloaded(file)
                                },
                            )
                        }
                        hub = vm
                        HubScreen(viewModel = vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    private fun sharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND ->
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }

        Intent.ACTION_PROCESS_TEXT ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString()

        else -> null
    }

    /**
     * The route a launcher shortcut asked for.
     *
     * Null for an unknown value rather than a default route. Silently opening
     * the chat screen for a shortcut pointing somewhere unknown is a feature
     * that looks broken, and the honest outcome — the app opens where it always
     * opens — is indistinguishable, so the check is the whole value here.
     *
     * A string rather than a typed constant because the value crosses a process
     * boundary (the launcher writes the extra, this process reads it) and a
     * `Parcelable` route object would need a serializable type for no gain.
     */
    private fun destination(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_DESTINATION)?.takeIf { it == DESTINATION_SCHEDULE }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // `setIntent` rather than leaving the Activity's own intent stale: the
        // LaunchedEffect above reads `intent`, the Activity property, and a new
        // delivery that is not recorded there is simply lost.
        setIntent(intent)
    }

    private companion object {
        const val ROUTE_CHAT = "chat"
        const val ROUTE_TRACE = "trace"
        const val ROUTE_MODELS = "models"
        const val ROUTE_HUB = "hub"
        const val ROUTE_SCHEDULE = "schedule"

        /** Written by the launcher shortcut in res/xml/shortcuts.xml. */
        const val EXTRA_DESTINATION = "dev.localintelligence.app.extra.DESTINATION"
        const val DESTINATION_SCHEDULE = ROUTE_SCHEDULE
    }
}

/**
 * Lifecycle-aware read of a [StateFlow].
 *
 * A named helper so no file reaches for the non-lifecycle-aware
 * `collectAsState` by accident — a backgrounded app recomposing a trace is a real
 * battery bug, and a one-character import difference is the whole defence.
 */
@Composable
private fun <T> StateFlow<T>.value(): T = collectAsStateWithLifecycle().value

/**
 * Asks for notification access, and only when it is actually worth asking.
 *
 * ## Why it is rendered here
 *
 * `notifications.list` and `notifications.reply` are real tools that reach a real
 * listener service, and both of them return an honest `PermissionDenied` when the
 * service is not bound. That honesty is necessary and not sufficient: a
 * permission error the user cannot act on is a dead end, and the only actor who
 * can resolve it is a human in Android Settings.
 *
 * So something in the app has to say "here is the switch". Before this, nothing
 * did — the tools described the settings path in prose to a model, and the model
 * would have had to relay it to a user who then had to know what to do with it.
 *
 * ## Why it is gated on BackgroundConsent.shouldAsk, not on the grant
 *
 * Its only condition used to be `if (granted) return`, which means every user
 * with the switch off saw this on every launch for as long as they left it off.
 * A fresh install is exactly that state, so the first thing the app said to
 * anyone was a request for a permission whose tools nothing had used yet — from
 * an app whose entire pitch is that nothing leaves the phone. It also had no
 * way to be silenced short of granting it.
 *
 * `BackgroundConsent.shouldAsk(context, BackgroundCapability.Notifications)`
 * is the rule the rest of the app already uses (docs/background.md §"About
 * consent"): offer it only when it is not granted, has not been dismissed, and
 * the user has recorded interest in background work by an explicit tap. On a
 * chat screen, with nothing scheduled, that is false — which is the correct
 * answer, not a lost prompt. The tools still explain the settings path in
 * prose when a run actually calls them, so the request is not lost, it is
 * asked at the moment it means something.
 *
 * ## Why both buttons dismiss
 *
 * Because the same rule applies to a deliberate act as to a refusal: a user who
 * taps "Turn on", changes their mind in Settings and comes back has already
 * answered, just in another process. Re-prompting them on the next launch would
 * read that as "I have never been asked", which is the bug this file is about.
 *
 * ## Why the state is re-read on resume
 *
 * The grant is made in another process. A value captured at composition is stale
 * the moment the user returns, and telling someone who just enabled it that it is
 * still off is the fastest way to make them stop trusting the app. The same tick
 * drives the dismissal read, because a dismissal written by this composable has
 * to take effect in the same frame or the banner stays up under the finger that
 * dismissed it.
 */
@Composable
private fun NotificationAccessBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The resume counter, and the cache key for every read below — the same
    // shape `BackgroundAccessScreen` uses, for the same reason.
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldAsk = remember(tick) {
        BackgroundConsent.shouldAsk(context, BackgroundCapability.Notifications)
    }
    if (!shouldAsk) return

    val dismiss: () -> Unit = {
        BackgroundConsent.dismiss(context, BackgroundCapability.Notifications)
        // Re-read under the new key, so the banner leaves now rather than at
        // the next resume.
        tick++
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Let the assistant read your notifications",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // "Not now" rather than a close icon: the target is a sentence a
            // user has to read to know what they are refusing, and the refusal
            // is about one capability, not about the whole app.
            TextButton(onClick = dismiss) {
                Text("Not now")
            }
            TextButton(
                onClick = {
                    dismiss()
                    openBackgroundAccess(context)
                },
            ) {
                Text("Turn on")
            }
        }
    }
}

/**
 * Opens the consent screen, falling back to a direct hop into Android Settings.
 *
 * The fallback matters: a button that silently does nothing when the Activity
 * cannot start is worse than no button, because the user has no way to tell the
 * difference between "broken" and "not yet configured".
 */
private fun openBackgroundAccess(context: Context) {
    val started = try {
        context.startActivity(Intent(context, BackgroundAccessActivity::class.java))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
    if (!started) {
        // Last resort: the system notification-access screen itself. The user
        // has to find this app in a list, which is why it is the fallback and
        // not the primary path.
        runCatching {
            LocalNotificationListenerService.requestConsent(context)
        }
    }
}
