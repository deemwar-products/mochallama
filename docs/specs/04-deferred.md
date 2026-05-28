# mochallama — Deferred Work

Everything explicitly out of scope for the current cut. Each item is
recorded so it's not lost, and so reviewers can verify that the omissions
are intentional rather than missed.

## Shipped (no longer deferred)

These were deferred in earlier cuts and have since landed — kept here as a
breadcrumb so the history is legible:

- **SSE streaming** — `POST /v1/chat/completions` with `"stream": true` now
  returns `text/event-stream`, one `chat.completion.chunk` per token then
  `data: [DONE]`, served via `SseEmitter` on a daemon worker thread so the
  blocking `synchronized` bridge call never holds the servlet thread.
- **Tool / function calling** — `tools` / `tool_choice` request fields and
  `tool_calls` + `finish_reason:"tool_calls"` in the response are modelled and
  wired through `GenerationOptions` / `ToolDefinition`. Drives off the
  tool-capable Qwen / Phi presets (proven on `qwen2.5-1.5b-instruct-q4_k_m`).
- **Real token usage** — both the OpenAI `usage` block and the
  `mochallama.tokens.*` meters now carry the exact native bridge counts from
  `ChatResult.usage()` (the old `length/4` approximation is gone).
- **Spring AI adapter** — `MochallamaChatModel` implements `call` + reactive
  `stream(Prompt) -> Flux<ChatResponse>`; surfaces model tool calls via
  `AssistantMessage.ToolCall`. (Inbound tool-definition mapping through Spring
  AI is intentionally minimal — see `streaming-and-tools.md`.)
- **Multi-model presets** — Spring profiles select the GGUF
  (`--spring.profiles.active=…`); the lineup is trimmed to tool-capable models
  only. See `models.md`.

See `streaming-and-tools.md` and `observability.md` for the full surfaces.

## Cross-OS native binaries

- **Today:** macOS Intel `x86_64` only. `src/main/resources/native/` has
  one platform directory (`darwin-x86_64`).
- **Needed platforms:**
  - `darwin-aarch64` (Apple Silicon, Metal-enabled).
  - `linux-x86_64` (CPU + optional CUDA variant).
  - `linux-aarch64` (server-side ARM).
  - `windows-x86_64` (CPU; DirectML / CUDA later).
- **Plan:** GitHub Actions matrix build, one runner per `(os, arch)`,
  publishing a resources jar per platform. The `NativeLoader` already
  picks up the right directory based on `os.name` + `os.arch`.

## Library packaging / Maven publish

- **Goal:** Publish the bridge + Java glue as a reusable library that
  other Spring apps can pull in.
- **Status:** Separate agent / task. Today the project is a Spring Boot
  application, not a library; `bootJar` is the only artefact.
- **What's needed:** Split into modules (or sub-projects) for
  `mochallama-core` (FFM glue + bridge), `mochallama-spring` (the service
  + controller), `mochallama-natives-<platform>` per OS/arch.

## VitePress documentation site

- **Status:** Separate agent / task. Out of scope here.
- **Note:** `docs/.vitepress/` is reserved and **must not** be touched by
  this agent.

## GraalVM `native-image` build

- **Status:** Blocked on platform support.
- **Reason:** FFM in `native-image` is officially stable in
  GraalVM-for-JDK-25 and is paved primarily on macOS AArch64. Current
  target is macOS Intel `x86_64`.
- **Revisit:** After Linux `x86_64` ships via CI matrix and we have a
  cold-start-sensitive use case (CLI, serverless).

## Legacy in-tree pure-Java implementation

- **Files:** `app/src/main/java/tools/deemwar/mochallama/Llama3.java` and
  `ChatBot.java`, plus their tests.
- **State:** Not on the runtime path of the HTTP service. Carry-over from
  a separate jbang single-file Llama implementation.
- **Plan:** Remove once the FFM path has shipped to at least one
  downstream consumer. Until then they stay — they don't hurt anything,
  and the Vector-API code is interesting reference material.
