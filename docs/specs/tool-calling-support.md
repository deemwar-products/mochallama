# Tool-calling support: detection spec & HF fetch/verify flow

**Goal.** mochallama serves tool/function calling
(`/v1/chat/completions` with `tools`). We want to (A) reliably detect whether a
GGUF supports tool calling, ENFORCE that only tool-capable models load, and (B)
let users pull models from Hugging Face by ID, verifying tool support before
(or as part of) accepting them.

This document is the spec the implementation will follow. Every llama.cpp claim
below is cited to the **vendored** source at tag `b9371`
(`core/src/main/native/llama.cpp/`, HEAD `f12cc6d0`, `git describe` =>
`b9371`). Every HF claim was verified against the live API on 2026-05-28.

> Note on the b9371 chat stack. This tag is **not** the classic
> `common_chat` of 2024. The old hardcoded `common_chat_format` enum
> (Hermes2Pro, Llama3.x, FunctionaryV3, Mistral-Nemo, Command-R, DeepSeek, …)
> is **gone**. b9371 ships a rewritten stack: a Jinja engine (`common/jinja/`),
> a **differential autoparser** (`common/chat-auto-parser*.cpp`) and a **PEG
> parser** (`common/chat-peg-parser*.cpp`). Tool-call *parsing* is now generated
> per-template at runtime; the format enum is just a coarse parser-family tag.
> The authoritative tool-*capability* signal lives elsewhere — see §A.

---

## A. How tool-calling support is actually determined

### A.1 The authoritative signal: `jinja::caps`

At b9371 the capability of a chat template is computed by **probing** the
template: render it with a sample tool + tool-call conversation and observe
whether the `tools` / `tool_calls` Jinja variables are actually *used* during
rendering. If the template never reads `tools[0].function.name`, it cannot
describe tools to the model => `supports_tools = false`.

The struct (`common/jinja/caps.h:10-28`):

```c++
struct caps {
    bool supports_tools                = true;  // template renders the `tools` list
    bool supports_tool_calls           = true;  // template renders assistant `tool_calls`
    bool supports_system_role          = true;
    bool supports_parallel_tool_calls  = true;
    bool supports_preserve_reasoning   = false;
    bool supports_string_content       = true;
    bool supports_typed_content        = false;
    bool supports_object_arguments     = false;
    std::map<std::string, bool> to_map() const;   // for server /props
};
caps caps_get(jinja::program & prog);
```

`caps_get` (`common/jinja/caps.cpp:91-477`) runs a series of probes via
`caps_try_execute`. The relevant one (`caps.cpp:163-252`): it builds a sample
conversation with one `assistant` `tool_calls` entry + a `tool` response, and a
`tools` array describing `tool1`, renders the template with
`ctx.is_get_stats = true`, then inspects usage stats:

- `caps.cpp:232-237` — if `tools[0].function.name` was **not used** during
  render => `result.supports_tools = false`.
- `caps.cpp:239-243` — if `messages[1].tool_calls` was **not used** =>
  `result.supports_tool_calls = false`.
- `caps.cpp:246-250` — sets `supports_object_arguments` based on whether the
  arguments are read as an object.

This is computed **once, at template construction**: the
`common_chat_template` ctor calls `caps_get(prog)` and stores the result
(`common/chat.h:59-79`, field `chat_template_caps caps;`,
`original_caps()` accessor).

**This is the most reliable detection given our stack.** It does not rely on a
substring match or a model name; it observes the template's actual behaviour
under the same Jinja engine used at inference.

### A.2 How to query it post-load (the exact API)

`common/chat.h:304`:

```c++
std::map<std::string, bool> common_chat_templates_get_caps(
    const common_chat_templates * chat_templates);
```

Implementation (`common/chat.cpp:2627-2631`): returns
`chat_templates->template_default->caps.to_map()`. The map contains the
`supports_tools` / `supports_tool_calls` keys above. This is exactly what
llama-server reports on `/props`
(`tools/server/server-context.cpp:3511`, and used for
`parallel_tool_calls` defaulting at `tools/server/server-common.cpp:1032`,
`:1041`).

**=> `supports_tools == true` (and `supports_tool_calls == true`) is the
capability gate.** `supports_tool_calls` covers emitting/round-tripping calls;
`supports_tools` covers describing tools to the model. We require **both**.
(The runtime even warns when `supports_tool_calls && !supports_tools`:
`common/chat.cpp:2416-2420`.)

