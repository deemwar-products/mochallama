# curl

Every example below talks to the OpenAI-compatible endpoint on the demo `app`
(`POST /v1/chat/completions`, `GET /v1/models`). Start the service first:

```bash
./gradlew :app:bootRun
```

The HTTP port (`8080`) comes up immediately, but the model loads asynchronously.
Until it is `READY` the endpoint returns `503`:

```json
{ "error": "model loading", "state": "DOWNLOADING" }
```

Watch the logs for `state: READY` (or poll `/actuator/health`) before sending
chat requests.

## List models

```bash
curl -s http://localhost:8080/v1/models
```

```json
{ "object": "list", "data": [ { "id": "qwen2.5-1.5b-instruct-q4_k_m", "object": "model" } ] }
```

The `id` is derived from the loaded GGUF filename, so it changes when you switch
[model profiles](/specs/models).

## Chat (non-streaming)

```bash
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "Write a haiku about Project Panama."}
    ],
    "max_tokens": 128,
    "temperature": 0.7
  }'
```

```json
{
  "id": "chatcmpl-…",
  "object": "chat.completion",
  "created": 1716800000,
  "model": "qwen2.5-1.5b-instruct-q4_k_m",
  "choices": [
    {
      "index": 0,
      "message": { "role": "assistant", "content": "Bridges of memory…" },
      "finish_reason": "stop"
    }
  ],
  "usage": { "prompt_tokens": 18, "completion_tokens": 22, "total_tokens": 40 }
}
```

`usage` carries the **real** token counts reported by the native bridge.

## Streaming (SSE)

Pass `"stream": true` and use `curl -N` to disable buffering. The response is
`text/event-stream`: a role chunk, one content chunk per token, a final chunk
with `finish_reason`, then `data: [DONE]`.

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "count 1 to 5"}],
    "stream": true,
    "max_tokens": 32
  }'
```

```
data: {"id":"chatcmpl-…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"1"},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":", 2"},"finish_reason":null}]}

…

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

## Tool calling

Declare `tools[]` in OpenAI shape. When the model decides to call a function,
the reply has `finish_reason: "tool_calls"` and the assistant message carries
`tool_calls[]` instead of (or alongside) `content`.

```bash
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "What is the weather in Paris?"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get the current weather for a location",
        "parameters": {
          "type": "object",
          "properties": { "location": { "type": "string" } },
          "required": ["location"]
        }
      }
    }]
  }'
```

```json
{
  "choices": [{
    "index": 0,
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_0",
        "type": "function",
        "function": { "name": "get_weather", "arguments": "{\"location\":\"Paris\"}" }
      }]
    },
    "finish_reason": "tool_calls"
  }],
  "usage": { "prompt_tokens": 180, "completion_tokens": 22, "total_tokens": 202 }
}
```

`tool_choice` accepts a string (`"auto"` / `"none"` / `"required"`) or an object
form `{"type":"function","function":{"name":"get_weather"}}` (mapped to
`required`). See [Tools & streaming](/examples/04-tools-and-streaming) for the
full round-trip.

## Full parameter set

All sampling parameters are server-side defaults (bound from `llamacpp.model.*`)
overridable per request. A `system` message steers the model — it's just a
`messages` entry with `role: system`.

```bash
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "system", "content": "You are terse. Answer in one short sentence."},
      {"role": "user",   "content": "Explain Project Panama."}
    ],
    "max_tokens": 64,
    "temperature": 0.4,
    "top_k": 20,
    "top_p": 0.9,
    "min_p": 0.05,
    "repeat_penalty": 1.1,
    "seed": 42,
    "stop": ["\n\n"]
  }'
```

| Field            | Type     | Default       |
|------------------|----------|---------------|
| `max_tokens`     | int      | 256           |
| `temperature`    | float    | 0.7           |
| `top_k`          | int      | 40            |
| `top_p`          | float    | 0.95          |
| `min_p`          | float    | 0.05          |
| `repeat_penalty` | float    | 1.0           |
| `seed`           | long     | -1 (random)   |
| `stop`           | string[] | (none)        |
| `stream`         | bool     | false         |

Pin `seed` to a fixed value for reproducible output (with all other sampling
params held constant).
