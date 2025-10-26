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

@Entity(tableName = "telemetry_snapshot")
data class TelemetrySnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "uptime_seconds") val uptimeSeconds: Long?,
    @ColumnInfo(name = "left_battery_percent") val leftBatteryPercent: Int?,
    @ColumnInfo(name = "left_case_battery_percent") val leftCaseBatteryPercent: Int?,
    @ColumnInfo(name = "left_last_updated") val leftLastUpdated: Long?,
    @ColumnInfo(name = "left_rssi") val leftRssi: Int?,
    @ColumnInfo(name = "left_firmware_version") val leftFirmwareVersion: String?,
    @ColumnInfo(name = "left_notes") val leftNotes: String?,
    @ColumnInfo(name = "right_battery_percent") val rightBatteryPercent: Int?,
    @ColumnInfo(name = "right_case_battery_percent") val rightCaseBatteryPercent: Int?,
    @ColumnInfo(name = "right_last_updated") val rightLastUpdated: Long?,
    @ColumnInfo(name = "right_rssi") val rightRssi: Int?,
    @ColumnInfo(name = "right_firmware_version") val rightFirmwareVersion: String?,
    @ColumnInfo(name = "right_notes") val rightNotes: String?,
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun addConsole(line: ConsoleLine)

    @Insert
    suspend fun addAssistant(entry: AssistantEntry)

    @Insert
    suspend fun addTelemetry(snapshot: TelemetrySnapshot)

    @Query("SELECT * FROM console_log ORDER BY ts DESC LIMIT :n")
    suspend fun lastConsole(n: Int): List<ConsoleLine>

    @Query("SELECT * FROM assistant_log ORDER BY ts DESC LIMIT :n")
    suspend fun lastAssistant(n: Int): List<AssistantEntry>

    @Query("SELECT * FROM telemetry_snapshot ORDER BY recorded_at DESC LIMIT :n")
    suspend fun recentTelemetrySnapshots(n: Int): List<TelemetrySnapshot>

    @Query("DELETE FROM console_log")
    suspend fun clearConsole()
}

@Database(
    entities = [ConsoleLine::class, AssistantEntry::class, TelemetrySnapshot::class],
    version = 4,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao
}

class MemoryRepository(private val dao: MemoryDao) {
    data class LensSnapshot(
        val batteryPercent: Int?,
        val caseBatteryPercent: Int?,
        val lastUpdated: Long?,
        val rssi: Int?,
        val firmwareVersion: String?,
        val notes: String?,
    )

    data class TelemetrySnapshotRecord(
        val recordedAt: Long,
        val uptimeSeconds: Long?,
        val left: LensSnapshot,
        val right: LensSnapshot,
    )

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

    suspend fun addTelemetrySnapshot(snapshot: TelemetrySnapshotRecord) {
        withContext(Dispatchers.IO) {
            dao.addTelemetry(snapshot.toEntity())
        }
    }

    suspend fun recentTelemetrySnapshots(limit: Int): List<TelemetrySnapshotRecord> {
        return withContext(Dispatchers.IO) {
            dao.recentTelemetrySnapshots(limit).map { it.toRecord() }
        }
    }

    private fun TelemetrySnapshotRecord.toEntity(): TelemetrySnapshot {
        return TelemetrySnapshot(
            recordedAt = recordedAt,
            uptimeSeconds = uptimeSeconds,
            leftBatteryPercent = left.batteryPercent,
            leftCaseBatteryPercent = left.caseBatteryPercent,
            leftLastUpdated = left.lastUpdated,
            leftRssi = left.rssi,
            leftFirmwareVersion = left.firmwareVersion,
            leftNotes = left.notes,
            rightBatteryPercent = right.batteryPercent,
            rightCaseBatteryPercent = right.caseBatteryPercent,
            rightLastUpdated = right.lastUpdated,
            rightRssi = right.rssi,
            rightFirmwareVersion = right.firmwareVersion,
            rightNotes = right.notes,
        )
    }

    private fun TelemetrySnapshot.toRecord(): TelemetrySnapshotRecord {
        return TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = uptimeSeconds,
            left = LensSnapshot(
                batteryPercent = leftBatteryPercent,
                caseBatteryPercent = leftCaseBatteryPercent,
                lastUpdated = leftLastUpdated,
                rssi = leftRssi,
                firmwareVersion = leftFirmwareVersion,
                notes = leftNotes,
            ),
            right = LensSnapshot(
                batteryPercent = rightBatteryPercent,
                caseBatteryPercent = rightCaseBatteryPercent,
                lastUpdated = rightLastUpdated,
                rssi = rightRssi,
                firmwareVersion = rightFirmwareVersion,
                notes = rightNotes,
            ),
        )
    }
}