### A.3 Where the template comes from (GGUF metadata)

`common_chat_templates_init` (`common/chat.cpp:655-753`) builds the templates
from the **model's GGUF metadata**:

- default: `llama_model_chat_template(model, nullptr)` (`chat.cpp:665`)
- tool-use variant: `llama_model_chat_template(model, "tool_use")`
  (`chat.cpp:670`) — stored as `template_tool_use`
  (`common/chat.cpp:287`).

`llama_model_chat_template` (`src/llama-model.cpp:2455-2472`) reads GGUF KV:

- default key: `tokenizer.chat_template`
- named key (`name = "tool_use"`): `tokenizer.chat_template.tool_use`

(KV name table: `src/llama-arch.cpp:320`,
`{ LLM_KV_TOKENIZER_CHAT_TEMPLATE, "tokenizer.chat_template" }`; the named
variant is `LLM_KV(arch, name)(...)` at `llama-model.cpp:2456`.) If the GGUF has
**no** `tokenizer.chat_template` at all, init falls back to the bundled ChatML
template (`chat.cpp:678-684`, `CHATML_TEMPLATE_SRC`), which is **not**
tool-capable — so such a model correctly fails the gate.

> Caveat: `common_chat_templates_get_caps` reports caps of
> `template_default`. A model with a tool-less default template **and** a
> separate `tokenizer.chat_template.tool_use` would report
> `supports_tools=false` on the default even though it can do tools via the
> variant. For mochallama's purposes (single OpenAI endpoint) the practical fix
> is: if a `tool_use` variant exists, build caps from it. See §C.2.

### A.4 The format enum at b9371 (for completeness)

`common/chat.h:169-178`:

```c++
enum common_chat_format {
    COMMON_CHAT_FORMAT_CONTENT_ONLY,  // no tool parsing
    COMMON_CHAT_FORMAT_PEG_SIMPLE,    // PEG-parsed
    COMMON_CHAT_FORMAT_PEG_NATIVE,    // PEG-parsed
    COMMON_CHAT_FORMAT_PEG_GEMMA4,    // PEG-parsed (gemma4 mapper)
    COMMON_CHAT_FORMAT_COUNT,
};
```

Names: `common_chat_format_name` (`chat.cpp:755-768`) =>
`"Content-only"`, `"peg-simple"`, `"peg-native"`, `"peg-gemma4"`.

**Important:** the format is produced by `common_chat_templates_apply` *after*
you pass it tools (`common/chat.cpp:2541`, jinja path
`common_chat_templates_apply_jinja`), and reflects which *parser* was generated,
not whether the model supports tools. `CONTENT_ONLY` means "no tool parsing for
this call", but a tool-capable model called *without* tools also yields a
content-only parser. **Do NOT use the format enum as the capability gate** — use
`caps.supports_tools` (§A.2). The format enum is useful only for
diagnostics/telemetry (which parser family fired).

### A.5 How llama-server gates tools (the closest authoritative precedent)

llama-server does **not** hard-reject non-tool models. It gates on
`--jinja`: with tools in the request and no `--jinja`, it throws
`"tools param requires --jinja flag"`
(`tools/server/server-common.cpp:923-930`). It then reads caps via
`common_chat_templates_get_caps` (`server-common.cpp:1032`) and *warns* (does
not fail) if the template can't natively describe tools
(`common/chat.cpp:2416-2420`). It exposes caps on `/props`
(`server-context.cpp:3511`) and the raw templates on `/props`
(`chat_template`, `chat_template_tool_use`:
`server-context.cpp:4072-4103`).

mochallama's policy is **stricter than the server**: we *enforce*
tool-capability at load. The server's caps query is the mechanism we reuse; the
enforcement is our addition.

---

## B. Web-verified conventions (2025–2026)

### B.1 HF chat-template tool convention

The `tools` / `tool_calls` Jinja variable names are the **standard API**, per HF
transformers docs (verified 2026-05-28):

- `tools` is "a list of tools in JSON schema format … we highly recommend
  sticking to convention and using `tools`."
- Tool calls are passed in the `tool_calls` key of an `assistant` message; tool
  responses are `role: "tool"` messages.
- A tool-aware template guards with `{%- if tools %}` and iterates
  `tools[].function.name` / `.parameters` — exactly the variables the
  `caps_get` probe checks.

Source: <https://huggingface.co/docs/transformers/main/en/chat_templating_writing>
(sections "Templates for tools", "Tool definitions", "Tool calls").

