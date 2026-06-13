package com.editora.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for {@link ProcessRunner}'s pure helpers (the login-shell PATH marker parse). */
class ProcessRunnerTest {

    private static final String B = "__EDITORA_PATH_BEGIN__";
    private static final String E = "__EDITORA_PATH_END__";

    @Test
    void extractsPathBetweenMarkers() {
        String out = B + "/usr/bin:/opt/homebrew/bin" + E;
        assertEquals("/usr/bin:/opt/homebrew/bin", ProcessRunner.extractMarked(out, B, E));
    }

    @Test
    void ignoresShellBannerNoiseAroundMarkers() {
        // An interactive rc may print a banner/prompt before and after; markers fence the PATH off.
        String out = "Welcome to zsh!\n[32mprompt[0m " + B + "/a:/b" + E + "\n% ";
        assertEquals("/a:/b", ProcessRunner.extractMarked(out, B, E));
    }

    @Test
    void nullWhenMarkerMissing() {
        assertNull(ProcessRunner.extractMarked("no markers here", B, E));
        assertNull(ProcessRunner.extractMarked(B + "/only/begin", B, E));
        assertNull(ProcessRunner.extractMarked("/only/end" + E, B, E));
    }

    @Test
    void nullWhenEmptyOrBlankBetweenMarkers() {
        assertNull(ProcessRunner.extractMarked(B + E, B, E));
        assertNull(ProcessRunner.extractMarked(B + "   " + E, B, E));
    }

    @Test
    void nullInput() {
        assertNull(ProcessRunner.extractMarked(null, B, E));
    }

    @Test
    void usesFirstBeginAndFollowingEnd() {
        // A path value can't contain the markers themselves, but be explicit about the scan order.
        String out = B + "/a" + E + " trailing " + B + "/b" + E;
        assertEquals("/a", ProcessRunner.extractMarked(out, B, E));
    }
}
