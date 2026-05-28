package tools.deemwar.mochallama.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import tools.deemwar.mochallama.GenerationOptions;

/**
 * Bound configuration for the local model, prefix {@code llamacpp.model}.
 *
 * <p>This carries the model location ({@code url} / {@code filename} /
 * {@code cacheDir}), the runtime sizing ({@code contextSize} / {@code threads})
 * and — new in the streaming/tools cut — server-side <em>defaults</em> for the
 * full generation/sampling parameter set. Per-request values from the OpenAI
 * endpoint override these defaults; when a request omits a field, the default
 * here applies. See {@link #toGenerationOptions()}.
 *
 * <p>Defaults mirror {@link GenerationOptions.Builder} so behaviour is identical
 * whether the controller starts from these properties or from
 * {@code GenerationOptions.defaults()}.
 */
@ConfigurationProperties(prefix = "llamacpp.model")
public class MochallamaProperties {

    /** Public GGUF download URL (HuggingFace resolve link). */
    private String url;

    /** On-disk filename for the cached GGUF; also drives the reported model id. */
    private String filename;

    /**
     * Hugging Face model id ({@code org/repo}, e.g.
     * {@code Qwen/Qwen2.5-3B-Instruct-GGUF}) — an ALTERNATIVE to explicit
     * {@code url}/{@code filename}. When set (and no explicit url/filename), the
     * service resolves the {@code .gguf} via the Hub API and downloads it.
     * Resolution precedence: explicit url/filename &gt; hf-id+quant &gt; built-in default.
     */
    private String hfId;

    /** Quant tag to prefer when resolving via {@link #hfId} (e.g. {@code Q4_K_M}). */
    private String quant = "Q4_K_M";

    /** Directory the GGUF is downloaded into / loaded from. */
    private String cacheDir = "${user.home}/.chatbot_models";

    /** Context window the engine is loaded with. */
    private int contextSize = 2048;

    /** Worker threads the native engine uses. */
    private int threads = 4;

    // --- Generation defaults (overridable per request) -----------------------

    private double temperature   = 0.7;
    private int    topK          = 40;
    private double topP          = 0.95;
    private double minP          = 0.05;
    private int    maxTokens     = 256;
    private double repeatPenalty = 1.0;
    /** {@code -1} means "let the native layer pick a random seed". */
    private long   seed          = GenerationOptions.RANDOM_SEED;

    /**
     * Build a {@link GenerationOptions} seeded entirely from these defaults.
     * The controller starts from this and overlays any per-request values.
     */
    public GenerationOptions toGenerationOptions() {
        return GenerationOptions.builder()
                .temperature(temperature)
                .topK(topK)
                .topP(topP)
                .minP(minP)
                .maxTokens(maxTokens)
                .repeatPenalty(repeatPenalty)
                .seed(seed)
                .build();
    }

    public String getUrl()                 { return url; }
    public void   setUrl(String v)         { this.url = v; }
    public String getFilename()            { return filename; }
    public void   setFilename(String v)    { this.filename = v; }
    public String getHfId()                { return hfId; }
    public void   setHfId(String v)        { this.hfId = v; }
    public String getQuant()               { return quant; }
    public void   setQuant(String v)       { this.quant = v; }
    public String getCacheDir()            { return cacheDir; }
    public void   setCacheDir(String v)    { this.cacheDir = v; }
    public int    getContextSize()         { return contextSize; }
    public void   setContextSize(int v)    { this.contextSize = v; }
    public int    getThreads()             { return threads; }
    public void   setThreads(int v)        { this.threads = v; }

    public double getTemperature()             { return temperature; }
    public void   setTemperature(double v)     { this.temperature = v; }
    public int    getTopK()                    { return topK; }
    public void   setTopK(int v)               { this.topK = v; }
    public double getTopP()                    { return topP; }
    public void   setTopP(double v)            { this.topP = v; }
    public double getMinP()                    { return minP; }
    public void   setMinP(double v)            { this.minP = v; }
    public int    getMaxTokens()               { return maxTokens; }
    public void   setMaxTokens(int v)          { this.maxTokens = v; }
    public double getRepeatPenalty()           { return repeatPenalty; }
    public void   setRepeatPenalty(double v)   { this.repeatPenalty = v; }
    public long   getSeed()                    { return seed; }
    public void   setSeed(long v)              { this.seed = v; }
}
