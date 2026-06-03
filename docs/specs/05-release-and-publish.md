# mochallama — Release & Publish Spec (v0.1.x)

Status: **drafted 2026-06-02 (overnight autonomous session)**. This is the
authoritative plan for cutting a public v0.1.x release. It supersedes the
release notes scattered across `inprogress.md` and the stale "deferred" entries
in `03-decisions.md` / `04-deferred.md`.

## Goal (what "done" means)

One published, runnable library so any developer can, with **no native install
dance**:

1. **Plain Java app** — add `tools.deemwar:mochallama-core`, call `MochallamaClient`.
2. **Spring Boot app** — add `mochallama-spring-boot-starter` → an
   OpenAI-compatible `/v1/chat/completions` (+ SSE) and `/v1/models` autoconfigure.
3. **CLI / instant chat** — `npx @deemwario/mochallama chat`.

Plus a demo app + web UI and live docs. The product is the **published
library set (core + starter + spring-ai + cli)** — the `app` module is a demo,
not the deliverable. (Strategy: this is the Spring-native control plane over
llama.cpp; the moat is the OpenAI shape + GGUF lifecycle + Spring DI, *not*
inference speed — see company-research `14-cluster-local-first-ai.md`,
`21-prioritization-2026-05.md`. The chronic failure mode this release closes is
"distribution configured but never pushed" — research `20` §A3.)

## Hard invariants (do not regress)

- **Tool-calling-only.** Non-tool-capable models are rejected at load
  (`MODEL_NOT_TOOL_CAPABLE`); HF-by-id resolution must keep refusing them.
