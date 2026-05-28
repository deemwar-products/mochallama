package tools.deemwar.mochallama.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * OpenAI chat-completions request. Carries the full sampling parameter set plus
 * tool declarations; unknown fields are ignored for forward compatibility.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionRequest {
    private String model;
    private List<Message> messages;

    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private Float temperature;
    @JsonProperty("top_k")
    private Integer topK;
    @JsonProperty("top_p")
    private Float topP;
    @JsonProperty("min_p")
    private Float minP;
    @JsonProperty("repeat_penalty")
    private Float repeatPenalty;
    private Long seed;
    private List<String> stop;
    private Boolean stream;

    private List<Tool> tools;
    @JsonProperty("tool_choice")
    private JsonNode toolChoice;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
    }

    /** OpenAI tool wrapper: {@code {"type":"function","function":{...}}}. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Tool {
        private String type;
        private Function function;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Function {
        private String name;
        private String description;
        /** JSON-schema object for the function parameters. */
        private JsonNode parameters;
    }
}
