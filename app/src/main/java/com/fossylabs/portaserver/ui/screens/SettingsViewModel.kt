package com.fossylabs.portaserver.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fossylabs.portaserver.settings.SettingsRepository
import com.fossylabs.portaserver.settings.SettingsState
import com.fossylabs.portaserver.settings.settingsDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.settingsDataStore)

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
}
