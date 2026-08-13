# PortaServer

Turn your Android phone into a portable local server — run LLM inference and expose an SQLite database over your local network.

## Features

- **LLM Server** — Load and run GGUF models on-device via [llama.cpp](https://github.com/ggerganov/llama.cpp). Exposes an OpenAI-compatible API with streaming, tool calling and embeddings, usable from agentic coding clients such as Continue.dev and OpenCode, from curl, or from any OpenAI client.
- **SQL Server** — Expose a local SQLite database over the network via a REST API.
- **Model Discovery** — Browse and download GGUF text-generation models from HuggingFace. Models are prioritized by a RAM-based fit recommendation, and downloaded models can be removed with swipe-to-delete.
- **Foreground Service** — The server keeps running in the background with a persistent notification showing the device IP and active ports.
- **Device-aware recommendations** — Recommendations are RAM-tier based; CPU cores and SoC are surfaced in the UI for quick device context.

## API

The LLM server implements the OpenAI API surface that coding clients actually use:

```
GET  http://<phone-ip>:<llm-port>/health
GET  http://<phone-ip>:<llm-port>/v1/models
GET  http://<phone-ip>:<llm-port>/v1/models/{id}
POST http://<phone-ip>:<llm-port>/v1/chat/completions
POST http://<phone-ip>:<llm-port>/v1/completions
POST http://<phone-ip>:<llm-port>/v1/embeddings
```

Both standard JSON and streaming (`"stream": true`) are supported, including
incremental `tool_calls` fragments. CORS is enabled, so browser clients work too.

A second request while one is generating returns `429` with `Retry-After` rather than
queueing behind it — the model serves one request at a time.

### Tool calling

`/v1/chat/completions` accepts `tools` and returns `tool_calls` with
`finish_reason: "tool_calls"`, which is what agentic clients need. Tool definitions are
rendered through the model's own chat template, so the format matches whatever the model
was trained on, and sampling is constrained by the grammar that template produces —
a malformed tool call cannot be emitted.

Use a model trained for it (the Qwen2.5 Instruct and Coder builds in the curated list
are). A model without tool training will simply answer in prose.

### Embeddings

`/v1/embeddings` accepts a string or an array of strings. Note that a chat model is a
mediocre embedder; for retrieval quality, load a dedicated embedding GGUF instead.

### Example (curl)

```bash
curl http://192.168.1.x:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "model-name",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": false
  }'
```

### Connecting a coding client

The app generates ready-to-paste config once a model is loaded and the server is
running — open the **Connect a client** section on the LLM screen. Every value is filled
in from the live server, so there is nothing to look up.

**Continue.dev** (`~/.continue/config.yaml`) needs one entry per role. Agent mode runs on
the `chat` entry — Continue has no separate `agent` role:

```yaml
models:
  - name: PortaServer (chat)
    provider: openai
    model: <loaded-model-name>
    apiBase: http://192.168.1.x:8080/v1
    apiKey: portaserver
    roles: [chat, edit, apply]
  - name: PortaServer (autocomplete)
    provider: openai
    model: <loaded-model-name>
    apiBase: http://192.168.1.x:8080/v1
    apiKey: portaserver
    roles: [autocomplete]
    useLegacyCompletionsEndpoint: true
```

**OpenCode** (`~/.config/opencode/opencode.json`) — set the limits to match the context
the model was loaded with, or long sessions truncate in ways that are hard to diagnose
from the client side:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "provider": {
    "portaserver": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "PortaServer (phone)",
      "options": { "baseURL": "http://192.168.1.x:8080/v1" },
      "models": {
        "<loaded-model-name>": {
          "name": "<loaded-model-name>",
          "limit": { "context": 2048, "output": 512 }
        }
      }
    }
  }
}
```

### Limitations

- `/v1/completions` takes `prompt` as a string only; the array form is not supported.
- One request at a time, as above.

## Requirements

- Android 8.0+ (API 26)
- arm64-v8a or x86_64 device
- Local network access

## Tech Stack

| Layer         | Library                       |
| ------------- | ----------------------------- |
| UI            | Jetpack Compose + Material 3  |
| Navigation    | Navigation Compose            |
| HTTP Server   | Ktor (CIO engine)             |
| LLM Inference | llama.cpp (NDK/JNI)           |
| Persistence   | DataStore Preferences         |
| Serialization | kotlinx.serialization         |
| HTTP Client   | Ktor Client (HuggingFace API) |

## Building

```bash
# Debug build + install to connected device
./gradlew installDebug
```

Requires Android NDK (CMake will fetch llama.cpp automatically via FetchContent).

## Project Structure

```
app/src/main/
├── cpp/                    # JNI bridge to llama.cpp
├── java/com/fossylabs/portaserver/
│   ├── llm/                # Model discovery, inference engine, HF API
│   ├── server/             # Ktor server, routing, request log
│   ├── service/            # Foreground service
│   ├── settings/           # App settings (DataStore)
│   ├── sql/                # SQLite manager + REST routes
│   └── ui/                 # Compose screens and ViewModels
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

PortaServer derives code from [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) and [mobile-server](https://github.com/techjarves/mobile-server), both Apache-2.0. See [NOTICE](NOTICE) for attribution.
