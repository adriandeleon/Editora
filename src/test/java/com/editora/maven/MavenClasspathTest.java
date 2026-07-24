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
        assertTrue(argv.contains("-Dmdep.outputFile=/tmp/cp.txt"));
        assertTrue(argv.contains("-Dmdep.pathSeparator=" + File.pathSeparator));
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
}
