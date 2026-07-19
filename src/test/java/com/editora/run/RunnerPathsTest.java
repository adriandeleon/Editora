package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerPathsTest {

    @Test
    void runnerAbsolutePathYieldsTheRepoRelativeSuffix() {
        // The GitHub-hosted runner layout: /home/runner/work/<repo>/<repo>/<repo-relative path>
        List<String> c = RunnerPaths.candidates("/home/runner/work/Editora/Editora/src/main/java/X.java");
        assertEquals("home/runner/work/Editora/Editora/src/main/java/X.java", c.get(0));
        assertTrue(c.contains("src/main/java/X.java"), "the repo-relative suffix must be a candidate");
        assertEquals("X.java", c.get(c.size() - 1));
    }

    @Test
    void windowsRunnerPathIsNormalizedAndDriveDropped() {
        List<String> c = RunnerPaths.candidates("D:\\a\\repo\\repo\\src\\lib\\x.ts");
        assertTrue(c.contains("src/lib/x.ts"), "backslashes normalized + drive prefix dropped");
        assertEquals("x.ts", c.get(c.size() - 1));
        assertTrue(c.stream().noneMatch(s -> s.startsWith("D:")), "no drive-letter segment survives");
    }

    @Test
    void repoRelativePathIsTheFirstCandidate() {
        assertEquals(List.of("src/lib/x.ts", "lib/x.ts", "x.ts"), RunnerPaths.candidates("src/lib/x.ts"));
    }

    @Test
    void bareFileNameYieldsItself() {
        assertEquals(List.of("Foo.java"), RunnerPaths.candidates("Foo.java"));
    }

    @Test
    void parentTraversalIsRejected() {
        assertTrue(RunnerPaths.candidates("../../etc/passwd").isEmpty());
        assertTrue(RunnerPaths.candidates("src/../../../etc/passwd").isEmpty());
    }

    @Test
    void blankAndNullYieldNothing() {
        assertTrue(RunnerPaths.candidates(null).isEmpty());
        assertTrue(RunnerPaths.candidates("").isEmpty());
        assertTrue(RunnerPaths.candidates("   ").isEmpty());
        assertTrue(RunnerPaths.candidates("/").isEmpty());
    }

    @Test
    void deepPathIsBoundedToTheTrailingSegments() {
        String deep = "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/Deep.java"; // 15 segments
        List<String> c = RunnerPaths.candidates(deep);
        assertEquals(12, c.size(), "bounded to the last 12 segments");
        assertEquals("Deep.java", c.get(c.size() - 1));
        // Longest-first ordering, each one segment shorter than the last.
        assertTrue(c.get(0).length() > c.get(1).length());
    }

    /** A runner prefix plus a deep Java package must still emit the repo-relative suffix — the cap has to be
     *  generous enough or the CI link silently fails (the whole point of the class). */
    @Test
    void runnerPrefixPlusDeepJavaPackageStillYieldsTheRepoRelativeSuffix() {
        List<String> c = RunnerPaths.candidates(
                "/home/runner/work/Editora/Editora/src/main/java/com/editora/config/migration/ConfigSchema.java");
        assertTrue(
                c.contains("src/main/java/com/editora/config/migration/ConfigSchema.java"),
                "the 8-segment repo-relative suffix must survive the segment cap");
    }

    @Test
    void currentDirSegmentsAreIgnored() {
        assertEquals(List.of("src/x.ts", "x.ts"), RunnerPaths.candidates("./src/./x.ts"));
    }
}
