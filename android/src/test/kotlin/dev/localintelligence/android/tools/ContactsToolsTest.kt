package dev.localintelligence.android.tools

import android.database.Cursor
import dev.localintelligence.android.tools.contacts.ContactDetail
import dev.localintelligence.android.tools.contacts.ContactPhoneRow
import dev.localintelligence.android.tools.contacts.ContactsArgs
import dev.localintelligence.android.tools.contacts.ContactsGetTool
import dev.localintelligence.android.tools.contacts.ContactsProvider
import dev.localintelligence.android.tools.contacts.ContactsSearchTool
import dev.localintelligence.android.tools.contacts.ContactsText
import dev.localintelligence.android.tools.contacts.ParsedContactId
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the contacts tools. No device, no Robolectric: `ContactsContract`
 * and `ContentResolver` cannot execute here. What IS proven by real execution:
 *
 *  1. The real `execute()` of both tools through a fake [ContactsProvider] and
 *     [FakeCursor] — permission, cancellation, argument rejection, row mapping, dedup,
 *     capping, typed provider failures, and cursor closing on every path.
 *  2. The pure helpers directly, for coercion and rendering edge cases.
 *
 * NOT provable here, needs a device: that the production provider builds the right
 * `Phone`/`Data` queries and that a real contacts provider returns those columns.
 */
class ContactsToolsTest {

    // ================================================================ the seam --

    private class FakeContactsProvider(
        var phones: Cursor? = null,
        var data: Cursor? = null,
        var throwOnQuery: Throwable? = null,
    ) : ContactsProvider {
        var phoneQueryCount = 0
        var lastPattern: String? = null
        var lastMaxRows: Int? = null
        var lastContactId: Long? = null

        override fun queryPhones(pattern: String, maxRows: Int): Cursor? {
            phoneQueryCount++
            lastPattern = pattern
            lastMaxRows = maxRows
            throwOnQuery?.let { throw it }
            return phones
        }

        override fun queryContactData(contactId: Long): Cursor? {
            lastContactId = contactId
            throwOnQuery?.let { throw it }
            return data
        }
    }

    /**
     * The column names the tools ask the phone cursor for. `android.provider` is stubbed
     * in a unit test, so they are spelled out here as plain strings.
     */
    private object Phone {
        const val CONTACT_ID = "contact_id"
        const val DISPLAY_NAME = "display_name"
        const val NUMBER = "data1"  // == ContactsContract.CommonDataKinds.Phone.NUMBER
    }

    private object Data {
        const val MIMETYPE = "mimetype"
        const val VALUE = "data1"
        const val DISPLAY_NAME = "display_name"

        const val PHONE = "vnd.android.cursor.item/phone_v2"
        const val EMAIL = "vnd.android.cursor.item/email_v2"
        const val ORG = "vnd.android.cursor.item/organization"
    }

    private fun phoneCursor(vararg rows: Triple<Long, String, String>): FakeCursor = FakeCursor(
        columns = listOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
        rows = rows.map {
            mapOf(
                Phone.CONTACT_ID to it.first,
                Phone.DISPLAY_NAME to it.second,
                Phone.NUMBER to it.third,
            )
        },
    )

    private fun dataCursor(
        name: String,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        organization: String? = null,
    ): FakeCursor {
        val rows = buildList {
            for (value in phones) {
                add(mapOf(Data.MIMETYPE to Data.PHONE, Data.VALUE to value, Data.DISPLAY_NAME to name))
            }
            for (value in emails) {
                add(mapOf(Data.MIMETYPE to Data.EMAIL, Data.VALUE to value, Data.DISPLAY_NAME to name))
            }
            if (organization != null) {
                add(mapOf(Data.MIMETYPE to Data.ORG, Data.VALUE to organization, Data.DISPLAY_NAME to name))
            }
        }
        return FakeCursor(listOf(Data.MIMETYPE, Data.VALUE, Data.DISPLAY_NAME), rows)
    }

    private fun args(vararg pairs: Pair<String, Any?>): ToolArgs = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                null -> put(key, JsonNull)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    // ============================================================ 1. permission --

    @Test
    fun `search without permission returns PermissionDenied without reading`() = runTest {
        val provider = FakeContactsProvider(phones = phoneCursor(Triple(1, "Dario", "+31611111111")))

        val result = search(provider, "query" to "dario", permissionGranted = false)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertEquals("permission_denied", result.error?.code)
        assertTrue(result.observation, result.observation.contains("Contacts permission"))
        assertEquals("the provider must not be called", 0, provider.phoneQueryCount)
    }

