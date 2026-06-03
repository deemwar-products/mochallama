package tools.deemwar.mochallama.panama;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Extracts the bundled native libraries from {@code classpath:/native/<os>-<arch>/}
 * into a fresh temp directory and loads them in dependency order.
 *
 * <p>Supported platform keys: {@code darwin-x86_64}, {@code darwin-aarch64},
 * {@code linux-x86_64}, {@code windows-x86_64}. The library extension and name
 * prefix vary per-OS:
 * <ul>
 *   <li>macOS — {@code lib<stem>.dylib}</li>
 *   <li>Linux — {@code lib<stem>.so}</li>
 *   <li>Windows — {@code <stem>.dll} (no {@code lib} prefix)</li>
 * </ul>
 *
 * <p>The bridge is dlopen'd against versioned SONAMEs (e.g.
 * {@code @rpath/libllama.0.dylib} on macOS, {@code libllama.so.0} on Linux).
 * The build copies every name in each library's symlink chain into resources,
 * so we mirror them all into the temp dir verbatim — that lets {@code @rpath} /
 * {@code $ORIGIN} resolution from inside libllamabridge find the sibling
 * libraries without any DYLD_LIBRARY_PATH / LD_LIBRARY_PATH hackery.
 *
 * <p>Loading is idempotent: a static guard prevents reloading on repeated calls.
 */
public final class NativeLoader {

    /** Stems in load order: each level's libs depend only on prior levels. */
    private static final List<String> LOAD_ORDER = List.of(
            "ggml-base",
            "ggml-cpu",
            "ggml-blas",
            "ggml",
            "llama",
            "llama-common",   // common_chat_* / common_sampler_* helpers (depends on llama)
            "llamabridge"
    );

    private static final Object LOCK = new Object();
    private static volatile Path bridgePath;

    private NativeLoader() {}

    /**
     * Extract and {@link System#load} all bundled dylibs. Returns the absolute
     * filesystem path of {@code libllamabridge.dylib} (useful for downstream
     * {@code SymbolLookup.libraryLookup}).
     *
     * <p>Safe to call repeatedly — only performs work the first time.
     */
    public static Path load() {
        Path cached = bridgePath;
        if (cached != null) return cached;

        synchronized (LOCK) {
            if (bridgePath != null) return bridgePath;

            try {
                String platform = platformDir();
                String resourceDir = "/native/" + platform + "/";

                // Fail fast (and helpfully) if this build wasn't packaged with
                // natives for the running platform.
                if (NativeLoader.class.getResource(resourceDir) == null) {
                    throw new IllegalStateException(
                            "no mochallama native binaries bundled for " + platform
                                    + " — this build supports: " + presentPlatforms());
                }

                Path tmp = Files.createTempDirectory("llamabridge");
                tmp.toFile().deleteOnExit();

                // Stage EVERY native library file under the resource dir into
                // the temp dir, preserving filenames. We need every versioned
                // variant (libfoo.dylib, libfoo.0.dylib, libfoo.0.13.0.dylib on
                // macOS; libfoo.so, libfoo.so.0 on Linux) so that @rpath /
                // $ORIGIN lookups inside libllamabridge resolve.
                List<String> staged = stageAllDylibs(resourceDir, tmp);
                if (staged.isEmpty()) {
                    throw new IllegalStateException(
                            "no mochallama native binaries bundled for " + platform
                                    + " — this build supports: " + presentPlatforms());
                }

                String libExt = dylibExtension();
                String libPrefix = libPrefix();
                Path bridge = null;
                // Pre-load the dependency chain in order. We skip any stem that
                // isn't bundled rather than failing hard: which ggml backends
                // ship varies by platform/build (e.g. ggml-blas is macOS-only;
                // ggml-rpc isn't always present). Loading the present libs by
                // absolute path before the bridge is what makes Windows resolve
                // its imports (no rpath there); on macOS/Linux @loader_path /
                // $ORIGIN would also resolve them. If a genuinely required lib
                // is absent, the bridge's own System.load below fails loudly
                // with an UnsatisfiedLinkError naming the missing symbol/lib.
                for (String stem : LOAD_ORDER) {
                    Path lib = tmp.resolve(libPrefix + stem + libExt);
                    if (!Files.isRegularFile(lib)) {
                        continue;
                    }
                    System.load(lib.toAbsolutePath().toString());
                    if (stem.equals("llamabridge")) {
                        bridge = lib;
                    }
                }
                if (bridge == null) {
                    throw new IllegalStateException(
                            "Missing required native library: "
                                    + libPrefix + "llamabridge" + libExt
                                    + " (staged: " + staged + ")");
                }

                bridgePath = bridge;
                return bridge;
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Failed to extract native libraries", e);
            }
        }
    }

