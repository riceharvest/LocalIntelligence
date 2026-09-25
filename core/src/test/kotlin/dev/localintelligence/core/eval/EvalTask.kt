package dev.localintelligence.core.eval

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The seven categories from docs/evals.md, with the counts the spec pins. */
enum class TaskCategory(val slug: String, val expectedCount: Int) {
    SINGLE("single", 10),
    TWO("two", 10),
    MULTI("multi", 10),
    MEMORY("memory", 5),
    AMBIGUITY("ambiguity", 5),
    FAILURE("failure", 5),
    IMPOSSIBLE("impossible", 5),
}

/**
 * A semantic requirement on the final answer.
 *
 * NEVER assert on exact wording. A model that says "43%" and a model that says
 * "your battery is at 43 percent" are both correct; a harness that rejects one
 * of them is measuring string equality and calling it agent quality.
 */
sealed interface AnswerPredicate {
    fun satisfiedBy(answer: String): Boolean
    fun describe(): String

    /** The answer must be non-blank. */
    data object NonEmpty : AnswerPredicate {
        override fun satisfiedBy(answer: String) = answer.isNotBlank()
        override fun describe() = "answer is non-empty"
    }

    /** Mentions a number, allowing "43%", "43 percent", "43.0", "43". */
    data class MentionsNumber(val value: Long) : AnswerPredicate {
        override fun satisfiedBy(answer: String): Boolean {
            val digits = value.toString()
            // A standalone occurrence: not part of a longer number like 143.
            val pattern = Regex("(?<![\\d])$digits(?![\\d])")
            return pattern.containsMatchIn(answer)
        }
        override fun describe() = "mentions the number $value"
    }

    /** Mentions a wall-clock time in any of the common renderings. */
    data class MentionsTime(val hour: Int, val minute: Int, val ampm: Boolean = false) : AnswerPredicate {
        override fun satisfiedBy(answer: String): Boolean {
            val h24 = if (ampm && hour <= 12) hour + 12 else hour
            val h12 = if (h24 > 12) h24 - 12 else h24
            val mm = minute.toString().padStart(2, '0')
            val h = hour.toString().padStart(2, '0')
            val forms = listOf(
                "$h24:$mm", "$h:$mm", "${h12}:$mm", "$h12:$mm",
                "$h24.${minute.toString().padStart(2, '0')}",
            ) + if (ampm) listOf("${h12}pm", "${h12} pm", "$h12:$mm pm", "$h12:$mm pm") else emptyList()
            val lower = answer.lowercase()
            return forms.any { lower.contains(it.lowercase()) }
        }
        override fun describe() = "mentions the time %02d:%02d".format(hour, minute)
    }

    /** Mentions a date in any of the common renderings (2026-10-02, Oct 2, 2 Oct). */
    data class MentionsDate(val year: Int, val month: Int, val day: Int) : AnswerPredicate {
        override fun satisfiedBy(answer: String): Boolean {
            val lower = answer.lowercase()
            val monthName = MONTHS[month - 1]
            val monthAbbr = monthName.take(3)
            val forms = listOf(
                "%04d-%02d-%02d".format(year, month, day),
                "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}",
                "$monthName $day", "$monthName ${day}, $year", "$monthName ${day}th",
                "$monthName $day, $year", "$monthAbbr $day", "$monthAbbr ${day}, $year",
                "$day $monthName", "$day $monthName $year", "$day $monthAbbr",
            )
            return forms.any { lower.contains(it.lowercase()) }
        }
        override fun describe() = "mentions %04d-%02d-%02d".format(year, month, day)
    }

    /** Contains any one of [needles], case-insensitively. */
    data class ContainsAny(val needles: List<String>) : AnswerPredicate {
        override fun satisfiedBy(answer: String): Boolean {
            val lower = answer.lowercase()
            return needles.any { lower.contains(it.lowercase()) }
        }
        override fun describe() = "mentions any of ${needles.joinToString("|")}"
    }

    /** Contains none of [needles]. Used to catch hallucinated success. */
    data class ContainsNone(val needles: List<String>) : AnswerPredicate {
        override fun satisfiedBy(answer: String): Boolean {
            val lower = answer.lowercase()
            return needles.none { lower.contains(it.lowercase()) }
        }
        override fun describe() = "avoids ${needles.joinToString("|")}"
    }

    /** The answer asks the user something rather than guessing. */
    data object AsksClarification : AnswerPredicate {
        private val MARKERS = listOf("?", "which", "what do you mean", "did you mean", "clarify", "can you clarify")
        override fun satisfiedBy(answer: String): Boolean {
            val lower = answer.lowercase()
            return MARKERS.any { lower.contains(it) }
        }
        override fun describe() = "asks a clarifying question"
    }

    /** The answer states a capability limit instead of pretending. */
    data object ReportsLimitation : AnswerPredicate {
        // Deliberately broad. This predicate is about "the agent admitted a
        // limit instead of inventing success", not about any particular phrasing,
        // so it must accept every honest way of saying so: "can't", "couldn't",
        // "I'm unable", "that failed", "I don't have access to".
        private val MARKERS = listOf(
            "can't", "cannot", "cant", "couldn't", "could not", "can not",
            "not able", "unable", "no tool", "not available", "isn't available",
            "don't have", "do not have", "not supported", "not possible",
            "grant", "permission", "denied", "failed", "error", "refuse",
            "i don't want to guess", "not installed", "no network",
        )
        override fun satisfiedBy(answer: String): Boolean {
            val lower = answer.lowercase()
            return MARKERS.any { lower.contains(it) }
        }
        override fun describe() = "states a limitation instead of inventing success"
    }

