# mochallama Spring Boot example

A standalone Spring Boot app that consumes the published **mochallama 0.1.5** artifacts
from Maven Central (no local build required).

- `io.github.deemwario:mochallama-spring-boot-starter:0.1.5` — auto-configures the
  OpenAI-compatible chat endpoint (`/v1/chat/completions`).
- `io.github.deemwario:mochallama-core-platform:0.1.5` — pulls in the native
  llama.cpp binaries for your platform at runtime.

The model is downloaded on first run from the URL in `application.properties` and
cached under `~/.chatbot_models`.

## Run

```sh
export JAVA_HOME=/Users/muthuishere/Library/Java/JavaVirtualMachines/temurin-22.0.1/Contents/Home
./gradlew bootRun
```

Then call the OpenAI-compatible endpoint:

```sh
curl -sS -X POST localhost:8091/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"hi"}],"max_tokens":32}'
```
