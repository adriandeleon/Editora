package com.editora.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the $shared environment merge in http-client.env.json. */
class SharedEnvTest {

    private static final String JSON = """
            {
              "$shared": { "host": "https://shared", "common": "c" },
              "dev":     { "host": "https://dev", "token": "d" }
            }
            """;

    @Test
    void sharedVarsMergeUnderTheSelectedEnvironment() {
        Map<String, String> dev = HttpEnv.variables(JSON, "dev");
        assertEquals("https://dev", dev.get("host")); // specific overrides shared
        assertEquals("c", dev.get("common")); // shared-only key present
        assertEquals("d", dev.get("token"));
    }

    @Test
    void sharedIsNotListedAsASelectableEnvironment() {
        List<String> names = HttpEnv.environmentNames(JSON);
        assertTrue(names.contains("dev"));
        assertFalse(names.contains("$shared"));
        assertEquals(1, names.size());
    }
}
