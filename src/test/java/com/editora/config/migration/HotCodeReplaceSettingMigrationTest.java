package com.editora.config.migration;

import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v126→127: {@code debugHotCodeReplace} is new — nothing else changes, and it starts at "auto". */
class HotCodeReplaceSettingMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theHotCodeReplaceSettingArrivesWithoutTouchingAnythingElse() throws Exception {
        JsonNode v126 = mapper.readTree("{\"schemaVersion\":126,\"debugSupport\":true,\"debugProgramConsole\":false}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v126.deepCopy(), mapper);

        assertEquals(127, Settings.SCHEMA_VERSION);
        assertEquals(Settings.SCHEMA_VERSION, out.get("schemaVersion").asInt(), "stamped current");
        assertTrue(out.get("debugSupport").asBoolean());
        assertFalse(out.get("debugProgramConsole").asBoolean());
        assertFalse(out.has("debugHotCodeReplace"), "left to the default");
        assertEquals("auto", mapper.treeToValue(out, Settings.class).getDebugHotCodeReplace());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":127,\"debugHotCodeReplace\":\"manual\"}");
        assertEquals("manual", mapper.treeToValue(chosen, Settings.class).getDebugHotCodeReplace());
        assertEquals("auto", new Settings().getDebugHotCodeReplace());
    }
}
