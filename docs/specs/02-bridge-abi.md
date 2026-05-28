# mochallama — Bridge ABI

The Java side never sees `llama.h`. Everything goes through five symbols
exported by `libllamabridge.dylib`, declared in
`src/main/cpp/include/llamabridge.h`:

```c
/*
 * llamabridge.h
 *
 * Thin C ABI over llama.cpp for use from Java (Panama FFM).
 *
 * Design goals:
 *   - Pure C surface, no C++ name-mangling, easy to bind from any FFI.
 *   - Opaque handle pattern so the Java side never sees llama.cpp types.
 *   - Strings cross the boundary as malloc'd UTF-8 — caller frees via
 *     llb_string_free. This avoids requiring callers to allocate
 *     output buffers up front and keeps ownership obvious.
 *   - Single-call inference: request JSON in, response JSON out.
 *
 * Build artefact: libllamabridge.dylib (linked against libllama.dylib).
 */

#ifndef LLAMABRIDGE_H
#define LLAMABRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

/* Opaque chat handle. */
typedef struct llb_chat llb_chat_t;

/* Event callback. Invoked by the bridge with short, NUL-terminated event
 * strings during model load and inference. Pointer valid only for the
 * duration of the call. user_data is forwarded verbatim. May be NULL. */
typedef void (*llb_event_cb)(const char* event, void* user_data);

/* Load a GGUF chat model and create an inference engine.
 * Returns NULL on any failure. */
llb_chat_t* llb_chat_create(const char* gguf_path,
                            llb_event_cb event_cb,
                            void* user_data);

/* Run a single chat inference. Returns a heap-allocated JSON string the
 * caller MUST release via llb_string_free. Never returns NULL — errors
 * are reported as error JSON. */
const char* llb_chat_infer(llb_chat_t* chat, const char* request_json);

/* Release a string previously returned by llb_chat_infer.
 * Safe to call on NULL. */
void llb_string_free(const char* s);

/* Tear down an engine. Safe to call on NULL. */
void llb_chat_destroy(llb_chat_t* chat);

/* Static, NUL-terminated build identity string. Do NOT free. */
const char* llb_version(void);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* LLAMABRIDGE_H */
```

## Request JSON shape (input to `llb_chat_infer`)

```json
{
  "messages": [
    {"role": "system",    "content": "..."},
    {"role": "user",      "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "max_tokens":  512,
  "temperature": 0.8
}
```

- `messages` is required and must be non-empty.
- `max_tokens` defaults to 512 if missing or `<= 0`.
- `temperature` defaults to 0.8; clamped to `>= 0.0`.
- The C parser is minimal: it understands top-level string/int/float fields
  and an array of `{role, content}` objects. Escapes supported in strings:
  `\\`, `\"`, `\/`, `\n`, `\r`, `\t`, `\b`, `\f`, and `\uXXXX` (no
  surrogate-pair handling).

## Response JSON shape (output from `llb_chat_infer`)

Success:

```json
{
  "type": "assistant_text",
  "text": "...",
  "usage": {
    "prompt_tokens":     42,
    "completion_tokens": 128,
    "total_tokens":      170
  }
}
```

Failure:

```json
{
  "type":  "error",
  "error": {
    "code":    "INVALID_REQUEST" | "ENGINE_CLOSED" | "INFERENCE_FAILED" | "INTERNAL_BRIDGE_ERROR",
    "message": "human-readable detail"
  }
}
```

The bridge never returns a NULL pointer from `llb_chat_infer`. Hard failures
that can't even allocate an error envelope are reported by returning the
NULL-like fallback path inside the Java facade (`ChatEngine` throws if the
returned address is `MemorySegment.NULL`).

## Ownership and lifetime

- **Strings returned by `llb_chat_infer`** are allocated with `malloc()` by
  the bridge. The caller MUST release them via `llb_string_free`. The
  `const` qualifier on the return type is for the caller's convenience, not
  a real qualifier on the storage. The Java side does this immediately
  after copying the bytes into a Java `String` (`LlamaBridge.readCString`).
- **`llb_version`** returns a pointer to a static `char[]`. Do NOT free it.
- **`llb_chat_create`** returns a pointer to an opaque heap struct (or
  NULL). The caller MUST eventually call `llb_chat_destroy` on it.
- **The `gguf_path` and `request_json` C strings passed in** are read but
  not retained — the bridge copies what it needs. They may be freed
  immediately after the call returns. The Java facade uses a confined
  `Arena` per call for the request JSON and a long-lived confined arena for
  the model path.

## Event callback

`llb_event_cb` is currently unused by the Java side (`ChatEngine` passes
`NULL`), but the C side emits these events when a callback is provided:

| Phase  | Event string             | Meaning                                   |
|--------|--------------------------|-------------------------------------------|
| create | `create_start`           | Entered `llb_chat_create`                 |
| create | `create_failure:null_path` | `gguf_path` was NULL                    |
| create | `create_failure:model_not_found` | `fopen` of the path failed        |
| create | `create_failure:oom`     | Allocation failure                        |
| create | `create_failure:load_model` | `llama_model_load_from_file` failed    |
| create | `create_failure:init_context` | `llama_init_from_model` failed       |
| create | `create_success`         | Engine fully constructed                  |
| infer  | `infer_start`            | Entered `llb_chat_infer`                  |
| infer  | `infer_success`          | Response built and ready to return        |
| infer  | `infer_failure`          | Generation or response build aborted      |
| close  | `destroy`                | Entering `llb_chat_destroy`               |

The pointer is valid only for the duration of the callback. `user_data` is
forwarded verbatim from `llb_chat_create`.

## Threading

The bridge does not lock. `llama.cpp` itself is not safe for concurrent use
on a single context, and the bridge stores `model` + `ctx` directly in the
opaque handle. Callers must serialise:

- All calls to `llb_chat_infer` on the same handle.
- `llb_chat_destroy` must not race with any in-flight `llb_chat_infer`.

The Java side enforces this two ways:

- `ChatEngine` documents itself as not thread-safe.
- `LlamaCppService.chat(...)` is `synchronized`, so all HTTP traffic
  serialises through one monitor.

## Build identity

`llb_version()` returns a fixed-format string:

```
llamabridge <bridge-version> (llama.cpp <tag>)
```

`<bridge-version>` and `<tag>` are baked in at compile time by
`target_compile_definitions(... LLB_BRIDGE_VERSION=... LLB_LLAMA_TAG=...)`
in `CMakeLists.txt`. The vendored llama.cpp tag is currently `b9371`.
