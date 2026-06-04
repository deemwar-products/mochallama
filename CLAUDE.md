# CLAUDE.md — mochallama

Local LLM for the JVM. `Java → Project Panama FFM → thin C++ bridge (common_chat) →
vendored llama.cpp (tag b9371) → GGUF`. No JNI. Spring-first. **Tool-calling-only.**

Repo: https://github.com/deemwar-products/mochallama · Maven group `tools.deemwar` ·
Java pkg `tools.deemwar.mochallama` · npm scope `@deemwario` · gh user `deemwario`.
Live status snapshot is always `docs/specs/inprogress.md` (treat it as the source of truth; the
`docs/specs/03-decisions.md` + `04-deferred.md` journals are partly stale — they still
list streaming/tool-calling as "deferred" but both shipped).

## The goal (what "done" means)
One published library so any dev can: (a) drop a dependency into a plain Java app, or
(b) a Spring Boot app (autoconfig'd OpenAI `/v1/chat/completions`), or (c) `npx` a CLI
chat — all backed by local llama.cpp, no native install dance. Plus a demo app + web UI
and live docs. **Public publish is live** — Maven Central (`io.github.deemwario`) and npm
(`@deemwario`), all CI-driven; see "How we release" below.

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
- `task cli:stage-darwin-x64` — build the Intel jlink image locally + upload to the `cli-darwin-x64` GitHub release (gh, no npm); CI downloads it to publish the darwin-x64 npm pkg via OIDC. npm itself is **never** published locally — `release.yml` does all 5 platforms + launcher via OIDC on a `v*` tag.
- `task cli:stage-darwin-x64` — (see above) re-stage the Intel image; only when cli/core code changed
- `task publish:central` — **STUB, `exit 1`** — local Central push is unused; CI does Central now
- `task release` — local build+test+publish-local+jlink convenience; does NOT publish (CI does)

## How we release (two-tier, CI-driven, NOTHING published from a laptop)
Group `io.github.deemwario` on Maven Central; npm scope `@deemwario`. Native compile
and public publish are decoupled — see [[two-tier-native-release]] in memory.

- **Tier 1 `natives.yml` (rare):** builds the per-platform native closure (`llamabridge`
  + llama.cpp `b9371`) → durable **`natives-b9371`** prerelease. Triggers only on
  `core/src/main/cpp/**`. CI matrix = linux-x64, linux-arm64, darwin-arm64, windows;
  **darwin-x86_64 has no CI runner → seeded locally** (`task native` → `gh release upload`).
- **Staged Intel CLI image (local, gh-only):** darwin-x64 has no Intel CI runner, so
  `task cli:stage-darwin-x64` builds + load-smokes the jlink image once on a Mac and
  uploads it to the durable **`cli-darwin-x64`** prerelease (uses `gh`, never npm → no 2FA).
  Re-stage ONLY when `cli/` or `core/` code changes — version bumps alone don't need it.
- **Tier 2 `release.yml` (every `v*` tag):** **never compiles.** Downloads both releases,
  stages the natives (satisfies `buildNative`'s `onlyIf` guard so nothing builds), then:
  `publish-maven` (one Linux runner → classifier jars + `-platform` POM → signed Central
  bundle, `publishingType=AUTOMATIC`); `build-cli` (jlink the 4 CI platforms) + `cli-darwin-x64`
  (download staged image, `npm pack` on ubuntu); `publish-npm` (**OIDC trusted publishing**,
  all 5 platforms + launcher last, idempotent skip of already-published `name@version`);
  `release` (attach tarballs + native zips + app fat jar to the GitHub Release).

**To cut a release:** (1) if cli/core code changed, `task cli:stage-darwin-x64`; (2) bump
`version` in `build.gradle` + every `cli/npm*/package.json` (incl. launcher
`optionalDependencies`); (3) commit, `git tag vX.Y.Z`, `git push origin main vX.Y.Z`.
**One-time per package:** enable Trusted Publisher on npmjs.com (Settings → Trusted
Publisher → GitHub Actions → repo + `release.yml`). Repo secrets `SIGNING_KEY`,
`SIGNING_PASSWORD`, `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` drive Central.

### Status
- **Maven Central live at 0.1.4** (all 3 artifacts, every platform via the `-platform` jar).
- **npm: 4/5 platforms live at 0.1.4 via OIDC**; darwin-x64 + launcher were stuck at 0.1.1
  (old local-publish + npm 2FA) — fixed by the staged-image flow, shipping in **0.1.5**.
- Windows native + npm leg: `experimental`/`continue-on-error` (non-blocking).
- Docs on Pages: https://deemwar-products.github.io/mochallama/

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
  **rebrand muthuishere → deemwar-products / @deemwario**, v0.1.0 tag, ended with `docs/specs/inprogress.md`.
  (Lives under the ROOT slug, not the mochallama slug, because cwd was the workspace root.)
