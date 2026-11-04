package dev.localintelligence.core.policy

/**
 * What a screen-control call would act on, as the policy needs to see it.
 *
 * Pure data, resolved from the live accessibility tree by the full build's screen tools and
 * handed to [RiskPolicy.evaluate]. WHY pure data and not the tree itself: the policy is
 * `:core` and must stay free of Android types, and it must be able to replay a decision from
 * a trace, which a live node cannot do.
 *
 * [controlLabel] and [controlDescription] are the text of the element the call targets, and
 * they are what the guarded-word check reads. [isPassword] is true for a password field, so
 * the policy can refuse to type into one. The text of a password field is never read, so it
 * is not carried here at all.
 */
data class ScreenScope(
    val packageName: String,
    val appLabel: String,
    val controlLabel: String,
    val controlDescription: String,
    val isPassword: Boolean = false,
)

/**
 * The settings-facing identity of screen control, shared by the policy and the full build.
 *
 * Declared in `:core` so `:app` can read the block list for the policy without depending on
 * the full build's classes. The Play build never registers the plugin, so these rows are
 * never shown there.
 */
object ScreenControl {
    /** Stable id of the screen control plugin; its settings prefix is this id. */
    const val PLUGIN_ID = "screen.control"

    /** The tool that types text, which rule (d) refuses for a password field. */
    const val TYPE_TOOL = "screen.type"

    /**
     * Words that mark a control as one that acts on the world, with what each one does in a
     * sentence the person reads. A tap on a control whose label or description contains one of
     * these asks every time; the match is on whole words, so "resend" is not "send". English and
     * Dutch, lowercase.
     */
    val GUARDED_VERBS: Map<String, String> = mapOf(
        "send" to "send something to someone",
        "pay" to "take a payment",
        "buy" to "make a purchase",
        "order" to "place an order",
        "delete" to "delete something",
        "remove" to "remove something",
        "install" to "install an app",
        "confirm" to "confirm an action",
        "submit" to "submit a form",
        "transfer" to "move money",
        "verstuur" to "send something to someone",
        "verzend" to "send something to someone",
        "betaal" to "take a payment",
        "koop" to "make a purchase",
        "bestel" to "place an order",
        "verwijder" to "delete something",
        "installeer" to "install an app",
        "bevestig" to "confirm an action",
        "overmaken" to "move money",
    )

    /** The words [GUARDED_VERBS] matches. */
    val GUARDED_WORDS: Set<String> = GUARDED_VERBS.keys

    /** What a guarded word lets a control do, for the sentence the person reads. */
    fun verbFor(word: String): String = GUARDED_VERBS[word] ?: "act on the world"

    /**
     * Packages screen control never acts in: LocalIntelligence's own ids, system Settings and
     * SystemUI, and the package installer and permission dialogs. WHY: an app that can tap
     * these could approve itself or grant itself access, which is the one thing the approval
     * model exists to prevent (docs/adr/0003-screen-control-approval-per-app.md).
     */
    val ALWAYS_BLOCKED_PACKAGES: Set<String> = setOf(
        "dev.localintelligence.app",
        "dev.localintelligence.app.full",
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )
}
