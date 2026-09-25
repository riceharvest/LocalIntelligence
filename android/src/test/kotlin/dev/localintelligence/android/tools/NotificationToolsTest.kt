package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.notifications.DEFAULT_LIST_LIMIT
import dev.localintelligence.android.tools.notifications.LISTENER_SETTINGS_ACTION
import dev.localintelligence.android.tools.notifications.MAX_LIST_LIMIT
import dev.localintelligence.android.tools.notifications.NotificationArgs
import dev.localintelligence.android.tools.notifications.NotificationDismissTool
import dev.localintelligence.android.tools.notifications.NotificationListTool
import dev.localintelligence.android.tools.notifications.NotificationReplyTool
import dev.localintelligence.android.tools.notifications.NotificationSummary
import dev.localintelligence.android.tools.notifications.Notifications
import dev.localintelligence.android.tools.notifications.isNotificationListenerEnabledFor
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for `notifications.*`.
 *
 * The platform half of this feature — `NotificationListenerService`,
 * `StatusBarNotification`, `RemoteInput` — cannot be constructed without a
 * device or a Robolectric runtime, neither of which exists here. So the
 * surface proven here is the part that decides what the model is told:
 * [Notifications] (every observation string), [NotificationArgs] (coercion),
 * the listener-grant rule, and the three tools' *own* control flow driven
 * through their injectable `listener` seam with no service bound at all.
 *
 * That last part is not a consolation prize. The failure this file is really
 * guarding is a tool that reports "no notifications" or "reply sent" when it
 * has neither visibility nor a delivery, and every one of those paths returns
 * before a single platform object is touched. The positive paths
 * (`cancelNotification` accepted, `RemoteInput` filled) need the device; they
 * are marked as such in the PR description.
 */
class NotificationToolsTest {

    private val pkg = "dev.localintelligence"

    // ======================================================================
    // THE permission UX: not granted, granted but not connected, connected
    // ======================================================================

    @Test
    fun `the grant check is a package membership test`() {
        assertTrue(isNotificationListenerEnabledFor(pkg, setOf(pkg, "com.other")))
        assertTrue("an empty set is a clean false", !isNotificationListenerEnabledFor(pkg, emptySet()))
        assertTrue(
            "a grant to another app is not our grant",
            !isNotificationListenerEnabledFor(pkg, setOf("com.some.other.agent")),
        )
    }

    @Test
    fun `the not-enabled notice tells the user exactly what to switch on and where`() {
        val notice = Notifications.notEnabledNotice()

        // It must name the destination screen in words a human can act on.
        assertTrue("must name Settings: $notice", notice.contains("Settings"))
        assertTrue("must name the screen: $notice", notice.contains("Device & app notifications"))
        assertTrue("must name the app: $notice", notice.contains("LocalIntelligence"))
        assertTrue("must say what to toggle: $notice", notice.contains("notification access"))
        // And it must be actionable for a programmatic caller too.
        assertTrue("must carry the action: $notice", notice.contains(LISTENER_SETTINGS_ACTION))
        // The honesty clauses: nothing was read, and retrying is pointless.
        assertTrue("must say nothing was read: $notice", notice.contains("read nothing"))
        assertTrue("must stop the retry loop: $notice", notice.contains("stop"))
    }

    @Test
    fun `the not-connected notice is a different message from the not-enabled one`() {
        // These are different user problems: one needs a settings trip, the
        // other needs two seconds. Conflating them would send a user to
        // Settings for something that is already fixed.
        val notEnabled = Notifications.notEnabledNotice()
        val notConnected = Notifications.notConnectedNotice()
        assertTrue("must differ", notEnabled != notConnected)
        assertTrue("not-connected must not demand a settings trip: $notConnected", !notConnected.contains("Settings"))
        assertTrue("not-connected must say it is already enabled: $notConnected", notConnected.contains("enabled"))
        assertTrue("must tell the model to wait: $notConnected", notConnected.contains("wait") || notConnected.contains("shortly"))
        assertTrue("must forbid reading empty: $notConnected", notConnected.contains("empty"))
    }

