package com.loopermallee.moncchichi.hub.di

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.room.Room
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.db.MemoryDb
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.data.diagnostics.DiagnosticRepository
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.TtsTool
import com.loopermallee.moncchichi.hub.tools.impl.BleToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.BleToolLiveImpl
import com.loopermallee.moncchichi.hub.tools.impl.DisplayToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.LlmToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.PermissionToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.TtsToolImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppLocator {
    lateinit var memory: MemoryRepository
        private set
    lateinit var router: IntentRouter
        private set
    lateinit var ble: BleTool
        private set
    lateinit var llm: LlmTool
        private set
    lateinit var display: DisplayTool
        private set
    lateinit var perms: PermissionTool
        private set
    lateinit var tts: TtsTool
        private set
    lateinit var telemetry: BleTelemetryRepository
        private set
    lateinit var prefs: SharedPreferences
        private set
    lateinit var diagnostics: DiagnosticRepository
        private set

    private var initialized = false
    private const val useLiveBle: Boolean = true
    private lateinit var appScope: CoroutineScope
    private lateinit var bleService: MoncchichiBleService
    private lateinit var bleScanner: BluetoothScanner

    fun init(ctx: Context) {
        if (initialized) return
        val appCtx = ctx.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(appCtx)

        val db = Room.databaseBuilder(appCtx, MemoryDb::class.java, "moncchichi.db")
            .fallbackToDestructiveMigration()
            .build()
        memory = MemoryRepository(db.dao())
        router = IntentRouter()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        telemetry = BleTelemetryRepository()
        ble = if (useLiveBle) {
            bleScanner = BluetoothScanner(appCtx)
            bleService = MoncchichiBleService(appCtx, appScope)
            BleToolLiveImpl(appCtx, bleService, telemetry, bleScanner, appScope)
        } else {
            BleToolImpl(appCtx)
        }
        llm = LlmToolImpl(appCtx)
        display = DisplayToolImpl(appCtx)
        perms = PermissionToolImpl(appCtx)
        tts = TtsToolImpl(appCtx)
        diagnostics = DiagnosticRepository(appCtx, memory)
        initialized = true
    }
}
