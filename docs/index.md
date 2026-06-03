---
layout: home

hero:
  name: mochallama
  text: Local LLM for Spring Boot
  tagline: Project Panama FFM over a thin C++ bridge to vendored llama.cpp. OpenAI-compatible HTTP with streaming and tool calling. No JNI.
  actions:
    - theme: brand
      text: Get Started
      link: /examples/
    - theme: alt
      text: Architecture
      link: /specs/01-architecture
    - theme: alt
      text: GitHub
      link: https://github.com/deemwar-products/mochallama

features:
  - title: No JNI, all Panama FFM
    details: The Java side binds a thin C++ bridge through the JDK 22 Foreign Function & Memory API — no JNI. The CLI even bundles JDK 22, so end users need no Java.
  - title: Prebuilt llama.cpp, 5 platforms
    details: Downloads llama.cpp's official prebuilt binaries (no compiling) and compiles only the tiny bridge. Ships per-platform native jars for macOS Intel + Apple Silicon, Linux x86-64 + ARM64, and Windows x86-64.
  - title: OpenAI-compatible — streaming + tools
    details: POST /v1/chat/completions speaks the OpenAI wire format with SSE streaming, tool calling, and real token usage. GET /v1/models works with any OpenAI client or the openai SDK pointed at http://localhost:8080/v1.
  - title: Spring-first, autoconfigured
    details: Add the spring-boot-starter and get a local model service, the OpenAI endpoint, and a Spring AI ChatClient / ChatModel with no wiring. See the Spring Boot example.
  - title: Metrics out of the box
    details: The starter registers inference meters (timer, token distributions, tool-call counter, tokens/sec) and a model health indicator via Actuator + Micrometer — Prometheus is opt-in. See Metrics.
  - title: Tool-capable model profiles
    details: Switch GGUF models via Spring profiles (qwen2.5-1.5b, qwen2.5-3b, qwen3-4b, phi-4-mini) — all tool-callers. Default qwen2.5-1.5b. Models download on first start into ~/.chatbot_models.
---

## Quickstart

Once the service is running (default port `8080`) and the model has finished loading
(watch for `state: READY`), send an OpenAI-shaped request:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "Write a haiku about Project Panama."}
    ],
    "max_tokens": 128,
    "temperature": 0.7
  }'
```

While the model is still downloading or loading, the endpoint returns
`503 Service Unavailable` with `{"error": "model loading", "state": "DOWNLOADING" | "LOADING"}` —
that's intentional, so the HTTP port comes up immediately and supervisors can probe it.

For streaming, tool calling, the OpenAI Python SDK, the Spring Boot starter and
the CLI, see the [Examples](/examples/). For the inference meters and health
indicator, see [Metrics](/specs/observability).

## Learn more

How the stack fits together — Java → Panama FFM → the thin C++ bridge →
llama.cpp — is in [Architecture](/specs/01-architecture). For the model lineup
and how to switch GGUFs see [Model Profiles](/specs/models), and for the
Actuator meters and health indicator see [Metrics](/specs/observability).
