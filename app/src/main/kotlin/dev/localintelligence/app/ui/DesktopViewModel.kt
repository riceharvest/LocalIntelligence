package dev.localintelligence.app.ui

import dev.localintelligence.android.data.DesktopDao
import dev.localintelligence.android.data.DesktopOutboxEntity
import dev.localintelligence.android.data.DesktopTextEntity
import dev.localintelligence.android.desktop.DesktopClient
import dev.localintelligence.android.desktop.DesktopException
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.desktop.DesktopAgent
import dev.localintelligence.core.desktop.DesktopKeys
import dev.localintelligence.core.desktop.DesktopRules
import dev.localintelligence.core.settings.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext

/** What the My desktop screen shows. Errors and notices are codes; the screen maps them to strings. */
data class DesktopUiState(
    val serveUrl: String = "",
    val agents: List<DesktopAgent>? = null,
    val online: Boolean = true,
    val error: DesktopError? = null,
    val notice: DesktopNotice? = null,
    val selected: DesktopAgent? = null,
    /** Text streamed from the desktop while the view is open. Null means no live text yet. */
    val liveText: String? = null,
    /** The last text saved on this phone, shown when the desktop is not reachable. */
    val cachedText: String? = null,
    /** The sequence number of the text the user is looking at. Sent with each key press. */
    val lastSeq: Long = 0L,
    val queued: Int = 0,
    /** Messages waiting for the selected agent, oldest first, shown under the view. */
    val queuedForSelected: List<String> = emptyList(),
)

enum class DesktopError { NOT_SET, INVALID_ADDRESS, UNREACHABLE, REFUSED, BLOCKED, STALE, NOT_FOUND, MESSAGE_INVALID, QUEUE_FULL }

enum class DesktopNotice { QUEUED, QUEUE_SENT }

/**
 * The My desktop screen's state and calls (full build only). A plain class rather than a ViewModel, so the
 * screen owns its scope and everything it started is cancelled when it leaves composition.
 *
 * Rules this class enforces, all of them stated in `core/.../desktop/DesktopLink.kt`:
 * - Messages are queued while the desktop is unreachable and sent in order; key presses are never queued.
 * - A key press is sent only while live text is showing, with the sequence number the user saw.
 * - Cached text is read-only: it never offers answers.
 */
