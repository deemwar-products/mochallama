# Observability

The `mochallama-spring-boot-starter` ships inference visibility out of the box
via Spring Boot Actuator + Micrometer. Adding the starter to a Spring Boot app
auto-configures a model health indicator and a set of inference meters — no
extra wiring required.

The starter declares `spring-boot-starter-actuator` as an `implementation`
dependency, so Actuator (and Micrometer core) arrive transitively. This is an
accepted trade-off for this project: Actuator/Micrometer are Spring infra and do
not pin a Spring AI version, so the starter stays version-resilient. (To make it
optional instead, switch the dependency to `compileOnly` and gate the metric
registration on `@ConditionalOnClass(MeterRegistry.class)`.)

## Actuator endpoints

The demo `app` exposes these in `application.properties`:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

| Endpoint                                  | Purpose                                   |
|-------------------------------------------|-------------------------------------------|
| `GET /actuator/health`                    | Aggregate health incl. `mochallama` group |
| `GET /actuator/metrics`                   | List of all meter names                   |
| `GET /actuator/metrics/{name}`            | A single meter's measurements             |
| `GET /actuator/prometheus`               | Prometheus scrape (opt-in, see below)     |

## Health indicator

`tools.deemwar.mochallama.actuate.MochallamaHealthIndicator` reflects model load
progress so probes and dashboards can gate on real inference availability.

| Load state    | Health |
|---------------|--------|
| `DOWNLOADING` | DOWN   |
| `LOADING`     | DOWN   |
| `READY`       | UP     |
| `FAILED`      | DOWN (adds `error` detail = last error message) |

Details on every response: `model` (id), `state`, `loadDurationMs`.

It is auto-configured by `MochallamaActuatorAutoConfiguration`, gated
`@ConditionalOnClass(HealthIndicator.class)` and
`@ConditionalOnBean(LlamaCppService.class)`.

## Meters

All inference meters are registered by `LlamaCppService` against the injected
`MeterRegistry`. Naming is uniform: `mochallama.<area>.<thing>`, and every meter
is tagged `model=<modelId>` (the GGUF filename without its `.gguf` suffix) where
it makes sense (the process-wide `tokens_per_second` gauge is the only untagged
one).

| Meter                            | Type                  | Tags                                                  | Meaning |
|----------------------------------|-----------------------|-------------------------------------------------------|---------|
| `mochallama.inferences`          | Counter               | `model`, `outcome=success\|error`, `stream=true\|false` | One increment per `chat`/`chatStream` call |
| `mochallama.tool_calls`          | Counter               | `model`                                               | Incremented by the number of tool calls the model emitted in a turn |
| `mochallama.inference.duration`  | Timer                 | `model`                                               | Wall-clock time per inference call |
| `mochallama.tokens.completion`   | DistributionSummary   | `model`                                               | **Real** completion tokens per call (from `ChatResult.usage`) |
| `mochallama.tokens.prompt`       | DistributionSummary   | `model`                                               | **Real** prompt tokens per call (from `ChatResult.usage`) |
| `mochallama.tokens_per_second`   | Gauge                 | —                                                     | Throughput of the last completed turn |
| `mochallama.model.state`         | Gauge                 | `model`                                               | `LoadState.ordinal()`: 0=DOWNLOADING 1=LOADING 2=READY 3=FAILED |
| `mochallama.model.load.duration` | TimeGauge (ms)        | `model`                                               | Elapsed time from load start until READY |

### Real token usage

Token counts are now the **exact** values reported by the native bridge, read
from `ChatResult.usage()` (`promptTokens` / `completionTokens` / `totalTokens`)
after every successful inference. The earlier `length / 4` approximation is gone
— both the `mochallama.tokens.*` summaries and the OpenAI endpoint's `usage`
block carry real counts.

The `stream` tag on `mochallama.inferences` distinguishes streaming
(`chatStream`, SSE) from non-streaming calls. `mochallama.tool_calls` only moves
when the model actually requests tools (counted per call by the number of calls
in `ChatResult.toolCalls()`).

`mochallama.tokens_per_second` is computed per successful turn as
`completionTokens / elapsedSeconds` (real completion tokens) and held in a
volatile field the gauge reads; it reflects the last turn only.

## Examples

```bash
# Health (DOWN while loading, UP once READY)
curl -s localhost:8080/actuator/health

# Inference timer
curl -s localhost:8080/actuator/metrics/mochallama.inference.duration

# Inference counter (filter to streaming successes)
curl -s 'localhost:8080/actuator/metrics/mochallama.inferences?tag=outcome:success&tag=stream:true'

# Tool-call counter
curl -s localhost:8080/actuator/metrics/mochallama.tool_calls

# Real prompt-token distribution
curl -s localhost:8080/actuator/metrics/mochallama.tokens.prompt

# Last-turn throughput
curl -s localhost:8080/actuator/metrics/mochallama.tokens_per_second
```

### Sample output

`GET /actuator/health` once the model is `READY` (note the `mochallama`
component carries `model` / `state` / `loadDurationMs` details):

```json
{
  "status": "UP",
  "components": {
    "mochallama": {
      "status": "UP",
      "details": {
        "model": "qwen2.5-1.5b-instruct-q4_k_m",
        "state": "READY",
        "loadDurationMs": 1843
      }
    }
  }
}
```

While the model is still loading the same component is `DOWN` (so the aggregate
status is `DOWN`), with `state` = `DOWNLOADING` or `LOADING`; on a load failure
it is `DOWN` with an extra `error` detail.

`GET /actuator/metrics/mochallama.inference.duration` — the inference timer, with
its `count`, `total`, and `max` statistics and the available `model` tag:

```json
{
  "name": "mochallama.inference.duration",
  "baseUnit": "seconds",
  "measurements": [
    { "statistic": "COUNT", "value": 7.0 },
    { "statistic": "TOTAL_TIME", "value": 18.42 },
    { "statistic": "MAX", "value": 3.91 }
  ],
  "availableTags": [
    { "tag": "model", "values": ["qwen2.5-1.5b-instruct-q4_k_m"] }
  ]
}
```

`GET /actuator/metrics/mochallama.tokens_per_second` — the last-turn throughput
gauge (untagged):

```json
{
  "name": "mochallama.tokens_per_second",
  "measurements": [ { "statistic": "VALUE", "value": 12.4 } ],
  "availableTags": []
}
```

## Enabling Prometheus

Prometheus support is opt-in. The starter declares
`micrometer-registry-prometheus` as `compileOnly`, so consumers add it to light
up the scrape endpoint:

```gradle
runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
```

Then `GET /actuator/prometheus` returns all `mochallama_*` meters in Prometheus
text format (already exposed in the include list above).

## Demo web UI

`app`'s web UI shows a compact live footer stat that polls
`/actuator/metrics/mochallama.tokens_per_second` and `mochallama.model.state`
every 3s, rendering `x.x tok/s · model: <id> · <state>`.
