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

## 2. PUBLISH — from local

### 2a. Merge the branch first
```bash
gh pr view 11 -R deemwar-products/mochallama        # review the green checks
gh pr merge 11 -R deemwar-products/mochallama --squash
git checkout main && git pull
```

### 2b. Get all-platform natives, build ONE complete jar
The release workflow builds linux + darwin-aarch64 (+ windows best-effort) natives.
Your darwin-x86_64 is built locally. Combine them:
```bash
# trigger a release build if there isn't a recent green one (fast now — prebuilt):
gh workflow run release.yml --ref main -R deemwar-products/mochallama
gh run watch -R deemwar-products/mochallama

task release:download        # pulls CI natives, stages them next to your local mac build
task publish:local           # core jar now bundles darwin-x86_64 + darwin-aarch64 + linux  -> ~/.m2
```
→ This single jar runs on **Intel Mac, M1 Mac, and Linux**.

### 2c. npm  (needs `npm login`)
```bash
npm login                    # @deemwarhq org
task cli:npm:publish         # publishes darwin-x64 platform pkg + launcher (public)
# other platforms: gh run download <id> -n npm-<plat> then `npm publish <tgz> --access public`
```
→ `npx @deemwarhq/mochallama chat` works.

### 2d. Maven Central  (one-time setup — see PUBLISHING.md §Tier 3)
Needs a Central Portal account (`io.github.deemwar-products`, GitHub-verified) +
GPG key + the gradle signing plumbing. Until then, consumers use `~/.m2` (2b) or JitPack off the tag.

### 2e. Cut the official GitHub Release (the only "push")
```bash
git tag v0.1.0 && git push origin v0.1.0   # release.yml attaches all platform assets
```

---

## How consumers use it (OS-specific classifier jars)

The library now ships Java-only core + per-platform native jars (not a 40MB fat jar):
```gradle
dependencies {
  implementation 'io.github.deemwar-products:mochallama-core:0.1.0'        // Java, ~40KB
  runtimeOnly    'io.github.deemwar-products:mochallama-core:0.1.0:natives-linux-x86_64'  // their platform
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
| CI prebuilt build (linux + darwin-aarch64) | check PR #11 / `gh run list` — was building when I left |
| `build.yml` light PR check | check PR #11 |
| npm publish | ⏸ needs `npm login` |
| Maven Central | ⏸ needs Portal account + GPG (PUBLISHING.md §Tier 3) |

Full detail: `PUBLISHING.md` (channels) · `docs/specs/05-release-and-publish.md` (spec) ·
huddle note `~/.config/muthuishere-agent-skills/mochallama/ci-fix-tier1-workflows/huddle/2026-06-02.md`.
