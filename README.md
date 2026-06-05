# mochallama

**A local, tool-calling LLM that lives *inside* your JVM.** Spring-first, OpenAI-compatible,
llama.cpp-backed via Project Panama FFM — **no JNI, no daemon, no native-install dance.**

Maven Central `io.github.deemwario:mochallama-*` · npm `@deemwario/mochallama` · **MIT** · **JDK 22+**
· streaming · tool calling · Actuator metrics

**[Docs](https://deemwar-products.github.io/mochallama/)** ·
**[Why (the 6 design decisions)](https://deemwar-products.github.io/mochallama/why)** ·
**[Compare vs Ollama / Jlama / JNI](https://deemwar-products.github.io/mochallama/compare)** ·
**[Quickstart](https://deemwar-products.github.io/mochallama/quickstart)**

---

## Why this exists

Every other way to run a local LLM from a JVM app is either a **separate daemon** you talk to over
HTTP (Ollama, `llama-server` — an extra process to install, supervise, and ship) or a **JNI binding**
(in-process, but a native fault can take the whole JVM down and every llama.cpp bump breaks the shim).

mochallama is the third option: llama.cpp running **in your JVM's own process**, bound with the
**Foreign Function & Memory API** (GA in JDK 22) instead of JNI. The entire native binding is a handful
of `MethodHandle`s over a thin ~700-line `extern "C"` bridge — no `javah`, no generated headers, no
`native` keyword, no loader dance beyond `--enable-native-access`.

It is **tool-calling-only** by contract (non-tool models are rejected at load, not silently degraded),
and it **never makes you compile llama.cpp** — it consumes upstream's prebuilt release libs and
compiles only the bridge, with the right native auto-resolved for your platform.

> **Scope, honestly:** an inference engine + wire API, *not* a RAG/agent framework (Spring AI /
> LangChain4j sit *above* it — there's a Spring AI adapter so mochallama is the local provider
> underneath). CPU-first, small tool-capable models. If you want a shared GPU model server with the
> widest catalogue, [Ollama is the easier on-ramp](https://deemwar-products.github.io/mochallama/compare).

**Platforms:** macOS Intel + Apple Silicon · Linux x86-64 + ARM64 · Windows x86-64.

## Quickstart

### 1. Zero install — `npx` (no Java needed)

The CLI ships its own jlink JDK-22 runtime image, so this runs with no JDK on the host:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

A real multi-turn REPL — conversations persist as resumable sessions (see [CLI](#cli) below).

### 2. Spring Boot — one dependency

```kotlin
implementation("io.github.deemwario:mochallama-spring-boot-starter:0.1.6")
runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")   // native libs for your platform
```

`@AutoConfiguration` gives you an OpenAI-compatible server **in your app's process**. Start the app,
then point any OpenAI client at it:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Write a haiku about Project Panama."}]}'
```

### 3. Plain Java — `ChatEngine`

```kotlin
implementation("io.github.deemwario:mochallama-core:0.1.6")
runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
```

```java
import tools.deemwar.mochallama.panama.ChatEngine;
import java.nio.file.Path;

var engine = ChatEngine.load(Path.of("/path/to/model.gguf"));
String reply = engine.chat("Write a haiku about Project Panama.", 128, 0.7);
```

> Requires **JDK 22+** (FFM is GA there). Run with `--enable-native-access=ALL-UNNAMED`.

## Architecture

```
HTTP client (curl / OpenAI SDK / Spring AI)
        │  POST /v1/chat/completions
        ▼
Spring Boot app  →  LlamaCppService  →  ChatEngine (Panama FFM)
        │                                     │  downcall MethodHandles, Arena-scoped memory
        ▼                                     ▼
libllamabridge  (~700-line extern-C bridge over llama.cpp common_chat)
        ▼
libllama + libggml*  (prebuilt llama.cpp, tag b9371)  →  GGUF model on disk
```

The bridge exposes a small **JSON-in / JSON-out C ABI** (a handful of symbols), not a mirror of
`llama.h` — so a llama.cpp upgrade rebuilds the bridge without touching the Java. The main
`mochallama-core` jar is Java-only (~200 KB); native libs ship as per-platform `natives-<os>-<arch>`
classifier jars, and `mochallama-core-platform` resolves the right one for your host.

## Modules

| Module      | Coordinate                                            | What it is |
|-------------|-------------------------------------------------------|------------|
| `core`      | `io.github.deemwario:mochallama-core`                 | Framework-free Panama FFM bridge + `ChatEngine` + the stable `MochallamaClient` contract. Java-only; natives via classifier jars. No Spring. |
| (natives)   | `io.github.deemwario:mochallama-core-platform`        | Platform aggregator — pulls the native llama.cpp libraries for your OS/arch. Add as `runtimeOnly`. |
| `starter`   | `io.github.deemwario:mochallama-spring-boot-starter`  | Spring Boot starter: autoconfigures `LlamaCppService`, the OpenAI REST controller, Actuator metrics + health. No Spring AI dependency. |
| `spring-ai` | `io.github.deemwario:mochallama-spring-ai`            | Spring AI `ChatModel` / `ChatClient` adapter. Spring AI is `compileOnly`, so the consumer pins the version. |
| `cli`       | npm `@deemwario/mochallama`                            | Terminal CLI (`mochallama models` / `chat` / `sessions`), a self-contained jlink image — no JDK required. |
| `app`       | _(not published)_                                     | Demo Spring Boot app wiring it all together + a small web UI. The end-to-end reference. |

## HTTP API

Exposed by the starter:

| Endpoint                 | Method | Notes |
|--------------------------|--------|-------|
| `/v1/chat/completions`   | POST   | OpenAI chat completions. Supports `stream:true` (SSE) and `tools[]` → `tool_calls`. Full sampling params below. |
| `/v1/models`             | GET    | Lists the loaded model id (derived from the GGUF filename). |
| `/actuator/health`       | GET    | `UP` once the model is `READY`, `DOWN` while loading/failed. Includes `model`, `state`, `loadDurationMs`. |
| `/actuator/metrics`      | GET    | Inference meters (e.g. `mochallama.inference.duration`); `/actuator/prometheus` opt-in. |

While the model loads (async), endpoints return `503` with
`{"error":"model loading","state":"DOWNLOADING"|"LOADING"}`; watch for `state: READY`.

### `/v1/chat/completions` parameters

`messages[]` (roles `system` / `user` / `assistant` / `tool`) plus `max_tokens`, `temperature`,
`top_k`, `top_p`, `min_p`, `repeat_penalty`, `seed`, `stop[]`, `stream`, `tools[]`, `tool_choice`.
Per-request values override the server defaults bound from `llamacpp.model.*`.

```bash
# Streaming (SSE)
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

The lineup is **tool-callers only** — every shipped profile has a tool-capable chat template. The
default is **`qwen2.5-1.5b`** (Qwen2.5-1.5B-Instruct, Q4_K_M, ~1.1 GB): the smallest/fastest proven
tool-caller, so first boot is quick. Models download on first use into `~/.chatbot_models`.

| Profile        | Model                            | Size    | Tool calling |
|----------------|----------------------------------|---------|--------------|
| `qwen2.5-1.5b` | Qwen2.5-1.5B-Instruct (default)  | ~1.1 GB | Yes (proven) |
| `qwen2.5-3b`   | Qwen2.5-3B-Instruct              | ~2.1 GB | Yes |
| `qwen3-4b`     | Qwen3-4B-Instruct-2507           | ~2.5 GB | Yes |
| `phi-4-mini`   | Phi-4-mini-instruct              | ~2.5 GB | Yes |

**Or load any tool-capable model by Hugging Face id** (resolves the GGUF, preferred quant `Q4_K_M`):

```properties
llamacpp.model.hf-id=Qwen/Qwen2.5-3B-Instruct-GGUF
```

The CLI accepts the same — a profile name, a HF id, or a local `.gguf` path. **Only tool-capable
models load**; a non-tool model is rejected at load with a clear error. See
[model profiles](https://deemwar-products.github.io/mochallama/specs/models) and
[tool-calling support](https://deemwar-products.github.io/mochallama/specs/tool-calling-support).

## CLI

```bash
npx @deemwario/mochallama models                 # list tool-capable presets (or `npm i -g` it)
npx @deemwario/mochallama chat -m qwen2.5-1.5b   # real multi-turn REPL; saved as a session
npx @deemwario/mochallama sessions               # id, model, turns, last-updated
npx @deemwario/mochallama chat --resume <id>     # continue a prior conversation
```

`chat` keeps the **full conversation history** (not amnesiac single turns). Sessions persist at
`~/.chatbot_models/sessions/<id>.json`; `--no-save` runs ephemerally. In-REPL: `/reset`, `/help`,
`/exit`. The package bundles a jlink JDK-22 image per platform, so no Java install is required.

## Use as a library (Spring)

```kotlin
dependencies {
    implementation("io.github.deemwario:mochallama-spring-boot-starter:0.1.6")
    runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
    // Optional: Spring AI ChatClient / ChatModel adapter
    implementation("io.github.deemwario:mochallama-spring-ai:0.1.6")
    implementation("org.springframework.ai:spring-ai-client-chat:1.0.8")
}
```

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

Configure via `llamacpp.model.*` (e.g. `url`, `filename`, `hf-id`, `context-size`, `threads`,
`temperature`); disable the OpenAI endpoint with `mochallama.openai-endpoint.enabled=false`. JVM arg
`--enable-native-access=ALL-UNNAMED` is required. For the framework-free path, depend on
`mochallama-core` (+ `-core-platform`) and use `ChatEngine` / `MochallamaClient` directly.

## How it compares

| | mochallama | Ollama | Jlama | java-llama.cpp (JNI) |
|---|---|---|---|---|
| Runs in the JVM process | **yes** | no (HTTP daemon) | yes (pure Java) | yes (JNI) |
| Binding | FFM (JDK 22) | — (REST) | none (Vector API) | JNI |
| Native install | none (prebuilt) | install daemon | none | prebuilt / compile |
| Streaming **+** tool calls | **yes** | not over its OpenAI API | per-model | per-model |
| Best for | embedding a tool-calling LLM in a JVM app | shared GPU server, biggest catalogue | zero native binaries | older JDKs (Java 11) |

Full, honest breakdown: **[/compare](https://deemwar-products.github.io/mochallama/compare)**.

## Build from source

```bash
# needs JDK 22 in JAVA_HOME
./gradlew build            # build + test all modules
./gradlew :app:bootRun     # demo app + web UI at http://localhost:8080
```

The native build downloads prebuilt llama.cpp libs and compiles only the bridge (`-Pnative=source`
forces a from-source llama.cpp build). See the [Taskfile](Taskfile.yml) and
[architecture docs](https://deemwar-products.github.io/mochallama/specs/01-architecture).

## License

[MIT](LICENSE). Vendored llama.cpp + ggml are also MIT — see [NOTICE](NOTICE).
