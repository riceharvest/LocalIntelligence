# Wave 1 contract

Signatures that two or more wave-1 agents depend on. This file exists because agent A
writes the loop and agent B writes the tests for the loop, and they are running in
parallel in different worktrees. They cannot negotiate. So these signatures are fixed
here, and both implement against them.

If one of these looks wrong, say so in the PR description. Do not change it silently —
agent B will not see your change until integration, and the integration agent will
have two halves that do not compile.

Everything else in `docs/architecture.md` and `docs/tool-contract.md` is also binding.

---

## What already exists (do not recreate, do not modify)

```
core/src/main/kotlin/dev/LocalIntelligence/core/model/ModelBackend.kt
    ModelSpec, ModelCapabilities, ChatMessage, SamplingParams,
    GenerationRequest, GenerationResult, StopReason,
    ModelBackend, StreamingModelBackend, NoopModelBackend,
    ToolPayload, ToolArgs

core/src/main/kotlin/dev/LocalIntelligence/core/agent/AgentAction.kt
    AgentAction.Respond, AgentAction.CallTool, RawAction,
    ActionParseResult.Parsed, ActionParseResult.Malformed, ActionParser

core/src/main/kotlin/dev/LocalIntelligence/core/tool/AgentTool.kt
    AgentTool, ToolDefinition, ToolResult, ToolError, ToolContext,
    ToolRisk, CancellationSignal

core/src/main/kotlin/dev/LocalIntelligence/core/tool/ToolRegistry.kt
    ToolRegistry, SimpleToolRegistry, ToolSelector, LexicalToolSelector,
    ObservationTruncator, ToolCallValidator

core/src/main/kotlin/dev/LocalIntelligence/core/agent/LoopDetector.kt
    LoopDetector (check/recordResult/hasStalled/isUnavailable/reset/normalize)

core/src/main/kotlin/dev/LocalIntelligence/core/agent/MemoryStore.kt
    Memory, MemoryStore, SessionStore, InMemoryMemoryStore

core/src/main/kotlin/dev/LocalIntelligence/core/context/ContextBuilder.kt
    ContextBuilder, CompactedState, SystemPrompts
```

Read the actual files. This list is a map, not a substitute.

---

## Fixed: the agent loop (agent A owns it, agent B tests it)

```kotlin
package dev.LocalIntelligence.core.agent

data class AgentConfig(
    val maxSteps: Int = 8,
    val maxVisibleTools: Int = 6,
    val maxMalformedRetries: Int = 3,
    val workingTokenLimit: Int = 6000,
    val memoryResults: Int = 5,
    val observationBudgetChars: Int = ObservationTruncator.DEFAULT_BUDGET_CHARS,
)

sealed interface AgentResult {
    data class Success(val text: String, val trace: List<StepTrace>) : AgentResult
    data class AwaitingConfirmation(
        val toolName: String,
        val tool: AgentTool,
        val pendingArgs: JsonObject,
        val trace: List<StepTrace>,
    ) : AgentResult
    data class Stop(val reason: String, val trace: List<StepTrace>) : AgentResult
    data object StepLimitReached : AgentResult
    data object Cancelled : AgentResult
}

data class StepTrace(
    val step: Int,
    val kind: Kind,                       // GENERATION | TOOL_CALL | OBSERVATION | MALFORMED | COMPACTION
    val detail: String,
    val durationMs: Long = 0,
    val success: Boolean = true,
) { enum class Kind { GENERATION, TOOL_CALL, OBSERVATION, MALFORMED, COMPACTION } }

class AgentController(
    private val model: ModelBackend,
    private val parser: ActionParser,
    private val tools: ToolRegistry,
    private val toolSelector: ToolSelector,
    private val validator: ToolCallValidatorGate,
    private val loopDetector: LoopDetector,
    private val contextBuilder: ContextBuilder,
    private val memory: MemoryStore,
    private val sessions: Session,
    private val config: AgentConfig = AgentConfig(),
) {
    suspend fun run(task: String): AgentResult
    suspend fun confirmAndResume(approved: Boolean): AgentResult
    fun cancel()
}
```

`ToolCallValidatorGate` is a tiny fun interface in agent A's file so the loop does not
depend on the `ToolCallValidator` object's shape:

```kotlin
fun interface ToolCallValidatorGate {
    fun validate(
        name: String,
        args: JsonObject,
        visible: List<AgentTool>,
    ): ValidationOutcome
}

sealed interface ValidationOutcome {
    data class Ok(val tool: AgentTool) : ValidationOutcome
    data class Rejected(val observation: String) : ValidationOutcome
}
```

`Session` is agent A's mutable in-run state, not a persistence type:

```kotlin
class Session(val id: Long = 0L) {
    val messages: MutableList<ChatMessage>
    var workingSummary: CompactedState?
    fun observe(text: String)
    fun appendAssistant(text: String)
    fun currentKeywords(): List<String>
    fun tokens(model: ModelBackend): Int
    fun clear()
}
```

Agent B constructs `AgentController` with fakes and asserts on `AgentResult` and
`StepTrace`. Those two types are the entire observable surface of the loop.

---

## Fixed: tool selection output (agent A consumes, agent C produces)

Agent A calls `toolSelector.select(task, sessionKeywords, available, maxTools)` and
gets `List<AgentTool>`. That interface already exists and is frozen.

Agent C produces the grammar from the *visible* tool set:

```kotlin
package dev.LocalIntelligence.core.model

object GrammarBuilder {
    /** GBNF constraining output to Respond(text) or CallTool(name in [tools], args). */
    fun forActions(tools: List<ToolDefinition>): String

    /** A one-line human/model-readable hint naming the legal action shapes. */
    fun hint(tools: List<ToolDefinition>): String
}
```

Agent A calls `GrammarBuilder.hint(visibleTools)` to build the malformed-retry
observation. That is the only coupling between A and C.

---

## Fixed: context building (agent D owns it, agent A consumes it)

```kotlin
package dev.LocalIntelligence.core.context

class DefaultContextBuilder(
    private val systemPrompt: (List<ToolDefinition>) -> String = SystemPrompts::forTools,
    private val workingLimit: Int = 6000,
) : ContextBuilder {
    override fun build(
        task: String,
        history: List<ChatMessage>,
        memories: List<Memory>,
        tools: List<ToolDefinition>,
    ): List<ChatMessage>
}

class ContextCompactor(
    private val summarizer: SummaryWriter,     // interface, below
    private val workingLimit: Int = 6000,
    private val triggerFraction: Double = 0.65,
) {
    fun shouldCompact(activeTokens: Int, modelContext: Int): Boolean
    suspend fun compact(session: Session, model: ModelBackend): CompactedState
}

fun interface SummaryWriter {
    suspend fun summarize(state: CompactedState, model: ModelBackend): CompactedState
}
```

`Session` is agent A's type (above). If that creates a compile-order problem — agent D
needs `Session`, agent A needs `DefaultContextBuilder` — then **`Session` moves into
agent A's file and agent D's `compact` takes `(history, summary, model)` instead of a
`Session`.** Prefer that: fewer cross-agent type dependencies is strictly better.
Agent D must NOT define its own `Session`; if they collide, integration will pick A's.

---

## Build commands (verbatim — CI runs exactly these)

```bash
export JAVA_HOME=$HOME/jdk17
export ANDROID_HOME=$HOME/Android/Sdk

./gradlew :core:test          # the whole brain, JVM only, ~3s warm
./gradlew :app:assembleDebug  # APK, ~60s cold
```

`:core` must never import `android.*`. CI fails the build if it does. If your
verification suddenly needs an emulator, you have put a platform dependency in the
wrong module.
