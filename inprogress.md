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

## Verified working — local end-to-end (2026-06-02 night, Intel Mac, qwen2.5-1.5b)
- App boots, loads model in ~17s, `/actuator/health` → UP.
- `/v1/chat/completions` non-stream → assistant text + real token usage.
- `stream:true` → `chat.completion.chunk` SSE token stream.
- Tool call → `tool_calls` + `finish_reason:"tool_calls"` (get_weather example).
- `/v1/models` lists model; web UI at `/` → HTTP 200; CLI `models` lists tool-only lineup.
- `task publish:local` → core/starter/spring-ai in `~/.m2` (core jar bundles 19 darwin-x86_64 dylibs).
- `task cli:npm:pack` → launcher + darwin-x64 platform tarballs.
See PUBLISHING.md for the publish runbook and docs/specs/05-release-and-publish.md for the spec.

## Known issues / blockers
1. **CI native build OOM/hang — ELIMINATED (2026-06-02).** Root cause: unbounded
   `cmake --build --parallel` OOM'd runners while compiling llama.cpp (Linux died
   ~2m at 83%; macos-13 Intel swap-thrashed 24h — one cause). **Resolved by no
   longer compiling llama.cpp at all**: `buildNative` now downloads prebuilt
   llama.cpp release libs and compiles only the 1-file bridge (~11s local;
   darwin-aarch64 CI leg ~60s vs the old 95m). See `docs/specs/03-decisions.md` §12,
   `core/build.gradle`. `-Pnative=source` fallback still caps parallelism. Verified
   on Intel + CI darwin-aarch64; full CI on branch `ci/fix-tier1-workflows` (PR #11).
2. **Windows leg fails** — bridge `CMakeLists` not wired for MSVC; `continue-on-error` so it doesn't block others. Deferred (v0.1.1).
3. **`build.yml` over-triggers — FIXED.** Trimmed to a single ubuntu-only compile
   check (`compileJava -x buildNative`) on push/PR; the heavy cross-platform matrix
   now lives only in `release.yml`. (It was also stale — used pre-module paths.)
4. **GraalVM native-image** — deferred (FFM-in-native-image GA only in GraalVM-25 + macOS-AArch64; host is Intel).

## Platform sourcing (decided 2026-06-02)
darwin-x86_64 = **built locally** (Intel Mac; the macos-13 CI leg is the one that hung,
dropped from the matrix); darwin-aarch64 + linux + windows = CI. A complete core jar =
local mac natives + `task release:download` (pulls CI natives) → `task publish:local`.

## Remaining work
- **#24 LOCAL-PUBLISH** (pending): `task release:download` → publish cross-platform npm + Maven Central. Gated on:
  - npm: `npm login` + `@deemwarhq` org with publish rights
  - Maven Central: Central Portal account claiming `io.github.deemwar-products` (GitHub-verified) + GPG key → env `CENTRAL_PORTAL_USERNAME/TOKEN`, `SIGNING_KEY/PASSWORD`; switch group to `io.github.deemwar-products`
  - `task release:download` now implemented (pulls CI natives + stages them).
- Linux CI leg (#1) + `build.yml` (#3) FIXED this session; Windows (#2) still deferred to v0.1.1.
