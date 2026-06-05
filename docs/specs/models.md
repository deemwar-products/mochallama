---
title: Models & profiles
---

# Models & profiles

Reference for which models mochallama loads, how to point it at your own GGUF,
where models are cached, and the load-time error contract. The lineup is
**tool-callers only** — non-tool models are rejected at load, by design (the
reasoning lives in [tool-calling-support.md](tool-calling-support.md)).

## Quickstart

Pull a model and chat with no Java and no model files of your own — the CLI
ships its own JDK-22 runtime image and downloads the default GGUF on first run:

```bash
# default model is qwen2.5-1.5b (~1.1 GB, downloaded once)
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

From a JVM app, point `ChatEngine` at any tool-capable GGUF:

::: code-group

```groovy [build.gradle]
implementation 'io.github.deemwario:mochallama-core:0.1.6'
runtimeOnly    'io.github.deemwario:mochallama-core-platform:0.1.6'
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

In a Spring Boot app the model is selected by configuration; activate a profile
or set `llamacpp.model.*`. The id reported on `GET /v1/models` is derived from
the model filename, so switching profiles also switches the OpenAI-compatible
model id.

```sh
./gradlew bootRun --args='--spring.profiles.active=qwen2.5-3b'
```

## Built-in profiles

All four are tool-capable and quantized **Q4_K_M**. The default is
`qwen2.5-1.5b` — the smallest/fastest kept model and the proven tool-caller in
this lineup, so first boot is quick.

| Profile        | HF repo                               | Filename                              | Size on disk | RAM (approx) | Gen tok/s (CPU, approx) | Tool calling | Notes |
|----------------|---------------------------------------|---------------------------------------|--------------|--------------|-------------------------|--------------|-------|
| `qwen2.5-1.5b` | `Qwen/Qwen2.5-1.5B-Instruct-GGUF`     | `qwen2.5-1.5b-instruct-q4_k_m.gguf`   | 1.1 GB       | ~2 GB        | ~12                     | Yes (proven) | **Default.** Smallest + fastest kept model; native agent tool test passed on this exact GGUF. |
| `qwen2.5-3b`   | `Qwen/Qwen2.5-3B-Instruct-GGUF`       | `qwen2.5-3b-instruct-q4_k_m.gguf`     | 2.1 GB       | ~3 GB        | ~7                      | Yes          | Quality step up; same Qwen2.5 tool template. |
| `qwen3-4b`     | `unsloth/Qwen3-4B-Instruct-2507-GGUF` | `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`  | 2.5 GB       | ~4.5 GB      | ~9.8 (measured)         | Yes          | Strongest general-purpose 4B in the lineup; strong tool calling. Profile id is historical; the model is "Qwen3". |
| `phi-4-mini`   | `unsloth/Phi-4-mini-instruct-GGUF`    | `Phi-4-mini-instruct-Q4_K_M.gguf`     | 2.5 GB       | ~3.5 GB      | ~10.6 (measured)        | Yes          | Fastest 4B-class in the lineup; ships a tool-capable chat template. |

To switch models in a Spring app, activate the matching profile (the names
above) or set `llamacpp.model.url` / `llamacpp.model.filename` directly.

::: tip Why the defaults are small
Q4_K_M weights load fast and fit in a few GB of RAM, so a plain `npx` or
`bootRun` reaches first-token quickly on a laptop CPU with no GPU. The rationale
for the curated, tool-only lineup is in
[tool-calling-support.md](tool-calling-support.md) — this page is the reference.
:::

## Load any model by Hugging Face id

You are not limited to the four built-in profiles. Point at **any tool-capable
Hugging Face GGUF repo** by id (`org/repo`); the resolver picks the preferred
quant (`Q4_K_M`, falling back through `Q5_K_M → Q4_0 → …`) via the Hub API and
downloads it into the shared cache.

::: code-group

```properties [Spring (application.properties)]
# Alternative to llamacpp.model.url + .filename
llamacpp.model.hf-id=Qwen/Qwen2.5-3B-Instruct-GGUF
llamacpp.model.quant=Q4_K_M
```

```bash [CLI]
# --model accepts a profile name, an HF id, OR a local .gguf path
npx @deemwario/mochallama chat --model Qwen/Qwen2.5-3B-Instruct-GGUF
npx @deemwario/mochallama chat --model /path/to/your-model.gguf
```

:::

**Resolution precedence (Spring):** explicit `llamacpp.model.url` +
`.filename`  >  `llamacpp.model.hf-id` + `.quant`  >  the built-in default.

