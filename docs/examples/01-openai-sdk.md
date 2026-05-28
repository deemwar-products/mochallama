# OpenAI SDK (Python)

Because the endpoint speaks the OpenAI wire format, the official `openai` Python
SDK works unchanged — just point `base_url` at the local service. No API key is
required (mochallama ignores it), but the SDK insists on one, so pass any
placeholder.

```bash
pip install openai
```

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8080/v1",
    api_key="not-needed",   # placeholder; mochallama ignores it
)
```

The model id must match what `GET /v1/models` reports (derived from the loaded
GGUF filename), or you can omit it — the server falls back to the loaded model.

## Chat

```python
resp = client.chat.completions.create(
    model="qwen2.5-1.5b-instruct-q4_k_m",
    messages=[
        {"role": "system", "content": "You are terse."},
        {"role": "user", "content": "Write a haiku about Project Panama."},
    ],
    max_tokens=128,
    temperature=0.7,
)

print(resp.choices[0].message.content)
print(resp.usage)  # real prompt/completion/total token counts
```

## Streaming

```python
stream = client.chat.completions.create(
    model="qwen2.5-1.5b-instruct-q4_k_m",
    messages=[{"role": "user", "content": "count 1 to 5"}],
    max_tokens=32,
    stream=True,
)

for chunk in stream:
    delta = chunk.choices[0].delta
    if delta.content:
        print(delta.content, end="", flush=True)
print()
```

The SDK consumes the SSE frames (role chunk, content chunks, final
`finish_reason` chunk, `[DONE]`) for you.

## Tool calling

Declare tools and let the model propose a call. mochallama surfaces the proposed
call back to you (it does not auto-execute); you run the function and send the
result back as a `tool` message for the model to finish.

```python
tools = [{
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "Get the current weather for a location",
        "parameters": {
            "type": "object",
            "properties": {"location": {"type": "string"}},
            "required": ["location"],
        },
    },
}]

messages = [{"role": "user", "content": "What is the weather in Paris?"}]

resp = client.chat.completions.create(
    model="qwen2.5-1.5b-instruct-q4_k_m",
    messages=messages,
    tools=tools,
)

choice = resp.choices[0]
if choice.finish_reason == "tool_calls":
    call = choice.message.tool_calls[0]
    print(call.function.name, call.function.arguments)
    # → get_weather {"location":"Paris"}

    # Execute the tool yourself, then feed the result back:
    messages.append(choice.message)
    messages.append({
        "role": "tool",
        "tool_call_id": call.id,
        "content": '{"temp_c": 18, "sky": "clear"}',
    })
    followup = client.chat.completions.create(
        model="qwen2.5-1.5b-instruct-q4_k_m",
        messages=messages,
    )
    print(followup.choices[0].message.content)
```

See [Tools & streaming](/examples/04-tools-and-streaming) for the mechanics of
the round-trip and which models reliably emit tool calls.
