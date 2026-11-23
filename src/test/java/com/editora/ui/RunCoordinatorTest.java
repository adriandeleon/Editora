package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run coordinator's folder decisions, over real folders. */
class RunCoordinatorTest {

    @Test
    void aGradleModuleBelongsToTheTopmostSettingsFileUpToTheRoot(@TempDir Path root) throws Exception {
        Path module = Files.createDirectories(root.resolve("inner/app"));
        assertEquals(module, RunCoordinator.gradleBuildRoot(root, module), "no settings file: the module itself");
        Files.writeString(root.resolve("inner/settings.gradle.kts"), "");
        assertEquals(root.resolve("inner"), RunCoordinator.gradleBuildRoot(root, module));
        Files.writeString(root.resolve("settings.gradle"), "");
        assertEquals(root, RunCoordinator.gradleBuildRoot(root, module), "the topmost wins");
        assertEquals(
                root.resolve("inner"),
                RunCoordinator.gradleBuildRoot(root.resolve("inner"), module),
                "nothing above the root counts");
    }

    @Test
    void aScalaFileInAnSbtMillOrJvmBuildIsRunByThatBuild(@TempDir Path dir) throws Exception {
        Path sbt = Files.createDirectories(dir.resolve("sbt/src"));
        Files.writeString(dir.resolve("sbt/build.sbt"), "");
        assertTrue(RunCoordinator.scalaBuildOwner(sbt.resolve("A.scala"), null));

        Path mill = Files.createDirectories(dir.resolve("mill/src"));
        Files.writeString(dir.resolve("mill/build.mill"), "");
        assertTrue(RunCoordinator.scalaBuildOwner(mill.resolve("A.scala"), dir.resolve("mill")));

        Path gradle = Files.createDirectories(dir.resolve("gradle/src"));
        Files.writeString(dir.resolve("gradle/build.gradle"), "");
        assertTrue(RunCoordinator.scalaBuildOwner(gradle.resolve("A.scala"), dir.resolve("gradle")));

        Path cli = Files.createDirectories(dir.resolve("cli"));
        Files.writeString(cli.resolve("project.scala"), "");
        assertFalse(RunCoordinator.scalaBuildOwner(cli.resolve("A.scala"), cli), "Scala CLI runs it");

        Path loose = Files.createDirectories(dir.resolve("loose"));
        assertFalse(RunCoordinator.scalaBuildOwner(loose.resolve("A.scala"), loose), "no build at all");
    }

    @Test
    void aScriptsFirstLineIsReadUpToItsLineBreak(@TempDir Path dir) throws Exception {
        assertNull(RunCoordinator.firstLine(null));
        assertNull(RunCoordinator.firstLine(dir.resolve("missing.sh")));
        assertEquals("#!/bin/sh", RunCoordinator.firstLine(Files.writeString(dir.resolve("a.sh"), "#!/bin/sh\necho")));
        assertEquals("one line", RunCoordinator.firstLine(Files.writeString(dir.resolve("b.sh"), "one line")));
    }
}
