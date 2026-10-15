package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenClasspathTest {

    private static final String S = File.pathSeparator;

    @Test
    void argvHasGoalAndOutputFile() {
        Path out = Path.of("/tmp/cp.txt");
        List<String> argv = MavenClasspath.argv(out);
        assertEquals("mvn", argv.get(0));
        assertTrue(argv.contains("compile"));
        assertTrue(argv.contains("dependency:build-classpath"));
        assertTrue(argv.contains("-Dmdep.outputFile=" + out));
        assertTrue(argv.contains("-Dmdep.pathSeparator=" + File.pathSeparator));
    }

    @Test
    void reactorArgvSelectsModuleAndAlsoMakes() {
        Path out = Path.of("/tmp/cp.txt");
        List<String> argv = MavenClasspath.reactorArgv(out, "services/api");
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
        List<String> argv = MavenClasspath.argv("/home/u/app/target/editora-cp-1.txt", ":");
        assertTrue(argv.contains("-Dmdep.outputFile=/home/u/app/target/editora-cp-1.txt"), argv.toString());
        assertTrue(argv.contains("-Dmdep.pathSeparator=:"), argv.toString());
        List<String> reactor = MavenClasspath.reactorArgv("/home/u/r/target/cp.txt", ":app", ":");
        assertTrue(reactor.contains("-Dmdep.pathSeparator=:"), reactor.toString());

        assertEquals(
                List.of("/home/u/app/target/classes", "/home/u/.m2/a.jar", "/home/u/.m2/b.jar"),
                MavenClasspath.assemble("/home/u/.m2/a.jar: /home/u/.m2/b.jar:", "/home/u/app/target/classes", ":"));
    }
}
