package tools.deemwar.mochallama;

import java.util.ArrayList;
import java.util.List;

/**
 * The full generation / sampling parameter set passed to the native bridge.
 *
 * <p>Defaults: temperature 0.7, topK 40, topP 0.95, minP 0.05, maxTokens 256,
 * repeatPenalty 1.0 (disabled), seed {@code -1} (random), no stop strings,
 * tool choice {@code "auto"}.
 *
 * <p>Build via {@link #builder()}; use {@link #defaults()} for the defaults.
 */
public final class GenerationOptions {

    /** Sentinel seed meaning "let the native layer pick a random seed". */
    public static final long RANDOM_SEED = -1L;

    private final double       temperature;
    private final int          topK;
    private final double       topP;
    private final double       minP;
    private final int          maxTokens;
    private final double       repeatPenalty;
    private final long         seed;
    private final List<String> stop;
    private final String       toolChoice;

    private GenerationOptions(Builder b) {
        this.temperature   = b.temperature;
        this.topK          = b.topK;
        this.topP          = b.topP;
        this.minP          = b.minP;
        this.maxTokens     = b.maxTokens;
        this.repeatPenalty = b.repeatPenalty;
        this.seed          = b.seed;
        this.stop          = List.copyOf(b.stop);
        this.toolChoice    = b.toolChoice;
    }

    public double       temperature()   { return temperature; }
    public int          topK()          { return topK; }
    public double       topP()          { return topP; }
    public double       minP()          { return minP; }
    public int          maxTokens()     { return maxTokens; }
    public double       repeatPenalty() { return repeatPenalty; }
    public long         seed()          { return seed; }
    public List<String> stop()          { return stop; }
    public String       toolChoice()    { return toolChoice; }

    /** Whether an explicit (non-random) seed was set. */
    public boolean hasSeed() { return seed != RANDOM_SEED; }

    public static GenerationOptions defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double       temperature   = 0.7;
        private int          topK          = 40;
        private double       topP          = 0.95;
        private double       minP          = 0.05;
        private int          maxTokens     = 256;
        private double       repeatPenalty = 1.0;
        private long         seed          = RANDOM_SEED;
        private List<String> stop          = new ArrayList<>();
        private String       toolChoice    = "auto";

        public Builder temperature(double v)   { this.temperature = v; return this; }
        public Builder topK(int v)             { this.topK = v; return this; }
        public Builder topP(double v)          { this.topP = v; return this; }
        public Builder minP(double v)          { this.minP = v; return this; }
        public Builder maxTokens(int v)        { this.maxTokens = v; return this; }
        public Builder repeatPenalty(double v) { this.repeatPenalty = v; return this; }
        public Builder seed(long v)            { this.seed = v; return this; }
        public Builder toolChoice(String v)    { this.toolChoice = v; return this; }

        public Builder stop(List<String> v) {
            this.stop = (v == null) ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public Builder addStop(String v) {
            if (v != null && !v.isEmpty()) this.stop.add(v);
            return this;
        }

        public GenerationOptions build() { return new GenerationOptions(this); }
    }
}
