# Positioning — what mochallama is (and isn't)

## What's actually unique

1. **Custom thin C bridge, not a direct `llama.h` binding.**
   `libllamabridge.dylib` exposes five symbols total
   (`llb_chat_create`, `llb_chat_infer`, `llb_string_free`,
   `llb_chat_destroy`, `llb_version`) and moves JSON across the boundary.
   The FFM layer therefore stays trivial (one `MethodHandle` per symbol)
   instead of mirroring dozens of `llama_*` structs and enums. Upgrading
   `llama.cpp` only touches the C bridge; the Java side is unaffected.

2. **Spring Boot first-class, not a library bolted into Spring.**
   `LlamaCppService` is a `@Service` with:
   - async `@PostConstruct` download + native load (`CompletableFuture`),
   - a `LoadState` state machine (`DOWNLOADING` → `LOADING` → `READY` / `FAILED`),
   - HTTP 503 returned by `ChatCompletionsController` while not `READY`,
   - `@PreDestroy` shutdown that calls `llb_chat_destroy` and closes the `Arena`.
   The HTTP surface is the OpenAI wire format (`POST /v1/chat/completions`,
   `GET /v1/models`), so any client that already speaks OpenAI drops in.

3. **Panama FFM on a GA JDK, not JNI and not preview FFM.**
   JDK 22 is the first release where `java.lang.foreign` is final
   (JEP 454). No `System.loadLibrary` JNI shim, no preview flags. Run
   args are just `--enable-native-access=ALL-UNNAMED` (and the legacy
   `--add-modules=jdk.incubator.vector` kept for the in-tree
   `Llama3.java` sample).

4. **Vendored llama.cpp + Gradle CMake + staged dylibs.**
   `buildNative` Gradle task drives CMake against `src/main/native/llama.cpp`,
   then stages the produced dylibs (versioned and unversioned names so
   `@loader_path` resolves) into `src/main/resources/native/darwin-x86_64/`.
   The fat JAR carries its own native stack — no `brew install`, no
   `LD_LIBRARY_PATH`.

The combination is the differentiator. Each piece exists elsewhere; nothing
in the landscape table combines all four.

## What's not differentiated

Be honest with prospective users about all of this up front:

- **Single host, single process.** No clustering, no horizontal scaling, no
  multi-tenant model loading. One model per JVM.
- **CPU only, but 5 platforms.** macOS Intel + Apple Silicon, Linux x86-64 +
  ARM64, Windows x86-64 all ship (per-platform native jars). Metal / CUDA /
  Vulkan are gated off in `CMakeLists.txt` — a build-flag away. Windows ARM64 is
  the remaining gap.
- **No tool calling.** The `ChatCompletionRequest` does not model `tools`
  or `tool_choice`; the bridge has no facility for it.
- **No streaming.** `stream: true` returns HTTP 501. There is no SSE
  endpoint; the FFM call is a single blocking `llb_chat_infer`.
- **Single-turn chat in the bridge contract.** `ChatEngine#chat` wraps the
  prompt as one user message; multi-turn assembly is done as plain prompt
  concatenation in the controller (`role: content\n` per message). No KV
  cache reuse strategy is exposed.
- **No grammar / JSON schema / LoRA / embeddings / re-ranking / infilling.**
  All of these are surfaced by `de.kherud:llama` 4.x; none are surfaced
  here.
- **Licensed MIT.** A root `LICENSE` (MIT) and a `NOTICE` acknowledging the
  vendored MIT-licensed llama.cpp + ggml are in the repo.

## Who is it for

**Primary fit.** A Java/Spring Boot backend dev who wants a local LLM
endpoint they can call over the OpenAI wire format from within the same
host (or a sidecar pod) without:

- spawning and supervising a separate `llama-server` process, or
- pulling in a JNI dependency they can't easily debug, or
- rewriting inference in pure Java and giving up llama.cpp's GGUF / quant
  ecosystem.

They are running JDK 22+, on an Intel Mac for now, doing single-turn or
short multi-turn chat without tools, and they value being able to read every
line of the binding (Java side is ~200 lines, C bridge is comparably small).

**Not a fit.** Production multi-GPU inference farms, anyone needing
streaming today, anyone needing tool calling today, anyone on Apple Silicon
or Linux or Windows until binaries are built. For those, `de.kherud:llama`
or `nixiesearch/llamacpp-server-java` is the honest answer.

## One-sentence positioning

> mochallama is a Spring Boot service that calls llama.cpp through a thin
> C ABI via JDK 22 Panama FFM, ships its own native stack in the JAR, and
> speaks the OpenAI wire format — for Java devs who want a local LLM
> endpoint without JNI and without a sidecar process.
