---
layout: home

hero:
  name: mochallama
  text: A local, tool-calling LLM inside your JVM
  tagline: The only in-process, tool-calling local LLM for the JVM — Spring-first, OpenAI-compatible, llama.cpp-backed via Project Panama FFM. No JNI, no daemon, no native-install dance. Requires JDK 22+.
  actions:
    - theme: brand
      text: Quickstart
      link: /quickstart
    - theme: alt
      text: Why mochallama
      link: /why
    - theme: alt
      text: Examples
      link: /examples/
    - theme: alt
      text: GitHub
      link: https://github.com/deemwar-products/mochallama

features:
  - title: In-process — no daemon, no network hop
    details: The model runs inside your application's own process. No Ollama-style sidecar to install and supervise, no HTTP round-trip, no idle resource drain. Inference is stateful and rides your app's lifecycle, Actuator health, and Micrometer metrics.
  - title: No JNI — all Panama FFM
    details: Java talks to llama.cpp through the JDK 22 Foreign Function & Memory API (GA, not incubator), over a thin ~700-LOC extern-C bridge on llama.cpp's common_chat. No hand-written JNI glue, far fewer crash vectors.
  - title: Prebuilt llama.cpp, 5 platforms, zero native-install
    details: Consumes upstream's official prebuilt llama.cpp release libs (tag b9371) and compiles only the bridge (~2–11s, not a 95-minute from-source build). Per-platform classifier jars auto-load the right native — macOS Intel + Apple Silicon, Linux x86-64 + ARM64, Windows x86-64.
  - title: Spring autoconfig, OpenAI-compatible
    details: One @AutoConfiguration dependency exposes POST /v1/chat/completions (with SSE when stream:true) and GET /v1/models. Tools and streaming work together. Drop-in for code already written against OpenAI or Ollama.
  - title: Tool-calling-only — fail-fast
    details: Built for agentic / function-calling work. Non-tool-capable models are rejected at load with MODEL_NOT_TOOL_CAPABLE — an explicit contract instead of silent degradation on small models.
  - title: Metrics via Actuator
    details: The starter registers inference meters (timer, token distributions, tool-call counter, tokens/sec) and a model health indicator through Actuator + Micrometer. Prometheus is opt-in.
---

## The 10-second hook

No Java install, no daemon, no native build — `npx` a tool-calling local LLM and start chatting:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

The CLI ships its own jlink JDK-22 runtime image via npm, so this needs no JDK on the host.
`qwen2.5-1.5b` is the default tool-capable preset; the model downloads on first run into `~/.chatbot_models`.

## Embed it: the smallest plain-Java snippet

Two dependencies — the Java jar plus the platform aggregator that resolves the right native classifier jar for your host:

::: code-group

```kotlin [build.gradle.kts]
implementation("io.github.deemwario:mochallama-core:0.1.6")
runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
```

```xml [pom.xml]
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core</artifactId>
  <version>0.1.6</version>
</dependency>
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core-platform</artifactId>
  <version>0.1.6</version>
  <scope>runtime</scope>
</dependency>
```

:::

```java
import tools.deemwar.mochallama.panama.ChatEngine;
import java.nio.file.Path;

var engine = ChatEngine.load(Path.of("/path/to/model.gguf"));
String reply = engine.chat("Write a haiku about Project Panama.", 128, 0.7);
System.out.println(reply);
```

::: tip JVM flags
JDK 22+ is required (FFM is GA there). Run with `--enable-native-access=ALL-UNNAMED`.
:::

## Or one Spring dependency

The starter autoconfigures a local model service and the OpenAI-compatible endpoints — no `spring-ai` dependency required:

::: code-group

```kotlin [build.gradle.kts]
implementation("io.github.deemwario:mochallama-spring-boot-starter:0.1.6")
runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
```

```xml [pom.xml]
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-spring-boot-starter</artifactId>
  <version>0.1.6</version>
</dependency>
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core-platform</artifactId>
  <version>0.1.6</version>
  <scope>runtime</scope>
</dependency>
```

:::

Tell it which model to load — a Hugging Face id is the simplest (it resolves + caches the GGUF on first start). In `src/main/resources/application.properties`:

```properties
llamacpp.model.hf-id=Qwen/Qwen2.5-1.5B-Instruct-GGUF
# or an explicit url + filename:
# llamacpp.model.url=https://.../qwen2.5-1.5b-instruct-q4_k_m.gguf
# llamacpp.model.filename=qwen2.5-1.5b-instruct-q4_k_m.gguf
```

Start the app (the model loads asynchronously — endpoints return `503` until `state: READY`), then point any OpenAI client at it. `POST /v1/chat/completions` handles non-streaming, `stream:true` SSE, and `tools` / `tool_choice`; `GET /v1/models` lists the loaded model.

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Hello from local llama.cpp"}]}'
```

## A real multi-turn CLI

`mochallama chat` is a stateful REPL — it keeps the full conversation history, not amnesiac single turns.

```bash
# List the tool-capable presets / loaded models
npx @deemwario/mochallama models

# Start a multi-turn chat; the conversation is saved as a session
npx @deemwario/mochallama chat -m qwen2.5-1.5b

# List past sessions (id, model, turns, last-updated)
npx @deemwario/mochallama sessions

# Continue a prior conversation
npx @deemwario/mochallama chat --resume <id>
```

Sessions persist at `~/.chatbot_models/sessions/<id>.json`. Pass `--no-save` for an ephemeral run.
Inside the REPL, slash commands `/reset`, `/help`, and `/exit` are available.

## Honest positioning

Today every local-LLM path for the JVM reaches your app over HTTP — Ollama, llama-server, LM Studio and friends are all separate processes, and Spring AI / LangChain4j just point an HTTP client at them. The other in-process options are non-JVM, or on the JVM are pure-Java Jlama (reimplements inference on the incubating Vector API, GGUF-less) or JNI bindings whose native faults can take down the whole JVM. mochallama fills the empty quadrant: FFM (GA) + real upstream llama.cpp + Spring-autoconfigured OpenAI wire API + tools-and-SSE-together + zero native-install.

It is an inference engine and wire API, not a RAG/agent framework. For orchestration, memory, and provider-portability you still want Spring AI or LangChain4j — mochallama slots in **under** them as the local provider via its Spring AI `ChatModel` adapter. And if you want a shared standalone model server with automatic GPU offload and the widest model catalogue, Ollama is the easier on-ramp. See the full, PR-welcome breakdown in [Compare](/compare).

## What to do next

- **[Quickstart](/quickstart)** — time-to-first-success: npx, plain Java, and Spring Boot.
- **[Why mochallama](/why)** — the FFM-not-JNI, prebuilt-not-compiled, tool-only decisions.
- **[Examples](/examples/)** — curl, OpenAI Python SDK, Spring Boot, CLI, tools + streaming.
- **[Compare](/compare)** — mochallama vs Ollama, Jlama, java-llama.cpp, Spring AI, node-llama-cpp.
