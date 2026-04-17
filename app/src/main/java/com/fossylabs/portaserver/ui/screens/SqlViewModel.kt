package com.fossylabs.portaserver.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fossylabs.portaserver.server.ServerManager
import com.fossylabs.portaserver.server.ServerState
import com.fossylabs.portaserver.settings.SettingsRepository
import com.fossylabs.portaserver.settings.SettingsState
import com.fossylabs.portaserver.settings.settingsDataStore
import com.fossylabs.portaserver.sql.QueryResult
import com.fossylabs.portaserver.sql.SqliteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SqlViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application.settingsDataStore)

    val serverState: StateFlow<ServerState> = ServerManager.state

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState()
    )

    private val _databases = MutableStateFlow<List<String>>(emptyList())
    val databases: StateFlow<List<String>> = _databases.asStateFlow()

    private val _selectedDb = MutableStateFlow<String?>(null)
    val selectedDb: StateFlow<String?> = _selectedDb.asStateFlow()

    private val _tables = MutableStateFlow<List<String>>(emptyList())
    val tables: StateFlow<List<String>> = _tables.asStateFlow()

    private val _queryResult = MutableStateFlow<QueryResult?>(null)
    val queryResult: StateFlow<QueryResult?> = _queryResult.asStateFlow()

    init {
        SqliteManager.configure(application.getExternalFilesDir(null) ?: application.filesDir)
        refreshDatabases()
    }

    fun refreshDatabases() {
        viewModelScope.launch {
            _databases.value = withContext(Dispatchers.IO) { SqliteManager.listDatabases() }
        }
    }

    fun createDatabase(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { SqliteManager.openOrCreate(name) }
            refreshDatabases()
        }
    }

    fun selectDatabase(name: String) {
        _selectedDb.value = name
        viewModelScope.launch {
            _tables.value = withContext(Dispatchers.IO) { SqliteManager.listTables(name) }
        }
    }

    fun executeQuery(sql: String) {
        val db = _selectedDb.value ?: return
        viewModelScope.launch {
            _queryResult.value = withContext(Dispatchers.IO) { SqliteManager.executeQuery(db, sql) }
        }
    }

    fun clearQueryResult() {
        _queryResult.value = null
    }

    fun startServer() {
        val s = settings.value
        val timeoutMs = s.inactivityTimeoutMinutes?.let { it.toLong() * 60_000L }
        ServerManager.start(getApplication(), s.llmPort, s.sqlPort, timeoutMs)
    }

    fun stopServer() {
        ServerManager.stop(getApplication())
    }
}
