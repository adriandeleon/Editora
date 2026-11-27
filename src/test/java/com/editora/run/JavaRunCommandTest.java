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

    /** jdtls names a class of a named module {@code <module>/<class>}; {@code java -cp} takes the class only. */
    @Test
    void aModuleQualifiedMainClassRunsByItsClassNameOffTheClasspath() {
        List<String> argv = JavaRunCommand.build(
                "java", List.of("/mods"), List.of(), "app.core/app.core.ModMain", List.of(), List.of());
        assertEquals(List.of("java", "-cp", "/mods", "app.core.ModMain"), argv);
        assertEquals("app.core.ModMain", new JavaMainClass("app.core/app.core.ModMain", "modproj", "").className());
        assertEquals("demo.Args", new JavaMainClass("demo.Args", "p", "").className());
    }

    /** A project compiled with preview features is run with the flag — once, and ahead of the user's own. */
    @Test
    void aPreviewProjectAddsTheFlagToTheVmArguments() {
        JavaLaunchInfo preview = new JavaLaunchInfo("java", List.of(), List.of("/cp"), null, true);
        assertEquals(List.of("--enable-preview"), preview.vmArgs(List.of()));
        assertEquals(List.of("--enable-preview", "-Xmx1g"), preview.vmArgs(List.of("-Xmx1g")));
        assertEquals(List.of("-Xmx1g", "--enable-preview"), preview.vmArgs(List.of("-Xmx1g", "--enable-preview")));
        JavaLaunchInfo plain = new JavaLaunchInfo("java", List.of(), List.of("/cp"), null);
        assertEquals(List.of("-Xmx1g"), plain.vmArgs(List.of("-Xmx1g")));
    }

    /**
     * Regression (r/JavaFX dry run, 2026-11-27): Run on a Maven JavaFX app's {@code main} put the JavaFX jars on
     * {@code -cp}, and an {@code Application} subclass stopped with "JavaFX runtime components are missing".
     */
    @Test
    void javaFxJarsGoOnTheModulePathWithTheirModulesAdded() {
        List<String> argv = JavaRunCommand.build(
                "java",
                List.of(),
                List.of("/p/target/classes", "/m2/javafx-controls-25-linux.jar", "/m2/javafx-graphics-25-linux.jar"),
                "demo.HelloApp",
                List.of("-Xmx256m"),
                List.of());
        assertEquals(
                List.of(
                        "java",
                        "-Xmx256m",
                        "--module-path",
                        "/m2/javafx-controls-25-linux.jar" + S + "/m2/javafx-graphics-25-linux.jar",
                        "--add-modules",
                        "javafx.controls,javafx.graphics",
                        "-cp",
                        "/p/target/classes",
                        "demo.HelloApp"),
                argv);
    }

    @Test
    void javaFxStaysWhereTheUsersOwnModuleOptionsPutIt() {
        List<String> vm = List.of("--module-path", "/sdk/lib", "--add-modules", "javafx.controls");
        List<String> argv = JavaRunCommand.build(
                "java", List.of(), List.of("/c", "/m2/javafx-controls-25-linux.jar"), "M", vm, List.of());
        assertEquals(
                List.of(
                        "java",
                        "--module-path",
                        "/sdk/lib",
                        "--add-modules",
                        "javafx.controls",
                        "-cp",
                        "/c" + S + "/m2/javafx-controls-25-linux.jar",
                        "M"),
                argv);
    }
}
