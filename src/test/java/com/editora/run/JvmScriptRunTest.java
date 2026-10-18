package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmScriptRunTest {

    @Test
    void aGroovyFileRunsThroughGroovy() {
        assertTrue(JvmScriptRun.supports("groovy"));
        assertFalse(JvmScriptRun.supports("java"), "Java has its own launcher");
        assertFalse(JvmScriptRun.supports(null));
        assertEquals("groovy", JvmScriptRun.tool("groovy"));
        assertEquals(
                List.of("/opt/groovy/bin/groovy", "/p/Report.groovy", "--month", "10"),
                JvmScriptRun.argv("/opt/groovy/bin/groovy", "/p/Report.groovy", List.of("--month", "10")));
        assertEquals("status.run.toolMissing.groovy", JvmScriptRun.missingToolKey("groovy"));
    }

    @Test
    void jvmOptionsJoinTheUsersOwnInTheLaunchersVariable() {
        assertEquals("JAVA_OPTS", JvmScriptRun.jvmOptionsVariable("groovy"));
        assertNull(JvmScriptRun.jvmOptionsVariable("python"));
        assertEquals(
                Map.of("JAVA_HOME", "/jdk", "JAVA_OPTS", "-Xmx1g -ea"),
                JvmScriptRun.withJvmOption(
                        Map.of("JAVA_HOME", "/jdk"), Map.of("JAVA_OPTS", "-Xmx1g"), "JAVA_OPTS", "-ea"));
        assertEquals(
                Map.of("JAVA_OPTS", "-Dset=here -ea"),
                JvmScriptRun.withJvmOption(
                        Map.of("JAVA_OPTS", "-Dset=here"), Map.of("JAVA_OPTS", "-Xmx1g"), "JAVA_OPTS", "-ea"),
                "the launch's own value replaces the inherited one");
        assertEquals(Map.of(), JvmScriptRun.withJvmOption(Map.of(), Map.of(), "JAVA_OPTS", " "));
    }

    @Test
    void groovyIsLookedForInItsHomeThenSdkman() {
        Path home = Path.of("/home/u");
        assertEquals(
                List.of(
                        Path.of("/opt/groovy-5/bin/groovy"),
                        home.resolve(".sdkman/candidates/groovy/current/bin/groovy")),
                JvmTools.candidates("groovy", Map.of("GROOVY_HOME", "/opt/groovy-5"), home, false));
        assertEquals(
                List.of(Path.of("/sdk/candidates/groovy/current/bin/groovy.bat")),
                JvmTools.candidates("groovy", Map.of("SDKMAN_DIR", "/sdk"), home, true));
        assertEquals(List.of(), JvmTools.candidates("no-such-tool", Map.of(), home, false), "not a known tool");
        assertTrue(JvmTools.knows("groovy"));
    }

    @Test
    void kotlincIsLookedForInKotlinHomeThenSdkmansKotlinCandidate() {
        Path home = Path.of("/home/u");
        assertEquals(
                List.of(
                        Path.of("/opt/kotlinc/bin/kotlinc"),
                        home.resolve(".sdkman/candidates/kotlin/current/bin/kotlinc")),
                JvmTools.candidates("kotlinc", Map.of("KOTLIN_HOME", "/opt/kotlinc"), home, false));
        assertEquals("kotlinc", JvmScriptRun.tool("kotlin"));
        assertEquals("JAVA_OPTS", JvmScriptRun.jvmOptionsVariable("kotlin"), "kotlinc's launcher reads it");
        assertEquals("status.run.toolMissing.kotlinc", JvmScriptRun.missingToolKey("kotlin"));
    }
}
