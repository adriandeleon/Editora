package com.editora.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One store file written by two processes, at the boundary where the decision is made: what this process is
 * about to write against what is on disk now. The cases here are the ones where the file on disk is not what
 * a well-behaved second process would have left.
 */
class StoreSyncTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private static byte[] bytes(String json) {
        return json.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode onDisk(Path file) throws IOException {
        return JSON.readTree(Files.readAllBytes(file));
    }

    private static JsonNode tree(String json) throws IOException {
        return JSON.readTree(bytes(json));
    }

    @Test
    void aStoreThatDidNotChangeIsNotWrittenAgainUnlessItsFileIsGone() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("abbreviations.json");
        byte[] mine = bytes("{'schemaVersion':1,'abbreviations':[]}");
        Files.write(file, mine);
        sync.loaded(file, mine);

        assertFalse(sync.write(file, mine, ConfigSchema.ABBREVIATIONS, null), "nothing to persist");

        Files.delete(file); // the user removed it: the store in memory is written back
        assertTrue(sync.write(file, mine, ConfigSchema.ABBREVIATIONS, null));
        assertEquals(tree("{'schemaVersion':1,'abbreviations':[]}"), onDisk(file));
    }

    @Test
    void aFileLoadedInAnOlderShapeIsRewrittenInTheCurrentOneOnTheFirstSave() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("store.json");
        Files.write(file, bytes("{ 'a' : 1 }")); // what is on disk is not what the loaded store serializes to
        byte[] mine = bytes("{'a':1}");
        sync.loaded(file, mine);

        assertTrue(sync.write(file, mine, null, null), "the same store, but the file needs normalizing");
        assertEquals(new String(mine, StandardCharsets.UTF_8), Files.readString(file));
        assertFalse(sync.write(file, mine, null, null), "once");
    }

    @Test
    void whatAnotherProcessAddedSinceTheLoadIsKeptAndHandedBack() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("abbreviations.json");
        byte[] loaded = bytes("{'schemaVersion':1,'abbreviations':[{'abbreviation':'a'}]}");
        Files.write(file, loaded);
        sync.loaded(file, loaded);
        Files.write(
                file, bytes("{'schemaVersion':1,'abbreviations':[{'abbreviation':'a'},{'abbreviation':'theirs'}]}"));

        List<JsonNode> merged = new ArrayList<>();
        assertTrue(sync.write(
                file,
                bytes("{'schemaVersion':1,'abbreviations':[{'abbreviation':'a'},{'abbreviation':'mine'}]}"),
                ConfigSchema.ABBREVIATIONS,
                merged::add));

        JsonNode expected = tree(
                "{'schemaVersion':1,'abbreviations':[{'abbreviation':'a'},{'abbreviation':'mine'},{'abbreviation':'theirs'}]}");
        assertEquals(expected, onDisk(file));
        assertEquals(List.of(expected), merged, "the caller is told what its store now is");
    }

    @Test
    void aFileTheOtherProcessLeftUntouchedOrBroughtToTheSameStateNeedsNoMerge() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("store.json");
        byte[] loaded = bytes("{'k':1}");
        Files.write(file, loaded);
        sync.loaded(file, loaded);
        List<JsonNode> merged = new ArrayList<>();

        // Rewritten by someone with the same content in another layout: still the common ancestor.
        Files.write(file, bytes("{ 'k' : 1 }"));
        assertTrue(sync.write(file, bytes("{'k':2}"), null, merged::add));
        assertEquals(tree("{'k':2}"), onDisk(file));

        // The other process made the very change this one is about to save.
        Files.write(file, bytes("{ 'k' : 3 }"));
        assertTrue(sync.write(file, bytes("{'k':3}"), null, merged::add));
        assertEquals(tree("{'k':3}"), onDisk(file));
        assertEquals(List.of(), merged, "in neither case is there anything to hand back");
    }

    @Test
    void aStoreNeverLoadedHereIsMergedOntoWhatIsOnDiskKeyByKey() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("store.json");
        Files.write(file, bytes("{'theirs':true,'both':'theirs'}"));

        assertTrue(sync.write(file, bytes("{'mine':true,'both':'mine'}"), null, null));
        assertEquals(tree("{'mine':true,'both':'mine','theirs':true}"), onDisk(file));

        sync.forget(file); // deleted on purpose: the next write starts from nothing again
        Files.delete(file);
        assertTrue(sync.write(file, bytes("{'fresh':1}"), null, null));
        assertEquals(tree("{'fresh':1}"), onDisk(file));
    }

    @Test
    void aFileDamagedSinceTheLoadIsKeptAsideAndReplacedByThisProcesssStore() throws Exception {
        for (String damaged : List.of("{ this is not json", "[1,2,3]", "\"just text\"")) {
            Path folder = Files.createDirectories(dir.resolve(Integer.toHexString(damaged.hashCode())));
            StoreSync sync = new StoreSync(folder, JSON);
            Path file = folder.resolve("abbreviations.json");
            byte[] loaded = bytes("{'schemaVersion':1,'abbreviations':[]}");
            Files.write(file, loaded);
            sync.loaded(file, loaded);
            Files.writeString(file, damaged);

            List<JsonNode> merged = new ArrayList<>();
            byte[] mine = bytes("{'schemaVersion':1,'abbreviations':[{'abbreviation':'mine'}]}");
            assertTrue(sync.write(file, mine, ConfigSchema.ABBREVIATIONS, merged::add), damaged);

            assertEquals(JSON.readTree(mine), onDisk(file), damaged);
            assertEquals(List.of(), merged);
            List<Path> kept;
            try (var files = Files.list(folder)) {
                kept = files.filter(p -> p.getFileName().toString().contains(".corrupt"))
                        .toList();
            }
            assertEquals(1, kept.size(), "the unreadable bytes are not thrown away: " + damaged);
            assertEquals(damaged, Files.readString(kept.get(0)));
        }
    }

    @Test
    void aFileNowWrittenByANewerEditoraIsNotOverwritten() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = dir.resolve("abbreviations.json");
        byte[] loaded = bytes("{'schemaVersion':1,'abbreviations':[]}");
        Files.write(file, loaded);
        sync.loaded(file, loaded);
        String newer = "{\"schemaVersion\":9999,\"abbreviations\":[],\"somethingOnlyTheNewerBuildKnows\":true}";
        Files.writeString(file, newer);

        IOException refused = assertThrows(
                IOException.class,
                () -> sync.write(
                        file,
                        bytes("{'schemaVersion':1,'abbreviations':[{'abbreviation':'mine'}]}"),
                        ConfigSchema.ABBREVIATIONS,
                        null));
        assertTrue(refused.getMessage().contains("abbreviations.json"), refused.getMessage());
        assertTrue(refused.getMessage().contains("9999"), refused.getMessage());
        assertEquals(newer, Files.readString(file), "what only the newer build knows is still there");
    }

    @Test
    void somethingUnreadableWhereTheFileShouldBeIsNeverReplacedBlind() throws Exception {
        StoreSync sync = new StoreSync(dir, JSON);
        Path file = Files.createDirectory(dir.resolve("store.json")); // a folder by the store's name
        Files.writeString(file.resolve("inside.txt"), "not ours to lose");
        byte[] mine = bytes("{'k':1}");

        sync.loaded(file, mine); // the load made nothing of it; that is recorded, not thrown
        assertThrows(IOException.class, () -> sync.write(file, bytes("{'k':2}"), null, null));
        assertTrue(Files.isDirectory(file));
        assertEquals("not ours to lose", Files.readString(file.resolve("inside.txt")));
    }
}
