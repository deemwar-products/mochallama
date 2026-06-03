package tools.deemwar.mochallama.panama;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Native-closure smoke test — NO model required.
 *
 * <p>Calling {@link LlamaBridge#version()} triggers {@code NativeLoader.load()}
 * (which extracts and {@code System.load}s every bundled native lib for the
 * running platform) and then invokes the {@code llb_version} downcall. If the
 * staged native closure is wrong for this platform — a missing ggml backend, a
 * broken rpath/import, an absent dependency — this fails with an
 * {@code UnsatisfiedLinkError} / {@code IllegalStateException} instead of
 * silently shipping a jar that can't load.
 *
 * <p>This is the CI oracle for each platform leg (linux / darwin-aarch64 /
 * windows): it proves the libs we just built+staged actually load in a JVM on
 * that OS, which a build-only green check does not.
 */
class NativeLoadSmokeTest {

    @Test
    void bridgeLoadsAndReportsVersion() {
        String version = LlamaBridge.version();
        assertNotNull(version, "llb_version() returned null");
        assertFalse(version.isBlank(), "llb_version() returned blank");
        System.out.println("[native-smoke] bridge loaded, llb_version = " + version);
    }
}
