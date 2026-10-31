package dev.localintelligence.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.android.cloud.generatedMediaDir
import dev.localintelligence.app.export.ChatExporter
import dev.localintelligence.app.media.LiveDictation
import dev.localintelligence.app.media.encodePickedPicture
import dev.localintelligence.app.settings.SimpleSetup
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.app.ui.ChatGptModelCard
import dev.localintelligence.app.ui.ChatGptSettingsCard
import dev.localintelligence.app.ui.ChatMessage
import dev.localintelligence.app.ui.ChatScreen
import dev.localintelligence.app.ui.ChatViewModel
import dev.localintelligence.app.ui.DocumentsScreen
import dev.localintelligence.app.ui.DownloadedModelRegistrar
import dev.localintelligence.app.ui.HubScreen
import dev.localintelligence.app.ui.HubViewModel
import dev.localintelligence.app.ui.LocalIntelligenceTheme
import dev.localintelligence.app.ui.ModelManagerScreen
import dev.localintelligence.app.ui.ScriptPluginsScreen
import dev.localintelligence.app.ui.SettingsScreen
import dev.localintelligence.app.ui.SkillsScreen
import dev.localintelligence.app.ui.TraceScreen
import dev.localintelligence.app.ui.components.LocalTechnicalDetail
import dev.localintelligence.app.ui.trace.DiagnosticsScreen
import dev.localintelligence.app.ui.trace.DiagnosticsViewModel
import dev.localintelligence.app.ui.trace.RedactionScreen
import dev.localintelligence.app.ui.trace.ScheduleScreen
import dev.localintelligence.app.ui.trace.ScheduleViewModel
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import kotlinx.coroutines.CancellationException
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
 * Note what is *not* here: no permission requests on launch, which are gated on
 * a tool actually being invoked ([dev.localintelligence.app.ui.PermissionScreen]).
 * The one model load at launch is the saved one, restored so the chat works
 * without a first send. Any other load waits for a run or an explicit user action
 * ([dev.localintelligence.app.ui.ModelManagerScreen]).
 */
class MainActivity : ComponentActivity() {

    private var deliveredIntent by mutableStateOf<Intent?>(null)

    /**
     * The closest thing to app exit that Android gives a screen. Only a real
     * finish releases the text-to-speech engine; a rotation is not a finish.
     */
    override fun onDestroy() {
        if (isFinishing) (application as LocalIntelligenceApp).container.speaker.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            deliveredIntent = intent
        }
        val container = (application as LocalIntelligenceApp).container

