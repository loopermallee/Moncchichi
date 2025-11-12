package com.loopermallee.moncchichi.hub.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @ColumnInfo(name = "case_battery_percent") val caseBatteryPercent: Int?,
    @ColumnInfo(name = "case_charging") val caseCharging: Boolean?,
    @ColumnInfo(name = "case_lid_open") val caseLidOpen: Boolean?,
    @ColumnInfo(name = "case_silent_mode") val caseSilentMode: Boolean?,
    @ColumnInfo(name = "left_battery_percent") val leftBatteryPercent: Int?,
    @ColumnInfo(name = "left_battery_voltage_mv") val leftBatteryVoltageMv: Int?,
    @ColumnInfo(name = "left_case_battery_percent") val leftCaseBatteryPercent: Int?,
    @ColumnInfo(name = "left_case_open") val leftCaseOpen: Boolean?,
    @ColumnInfo(name = "left_case_silent_mode") val leftCaseSilentMode: Boolean?,
    @ColumnInfo(name = "left_last_updated") val leftLastUpdated: Long?,
    @ColumnInfo(name = "left_rssi") val leftRssi: Int?,
    @ColumnInfo(name = "left_firmware_version") val leftFirmwareVersion: String?,
    @ColumnInfo(name = "left_notes") val leftNotes: String?,
    @ColumnInfo(name = "left_reconnect_attempts") val leftReconnectAttempts: Int?,
    @ColumnInfo(name = "left_heartbeat_latency_ms") val leftHeartbeatLatencyMs: Int?,
    @ColumnInfo(name = "left_heartbeat_latency_avg_ms") val leftHeartbeatLatencyAvgMs: Int?,
    @ColumnInfo(name = "left_last_ack_mode") val leftLastAckMode: String?,
    @ColumnInfo(name = "left_last_ack_status") val leftLastAckStatus: String?,
    @ColumnInfo(name = "left_last_ack_timestamp") val leftLastAckTimestamp: Long?,
    @ColumnInfo(name = "left_uptime_seconds") val leftUptimeSeconds: Long?,
    @ColumnInfo(name = "left_snapshot_json") val leftSnapshotJson: String?,
    @ColumnInfo(name = "left_heartbeat_miss_count") val leftHeartbeatMissCount: Int?,
    @ColumnInfo(name = "right_battery_percent") val rightBatteryPercent: Int?,
    @ColumnInfo(name = "right_battery_voltage_mv") val rightBatteryVoltageMv: Int?,
    @ColumnInfo(name = "right_case_battery_percent") val rightCaseBatteryPercent: Int?,
    @ColumnInfo(name = "right_case_open") val rightCaseOpen: Boolean?,
    @ColumnInfo(name = "right_case_silent_mode") val rightCaseSilentMode: Boolean?,
    @ColumnInfo(name = "right_last_updated") val rightLastUpdated: Long?,
    @ColumnInfo(name = "right_rssi") val rightRssi: Int?,
    @ColumnInfo(name = "right_firmware_version") val rightFirmwareVersion: String?,
    @ColumnInfo(name = "right_notes") val rightNotes: String?,
    @ColumnInfo(name = "right_reconnect_attempts") val rightReconnectAttempts: Int?,
    @ColumnInfo(name = "right_heartbeat_latency_ms") val rightHeartbeatLatencyMs: Int?,
    @ColumnInfo(name = "right_heartbeat_latency_avg_ms") val rightHeartbeatLatencyAvgMs: Int?,
    @ColumnInfo(name = "right_last_ack_mode") val rightLastAckMode: String?,
    @ColumnInfo(name = "right_last_ack_status") val rightLastAckStatus: String?,
    @ColumnInfo(name = "right_last_ack_timestamp") val rightLastAckTimestamp: Long?,
    @ColumnInfo(name = "right_uptime_seconds") val rightUptimeSeconds: Long?,
    @ColumnInfo(name = "right_snapshot_json") val rightSnapshotJson: String?,
    @ColumnInfo(name = "right_heartbeat_miss_count") val rightHeartbeatMissCount: Int?,
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
    version = 9,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.ensureColumn("telemetry_snapshot", "left_snapshot_json", "TEXT")
                database.ensureColumn("telemetry_snapshot", "right_snapshot_json", "TEXT")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            @Suppress("UNUSED_PARAMETER")
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes between versions 7 and 8; placeholder to keep migration chain intact.
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.ensureColumn("telemetry_snapshot", "left_heartbeat_miss_count", "INTEGER")
                database.ensureColumn("telemetry_snapshot", "right_heartbeat_miss_count", "INTEGER")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
    }
}

class MemoryRepository(private val dao: MemoryDao) {
    data class LensSnapshot(
        val batteryPercent: Int?,
        val batteryVoltageMv: Int?,
        val caseBatteryPercent: Int?,
        val caseOpen: Boolean?,
        val caseSilentMode: Boolean?,
        val lastUpdated: Long?,
        val rssi: Int?,
        val firmwareVersion: String?,
        val notes: String?,
        val reconnectAttempts: Int?,
        val heartbeatLatencyMs: Int?,
        val heartbeatLatencyAvgMs: Int?,
        val lastAckMode: String?,
        val lastAckStatus: String?,
        val lastAckTimestamp: Long?,
        val uptimeSeconds: Long?,
        val snapshotJson: String?,
        val heartbeatMissCount: Int?,
    )

    data class CaseSnapshot(
        val batteryPercent: Int?,
        val charging: Boolean?,
        val lidOpen: Boolean?,
        val silentMode: Boolean?,
    )

