# Native third-party licences

The `natives-<platform>` jars published from this module contain compiled
llama.cpp/ggml binaries. MIT requires its copyright and permission notice to
accompany every copy of the software, so the licence text in this directory is
packaged into those jars under `META-INF/licenses/`.

`LICENSE-llama.cpp` is the LICENSE from the pinned llama.cpp release
(`llamaCppTag` in `core/build.gradle` — currently `b9371`), covering llama.cpp
and the ggml sources vendored inside it. ggml ships no separate licence file in
that tree; the root LICENSE covers both.

**When bumping `llamaCppTag`, refresh this file from the new tag.**