    @Test
    fun `get without permission returns PermissionDenied without reading`() = runTest {
        val provider = FakeContactsProvider(data = dataCursor("Dario", listOf("+31611111111")))

        val result = get(provider, "42", permissionGranted = false)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
        assertNull(provider.lastContactId)
    }

    // ================================================================ 2. empty --

    @Test
    fun `no match says so in words and names the query`() = runTest {
        val result = search(FakeContactsProvider(phones = phoneCursor()), "query" to "nosuchperson")

        assertTrue(result.success)
        assertEquals("No contacts matched \"nosuchperson\".", result.observation)
    }

    @Test
    fun `a contact with no phone number is still reported rather than dropped`() = runTest {
        val provider = FakeContactsProvider(phones = phoneCursor(Triple(1, "Voice Only", "   ")))

        val result = search(provider, "query" to "voice")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("Voice Only"))
        assertTrue(result.observation, result.observation.contains("no number"))
    }

    @Test
    fun `a contact id with no rows is NotFound and says how to recover`() = runTest {
        val result = get(FakeContactsProvider(data = dataCursor("Nobody")), "999")

        assertFalse(result.success)
        assertTrue(result.error is ToolError.NotFound)
        assertEquals("not_found", result.error?.code)
        assertTrue(result.observation, result.observation.contains("No contact with id 999"))
        assertTrue(result.observation, result.observation.contains("contacts.search"))
    }

    // ============================================================== 3. success --

    @Test
    fun `a successful search renders one numbered line per person`() = runTest {
        val provider = FakeContactsProvider(
            phones = phoneCursor(
                Triple(1, "Dario Jansen", "+31611111111"),
                Triple(2, "Sam de Vries", "+31622222222"),
            ),
        )

        val result = search(provider, "query" to "a")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.startsWith("2 contacts matching \"a\":"))
        assertTrue(result.observation, result.observation.contains("1. Dario Jansen — +31611111111"))
        assertTrue(result.observation, result.observation.contains("2. Sam de Vries — +31622222222"))
    }

    @Test
    fun `one match is written in the singular`() = runTest {
        val result = search(
            FakeContactsProvider(phones = phoneCursor(Triple(1, "Dario", "+31611111111"))),
            "query" to "dario",
        )

        assertTrue(result.observation, result.observation.startsWith("1 contact matching"))
    }

    @Test
    fun `a nameless contact is labelled rather than rendered as a blank name`() = runTest {
        val result = search(
            FakeContactsProvider(phones = phoneCursor(Triple(7, "", "+31611111111"))),
            "query" to "316",
        )

        assertTrue(result.observation, result.observation.contains("(no name)"))
    }

    @Test
    fun `a contact detail returns every phone email and the organisation`() = runTest {
        val provider = FakeContactsProvider(
            data = dataCursor(
                name = "Dario Jansen",
                phones = listOf("+31611111111", "+31622222222"),
                emails = listOf("dario@example.com", "d.jansen@acme.example.com"),
                organization = "Acme B.V.",
            ),
        )

        val result = get(provider, "42")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("Dario Jansen (id 42)"))
        assertTrue(result.observation, result.observation.contains("Acme B.V."))
        assertTrue(result.observation, result.observation.contains("+31611111111, +31622222222"))
        assertTrue(result.observation, result.observation.contains("dario@example.com, d.jansen@acme.example.com"))
        assertFalse(result.observation, result.observation.contains("more"))
    }

    @Test
    fun `a contact with nothing saved says so instead of returning blank lines`() = runTest {
        // One row, an empty value: a contact that exists but has no usable data.
        val provider = FakeContactsProvider(
            data = FakeCursor(
                listOf(Data.MIMETYPE, Data.VALUE, Data.DISPLAY_NAME),
                listOf(mapOf(Data.MIMETYPE to Data.PHONE, Data.VALUE to "", Data.DISPLAY_NAME to "Voice Only")),
            ),
        )

        val result = get(provider, "42")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("phones: none saved"))
        assertTrue(result.observation, result.observation.contains("emails: none saved"))
        assertFalse(result.observation, result.observation.contains("null"))
    }

    // ====================================================== 4. invalid arguments --

    @Test
    fun `a blank or missing query is rejected before the provider is called`() = runTest {
        for (bad in listOf(null, "", "   ")) {
            val provider = FakeContactsProvider(phones = phoneCursor(Triple(1, "Dario", "+3161")))

            val result = search(provider, "query" to bad)

            assertFalse("query=$bad", result.success)
            assertTrue("query=$bad", result.error is ToolError.InvalidArguments)
            assertEquals("query=$bad", 0, provider.phoneQueryCount)
        }
    }

    @Test
    fun `a missing id is rejected and points at contacts search`() = runTest {
        for (bad in listOf(null, "", "   ")) {
            val result = get(FakeContactsProvider(), bad)

            assertFalse("id=$bad", result.success)
            assertTrue("id=$bad", result.error is ToolError.InvalidArguments)
            assertTrue(result.observation, result.observation.contains("contacts.search"))
        }
    }

    @Test
    fun `a non numeric contact id is rejected`() = runTest {
        val result = get(FakeContactsProvider(), "dario")

        assertFalse(result.success)
        assertTrue(result.error is ToolError.InvalidArguments)
        assertTrue(result.error!!.message, result.error!!.message.contains("numeric"))
    }

    @Test
    fun `a zero or negative contact id is rejected`() = runTest {
        for (bad in listOf("0", "-5")) {
            assertTrue("id=$bad", get(FakeContactsProvider(), bad).error is ToolError.InvalidArguments)
        }
    }

    @Test
    fun `a stringified id is accepted because models quote integers`() = runTest {
        val provider = FakeContactsProvider(data = dataCursor("Dario", listOf("+31611111111")))

        val result = get(provider, " 42 ")

        assertTrue(result.success)
        assertEquals(42L, provider.lastContactId)
    }

    // ============================================================== 5. coercion --

    @Test
    fun `a stringified limit is honoured an absurd one is clamped and junk falls back`() = runTest {
        val many = (1..60).map { Triple(it.toLong(), "Person $it", "+316000000$it") }

        val asString = search(FakeContactsProvider(phones = phoneCursor(*many.toTypedArray())), "query" to "p", "limit" to "5")
        val absurd = search(FakeContactsProvider(phones = phoneCursor(*many.toTypedArray())), "query" to "p", "limit" to "9999")
        val junk = search(FakeContactsProvider(phones = phoneCursor(*many.toTypedArray())), "query" to "p", "limit" to "banana")

        assertTrue(asString.observation, asString.observation.startsWith("Showing 5 of"))
        assertTrue(absurd.observation, absurd.observation.startsWith("Showing 25 of"))
        assertTrue(junk.observation, junk.observation.contains("Showing 10 of"))
    }

    @Test
    fun `a like metacharacter in the query is escaped before it reaches the provider`() = runTest {
        val provider = FakeContactsProvider(phones = phoneCursor())

        search(provider, "query" to "50%")

        assertEquals("%50\\%%", provider.lastPattern)
    }

    @Test
    fun `the provider is asked for more rows than the limit because dedup shrinks the set`() = runTest {
        val provider = FakeContactsProvider(phones = phoneCursor())

        search(provider, "query" to "dario", "limit" to "10")

        // 10 contacts can occupy 40+ phone rows; asking for only 10 would under-fill.
        assertEquals(40, provider.lastMaxRows)
    }

    @Test
    fun `limit coercion covers the shapes a model actually emits`() {
        assertEquals(8, ContactsArgs.coerceLimit("8"))
        assertEquals(8, ContactsArgs.coerceLimit("  8  "))
        assertEquals(8, ContactsArgs.coerceLimit("8.0"))
        // A fractional limit truncates rather than rounds: never show more than asked.
        assertEquals(7, ContactsArgs.coerceLimit("7.9"))
        assertEquals(10, ContactsArgs.coerceLimit(null))
        assertEquals(10, ContactsArgs.coerceLimit(""))
        assertEquals(10, ContactsArgs.coerceLimit("null"))
        assertEquals(10, ContactsArgs.coerceLimit("banana"))
        assertEquals(10, ContactsArgs.coerceLimit("-4"))
        assertEquals(10, ContactsArgs.coerceLimit("0"))
        assertEquals(25, ContactsArgs.coerceLimit("5000"))
        assertEquals(25, ContactsArgs.coerceLimit(Int.MAX_VALUE.toString()))
    }

    @Test
    fun `like metacharacters are escaped so a percent does not match every contact`() {
        assertEquals("50\\%", ContactsArgs.escapeLike("50%"))
        assertEquals("a\\_b", ContactsArgs.escapeLike("a_b"))
        assertEquals("c\\\\d", ContactsArgs.escapeLike("c\\d"))
        assertEquals("'; DROP TABLE contacts; --", ContactsArgs.escapeLike("'; DROP TABLE contacts; --"))
    }

    @Test
    fun `an explicit json null argument reads as absent not as the string null`() {
        val args = buildJsonObject {
            put("query", JsonNull)
            put("limit", JsonNull)
        }
        assertNotNull(args["query"])
        // primitive() is the layer that maps JsonNull to absent; clean() just sees "null".
        assertNull(dev.localintelligence.android.tools.contacts.primitive(args, "query"))
        assertNull(dev.localintelligence.android.tools.contacts.primitive(args, "limit"))
        assertEquals(10, ContactsArgs.coerceLimit((args["limit"] as JsonPrimitive).content))
    }

    @Test
    fun `free text is collapsed trimmed and capped`() {
        assertEquals("a b", ContactsArgs.clean("  a \n b  ", 64))
        assertNull(ContactsArgs.clean("", 64))
        val capped = ContactsArgs.clean("z".repeat(100), 10)
        assertEquals(10, capped!!.length)
        assertTrue(capped.endsWith("…"))
    }

    // =============================================================== 6. dedup --

    @Test
    fun `one contact with three phone numbers is returned once, not three times`() = runTest {
        val provider = FakeContactsProvider(
            phones = phoneCursor(
                Triple(1, "Dario Jansen", "+31611111111"),
                Triple(1, "Dario Jansen", "+31622222222"),
                Triple(1, "Dario Jansen", "+31633333333"),
            ),
        )

        val result = search(provider, "query" to "dario")

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.startsWith("1 contact matching"))
        assertEquals("exactly one person, not three", 1, lineCount(result))
        // Three numbers, two shown, the third counted.
        assertTrue(result.observation, result.observation.contains("(+1 more)"))
    }

    @Test
    fun `the same number synced twice is collapsed but the other number survives`() = runTest {
        val result = search(
            FakeContactsProvider(
                phones = phoneCursor(
                    Triple(1, "Dario", "+31611111111"),
                    Triple(1, "Dario", "+31611111111"),
                    Triple(1, "Dario", "+31622222222"),
                ),
            ),
            "query" to "dario",
        )

        assertEquals(1, lineCount(result))
        assertFalse(result.observation, result.observation.contains("+31611111111, +31611111111"))
    }

    @Test
    fun `dedup preserves provider order so a repeated search gives a stable answer`() {
        val deduped = ContactsArgs.dedupe(
            listOf(
                ContactPhoneRow(3, "Carol", "+31633333333"),
                ContactPhoneRow(1, "Alice", "+31611111111"),
                ContactPhoneRow(3, "Carol", "+31633333333"),
                ContactPhoneRow(2, "Bob", "+31622222222"),
            ),
            limit = 10,
        )

        assertEquals(listOf(3L, 1L, 2L), deduped.contacts.map { it.contactId })
    }

    @Test
    fun `a name from a later row does not overwrite the first one`() {
        val deduped = ContactsArgs.dedupe(
            listOf(
                ContactPhoneRow(1, "  Dario Jansen ", "+31611111111"),
                ContactPhoneRow(1, "", "+31622222222"),
            ),
            limit = 10,
        )

        assertEquals("Dario Jansen", deduped.contacts[0].displayName)
    }

    @Test
    fun `duplicate phone numbers in a contact detail are collapsed`() {
        val detail = ContactsArgs.buildDetail(
            1, "Dario",
            listOf("+31611111111", "+31611111111", "+31622222222"),
            emptyList(), null,
        )

        assertEquals(listOf("+31611111111", "+31622222222"), detail.phones)
    }

    @Test
    fun `junk values that are not a phone or an email are dropped from the detail`() {
        val detail = ContactsArgs.buildDetail(
            contactId = 1,
            displayName = "Dario",
            phones = listOf("", "   ", "not a number", "+31611111111"),
            emails = listOf("no-at-sign", "dario@example.com"),
            organization = "   ",
        )

        assertEquals(listOf("+31611111111"), detail.phones)
        assertEquals(listOf("dario@example.com"), detail.emails)
        assertNull(detail.organization)
    }

    // ============================================================== 7. capping --

    @Test
    fun `more contacts than the limit are capped and the observation says so`() = runTest {
        val rows = (1..30).map { Triple(it.toLong(), "Person $it", "+316000000$it") }

        val result = search(FakeContactsProvider(phones = phoneCursor(*rows.toTypedArray())), "query" to "person")

        assertTrue(result.observation, result.observation.startsWith("Showing 10 of 30 contacts"))
        assertEquals(10, lineCount(result))
    }

    @Test
    fun `a contact with many numbers shows the first two and counts the rest`() = runTest {
        val result = search(
            FakeContactsProvider(phones = phoneCursor(*(1..12).map { Triple(1L, "Dario", "+316000000$it") }.toTypedArray())),
            "query" to "dario",
        )

        assertTrue(result.observation, result.observation.contains("+3160000001, +3160000002"))
        assertTrue(result.observation, result.observation.contains("(+10 more)"))
    }

    @Test
    fun `twenty five contacts each with twelve numbers still fit the observation budget`() = runTest {
        val rows = (1..25).map { id ->
            Triple(id.toLong(), "A".repeat(80), "+3160000000$id")
        }

        val result = search(
            FakeContactsProvider(phones = phoneCursor(*rows.toTypedArray())),
            "query" to "A".repeat(60),
            "limit" to "25",
        )

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    // ========================================== 8. contacts get: the budget risk --

    @Test
    fun `a twelve phone contact renders compactly and stays under the budget`() = runTest {
        val provider = FakeContactsProvider(
            data = dataCursor(
                name = "Dario Jansen-van-Damme-Alexander der Grosse",
                phones = (1..12).map { "+3161234567$it" },
                emails = (1..4).map { "dario.jansen.mailbox$it@a-very-long-corporate-domain.example.com" },
                organization = "The Very Long Example Corporation International Holdings B.V.",
            ),
        )

        val result = get(provider, "42")

        assertTrue(result.success)
        assertTrue(
            "worst-case observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertTrue(result.observation, result.observation.contains("id 42"))
        assertTrue(result.observation, result.observation.contains("+6 more"))
        assertTrue(result.observation, result.observation.contains("+31612345671"))
        assertFalse(result.observation, result.observation.contains("phones: none"))
    }

    @Test
    fun `the detail reports the provider total not the capped list when counting drops`() = runTest {
        val provider = FakeContactsProvider(
            data = dataCursor("Dario", phones = (1..20).map { "+316000000$it" }),
        )

        val result = get(provider, "42")

        // 20 came back, 6 are shown, so the model must be told 14 more exist.
        assertTrue(result.observation, result.observation.contains("(+14 more)"))
    }

    @Test
    fun `the detail names the organisation when there is one`() = runTest {
        val result = get(
            FakeContactsProvider(data = dataCursor("Dario", listOf("+31611111111"), organization = "Acme B.V.")),
            "42",
        )

        assertTrue(result.observation, result.observation.contains("Acme B.V."))
    }

    // ===================================================== 9. provider failure --

    @Test
    fun `a missing provider is Unavailable and no exception escapes`() = runTest {
        val result = search(FakeContactsProvider(phones = null), "query" to "dario")

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Unavailable)
        assertEquals("unavailable", result.error?.code)
        assertTrue(result.observation, result.observation.contains("No contacts provider"))
    }

    @Test
    fun `a provider that throws is a typed failure with no stack trace in the observation`() = runTest {
        val result = search(
            FakeContactsProvider(throwOnQuery = IllegalStateException("binder died")),
            "query" to "dario",
        )

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Internal)
        assertTrue(result.observation, result.observation.contains("IllegalStateException"))
        assertFalse(result.observation, result.observation.contains("at dev.localintelligence"))
        assertFalse(result.observation, result.observation.contains("binder died"))
    }

    @Test
    fun `a SecurityException from the provider is PermissionDenied`() = runTest {
        val result = search(
            FakeContactsProvider(throwOnQuery = SecurityException("READ_CONTACTS needed")),
            "query" to "dario",
        )

        assertFalse(result.success)
        assertTrue(result.error is ToolError.PermissionDenied)
    }

    @Test
    fun `a get whose provider is missing is NotFound not a crash`() = runTest {
        val result = get(FakeContactsProvider(data = null), "42")

        assertFalse(result.success)
        assertTrue(result.error is ToolError.NotFound)
    }

    // ======================================================== 10. cancellation --

    @Test
    fun `a cancelled signal short-circuits before the provider is touched`() = runTest {
        val provider = FakeContactsProvider(phones = phoneCursor(Triple(1, "Dario", "+3161")))

        val result = search(provider, "query" to "dario", signalCancelled = true)

        assertFalse(result.success)
        assertTrue(result.error is ToolError.Cancelled)
        assertEquals("cancelled", result.error?.code)
        assertEquals(0, provider.phoneQueryCount)
    }

    @Test
    fun `a cancelled get short-circuits before the provider is touched`() = runTest {
        val provider = FakeContactsProvider(data = dataCursor("Dario", listOf("+31611111111")))

        val result = ContactsGetTool(provider).execute(
            args("id" to "42"),
            ToolContext(permissionGranted = true, signal = { true }),
        )

        assertTrue(result.error is ToolError.Cancelled)
        assertNull(provider.lastContactId)
    }

    // ================================================== 11. cursor is closed --

    @Test
    fun `the search cursor is closed exactly once on the success path`() = runTest {
        val cursor = phoneCursor(Triple(1, "Dario", "+31611111111"))

        search(FakeContactsProvider(phones = cursor), "query" to "dario")

        assertEquals(1, cursor.closeCount)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun `the search cursor is closed on the empty path too`() = runTest {
        val cursor = phoneCursor()

        search(FakeContactsProvider(phones = cursor), "query" to "dario")

        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `the search cursor is closed when the provider throws mid scan`() = runTest {
        val cursor = FakeCursor(
            columns = listOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
            rows = listOf(
                mapOf(Phone.CONTACT_ID to 1L, Phone.DISPLAY_NAME to "Dario", Phone.NUMBER to "+31611111111"),
            ),
            failOnRead = IllegalStateException("binder died"),
        )

        val result = search(FakeContactsProvider(phones = cursor), "query" to "dario")

        assertFalse(result.success)
        assertEquals("a cursor must be released even when the read throws", 1, cursor.closeCount)
    }

    @Test
    fun `the get cursor is closed exactly once`() = runTest {
        val cursor = dataCursor("Dario", listOf("+31611111111"), listOf("dario@example.com"))

        get(FakeContactsProvider(data = cursor), "42")

        assertEquals(1, cursor.closeCount)
        assertTrue(cursor.isClosed)
    }

    @Test
    fun `the get cursor is closed when the provider throws mid scan`() = runTest {
        val cursor = FakeCursor(
            columns = listOf(Data.MIMETYPE, Data.VALUE, Data.DISPLAY_NAME),
            rows = listOf(
                mapOf(Data.MIMETYPE to Data.PHONE, Data.VALUE to "+31611111111", Data.DISPLAY_NAME to "Dario"),
            ),
            failOnRead = IllegalStateException("binder died"),
        )

        val result = get(FakeContactsProvider(data = cursor), "42")

        assertFalse(result.success)
        assertEquals(1, cursor.closeCount)
    }

    @Test
    fun `a cancellation part way through a search still closes the cursor`() = runTest {
        var reads = 0
        val cursor = FakeCursor(
            columns = listOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
            rows = (1..10).map {
                mapOf(Phone.CONTACT_ID to it.toLong(), Phone.DISPLAY_NAME to "Person $it", Phone.NUMBER to "+316$it")
            },
            onRead = { reads++ },
        )

        val result = ContactsSearchTool(FakeContactsProvider(phones = cursor)).execute(
            args("query" to "person", "limit" to "10"),
            ToolContext(permissionGranted = true, signal = { reads >= 1 }),
        )

        assertTrue("the scan actually started", reads >= 1)
        assertTrue(result.error is ToolError.Cancelled)
        assertEquals(1, cursor.closeCount)
    }

    // ================================================= 12. the shape of a tool --

    @Test
    fun `definitions declare honest risk category and permission`() {
        val search = ContactsSearchTool(FakeContactsProvider()).definition
        val get = ContactsGetTool(FakeContactsProvider()).definition

        assertEquals("contacts.search", search.name)
        assertEquals("contacts", search.category)
        assertEquals(ToolRisk.READ_ONLY, search.risk)
        assertEquals("android.permission.READ_CONTACTS", search.requiredPermission)

        assertEquals("contacts.get", get.name)
        assertEquals("contacts", get.category)
        assertEquals(ToolRisk.READ_ONLY, get.risk)
        assertEquals("android.permission.READ_CONTACTS", get.requiredPermission)

        assertFalse(search.risk.requiresConfirmation)
    }

    @Test
    fun `each tool carries between four and eight unique lowercase retrieval tags`() {
        for (definition in listOf(
            ContactsSearchTool(FakeContactsProvider()).definition,
            ContactsGetTool(FakeContactsProvider()).definition,
        )) {
            assertTrue("${definition.name} has ${definition.tags.size}", definition.tags.size in 4..8)
            assertEquals(definition.tags.size, definition.tags.map { it.lowercase() }.toSet().size)
        }
    }

    @Test
    fun `descriptions are one imperative sentence about what is returned`() {
        for (definition in listOf(
            ContactsSearchTool(FakeContactsProvider()).definition,
            ContactsGetTool(FakeContactsProvider()).definition,
        )) {
            assertTrue(definition.description, definition.description.endsWith("."))
            assertEquals(1, definition.description.split(". ").size)
            assertTrue(definition.description.first().isUpperCase())
        }
    }

    @Test
    fun `required fields are declared and the schema is an object schema`() {
        for (definition in listOf(
            ContactsSearchTool(FakeContactsProvider()).definition,
            ContactsGetTool(FakeContactsProvider()).definition,
        )) {
            assertEquals("object", (definition.schema["type"] as JsonPrimitive).content)
            assertNotNull(definition.schema["properties"])
            val required = definition.schema["required"] as? JsonArray
            assertNotNull(required)
            assertTrue(required!!.isNotEmpty())
        }

        val searchRequired = ContactsSearchTool(FakeContactsProvider())
            .definition.schema["required"] as JsonArray
        val getRequired = ContactsGetTool(FakeContactsProvider())
            .definition.schema["required"] as JsonArray
        assertEquals(listOf("query"), searchRequired.map { (it as JsonPrimitive).content })
        assertEquals(listOf("id"), getRequired.map { (it as JsonPrimitive).content })
    }

    @Test
    fun `no observation the tools can produce exceeds the budget`() {
        val huge = "A".repeat(500)
        val samples = listOf(
            ContactsText.search(ContactsArgs.dedupe(emptyList(), 10), huge),
            ContactsText.search(
                ContactsArgs.dedupe(
                    (1..25).map { ContactPhoneRow(it.toLong(), huge, "+3160000000$it") },
                    25,
                ),
                huge,
            ),
            ContactsText.detail(
                ContactsArgs.buildDetail(
                    1, huge,
                    (1..40).map { "+3161234567$it" },
                    (1..20).map { "very.long.mailbox.address.number.$it@a-very-long-corporate-domain.example.com" },
                    organization = huge,
                ),
                phonesTotal = 40,
                emailsTotal = 20,
            ),
        )
        for (sample in samples) {
            assertTrue("sample was ${sample.length} chars", sample.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS)
        }
    }



    @Test
    fun `a long email keeps its domain and TLD because that is what identifies it`() {
        val long = "dario.jansen.mailbox1@a-very-long-corporate-domain.example.com"
        val short = ContactsArgs.shortenEmail(long)
        assertTrue("was $short", short.length <= ContactsArgs.MAX_EMAIL_CHARS)
        assertTrue("was $short", short.endsWith("@a-very-long-corporate-domain.example.com"))

        // A short address is returned untouched.
        assertEquals("dario@example.com", ContactsArgs.shortenEmail("dario@example.com"))
    }

    // ---------------------------------------------------------------- helpers --

    private suspend fun search(
        provider: FakeContactsProvider,
        vararg extra: Pair<String, Any?>,
        permissionGranted: Boolean = true,
        signalCancelled: Boolean = false,
    ): ToolResult = ContactsSearchTool(provider).execute(
        args(*extra),
        ToolContext(permissionGranted = permissionGranted, signal = { signalCancelled }),
    )

    private suspend fun get(
        provider: FakeContactsProvider,
        id: Any?,
        permissionGranted: Boolean = true,
    ): ToolResult = ContactsGetTool(provider).execute(
        args("id" to id),
        ToolContext(permissionGranted = permissionGranted),
    )

    private fun lineCount(result: ToolResult): Int =
        result.observation.lines().count { Regex("""^\d+\. """).containsMatchIn(it) }
}