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
 * v128→129: {@code debugHotCodeReplace}, {@code debugStepFilters}, {@code debugShowStatics} and
 * {@code debugExceptionTypes} are new — nothing else changes, and each starts at its default.
 */
class HotCodeReplaceSettingMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theHotCodeReplaceSettingArrivesWithoutTouchingAnythingElse() throws Exception {
        JsonNode v128 = mapper.readTree("{\"schemaVersion\":128,\"debugSupport\":true,\"debugProgramConsole\":false}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v128.deepCopy(), mapper);

        assertEquals(129, Settings.SCHEMA_VERSION);
        assertEquals(Settings.SCHEMA_VERSION, out.get("schemaVersion").asInt(), "stamped current");
        assertTrue(out.get("debugSupport").asBoolean());
        assertFalse(out.get("debugProgramConsole").asBoolean());
        assertFalse(out.has("debugHotCodeReplace"), "left to the default");
        Settings upgraded = mapper.treeToValue(out, Settings.class);
        assertEquals("auto", upgraded.getDebugHotCodeReplace());
        assertTrue(upgraded.isDebugStepFilters());
        assertTrue(upgraded.isDebugShowStatics());
        assertEquals("", upgraded.getDebugExceptionTypes());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":129,\"debugHotCodeReplace\":\"manual\"}");
        assertEquals("manual", mapper.treeToValue(chosen, Settings.class).getDebugHotCodeReplace());
        assertEquals("auto", new Settings().getDebugHotCodeReplace());
    }
}
