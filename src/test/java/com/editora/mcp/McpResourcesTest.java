package com.editora.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP resources on {@link McpTools} (no HTTP): what is listed, what reads, and the stamps the watch compares. */
class McpResourcesTest {

    private final ObjectMapper m = new ObjectMapper();
    private final McpToolsTest.StubBridge bridge = new McpToolsTest.StubBridge();
    private final McpTools tools = new McpTools(bridge, m);
    private final String path = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath()
            .resolve("a.md")
            .toString();

    @Test
    void anUntitledBufferIsNotAResourceAndEveryListedFileHasAType() {
        bridge.openFiles = List.of(
                new McpBridge.OpenFile(null, "Untitled-1", "plain", true, true),
                new McpBridge.OpenFile(path, "a.md", "markdown", true, false));
        JsonNode list = tools.listResourcesResult().get("resources");
        assertEquals(3, list.size(), "the file, the diagnostics, the Git status: " + list);
        JsonNode file = list.get(0);
        assertEquals(McpTools.fileUri(path), file.get("uri").asText());
        assertEquals("text/markdown", file.get("mimeType").asText());
        assertTrue(file.get("description").asText().contains("unsaved"));
    }

    @Test
    void theDiagnosticsResourceGroupsThemByOpenFile() throws Exception {
        bridge.openFiles = List.of(new McpBridge.OpenFile(path, "a.md", "markdown", false, true));
        bridge.diagnostics = List.of(new McpBridge.Diagnostic(3, 7, "warning", "Unused", "markdownlint"));
        ObjectNode read = tools.readResourceResult(McpTools.DIAGNOSTICS_URI);
        JsonNode byPath = m.readTree(read.at("/contents/0/text").asText());
        assertEquals("Unused", byPath.get(path).get(0).get("message").asText());
        assertEquals(3, byPath.get(path).get(0).get("line").asInt());

        Map<String, Long> stamps = Map.of(path, 5L);
        String before = tools.resourceStamp(McpTools.DIAGNOSTICS_URI, stamps);
        bridge.diagnostics = List.of();
        assertNotEquals(before, tools.resourceStamp(McpTools.DIAGNOSTICS_URI, stamps), "a fixed warning is a change");
        assertEquals("5", tools.resourceStamp(McpTools.fileUri(path), stamps), "a file: its stamp, not its text");
        assertNull(tools.resourceStamp(McpTools.fileUri(path + ".gone"), stamps), "a file no longer open");
        assertTrue(tools.resourceStamp(McpTools.GIT_STATUS_URI, stamps).contains("repo"));
        assertEquals(
                java.util.Set.of(McpTools.fileUri(path), McpTools.DIAGNOSTICS_URI, McpTools.GIT_STATUS_URI),
                tools.resourceUris(stamps));
    }

    @Test
    void onlyAnOpenFileOrOneOfTheJsonResourcesReads() {
        assertThrows(McpTools.ResourceNotFound.class, () -> tools.readResourceResult(null));
        assertThrows(McpTools.ResourceNotFound.class, () -> tools.readResourceResult(""));
        assertThrows(McpTools.ResourceNotFound.class, () -> tools.readResourceResult("https://example.com/a"));
        McpTools.ResourceNotFound notOpen =
                assertThrows(McpTools.ResourceNotFound.class, () -> tools.readResourceResult(McpTools.fileUri(path)));
        assertTrue(notOpen.getMessage().contains("Only files open in Editora"), notOpen.getMessage());
        assertNull(McpTools.filePath("not a uri at all"));
        assertNull(McpTools.filePath("editora://diagnostics"));
        assertEquals(path, McpTools.filePath(McpTools.fileUri(path)));
    }

    @Test
    void eachLanguageGetsAMediaType() {
        Map<String, String> expected = Map.of(
                "markdown", "text/markdown",
                "json", "application/json",
                "html", "text/html",
                "xml", "application/xml",
                "css", "text/css",
                "javascript", "text/javascript",
                "yaml", "application/yaml",
                "java", "text/plain");
        expected.forEach((language, type) -> assertEquals(type, McpTools.mimeType(language), language));
    }
}
