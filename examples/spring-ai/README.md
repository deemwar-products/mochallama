# Spring AI + mochallama example

A standalone Spring Boot app that pulls the mochallama Spring AI adapter from
Maven Central (`io.github.deemwario:*:0.1.5`). The starter + adapter
autoconfigure a Spring AI `ChatClient` backed by a local llama.cpp model — no
external LLM service required. The GGUF model is downloaded on first run to
`~/.chatbot_models`.

## Run

```
export JAVA_HOME=/path/to/temurin-22
./gradlew bootRun
```

Then ask it something:

```
curl -sS -X POST localhost:8092/ask -H 'Content-Type: text/plain' -d 'hello in 5 words'
```
