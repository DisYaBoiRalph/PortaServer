package com.fossylabs.portaserver.llm

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * Attaches the HuggingFace bearer token when one is configured.
 *
 * Gated repositories — Llama and Gemma among them — reject unauthenticated requests, so
 * without this a download fails with a bare 401/403. The token is resolved by the caller
 * before the request is built, because Ktor's request-builder block is not suspending.
 */
internal fun HttpRequestBuilder.bearer(token: String?) {
    if (!token.isNullOrBlank()) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }
}

/**
 * Turns a HuggingFace auth failure into something a user can act on, rather than
 * surfacing the raw status code.
 */
internal fun huggingFaceAuthMessage(statusCode: Int, hasToken: Boolean): String? = when {
    statusCode != 401 && statusCode != 403 -> null
    hasToken -> "Access denied by HuggingFace. This model is gated — accept its terms " +
        "on huggingface.co, and check that your access token is valid."
    else -> "This model is gated. Add a HuggingFace access token in Settings, and " +
        "accept the model's terms on huggingface.co."
}
