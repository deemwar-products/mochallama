# CLI

`@deemwar/mochallama` is a terminal CLI that runs a local llama.cpp model
straight from the command line. It is JVM-powered (Project Panama FFM bridge,
same `mochallama-core` engine as the Spring app) but shipped as a self-contained
**jlink runtime image** — a trimmed JRE plus the native dylibs — so you do
**not** need a JDK installed.

> **v0.1.0 is macOS (x64) only.** The bundled image ships the `darwin-x86_64`
> native dylibs; `npm` refuses to install on other platforms (via the `os`/`cpu`
> fields) rather than hand you a broken binary. Linux and Apple-silicon bundles
> publish later.

## Install

```bash
npm i -g @deemwar/mochallama
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
llama-3.2-1b    Llama-3.2-1B-Instruct-Q4_0.gguf           ~700 MB    no
llama-3.2-3b    Llama-3.2-3B-Instruct-Q4_0.gguf           ~1.8 GB    no
qwen3.5-4b      Qwen3-4B-Instruct-2507-Q4_K_M.gguf        ~2.5 GB    no
gemma-4-e4b     gemma-3n-E4B-it-Q4_K_M.gguf               ~4.5 GB    no
phi-4-mini      Phi-4-mini-instruct-Q4_K_M.gguf           ~2.5 GB    no

Cache dir: /Users/you/.chatbot_models
```

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

You can also pass a path to a local `.gguf` file instead of a profile name, and
tune the turn:

```bash
mochallama chat --model ~/models/my-model.gguf --max-tokens 512 --temperature 0.4
```

Options: `-m`/`--model` (profile name or `.gguf` path), `--max-tokens`,
`--temperature`. Run `mochallama --help` for the full listing.

> **Note on profile names.** The CLI's built-in catalogue uses its own profile
> ids (shown above by `mochallama models`), which differ from the Spring app's
> profile ids (`qwen2.5-1.5b` / `qwen2.5-3b` / `qwen3-4b` / `phi-4-mini`). Both
> share the same `~/.chatbot_models` cache, so a GGUF fetched by either side is
> reused by the other.

## How it works

```
mochallama (npm bin)
  └─ bin/mochallama.js          # Node shim, forwards argv + exit code
       └─ image/bin/mochallama  # jlink launcher (trimmed JRE + JVM args)
            └─ mochallama-core   # Panama FFM bridge → llama.cpp dylibs
```

The launcher runs with `--enable-native-access=ALL-UNNAMED` and
`--add-modules=jdk.incubator.vector` baked in.
