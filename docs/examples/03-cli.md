# CLI

`@deemwario/mochallama` is a terminal CLI that runs a local llama.cpp model
straight from the command line — a real, multi-turn local chat, in the spirit of
`ollama run`. It is JVM-powered (Project Panama FFM bridge, the same
`mochallama-core` engine as the Spring app) but shipped as a self-contained
**jlink runtime image** — a trimmed **JDK 22** plus the native libs — so you do
**not** need Java installed.

## One command to chat

No global install, no Java, no daemon. `npx` fetches the CLI, the model
downloads on first run, and you're in a real conversation:

```bash
npx @deemwario/mochallama chat -m qwen2.5-1.5b
```

```
Loading model: /Users/you/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf
session a1b2c3d4
resume later with: mochallama chat --resume a1b2c3d4
Ready. Type a message, /help for commands, /exit to quit.

you> my name is Mira
bot> Nice to meet you, Mira! How can I help?
     (0.9s, ~7.0 words/s)

you> what's my name?
bot> Your name is Mira.
     (0.4s, ~3.0 words/s)

you> /exit
bye.
```

::: tip Real multi-turn since 0.1.6
`mochallama chat` keeps the **full conversation history** — the model sees every
prior turn, so it remembers context like the name above. Earlier builds were
amnesiac single-turns; 0.1.6 made `chat` a genuine `ollama run`-style session.
:::

## Install

```bash
npm i -g @deemwario/mochallama
# or run without installing:
npx @deemwario/mochallama chat
```

::: tip Cross-platform, no Java install
Ships per-platform packages for macOS (Intel + Apple Silicon), Linux (x86-64 +
ARM64), and Windows (x86-64). `npm` installs only the package matching your
OS/arch (via `optionalDependencies` + `os`/`cpu`), so you download just your
platform's jlink image (~31 MB). The model itself downloads on first run.
:::

## Sessions (persistent, resumable)

Every `chat` is a saved session. Conversations persist as one JSON file per
session at `~/.chatbot_models/sessions/<id>.json` (the `<id>` is the 8-hex
string the CLI prints on start, e.g. `a1b2c3d4`). The session stores the
**original** model ref you typed and the full turn history.

List what you can resume with `mochallama sessions`:

```bash
mochallama sessions
```

```
ID          MODEL                 TURNS  UPDATED
--          -----                 -----  -------
a1b2c3d4    qwen2.5-1.5b          2      2026-06-05T10:14:09.220Z
9f8e7d6c    Qwen/Qwen2.5-3B-In…   5      2026-06-04T22:01:53.118Z

Resume one with: mochallama chat --resume <id>
Sessions dir: /Users/you/.chatbot_models/sessions
```

The `TURNS` column counts your user turns. Continue any prior conversation with
`--resume <id>`. The full history is re-seeded, and the **session's stored model
wins** — you don't repeat `-m`:

```bash
mochallama chat --resume a1b2c3d4
```

```
Loading model: /Users/you/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf
session a1b2c3d4
resume later with: mochallama chat --resume a1b2c3d4
Resuming session a1b2c3d4 (2 turns)
Ready. Type a message, /help for commands, /exit to quit.

you> remind me of my name
bot> You told me your name is Mira.
```

Want a throwaway chat that leaves nothing on disk? Pass `--no-save`:

```bash
mochallama chat -m qwen2.5-1.5b --no-save
```

## In-chat slash commands

Inside the `you>` prompt:

| Command  | Effect                                                          |
| -------- | -------------------------------------------------------------- |
| `/help`  | List the available commands.                                   |
| `/reset` | Clear the conversation history (keeps the same session id).    |
| `/exit`  | Quit (so does EOF / `Ctrl-D`).                                  |

## List model profiles

`mochallama models` shows the built-in profiles and whether each is cached
locally (in the shared `~/.chatbot_models` dir, reused by the Spring app too).

```bash
mochallama models
```

```
PROFILE         FILENAME                                  SIZE       CACHED
-------         --------                                  ----       ------
qwen2.5-1.5b    qwen2.5-1.5b-instruct-q4_k_m.gguf         ~1.1 GB    no
qwen2.5-3b      qwen2.5-3b-instruct-q4_k_m.gguf           ~2.1 GB    no
qwen3-4b        Qwen3-4B-Instruct-2507-Q4_K_M.gguf        ~2.5 GB    no
phi-4-mini      Phi-4-mini-instruct-Q4_K_M.gguf           ~2.5 GB    no

Cache dir: /Users/you/.chatbot_models

These built-ins are the tool-only lineup shared with the Spring app.
Any tool-capable Hugging Face id also works, e.g.:
  mochallama chat --model Qwen/Qwen2.5-3B-Instruct-GGUF
Non-tool-capable models are refused at load.
```

The built-in profiles are the **same tool-only set** the Spring app ships
(`qwen2.5-1.5b` is the default).

## Choosing a model

You can pass **any tool-capable Hugging Face id** (`org/repo`) or a path to a
local `.gguf` file instead of a profile name, and tune the turn:

```bash
# Built-in profile
mochallama chat --model qwen2.5-3b

# Hugging Face id — resolves the Q4_K_M GGUF and caches it
mochallama chat --model Qwen/Qwen2.5-3B-Instruct-GGUF

# Local file, with sampling tweaks
mochallama chat --model ~/models/my-model.gguf --max-tokens 512 --temperature 0.4
```

Options: `-m`/`--model` (profile name, HF id, or `.gguf` path), `--max-tokens`
(default `256`), `--temperature` (default `0.7`), `--resume <id>`, `--no-save`.
Run `mochallama chat --help` for the full listing.

::: warning Tool-only, enforced
mochallama only runs tool-capable models. If you pass a non-tool model (profile,
HF id, or file), the CLI refuses it at load with a clear message and exits
non-zero — it never silently downgrades. A gated Hugging Face model fails early
too. See [tool-calling support](/specs/tool-calling-support).
:::

::: tip Shared catalogue + cache
The CLI's built-in profiles are the **same tool-only set** as the Spring app
(`qwen2.5-1.5b` / `qwen2.5-3b` / `qwen3-4b` / `phi-4-mini`), and both share one
resolver/downloader and the `~/.chatbot_models` cache — a GGUF fetched by either
side is reused by the other.
:::

## How it works

```
mochallama (npm bin)
  └─ bin/mochallama.js          # Node shim, forwards argv + exit code
       └─ image/bin/mochallama  # jlink launcher (trimmed JDK 22 + JVM args)
            └─ mochallama-core   # Panama FFM bridge → llama.cpp native libs
```

The launcher runs with `--enable-native-access=ALL-UNNAMED` and
`--add-modules=jdk.incubator.vector` baked in. No JNI, no separate daemon, no
network hop — the model runs in-process inside the CLI's own JVM image.
