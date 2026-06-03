# CLI

`@deemwario/mochallama` is a terminal CLI that runs a local llama.cpp model
straight from the command line. It is JVM-powered (Project Panama FFM bridge,
same `mochallama-core` engine as the Spring app) but shipped as a self-contained
**jlink runtime image** — a trimmed **JDK 22** plus the native libs — so you do
**not** need Java installed.

> **Cross-platform.** Ships per-platform packages for macOS (Intel + Apple
> Silicon), Linux (x86-64 + ARM64), and Windows (x86-64). `npm` installs only the
> package matching your OS/arch (via `optionalDependencies` + `os`/`cpu`), so you
> download just your platform's image (~31 MB). The model itself downloads on
> first run.

## Install

```bash
npm i -g @deemwario/mochallama
# or run without installing:
npx @deemwario/mochallama chat
```

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
(`qwen2.5-1.5b` is the default). The older `llama-3.2-*` / `gemma-4-e4b` entries
were removed — they are not reliable tool-callers and would be refused at load.

## Chat

Load a model and chat interactively (one turn per line; `/exit` or EOF quits).
The model downloads on first use into `~/.chatbot_models`.

```bash
mochallama chat --model qwen2.5-3b
```

```
Loading model: /Users/you/.chatbot_models/qwen2.5-3b-instruct-q4_k_m.gguf
Ready. Type a message, /exit to quit.

you> write a haiku about the JVM
bot> Bytecode rivers flow / Through a warm machine of dreams / Garbage swept away
     (2.4s, ~5.0 words/s)

you> /exit
bye.
```

You can also pass **any tool-capable Hugging Face id** (`org/repo`) or a path to
a local `.gguf` file instead of a profile name, and tune the turn:

```bash
# Hugging Face id — resolves the Q4_K_M GGUF and caches it
mochallama chat --model Qwen/Qwen2.5-3B-Instruct-GGUF

# Local file
mochallama chat --model ~/models/my-model.gguf --max-tokens 512 --temperature 0.4
```

Options: `-m`/`--model` (profile name, HF id, or `.gguf` path), `--max-tokens`,
`--temperature`. Run `mochallama --help` for the full listing.

> **Tool-only, enforced.** mochallama only runs tool-capable models. If you pass
> a non-tool model (profile, HF id, or file), the CLI refuses it at load with a
> clear message and exits non-zero — it never silently downgrades. See
> [tool-calling support](/specs/tool-calling-support).

> **Shared catalogue + cache.** The CLI's built-in profiles are the **same
> tool-only set** as the Spring app (`qwen2.5-1.5b` / `qwen2.5-3b` / `qwen3-4b` /
> `phi-4-mini`), and both share one resolver/downloader and the
> `~/.chatbot_models` cache — a GGUF fetched by either side is reused by the other.

## How it works

```
mochallama (npm bin)
  └─ bin/mochallama.js          # Node shim, forwards argv + exit code
       └─ image/bin/mochallama  # jlink launcher (trimmed JRE + JVM args)
            └─ mochallama-core   # Panama FFM bridge → llama.cpp dylibs
```

The launcher runs with `--enable-native-access=ALL-UNNAMED` and
`--add-modules=jdk.incubator.vector` baked in.
