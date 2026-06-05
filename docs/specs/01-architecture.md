---
title: Architecture
---

# Architecture

mochallama runs a local GGUF model **inside the JVM process** through llama.cpp,
with **no JNI**. The path is:

```
Java  →  Project Panama FFM  →  thin C++ bridge (llamabridge)  →  llama.cpp (b9371)  →  GGUF
```

The Java side never touches JNI. It binds a tiny extern-`"C"` ABI through the
JDK 22 Foreign Function & Memory API. The bridge is the only native code we
write — everything below it is upstream llama.cpp.

> For *why* the stack is built this way (FFM over JNI, prebuilt over from-source,
> tool-calling-only, Spring-first), see [Why mochallama](/why). This page is the
> factual reference for *what* the pieces are.

## Quickstart

Pick the on-ramp that matches the surface you want. Real, published coordinates
(Maven Central `io.github.deemwario`, npm `@deemwario`).

::: code-group

```bash [CLI (no Java install)]
# jlink ships its own JDK 22 image — no local Java required
npx @deemwario/mochallama models
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

```kotlin [Gradle (plain Java)]
dependencies {
  implementation("io.github.deemwario:mochallama-core:0.1.6")
  // pulls the right native classifier jar for the host platform
  runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
}
```

```xml [Maven (plain Java)]
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core</artifactId>
  <version>0.1.6</version>
</dependency>
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core-platform</artifactId>
  <version>0.1.6</version>
  <scope>runtime</scope>
