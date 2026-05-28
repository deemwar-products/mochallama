# @deemwar/mochallama

Run a local [llama.cpp](https://github.com/ggerganov/llama.cpp) LLM from your
terminal. The engine is JVM-powered — `mochallama-core` bridges llama.cpp via
[Project Panama](https://openjdk.org/projects/panama/) (the Foreign Function &
Memory API), no Python and no separate llama.cpp install required.

This npm package is a thin launcher around a **self-contained `jlink` runtime
image**: a trimmed JRE plus the native llama.cpp dylibs, all bundled inside the
package. You do **not** need a JDK installed to use it.

## Platform support

> **v0.1.0 is macOS (x64) only.**
>
> The bundled runtime image ships the `darwin-x86_64` native llama.cpp dylibs
> from `mochallama-core`. `npm` will refuse to install on other platforms (via
> the `os`/`cpu` fields) rather than hand you a broken binary. Linux and
> Apple-silicon builds will publish later as separate image bundles. This is an
> honest single-platform release, not a cross-platform promise.

## Install

```sh
npm i -g @deemwar/mochallama
```

## Usage

```sh
# List available model profiles and whether they're cached locally.
mochallama models

# Chat with a model (downloads it on first use into ~/.chatbot_models).
mochallama chat --model llama-3.2-1b

# Help.
mochallama --help
```

## How it works

```
mochallama (this npm bin)
  └─ bin/mochallama.js        # Node shim, forwards argv + exit code
       └─ image/bin/mochallama  # jlink launcher (trimmed JRE + JVM args)
            └─ mochallama-core   # Panama FFM bridge → llama.cpp dylibs
```

The launcher runs with `--enable-native-access=ALL-UNNAMED` and
`--add-modules=jdk.incubator.vector` baked in.

## Building / publishing (maintainers)

The `image/` directory is **not** committed — it's produced by Gradle and copied
in at pack time. From the repo root:

```sh
task cli:npm:pack      # builds the jlink image, copies it into cli/npm/image, npm pack
task cli:npm:publish   # the above, then `npm publish --access public`
```

## License

Apache-2.0
