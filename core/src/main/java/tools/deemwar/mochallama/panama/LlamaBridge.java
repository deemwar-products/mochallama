package tools.deemwar.mochallama.panama;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;

/**
 * Low-level Panama FFM bindings for {@code libllamabridge.dylib}.
 *
 * <p>Maps the symbols declared in {@code llamabridge.h}:
 * <pre>
 *   llb_chat_t* llb_chat_create(const char*, llb_event_cb, void*);
 *   const char* llb_chat_infer (llb_chat_t*, const char*);
 *   const char* llb_chat_infer_stream(llb_chat_t*, const char*, llb_token_cb, void*);
 *   void        llb_string_free(const char*);
 *   void        llb_chat_destroy(llb_chat_t*);
 *   const char* llb_version(void);
 * </pre>
 *
 * <p>The class is initialised eagerly: touching any static field triggers
 * {@link NativeLoader#load()} (which extracts and dlopens the bundled
 * dylibs) followed by symbol lookup.
 */
public final class LlamaBridge {

    /** {@code ADDRESS} with an unbounded reinterpret window — used for C strings we own. */
    public static final AddressLayout C_STRING =
            ValueLayout.ADDRESS.withTargetLayout(ValueLayout.JAVA_BYTE);

    public static final Linker LINKER = Linker.nativeLinker();

    /** Symbols visible to {@link System#load} (i.e. everything {@link NativeLoader} loaded). */
    public static final SymbolLookup SYMBOLS;

    // ---------------------------------------------------------------
    // FunctionDescriptors
    // ---------------------------------------------------------------

    /** {@code void on_event(const char* event_json, void* user_data)} — upcall stub layout. */
    public static final FunctionDescriptor EVENT_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS);

    /** {@code void token_cb(const char* token_piece, void* user_data)} — streaming upcall stub layout. */
    public static final FunctionDescriptor TOKEN_CB_DESCRIPTOR = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,   // const char* token_piece
            ValueLayout.ADDRESS);  // void* user_data

    private static final FunctionDescriptor CHAT_CREATE_DESC = FunctionDescriptor.of(
            ValueLayout.ADDRESS,   // return: llb_chat_t*
            ValueLayout.ADDRESS,   // const char* gguf_path
            ValueLayout.ADDRESS,   // llb_event_cb event_cb
            ValueLayout.ADDRESS);  // void* user_data

    private static final FunctionDescriptor CHAT_INFER_DESC = FunctionDescriptor.of(
            ValueLayout.ADDRESS,   // return: const char*
            ValueLayout.ADDRESS,   // llb_chat_t* chat
            ValueLayout.ADDRESS);  // const char* request_json

    private static final FunctionDescriptor CHAT_INFER_STREAM_DESC = FunctionDescriptor.of(
            ValueLayout.ADDRESS,   // return: const char*
            ValueLayout.ADDRESS,   // llb_chat_t* chat
            ValueLayout.ADDRESS,   // const char* request_json
            ValueLayout.ADDRESS,   // llb_token_cb token_cb
            ValueLayout.ADDRESS);  // void* user_data

    private static final FunctionDescriptor STRING_FREE_DESC = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS);

    private static final FunctionDescriptor CHAT_DESTROY_DESC = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS);

    private static final FunctionDescriptor VERSION_DESC = FunctionDescriptor.of(
            ValueLayout.ADDRESS);

    // ---------------------------------------------------------------
    // MethodHandles
    // ---------------------------------------------------------------

    public static final MethodHandle CHAT_CREATE;
    public static final MethodHandle CHAT_INFER;
    public static final MethodHandle CHAT_INFER_STREAM;
    public static final MethodHandle STRING_FREE;
    public static final MethodHandle CHAT_DESTROY;
    public static final MethodHandle VERSION;

    static {
        // Force the dylibs into the process before any symbol lookup.
        NativeLoader.load();

        SYMBOLS = SymbolLookup.loaderLookup().or(LINKER.defaultLookup());

        CHAT_CREATE       = downcall("llb_chat_create",       CHAT_CREATE_DESC);
        CHAT_INFER        = downcall("llb_chat_infer",        CHAT_INFER_DESC);
        CHAT_INFER_STREAM = downcall("llb_chat_infer_stream", CHAT_INFER_STREAM_DESC);
        STRING_FREE       = downcall("llb_string_free",       STRING_FREE_DESC);
        CHAT_DESTROY      = downcall("llb_chat_destroy",      CHAT_DESTROY_DESC);
        VERSION           = downcall("llb_version",           VERSION_DESC);
    }

    private LlamaBridge() {}

    /** Ensure the class (and therefore its static initialiser) has run. */
    public static void ensureLoaded() {
        // Touching any static field triggers <clinit> exactly once.
        if (CHAT_CREATE == null) {
            throw new IllegalStateException("LlamaBridge failed to initialise");
        }
    }

    /**
     * Read a NUL-terminated C string from a native pointer, returning an empty
     * string if {@code addr} is NULL.
     */
    public static String readCString(MemorySegment addr) {
        if (addr == null || addr.equals(MemorySegment.NULL)) return "";
        // The returned pointer comes from the bridge; we don't know its size,
        // so reinterpret with an effectively-unbounded window.
        return addr.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Convenience: read and return the bridge version string. */
    public static String version() {
        try {
            MemorySegment p = (MemorySegment) VERSION.invokeExact();
            return readCString(p);
        } catch (Throwable t) {
            throw new RuntimeException("llb_version failed", t);
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return SYMBOLS.find(name)
                .map(addr -> LINKER.downcallHandle(addr, desc))
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "Symbol not found in libllamabridge: " + name));
    }

    // ---------------------------------------------------------------
    // Streaming upcall stub
    // ---------------------------------------------------------------

    /** MethodHandle to {@link #invokeTokenConsumer}, bound once for stub creation. */
    private static final MethodHandle TOKEN_TRAMPOLINE;

    static {
        try {
            TOKEN_TRAMPOLINE = MethodHandles.lookup().findStatic(
                    LlamaBridge.class, "invokeTokenConsumer",
                    MethodType.methodType(void.class, Consumer.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Trampoline matching {@code void token_cb(const char*, void*)}: reads the
     * NUL-terminated token piece and forwards it to the bound {@link Consumer}.
     * The {@code userData} pointer is ignored — the consumer is captured by the
     * bound MethodHandle instead.
     */
    @SuppressWarnings("unused")
    private static void invokeTokenConsumer(Consumer<String> consumer,
                                            MemorySegment tokenPiece,
                                            MemorySegment userData) {
        // Exceptions must never cross back into native code: swallow + ignore.
        try {
            consumer.accept(readCString(tokenPiece));
        } catch (Throwable ignored) {
            // best-effort streaming; don't unwind into the C call
        }
    }

    /**
     * Build a C function pointer (upcall stub) for the {@code llb_token_cb}
     * signature that dispatches to {@code onToken}. The stub is allocated in
     * {@code arena}; it is valid until that arena is closed.
     */
    public static MemorySegment tokenCallbackStub(Arena arena, Consumer<String> onToken) {
        MethodHandle bound = TOKEN_TRAMPOLINE.bindTo(onToken);
        return LINKER.upcallStub(bound, TOKEN_CB_DESCRIPTOR, arena);
    }
}
