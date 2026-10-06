package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedRunConfigsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RunConfiguration cfg(String name, String args) {
        return new RunConfiguration(name, "com.example.App", "", args, "", "");
    }

    @Test
    void configurationsRoundTripThroughTheProjectFile(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        SharedRunConfigs.save(MAPPER, root, List.of(cfg("Server", "--port 80"), cfg("Client", "")));

        assertTrue(Files.exists(SharedRunConfigs.fileFor(root)), "written where a repo can commit it");
        List<RunConfiguration> back = SharedRunConfigs.load(MAPPER, root);
        assertEquals(2, back.size());
        assertEquals("Server", back.get(0).name());
        assertEquals("--port 80", back.get(0).args(), "fields survive the trip");
    }

    /** A hand-edited or half-written file must not stop a project opening. */
    @Test
    void anUnreadableFileYieldsNothingRatherThanThrowing(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        assertEquals(List.of(), SharedRunConfigs.load(MAPPER, root), "absent file");

        Path file = SharedRunConfigs.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");
        assertEquals(List.of(), SharedRunConfigs.load(MAPPER, root), "malformed file");
    }

    /** Importing twice must not duplicate; re-importing after a colleague's edit must update. */
    @Test
    void mergeReplacesByNameRatherThanAppending() {
        List<RunConfiguration> existing = List.of(cfg("Server", "old"), cfg("Local only", ""));
        List<RunConfiguration> incoming = List.of(cfg("Server", "new"), cfg("Fresh", ""));

        List<RunConfiguration> merged = SharedRunConfigs.merge(existing, incoming);

        assertEquals(3, merged.size(), "no duplicate Server");
        assertEquals("Server", merged.get(0).name(), "existing order preserved");
        assertEquals("new", merged.get(0).args(), "and it was updated, not kept");
        assertEquals("Local only", merged.get(1).name(), "a local-only configuration survives an import");
        assertEquals("Fresh", merged.get(2).name(), "genuinely new ones are appended");
    }

    @Test
    void mergingIntoNothingIsJustTheIncomingSet() {
        assertEquals(1, SharedRunConfigs.merge(List.of(), List.of(cfg("A", ""))).size());
        assertEquals(1, SharedRunConfigs.merge(List.of(cfg("A", "")), List.of()).size());
    }

    /** The on-disk shape is an object so that keys can be added; one more key must not hide the list. */
    @Test
    void anExtraTopLevelKeyDoesNotHideTheConfigurations(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path file = SharedRunConfigs.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"_comment": "shared by the team", "schemaVersion": 1,
                 "configurations": [{"name": "Server", "mainClass": "com.example.App"}]}
                """);

        List<RunConfiguration> loaded = SharedRunConfigs.load(MAPPER, root);

        assertEquals(1, loaded.size());
        assertEquals("Server", loaded.get(0).name());
    }

    /** A hand edit can leave a null entry behind; importing reads every entry's name. */
    @Test
    void aNullEntryIsDroppedRatherThanBreakingTheImport(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        Path file = SharedRunConfigs.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"configurations": [{"name": "Server", "mainClass": "com.example.App"}, null]}
                """);

        List<RunConfiguration> loaded = SharedRunConfigs.load(MAPPER, root);

        assertEquals(1, loaded.size());
        assertEquals(1, SharedRunConfigs.merge(List.of(), loaded).size(), "merge reads each name");
    }
}
