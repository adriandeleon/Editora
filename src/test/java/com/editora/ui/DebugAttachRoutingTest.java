package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An attach starts the debug adapter through jdtls, and jdtls only answers for a document it has open. The
 * anchor of "Debug Test" is the test class's source as found <em>on disk</em>; with no tab for it the attach
 * failed with "no language server for file" and the forked test JVM stayed suspended on its JDWP port.
 */
class DebugAttachRoutingTest {

    private static final Path ROOT = Path.of("/work/app").toAbsolutePath();
    private static final Path TEST = ROOT.resolve("src/test/java/com/foo/BarTest.java");
    private static final Path MAIN = ROOT.resolve("src/main/java/com/foo/Bar.java");
    private static final Path OTHER_MAIN = ROOT.resolve("src/main/java/com/foo/Baz.java");
    private static final Path ELSEWHERE =
            Path.of("/work/other/src/main/java/X.java").toAbsolutePath();

    @Test
    void anAnchorOpenInATabRoutesThroughItself() {
        assertEquals(TEST, DebugCoordinator.attachRouting(TEST, List.of(MAIN, TEST), ROOT, Set.of(MAIN)::contains));
    }

    @Test
    void anAnchorWithNoTabRoutesThroughAnOpenFileOfItsProject() {
        assertEquals(
                MAIN,
                DebugCoordinator.attachRouting(TEST, List.of(ELSEWHERE, MAIN), ROOT, Set.of(ELSEWHERE)::contains));
    }

    @Test
    void amongTheProjectsOpenFilesOneAlreadyOnTheServerWins() {
        assertEquals(
                OTHER_MAIN,
                DebugCoordinator.attachRouting(TEST, List.of(MAIN, OTHER_MAIN), ROOT, Set.of(OTHER_MAIN)::contains));
    }

    @Test
    void withNoFileOfTheProjectOpenAnyServerManagedFileIsUsed() {
        assertEquals(
                ELSEWHERE, DebugCoordinator.attachRouting(TEST, List.of(ELSEWHERE), ROOT, Set.of(ELSEWHERE)::contains));
    }

    @Test
    void withNothingUsableTheAnchorIsKeptSoTheFailureNamesIt() {
        assertEquals(TEST, DebugCoordinator.attachRouting(TEST, List.of(), ROOT, p -> false));
        assertEquals(TEST, DebugCoordinator.attachRouting(TEST, List.of(ELSEWHERE), ROOT, p -> false));
    }
}
