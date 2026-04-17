package com.fossylabs.portaserver.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong

class InactivityWatcher {

    private val lastRequestTime = AtomicLong(System.currentTimeMillis())

    fun recordRequest() {
        lastRequestTime.set(System.currentTimeMillis())
    }

    suspend fun watchForInactivity(timeoutMs: Long, onInactive: () -> Unit) {
        while (currentCoroutineContext().isActive) {
            delay(CHECK_INTERVAL_MS)
            if (System.currentTimeMillis() - lastRequestTime.get() >= timeoutMs) {
                onInactive()
                return
            }
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L
    }
}