A model **can** ship multiple named chat templates — typically one default and
one `tool_use` — stored as a list of `{name, template}` in
`tokenizer_config.json`; the convert script propagates the `tool_use` one to the
GGUF key `tokenizer.chat_template.tool_use`. (Confirmed by llama.cpp wiki /
DeepWiki and matched by the vendored `llama-model.cpp:2456`.)
Sources: <https://github.com/ggml-org/llama.cpp/wiki/Templates-supported-by-llama_chat_apply_template>,
<https://deepwiki.com/ggml-org/llama.cpp/3.9-chat-templates-and-message-parsing>.

### B.2 GGUF embeds the template; HF parses it server-side

GGUF embeds the chat template as metadata key `tokenizer.chat_template` (and the
`...tool_use` variant). llama.cpp reads it (§A.3). **Verified live:** the HF Hub
API parses the GGUF header and re-exposes selected metadata under a top-level
`gguf` object (`?expand=gguf`), including `chat_template`, `bos_token`,
`eos_token`, `architecture`, `context_length`. This is the basis of the cheap
pre-download check (§B.4).

### B.3 OpenAI tools / tool_calls schema (request/response shape — already used)

Request `tools[]`: `{"type":"function","function":{"name","description",
"parameters": <JSON Schema>}}`. Response `tool_calls[]`:
`{"id","type":"function","function":{"name","arguments": <string>}}` with
`finish_reason: "tool_calls"`. This already matches
`docs/specs/streaming-and-tools.md` and the OpenAI Chat Completions spec. No
change needed.

### B.4 Cheap pre-download template inspection — FEASIBLE (with one caveat)

`GET https://huggingface.co/api/models/{id}?expand=gguf` returns
`json.gguf.chat_template` **without downloading the GGUF**. Verified across the
shipped lineup on 2026-05-28:

| repo | `gguf.chat_template` present | template references `tools` | refs `tool_calls` literally |
|------|------|------|------|
| `Qwen/Qwen2.5-1.5B-Instruct-GGUF` | yes | yes | yes |
| `Qwen/Qwen2.5-3B-Instruct-GGUF`   | yes | yes | yes |
| `unsloth/Qwen3-4B-Instruct-2507-GGUF` | yes | yes | yes |
| `unsloth/Phi-4-mini-instruct-GGUF` | yes | yes | **no** (uses `tools` only) |
| `bartowski/Qwen2.5-3B-Instruct-GGUF` | yes | yes | yes |
| `NousResearch/Hermes-2-Pro-Mistral-7B-GGUF` | yes (196 chars) | **no** | **no** |

**Two caveats, both important:**

1. **Naive substring matching is unreliable.** Phi-4-mini references `tools`
   but never the literal `tool_calls` — yet it *is* tool-capable. A substring
   check would mis-rank it. The HF field is fine as a *cheap heuristic* but is
   **not** authoritative.
2. **HF exposes only ONE template (the default `tokenizer.chat_template`).**
   Hermes-2-Pro's `gguf.chat_template` is a 196-char ChatML stub with no tools;
   its tool template lives in `tokenizer.chat_template.tool_use`, which the HF
   `gguf` field does **not** surface. So the pre-download check can produce a
   **false negative** for models whose tool support is in the named variant.

**Conclusion:** pre-download inspection via HF `?expand=gguf` is feasible and
useful as a *fast pre-filter / UX hint*, but the **post-load `jinja::caps`
probe is the source of truth.** Do not reject solely on the pre-download
heuristic; reject only on the post-load gate (or accept the false-negative risk
explicitly if avoiding a download). The per-file GGUF KV is **not** exposed by
the tree API (verified: `/tree/main` entries have no `gguf` field), so there is
no cheap way to read a *specific* quant's template short of a GGUF range-read.

---

## C. Recommendation: the design the implementation will follow

### C.1 Most reliable detection (post-load) — reuse, don't reinvent

On load, after `common_chat_templates_init(model, "")`, call
`common_chat_templates_get_caps(tmpls)` and read `supports_tools` &
`supports_tool_calls`. Gate = **both true**.

### C.2 Bridge ABI addition

The current ABI is the 5-symbol surface in `docs/specs/02-bridge-abi.md`
(`llb_chat_create` / `llb_chat_infer` / `llb_string_free` / `llb_chat_destroy`
/ `llb_version`). Add a model-info query so Java can enforce before committing
to a model, and so it is reported on `/v1/models`. Two options; **prefer (b)**.

