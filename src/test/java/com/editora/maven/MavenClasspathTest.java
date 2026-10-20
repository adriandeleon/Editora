package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenClasspathTest {

    private static final String S = File.pathSeparator;

    private static final List<String> MVN = List.of("mvn");

    @Test
    void argvHasGoalAndOutputFile() {
        Path out = Path.of("/tmp/cp.txt");
        List<String> argv = MavenClasspath.argv(MVN, out, MavenClasspath.Scope.RUNTIME);
        assertEquals("mvn", argv.get(0));
        assertTrue(argv.contains("compile"));
        assertTrue(argv.contains("dependency:build-classpath"));
        assertTrue(argv.contains("-Dmdep.outputFile=" + out));
        assertTrue(argv.contains("-Dmdep.pathSeparator=" + File.pathSeparator));
    }

    /** The executable is the caller's — the project's wrapper, or the Settings override as typed (review D3). */
    @Test
    void theExecutableIsTheCallersArgvPrefix() {
        List<String> wrapper =
                MavenClasspath.argv(List.of("./mvnw"), Path.of("/tmp/cp.txt"), MavenClasspath.Scope.RUNTIME);
        assertEquals(List.of("./mvnw", "-q", "compile"), wrapper.subList(0, 3));
        List<String> override = MavenClasspath.reactorArgv(
                List.of("/opt/maven/bin/mvn", "-s", "/home/u/settings.xml"),
                Path.of("/tmp/cp.txt"),
                "api",
                MavenClasspath.Scope.RUNTIME);
        assertEquals(
                List.of("/opt/maven/bin/mvn", "-s", "/home/u/settings.xml", "-q", "-pl", "api", "-am"),
                override.subList(0, 7));
    }

    /** A program's classpath carries the runtime scope only; a test class gets the test scope (review D6). */
    @Test
    void theScopeChoosesThePhaseAndTheDependencyScope() {
        List<String> runtime = MavenClasspath.argv(MVN, Path.of("/tmp/cp.txt"), MavenClasspath.Scope.RUNTIME);
        assertTrue(runtime.contains("compile"), runtime.toString());
        assertTrue(runtime.contains("-Dmdep.includeScope=runtime"), runtime.toString());
        assertFalse(runtime.contains("test-compile"));

        List<String> test = MavenClasspath.argv(MVN, Path.of("/tmp/cp.txt"), MavenClasspath.Scope.TEST);
        assertTrue(test.contains("test-compile"), test.toString());
        assertTrue(test.contains("-Dmdep.includeScope=test"), test.toString());
        assertFalse(test.contains("compile"));
    }

    @Test
    void theScopeOfASourceFollowsItsTree() {
        Path root = Path.of("/proj");
        assertEquals(
                MavenClasspath.Scope.RUNTIME,
                MavenClasspath.Scope.forSource(root.resolve("src/main/java/a/App.java"), root));
        assertEquals(
                MavenClasspath.Scope.TEST,
                MavenClasspath.Scope.forSource(root.resolve("src/test/java/a/AppTest.java"), root));
        assertEquals(
                MavenClasspath.Scope.RUNTIME,
                MavenClasspath.Scope.forSource(root.resolve("src/test"), root),
                "a folder, not a file in the tree");
        assertEquals(
                MavenClasspath.Scope.RUNTIME,
                MavenClasspath.Scope.forSource(Path.of("/elsewhere/src/test/java/T.java"), root));
        assertEquals(MavenClasspath.Scope.RUNTIME, MavenClasspath.Scope.forSource(null, root));
        assertEquals(
                MavenClasspath.Scope.RUNTIME,
                MavenClasspath.Scope.forSource(root.resolve("src/test/java/T.java"), null));
    }

    @Test
    void reactorArgvSelectsModuleAndAlsoMakes() {
        Path out = Path.of("/tmp/cp.txt");
        List<String> argv = MavenClasspath.reactorArgv(MVN, out, "services/api", MavenClasspath.Scope.RUNTIME);
        assertEquals("mvn", argv.get(0));
        assertTrue(argv.contains("-pl"));
        assertTrue(argv.contains("services/api"));
        assertTrue(argv.contains("-am"));
        assertTrue(argv.contains("dependency:build-classpath"));
        assertTrue(argv.contains("-Dmdep.outputFile=" + out));
    }

    @Test
    void assemblePrependsTargetClasses() {
        List<String> cp = MavenClasspath.assemble("/m2/a.jar" + S + "/m2/b.jar", Path.of("/proj"));
        assertEquals(List.of(Path.of("/proj/target/classes").toString(), "/m2/a.jar", "/m2/b.jar"), cp);
    }

    @Test
    void assembleForATestLaunchPrependsTestClassesToo() {
        List<String> cp = MavenClasspath.assemble("/m2/a.jar", Path.of("/proj"), MavenClasspath.Scope.TEST);
        assertEquals(
                List.of(
                        Path.of("/proj/target/test-classes").toString(),
                        Path.of("/proj/target/classes").toString(),
                        "/m2/a.jar"),
                cp);
    }

    @Test
    void assembleDropsBlankEntries() {
        List<String> cp = MavenClasspath.assemble("/m2/a.jar" + S + S + "  " + S + "/m2/b.jar", Path.of("/p"));
        assertEquals(List.of(Path.of("/p/target/classes").toString(), "/m2/a.jar", "/m2/b.jar"), cp);
    }

    @Test
    void assembleWithNoDependencies() {
        assertEquals(List.of(Path.of("/p/target/classes").toString()), MavenClasspath.assemble("", Path.of("/p")));
        assertEquals(List.of(Path.of("/p/target/classes").toString()), MavenClasspath.assemble(null, Path.of("/p")));
    }

    /** On a remote host the file and the separator are the host's, whatever this machine uses. */
    @Test
    void aHostsFileAndSeparatorAreUsedAsGiven() {
        List<String> argv =
                MavenClasspath.argv(MVN, "/home/u/app/target/editora-cp-1.txt", ":", MavenClasspath.Scope.RUNTIME);
        assertTrue(argv.contains("-Dmdep.outputFile=/home/u/app/target/editora-cp-1.txt"), argv.toString());
        assertTrue(argv.contains("-Dmdep.pathSeparator=:"), argv.toString());
        List<String> reactor =
                MavenClasspath.reactorArgv(MVN, "/home/u/r/target/cp.txt", ":app", ":", MavenClasspath.Scope.RUNTIME);
        assertTrue(reactor.contains("-Dmdep.pathSeparator=:"), reactor.toString());

        assertEquals(
                List.of("/home/u/app/target/classes", "/home/u/.m2/a.jar", "/home/u/.m2/b.jar"),
                MavenClasspath.assemble("/home/u/.m2/a.jar: /home/u/.m2/b.jar:", "/home/u/app/target/classes", ":"));
    }
}
