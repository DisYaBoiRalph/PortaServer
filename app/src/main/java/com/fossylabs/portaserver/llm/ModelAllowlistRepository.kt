package com.fossylabs.portaserver.llm

import android.content.Context
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Serves the curated model list, preferring a cached remote copy and always falling back
 * to the copy bundled in assets.
 *
 * The bundled copy is what makes a first run work offline; the remote refresh is what
 * lets the list gain models without an app update. A refresh that fails for any reason
 * must never leave the user with no list at all.
 */
class ModelAllowlistRepository(
    private val context: Context,
    private val httpClient: HttpClient,
    private val remoteUrl: String? = DEFAULT_REMOTE_URL,
) {

    private companion object {
        const val ASSET_NAME = "model_allowlist.json"
        const val CACHE_NAME = "model_allowlist.json"

        /**
         * Raw file on the default branch. Refreshing is best-effort: any failure falls
         * back to cache, then to the bundled asset.
         */
        const val DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/disyaboiralph/PortaServer/main/app/src/main/assets/model_allowlist.json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Cached-or-bundled list. Never throws; returns an empty list only if both fail. */
    suspend fun load(): List<AllowlistEntry> = withContext(Dispatchers.IO) {
        parse(readCache()) ?: parse(readAsset()) ?: emptyList()
    }

    /**
     * Fetches the remote list and caches it when it parses. Returns the refreshed list,
     * or null when the refresh did not produce a usable one — callers keep showing
     * whatever [load] already gave them.
     */
    suspend fun refresh(): List<AllowlistEntry>? = withContext(Dispatchers.IO) {
        val url = remoteUrl ?: return@withContext null
        try {
            val body = httpClient.get(url).bodyAsText()
            val parsed = parse(body)
            if (parsed == null) {
                LogRepository.log(LogLevel.WARN, "Curated model list from $url did not parse")
                return@withContext null
            }
            // Only cache content that parsed, so a bad deploy cannot poison the cache.
            runCatching { cacheFile().writeText(body) }
            parsed
        } catch (e: Exception) {
            LogRepository.log(LogLevel.WARN, "Could not refresh curated model list: ${e.message}")
            null
        }
    }

    private fun parse(raw: String?): List<AllowlistEntry>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ModelAllowlist>(raw).models }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun cacheFile() = File(context.filesDir, CACHE_NAME)

    private fun readCache(): String? =
        runCatching { cacheFile().takeIf { it.exists() }?.readText() }.getOrNull()

    private fun readAsset(): String? = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }.getOrNull()
}
