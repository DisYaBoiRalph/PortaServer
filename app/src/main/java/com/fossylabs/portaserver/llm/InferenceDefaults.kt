package com.fossylabs.portaserver.llm

import com.fossylabs.portaserver.settings.DEFAULT_MAX_TOKENS
import com.fossylabs.portaserver.settings.DEFAULT_TEMPERATURE
import com.fossylabs.portaserver.settings.DEFAULT_TOP_P

/**
 * Sampling defaults applied to API requests that omit them.
 *
 * The route handlers run inside the foreground service, which has no ViewModel and no
 * convenient DataStore scope, so the service pushes the current settings here at start
 * up — the same arrangement [com.fossylabs.portaserver.sql.SqliteManager] uses for its
 * database directory.
 *
 * Per-request values always win; these only fill in the gaps.
 */
object InferenceDefaults {

    @Volatile
    var maxTokens: Int = DEFAULT_MAX_TOKENS
        private set

    @Volatile
    var temperature: Float = DEFAULT_TEMPERATURE
        private set

    @Volatile
    var topP: Float = DEFAULT_TOP_P
        private set

    fun configure(maxTokens: Int, temperature: Float, topP: Float) {
        this.maxTokens = maxTokens
        this.temperature = temperature
        this.topP = topP
    }
}
