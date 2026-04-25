package com.fossylabs.portaserver.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fossylabs.portaserver.llm.DeviceSpecs
import com.fossylabs.portaserver.llm.DeviceSpecsReader
import com.fossylabs.portaserver.llm.HuggingFaceFileDto
import com.fossylabs.portaserver.llm.LlmInferenceEngine
import com.fossylabs.portaserver.llm.ModelCacheManager
import com.fossylabs.portaserver.llm.ModelInfo
import com.fossylabs.portaserver.llm.ModelRecommender
import com.fossylabs.portaserver.llm.ModelRepository
import com.fossylabs.portaserver.llm.ModelTier
import com.fossylabs.portaserver.service.ServerForegroundService
import com.fossylabs.portaserver.server.ServerManager
import com.fossylabs.portaserver.server.ServerState
import com.fossylabs.portaserver.settings.SettingsRepository
import com.fossylabs.portaserver.settings.settingsDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.json.Json
import com.fossylabs.portaserver.llm.DownloadState
import com.fossylabs.portaserver.llm.ModelDownloadManager
import java.io.File
import java.util.Locale

class LlmViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val IO_BUFFER_SIZE = 8192
        val GGUF_SPLIT_NAME_REGEX = Regex("^(.*)-(\\d{5})-of-(\\d{5})(\\.gguf)$", RegexOption.IGNORE_CASE)
    }

    private val settingsRepo = SettingsRepository(application.settingsDataStore)
    private val deviceSpecsReader = DeviceSpecsReader(application)
    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    private val modelRepository = ModelRepository(httpClient, application.contentResolver)

    val serverState: StateFlow<ServerState> = ServerManager.state

    val loadedModel: StateFlow<ModelInfo?> = LlmInferenceEngine.loadedModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isPreparingModelLoad = MutableStateFlow(false)

    val isLoadingModel: StateFlow<Boolean> = combine(
        LlmInferenceEngine.isLoading,
        _isPreparingModelLoad,
    ) { engineLoading, preparing -> engineLoading || preparing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _deviceSpecs = MutableStateFlow<DeviceSpecs?>(null)
    val deviceSpecs: StateFlow<DeviceSpecs?> = _deviceSpecs.asStateFlow()

    private val _modelTier = MutableStateFlow<ModelTier?>(null)
    val modelTier: StateFlow<ModelTier?> = _modelTier.asStateFlow()

    private val _localModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val localModels: StateFlow<List<ModelInfo>> = _localModels.asStateFlow()

    private val _hfModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val hfModels: StateFlow<List<ModelInfo>> = _hfModels.asStateFlow()

    private val _isFetchingHf = MutableStateFlow(false)
    val isFetchingHf: StateFlow<Boolean> = _isFetchingHf.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Model detail sheet ────────────────────────────────────────────────────

    private val _selectedHfModel = MutableStateFlow<ModelInfo?>(null)
    val selectedHfModel: StateFlow<ModelInfo?> = _selectedHfModel.asStateFlow()

    private val _hfModelFiles = MutableStateFlow<List<HuggingFaceFileDto>>(emptyList())
    val hfModelFiles: StateFlow<List<HuggingFaceFileDto>> = _hfModelFiles.asStateFlow()

    private val _isFetchingFiles = MutableStateFlow(false)
    val isFetchingFiles: StateFlow<Boolean> = _isFetchingFiles.asStateFlow()

    private val downloadManager = ModelDownloadManager(
        application = application,
        httpClient = httpClient,
        settingsRepo = settingsRepo,
        scope = viewModelScope,
        onError = { msg -> _errorMessage.value = msg },
        onRefreshLocalModels = ::refreshLocalModels,
    )
    val downloadStates: StateFlow<Map<String, DownloadState>> = downloadManager.downloadStates
    private val modelCacheLock = Any()
    private var activeModelCachePaths: Set<String> = emptySet()

    // ──────────────────────────────────────────────────────────────────────────

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        com.fossylabs.portaserver.settings.SettingsState()
    )

    init {
        refreshDeviceSpecs()
        refreshLocalModels()
        viewModelScope.launch(Dispatchers.IO) {
            val cleanup = ModelCacheManager.clearModelCache(getApplication<Application>().cacheDir)
            if (cleanup.deletedFiles > 0 || cleanup.failedFiles > 0) {
                Log.i(
                    "LlmViewModel",
                    "Startup model cache cleanup: deleted=${cleanup.deletedFiles}, failed=${cleanup.failedFiles}, freedBytes=${cleanup.freedBytes}",
                )
            }
        }
    }

    fun refreshDeviceSpecs() {
        val specs = deviceSpecsReader.read()
        _deviceSpecs.value = specs
        _modelTier.value = ModelRecommender.recommend(specs)
        viewModelScope.launch(Dispatchers.IO) {
            _localIp.value = getLocalIpAddress()
        }
    }

    fun refreshLocalModels() {
        viewModelScope.launch {
            val storedSettings = settingsRepo.settings.first()
            val dirs = storedSettings.scanDirectories
            val metadata = storedSettings.fileMetadata
            val hfMetadata = storedSettings.hfFileMetadata
            val tier = _modelTier.value
            // Scan + size check (metadata-only query — fast)
            val scanned = withContext(Dispatchers.IO) {
                modelRepository.scanLocalModels(dirs)
                    .map { model ->
                        // Prefer local metadata (saved at download), fallback to HF-provided metadata by filename
                        val localMeta = metadata[model.path]
                        val remoteMeta = hfMetadata.entries.firstOrNull { it.key.substringAfterLast("/") == model.name }?.value
                        val meta = localMeta ?: remoteMeta
                        val sizeOk = if (meta != null && meta.expectedSize > 0L) {
                            downloadManager.getDocumentSize(Uri.parse(model.path)) == meta.expectedSize
                        } else true
                        model.copy(
                            isRecommended = tier?.let { ModelRecommender.fitsInTier(model.name, it) } ?: false,
                            isCorrupted = !sizeOk,
                        )
                    }
                    .sortedByDescending { it.isRecommended }
            }
            _localModels.value = scanned
            // Background SHA256 check for files that passed size check; consider both local and HF metadata
            val hasAnySha = metadata.any { it.value.sha256 != null } || hfMetadata.any { it.value.sha256 != null }
            if (hasAnySha) {
                launch(Dispatchers.IO) {
                    val updated = scanned.map { model ->
                        if (model.isCorrupted) return@map model
                        val localSha = metadata[model.path]?.sha256
                        val remoteSha = hfMetadata.entries.firstOrNull { it.key.substringAfterLast("/") == model.name }?.value?.sha256
                        val candidateSha = localSha ?: remoteSha ?: return@map model
                        val actual = downloadManager.computeSha256(Uri.parse(model.path))
                        if (actual != candidateSha) model.copy(isCorrupted = true) else model
                    }
                    _localModels.value = updated
                }
            }
        }
    }

    fun fetchHuggingFaceModels() {
        viewModelScope.launch {
            _isFetchingHf.value = true
            try {
                val tier = _modelTier.value
                _hfModels.value = modelRepository.fetchHuggingFaceModels()
                    .map { model ->
                        model.copy(isRecommended = tier?.let { ModelRecommender.fitsInTier(model.name, it) } ?: false)
                    }
                    .sortedByDescending { it.isRecommended }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to fetch models: ${e.message}"
            } finally {
                _isFetchingHf.value = false
            }
        }
    }

    fun selectHfModel(model: ModelInfo?) {
        _selectedHfModel.value = model
        _hfModelFiles.value = emptyList()
        if (model != null) {
            viewModelScope.launch {
                _isFetchingFiles.value = true
                try {
                    val files = modelRepository.fetchModelFiles(model.name)
                    _hfModelFiles.value = files
                    // Persist HF-provided metadata so we can validate local files later
                    for (file in files) {
                        try {
                            val size = file.lfs?.size ?: file.size
                            settingsRepo.saveRemoteFileMeta(model.name, file.rfilename, size, file.lfs?.sha256)
                        } catch (_: Exception) {
                        }
                    }
                    // Pre-populate downloadStates from already-present local models
                    val currentLocals = _localModels.value
                    val prePopulated = buildMap {
                        for (file in files) {
                            val local = currentLocals.firstOrNull { it.name == file.rfilename }
                            if (local != null) put(file.rfilename, DownloadState(1f, done = true, fileUri = local.path))
                        }
                    }
                    downloadManager.prePopulateStates(prePopulated)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to fetch files for ${model.name}: ${e.message}"
                } finally {
                    _isFetchingFiles.value = false
                }
            }
        }
    }

    fun setDownloadDirectory(uriString: String) {
        viewModelScope.launch {
            settingsRepo.setDownloadDirectory(uriString)
            // Also add as scan directory so downloaded models appear automatically
            settingsRepo.addScanDirectory(uriString)
        }
    }

    fun downloadFile(modelId: String, fileName: String) {
        val downloadDirUri = settings.value.downloadDirectory ?: return
        downloadManager.downloadFile(modelId, fileName, downloadDirUri, _hfModelFiles.value)
    }

    fun cancelDownload(fileName: String) {
        downloadManager.cancelDownload(fileName)
    }

    fun deleteLocalModel(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(path)
                DocumentsContract.deleteDocument(
                    getApplication<Application>().contentResolver, uri
                )
                settingsRepo.removeFileMeta(path)
            } catch (_: Exception) {}
            refreshLocalModels()
        }
    }

    private fun getLocalIpAddress(): String? = ServerForegroundService.getLocalIpAddress()

    private data class SplitFileName(
        val prefix: String,
        val partIndex: Int,
        val totalParts: Int,
        val extension: String,
    )

    private fun parseSplitFileName(name: String): SplitFileName? {
        val match = GGUF_SPLIT_NAME_REGEX.matchEntire(name) ?: return null
        val prefix = match.groupValues[1]
        val partIndex = match.groupValues[2].toIntOrNull() ?: return null
        val totalParts = match.groupValues[3].toIntOrNull() ?: return null
        val extension = match.groupValues[4].lowercase(Locale.US)
        if (partIndex <= 0 || totalParts <= 1 || partIndex > totalParts) return null
        return SplitFileName(
            prefix = prefix,
            partIndex = partIndex,
            totalParts = totalParts,
            extension = extension,
        )
    }

    private fun buildSplitPartName(split: SplitFileName, index: Int): String {
        val indexPadded = String.format(Locale.US, "%05d", index)
        val totalPadded = String.format(Locale.US, "%05d", split.totalParts)
        return "${split.prefix}-$indexPadded-of-$totalPadded${split.extension}"
    }

    private fun queryDocumentDisplayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }

    private fun documentParentHint(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parent = docId.substringBeforeLast('/', "")
        return parent.takeIf { it.isNotBlank() }
    }

    private fun ensureCachedModelFile(sourceUri: Uri, cacheKey: String, preferredName: String): File {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val cacheFile = ModelCacheManager.cacheFileForUri(app.cacheDir, cacheKey, preferredName)
        val sourceSize = downloadManager.getDocumentSize(sourceUri).takeIf { it > 0L }
        val canReuseCache = sourceSize != null && cacheFile.exists() && cacheFile.length() == sourceSize

        if (canReuseCache) {
            Log.i("LlmViewModel", "Reusing existing model cache file: ${cacheFile.absolutePath}")
            return cacheFile
        }

        val partFile = File("${cacheFile.absolutePath}.part")
        try {
            if (partFile.exists()) partFile.delete()
            resolver.openInputStream(sourceUri)?.use { input ->
                Log.i("LlmViewModel", "Copying SAF content to cache file: ${cacheFile.absolutePath}")
                partFile.outputStream().use { output ->
                    input.copyTo(output, IO_BUFFER_SIZE)
                }
            } ?: error("Cannot open model input stream: $sourceUri")

            if (cacheFile.exists() && !cacheFile.delete()) {
                error("Cannot replace cache file: ${cacheFile.absolutePath}")
            }
            if (!partFile.renameTo(cacheFile)) {
                partFile.inputStream().use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, IO_BUFFER_SIZE)
                    }
                }
                partFile.delete()
            }

            sourceSize?.let { expected ->
                val actual = cacheFile.length()
                if (actual != expected) {
                    error("Cache copy size mismatch for $preferredName: expected=$expected, actual=$actual")
                }
            }
        } catch (e: Exception) {
            partFile.delete()
            cacheFile.delete()
            throw e
        }

        return cacheFile
    }

    private fun getActiveModelCachePaths(): Set<String> = synchronized(modelCacheLock) {
        activeModelCachePaths
    }

    private fun setActiveModelCachePaths(paths: Set<String>) {
        synchronized(modelCacheLock) {
            activeModelCachePaths = paths
        }
    }

    fun loadModel(path: String) {
        if (!_isPreparingModelLoad.compareAndSet(false, true)) return
        viewModelScope.launch {
            var engineLoadAttempted = false
            var newActiveCachePaths: Set<String> = emptySet()
            try {
                if (path.startsWith("content://")) {
                    // SAF content URI — first try native fd loading.
                    // If provider semantics block reopen-from-proc behavior, fall back
                    // to deterministic cache copy and load from the cache path.
                    withContext(Dispatchers.IO) {
                        val app = getApplication<Application>()
                        val resolver = app.contentResolver
                        val uri = Uri.parse(path)
                        val selectedModel = _localModels.value.firstOrNull { it.path == path }
                        val selectedFileName = selectedModel?.name
                            ?: queryDocumentDisplayName(uri)
                            ?: "model.gguf"

                        // Fast path: open PFD and call native fd loader.
                        var fastPathOk = false
                        try {
                            val pfd = resolver.openFileDescriptor(uri, "r")
                                ?: error("Cannot open model file: $path")
                            pfd.use {
                                Log.i(
                                    "LlmViewModel",
                                    "Loading model via native fd loader: fd=${it.fd}, name=$selectedFileName",
                                )
                                engineLoadAttempted = true
                                LlmInferenceEngine.loadModelFromFd(it.fd, selectedFileName)
                            }
                            fastPathOk = true
                        } catch (e: Exception) {
                            Log.i("LlmViewModel", "Native fd load failed, will copy to cache: ${e.message}")
                        }

                        if (!fastPathOk) {
                            val copiedCacheFiles = linkedSetOf<File>()
                            val selectedSplit = parseSplitFileName(selectedFileName)
                            val selectedParent = documentParentHint(uri)

                            val entryCacheFile = if (selectedSplit != null) {
                                val expectedPartNames = (1..selectedSplit.totalParts)
                                    .map { index -> buildSplitPartName(selectedSplit, index) }

                                val candidateModels = _localModels.value.filter { model ->
                                    if (selectedParent == null) return@filter true
                                    val modelUri = runCatching { Uri.parse(model.path) }.getOrNull()
                                    modelUri != null && documentParentHint(modelUri) == selectedParent
                                }
                                val modelsByName = candidateModels.associateBy { it.name.lowercase(Locale.US) }
                                val missingParts = expectedPartNames.filter { partName ->
                                    modelsByName[partName.lowercase(Locale.US)] == null
                                }

                                if (missingParts.isNotEmpty()) {
                                    error(
                                        "Split model detected ($selectedFileName) but missing " +
                                            "${missingParts.size}/${selectedSplit.totalParts} parts in scanned directories",
                                    )
                                }

                                var selectedPartCache: File? = null
                                for (partName in expectedPartNames) {
                                    val model = modelsByName[partName.lowercase(Locale.US)]
                                        ?: error("Missing split part metadata for $partName")
                                    val partUri = Uri.parse(model.path)
                                    val partCache = ensureCachedModelFile(partUri, model.path, partName)
                                    copiedCacheFiles.add(partCache)
                                    if (partName.equals(selectedFileName, ignoreCase = true)) {
                                        selectedPartCache = partCache
                                    }
                                }

                                selectedPartCache ?: copiedCacheFiles.firstOrNull()
                                ?: error("Split model cache copy failed for $selectedFileName")
                            } else {
                                val cacheFile = ensureCachedModelFile(uri, uri.toString(), selectedFileName)
                                copiedCacheFiles.add(cacheFile)
                                cacheFile
                            }

                            try {
                                engineLoadAttempted = true
                                LlmInferenceEngine.loadModel(entryCacheFile.absolutePath)
                            } catch (e: Exception) {
                                // Remove copied files when load fails to avoid cache bloat.
                                copiedCacheFiles.forEach { it.delete() }
                                throw e
                            }

                            newActiveCachePaths = copiedCacheFiles.map { it.absolutePath }.toSet()
                        }
                    }
                } else {
                    engineLoadAttempted = true
                    LlmInferenceEngine.loadModel(path)
                    newActiveCachePaths = emptySet()
                }
                setActiveModelCachePaths(newActiveCachePaths)
                withContext(Dispatchers.IO) {
                    val cleanup = ModelCacheManager.clearModelCache(
                        getApplication<Application>().cacheDir,
                        keepAbsolutePaths = getActiveModelCachePaths(),
                    )
                    if (cleanup.deletedFiles > 0 || cleanup.failedFiles > 0) {
                        Log.i(
                            "LlmViewModel",
                            "Model cache cleanup after load: deleted=${cleanup.deletedFiles}, failed=${cleanup.failedFiles}, freedBytes=${cleanup.freedBytes}",
                        )
                    }
                }
                _errorMessage.value = null
            } catch (e: Exception) {
                if (engineLoadAttempted) {
                    setActiveModelCachePaths(emptySet())
                    withContext(Dispatchers.IO) {
                        val cleanup = ModelCacheManager.clearModelCache(getApplication<Application>().cacheDir)
                        if (cleanup.deletedFiles > 0 || cleanup.failedFiles > 0) {
                            Log.i(
                                "LlmViewModel",
                                "Model cache cleanup after load error: deleted=${cleanup.deletedFiles}, failed=${cleanup.failedFiles}, freedBytes=${cleanup.freedBytes}",
                            )
                        }
                    }
                }
                _errorMessage.value = "Failed to load model: ${e.message}"
            } finally {
                _isPreparingModelLoad.value = false
            }
        }
    }

    fun unloadModel() {
        setActiveModelCachePaths(emptySet())
        viewModelScope.launch(Dispatchers.IO) {
            LlmInferenceEngine.unloadModel()
            val cleanup = ModelCacheManager.clearModelCache(getApplication<Application>().cacheDir)
            if (cleanup.deletedFiles > 0 || cleanup.failedFiles > 0) {
                Log.i(
                    "LlmViewModel",
                    "Model cache cleanup after unload: deleted=${cleanup.deletedFiles}, failed=${cleanup.failedFiles}, freedBytes=${cleanup.freedBytes}",
                )
            }
        }
    }

    fun startServer() {
        val s = settings.value
        val timeoutMs = s.inactivityTimeoutMinutes?.let { it.toLong() * 60_000L }
        ServerManager.start(
            context = getApplication(),
            llmPort = s.llmPort,
            sqlPort = s.sqlPort,
            timeoutMs = timeoutMs,
            modelName = loadedModel.value?.name,
        )
    }

    fun stopServer() {
        ServerManager.stop(getApplication())
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        httpClient.close()
        super.onCleared()
    }
}
