---
title: How mochallama compares
description: An honest comparison of mochallama against Ollama, Jlama, java-llama.cpp, Spring AI, and node-llama-cpp — including where each alternative genuinely wins.
---

# How mochallama compares

mochallama is the only **in-process, tool-calling local LLM for the JVM** — Spring-first,
OpenAI-compatible, llama.cpp-backed via Project Panama FFM (no JNI, no daemon, no
native-install dance).

That's a narrow claim, and this page exists to back it up *fairly*. Every alternative
below is a good tool that wins somewhere — we say exactly where. If you only need an
external model server, or a pure-Java engine, or RAG orchestration, one of them is the
better answer and we'll tell you so.

::: tip Corrections welcome
This table is maintained by hand and the ecosystem moves fast. If a cell is wrong or
out of date, **[open a PR](https://github.com/deemwar-products/mochallama)** — we'd
rather be corrected than be wrong.
:::

## 60-second on-ramp

The fastest way to judge a tool is to run it. No Java install required:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

That downloads a ~31 MB jlink JDK-22 image for your platform plus the model on first
run, then drops you into a real multi-turn chat. To embed it instead, it's one Maven
or Gradle dependency (see [Adopt path](#adopt-path) below).

## The comparison

The honest read: pick the row that matches your constraint, not the one with the most
"yes" cells. The **Why it matters** column says what each row is really optimizing for.

| | Approach | JVM-native? | Install burden | API surface | Tool calling | Best for |
|---|---|---|---|---|---|---|
| **mochallama** | Java → Panama FFM (GA, JDK 22) → ~700-LOC extern-C bridge over llama.cpp `common_chat` → llama.cpp b9371 → GGUF. No JNI. | **yes (in-process)** | One dependency; per-platform classifier jars auto-load the right native (no C toolchain, no `LD_LIBRARY_PATH`). CLI ships a jlink JDK-22 image via `npx` — no Java install. | Plain-Java `ChatEngine`/`MochallamaClient`; Spring Boot starter with OpenAI-compatible `POST /v1/chat/completions` (+SSE) and `GET /v1/models`; Spring AI `ChatModel`/`ChatClient` adapter; Picocli CLI. | Tool-only **by contract** — non-tool models rejected at load (`MODEL_NOT_TOOL_CAPABLE`). Tools + SSE + `tool_choice` work **together**. | Embedding a local, tool-calling LLM **inside** a JVM/Spring app, or a zero-install CLI — no sidecar, no daemon, no network hop. |
| Ollama | Go daemon wrapping llama.cpp; separate long-running process on `:11434`. JVM apps talk to it over HTTP. | sidecar (HTTP only) | Install + run the daemon; uses resources even when idle. No JDK constraint. Models in a proprietary hashed blob store. | Native REST + OpenAI-compatible `/v1/chat/completions`, `/v1/models`, `/v1/embeddings`. Reached from the JVM via Spring AI's Ollama starter or LangChain4j. | Tools supported but do **not** stream; no `tool_choice`; degrades silently on the small models people run locally. | A shared standalone model server, **GPU offload out of the box**, the widest model catalogue. |
| Jlama | Pure-Java engine — reimplements inference in Java on the incubating Vector API (Panama native fallback). Not a llama.cpp binding. SafeTensors, not GGUF. | yes (in-process) | **No native binary at all** (runs anywhere the JVM runs), but needs `--enable-preview` + `--add-modules=jdk.incubator.vector` forever; finite hand-ported model set. | Java API; LangChain4j integration (`langchain4j-jlama`, still beta). | Function calling per-model where supported; no fail-fast contract; no JSON mode yet. | A **pure-JVM** artifact with zero native binaries, exotic CPUs llama.cpp doesn't prebuild, or stepping a debugger through inference. |
| java-llama.cpp (kherud, JNI) | JNI binding loading a hand-written `jllama` shared lib compiled from llama.cpp sources (b4916, behind upstream). | yes (in-process, JNI) | Prebuilt libs for 5 platforms; GPU/other targets need a local cmake compile + `LD_LIBRARY_PATH` + `-D` flags. **Java 11+** (low bar). | Java inference API; GBNF grammar constraints; chat-template support. | Grammar/template-driven; no enforced tool-only contract. | **Older JDKs (Java 11)** where FFM isn't available, accepting hand-maintained JNI glue. |
| Spring AI + Ollama | Spring AI is an orchestration layer; its only local path is an HTTP client pointed at a separately-run Ollama daemon. No in-process inference. | sidecar (HTTP only) | One Spring starter, but you still install/run/supervise the Ollama daemon. | `ChatModel`/`ChatClient`, `@Tool` annotations with auto-generated JSON schemas, RAG/memory/agents. | First-class `@Tool` calling — but inherits Ollama's gaps (no streaming tool calls / `tool_choice` over the Ollama API). | **Provider-portable** apps wanting RAG/agents/memory and one-line backend swaps. |
| node-llama-cpp | Node.js bindings to llama.cpp; prebuilt binaries with source fallback. In-process, but Node/Bun/Electron — not the JVM. | no (Node runtime) | Zero-config `npm install`; prebuilt natives, no node-gyp/Python. | Typed JS API; declarative schema-enforced function calling; `npx` CLI chat; OpenAI-style usage. | Schema-enforced declarative tools at generation time. | In-process local LLMs in **Node/Bun/Electron** apps — the closest analog, but for JS. |

## Where each alternative honestly wins

We mean this. If one of these matches your constraint, use it.

### Choose Ollama if…

…you want a **shared standalone model server**, **automatic GPU offload out of the box**,
the **widest ready-to-pull catalogue**, or you can't move to JDK 22. It is the easiest
on-ramp and the most documented Java + local path that exists today. mochallama runs
*in your process*; that's a different shape, not a strictly better one. If a single
shared model server feeding many apps is what you want, that's Ollama's job, not ours.

The honest contrast: Ollama's **tool calls don't stream**, and there's **no `tool_choice`**.
Spring AI's own docs note it offers "no Streaming Tool Calls nor Tool choice" over the
Ollama API, and tool reliability degrades quietly on the small models people actually run
locally. If streamed tool calls and a fail-fast tool contract matter, that's the gap
mochallama fills.

### Choose Jlama if…

…you need a **pure-JVM artifact with zero native binaries**, must run on an **exotic CPU**
target llama.cpp doesn't prebuild, or you want to **step a debugger end-to-end** through
inference. Its no-native purity is genuinely cleaner than shipping classifier jars, and —
unlike mochallama — it imposes **no tool-only restriction**, so it'll happily run plain
chat models.

The trade it asks for: Jlama reimplements the math in Java on the **incubating Vector API**,
so you carry `--enable-preview` + `--add-modules=jdk.incubator.vector` **forever** (the
Vector API has been incubating for years with no GA date), and you're limited to its
hand-ported model set rather than llama.cpp's full GGUF zoo and Metal/CUDA/AVX kernels.

### Choose java-llama.cpp (JNI) if…

…you're **stuck on an older JDK** — Java 11 — where FFM simply doesn't exist. It's a
real, working binding with GBNF grammar support and a low version bar.

The trade: it's **JNI**, so a native fault takes down the whole JVM rather than throwing
an exception (a real reported issue is a hard `SIGILL` in `ggml_init` that "happened
outside the Java Virtual Machine in native code"). Its bundled lib is compiled from
llama.cpp **b4916, behind upstream**, and GPU/other platforms mean a local cmake compile.
FFM exists precisely to make that class of crash an exception instead.

### Choose Spring AI or LangChain4j if…

…you want **RAG, agents, conversation memory, or provider-portability** — one-line swaps
between OpenAI, Anthropic, Ollama, and the rest. mochallama is an **inference engine + wire
API**, not an orchestration framework, and it does **not** compete with these.

It slots in **underneath** them: `mochallama-spring-ai` is a Spring AI `ChatModel` adapter,
so you get the full Spring AI surface (RAG/memory/`@Tool`) with mochallama as the **local,
in-process provider** instead of a remote daemon. Use both.

## The quadrant nobody else fills

Stack up the requirements and the overlap collapses to one option:

- **in-process on the JVM** — rules out Ollama, llama-server, LM Studio, vLLM (all HTTP sidecars) and node-llama-cpp (Node, not JVM)
- **real upstream llama.cpp** (full GGUF zoo + Metal/CUDA/AVX kernels) — rules out Jlama (pure-Java reimplementation, SafeTensors)
- **Panama FFM, GA, not JNI/incubator** — rules out java-llama.cpp (JNI) and Jlama (incubating Vector API)
- **zero native-install *and* zero Java-install** (classifier jars + jlink `npx` CLI) — rules out the FFM-over-llama.cpp proof-of-concept Utilitron/**LlamaFFM**, which is unreleased and makes you build llama.cpp yourself
- **Spring-autoconfigured, OpenAI-compatible wire API**
- **tools + SSE streaming + `tool_choice`, all together**

No other option satisfies all of these **simultaneously**. That intersection is the
empty quadrant mochallama was built to occupy — and it's published on **Maven Central +
npm**, not a hobby snapshot. (For completeness: AWS's **DJL** llama engine is deprecated,
so it isn't a live option here either.)

## Adopt path

When you're past the CLI and ready to embed, it's one dependency. Real coordinates,
copy-paste runnable:

::: code-group

```kotlin [Gradle (Kotlin)]
dependencies {
  implementation("io.github.deemwario:mochallama-core:0.1.6")
  // Aggregator: pulls the right per-platform native jar automatically.
  runtimeOnly("io.github.deemwario:mochallama-core-platform:0.1.6")
}
```

```xml [Maven]
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core</artifactId>
  <version>0.1.6</version>
</dependency>
<!-- Aggregator: pulls the right per-platform native jar automatically. -->
<dependency>
  <groupId>io.github.deemwario</groupId>
  <artifactId>mochallama-core-platform</artifactId>
  <version>0.1.6</version>
  <scope>runtime</scope>
</dependency>
```

```groovy [Gradle (Groovy)]
dependencies {
  implementation 'io.github.deemwario:mochallama-core:0.1.6'
  // Aggregator: pulls the right per-platform native jar automatically.
  runtimeOnly 'io.github.deemwario:mochallama-core-platform:0.1.6'
}
```

:::

```java
// Plain Java — no Spring required.
import tools.deemwar.mochallama.panama.ChatEngine;
import java.nio.file.Path;

var engine = ChatEngine.load(Path.of(System.getProperty("user.home"),
    ".chatbot_models", "qwen2.5-1.5b-instruct-q4_k_m.gguf"));
System.out.println(engine.chat("Write a haiku about Project Panama.", 128, 0.7));
```

::: warning JDK 22+
mochallama needs **JDK 22+** (FFM is GA there) and runs with
`--enable-native-access=ALL-UNNAMED`. If you can't move off Java 11, that's the one
case where java-llama.cpp (JNI) is the right call — see above.
:::

For the Spring Boot starter (OpenAI endpoint + Actuator) and the Spring AI adapter, see
the [Spring Boot example](/examples/02-spring-boot). For tools + streaming together, see
[Tools & Streaming](/examples/04-tools-and-streaming).

## The CLI, in detail

The `npx` chat above isn't a toy single-shot prompt — as of **0.1.6** it's a real
multi-turn REPL with persistent sessions.

```bash
# Multi-turn chat — keeps the full conversation history, not amnesiac single turns.
npx @deemwario/mochallama chat -m qwen2.5-1.5b

# List built-in tool-capable profiles and whether each is cached.
npx @deemwario/mochallama models

# List saved conversations (id, model, turns, last-updated).
npx @deemwario/mochallama sessions

# Continue a prior conversation by id.
npx @deemwario/mochallama chat --resume <id>

# Run an ephemeral chat that is never written to disk.
npx @deemwario/mochallama chat -m qwen2.5-1.5b --no-save
```

Conversations persist as JSON at `~/.chatbot_models/sessions/<id>.json`. Inside the REPL,
slash commands `/reset` (clear history), `/help`, and `/exit` are available. This is
strictly **less install than Ollama**: the CLI ships its own jlink JDK-22 runtime via npm
`optionalDependencies`, so there's no Java to install and no daemon to supervise.

::: tip Still skeptical?
Good — that's the right instinct for a comparison page. Run the `npx` line, read the
[Architecture](/specs/01-architecture), and if something here is wrong,
[send a PR](https://github.com/deemwar-products/mochallama). We maintain this table in
public on purpose.
:::
