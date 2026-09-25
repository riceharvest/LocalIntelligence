package dev.pidroid.android.data

import androidx.room.TypeConverter
import dev.pidroid.core.model.ChatMessage
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
        MessageKind.TOOL_OBSERVATION -> ChatMessage.ToolObservation(
            toolName = entity.toolName.orEmpty(),
            observation = entity.observation.orEmpty(),
            success = entity.success ?: false,
        )
    }
}

/**
 * Instant <-> epoch-millis. Epoch millis rather than ISO-8601 text: it sorts
 * correctly with a plain `ORDER BY`, which the context builder relies on.
 */
class PiDroidTypeConverters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
