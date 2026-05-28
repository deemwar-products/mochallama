package tools.deemwar.mochallama.service;

import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.LlamaException;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.ModelInfo;
import tools.deemwar.mochallama.MochallamaClient;
import tools.deemwar.mochallama.ToolDefinition;
import tools.deemwar.mochallama.Usage;
import tools.deemwar.mochallama.autoconfigure.MochallamaProperties;
import tools.deemwar.mochallama.hf.HuggingFaceModels;
import tools.deemwar.mochallama.hf.HuggingFaceModels.HfModel;
import tools.deemwar.mochallama.panama.ChatEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LlamaCppService implements MochallamaClient {

    public enum LoadState { DOWNLOADING, LOADING, READY, FAILED }

    private final MochallamaProperties props;

    @Getter
    private volatile LoadState state = LoadState.DOWNLOADING;

    @Getter
    private volatile String lastError;

    /** Filename actually used on disk; set once the location is resolved (esp. for hf-id). */
    private volatile String resolvedFilename;

    private volatile ChatEngine engine;

    private final MeterRegistry meterRegistry;

    private final long loadStartedNanos = System.nanoTime();

    @Getter
    private volatile long loadDurationMs = 0L;

    private volatile double tokensPerSecond = 0.0;

    private Timer inferenceTimer;
    private DistributionSummary completionTokens;
    private DistributionSummary promptTokens;

    public LlamaCppService(MeterRegistry meterRegistry, MochallamaProperties props) {
        this.meterRegistry = meterRegistry;
        this.props = props;
    }

    @PostConstruct
    public void init() {
        registerMeters();
        CompletableFuture.runAsync(this::downloadAndLoad)
                .exceptionally(ex -> {
                    state = LoadState.FAILED;
                    lastError = ex.getMessage();
                    log.error("model init failed", ex);
                    return null;
                });
    }

    private void registerMeters() {
        String model = getModelId();
        inferenceTimer = Timer.builder("mochallama.inference.duration")
                .description("Wall-clock time per chat inference call")
                .tag("model", model)
                .register(meterRegistry);
        completionTokens = DistributionSummary.builder("mochallama.tokens.completion")
                .description("Completion tokens generated per chat call (real bridge usage)")
                .tag("model", model)
                .register(meterRegistry);
        promptTokens = DistributionSummary.builder("mochallama.tokens.prompt")
                .description("Prompt tokens consumed per chat call (real bridge usage)")
                .tag("model", model)
                .register(meterRegistry);
        Gauge.builder("mochallama.tokens_per_second", this, LlamaCppService::getTokensPerSecond)
                .description("Generation throughput of the last completed turn")
                .baseUnit("tokens/second")
                .register(meterRegistry);
        Gauge.builder("mochallama.model.state", this, s -> s.getState().ordinal())
                .description("Model load state ordinal: 0=DOWNLOADING 1=LOADING 2=READY 3=FAILED")
                .tag("model", model)
                .register(meterRegistry);
        TimeGauge.builder("mochallama.model.load.duration", this, TimeUnit.MILLISECONDS,
                        LlamaCppService::getLoadDurationMs)
                .description("Elapsed time from load start until the model reached READY")
                .tag("model", model)
                .register(meterRegistry);
    }

    private double getTokensPerSecond() {
        return tokensPerSecond;
    }

    @Override
    public boolean isReady() {
        return state == LoadState.READY;
    }

    public String getModelId() {
        String f = resolvedFilename != null ? resolvedFilename : props.getFilename();
        if (f == null) {
            // hf-id-only config that hasn't resolved yet: report the HF id.
            return props.getHfId() != null ? props.getHfId() : "unknown";
        }
        return f.endsWith(".gguf") ? f.substring(0, f.length() - ".gguf".length()) : f;
    }

    /** The server-side generation defaults (per-request values override these). */
    public GenerationOptions defaultOptions() {
        return props.toGenerationOptions();
    }

    // ---------------------------------------------------------------
    // Simple (back-compat) API
    // ---------------------------------------------------------------

    @Override
    public synchronized String chat(String prompt, int maxTokens, double temperature) {
        GenerationOptions opts = GenerationOptions.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        return chat(List.of(Message.user(prompt)), List.of(), opts).text();
    }

    @Override
    public String chat(String prompt) {
        return chat(prompt, props.getMaxTokens(), props.getTemperature());
    }

    // ---------------------------------------------------------------
    // Rich API — full message list, tools, generation options
    // ---------------------------------------------------------------

    @Override
    public synchronized ChatResult chat(List<Message> messages, List<ToolDefinition> tools,
                                        GenerationOptions opts) {
        return run(messages, tools, opts, null, false);
    }

    @Override
    public synchronized ChatResult chatStream(List<Message> messages, List<ToolDefinition> tools,
                                              GenerationOptions opts, Consumer<String> onToken) {
        return run(messages, tools, opts, onToken, true);
    }

    private ChatResult run(List<Message> messages, List<ToolDefinition> tools,
                           GenerationOptions opts, Consumer<String> onToken, boolean stream) {
        ChatEngine eng = requireReadyEngine();
        long start = System.nanoTime();
        try {
            ChatResult result = (onToken == null)
                    ? eng.chat(messages, tools, opts)
                    : eng.chatStream(messages, tools, opts, onToken);
            recordSuccess(result, System.nanoTime() - start, stream);
            return result;
        } catch (RuntimeException e) {
            inferenceTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            Counter.builder("mochallama.inferences")
                    .tag("model", getModelId())
                    .tag("outcome", "error")
                    .tag("stream", Boolean.toString(stream))
                    .register(meterRegistry).increment();
            throw e;
        }
    }

    private ChatEngine requireReadyEngine() {
        if (!isReady()) {
            throw new IllegalStateException("model not ready: " + state);
        }
        ChatEngine eng = engine;
        if (eng == null) {
            throw new IllegalStateException("engine not initialised");
        }
        return eng;
    }

    /** Record real bridge usage from the {@link ChatResult}. */
    private void recordSuccess(ChatResult result, long elapsedNanos, boolean stream) {
        inferenceTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        Counter.builder("mochallama.inferences")
                .tag("model", getModelId())
                .tag("outcome", "success")
                .tag("stream", Boolean.toString(stream))
                .register(meterRegistry).increment();

        Usage usage = result.usage();
        int promptCount = usage != null ? usage.promptTokens() : 0;
        int completionCount = usage != null ? usage.completionTokens() : 0;
        promptTokens.record(promptCount);
        completionTokens.record(completionCount);

        if (result.hasToolCalls()) {
            Counter.builder("mochallama.tool_calls")
                    .tag("model", getModelId())
                    .register(meterRegistry)
                    .increment(result.toolCalls().size());
        }

        double seconds = elapsedNanos / 1_000_000_000.0;
        tokensPerSecond = seconds > 0 ? completionCount / seconds : 0.0;
    }

    private void downloadAndLoad() {
        try {
            Path modelPath = ensureModelDownloaded();
            state = LoadState.LOADING;
            log.info("loading model from {}", modelPath);
            loadNative(modelPath);
            loadDurationMs = (System.nanoTime() - loadStartedNanos) / 1_000_000L;
            state = LoadState.READY;
            log.info("model ready ({}) in {} ms", getModelId(), loadDurationMs);
        } catch (LlamaException e) {
            state = LoadState.FAILED;
            lastError = "MODEL_NOT_TOOL_CAPABLE".equals(e.code())
                    ? "model does not support tool calling — only tool-capable models are supported"
                    : e.getMessage();
            log.error("model load rejected ({}): {}", e.code(), lastError);
        } catch (IOException e) {
            state = LoadState.FAILED;
            lastError = e.getMessage();
            log.error("model download/load failed", e);
        }
    }

    /**
     * Resolve the configured model location and download it if absent, via the
     * shared {@link HuggingFaceModels}. Resolution precedence:
     * explicit {@code url}+{@code filename} &gt; {@code hf-id}+{@code quant}.
     */
    private Path ensureModelDownloaded() throws IOException {
        Path dir = Paths.get(props.getCacheDir());

        String url = props.getUrl();
        String filename = props.getFilename();
        String hfId = props.getHfId();

        // 1) Explicit url + filename win.
        if (url != null && !url.isBlank() && filename != null && !filename.isBlank()) {
            resolvedFilename = filename;
            return HuggingFaceModels.downloadIfAbsent(url, filename, dir, this::logProgress);
        }

        // 2) hf-id + quant.
        if (hfId != null && !hfId.isBlank()) {
            log.info("resolving Hugging Face model id '{}' (quant {})", hfId, props.getQuant());
            HfModel m = HuggingFaceModels.resolve(hfId, props.getQuant());
            log.info("resolved {} -> {}", hfId, m.fileName());
            resolvedFilename = m.fileName();
            return HuggingFaceModels.downloadIfAbsent(m.resolveUrl(), m.fileName(), dir, this::logProgress);
        }

        throw new IOException(
                "no model configured: set llamacpp.model.hf-id or llamacpp.model.url + .filename");
    }

    private void logProgress(String msg) {
        log.info(msg);
    }

    /**
     * Load the GGUF, enforcing the tool-only policy. A fast {@code inspect()}
     * pre-check gives a clear, cheap failure for non-tool models; the
     * authoritative gate is {@code ChatEngine.load} which throws
     * {@code MODEL_NOT_TOOL_CAPABLE} on its own for non-tool templates.
     */
    private void loadNative(Path modelPath) {
        ModelInfo info = ChatEngine.inspect(modelPath);
        if (info.ok() && !info.toolCapable()) {
            throw new LlamaException("MODEL_NOT_TOOL_CAPABLE",
                    "model does not support tool calling: " + modelPath);
        }
        this.engine = ChatEngine.load(modelPath); // enforces the same gate authoritatively
        log.info("native engine loaded for {}", modelPath);
    }

    @PreDestroy
    public synchronized void shutdown() {
        ChatEngine eng = engine;
        engine = null;
        if (eng != null) {
            try {
                eng.close();
                log.info("native engine closed");
            } catch (RuntimeException e) {
                log.warn("error closing native engine", e);
            }
        }
    }
}
