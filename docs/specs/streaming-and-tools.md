# Streaming, tool calling & the generation parameter set

The OpenAI-compatible endpoint (`POST /v1/chat/completions`, served by the
starter's `ChatCompletionsController`) now exposes the full native capability
surface: SSE streaming, tool calling, real token usage, and the complete
sampling parameter set including a system prompt.

## Request parameters

`messages` (with `role` `system` / `user` / `assistant` / `tool`) plus:

| Field             | Type            | Default | Maps to (`GenerationOptions`) |
|-------------------|-----------------|---------|-------------------------------|
| `max_tokens`      | int             | 256     | `maxTokens`                   |
| `temperature`     | float           | 0.7     | `temperature`                 |
| `top_k`           | int             | 40      | `topK`                        |
| `top_p`           | float           | 0.95    | `topP`                        |
| `min_p`           | float           | 0.05    | `minP`                        |
| `repeat_penalty`  | float           | 1.0     | `repeatPenalty`               |
| `seed`            | long            | -1 (random) | `seed`                    |
| `stop`            | string[]        | (none)  | `stop`                        |
| `stream`          | bool            | false   | SSE vs JSON                   |
| `tools`           | object[]        | (none)  | `List<ToolDefinition>`        |
| `tool_choice`     | string\|object  | auto    | `toolChoice`                  |

Defaults are server-side, bound from `llamacpp.model.*`
(`MochallamaProperties`). A request field overrides the matching default; an
omitted field falls back to it. The system prompt is just a
`messages[role=system]` entry — the native chat template renders it.

`tool_choice`: a string (`"auto"` / `"none"` / `"required"`) passes through; an
object form (`{"type":"function","function":{"name":...}}`) is mapped to
`"required"`.

## Non-streaming response

Real usage, and — when the model emits tool calls — OpenAI-shaped `tool_calls`:

```jsonc
{
  "id": "chatcmpl-…", "object": "chat.completion", "created": 1716800000,
  "model": "qwen2.5-1.5b-instruct-q4_k_m",
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_0", "type": "function",
        "function": { "name": "get_weather", "arguments": "{\"location\":\"Paris\"}" }
      }]
    },
    "finish_reason": "tool_calls"
  }],
  "usage": { "prompt_tokens": 180, "completion_tokens": 22, "total_tokens": 202 }
}
```

When there are no tool calls, `message.content` is the reply and
`finish_reason` is `"stop"` (or whatever the bridge reports). `usage` always
carries the **real** bridge token counts.

## Streaming (`stream: true`) — SSE

`Content-Type: text/event-stream`. The controller returns an `SseEmitter`; the
blocking, `synchronized` bridge call runs on a daemon worker thread
(`mochallamaStreamExecutor`) so the servlet container thread is freed
immediately. Each decoded token piece is forwarded as a chunk.

Frame sequence:

1. A role chunk: `delta.role = "assistant"`.
2. One content chunk per token: `delta.content = "<piece>"`.
3. A final chunk with `finish_reason` and an empty delta.
4. `data: [DONE]`.

```
data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":…,"model":"…","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk",…,"choices":[{"index":0,"delta":{"content":"1"},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk",…,"choices":[{"index":0,"delta":{"content":", 2"},"finish_reason":null}]}

…

data: {"id":"chatcmpl-…","object":"chat.completion.chunk",…,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

### Examples

```bash
# Streaming
curl -N -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"count 1 to 5"}],"stream":true,"max_tokens":32}'

# Tool call
curl -s -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages":[{"role":"user","content":"What is the weather in Paris?"}],
    "tools":[{"type":"function","function":{
      "name":"get_weather",
      "description":"Get the current weather for a location",
      "parameters":{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}
    }}]
  }'

# System prompt + full params + reproducible seed
curl -s -X POST localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages":[{"role":"system","content":"You are terse."},{"role":"user","content":"hi"}],
    "max_tokens":16, "top_k":20, "top_p":0.9, "seed":42
  }'
```

## Spring AI adapter

`MochallamaChatModel` (in `mochallama-spring-ai`) implements both
`ChatModel.call(Prompt)` and `ChatModel.stream(Prompt) -> Flux<ChatResponse>`.
`stream` bridges the blocking `chatStream` callback onto a `Flux` published on
`Schedulers.boundedElastic()`, emitting one `ChatResponse` chunk per token and a
final chunk carrying the finish reason.

Spring AI `ChatOptions` map to `GenerationOptions`: `temperature`, `topK`,
`topP`, `maxTokens`, `stopSequences`. Model-emitted tool calls are surfaced back
to Spring AI via `AssistantMessage.ToolCall`. **Inbound** tool/function
declarations are not mapped through the Spring AI adapter: Spring AI 1.0.0-M2's
`ChatOptions` has no portable function-definition surface and the function/tool
APIs moved heavily across milestones, so to keep this adapter resilient across
Spring AI versions (spring-ai-core stays `compileOnly`) inbound tool declaration
is left to the OpenAI HTTP endpoint, which models it natively.
