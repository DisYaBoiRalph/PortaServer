package com.fossylabs.portaserver.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class FileMeta(val expectedSize: Long, val sha256: String?)

@Serializable
private data class FileMetaEntry(val uri: String, val size: Long, val sha256: String?)

@Serializable
private data class HfFileMetaEntry(val modelId: String, val fileName: String, val size: Long, val sha256: String?)

private val metaJson = Json { ignoreUnknownKeys = true }

data class SettingsState(
    val backgroundEnabled: Boolean = true,
    val inactivityTimeoutMinutes: Int? = null,
    val llmPort: Int = 8080,
    val sqlPort: Int = 8181,
    val scanDirectories: Set<String> = emptySet(),
    val downloadDirectory: String? = null,
    val fileMetadata: Map<String, FileMeta> = emptyMap(),
    val hfFileMetadata: Map<String, FileMeta> = emptyMap(),
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private companion object {
        val KEY_BACKGROUND_ENABLED = booleanPreferencesKey("background_enabled")
        val KEY_INACTIVITY_TIMEOUT = intPreferencesKey("inactivity_timeout_minutes")
        val KEY_LLM_PORT = intPreferencesKey("llm_port")
        val KEY_SQL_PORT = intPreferencesKey("sql_port")
        val KEY_SCAN_DIRS = stringSetPreferencesKey("scan_directories")
        val KEY_DOWNLOAD_DIR = stringPreferencesKey("download_directory")
        val KEY_FILE_METADATA = stringSetPreferencesKey("file_metadata")
        val KEY_HF_FILE_METADATA = stringSetPreferencesKey("hf_file_metadata")
        const val TIMEOUT_DISABLED = -1
    }

    val settings: Flow<SettingsState> = dataStore.data.map { prefs ->
        SettingsState(
            backgroundEnabled = prefs[KEY_BACKGROUND_ENABLED] ?: true,
            inactivityTimeoutMinutes = prefs[KEY_INACTIVITY_TIMEOUT]
                ?.takeIf { it != TIMEOUT_DISABLED },
            llmPort = prefs[KEY_LLM_PORT] ?: 8080,
            sqlPort = prefs[KEY_SQL_PORT] ?: 8181,
            scanDirectories = prefs[KEY_SCAN_DIRS] ?: emptySet(),
            downloadDirectory = prefs[KEY_DOWNLOAD_DIR],
            fileMetadata = (prefs[KEY_FILE_METADATA] ?: emptySet()).mapNotNull { raw ->
                runCatching {
                    if (raw.startsWith("{")) {
                        val e = metaJson.decodeFromString<FileMetaEntry>(raw)
                        e.uri to FileMeta(e.size, e.sha256)
                    } else {
                        // legacy tab-delimited fallback
                        val parts = raw.split('\t', limit = 3)
                        val uri = parts[0]
                        val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val sha256 = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                        uri to FileMeta(size, sha256)
                    }
                }.getOrNull()
            }.toMap(),
            hfFileMetadata = (prefs[KEY_HF_FILE_METADATA] ?: emptySet()).mapNotNull { raw ->
                runCatching {
                    if (raw.startsWith("{")) {
                        val e = metaJson.decodeFromString<HfFileMetaEntry>(raw)
                        "hf://${e.modelId}/${e.fileName}" to FileMeta(e.size, e.sha256)
                    } else {
                        // legacy tab-delimited fallback
                        val parts = raw.split('\t', limit = 4)
                        val modelId = parts.getOrNull(0) ?: ""
                        val filename = parts.getOrNull(1) ?: ""
                        val key = "hf://$modelId/$filename"
                        val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        val sha256 = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
                        key to FileMeta(size, sha256)
                    }
                }.getOrNull()
            }.toMap(),
        )
    }

    suspend fun setBackgroundEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKGROUND_ENABLED] = enabled }
    }

    suspend fun setInactivityTimeout(minutes: Int?) {
        dataStore.edit { it[KEY_INACTIVITY_TIMEOUT] = minutes ?: TIMEOUT_DISABLED }
    }

    suspend fun setLlmPort(port: Int) {
        dataStore.edit { it[KEY_LLM_PORT] = port }
    }

    suspend fun setSqlPort(port: Int) {
        dataStore.edit { it[KEY_SQL_PORT] = port }
    }

    suspend fun addScanDirectory(uriString: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SCAN_DIRS] ?: emptySet()
            prefs[KEY_SCAN_DIRS] = current + uriString
        }
    }

    suspend fun removeScanDirectory(uriString: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SCAN_DIRS] ?: emptySet()
            prefs[KEY_SCAN_DIRS] = current - uriString
        }
    }

    suspend fun setDownloadDirectory(uriString: String) {
        dataStore.edit { it[KEY_DOWNLOAD_DIR] = uriString }
    }

    suspend fun saveFileMeta(fileUri: String, expectedSize: Long, sha256: String?) {
        val entry = metaJson.encodeToString(FileMetaEntry(fileUri, expectedSize, sha256))
        dataStore.edit { prefs ->
            val current = prefs[KEY_FILE_METADATA] ?: emptySet()
            // Remove any existing entry for this URI (both JSON and legacy formats)
            prefs[KEY_FILE_METADATA] = current.filter { raw ->
                if (raw.startsWith("{")) {
                    runCatching { metaJson.decodeFromString<FileMetaEntry>(raw).uri != fileUri }.getOrDefault(true)
                } else {
                    !raw.startsWith("$fileUri\t")
                }
            }.toSet() + entry
        }
    }

    suspend fun saveRemoteFileMeta(modelId: String, fileName: String, expectedSize: Long?, sha256: String?) {
        val entry = metaJson.encodeToString(HfFileMetaEntry(modelId, fileName, expectedSize ?: 0L, sha256))
        dataStore.edit { prefs ->
            val current = prefs[KEY_HF_FILE_METADATA] ?: emptySet()
            // Remove any existing entry for this modelId+fileName (both JSON and legacy formats)
            prefs[KEY_HF_FILE_METADATA] = current.filter { raw ->
                if (raw.startsWith("{")) {
                    runCatching {
                        val e = metaJson.decodeFromString<HfFileMetaEntry>(raw)
                        e.modelId != modelId || e.fileName != fileName
                    }.getOrDefault(true)
                } else {
                    !raw.startsWith("$modelId\t$fileName\t")
                }
            }.toSet() + entry
        }
    }

    suspend fun removeFileMeta(fileUri: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_FILE_METADATA] ?: emptySet()
            prefs[KEY_FILE_METADATA] = current.filter { raw ->
                if (raw.startsWith("{")) {
                    runCatching { metaJson.decodeFromString<FileMetaEntry>(raw).uri != fileUri }.getOrDefault(true)
                } else {
                    !raw.startsWith("$fileUri\t")
                }
            }.toSet()
        }
    }
}