Both the starter and the CLI share one resolver/downloader and one model cache,
so a model pulled by either is reused by the other.

## Model cache

Models cache under **`~/.chatbot_models`** (overridable in Spring via
`llamacpp.model.cache-dir`). The first run downloads the GGUF there and reuses
it after that. The CLI and the Spring app share this directory.

## Load-time error contract

Model selection fails **fast and explicitly** rather than degrading silently.
These are documented, stable error codes:

| Code | When it fires | Behavior |
|------|---------------|----------|
| `MODEL_NOT_TOOL_CAPABLE` | The GGUF's chat template does not support tool calling (e.g. Gemma GGUFs; Llama-3.2 1B/3B are only partial). | The model is **rejected at load** — never silently downgraded to a non-tool fallback. In Java, `ChatEngine`/`MochallamaClient` throw `LlamaException(MODEL_NOT_TOOL_CAPABLE)`; the native bridge returns `NULL`. |
| `MODEL_GATED` | The repo is gated/private and no accepted license / `HF_TOKEN` is available. | **Fails early** at resolve/download time. Set `HF_TOKEN` (and accept the license on Hugging Face) to use a gated repo. |

::: warning Tool-callers only
A non-tool HF id is rejected, not silently accepted and downgraded. This is the
core contract of the library — see
[tool-calling-support.md](tool-calling-support.md) for the detection mechanism
and the HF fetch/verify flow.
:::

### Dropped (not tool-capable / unreliable)

| Former profile | Why dropped |
|----------------|-------------|
| `gemma-4-e4b` (`unsloth/gemma-3n-E4B-it-GGUF`) | Gemma GGUFs generally lack a tool-calling chat template. Also the highest RAM cost in the old lineup. |
| `llama-3.2-1b` (`mukel/Llama-3.2-1B-Instruct-GGUF`) | Only partial tool support; not reliable enough to emit tool-call JSON. |
| `llama-3.2-3b` (`mukel/Llama-3.2-3B-Instruct-GGUF`) | Same partial tool support as the 1B. |

## CLI: models, chat & sessions

`mochallama models` lists the built-in profiles. `mochallama chat` is a real
**multi-turn** REPL (it keeps full conversation history, not amnesiac
single-turns) and **persists conversations as sessions**.

```bash
npx @deemwario/mochallama models                       # list profiles
npx @deemwario/mochallama chat -m qwen2.5-1.5b         # start a chat
npx @deemwario/mochallama sessions                      # list saved sessions
npx @deemwario/mochallama chat --resume <id>           # continue a prior chat
```

Sessions persist as one JSON file per session under
**`~/.chatbot_models/sessions/<id>.json`**. `mochallama sessions` lists them
(id, model, turns, last-updated); resume a prior conversation with
`chat --resume <id>` (a resumed session decides its own model). `mochallama
chat --no-save` runs an ephemeral session that is never written to disk.

`chat` options:

| Option | Default | Meaning |
|--------|---------|---------|
| `-m`, `--model` | `qwen2.5-1.5b` | Profile name, HF id (`org/repo`), or local `.gguf` path. |
| `--resume <id>` | — | Continue a saved session (see `mochallama sessions`). |
| `--no-save` | off | Ephemeral session — nothing written to disk. |
| `--max-tokens` | `256` | Max tokens per reply. |
| `--temperature` | `0.7` | Sampling temperature. |

In-REPL slash commands:

| Command | Effect |
|---------|--------|
| `/help` | List the commands. |
| `/reset` | Clear the conversation history (keeps the session id). |
| `/exit` | Quit (EOF / Ctrl-D also quits). |

## Notes on the numbers

- **Size on disk** is the HF-reported file size for the listed `.gguf`.
- **RAM** is a rule-of-thumb: Q4 weights × ~1.3 plus the KV cache for the
  configured context size. Longer contexts use more.
- **Gen tok/s** marked *measured* is single-stream on a stock AVX2 CPU build,
  Q4_K_M, fp16 KV. The other figures are extrapolated rules of thumb, not
  measured here.

::: warning Honest CPU latency
These are small Q4 models on **CPU**. Expect roughly **single-digit to ~12
tokens/sec** on a typical laptop/desktop CPU — readable and interactive, but not
GPU-fast. Your CPU, RAM, and thread count matter more than the choice of model.
mochallama runs in-process via llama.cpp; it does not auto-offload to a GPU the
way a standalone server might. Pick the smallest model that meets your quality
bar.
:::

All four shipped GGUF resolve URLs are HEAD-checked for anonymous download — no
gated/private models are referenced in any built-in profile.
