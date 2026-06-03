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
# -> cli/npm/deemwarhq-mochallama-0.1.0.tgz                       (launcher)
#    cli/npm/platforms/darwin-x64/deemwarhq-mochallama-darwin-x64-0.1.0.tgz  (jlink image)
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

## Tier 2 — npm publish  ⚠️ needs `npm login` (@deemwarhq)

```bash
npm login                    # HUMAN: log in with @deemwarhq org publish rights
task cli:npm:publish         # publishes the darwin-x64 platform pkg + the main launcher (public)
```
After this, `npx @deemwarhq/mochallama chat` works on macOS Intel. The other
platforms' packages (`linux-x64`, `darwin-arm64`, `win32-x64`) are published
from their CI `npm-*` tarballs — download them via `gh run download <id> -n npm-linux-x64`
and `npm publish <tgz> --access public` each, then they resolve as
`optionalDependencies` of the launcher.

---

## Tier 3 — Maven Central  ⚠️ needs Central Portal account + GPG (one-time)

This is the only part not auto-wired (so a credential-free build never breaks).
One-time human setup:

1. **Central Portal account** at https://central.sonatype.com — claim namespace
   `io.github.deemwar-products` (GitHub-verified: it asks you to create a repo
   named after a verification code under the org).
2. **GPG signing key**: `gpg --gen-key`; publish it:
   `gpg --keyserver keys.openpgp.org --send-keys <KEYID>`.
3. **Switch the Maven group** `tools.deemwar` → `io.github.deemwar-products`
   in the three `*/build.gradle` publication blocks (or via a `-Pgroup=` toggle
   once wired).
4. **Wire the gradle plumbing** (not yet added — see `docs/specs/05-release-and-publish.md`):
   add the `signing` plugin + `central-publishing` (or `nmcp`) plugin to
   core/starter/spring-ai, guarded so it only activates when these env vars exist:
   ```bash
   export CENTRAL_PORTAL_USERNAME=...   CENTRAL_PORTAL_TOKEN=...
   export SIGNING_KEY="$(gpg --export-secret-keys --armor <KEYID>)"  SIGNING_PASSWORD=...
   ```
5. Then: `task release:download` (Tier 1, for the full jar) → `task publish:central`.

Until Tier 3 is wired, consumers use `~/.m2` (Tier 0/1) or JitPack off the tag.

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
