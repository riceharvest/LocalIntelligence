package dev.localintelligence.core.tool.redaction

/**
 * Best-effort redaction of credential-shaped text.
 *
 * Pure Kotlin, stdlib only, no Android. It lives in `:core` because the thing
 * it protects is core's: a [dev.localintelligence.core.tool.ToolResult]
 * observation is the only text the agent loop hands to the model, and the loop
 * is `:core`.
 *
 * ## WHAT IT IS NOT
 *
 * It is not a guarantee, and it is not a security boundary. It is a set of
 * regexes. Anything a regex does not recognise passes through untouched,
 * including a password typed as prose ("the wifi code is hunter2" has no
 * keyword this file matches on some paths — see [SecretCategory] for what
 * each pattern actually requires). The screen that documents this filter
 * says so in the same words, because a screen that overclaims is worse than
 * no screen.
 *
 * ## FALSE-POSITIVE POLICY — read this before adding a pattern
 *
 * A false positive here is not a cosmetic problem. The redacted text is what
 * the MODEL reasons about, so eating ordinary text does not merely hide
 * something from the user; it hands the model corrupted input and the answer
 * becomes wrong in a way nobody can diagnose. Every pattern below therefore
 * satisfies at least one of:
 *
 *  1. **A structural marker.** JWT, PEM, `Authorization: Bearer`, `sk-…` —
 *     the shape itself is the evidence. These are safe to run on any text.
 *  2. **A keyword AND a shape.** `password = hunter2` needs both the word
 *     "password" and an 8+ character value. A password-shaped value with no
 *     keyword nearby is left alone, because a bare 12-character alnum string
 *     is what a SHA-1 fragment, a git short hash, and a base64 chunk all
 *     look like.
 *
 * Deliberately NOT detected, and this is the interesting part:
 *
 *  - **Bare OTP digits with no context.** "123456" on its own is a street
 *    number, a price, a timestamp fragment, a line of a diff. Only a digit
 *    run *next to a cue word* ("your code is 123456", "OTP: 123456") counts.
 *    This is the single most important false-positive decision in the file
 *    and it is what stops the redactor eating ordinary numbers. The cost is
 *    stated plainly: a user who copies a bare 6-digit code to the clipboard
 *    and asks "what does this say?" is NOT protected here. The keyword
 *    requirement is a deliberate trade of that case for not corrupting
 *    ordinary text.
 *  - **Sequences with separators** (`123-456-789`, `1234 5678 9012 3456`)
 *    are not treated as codes on shape alone for the same reason; a phone
 *    number and a card number look identical to a regex, and one of those
 *    being redacted in a bank SMS is a real annoyance.
 *  - **Named, vendor-specific key formats** beyond the one structural prefix
 *    that is unambiguous. A "known key formats" list is a maintenance
 *    liability and a false-positive source; the generic keyword+shape rule
 *    catches `ghp_…` in a pasted file anyway, because the shape is there.
 *
 * ## NO SECRET VALUES LIVE IN THIS FILE
 *
 * Every pattern below is a shape. There is no example, no doc sample, no
 * test fixture, and no comment containing a real token, key, or code — the
 * repository has no credentials in it and that has to stay true. The shapes
 * are written to be recognisable without a specimen.
 *
 * ## THE SEAM, AND WHY IT IS HERE
 *
 * This is applied to a tool RESULT as the observation is produced — the
 * moment bytes the user never typed become text the model will read. It is
 * deliberately NOT applied to the model's own output: rewriting generated
 * tokens mid-decode corrupts the answer and can break grammar-constrained
 * decoding, which is a strictly worse failure than showing a secret the
 * model was given. See `RedactingToolWrapper` for the mechanics and
 * `app/security/` for the wiring and the screen.
 */
object SecretRedactor {

