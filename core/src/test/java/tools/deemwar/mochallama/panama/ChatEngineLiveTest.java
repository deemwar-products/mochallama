package tools.deemwar.mochallama.panama;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.ToolCall;
import tools.deemwar.mochallama.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration test exercising the rich native bridge. NOT run in normal
 * CI — it requires a tool-capable GGUF on disk. Enable by setting the env var
 * {@code MOCHALLAMA_TEST_MODEL} to an absolute GGUF path, e.g.
 *
 * <pre>
 *   MOCHALLAMA_TEST_MODEL=~/.chatbot_models/qwen2.5-1.5b-instruct-q4_k_m.gguf \
 *     ./gradlew :core:test --tests '*ChatEngineLiveTest'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MOCHALLAMA_TEST_MODEL", matches = ".+")
class ChatEngineLiveTest {

    private static Path modelPath() {
        String p = System.getenv("MOCHALLAMA_TEST_MODEL");
        Path path = Path.of(p.replaceFirst("^~", System.getProperty("user.home")));
        assertTrue(Files.isRegularFile(path), "model file not found: " + path);
        return path;
    }

    @Test
    void realUsage() {
        try (ChatEngine engine = ChatEngine.load(modelPath())) {
            ChatResult r = engine.chat(
                    List.of(Message.user("Say the single word: hello")),
                    List.of(),
                    GenerationOptions.builder().maxTokens(16).temperature(0.0).seed(1).build());

            System.out.println("[realUsage] text='" + r.text() + "'");
            System.out.println("[realUsage] usage=" + r.usage());

            assertTrue(r.usage().promptTokens() > 0, "prompt tokens should be > 0");
            assertTrue(r.usage().completionTokens() > 0, "completion tokens should be > 0");
            assertEquals(r.usage().promptTokens() + r.usage().completionTokens(),
                    r.usage().totalTokens(), "total = prompt + completion");
            // Qwen chat template wraps a short user message; prompt should be a
            // small but non-trivial token count, NOT a length/4 estimate.
            assertTrue(r.usage().promptTokens() >= 8 && r.usage().promptTokens() < 60,
                    "prompt token count looks like a real tokenization: " + r.usage().promptTokens());
        }
    }

    @Test
    void streaming() {
        try (ChatEngine engine = ChatEngine.load(modelPath())) {
            AtomicInteger callbacks = new AtomicInteger();
            StringBuilder streamed = new StringBuilder();

            ChatResult r = engine.chatStream(
                    List.of(Message.user("Count from 1 to 5.")),
                    List.of(),
                    GenerationOptions.builder().maxTokens(48).temperature(0.0).seed(7).build(),
                    piece -> { callbacks.incrementAndGet(); streamed.append(piece); });

            System.out.println("[streaming] callbacks=" + callbacks.get());
            System.out.println("[streaming] streamed='" + streamed + "'");
            System.out.println("[streaming] final  ='" + r.text() + "'");
            System.out.println("[streaming] usage=" + r.usage());

            assertTrue(callbacks.get() > 1, "onToken should fire multiple times, got " + callbacks.get());
            // The concatenated stream equals the final text (the bridge builds
            // text from the same pieces it streams).
            assertEquals(r.text(), streamed.toString(), "concatenated stream == final text");
            assertEquals(callbacks.get(), r.usage().completionTokens(),
                    "one callback per decoded completion token");
        }
    }

    @Test
    void toolCalling() {
        try (ChatEngine engine = ChatEngine.load(modelPath())) {
            ToolDefinition weather = new ToolDefinition(
                    "get_weather",
                    "Get the current weather for a location",
                    "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\","
                            + "\"description\":\"City name\"}},\"required\":[\"location\"]}");

            ChatResult r = engine.chat(
                    List.of(Message.user("What's the weather in Paris?")),
                    List.of(weather),
                    GenerationOptions.builder().maxTokens(128).temperature(0.0).seed(1)
                            .toolChoice("auto").build());

            System.out.println("[toolCalling] finishReason=" + r.finishReason());
            System.out.println("[toolCalling] text='" + r.text() + "'");
            System.out.println("[toolCalling] toolCalls=" + r.toolCalls());
            System.out.println("[toolCalling] usage=" + r.usage());

            assertTrue(r.hasToolCalls(), "model should request a tool call");
            assertEquals("tool_calls", r.finishReason());
            ToolCall call = r.toolCalls().get(0);
            assertEquals("get_weather", call.name(), "tool name");
            assertTrue(call.argumentsJson().toLowerCase().contains("paris"),
                    "arguments should mention Paris: " + call.argumentsJson());
        }
    }

    @Test
    void seedReproducibility() {
        try (ChatEngine engine = ChatEngine.load(modelPath())) {
            // A non-zero temperature with a FIXED seed must yield identical output.
            GenerationOptions opts = GenerationOptions.builder()
                    .maxTokens(40).temperature(0.8).topK(50).topP(0.9).seed(424242).build();

            String a = engine.chat(List.of(Message.user("Write one short sentence about the sea.")),
                    List.of(), opts).text();
            String b = engine.chat(List.of(Message.user("Write one short sentence about the sea.")),
                    List.of(), opts).text();

            System.out.println("[seed] a='" + a + "'");
            System.out.println("[seed] b='" + b + "'");

            assertFalse(a.isEmpty(), "output should be non-empty");
            assertEquals(a, b, "same seed + params => identical output (top_k/top_p/seed accepted)");
        }
    }

    /** Quick sanity: bridge version string includes the b9371 tag. */
    @Test
    void versionString() {
        List<Message> ignored = new ArrayList<>();
        ignored.add(Message.user("noop"));
        System.out.println("[version] " + LlamaBridge.version());
        assertTrue(LlamaBridge.version().contains("b9371"));
    }
}
