package tools.deemwar.mochallama;

import java.util.Objects;

/**
 * Declares a tool (function) the model may call.
 *
 * <ul>
 *   <li>{@code name} — the function name (e.g. {@code get_weather})</li>
 *   <li>{@code description} — a natural-language description of what the tool does</li>
 *   <li>{@code parametersJsonSchema} — the JSON-schema object describing the
 *       function's parameters, as a JSON string (e.g.
 *       {@code {"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}})</li>
 * </ul>
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersJsonSchema;

    public ToolDefinition(String name, String description, String parametersJsonSchema) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.parametersJsonSchema =
                (parametersJsonSchema == null || parametersJsonSchema.isBlank())
                        ? "{\"type\":\"object\",\"properties\":{}}"
                        : parametersJsonSchema;
    }

    public String name()                 { return name; }
    public String description()          { return description; }
    public String parametersJsonSchema() { return parametersJsonSchema; }

    @Override
    public String toString() {
        return "ToolDefinition{name='" + name + "', description='" + description + "'}";
    }
}
