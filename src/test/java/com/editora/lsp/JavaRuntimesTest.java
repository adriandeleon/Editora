package com.editora.lsp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimesTest {

    @Test
    void readsTheModernVersionScheme() {
        assertEquals(21, JavaRuntimes.majorFromRelease("JAVA_VERSION=\"21.0.11\"\nOS_NAME=\"Linux\"\n"));
        assertEquals(25, JavaRuntimes.majorFromRelease("MODULES=\"x\"\nJAVA_VERSION=\"25\"\n"));
    }

    @Test
    void readsTheLegacyOneDotEightScheme() {
        assertEquals(8, JavaRuntimes.majorFromRelease("JAVA_VERSION=\"1.8.0_402\""));
    }

    @Test
    void anUnreadableReleaseFileIsZeroNotAnException() {
        assertEquals(0, JavaRuntimes.majorFromRelease(null));
        assertEquals(0, JavaRuntimes.majorFromRelease(""));
        assertEquals(0, JavaRuntimes.majorFromRelease("OS_NAME=\"Linux\""));
        assertEquals(0, JavaRuntimes.majorFromRelease("JAVA_VERSION=\"unknown\""));
        assertEquals(0, JavaRuntimes.majorFromRelease("JAVA_VERSION"));
    }

    @Test
    void executionEnvironmentNamesMatchWhatJdtlsExpects() {
        assertEquals("JavaSE-1.8", JavaRuntimes.executionEnvironment(8));
        assertEquals("JavaSE-11", JavaRuntimes.executionEnvironment(11));
        assertEquals("JavaSE-25", JavaRuntimes.executionEnvironment(25));
    }

    @Test
    void newestFirstAndOnlyItIsDefault() {
        List<Map<String, Object>> out = JavaRuntimes.runtimes(List.of(
                new JavaRuntimes.Jdk(17, "/jdk17"),
                new JavaRuntimes.Jdk(25, "/jdk25"),
                new JavaRuntimes.Jdk(21, "/jdk21")));
        assertEquals(
                List.of("JavaSE-25", "JavaSE-21", "JavaSE-17"),
                out.stream().map(m -> (String) m.get("name")).toList());
        assertEquals(Boolean.TRUE, out.get(0).get("default"));
        assertEquals(
                1, out.stream().filter(m -> m.containsKey("default")).count(), "jdtls accepts at most one default");
    }

    @Test
    void oneEntryPerExecutionEnvironment() {
        // sdkman's "current" symlink and its target are the same JDK reached two ways; two entries naming
        // JavaSE-25 would make jdtls reject the whole setting.
        List<Map<String, Object>> out = JavaRuntimes.runtimes(
                List.of(new JavaRuntimes.Jdk(25, "/jdk25"), new JavaRuntimes.Jdk(25, "/other25")));
        assertEquals(1, out.size());
        assertEquals("JavaSE-25", out.get(0).get("name"));
    }

    @Test
    void junkEntriesAreDropped() {
        assertTrue(JavaRuntimes.runtimes(null).isEmpty());
        assertTrue(JavaRuntimes.runtimes(List.of()).isEmpty());
        assertTrue(
                JavaRuntimes.runtimes(List.of(new JavaRuntimes.Jdk(0, "/nope"))).isEmpty());
        assertTrue(JavaRuntimes.runtimes(List.of(new JavaRuntimes.Jdk(21, " "))).isEmpty());
    }

    @Test
    void discoveryFindsTheRunningJdkAtLeast() {
        // Editora always runs on a JDK, so this can never legitimately be empty — and an empty setting is
        // exactly the state that leaves a project's JRE container unresolved.
        List<Map<String, Object>> out = JavaRuntimes.runtimes(JavaRuntimes.discover());
        assertFalse(out.isEmpty(), "the JVM running the tests must be discovered");
        assertTrue(out.stream().allMatch(m -> ((String) m.get("name")).startsWith("JavaSE-")));
    }

    @Test
    void majorsAreDistinctAndNewestFirst() {
        // Several installs commonly share a major — a 25 and a 25.0.4 side by side.
        java.util.List<JavaRuntimes.Jdk> jdks = java.util.List.of(
                new JavaRuntimes.Jdk(17, "/a"),
                new JavaRuntimes.Jdk(25, "/b"),
                new JavaRuntimes.Jdk(25, "/c"),
                new JavaRuntimes.Jdk(21, "/d"));
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(25, 21, 17), JavaRuntimes.majorsDescending(jdks));
    }

    /** A JDK whose release file could not be parsed has major 0 — not a version to offer. */
    @Test
    void majorsSkipsUnparsedRuntimesAndNull() {
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(21),
                JavaRuntimes.majorsDescending(
                        java.util.List.of(new JavaRuntimes.Jdk(0, "/x"), new JavaRuntimes.Jdk(21, "/y"))));
        org.junit.jupiter.api.Assertions.assertTrue(
                JavaRuntimes.majorsDescending(null).isEmpty());
    }

    /**
     * A jlinked runtime image is not a JDK, however convincing its {@code release} file is.
     *
     * <p>This is Editora's own packaged runtime ({@code <app>/lib/runtime}): a real {@code release} saying
     * {@code JAVA_VERSION="25.0.4"} and no compiler — on Linux no {@code bin/} at all, since
     * {@code scripts/aot_build.java} deletes it. Accepting it made {@code java.home} claim the JavaSE-25
     * slot in every packaged build and jdtls reject it ("does not point to a JDK").
     */
    @Test
    void aRuntimeImageWithAReleaseFileButNoCompilerIsNotAJdk(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path runtime = java.nio.file.Files.createDirectory(tmp.resolve("runtime"));
        java.nio.file.Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n");
        assertFalse(JavaRuntimes.hasCompiler(runtime), "no bin/ at all — the Linux app-image layout");

        // Windows keeps bin/ (the JVM lives there) but still ships no compiler.
        java.nio.file.Files.createDirectory(runtime.resolve("bin"));
        java.nio.file.Files.writeString(runtime.resolve("bin").resolve("java"), "");
        assertFalse(JavaRuntimes.hasCompiler(runtime), "bin/java is a runtime; only javac makes it a JDK");
    }

    @Test
    void aRealJdkIsAccepted(@TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path jdk = java.nio.file.Files.createDirectory(tmp.resolve("jdk"));
        java.nio.file.Files.createDirectory(jdk.resolve("bin"));
        java.nio.file.Files.writeString(jdk.resolve("bin").resolve("javac"), "");
        assertTrue(JavaRuntimes.hasCompiler(jdk));

        java.nio.file.Path win = java.nio.file.Files.createDirectory(tmp.resolve("win"));
        java.nio.file.Files.createDirectory(win.resolve("bin"));
        java.nio.file.Files.writeString(win.resolve("bin").resolve("javac.exe"), "");
        assertTrue(JavaRuntimes.hasCompiler(win), "Windows JDK");
    }

    @Test
    void aMissingDirectoryIsNotAJdk(@TempDir java.nio.file.Path tmp) {
        assertFalse(JavaRuntimes.hasCompiler(null));
        assertFalse(JavaRuntimes.hasCompiler(tmp.resolve("nope")));
    }
}
