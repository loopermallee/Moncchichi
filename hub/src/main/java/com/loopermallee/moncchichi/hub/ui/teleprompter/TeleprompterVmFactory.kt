package com.loopermallee.moncchichi.hub.ui.teleprompter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.loopermallee.moncchichi.hub.data.repo.SettingsRepository
import com.loopermallee.moncchichi.hub.data.db.MemoryRepository
import com.loopermallee.moncchichi.hub.model.Repository

class TeleprompterVmFactory(
    private val settingsRepository: SettingsRepository,
    private val repository: Repository,
    private val memoryRepository: MemoryRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TeleprompterViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TeleprompterViewModel(settingsRepository, repository, memoryRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}
