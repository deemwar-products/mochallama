package tools.deemwar.mochallama.cli;

import tools.deemwar.mochallama.hf.HuggingFaceModels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in catalogue of the SAME tool-only model presets the Spring app ships
 * (see {@code app/src/main/resources/application-*.properties} and
 * {@code docs/specs/models.md}). Resolution + download are delegated to the
 * shared {@link HuggingFaceModels} so the CLI and the Spring starter share one
 * downloader and one {@code ~/.chatbot_models} cache.
 */
final class ModelRegistry {

    record Profile(String name, String url, String filename, String sizeHint) {}

    static final Path CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".chatbot_models");

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();

    static {
        // Tool-only lineup, identical to the Spring app profiles.
        add(new Profile("qwen2.5-1.5b",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "qwen2.5-1.5b-instruct-q4_k_m.gguf", "~1.1 GB"));
        add(new Profile("qwen2.5-3b",
                "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                "qwen2.5-3b-instruct-q4_k_m.gguf", "~2.1 GB"));
        add(new Profile("qwen3-4b",
                "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "~2.5 GB"));
        add(new Profile("phi-4-mini",
                "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
                "Phi-4-mini-instruct-Q4_K_M.gguf", "~2.5 GB"));
    }

    private ModelRegistry() {}

    private static void add(Profile p) {
        PROFILES.put(p.name(), p);
    }

    static Map<String, Profile> profiles() {
        return PROFILES;
    }

    static boolean isCached(Profile p) {
        return Files.exists(CACHE_DIR.resolve(p.filename()));
    }

    /**
     * Resolve {@code ref} to a local GGUF on disk, downloading into the shared
     * cache if needed. Accepts, in order:
     * <ol>
     *   <li>a built-in profile name (see {@link #profiles()});</li>
     *   <li>a local {@code .gguf} file path;</li>
     *   <li>a direct {@code .gguf} URL;</li>
     *   <li>an arbitrary Hugging Face id ({@code org/repo}).</li>
     * </ol>
     * Tool-capability is enforced at load time by {@code ChatEngine} — this
     * method only resolves the file.
     */
    static Path resolve(String ref) throws IOException {
        // 1) Built-in profile name.
        Profile p = PROFILES.get(ref);
        if (p != null) {
            return HuggingFaceModels.downloadIfAbsent(
                    p.url(), p.filename(), CACHE_DIR, ModelRegistry::print);
        }

        // 2/3/4) Local path, .gguf URL, or HF id.
        return HuggingFaceModels.resolveToLocal(
                ref, HuggingFaceModels.DEFAULT_QUANT, CACHE_DIR, ModelRegistry::print);
    }

    private static void print(String msg) {
        System.out.println(msg);
    }
}
