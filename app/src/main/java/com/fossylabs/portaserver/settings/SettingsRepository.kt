package com.fossylabs.portaserver.settings

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /** HuggingFace access token, required for gated repos. Null when unset. */
    val hfToken: String? = null,
    // ── Inference ────────────────────────────────────────────────────────────
    /** Context window, in tokens. Load-time: a reload is required to apply. */
    val nCtx: Int = DEFAULT_N_CTX,
    /** Inference threads, or 0 to derive from the CPU core count. Load-time. */
    val nThreads: Int = 0,
    /** Default sampling temperature; per-request values override it. */
    val temperature: Float = DEFAULT_TEMPERATURE,
    /** Default nucleus-sampling cutoff; per-request values override it. */
    val topP: Float = DEFAULT_TOP_P,
    /** Default response cap in tokens; per-request max_tokens overrides it. */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    /** Resolves [nThreads], substituting the derived default when set to auto. */
    fun resolvedThreads(availableCores: Int): Int =
        if (nThreads > 0) nThreads else maxOf(1, availableCores / 2)
}

// Defaults match the values these settings replaced, so behaviour is unchanged
// until a user edits them.
const val DEFAULT_N_CTX = 2048
const val DEFAULT_TEMPERATURE = 0.7f
const val DEFAULT_TOP_P = 0.9f
const val DEFAULT_MAX_TOKENS = 512

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
        val KEY_HF_TOKEN = stringPreferencesKey("hf_token")
        val KEY_N_CTX = intPreferencesKey("n_ctx")
        val KEY_N_THREADS = intPreferencesKey("n_threads")
        val KEY_TEMPERATURE = floatPreferencesKey("temperature")
        val KEY_TOP_P = floatPreferencesKey("top_p")
        val KEY_MAX_TOKENS = intPreferencesKey("max_tokens")
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
            hfToken = prefs[KEY_HF_TOKEN]?.takeIf { it.isNotBlank() },
            nCtx = prefs[KEY_N_CTX] ?: DEFAULT_N_CTX,
            nThreads = prefs[KEY_N_THREADS] ?: 0,
            temperature = prefs[KEY_TEMPERATURE] ?: DEFAULT_TEMPERATURE,
            topP = prefs[KEY_TOP_P] ?: DEFAULT_TOP_P,
            maxTokens = prefs[KEY_MAX_TOKENS] ?: DEFAULT_MAX_TOKENS,
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

    suspend fun setNCtx(value: Int) {
        dataStore.edit { it[KEY_N_CTX] = value }
    }

    suspend fun setNThreads(value: Int) {
        dataStore.edit { it[KEY_N_THREADS] = value }
    }

    suspend fun setTemperature(value: Float) {
        dataStore.edit { it[KEY_TEMPERATURE] = value }
    }

    suspend fun setTopP(value: Float) {
        dataStore.edit { it[KEY_TOP_P] = value }
    }

    suspend fun setMaxTokens(value: Int) {
        dataStore.edit { it[KEY_MAX_TOKENS] = value }
    }

    suspend fun setHfToken(token: String) {
        dataStore.edit { it[KEY_HF_TOKEN] = token.trim() }
    }

    /** Current HuggingFace token, or null when unset. */
    suspend fun hfToken(): String? = settings.first().hfToken

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

/**
 * Where downloads should be written: the explicitly chosen directory, otherwise the first
 * scan directory we actually hold write permission on.
 *
 * Scan directories were historically taken read-only, so holding the URI is not proof we
 * can write into it -- the grant has to be checked rather than assumed, or the download
 * fails at createDocument with a permission error instead of prompting.
 */
fun SettingsState.resolveDownloadDirectory(resolver: ContentResolver): String? {
    downloadDirectory?.let { return it }
    val writable = resolver.persistedUriPermissions
        .filter { it.isWritePermission }
        .mapTo(mutableSetOf()) { it.uri.toString() }
    return scanDirectories.firstOrNull { it in writable }
}
