# mochallama — Design Decisions

A running journal of choices made while building the bridge + service.
Every entry: what we picked, what we rejected, why.

## 1. Why a separate C bridge, not jextract over `llama.h`

**Decision:** Write a tiny C wrapper (`llamabridge.h`, 5 functions, JSON in /
JSON out) and have Java bind only to that.

**Rejected:** Generating Panama bindings directly from `llama.h` via
`jextract`.

**Why:**

- `llama.h` exposes ~150 symbols and a dozen-plus structs with field layouts
  that change between releases. Pinning the Java side to that surface ties
  us to an upstream version forever.
- A lot of the API is helper-shaped (sampler chains, batches, tokeniser
  details). Doing that orchestration from Java means every call site needs
  to allocate native structs, walk pointers, and remember to free. That's
  fine for a demo but tedious for a service.
- Chat templating in particular needs probe-then-allocate calls into
  `llama_chat_apply_template` and a fallback path. Easier to keep that in
  C, where it's a five-liner, than to script it through MethodHandles.
- A stable 5-function ABI lets us swap the implementation (rocm, metal,
  even llama-server-over-HTTP) without touching Java.

**Cost:** We hand-maintain ~700 LOC of C. Acceptable.

## 2. Why Panama FFM, not JNI

**Decision:** JDK 22 FFM (`Linker`, `MethodHandle`, `Arena`, `MemorySegment`).

**Rejected:** Hand-rolled JNI with a custom `.so`/`.dylib` of glue code.

**Why:**

- FFM is GA in JDK 22 — no `--enable-preview`. Stable surface from here on.
- No second native artefact to build and ship — we already have one
  (`libllamabridge.dylib`), and FFM lets us bind to it from pure Java.
- Memory ownership is explicit via `Arena`. No `JNIEnv*`, no `DeleteLocalRef`,
  no JVM-side weirdness.
- Upcalls (the event callback) are a one-liner via `Linker.upcallStub`
  when we wire them up — currently unused but cheap to add.

**Cost:** Requires `--enable-native-access=ALL-UNNAMED`. Fine for a server
process; would be a problem for an embedded library scenario, which we
don't have.

## 3. Why vendor `llama.cpp` in-tree

**Decision:** `src/main/native/llama.cpp/` is a full copy of the upstream
repo, included as `add_subdirectory(... EXCLUDE_FROM_ALL)` from our
`CMakeLists.txt`.

**Rejected:**

- Pre-built bridge dylib shipped in a Maven artefact.
- `FetchContent` / git submodule from CMake.

**Why:**

- Reproducible builds without network. Anyone who clones the repo can
  build, including in CI environments where outbound HTTPS to GitHub is
  flaky.
- We pin a specific `llama.cpp` revision (currently tag `b9371`) and can
  audit the diff before bumping. Critical because the upstream chat
  template + sampler APIs have churned several times.
- Distributing a pre-built dylib via Maven is on the roadmap, but it's a
  matrix problem (OS × arch × backend) — see `04-deferred.md`. For now,
  vendoring is the lowest-friction path.

**Cost:** The repo is large (~hundred-MB submodule). The git history of
the upstream isn't preserved in our tree.

## 4. Why CMake from `exec`, not a Gradle native plugin

**Decision:** A custom Gradle task `buildNative` that shells out to `cmake`
configure + build, then stages the dylibs into `resources/native/...`.

**Rejected:**

- `org.gradle.language.cpp` / `cpp-application` plugin.
- `com.google.osdetector` + a Maven-published native module.

**Why:**

- The vendored llama.cpp already has a working `CMakeLists.txt` with all
  the right `GGML_*` knobs. Re-expressing that in a Gradle DSL means
  re-implementing the upstream's build matrix from scratch and keeping it
  in sync as they add backends.
- `cmake` driven from Gradle exec is universally understood — anyone with
  CMake installed can reproduce the build, including outside Gradle.
- The custom stage step (copying the regular dylib bytes under every name
  in the symlink chain) is awkward to express in a stock plugin anyway.

**Cost:** `cmake` must be on `PATH`. No incremental rebuilds inside the
native subproject finer-grained than CMake's own.

## 5. Why JDK 22

**Decision:** `JavaLanguageVersion.of(22)` in `build.gradle`.

