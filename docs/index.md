---
layout: home

hero:
  name: mochallama
  text: Local LLM for Spring Boot
  tagline: Project Panama FFM over a thin C++ bridge to vendored llama.cpp. OpenAI-compatible HTTP with streaming and tool calling. No JNI.
  actions:
    - theme: brand
      text: Get Started
      link: /specs/00-overview
    - theme: alt
      text: Examples
      link: /examples/
    - theme: alt
      text: Why mochallama
      link: /research/01-positioning
    - theme: alt
      text: GitHub
      link: https://github.com/deemwar-products/mochallama

features:
  - title: No JNI, all Panama FFM
    details: The Java side binds the native bridge through the JDK 22 Foreign Function & Memory API — no JNI and no native compilation in the Java toolchain.
  - title: Vendored llama.cpp
    details: Upstream llama.cpp lives in-tree as a CMake subproject. A custom Gradle buildNative task compiles it and stages the dylibs into the JAR under platform-specific resources.
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

## See the design journal

Everything about how the bridge works, why the C ABI looks the way it does, what's
deferred, and the trade-offs taken along the way lives in the [specs](/specs/00-overview).
Start with [Overview](/specs/00-overview), then [Architecture](/specs/01-architecture),
then the [Bridge ABI](/specs/02-bridge-abi) and the running
[Design Decisions](/specs/03-decisions) journal.

For the wider picture — how mochallama compares to the rest of the
JVM-meets-llama.cpp ecosystem — read [Landscape](/research/00-landscape) and
[Positioning](/research/01-positioning).
