package tools.deemwar.mochallama;

import java.util.List;

/**
 * Result of a single inference.
 *
 * <p>When the model decides to call one or more tools, {@link #toolCalls()} is
 * non-empty and {@link #finishReason()} is {@code "tool_calls"}; {@link #text()}
 * then holds any assistant text emitted alongside the call(s) (often empty).
 * Otherwise {@link #text()} is the assistant's reply and {@link #toolCalls()} is
 * empty.
 */
public final class ChatResult {

    private final String         text;
    private final Usage          usage;
    private final List<ToolCall> toolCalls;
    private final String         finishReason;

    public ChatResult(String text, Usage usage, List<ToolCall> toolCalls, String finishReason) {
        this.text = text == null ? "" : text;
        this.usage = usage;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.finishReason = finishReason;
    }

    public String         text()         { return text; }
    public Usage          usage()        { return usage; }
    public List<ToolCall> toolCalls()    { return toolCalls; }
    public String         finishReason() { return finishReason; }

    /** Whether the model requested at least one tool call. */
    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }

    @Override
    public String toString() {
        return "ChatResult{text='" + text + "', usage=" + usage
                + ", toolCalls=" + toolCalls + ", finishReason='" + finishReason + "'}";
    }
}
