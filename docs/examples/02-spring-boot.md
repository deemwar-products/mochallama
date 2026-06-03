# Spring Boot

Add the starter and you get an autoconfigured local model service, the
OpenAI-compatible endpoint, and Actuator metrics + health — no manual wiring.
Add `mochallama-spring-ai` (plus a Spring AI version) and you also get an
autoconfigured `ChatModel` / `ChatClient`.

## Add the dependency

```groovy
dependencies {
    implementation 'io.github.deemwario:mochallama-spring-boot-starter:0.1.2'

    // Native llama.cpp libs ship SEPARATELY (the core jar is Java-only).
    // Easiest — all platforms' natives, the right one loads at runtime:
    runtimeOnly 'io.github.deemwario:mochallama-core-platform:0.1.2'
    // ...OR lean: just your deploy platform's classifier (smaller), e.g.:
    // runtimeOnly 'io.github.deemwario:mochallama-core:0.1.2:natives-linux-x86_64'

    // Optional — Spring AI ChatModel / ChatClient adapter.
    // Spring AI is compileOnly in the adapter, so you pin the version here.
    implementation 'io.github.deemwario:mochallama-spring-ai:0.1.2'
    implementation 'org.springframework.ai:spring-ai-client-chat:1.0.8'
}
```

The starter pulls in `mochallama-core` (the Java engine). As of v0.1.0 the
native libs are **not** in the core jar — they ship as separate per-platform
artifacts so you download only what you need:

- `mochallama-core-platform` — zero-config, bundles **all** platforms' natives
  (Intel/Apple-Silicon macOS, x86-64/ARM64 Linux, x86-64 Windows); the matching
  one loads at runtime. Simplest (~40 MB of natives).
- `mochallama-core:<ver>:natives-<os>-<arch>` — lean, just your deploy platform
  (e.g. `natives-linux-x86_64`, `natives-linux-aarch64`). ~8–12 MB.

The starter does **not** drag in Spring AI — that stays version-resilient and optional.

## JVM args

The Panama FFM bridge needs JDK 22 and native-access enabled:

```
--enable-native-access=ALL-UNNAMED
--add-modules=jdk.incubator.vector
```

With the Spring Boot Gradle plugin:

```groovy
bootRun {
    jvmArgs = ['--enable-native-access=ALL-UNNAMED', '--add-modules=jdk.incubator.vector']
}
```

## Configure the model

Model location, sizing, and generation defaults bind from the `llamacpp.model.*`
prefix (`MochallamaProperties`). Per-request values on the OpenAI endpoint
override these.

```properties
# Model location — explicit url + filename
llamacpp.model.url=https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf
llamacpp.model.filename=qwen2.5-1.5b-instruct-q4_k_m.gguf
llamacpp.model.cache-dir=${user.home}/.chatbot_models

# Runtime sizing
llamacpp.model.context-size=4096
llamacpp.model.threads=4

# Generation defaults (overridable per request)
llamacpp.model.max-tokens=256
llamacpp.model.temperature=0.7
llamacpp.model.top-k=40
llamacpp.model.top-p=0.95
llamacpp.model.min-p=0.05
llamacpp.model.repeat-penalty=1.0
llamacpp.model.seed=-1

# Disable the bundled OpenAI controller if you only want the service beans:
# mochallama.openai-endpoint.enabled=false
```

### Load any model by Hugging Face id

Instead of an explicit `url` + `filename`, point the starter at a **tool-capable
Hugging Face GGUF repo** by id; it resolves the right `.gguf` (preferred quant
`Q4_K_M`) via the Hub API and downloads it into the shared cache:

```properties
llamacpp.model.hf-id=Qwen/Qwen2.5-3B-Instruct-GGUF
# optional, defaults to Q4_K_M
llamacpp.model.quant=Q4_K_M
```

**Resolution precedence:** explicit `url`+`filename` > `hf-id`+`quant` > the
built-in default. Only tool-capable models load — a non-tool model is rejected
at load time and `/actuator/health` reports the service DOWN/FAILED with
*"model does not support tool calling — only tool-capable models are supported"*.
Gated/private repos fail early unless an `HF_TOKEN` is set (and the license
accepted on Hugging Face). See [tool-calling support](/specs/tool-calling-support).

To switch among the bundled profiles without editing properties, activate a
profile (`qwen2.5-1.5b` / `qwen2.5-3b` / `qwen3-4b` / `phi-4-mini`):

```bash
./gradlew bootRun --args='--spring.profiles.active=qwen2.5-3b'
```

## Inject the ChatClient

With `mochallama-spring-ai` on the classpath, a `ChatClient` and `ChatModel` are
autoconfigured over the local model:

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

@RestController
class AssistantController {

    private final ChatClient chat;

    AssistantController(ChatClient chat) {
        this.chat = chat;
    }

    @PostMapping("/ask")
    String ask(@RequestBody String prompt) {
        return chat.prompt().user(prompt).call().content();
    }
}
```

Or inject the lower-level `ChatModel` directly (it implements both
`call(Prompt)` and `stream(Prompt) -> Flux<ChatResponse>`):

```java
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

@RestController
class StreamController {

    private final ChatModel model;

    StreamController(ChatModel model) {
        this.model = model;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    Flux<String> stream(@RequestBody String prompt) {
        return model.stream(new Prompt(prompt))
                .map(r -> r.getResult().getOutput().getText());
    }
}
```

## Framework-free path

If you do not want Spring AI at all, the starter still gives you the
`MochallamaClient` bean (implemented by `LlamaCppService`):

```java
import tools.deemwar.mochallama.MochallamaClient;

@Service
class Summarizer {
    private final MochallamaClient client;
    Summarizer(MochallamaClient client) { this.client = client; }

    String summarize(String text) {
        if (!client.isReady()) throw new IllegalStateException("model still loading");
        return client.chat("Summarize: " + text, 200, 0.5);
    }
}
```

The model service comes up immediately and loads asynchronously, so guard calls
with `client.isReady()` (or gate on `/actuator/health`). See [Metrics](/specs/observability)
for the health indicator semantics and the inference meters the starter
registers automatically.