    /** Absolute filesystem path of the loaded bridge, or {@code null}. */
    public static Path bridgePath() {
        return bridgePath;
    }

    /** Returns {@code darwin-x86_64} / {@code darwin-aarch64} / {@code linux-x86_64} etc. */
    private static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osKey;
        if (os.contains("mac") || os.contains("darwin")) {
            osKey = "darwin";
        } else if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("win")) {
            osKey = "windows";
        } else {
            throw new IllegalStateException("Unsupported OS: " + os);
        }

        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archKey = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archKey = "aarch64";
        } else {
            throw new IllegalStateException("Unsupported arch: " + arch);
        }
        return osKey + "-" + archKey;
    }

    private static String dylibExtension() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return ".dylib";
        if (os.contains("win")) return ".dll";
        return ".so";
    }

    /** Library file-name prefix: {@code lib} on Unix, none on Windows. */
    private static String libPrefix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "" : "lib";
    }

    /** Candidate platform keys we may ship natives for. */
    private static final List<String> KNOWN_PLATFORMS = List.of(
            "darwin-x86_64", "darwin-aarch64",
            "linux-x86_64", "linux-aarch64",
            "windows-x86_64");

    /**
     * The subset of {@link #KNOWN_PLATFORMS} actually bundled in this build —
     * i.e. those whose {@code classpath:/native/<key>/} resource dir is present.
     * Used only to build a helpful error message when the running platform is
     * unsupported.
     */
    private static String presentPlatforms() {
        StringBuilder sb = new StringBuilder();
        for (String key : KNOWN_PLATFORMS) {
            if (NativeLoader.class.getResource("/native/" + key + "/") != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(key);
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /**
     * Stage every regular file under {@code classpath:/native/<platform>/}
     * into {@code dest}, returning the list of staged filenames.
     *
     * <p>Supports both directory-classpath (gradle dev runs) and jar-classpath
     * (boot fat jar) layouts.
     */
    private static List<String> stageAllDylibs(String resourceDir, Path dest)
            throws IOException {
        URL dirUrl = NativeLoader.class.getResource(resourceDir);
        if (dirUrl == null) return List.of();

        // Directory on disk — straightforward listing.
        if ("file".equals(dirUrl.getProtocol())) {
            Path src = Path.of(URI.create(dirUrl.toString()));
            try (Stream<Path> entries = Files.list(src)) {
                return entries
                        .filter(Files::isRegularFile)
                        .map(p -> {
                            Path out = dest.resolve(p.getFileName().toString());
                            try {
                                Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            return out.getFileName().toString();
                        })
                        .toList();
            }
        }

        // Inside a jar — open as a FileSystem to walk the entries.
        if ("jar".equals(dirUrl.getProtocol())) {
            URLConnection conn = dirUrl.openConnection();
            URI jarUri = URI.create(dirUrl.toString());
            try (FileSystem fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
                Path src = fs.getPath(resourceDir);
                try (Stream<Path> entries = Files.list(src)) {
                    return entries
                            .filter(Files::isRegularFile)
                            .map(p -> {
                                String name = p.getFileName().toString();
                                Path out = dest.resolve(name);
                                try (InputStream in = Files.newInputStream(p)) {
                                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                                return name;
                            })
                            .toList();
                }
            } finally {
                // URLConnection has no close(); silence the unused warning.
                if (conn != null) { /* no-op */ }
            }
        }

        throw new IOException("Unsupported resource URL: " + dirUrl);
    }
}
