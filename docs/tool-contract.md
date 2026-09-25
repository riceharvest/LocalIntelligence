# Tool contract (FROZEN)

The interfaces in this document are **frozen**. Implement them; do not redesign them.
If one looks wrong, open an issue. A change to anything here needs sign-off because
every workstream compiles against it.

The canonical definitions live in `core/src/main/kotlin/dev/LocalIntelligence/core/`. This
document is the human-readable contract; the code is the enforcement.

---

## AgentTool

```kotlin
interface AgentTool {
    val definition: ToolDefinition
    suspend fun execute(args: JsonObject, context: ToolContext): ToolResult
}
```

### Rules for implementers

1. **Never throw for an expected failure.** Permission denied, not found, no network,
   bad input from the model: all of these are `ToolResult(success = false, ...)` with a
   typed `ToolError`. An exception that escapes `execute` is a bug, and it will take
   down an agent step that should have recovered.
2. **Coerce arguments defensively.** Models emit `"8"` for an Int, `null` for a
   String, and a number for a boolean. Clamp, default, and coerce before use.
3. **Never trust `args`.** `ToolCallValidator` checks shape, not semantics. Range,
   length, and existence checks belong in your tool.
4. **Keep the observation short.** 2048 chars, hard.
5. **Check `context.signal`** in any loop or long read. Cancellation must be prompt.
6. **Do not block the caller's thread.** `execute` is `suspend`; use withContext
   where the platform API is blocking.

---

## ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,            // "calendar.search"  — dotted, lowercase, verb.noun
    val description: String,     // ONE sentence, imperative, what it returns
    val category: String,        // "calendar" — matches the file/package grouping
    val schema: JsonObject,      // JSON Schema, object type, "properties" + "required"
    val risk: ToolRisk,
    val tags: Set<String>,       // extra retrieval keywords, lowercase
    val requiredPermission: String?,  // documentation + UI only
)
```

`schema` must be a valid JSON Schema object schema. It is used for three things:
argument validation, the generation grammar, and the UI's confirmation dialog.
`ToolCallValidator` currently checks unknown top-level keys against
`schema["properties"]`; keep that shape.

---

## ToolResult

```kotlin
data class ToolResult(
    val success: Boolean,
    val observation: String,   // THE MODEL SEES ONLY THIS
    val data: JsonObject? = null,   // UI + database only
    val error: ToolError? = null,
)
```

### The observation rule

> `observation` is the only thing fed back to the LLM.

It must be:

- plain text, no JSON blobs, no `toString()` of platform objects
- a statement of what was found, in a form the model can act on
- explicit about emptiness: `"No contacts matched 'dario'."` beats `"[]"`
- explicit about failure: name the failure and what would fix it

```kotlin
// good
"1 match: Dario Jansen, +31612345678"

// bad
"[{\"name\":\"Dario Jansen\",\"phone\":\"+31612345678\"}]"

// worse
"java.lang.SecurityException: READ_CONTACTS permission not granted..."
```

`data` exists for the trace UI and the database. The model never sees it.

---

## ToolError

```kotlin
sealed class ToolError(val code: String, open val message: String) {
    InvalidArguments  // "invalid_arguments"
    PermissionDenied  // "permission_denied"
    NotFound          // "not_found"
    Unavailable       // "unavailable"  — provider/API temporarily absent
    Timeout           // "timeout"
    Cancelled         // "cancelled"
    Internal          // "internal"     — a bug; include a short reference, not a stack trace
}
```

`PermissionDenied` and `NotFound` are the two the model must handle gracefully.
Retry-until-battery-death on either is a loop-detector failure, so make the
observation say what happened and what to do instead.

---

## ToolContext

```kotlin
data class ToolContext(
    val permissionGranted: Boolean = true,
    val userConfirmed: Boolean = false,
    val signal: CancellationSignal = CancellationSignal.None,
)
```

`:android` fills these. A tool with `permissionGranted == false` should return
`ToolError.PermissionDenied` without attempting the operation.

---

## ToolRisk

```kotlin
enum class ToolRisk {
    READ_ONLY,               // battery.read, contacts.search
    REVERSIBLE,              // clipboard.write, calendar.create, alarm.create
    EXTERNAL_COMMUNICATION,  // sms.send, email.send, web.post
    DESTRUCTIVE,             // file.delete, calendar.delete
    PRIVILEGED,              // disabled in v0
}
```

`requiresConfirmation` is **derived**: `DESTRUCTIVE || EXTERNAL_COMMUNICATION`.
It is not a field you set. The runtime decides, not the model, and not the tool.

---

## ToolRegistry / ToolSelector

```kotlin
interface ToolRegistry {
    fun all(): List<AgentTool>
    fun byName(name: String): AgentTool?
    fun byCategory(category: String): List<AgentTool>   // has a default impl
}

fun interface ToolSelector {
    fun select(task: String, sessionKeywords: List<String>,
               available: List<AgentTool>, maxTools: Int): List<AgentTool>
}
```

`SimpleToolRegistry` and `LexicalToolSelector` are the shipped implementations.
Do not add a DI framework around them. Constructor injection is the DI system.

---

## ModelBackend

```kotlin
interface ModelBackend {
    val id: String
    val capabilities: ModelCapabilities

    suspend fun load(model: ModelSpec)
    suspend fun generate(request: GenerationRequest): GenerationResult
    suspend fun unload()

    fun countTokens(text: String): Int    // best effort, used for budgeting
    fun cancel()                          // cooperative, safe when idle
}
```

`StreamingModelBackend` adds `generateStreaming(request, onToken)`.
`NoopModelBackend` is the test double.

No OpenAI-shaped types. No llama.cpp types. No LiteRT types. Inside this interface
lives only what both backends can honour. `GenerationRequest.messages` is
`List<ChatMessage>`, and `ChatMessage` is one of `System`, `User`, `Assistant`,
`ToolObservation`.

Backends must not throw on generation error — report via
`GenerationResult.stopReason`. The agent loop handles `StopReason` uniformly; a
backend that throws takes the loop with it.

---

## Adding a tool: the checklist

```
1. Pick the category. Match the package you are writing it in.
2. Name it verb.noun, lowercase, dotted.
3. One-sentence description starting with a verb, saying what it RETURNS.
4. Write a real JSON Schema with "properties" and "required".
5. Pick the risk honestly. If you are unsure, it is DESTRUCTIVE.
6. Add 4-8 retrieval tags: the words a user would actually type.
7. Implement execute with coercion, cancellation checks, and no escaping throws.
8. Write the observation by hand. Do not dump the data object.
9. Test: granted / denied / empty / success / invalid args / large result /
   API failure / cancellation / observation size.
```

Step 9 is not optional and it is not a rubber stamp. A tool with seven of those
cases covered is a tool that will page you at 2am on someone else's phone.
