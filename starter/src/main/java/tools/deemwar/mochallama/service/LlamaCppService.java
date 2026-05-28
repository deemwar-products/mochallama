package tools.deemwar.mochallama.service;

import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.MochallamaClient;
import tools.deemwar.mochallama.ToolDefinition;
import tools.deemwar.mochallama.Usage;
import tools.deemwar.mochallama.autoconfigure.MochallamaProperties;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class LlamaCppService implements MochallamaClient {

    public enum LoadState { DOWNLOADING, LOADING, READY, FAILED }

    private static final long PROGRESS_LOG_BYTES = 10L * 1024 * 1024;

    private final MochallamaProperties props;

    @Getter
    private volatile LoadState state = LoadState.DOWNLOADING;

    @Getter
    private volatile String lastError;

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
        String f = props.getFilename();
        if (f == null) return "unknown";
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
        } catch (IOException e) {
            state = LoadState.FAILED;
            lastError = e.getMessage();
            log.error("model download/load failed", e);
        }
    }

    private Path ensureModelDownloaded() throws IOException {
        Path dir = Paths.get(props.getCacheDir());
        Files.createDirectories(dir);
        Path target = dir.resolve(props.getFilename());
        if (Files.exists(target)) {
            log.info("model present at {} ({} bytes)", target, Files.size(target));
            return target;
        }

        Path partial = dir.resolve(props.getFilename() + ".partial");
        URL url = URI.create(props.getUrl()).toURL();
        HttpURLConnection head = (HttpURLConnection) url.openConnection();
        head.setRequestMethod("HEAD");
        long expectedSize = head.getContentLengthLong();
        head.disconnect();
        log.info("downloading {} ({} bytes) -> {}", props.getUrl(), expectedSize, partial);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(partial)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            long nextLog = PROGRESS_LOG_BYTES;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= nextLog) {
                    log.info("download progress: {} MB", total / (1024 * 1024));
                    nextLog += PROGRESS_LOG_BYTES;
                }
            }
            log.info("download complete: {} bytes", total);
        } finally {
            conn.disconnect();
        }

        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private void loadNative(Path modelPath) {
        this.engine = ChatEngine.load(modelPath);
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
