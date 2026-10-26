package com.editora.lsp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The user's JDK is jdtls's default (Java review C4): Gradle runs on it and a folder without a build file
     * compiles for it. Marking the newest handed Gradle 9.7 a JDK 27 it cannot run on.
     */
    @Test
    void thePreferredJdkIsTheDefaultAndHoldsItsMajorsSlot() {
        var jdks = List.of(
                new JavaRuntimes.Jdk(27, "/jdk27"),
                new JavaRuntimes.Jdk(25, "/jdk25.0.4"),
                new JavaRuntimes.Jdk(25, "/jdk25.0.1"),
                new JavaRuntimes.Jdk(21, "/jdk21"));
        List<Map<String, Object>> out = JavaRuntimes.runtimes(jdks, "/jdk25.0.1");
        assertEquals(
                List.of("/jdk27", "/jdk25.0.1", "/jdk21"),
                out.stream().map(m -> m.get("path")).toList());
        assertEquals("/jdk25.0.1", JavaRuntimes.defaultPath(out));
        assertEquals(1, out.stream().filter(m -> m.containsKey("default")).count());

        assertEquals(
                "/jdk27", JavaRuntimes.defaultPath(JavaRuntimes.runtimes(jdks, "/not/installed")), "else the newest");
        assertEquals("/jdk27", JavaRuntimes.defaultPath(JavaRuntimes.runtimes(jdks, null)));
        assertNull(JavaRuntimes.defaultPath(List.of()));
    }

    @Test
    void theDefaultJdkSettingComesBeforeJavaHome() {
        assertEquals("/chosen", JavaRuntimes.preferredHome(" /chosen ", "/env"));
        assertEquals("/env", JavaRuntimes.preferredHome("", "/env"));
        assertNull(JavaRuntimes.preferredHome(null, " "));
    }

    /** jdtls's launcher needs Java 21; with JAVA_HOME on 17 it could not start (Java review C5). */
    @Test
    void jdtlsIsGivenAJavaNewEnoughToRunOn() {
        var jdks = List.of(
                new JavaRuntimes.Jdk(17, "/jdk17"),
                new JavaRuntimes.Jdk(21, "/jdk21"),
                new JavaRuntimes.Jdk(25, "/jdk25"));
        String exe = java.io.File.separatorChar == '\\' ? "java.exe" : "java";
        // As the code builds it: on Windows Path.of turns "/jdk25" into "\jdk25".
        java.util.function.Function<String, String> javaIn =
                home -> java.nio.file.Path.of(home, "bin", exe).toString();

        assertEquals(new JavaRuntimes.ServerJava(null, false), JavaRuntimes.serverJava(21, jdks, null), "fine as is");
        assertEquals(new JavaRuntimes.ServerJava(null, false), JavaRuntimes.serverJava(0, jdks, null), "unknown: left");
        assertEquals(
                new JavaRuntimes.ServerJava(javaIn.apply("/jdk25"), false),
                JavaRuntimes.serverJava(17, jdks, null),
                "the newest that qualifies");
        assertEquals(
                new JavaRuntimes.ServerJava(javaIn.apply("/jdk21"), false),
                JavaRuntimes.serverJava(17, jdks, "/jdk21"),
                "the user's choice when it qualifies");
        assertEquals(
                new JavaRuntimes.ServerJava(javaIn.apply("/jdk25"), false),
                JavaRuntimes.serverJava(17, jdks, "/jdk17"),
                "not when it is too old itself");
        assertEquals(
                new JavaRuntimes.ServerJava(null, true),
                JavaRuntimes.serverJava(17, List.of(new JavaRuntimes.Jdk(17, "/jdk17")), null),
                "nothing new enough: say so");
    }

    /**
     * jdtls 1.63's bundles need Java 25 though its launcher checks for 21: the floor is read from the install, so a
     * launcher on 21 is given the 25 Editora found (sixth review DS1).
     */
    @Test
    void theFloorIsReadFromTheInstalledJdtlsBundles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path plugins = java.nio.file.Files.createDirectories(dir.resolve("jdtls/plugins"));
        java.nio.file.Path bin = java.nio.file.Files.createDirectories(dir.resolve("jdtls/bin"));
        java.nio.file.Path launcher = java.nio.file.Files.writeString(bin.resolve("jdtls"), "#!/bin/sh\n");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-25");
        try (var out = new java.util.jar.JarOutputStream(
                java.nio.file.Files.newOutputStream(plugins.resolve("org.eclipse.jdt.core_3.48.0.jar")), manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry("a.txt"));
            out.closeEntry();
        }
        assertEquals(25, JavaRuntimes.serverMinMajor(launcher.toString()));
        // an Update in place: read again, not kept from before (seventh review CR1)
        manifest.getMainAttributes().putValue("Bundle-RequiredExecutionEnvironment", "JavaSE-27");
        try (var out = new java.util.jar.JarOutputStream(
                java.nio.file.Files.newOutputStream(plugins.resolve("org.eclipse.jdt.core_3.48.0.jar")), manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry("a.txt"));
            out.closeEntry();
        }
        assertEquals(27, JavaRuntimes.serverMinMajor(launcher.toString()), "the updated bundles' floor");
        assertEquals(
                21, JavaRuntimes.serverMinMajor(dir.resolve("none/bin/jdtls").toString()), "unknown: 21");

        List<JavaRuntimes.Jdk> jdks = List.of(new JavaRuntimes.Jdk(21, "/jdk21"), new JavaRuntimes.Jdk(25, "/jdk25"));
        String exe = java.io.File.separatorChar == '\\' ? "java.exe" : "java";
        assertEquals(
                new JavaRuntimes.ServerJava(
                        java.nio.file.Path.of("/jdk25", "bin", exe).toString(), false),
                JavaRuntimes.serverJava(21, jdks, "/jdk21", 25),
                "a launcher on 21 runs jdtls on the 25");
        assertEquals(
                new JavaRuntimes.ServerJava(null, true),
                JavaRuntimes.serverJava(21, List.of(new JavaRuntimes.Jdk(21, "/jdk21")), null, 25),
                "none new enough: said, not a crash loop");
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

    /**
     * Within a major a JDK with its sources is preferred, and one without gets the online API docs: a distro
     * JDK whose {@code src.zip} links to a package not installed gave jdtls JDK parameters as {@code arg0} and no
     * signature-help docs (fourth review DF1).
     */
    @Test
    void aJdkWithItsSourcesIsPreferredEvenOverThePreferredJdkOfTheSameVersion(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path distro = java.nio.file.Files.createDirectories(dir.resolve("distro21/lib"));
        java.nio.file.Files.createSymbolicLink(distro.resolve("src.zip"), dir.resolve("not-installed/src.zip"));
        java.nio.file.Path sdk = java.nio.file.Files.createDirectories(dir.resolve("sdk21/lib"));
        java.nio.file.Files.writeString(sdk.resolve("src.zip"), "zip");
        java.nio.file.Path newer = java.nio.file.Files.createDirectories(dir.resolve("sdk25/lib"));
        String distroHome = distro.getParent().toString();
        String sdkHome = sdk.getParent().toString();
        String newerHome = newer.getParent().toString();

        List<Map<String, Object>> out = JavaRuntimes.runtimes(
                List.of(
                        new JavaRuntimes.Jdk(21, distroHome),
                        new JavaRuntimes.Jdk(21, sdkHome),
                        new JavaRuntimes.Jdk(25, newerHome)),
                distroHome);

        assertEquals(newerHome, out.get(0).get("path"));
        assertEquals(sdkHome, out.get(1).get("path"), "the twin with sources stands in for the preferred JDK");
        assertEquals(true, out.get(1).get("default"), "and is the default in its place (fifth review DF1)");
        assertNull(out.get(0).get("default"));
        assertNull(out.get(1).get("javadoc"), "no javadoc url: JDT already has the release's docs");
    }

    /** Doctor's hint: the default JDK has no sources and no JDK of its version has them (sixth review DF1). */
    @Test
    void aDefaultJdkWithNoSourcesAnywhereIsNamed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path distro = java.nio.file.Files.createDirectories(dir.resolve("distro21/lib"));
        java.nio.file.Path sdk = java.nio.file.Files.createDirectories(dir.resolve("sdk21/lib"));
        java.nio.file.Files.writeString(sdk.resolve("src.zip"), "zip");
        String distroHome = distro.getParent().toString();
        var bare = new JavaRuntimes.Jdk(21, distroHome);
        assertEquals(bare, JavaRuntimes.withoutSources(List.of(bare), distroHome));
        assertNull(
                JavaRuntimes.withoutSources(
                        List.of(bare, new JavaRuntimes.Jdk(21, sdk.getParent().toString())), distroHome),
                "a twin with sources stands in");
    }

    /** Every JDK a project may get is checked for sources, not only the default (seventh review DR2). */
    @Test
    void aProjectJdkWithoutSourcesIsNamedThoughTheDefaultHasThem(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path distro = java.nio.file.Files.createDirectories(dir.resolve("distro21/lib"));
        java.nio.file.Path sdk25 = java.nio.file.Files.createDirectories(dir.resolve("sdk25/lib"));
        java.nio.file.Files.writeString(sdk25.resolve("src.zip"), "zip");
        var jdk21 = new JavaRuntimes.Jdk(21, distro.getParent().toString());
        var jdk25 = new JavaRuntimes.Jdk(25, sdk25.getParent().toString());
        assertEquals(List.of(jdk21), JavaRuntimes.allWithoutSources(List.of(jdk21, jdk25), jdk25.path()));
        assertEquals(jdk21, JavaRuntimes.withoutSources(List.of(jdk21, jdk25), jdk25.path()));
    }
}
