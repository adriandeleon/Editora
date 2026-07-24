package com.editora.run;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaRunCommandTest {

    private static final String S = File.pathSeparator;

    @Test
    void classpathMainClassAndArgs() {
        List<String> argv = JavaRunCommand.build(
                "/jdk/bin/java",
                List.of(),
                List.of("/a.jar", "/classes"),
                "com.app.Main",
                List.of(),
                List.of("x", "y"));
        assertEquals(List.of("/jdk/bin/java", "-cp", "/a.jar" + S + "/classes", "com.app.Main", "x", "y"), argv);
    }

    @Test
    void blankJavaExecFallsBackToJava() {
        List<String> argv = JavaRunCommand.build("", List.of(), List.of("/cp"), "Main", List.of(), List.of());
        assertEquals(List.of("java", "-cp", "/cp", "Main"), argv);
    }

    @Test
    void nullJavaExecFallsBackToJava() {
        List<String> argv = JavaRunCommand.build(null, null, List.of("/cp"), "Main", null, null);
        assertEquals(List.of("java", "-cp", "/cp", "Main"), argv);
    }

    @Test
    void modulePathsAreFoldedIntoClasspath() {
        List<String> argv =
                JavaRunCommand.build("java", List.of("/mods/m.jar"), List.of("/lib/c.jar"), "M", List.of(), List.of());
        assertEquals(List.of("java", "-cp", "/mods/m.jar" + S + "/lib/c.jar", "M"), argv);
    }

    @Test
    void vmArgsComeBeforeClasspath() {
        List<String> argv = JavaRunCommand.build(
                "java", List.of(), List.of("/cp"), "Main", List.of("-Xmx256m", "-Dk=v"), List.of("a"));
        assertEquals(List.of("java", "-Xmx256m", "-Dk=v", "-cp", "/cp", "Main", "a"), argv);
    }

    @Test
    void noClasspathWhenEmpty() {
        List<String> argv = JavaRunCommand.build("java", List.of(), List.of(), "Main", List.of(), List.of());
        assertEquals(List.of("java", "Main"), argv);
    }
}