    /**
     * Replaces every recognised secret in [text] with a category marker.
     *
     * Total, never throws, and never returns null for a null-ish input: a
     * redactor that can crash a tool call is worse than no redactor, because
     * the failure mode is an agent loop that cannot complete.
     *
     * @return the redacted text plus which categories fired, so a caller can
     *   tell the user *something* was removed without ever learning *what*.
     *
    * WHY [redact] SCANS THE ORIGINAL AND WRITES MARKERS INTO A SEPARATE BUFFER
    *
    * The obvious implementation rewrites the string and re-scans the result,
    * and it is wrong in a way that only shows up on the second call. A
    * replacement like `[one-time code redacted]` is itself matched by the
    * credential rule, because `OTP` — the cue that produced the marker — is
    * also a credential keyword, and the bare-value branch will happily read
    * the next nine characters (`[one-time`) as a password. Run the filter
    * twice on the same observation and `OTP: 8831` becomes
    * `OTP = [redacted] code redacted]`, which is both wrong and no longer
    * parseable by anything.
    *
    * That is necessary and NOT sufficient, and the difference is the whole
    * subtlety. Buffering stops a marker being re-matched *within* one call.
    * It does nothing about a second call, which starts from text that is
    * already marker-bearing — and the markers deliberately preserve the
    * context that produced them, because a model told "the key had a value
    * here" is more useful than one told nothing. `api_key = [redacted]` is
    * still `keyword <separator> value`, so the next pass eats its own output.
    *
    * The fixed point therefore needs both halves, and both are here:
    *
    *  1. **Within a call**, every rule searches the untouched `source` from
    *     an offset that only moves forward past a redacted span, so a marker
    *     is written once and never scanned.
    *  2. **Across calls**, the credential rule carries a negative lookahead
    *     for the exact marker strings ([MARKER_ALTERNATION]), and the OTP
    *     marker drops the cue word that would otherwise reproduce the
    *     `keyword <separator> value` shape.
    *
    * Together: `redact(redact(x)) == redact(x)`. That is load-bearing rather
    * than tidy, because the registry decorator filters and a caller above it
    * may filter again, and neither can know whether the other already did.
    *
    * The rejected alternative was excluding `[` and `]` from the value
    * character class. It was also a fixed point, and it was wrong: a real
    * password containing brackets stopped matching, so the filter grew a
    * blind spot exactly where the input was unusual. A lookahead against
    * whole markers leaves brackets legal in a value.
    */
    fun redact(text: String): RedactionResult {
        if (text.isEmpty()) return RedactionResult(text, emptySet(), 0)

        // Bounded. A clipboard or a file can be large, and a phone does not
        // need a 2 MB scan per observation. The cap is far above the
        // observation budget (2048 chars) that everything downstream clips to
        // anyway, so a truncated tail is a tail the model would never have
        // seen. Longest-first ordering is applied BEFORE the cap, so the
        // patterns that matter most are the ones that survive it.
        val source = if (text.length > MAX_SCAN_CHARS) text.take(MAX_SCAN_CHARS) else text

        val result = StringBuilder(source.length)
        // The half-open region of `source` not yet copied into `result`, and the
        // region of `result` that is not yet copied back into `source`. Redacted
        // span accumulates in the second and are copied out in the first, so a
        // marker is never itself a scan target. See the note on markers in the
        // KDoc above for why that is only half of the fixed-point property.
        var scanFrom = 0
        var writeBackFrom = 0
        val fired = LinkedHashSet<SecretCategory>()
        var count = 0

        for (rule in RULES) {
            // A local `found` per iteration rather than a `var` declared above
            // the loop: Kotlin will not smart-cast a captured `var` back to
            // non-null after the `while` assigns to it, and a do/while with a
            // nullable cursor needs the cast suppressed. Declaring it here
            // keeps the non-null type visible to the compiler with no assertion
            // in the source.
            var found = rule.pattern.find(source, scanFrom) ?: continue
            while (true) {
                result.append(source, writeBackFrom, found.range.first)
                result.append(rule.marker(found))
                fired += rule.category
                count++
                scanFrom = found.range.last + 1
                writeBackFrom = scanFrom
                if (source.length - scanFrom < MIN_SCAN_CHARS) break
                found = rule.pattern.find(source, scanFrom) ?: break
            }
        }
        // Whatever is left after the last redaction, verbatim.
        if (scanFrom < source.length) result.append(source, writeBackFrom, source.length)

        return RedactionResult(result.toString(), fired, count)
    }

    /** Convenience for callers that only want the text. */
    fun redactToText(text: String): String = redact(text).text

    /**
     * The categories this build actually detects.
     *
     * Read by the UI rather than hardcoded there, so a pattern added here
     * shows up on the screen without a second edit — and, more importantly, so
     * the screen cannot claim a category that is not implemented.
     */
    // A getter rather than a val initialiser: RULES is declared below this
    // property, and a property initialiser that reads a later-declared `val`
    // reads it before it is assigned. A computed property is immune to
    // declaration order and costs one map at call time, on a screen.
    val detectedCategories: List<SecretCategory>
        get() = RULES.map { it.category }.distinct()

