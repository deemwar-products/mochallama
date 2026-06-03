# Example Verification Report — published 0.1.1

Date: 2026-06-03. Verifies the **published** mochallama artifacts work for real
consumers, pulling **only from Maven Central / npm** (no local build). Examples
live in `examples/` (spec: `06-examples.md`).

## Maven Central library — `io.github.deemwario:*:0.1.1` — ✅ ALL PASS (built + RAN)

Each example resolved `0.1.1` from Maven Central (`--refresh-dependencies`, no
`mavenLocal()`) **and was run end-to-end** against the cached qwen2.5-1.5b model.

| Example | Artifacts (from Central) | Build | Run — real LLM response |
|---|---|---|---|
| `examples/java-plain` | `mochallama-core` + `mochallama-core-platform` | ✅ | ✅ **"Hello, Mochallama!"** (ChatEngine, no Spring) |
| `examples/spring-boot` | `mochallama-spring-boot-starter` + `-core-platform` | ✅ | ✅ **"Spring Boot works."** (`POST :8091/v1/chat/completions`) |
| `examples/spring-ai` | `mochallama-spring-ai` + starter + `-core-platform` + spring-ai 1.0.8 | ✅ | ✅ **"Yes, Spring AI works."** (`POST :8092/ask`, ChatClient) |

→ The published library is consumable + functional for **plain Java, Spring Boot,
and Spring AI** consumers, with natives delivered via the `-core-platform`
aggregator (right platform loaded at runtime).

## npm CLI — `@deemwario/mochallama` — ❌ 0.1.1 BROKEN → FIXED (needs 0.1.2 republish)

`npx @deemwario/mochallama@0.1.1` failed at runtime:
```
java.lang.IllegalStateException: no mochallama native binaries bundled for
darwin-x86_64 — this build supports: (none)
```

**Root cause:** the classifier split made `mochallama-core.jar` Java-only, but
`cli/build.gradle` only had `implementation project(':core')` — so the jlink
image bundled the Java core jar with **no natives** and no native classifier jar.
(The build-only agent checks didn't catch it — a *run* would have.)

**Fix (committed):** `cli/build.gradle` now also
`runtimeOnly project(path: ':core', configuration: 'hostNatives')`, so the jlink
image carries the host platform's native jar. Verified locally: the rebuilt image
bundles `core-0.1.1-natives-darwin-x86_64.jar` (15 dylibs); `mochallama models`
lists profiles and `mochallama chat` replied **"CLI works."**

**Action required:** npm `0.1.1` is published-but-broken (can't republish that
version for 24h). Bump to **0.1.2** and republish the CLI (and Maven, to keep
versions in sync).

## Bottom line
- Maven library **0.1.1**: shipped + verified working across Java / Spring / Spring AI. ✅
- npm CLI: bug found by *running* (not building), fixed; **re-release as 0.1.2**. ⏳

## Lesson
Build-verification is not enough — every published artifact must be **run**
(native load + one inference), not just compiled. The native load smoke test
covers core/starter; the CLI jlink image needs the same run check before publish.
