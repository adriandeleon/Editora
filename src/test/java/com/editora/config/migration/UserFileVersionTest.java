package com.editora.config.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The format version of a user-owned file: read, migrated one version at a time, and a newer file copied once. */
class UserFileVersionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aFileWithoutAMarkerIsTheFormatBeforeVersions() throws Exception {
        assertEquals(1, UserFileVersion.of(JSON.readTree("{\"name\": \"x\"}")));
        assertEquals(3, UserFileVersion.of(JSON.readTree("{\"schemaVersion\": 3}")));
        assertEquals(1, UserFileVersion.of(JSON.readTree("{\"schemaVersion\": \"three\"}")), "not a number: no marker");
        assertEquals(1, UserFileVersion.of(JSON.readTree("[1, 2]")));
        assertEquals(1, UserFileVersion.of(null));
    }

    @Test
    void migrationRunsEveryStepFromTheStoredVersionUp() throws Exception {
        UnaryOperator<JsonNode> oneToTwo = tree -> ((ObjectNode) tree).put("two", true);
        UnaryOperator<JsonNode> twoToThree = tree -> ((ObjectNode) tree).put("three", true);
        List<UnaryOperator<JsonNode>> steps = List.of(oneToTwo, twoToThree);

        assertEquals(
                "{\"two\":true,\"three\":true}",
                UserFileVersion.migrate(JSON.readTree("{}"), 1, steps).toString());
        assertEquals(
                "{\"three\":true}",
                UserFileVersion.migrate(JSON.readTree("{}"), 2, steps).toString());
        assertEquals(
                "{}", UserFileVersion.migrate(JSON.readTree("{}"), 3, steps).toString(), "already current");
        assertEquals(
                "{}", UserFileVersion.migrate(JSON.readTree("{}"), 1, List.of()).toString(), "version 1 has none");
    }

    @Test
    void aNewerFileIsCopiedBesideItOnceAndLeftInPlace(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("mine.json"), "{\"schemaVersion\": 9, \"name\": \"Mine\"}");

        Path copy = UserFileVersion.keepCopyOfNewer(file, 9);
        assertEquals(dir.resolve("mine.json.v9.bak"), copy);
        assertEquals(Files.readString(file), Files.readString(copy));
        assertEquals(copy, UserFileVersion.keepCopyOfNewer(file, 9), "read again: the same copy, not another");
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }

        // The newer Editora changed its file since: that is another copy, and the first one is kept.
        Files.writeString(file, "{\"schemaVersion\": 9, \"name\": \"Mine, edited\"}");
        Path second = UserFileVersion.keepCopyOfNewer(file, 9);
        assertNotNull(second);
        assertNotEquals(copy, second);
        assertEquals("{\"schemaVersion\": 9, \"name\": \"Mine\"}", Files.readString(copy));
        assertEquals(Files.readString(file), Files.readString(second));
    }
}
