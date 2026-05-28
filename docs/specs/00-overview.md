# mochallama — Overview

mochallama is a Spring Boot service that runs GGUF chat models locally by
binding to `llama.cpp` through a thin C ABI (`libllamabridge.dylib`) using the
JDK 22 Foreign Function & Memory API (Panama FFM). It exposes an
OpenAI-compatible HTTP surface (`POST /v1/chat/completions`, `GET /v1/models`)
so it can drop into any client that already speaks the OpenAI wire format.
The native bridge is built from a vendored copy of `llama.cpp`, staged into
the JAR as platform-specific resources, and extracted + loaded at runtime by
a small `NativeLoader`.

## Architecture (logical)

```
+-----------------------------------------------------------+
|  HTTP client (curl, OpenAI SDK, Spring AI, ...)           |
+------------------------+----------------------------------+
                         | POST /v1/chat/completions
                         v
+-----------------------------------------------------------+
|  Spring Boot 3.3.1 app                                    |
|  +-----------------------------------------------------+  |
|  |  ChatCompletionsController  (OpenAI wire format)    |  |
|  +-----------------------+-----------------------------+  |
|                          |                                |
|                          v                                |
|  +-----------------------------------------------------+  |
|  |  LlamaCppService                                    |  |
|  |    async download + load,  LoadState state machine  |  |
|  +-----------------------+-----------------------------+  |
|                          |                                |
|                          v                                |
|  +-----------------------------------------------------+  |
|  |  ChatEngine  (Panama FFM facade)                    |  |
|  +-----------------------+-----------------------------+  |
|                          |                                |
|                          v                                |
|  +-----------------------------------------------------+  |
|  |  LlamaBridge  (downcall MethodHandles)              |  |
|  +-----------------------+-----------------------------+  |
+--------------------------|--------------------------------+
                           |  C ABI (5 functions)
                           v
            +-------------------------------+
            |  libllamabridge.dylib         |   <-- our C wrapper
            +---------------+---------------+
                            |
                            v
            +-------------------------------+
            |  libllama.dylib + libggml*    |   <-- vendored llama.cpp
            +---------------+---------------+
                            |
                            v
            +-------------------------------+
            |  GGUF model file on disk      |
            +-------------------------------+
```

## Quick facts

- Runtime: JDK 22 (FFM went GA in 22). Required JVM args:
  `--enable-native-access=ALL-UNNAMED` and the legacy
  `--add-modules=jdk.incubator.vector` (kept for the in-tree Vector-API
  pure-Java sample, `Llama3.java`).
- Framework: Spring Boot 3.3.1, single web app, no DB.
- Native: built from vendored `src/main/native/llama.cpp` via CMake, driven
  by a custom Gradle `buildNative` task.
- Platform support today: macOS Intel `x86_64`, CPU-only (Accelerate / BLAS).
  Metal/CUDA/Vulkan are gated off in `CMakeLists.txt`; flipping them on is
  a build-flag change.
- Default model: `qwen2.5-1.5b-instruct-q4_k_m.gguf` (downloaded on first
  startup into `~/.chatbot_models`). Tool-capable; see `models.md`.
- Streaming (SSE), tool calling, real token usage and the Spring AI adapter
  have shipped — see `streaming-and-tools.md`. Linux/Windows binaries remain
  deferred — see `04-deferred.md`.

## Naming

The Gradle project is named **mochallama** (`rootProject.name = 'mochallama'`,
group `tools.deemwar`) and the Java package is `tools.deemwar.mochallama`.
The on-disk repo directory is still `llamavector-java` (left as-is; the rename to
mochallama was applied to packages, classes, and Gradle identifiers only — see
decision log in `03-decisions.md`).
