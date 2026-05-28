# mochallama — Architecture

## File layout

Top-level (project root, currently `llamavector-java/`):

```
.
├── build.gradle
├── settings.gradle              (rootProject.name = 'mochallama')
├── gradle/, gradlew, gradlew.bat
├── test-completions.sh
└── src
    ├── main
    │   ├── cpp                  C bridge source
    │   │   ├── CMakeLists.txt
    │   │   ├── include
    │   │   │   └── llamabridge.h
    │   │   └── src
    │   │       └── llamabridge.c
    │   ├── native
    │   │   └── llama.cpp        vendored upstream (subdir, full tree)
    │   ├── java/tools/deemwar/mochallama
    │   │   ├── MochallamaApplication.java
    │   │   ├── ChatCompletionsController.java
    │   │   ├── ChatCompletionRequest.java
    │   │   ├── ChatCompletionResponse.java
    │   │   ├── ChatBot.java         (legacy, pure-Java demo)
    │   │   ├── Llama3.java          (legacy, pure-Java Vector-API impl)
    │   │   ├── panama
    │   │   │   ├── LlamaBridge.java
    │   │   │   ├── NativeLoader.java
    │   │   │   └── ChatEngine.java
    │   │   └── service
    │   │       └── LlamaCppService.java
    │   └── resources
    │       ├── application.properties
    │       └── native/darwin-x86_64/      staged dylibs (built artefacts)
    │           ├── libllamabridge.dylib
    │           ├── libllama{,.0,.0.0.1}.dylib
    │           └── libggml{,-base,-cpu,-blas}{,.0,.0.13.0}.dylib
    └── test/java/tools/deemwar/mochallama
        ├── MochallamaApplicationTests.java
        ├── ChatBotTest.java
        └── Llama3Test.java
```

Note: `Llama3.java` and `ChatBot.java` are a pre-existing pure-Java
implementation (Vector API based, from a separate jbang single-file project).
They are not on the runtime path of the HTTP service. See `03-decisions.md`.

## Package responsibilities

### `tools.deemwar.mochallama` (root)

- `MochallamaApplication` — Spring Boot main class.
- `ChatCompletionsController` — `@RestController` at `/v1`. Maps
  `POST /v1/chat/completions` to the engine and `GET /v1/models` to a
  single-model list. Returns `503` while the model isn't `READY`, `400` for
  empty messages, `501` for `stream:true`.
- `ChatCompletionRequest` / `ChatCompletionResponse` — Jackson DTOs for the
  OpenAI wire shape (Lombok `@Data`).

### `tools.deemwar.mochallama.panama`

- `LlamaBridge` — Pure FFM glue. Holds a `Linker`, a process-wide
  `SymbolLookup` (`loaderLookup().or(defaultLookup())`), and one downcall
  `MethodHandle` per ABI function (`CHAT_CREATE`, `CHAT_INFER`,
  `STRING_FREE`, `CHAT_DESTROY`, `VERSION`). Static initialiser calls
  `NativeLoader.load()` before any symbol lookup. Exposes a `readCString`
  helper that reinterprets an address with an unbounded window.
- `NativeLoader` — Extracts every regular file under
  `classpath:/native/<os>-<arch>/` into a fresh `Files.createTempDirectory`
  (handles both file: and jar: URLs), then `System.load`s the dylibs in a
  fixed dependency order: `ggml-base, ggml-cpu, ggml-blas, ggml, llama,
  llamabridge`. Idempotent (`volatile Path bridgePath` + lock).
- `ChatEngine` — Higher-level facade. Owns a confined `Arena` for the
  engine handle's lifetime; opens a per-call confined arena to marshal the
  request JSON. Marshals/unmarshals JSON with Jackson. Single-shot
  `chat(prompt, maxTokens, temperature)` API. `AutoCloseable` — `close()`
  calls `llb_chat_destroy` and tears down the arena. Not thread-safe;
  caller must serialise.

### `tools.deemwar.mochallama.service`

- `LlamaCppService` — Spring `@Service` that owns the model lifecycle:
  - `@PostConstruct init()` returns immediately and kicks off
    `CompletableFuture.runAsync(this::downloadAndLoad)`.
  - `LoadState` enum: `DOWNLOADING → LOADING → READY` (or `FAILED`).
  - `ensureModelDownloaded()` — `HEAD` for size, GET to a `.partial` file,
    atomic `Files.move` on success. Idempotent: skips if the file is
    already present.
  - `loadNative(modelPath)` calls `ChatEngine.load(modelPath)`.
  - `chat(prompt, ...)` — `synchronized`, throws if not `READY`.
  - `@PreDestroy shutdown()` closes the `ChatEngine`.

