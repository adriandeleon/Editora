package com.editora.run;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KotlinRunTest {

    @Test
    void aFileIsAScriptAProgramOrABuildScript() {
        assertEquals(KotlinRun.Shape.SCRIPT, KotlinRun.shape("tool.main.kts"));
        assertEquals(KotlinRun.Shape.SCRIPT, KotlinRun.shape("Report.KTS"));
        assertEquals(KotlinRun.Shape.PROGRAM, KotlinRun.shape("Hello.kt"));
        assertEquals(KotlinRun.Shape.NONE, KotlinRun.shape("build.gradle.kts"));
        assertEquals(KotlinRun.Shape.NONE, KotlinRun.shape("settings.gradle.kts"));
        assertEquals(KotlinRun.Shape.NONE, KotlinRun.shape("Hello.java"));
        assertEquals(KotlinRun.Shape.NONE, KotlinRun.shape(null));
        assertTrue(KotlinRun.isBuildScript("init.gradle.kts"));
    }

    @Test
    void onlyATopLevelMainMakesAProgram() {
        assertEquals(2, KotlinRun.mainLine("package a\n\nfun main() {}\n"));
        assertEquals(0, KotlinRun.mainLine("suspend fun main(args: Array<String>) = coroutineScope {}\n"));
        assertEquals(0, KotlinRun.mainLine("public fun main() {}"));
        assertEquals(-1, KotlinRun.mainLine("object App {\n    @JvmStatic fun main(args: Array<String>) {}\n}\n"));
        assertEquals(-1, KotlinRun.mainLine("fun mainly() {}\n// fun main() {}\n"));
        assertEquals(-1, KotlinRun.runLine("Util.kt", "fun x() = 1\n"));
        assertEquals(0, KotlinRun.runLine("a.main.kts", "fun main() {}\nprintln(1)\n"), "a script runs from the top");
        assertEquals(-1, KotlinRun.runLine("build.gradle.kts", "fun main() {}\n"));
    }

    @Test
    void theCommandsAreKotlincsAndJavas() {
        assertEquals(
                List.of("/k/bin/kotlinc", "-script", "/p/tool.main.kts", "a", "b"),
                KotlinRun.scriptArgv("/k/bin/kotlinc", "/p/tool.main.kts", List.of("a", "b")));
        assertEquals(
                List.of("/k/bin/kotlinc", "/p/Hello.kt", "-include-runtime", "-d", "/c/Hello-x.jar"),
                KotlinRun.compileArgv("/k/bin/kotlinc", "/p/Hello.kt", "/c/Hello-x.jar"));
        assertEquals(
                List.of("/jdk/bin/java", "-Xmx1g", "-jar", "/c/Hello-x.jar", "--n", "3"),
                KotlinRun.programArgv("/jdk/bin/java", List.of("-Xmx1g"), "/c/Hello-x.jar", List.of("--n", "3")));
        assertEquals(
                List.of("/k/kotlinc", "-script", "/p/a.kts"),
                JvmScriptRun.argv("kotlin", "/k/kotlinc", "/p/a.kts", null),
                "a Kotlin script goes through -script");
        assertEquals(
                List.of("/g/groovy", "/p/a.groovy"),
                JvmScriptRun.argv("groovy", "/g/groovy", "/p/a.groovy", List.of()));
    }

    @Test
    void theJarIsNamedByFileAndContentSoAnEditRecompilesAndTheOldOneGoes() {
        String a = KotlinRun.jarName("/p/demo/Hello.kt", "fun main() {}", "/k/kotlinc");
        assertTrue(a.startsWith("Hello-") && a.endsWith(".jar"), a);
        assertEquals(a, KotlinRun.jarName("/p/demo/Hello.kt", "fun main() {}", "/k/kotlinc"), "unchanged: reused");
        String edited = KotlinRun.jarName("/p/demo/Hello.kt", "fun main() { }", "/k/kotlinc");
        String otherCompiler = KotlinRun.jarName("/p/demo/Hello.kt", "fun main() {}", "/k2/kotlinc");
        String otherFile = KotlinRun.jarName("/q/Hello.kt", "fun main() {}", "/k/kotlinc");
        assertNotEquals(a, edited);
        assertNotEquals(a, otherCompiler);
        assertNotEquals(a, otherFile);
        assertTrue(KotlinRun.isStaleJar(edited, a), "the older build of the same file");
        assertTrue(KotlinRun.isStaleJar(edited, KotlinRun.partialName(a)), "an interrupted compile's leftover");
        assertFalse(KotlinRun.isStaleJar(edited, edited));
        assertFalse(KotlinRun.isStaleJar(a, otherFile), "another file's jar stays");
        assertTrue(KotlinRun.partialName(a).endsWith(".partial.jar"));
        assertEquals(Path.of("/cfg/cache/kotlin-run"), KotlinRun.cacheDir(Path.of("/cfg"), Path.of("/tmp")));
        assertEquals(Path.of("/tmp/editora-kotlin-run"), KotlinRun.cacheDir(null, Path.of("/tmp")));
    }

    @Test
    void theCompilerIsToldApartFromTheToolchainCli() {
        assertArrayEquals(
                new String[] {"2.4.21", "25.0.4"},
                KotlinRun.compilerVersion("info: kotlinc-jvm 2.4.21 (JRE 25.0.4+7-LTS)\n"));
        assertArrayEquals(
                new String[] {"2.4.21", "25.0.4"},
                KotlinRun.compilerVersion("Kotlin version 2.4.21 (JRE 25.0.4+7-LTS)"));
        assertArrayEquals(new String[] {"2.1.0", null}, KotlinRun.compilerVersion("info: kotlinc-jvm 2.1.0"));
        assertNull(KotlinRun.compilerVersion("kotlin 0.3.1 (toolchain)\n"));
        assertFalse(KotlinRun.isCompilerVersion(""));
        assertTrue(KotlinRun.isCompilerVersion("info: kotlinc-jvm 2.4.21 (JRE 25.0.4+7-LTS)"));
    }
}
