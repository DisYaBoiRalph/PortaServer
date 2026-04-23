package com.fossylabs.portaserver.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fossylabs.portaserver.llm.ModelCacheManager
import com.fossylabs.portaserver.settings.SettingsRepository
import com.fossylabs.portaserver.settings.SettingsState
import com.fossylabs.portaserver.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.settingsDataStore)

    private val _isClearingModelCache = MutableStateFlow(false)
    val isClearingModelCache: StateFlow<Boolean> = _isClearingModelCache.asStateFlow()

    private val _clearModelCacheMessage = MutableStateFlow<String?>(null)
    val clearModelCacheMessage: StateFlow<String?> = _clearModelCacheMessage.asStateFlow()

    val settings = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsState(),
    )

    fun setBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setBackgroundEnabled(enabled) }
    }

    fun setInactivityTimeout(minutes: Int?) {
        viewModelScope.launch { repository.setInactivityTimeout(minutes) }
    }

    fun setLlmPort(port: Int) {
        viewModelScope.launch { repository.setLlmPort(port) }
    }

    fun setSqlPort(port: Int) {
        viewModelScope.launch { repository.setSqlPort(port) }
    }

    fun addScanDirectory(uriString: String) {
        viewModelScope.launch { repository.addScanDirectory(uriString) }
    }

    fun removeScanDirectory(uriString: String) {
        viewModelScope.launch { repository.removeScanDirectory(uriString) }
    }

    fun clearModelCache() {
        if (_isClearingModelCache.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isClearingModelCache.value = true
            _clearModelCacheMessage.value = null
            try {
                val result = ModelCacheManager.clearModelCache(getApplication<Application>().cacheDir)
                _clearModelCacheMessage.value = when {
                    result.deletedFiles == 0 && result.failedFiles == 0 -> "Model cache is already empty."
                    result.failedFiles == 0 ->
                        "Cleared ${formatBytes(result.freedBytes)} from ${result.deletedFiles} cache file(s)."
                    else ->
                        "Cleared ${formatBytes(result.freedBytes)} from ${result.deletedFiles} cache file(s); ${result.failedFiles} file(s) could not be removed."
                }
            } finally {
                _isClearingModelCache.value = false
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx += 1
        }
        return if (unitIdx == 0) {
            "${value.toLong()} ${units[unitIdx]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIdx])
        }
    }
}
