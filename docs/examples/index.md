# Examples

Practical, copy-pasteable ways to drive mochallama. Every example runs against
the demo `app` on `http://localhost:8080` — start it with `./gradlew :app:bootRun`
and wait for the model to reach `state: READY`.

- [curl](/examples/00-curl) — chat, streaming, tool calling, and the full
  sampling parameter set straight from the shell.
- [OpenAI SDK (Python)](/examples/01-openai-sdk) — the official `openai` client
  with `base_url` pointed at the local endpoint (chat + stream + tools).
- [Spring Boot](/examples/02-spring-boot) — add the starter, inject the
  autoconfigured `ChatClient` / `ChatModel`, configure `llamacpp.model.*`.
- [CLI](/examples/03-cli) — `mochallama models` / `mochallama chat` via the npm
  package `@deemwario/mochallama`.
- [Tools & streaming](/examples/04-tools-and-streaming) — the full tool-calling
  round-trip and SSE consumption walkthrough.

For the inference meters and health indicator, see [Metrics](/specs/observability).
