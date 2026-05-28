package tools.deemwar.mochallama.hf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tools.deemwar.mochallama.LlamaException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shared, framework-free Hugging Face model resolver + downloader. This is the
 * single place both the Spring starter ({@code LlamaCppService}) and the CLI
 * ({@code ModelRegistry} / {@code ChatCommand}) use to turn a Hugging Face model
 * id (or a direct {@code .gguf} URL/path) into a local file on disk.
 *
 * <p>Resolution follows the flow in
 * {@code docs/specs/tool-calling-support.md} §C.4: query the Hub model API for
 * the {@code .gguf} siblings, pick the file matching the preferred quant, build
 * the {@code /resolve/main/...} URL, and download with a HEAD size check +
 * {@code .partial} → atomic move. Tool-capability is NOT decided here — it is
 * enforced authoritatively at load time by {@code ChatEngine.load} /
 * {@code ChatEngine.inspect}. The optional {@link HfModel#chatTemplate()} is a
 * cheap, NON-authoritative pre-filter hint only.
 *
 * <p>No Spring, no Lombok — plain {@code java.net.http}.
 */
public final class HuggingFaceModels {

    /** Default quant convention across the shipped lineup. */
    public static final String DEFAULT_QUANT = "Q4_K_M";

    /** Ordered quant fallbacks when the preferred quant is absent. */
    private static final List<String> QUANT_FALLBACKS =
            List.of("q4_k_m", "q5_k_m", "q4_0", "q8_0", "q6_k", "q3_k_m", "q2_k");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final long PROGRESS_LOG_BYTES = 100L * 1024 * 1024;

    private HuggingFaceModels() {}

    /**
     * A resolved Hugging Face GGUF: the source model id, the picked {@code .gguf}
     * file name, the direct resolve URL to download it, and (optionally) the
     * server-parsed default chat template for a cheap pre-check.
     *
     * @param modelId      the {@code org/repo} id
     * @param fileName     the chosen {@code .gguf} sibling
     * @param resolveUrl   {@code https://huggingface.co/{id}/resolve/main/{file}?download=true}
     * @param chatTemplate the server-parsed {@code gguf.chat_template} (may be
     *                     {@code null}); NON-authoritative — pre-filter hint only
     */
    public record HfModel(String modelId, String fileName, String resolveUrl, String chatTemplate) {

        /**
         * Cheap, NON-authoritative hint: does the default chat template appear to
         * describe tools? The authoritative gate is {@code ChatEngine.inspect/load}.
         */
        public boolean looksToolCapable() {
            return chatTemplate != null && chatTemplate.contains("tools");
        }
    }

    // ------------------------------------------------------------------
    // Resolve
    // ------------------------------------------------------------------

    /**
     * Resolve a Hugging Face model id to a concrete {@code .gguf} download.
     *
     * @param modelId        the {@code org/repo} id (e.g.
     *                       {@code Qwen/Qwen2.5-3B-Instruct-GGUF})
     * @param preferredQuant case-insensitive quant tag to prefer (e.g.
     *                       {@code Q4_K_M}); {@code null} uses {@link #DEFAULT_QUANT}
     * @throws LlamaException on a gated/private repo without auth, or when no
     *                        {@code .gguf} sibling exists
     */
    public static HfModel resolve(String modelId, String preferredQuant) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        String quant = (preferredQuant == null || preferredQuant.isBlank())
                ? DEFAULT_QUANT : preferredQuant;

        // The default model API response already includes `siblings`, `gguf`
        // (parsed GGUF header incl. the default `chat_template`) and `gated` — no
        // `expand` needed. (`?expand=gguf,siblings` is rejected with HTTP 400;
        // `expand` only accepts repeated single-value params.)
        String api = "https://huggingface.co/api/models/" + modelId;
        JsonNode root = getJson(api, modelId);

        // Gating pre-check — fail early rather than attempting a 401 download.
        JsonNode gatedNode = root.get("gated");
        boolean gated = gatedNode != null && !gatedNode.isNull()
                && !"false".equalsIgnoreCase(gatedNode.asText());
        boolean privateRepo = root.path("private").asBoolean(false);
        if ((gated || privateRepo) && hfToken() == null) {
            throw new LlamaException("MODEL_GATED",
                    "gated/private model '" + modelId + "': set HF_TOKEN to download it "
                            + "(and accept the model license on Hugging Face)");
        }

        List<String> ggufFiles = listGgufSiblings(root);
        if (ggufFiles.isEmpty()) {
            throw new LlamaException("NO_GGUF",
                    "no .gguf file found for Hugging Face model '" + modelId + "'");
        }

        String fileName = pickQuant(ggufFiles, quant);
        String resolveUrl = "https://huggingface.co/" + modelId
                + "/resolve/main/" + fileName + "?download=true";

        String chatTemplate = null;
        JsonNode gguf = root.get("gguf");
        if (gguf != null && gguf.hasNonNull("chat_template")) {
            chatTemplate = gguf.get("chat_template").asText();
        }
        return new HfModel(modelId, fileName, resolveUrl, chatTemplate);
    }

    /** Convenience overload using the default quant. */
    public static HfModel resolve(String modelId) {
        return resolve(modelId, DEFAULT_QUANT);
    }

    private static List<String> listGgufSiblings(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode siblings = root.get("siblings");
        if (siblings != null && siblings.isArray()) {
            for (JsonNode sib : siblings) {
                String name = sib.path("rfilename").asText(null);
                if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /**
     * Pick a {@code .gguf} file matching the preferred quant; fall back through
     * {@link #QUANT_FALLBACKS}, else the first {@code .gguf}. Multi-part
     * ({@code -00001-of-000NN}) shards are skipped where a single-file
     * alternative exists.
     */
    private static String pickQuant(List<String> files, String preferredQuant) {
        String want = preferredQuant.toLowerCase(Locale.ROOT);

        // 1) Exact preferred quant, single-file (no shard suffix) preferred.
        String hit = firstMatch(files, want, true);
        if (hit != null) return hit;
        hit = firstMatch(files, want, false);
        if (hit != null) return hit;

        // 2) Ordered fallbacks.
        for (String fb : QUANT_FALLBACKS) {
            if (fb.equals(want)) continue;
            hit = firstMatch(files, fb, true);
            if (hit != null) return hit;
            hit = firstMatch(files, fb, false);
            if (hit != null) return hit;
        }

        // 3) First single-file .gguf, else first of any.
        for (String f : files) {
            if (!isShard(f)) return f;
        }
        return files.get(0);
    }

    private static String firstMatch(List<String> files, String quant, boolean singleFileOnly) {
        for (String f : files) {
            String lower = f.toLowerCase(Locale.ROOT);
            if (lower.contains(quant) && (!singleFileOnly || !isShard(f))) {
                return f;
            }
        }
        return null;
    }

    private static boolean isShard(String file) {
        // e.g. model-00001-of-00002.gguf
        return file.toLowerCase(Locale.ROOT).matches(".*-\\d{5}-of-\\d{5}\\.gguf");
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /**
     * Download {@code resolveUrl} into {@code cacheDir/fileName} if it is not
     * already present. Uses a HEAD size check, downloads to {@code .partial}, then
     * atomically moves into place — mirroring the existing downloaders.
     *
     * @param progress optional per-MB progress callback (may be {@code null})
     * @return the local {@link Path} to the downloaded (or pre-existing) file
     * @throws LlamaException on HTTP 401/403 (gated / license not accepted)
     */
    public static Path downloadIfAbsent(String resolveUrl, String fileName, Path cacheDir,
                                        Consumer<String> progress) throws IOException {
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(fileName);
        if (Files.exists(target)) {
            log(progress, "model present at " + target + " (" + Files.size(target) + " bytes)");
            return target;
        }

        long expectedSize = headContentLength(resolveUrl);
        Path partial = cacheDir.resolve(fileName + ".partial");
        log(progress, "downloading " + resolveUrl + " ("
                + (expectedSize > 0 ? (expectedSize / (1024 * 1024)) + " MB" : "size unknown")
                + ") -> " + partial);

        HttpRequest.Builder get = HttpRequest.newBuilder(URI.create(resolveUrl)).GET();
        applyAuth(get);
        HttpResponse<InputStream> resp;
        try {
            resp = HTTP.send(get.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("download interrupted: " + resolveUrl, e);
        }
        checkAuthStatus(resp.statusCode(), resolveUrl);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("download failed HTTP " + resp.statusCode() + ": " + resolveUrl);
        }

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(partial)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            long nextLog = PROGRESS_LOG_BYTES;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= nextLog) {
                    log(progress, "  ... " + (total / (1024 * 1024)) + " MB");
                    nextLog += PROGRESS_LOG_BYTES;
                }
            }
            log(progress, "download complete: " + (total / (1024 * 1024)) + " MB");
        }

        Files.move(partial, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /** {@link #downloadIfAbsent(String, String, Path, Consumer)} without progress. */
    public static Path downloadIfAbsent(String resolveUrl, String fileName, Path cacheDir)
            throws IOException {
        return downloadIfAbsent(resolveUrl, fileName, cacheDir, null);
    }

    // ------------------------------------------------------------------
    // Static convenience: HF id OR direct .gguf path/URL → local Path
    // ------------------------------------------------------------------

    /**
     * Accept EITHER a Hugging Face id ({@code org/repo}), a direct {@code .gguf}
     * URL, or a local {@code .gguf} file path, and return a local {@link Path}.
     *
     * <ul>
     *   <li>Existing local {@code .gguf} file → returned as-is.</li>
     *   <li>{@code http(s)://…gguf} URL → downloaded into {@code cacheDir}.</li>
     *   <li>{@code org/repo} HF id → resolved via {@link #resolve} then downloaded.</li>
     * </ul>
     *
     * @param preferredQuant quant preference for the HF-id path ({@code null} →
     *                       {@link #DEFAULT_QUANT}); ignored for file/URL inputs
     */
    public static Path resolveToLocal(String ref, String preferredQuant, Path cacheDir,
                                      Consumer<String> progress) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("model reference must not be blank");
        }

        // 1) Local file?
        Path direct = Path.of(ref);
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        // 2) Direct .gguf URL?
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String fileName = fileNameFromUrl(ref);
            return downloadIfAbsent(ref, fileName, cacheDir, progress);
        }

        // 3) Hugging Face id (org/repo).
        if (looksLikeHfId(ref)) {
            HfModel m = resolve(ref, preferredQuant);
            return downloadIfAbsent(m.resolveUrl(), m.fileName(), cacheDir, progress);
        }

        throw new IllegalArgumentException(
                "'" + ref + "' is not a local .gguf, a .gguf URL, or a Hugging Face id (org/repo)");
    }

    /** Whether {@code ref} looks like a Hugging Face {@code org/repo} id. */
    public static boolean looksLikeHfId(String ref) {
        if (ref == null) return false;
        // org/repo: exactly one slash, no whitespace, not a file path / URL.
        if (ref.contains(" ") || ref.endsWith(".gguf")) return false;
        int slash = ref.indexOf('/');
        return slash > 0 && slash == ref.lastIndexOf('/') && slash < ref.length() - 1;
    }

    private static String fileNameFromUrl(String url) {
        String path = URI.create(url).getPath();
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "model.gguf" : name;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private static JsonNode getJson(String url, String modelId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json").GET();
        applyAuth(b);
        try {
            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new LlamaException("MODEL_GATED",
                        "gated/private model '" + modelId + "' (HTTP " + resp.statusCode()
                                + "): set HF_TOKEN and accept the license on Hugging Face");
            }
            if (resp.statusCode() == 404) {
                throw new LlamaException("MODEL_NOT_FOUND",
                        "Hugging Face model '" + modelId + "' not found (HTTP 404)");
            }
            if (resp.statusCode() / 100 != 2) {
                throw new LlamaException("HF_API_ERROR",
                        "Hugging Face API HTTP " + resp.statusCode() + " for " + url);
            }
            return MAPPER.readTree(resp.body());
        } catch (LlamaException le) {
            throw le;
        } catch (IOException e) {
            throw new LlamaException("HF_API_ERROR", "failed to query Hugging Face API: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlamaException("HF_API_ERROR", "Hugging Face API call interrupted: " + url, e);
        }
    }

    private static long headContentLength(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        applyAuth(b);
        try {
            HttpResponse<Void> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding());
            checkAuthStatus(resp.statusCode(), url);
            return resp.headers().firstValueAsLong("content-length").orElse(-1L);
        } catch (IOException e) {
            return -1L; // best-effort; the GET below is authoritative
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1L;
        }
    }

    private static void checkAuthStatus(int status, String url) throws IOException {
        if (status == 401) {
            throw new IOException("gated model — authentication required (HTTP 401): " + url
                    + " (set HF_TOKEN and accept the model license on Hugging Face)");
        }
        if (status == 403) {
            throw new IOException("access forbidden (HTTP 403): " + url
                    + " (accept the model license on Hugging Face)");
        }
    }

    private static void applyAuth(HttpRequest.Builder b) {
        String token = hfToken();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
    }

    /** HF token from {@code HF_TOKEN} (or {@code HUGGING_FACE_HUB_TOKEN}); never logged. */
    private static String hfToken() {
        String t = System.getenv("HF_TOKEN");
        if (t == null || t.isBlank()) t = System.getenv("HUGGING_FACE_HUB_TOKEN");
        return (t == null || t.isBlank()) ? null : t;
    }

    private static void log(Consumer<String> sink, String msg) {
        if (sink != null) sink.accept(msg);
    }
}
