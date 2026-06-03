# CLAUDE.md — mochallama

Local LLM for the JVM. `Java → Project Panama FFM → thin C++ bridge (common_chat) →
vendored llama.cpp (tag b9371) → GGUF`. No JNI. Spring-first. **Tool-calling-only.**

Repo: https://github.com/deemwar-products/mochallama · Maven group `tools.deemwar` ·
Java pkg `tools.deemwar.mochallama` · npm scope `@deemwario` · gh user `deemwario`.
Live status snapshot is always `inprogress.md` (treat it as the source of truth; the
`docs/specs/03-decisions.md` + `04-deferred.md` journals are partly stale — they still
list streaming/tool-calling as "deferred" but both shipped).

## The goal (what "done" means)
One published library so any dev can: (a) drop a dependency into a plain Java app, or
(b) a Spring Boot app (autoconfig'd OpenAI `/v1/chat/completions`), or (c) `npx` a CLI
chat — all backed by local llama.cpp, no native install dance. Plus a demo app + web UI
and live docs. Demo + docs + local Maven already work; **public publish is not done** —
see "Publish state" below.

## Modules (5 Gradle subprojects — `settings.gradle`)
- **core** — FFM bindings + `ChatEngine` + `MochallamaClient` + C++ bridge
  (`core/src/main/cpp`). Native build (`:core:buildNative`) **downloads prebuilt
  llama.cpp release libs** (mode=prebuilt, default; `-Pnative=source` to build
  from the vendored tree) and compiles only the bridge — ~11s, no llama.cpp
  compile. Bundles the per-platform native libs into the jar. Publishable.
  See `docs/specs/03-decisions.md` §12.
- **starter** — `mochallama-spring-boot-starter`: `@AutoConfiguration` for
  `LlamaCppService` (async load + state machine), OpenAI `/v1/chat/completions`
  (+SSE `stream:true`) + `/v1/models`, Actuator metrics/health. No spring-ai dep. Publishable.
- **spring-ai** — `mochallama-spring-ai`: Spring AI `ChatModel`/`ChatClient` adapter;
  `spring-ai-*` 1.0.8 as `compileOnly` (consumer supplies). Publishable.
- **cli** — Picocli (`mochallama models` / `chat --model <profile|HF-id|path>`); jlink via
  Badass Runtime; npm wrapper under `cli/npm`. Not a library.
- **app** — demo Spring Boot app + vanilla-JS web UI at `/`. Not published.

## Runtime facts
- JDK 22 (FFM GA). JVM args: `--enable-native-access=ALL-UNNAMED` +
  `--add-modules=jdk.incubator.vector` (legacy, for the in-tree `Llama3.java` sample).
- Tool-capable presets only: `qwen2.5-1.5b` (default), `qwen2.5-3b`, `qwen3-4b`,
  `phi-4-mini`. Non-tool models rejected at load (`MODEL_NOT_TOOL_CAPABLE`).
- HF-by-id: `llamacpp.model.hf-id`; gated models fail early (`MODEL_GATED`).
- `NativeLoader` resolves os/arch (`.dylib`/`.so`/`.dll`).

## Key commands (Taskfile.yml — needs JDK 22 in JAVA_HOME)
- `task build` / `task test` / `task native` (`:core:buildNative`)
- `task app:run` — demo app + web UI
- `task publish:local` — core+starter+spring-ai → `~/.m2` (works today, host arch only)
- `task cli:npm:pack` / `cli:npm:publish` — npm (darwin-x64 local; other platforms from CI)
- `task publish:central` — **STUB, `exit 1`** — needs creds + signing
- `task release` — build+test+publish-local+jlink, prints manual next steps (no auto-publish)

## Publish state (as of v0.1.0)
- `v0.1.0` tagged + pushed, but the **release.yml run FAILED (~24h) → the GitHub Release
  has ZERO assets.** Nothing to download, so the "build in CI → download → publish locally"
  flow (npm + Maven Central) cannot proceed.
- Works: build+test green on mac; darwin-aarch64 + darwin-x86_64 build green in CI;
  `publish:local`; demo; docs on Pages (https://deemwar-products.github.io/mochallama/).

### Blockers to a real public release
1. **CI native OOM/hang — FIXED 2026-06-02.** Linux fast-fail ("runner received a
   shutdown signal" at 83%) and the macos-13 24h hang were one cause: unbounded
   `cmake --build --parallel` OOM'd the runner. Fixed in `core/build.gradle`
   (cap to 2 jobs via `-PnativeJobs`, build only `llamabridge`). Validating on
   branch `ci/fix-tier1-workflows`.
2. **Windows leg fails** — bridge `CMakeLists` has mac-isms (Accelerate/`@loader_path`);
   `continue-on-error: true` so non-blocking. Deferred to v0.1.1.
3. **`build.yml` over-triggers — FIXED.** Trimmed to a single ubuntu compile check
   (`compileJava -x buildNative`); heavy matrix now only in `release.yml`.
4. **Maven Central not set up** (human-gated) — Central Portal account claiming
   `io.github.deemwario` (GitHub-verified) + GPG key → `CENTRAL_PORTAL_USERNAME/TOKEN`,
   `SIGNING_KEY/PASSWORD`; switch group `tools.deemwar` → `io.github.deemwario`.
5. **npm `@deemwario` org publish rights** (human-gated) — `npm login` + org access.
6. **10 open dependabot PRs** — some major/risky (Spring Boot 4.0.6, jakarta 3,
   download-artifact 8). Triage, don't auto-merge.

## GraalVM native-image — deferred (FFM-in-native-image GA only on GraalVM-25 + macOS-AArch64; host is Intel).

## Project lineage (how this folder came to be)
mochallama descends from two now-archived ancestors in `../archive/`:
- `archive/llama-java` (Jul 2024) — pure-Java Vector-API Llama3 (`Llama3.java`, 2080 LOC);
  the pre-FFM seed. Its `Llama3.java`/`ChatBot.java` survive as off-path reference in `app/`.
- `archive/llamavector-java` (May 2026) — FFM scaffold + abandoned 5-module split, remote
  `muthuishere/mochallama`, pkg `tools.muthuishere.mochallama`. Stale dead-end fork.
- Direct C-bridge design ancestor: `~/muthu/gitworkspace/small-llm-workspace/llama-bindings/`
  (earlier thin-C-bridge + Java-FFM + Go/WASM project — why we chose "thin C bridge, not jextract").

**Sibling projects (NOT mochallama, same workspace, share the llama.cpp domain):**
- `../llama-cpu-benchmarks` (was `small-server-ws`) → repo `deemwar-products/llama-local-benchmarks`
  — turboquant + tool-calling model benchmarking.
- `../pocket-llm` — separate M0/M2-milestone project, also under deemwar-products.

### Claude session history (where the work happened)
- `local-llama-workspace` root `2b15f585` (May 28 07:47, 100 ln) — genesis/research:
  Maven+C++ layout, prior-art hunt (kherud/java-llama.cpp = JNI, rejected; Utilitron/LlamaFFM
  = FFM-no-Spring; debunked the mythical `spring-ai-llama-cpp`).
- `llamavector-java` `4feb02e1` (May 28, 164 ln) — naming (→ Mochallama, Mocha=Java),
  initial commit, worktree, Jlama comparison.
- `local-llama-workspace` root `d80678c5` (May 28 17:10, **1888 ln**) — THE main build:
  5-module split, FFM bridge, streaming, real tokens, tool-calling-only, HF-by-id, CLI+web UI,
  **rebrand muthuishere → deemwar-products / @deemwario**, v0.1.0 tag, ended with `inprogress.md`.
  (Lives under the ROOT slug, not the mochallama slug, because cwd was the workspace root.)