    /**
     * How large a scan gets before it is cut short. Generous: the observation
     * budget downstream is 2048 characters, so anything past this was not
     * going to reach the model intact regardless.
     */
    const val MAX_SCAN_CHARS = 64_000

    /**
     * Below this there is nothing worth matching and the scan is skipped.
     */
    private const val MIN_SCAN_CHARS = 4

    // ------------------------------------------------------------------
    // The rules
    // ------------------------------------------------------------------

    /**
     * Every marker this object can emit, as a regex alternation.
     *
     * Read by the credential rule as a negative lookahead so a value that is
     * ALREADY a marker is not matched again. Two properties depend on it:
     *
     *  - **The filter is a fixed point.** `redact(redact(x)) == redact(x)`.
     *    Without this, the first pass turns `api_key` into
     *    `api_key = [redacted]`, and the second pass sees a keyword, a
     *    separator, and a ten-character value that is itself a marker — and
     *    redacts it again, producing `api_key = [redacted]` with the bracket
     *    eaten. The output stops being parseable and starts growing on every
     *    pass. That matters here specifically because the registry decorator
     *    filters, and a caller above it may filter again; neither can know
     *    whether the other already did.
     *  - **The lookahead is anchored to whole markers**, not to "contains a
     *    bracket". An earlier version excluded `[` and `]` from the value
     *    character class instead, which was stable and silently wrong: a real
     *    password containing brackets (`password=ab[cd]efghijk`) stopped
     *    matching, so the filter grew a blind spot precisely where the input
     *    was unusual. Brackets are legal in a value here, and this lookahead
     *    does not touch that.
     *
     * It is a literal list rather than a pattern like `\[\w+ redacted\]`
     * because a looser shape eventually matches a value a user actually chose,
     * which is the one failure this whole file is trying to avoid.
     */
    private const val MARKER_ALTERNATION =
        "\\[redacted\\]|\\[jwt redacted\\]|\\[private key redacted\\]|" +
            "\\[api key redacted\\]|\\[bearer token redacted\\]|" +
            "\\[basic token redacted\\]|\\[one-time code redacted\\]"

    private class Rule(
        val category: SecretCategory,
        val pattern: Regex,
        /**
         * Builds the replacement. Takes the match so a rule can preserve
         * structure — a PEM block keeps its BEGIN/END lines, because a model
         * told "there is a private key here" and a model told
         * "[private key redacted]" answer differently, and the second is a
         * worse answer.
         */
        val marker: (MatchResult) -> String,
    )