    // ======================================================================
    // The three tools with NO listener bound: the paths that must not lie
    // ======================================================================

    @Test
    fun `list with no listener bound reports Unavailable and never claims an empty device`() = runTest {
        // THE critical assertion. Reporting an empty list here would tell the
        // model — and through it the user — that their phone is silent, when
        // the truth is that the app cannot see anything at all.
        val result = NotificationListTool(listener = { null }).execute(buildJsonObject { }, ToolContext())

        assertFalse("must not claim success", result.success)
        assertTrue("must be Unavailable, got ${result.error}", result.error is ToolError.Unavailable)
        assertFalse(
            "must not claim an empty device: ${result.observation}",
            result.observation.contains("No notifications are currently showing"),
        )
        assertTrue(
            "must point at the real reason: ${result.observation}",
            result.observation.contains("not connected"),
        )
    }

    @Test
    fun `reply with no listener bound never reports a send`() = runTest {
        var sendAttempted = false
        val result = NotificationReplyTool(
            listener = { null },
            sender = { _, _, _ ->
                sendAttempted = true
                true
            },
        ).execute(
            buildJsonObject {
                put("key", JsonPrimitive("com.whatsapp#1"))
                put("text", JsonPrimitive("on my way"))
            },
            ToolContext(),
        )

        assertFalse("must not claim success", result.success)
        assertTrue("must be Unavailable, got ${result.error}", result.error is ToolError.Unavailable)
        assertTrue("must not even attempt a send", !sendAttempted)
        assertTrue("must say nothing was sent: ${result.observation}", result.observation.contains("No reply was sent"))
        assertFalse("must not say sent: ${result.observation}", result.observation.contains("Reply handed"))
    }

