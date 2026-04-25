package com.fossylabs.portaserver.llm

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.fossylabs.portaserver.notification.DownloadNotifier
import com.fossylabs.portaserver.settings.SettingsRepository
import com.fossylabs.portaserver.util.toHexString
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Per-file download state: progress 0f–1f while downloading, 1f when done. */
data class DownloadState(
    val progress: Float,
    val done: Boolean = false,
    val fileUri: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long? = null,
)

/**
 * Manages model file downloads: parallel and sequential HTTP download orchestration,
 * SAF document helpers (size query, SHA-256), and download-notification integration.
 */
class ModelDownloadManager(
    private val application: Application,
    private val httpClient: HttpClient,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
    private val onRefreshLocalModels: () -> Unit,
) {
    private companion object {
        const val IO_BUFFER_SIZE = 8192
        const val UI_PROGRESS_UPDATE_INTERVAL_MS = 250L
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 750L
        const val PARALLEL_MIN_FILE_SIZE_BYTES = 2_000_000L
        const val MAX_PARALLEL_RANGES = 4
        const val MIN_FREE_HEAP_FOR_PARALLEL_BYTES = 96L * 1024L * 1024L
    }

    private val resolver get() = application.contentResolver

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadJobsLock = Any()

    /** Monotonic counter used to assign unique notification IDs, avoiding hashCode() collisions. */
    private val notifIdCounter = AtomicInteger(0)

    /** fileName → notification ID assigned at download start. */
    private val notifIds = mutableMapOf<String, Int>()

    // ── SAF helpers ───────────────────────────────────────────────────────────

    fun getDocumentSize(fileUri: Uri): Long {
        val cursor = resolver.query(
            fileUri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null,
        ) ?: return -1L
        return cursor.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
    }

    fun computeSha256(fileUri: Uri): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(fileUri)?.use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().toHexString()
    }

    private fun findExistingFile(treeUri: Uri, treeDocId: String, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val cursor = resolver.query(
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

    // ── Download state helpers ────────────────────────────────────────────────

    /**
     * Pre-populates download states for files that are already present locally.
     * Called by the ViewModel after fetching the HF file list for a model.
     */
    fun prePopulateStates(states: Map<String, DownloadState>) {
        if (states.isNotEmpty()) {
            _downloadStates.update { current -> states + current }
        }
    }

    // ── Download orchestration ────────────────────────────────────────────────

    fun downloadFile(
        modelId: String,
        fileName: String,
        downloadDirUri: String,
        hfModelFiles: List<HuggingFaceFileDto>,
    ) {
        synchronized(downloadJobsLock) {
            val existing = downloadJobs[fileName]
            if (existing != null && existing.isActive) return
        }

        val fileInfo = hfModelFiles.firstOrNull { it.rfilename == fileName }
        val expectedSha256 = fileInfo?.lfs?.sha256
        val expectedSize = fileInfo?.let { it.lfs?.size ?: it.size }
        val url = "https://huggingface.co/$modelId/resolve/main/$fileName"

        val job = scope.launch(Dispatchers.IO) {
            _downloadStates.update { it + (fileName to DownloadState(0f)) }
            var createdFileUri: Uri? = null
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
                            onRefreshLocalModels()
                            return@launch
                        }
                    }
                    // Incomplete or corrupt — delete and re-download
                    DocumentsContract.deleteDocument(resolver, existingUri)
                }

                val fileUri = DocumentsContract.createDocument(
                    resolver, parentDocUri, "application/octet-stream", fileName
                ) ?: error("Cannot create file in download directory")
                createdFileUri = fileUri

                val digest = expectedSha256?.let { java.security.MessageDigest.getInstance("SHA-256") }

                var totalDownloaded = 0L
                val notifId = synchronized(downloadJobsLock) {
                    notifIds.getOrPut(fileName) { notifIdCounter.incrementAndGet() }
                }
                DownloadNotifier.ensureChannel(application)
                val startMs = System.currentTimeMillis()

                // Try to get metadata (HEAD) to decide on parallel ranged download
                var contentLength: Long? = null
                var acceptRanges: String? = null
                try {
                    httpClient.prepareGet(url).execute { resp ->
                        contentLength = resp.headers["Content-Length"]?.toLongOrNull()
                        acceptRanges = resp.headers["Accept-Ranges"]?.lowercase()
                    }
                } catch (_: Exception) {
                    // ignore and fall back to GET
                }

                val progressLock = Any()
                var lastUiUpdateAt = 0L
                var lastNotifUpdateAt = 0L

                fun publishProgress(downloadedBytes: Long, totalBytes: Long?, force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - startMs).coerceAtLeast(1L)
                    val speed = (downloadedBytes * 1000L) / elapsed
                    val progress = if (totalBytes != null && totalBytes > 0L) {
                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else 0f

                    var doUiUpdate = false
                    var doNotifUpdate = false
                    synchronized(progressLock) {
                        if (force || now - lastUiUpdateAt >= UI_PROGRESS_UPDATE_INTERVAL_MS) {
                            lastUiUpdateAt = now
                            doUiUpdate = true
                        }
                        if (force || now - lastNotifUpdateAt >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                            lastNotifUpdateAt = now
                            doNotifUpdate = true
                        }
                    }

                    if (doUiUpdate) {
                        _downloadStates.update {
                            it + (fileName to DownloadState(
                                progress,
                                done = false,
                                fileUri = null,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                speedBytesPerSec = speed,
                            ))
                        }
                    }

                    if (doNotifUpdate) {
                        try {
                            DownloadNotifier.update(
                                application, notifId, fileName,
                                downloadedBytes, totalBytes, speed,
                            )
                        } catch (_: Exception) {}
                    }
                }

                // Local helper: sequential download (used as fallback)
                suspend fun doSequentialDownload() {
                    totalDownloaded = 0L
                    httpClient.prepareGet(url).execute { response ->
                        val cl = response.headers["Content-Length"]?.toLongOrNull()
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(IO_BUFFER_SIZE)

                        val output = resolver.openOutputStream(fileUri)
                            ?: error("Cannot open output stream for download target")
                        output.use { os ->
                            while (!channel.isClosedForRead) {
                                val read = channel.readAvailable(buffer)
                                if (read > 0) {
                                    os.write(buffer, 0, read)
                                    digest?.update(buffer, 0, read)
                                    totalDownloaded += read
                                    publishProgress(totalDownloaded, cl)
                                }
                            }
                        }
                        publishProgress(totalDownloaded, cl, force = true)
                    }
                }

                val _cnt = contentLength
                val runtime = Runtime.getRuntime()
                val freeHeapBytes = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                val canUseParallel = _cnt != null && acceptRanges == "bytes" && _cnt > PARALLEL_MIN_FILE_SIZE_BYTES &&
                    freeHeapBytes >= MIN_FREE_HEAP_FOR_PARALLEL_BYTES

                if (canUseParallel) {
                    val totalLen = _cnt!!
                    // Parallel ranged download into a temp file in app cache, then copy to SAF
                    val tempFile = File(application.cacheDir, "$fileName.part")
                    try {
                        RandomAccessFile(tempFile, "rw").use { raf -> raf.setLength(totalLen) }

                        val maxParallelByHeap = when {
                            freeHeapBytes >= 192L * 1024L * 1024L -> MAX_PARALLEL_RANGES
                            freeHeapBytes >= 144L * 1024L * 1024L -> 3
                            else -> 2
                        }
                        val concurrency = minOf(maxParallelByHeap, ((totalLen / PARALLEL_MIN_FILE_SIZE_BYTES).toInt()).coerceAtLeast(1))
                        val chunkSize = (totalLen + concurrency - 1L) / concurrency.toLong()
                        Log.i("ModelDownloadManager", "Parallel download enabled: concurrency=$concurrency, freeHeapBytes=$freeHeapBytes")

                        val totalAtomic = AtomicLong(0L)
                        var parallelCompleted = false

                        val ranges = mutableListOf<Pair<Long, Long>>()
                        var pos = 0L
                        while (pos < totalLen) {
                            val end = minOf(pos + chunkSize - 1, totalLen - 1)
                            ranges.add(pos to end)
                            pos = end + 1
                        }

                        try {
                            // Download ranges in parallel and write directly into the temp file
                            java.nio.channels.FileChannel.open(
                                tempFile.toPath(),
                                java.nio.file.StandardOpenOption.READ,
                                java.nio.file.StandardOpenOption.WRITE,
                            ).use { fc ->
                            coroutineScope {
                                val jobs = ranges.map { (start, end) ->
                                    launch(Dispatchers.IO) {
                                        val resp = httpClient.get(url) {
                                            headers { append("Range", "bytes=$start-$end") }
                                        }
                                        if (resp.status.value != 206) {
                                            error("Server did not return partial content for range request")
                                        }
                                        val ch = resp.bodyAsChannel()
                                        val buf = ByteArray(IO_BUFFER_SIZE)
                                        var pos = start
                                        while (!ch.isClosedForRead) {
                                            val r = ch.readAvailable(buf)
                                            if (r <= 0) break
                                            fc.write(java.nio.ByteBuffer.wrap(buf, 0, r), pos)
                                            pos += r
                                            val tot = totalAtomic.addAndGet(r.toLong())
                                            publishProgress(tot, totalLen)
                                        }
                                    }
                                }
                                jobs.forEach { it.join() }
                            }
                            }

                            totalDownloaded = totalAtomic.get()
                            publishProgress(totalDownloaded, totalLen, force = true)
                            parallelCompleted = true
                        } catch (oom: OutOfMemoryError) {
                            Log.e("ModelDownloadManager", "OOM during parallel download; falling back to sequential: ${oom.message}")
                            doSequentialDownload()
                        } catch (e: Exception) {
                            Log.w("ModelDownloadManager", "Parallel range download failed, falling back to sequential: ${e.message}")
                            doSequentialDownload()
                        }

                        if (parallelCompleted) {
                            // Compute SHA256 from temp file if required
                            if (digest != null) {
                                FileInputStream(tempFile).use { fis ->
                                    val buf = ByteArray(IO_BUFFER_SIZE)
                                    var r: Int
                                    while (fis.read(buf).also { r = it } != -1) digest.update(buf, 0, r)
                                }
                            }

                            // Copy temp file to SAF target
                            FileInputStream(tempFile).use { fis ->
                                val output = resolver.openOutputStream(fileUri)
                                    ?: error("Cannot open output stream for download target")
                                output.use { os ->
                                    fis.copyTo(os, IO_BUFFER_SIZE)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        try { DownloadNotifier.cancel(application, notifId) } catch (_: Exception) {}
                        throw e
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                } else {
                    if (_cnt != null && acceptRanges == "bytes" && _cnt > PARALLEL_MIN_FILE_SIZE_BYTES) {
                        Log.i("ModelDownloadManager", "Skipping parallel download due to low free heap: $freeHeapBytes bytes")
                    }
                    // Sequential fallback (single stream)
                    doSequentialDownload()
                }

                // ── Verify SHA256 if available ─────────────────────────────
                var verifiedSha256: String? = null
                if (digest != null) {
                    val actualSha256 = digest.digest().toHexString()
                    if (actualSha256 != expectedSha256) {
                        DocumentsContract.deleteDocument(resolver, fileUri)
                        _downloadStates.update { it - fileName }
                        try { DownloadNotifier.cancel(application, notifId) } catch (_: Exception) {}
                        onError("Integrity check failed for $fileName")
                        return@launch
                    }
                    verifiedSha256 = actualSha256
                }
                _downloadStates.update {
                    it + (fileName to DownloadState(
                        1f, done = true, fileUri = fileUri.toString(),
                        downloadedBytes = totalDownloaded, totalBytes = expectedSize, speedBytesPerSec = null
                    ))
                }
                try { DownloadNotifier.complete(application, notifId, fileName) } catch (_: Exception) {}
                settingsRepo.saveFileMeta(fileUri.toString(), totalDownloaded, verifiedSha256 ?: expectedSha256)
                onRefreshLocalModels()
            } catch (_: CancellationException) {
                createdFileUri?.let { uri ->
                    try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) {}
                }
                _downloadStates.update { it - fileName }
                synchronized(downloadJobsLock) { notifIds.remove(fileName) }?.let { id ->
                    try { DownloadNotifier.cancel(application, id) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                _downloadStates.update { it - fileName }
                synchronized(downloadJobsLock) { notifIds.remove(fileName) }?.let { id ->
                    try { DownloadNotifier.cancel(application, id) } catch (_: Exception) {}
                }
                createdFileUri?.let { uri ->
                    try { DocumentsContract.deleteDocument(resolver, uri) } catch (_: Exception) {}
                }
                onError("Download failed: ${e.message}")
            }
        }

        synchronized(downloadJobsLock) { downloadJobs[fileName] = job }
        job.invokeOnCompletion {
            synchronized(downloadJobsLock) {
                if (downloadJobs[fileName] === job) {
                    downloadJobs.remove(fileName)
                }
                notifIds.remove(fileName)
            }
        }
    }

    fun cancelDownload(fileName: String) {
        val job = synchronized(downloadJobsLock) { downloadJobs.remove(fileName) }
        if (job != null) {
            job.cancel(CancellationException("Cancelled by user"))
        }
        val notifId = synchronized(downloadJobsLock) { notifIds.remove(fileName) }
        _downloadStates.update { it - fileName }
        if (notifId != null) {
            try { DownloadNotifier.cancel(application, notifId) } catch (_: Exception) {}
        }
    }
}
