package com.fossylabs.portaserver.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide state for the currently resident model.
 *
 * The set of cache files a load is relying on used to live in the LLM ViewModel, which
 * made it unreachable from anything outside the UI. The keep-alive notification's Unload
 * action runs in a service with no ViewModel, and an unload that skipped this bookkeeping
 * would leave the tracked set pointing at files it had already deleted.
 */
object ModelSession {

    private const val TAG = "ModelSession"

    private val lock = Any()
    private var activeCachePaths: Set<String> = emptySet()

    fun activeCachePaths(): Set<String> = synchronized(lock) { activeCachePaths }

    fun setActiveCachePaths(paths: Set<String>) {
        synchronized(lock) { activeCachePaths = paths }
    }

    /**
     * Releases the model and deletes cache files that are no longer in use.
     *
     * Safe to call when nothing is loaded, so callers that are unsure of the current
     * state — the notification action in particular — need not check first.
     */
    suspend fun unload(context: Context) {
        setActiveCachePaths(emptySet())
        withContext(Dispatchers.IO) {
            LlmInferenceEngine.unloadModel()
            clearCache(context, reason = "unload")
        }
    }

    /** Clears unused cache files, keeping whatever the active load still needs. */
    suspend fun clearCache(context: Context, reason: String, keepActive: Boolean = false) {
        withContext(Dispatchers.IO) {
            val cleanup = ModelCacheManager.clearModelCache(
                context.cacheDir,
                keepAbsolutePaths = if (keepActive) activeCachePaths() else emptySet(),
            )
            if (cleanup.deletedFiles > 0 || cleanup.failedFiles > 0) {
                Log.i(
                    TAG,
                    "Model cache cleanup after $reason: deleted=${cleanup.deletedFiles}, " +
                        "failed=${cleanup.failedFiles}, freedBytes=${cleanup.freedBytes}",
                )
            }
        }
    }
}