    @Test
    fun `dismiss with no listener bound never reports a dismissal`() = runTest {
        val result = NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("com.x#1")) }, ToolContext())

        assertFalse("must not claim success", result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertTrue("must say nothing was dismissed: ${result.observation}", result.observation.contains("Nothing was dismissed"))
    }

    // ======================================================================
    // context.permissionGranted = false short-circuits everything
    // ======================================================================

    @Test
    fun `permissionGranted false short-circuits all three tools with PermissionDenied`() = runTest {
        val denied = ToolContext(permissionGranted = false)
        var anyCall = false

        val list = NotificationListTool(listener = { null }).execute(buildJsonObject { }, denied)
        val reply = NotificationReplyTool(
            listener = { null },
            sender = { _, _, _ -> anyCall = true; true },
        ).execute(
            buildJsonObject {
                put("key", JsonPrimitive("k"))
                put("text", JsonPrimitive("t"))
            },
            denied,
        )
        val dismiss = NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("k")) }, denied)

        for ((name, result) in listOf("list" to list, "reply" to reply, "dismiss" to dismiss)) {
            assertFalse(name, result.success)
            assertTrue(
                "$name must be PermissionDenied, got ${result.error}",
                result.error is ToolError.PermissionDenied,
            )
            assertTrue("$name must name the grant: ${result.observation}", result.observation.contains("notification access"))
        }
        assertTrue("nothing may be attempted", !anyCall)
    }

    // ======================================================================
    // Invalid arguments
    // ======================================================================

    @Test
    fun `a missing key is InvalidArguments and points at the list call`() = runTest {
        for (args in listOf(
            buildJsonObject { },
            buildJsonObject { put("key", JsonPrimitive("")) },
            buildJsonObject { put("key", JsonPrimitive("   ")) },
            buildJsonObject { put("key", JsonNull) },
        )) {
            val result = NotificationDismissTool(listener = { null }).execute(args, ToolContext())
            assertFalse(result.success)
            assertTrue("got ${result.error}", result.error is ToolError.InvalidArguments)
            assertTrue("must name the tool: ${result.observation}", result.observation.contains("notifications.list"))
        }
    }

    @Test
    fun `reply without text is InvalidArguments and says there is nothing to send`() = runTest {
        val result = NotificationReplyTool(listener = { null }).execute(
            buildJsonObject { put("key", JsonPrimitive("com.x#1")) },
            ToolContext(),
        )
        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue("must name text: ${result.observation}", result.observation.contains("text"))
    }

    @Test
    fun `an over-long reply is refused before any platform work`() = runTest {
        var attempted = false
        val result = NotificationReplyTool(
            listener = { null },
            sender = { _, _, _ -> attempted = true; true },
        ).execute(
            buildJsonObject {
                put("key", JsonPrimitive("com.x#1"))
                put("text", JsonPrimitive("x".repeat(2001)))
            },
            ToolContext(),
        )
        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue("must not attempt", !attempted)
    }

    // ======================================================================
    // Cancellation
    // ======================================================================

    @Test
    fun `a cancelled signal returns Cancelled promptly and does nothing`() = runTest {
        val cancelled = ToolContext(signal = CancellationSignal { true })
        var attempted = false

        val list = NotificationListTool(listener = { null }).execute(buildJsonObject { }, cancelled)
        val reply = NotificationReplyTool(
            listener = { null },
            sender = { _, _, _ -> attempted = true; true },
        ).execute(
            buildJsonObject {
                put("key", JsonPrimitive("k"))
                put("text", JsonPrimitive("t"))
            },
            cancelled,
        )
        val dismiss = NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("k")) }, cancelled)

        for ((name, result) in listOf("list" to list, "reply" to reply, "dismiss" to dismiss)) {
            assertFalse(name, result.success)
            assertTrue("$name must be Cancelled, got ${result.error}", result.error is ToolError.Cancelled)
        }
        assertTrue("no send may be attempted", !attempted)
        // The reply and the dismiss must both state that nothing happened.
        assertTrue(reply.observation.contains("Nothing was sent"))
        assertTrue(dismiss.observation.contains("Nothing was dismissed"))
    }

    // ======================================================================
    // Key handling: the model has to be able to echo what we gave it
    // ======================================================================

    @Test
    fun `a raw key is shortened to something a 1B model can copy`() {
        val raw = "0|com.whatsapp|2|null|10023"
        val short = Notifications.shortKey(raw)
        assertEquals("com.whatsapp#10023", short)
        assertTrue("must be much shorter: ${short.length} vs ${raw.length}", short.length < raw.length)
        assertFalse("pipes are hostile to generation", short.contains("|"))
    }

    @Test
    fun `both the short form and the exact key resolve to the same notification`() {
        val item = summary(key = "0|com.whatsapp|2|null|10023", pkg = "com.whatsapp")
        val list = listOf(item)

        assertEquals(item, Notifications.keyMatches("com.whatsapp#10023", list))
        assertEquals("the exact key must also work", item, Notifications.keyMatches(item.key, list))
        assertEquals("case insensitive", item, Notifications.keyMatches("COM.WHATSAPP#10023", list))
    }

    @Test
    fun `an unknown key resolves to null so the tool can say NotFound`() {
        val list = listOf(summary(key = "0|com.whatsapp|2|null|1", pkg = "com.whatsapp"))
        assertEquals(null, Notifications.keyMatches("com.telegram#999", list))
        assertEquals(null, Notifications.keyMatches("", list))
        assertEquals(null, Notifications.keyMatches("   ", list))
    }

    @Test
    fun `two notifications with the same id in different apps do not collide`() {
        // The same trailing id under two packages is common (both apps get id
        // 1), so the short form must keep the package to stay unique.
        val a = summary(key = "0|com.whatsapp|1|null|7", pkg = "com.whatsapp")
        val b = summary(key = "0|com.telegram|1|null|7", pkg = "com.telegram")
        val list = listOf(a, b)
        assertEquals(a, Notifications.keyMatches("com.whatsapp#7", list))
        assertEquals(b, Notifications.keyMatches("com.telegram#7", list))
        assertFalse(Notifications.shortKey(a.key) == Notifications.shortKey(b.key))
    }

    @Test
    fun `a stale key is reported as NotFound with a run-list-again instruction`() {
        val notice = Notifications.notFoundNotice("com.whatsapp#7")
        assertTrue("must name the key: $notice", notice.contains("com.whatsapp#7"))
        assertTrue("must tell the model to re-list: $notice", notice.contains("notifications.list"))
        assertTrue("must forbid guessing: $notice", notice.contains("guess") || notice.contains("do not"))
    }

    // ======================================================================
    // The not-replyable path: the honesty requirement
    // ======================================================================

    @Test
    fun `the not-replyable notice never claims a send and names the app`() {
        val notice = Notifications.notReplyableNotice("WhatsApp")

        assertTrue("must name the app: $notice", notice.contains("WhatsApp"))
        assertTrue("must state nothing was sent: $notice", notice.contains("Nothing was sent"))
        assertTrue("must explain why: $notice", notice.contains("RemoteInput"))
        assertTrue("must offer the real path: $notice", notice.contains("inside"))
        assertTrue("must not claim success", !notice.contains("Reply handed"))
        assertFalse("no stack trace", notice.contains("\tat "))
    }

    @Test
    fun `the reply-sent notice quotes the text and does not overclaim delivery`() {
        val notice = Notifications.replySentNotice("WhatsApp", "on my way")
        assertTrue("names the app: $notice", notice.contains("WhatsApp"))
        assertTrue("quotes the text: $notice", notice.contains("on my way"))
        // It says the reply was HANDED OVER. The messaging app owns delivery
        // from that point, and claiming otherwise is the overclaim this tool
        // must never make.
        assertTrue("must not overclaim: $notice", notice.contains("app owns delivery"))
    }

    @Test
    fun `a reply observation stays under budget even for a maximal message`() {
        val notice = Notifications.replySentNotice("SomeVeryLongApplicationNameIndeed", "y".repeat(160))
        assertTrue(
            "budget blown: ${notice.length}",
            notice.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `the dismiss notices name the app and do not overclaim`() {
        val ok = Notifications.dismissedNotice("Telegram", "Bram")
        assertTrue(ok.contains("Telegram"))
        assertTrue(ok.contains("Bram"))
        assertTrue("must say it can come back: $ok", ok.contains("post a new one"))

        val refused = Notifications.dismissFailedNotice("SystemUI")
        assertTrue(refused.contains("SystemUI"))
        assertTrue("must explain the block: $refused", refused.contains("block"))
        assertTrue("must stop the loop: $refused", refused.contains("Do not retry"))
    }

    // ======================================================================
    // List observation formatting
    // ======================================================================

    @Test
    fun `an empty device is stated in prose and never as an empty list`() {
        val observation = Notifications.listObservation(emptyList(), totalActive = 0)
        assertEquals("No notifications are currently showing on the device.", observation)
        assertFalse("never a json blob", observation.contains("["))
        assertFalse("never a null", observation.contains("null"))
    }

    @Test
    fun `a filter that matches nothing says so and reports the total`() {
        val observation = Notifications.listObservation(emptyList(), totalActive = 7)
        assertTrue("must say none matched: $observation", observation.contains("No notifications matched"))
        assertTrue("must report the real total: $observation", observation.contains("7"))
        assertTrue("must distinguish from a quiet phone: $observation", observation.contains("matched"))
    }

    @Test
    fun `a populated list is numbered, labelled and carries the short key`() {
        val items = listOf(
            summary(title = "Bram", body = "running 10 late", canReply = true, key = "0|com.whatsapp|1|null|5"),
            summary(title = "Invoice", body = "attached", pkg = "com.mail", key = "0|com.mail|1|null|6"),
        )

        val observation = Notifications.listObservation(items, totalActive = 2)

        assertTrue("must be numbered: $observation", observation.contains("1."))
        assertTrue("must be numbered: $observation", observation.contains("2."))
        assertTrue("must name the app: $observation", observation.contains("WhatsApp"))
        assertTrue("must carry the message: $observation", observation.contains("running 10 late"))
        assertTrue("must carry the key the model must echo: $observation", observation.contains("com.whatsapp#5"))
        assertTrue("must flag replyable: $observation", observation.contains("replyable"))
        assertTrue("must state the count: $observation", observation.contains("2 active notifications"))
    }

    @Test
    fun `the replyable and ongoing flags only appear when true`() {
        val plain = listOf(summary(title = "t", canReply = false, isOngoing = false))
        val observation = Notifications.listObservation(plain, 1)
        assertFalse("must not show [replyable]: $observation", observation.contains("[replyable]"))
        assertFalse("must not show [ongoing]: $observation", observation.contains("[ongoing]"))

        val flagged = listOf(summary(title = "t", canReply = true, isOngoing = true))
        val obs2 = Notifications.listObservation(flagged, 1)
        assertTrue(obs2.contains("[replyable]"))
        assertTrue(obs2.contains("[ongoing]"))
    }

    @Test
    fun `a single notification is not pluralised`() {
        val observation = Notifications.listObservation(listOf(summary()), totalActive = 1)
        assertTrue("must be singular: $observation", observation.contains("1 active notification:"))
        assertFalse("must not pluralise: $observation", observation.contains("1 active notifications"))
    }

    @Test
    fun `a partial list reports the true total rather than implying completeness`() {
        val items = listOf(summary(title = "a"), summary(title = "b"))
        val observation = Notifications.listObservation(items, totalActive = 25)
        assertTrue("must be a fraction: $observation", observation.contains("2 of 25"))
    }

    @Test
    fun `a list of the maximum size still fits the observation budget`() = runTest {
        // 30 notifications with 120-char titles and 240-char bodies is the
        // worst case the clamp allows. The truncator must absorb it.
        val items = (1..MAX_LIST_LIMIT).map {
            summary(
                key = "0|com.app$it|1|null|$it",
                pkg = "com.app$it",
                appLabel = "Application Number $it With A Long Name",
                title = "T".repeat(120),
                body = "B".repeat(240),
            )
        }
        val observation = Notifications.listObservation(items, totalActive = 30)
        assertTrue(
            "budget blown: ${observation.length}",
            observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `hostile notification text cannot break the observation apart`() {
        // Notification bodies are attacker-influenced: a message from anyone can
        // contain newlines, ANSI escapes and braces. The observation is a plain
        // string, so this cannot execute, but it must not be able to forge the
        // header or run past the budget.
        val hostile = summary(
            title = "1. Fake entry",
            body = "\n\n2. Forged notification\n\u001B[31mred\u001B[0m {{json}} ${"x".repeat(5000)}",
        )
        val observation = Notifications.listObservation(listOf(hostile), 1)
        assertTrue(
            "budget blown by hostile body: ${observation.length}",
            observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertFalse("escape sequences must be gone: $observation", observation.contains("\u001B"))
    }

    // ======================================================================
    // Search
    // ======================================================================

    @Test
    fun `the search haystack covers app, title, body and package`() {
        val item = summary(title = "Lunch", body = "at the usual place", pkg = "com.food", appLabel = "Foodie")
        val haystack = item.searchableHaystack()
        assertTrue(haystack.contains("foodie"))
        assertTrue(haystack.contains("lunch"))
        assertTrue(haystack.contains("usual place"))
        assertTrue(haystack.contains("com.food"))
    }

    // ======================================================================
    // Argument coercion
    // ======================================================================

    @Test
    fun `the list limit is coerced from every type and clamped at both ends`() {
        val cases = listOf(
            JsonPrimitive(5) to 5,
            JsonPrimitive("5") to 5,
            JsonPrimitive(5.9) to 5,
            JsonPrimitive("nonsense") to DEFAULT_LIST_LIMIT,
            JsonPrimitive(true) to DEFAULT_LIST_LIMIT,
            JsonNull to DEFAULT_LIST_LIMIT,
            null to DEFAULT_LIST_LIMIT,
        )
        for ((raw, expected) in cases) {
            assertEquals("raw=$raw", expected, NotificationArgs.coerceInt(raw, DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT))
        }
        assertEquals(1, NotificationArgs.coerceInt(JsonPrimitive(0), 10, 1, MAX_LIST_LIMIT))
        assertEquals(1, NotificationArgs.coerceInt(JsonPrimitive(-5), 10, 1, MAX_LIST_LIMIT))
        assertEquals(MAX_LIST_LIMIT, NotificationArgs.coerceInt(JsonPrimitive(10_000), 10, 1, MAX_LIST_LIMIT))
        assertEquals(MAX_LIST_LIMIT, NotificationArgs.coerceInt(JsonPrimitive(Int.MAX_VALUE), 10, 1, MAX_LIST_LIMIT))
    }

    @Test
    fun `booleans are coerced from the shapes a model emits`() {
        assertTrue(NotificationArgs.coerceBool(JsonPrimitive(true)))
        assertTrue(NotificationArgs.coerceBool(JsonPrimitive("true")))
        assertTrue(NotificationArgs.coerceBool(JsonPrimitive("YES")))
        assertTrue(NotificationArgs.coerceBool(JsonPrimitive(1)))
        assertFalse(NotificationArgs.coerceBool(JsonPrimitive(false)))
        assertFalse(NotificationArgs.coerceBool(JsonPrimitive("false")))
        assertFalse("junk falls back", NotificationArgs.coerceBool(JsonPrimitive("maybe")))
        assertFalse(NotificationArgs.coerceBool(null))
    }

    @Test
    fun `a string argument is trimmed and blank becomes null`() {
        assertEquals("k", NotificationArgs.coerceString(JsonPrimitive("  k  ")))
        assertEquals(null, NotificationArgs.coerceString(JsonPrimitive("   ")))
        assertEquals(null, NotificationArgs.coerceString(JsonPrimitive("")))
        assertEquals(null, NotificationArgs.coerceString(JsonNull))
        assertEquals(null, NotificationArgs.coerceString(null))
    }

    // ======================================================================
    // Definitions
    // ======================================================================

    @Test
    fun `each definition is well formed and its risk matches the contract`() {
        val list = NotificationListTool(listener = { null }).definition
        val reply = NotificationReplyTool(listener = { null }).definition
        val dismiss = NotificationDismissTool(listener = { null }).definition

        assertEquals("notifications.list", list.name)
        assertEquals("notifications.reply", reply.name)
        assertEquals("notifications.dismiss", dismiss.name)

        for (def in listOf(list, reply, dismiss)) {
            assertEquals("notifications", def.category)
            assertTrue("4-8 tags, got ${def.tags.size}", def.tags.size in 4..8)
            assertTrue("tags lowercase", def.tags.all { it == it.lowercase() })
            assertTrue("one imperative sentence: ${def.description}", def.description.endsWith("."))
            assertTrue("must document the grant: ${def.requiredPermission}", def.requiredPermission!!.contains("Settings"))
            val schema = def.schema
            assertEquals("object", schema["type"].toString().trim('"'))
            assertNotNull(schema["properties"])
        }
    }

    @Test
    fun `the risk classification is honest and the confirmation flag is derived`() {
        val list = NotificationListTool(listener = { null }).definition
        val reply = NotificationReplyTool(listener = { null }).definition
        val dismiss = NotificationDismissTool(listener = { null }).definition

        assertEquals(ToolRisk.READ_ONLY, list.risk)
        assertFalse("a read must never ask for confirmation", list.risk.requiresConfirmation)

        // Sending a message as the user, under the user's identity, to a third
        // party is the textbook EXTERNAL_COMMUNICATION case.
        assertEquals(ToolRisk.EXTERNAL_COMMUNICATION, reply.risk)
        assertTrue("a reply must always be confirmed", reply.risk.requiresConfirmation)

        // Dismissing a banner destroys nothing: the app can re-post. REVERSIBLE
        // is correct, and DESTRUCTIVE would be a lie that trains the user to
        // rubber-stamp confirmations.
        assertEquals(ToolRisk.REVERSIBLE, dismiss.risk)
        assertFalse(dismiss.risk.requiresConfirmation)
    }

    @Test
    fun `the schemas declare exactly the arguments the tools read`() {
        val listProps = propsOf(NotificationListTool(listener = { null }).definition)
        assertEquals(setOf("limit", "query", "onlyReplyable"), listProps)

        val replyProps = propsOf(NotificationReplyTool(listener = { null }).definition)
        assertEquals(setOf("key", "text"), replyProps)
        assertEquals(
            setOf("key", "text"),
            requiredOf(NotificationReplyTool(listener = { null }).definition),
        )

        val dismissProps = propsOf(NotificationDismissTool(listener = { null }).definition)
        assertEquals(setOf("key"), dismissProps)
        assertEquals(setOf("key"), requiredOf(NotificationDismissTool(listener = { null }).definition))
    }

    // ======================================================================
    // Budget
    // ======================================================================

    @Test
    fun `every failure observation these tools emit fits the contract budget`() = runTest {
        val cancelled = ToolContext(signal = CancellationSignal { true })
        val denied = ToolContext(permissionGranted = false)
        val normal = ToolContext()

        val results = mutableListOf<ToolResult>()
        // No listener bound, both permission states, and a cancelled signal.
        results += NotificationListTool(listener = { null }).execute(buildJsonObject { }, normal)
        results += NotificationListTool(listener = { null }).execute(buildJsonObject { }, denied)
        results += NotificationListTool(listener = { null }).execute(buildJsonObject { }, cancelled)
        results += NotificationReplyTool(listener = { null }).execute(
            buildJsonObject {
                put("key", JsonPrimitive("k"))
                put("text", JsonPrimitive("t"))
            },
            normal,
        )
        results += NotificationReplyTool(listener = { null }).execute(buildJsonObject { }, denied)
        results += NotificationReplyTool(listener = { null }).execute(
            buildJsonObject {
                put("key", JsonPrimitive("k"))
                put("text", JsonPrimitive("t"))
            },
            cancelled,
        )
        results += NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("k")) }, normal)
        results += NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("k")) }, denied)
        results += NotificationDismissTool(listener = { null })
            .execute(buildJsonObject { put("key", JsonPrimitive("k")) }, cancelled)
        // Invalid-argument paths.
        results += NotificationListTool(listener = { null })
            .execute(buildJsonObject { put("limit", JsonPrimitive("x")) }, normal)
        results += NotificationDismissTool(listener = { null }).execute(buildJsonObject { }, normal)

        assertTrue("expected a spread of results", results.size >= 10)
        for (result in results) {
            assertTrue(
                "budget blown: ${result.observation.length} :: ${result.observation.take(80)}",
                result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
            )
            assertFalse("no stack traces", result.observation.contains("\tat "))
            assertFalse("no exception class names", result.observation.contains("Exception:"))
        }
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private fun summary(
        key: String = "0|com.whatsapp|1|null|1",
        pkg: String = "com.whatsapp",
        appLabel: String = "WhatsApp",
        title: String = "Bram",
        body: String = "running 10 late",
        canReply: Boolean = true,
        isOngoing: Boolean = false,
    ) = NotificationSummary(
        key = key,
        packageName = pkg,
        appLabel = appLabel,
        title = title,
        body = body,
        isOngoing = isOngoing,
        canReply = canReply,
        postedAtMillis = 0L,
    )

    private fun propsOf(def: dev.localintelligence.core.tool.ToolDefinition): Set<String> =
        (def.schema["properties"] as kotlinx.serialization.json.JsonObject).keys

    private fun requiredOf(def: dev.localintelligence.core.tool.ToolDefinition): Set<String> =
        (def.schema["required"] as kotlinx.serialization.json.JsonArray)
            .map { it.toString().trim('"') }
            .toSet()
}
