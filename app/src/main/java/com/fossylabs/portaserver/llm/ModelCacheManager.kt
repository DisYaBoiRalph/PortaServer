package com.fossylabs.portaserver.llm

import java.io.File
import java.security.MessageDigest

data class ModelCacheClearResult(
    val deletedFiles: Int,
    val failedFiles: Int,
    val freedBytes: Long,
)

object ModelCacheManager {

    private const val CACHE_PREFIX = "llm-model-cache-"
    private const val CACHE_SUFFIX = ".bin"
    private const val LEGACY_PREFIX = "model-"
    private const val MAX_NAME_LENGTH = 120

    fun cacheFileForUri(cacheDir: File, uriString: String, preferredName: String? = null): File {
        val hash = stableHash(uriString)
        val safeName = preferredName
            ?.trim()
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.trim('_', '.', '-')
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                if (it.length <= MAX_NAME_LENGTH) it
                else {
                    val dot = it.lastIndexOf('.')
                    if (dot in 1 until it.length - 1) {
                        val ext = it.substring(dot)
                        val stemBudget = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(1)
                        it.substring(0, stemBudget) + ext
                    } else {
                        it.substring(0, MAX_NAME_LENGTH)
                    }
                }
            }

        return if (safeName != null) {
            File(cacheDir, "$CACHE_PREFIX$hash-$safeName")
        } else {
            File(cacheDir, "$CACHE_PREFIX$hash$CACHE_SUFFIX")
        }
    }

    fun clearModelCache(cacheDir: File, keepAbsolutePaths: Set<String> = emptySet()): ModelCacheClearResult {
        val files = cacheDir.listFiles() ?: return ModelCacheClearResult(0, 0, 0L)
        var deleted = 0
        var failed = 0
        var freedBytes = 0L

        for (file in files) {
            if (!isManagedModelCacheFile(file)) continue
            if (keepAbsolutePaths.contains(file.absolutePath)) continue

            val size = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                deleted += 1
                freedBytes += size
            } else {
                failed += 1
            }
        }
        return ModelCacheClearResult(deleted, failed, freedBytes)
    }

    private fun isManagedModelCacheFile(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name
        val isCurrentPattern = name.startsWith(CACHE_PREFIX)
        val isLegacyPattern = name.startsWith(LEGACY_PREFIX) && name.endsWith(CACHE_SUFFIX)
        val isPartial = name.startsWith(CACHE_PREFIX) && name.endsWith(".part")
        return isCurrentPattern || isLegacyPattern || isPartial
    }

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        // 16 bytes of SHA-256 is enough for a compact, deterministic cache filename.
        return digest.take(16).joinToString(separator = "") { "%02x".format(it) }
    }
}