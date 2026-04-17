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
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Per-file download state: progress 0f–1f while downloading, 1f when done. */
data class DownloadState(val progress: Float, val done: Boolean = false, val fileUri: String? = null)

class LlmViewModel(application: Application) : AndroidViewModel(application) {

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

    val isLoadingModel: StateFlow<Boolean> = LlmInferenceEngine.isLoading
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

    /** fileName → download state (progress, done). */
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    // ──────────────────────────────────────────────────────────────────────────

    val settings = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        com.fossylabs.portaserver.settings.SettingsState()
    )

    init {
        refreshDeviceSpecs()
        refreshLocalModels()
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
            val tier = _modelTier.value
            // Scan + size check (metadata-only query — fast)
            val scanned = withContext(Dispatchers.IO) {
                modelRepository.scanLocalModels(dirs)
                    .map { model ->
                        val meta = metadata[model.path]
                        val sizeOk = if (meta != null) {
                            getDocumentSize(Uri.parse(model.path)) == meta.expectedSize
                        } else true
                        model.copy(
                            isRecommended = tier?.let { ModelRecommender.fitsInTier(model.name, it) } ?: false,
                            isCorrupted = !sizeOk,
                        )
                    }
                    .sortedByDescending { it.isRecommended }
            }
            _localModels.value = scanned
            // Background SHA256 check for files that passed size check
            if (metadata.any { it.value.sha256 != null }) {
                viewModelScope.launch(Dispatchers.IO) {
                    val updated = scanned.map { model ->
                        if (model.isCorrupted) return@map model
                        val sha256 = metadata[model.path]?.sha256 ?: return@map model
                        val actual = computeSha256(Uri.parse(model.path))
                        if (actual != sha256) model.copy(isCorrupted = true) else model
                    }
                    _localModels.value = updated
                }
            }
        }
    }

    fun fetchHuggingFaceModels() {
        viewModelScope.launch {
            _isFetchingHf.value = true
            val tier = _modelTier.value
            _hfModels.value = modelRepository.fetchHuggingFaceModels()
                .map { model ->
                    model.copy(isRecommended = tier?.let { ModelRecommender.fitsInTier(model.name, it) } ?: false)
                }
                .sortedByDescending { it.isRecommended }
            _isFetchingHf.value = false
        }
    }

    fun selectHfModel(model: ModelInfo?) {
        _selectedHfModel.value = model
        _hfModelFiles.value = emptyList()
        if (model != null) {
            viewModelScope.launch {
                _isFetchingFiles.value = true
                val files = modelRepository.fetchModelFiles(model.name)
                _hfModelFiles.value = files
                _isFetchingFiles.value = false
                // Pre-populate downloadStates from already-present local models
                val currentLocals = _localModels.value
                val prePopulated = buildMap {
                    for (file in files) {
                        val local = currentLocals.firstOrNull { it.name == file.rfilename }
                        if (local != null) put(file.rfilename, DownloadState(1f, done = true, fileUri = local.path))
                    }
                }
                if (prePopulated.isNotEmpty()) {
                    _downloadStates.update { current -> prePopulated + current }
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
        val fileInfo = _hfModelFiles.value.firstOrNull { it.rfilename == fileName }
        val expectedSha256 = fileInfo?.lfs?.sha256
        val expectedSize = fileInfo?.let { it.lfs?.size ?: it.size }
        val url = "https://huggingface.co/$modelId/resolve/main/$fileName"
        val resolver = getApplication<Application>().contentResolver

        viewModelScope.launch(Dispatchers.IO) {
            _downloadStates.update { it + (fileName to DownloadState(0f)) }
            try {
                val treeUri = Uri.parse(downloadDirUri)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

                // ── Check for an existing file ─────────────────────────────
                val existingUri = findExistingFile(treeUri, treeDocId, fileName)
                if (existingUri != null) {
                    val existingSize = getDocumentSize(existingUri)
                    if (expectedSize != null && existingSize == expectedSize) {
                        // File appears complete — optionally verify SHA256
                        if (expectedSha256 == null || computeSha256(existingUri) == expectedSha256) {
                            _downloadStates.update {
                                it + (fileName to DownloadState(1f, done = true, fileUri = existingUri.toString()))
                            }
                            settingsRepo.saveFileMeta(existingUri.toString(), existingSize, expectedSha256)
                            refreshLocalModels()
                            return@launch
                        }
                    }
                    // Incomplete or corrupt — delete and re-download
                    DocumentsContract.deleteDocument(resolver, existingUri)
                }

                val fileUri = DocumentsContract.createDocument(
                    resolver, parentDocUri, "application/octet-stream", fileName
                ) ?: error("Cannot create file in download directory")

                val digest = expectedSha256?.let { java.security.MessageDigest.getInstance("SHA-256") }

                var totalDownloaded = 0L
                httpClient.prepareGet(url).execute { response ->
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    resolver.openOutputStream(fileUri)?.use { os ->
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read > 0) {
                                os.write(buffer, 0, read)
                                digest?.update(buffer, 0, read)
                                totalDownloaded += read
                                if (contentLength != null && contentLength > 0) {
                                    _downloadStates.update {
                                        it + (fileName to DownloadState(totalDownloaded.toFloat() / contentLength))
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Verify SHA256 if available ─────────────────────────────
                var verifiedSha256: String? = null
                if (digest != null) {
                    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    if (actualSha256 != expectedSha256) {
                        DocumentsContract.deleteDocument(resolver, fileUri)
                        _downloadStates.update { it - fileName }
                        _errorMessage.value = "Integrity check failed for $fileName"
                        return@launch
                    }
                    verifiedSha256 = actualSha256
                }

                _downloadStates.update { it + (fileName to DownloadState(1f, done = true, fileUri = fileUri.toString())) }
                settingsRepo.saveFileMeta(fileUri.toString(), totalDownloaded, verifiedSha256 ?: expectedSha256)
                refreshLocalModels()
            } catch (e: Exception) {
                _downloadStates.update { it - fileName }
                _errorMessage.value = "Download failed: ${e.message}"
            }
        }
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

    private fun findExistingFile(treeUri: Uri, treeDocId: String, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val cursor = getApplication<Application>().contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        ) ?: return null
        return cursor.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == fileName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
            null
        }
    }

    private fun getDocumentSize(fileUri: Uri): Long {
        val cursor = getApplication<Application>().contentResolver.query(
            fileUri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null,
        ) ?: return -1L
        return cursor.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
    }

    private fun computeSha256(fileUri: Uri): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        getApplication<Application>().contentResolver.openInputStream(fileUri)?.use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getLocalIpAddress(): String? = ServerForegroundService.getLocalIpAddress()

    fun loadModel(path: String) {
        viewModelScope.launch {
            try {
                if (path.startsWith("content://")) {
                    // SAF content URI — resolve to /proc/self/fd/<n> for NDK access.
                    // Keep the PFD open during loading so the fd remains valid.
                    withContext(Dispatchers.IO) {
                        val pfd = getApplication<Application>().contentResolver
                            .openFileDescriptor(android.net.Uri.parse(path), "r")
                            ?: error("Cannot open model file: $path")
                        pfd.use {
                            LlmInferenceEngine.loadModel("/proc/self/fd/${it.fd}")
                        }
                    }
                } else {
                    LlmInferenceEngine.loadModel(path)
                }
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load model: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        LlmInferenceEngine.unloadModel()
    }

    fun startServer() {
        val s = settings.value
        val timeoutMs = s.inactivityTimeoutMinutes?.let { it.toLong() * 60_000L }
        ServerManager.start(getApplication(), s.llmPort, s.sqlPort, timeoutMs)
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