**(a) Minimal boolean**

```c
/* 1 = model's chat template supports tool calling, 0 = not, -1 = error. */
int llb_model_supports_tools(const char* gguf_path);
```

**(b) Model-info JSON (recommended)** — richer, future-proof, mirrors the
caps map and the existing "JSON in / JSON out" ABI style:

```c
/* Heap JSON describing a GGUF without creating an engine.
 * Caller frees via llb_string_free. Never NULL (errors as error-JSON). */
const char* llb_model_info(const char* gguf_path);
```

returning:

```jsonc
{
  "type": "model_info",
  "supports_tools": true,          // caps.supports_tools && caps.supports_tool_calls
  "caps": {                        // verbatim common_chat_templates_get_caps()
    "supports_tools": true, "supports_tool_calls": true,
    "supports_parallel_tool_calls": true, "supports_system_role": true,
    "supports_typed_content": false, "supports_object_arguments": true,
    "supports_preserve_reasoning": false, "supports_string_content": true
  },
  "chat_format": "peg-native",     // common_chat_format_name(...) — diagnostic only
  "has_tool_use_template": false,  // true if tokenizer.chat_template.tool_use present
  "architecture": "qwen2",
  "n_params": 1543714304
}
```

Native impl notes:
- Build `caps` from the **`tool_use` variant if present**, else the default
  (addresses §A.3 caveat). Detect presence via
  `llama_model_chat_template(model, "tool_use") != nullptr`, or check
  `tmpls->template_tool_use`.
- `chat_format`: derive by calling `common_chat_templates_apply` with a
  one-tool probe input and reading `params.format` via
  `common_chat_format_name`. Diagnostic only — not the gate.
- This needs a lightweight model load (no context). Loading just the model
  (`llama_model_load_from_file`) is enough to read GGUF KV + build templates;
  skip `llama_init_from_model`. Keep it cheap.

Also: bake the gate into `llb_chat_create` so a non-tool model **cannot**
produce a usable engine (§C.3) — `llb_model_info` is the pre-flight; the
create-time check is the hard stop.

### C.3 Enforcement (hard rule)

In `llb_chat_create`, after templates init + caps query: if
`!(supports_tools && supports_tool_calls)`, emit event
`create_failure:tools_unsupported`, return `NULL`. (Mirror the existing failure
events in `docs/specs/02-bridge-abi.md`.) The Java facade surfaces a clear
error, e.g.:

```
Model <id/filename> rejected: its chat template does not support tool calling
(supports_tools=false). mochallama only loads tool-capable GGUFs.
```

This makes the `docs/specs/models.md` "Tool-callers only" policy
machine-enforced instead of curated-by-hand.

### C.4 HF-by-ID fetch + verify flow

1. **Resolve ID -> file list.**
   `GET https://huggingface.co/api/models/{id}` -> `siblings[].rfilename`;
   filter `*.gguf`. (Verified: returns `siblings` for GGUF repos.)
2. **Gating pre-check (fail gracefully).** Read `json.gated` from the same
   response (verified values: `false`, `"manual"`, `"auto"`). If gated and no
   token configured, fail early with a clear "model is gated, set HF token"
   message — don't attempt the download. (Anonymous resolve of a gated file
   returns **401**; verified on `meta-llama/Llama-3.2-1B-Instruct`.)
3. **Cheap pre-filter (optional UX).**
   `GET .../api/models/{id}?expand=gguf` -> `gguf.chat_template`. If present and
   it references `tools`, it's *likely* tool-capable; if it's a short ChatML
   stub, *warn* (could still have a `tool_use` variant). **Heuristic only** —
   never the final reject (§B.4).
4. **Pick the quant.** Default convention **`Q4_K_M`**: match
   `(?i)q4_k_m` among the `.gguf` siblings (matches every shipped profile in
   `docs/specs/models.md`). Fallbacks if absent, in order:
   `q5_k_m -> q4_0 -> q8_0 -> q6_k -> q3_k_m -> q2_k`; if a single `.gguf`
   exists, take it; if multi-part (`*-00001-of-000NN.gguf`), pick the first
   shard's base (multi-part download is out of scope for v1 — flag and reject).
   Allow an explicit `filename` override.
5. **Construct resolve URL.**
   `https://huggingface.co/{id}/resolve/{rev}/{file}` (`rev` defaults to
   `main`). Verified: public files 302 -> 200 via the xet bridge
   (`X-Xet-Cas-Uid=public`); gated files 401. Send
   `Authorization: Bearer <HF_TOKEN>` when configured.
