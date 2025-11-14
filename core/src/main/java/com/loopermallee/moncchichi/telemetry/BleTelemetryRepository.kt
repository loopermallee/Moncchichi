package com.loopermallee.moncchichi.telemetry

import com.loopermallee.moncchichi.bluetooth.G1MessageParser
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService.Lens
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
        val caseOpen: Boolean? = null,
        val inCase: Boolean? = null,
        val foldState: Boolean? = null,
        val lastVitalsTimestamp: Long? = null,
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
        val caseOpen: Boolean? = null,
        val inCase: Boolean? = null,
        val foldState: Boolean? = null,
        val lastVitalsTimestamp: Long? = null,
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

    suspend fun recordTelemetry(side: Lens, telemetry: Map<String, Any>) {
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

    suspend fun recordTelemetry(side: Lens, telemetry: G1MessageParser.TelemetryUpdate) {
        val values = telemetry.toRepositoryMap()
        if (values.isEmpty()) return
        recordTelemetry(side, values)
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

    suspend fun updateHeartbeat(side: Lens, lastPingAt: Long, lastAckAt: Long, missedCount: Int) {
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

    private fun Snapshot.lens(side: Lens): LensSnapshot {
        return when (side) {
            Lens.LEFT -> left
            Lens.RIGHT -> right
        }
    }

    private fun Snapshot.update(side: Lens, lens: LensSnapshot, timestamp: Long): Snapshot {
        val other = when (side) {
            Lens.LEFT -> right
            Lens.RIGHT -> left
        }
        val mergedCaseOpen = lens.caseOpen ?: other.caseOpen ?: caseOpen
        val mergedInCase = lens.inCase ?: other.inCase ?: inCase
        val mergedFoldState = lens.foldState ?: other.foldState ?: foldState
        val mergedVitals = maxOfNonNull(lens.lastVitalsTimestamp, other.lastVitalsTimestamp, lastVitalsTimestamp)
        val normalizedLens = lens.withSharedValues(mergedCaseOpen, mergedInCase, mergedFoldState, mergedVitals)
        val normalizedOther = other.withSharedValues(mergedCaseOpen, mergedInCase, mergedFoldState, mergedVitals)
        return when (side) {
            Lens.LEFT -> copy(
                left = normalizedLens,
                right = normalizedOther,
                caseOpen = mergedCaseOpen,
                inCase = mergedInCase,
                foldState = mergedFoldState,
                lastVitalsTimestamp = mergedVitals,
                recordedAt = timestamp,
            )
            Lens.RIGHT -> copy(
                left = normalizedOther,
                right = normalizedLens,
                caseOpen = mergedCaseOpen,
                inCase = mergedInCase,
                foldState = mergedFoldState,
                lastVitalsTimestamp = mergedVitals,
                recordedAt = timestamp,
            )
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
                "caseOpen" -> snapshot = snapshot.copy(caseOpen = raw.toBooleanOrNull(snapshot.caseOpen))
                "inCase" -> snapshot = snapshot.copy(inCase = raw.toBooleanOrNull(snapshot.inCase))
                "foldState", "folded" -> snapshot = snapshot.copy(foldState = raw.toBooleanOrNull(snapshot.foldState))
                "lastVitalsTimestamp" -> snapshot = snapshot.copy(lastVitalsTimestamp = raw.toLongOrNull(snapshot.lastVitalsTimestamp))
            }
        }
        snapshot = snapshot.copy(lastVitalsTimestamp = maxOfNonNull(snapshot.lastVitalsTimestamp, timestamp))
        return snapshot
    }

    private fun LensSnapshot.withSharedValues(
        caseOpenValue: Boolean?,
        inCaseValue: Boolean?,
        foldStateValue: Boolean?,
        lastVitalsValue: Long?,
    ): LensSnapshot {
        return copy(
            caseOpen = caseOpen ?: caseOpenValue,
            inCase = inCase ?: inCaseValue,
            foldState = foldState ?: foldStateValue,
            lastVitalsTimestamp = lastVitalsTimestamp ?: lastVitalsValue,
        )
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
        caseOpen?.let { json.put("caseOpen", it) }
        inCase?.let { json.put("inCase", it) }
        foldState?.let {
            json.put("foldState", it)
            json.put("folded", it)
        }
        lastVitalsTimestamp?.let { json.put("lastVitalsTimestamp", it) }
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

    private fun Any.toBooleanOrNull(default: Boolean?): Boolean? {
        return when (this) {
            is Boolean -> this
            is Number -> when (toInt()) {
                0 -> false
                1 -> true
                else -> default
            }
            is String -> when {
                equals("true", ignoreCase = true) -> true
                equals("false", ignoreCase = true) -> false
                this == "1" -> true
                this == "0" -> false
                else -> default
            }
            else -> default
        }
    }

    fun isSleeping(lens: Lens, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val snapshot = _snapshot.value
        val lensSnapshot = snapshot.lens(lens)
        val resolvedCaseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        val resolvedInCase = lensSnapshot.inCase ?: snapshot.inCase
        val resolvedFoldState = lensSnapshot.foldState ?: snapshot.foldState
        val lastVitals = lensSnapshot.lastVitalsTimestamp ?: snapshot.lastVitalsTimestamp
        if (resolvedCaseOpen == null || resolvedInCase == null || resolvedFoldState == null || lastVitals == null) {
            return false
        }
        val vitalsFresh = nowMillis - lastVitals <= VITALS_SLEEP_TIMEOUT_MS
        return (resolvedCaseOpen == false) && (resolvedInCase == true) && (resolvedFoldState == true) && vitalsFresh
    }

    fun isAwake(lens: Lens, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val snapshot = _snapshot.value
        val lensSnapshot = snapshot.lens(lens)
        val resolvedCaseOpen = lensSnapshot.caseOpen ?: snapshot.caseOpen
        val resolvedInCase = lensSnapshot.inCase ?: snapshot.inCase
        val resolvedFoldState = lensSnapshot.foldState ?: snapshot.foldState
        val lastVitals = lensSnapshot.lastVitalsTimestamp ?: snapshot.lastVitalsTimestamp
        val vitalsStale = lastVitals?.let { nowMillis - it > VITALS_SLEEP_TIMEOUT_MS } ?: true
        if (resolvedCaseOpen == null && resolvedInCase == null && resolvedFoldState == null && lastVitals == null) {
            return false
        }
        if (resolvedCaseOpen == true) return true
        if (resolvedInCase == false) return true
        if (resolvedFoldState == false) return true
        return vitalsStale
    }

    private fun SnapshotRecord?.hasSameContent(other: SnapshotRecord?): Boolean {
        if (this == null || other == null) return false
        return caseJson == other.caseJson && leftJson == other.leftJson && rightJson == other.rightJson
    }

    companion object {
        private const val VITALS_SLEEP_TIMEOUT_MS = 3_000L
    }
}

private fun G1MessageParser.TelemetryUpdate.toRepositoryMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    batteryPercent?.let { map["batteryPercent"] = it }
    firmwareVersion?.let { map["firmwareVersion"] = it }
    rssi?.let { map["rssi"] = it }
    caseOpen?.let { map["caseOpen"] = it }
    inCase?.let { map["inCase"] = it }
    foldState?.let { map["foldState"] = it }
    return map
}

private fun maxOfNonNull(vararg values: Long?): Long? {
    return values.filterNotNull().maxOrNull()
}
