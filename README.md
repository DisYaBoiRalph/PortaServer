# PortaServer

Turn your Android phone into a portable local server — run LLM inference and expose an SQLite database over your local network.

## Features

- **LLM Server** — Load and run GGUF models on-device via [llama.cpp](https://github.com/ggerganov/llama.cpp). Exposes an OpenAI-compatible `/v1/chat/completions` endpoint with streaming (SSE) support, usable directly from VS Code extensions, curl, or any OpenAI client.
- **SQL Server** — Expose a local SQLite database over the network via a REST API.
- **Model Discovery** — Browse and download models from HuggingFace filtered to what will actually fit in your device's RAM. Swipe to delete downloaded models.
- **Foreground Service** — The server keeps running in the background with a persistent notification showing the device IP and active ports.
- **Device-aware recommendations** — Models are ranked based on your device's RAM, CPU cores, and SoC.

## API

The LLM server implements the OpenAI chat completions API:

```
GET  http://<phone-ip>:<llm-port>/v1/models
POST http://<phone-ip>:<llm-port>/v1/chat/completions
```

Supports both standard JSON responses and streaming (`"stream": true`).

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

### VS Code (settings.json)

```json
"github.copilot.advanced": {
  "debug.overrideEngine": "http://192.168.1.x:8080"
}
```

Or use it as an Ollama-compatible endpoint in extensions like **Continue**.

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
