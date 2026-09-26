package dev.localintelligence.android.data

import androidx.room.TypeConverter
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.ObservationOrigin
import java.time.Instant

/**
 * Discriminator for the sealed [ChatMessage] hierarchy, stored in
 * [MessageEntity.kind].
 *
 * The wire values are lowercase and stable: they are persisted data, so renaming a
 * variant must be treated as a schema migration, not a refactor.
 */
enum class MessageKind(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL_OBSERVATION("tool_observation"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy(MessageKind::wire)

        /** @throws IllegalArgumentException on an unknown discriminator. */
        fun fromWire(wire: String): MessageKind =
            BY_WIRE[wire] ?: throw IllegalArgumentException("Unknown message kind: '$wire'")
    }
}

/**
 * ChatMessage <-> column mapping. Pure Kotlin on purpose: no `android.*`, no Room
 * runtime, no SQLite. The lossy-round-trip hazard of a 4-variant sealed type is
 * exactly the kind of thing that must be unit-testable without a device, so this is
 * where the whole codec lives and [RoomStoreTest] exercises it directly.
 */
object MessageMapping {

    /**
     * Flattens a [ChatMessage] into the entity columns.
     *
     * [id]/[sessionId]/[createdAt] are supplied by the caller (the store owns identity
     * and time); this function only decides kind + payload.
     */
    fun toColumns(
        message: ChatMessage,
        sessionId: Long,
        createdAt: Long,
        id: Long = 0L,
    ): MessageEntity = when (message) {
        is ChatMessage.System -> MessageEntity(
            id = id,
            sessionId = sessionId,
            kind = MessageKind.SYSTEM.wire,
            text = message.text,
            createdAt = createdAt,
        )

        is ChatMessage.User -> MessageEntity(
            id = id,
            sessionId = sessionId,
            kind = MessageKind.USER.wire,
            text = message.text,
            createdAt = createdAt,
        )

        is ChatMessage.Assistant -> MessageEntity(
            id = id,
            sessionId = sessionId,
            kind = MessageKind.ASSISTANT.wire,
            text = message.text,
            createdAt = createdAt,
        )

        is ChatMessage.ToolObservation -> MessageEntity(
            id = id,
            sessionId = sessionId,
            kind = MessageKind.TOOL_OBSERVATION.wire,
            // text stays NULL: a tool result is not a text message, and conflating
            // the two is how a compaction pass ends up quoting a tool output as speech.
            text = null,
            toolName = message.toolName,
            observation = message.observation,
            success = message.success,
            createdAt = createdAt,
        )
    }

    /**
     * Reconstructs the exact original [ChatMessage]. Total in both directions for all
     * four variants; anything else is a corrupt row and throws rather than silently
     * degrading the context.
     */
    fun fromColumns(entity: MessageEntity): ChatMessage = when (MessageKind.fromWire(entity.kind)) {
        MessageKind.SYSTEM -> ChatMessage.System(entity.text.orEmpty())
        MessageKind.USER -> ChatMessage.User(entity.text.orEmpty())
        MessageKind.ASSISTANT -> ChatMessage.Assistant(entity.text.orEmpty())
        // WHY THE ORIGIN IS NOT READ FROM A COLUMN, AND WHY THAT IS SAFE.
        //
        // `MessageEntity` has no origin column and this change does not add
        // one. The database is at version 1 with no migrations and
        // `allowBackup=false`, so the only way to carry the flag is a version
        // bump plus a migration — and a failed migration on a store holding the
        // user's conversation history, with no backup to restore from, trades a
        // cosmetic fidelity loss for a real chance of losing every past
        // conversation. Not worth it.
        //
        // `fromWire(null)` is NETWORK, so a restored observation is always
        // treated as externally authored. That direction is the safe one: a
        // local read that comes back over-fenced costs the model a slightly
        // wrong header on a tool it was going to quote anyway, whereas the
        // other direction would strip the fence from a page someone else wrote.
        // An over-fence is a cosmetic inaccuracy; an under-fence is the bug
        // this whole change exists to close.
        MessageKind.TOOL_OBSERVATION -> ChatMessage.ToolObservation(
            toolName = entity.toolName.orEmpty(),
            observation = entity.observation.orEmpty(),
            success = entity.success ?: false,
            origin = ObservationOrigin.fromWire(null),
        )
    }
}

/**
 * Instant <-> epoch-millis. Epoch millis rather than ISO-8601 text: it sorts
 * correctly with a plain `ORDER BY`, which the context builder relies on.
 */
class LocalIntelligenceTypeConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
