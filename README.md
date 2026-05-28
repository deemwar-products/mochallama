# mochallama

**Local LLM for Spring Boot — Java → Project Panama FFM → a thin C++ `common_chat` bridge → vendored llama.cpp. No JNI. Spring-first.**

`tools.deemwar:mochallama-*` · npm `@deemwar/mochallama` · Apache-2.0 · JDK 22 · OpenAI-compatible HTTP · streaming · tool calling · Actuator metrics

[Documentation](https://deemwar-products.github.io/mochallama/) · [GitHub](https://github.com/deemwar-products/mochallama)

---

## What it is

mochallama runs GGUF chat models **locally, in-process on the JVM**. The Java
side binds a handful of C symbols through the JDK 22 **Foreign Function & Memory
API (Project Panama)** — there is **no JNI** and no native compilation in the
Java toolchain. Those symbols belong to a small C++ bridge
(`libllamabridge`) built on llama.cpp's `common_chat` helpers, which in turn
drives a **vendored copy of llama.cpp** compiled via CMake and staged into the
JAR as platform-specific resources.

It is **Spring-first**: drop in the starter and you get an autoconfigured local
model service, an OpenAI-compatible HTTP endpoint, a Spring AI `ChatModel` /
`ChatClient`, and inference metrics + a health indicator — no extra wiring.

```
HTTP client (curl / OpenAI SDK / Spring AI)
        │  POST /v1/chat/completions
        ▼
Spring Boot app  →  LlamaCppService  →  ChatEngine (Panama FFM)
        │                                     │  downcall MethodHandles
        ▼                                     ▼
libllamabridge  (our C++ bridge over common_chat)
        ▼
libllama + libggml*  (vendored llama.cpp)  →  GGUF model on disk
```

> **Today: macOS Intel `x86_64`, CPU-only.** The shipped artifacts bundle the
> `darwin-x86_64` native dylibs (Accelerate / BLAS; Metal/CUDA/Vulkan are gated
> off in CMake). Linux and Apple-silicon binaries build in CI (see
> `.github/workflows/build.yml`) and will publish as separate bundles later.
> This is an honest single-platform release, not a cross-platform promise.

## Quickstart

Requires **JDK 22** (FFM went GA in 22). Run the demo app:

```bash
./gradlew :app:bootRun
```

The HTTP port (`8080`) comes up immediately; the model downloads on first start
into `~/.chatbot_models` and loads asynchronously. While it loads, endpoints
return `503` with `{"error":"model loading","state":"DOWNLOADING"|"LOADING"}`.
Watch the logs for `state: READY`, then:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "Write a haiku about Project Panama."}
    ],
    "max_tokens": 128,
    "temperature": 0.7
  }'
