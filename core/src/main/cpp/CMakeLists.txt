# CMakeLists.txt for the llamavector-java native bridge.
#
# Builds two shared libraries:
#   - libllama.dylib (+ libggml*.dylib) via vendored llama.cpp at
#     ../native/llama.cpp
#   - libllamabridge.dylib — our thin C wrapper that exposes a stable
#     ABI to Java (Panama FFM).
#
# Target: macOS Intel x86_64. CPU-only — Metal is OFF because the host
# is a 7th-gen Intel iGPU (no Apple Silicon GPU).
#
# Output layout (after `cmake --build`):
#   build/bin/libllama.dylib
#   build/bin/libggml.dylib
#   build/bin/libggml-base.dylib
#   build/bin/libggml-cpu.dylib
#   build/bin/libggml-blas.dylib     (Accelerate backend, if enabled)
#   build/libllamabridge.dylib
#
# Invoked from Gradle via the `buildNative` task — see ../../build.gradle.

cmake_minimum_required(VERSION 3.18)
# The bridge is C++ now (it drives llama.cpp's common_chat / common_sampler
# APIs, which are C++), but still exports a pure C ABI via extern "C".
project(llamabridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------
# Vendored llama.cpp configuration
# ---------------------------------------------------------------------
# Toggle the parts of llama.cpp we DON'T want before we add the subdir.
# These must be set as cache vars so the subproject sees them.

set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)

# We need the "common" utils library (common_chat_* / common_sampler_*).
# It is only built when LLAMA_BUILD_COMMON is ON; that defaults to
# LLAMA_STANDALONE, which is OFF when llama.cpp is consumed as a subdirectory.
# Force it ON so the `llama-common` target (+ its cpp-httplib dep) exists.
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
# The HTML/UI bundle is server-only; we don't need it and it slows the build.
set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_HTML      OFF CACHE BOOL "" FORCE)
# Drop the OpenSSL dependency pulled in by cpp-httplib's download support: we
# only use common_chat_* / common_sampler_*, never the HTTP(S) downloader, and
# the default links Homebrew OpenSSL via an absolute path that is not portable
# in the published JAR.
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)

# CPU-only on Intel Mac. Accelerate is fast and present on every macOS.
set(GGML_METAL            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA             OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)
set(GGML_HIP              OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE       ON  CACHE BOOL "" FORCE)
set(GGML_BLAS             ON  CACHE BOOL "" FORCE)
set(GGML_BLAS_VENDOR      "Apple" CACHE STRING "" FORCE)

# Vendored llama.cpp lives next to the bridge source under src/main/native.
set(LLAMA_CPP_DIR "${CMAKE_SOURCE_DIR}/../native/llama.cpp"
    CACHE PATH "Path to vendored llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "Vendored llama.cpp not found at ${LLAMA_CPP_DIR}. "
        "Run the clone step before building.")
endif()

add_subdirectory("${LLAMA_CPP_DIR}" llama-build EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------
# Bridge library
# ---------------------------------------------------------------------

add_library(llamabridge SHARED
    src/llamabridge.cpp
)

target_include_directories(llamabridge
    PUBLIC  include
    PRIVATE "${LLAMA_CPP_DIR}/include"
            "${LLAMA_CPP_DIR}/ggml/include"
            "${LLAMA_CPP_DIR}/common"
            "${LLAMA_CPP_DIR}/vendor"
)

# Stamp build identity into the bridge so llb_version() is meaningful.
target_compile_definitions(llamabridge PRIVATE
    LLB_BRIDGE_VERSION="0.2.0"
    LLB_LLAMA_TAG="b9371"
)

# Link against the targets produced by the vendored subproject:
#   - llama        : core inference + tokenizer
#   - llama-common : common_chat_* / common_sampler_* helpers (prompt
#                    building, tool-call grammar synthesis, sampling chain).
# The common target is named "llama-common" in this tag's CMake (see
# common/CMakeLists.txt). It pulls in ggml transitively via llama.
target_link_libraries(llamabridge PRIVATE llama llama-common)

# Resolve sibling dylibs (libllama, libggml*) at runtime from the same
# directory as libllamabridge.dylib — that's where Gradle drops them.
set_target_properties(llamabridge PROPERTIES
    INSTALL_RPATH           "@loader_path"
    BUILD_WITH_INSTALL_RPATH TRUE
    MACOSX_RPATH             TRUE
)
