# Architecture

mochallama is a small stack with one job: let a JVM app run a local GGUF model
through llama.cpp, with **no JNI**. The path is:

```
Java  →  Project Panama FFM  →  thin C++ bridge (llamabridge)  →  llama.cpp  →  GGUF
```

The Java side never touches JNI. It binds a tiny extern-`"C"` ABI through the
JDK 22 Foreign Function & Memory API. The bridge is the only native code we
write — everything below it is upstream llama.cpp.

## Modules

The build is five Gradle subprojects (`settings.gradle`):

| Module | What it is | Published |
|--------|------------|-----------|
| **core** | FFM bindings, `ChatEngine`, `MochallamaClient`, and the C++ bridge (`core/src/main/cpp`). Bundles the per-platform native libs into classifier jars. | ✅ |
| **starter** | `mochallama-spring-boot-starter` — autoconfigures the model service, the OpenAI `/v1/chat/completions` (+SSE) and `/v1/models` endpoints, and Actuator metrics/health. No Spring AI dependency. | ✅ |
| **spring-ai** | `mochallama-spring-ai` — a Spring AI `ChatModel` / `ChatClient` adapter. `spring-ai-*` is `compileOnly` (the consumer supplies the version). | ✅ |
| **cli** | Picocli CLI (`mochallama models` / `chat`), packaged as a jlink image that **bundles JDK 22**, wrapped for `npx`. | — |
| **app** | Demo Spring Boot app + a vanilla-JS web UI at `/`. | — |

## core — the FFM layer

`core/src/main/java/tools/deemwar/mochallama/`:

- **`panama/LlamaBridge`** — pure FFM glue. Holds a `Linker`, a process-wide
  `SymbolLookup`, and one downcall `MethodHandle` per ABI function. Its static
  initialiser calls `NativeLoader.load()` before any symbol lookup.
- **`panama/NativeLoader`** — resolves `os`/`arch` (`.dylib` / `.so` / `.dll`),
  extracts the libs from `classpath:/native/<os>-<arch>/` into a temp dir, and
  `System.load`s them in dependency order. Requires only `llamabridge`; sibling
  libs that are absent (already linked-in on a given platform) are skipped.
- **`panama/ChatEngine`** — the higher-level facade over the bridge. Owns a
  confined `Arena` for the engine handle's lifetime, marshals the request to
  JSON, and exposes both single-shot `chat(...)` and streaming `chatStream(...)`.
  `AutoCloseable`.
- **`MochallamaClient`** — the plain-Java entry point (no Spring). Wraps a
  `ChatEngine` with the request/response value types below.
- **value types** — `Message`, `ToolDefinition`, `ToolCall`, `GenerationOptions`,
  `ChatResult`, `Usage`, `ModelInfo`, `LlamaException`.
- **`hf/HuggingFaceModels`** — resolves a Hugging Face repo id to a concrete
  `.gguf` (preferred quant with fallbacks) and downloads into `~/.chatbot_models`.
  Shared by the starter and the CLI. See [Models](models).

## The native bridge

`core/src/main/cpp/` is the only C++ we maintain — a single translation unit
(`src/llamabridge.cpp`, ~700 LOC) behind a small extern-`"C"` header
(`include/llamabridge.h`). It delegates the hard parts to llama.cpp's own
`common_chat` (chat-template application, tool-call grammar/parsing) and
`common_sampler` (the sampler chain) rather than re-implementing them.

The ABI:

| Function | Purpose |
|----------|---------|
| `llb_chat_create(gguf_path, …)` | Load a model + context, build the engine handle. Returns `NULL` if the model's chat template is not tool-capable. |
| `llb_chat_infer(h, request_json)` | Single-shot inference; returns a JSON result string. |
| `llb_chat_infer_stream(h, request_json, token_cb, userdata)` | Streaming inference; invokes `token_cb` per token, used by the SSE path. |
| `llb_model_info(h)` | Model metadata (id, context size, …). |
| `llb_version()` | llama.cpp version string (static — do not free). |
| `llb_string_free(s)` | Free a string the bridge returned. |
| `llb_chat_destroy(h)` | Tear down the engine handle. |

Tool capability is enforced **here**: a model whose chat template can't do tool
calling is rejected at load (`MODEL_NOT_TOOL_CAPABLE`).

### Native build — prebuilt by default

`:core:buildNative` downloads llama.cpp's **official prebuilt release binaries**
(tag `b9371`) for the host platform and compiles **only the bridge** against the
vendored headers — about ~11s, no llama.cpp compile. This is the default
(`mode=prebuilt`). `-Pnative=source` builds llama.cpp from the vendored tree
instead (capped parallelism via `-PnativeJobs=N`); CI uses source on the legs
where a clean prebuilt closure isn't available.

The compiled libs are staged under `native/<os>-<arch>/` and bundled into
**per-platform classifier jars** (`mochallama-core-<version>-<os>-<arch>.jar`) —
not a fat jar. A consumer pulls `mochallama-core` plus the classifier jar for
their platform (the `-platform` aggregator POM wires the right one). The rationale lives in the
repo's design-decisions journal (`docs/specs/03-decisions.md`, §12).

## starter — Spring Boot

`mochallama-spring-boot-starter` is `@AutoConfiguration` over core:

- **`LlamaCppService`** — owns the model lifecycle. `@PostConstruct init()`
  returns immediately and kicks off an async download+load on a
  `CompletableFuture`. State machine: `DOWNLOADING → LOADING → READY` (or
  `FAILED`). Registers the inference meters against the `MeterRegistry`.
- **`ChatCompletionsController`** — `@RestController` at `/v1`. Maps
  `POST /v1/chat/completions` (with `stream:true` → SSE) and `GET /v1/models`.
  Returns `503` while the model isn't `READY`, `400` for empty messages.
- **`MochallamaProperties`** — binds `llamacpp.model.*` (url/filename, `hf-id` +
  `quant`, context-size, generation defaults).
- **`MochallamaActuatorAutoConfiguration` / `MochallamaHealthIndicator`** — the
  Actuator health + meters story. See [Metrics](observability).

The OpenAI wire DTOs (`ChatCompletionRequest` / `ChatCompletionResponse`) live
here, not in core — core stays HTTP-agnostic.

## Startup behaviour

1. The Spring context boots; `LlamaCppService.init()` returns in microseconds
   after submitting one async task.
2. While it runs, `state` is `DOWNLOADING` then `LOADING`.
3. The task downloads the GGUF (if missing) to `~/.chatbot_models/<filename>`,
   then `ChatEngine.load(path)` calls `llb_chat_create`. On success `state`
   flips to `READY`; on failure `state = FAILED` with the last error.
4. The controller returns `503 {"error":"model loading","state":"…"}` until ready.

This guarantees the HTTP port comes up immediately even on a cold model cache —
useful behind a probe-based supervisor.

## JVM args

```
--enable-native-access=ALL-UNNAMED      # required by FFM
--add-modules=jdk.incubator.vector      # legacy, only for the in-tree Llama3.java sample
```

`--enable-native-access` is required for FFM downcalls. `--add-modules=…vector`
is only needed by the off-path pure-Java `Llama3.java` reference sample carried
over from the project's lineage; it is not on the runtime path of the service.
