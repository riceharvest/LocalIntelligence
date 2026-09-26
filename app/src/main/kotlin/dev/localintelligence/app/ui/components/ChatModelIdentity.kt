package dev.localintelligence.app.ui.components

/**
 * Which model is answering, as far as the UI is able to state it.
 *
 * ## WHY THIS TYPE IS DECLARED HERE AND NOT ON `ModelAvailability`
 *
 * `ModelAvailability` is a sealed interface of `None | Failed(reason) | Ready`.
 * All three are *facts about whether a run can happen*, and none of them carries
 * a name. `Ready` in particular means "a model is resident and the backend will
 * answer" — it does not say which one. So a user looking at a working chat has
 * no way to learn what is talking to them, and there is nothing in the state to
 * render even if the screen wanted to.
 *
 * This type is the UI's own answer to that gap, and it is deliberately
 * *separate* from the state machine rather than merged into it: the chat screen
 * renders it, it does not derive it, and the thing that has to know which file
 * is resident lives in the container, not in a composable.
 *
 * The wiring is a follow-up and is written out in full in the PR description.
 * Until it lands, [ChatScreen] takes this as a nullable, defaulted parameter, so
 * a caller that passes nothing still compiles and still renders an honest
 * state rather than a blank.
 *
 * @param displayName the model's name as the user would recognise it — the
 *   imported file's display name, not a quantisation label or an internal id.
 * @param quantType the quantisation, when the header carried one. Shown because
 *   two imports of the same architecture can differ only here, and "which model"
 *   is a question about both.
 */
data class ChatModelIdentity(
    val displayName: String,
    val quantType: String? = null,
)