class DesktopViewModel(
    private val settings: SettingsStore,
    private val dao: DesktopDao,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DesktopUiState(serveUrl = settings[DesktopSettings.SERVE_URL]))
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val outboxLock = Mutex()
    private var streamJob: Job? = null

    /** Loads the queue count and the agent list. Called once when the screen opens and on each refresh. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            val waiting = dao.outboxCount()
            update { it.copy(queued = waiting) }
            if (_state.value.serveUrl.isNotBlank()) refresh()
        }
    }

    fun saveAddress(raw: String) {
        val normalized = DesktopRules.normalizeServeUrl(raw)
        if (normalized == null) {
            update { it.copy(error = DesktopError.INVALID_ADDRESS) }
            return
        }
        settings.set(DesktopSettings.SERVE_URL, normalized)
        update { it.copy(serveUrl = normalized, error = null, agents = null) }
        refresh()
    }

    fun refresh() {
        val client = client() ?: run {
            update { it.copy(error = DesktopError.NOT_SET) }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val agents = client.agents()
                update { it.copy(agents = agents, online = true, error = null) }
                flushOutbox()
            } catch (e: DesktopException) {
                fail(e)
            }
        }
    }

    /** Opens an agent: shows the saved text at once, then streams the live text while the view is open. */
    fun open(agent: DesktopAgent) {
        streamJob?.cancel()
        update { it.copy(selected = agent, liveText = null, cachedText = null, lastSeq = agent.stateSeq, error = null) }
        streamJob = scope.launch(Dispatchers.IO) {
            val saved = dao.text(agent.target)?.text
            update { it.copy(cachedText = saved) }
            showQueued(agent.target)
            watch(agent)
        }
    }

    fun close() {
        streamJob?.cancel()
        streamJob = null
        update { it.copy(selected = null, liveText = null, cachedText = null, error = null) }
    }

    /** Sends a message to the selected agent, or queues it when the desktop does not answer. */
    fun send(text: String) {
        val target = _state.value.selected?.target ?: return
        if (!DesktopRules.validMessage(text)) {
            update { it.copy(error = DesktopError.MESSAGE_INVALID) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val client = client() ?: return@launch
            // Keep the order: anything already waiting goes first, and a new message joins the queue behind it.
            if (flushOutbox() > 0) {
                enqueue(target, text)
                return@launch
            }
            try {
                client.prompt(target, text)
                update { it.copy(online = true, error = null) }
            } catch (e: DesktopException) {
                if (e.unreachable) enqueue(target, text) else fail(e)
            }
        }
    }

    /** Sends one key. Refused unless live text is showing and the desktop answers. Keys are never queued. */
    fun key(key: String) {
        val s = _state.value
        val target = s.selected?.target ?: return
        if (key !in DesktopKeys.ALL || !DesktopRules.keysAllowed(s.online, s.liveText != null)) {
            update { it.copy(error = DesktopError.UNREACHABLE) }
            return
        }
        val client = client() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                client.keys(target, listOf(key), s.lastSeq)
                update { it.copy(error = null) }
            } catch (e: DesktopException) {
                fail(e)
            }
        }
    }

    /** Called when the phone gets a network back. Sends what was waiting. */
    fun onNetworkAvailable() {
        scope.launch(Dispatchers.IO) { if (dao.outboxCount() > 0) flushOutbox() }
    }

    private suspend fun watch(agent: DesktopAgent) {
        val client = client() ?: return
        val running = coroutineContext.job
        var failures = 0
        while (running.isActive && failures < MAX_STREAM_FAILURES) {
            try {
                client.stream(agent.target, keepGoing = { running.isActive }) { text ->
                    update { it.copy(liveText = text.text, lastSeq = text.stateSeq, online = true, error = null) }
                    scope.launch(Dispatchers.IO) {
                        dao.putText(DesktopTextEntity(agent.target, text.text, text.stateSeq, System.currentTimeMillis()))
                    }
                }
                failures = 0
            } catch (e: DesktopException) {
                failures++
                // Keep the last live text as the saved text, so the view still shows what the user was reading.
                update { it.copy(online = false, cachedText = it.liveText ?: it.cachedText, liveText = null) }
                if (e.code != "unreachable") fail(e)
                delay(STREAM_RETRY_MS)
            }
        }
    }

    /**
     * Sends waiting messages in order. Stops at the first one the desktop cannot take right now, so nothing
     * is skipped. Returns how many are still waiting.
     */
    private suspend fun flushOutbox(): Int = outboxLock.withLock {
        val client = client() ?: return@withLock dao.outboxCount()
        var sent = 0
        for (item in dao.outbox()) {
            try {
                client.prompt(item.target, item.text)
                dao.dequeue(item.id)
                sent++
            } catch (e: DesktopException) {
                if (e.unreachable) break
                // Refused for good (the agent is blocked or gone). Drop it and say so, rather than sending it later.
                dao.dequeue(item.id)
                fail(e)
            }
        }
        val remaining = dao.outboxCount()
        update {
            it.copy(
                queued = remaining,
                online = if (sent > 0) true else it.online,
                notice = if (sent > 0) DesktopNotice.QUEUE_SENT else it.notice,
            )
        }
        _state.value.selected?.let { showQueued(it.target) }
        remaining
    }

    private suspend fun showQueued(target: String) {
        val texts = dao.outboxFor(target).map { it.text }
        update { if (it.selected?.target == target) it.copy(queuedForSelected = texts) else it }
    }

    private suspend fun enqueue(target: String, text: String) {
        if (!DesktopRules.canQueue(dao.outboxCount())) {
            update { it.copy(online = false, error = DesktopError.QUEUE_FULL) }
            return
        }
        dao.enqueue(DesktopOutboxEntity(target = target, text = text, createdAt = System.currentTimeMillis()))
        val queued = dao.outboxCount()
        update { it.copy(online = false, queued = queued, notice = DesktopNotice.QUEUED, error = null) }
        showQueued(target)
    }

    private fun fail(e: DesktopException) {
        if (e.unreachable) {
            update { it.copy(online = false, error = DesktopError.UNREACHABLE) }
            return
        }
        val code = when (e.code) {
            "agent_blocked" -> DesktopError.BLOCKED
            "stale" -> DesktopError.STALE
            "not_found" -> DesktopError.NOT_FOUND
            else -> DesktopError.REFUSED
        }
        update { it.copy(error = code) }
    }

    private fun client(): DesktopClient? {
        val url = _state.value.serveUrl
        return if (DesktopRules.normalizeServeUrl(url) == null) null else DesktopClient(url)
    }

    private fun update(block: (DesktopUiState) -> DesktopUiState) {
        _state.update(block)
    }

    private companion object {
        const val MAX_STREAM_FAILURES = 6
        const val STREAM_RETRY_MS = 5_000L
    }
}
