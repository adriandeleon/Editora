package com.editora.run;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkToolchainTest {

    @Test
    void configurationOverrideWinsAndBlankInheritsGlobal() {
        assertEquals("/project", JdkToolchain.effectiveHome(" /project ", "/global"));
        assertEquals("/global", JdkToolchain.effectiveHome("", " /global "));
        assertEquals("", JdkToolchain.effectiveHome(null, null));
    }

    /** No JDK selected: the program runs on the JAVA_HOME Maven compiled with, when it names a real launcher (review D18). */
    @Test
    void theInheritedJavaHomeNamesTheLauncherWhenItExists(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws Exception {
        assertEquals("", JdkToolchain.inheritedJavaExecutable(java.util.Map.of()));
        assertEquals("", JdkToolchain.inheritedJavaExecutable(null));
        assertEquals(
                "",
                JdkToolchain.inheritedJavaExecutable(
                        java.util.Map.of("JAVA_HOME", home.resolve("nope").toString())));
        java.nio.file.Path bin = java.nio.file.Files.createDirectories(home.resolve("bin"));
        String launcher =
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        java.nio.file.Files.writeString(bin.resolve(launcher), "");
        assertEquals(
                bin.resolve(launcher).toString(),
                JdkToolchain.inheritedJavaExecutable(java.util.Map.of("JAVA_HOME", " " + home + " ")));
        assertEquals(
                JdkToolchain.javaExecutable(home.toString()),
                JdkToolchain.javaExecutableOrInherited(home.toString()),
                "a selected JDK wins over the environment");
    }

    @Test
    void blankSelectionLeavesLauncherAndEnvironmentAutomatic() {
        assertEquals("", JdkToolchain.javaExecutable(" "));
        assertEquals("javac", JdkToolchain.compilerForJavaExecutable(" "));
        assertTrue(JdkToolchain.environment(null, "/usr/bin").isEmpty());
    }

    @Test
    void selectedJdkSetsJavaHomeAndPutsItsBinFirst() {
        Map<String, String> env = JdkToolchain.environment("/opt/jdk-25", "/usr/bin");
        assertEquals("/opt/jdk-25", env.get("JAVA_HOME"));
        String path = env.entrySet().stream()
                .filter(e -> "PATH".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        assertEquals(Path.of("/opt/jdk-25", "bin") + File.pathSeparator + "/usr/bin", path);
        assertTrue(JdkToolchain.javaExecutable("/opt/jdk-25").contains("bin"));
        assertEquals(
                Path.of("/opt/jdk-25", "bin", isWindows() ? "javac.exe" : "javac")
                        .toString(),
                JdkToolchain.compilerForJavaExecutable(JdkToolchain.javaExecutable("/opt/jdk-25")));
    }

    @Test
    void compilerDistinguishesAJdkFromARuntime(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        assertFalse(JdkToolchain.isJdkHome(dir.toString()));
        Files.writeString(bin.resolve(isWindows() ? "javac.exe" : "javac"), "");
        assertTrue(JdkToolchain.isJdkHome(dir.toString()));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
