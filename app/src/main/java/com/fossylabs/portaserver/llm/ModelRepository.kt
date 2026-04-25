package com.fossylabs.portaserver.llm

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val hfJson = Json { ignoreUnknownKeys = true }

class ModelRepository(
    private val httpClient: HttpClient,
    private val contentResolver: ContentResolver,
) {

    /** Recursively scans SAF tree URIs for .gguf files. */
    suspend fun scanLocalModels(scanDirUris: Set<String>): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            scanDirUris.flatMap { uriString ->
                try {
                    scanDirectoryForGguf(Uri.parse(uriString))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    private fun scanDirectoryForGguf(dirUri: Uri): List<ModelInfo> {
        val results = mutableListOf<ModelInfo>()
        val treeId = try {
            DocumentsContract.getTreeDocumentId(dirUri)
        } catch (_: Exception) { return results }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, treeId)
        val cursor = contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: return results

        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val docId = it.getString(1) ?: continue
                val mimeType = it.getString(2)

                when {
                    name.endsWith(".gguf", ignoreCase = true) -> {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                        results += ModelInfo(path = fileUri.toString(), name = name)
                    }
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                        val subUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                        results += scanDirectoryForGguf(subUri)
                    }
                }
            }
        }
        return results
    }

    /** Fetches text-generation GGUF models from HuggingFace Hub. */
    suspend fun fetchHuggingFaceModels(): List<ModelInfo> {
        return try {
            val dtos: List<HuggingFaceModelDto> = httpClient
                .get("https://huggingface.co/api/models") {
                    parameter("filter", "gguf")
                    parameter("task", "text-generation")
                    parameter("sort", "downloads")
                    parameter("limit", 30)
                }.body()

            dtos
                .filter { dto ->
                    val tags = dto.tags.map { it.lowercase() }
                    // Exclude image/multimodal models
                    "image" !in tags && "vision" !in tags && "multimodal" !in tags
                }
                .map { dto ->
                    ModelInfo(
                        path = "https://huggingface.co/${dto.modelId}",
                        name = dto.modelId,
                        isLocal = false,
                        downloads = dto.downloads,
                        likes = dto.likes,
                        pipelineTag = dto.pipelineTag,
                    )
                }
        } catch (e: Exception) {
            LogRepository.log(LogLevel.WARN, "Failed to fetch HuggingFace models: ${e.message}")
            throw e
        }
    }

    /** Fetches the list of GGUF sibling files for a given HuggingFace model. */
    suspend fun fetchModelFiles(modelId: String): List<HuggingFaceFileDto> {
        return try {
            val detail: HuggingFaceModelDetailDto = httpClient
                .get("https://huggingface.co/api/models/$modelId") {
                    // Include blob metadata so sibling GGUF entries expose size/lfs before download.
                    parameter("blobs", true)
                }
                .body()
            detail.siblings.filter { it.rfilename.endsWith(".gguf", ignoreCase = true) }
        } catch (e: Exception) {
            LogRepository.log(LogLevel.WARN, "Failed to fetch files for $modelId: ${e.message}")
            throw e
        }
    }
}
