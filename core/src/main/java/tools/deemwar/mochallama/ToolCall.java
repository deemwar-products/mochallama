package tools.deemwar.mochallama;

/**
 * A tool call requested by the model.
 *
 * <ul>
 *   <li>{@code id} — an identifier for this call (may be empty if the model
 *       did not assign one)</li>
 *   <li>{@code name} — the name of the tool to invoke</li>
 *   <li>{@code argumentsJson} — the call arguments as a JSON object string</li>
 * </ul>
 */
public final class ToolCall {

    private final String id;
    private final String name;
    private final String argumentsJson;

    public ToolCall(String id, String name, String argumentsJson) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
    }

    public String id()            { return id; }
    public String name()          { return name; }
    public String argumentsJson() { return argumentsJson; }

    @Override
    public String toString() {
        return "ToolCall{id='" + id + "', name='" + name + "', arguments=" + argumentsJson + "}";
    }
}
