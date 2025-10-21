package com.loopermallee.moncchichi.hub.di

import android.content.Context
import androidx.room.Room
import com.loopermallee.moncchichi.hub.data.db.MemoryDb
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.router.IntentRouter
import com.loopermallee.moncchichi.hub.tools.BleTool
import com.loopermallee.moncchichi.hub.tools.DisplayTool
import com.loopermallee.moncchichi.hub.tools.LlmTool
import com.loopermallee.moncchichi.hub.tools.PermissionTool
import com.loopermallee.moncchichi.hub.tools.SpeechTool
import com.loopermallee.moncchichi.hub.tools.impl.BleToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.DisplayToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.LlmToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.PermissionToolImpl
import com.loopermallee.moncchichi.hub.tools.impl.SpeechToolImpl

object AppLocator {
    lateinit var memory: MemoryRepository
        private set
    lateinit var router: IntentRouter
        private set
    lateinit var ble: BleTool
        private set
    lateinit var speech: SpeechTool
        private set
    lateinit var llm: LlmTool
        private set
    lateinit var display: DisplayTool
        private set
    lateinit var perms: PermissionTool
        private set

    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        val appCtx = ctx.applicationContext
        val db = Room.databaseBuilder(appCtx, MemoryDb::class.java, "moncchichi.db")
            .fallbackToDestructiveMigration()
            .build()
        memory = MemoryRepository(db.dao())
        router = IntentRouter()
        ble = BleToolImpl(appCtx)
        speech = SpeechToolImpl(appCtx)
        llm = LlmToolImpl(appCtx)
        display = DisplayToolImpl(appCtx)
        perms = PermissionToolImpl(appCtx)
        initialized = true
    }
}
