# Tools & streaming

A fuller walkthrough of the two capabilities that make the local endpoint
useful for agents: **SSE streaming** and **tool / function calling**. For the
design and the exact frame format, see the
[streaming & tools spec](/specs/streaming-and-tools).

> All shipped [model profiles](/specs/models) are **tool-callers** — they ship a
> tool-capable chat template. The default `qwen2.5-1.5b` is the proven
> tool-caller; step up to `qwen2.5-3b` / `qwen3-4b` for stronger results.

## Streaming, end to end

`stream: true` switches the response to `text/event-stream`. The blocking,
`synchronized` bridge call runs on a daemon worker thread
(`mochallamaStreamExecutor`) so the servlet thread is freed immediately. The
frame sequence is:

1. A **role chunk** — `delta.role = "assistant"`, empty content.
2. One **content chunk per token** — `delta.content = "<piece>"`.
3. A **final chunk** — empty delta, `finish_reason` set.
4. `data: [DONE]`.

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "Stream a short limerick about Java."}],
    "stream": true,
    "max_tokens": 96
  }'
```

Consuming it in JavaScript:

```js
const res = await fetch("http://localhost:8080/v1/chat/completions", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    messages: [{ role: "user", content: "Stream a short limerick about Java." }],
    stream: true,
    max_tokens: 96,
  }),
});

const reader = res.body.getReader();
const decoder = new TextDecoder();
let buffer = "";

while (true) {
  const { value, done } = await reader.read();
  if (done) break;
  buffer += decoder.decode(value, { stream: true });

  for (const line of buffer.split("\n")) {
    if (!line.startsWith("data: ")) continue;
    const data = line.slice(6).trim();
    if (data === "[DONE]") continue;
    const chunk = JSON.parse(data);
    const piece = chunk.choices[0].delta.content;
    if (piece) process.stdout.write(piece);
  }
  buffer = buffer.slice(buffer.lastIndexOf("\n") + 1);
}
```

## Tool calling, end to end

The model never executes a tool itself — it **proposes** a call, you run it, and
you feed the result back so the model can produce a final answer. That is the
standard OpenAI tool loop.

### 1. Declare tools and let the model propose a call

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
          "properties": {"location": {"type": "string", "description": "City name"}},
          "required": ["location"]
        }
      }
    }],
    "tool_choice": "auto"
  }'
```

The reply has `finish_reason: "tool_calls"` and the proposed call:

```json
{
  "choices": [{
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
  }]
}
```

`tool_calls` is incremented in the `mochallama.tool_calls` meter when this
happens — see [Metrics](/specs/observability).

### 2. Run the tool and send the result back

Append the assistant message (with its `tool_calls`) and a `tool` message
carrying the result, then call again **without** `tools` to get the final
natural-language answer:

```bash
curl -s -X POST http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "What is the weather in Paris?"},
      {"role": "assistant", "content": null,
       "tool_calls": [{"id": "call_0", "type": "function",
         "function": {"name": "get_weather", "arguments": "{\"location\":\"Paris\"}"}}]},
      {"role": "tool", "tool_call_id": "call_0",
       "content": "{\"temp_c\": 18, \"sky\": \"clear\"}"}
    ]
  }'
```

```json
{
  "choices": [{
    "message": { "role": "assistant", "content": "It's about 18 °C and clear in Paris." },
    "finish_reason": "stop"
  }]
}
```

### Forcing a tool call

`tool_choice` controls whether the model may call a tool:

- `"auto"` (default) — the model decides.
- `"none"` — never call a tool.
- `"required"` — must call a tool.
- `{"type":"function","function":{"name":"get_weather"}}` — object form; mapped
  to `required`.

## Spring AI tool demo

The demo `app` drives the Spring AI tool path directly via `POST /spring-ai/tool-demo`.
It declares a sample `get_weather` tool with `internalToolExecutionEnabled(false)`,
so the proposed call is **surfaced** instead of auto-executed:

```bash
curl -s -X POST http://localhost:8080/spring-ai/tool-demo \
  -H 'Content-Type: application/json' \
  -d '{"message": "What is the weather in Paris?"}'
```

```json
{
  "toolCallRequested": true,
  "finishReason": "TOOL_CALL",
  "toolCalls": [{ "name": "get_weather", "arguments": "{\"location\":\"Paris\"}" }]
}
```

Note: inbound tool declarations flow through the **OpenAI HTTP endpoint**, not
the Spring AI `ChatOptions` adapter — Spring AI's function-definition surface
moved heavily across milestones, so the adapter keeps tool declaration on the
HTTP side (model-emitted tool calls are still surfaced back through Spring AI as
`AssistantMessage.ToolCall`). See the
[streaming & tools spec](/specs/streaming-and-tools) for the rationale.
