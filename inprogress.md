# mochallama — in progress

Snapshot of where the project stands. Local working dir: `local-llama-workspace/mochallama`. Repo: https://github.com/deemwar-products/mochallama

## What it is
Local LLM for the JVM: `Java → Project Panama FFM → custom thin C++ bridge (common_chat) → vendored llama.cpp → GGUF`. No JNI. Spring-first. Tool-calling-only.

## Architecture (5 Gradle modules)
- **core** — Panama FFM bindings + `ChatEngine` + `MochallamaClient` + the C++ bridge (`src/main/cpp`, links `llama` + `llama-common`) + vendored llama.cpp (`src/main/native`, tag b9371, gitignored). Publishable.
- **starter** — `mochallama-spring-boot-starter`: `@AutoConfiguration` for `LlamaCppService` (async load + state machine), OpenAI-compatible `/v1/chat/completions` (+ `stream:true` SSE) + `/v1/models`, Actuator metrics + health. No spring-ai dep (version-resilient). Publishable.
- **spring-ai** — `mochallama-spring-ai`: Spring AI `ChatModel`/`ChatClient` adapter incl. inbound tool mapping; `spring-ai-client-chat`/`-model` 1.0.8 as `compileOnly`. Publishable.
- **cli** — Picocli (`mochallama models` / `chat --model <profile|HF-id|path>`); jlink via Badass Runtime; npm wrapper.
- **app** — demo Spring Boot app + vanilla-JS web UI at `/`. Not published.

## Shipped + verified
- Real token usage (from the bridge, not estimates)
- Streaming (SSE + Spring AI `stream()`→Flux) via an FFM upcall token callback
- Tool calling (llama.cpp `common_chat`; full sampler params: temperature/top_k/top_p/min_p/max_tokens/repeat_penalty/seed/stop + system prompt)
- **Tool-calling-only enforcement**: bridge gate via `common_chat_templates_get_caps()`; non-tool models rejected at load (`MODEL_NOT_TOOL_CAPABLE`); Spring health `FAILED` + CLI exit-2 refusal. Verified: qwen2.5-1.5b loads, TinyLlama rejected.
- Hugging Face by model ID: shared `HuggingFaceModels` resolver (core; CLI + Spring de-duped); `llamacpp.model.hf-id` config; gated models fail early (`MODEL_GATED`).
- Tool-capable model presets only: `qwen2.5-1.5b` (default), `qwen2.5-3b`, `qwen3-4b`, `phi-4-mini`. Dropped gemma + llama-3.2.
- License **MIT** everywhere (LICENSE + NOTICE + POMs + npm); llama-bindings scrubbed from public docs.
- Multi-platform code: `NativeLoader` resolves os/arch (`.dylib`/`.so`/`.dll`); esbuild-style npm packages (`@deemwarhq/mochallama` launcher + per-platform `optionalDependencies`); `npx @deemwarhq/mochallama chat` works cross-platform.
- Cross-platform bridge `CMakeLists` (Apple: Accelerate/BLAS + `@loader_path`; Linux/Windows: plain CPU + `$ORIGIN`/none).
- VitePress docs live on GitHub Pages: https://deemwar-products.github.io/mochallama/ (specs, models, metrics, examples).
- Branding: Maven group `tools.deemwar` (→ `io.github.deemwar-products` planned for Central), Java package `tools.deemwar.mochallama`, npm scope `@deemwarhq`, gh `deemwario`, GitHub `deemwar-products`.

## Release state
- `v0.1.0` tagged + pushed (version off `-SNAPSHOT`).
- `release.yml` matrix builds natives + jlink images per platform → GitHub Release.
- **darwin-aarch64 builds successfully.** darwin-x86_64 builds locally.

## Known issues / blockers
1. **Linux release leg fails in CI** — dies with "runner received a shutdown signal / operation was canceled", no compiler error (compiles cleanly then gets killed; recurs even on a clean runner queue). Root cause not yet pinned (suspect GitHub-hosted runner/macOS-concurrency/infra, or an early cancel). UNDER INVESTIGATION — blocks a complete multi-platform release.
2. **Windows leg fails** — bridge `CMakeLists` not wired for MSVC; `continue-on-error` so it doesn't block others. Deferred (v0.1.1).
3. **`build.yml` over-triggers** — runs the full 4-platform matrix on every push AND every dependabot PR, jamming the scarce macOS runners. Needs trimming to an ubuntu-only light check; reserve the heavy matrix for `release.yml` (tags) only.
4. **GraalVM native-image** — deferred (FFM-in-native-image GA only in GraalVM-25 + macOS-AArch64; host is Intel).

## Remaining work
- **#24 LOCAL-PUBLISH** (pending): `task release:download` → publish cross-platform npm + Maven Central. Gated on:
  - npm: `npm login` + `@deemwarhq` org with publish rights
  - Maven Central: Central Portal account claiming `io.github.deemwar-products` (GitHub-verified) + GPG key → env `CENTRAL_PORTAL_USERNAME/TOKEN`, `SIGNING_KEY/PASSWORD`; switch group to `io.github.deemwar-products`
- Fix the Linux CI leg (#1 above); fix/defer Windows (#2); trim `build.yml` (#3).
