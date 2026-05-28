package tools.deemwar.mochallama.panama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import tools.deemwar.mochallama.ChatResult;
import tools.deemwar.mochallama.GenerationOptions;
import tools.deemwar.mochallama.LlamaException;
import tools.deemwar.mochallama.Message;
import tools.deemwar.mochallama.ModelInfo;
import tools.deemwar.mochallama.ToolCall;
import tools.deemwar.mochallama.ToolDefinition;
import tools.deemwar.mochallama.Usage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * High-level façade around {@link LlamaBridge}: load a GGUF, chat against it,
 * release it.
 *
 * <p>The engine owns a confined {@link Arena} for its lifetime (the GGUF
 * handle pointer lives there) and opens a fresh confined arena per call to
 * marshal the request JSON (and, for streaming, the token-callback upcall stub).
 *
 * <p>Thread safety: this class does NOT lock; callers must serialise calls
 * (the surrounding {@code LlamaCppService} already does).
 */
public final class ChatEngine implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Arena         arena;
    private final MemorySegment handle;
    private volatile boolean    closed;

    private ChatEngine(Arena arena, MemorySegment handle) {
        this.arena  = arena;
        this.handle = handle;
    }

    /**
     * Inspect a GGUF's tool-calling capability WITHOUT creating an engine.
     *
     * <p>Loads only the model metadata + chat templates in the bridge (no
     * inference context) and returns the parsed {@link ModelInfo}. Use this to
     * pre-flight a model before {@link #load(Path)} — but note that
     * {@code load} enforces the same gate, so this is an optimisation/UX hook,
     * not a security boundary.
     *
     * @return parsed {@link ModelInfo}; {@link ModelInfo#error()} is non-null
     *         when the bridge could not inspect the file.
     */
    public static ModelInfo inspect(Path gguf) {
        LlamaBridge.ensureLoaded();
        if (gguf == null) {
            throw new IllegalArgumentException("gguf path must not be null");
        }

        String json;
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment cPath = callArena.allocateFrom(
                    gguf.toAbsolutePath().toString(), StandardCharsets.UTF_8);

            MemorySegment cInfo = (MemorySegment) LlamaBridge.MODEL_INFO.invokeExact(cPath);
            if (cInfo == null || cInfo.equals(MemorySegment.NULL)) {
                // Contract says never-NULL; treat as a hard failure if it happens.
                throw new LlamaException("MODEL_INFO_FAILED",
                        "llb_model_info returned NULL for " + gguf);
            }
            json = LlamaBridge.readCString(cInfo);
            // Bridge contract: caller frees the returned string.
            LlamaBridge.STRING_FREE.invokeExact(cInfo);
        } catch (LlamaException le) {
            throw le;
        } catch (Throwable t) {
            throw new RuntimeException("llb_model_info failed for " + gguf, t);
        }

        try {
            BridgeModelInfo bmi = MAPPER.readValue(json, BridgeModelInfo.class);
            return new ModelInfo(
                    bmi.supportsTools,
                    bmi.supportsToolCalls,
                    bmi.hasToolUseTemplate,
                    bmi.chatFormat,
                    bmi.error);
        } catch (Exception e) {
            throw new RuntimeException(
                    "failed to parse model_info JSON for " + gguf + ": " + json, e);
        }
    }

    /**
     * Load a GGUF model from disk and return a ready-to-use engine.
     *
     * <p>mochallama only loads tool-capable models: if the bridge rejects the
     * model because its chat template does not support tool calling (signalled
     * via the {@code create_failure:tools_unsupported} event), this throws
     * {@link LlamaException} with code {@code MODEL_NOT_TOOL_CAPABLE}.
     *
     * @throws LlamaException        with code {@code MODEL_NOT_TOOL_CAPABLE} when
     *                               the model does not support tool calling
     * @throws IllegalStateException if the bridge returns NULL for any other
     *                               reason (bad path, OOM, init failure…)
     */
    public static ChatEngine load(Path gguf) {
        LlamaBridge.ensureLoaded();

        // Capture the last create_failure:* event so we can distinguish the
        // tool-unsupported rejection from other load failures. The native bridge
        // emits "create_failure:tools_unsupported" before returning NULL when the
        // chat template is not tool-capable.
        String[] lastFailure = new String[] { null };

        Arena engineArena = Arena.ofConfined();
        MemorySegment handle = MemorySegment.NULL;
        Throwable callError = null;
        try {
            MemorySegment cPath = engineArena.allocateFrom(
                    gguf.toAbsolutePath().toString(), StandardCharsets.UTF_8);

            // Keep the event upcall stub in the engine arena (closed only on the
            // failure paths below). Allocating it in a nested arena that is
            // closed immediately after the downcall has proven to destabilise
            // the FFM upcall return path on this JVM, so we keep one arena.
            MemorySegment cb = LlamaBridge.eventCallbackStub(engineArena, evt -> {
                if (evt != null && evt.startsWith("create_failure:")) {
                    lastFailure[0] = evt;
                }
            });

            handle = (MemorySegment) LlamaBridge.CHAT_CREATE
                    .invokeExact(cPath, cb, MemorySegment.NULL);
        } catch (Throwable t) {
            callError = t;
        }

        // All throwing happens here, OUTSIDE the frame that performed the
        // downcall+upcall, to avoid an FFM exception-return crash on this JVM.
        if (callError != null) {
            engineArena.close();
            throw new RuntimeException("llb_chat_create failed", callError);
        }
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            engineArena.close();
            String failure = lastFailure[0];
            if ("create_failure:tools_unsupported".equals(failure)) {
                throw new LlamaException("MODEL_NOT_TOOL_CAPABLE",
                        "model does not support tool calling: " + gguf);
            }
            throw new IllegalStateException(
                    "llb_chat_create returned NULL for " + gguf
                            + (failure != null ? " (" + failure + ")" : ""));
        }

        return new ChatEngine(engineArena, handle);
    }

    // ---------------------------------------------------------------
    // Back-compat simple API
    // ---------------------------------------------------------------

    /**
     * Run a single-turn inference and return the assistant text. Retained for
     * existing callers (e.g. {@code LlamaCppService}); delegates to the rich
     * {@link #chat(List, List, GenerationOptions)} with a single user message.
     */
    public String chat(String prompt, int maxTokens, double temperature) {
        GenerationOptions opts = GenerationOptions.builder()
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
        ChatResult result = chat(List.of(Message.user(prompt)), List.of(), opts);
        return result.text();
    }

    // ---------------------------------------------------------------
    // Rich API
    // ---------------------------------------------------------------

    /**
     * Run a single inference over a full message list, optionally exposing tools.
     * Returns the assistant text, real token usage and any parsed tool calls.
     */
    public ChatResult chat(List<Message> messages, List<ToolDefinition> tools, GenerationOptions opts) {
        return doInfer(messages, tools, opts, null);
    }

    /**
     * Like {@link #chat}, but streams each decoded token piece to {@code onToken}
     * as it is produced. Returns the same final {@link ChatResult} (with usage +
     * parsed tool calls) once generation completes.
     */
    public ChatResult chatStream(List<Message> messages, List<ToolDefinition> tools,
                                 GenerationOptions opts, Consumer<String> onToken) {
        return doInfer(messages, tools, opts, onToken);
    }

    private ChatResult doInfer(List<Message> messages, List<ToolDefinition> tools,
                               GenerationOptions opts, Consumer<String> onToken) {
        if (closed) {
            throw new IllegalStateException("ChatEngine is closed");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must be non-empty");
        }
        if (opts == null) opts = GenerationOptions.defaults();

        try {
            String reqJson = MAPPER.writeValueAsString(buildRequest(messages, tools, opts));

            String respJson;
            try (Arena callArena = Arena.ofConfined()) {
                MemorySegment cReq = callArena.allocateFrom(reqJson, StandardCharsets.UTF_8);

                MemorySegment cResp;
                if (onToken == null) {
                    cResp = (MemorySegment) LlamaBridge.CHAT_INFER.invokeExact(handle, cReq);
                } else {
                    // Upcall stub lives in the call arena: valid for the whole
                    // (synchronous) duration of llb_chat_infer_stream.
                    MemorySegment stub = LlamaBridge.tokenCallbackStub(callArena, onToken);
                    cResp = (MemorySegment) LlamaBridge.CHAT_INFER_STREAM
                            .invokeExact(handle, cReq, stub, MemorySegment.NULL);
                }

                if (cResp == null || cResp.equals(MemorySegment.NULL)) {
                    throw new RuntimeException("bridge inference returned NULL");
                }

                respJson = LlamaBridge.readCString(cResp);
                // Bridge contract: caller frees the returned string.
                LlamaBridge.STRING_FREE.invokeExact(cResp);
            }

            BridgeResponse resp = MAPPER.readValue(respJson, BridgeResponse.class);
            if ("error".equals(resp.type)) {
                String code = resp.error != null ? resp.error.code : "UNKNOWN";
                String msg  = resp.error != null ? resp.error.message : "unknown error";
                throw new RuntimeException("bridge error " + code + ": " + msg);
            }
            return toChatResult(resp);
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException("chat inference failed", t);
        }
    }

    private static BridgeRequest buildRequest(List<Message> messages,
                                              List<ToolDefinition> tools,
                                              GenerationOptions opts) {
        List<BridgeMessage> msgs = new ArrayList<>(messages.size());
        for (Message m : messages) {
            msgs.add(new BridgeMessage(m.role(), m.content()));
        }

        List<BridgeTool> bridgeTools = null;
        if (tools != null && !tools.isEmpty()) {
            bridgeTools = new ArrayList<>(tools.size());
            for (ToolDefinition t : tools) {
                bridgeTools.add(BridgeTool.from(t));
            }
        }

        BridgeRequest req = new BridgeRequest();
        req.messages       = msgs;
        req.tools          = bridgeTools;
        req.toolChoice     = (bridgeTools == null) ? null : opts.toolChoice();
        req.temperature    = opts.temperature();
        req.topK           = opts.topK();
        req.topP           = opts.topP();
        req.minP           = opts.minP();
        req.maxTokens      = opts.maxTokens();
        req.repeatPenalty  = opts.repeatPenalty();
        req.seed           = opts.hasSeed() ? opts.seed() : null;
        req.stop           = opts.stop().isEmpty() ? null : opts.stop();
        return req;
    }

    private static ChatResult toChatResult(BridgeResponse resp) {
        List<ToolCall> calls = new ArrayList<>();
        if (resp.toolCalls != null) {
            for (BridgeToolCall tc : resp.toolCalls) {
                calls.add(new ToolCall(tc.id, tc.name, tc.arguments));
            }
        }
        Usage usage = (resp.usage == null)
                ? new Usage(0, 0, 0)
                : new Usage(resp.usage.promptTokens, resp.usage.completionTokens, resp.usage.totalTokens);
        String text = resp.text != null ? resp.text : "";
        return new ChatResult(text, usage, calls, resp.finishReason);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            LlamaBridge.CHAT_DESTROY.invokeExact(handle);
        } catch (Throwable ignored) {
            // Swallow — destruction is best-effort.
        } finally {
            arena.close();
        }
    }

    // ---------------------------------------------------------------
    // Wire DTOs — kept package-private; not part of the public API.
    // ---------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class BridgeMessage {
        public final String role;
        public final String content;
        BridgeMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** OpenAI-shaped tool wrapper: {@code {"type":"function","function":{...}}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class BridgeTool {
        public final String type = "function";
        public final BridgeFunction function;
        BridgeTool(BridgeFunction function) { this.function = function; }

        static BridgeTool from(ToolDefinition t) {
            // parameters is a JSON-schema string; embed it as a parsed JSON node so
            // it stays an object rather than a quoted string in the request.
            return new BridgeTool(new BridgeFunction(
                    t.name(), t.description(), rawJson(t.parametersJsonSchema())));
        }

        private static com.fasterxml.jackson.databind.JsonNode rawJson(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (Exception e) {
                // Fall back to an empty object schema on malformed input.
                return MAPPER.createObjectNode();
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class BridgeFunction {
        public final String name;
        public final String description;
        public final Object parameters;
        BridgeFunction(String name, String description, Object parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class BridgeRequest {
        public List<BridgeMessage> messages;
        public List<BridgeTool>    tools;
        @JsonProperty("tool_choice")    public String  toolChoice;
        public double                   temperature;
        @JsonProperty("top_k")          public int     topK;
        @JsonProperty("top_p")          public double  topP;
        @JsonProperty("min_p")          public double  minP;
        @JsonProperty("max_tokens")     public int     maxTokens;
        @JsonProperty("repeat_penalty") public double  repeatPenalty;
        public Long                     seed;
        public List<String>            stop;
    }

    static final class BridgeUsage {
        @JsonProperty("prompt_tokens")     public int promptTokens;
        @JsonProperty("completion_tokens") public int completionTokens;
        @JsonProperty("total_tokens")      public int totalTokens;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static final class BridgeToolCall {
        public String id;
        public String name;
        public String arguments;
    }

    static final class BridgeError {
        public String code;
        public String message;
    }

    /** Wire shape of {@code llb_model_info}'s JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static final class BridgeModelInfo {
        @JsonProperty("supports_tools")        public boolean supportsTools;
        @JsonProperty("supports_tool_calls")   public boolean supportsToolCalls;
        @JsonProperty("has_tool_use_template") public boolean hasToolUseTemplate;
        @JsonProperty("chat_format")           public String  chatFormat;
        public String                          error;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static final class BridgeResponse {
        public String               type;
        public String               text;
        @JsonProperty("tool_calls")    public List<BridgeToolCall> toolCalls;
        @JsonProperty("finish_reason") public String               finishReason;
        public BridgeUsage           usage;
        public BridgeError           error;
    }
}
