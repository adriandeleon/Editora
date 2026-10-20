package com.editora.config.migration;

import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v140→141: {@code buildBeforeRun} is new (on) — nothing else changes, every stored choice is kept, and a file
 * that already holds the setting keeps its value.
 */
class BuildBeforeRunSettingMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theBuildBeforeRunSettingArrivesOnWithoutTouchingAnythingElse() throws Exception {
        JsonNode v140 = mapper.readTree(
                "{\"schemaVersion\":140,\"mavenSupport\":false,\"mavenCommand\":\"/opt/mvn/bin/mvn\",\"testRunner\":false}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v140.deepCopy(), mapper);

        assertTrue(Settings.SCHEMA_VERSION >= 141);
        assertEquals(Settings.SCHEMA_VERSION, out.get("schemaVersion").asInt(), "stamped current");
        assertFalse(out.get("mavenSupport").asBoolean(), "the user's choices are kept");
        assertEquals("/opt/mvn/bin/mvn", out.get("mavenCommand").asText());
        assertFalse(out.get("testRunner").asBoolean());
        assertFalse(out.has("buildBeforeRun"), "left to the default");
        Settings upgraded = mapper.treeToValue(out, Settings.class);
        assertTrue(upgraded.isBuildBeforeRun());
        assertFalse(upgraded.isMavenSupport());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":141,\"buildBeforeRun\":false}");
        assertFalse(mapper.treeToValue(chosen, Settings.class).isBuildBeforeRun());
        assertTrue(new Settings().isBuildBeforeRun());
    }
}
