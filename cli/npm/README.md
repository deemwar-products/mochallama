# @deemwarhq/mochallama

Run a local [llama.cpp](https://github.com/ggerganov/llama.cpp) LLM from your
terminal. The engine is JVM-powered — `mochallama-core` bridges llama.cpp via
[Project Panama](https://openjdk.org/projects/panama/) (the Foreign Function &
Memory API), no Python and no separate llama.cpp install required.

This npm package is a thin **cross-platform launcher**. The actual payload — a
self-contained `jlink` runtime image (a trimmed JRE plus the native llama.cpp
libraries) — ships in a per-platform companion package that `npm` installs
automatically for your OS/CPU. You do **not** need a JDK installed to use it.

## Platform support

Installs and runs on:

| platform               | companion package                     |
|------------------------|---------------------------------------|
| macOS Intel (x64)      | `@deemwarhq/mochallama-darwin-x64`    |
| macOS Apple Silicon    | `@deemwarhq/mochallama-darwin-arm64`  |
| Linux (x64)            | `@deemwarhq/mochallama-linux-x64`     |
| Windows (x64)          | `@deemwarhq/mochallama-win32-x64`     |

`npm` pulls in only the companion matching your host (via each companion's
`os`/`cpu` fields); the others are skipped. The launcher resolves the right one
at runtime.

## Install / run

```sh
# One-shot via npx — drops straight into a chat session.
npx @deemwarhq/mochallama

# Or install globally.
npm i -g @deemwarhq/mochallama
```

## Usage

```sh
# Bare invocation starts chatting immediately (defaults to `chat`).
mochallama

# List available model profiles and whether they're cached locally.
mochallama models

# Chat with a specific model (downloads it on first use into ~/.chatbot_models).
mochallama chat --model llama-3.2-1b

# Help.
mochallama --help
```

## How it works

```
mochallama (this npm bin)
  └─ bin/mochallama.js                        # cross-platform launcher
       └─ @deemwarhq/mochallama-<platform>    # resolved via os/cpu
            └─ image/bin/mochallama           # jlink launcher (trimmed JRE + JVM args)
                 └─ mochallama-core           # Panama FFM bridge → llama.cpp natives
```

The jlink launcher runs with `--enable-native-access=ALL-UNNAMED` and
`--add-modules=jdk.incubator.vector` baked in. A bare invocation with no
arguments forwards `chat` so you start talking to a model right away.

## Building / publishing (maintainers)

The companion packages' `image/` directories are **not** committed — they're
produced by Gradle (`:cli:jlink`) and copied in before publishing. Locally
(macOS x64), from the repo root:

```sh
task cli:npm:pack      # builds the jlink image, stages it into the darwin-x64
                       # companion + the main package, then `npm pack` both
```

All four platforms' images are produced by the GitHub Actions release matrix on
a `v*` tag — see `.github/workflows/release.yml`.

## License

MIT
