package dev.localintelligence.core.model.token

/**
 * One measured sample: a string, and what three real BPE tokenizers charge
 * for it.
 *
 * @property label what the sample represents, so a failure names the class
 *   that regressed instead of an index.
 * @property qwen25 tokens under Qwen2.5-7B-Instruct. The primary target: a
 *   0.5B-4B on-device model in this family is what this harness is for.
 * @property qwen3 tokens under Qwen3-27B.
 * @property ling3 tokens under Ling-3.0-tiny-int4.
 */
data class Golden(
    val label: String,
    val text: String,
    val qwen25: Int,
    val qwen3: Int,
    val ling3: Int,
)

/**
 * The committed calibration set.
 *
 * ## Where these numbers come from
 *
 * Every count here was produced by running the real `tokenizer.json` from
 * `Qwen/Qwen2.5-7B-Instruct`, `Qwen/Qwen3-27B` and `inclusionAI/Ling-3.0-tiny-int4`
 * over the string. Not estimated, not rounded, not taken from documentation.
 *
 * The set is deliberately the text this product actually sends — a system
 * prompt, tool definition lines, memory lines, a tool observation, Kotlin,
 * Python, a JSON tool schema, an IP address, an ISO timestamp, indent runs,
 * four scripts, and emoji including ZWJ and skin-tone sequences — because an
 * estimator tuned on Wikipedia prose tells you nothing about a prompt that is
 * half tool schemas.
 *
 * ## Why it is committed
 *
 * Because it is the only thing stopping a future parameter tweak from making
 * the estimator worse while the tests still pass. A change to any constant in
 * `HeuristicTokenCounter.kt` that pushes aggregate error past the asserted
 * bound fails the build here, with a message naming the worst class.
 */
internal object CalibrationSet {

    /**
     * A deliberately non-Latin-heavy line: a model asked about a Japanese
     * calendar entry must not have that entry priced at English rates.
     */
    private const val JA_LONG =
        "関数型プログラミング言語の内で、全ての関数が参照透過性を持つような" +
            "ものを純粋関数型プログラミング言語という。"

    private const val ZH_LONG = "这是一个测试句子，用于测量中文分词的密度。"
    private const val KO_LONG =
        "과학기술은 사람의 필요에 따라 도구나 기계, 재료 등을 개발하고 사용하는 과정"
    private const val RU_LONG =
        "История Праистория и Античност Сред най-ранните човешки след"
    private const val KOTLIN_SNIPPET =
        "fun main() {\n    val list = listOf(1, 2, 3)\n" +
            "    for (i in list) {\n        println(\"i = \$i\")\n    }\n}"
    private const val PYTHON_SNIPPET =
        "def f(a, b):\n    return {'x': a + b, 'y': [1, 2, 3], 'ok': True}\n"
    private const val SCHEMA =
        """{"type":"object","properties":{"query":{"type":"string"},""" +
            """"limit":{"type":"integer"}},"required":["query"]}"""
    private const val MEMORY_BLOCK =
        "Remembered facts:\n- Home Assistant server is 192.168.1.20\n" +
            "- Dario prefers metric units"
    private const val OBSERVATION =
        "Found 3 events on 2026-09-26: standup 09:30, 1:1 with Dario 11:00, gym 18:15."

    val cases: List<Golden> = listOf(
        Golden("prose", "The quick brown fox jumps over the lazy dog.", 10, 10, 10),
        Golden(
            "system",
            "You operate this Android device on behalf of the user. " +
                "Use the available tools when required.",
            18, 18, 18,
        ),
        Golden(
            "system",
            "Never claim an action succeeded unless its tool result says it succeeded.",
            13, 13, 13,
        ),
        Golden(
            "tooldef",
            "- calendar.search: Search calendar events for a query and time range.",
            14, 14, 14,
        ),
        Golden(
            "tooldef",
            "- contacts.search: Look up a person by name and return their phone number.",
            16, 16, 16,
        ),
        Golden(
            "task",
            "Task: Find the PDF I downloaded yesterday and share it with Dario.",
            15, 15, 15,
        ),
        Golden("memories", MEMORY_BLOCK, 29, 30, 30),
        Golden("observation", OBSERVATION, 48, 48, 48),
        Golden("kotlin", KOTLIN_SNIPPET, 37, 42, 42),
        Golden("python", PYTHON_SNIPPET, 32, 34, 34),
        Golden("schema", SCHEMA, 24, 24, 27),
        Golden("ip", "192.168.1.20", 12, 12, 12),
        Golden("iso", "2026-09-26T10:30:00Z", 20, 20, 20),
        Golden("indent4", "    ", 1, 1, 1),
        Golden("indent8", "        ", 1, 1, 1),
        Golden("ja", "こんにちは世界", 2, 2, 6),
        Golden("ja-long", JA_LONG, 35, 26, 41),
        Golden("zh", ZH_LONG, 12, 11, 10),
        Golden("ko", KO_LONG, 25, 16, 23),
        Golden("ru", RU_LONG, 26, 20, 22),
        Golden("ru-short", "Привет мир", 4, 4, 6),
        Golden("ar", "مرحبا بالعالم", 5, 5, 6),
        Golden("el", "αθήνα καλημέρα", 13, 7, 8),
        Golden("emoji", "👍", 1, 3, 2),
        Golden("zwj-family", "👨‍👩‍👧‍👦", 10, 18, 11),
        Golden("zwj-skin", "👩🏽‍💻 coding", 6, 11, 8),
        Golden("emoji-run", "👍🎉😀🚀", 4, 12, 8),
        Golden("longword", "internationalization supercalifragilistic", 8, 8, 8),
        Golden("shortwords", "a b c d e f g h i j k l m n o p", 16, 16, 16),
        Golden("punct", "{}", 1, 1, 1),
        Golden("punct-run", "()[]{}", 3, 3, 3),
        Golden("newline", "\n", 1, 1, 1),
        Golden("letters20", "w".repeat(20), 10, 10, 10),
        Golden("digits13", "1234567890123", 13, 13, 13),
    )
}
