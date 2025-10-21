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
}

@Database(entities = [ConsoleLine::class, AssistantEntry::class], version = 1)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

class MemoryRepository(private val dao: MemoryDao) {
    suspend fun addConsoleLine(s: String) {
        withContext(Dispatchers.IO) {
            dao.addConsole(ConsoleLine(ts = System.currentTimeMillis(), line = s))
        }
    }

    suspend fun addAssistantResponse(s: String) {
        withContext(Dispatchers.IO) {
            dao.addAssistant(AssistantEntry(ts = System.currentTimeMillis(), text = s))
        }
    }

    suspend fun lastConsoleLines(limit: Int): List<String> {
        return withContext(Dispatchers.IO) { dao.lastConsole(limit).map { it.line } }
    }
}
