---
title: "Calling llama.cpp from Java without JNI — a Project Panama FFM walkthrough"
date: 2026-06-12
tags: [java, jvm, project-panama, ffm, jni, llama.cpp, native]
canonical: https://deemwar-products.github.io/mochallama/why
---

# Calling llama.cpp from Java without JNI — a Project Panama FFM walkthrough

This is the post I wanted to read before binding a C library from Java in 2026. It's about how
[mochallama](https://github.com/deemwar-products/mochallama) talks to llama.cpp with the **Foreign
Function & Memory API** (FFM, GA in JDK 22) instead of JNI — and why that's now the better default.

## The JNI tax

For 25 years JNI was the only door. The tax it charges is familiar to anyone who's paid it: you write
a `native` method, run `javah`/`javac -h` to generate a header, write C glue that mirrors mangled
method names, compile a shared library, then fight `UnsatisfiedLinkError` and `LD_LIBRARY_PATH` at
runtime. Worse, the binding is *coupled to the upstream API* — bind `llama.h` directly and every
llama.cpp release can break your shim. And a native fault still takes the whole JVM down, because JNI
isn't a safety boundary either.

FFM removes most of that. No `javah`, no generated headers, no `native` keyword in your code. A C
function becomes a plain `MethodHandle`; native memory becomes a `MemorySegment` with a *lifetime* you
control via an `Arena`. The single permission knob is `--enable-native-access`.

## Design move #1: don't bind `llama.h` — bind a tiny JSON ABI

The most important decision isn't FFM vs JNI; it's the *shape* of the boundary. Instead of mirroring
llama.cpp's evolving C++ surface, mochallama ships a thin (~700-line) `extern "C"` bridge over
llama.cpp's `common_chat`, exposing a handful of stable symbols with a JSON-in / JSON-out contract:

```c
const char* llb_version(void);
void*       llb_chat_create(const char* model_path_json);     // returns an opaque engine handle
const char* llb_chat_infer(void* engine, const char* request_json);          // returns result JSON
const char* llb_chat_infer_stream(void* engine, const char* request_json,
                                  llb_token_cb on_token, void* user_data);
const char* llb_model_info(const char* model_path);
void        llb_string_free(const char* s);                   // caller frees every returned string
void        llb_chat_destroy(void* engine);
```

Because the request and response are just JSON strings, **a llama.cpp upgrade rebuilds the bridge
without touching a line of Java.** The Java side never knows the shape of a `llama_context`. That's the
property you want from any FFI binding.

## Wiring a C function to a MethodHandle

Linking is three objects: a `Linker`, a `SymbolLookup`, and a `FunctionDescriptor` that says what the
C signature looks like in terms of `ValueLayout`s.

```java
Arena arena = Arena.ofShared();                       // lifetime of the whole library
Linker linker = Linker.nativeLinker();
SymbolLookup lib = SymbolLookup.libraryLookup("llamabridge", arena);

// const char* llb_version(void);
MethodHandle versionFn = linker.downcallHandle(
        lib.find("llb_version").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS));  // returns a C char*

MemorySegment ptr = (MemorySegment) versionFn.invoke();
// the returned pointer is zero-length; reinterpret to read the C string
String version = ptr.reinterpret(Long.MAX_VALUE).getString(0);
```

`getString(0)` reads a NUL-terminated UTF-8 string. The `reinterpret` is the one sharp edge: a pointer
returned from C has no size, so you widen it before reading. (You're telling the JVM "trust me, this is
a valid C string" — the same trust you extend in any FFI.)

## A full call: JSON in, JSON out, free the result

Inference is one downcall. We allocate the request JSON as a C string in a *confined* arena, invoke,
read the result, and — per the ABI's ownership rule — free the string the bridge handed back.

```java
// const char* llb_chat_infer(void* engine, const char* request_json);
MethodHandle inferFn = linker.downcallHandle(
        lib.find("llb_chat_infer").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

String run(MemorySegment engine, String requestJson) throws Throwable {
    try (Arena call = Arena.ofConfined()) {           // scoped to this one call
        MemorySegment req = call.allocateFrom(requestJson);     // UTF-8 C string
        MemorySegment res = (MemorySegment) inferFn.invoke(engine, req);
        String json = res.reinterpret(Long.MAX_VALUE).getString(0);
        stringFreeFn.invoke(res);                     // llb_string_free — we own it
        return json;
    }                                                 // req is freed here, deterministically
}
```

Two things JNI never gave you for free are happening here. First, `Arena.ofConfined()` scopes the
request buffer to exactly this call — when the try-block exits, the native memory is released
deterministically, no GC, no manual `free` of *our* allocation. Second, the engine handle lives in a
longer-lived arena, so its lifetime is explicit and tied to a Java object, not to a pinned raw pointer.

## Streaming: an upcall stub for the token callback

For `stream:true`, the bridge calls back per token. FFM turns a Java method into a C function pointer
with an **upcall stub**:

```java
// typedef void (*llb_token_cb)(const char* token_utf8, void* user_data);
FunctionDescriptor cbDesc =
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);

MethodHandle onToken = MethodHandles.lookup().findStatic(
        MyBridge.class, "onToken",
        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));

try (Arena cbArena = Arena.ofConfined()) {
    MemorySegment cbPtr = linker.upcallStub(onToken, cbDesc, cbArena);
    inferStreamFn.invoke(engine, req, cbPtr, MemorySegment.NULL);   // C calls Java per token
}

static void onToken(MemorySegment tok, MemorySegment userData) {
    String piece = tok.reinterpret(Long.MAX_VALUE).getString(0);
    // forward to your SSE emitter / consumer
}
```

No `native` methods, no registration table — a `MethodHandle` becomes a callable C function pointer,
and the stub's lifetime is (again) an `Arena`.

## The honest caveats

FFM is a better *binding*, not a sandbox. A genuine segfault inside llama.cpp is still fatal to the
process — FFM doesn't isolate native code. What it removes is the JNI binding surface: the header
generation, the hand-written glue, the loader fragility, the manual pointer pinning. You also pay for
it in JDK version: FFM is preview/incubator before JDK 22, and mochallama targets 22 precisely so it
ships on the GA API rather than a moving one. And `--enable-native-access=ALL-UNNAMED` is required —
that's the single, auditable permission for "this code may call native functions."

## The payoff

The entire native binding for a production-capable local LLM is a few `MethodHandle`s, a couple of
`FunctionDescriptor`s, and `Arena`-scoped memory — readable Java, no C toolchain in your build, no
`UnsatisfiedLinkError` archaeology, and a JSON ABI that survives llama.cpp upgrades. The next native
binding you write should probably be FFM unless you have a concrete reason it can't be.

The full bridge and the FFM binding are MIT on GitHub:
<https://github.com/deemwar-products/mochallama>. The "why" behind each decision lives at
<https://deemwar-products.github.io/mochallama/why>.

*(Code above is illustrative of the FFM patterns mochallama uses; see the repo for the exact source.)*