</dependency>
```

```kotlin [Gradle (Spring Boot)]
dependencies {
  implementation("io.github.deemwario:mochallama-spring-boot-starter:0.1.6")
}
// POST /v1/chat/completions (+SSE), GET /v1/models, Actuator health/metrics
```

:::

Plain-Java call:

```java
try (var engine = ChatEngine.load(Path.of("model.gguf"))) {
    ChatResult r = engine.chat(List.of(new Message("user", "Hello")));
    System.out.println(r.content());
}
```

JVM args (see [JVM args](#jvm-args)) are required for FFM downcalls:
`--enable-native-access=ALL-UNNAMED`.

## Modules

The build is five Gradle subprojects (`settings.gradle`):

| Module | What it is | Published |
|--------|------------|-----------|
| **core** | `mochallama-core`: FFM bindings, `ChatEngine`, `MochallamaClient`, and the C++ bridge (`core/src/main/cpp`). Java-only jar (~40 KB); per-platform native libs ship as classifier jars. | ✅ |
| **starter** | `mochallama-spring-boot-starter` — `@AutoConfiguration` for the model service, the OpenAI `/v1/chat/completions` (+SSE) and `/v1/models` endpoints, and Actuator metrics/health. No Spring AI dependency. | ✅ |
| **spring-ai** | `mochallama-spring-ai` — a Spring AI `ChatModel` / `ChatClient` adapter. `spring-ai-*` 1.0.8 is `compileOnly` (the consumer supplies the version). | ✅ |
| **cli** | Picocli CLI (`mochallama models` / `chat`), packaged as a jlink image that **bundles JDK 22**, wrapped for `npx` as `@deemwario/mochallama`. | — |
| **app** | Demo Spring Boot app + a vanilla-JS web UI at `/`. | — |

Coordinates: Maven group `io.github.deemwario`; Java package
`tools.deemwar.mochallama`; npm scope `@deemwario`. Published live on Maven
Central + npm at **0.1.6** (**0.1.6** adds the multi-turn CLI; see
[CLI](#cli-multi-turn-sessions)).

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
`common_sampler` (the sampler chain) rather than re-implementing them. This is a
hand-written bridge, **not** jextract-generated, so the ABI surface stays tiny
and under our control.

The ABI — 7 functions:

| Function | Purpose |
|----------|---------|
| `llb_chat_create(gguf_path, …)` | Load a model + context, build the engine handle. Returns `NULL` if the model's chat template is not tool-capable. |
| `llb_chat_infer(h, request_json)` | Single-shot inference; returns a JSON result string. |
| `llb_chat_infer_stream(h, request_json, token_cb, userdata)` | Streaming inference; invokes `token_cb` per token, used by the SSE path. |
| `llb_model_info(h)` | Model metadata (id, context size, …). |
| `llb_version()` | llama.cpp version string (static — do not free). |
| `llb_string_free(s)` | Free a string the bridge returned. |
| `llb_chat_destroy(h)` | Tear down the engine handle. |

Tool capability is enforced **here, at the bridge**: a model whose chat template
can't do tool calling is rejected at load with `MODEL_NOT_TOOL_CAPABLE`.

### Native build — prebuilt by default

`:core:buildNative` downloads llama.cpp's **official prebuilt release binaries**
(tag `b9371`) for the host platform and compiles **only the bridge** against the
vendored headers — about 2–11s, not a from-source llama.cpp build. This is the
default (`mode=prebuilt`). `-Pnative=source` builds llama.cpp from the vendored
tree instead (capped parallelism via `-PnativeJobs=N`); CI uses source on the
legs where a clean prebuilt closure isn't available.

### Packaging — classifier jars + the platform aggregator

The compiled libs are staged under `native/<os>-<arch>/` and bundled into
**per-platform classifier jars** — not a fat jar:

```
mochallama-core-0.1.6.jar                       # Java only, ~40 KB
mochallama-core-0.1.6-darwin-aarch64.jar        # natives, one per platform
mochallama-core-0.1.6-darwin-x86_64.jar
mochallama-core-0.1.6-linux-x86_64.jar
mochallama-core-0.1.6-linux-aarch64.jar
mochallama-core-0.1.6-windows-x86_64.jar
```

`NativeLoader` resolves `classpath:/native/<os>-<arch>/` at runtime, so a
consumer only needs the **one** classifier jar that matches their host. Picking
that classifier by hand per platform is the chore — so
**`mochallama-core-platform`** is an *aggregator POM*: it has no code of its own,
just `runtime`-scope dependencies on all five classifier jars. Add it once and
the host's resolver brings in every platform's native; `NativeLoader` then loads
the one that fits. (Maven/Gradle download all five classifier jars but only one
is ever `System.load`ed.)

```kotlin
implementation("io.github.deemwario:mochallama-core:0.1.6")
runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
```

The starter depends on `mochallama-core` + the platform aggregator transitively,
so a Spring Boot consumer gets natives with no extra declaration.

Supported platforms: macOS Intel + Apple Silicon, Linux x86-64 + ARM64,
Windows x86-64. For the rationale behind this packaging, see
[Why mochallama](/why).

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

## cli — multi-turn, sessions {#cli-multi-turn-sessions}

The Picocli CLI ships as a self-contained jlink JDK-22 runtime image (Badass
Runtime), wrapped as npm `@deemwario/mochallama` with per-platform
`optionalDependencies` (the host's jlink image, ~31 MB). `npx @deemwario/mochallama`
needs **no local Java install**.

Commands:

- `mochallama models` — list the tool-capable presets.
- `mochallama chat -m <profile|HF-id|path>` — interactive chat. As of **0.1.6**
  this is **real multi-turn**: it keeps the full conversation history across
  turns instead of treating each line as an amnesiac single-shot.
- `mochallama sessions` — list saved sessions (id, model, turns, last-updated).

::: tip New in 0.1.6
Conversations persist as sessions under
`~/.chatbot_models/sessions/<id>.json`.

- `mochallama chat --resume <id>` — continue a prior conversation.
- `--no-save` — run an ephemeral session that is never written to disk.

In-REPL slash commands: `/reset` (clear history), `/help`, `/exit`.
:::

## JVM args {#jvm-args}

```
--enable-native-access=ALL-UNNAMED      # required by FFM
--add-modules=jdk.incubator.vector      # legacy, only for the in-tree Llama3.java sample
```

`--enable-native-access` is required for FFM downcalls. `--add-modules=…vector`
is only needed by the off-path pure-Java `Llama3.java` reference sample carried
over from the project's lineage; it is not on the runtime path of the service.
JDK 22+ is required (FFM GA).
