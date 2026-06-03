# PUBLISHING — mochallama v0.1.x runbook

Tomorrow-morning steps to publish from local. Ordered by credentials needed.
Everything in **Tier 0** is already verified working tonight (2026-06-02) on
the Intel Mac; **Tier 1+** need your accounts (npm / Maven Central).

```bash
# Always set JDK 22 first (every tier assumes this):
export JAVA_HOME=/Users/muthuishere/Library/Java/JavaVirtualMachines/temurin-22.0.1/Contents/Home
```

> Native libs for darwin-x86_64 are already cached/staged, so all builds below
> skip the ~minutes-long CMake/llama.cpp compile. (`-PnativeJobs=N` tunes
> parallelism if you ever rebuild them.)

---

## Tier 0 — Local, no credentials (VERIFIED tonight ✅)

### Run it / prove it responds
```bash
task app:run     # demo app + web UI at http://localhost:8080
# (an app-0.1.0.jar is already built; or run it directly:)
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -jar app/build/libs/app-0.1.0.jar
```
First boot loads qwen2.5-1.5b (already downloaded to `~/.chatbot_models`) in ~17s,
then `health` flips to `UP`. Smoke-test:
```bash
curl -s localhost:8080/v1/models
curl -sS -X POST localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m","messages":[{"role":"user","content":"hi"}],"max_tokens":32}'
# streaming: add "stream":true  | tool calls: add a "tools":[...] array (both verified)
```

### Publish the library to your local Maven (~/.m2) — Mac
```bash
task publish:local
# -> ~/.m2/repository/tools/deemwar/{mochallama-core, -spring-boot-starter, -spring-ai}/0.1.0/
#    core jar bundles the 19 darwin-x86_64 dylibs. Any local Java/Spring app can consume it now.
```

### Build the npm tarballs (Mac platform) — verified
```bash
task cli:npm:pack
# -> cli/npm/deemwario-mochallama-0.1.0.tgz                       (launcher)
#    cli/npm/platforms/darwin-x64/deemwario-mochallama-darwin-x64-0.1.0.tgz  (jlink image)
```

---

## Tier 1 — Pull the CI-built natives (linux / darwin-aarch64 / windows)

A complete, multi-platform `core` jar = your local darwin-x86_64 natives + the
CI-built ones. The fixed `release.yml` builds + uploads them (it does NOT push a
GitHub Release unless you tag — see below).

```bash
# 1. (once) trigger a release build if there isn't a recent successful run:
gh workflow run release.yml --ref main -R deemwar-products/mochallama
gh run watch -R deemwar-products/mochallama   # ~15-25m linux, ~95m arm64 (cached on repeat)

# 2. download + stage every platform's natives next to the local mac libs:
task release:download        # auto-picks the latest successful release run
#   (or: task release:download RUN_ID=<id>)

# 3. now publish a COMPLETE multi-platform jar locally:
task publish:local           # core jar now bundles darwin-x86_64 + -aarch64 + linux (+ win if green)
```

---

## Tier 2 — npm publish  (@deemwario — all 5 platforms + launcher)

Publishes everything so `npx @deemwario/mochallama` works on every platform. Each
platform ships a self-contained jlink **JDK 22** image (~31 MB) — no Java needed by
the user; only the model downloads on first run.

```bash
# auth: put an npm AUTOMATION token (bypasses the org 2FA OTP) in .env:
#   NPM_DEEMWAR_IO_PUBLISH_TOKEN=npm_xxx        # else `npm login` (OTP per package)
# prereq: a SUCCESSFUL release.yml run on current code (gives @deemwario CI tarballs):
gh workflow run release.yml --ref main && gh run watch -R deemwar-products/mochallama

task cli:npm:publish         # 5 platform pkgs + launcher
```
Sourcing: `darwin-x64` is built+packed on this Mac (no CI Intel runner); `linux-x64`,
`linux-arm64`, `darwin-arm64`, `win32-x64` come from the release run's `npm-*` artifacts.
Platform packages publish first, launcher last (its `optionalDependencies` resolve against them).
`task cli:npm:publish:darwin-x64` does just the host + launcher as a quick path.

---

## Tier 3 — Maven Central (Central Portal)  — gradle plumbing WIRED ✅

Group is `io.github.deemwario`; `signing` + a Central-Portal staging/bundle
flow are wired (`build.gradle` + `task publish:central`). Signing only activates
when `SIGNING_KEY` is set, so credential-free builds never break.

**One-time human setup (done once):**
1. **Central Portal account** at https://central.sonatype.com — register namespace
   `io.github.deemwario` (GitHub-verified: create a public repo named after
   the verification code under the org).
2. **Token**: Central Portal → Account → Generate User Token → `username` + `password`.
3. **GPG key** (RSA 4096 recommended; ed25519 may work):
   ```bash
   gpg --full-generate-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
   gpg --export-secret-keys --armor <FINGERPRINT> > .seckey   # gitignored
   ```

**Each publish:**
```bash
task release:download                       # stage ALL 5 platforms' natives (else core ships only your platform)
export SIGNING_KEY="$(cat .seckey)"         # armored secret key
export SIGNING_PASSWORD='<key passphrase>'  # empty string if the key has none
export CENTRAL_USERNAME='<portal token user>'
export CENTRAL_PASSWORD='<portal token password>'

task publish:central        # builds the SIGNED bundle, uploads to the Portal (USER_MANAGED)
# -> then review + click Publish at https://central.sonatype.com/publishing/deployments
```
`task publish:central:bundle` builds just the signed `build/central-bundle.zip`
(inspect before uploading). Appears on search.maven.org ~30 min after you click Publish.

Notes: version is `0.1.0` (bump in root `build.gradle` to re-publish). The `core`
jar's classifier natives only cover platforms staged at publish time — always run
`task release:download` first. Consumers without Central can still use `~/.m2` (Tier 0/1).

---

## Cut the official GitHub Release (optional — this is the only "push")

The release workflow only pushes a GitHub Release for a real `v*` tag:
```bash
git tag v0.1.0 && git push origin v0.1.0   # triggers release.yml -> attaches all assets
```
A manual `workflow_dispatch` (Tier 1) just builds + uploads downloadable artifacts.

---

## Status snapshot (2026-06-02 night)

| Step | State |
|---|---|
| App runs + responds (non-stream / stream / tool) | ✅ verified |
| Web UI, CLI `models` | ✅ verified |
| `task publish:local` (mac lib) | ✅ verified |
| `task cli:npm:pack` (mac tarballs) | ✅ verified |
| CI multi-platform build (parallelism fix) | 🔄 validating on branch `ci/fix-tier1-workflows` |
| npm publish | ⏸ needs `npm login` |
| Maven Central | ⏸ needs Portal account + GPG + gradle wiring |
