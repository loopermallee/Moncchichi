package com.loopermallee.moncchichi.telemetry

import com.loopermallee.moncchichi.bluetooth.LensSide
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Lightweight telemetry cache shared by the service layer to track lens vitals and persist snapshots.
 */
class BleTelemetryRepository(
    private val snapshotStore: TelemetrySnapshotStore? = null,
    private val logger: (String) -> Unit = {},
) {

    data class LensSnapshot(
        val batteryPercent: Int? = null,
        val rssi: Int? = null,
        val firmwareVersion: String? = null,
        val uptimeSeconds: Long? = null,
        val lastPingAt: Long? = null,
        val lastAckAt: Long? = null,
        val missedHeartbeats: Int = 0,
        val lastUpdatedAt: Long? = null,
    )

    data class CaseSnapshot(
        val batteryPercent: Int? = null,
        val charging: Boolean? = null,
        val lidOpen: Boolean? = null,
        val silentMode: Boolean? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class Snapshot(
        val left: LensSnapshot = LensSnapshot(),
        val right: LensSnapshot = LensSnapshot(),
        val recordedAt: Long = System.currentTimeMillis(),
    )

    data class SnapshotRecord(
        val recordedAt: Long,
        val caseJson: String?,
        val leftJson: String?,
        val rightJson: String?,
    )

    interface TelemetrySnapshotStore {
        suspend fun persist(record: SnapshotRecord)
    }

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _caseSnapshot = MutableStateFlow<CaseSnapshot?>(null)
    val caseSnapshot: StateFlow<CaseSnapshot?> = _caseSnapshot.asStateFlow()

    private val firstTelemetryAt = AtomicLong(0L)
    private var lastPersisted: SnapshotRecord? = null

    suspend fun recordTelemetry(side: LensSide, telemetry: Map<String, Any>) {
        val now = System.currentTimeMillis()
        mutex.withLock {
            val current = _snapshot.value
            val updatedLens = current.lens(side).merge(telemetry, now)
            _snapshot.value = current.update(side, updatedLens, now)
            if (firstTelemetryAt.get() == 0L) {
                firstTelemetryAt.compareAndSet(0L, now)
            }
        }
    }

    suspend fun recordCaseTelemetry(
        batteryPercent: Int?,
        charging: Boolean?,
        lidOpen: Boolean?,
        silentMode: Boolean?,
    ) {
        val now = System.currentTimeMillis()
        mutex.withLock {
            val current = _caseSnapshot.value
            val snapshot = CaseSnapshot(
                batteryPercent = batteryPercent ?: current?.batteryPercent,
                charging = charging ?: current?.charging,
                lidOpen = lidOpen ?: current?.lidOpen,
                silentMode = silentMode ?: current?.silentMode,
                updatedAt = now,
            )
            _caseSnapshot.value = snapshot
        }
    }

    suspend fun updateHeartbeat(side: LensSide, lastPingAt: Long, lastAckAt: Long, missedCount: Int) {
        mutex.withLock {
            val current = _snapshot.value
            val lens = current.lens(side)
            val updated = lens.copy(
                lastPingAt = lastPingAt.takeIf { it > 0 },
                lastAckAt = lastAckAt.takeIf { it > 0 },
                missedHeartbeats = missedCount,
            )
            _snapshot.value = current.update(side, updated, System.currentTimeMillis())
        }
    }

    suspend fun persistSnapshot() {
        val record = mutex.withLock {
            val current = _snapshot.value
            val case = _caseSnapshot.value
            val candidate = SnapshotRecord(
                recordedAt = System.currentTimeMillis(),
                caseJson = case?.toJsonString(),
                leftJson = current.left.toJsonString(),
                rightJson = current.right.toJsonString(),
            )
            if (candidate.hasSameContent(lastPersisted)) {
                return@withLock null
            }
            candidate
        }
        if (record != null) {
            snapshotStore?.let {
                it.persist(record)
                mutex.withLock { lastPersisted = record }
            } ?: mutex.withLock { lastPersisted = record }
            logger("[TELEMETRY] Snapshot persisted @${record.recordedAt}")
        }
    }

    fun firstTelemetryTimestamp(): Long? {
        val value = firstTelemetryAt.get()
        return if (value == 0L) null else value
    }

    private fun Snapshot.lens(side: LensSide): LensSnapshot {
        return when (side) {
            LensSide.LEFT -> left
            LensSide.RIGHT -> right
        }
    }

    private fun Snapshot.update(side: LensSide, lens: LensSnapshot, timestamp: Long): Snapshot {
        return when (side) {
            LensSide.LEFT -> copy(left = lens, recordedAt = timestamp)
            LensSide.RIGHT -> copy(right = lens, recordedAt = timestamp)
        }
    }

    private fun LensSnapshot.merge(values: Map<String, Any>, timestamp: Long): LensSnapshot {
        var snapshot = this.copy(lastUpdatedAt = timestamp)
        values.forEach { (key, raw) ->
            when (key) {
                "batteryPercent" -> snapshot = snapshot.copy(batteryPercent = raw.toIntOrNull(snapshot.batteryPercent))
                "rssi" -> snapshot = snapshot.copy(rssi = raw.toIntOrNull(snapshot.rssi))
                "firmwareVersion" -> snapshot = snapshot.copy(firmwareVersion = raw.toStringOrNull(snapshot.firmwareVersion))
                "uptime" , "uptimeSeconds" -> snapshot = snapshot.copy(uptimeSeconds = raw.toLongOrNull(snapshot.uptimeSeconds))
                "lastPingAt" -> snapshot = snapshot.copy(lastPingAt = raw.toLongOrNull(snapshot.lastPingAt))
                "lastAckAt" -> snapshot = snapshot.copy(lastAckAt = raw.toLongOrNull(snapshot.lastAckAt))
                "missedPingCount" -> snapshot = snapshot.copy(missedHeartbeats = raw.toIntOrNull(snapshot.missedHeartbeats) ?: snapshot.missedHeartbeats)
            }
        }
        return snapshot
    }

    private fun CaseSnapshot.toJsonString(): String? {
        val json = JSONObject()
        batteryPercent?.let { json.put("batteryPercent", it) }
        charging?.let { json.put("charging", it) }
        lidOpen?.let { json.put("lidOpen", it) }
        silentMode?.let { json.put("silentMode", it) }
        json.put("updatedAt", updatedAt)
        return json.takeUnless { it.length() == 0 }?.toString()
    }

    private fun LensSnapshot.toJsonString(): String? {
        val json = JSONObject()
        batteryPercent?.let { json.put("batteryPercent", it) }
        rssi?.let { json.put("rssi", it) }
        firmwareVersion?.let { json.put("firmwareVersion", it) }
        uptimeSeconds?.let { json.put("uptimeSeconds", it) }
        lastPingAt?.let { json.put("lastPingAt", it) }
        lastAckAt?.let { json.put("lastAckAt", it) }
        json.put("missedHeartbeats", missedHeartbeats)
        lastUpdatedAt?.let { json.put("updatedAt", it) }
        return json.takeUnless { it.length() == 0 }?.toString()
    }

    private fun Any.toIntOrNull(default: Int?): Int? {
        return when (this) {
            is Number -> toInt()
            is String -> toIntOrNull()
            else -> default
        }
    }

    private fun Any.toLongOrNull(default: Long?): Long? {
        return when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> default
        }
    }

    private fun Any.toStringOrNull(default: String?): String? {
        val value = toString()
        return value.takeIf { it.isNotBlank() } ?: default
    }

    private fun SnapshotRecord?.hasSameContent(other: SnapshotRecord?): Boolean {
        if (this == null || other == null) return false
        return caseJson == other.caseJson && leftJson == other.leftJson && rightJson == other.rightJson
    }
}
