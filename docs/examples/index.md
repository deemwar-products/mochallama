---
title: Examples
---

# Examples

Task-first index of how-tos, ordered by the adoption funnel: try it in a
terminal, then over HTTP, then wire it into your app. Each page is
copy-paste-runnable. No tutorial content lives here — this page is just the
router.

::: tip Run the demo app first
Every HTTP example below talks to the demo `app` on
`http://localhost:8080`:

```bash
./gradlew :app:bootRun
```

The port comes up immediately but the model loads asynchronously — wait for
`state: READY` in the logs (or poll `/actuator/health`) before sending chat
requests. Until then the endpoint returns `503 { "error": "model loading" }`.
The CLI examples need no server.
:::

## Try it (zero install)

- [**CLI**](/examples/03-cli) — `npx @deemwario/mochallama chat -m qwen2.5-1.5b`.
  Proves the local engine from a terminal with **no Java install** (ships a jlink
  JDK-22 image). 0.1.6 adds real multi-turn chat with persistent **sessions**:
  `--resume <id>`, `mochallama sessions`, in-REPL `/reset` `/help` `/exit`,
  `--no-save`.

## Drive it over HTTP

- [**curl**](/examples/00-curl) — `POST /v1/chat/completions` (+ SSE `stream:true`)
  and `GET /v1/models` straight from the shell. Proves the OpenAI-compatible
  wire API.
- [**OpenAI SDK (Python)**](/examples/01-openai-sdk) — the official `openai`
  client with `base_url` pointed at the local endpoint. Proves drop-in
  compatibility — point existing OpenAI code at localhost with one line.

## Embed it in your app

- [**Spring Boot**](/examples/02-spring-boot) — add the
  `mochallama-spring-boot-starter`; autoconfig exposes
  `/v1/chat/completions` (+SSE), `/v1/models`, and Actuator health/metrics. The
  same page covers the **Spring AI** adapter (`mochallama-spring-ai`): add it
  alongside the starter and inject a `ChatClient` / `ChatModel`. Proves
  in-process, tool-calling inference inside your own JVM process — no sidecar.

## Tool calling & streaming

- [**Tools & Streaming**](/examples/04-tools-and-streaming) — the full
  tool-calling round-trip plus SSE consumption. Proves that tools, `tool_choice`,
  and streaming work **together** (mochallama is tool-calling-only; non-tool
  models are rejected at load with `MODEL_NOT_TOOL_CAPABLE`).

## Reference

- **Switching models** — presets (`qwen2.5-1.5b` default, `qwen2.5-3b`,
  `qwen3-4b`, `phi-4-mini`) and any tool-capable Hugging Face GGUF id:
  see [Model Profiles](/specs/models).
- **Metrics & health** — inference meters and the readiness indicator:
  see [Metrics](/specs/observability).

::: info Coordinates
Maven Central `io.github.deemwario:*:0.1.6` · npm `@deemwario/mochallama` ·
requires JDK 22+ (FFM GA).
:::
