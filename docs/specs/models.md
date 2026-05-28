# Model profiles

**Tool-callers only.** The lineup is deliberately limited to GGUFs that ship a
tool-capable chat template, because the service exposes tool/function calling
(`/v1/chat/completions` with `tools`). Models without a reliable tool template
(notably the Gemma GGUFs, and Llama-3.2 1B/3B which are only partial) were
dropped.

`application.properties` ships with **Qwen2.5-1.5B-Instruct (Q4_K_M)** as the
default. It is chosen because it is the *proven* tool-caller in this lineup —
the native agent's tool test passed on this exact GGUF — and it is also the
smallest/fastest kept model (~1.1 GB), so first boot is quick. Step up to
`qwen2.5-3b` for stronger quality with the same Qwen2.5 tool template.

To switch models, activate a Spring profile that points `llamacpp.model.url` /
`llamacpp.model.filename` at a different GGUF.

```sh
./gradlew bootRun --args='--spring.profiles.active=qwen2.5-3b'
```

Profiles available today (all tool-capable):

- `qwen2.5-1.5b` (same as default, explicit)
- `qwen2.5-3b`
- `qwen3-4b` (renamed from the old `qwen3.5-4b` id — same model)
- `phi-4-mini`

The first run downloads the GGUF into `~/.chatbot_models/` (or whatever
`llamacpp.model.cache-dir` points at) and reuses it after that. The model id
reported on `GET /v1/models` is derived from the filename, so switching
profiles also switches the OpenAI-compatible model id.

## Lineup

| Profile        | HF repo                               | Filename                              | Size on disk | RAM (approx) | Gen tok/s (CPU, approx) | Tool calling | Notes |
|----------------|---------------------------------------|---------------------------------------|--------------|--------------|-------------------------|--------------|-------|
| `qwen2.5-1.5b` | `Qwen/Qwen2.5-1.5B-Instruct-GGUF`     | `qwen2.5-1.5b-instruct-q4_k_m.gguf`   | 1.1 GB       | ~2 GB        | ~12                     | Yes (proven) | **Default.** Smallest + fastest kept model and the proven tool-caller (native agent tool test passed on this file). Qwen2.5 instruct chat template ships tool support. |
| `qwen2.5-3b`   | `Qwen/Qwen2.5-3B-Instruct-GGUF`       | `qwen2.5-3b-instruct-q4_k_m.gguf`     | 2.1 GB       | ~3 GB        | ~7                      | Yes          | Quality step up from the 1.5B; same Qwen2.5 tool template. |
| `qwen3-4b`     | `unsloth/Qwen3-4B-Instruct-2507-GGUF` | `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`  | 2.5 GB       | ~4.5 GB      | ~9.8 (measured)         | Yes          | Strongest general-purpose 4B in the lineup. Strong tool calling (91.4% BFCL in benchmarks/results/qwen3.5-4b_std.json). Bench id is historical; the model is "Qwen3". |
| `phi-4-mini`   | `unsloth/Phi-4-mini-instruct-GGUF`    | `Phi-4-mini-instruct-Q4_K_M.gguf`     | 2.5 GB       | ~3.5 GB      | ~10.6 (measured)        | Yes          | Fastest 4B-class in the lineup. Phi-4-mini ships a tool-capable chat template; the earlier note about `--jinja` breakage applied to a stock-CLI flag path the native bridge does not use. |

### Dropped (not tool-capable / unreliable)

| Former profile | Why dropped |
|----------------|-------------|
| `gemma-4-e4b` (`unsloth/gemma-3n-E4B-it-GGUF`) | Gemma GGUFs generally lack a tool-calling chat template. Also the highest RAM cost in the old lineup. |
| `llama-3.2-1b` (`mukel/Llama-3.2-1B-Instruct-GGUF`) | Only partial tool support; not reliable enough to emit tool-call JSON. |
| `llama-3.2-3b` (`mukel/Llama-3.2-3B-Instruct-GGUF`) | Same partial tool support as the 1B. |

### How the numbers were chosen

- **Size on disk** is the HF-reported file size for the listed `.gguf`.
- **RAM** is a rule-of-thumb: Q4 weights × ~1.3 plus the KV cache for
  `context-size`. Real usage will be higher for longer contexts.
- **Gen tok/s** marked *measured* comes from
  `/llama-cpu-benchmarks/results/<cell>_std.json` (single-stream, stock
  `ghcr.io/ggml-org/llama.cpp:full`, Q4_K_M, fp16 KV, AVX2 CPU). The 1B / 3B
  numbers are extrapolated rules of thumb (1B ≈ 7 tok/s, 3B ≈ 3 tok/s on a
  typical 4-core desktop CPU) — not measured here.
- All numbers are approximate. Your CPU, RAM, and thread count matter more
  than the model.

### Tuning per profile

Each profile sets `llamacpp.model.context-size` and `llamacpp.model.max-tokens`
appropriate for the model (smaller context on the 1.5B to keep RAM low; larger
on the 4B-class models that benefit from it). The full generation default set
(`temperature`, `top-k`, `top-p`, `min-p`, `repeat-penalty`, `seed`,
`max-tokens`) is bound from `llamacpp.model.*` in the default
`application.properties` and overridden per request by the OpenAI endpoint — see
`streaming-and-tools.md`.

### Coordinates verified

All four shipped GGUF resolve URLs were HEAD-checked for anonymous download:
each returns `302 → 200` via HuggingFace's public xet-bridge
(`X-Xet-Cas-Uid=public`). The Qwen2.5 defaults come from the official
`Qwen/Qwen2.5-*-Instruct-GGUF` repos (single-file `q4_k_m`); `qwen3-4b` and
`phi-4-mini` come from `unsloth/*` (the `bartowski/*` equivalents 401 on
anonymous fetch). No gated/private models are referenced in any shipped profile
— the project cannot prompt for HF auth tokens at startup.