    /** All of the sub-predicates hold. */
    data class All(val parts: List<AnswerPredicate>) : AnswerPredicate {
        override fun satisfiedBy(answer: String) = parts.all { it.satisfiedBy(answer) }
        override fun describe() = parts.joinToString(" and ") { it.describe() }
    }

    /** At least one sub-predicate holds. For "ask OR pick, but do not invent". */
    data class Any(val parts: List<AnswerPredicate>) : AnswerPredicate {
        override fun satisfiedBy(answer: String) = parts.any { it.satisfiedBy(answer) }
        override fun describe() = parts.joinToString(" or ") { it.describe() }
    }

    companion object {
        private val MONTHS = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
    }
}

/** One expected step in the tool trajectory. */
data class ExpectedCall(
    val tool: String,
    /** Subset of arguments that must be present and match. Order-insensitive. */
    val args: Map<String, String> = emptyMap(),
    /** Argument keys that must be absent. */
    val forbiddenArgs: Set<String> = emptySet(),
)

/** Ceilings a task places on the loop. These are the "unnecessary work" guards. */
data class TaskLimits(
    val maxSteps: Int = 8,
    val maxToolCalls: Int = 4,
    val maxDuplicateCalls: Int = 0,
    /** Tools the model must never call, e.g. a hallucinated "send.email". */
    val forbiddenTools: Set<String> = emptySet(),
    /** Argument keys the model must never send to the given tool. */
    val forbiddenArgs: Map<String, Set<String>> = emptyMap(),
)

/** A single evaluation task. Pure data — no behaviour, no I/O. */
data class EvalTask(
    val id: String,
    val category: TaskCategory,
    /** What the user says. */
    val utterance: String,
    /** Tool names the model is allowed to see. */
    val visibleTools: List<String>,
    /** The trajectory the model should produce, in order. */
    val expectedCalls: List<ExpectedCall>,
    /** The scripted model turns: the raw text the fake backend returns, in order. */
    val modelScript: List<String>,
    /** What must be true of the final answer. Semantic only. */
    val answerPredicates: List<AnswerPredicate> = listOf(AnswerPredicate.NonEmpty),
    val limits: TaskLimits = TaskLimits(),
    /** Assertions on the prompts the model received. */
    val promptAssertions: List<PromptAssertion> = emptyList(),
    /** Tool names that must never appear in the visible set (guards the fixture). */
    val requiresConfirmation: String? = null,
    // -- scripted observations ------------------------------------------------
    // A task overrides the default observation for a tool when the wording
    // matters (a specific meeting time, a specific NAS name). Without this the
    // library default would leak one fixture's answer into another task.
    /** tool name -> the observation that tool returns. */
    val observations: Map<String, String> = emptyMap(),
    /** Tools that return permission_denied no matter what. */
    val deniedTools: Set<String> = emptySet(),
    /** Tools that throw instead of returning. Tests the exception path. */
    val throwingTools: Set<String> = emptySet(),
    /** Tools that return "no results". A legitimate empty, not a failure. */
    val emptyTools: Set<String> = emptySet(),
    /** Tools that return an oversized observation, to exercise truncation. */
    val hugeTools: Set<String> = emptySet(),
) {
    val slug: String get() = "${category.slug}/$id"
}

/** An assertion about the prompt the model was shown. */
sealed interface PromptAssertion {
    fun holdsOn(prompt: String): Boolean
    fun describe(): String

    /** Some prompt in the run must contain this text. */
    data class AnyPromptContains(val fragment: String) : PromptAssertion {
        override fun holdsOn(prompt: String) = prompt.contains(fragment, ignoreCase = true)
        override fun describe() = "some prompt contains \"$fragment\""
    }

    /** The first prompt must contain this text. */
    data class FirstPromptContains(val fragment: String) : PromptAssertion {
        override fun holdsOn(prompt: String) = prompt.contains(fragment, ignoreCase = true)
        override fun describe() = "first prompt contains \"$fragment\""
    }

    /** No prompt may contain this text — catches a task leaking its own answer. */
    data class NoPromptContains(val fragment: String) : PromptAssertion {
        override fun holdsOn(prompt: String) = !prompt.contains(fragment, ignoreCase = true)
        override fun describe() = "no prompt contains \"$fragment\""
    }
}

// ---------------------------------------------------------------------------
// Argument builders
// ---------------------------------------------------------------------------

/** `{"query": "alice"}` */
fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit // omit
            is String -> put(key, JsonPrimitive(value))
            is Int -> put(key, JsonPrimitive(value))
            is Long -> put(key, JsonPrimitive(value))
            is Double -> put(key, JsonPrimitive(value))
            is Boolean -> put(key, JsonPrimitive(value))
            else -> put(key, JsonPrimitive(value.toString()))
        }
    }
}

fun expected(tool: String, vararg argPairs: Pair<String, Any?>): ExpectedCall =
    ExpectedCall(
        tool = tool,
        args = argPairs.mapNotNull { (k, v) -> if (v == null) null else k to v.toString() }.toMap(),
    )
