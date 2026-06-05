# mochallama — Product Brief (Why, How We Got Here, Where We Stand)

The other specs in this folder describe *what mochallama is*. This one
documents **why it exists, what we evaluated and rejected, what we learned
the hard way, and what's left**. It's the README that goes underneath the
README — written so a future contributor (or future-Muthu) can recover the
reasoning without reading 2,600-line session transcripts.

Sources mined: CLAUDE.md, inprogress.md, README.md, the existing
`00-overview` / `03-decisions` / `04-deferred` specs, and three Claude
session transcripts (workspace-root genesis `2b15f585`, the main build
`d80678c5` at 1887 lines, and the v0.1.0/CI session `5565d72e` at 2647
lines).

---

## 1. The thesis — why this needs to exist

**One-liner.** *Drop a Spring Boot starter into a Java app and get a local
LLM with an OpenAI-compatible HTTP surface, Spring AI `ChatClient`, real
streaming, tool calling, and metrics — with zero native install dance.*

The four-way intersection that nobody else covers:

```
              JNI-free (Panama FFM)
                       │
        ┌──────────────┼──────────────┐
        │              │              │
   In-process     OpenAI-compatible   Spring-first
   (not HTTP to   (drop-in for any    (autoconfig,
    Ollama)        OpenAI client)      Actuator, AI)
        │              │              │
        └──────────────┴──────────────┘
                       │
                Tool-calling-only
                  (real agents,
               not chat-only demos)
```

Verbatim from the genesis session (`2b15f585`, message 4):

> "find on web whoever tries this and where they stopped and how we are
> different or same so i dont want to do the same"

The answer that came back, after a fan-out research pass:

| Project | What | Why not it |
|---|---|---|
| **kherud/java-llama.cpp** | De-facto JNI bindings | JNI, not FFM. "If you replace your FFM TODOs with this, you become *this* project." |
| **spring-ai + Ollama starter** | HTTP to local Ollama daemon | Sidesteps in-process binding. Ollama owns model lifecycle. |
| **mukel/llama3.java** | Pure-Java Vector API | No native acceleration ceiling. No Spring. (Lives on in `app/` as reference.) |
| **Jlama** | Pure-Java Panama Vector | Same — explicitly skips the Spring DI / config / Actuator surface. |
| **Utilitron/LlamaFFM** | Closest on FFM dimension | No Spring, not published, no tool calling, no streaming. |
| **rsatrio/llm-chatbot-springboot** | Spring 3 + llama.cpp + OpenAI endpoint | Uses kherud (JNI). |
| ~~`spring-ai-llama-cpp`~~ | (Hallucinated artifact) | Doesn't exist. User caught the hallucination: *"can you recheck spring-ai-llama-cpp"*. |

Muthu's framing of the gap (session `d80678c5`):

> "i want a single library so whoever uses java and spring can do and run
> stuff."
>
> "mochallama idea is to projduce a java binding library which can work on
> plain cli and also another library in spring so it can work wherever."

This is **the entire product thesis**: one library, three consumption
modes (plain Java, Spring Boot, CLI), no native install step for the
consumer, OpenAI-shape so anything already written for OpenAI runs locally.

---

## 2. Lineage — how we got here

Mochallama is the third attempt; the prior two are archived under
`../archive/`.

```
2024-07  ┌─ archive/llama-java                    (pure-Java Vector API, "Llama3.java")
         │     │  Pre-FFM. Karpathy-port-style. Worked for Llama-3 only.
         │     │  Survives as reference in app/.
2025–26  │     ▼
         ├─ archive/llamavector-java              (FFM scaffold, abandoned)
         │     │  First Spring-Boot-on-FFM attempt. 5-module split started.
         │     │  Personal `muthuishere/mochallama` remote.
         │     │  Dead-end fork; useful pattern, wrong identity.
         │     ▼
2026-05  └─ mochallama                            (this project)
                │  Same workspace dir name as the dead fork (intentional
                │  — renaming a live working dir mid-session is risky)
                │  but the code, naming, and remote are all new.

Design ancestor (sibling, not parent):
  ~/muthu/gitworkspace/small-llm-workspace/llama-bindings/
    ├─ Java + Go (purego) + Browser WASM bindings
    └─ Established the pattern: "thin C bridge, not jextract over llama.h"
       Mochallama re-implements the pattern; doesn't depend on this repo.
```