## Native side

### `src/main/cpp/include/llamabridge.h`

Five-function C ABI. Full content is reproduced in `02-bridge-abi.md`.
Highlights:

- `llb_chat_create(const char* gguf_path, llb_event_cb cb, void* userdata)`
- `llb_chat_infer(llb_chat_t*, const char* request_json)`
- `llb_string_free(const char*)`
- `llb_chat_destroy(llb_chat_t*)`
- `llb_version(void)` — static string, do not free.

### `src/main/cpp/src/llamabridge.c`

Single translation unit. Implements: model + context lifecycle, a minimal
JSON request parser (top-level scalar fields + `messages` array; `\uXXXX`
without surrogate-pair support), chat-template application via
`llama_chat_apply_template` with a chatml fallback, prompt tokenisation,
batched `llama_decode` over the prompt, a sampler chain
(top-k 40 → top-p 0.95 → temperature → dist), greedy token loop until EOG
or `max_tokens`, JSON response builder with proper escaping. KV cache is
cleared at the start of every `infer` so calls are independent.

### `src/main/cpp/CMakeLists.txt`

Configures the vendored llama.cpp subproject as `EXCLUDE_FROM_ALL`, with:

- `BUILD_SHARED_LIBS=ON`
- `LLAMA_BUILD_{TESTS,EXAMPLES,SERVER,TOOLS}=OFF`, `LLAMA_CURL=OFF`
- `GGML_{METAL,CUDA,VULKAN,HIP}=OFF`, `GGML_ACCELERATE=ON`, `GGML_BLAS=ON`
  with `GGML_BLAS_VENDOR=Apple`

Builds `libllamabridge.so` linked against `llama` (transitively pulling
ggml). `INSTALL_RPATH=@loader_path` so the bridge finds its sibling dylibs
at runtime from the same directory.

## Build flow

```
./gradlew bootRun   (or assemble)
        │
        ▼
buildNative  (Gradle task, registered in build.gradle)
   ├─ cmake -S src/main/cpp -B build/native-cmake -DCMAKE_BUILD_TYPE=Release
   ├─ cmake --build build/native-cmake --parallel
   └─ stage: for each stem (llamabridge, llama, ggml, ggml-base/cpu/blas)
         locate the regular dylib file (not the symlink), copy its bytes
         under EVERY name in the symlink chain into
         src/main/resources/native/darwin-x86_64/
        │
        ▼
compileJava / processResources  (depend on buildNative)
        │
        ▼
bootJar  → fat jar with the dylibs under BOOT-INF/classes/native/...
        │
        ▼
runtime → NativeLoader extracts every file in that directory into a
          tempdir and System.load()s them in dependency order
```

The bridge is linked against versioned SONAMEs (e.g. `@rpath/libllama.0.dylib`).
That's why the loader stages every name from each symlink chain rather than
just the canonical `lib<x>.dylib`: macOS dyld resolves the exact name the
bridge was linked against, and `@loader_path` finds the sibling.

## JVM args

From `build.gradle`:

```groovy
applicationDefaultJvmArgs = [
    '--add-modules=jdk.incubator.vector',
    '--enable-native-access=ALL-UNNAMED'
]
```

- `--enable-native-access=ALL-UNNAMED` — required by FFM; otherwise downcall
  handle creation logs warnings (and will fail outright in later JDKs).
- `--add-modules=jdk.incubator.vector` — only needed by the legacy
  `Llama3.java` pure-Java implementation. Carries over for compatibility
  with the existing tests; can be dropped when that code is removed.

## Spring Boot startup

1. `MochallamaApplication.main()` boots the context.
2. `LlamaCppService.@PostConstruct init()` returns in microseconds, having
   submitted a single async task on the common pool.
3. While the task runs, `state` is `DOWNLOADING` then `LOADING`.
4. The async task downloads the GGUF (if missing) to
   `~/.chatbot_models/<filename>`, then calls `ChatEngine.load(path)` which
   invokes `llb_chat_create`. On success, `state` flips to `READY`. On
   failure, `state = FAILED` and `lastError` is set.
5. The controller checks `isReady()` and returns `503 Service Unavailable`
   with `{"error":"model loading","state":"..."}` until ready.

This guarantees the HTTP port comes up immediately even when the model file
isn't cached locally — useful behind a probe-based supervisor.