    data class TelemetrySnapshotRecord(
        val recordedAt: Long,
        val uptimeSeconds: Long?,
        val case: CaseSnapshot?,
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
            caseBatteryPercent = case?.batteryPercent,
            caseCharging = case?.charging,
            caseLidOpen = case?.lidOpen,
            caseSilentMode = case?.silentMode,
            leftBatteryPercent = left.batteryPercent,
            leftBatteryVoltageMv = left.batteryVoltageMv,
            leftCaseBatteryPercent = left.caseBatteryPercent ?: case?.batteryPercent,
            leftCaseOpen = left.caseOpen ?: case?.lidOpen,
            leftCaseSilentMode = left.caseSilentMode,
            leftLastUpdated = left.lastUpdated,
            leftRssi = left.rssi,
            leftFirmwareVersion = left.firmwareVersion,
            leftNotes = left.notes,
            leftReconnectAttempts = left.reconnectAttempts,
            leftHeartbeatLatencyMs = left.heartbeatLatencyMs,
            leftHeartbeatLatencyAvgMs = left.heartbeatLatencyAvgMs,
            leftLastAckMode = left.lastAckMode,
            leftLastAckStatus = left.lastAckStatus,
            leftLastAckTimestamp = left.lastAckTimestamp,
            leftUptimeSeconds = left.uptimeSeconds,
            leftSnapshotJson = left.snapshotJson,
            leftHeartbeatMissCount = left.heartbeatMissCount,
            rightBatteryPercent = right.batteryPercent,
            rightBatteryVoltageMv = right.batteryVoltageMv,
            rightCaseBatteryPercent = right.caseBatteryPercent ?: case?.batteryPercent,
            rightCaseOpen = right.caseOpen ?: case?.lidOpen,
            rightCaseSilentMode = right.caseSilentMode,
            rightLastUpdated = right.lastUpdated,
            rightRssi = right.rssi,
            rightFirmwareVersion = right.firmwareVersion,
            rightNotes = right.notes,
            rightReconnectAttempts = right.reconnectAttempts,
            rightHeartbeatLatencyMs = right.heartbeatLatencyMs,
            rightHeartbeatLatencyAvgMs = right.heartbeatLatencyAvgMs,
            rightLastAckMode = right.lastAckMode,
            rightLastAckStatus = right.lastAckStatus,
            rightLastAckTimestamp = right.lastAckTimestamp,
            rightUptimeSeconds = right.uptimeSeconds,
            rightSnapshotJson = right.snapshotJson,
            rightHeartbeatMissCount = right.heartbeatMissCount,
        )
    }

    private fun TelemetrySnapshot.toRecord(): TelemetrySnapshotRecord {
        return TelemetrySnapshotRecord(
            recordedAt = recordedAt,
            uptimeSeconds = uptimeSeconds,
            case = CaseSnapshot(
                batteryPercent = caseBatteryPercent,
                charging = caseCharging,
                lidOpen = caseLidOpen,
                silentMode = caseSilentMode,
            ).takeIf { it.batteryPercent != null || it.charging != null || it.lidOpen != null || it.silentMode != null },
            left = LensSnapshot(
                batteryPercent = leftBatteryPercent,
                batteryVoltageMv = leftBatteryVoltageMv,
                caseBatteryPercent = leftCaseBatteryPercent,
                caseOpen = leftCaseOpen,
                caseSilentMode = leftCaseSilentMode,
                lastUpdated = leftLastUpdated,
                rssi = leftRssi,
                firmwareVersion = leftFirmwareVersion,
                notes = leftNotes,
                reconnectAttempts = leftReconnectAttempts,
                heartbeatLatencyMs = leftHeartbeatLatencyMs,
                heartbeatLatencyAvgMs = leftHeartbeatLatencyAvgMs,
                lastAckMode = leftLastAckMode,
                lastAckStatus = leftLastAckStatus,
                lastAckTimestamp = leftLastAckTimestamp,
                uptimeSeconds = leftUptimeSeconds,
                snapshotJson = leftSnapshotJson,
                heartbeatMissCount = leftHeartbeatMissCount,
            ),
            right = LensSnapshot(
                batteryPercent = rightBatteryPercent,
                batteryVoltageMv = rightBatteryVoltageMv,
                caseBatteryPercent = rightCaseBatteryPercent,
                caseOpen = rightCaseOpen,
                caseSilentMode = rightCaseSilentMode,
                lastUpdated = rightLastUpdated,
                rssi = rightRssi,
                firmwareVersion = rightFirmwareVersion,
                notes = rightNotes,
                reconnectAttempts = rightReconnectAttempts,
                heartbeatLatencyMs = rightHeartbeatLatencyMs,
                heartbeatLatencyAvgMs = rightHeartbeatLatencyAvgMs,
                lastAckMode = rightLastAckMode,
                lastAckStatus = rightLastAckStatus,
                lastAckTimestamp = rightLastAckTimestamp,
                uptimeSeconds = rightUptimeSeconds,
                snapshotJson = rightSnapshotJson,
                heartbeatMissCount = rightHeartbeatMissCount,
            ),
        )
    }
}

private fun SupportSQLiteDatabase.ensureColumn(table: String, column: String, definition: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info($table)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex == -1) {
            return false
        }
        while (cursor.moveToNext()) {
            if (column.equals(cursor.getString(nameIndex), ignoreCase = true)) {
                return true
            }
        }
    }
    return false
}