**Why:** FFM (`java.lang.foreign.*`) is GA in 22, exits the incubator and
removes the `--enable-preview` requirement. 21 still has FFM but as a
preview; 24+ would also work but would force GraalVM toolchain choices we
don't want yet (see deferred list).

**Cost:** JDK 22 is not LTS. We will move to 25 (next LTS) once it's out
and GraalVM-for-25 macOS x64 support is stable.

## 6. Why Llama-3.2-1B-Instruct-Q4_0 as the default

**Decision:** `llamacpp.model.url` points to
`mukel/Llama-3.2-1B-Instruct-GGUF/.../Q4_0.gguf`.

**Why:**

- Tiny (~800 MB Q4_0) — fits in memory on every dev machine, downloads in
  minutes on a normal connection.
- Llama-3.2 ships a chat template that the vendored llama.cpp recognises,
  so `llama_chat_apply_template` does the right thing without us having to
  hard-code role tokens. Our bridge has a chatml fallback, but the
  native-template path is the well-tested one.
- 1B is enough to demo "is the pipe alive end-to-end" and "is the chat
  format right". Quality is intentionally not the point — see deferred
  multi-model presets.

**Trade-off:** 1B is not capable enough for tool calling or any non-trivial
agentic workload. That's a follow-up.

## 7. Streaming — deferred

**Decision:** `POST /v1/chat/completions` with `"stream": true` returns
`501 Not Implemented`. Single response only.

**Why deferred:**

- The bridge today returns the full assistant text from one `llb_chat_infer`
  call. Streaming requires either an upcall on every token (extra crossing
  per token, allocation pressure) or restructuring the bridge to expose
  prepare / step-once / cleanup.
- The OpenAI SSE format is also non-trivial: heartbeat handling,
  client-disconnect detection through Spring's response writer.
- We want to nail the single-shot path first, including chat-template edge
  cases, before adding streaming complexity on top.

**When to revisit:** After multi-model support lands and we have at least
one model where the latency of full responses is user-visible (>3s).

## 8. Tool calling — deferred

**Decision:** No `tools` / `tool_choice` support. Request fields are not
even modelled.

**Why deferred:**

- The default model (Llama-3.2-1B) is not reliably capable of producing
  tool-call JSON. Wiring up the plumbing without a model that can use it is
  testing-by-mock.
- Llama-3.x tool calling uses model-specific special tokens / formats; we'd
  need at least 7B+ (Llama-3.1-8B-Instruct or similar) to validate it.
- Spring AI ChatClient already handles tool dispatch on top of an
  OpenAI-compatible endpoint, so once we ship a capable model the work is
  in the bridge / template handling, not in the controller.

## 9. GraalVM native-image — deferred

**Decision:** The `org.graalvm.buildtools.native` plugin is on the
classpath but not exercised. No `nativeCompile` task is run.

**Why deferred:**

- FFM in GraalVM native-image is officially stable only in
  GraalVM-for-JDK-25, and primarily on macOS AArch64.
- Our current target is macOS Intel `x86_64`. The combination
  (Intel + FFM + native-image) is not a paved road.
- The HotSpot startup time + memory footprint is dominated by the model
  itself (hundreds of MB), so native-image savings are marginal.

**When to revisit:** Once we ship a Linux `x86_64` binary (in CI) and have
a use case that cares about cold-start (CLI mode, serverless).

## 10. Cross-platform builds — deferred to GitHub Actions matrix

**Decision:** Today, the build only targets macOS Intel `x86_64`. No
attempts to cross-compile for Linux or Windows from a developer machine.

**Why deferred:**

- No local Docker / VM infra for cross-builds.
- Each platform needs its own toolchain matrix (Linux: gcc/clang + BLAS
  variant choice; Windows: MSVC + DirectML or CPU-only).
- GitHub Actions has runners for each — the right place to do this is in
  CI, where we can publish a per-OS, per-arch resources jar.

**When to revisit:** Right after the first end-to-end working release on
mac. Linux is highest priority.

## 11. Package + project rename — pending

**Decision:** Rename `apps.llamavector` → something like `dev.mochallama`
(or similar) and `settings.gradle`'s `rootProject.name` and the directory
in one coordinated commit, separately from the design-journal work.

**Why now-not-yet:** The rename touches every file (package declarations,
imports, gradle config, resources path is fine since it's
`/native/<platform>/`). Folding it into the design work would make the
diff unreadable. Tracked as a follow-up.