6. **Download** into the model cache (`llamacpp.model.cache-dir`,
   default `~/.chatbot_models/`).
7. **Post-load verify (authoritative).** `llb_model_info` (or the
   `create_failure:tools_unsupported` path of `llb_chat_create`). If
   `supports_tools == false` -> reject, delete/quarantine the file, surface the
   reason. Else accept and load.

### C.5 Gated / auth handling — summary

| Signal | Where | Verified value | Action |
|--------|-------|----------------|--------|
| `gated` | `api/models/{id}` JSON | `false` / `"manual"` / `"auto"` | if not `false` and no token -> fail early |
| `private` | same | `true`/`false`/`null` | `true` w/o token -> fail |
| resolve HTTP 401 | `…/resolve/{rev}/{file}` | 401 anon on gated | map to "auth required / accept license" |
| resolve HTTP 403 | same | (license not accepted) | "accept the model license on HF" |
| resolve 302->200 | same | public xet | proceed |

HF token: read from `HF_TOKEN` env / config; never log it; never persist into
the cache dir.

---

## D. Open items / could-not-fully-verify

- **`gguf.chat_template` as a list.** Every repo probed returned a **string**
  (never a JSON list of named templates) in the HF `gguf` field, even for
  multi-template models — HF surfaces only the default. So the pre-download
  check is structurally blind to `tool_use`-variant-only models (§B.4 caveat
  2). Could not find an HF API field that exposes named template variants.
- **GGUF range-read of a specific quant's template** (to read the exact file's
  `tokenizer.chat_template` without full download) is *theoretically* possible
  (GGUF KV is in the header; HTTP Range is supported by the xet bridge) but was
  **not** verified end-to-end and is not recommended for v1 — the post-load
  probe is simpler and authoritative.
- **`chat_format` for the JSON** requires running a one-tool `apply` probe at
  info time; confirmed the API exists (`common_chat_templates_apply` +
  `common_chat_format_name`) but the exact format value per model was not
  enumerated (it is diagnostic, not the gate, so this is low-risk).

---

## Cited llama.cpp sources (vendored, tag b9371)

- `common/jinja/caps.h:10-30` — `struct caps`, `caps_get`.
- `common/jinja/caps.cpp:21-53` — `caps_try_execute` probe harness.
- `common/jinja/caps.cpp:163-252` — tool-support probe (sets `supports_tools` /
  `supports_tool_calls`).
- `common/chat.h:59-79` — `common_chat_template` ctor calls `caps_get`,
  `original_caps()`.
- `common/chat.h:169-178` — `enum common_chat_format`.
- `common/chat.h:304` / `common/chat.cpp:2627-2631` —
  `common_chat_templates_get_caps`.
- `common/chat.cpp:655-753` — `common_chat_templates_init` (reads GGUF
  templates incl. `tool_use`).
- `common/chat.cpp:755-768` — `common_chat_format_name`.
- `common/chat.cpp:2416-2420` — runtime warning when tool_calls without tools.
- `common/chat.cpp:2541-2546` — `common_chat_templates_apply`.
- `src/llama-model.cpp:2455-2472` — `llama_model_chat_template` (KV
  `tokenizer.chat_template[.<name>]`).
- `src/llama-arch.cpp:320` — `LLM_KV_TOKENIZER_CHAT_TEMPLATE` name.
- `tools/server/server-common.cpp:923-930, 1032, 1041` — server `--jinja` gate
  + caps usage.
- `tools/server/server-context.cpp:3511, 4072-4103` — `/props` caps + raw
  templates.

## Cited web sources (verified 2026-05-28)

- HF chat-template + tools convention:
  <https://huggingface.co/docs/transformers/main/en/chat_templating_writing>
- llama.cpp named templates / `tool_use`:
  <https://github.com/ggml-org/llama.cpp/wiki/Templates-supported-by-llama_chat_apply_template>,
  <https://deepwiki.com/ggml-org/llama.cpp/3.9-chat-templates-and-message-parsing>
- HF Hub model API (`siblings`, `gated`, `gguf.chat_template`):
  `GET https://huggingface.co/api/models/{id}[?expand=gguf]` (live-verified)
- Resolve URL behaviour: `https://huggingface.co/{id}/resolve/{rev}/{file}`
  (live-verified: 302->200 public, 401 gated)
