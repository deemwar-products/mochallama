# Landscape — Java + llama.cpp options (2026-05)

Snapshot of what's available for running a local LLM from a JVM process. All
facts verified against the project's GitHub repo or Maven Central entry on
2026-05-28; entries marked `(unverified)` were not obtainable from the
authoritative source.

## Comparison matrix

| Project | Binding | Bundled natives | On Maven Central | Spring Boot ready | Tool calling | Streaming | License | Last commit |
|---|---|---|---|---|---|---|---|---|
| **de.kherud:llama** | JNI | Yes (Linux x86_64/aarch64, macOS x86_64/aarch64, Windows x86_64; CUDA12 classifier) | Yes (`de.kherud:llama:4.2.0`) | No (plain library; bring your own glue) | No (not in 4.x README) | Yes (`model.generate()` token stream) | MIT | 2025-06-20 |
| **JLama** | Pure Java (Panama Vector API; no llama.cpp) | n/a — pure Java; optional native SIMD + WebGPU backends | Yes (`com.github.tjake:jlama-core:0.8.4`) | No (Langchain4j integration available) | Yes | Yes | Apache-2.0 | 2025-10-12 |
| **llama-bindings** (deemwar) | Panama FFM → in-repo C bridge → llama.cpp | Built locally via `task build`; not published | No | No (Java target is a plain library) | No | No (not in README) | (unverified) | 2026-04-13 |
| **Llama3.java** (mukel) | Pure Java (Vector API), single file | n/a — pure Java | No (download / jbang) | No | No | Yes (`--stream`) | MIT | 2026-04-24 |
| **Utilitron/LlamaFFM** | Panama FFM (direct llama.h binding) | No — user supplies `libllama` via env / library path | No (build & install locally) | Via separate `LlamaFFM-SpringAI` adapter (not on Central) | (unverified) | (unverified) | MIT | 2026-05-08 |
| **nixiesearch/llamacpp-server-java** | Process-spawn the `llama-server` HTTP binary | Yes (CPU x86_64/arm64, CUDA12 x86_64) | Yes (`ai.nixiesearch:llamacpp-server-java:0.0.4-b5604`) | No (just spawns the server) | Inherits from llama-server HTTP API | Inherits from llama-server HTTP API | MIT | 2025-06-08 |
| **mochallama** (this) | Panama FFM → custom thin C bridge → vendored llama.cpp | Yes, bundled in JAR (macOS Intel `x86_64` only today) | No (planned) | Yes — first-class: async load, `/v1/chat/completions`, `/v1/models`, 503 while loading | No | No (returns 501 for `stream: true`) | (unverified — repo lacks LICENSE) | 2026-05-28 |

## Notes per row

- **de.kherud:llama** — Most mature JNI binding. Wide platform coverage and
  CUDA classifier, but it's a library, not a service; the `jllama` shared
  object is a JNI shim, not a thin C bridge. Streaming via Java iterator is
  supported.
- **JLama** — Doesn't use llama.cpp at all; reimplements inference in Java on
  top of the Vector API. Listed here because it's the obvious "no native"
  alternative if you want to stay in pure Java. Different runtime
  characteristics (GGUF support is partial; quantization story is its own).
- **llama-bindings** (`~/muthu/gitworkspace/small-llm-workspace/llama-bindings/`)
  — Author's own multi-language workspace (Go + Java + browser/WASM) sharing
  one C bridge. The Java target uses Panama FFM with a similar bridge
  philosophy. Not published; intended as a foundation, not a turnkey service.
- **Llama3.java** — Karpathy-style single-file inference in pure Java. An
  earlier copy lived inside `llamavector-java/src/main/java/apps/llamavector/Llama3.java`.
  Pedagogical / CLI-first, not a service.
- **Utilitron/LlamaFFM** — The closest existing analogue to mochallama's
  binding strategy (FFM, no JNI). Two important differences: (a) no bundled
  natives — you have to bring your own `libllama`; (b) not on Maven Central.
  A separate `LlamaFFM-SpringAI` adapter exists but is empty-ish (3 commits,
  no releases) at the time of writing.
- **nixiesearch/llamacpp-server-java** — Different model entirely: bundles
  the upstream `llama-server` binary and spawns it as a subprocess. You talk
  HTTP, not FFM. Good if you want llama.cpp's native HTTP feature set
  (including its tool-calling and streaming) at the cost of a separate
  process and OS-level IPC.
- **mochallama** — The only entry that combines: Panama FFM (no JNI shim) +
  custom thin C bridge (5 symbols, JSON over the boundary) + Spring Boot
  service + bundled native (single platform today). Trade-offs documented
  in `01-positioning.md`.

## Sources

See `02-related-reading.md`.
