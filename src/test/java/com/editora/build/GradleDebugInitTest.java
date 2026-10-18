package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle's {@code --debug-jvm} always listens on 5005; an init script debugs the named tasks' forked JVMs on a
 * port the system picks (follow-up to review A15). Checked live with Gradle 9.6.1 offline: {@code run} printed
 * "Listening for transport dt_socket at address: 33165", a test task with {@code maxParallelForks = 2} printed
 * two banners on two ports.
 */
class GradleDebugInitTest {

    @Test
    void theScriptDebugsOnlyTheNamedForkingTasksOnAPortTheSystemPicks() {
        String script = GradleDebugInit.script(List.of("test"));
        assertTrue(script.contains("def editoraDebugTasks = ['test'] as Set"), script);
        assertTrue(script.contains("it.name in editoraDebugTasks && it instanceof JavaForkOptions"), script);
        assertTrue(
                script.contains(
                        "task.jvmArgs('-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0')"),
                script);
        assertTrue(
                script.contains("task.outputs.upToDateWhen { false }"), "a test task already run would skip the JVM");
        assertTrue(GradleDebugInit.script(List.of("bootRun", "run")).contains("['bootRun', 'run']"));
    }

    @Test
    void namesAreGroovyStrings() {
        assertEquals("'it\\'s'", GradleDebugInit.groovyString("it's"));
        assertEquals("'a\\\\b'", GradleDebugInit.groovyString("a\\b"));
    }

    @Test
    void theScriptIsWrittenAndPassedWithInitScript() throws Exception {
        Path file = GradleDebugInit.write(List.of("run"));
        assertNotNull(file);
        try {
            assertEquals(GradleDebugInit.script(List.of("run")), Files.readString(file));
            assertEquals(List.of("--init-script", file.toString()), GradleDebugInit.args(file));
            assertEquals(List.of("run", "--init-script", file.toString()), BuildDebug.gradleDebugArgs("run", file));
            assertEquals(List.of("run", "--debug-jvm"), BuildDebug.gradleDebugArgs("run", null), "no script: 5005");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
