package tools.deemwar.mochallama.springai;

import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.MochallamaClient;
import tools.deemwar.mochallama.ToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring AI {@link ChatModel} backed by the framework-free {@link MochallamaClient}.
 *
 * <p>Implements both the blocking {@link #call(Prompt)} and the reactive
 * {@link #stream(Prompt)} paths. Spring AI {@link ChatOptions} (temperature,
 * topK, topP, maxTokens, stop) are mapped onto {@link GenerationOptions}; the
 * server-side defaults apply for anything the prompt leaves unset.
 *
 * <h2>Tool calling (Spring AI 1.0 GA)</h2>
 *
 * <p><b>Inbound</b> — when the incoming {@link Prompt#getOptions()} is a
 * {@link ToolCallingChatOptions}, each declared {@link ToolCallback} is mapped to
 * our core {@link tools.deemwar.mochallama.ToolDefinition}
 * ({@code name}/{@code description}/{@code inputSchema}) and handed to the model
 * so it can decide whether to call one. When no tools are supplied the behaviour
 * is identical to plain text generation.
 *
 * <p><b>Outbound</b> — model-emitted tool calls are surfaced back to Spring AI as
 * {@link AssistantMessage.ToolCall}s (so callers can read
 * {@code response.getResult().getOutput().getToolCalls()}), with the generation
 * {@code finishReason} set to {@code tool_calls}.
 *
 * <p><b>Execution</b> — this adapter only <em>surfaces</em> the proposed tool
 * calls; it never executes them. Spring AI's {@code ChatClient}/
 * {@code ToolCallingManager} owns the execution loop. A caller who wants the raw
 * tool calls (rather than auto-execution) should build the {@link Prompt} with
 * {@code ToolCallingChatOptions.builder()....internalToolExecutionEnabled(false)}
 * and read the {@link ChatResponse} directly (or call {@link #call(Prompt)} on
 * this {@code ChatModel}), which returns the {@link AssistantMessage.ToolCall}s
 * without running them.
 */
@RequiredArgsConstructor
public class MochallamaChatModel implements ChatModel {

    private final MochallamaClient client;
    private final ChatOptions defaultOptions = ChatOptions.builder().build();

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!client.isReady()) {
            throw new IllegalStateException("model not ready");
        }
        ChatResult result = client.chat(
                MochallamaMessages.toCore(prompt.getInstructions()),
                toCoreTools(prompt.getOptions()),
                toGenerationOptions(prompt.getOptions()));
        return toChatResponse(result);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (!client.isReady()) {
            return Flux.error(new IllegalStateException("model not ready"));
        }
        // Bridge the blocking, callback-driven chatStream onto a Flux: each token
        // is pushed into the sink as it arrives; once the (synchronous) call
        // returns we emit a final chunk carrying the finish reason and complete.
        // subscribeOn(boundedElastic) keeps the blocking bridge call off any
        // event-loop thread.
        return Flux.<ChatResponse>create(sink -> {
            try {
                ChatResult result = client.chatStream(
                        MochallamaMessages.toCore(prompt.getInstructions()),
                        toCoreTools(prompt.getOptions()),
                        toGenerationOptions(prompt.getOptions()),
                        token -> sink.next(chunkResponse(token, null)));
                String finishReason = result.hasToolCalls()
                        ? "tool_calls"
                        : (result.finishReason() != null ? result.finishReason() : "stop");
                sink.next(chunkResponse("", finishReason));
                sink.complete();
            } catch (RuntimeException e) {
                sink.error(e);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    // ---------------------------------------------------------------
    // Inbound tool mapping (Spring AI -> core)
    // ---------------------------------------------------------------

    /**
     * Reads tool declarations off the incoming prompt options. Spring AI 1.0 GA
     * attaches tools as {@link ToolCallback}s on a {@link ToolCallingChatOptions}
     * (via {@code .toolCallbacks(...)} or resolved {@code .toolNames(...)}); each
     * carries a {@link ToolDefinition} with {@code name}/{@code description}/
     * {@code inputSchema}. We map those onto our framework-free core
     * {@link tools.deemwar.mochallama.ToolDefinition}. Returns an empty list
     * when no tools are present, preserving plain-text behaviour.
     */
    private List<tools.deemwar.mochallama.ToolDefinition> toCoreTools(ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions tco)) {
            return List.of();
        }
        List<ToolCallback> callbacks = tco.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }
        List<tools.deemwar.mochallama.ToolDefinition> out = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            out.add(new tools.deemwar.mochallama.ToolDefinition(
                    def.name(), def.description(), def.inputSchema()));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Outbound mapping (core -> Spring AI)
    // ---------------------------------------------------------------

    private ChatResponse toChatResponse(ChatResult result) {
        AssistantMessage message;
        if (result.hasToolCalls()) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (ToolCall c : result.toolCalls()) {
                String id = (c.id() == null || c.id().isEmpty()) ? "call_" + (i++) : c.id();
                calls.add(new AssistantMessage.ToolCall(id, "function", c.name(), c.argumentsJson()));
            }
            message = new AssistantMessage(result.text(), Map.of(), calls);
        } else {
            message = new AssistantMessage(result.text());
        }
        String finishReason = result.hasToolCalls()
                ? "tool_calls"
                : (result.finishReason() != null ? result.finishReason() : "stop");
        Generation generation = new Generation(message, metadata(finishReason));
        return new ChatResponse(List.of(generation));
    }

    private ChatResponse chunkResponse(String token, String finishReason) {
        ChatGenerationMetadata meta = finishReason != null
                ? metadata(finishReason)
                : ChatGenerationMetadata.NULL;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(token), meta)));
    }

    /** GA dropped {@code ChatGenerationMetadata.from(...)} in favour of a builder. */
    private ChatGenerationMetadata metadata(String finishReason) {
        return ChatGenerationMetadata.builder().finishReason(finishReason).build();
    }

    private GenerationOptions toGenerationOptions(ChatOptions options) {
        GenerationOptions.Builder b = GenerationOptions.builder();
        if (options != null) {
            if (options.getTemperature() != null) b.temperature(options.getTemperature());
            if (options.getTopK() != null)        b.topK(options.getTopK());
            if (options.getTopP() != null)        b.topP(options.getTopP());
            if (options.getMaxTokens() != null)   b.maxTokens(options.getMaxTokens());
            if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
                b.stop(options.getStopSequences());
            }
        }
        return b.build();
    }

    /** Maps Spring AI {@link Message}s to core {@link tools.deemwar.mochallama.Message}s. */
    static final class MochallamaMessages {
        private MochallamaMessages() {}

        static List<tools.deemwar.mochallama.Message> toCore(List<Message> in) {
            List<tools.deemwar.mochallama.Message> out = new ArrayList<>(in.size());
            for (Message m : in) {
                String role = m.getMessageType() != null ? m.getMessageType().getValue() : "user";
                out.add(new tools.deemwar.mochallama.Message(role, m.getText()));
            }
            return out;
        }
    }
}
