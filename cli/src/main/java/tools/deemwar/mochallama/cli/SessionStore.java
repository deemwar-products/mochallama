package tools.deemwar.mochallama.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Disk persistence for {@link ChatSession}s, stored as one pretty-printed JSON
 * file per session under {@code ~/.chatbot_models/sessions/&lt;id&gt;.json}
 * (sharing the {@link ModelRegistry#CACHE_DIR} cache root).
 */
final class SessionStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Returns the sessions dir, creating it on demand. */
    Path dir() throws IOException {
        Path d = ModelRegistry.CACHE_DIR.resolve("sessions");
        Files.createDirectories(d);
        return d;
    }

    /** A fresh 8-lowercase-hex id derived from a random UUID. */
    String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /**
     * Write {@code s} to {@code <id>.json}. Writes a temp file then atomically
     * moves it into place for crash-safety; falls back to a plain replace where
     * atomic moves are unsupported.
     */
    void save(ChatSession s) {
        try {
            Path dir = dir();
            Path target = dir.resolve(s.id + ".json");
            Path tmp = Files.createTempFile(dir, s.id + "-", ".json.tmp");
            try {
                MAPPER.writeValue(tmp.toFile(), s);
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save session '" + s.id + "' under " + sessionsDirHint(), e);
        }
    }

    /** Read {@code <id>.json}; empty if it does not exist. */
    Optional<ChatSession> load(String id) {
        try {
            Path f = dir().resolve(id + ".json");
            if (!Files.exists(f)) {
                return Optional.empty();
            }
            ChatSession s = MAPPER.readValue(f.toFile(), ChatSession.class);
            // A hand-edited/older-schema file can carry a turn with a null role;
            // surface it as a clean load failure rather than letting a bare NPE
            // escape later when the turns are converted to core Messages.
            for (ChatSession.Turn t : s.messages) {
                if (t.role == null) {
                    throw new IOException(
                            "session '" + id + "' has a stored turn with no role");
                }
            }
            return Optional.of(s);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load session '" + id + "'", e);
        }
    }

    /** All sessions, newest-updated first; unparseable files are skipped. */
    List<ChatSession> list() {
        try (Stream<Path> files = Files.list(dir())) {
            List<ChatSession> out = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            out.add(MAPPER.readValue(p.toFile(), ChatSession.class));
                        } catch (IOException skip) {
                            // Skip files that don't parse as a ChatSession.
                        }
                    });
            out.sort(Comparator.comparing(
                    (ChatSession s) -> s.updatedAt == null ? "" : s.updatedAt).reversed());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list sessions under " + sessionsDirHint(), e);
        }
    }

    private static String sessionsDirHint() {
        return ModelRegistry.CACHE_DIR.resolve("sessions").toString();
    }
}
