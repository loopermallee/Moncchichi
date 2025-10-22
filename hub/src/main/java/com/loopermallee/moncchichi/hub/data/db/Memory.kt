package com.loopermallee.moncchichi.hub.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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
    val role: String,
    val text: String
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
}

@Database(entities = [ConsoleLine::class, AssistantEntry::class], version = 2)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

enum class AssistantRole { USER, ASSISTANT, SYSTEM }

data class AssistantMessage(val role: AssistantRole, val text: String, val timestamp: Long)

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun addConsoleLine(s: String) {
        withContext(Dispatchers.IO) {
            dao.addConsole(ConsoleLine(ts = System.currentTimeMillis(), line = s))
        }
    }

    suspend fun addAssistantMessage(role: AssistantRole, s: String) {
        withContext(Dispatchers.IO) {
            dao.addAssistant(
                AssistantEntry(
                    ts = System.currentTimeMillis(),
                    role = role.name,
                    text = s
                )
            )
        }
    }

    suspend fun lastConsoleLines(limit: Int): List<String> {
        return withContext(Dispatchers.IO) { dao.lastConsole(limit).map { it.line } }
    }

    suspend fun assistantHistory(limit: Int): List<AssistantMessage> {
        return withContext(Dispatchers.IO) {
            dao.lastAssistant(limit)
                .mapNotNull { entry ->
                    runCatching { AssistantRole.valueOf(entry.role) }.getOrNull()?.let { role ->
                        AssistantMessage(role, entry.text, entry.ts)
                    }
                }
                .reversed()
        }
    }
}