```

## Modules

| Module     | Maven / npm coordinate                          | What it is |
|------------|-------------------------------------------------|------------|
| `core`     | `tools.deemwar:mochallama-core`                 | Framework-free Panama FFM bridge + `ChatEngine` + the stable `MochallamaClient` contract. Bundles the native dylibs. No Spring. |
| `starter`  | `tools.deemwar:mochallama-spring-boot-starter`  | Spring Boot starter: autoconfigures `LlamaCppService`, the OpenAI-compatible REST controller, Actuator metrics + health. No Spring AI dependency. |
| `spring-ai`| `tools.deemwar:mochallama-spring-ai`            | Spring AI `ChatModel` / `ChatClient` adapter over `MochallamaClient`. Spring AI is `compileOnly` so the consumer pins the version. |
| `cli`      | npm `@deemwar/mochallama`                        | Terminal CLI (`mochallama models` / `mochallama chat`), shipped as a self-contained jlink image — no JDK required. |
| `app`      | _(not published)_                               | Demo Spring Boot app that wires the starter + Spring AI adapter together, plus a small web UI. The reference for running everything end-to-end. |

## Endpoints

Served by the demo `app` (the OpenAI surface comes from the starter; the
`/spring-ai/*` routes are app-local demos of the Spring AI adapter):

| Endpoint                                | Method | Notes |
|-----------------------------------------|--------|-------|
| `/v1/chat/completions`                  | POST   | OpenAI chat completions. Supports `stream: true` (SSE) and `tools[]` → `tool_calls`. Full sampling params (see below). |
| `/v1/models`                            | GET    | Lists the loaded model id (derived from the GGUF filename). |
| `/spring-ai/chat`                       | POST   | `{"message": "..."}` → `{"reply": "..."}` via the autoconfigured `ChatClient`. |
| `/spring-ai/tool-demo`                  | POST   | Drives Spring AI tool calling end-to-end; surfaces the proposed `get_weather` tool call. |
| `/actuator/health`                      | GET    | `UP` once the model is `READY`, `DOWN` while loading/failed. Includes `model`, `state`, `loadDurationMs`. |
| `/actuator/metrics`                     | GET    | All meter names; `/actuator/metrics/{name}` for one meter (e.g. `mochallama.inference.duration`). |
| `/actuator/prometheus`                  | GET    | Prometheus scrape (opt-in — add `micrometer-registry-prometheus`). |

### `/v1/chat/completions` parameters

`messages[]` (roles `system` / `user` / `assistant` / `tool`) plus
`max_tokens`, `temperature`, `top_k`, `top_p`, `min_p`, `repeat_penalty`,
`seed`, `stop[]`, `stream`, `tools[]`, `tool_choice`. Per-request values
override the server-side defaults bound from `llamacpp.model.*`.

```bash
# Streaming
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"count 1 to 5"}],"stream":true,"max_tokens":32}'

# Tool calling
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages":[{"role":"user","content":"What is the weather in Paris?"}],
    "tools":[{"type":"function","function":{
      "name":"get_weather",
      "description":"Get the current weather for a location",
      "parameters":{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}
    }}]
  }'
```

## Models

The lineup is **tool-callers only** — every shipped profile ships a tool-capable
chat template. The default is **`qwen2.5-1.5b`** (Qwen2.5-1.5B-Instruct, Q4_K_M,
~1.1 GB): the proven tool-caller in this lineup and the smallest/fastest, so
first boot is quick.

| Profile        | Model                          | Size  | Tool calling |
|----------------|--------------------------------|-------|--------------|
| `qwen2.5-1.5b` | Qwen2.5-1.5B-Instruct (default)| ~1.1 GB | Yes (proven) |
| `qwen2.5-3b`   | Qwen2.5-3B-Instruct            | ~2.1 GB | Yes |
| `qwen3-4b`     | Qwen3-4B-Instruct-2507         | ~2.5 GB | Yes |
| `phi-4-mini`   | Phi-4-mini-instruct            | ~2.5 GB | Yes |

Switch by activating a Spring profile:

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=qwen2.5-3b'
```

Models download on first start into `~/.chatbot_models`. The id on
`GET /v1/models` is derived from the filename, so switching profiles switches
the OpenAI model id too. See the
[model profiles](https://deemwar-products.github.io/mochallama/specs/models) doc.

## Use as a library

Add the starter to a Spring Boot app. It autoconfigures the local model service,
the OpenAI endpoint, and (if `mochallama-spring-ai` + Spring AI are present) a
`ChatClient` / `ChatModel`:

```gradle
dependencies {
    implementation 'tools.deemwar:mochallama-spring-boot-starter:0.1.0-SNAPSHOT'
    // Optional: Spring AI ChatClient / ChatModel adapter
    implementation 'tools.deemwar:mochallama-spring-ai:0.1.0-SNAPSHOT'
    implementation 'org.springframework.ai:spring-ai-client-chat:1.0.8'
}
```

Inject the autoconfigured `ChatClient`:

```java
@RestController
class AssistantController {
    private final ChatClient chat;
    AssistantController(ChatClient chat) { this.chat = chat; }

    @PostMapping("/ask")
    String ask(@RequestBody String prompt) {
        return chat.prompt().user(prompt).call().content();
    }
}
```

Point the model location and sampling defaults via `llamacpp.model.*` (e.g.
`llamacpp.model.url`, `llamacpp.model.filename`, `llamacpp.model.context-size`,
`llamacpp.model.threads`, `llamacpp.model.temperature`). Disable the OpenAI
endpoint with `mochallama.openai-endpoint.enabled=false`. JVM args
`--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector` are
required.

For the framework-free path, depend on `mochallama-core` and use
`MochallamaClient` / `ChatEngine` directly.

## CLI

```bash
npm i -g @deemwar/mochallama   # macOS x64 only for v0.1.0
mochallama models
mochallama chat --model qwen2.5-3b
```

## Documentation

Full docs (architecture, the C ABI, model profiles, metrics, and a complete
examples section) live at **https://deemwar-products.github.io/mochallama/**.

## License

[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0).
