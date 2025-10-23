package com.loopermallee.moncchichi.hub.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.loopermallee.moncchichi.core.model.ChatMessage
import com.loopermallee.moncchichi.core.model.MessageOrigin
import com.loopermallee.moncchichi.core.model.MessageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "console_log")
data class ConsoleLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val line: String
)

@Entity(tableName = "assistant_log")
data class AssistantEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    @ColumnInfo(name = "role") val source: String,
    val text: String,
    @ColumnInfo(name = "origin") val origin: String,
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun addConsole(line: ConsoleLine)

    @Insert
    suspend fun addAssistant(entry: AssistantEntry)

    @Query("SELECT * FROM console_log ORDER BY ts DESC LIMIT :n")
    suspend fun lastConsole(n: Int): List<ConsoleLine>

    @Query("SELECT * FROM assistant_log ORDER BY ts DESC LIMIT :n")
    suspend fun lastAssistant(n: Int): List<AssistantEntry>

    @Query("DELETE FROM console_log")
    suspend fun clearConsole()
}

@Database(entities = [ConsoleLine::class, AssistantEntry::class], version = 3)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun addConsoleLine(s: String) {
        withContext(Dispatchers.IO) {
            dao.addConsole(ConsoleLine(ts = System.currentTimeMillis(), line = s))
        }
    }

    suspend fun addChatMessage(source: MessageSource, text: String, origin: MessageOrigin) {
        withContext(Dispatchers.IO) {
            dao.addAssistant(
                AssistantEntry(
                    ts = System.currentTimeMillis(),
                    source = source.name,
                    text = text,
                    origin = origin.name,
                )
            )
        }
    }

    suspend fun lastConsoleLines(limit: Int): List<String> {
        return withContext(Dispatchers.IO) { dao.lastConsole(limit).map { it.line } }
    }

    suspend fun clearConsole() {
        withContext(Dispatchers.IO) { dao.clearConsole() }
    }

    suspend fun chatHistory(limit: Int): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            dao.lastAssistant(limit)
                .mapNotNull { entry ->
                    runCatching { MessageSource.valueOf(entry.source) }.getOrNull()?.let { source ->
                        val origin = runCatching { MessageOrigin.valueOf(entry.origin) }
                            .getOrDefault(MessageOrigin.LLM)
                        ChatMessage(entry.text, source, entry.ts, origin)
                    }
                }
                .reversed()
        }
    }
}
