package tools.deemwar.mochallama;

/**
 * Tool-calling capability metadata for a GGUF model, derived from its chat
 * template(s) by the native bridge ({@code llb_model_info}).
 *
 * <p>mochallama only loads models that support tool calling. This record lets
 * callers pre-flight a model before committing to a (potentially expensive)
 * load:
 * <pre>{@code
 *   ModelInfo info = ChatEngine.inspect(path);
 *   if (!info.supportsTools()) { reject(...); }
 * }</pre>
 *
 * @param supportsTools       the chat template can describe a {@code tools}
 *                            list to the model (caps.supports_tools). Computed
 *                            from the {@code tool_use} template variant when the
 *                            GGUF ships one, else the default template.
 * @param supportsToolCalls   the chat template can round-trip assistant
 *                            {@code tool_calls} (caps.supports_tool_calls).
 * @param hasToolUseTemplate  the GGUF defines a
 *                            {@code tokenizer.chat_template.tool_use} variant.
 * @param chatFormat          diagnostic parser-family tag
 *                            ({@code CONTENT_ONLY|PEG_SIMPLE|PEG_NATIVE|PEG_GEMMA4|UNKNOWN});
 *                            NOT the capability gate. {@code null} when inspection failed.
 * @param error               {@code null} on success, otherwise a short reason
 *                            (e.g. {@code "model_not_found"}, {@code "load_model"}).
 */
public record ModelInfo(
        boolean supportsTools,
        boolean supportsToolCalls,
        boolean hasToolUseTemplate,
        String  chatFormat,
        String  error) {

    /** Whether inspection succeeded (no error reported by the bridge). */
    public boolean ok() {
        return error == null;
    }

    /**
     * The capability gate mochallama enforces: a model is tool-capable only
     * when its template both describes tools AND round-trips tool calls.
     */
    public boolean toolCapable() {
        return supportsTools && supportsToolCalls;
    }
}
