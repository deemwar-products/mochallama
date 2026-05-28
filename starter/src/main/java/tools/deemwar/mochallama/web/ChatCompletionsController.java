package tools.deemwar.mochallama.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.ToolCall;
import tools.deemwar.mochallama.ToolDefinition;
import tools.deemwar.mochallama.Usage;
import tools.deemwar.mochallama.service.LlamaCppService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/v1")
public class ChatCompletionsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlamaCppService llamaCppService;
    private final ExecutorService streamExecutor;

    /**
     * @param streamExecutor daemon pool that runs the blocking, {@code synchronized}
     *                       bridge call off the servlet thread for SSE streaming
     *                       (supplied by {@code MochallamaAutoConfiguration}).
     */
    public ChatCompletionsController(LlamaCppService llamaCppService, ExecutorService streamExecutor) {
        this.llamaCppService = llamaCppService;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestBody ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "messages is required"));
        }
        if (!llamaCppService.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "model loading", "state", llamaCppService.getState().name()));
        }

        List<Message> messages = toMessages(request.getMessages());
        List<ToolDefinition> tools = toToolDefinitions(request.getTools());
        GenerationOptions opts = buildOptions(request);
        String modelName = request.getModel() != null ? request.getModel() : llamaCppService.getModelId();

        if (Boolean.TRUE.equals(request.getStream())) {
            return stream(messages, tools, opts, modelName);
        }
        return nonStream(messages, tools, opts, modelName);
    }

    // ---------------------------------------------------------------
    // Non-streaming
    // ---------------------------------------------------------------

    private ResponseEntity<?> nonStream(List<Message> messages, List<ToolDefinition> tools,
                                        GenerationOptions opts, String modelName) {
        ChatResult result = llamaCppService.chat(messages, tools, opts);

        ChatCompletionResponse.Message assistant = new ChatCompletionResponse.Message();
        assistant.setRole("assistant");

        String finishReason;
        if (result.hasToolCalls()) {
            assistant.setContent(result.text().isEmpty() ? null : result.text());
            assistant.setToolCalls(toResponseToolCalls(result.toolCalls()));
            finishReason = "tool_calls";
        } else {
            assistant.setContent(result.text());
            finishReason = result.finishReason() != null ? result.finishReason() : "stop";
        }

        Usage u = result.usage() != null ? result.usage() : new Usage(0, 0, 0);
        ChatCompletionResponse response = new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                System.currentTimeMillis() / 1000L,
                modelName,
                List.of(new ChatCompletionResponse.Choice(0, assistant, finishReason)),
                new ChatCompletionResponse.Usage(u.promptTokens(), u.completionTokens(), u.totalTokens())
        );
        return ResponseEntity.ok(response);
    }

    private List<ChatCompletionResponse.ToolCall> toResponseToolCalls(List<ToolCall> calls) {
        List<ChatCompletionResponse.ToolCall> out = new ArrayList<>(calls.size());
        int i = 0;
        for (ToolCall c : calls) {
            String id = (c.id() == null || c.id().isEmpty()) ? "call_" + (i++) : c.id();
            out.add(new ChatCompletionResponse.ToolCall(
                    id, "function",
                    new ChatCompletionResponse.Function(c.name(), c.argumentsJson())));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // Streaming (SSE) — bridge call runs off the servlet thread
    // ---------------------------------------------------------------

    private SseEmitter stream(List<Message> messages, List<ToolDefinition> tools,
                              GenerationOptions opts, String modelName) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; bounded by generation
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = System.currentTimeMillis() / 1000L;

        streamExecutor.execute(() -> {
            try {
                // First chunk announces the assistant role (OpenAI convention).
                sendChunk(emitter, roleChunk(id, created, modelName));

                ChatResult result = llamaCppService.chatStream(messages, tools, opts, token -> {
                    try {
                        sendChunk(emitter, contentChunk(id, created, modelName, token));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                String finishReason = result.hasToolCalls()
                        ? "tool_calls"
                        : (result.finishReason() != null ? result.finishReason() : "stop");
                sendChunk(emitter, finishChunk(id, created, modelName, finishReason));
                emitter.send(SseEmitter.event().data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendChunk(SseEmitter emitter, ChatCompletionResponse.Chunk chunk) throws IOException {
        emitter.send(SseEmitter.event().data(MAPPER.writeValueAsString(chunk)));
    }

    private ChatCompletionResponse.Chunk roleChunk(String id, long created, String model) {
        ChatCompletionResponse.Delta delta = new ChatCompletionResponse.Delta();
        delta.setRole("assistant");
        delta.setContent("");
        return chunk(id, created, model, delta, null);
    }

    private ChatCompletionResponse.Chunk contentChunk(String id, long created, String model, String token) {
        ChatCompletionResponse.Delta delta = new ChatCompletionResponse.Delta();
        delta.setContent(token);
        return chunk(id, created, model, delta, null);
    }

    private ChatCompletionResponse.Chunk finishChunk(String id, long created, String model, String finishReason) {
        return chunk(id, created, model, new ChatCompletionResponse.Delta(), finishReason);
    }

    private ChatCompletionResponse.Chunk chunk(String id, long created, String model,
                                               ChatCompletionResponse.Delta delta, String finishReason) {
        ChatCompletionResponse.ChunkChoice choice =
                new ChatCompletionResponse.ChunkChoice(0, delta, finishReason);
        return new ChatCompletionResponse.Chunk(
                id, "chat.completion.chunk", created, model, List.of(choice));
    }

    // ---------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------

    private List<Message> toMessages(List<ChatCompletionRequest.Message> in) {
        List<Message> out = new ArrayList<>(in.size());
        for (ChatCompletionRequest.Message m : in) {
            String role = m.getRole() != null ? m.getRole() : "user";
            out.add(new Message(role, m.getContent()));
        }
        return out;
    }

    private List<ToolDefinition> toToolDefinitions(List<ChatCompletionRequest.Tool> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        List<ToolDefinition> out = new ArrayList<>(tools.size());
        for (ChatCompletionRequest.Tool t : tools) {
            ChatCompletionRequest.Function fn = t.getFunction();
            if (fn == null || fn.getName() == null) continue;
            String schema = fn.getParameters() != null ? fn.getParameters().toString() : null;
            out.add(new ToolDefinition(fn.getName(), fn.getDescription(), schema));
        }
        return out;
    }

    /** Start from server-side defaults; overlay any per-request values. */
    private GenerationOptions buildOptions(ChatCompletionRequest req) {
        GenerationOptions d = llamaCppService.defaultOptions();
        GenerationOptions.Builder b = GenerationOptions.builder()
                .temperature(req.getTemperature() != null ? req.getTemperature() : d.temperature())
                .topK(req.getTopK() != null ? req.getTopK() : d.topK())
                .topP(req.getTopP() != null ? req.getTopP() : d.topP())
                .minP(req.getMinP() != null ? req.getMinP() : d.minP())
                .maxTokens(req.getMaxTokens() != null ? req.getMaxTokens() : d.maxTokens())
                .repeatPenalty(req.getRepeatPenalty() != null ? req.getRepeatPenalty() : d.repeatPenalty())
                .seed(req.getSeed() != null ? req.getSeed() : d.seed())
                .stop(req.getStop());

        String toolChoice = toolChoice(req.getToolChoice());
        if (toolChoice != null) b.toolChoice(toolChoice);
        return b.build();
    }

    /** OpenAI tool_choice may be a string ("auto"/"none"/"required") or an object. */
    private String toolChoice(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        // Object form {"type":"function","function":{"name":...}} → force that call.
        return "required";
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "object", "list",
                "data", List.of(Map.of("id", llamaCppService.getModelId(), "object", "model"))
        );
    }
}
