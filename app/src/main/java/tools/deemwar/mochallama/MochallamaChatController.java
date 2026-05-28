package tools.deemwar.mochallama;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/spring-ai")
@RequiredArgsConstructor
public class MochallamaChatController {

    private final ChatClient chatClient;
    private final ChatModel chatModel;

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        try {
            String reply = chatClient.prompt().user(message).call().content();
            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Drives the Spring AI tool-calling path end-to-end: builds a {@link Prompt}
     * carrying a sample {@code get_weather(location)} tool via Spring AI 1.0 GA's
     * {@link ToolCallingChatOptions} and calls the {@link ChatModel} directly.
     *
     * <p>{@code internalToolExecutionEnabled(false)} keeps Spring AI's
     * {@code ToolCallingManager} from auto-executing the call, so the raw tool
     * call the model proposed is surfaced back in the {@link ChatResponse} for us
     * to read. Returns whether a tool call came back, plus its name + arguments.
     */
    @PostMapping("/tool-demo")
    public ResponseEntity<?> toolDemo(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "What's the weather in Paris?");

        // A sample tool exposed to the model. The function body never runs here
        // (execution is surfaced, not auto-invoked); it only needs a valid shape.
        FunctionToolCallback<Map, String> weather = FunctionToolCallback
                .builder("get_weather", (Function<Map, String>) args -> "sunny")
                .description("Get the current weather for a given location")
                .inputSchema("""
                        {"type":"object",
                         "properties":{"location":{"type":"string","description":"City name"}},
                         "required":["location"]}""")
                .inputType(Map.class)
                .build();

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(weather)
                // Surface the proposed tool call instead of auto-executing it.
                .internalToolExecutionEnabled(false)
                .build();

        try {
            ChatResponse response = chatModel.call(new Prompt(message, options));
            AssistantMessage out = response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = out.getToolCalls();

            boolean hasToolCall = toolCalls != null && !toolCalls.isEmpty();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("toolCallRequested", hasToolCall);
            result.put("finishReason", response.getResult().getMetadata().getFinishReason());
            if (hasToolCall) {
                List<Map<String, String>> calls = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    Map<String, String> m = new java.util.LinkedHashMap<>();
                    m.put("name", tc.name());
                    m.put("arguments", tc.arguments());
                    calls.add(m);
                }
                result.put("toolCalls", calls);
            } else {
                result.put("text", out.getText());
            }
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
