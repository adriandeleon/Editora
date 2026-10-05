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
        String out = "Welcome to zsh!\n\u001B[32mprompt\u001B[0m " + B + "/a:/b" + E + "\n% ";
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

    // --- resolveExecutable -------------------------------------------------------------------------

    private static java.nio.file.Path executable(java.nio.file.Path file) throws java.io.IOException {
        java.nio.file.Files.writeString(file, "");
        file.toFile().setExecutable(true); // stands in for Windows, where isExecutable is true for any file
        return file;
    }

    /**
     * npm, Maven and Gradle ship a POSIX shell shim beside the real Windows launcher. The shim was found
     * first (extension-less, "executable"), and CreateProcess cannot start it.
     */
    @Test
    void onWindowsTheRealLauncherWinsOverThePosixShimBesideIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        executable(dir.resolve("npm"));
        java.nio.file.Path npmCmd = executable(dir.resolve("npm.cmd"));
        executable(dir.resolve("gradle"));
        java.nio.file.Path gradleBat = executable(dir.resolve("gradle.bat"));
        executable(dir.resolve("onlyshim"));
        String path = dir.toString();

        assertEquals(
                java.util.List.of(npmCmd.toString(), "run", "build"),
                ProcessRunner.resolveExecutable(java.util.List.of("npm", "run", "build"), path, true));
        assertEquals(
                java.util.List.of(gradleBat.toString()),
                ProcessRunner.resolveExecutable(java.util.List.of("gradle"), path, true));
        assertEquals(
                java.util.List.of(npmCmd.toString()),
                ProcessRunner.resolveExecutable(java.util.List.of("npm.cmd"), path, true),
                "a name that already carries its extension resolves as written");
        assertEquals(
                java.util.List.of("onlyshim"),
                ProcessRunner.resolveExecutable(java.util.List.of("onlyshim"), path, true),
                "a shim with no launcher is left for ProcessBuilder, not resolved to a file Windows cannot run");
        // Elsewhere the extension-less file is the executable.
        assertEquals(
                java.util.List.of(dir.resolve("npm").toString(), "run"),
                ProcessRunner.resolveExecutable(java.util.List.of("npm", "run"), path, false));
    }
}