    /**
     * ORDER MATTERS: the first rule to match a span wins, because the loop
     * rewrites as it goes. The list is ordered most-structural-first, so a
     * PEM body containing something that looks like a JWT is redacted as a
     * private key rather than leaking a fragment the marker would have
     * preserved.
     */
    private val RULES: List<Rule> = listOf(
        // ---- 1. PEM private key blocks -------------------------------
        // Structural. The BEGIN/END arms are the evidence; there is no
        // keyword and no shape guess. Multi-line, so DOT_MATCHES_ALL.
        Rule(
            category = SecretCategory.PRIVATE_KEY,
            pattern = Regex(
                "-----BEGIN[ A-Z0-9]*PRIVATE KEY-----[\\s\\S]*?-----END[ A-Z0-9]*PRIVATE KEY-----",
            ),
            // The arms are kept: they tell the model a key was here, and they
            // leak nothing — the header names a format, not a key.
            marker = { "[private key redacted]" },
        ),

        // ---- 2. JSON Web Tokens --------------------------------------
        // Structural: three base64url segments, the first of which decodes
        // to a JSON object and therefore always begins `eyJ`. That `eyJ`
        // prefix is what makes this safe to run unconditioned — it is not a
        // guess about entropy, it is a fact about how a JWT header encodes.
        Rule(
            category = SecretCategory.JWT,
            pattern = Regex("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
            marker = { "[jwt redacted]" },
        ),

        // ---- 3. Authorization / Bearer headers -----------------------
        // Structural: the scheme word is the marker. Basic is included
        // because base64 of user:pass is exactly as bad as the bearer token
        // next to it, and it is the same keyword.
        Rule(
            category = SecretCategory.BEARER_TOKEN,
            pattern = Regex(
                "(?i)\\b(authorization\\s*[:=]\\s*)?\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}",
            ),
            // The scheme word survives so the model knows a header was there.
            // `m.groupValues[2]` is the scheme, matched case-insensitively.
            marker = { m -> "[${m.groupValues.getOrElse(2) { "auth" }.lowercase()} token redacted]" },
        ),

        // ---- 4. Vendor API key prefixes ------------------------------
        // Structural: the prefix is the evidence. Two prefixes only, both
        // long and both vendor-unique, and a word boundary after so a normal
        // hyphenated English word starting with the same letters is not
        // caught. The exact character counts below are the published FORMAT
        // lengths for these key types, not a value.
        Rule(
            category = SecretCategory.API_KEY,
            pattern = Regex("\\bsk-[A-Za-z0-9]{20,}\\b"),
            marker = { "[api key redacted]" },
        ),
        Rule(
            category = SecretCategory.API_KEY,
            pattern = Regex("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\\b"),
            marker = { "[api key redacted]" },
        ),

        // ---- 5. keyword + value --------------------------------------
        // The only non-structural rule, and the one that carries the whole
        // false-positive policy. Requires BOTH a credential-ish keyword and
        // a value of at least MIN_VALUE_CHARS that is not obviously prose.
        //
        // THE QUOTES ARE NOT COSMETIC. A JSON credential looks like
        // `{"api_key": "value"}` — the keyword is followed by a CLOSING quote
        // before the colon, so `keyword\s*[:=]` does not match it and the whole
        // category silently misses every credential in a config file, which is
        // the single most likely place one appears in a file the user hands the
        // agent. Hence the optional `["']?` after the keyword, and the three
        // value branches below (double-quoted, single-quoted, bare) rather
        // than one class that has to exclude quotes and so cannot match the
        // inside of one.
        Rule(
            category = SecretCategory.CREDENTIAL_ASSIGNMENT,
            pattern = Regex(
                "(?i)\\b(" +
                    "password|passwd|passphrase|pwd|" +
                    "secret|client_secret|api[_-]?key|apikey|access[_-]?key|" +
                    "auth[_-]?token|access[_-]?token|refresh[_-]?token|bearer[_-]?token|" +
                    "private[_-]?token|session[_-]?key|credential|" +
                    "otp|2fa|two[_-]?factor|verification[_-]?code|auth[_-]?code|pin" +
                ")\\b[\"']?\\s*[:=]\\s*(?!$MARKER_ALTERNATION)(?:" +
                    "\"([^\"\\s]{$MIN_VALUE_CHARS,})\"|" +
                    "'([^'\\s]{$MIN_VALUE_CHARS,})'|" +
                    "([^\\s,;\"']{$MIN_VALUE_CHARS,})" +
                ")",
            ),
            // The keyword is kept and the value goes. A model asked to
            // explain a config file still sees which key held a value, which
            // is usually the actual question.
            marker = { m -> "${m.groupValues[1]} = [redacted]" },
        ),

        // ---- 6. OTP next to a cue word --------------------------------
        // The narrow, deliberate case: digits are ONLY redacted when a cue
        // word sits immediately before them. See the false-positive policy —
        // this is the rule that could have eaten every number in a file, and
        // the context requirement is what stops it.
        //
        // The connective clause is load-bearing and was a real bug before it
        // was written. Without it, "your verification code IS 492817" does not
        // match: the alternation binds to "verification" first, the gap then
        // cannot reach across the word "code" to the digits, and a regex
        // engine reports the leftmost starting position only — it does not
        // retry the same anchor at the following cue word. So the most
        // ordinary phrasing of the thing this rule exists to catch was the
        // one phrasing it missed.
        //
        // The digit run is 4-8. Six is the common 2FA length, but 4-digit
        // PINs and 8-digit one-time codes both exist, and the cue word is
        // doing the work of making this safe.
        Rule(
            category = SecretCategory.OTP_CODE,
            pattern = Regex(
                "(?i)\\b(?:code|otp|passcode|pin|token|verification|verify|signin|sign-in|one-time|one time)\\b" +
                    "(?:\\s+(?:is|was|here)\\s*|[:=\\-]\\s*|[^0-9A-Za-z]{0,12})(\\d{4,8})\\b",
            ),
            // The whole match goes, cue word included. Keeping the cue was
            // wrong for a concrete reason: "OTP: [one-time code redacted]"
            // still reads as `keyword <separator> value`, because the
            // credential rule also lists OTP as a keyword and its bare-value
            // branch happily takes `[one-time` for a password. Dropping the
            // cue removes the shape that caused the re-match instead of
            // trying to blacklist the marker text.
            marker = { "[one-time code redacted]" },
        ),
    )

    /**
     * Minimum length of a value for rule 5 to fire.
     *
     * Eight characters, which is where `password=x` stops being a config
     * placeholder (`password=changeme` is nine, so it IS caught, and that is
     * correct — `changeme` in a file is a real password-shaped thing to warn
     * about) and where short values like `pin=1` or `otp=` stop being
     * redacted at all. A keyword-plus-empty-value is a config line, not a
     * secret, and redacting it would be pure noise.
     */
    private const val MIN_VALUE_CHARS = 8
}

/** What was found and removed. Carries no secret material, by construction. */
data class RedactionResult(
    val text: String,
    /** Categories that fired. Names only — never the matched value. */
    val categories: Set<SecretCategory>,
    /** How many spans were replaced. */
    val count: Int,
) {
    val changed: Boolean get() = count > 0
}

/**
 * The categories the filter covers.
 *
 * Each carries the sentence the screen shows. Those sentences are the
 * false-positive policy made visible: each one says what is REQUIRED, not
 * just what is caught, because "we catch OTPs" is a claim and "we catch an
 * OTP only when a cue word is next to it" is the truth.
 */
enum class SecretCategory(
    val title: String,
    /** What the user is told. Written to be true, not reassuring. */
    val explanation: String,
    /** The shape, described rather than shown. */
    val shape: String,
    /**
     * Whether the pattern is structural (a marker in the text is the
     * evidence) or contextual (a keyword AND a shape are both required).
     * The screen shows this, because it is the honest way to say which
     * detections you can rely on and which are a judgement call.
     */
    val structural: Boolean,
) {
    PRIVATE_KEY(
        title = "Private keys",
        explanation = "A PEM block from BEGIN to END. The whole block goes, and " +
            "the model is told one was there rather than being left to guess.",
        shape = "A dashed BEGIN/END envelope around the key material.",
        structural = true,
    ),

    JWT(
        title = "JSON Web Tokens",
        explanation = "Three base64 segments in a row. Recognised by the shape " +
            "alone — a JWT header always encodes the same way — so this one " +
            "does not depend on a keyword being nearby.",
        shape = "Three dot-separated base64 runs.",
        structural = true,
    ),

    BEARER_TOKEN(
        title = "Authorization headers",
        explanation = "A Bearer or Basic value. The scheme word stays so the " +
            "model knows a header was there; the value does not.",
        shape = "The scheme word, then a run of token characters.",
        structural = true,
    ),

    API_KEY(
        title = "API keys",
        explanation = "Two key formats whose prefix is long and vendor-unique. " +
            "Deliberately not a longer list: a table of known key formats is a " +
            "maintenance liability, and the keyword rule below catches a pasted " +
            "config file anyway.",
        shape = "A long, vendor-specific prefix followed by a long random run.",
        structural = true,
    ),

    CREDENTIAL_ASSIGNMENT(
        explanation = "A credential keyword next to a value of eight characters " +
            "or more. Both halves are required, so a bare long string that is " +
            "really a hash or a git short SHA is left alone.",
        title = "Passwords and secrets in text",
        shape = "A credential word, then a separator, then a long value.",
        structural = false,
    ),

    OTP_CODE(
        title = "One-time and 2FA codes",
        explanation = "A four-to-eight digit run, but ONLY when a cue word " +
            "sits right next to it — \"your code is…\", \"OTP:…\". A bare six " +
            "digit number is not redacted, because a bare six digit number is " +
            "also a price, a timestamp and a line of a diff. This is the " +
            "sharpest trade in the filter: copy a code to the clipboard on its " +
            "own and it will NOT be caught.",
        shape = "A cue word, a short gap, then four to eight digits.",
        structural = false,
    ),
    ;

    /**
     * One line for the summary at the top of the screen.
     *
     * "Structural" categories are matched on shape alone. "Contextual" ones
     * need a word nearby, which is what keeps them from eating ordinary text
     * and is also why they can miss a secret that arrived on its own.
     */
    val confidence: String
        get() = if (structural) "Matched on shape alone" else "Needs a keyword nearby"
}
