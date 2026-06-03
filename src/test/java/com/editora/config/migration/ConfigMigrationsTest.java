package com.editora.config.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMigrationsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionOfHandlesArrayPresentAndMissing() {
        assertEquals(0, ConfigMigrations.versionOf(JsonNodeFactory.instance.arrayNode(), 1),
                "a bare array is the legacy v0 form");
        ObjectNode withVersion = mapper.createObjectNode().put("schemaVersion", 5);
        assertEquals(5, ConfigMigrations.versionOf(withVersion, 1));
        ObjectNode noVersion = mapper.createObjectNode().put("x", 1);
        assertEquals(1, ConfigMigrations.versionOf(noVersion, 1), "no marker ⇒ assumed legacy baseline");
    }

    @Test
    void applyStepsRunsEachStepInOrder() {
        // v1 → v4 chain; each step appends its target version to a "trace" array.
        java.util.function.IntFunction<Migration> stepFor = v -> input -> {
            ObjectNode o = (ObjectNode) input;
            ArrayNode trace = o.has("trace") ? (ArrayNode) o.get("trace") : o.putArray("trace");
            trace.add(v + 1);
            return o;
        };
        ObjectNode start = mapper.createObjectNode();
        JsonNode out = ConfigMigrations.applySteps(start, 1, 4, stepFor);
        assertEquals("[2,3,4]", out.get("trace").toString().replace(" ", ""));
    }

    @Test
    void applyStepsThrowsOnMissingStep() {
        assertThrows(IllegalStateException.class,
                () -> ConfigMigrations.applySteps(mapper.createObjectNode(), 1, 2, v -> null));
    }

    @Test
    void upgradeStampsCurrentVersionAndWrapsRecentArray() {
        ArrayNode legacy = JsonNodeFactory.instance.arrayNode();
        legacy.add("/a.txt");
        legacy.add("/b.txt");
        ObjectNode migrated = ConfigMigrations.upgrade(ConfigSchema.RECENT, legacy, mapper);
        assertEquals(1, migrated.get("schemaVersion").asInt());
        assertTrue(migrated.has("files"));
        assertEquals(2, migrated.get("files").size());
        assertEquals("/a.txt", migrated.get("files").get(0).asText());
    }

    @Test
    void upgradeThrowsWhenFileIsNewerThanSupported() {
        ObjectNode tooNew = mapper.createObjectNode().put("schemaVersion", 99);
        NewerThanSupportedException ex = assertThrows(NewerThanSupportedException.class,
                () -> ConfigMigrations.upgrade(ConfigSchema.SETTINGS, tooNew, mapper));
        assertEquals(99, ex.storedVersion());
        assertEquals(ConfigSchema.SETTINGS, ex.schema());
    }

    @Test
    void readVersionedBacksUpAndDefaultsWhenFileTooNew(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("workspace-state.json");
        Files.writeString(f, "{\"schemaVersion\":99,\"zenMode\":true}");
        ObjectNode defaults = mapper.createObjectNode().put("zenMode", false);
        JsonNode result = ConfigMigrations.readVersioned(f, mapper, defaults, ConfigSchema.WORKSPACE);
        assertFalse(result.get("zenMode").asBoolean(), "too-new file ⇒ defaults used");
        assertFalse(Files.exists(f), "the too-new file is moved aside");
        assertTrue(Files.exists(dir.resolve("workspace-state.json.v99.bak")), "backed up");
    }

    @Test
    void backupDoesNotClobberAnExistingBak(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("settings.toml");
        Files.writeString(f, "new");
        Path bak = dir.resolve("settings.toml.v2.bak");
        Files.writeString(bak, "original-backup");
        ConfigMigrations.backup(f, 2);
        assertEquals("original-backup", Files.readString(bak), "first backup is preserved");
    }
}
