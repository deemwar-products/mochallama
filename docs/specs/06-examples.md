# Examples — Spec & Verification Plan

Goal: prove the **published** mochallama 0.1.1 artifacts work for a real consumer,
pulling **only from Maven Central** (`io.github.deemwario`) and npm (`@deemwario`) —
no local build, no `mavenLocal()`. Each example is a standalone project under
`examples/<name>/`.

## Published artifacts under test
- Maven Central (live on repo1): `io.github.deemwario:mochallama-core:0.1.2`,
  `:mochallama-core:0.1.2:natives-<os>-<arch>` (5 platforms),
  `:mochallama-core-platform:0.1.2` (aggregator), `:mochallama-spring-boot-starter:0.1.2`,
  `:mochallama-spring-ai:0.1.2`.
- npm: `@deemwario/mochallama` (launcher) + per-platform packages, `0.1.1`.

## Examples

### 1. `examples/java-plain` — plain Java, no Spring
- Gradle, `repositories { mavenCentral() }` ONLY. JDK 22 toolchain.
- Deps: `io.github.deemwario:mochallama-core:0.1.2` + `io.github.deemwario:mochallama-core-platform:0.1.2` (runtimeOnly, natives).
- Code: `try (var engine = ChatEngine.load(Path.of(home, ".chatbot_models", "qwen2.5-1.5b-instruct-q4_k_m.gguf"))) { System.out.println(engine.chat("...", 32, 0.2)); }`
  - `ChatEngine` is in `tools.deemwar.mochallama.panama`. Signature: `static ChatEngine load(Path)`, `String chat(String prompt, int maxTokens, double temperature)`, `AutoCloseable`.
- Run args: `--enable-native-access=ALL-UNNAMED`.

### 2. `examples/spring-boot` — starter + OpenAI endpoint
- Spring Boot 3.3.1, `mavenCentral()`. JDK 22.
- Deps: `io.github.deemwario:mochallama-spring-boot-starter:0.1.2` + `:mochallama-core-platform:0.1.2` (runtimeOnly) + `spring-boot-starter-web`.
- `server.port=8091`. `application.properties`: default qwen2.5-1.5b, cache `~/.chatbot_models`.
- bootRun jvmArgs: `--enable-native-access=ALL-UNNAMED`, `--add-modules=jdk.incubator.vector`.
- Verify: `POST :8091/v1/chat/completions` returns assistant text.

### 3. `examples/spring-ai` — Spring AI ChatClient
- Spring Boot 3.3.1 + `mochallama-spring-ai:0.1.2` + `mochallama-spring-boot-starter:0.1.2` + `:mochallama-core-platform:0.1.2` + `org.springframework.ai:spring-ai-client-chat:1.0.8`.
- `server.port=8092`. A `@RestController /ask` that injects `ChatClient` and returns `chat.prompt().user(p).call().content()`.
- Verify: `POST :8092/ask` returns text.

### 4. `examples/cli` — npm CLI (npx)
- Just docs + a smoke command: `npx @deemwario/mochallama chat` / `mochallama models`.
- Blocked until the npm **launcher** `@deemwario/mochallama` is published (platform pkgs are live; launcher pending).

## Build setup (each Gradle example)
- Copy `gradlew` + `gradle/` wrapper from the repo root into the example.
- `repositories { mavenCentral() }` only — proves Central resolution.
- JDK 22 toolchain (`JavaLanguageVersion.of(22)`).

## Verification criteria
- **Build:** `./gradlew build` resolves `0.1.1` from Maven Central + compiles. ✅/❌
- **Run (model cached at `~/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf`):**
  app starts, loads model, returns a real chat response. ✅/❌
- **CLI:** `npx @deemwario/mochallama` runs (pending launcher publish).
- Results + any errors recorded in `examples/REPORT.md`.

## Process
1. A fan of agents creates + **build-verifies** each Gradle example (resolve from
   Central + compile) in parallel.
2. Runtime verification (run + real response) done sequentially (shared cached
   model, distinct ports) for reliability.
3. npm CLI smoke (when launcher is live).
4. `examples/REPORT.md` synthesizes pass/fail per example.
