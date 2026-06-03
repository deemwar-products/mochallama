# java-plain

A minimal, plain-Java example (no Spring, no framework) that consumes the published
**mochallama 0.1.1** artifacts from Maven Central.

It uses `ChatEngine` from `mochallama-core` to load a local GGUF model and run a single
chat completion. The native llama.cpp libraries are pulled in transitively at runtime via
`mochallama-core-platform` (the platform-specific natives bundle) — no local build of
mochallama is required.

## Dependencies

```
implementation 'io.github.deemwario:mochallama-core:0.1.1'      // ChatEngine API
runtimeOnly    'io.github.deemwario:mochallama-core-platform:0.1.1'  // native libs
```

Both are resolved from Maven Central (`mavenCentral()`).

## Run

The example expects the model cached at `~/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf`.

```bash
export JAVA_HOME=/Users/muthuishere/Library/Java/JavaVirtualMachines/temurin-22.0.1/Contents/Home
./gradlew run
```

Requires Java 22 (the build is configured with a toolchain for Java 22) and the
`--enable-native-access=ALL-UNNAMED` JVM arg, which the `application` plugin applies
automatically via `applicationDefaultJvmArgs`.