The lineage matters because it explains several mochallama choices:

- **Why a thin C bridge, not jextract** — `llama-bindings/` proved a
  5-function `extern "C"` ABI is enough; `llama.h` exposes ~150 symbols
  whose layouts change every release. We pin a narrow surface.
- **Why vendor llama.cpp** — `llama-java`'s Llama-3-only loader broke on
  every model that wasn't Llama-3 Q4_0. Vendoring + a chat-template-aware
  bridge sidesteps the whole class of "tokenizer.ggml.merges missing"
  errors.
- **Why "tool-calling only"** — `llamavector-java` had a working chat
  endpoint with non-tool models. It looked finished but was useless for
  the agentic workflows we actually want to drive locally. Mochallama
  rejected this softness up front.

---

## 3. Lessons learned (the hard ones)

These are the discoveries that have already been paid for. They live here
so we don't repay for them.

### 3.1 Don't compile what upstream already ships

**The 95-minute compile that became 11 seconds.** The CI matrix was
unbuildable: Linux ran out of RAM in ~2 min at 83% of the llama.cpp build;
macos-13 Intel **swap-thrashed for 24 hours** before being cancelled.
First fix was capping `cmake --build --parallel`. Real fix: stop
compiling llama.cpp at all. llama.cpp ships official prebuilt release
binaries (`libllama` + `libggml*` + `libllama-common`) for every platform
we target, pinned to the same tag we already vendor headers from.
Download those; compile only the ~700 LOC `extern "C"` bridge.

Local: 95 min → ~11 sec. CI darwin-aarch64 leg: 95 min → ~60 sec.

Muthu's principle in his own words (session `5565d72e`):

> "i want a simple binary dont want them to compile stuff which i hate."

Saved as a memory entry (`prefers-prebuilt-no-compile`) and codified in
`docs/specs/03-decisions.md` §12. The principle generalises: **before
vendoring + compiling an upstream C/C++ dep, check if it publishes
prebuilt binaries and consume those; compile only the thin glue you own.**

### 3.2 Green build ≠ working binary (the smoke-test discovery)

Three platforms had green CI builds that wouldn't load at runtime:

