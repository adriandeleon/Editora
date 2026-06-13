package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the plugin-registry index parsing + the HTTPS guard (pure, no network). */
class RegistryIndexTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static RegistryIndex parse(String json) throws Exception {
        return RegistryIndex.parse(MAPPER, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesEntriesAndIgnoresUnknownFields() throws Exception {
        RegistryIndex idx = parse("""
                {
                  "schemaVersion": 1,
                  "future": "ignored",
                  "plugins": [
                    {
                      "id": "example",
                      "name": "Example Plugin",
                      "version": "1.0.0",
                      "description": "A demo",
                      "author": "me",
                      "homepage": "https://github.com/me/example",
                      "download": "https://github.com/me/example/releases/download/v1.0.0/example.zip",
                      "sha256": "abc123",
                      "minEditoraVersion": "1.0.0",
                      "extra": true
                    }
                  ]
                }""");
        assertEquals(1, idx.schemaVersion);
        assertEquals(1, idx.plugins.size());
        RegistryEntry e = idx.plugins.get(0);
        assertEquals("example", e.id);
        assertEquals("Example Plugin", e.name);
        assertEquals("1.0.0", e.version);
        assertEquals("abc123", e.sha256);
        assertTrue(e.download.endsWith("example.zip"));
    }

    @Test
    void dropsEntriesWithNoIdAndToleratesMissingPlugins() throws Exception {
        RegistryIndex idx = parse("""
                {
                  "plugins": [
                    { "name": "no id here" },
                    { "id": "", "name": "blank id" },
                    { "id": "ok", "name": "Fine" }
                  ]
                }""");
        assertEquals(1, idx.plugins.size());
        assertEquals("ok", idx.plugins.get(0).id);

        RegistryIndex empty = parse("{}");
        assertTrue(empty.plugins.isEmpty());
    }

    @Test
    void httpsGuardAcceptsOnlyHttps() {
        assertTrue(PluginRegistry.isHttps("https://example.com/index.json"));
        assertTrue(PluginRegistry.isHttps("  HTTPS://Example.com/i.json  "));
        assertFalse(PluginRegistry.isHttps("http://example.com/index.json"));
        assertFalse(PluginRegistry.isHttps("file:///tmp/index.json"));
        assertFalse(PluginRegistry.isHttps("ftp://example.com"));
        assertFalse(PluginRegistry.isHttps(""));
        assertFalse(PluginRegistry.isHttps(null));
    }
}
