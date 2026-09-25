package dev.pidroid.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema for PiDroid. Exactly four tables, per docs/architecture.md section 14:
 * `sessions`, `messages`, `memories`, `session_summaries`.
 *
 * There is deliberately NO embeddings table. v0 memory search is lexical (FTS5) only.
 * See [MemoryQueries] for the search SQL and [MemoryFtsSchema] for the virtual table.
 *
 * RAM: none of these hold state. They are declarations.
 */

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long,
    /** Null until the user or the runtime names the thread. */
    @ColumnInfo(name = "title") val title: String? = null,
)

/**
 * One [dev.pidroid.core.model.ChatMessage], flattened.
 *
 * The sealed variant is stored as an explicit [kind] discriminator column plus a
 * dedicated payload per variant, so a round-trip is lossless for all four variants
 * (System, User, Assistant, ToolObservation). See [MessageMapping] for the codec.
 *
 * Ordering is insertion order: [id] is monotonic (autoincrement rowid), so
 * `ORDER BY id ASC` reconstructs the exact conversation transcript. That is what
 * context compaction needs.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** Discriminator: see [MessageKind]. */
    @ColumnInfo(name = "kind") val kind: String,
    /** Payload for System/User/Assistant. Null for ToolObservation. */
    @ColumnInfo(name = "text") val text: String? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    /** ToolObservation payload: already truncated and model-facing. */
    @ColumnInfo(name = "observation") val observation: String? = null,
    @ColumnInfo(name = "success") val success: Boolean? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * One durable memory row. [keywords] is the tokenised, lowercased projection of
 * [text] — derived exactly as `InMemoryMemoryStore` does it, so the two stores agree
 * on what a "term" is. Indexed by the FTS5 virtual table alongside [text].
 */
@Entity(
    tableName = "memories",
    indices = [Index("importance"), Index("created_at")],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "keywords") val keywords: String,
    @ColumnInfo(name = "importance") val importance: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A collapsed window of an old session. Written when the context window fills;
 * read by compaction to replace the messages it covers.
 *
 * [fromMessageId] is inclusive and [toMessageId] exclusive, which makes adjacent
 * summaries non-overlapping and lets a reader compute the covered set by subtraction.
 */
@Entity(
    tableName = "session_summaries",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class SessionSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "from_message_id") val fromMessageId: Long,
    @ColumnInfo(name = "to_message_id") val toMessageId: Long,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
