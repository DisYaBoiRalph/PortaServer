package com.fossylabs.portaserver.ui.components

/**
 * Ready-to-paste client configuration, generated against the server that is actually
 * running. Every value is interpolated — a snippet containing a placeholder like
 * `<phone-ip>` has not saved the user the lookup that made them open this screen.
 */
data class ClientSetup(
    /** Base URL without a trailing slash, e.g. `http://192.168.1.5:8080`. */
    val baseUrl: String,
    /** Model id exactly as `/v1/models` reports it. */
    val modelName: String,
    val contextLength: Int,
    val maxTokens: Int,
) {
    val apiBase: String get() = "$baseUrl/v1"
}

/** One client's setup instructions: where the file lives and what goes in it. */
data class ClientSnippet(
    val title: String,
    val filePath: String,
    val language: String,
    val body: String,
    val note: String? = null,
)

/**
 * Continue needs separate entries per role: `autocomplete` cannot share a model entry
 * with `chat`, and it reaches the legacy completions endpoint via
 * `useLegacyCompletionsEndpoint`.
 *
 * There is deliberately no `agent` entry -- Continue has no such role. Agent mode runs on
 * the `chat` model, so tool calling works through the entry below without extra config.
 */
fun ClientSetup.continueSnippet(): ClientSnippet = ClientSnippet(
    title = "Continue.dev",
    filePath = "~/.continue/config.yaml",
    language = "yaml",
    note = "Agent mode uses the chat entry. Autocomplete and embeddings need their own.",
    body = """
        name: PortaServer
        version: 0.0.1
        schema: v1
        models:
          - name: PortaServer (chat)
            provider: openai
            model: $modelName
            apiBase: $apiBase
            apiKey: portaserver
            roles: [chat, edit, apply]
          - name: PortaServer (autocomplete)
            provider: openai
            model: $modelName
            apiBase: $apiBase
            apiKey: portaserver
            roles: [autocomplete]
            useLegacyCompletionsEndpoint: true
          - name: PortaServer (embed)
            provider: openai
            model: $modelName
            apiBase: $apiBase
            apiKey: portaserver
            roles: [embed]
    """.trimIndent(),
)

/**
 * OpenCode's context limit is wired to the server's configured context so the client does
 * not send more than the model was loaded with — a mismatch shows up as truncation that
 * is painful to diagnose from the client side.
 */
fun ClientSetup.openCodeSnippet(): ClientSnippet = ClientSnippet(
    title = "OpenCode",
    filePath = "~/.config/opencode/opencode.json",
    language = "json",
    note = "Limits match the server's configured context and max tokens. Tool calling is automatic.",
    body = """
        {
          "${'$'}schema": "https://opencode.ai/config.json",
          "provider": {
            "portaserver": {
              "npm": "@ai-sdk/openai-compatible",
              "name": "PortaServer (phone)",
              "options": { "baseURL": "$apiBase" },
              "models": {
                "$modelName": {
                  "name": "$modelName",
                  "limit": { "context": $contextLength, "output": $maxTokens }
                }
              }
            }
          }
        }
    """.trimIndent(),
)

fun ClientSetup.curlSnippet(): ClientSnippet = ClientSnippet(
    title = "curl",
    filePath = "Terminal",
    language = "bash",
    body = """
        curl $apiBase/chat/completions \
          -H 'Content-Type: application/json' \
          -d '{
            "model": "$modelName",
            "messages": [{"role": "user", "content": "Hello!"}],
            "stream": false
          }'
    """.trimIndent(),
)

fun ClientSetup.pythonSnippet(): ClientSnippet = ClientSnippet(
    title = "Python (OpenAI SDK)",
    filePath = "any .py file",
    language = "python",
    body = """
        from openai import OpenAI

        client = OpenAI(base_url="$apiBase", api_key="portaserver")

        response = client.chat.completions.create(
            model="$modelName",
            messages=[{"role": "user", "content": "Hello!"}],
        )
        print(response.choices[0].message.content)
    """.trimIndent(),
)

fun ClientSetup.allSnippets(): List<ClientSnippet> = listOf(
    continueSnippet(),
    openCodeSnippet(),
    curlSnippet(),
    pythonSnippet(),
)