        setContent {
            LocalIntelligenceTheme {
                val nav = rememberNavController()
                // The gateway is the screen's whole handle on the agent. It holds
                // no state of its own — it reads the process-scoped RunSinks the
                // service writes to — so a rotation cannot desync UI from run.
                val gateway = remember { ServiceAgentGateway(applicationContext, container.runSinks) }
                val chat: ChatViewModel = viewModel(
                    factory = remember(gateway, container) {
                        ChatViewModel.Factory(
                            gateway = gateway,
                            modelAvailability = container.modelAvailability,
                            attachmentQueue = container.pendingAttachments,
                            capabilities = { container.residentBackend().capabilities },
                            speaker = container.speaker,
                            readRepliesAloud = { container.settings[CoreSettings.READ_REPLIES_ALOUD] },
                            dictation = LiveDictation(applicationContext),
                            handsFreeSilence = { container.settings[CoreSettings.HANDS_FREE_SILENCE] },
                            // Picked pictures are read again from their URIs after process death.
                            readPicture = { encodePickedPicture(applicationContext.contentResolver, Uri.parse(it)) },
                        )
                    },
                )
                // Survives rotation: held in the application-scoped AppContainer.
                // The model list is a UI concern: `:android` has no model table,
                // and adding one is a schema change that belongs with the database,
                // not with this screen.
                val models = container.models
                val scope = rememberCoroutineScope()

                // What the app had at its last exit comes back before the chat is
                // read: the stored turns are shown, and the saved model is loaded so
                // the composer works without a first send. The work runs on the
                // container's scope, so a rotation cannot cancel a native load
                // halfway, and seeding does nothing on later compositions.
                LaunchedEffect(Unit) {
                    chat.seedRestored(container.launchRestore.await())
                }

                // A SEND intent arrives on cold start or warm via onNewIntent.
                // Keyed on deliveredIntent so new arrivals re-run, and consumed
                // once so recomposition or rotation does not repeat the action.
                LaunchedEffect(deliveredIntent) {
                    val incoming = deliveredIntent ?: return@LaunchedEffect
                    // Shared text goes into the message box and is never sent by
                    // itself. This activity is exported, so any app on the phone
                    // can start it with ACTION_SEND; the user has to tap Send (#150).
                    // Waits for the restore so a cold start does not race it.
                    sharedText(incoming)?.let { text ->
                        container.launchRestore.await()
                        chat.offerSharedText(text)
                    }
                    destination(incoming)?.let { nav.navigate(it) }
                    deliveredIntent = null
                }

                // One hub ViewModel per navigation, created here rather than
                // in the container, so popping the screen cancels its download
                // with its ViewModel scope instead of leaving it running.
                var hub: HubViewModel? = null

                NavHost(navController = nav, startDestination = ROUTE_CHAT) {
                    composable(ROUTE_CHAT) {
                        // The consent banner lives here rather than inside
                        // ChatScreen because the banner is a property of the
                        // *app* — it is about a background capability the whole
                        // assistant depends on, not about this one transcript.
                        // Observed, so switching Simple/Technical in Settings shows here at once.
                        val chatSettingsVersion by container.settings.changes.collectAsStateWithLifecycle()
                        val technical = remember(chatSettingsVersion) {
                            container.settings.mode == SettingsMode.TECHNICAL
                        }
                        // Simple mode's one-tap first run. Technical keeps the Hub card, so it gets no starter.
                        val starterState by container.starterSetup.state.collectAsStateWithLifecycle()
                        Column(modifier = Modifier.fillMaxSize()) {
                            NotificationAccessBanner()
                            CompositionLocalProvider(LocalTechnicalDetail provides technical) {
                            ChatScreen(
                                viewModel = chat,
                                onOpenModels = { nav.navigate(ROUTE_MODELS) },
                                onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                                modifier = Modifier.fillMaxSize(),
                                // Read here, where the screen is built, and passed down
                                // as a plain Boolean: the screen never touches prefs.
                                toolCallsAutoCollapse = container.settings[CoreSettings.TOOL_CALLS_AUTO_COLLAPSE],
                                // "Save as skill" writes the raw skill format, with tool ids in
                                // its steps: a Technical tool. Simple users write skills on the
                                // Skills screen's form.
                                skills = if (technical) container.skills else null,
                                assistantName = container.settings[CoreSettings.ASSISTANT_NAME],
                                suggestion = SimpleSetup.suggestedModel(container.deviceFacts),
                                onGetSuggested = {
                                    val pick = SimpleSetup.suggestedModel(container.deviceFacts)
                                    nav.navigate("$ROUTE_HUB?repo=${Uri.encode(pick.repo)}&file=${Uri.encode(pick.file)}")
                                },
                                starter = if (technical) null else starterState,
                                onStartStarter = { container.starterSetup.start() },
                                onCancelStarter = { container.starterSetup.cancel() },
                            )
                            }
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

                    composable(ROUTE_REDACTION) {
                        // Opened from Settings, under Diagnostics: it describes the
                        // filter, which is always on, so it does not need a place
                        // on the chat screen.
                        //
                        // WHY IT IS A TOP-LEVEL ROUTE RATHER THAN A DIALOG: the
                        // screen's content is a list of patterns and a list of
                        // things it does not cover. That is a document, it
                        // scrolls, and a dialog on a phone is the worst
                        // container for a scrolling document there is.
                        //
                        // WHY IT IS NOT GATED: there is nothing to unlock. The
                        // filter is always on, so the screen is a description,
                        // and a description that sometimes refuses to open is
                        // worse than one that is always there.
                        RedactionScreen(onBack = { nav.popBackStack() })
                    }

                    composable(ROUTE_MODELS) {
                        // The resident model's URI, so only its row offers the speed test.
                        val modelState by container.modelAvailability.state
                            .collectAsStateWithLifecycle()
                        // Re-read when a setting changes, so the OpenRouter entry appears
                        // once a key is saved and disappears when the plugin is switched off.
                        val settingsVersion by container.settings.changes.collectAsStateWithLifecycle()
                        val remoteModel = remember(settingsVersion) { container.remoteChatModelOrNull() }
                        val modelScope = rememberCoroutineScope()
                        ModelManagerScreen(
                            technical = remember(settingsVersion) { container.settings.mode == SettingsMode.TECHNICAL },
                            models = models,
                            contextLengthFor = container::contextLengthFor,
                            speedTest = container.speedTest,
                            loadedModelUri = if (modelState is ModelAvailability.Ready && !container.remoteChatActive && !container.chatGptActive) {
                                container.selectedModel?.uri?.toString()
                            } else {
                                null
                            },
                            remoteChatModel = remoteModel,
                            remoteChatActive = container.remoteChatActive,
                            onUseRemoteChat = {
                                withContext(Dispatchers.IO) { container.useRemoteChat() }
                            },
                            chatGptCard = { ChatGptModelCard(container) },
                            onUseModel = { model ->
                                withContext(Dispatchers.IO) { container.loadModel(model) }
                            },
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
                                val model = withContext(Dispatchers.IO) { container.importer.inspect(uri) }
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
                                withContext(Dispatchers.IO) {
                                    container.loadModel(model)
                                }
                            },
                            copyLiteRtLm = { uri, name, progress ->
                                // The copy is GBs of I/O: the copier runs on IO itself.
                                // The screen then imports the copy like a hub download.
                                dev.localintelligence.android.inference.LiteRtLmImportCopier(
                                    this@MainActivity.contentResolver,
                                    container.modelsDir,
                                ).copy(uri, name, progress)
                            },
                            onScanLocal = {
                                // Adopt GGUF files already in app storage. A model
                                // can be there because it was downloaded, pushed
                                // over adb, or restored from a backup, and none of
                                // those need the SAF picker — the app already owns
                                // the bytes, so it can just read the header.
                                val files = withContext(Dispatchers.IO) {
                                    container.modelsDir.listFiles()
                                        ?.filter { file ->
                                            file.isFile && (
                                                file.name.endsWith(".gguf", ignoreCase = true) ||
                                                    file.name.endsWith(".litertlm", ignoreCase = true)
                                                )
                                        }
                                } ?: emptyList()
                                var firstFailure: Throwable? = null
                                for (file in files) {
                                    val uri = android.net.Uri.fromFile(file)
                                    val model = try {
                                        withContext(Dispatchers.IO) { container.importer.inspect(uri) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        if (firstFailure == null) firstFailure = t
                                        continue
                                    }
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
                                    withContext(Dispatchers.IO) {
                                        container.loadModel(model)
                                    }
                                }
                                firstFailure?.let { throw it }
                            },
                            // Deletes the file when the app owns it, unloads the model if it is
                            // the one in use, and drops the saved selection; see removeModel.
                            onDelete = { model -> modelScope.launch { container.removeModel(model) } },
                            isOwnFile = { model -> container.isOwnModelFile(model) },
                            onAutoScan = { container.scanOwnModels() },
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
                            onOpenSelfCheck = { nav.navigate(ROUTE_SELF_CHECK) },
                        )
                    }

                    composable(ROUTE_SELF_CHECK) {
                        DiagnosticsScreen(
                            viewModel = viewModel<DiagnosticsViewModel>(),
                            onBack = { nav.popBackStack() },
                            onOpenModels = { nav.navigate(ROUTE_MODELS) },
                        )
                    }

                    // Plain "hub" opens empty. The Settings suggestion adds the
                    // repo and file, so the Hub opens with that file selected.
                    composable(
                        route = "$ROUTE_HUB?repo={repo}&file={file}",
                        arguments = listOf(
                            navArgument("repo") { nullable = true; defaultValue = null },
                            navArgument("file") { nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        // The registrar is what makes a download usable: the
                        // default adopts nothing, so the finished file would sit
                        // in files/models while the app still said "import a
                        // model". adoptDownloaded reads the header, loads it
                        // through the RAM gate, and returns a note the Hub screen
                        // shows - or null, which the screen surfaces as a
                        // failure rather than a success.
                        // Scoped to this back stack entry (not `remember`), so a rotation
                        // keeps the progress view of a transfer that is still running.
                        val vm: HubViewModel = viewModel(
                            viewModelStoreOwner = entry,
                            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                                    container.newHubViewModel(
                                        DownloadedModelRegistrar { file ->
                                            container.adoptDownloaded(file)
                                        },
                                    ) as T
                            },
                        )
                        hub = vm
                        val hubState by vm.state.collectAsStateWithLifecycle()
                        var confirmStop by remember { mutableStateOf(false) }
                        // Leaving mid-download would drop the progress view while the
                        // transfer went on unseen, so Back asks first.
                        val hubBack: () -> Unit = {
                            if (hubState.downloading) confirmStop = true else nav.popBackStack()
                        }
                        androidx.activity.compose.BackHandler(enabled = hubState.downloading) { confirmStop = true }
                        if (confirmStop) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { confirmStop = false },
                                title = { Text("Stop downloading?") },
                                text = { Text("What's downloaded so far is kept.") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmStop = false
                                        vm.cancelDownload()
                                        nav.popBackStack()
                                    }) { Text("Stop") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmStop = false }) { Text("Keep downloading") }
                                },
                            )
                        }
                        val repo = entry.arguments?.getString("repo")
                        val file = entry.arguments?.getString("file")
                        LaunchedEffect(repo, file) {
                            if (repo != null && file != null) vm.prefill(repo, file)
                        }
                        // Read so the screen follows a mode change made in Settings.
                        val hubSettingsVersion by container.settings.changes.collectAsStateWithLifecycle()
                        HubScreen(
                            viewModel = vm,
                            onBack = hubBack,
                            onStartChat = { nav.popBackStack(ROUTE_CHAT, inclusive = false) },
                            onOpenModels = {
                                nav.navigate(ROUTE_MODELS) { popUpTo(ROUTE_HUB) { inclusive = true } }
                            },
                            technical = remember(hubSettingsVersion) {
                                container.settings.mode == SettingsMode.TECHNICAL
                            },
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        val messages by chat.messages.collectAsStateWithLifecycle()
                        val settingsRunState by chat.runState.collectAsStateWithLifecycle()
                        val exportScope = rememberCoroutineScope()
                        val suggestedModel = SimpleSetup.suggestedModel(container.deviceFacts)
                        SettingsScreen(
                            store = container.settings,
                            plugins = container.plugins,
                            suggestion = suggestedModel,
                            suggestionInstalled = models.any { m -> isInstalledFile(m, suggestedModel) },
                            alternativeInstalled = suggestedModel.alternative?.let { alt ->
                                models.any { m -> isInstalledFile(m, alt) }
                            } ?: true,
                            onOpenHub = { repo, file ->
                                nav.navigate("$ROUTE_HUB?repo=${Uri.encode(repo)}&file=${Uri.encode(file)}")
                            },
                            onOpenSkills = { nav.navigate(ROUTE_SKILLS) },
                            speedTest = container.speedTest,
                            generatedMedia = generatedMediaDir(applicationContext),
                            onOpenDocuments = { nav.navigate(ROUTE_DOCUMENTS) },
                            onOpenScripts = { nav.navigate(ROUTE_SCRIPTS) },
                            chatGptRow = { ChatGptSettingsCard(container) },
                            onOpenModels = { nav.navigate(ROUTE_MODELS) },
                            onOpenSchedule = { nav.navigate(ROUTE_SCHEDULE) },
                            onOpenTrace = { nav.navigate(ROUTE_TRACE) },
                            onOpenRedaction = { nav.navigate(ROUTE_REDACTION) },
                            onOpenSelfCheck = { nav.navigate(ROUTE_SELF_CHECK) },
                            // Not offered mid-run: the conversation is being written to.
                            onNewChat = if (messages.isEmpty() || settingsRunState.isActive) {
                                null
                            } else {
                                {
                                    exportScope.launch {
                                        container.startNewConversation()
                                        chat.clear()
                                        nav.popBackStack(ROUTE_CHAT, inclusive = false)
                                    }
                                }
                            },
                            onExportChat = if (messages.isEmpty()) {
                                null
                            } else {
                                { format -> exportScope.launch { exportChat(messages, format) } }
                            },
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(ROUTE_SKILLS) {
                        SkillsScreen(
                            store = container.settings,
                            repository = container.skills,
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(ROUTE_SCRIPTS) {
                        ScriptPluginsScreen(
                            store = container.settings,
                            repository = container.scriptRepository,
                            runner = container.scriptRunner,
                            onBack = { nav.popBackStack() },
                        )
                    }

                    composable(ROUTE_DOCUMENTS) {
                        DocumentsScreen(
                            repository = container.documents,
                            embeddingModelReady = container.documentModelFile() != null,
                            onOpenHub = { repo, file ->
                                nav.navigate("$ROUTE_HUB?repo=${Uri.encode(repo)}&file=${Uri.encode(file)}")
                            },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    /** Writes the chat on IO and opens the share sheet; a failure is a toast, not a crash. */
    private suspend fun exportChat(messages: List<ChatMessage>, format: ChatExporter.Format) {
        try {
            val file = withContext(Dispatchers.IO) { ChatExporter.write(this@MainActivity, messages, format) }
            ChatExporter.share(this, file, format)
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
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
        setIntent(intent)
        deliveredIntent = intent
    }

    private companion object {
        const val ROUTE_CHAT = "chat"
        const val ROUTE_TRACE = "trace"
        const val ROUTE_MODELS = "models"
        const val ROUTE_HUB = "hub"
        const val ROUTE_SCHEDULE = "schedule"
        const val ROUTE_REDACTION = "redaction"
        const val ROUTE_SELF_CHECK = "selfcheck"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_SKILLS = "skills"
        const val ROUTE_SCRIPTS = "scripts"
        const val ROUTE_DOCUMENTS = "documents"

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
 * True when the imported model [m] is [suggested]. The file is matched by its Hub name and by
 * the local name it was downloaded under; the display name is the GGUF's own, which is rarely a
 * file name, so it is only a fallback for imports that were named after the file.
 */
private fun isInstalledFile(m: ImportedModel, suggested: SuggestedModel): Boolean =
    suggested.isFile(m.displayName) || suggested.isFile(m.uri.lastPathSegment?.substringAfterLast('/').orEmpty())

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
            // The app draws edge to edge, so the banner's colour goes under the
            // status bar and its text starts below it.
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
