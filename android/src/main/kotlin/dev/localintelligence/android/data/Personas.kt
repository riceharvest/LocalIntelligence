package dev.localintelligence.android.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.localintelligence.core.persona.Persona
import dev.localintelligence.core.persona.PersonaStore

/**
 * One saved persona. Added in database version 3 (see `LocalIntelligenceDatabase.MIGRATION_2_3`).
 *
 * The chat side is `sessions.persona_id`, a plain nullable column with no foreign key:
 * SQLite cannot add a foreign key with ALTER TABLE, and [RoomPersonaStore.delete] clears
 * the references itself, so a stale id can only ever mean "no persona".
 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY created_at ASC, id ASC")
    suspend fun all(): List<PersonaEntity>

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun byId(id: Long): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(persona: PersonaEntity): Long

    @Query("UPDATE personas SET name = :name, text = :text WHERE id = :id")
    suspend fun rename(id: Long, name: String, text: String): Int

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("UPDATE sessions SET persona_id = NULL WHERE persona_id = :id")
    suspend fun detachChats(id: Long): Int

    @Query("UPDATE sessions SET persona_id = :personaId WHERE id = :sessionId")
    suspend fun setChatPersona(sessionId: Long, personaId: Long?): Int

    @Query("SELECT persona_id FROM sessions WHERE id = :sessionId")
    suspend fun chatPersonaId(sessionId: Long): Long?
}

/** The Room-backed [PersonaStore]. Each call is one Room query, which already runs off the main thread. */
class RoomPersonaStore(private val dao: PersonaDao, private val clock: () -> Long = System::currentTimeMillis) : PersonaStore {

    override suspend fun list(): List<Persona> = dao.all().map { Persona(it.id, it.name, it.text) }

    override suspend fun get(id: Long): Persona? = dao.byId(id)?.let { Persona(it.id, it.name, it.text) }

    override suspend fun save(id: Long?, name: String, text: String): Long {
        val cleanName = name.trim()
        val cleanText = text.trim()
        if (id == null) {
            return dao.insert(PersonaEntity(name = cleanName, text = cleanText, createdAt = clock()))
        }
        dao.rename(id, cleanName, cleanText)
        return id
    }

    /**
     * Chats first, then the persona. If the process dies between the two, the persona stays
     * in the list with no chat using it, which is harmless. The reverse order could leave chats
     * pointing at a deleted persona.
     */
    override suspend fun delete(id: Long) {
        dao.detachChats(id)
        dao.delete(id)
    }

    override suspend fun chatPersonaId(sessionId: Long): Long? = dao.chatPersonaId(sessionId)

    override suspend fun setChatPersona(sessionId: Long, personaId: Long?) {
        dao.setChatPersona(sessionId, personaId)
    }
}

/**
 * The durable [PersonaStore]. Like [durableSessionStore], it is reached only from `:app`
 * through this function, because `:app` cannot name Room's types.
 */
fun durablePersonaStore(context: Context): PersonaStore =
    RoomPersonaStore(LocalIntelligenceDatabase.build(context.applicationContext).personaDao())
