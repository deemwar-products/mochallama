package tools.deemwar.mochallama;

/**
 * Runtime exception carrying a stable, machine-readable {@code code} alongside a
 * human-readable message — thrown by the native-bridge facade for failures the
 * caller may want to branch on.
 *
 * <p>Notable codes:
 * <ul>
 *   <li>{@code MODEL_NOT_TOOL_CAPABLE} — the model's chat template does not
 *       support tool calling, so the bridge refused to load it. mochallama only
 *       loads tool-capable GGUFs.</li>
 * </ul>
 */
public class LlamaException extends RuntimeException {

    private final String code;

    public LlamaException(String code, String message) {
        super(message);
        this.code = code;
    }

    public LlamaException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable, machine-readable error code (e.g. {@code MODEL_NOT_TOOL_CAPABLE}). */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return "LlamaException{code='" + code + "', message='" + getMessage() + "'}";
    }
}