- **`starter` has no Spring-AI dependency** (version-resilient). Spring AI lives
  only in the separate optional `spring-ai` module (`compileOnly`). Honors
  research non-goal NG6 (don't couple products to the Spring AI framework).
- **MIT** everywhere. **JDK 22** (FFM GA). Native libs bundled in the `core` jar.

## Platform sourcing strategy (decided 2026-06-02)

Cross-platform natives cannot all be built on one machine. Split by where each
is cheapest/most reliable:

| Native | Built where | Mode | Why |
|---|---|---|---|
| `darwin-x86_64` | **local** (dev Intel Mac) | prebuilt | single ggml-cpu, no Metal dep — loads; ~11s |
| `darwin-aarch64` (M1) | CI (`macos-14`) | **source** | arm64 prebuilt libggml hard-links Metal (not bundled) |
| `linux-x86_64` | CI (`ubuntu-latest`) | **source** | linux prebuilt ships split ggml-cpu (no single) |
| `windows-x86_64` | CI (`windows-latest`) | **source** | split ggml-cpu + no import `.lib` in prebuilt |

Every leg runs `NativeLoadSmokeTest` (loads the full native closure + calls
`llb_version`, no model) as a CI runtime oracle — it caught a distinct
load failure on each of arm64 (Metal), windows (corrupt-DLL staging bug), that a
build-only check missed. Windows stays `experimental` until its smoke passes.

A complete multi-platform `core` jar = local `darwin-x86_64` natives + the CI
natives downloaded and staged together before `:core:publish`.

## Root-cause fix landed this session

The Linux fast-fail (~2m, "runner received a shutdown signal" at 83%) and the
macos-13 24h hang had **one cause**: unbounded `cmake --build --parallel` OOM'd
the runner while compiling llama.cpp's template-heavy sources.

**Resolved by not compiling llama.cpp at all** (see `03-decisions.md` §12):
`buildNative` defaults to **mode=prebuilt** — download llama.cpp's official
release libs for the host platform and compile only the 1-file bridge (~11s
local; darwin-aarch64 CI leg ~60s vs the old ~95m). `-Pnative=source` keeps the
from-source build (with the 2-job parallelism cap, `-PnativeJobs=N`) as a
fallback. Closure gotcha: prebuilt `libllama` links `@rpath/libggml-rpc`, so
`ggml-rpc` must be bundled (the source build never emitted it).

## CI workflows (post-fix)

- **`build.yml`** — light **ubuntu-only compile check** (`compileJava -x
  buildNative`) on push/PR. No more 4-platform matrix jamming macOS runners.
- **`release.yml`** — tag/dispatch matrix (linux, darwin-aarch64, windows-exp).
  - `timeout-minutes: 150` so no leg can hang the release again.
  - Caches staged natives keyed on llama.cpp tag + bridge sources (repeat runs
    skip the ~95m compile).
  - Assemble job runs `if: !cancelled()` (builds from whatever legs succeeded)
    and uploads a downloadable `release-assets` bundle.
  - **Pushes a GitHub Release ONLY on a real `v*` tag.** A manual dispatch just
    builds + uploads — never pushes. (Honors "no push from CI; publish from local".)

## Acceptance criteria

Runtime (VERIFIED locally 2026-06-02 on the Intel Mac, qwen2.5-1.5b):
- [x] `/v1/chat/completions` non-stream returns assistant text + real token usage.
- [x] `stream:true` streams `chat.completion.chunk` SSE tokens.
- [x] Tool call returns `tool_calls` + `finish_reason:"tool_calls"`.
- [x] `/v1/models` lists the configured model; web UI at `/` serves (HTTP 200).
- [x] CLI `mochallama models` lists the tool-only lineup.
- [x] `task publish:local` → core/starter/spring-ai in `~/.m2`; core jar bundles
      19 `darwin-x86_64` dylibs.
- [x] `task cli:npm:pack` → launcher + darwin-x64 platform tarball.

Release (pending):
- [ ] `release.yml` produces linux + darwin-aarch64 (+ windows best-effort)
      native artifacts on a clean run. ← validating this session.
- [ ] Complete multi-platform `core` jar assembled locally and published.
- [ ] Maven Central: namespace + GPG + creds (human-gated — see PUBLISHING.md).
- [ ] npm: `@deemwario` org login (human-gated — see PUBLISHING.md).

## Native packaging — OS-specific classifier jars (LANDED 2026-06-02)

Not a fat jar. The `mochallama-core` jar is **Java-only (~40 KB)**; each platform's
native libs ship as a **classifier artifact** `mochallama-core:<ver>:natives-<os>-<arch>`
(~6–12 MB), the LWJGL / JavaCPP / sqlite-jdbc pattern. Rationale: each consumer
pulls Java + only their platform (not ~40 MB of all five); a new platform is just
another classifier (no fat rebuild); **custom builders** run `:core:buildNative`
locally and publish only their own platform's native jar.

- `core/build.gradle`: main `jar` excludes `native/**`; one `Jar` task per staged
  platform → classifier artifact with entries under `native/<platform>/` (so
  `NativeLoader`'s `classpath:/native/<platform>/` lookup resolves). Host platform
  jar exposed via a `hostNatives` configuration the demo app consumes.
- `NativeLoader` is unchanged in behaviour — it loads `/native/<platform>/` from
  whichever jar provides it.
- Verified: `mochallama-core-0.1.0.jar` = 40 KB Java-only;
  `…-natives-darwin-x86_64.jar` = 12 MB; demo app loads the classifier jar and responds.

**Consumer usage:**
```gradle
implementation 'io.github.deemwario:mochallama-core:0.1.0'
runtimeOnly    'io.github.deemwario:mochallama-core:0.1.0:natives-linux-x86_64'  // your platform
```
(A future `-platform` aggregator POM can auto-select via `com.google.osdetector`.)

- npm/CLI is already split the same way (per-platform `optionalDependencies`),
  now including `linux-arm64`.
- DONE: the redundant 3rd versioned lib name (`lib*.0.0.9371.dylib`) is no longer
  staged (loader needs only load-name + SONAME) — native jars are ~⅓ smaller
  (darwin-x86_64: 12 MB → 8.3 MB).

### Consuming — three ways

1. **Lean, explicit (recommended):** name your platform's classifier.
   ```gradle
   implementation 'io.github.deemwario:mochallama-core:0.1.0'
   runtimeOnly    'io.github.deemwario:mochallama-core:0.1.0:natives-linux-x86_64'
   ```
2. **Lean, auto-select** (only your platform, via OS detection):
   ```gradle
   plugins { id 'com.google.osdetector' version '1.7.3' }
   def mochaNative = [
     'osx-x86_64':'natives-darwin-x86_64', 'osx-aarch_64':'natives-darwin-aarch64',
     'linux-x86_64':'natives-linux-x86_64', 'linux-aarch_64':'natives-linux-aarch64',
     'windows-x86_64':'natives-windows-x86_64',
   ][osdetector.classifier]
   dependencies {
     implementation 'io.github.deemwario:mochallama-core:0.1.0'
     runtimeOnly "io.github.deemwario:mochallama-core:0.1.0:${mochaNative}"
   }
   ```
   (Maven: the same via `os-maven-plugin`'s `${os.detected.classifier}` + a profile map.)
   The osdetector classifier (`osx-`, `aarch_64`) differs from ours (`darwin-`,
   `aarch64`), hence the small map.
3. **Zero-config, all platforms:** depend on the aggregator
   `io.github.deemwario:mochallama-core-platform:0.1.0` — pulls Java +
   every platform's native jar (bigger; works anywhere without naming a classifier).

## Out of scope for v0.1.x

- Windows native (v0.1.1 — MSVC CMake port).
- GPU backends (Metal/CUDA/Vulkan are build-flag flips, not this cut).
- GraalVM native-image (blocked: GraalVM-25 + macOS-AArch64; host is Intel).
- Maven Central publishing gradle plumbing is documented but NOT auto-wired —
  it activates only once signing creds exist (see PUBLISHING.md), so a
  credential-free build never breaks.
