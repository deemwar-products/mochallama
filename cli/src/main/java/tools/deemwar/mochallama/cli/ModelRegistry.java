package tools.deemwar.mochallama.cli;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in catalogue of the same model presets the Spring app ships, plus a
 * downloader that caches into {@code ~/.chatbot_models} — the dir shared with
 * the Spring app, so a model fetched by either side is reused by both.
 */
final class ModelRegistry {

    record Profile(String name, String url, String filename, String sizeHint) {}

    static final Path CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".chatbot_models");

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();

    static {
        add(new Profile("llama-3.2-1b",
                "https://huggingface.co/mukel/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_0.gguf",
                "Llama-3.2-1B-Instruct-Q4_0.gguf", "~700 MB"));
        add(new Profile("llama-3.2-3b",
                "https://huggingface.co/mukel/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_0.gguf",
                "Llama-3.2-3B-Instruct-Q4_0.gguf", "~1.8 GB"));
        add(new Profile("qwen3.5-4b",
                "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "~2.5 GB"));
        add(new Profile("gemma-4-e4b",
                "https://huggingface.co/unsloth/gemma-3n-E4B-it-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf",
                "gemma-3n-E4B-it-Q4_K_M.gguf", "~4.5 GB"));
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
     * Resolve {@code profileOrPath} to a local GGUF on disk. If it names an
     * existing file, use it verbatim; otherwise treat it as a profile name,
     * downloading into the shared cache if not already present.
     */
    static Path resolve(String profileOrPath) throws IOException {
        Path direct = Paths.get(profileOrPath);
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        Profile p = PROFILES.get(profileOrPath);
        if (p == null) {
            throw new IllegalArgumentException(
                    "unknown model '" + profileOrPath + "' (not a file path and not a known profile; run 'models' to list)");
        }

        Files.createDirectories(CACHE_DIR);
        Path target = CACHE_DIR.resolve(p.filename());
        if (Files.exists(target)) {
            return target;
        }
        return download(p, target);
    }

    private static Path download(Profile p, Path target) throws IOException {
        Path partial = target.resolveSibling(p.filename() + ".partial");
        URL url = URI.create(p.url()).toURL();

        HttpURLConnection head = (HttpURLConnection) url.openConnection();
        head.setRequestMethod("HEAD");
        long expectedSize = head.getContentLengthLong();
        head.disconnect();

        System.out.printf("Downloading %s (%s) -> %s%n",
                p.name(), expectedSize > 0 ? (expectedSize / (1024 * 1024)) + " MB" : "size unknown", target);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(partial)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            long nextLog = 100L * 1024 * 1024;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= nextLog) {
                    System.out.printf("  ... %d MB%n", total / (1024 * 1024));
                    nextLog += 100L * 1024 * 1024;
                }
            }
            System.out.printf("Download complete: %d MB%n", total / (1024 * 1024));
        } finally {
            conn.disconnect();
        }

        Files.move(partial, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
