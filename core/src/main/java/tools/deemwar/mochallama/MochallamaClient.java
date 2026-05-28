package tools.deemwar.mochallama;

import java.util.List;
import java.util.function.Consumer;

/**
 * Stable, framework-free contract for talking to a loaded local model.
 *
 * <p>This interface lives in {@code mochallama-core} so that both the Spring
 * Boot starter and the Spring AI adapter can depend on it without dragging in
 * Spring AI itself — keeping the published libraries resilient across Spring AI
 * versions.
 *
 * <p>Two API tiers are offered:
 * <ul>
 *   <li>The simple {@link #chat(String, int, double)} (text in, text out) for
 *       quick single-turn use.</li>
 *   <li>The rich {@link #chat(List, List, GenerationOptions)} /
 *       {@link #chatStream(List, List, GenerationOptions, Consumer)} which expose
 *       full message lists, tool calling, the complete sampling parameter set,
 *       real token usage and streaming.</li>
 * </ul>
 */
public interface MochallamaClient {

    /**
     * Run a single-turn inference and return the assistant text.
     *
     * @param prompt      user message
     * @param maxTokens   cap on generated tokens
     * @param temperature sampling temperature
     */
    String chat(String prompt, int maxTokens, double temperature);

    /** Convenience overload using sensible defaults. */
    default String chat(String prompt) {
        return chat(prompt, 256, 0.7);
    }

    /**
     * Run a single inference over a full message list, optionally exposing tools,
     * with the full generation/sampling parameter set. Returns assistant text,
     * real token usage and any parsed tool calls.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException};
     * implementations backed by the native bridge override it.
     */
    default ChatResult chat(List<Message> messages, List<ToolDefinition> tools, GenerationOptions opts) {
        throw new UnsupportedOperationException("rich chat() not supported by this client");
    }

    /**
     * Like {@link #chat(List, List, GenerationOptions)} but streams each decoded
     * token piece to {@code onToken} as it is produced, returning the same final
     * {@link ChatResult} once complete.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException};
     * implementations backed by the native bridge override it.
     */
    default ChatResult chatStream(List<Message> messages, List<ToolDefinition> tools,
                                  GenerationOptions opts, Consumer<String> onToken) {
        throw new UnsupportedOperationException("chatStream() not supported by this client");
    }

    /** Whether the model is loaded and ready to serve inference. */
    boolean isReady();
}
