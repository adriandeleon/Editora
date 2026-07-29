package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigToolbarTest {

    private static final Path PROJECT = Path.of("/src/app");

    @Test
    void hiddenWhenNothingIsLaunchableAndNothingIsSaved() {
        assertFalse(RunConfigToolbar.visible(false, false, 0));
    }

    @Test
    void shownForALaunchableContext() {
        assertTrue(RunConfigToolbar.visible(false, true, 0));
    }

    /** Saved configurations must stay reachable, or the gate strands them — see the class doc. */
    @Test
    void savedConfigurationsKeepTheGroupVisible() {
        assertTrue(RunConfigToolbar.visible(false, false, 1));
    }

    /** Simple UI mode outranks everything, including saved configurations. */
    @Test
    void simpleModeHidesItRegardless() {
        assertFalse(RunConfigToolbar.visible(true, true, 5));
    }

    /** The case that prompted the gate: a project of Markdown notes with no build file anywhere in it. */
    @Test
    void aProjectWithNoBuildSystemIsNotLaunchable() {
        assertFalse(RunConfigToolbar.launchable(PROJECT, null, false));
    }

    @Test
    void aProjectWithItsOwnBuildFileIsLaunchable() {
        assertTrue(RunConfigToolbar.launchable(PROJECT, Path.of("/src/app/server"), false));
    }

    @Test
    void aProjectWithAMakefileIsLaunchable() {
        assertTrue(RunConfigToolbar.launchable(PROJECT, null, true));
    }

    /** The reported shape: a notes project must not inherit a sibling checkout's build file from above. */
    @Test
    void aBuildFileAboveTheProjectDoesNotMakeItLaunchable() {
        assertFalse(RunConfigToolbar.launchable(Path.of("/src/work/notes"), Path.of("/src/work"), false));
    }

    /**
     * With no project, any detected build counts. Projects are off by default, so requiring one would hide
     * the group permanently for everyone who never turned the feature on — Maven checkouts included.
     */
    @Test
    void withNoProjectADetectedBuildIsEnough() {
        assertTrue(RunConfigToolbar.launchable(null, Path.of("/src/work"), false));
        assertFalse(RunConfigToolbar.launchable(null, null, false), "and nothing detected is not");
    }

    @Test
    void findsEachMakefileSpelling(@TempDir Path dir) throws Exception {
        for (String name : new String[] {"Makefile", "makefile", "GNUmakefile"}) {
            Path f = dir.resolve(name);
            Files.writeString(f, "all:\n\techo hi\n");
            assertTrue(RunConfigToolbar.hasMakefile(dir), name);
            Files.delete(f);
        }
        assertFalse(RunConfigToolbar.hasMakefile(dir), "no makefile left");
    }

    /** A <em>directory</em> named Makefile is not a build file — the same trap as #451 for build markers. */
    @Test
    void aDirectoryNamedMakefileIsNotAMakefile(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("Makefile"));
        assertFalse(RunConfigToolbar.hasMakefile(dir));
    }

    @Test
    void aBuildFileInsideTheProjectCounts() {
        assertTrue(RunConfigToolbar.withinProject(Path.of("/src/app"), Path.of("/src/app")));
        assertTrue(RunConfigToolbar.withinProject(Path.of("/src/app"), Path.of("/src/app/server")));
    }

    /** Compared on components, not strings — {@code app2} is not inside {@code app}. */
    @Test
    void aSiblingSharingAPrefixIsNotInside() {
        assertFalse(RunConfigToolbar.withinProject(Path.of("/src/app"), Path.of("/src/app2")));
    }

    @Test
    void nullsAreNotWithin() {
        assertFalse(RunConfigToolbar.withinProject(null, Path.of("/src/app")));
        assertFalse(RunConfigToolbar.withinProject(Path.of("/src/app"), null));
    }

    @Test
    void nullRootHasNoMakefile() {
        assertFalse(RunConfigToolbar.hasMakefile(null));
    }
}
