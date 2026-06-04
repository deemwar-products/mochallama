# mochallama — How to Test & Publish (morning runbook)

Good morning. Everything below is verified working on your Intel Mac as of
2026-06-02 night. Native libs are now **downloaded prebuilt** from llama.cpp
releases — we no longer compile llama.cpp (the 95-min/OOM pain is gone; only our
1-file bridge compiles, ~11s). Runs on **Intel and M1** from one published jar.

```bash
cd ~/muthu/gitworkspace/local-llama-workspace/mochallama
export JAVA_HOME=/Users/muthuishere/Library/Java/JavaVirtualMachines/temurin-22.0.1/Contents/Home
```

What changed overnight (branch `ci/fix-tier1-workflows`, PR #11):
- Native build → **prebuilt-download** (no llama.cpp compile). `-Pnative=source` still available as fallback.
- Fixed the CI OOM/hang (root cause: unbounded parallel compile); trimmed `build.yml`.
- Added `task release:download`, the spec (`docs/specs/05-release-and-publish.md`), and this runbook.
- Verified locally: app responds (non-stream / streaming / tool calls), web UI, CLI, `publish:local`, `npm pack`.

---

## 1. TEST — prove it runs and responds (2 min)

```bash
task app:run          # boots, loads qwen2.5-1.5b (~17s), web UI at http://localhost:8080
```
In another terminal:
```bash
# health (wait for "UP")
curl -s localhost:8080/actuator/health | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])'

# non-streaming
curl -sS -X POST localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m","messages":[{"role":"user","content":"hi in 5 words"}],"max_tokens":32}'

# streaming
curl -sN -X POST localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m","messages":[{"role":"user","content":"count to 5"}],"stream":true,"max_tokens":32}'

# tool call (returns finish_reason:"tool_calls")
curl -sS -X POST localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m","messages":[{"role":"user","content":"weather in Paris?"}],"tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],"max_tokens":48}'
```
Also: open http://localhost:8080 (web chat UI), and `./gradlew :cli:run --args="models"`.

---

## 2. PUBLISH — CI-driven, nothing published from local

Publishing is a **two-tier** pipeline. Native compilation and the public publish are
decoupled, and **no package is ever published from a laptop** (no `npm login`, no 2FA,
no local Maven push).

```
Tier 0  natives.yml     (rare)  build llama.cpp+bridge per platform -> release `natives-b9371`
Tier 0' cli:stage-...  (local)  build the Intel jlink image, upload -> release `cli-darwin-x64`
Tier 2  release.yml    (v* tag) download both -> Maven Central + all 5 npm + launcher, via OIDC
```

### 2a. (only if cli/ or core/ code changed) re-stage the Intel image
There is no Intel-mac CI runner, so the darwin-x64 jlink image is built **once on a Mac**
and parked in a durable GitHub release. Version-bump-only commits do **not** need this.
```bash
task cli:stage-darwin-x64    # build + load-smoke the Intel image, upload to release `cli-darwin-x64`
```

### 2b. Bump the version + tag
```bash
# bump build.gradle `version` and all cli/npm*/package.json (+ launcher optionalDependencies)
git commit -am "release: vX.Y.Z" && git tag vX.Y.Z && git push origin main vX.Y.Z
```

### 2c. release.yml does the rest (no manual step)
On the `v*` tag it:
- **Maven Central** — one Linux runner downloads all 5 native zips, builds the classifier
  jars + `-platform` aggregator, signs, and uploads to Central (`publishingType=AUTOMATIC`).
- **npm** — builds 4 platform jlink images on their own runners, downloads the staged
  darwin-x64 image, then `publish-npm` publishes **all 5 platforms + the launcher (last)**
  via **OIDC trusted publishing** — no token. Idempotent: already-published versions are skipped.
- **GitHub Release** — attaches the npm tarballs, native zips, and the app fat jar.

→ `npx @deemwario/mochallama chat` and the Maven coordinates below both work afterward.

> One-time per package: enable **Trusted Publisher** on npmjs.com (Settings → Trusted
> Publisher → GitHub Actions → repo + `release.yml`) for each `@deemwario/mochallama*`
> package and the launcher. Required before its first OIDC publish.

---

## How consumers use it (OS-specific classifier jars)

The library now ships Java-only core + per-platform native jars (not a 40MB fat jar):
```groovy
dependencies {
  implementation 'io.github.deemwario:mochallama-core:0.1.2'        // Java, ~40KB
  runtimeOnly    'io.github.deemwario:mochallama-core:0.1.2:natives-linux-x86_64'  // their platform
}
```
Platforms shipped: `natives-darwin-x86_64`, `natives-darwin-aarch64`,
`natives-linux-x86_64`, `natives-linux-aarch64` (ARM/Graviton), `natives-windows-x86_64`.

## Custom build (someone builds their own platform's native)
```bash
git clone https://github.com/deemwar-products/mochallama && cd mochallama
export JAVA_HOME=<jdk-22>
./gradlew :core:buildNative            # builds + stages THEIR host platform's natives
./gradlew :core:publishToMavenLocal    # -> ~/.m2: Java core + natives-<their-platform>.jar
# their app then depends on mochallama-core + natives-<their-platform> from mavenLocal
```
`-Pnative=source` forces a from-source build; default downloads prebuilt where clean (macOS).

## Platform coverage (honest)
- **Java: JDK 22+ required** (Panama FFM); needs `--enable-native-access=ALL-UNNAMED`.
- **OS/arch:** macOS Intel + Apple Silicon, Linux x86-64 + **ARM64**, Windows x86-64.
- Not yet: Windows ARM64, 32-bit, other OSes. Unsupported platforms fail fast with a clear message.

## Status when I left it (check the live state)

| Item | State |
|---|---|
| Prebuilt native build (no llama.cpp compile) | ✅ implemented + verified on Intel (11s) |
| App responds: non-stream / stream / tool | ✅ verified |
| Web UI, CLI `models` | ✅ verified |
| `task publish:local`, `task cli:npm:pack` | ✅ verified |
| CI multi-platform build + **load smoke** (linux x64 + ARM64, macOS arm64, windows x64) | ✅ all green (run 26862489610) |
| OS-specific classifier jars (Java core + per-platform native jars) | ✅ verified (app responds) |
| `build.yml` light PR check | ✅ pass |
| npm publish (OIDC, all 5 + launcher) | ✅ CI via `release.yml`; darwin-x64 from staged image |
| Maven Central (`io.github.deemwario`, AUTOMATIC) | ✅ CI via `release.yml` (0.1.4 live) |

Full detail: `PUBLISHING.md` (channels) · `docs/specs/05-release-and-publish.md` (spec) ·
huddle note `~/.config/muthuishere-agent-skills/mochallama/ci-fix-tier1-workflows/huddle/2026-06-02.md`.
