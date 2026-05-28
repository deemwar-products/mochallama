# Related reading & sources

Sources used to build `00-landscape.md` and `01-positioning.md`. Verified on
2026-05-28 unless otherwise noted.

## Comparison-matrix sources

- de.kherud:llama
  - <https://github.com/kherud/java-llama.cpp>
  - <https://central.sonatype.com/artifact/de.kherud/llama> (latest `4.2.0`)
- JLama
  - <https://github.com/tjake/Jlama>
  - <https://central.sonatype.com/artifact/com.github.tjake/jlama-core> (latest `0.8.4`)
- Llama3.java (mukel)
  - <https://github.com/mukel/llama3.java>
- Utilitron/LlamaFFM
  - <https://github.com/Utilitron/LlamaFFM>
  - <https://github.com/Utilitron/LlamaFFM-SpringAI>
- nixiesearch/llamacpp-server-java
  - <https://github.com/nixiesearch/llamacpp-server-java>
  - <https://central.sonatype.com/artifact/ai.nixiesearch/llamacpp-server-java> (latest `0.0.4-b5604`)

## llama.cpp upstream

- <https://github.com/ggml-org/llama.cpp>
- GGUF spec: <https://github.com/ggml-org/ggml/blob/master/docs/gguf.md>

## Panama FFM (JEP 454, GA in JDK 22)

- JEP 454: <https://openjdk.org/jeps/454>
- JDK 22 `java.lang.foreign` Javadoc:
  <https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html>
- `--enable-native-access` background:
  <https://openjdk.org/jeps/472>

## Spring Boot

- 3.3.1 release notes:
  <https://github.com/spring-projects/spring-boot/releases/tag/v3.3.1>

## OpenAI wire format (what we mimic)

- Chat Completions reference:
  <https://platform.openai.com/docs/api-reference/chat>
- Models list reference:
  <https://platform.openai.com/docs/api-reference/models/list>

## Background reading on JNI vs FFM

- "Foreign Function & Memory API – a (quick) peek under the hood"
  (Maurizio Cimadamore talk index): <https://openjdk.org/projects/panama/>
- Tagir Valeev, "Foreign Function & Memory API in action" (JetBrains blog):
  <https://blog.jetbrains.com/idea/2023/06/jep-442-foreign-function-memory-api-third-preview/>
  (preview-era; FFM finalised in JEP 454 / JDK 22 — read with that caveat)

## Comparable projects worth tracking (not in the matrix)

- LangChain4j — high-level Java AI orchestration; can sit on top of any
  of the row entries above. <https://github.com/langchain4j/langchain4j>
- Spring AI — Spring's official AI abstraction; consumes OpenAI-compatible
  endpoints, which makes mochallama drop-in usable.
  <https://github.com/spring-projects/spring-ai>
