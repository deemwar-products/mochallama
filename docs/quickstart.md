---
title: Quickstart
description: Three copy-paste tracks to your first local token — the no-install CLI, plain Java, and Spring Boot.
---

# Quickstart

The metric here is **time-to-first-token**. Pick the track that matches what you're
building, paste the block, get a local model talking back. Models are real GGUFs,
coordinates are the published `0.1.6` artifacts (the CLI ships `0.1.6`), and the
preset ids below are the actual tool-capable defaults — no placeholders.

Three tracks, fastest first:

- **[Track 1 — Zero install (CLI)](#track-1-zero-install-cli)** — `npx`, no Java, chat in your terminal.
- **[Track 2 — Embed in plain Java](#track-2-embed-in-plain-java)** — one dependency, `ChatEngine`.
- **[Track 3 — Spring Boot](#track-3-spring-boot)** — one starter, OpenAI-compatible HTTP.

## Track 1: Zero install (CLI)

No Java, no build, no native install. The CLI ships its own jlink JDK-22 runtime
through npm, so `npx` is all you need:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

The model (`qwen2.5-1.5b`, the default preset) downloads on first run into
`~/.chatbot_models`, then you're chatting in the terminal. It's **real multi-turn**
— the full conversation history is kept, not amnesiac single shots.

List the tool-capable presets first if you'd rather browse:

```bash
npx @deemwario/mochallama models
```

::: tip In-chat slash commands
Inside the chat REPL:

- `/help` — list commands
- `/reset` — clear the conversation history (keeps the session id)
- `/exit` — quit (or `Ctrl-D` / EOF)
:::

### Sessions and `--resume`

Every chat persists as a session under `~/.chatbot_models/sessions/<id>.json`.
List them and pick one up where you left off:

```bash
# List saved sessions (id, model, turns, last updated)
npx @deemwario/mochallama sessions

# Continue a prior conversation — the session decides the model
npx @deemwario/mochallama chat --resume <id>
```

Want a throwaway chat that leaves no trace? Add `--no-save`:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b --no-save
```

## Track 2: Embed in plain Java

One library dependency plus the native aggregator, and you call the model
in-process — no daemon, no HTTP, no sidecar.

::: warning Prerequisite
The library tracks need **JDK 22+** (Project Panama FFM is GA there). The CLI in
Track 1 needs no Java at all.
:::

### Add the dependencies

The `core` jar is Java-only (~40 KB). The per-platform native libs ship
separately; the `-platform` aggregator pulls all five platforms' natives and the
matching one loads at runtime.

::: code-group

```groovy [build.gradle]
dependencies {
    implementation 'io.github.deemwario:mochallama-core:0.1.6'
    // All platforms' natives; the right one loads at runtime.
    runtimeOnly 'io.github.deemwario:mochallama-core-platform:0.1.6'
}
```

```kotlin [build.gradle.kts]
dependencies {
    implementation("io.github.deemwario:mochallama-core:0.1.6")
    runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
}
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

### Run it with the FFM access flag

The FFM bridge needs one JVM argument:

```
--enable-native-access=ALL-UNNAMED
```

### Minimal program

`ChatEngine.load(Path)` takes a path to a tool-capable GGUF on disk and returns a
ready-to-use engine. (Use the CLI in Track 1 once to download
`~/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf`, or point at any GGUF you
already have.)

```java
import tools.deemwar.mochallama.panama.ChatEngine;
import java.nio.file.Path;

public class Demo {
    public static void main(String[] args) {
        Path gguf = Path.of(System.getProperty("user.home"),
                ".chatbot_models", "qwen2.5-1.5b-instruct-q4_k_m.gguf");

        try (ChatEngine engine = ChatEngine.load(gguf)) {
            String reply = engine.chat("Write a haiku about Project Panama.", 128, 0.7);
            System.out.println(reply);
        }
    }
}
```

::: tip Tool-calling-only, by contract
If the GGUF's chat template isn't tool-capable, `load` fails fast with a
`LlamaException` carrying code `MODEL_NOT_TOOL_CAPABLE` — never silent
degradation. Stick to the presets below and you're fine.
:::

## Track 3: Spring Boot

Add one starter and you get an autoconfigured local model service plus an
OpenAI-compatible HTTP API — `POST /v1/chat/completions` (with SSE when
`stream:true`) and `GET /v1/models` — with no manual wiring. No Spring AI
dependency required.

### Add the dependencies

::: code-group

```groovy [build.gradle]
dependencies {
    implementation 'io.github.deemwario:mochallama-spring-boot-starter:0.1.6'
    runtimeOnly 'io.github.deemwario:mochallama-core-platform:0.1.6'
}
```

```kotlin [build.gradle.kts]
dependencies {
    implementation("io.github.deemwario:mochallama-spring-boot-starter:0.1.6")
    runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
}
```

:::

Add the FFM access flag to your run config:

```groovy
bootRun {
    jvmArgs = ['--enable-native-access=ALL-UNNAMED']
}
```

### Point at a model

One `llamacpp.model.*` block in `application.properties`. The model downloads on
first start into `~/.chatbot_models`:

```properties
llamacpp.model.url=https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
llamacpp.model.filename=qwen2.5-1.5b-instruct-q4_k_m.gguf
llamacpp.model.cache-dir=${user.home}/.chatbot_models
```

### Call it

Start the app, wait for the model to reach `READY`, then send an OpenAI-shaped
request (default port `8080`):

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

::: info 503 while loading is intentional
The HTTP port comes up immediately so supervisors can probe it. While the model
is still downloading or loading you get `503 Service Unavailable` with
`{"error": "model loading", "state": "DOWNLOADING" | "LOADING"}`. Once the state
machine reaches `READY`, requests succeed. `GET /v1/models` lists the loaded model.
:::

## Prerequisites

::: warning Requirements at a glance
- **CLI (Track 1):** nothing — the npm package bundles its own JDK-22 runtime.
- **Library + Spring (Tracks 2 & 3):** **JDK 22+** (Project Panama FFM is GA there)
  and the JVM arg `--enable-native-access=ALL-UNNAMED`.
- **Platforms:** macOS (Intel + Apple Silicon), Linux (x86-64 + ARM64), Windows (x86-64).
- **Model cache:** all tracks cache GGUFs under `~/.chatbot_models`.
:::

**Tool-capable presets:** `qwen2.5-1.5b` (default), `qwen2.5-3b`, `qwen3-4b`,
`phi-4-mini`. These are the model ids the CLI and presets accept out of the box.

## Next steps

- **[Examples](/examples/)** — the [OpenAI Python SDK](/examples/01-openai-sdk)
  pointed at your local endpoint, the [Spring AI](/examples/02-spring-boot)
  `ChatClient`, and the full [tools + streaming](/examples/04-tools-and-streaming)
  round-trip.
- **[Why](/why)** — the decision narrative: in-process FFM over a thin bridge to
  upstream llama.cpp, not JNI or a sidecar.
- **[Compare](/compare)** — mochallama next to Ollama, Jlama, java-llama.cpp,
  Spring AI + Ollama, and node-llama-cpp, and where each is honestly better.
- **[Architecture](/specs/01-architecture)** — the full pipeline, module by module.
- **[Model profiles](/specs/models)** — the preset details, plus Hugging-Face-by-id
  and gated-model behaviour (deferred from this happy path).
- **[Tool calling](/specs/tool-calling-support)** and
  **[Streaming & tools](/specs/streaming-and-tools)** — the tool-only contract and
  SSE specifics.
