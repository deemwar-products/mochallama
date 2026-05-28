package tools.deemwar.mochallama.panama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.deemwar.mochallama.LlamaException;
import tools.deemwar.mochallama.ModelInfo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live test for the tool-calling capability gate. NOT run in normal CI — needs
 * GGUFs on disk. Enable by pointing two env vars at absolute GGUF paths:
 *
 * <pre>
 *   MOCHALLAMA_TOOL_MODEL=~/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf \
 *   MOCHALLAMA_NONTOOL_MODEL=~/.chatbot_models/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf \
 *     ./gradlew :core:test --tests '*ModelGateLiveTest'
 * </pre>
 *
 * <p>The two cases are independently guarded so either can run alone.
 */
class ModelGateLiveTest {

    private static Path model(String env) {
        String p = System.getenv(env);
        Path path = Path.of(p.replaceFirst("^~", System.getProperty("user.home")));
        assertTrue(Files.isRegularFile(path), "model file not found: " + path);
        return path;
    }

    /** Positive: a tool-capable instruct model inspects true AND loads. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MOCHALLAMA_TOOL_MODEL", matches = ".+")
    void toolCapableModel_inspectsTrue_andLoads() {
        Path path = model("MOCHALLAMA_TOOL_MODEL");

        ModelInfo info = ChatEngine.inspect(path);
        System.out.println("[positive] path=" + path);
        System.out.println("[positive] info=" + info);

        assertTrue(info.ok(), "inspect should not error: " + info.error());
        assertTrue(info.supportsTools(), "supportsTools should be true");
        assertTrue(info.supportsToolCalls(), "supportsToolCalls should be true");
        assertTrue(info.toolCapable(), "toolCapable should be true");

        // Load must SUCCEED for a tool-capable model.
        try (ChatEngine engine = ChatEngine.load(path)) {
            System.out.println("[positive] load SUCCEEDED");
            assertTrue(true);
        }
    }

    /** Negative: a non-tool model inspects false AND load throws the gate error. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MOCHALLAMA_NONTOOL_MODEL", matches = ".+")
    void nonToolModel_inspectsFalse_andLoadThrows() {
        Path path = model("MOCHALLAMA_NONTOOL_MODEL");

        ModelInfo info = ChatEngine.inspect(path);
        System.out.println("[negative] path=" + path);
        System.out.println("[negative] info=" + info);

        assertTrue(info.ok(), "inspect should not error: " + info.error());
        assertFalse(info.supportsTools(), "supportsTools should be false for a non-tool model");
        assertFalse(info.toolCapable(), "toolCapable should be false");

        // Load must THROW MODEL_NOT_TOOL_CAPABLE.
        LlamaException ex = assertThrows(LlamaException.class, () -> ChatEngine.load(path));
        System.out.println("[negative] load threw: " + ex);
        assertEquals("MODEL_NOT_TOOL_CAPABLE", ex.code(),
                "load must reject with MODEL_NOT_TOOL_CAPABLE");
    }
}