1. **darwin-aarch64** — Apple Silicon prebuilt `libggml.dylib`
   hard-links `@rpath/libggml-metal.0.dylib`; we don't bundle Metal →
   `UnsatisfiedLinkError`. (Intel prebuilt doesn't have this.)
2. **windows-x86_64 (round 1)** — MSBuild emits a
   `llamabridge.dll.recipe` (1195 bytes); the staging glob's
   `.contains(".dll.")` rule matched it and **copied its 1195 bytes
   over the real 1.99 MB DLL** → "not a valid Win32 application."
3. **windows-x86_64 (round 2)** — after the recipe fix, missing symbol
   exports from MSVC (no `__declspec(dllexport)`).

Fix: a `NativeLoadSmokeTest` that calls `LlamaBridge.version()` →
`llb_version` — a no-model native call that proves the full
classpath → extract → `dlopen`/`LoadLibrary` chain works at runtime. Runs
as a per-leg CI step. Cost: trivial. Catches: exactly the failure class
build-only checks miss.

> "The key engineering win this round: adding NativeLoadSmokeTest as a
> per-leg CI oracle — without it, we'd have shipped three platforms that
> 'built successfully' but crash on first load."

### 3.3 Platform sourcing: not all prebuilts are equal

llama.cpp's prebuilt releases are inconsistent across platforms. The
final matrix is:

| Platform | Source | Why |
|---|---|---|
| `darwin-x86_64` | **prebuilt, local** | Single `ggml-cpu`, no Metal dep, no need for cross-compile. Built on Muthu's Intel mac (`task release:download` pulls others). |
| `darwin-aarch64` | source, CI | Prebuilt hard-links `@rpath/libggml-metal` — we don't bundle Metal. Source build with `GGML_NATIVE OFF` produces a portable CPU baseline. |
| `linux-x86_64` / `linux-aarch64` | source, CI | Prebuilt ships **split** `ggml-cpu` (~15 arch-specific libs dispatched via `GGML_BACKEND_DL`); our `NativeLoader` requires a single `ggml-cpu`. |
| `windows-x86_64` | source, CI | Same split-cpu problem + Windows prebuilt has **no MSVC import `.lib`**. |

Encoded in `core/build.gradle` (mode=prebuilt vs `-Pnative=source`) and
documented at length in `03-decisions.md` §12a.

### 3.4 Spring AI moves under your feet

We wrote the adapter against Spring AI 1.0.0-M2; at GA the artifact
graph reshuffled:

- `spring-ai-core` **does not exist at GA** — it split into
  `spring-ai-commons` → `spring-ai-model` → `spring-ai-client-chat`.
- `ChatOptionsBuilder.builder()` → `ChatOptions.builder()`.
- `Message.getContent()` → `getText()`.

Defence: `spring-ai-*` deps are `compileOnly` so the consumer pins the
version; our `spring-ai` module is a thin adapter that breaks loudly on
upgrade rather than dragging a pinned `spring-ai` into every consumer.

### 3.5 Tool-calling correctness lives in C++, not Java

The bridge is C++ (`llamabridge.cpp`, `extern "C"` ABI) because the
parts that get tool-calling right — llama.cpp's `common_chat_*` /
`common_sampler_*` — are a **C++ ABI** (mangled, STL args) that FFM
can't bind to directly. The `extern "C"` shim is the entire reason a
thin bridge beats jextract here.

Concretely: `common_chat_parser_params`' constructor only copies
`format` + `generation_prompt`; we needed an explicit
`pp.parser.load(cparams.parser)` call or the prompt leaked into content
and `tool_calls` came back empty. That's a one-line C++ fix; same logic
in Java would need to round-trip native struct memory.

### 3.6 Drop models that don't deliver

Started with Llama-3.2-1B/3B as defaults. Pivoted hard:

> "i want real token count streaming and also tool call we dont want any
> moidel which doesnt support tool call so change it."

Dropped llama-3.2-1b/3b (partial tool template), gemma-4-e4b (no tool
template), Phi-3 (loader couldn't read its tokenizer). Lineup is now
tool-callers only — `qwen2.5-1.5b` (default), `qwen2.5-3b`, `qwen3-4b`,
`phi-4-mini`. Non-tool models are rejected at load
(`MODEL_NOT_TOOL_CAPABLE`) by gating on
`common_chat_templates_get_caps()`.

Verified: qwen2.5-1.5b loads; TinyLlama is rejected with a clear error.

### 3.7 Confinement & threading footgun

`ChatEngine`'s engine-lifetime `Arena` is created on the async loader
thread but used from Spring MVC request threads. Currently safe (nothing
else touches it) but flagged in the session as a fragility — future
work should make the Arena confinement explicit or per-call.

---

## 4. What "done" means

The product target hasn't shifted across the build. Three consumption
modes, all on top of the same `core`:

```
                            mochallama-core
                            (FFM + bridge + dylibs)
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
       plain Java                Spring Boot            CLI
       MochallamaClient          starter +              npx @deemwario/
       directly                  /v1/chat/completions   mochallama chat
                                 + Spring AI adapter
```

A dev can:

```bash
# (a) drop into a plain Java app
implementation 'io.github.deemwario:mochallama-core:0.1.0'

# (b) drop into a Spring Boot app
implementation 'io.github.deemwario:mochallama-spring-boot-starter:0.1.0'
implementation 'io.github.deemwario:mochallama-spring-ai:0.1.0'   // optional

# (c) just chat from a terminal
npx @deemwario/mochallama chat
```

…on **macOS Intel / Apple Silicon, Linux x64 / arm64, Windows x64**, with
**no native install** beyond the JDK.

---

## 5. Where we stand (2026-06-03)

Detailed state lives in `inprogress.md` — this is the executive cut.

### Working, verified end-to-end

- Build: `task build` / `task native` green on Intel mac; CI matrix
  builds darwin-aarch64 + linux-x64 + windows-x64 with the smoke test
  gating each leg.
- Runtime: app boots in ~17s, `/actuator/health` → UP, real token
  usage from the bridge (not estimates), SSE streaming via FFM upcall
  callback, tool calling (`get_weather` proven end-to-end), full
  sampler param set, CLI works (`mochallama models` / `mochallama chat`).
- `task publish:local` — core + starter + spring-ai → `~/.m2`, jar
  bundles 19 native libs for darwin-x86_64.
- Docs: VitePress site live at
  https://deemwar-products.github.io/mochallama/.
- Branding consolidated: Maven group `io.github.deemwar-products`
  (Central-verified namespace), Java package `tools.deemwar.mochallama`,
  npm `@deemwario`, GitHub `deemwar-products/mochallama`.

### Blocked on credentials only

**1. Maven Central publish.** Namespace verified. Staging bundle
structure correct. `task publish:central` is still a stub (`exit 1`).
Needs: Central Portal account final claim, GPG signing key, env
`CENTRAL_PORTAL_USERNAME/TOKEN` + `SIGNING_KEY/PASSWORD`. No
engineering work — a human sit-down.

**2. npm publish.** `@deemwario` org needs publish rights. A mid-build
rename from `@deemwarhq` → `@deemwario` left some stale packages that
need cleanup. The interactive `npm login` browser auth (5× during one
session) was the last friction.

**3. `v0.1.0` GitHub Release has zero assets.** The original release.yml
run failed (~24h CI hang from §3.1, now fixed). Re-running the matrix
should produce attachable binaries; the "build in CI → download → publish
locally" flow can then run.

### Deferred (explicitly out of scope for v0.1.0)

- **GraalVM native-image** — FFM-in-native-image is GA only on
  GraalVM-25 + macOS-AArch64; our host is Intel.
- **Windows ARM64** — no upstream prebuilt, no CI runner.
- **GPU backends** (Metal / CUDA / Vulkan) — gated off in CMake.
  CPU-only is the v0.1.0 promise.
- **Removing legacy `Llama3.java` / `ChatBot.java`** — keeps until
  v0.1.x ships to at least one consumer.

---

## 6. The opinionated bits (non-goals, in his words)

These are deliberate, not lazy. They define what mochallama **isn't**.

- **No JNI.** *"no need jni use project paname or java ffi."*
- **No HTTP to Ollama.** We bind in-process. If you want HTTP-to-daemon,
  Spring AI's Ollama starter is right there.
- **No `llama.cpp` as a Maven dep.** *"no dont add as dependencies just
  copt and reuse part of it here."* Vendor it; we control the version
  cliff.
- **No non-tool-calling models.** *"we dont want any moidel which doesnt
  support tool call."* Enforced at load time.
- **No compile step for the consumer.** *"i want a simple binary dont
  want them to compile stuff which i hate."*
- **No `spring-ai-*` as a runtime dep.** `compileOnly` — the consumer
  pins the version.
- **No half-platforms.** *"we are shipping library not some school
  project."* Five platforms or it's not v0.1.0.

---

## 7. What's next

In rough order:

1. **Re-run `release.yml`** with the CI fixes from §3.1; attach the
   per-platform native + jlink artifacts to the v0.1.0 tag.
2. **Maven Central** — finish the Central Portal sit-down, push GPG
   key, fill in `task publish:central`, publish v0.1.0 to Central.
3. **npm publish** — clean up `@deemwarhq` stragglers, publish
   `@deemwario/mochallama` + per-platform `optionalDependencies`.
4. **One downstream consumer** — drop the starter into a real Spring
   Boot app (likely one of the deemwar-products siblings:
   `llama-cpu-benchmarks`, `pocket-llm`) and prove the contract.
5. **Then** remove `Llama3.java` / `ChatBot.java` legacy code, triage
   the 10 open dependabot PRs, and start on Windows ARM64 + GPU
   backends as v0.1.1 work.

---

## Appendix — Where to look next

- `inprogress.md` — live status; always more current than this doc.
- `docs/specs/00-overview.md` — what mochallama *is* (no journey).
- `docs/specs/03-decisions.md` — full design-decision journal,
  including the §12 / §12a prebuilt-binary pivot.
- `docs/specs/04-deferred.md` — explicit out-of-scope list.
- `docs/specs/05-release-and-publish.md` — the publish runbook
  (and what's still stub).
- `CLAUDE.md` — agent-facing build invariants and module map.
- `PUBLISHING.md`, `HOW-TO-PUBLISH-AND-TEST.md` — operator-facing
  publish steps.
- Sibling projects (NOT mochallama, same workspace, share the
  llama.cpp domain): `../llama-cpu-benchmarks`, `../pocket-llm`.
