package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code projects.json} that failed to load (data-loss review C4): the open-window set in memory is then the
 * empty default, which must neither be acted on nor written back over the file.
 */
class ProjectIndexLoadFailureTest {

    private static final String GOOD =
            "{\"schemaVersion\":2,\"projects\":[{\"id\":\"p-1\",\"name\":\"p\",\"root\":\"/p\"}],"
                    + "\"activeProjectId\":\"\",\"openProjectIds\":[\"\",\"untitled:aaaa1111\",\"untitled:bbbb2222\"]}";

    @Test
    void anIndexThatLoadedIsIntactAndSavesAsBefore(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("projects.json"), GOOD);
        ProjectManager pm = new ProjectManager(dir);

        assertTrue(pm.loadedIntact());
        assertTrue(pm.openSetIsComplete());
        pm.markClosed("untitled:bbbb2222");
        assertTrue(pm.save());
        assertFalse(Files.readString(dir.resolve("projects.json")).contains("bbbb2222"));
    }

    @Test
    void aFirstRunIsIntact(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);

        assertTrue(pm.loadedIntact());
        assertTrue(pm.save());
        assertTrue(Files.exists(dir.resolve("projects.json")));
    }

    @ParameterizedTest(name = "index holding [{0}]")
    @ValueSource(
            strings = {
                "",
                "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"\",\"openProj",
                "{\"schemaVersion\":99,\"projects\":[],\"openProjectIds\":[\"untitled:aaaa1111\"]}"
            })
    void anIndexThatDidNotLoadIsNotIntactAndIsNotRewrittenByWindowBookkeeping(String damaged, @TempDir Path dir)
            throws Exception {
        Path index = dir.resolve("projects.json");
        Files.writeString(index, damaged);
        ProjectManager pm = new ProjectManager(dir);

        assertFalse(pm.loadedIntact());
        assertFalse(pm.openSetIsComplete(), "the open set here is the empty default, not what was written");
        // What launch, focus and close do all the time.
        pm.setActive("");
        pm.markOpen("");
        assertFalse(pm.save(), "an empty index must not replace the one that could not be read");
        if (Files.exists(index)) { // a newer index was moved aside; nothing may take its place either
            assertEquals(damaged, Files.readString(index));
        }

        // The next launch of this build still knows the open set was never read.
        assertFalse(new ProjectManager(dir).openSetIsComplete());
    }

    @Test
    void addingAProjectStartsANewIndexSoTheUsersWorkIsNotHeldBackForTheSession(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("projects.json");
        Files.writeString(index, "{\"schemaVersion\":2,\"projects\":[");
        ProjectManager pm = new ProjectManager(dir);
        assertFalse(pm.loadedIntact());

        pm.createOrGet("mine", Files.createDirectories(dir.resolve("mine")));

        assertTrue(pm.loadedIntact());
        assertTrue(pm.save());
        assertTrue(Files.readString(index).contains("mine"));
        assertTrue(Files.exists(dir.resolve("projects.json.corrupt.bak")), "what could not be read is still there");
        assertFalse(
                new ProjectManager(dir).openSetIsComplete(),
                "and while it is, the windows it lists keep their session files");
    }
}
