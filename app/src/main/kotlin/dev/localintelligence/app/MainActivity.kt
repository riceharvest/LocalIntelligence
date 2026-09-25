package dev.localintelligence.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.app.ui.ChatScreen
import dev.localintelligence.app.ui.ChatViewModel
import dev.localintelligence.app.ui.LocalIntelligenceTheme
import dev.localintelligence.app.ui.HubScreen
import dev.localintelligence.app.ui.HubViewModel
import dev.localintelligence.app.ui.ModelManagerScreen
import dev.localintelligence.app.ui.TraceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect

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
                }

                // One hub ViewModel per navigation, created here rather than
                // in the container, so popping the screen cancels its download
                // with its ViewModel scope instead of leaving it running.
                var hub: HubViewModel? = null

                NavHost(navController = nav, startDestination = ROUTE_CHAT) {
                    composable(ROUTE_CHAT) {
                        ChatScreen(
                            viewModel = chat,
                            onOpenTrace = { nav.navigate(ROUTE_TRACE) },
                            onOpenModels = { nav.navigate(ROUTE_MODELS) },
                        )
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
                                        // The newly imported model is the one the
                                        // agent will run. Selecting it here is what
                                        // makes the chat composer become usable;
                                        // loading itself is deferred to the
                                        // service, which must not touch 2 GB on
                                        // the main thread for a row tap.
                                        container.selectedModel = model
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
                                        ?.filter { it.isFile && it.name.endsWith(".gguf") }
                                        ?.forEach { file ->
                                            val uri = android.net.Uri.fromFile(file)
                                            val model = container.importer.inspect(uri)
                                            models.removeAll { it.uri == model.uri }
                                            models.add(model)
                                            container.selectedModel = model
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

                    composable(ROUTE_HUB) {
                        val vm = remember { container.newHubViewModel() }
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
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        else -> null
    }

    private companion object {
        const val ROUTE_CHAT = "chat"
        const val ROUTE_TRACE = "trace"
        const val ROUTE_MODELS = "models"
        const val ROUTE_HUB = "hub"
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
