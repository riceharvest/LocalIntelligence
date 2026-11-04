package dev.localintelligence.app.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.localintelligence.core.policy.ScreenScope

// ===========================================================================
// The accessibility tree, flattened into the numbered list a model can act on.
//
// Idea and format follow google-research/android_world (env/representation_utils.py,
// Apache-2.0): the tree is walked once, each node that a person could read or act on becomes one
// numbered line, and the number is what the model uses to refer to it. The source was not
// copied; this is a fresh implementation in the same shape.
//
// WHY numbers and not coordinates: a coordinate is a guess about pixels, and a 1-3B model guesses
// badly. A number refers to a node that was read, and the node is checked again before it is used.
// ===========================================================================

/** One item on the screen, as the model is shown it. */
internal data class ScreenElement(
    val packageName: String,
    val className: String,
    /** The visible text. Empty for a password field, whose text is never read. */
    val text: String,
    val description: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val bounds: String,
) {
    /**
     * Identity across two reads of the same screen. An index from an earlier read is looked up by
     * this key, so a changed screen cannot make a number point at a different item silently.
     */
    val key: String get() = "$packageName|$className|$text|$description|$bounds"

    /** The role word the model sees, from the class name and what the node can do. */
    val role: String
        get() = when {
            editable -> "field"
            className.endsWith("Button") -> "button"
            className.endsWith("CheckBox") -> "checkbox"
            className.endsWith("Switch") -> "switch"
            className.endsWith("ImageView") || className.endsWith("Image") -> "image"
            clickable -> "control"
            else -> "text"
        }
}

/** A read of the app in front: its items, and the live nodes they came from, in the same order. */
internal class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val elements: List<ScreenElement>,
    val nodes: List<AccessibilityNodeInfo>,
)

internal object ScreenTree {
    /** Most items one read lists. A longer screen is cut, and the model is told so. */
    const val MAX_ELEMENTS = 60

    /** Longest text kept for one item, before the whole list is capped. */
    const val MAX_TEXT_CHARS = 120

    /** Most characters one read may produce. */
    const val MAX_CHARS = 8000

    /** Deepest level of the tree walked. Real screens rarely pass 30; a cycle or a bad app must not hang. */
    private const val MAX_DEPTH = 40

    /**
     * Collects what a person could read or act on, depth first, up to [MAX_ELEMENTS].
     *
     * A password field is kept (so the model knows it is there and can ask the user to type) but its
     * text is never read. Invisible nodes are skipped; so are nodes that carry nothing a person or a
     * tap could use.
     */
    fun collect(root: AccessibilityNodeInfo, packageName: String, appLabel: String): ScreenSnapshot {
        val elements = ArrayList<ScreenElement>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (elements.size >= MAX_ELEMENTS || depth > MAX_DEPTH) return
            if (!node.isVisibleToUser) return

            val password = node.isPassword
            val text = if (password) "" else node.text?.toString().orEmpty().trim().take(MAX_TEXT_CHARS)
            val description = node.contentDescription?.toString().orEmpty().trim().take(MAX_TEXT_CHARS)
            val clickable = node.isClickable
            // Editable means the node takes text: the platform's own action, not a guess from the class name.
            val editable = password || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            val scrollable = node.isScrollable

            val worthListing = text.isNotEmpty() || description.isNotEmpty() ||
                clickable || editable || scrollable
            if (worthListing) {
                node.getBoundsInScreen(rect)
                elements += ScreenElement(
                    packageName = packageName,
                    className = node.className?.toString().orEmpty(),
                    text = text,
                    description = description,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    password = password,
                    bounds = rect.toShortString(),
                )
                nodes += node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return ScreenSnapshot(packageName, appLabel, elements, nodes)
    }

    /**
     * The model-visible list: a header, then one line per item,
     * `[n] role "text or description" (flags)`. Capped at [MAX_CHARS].
     *
     * Quotes inside a label become single quotes, so a label cannot end its own quoting early.
     */
    fun render(snapshot: ScreenSnapshot): String {
        val header = "Screen of ${snapshot.appLabel} (${snapshot.packageName}). " +
            "Act on an item by its number.\n"
        if (snapshot.elements.isEmpty()) {
            return header + "(no readable items on screen; try screen.wait, then screen.read again)"
        }
        val out = StringBuilder(header)
        var cut = false
        for ((i, e) in snapshot.elements.withIndex()) {
            val label = (e.text.ifBlank { e.description }).replace('"', '\'')
            val flags = buildList {
                if (e.clickable) add("clickable")
                if (e.editable) add("editable")
                if (e.password) add("password")
                if (e.scrollable) add("scrollable")
            }.joinToString(" ")
            val line = "[${i + 1}] ${e.role} \"$label\"" + if (flags.isEmpty()) "\n" else " ($flags)\n"
            if (out.length + line.length > MAX_CHARS) {
                cut = true
                break
            }
            out.append(line)
        }
        if (cut) out.append("(more items cut to fit; scroll and read again for the rest)\n")
        return out.toString().trimEnd()
    }
}

/** The screen scope a number resolves to on the live screen: what the policy judges and what a tap acts on. */
internal class ScreenTarget(
    val element: ScreenElement,
    val node: AccessibilityNodeInfo,
    val appLabel: String,
) {
    fun scope(): ScreenScope = ScreenScope(
        packageName = element.packageName,
        appLabel = appLabel,
        controlLabel = element.text,
        controlDescription = element.description,
        isPassword = element.password,
    )
}
