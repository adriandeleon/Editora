package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link McpProtocol}: the revisions, the eras and what each asks of a message. Pure. */
class McpProtocolTest {

    private final ObjectMapper m = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return m.readTree(s);
    }

    @Test
    void initializeAgreesOnTheClientsLegacyRevisionElseTheNewest() {
        for (String v : McpProtocol.LEGACY) {
            assertEquals(v, McpProtocol.negotiateLegacy(v));
        }
        assertEquals("2025-11-25", McpProtocol.negotiateLegacy("1999-01-01"));
        assertEquals("2025-11-25", McpProtocol.negotiateLegacy(null));
        assertEquals("2025-11-25", McpProtocol.negotiateLegacy(McpProtocol.MODERN), "not through initialize");
    }

    @Test
    void aRequestIsModernExactlyWhenItsMetaNamesARevision() throws Exception {
        assertEquals(
                "2026-07-28",
                McpProtocol.modernVersion(
                        json("{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}")));
        assertNull(McpProtocol.modernVersion(json("{\"protocolVersion\":\"2025-11-25\"}")), "initialize's own field");
        assertNull(McpProtocol.modernVersion(json("{\"_meta\":{\"progressToken\":1}}")));
        assertNull(McpProtocol.modernVersion(null));
    }

    @Test
    void headersThatAreSentMustMatchTheBody() {
        assertNull(McpProtocol.headerMismatch(null, null, null, "2026-07-28", "tools/call", "read_buffer"));
        assertNull(McpProtocol.headerMismatch(
                "2026-07-28", "tools/call", "read_buffer", "2026-07-28", "tools/call", "read_buffer"));
        assertTrue(McpProtocol.headerMismatch("2025-11-25", null, null, "2026-07-28", "ping", null)
                .contains("MCP-Protocol-Version"));
        assertTrue(McpProtocol.headerMismatch(null, "tools/list", null, "2026-07-28", "tools/call", null)
                .contains("Mcp-Method"));
        assertTrue(McpProtocol.headerMismatch(null, null, "edit_buffer", "2026-07-28", "tools/call", "read_buffer")
                .contains("Mcp-Name"));
        String encoded = McpStdio.headerValue("file:///tmp/ünï code.txt");
        assertTrue(encoded.startsWith("=?base64?"), encoded);
        assertNull(McpProtocol.headerMismatch(
                null, null, encoded, "2026-07-28", "resources/read", "file:///tmp/ünï code.txt"));
        assertEquals("plain", McpStdio.headerValue("plain"));
        assertEquals("=?base64?bad", McpProtocol.decode("=?base64?bad"), "not a sentinel: as it is");
    }

    @Test
    void aModernResultSaysItIsCompleteWhoAnsweredAndHowLongItKeeps() {
        ObjectNode tools = McpProtocol.modernResult(m, "tools/list", m.createObjectNode());
        assertEquals("complete", tools.get("resultType").asText());
        assertEquals(
                "editora",
                tools.at("/_meta/io.modelcontextprotocol~1serverInfo/name").asText());
        assertEquals(300_000, tools.get("ttlMs").asInt());
        assertEquals("private", tools.get("cacheScope").asText());
        ObjectNode resources = McpProtocol.modernResult(m, "resources/list", m.createObjectNode());
        assertEquals(0, resources.get("ttlMs").asInt(), "the open files change at any time");
        ObjectNode call = McpProtocol.modernResult(m, "tools/call", m.createObjectNode());
        assertFalse(call.has("ttlMs"), "a call is not cached");
        assertEquals("complete", call.get("resultType").asText());
    }

    @Test
    void anUnsupportedRevisionListsTheSupportedOnes() {
        ObjectNode e = McpProtocol.unsupportedVersion(m, m.getNodeFactory().numberNode(3), "1900-01-01");
        assertEquals(-32022, e.at("/error/code").asInt());
        assertEquals("1900-01-01", e.at("/error/data/requested").asText());
        List<String> supported = new java.util.ArrayList<>();
        e.at("/error/data/supported").forEach(v -> supported.add(v.asText()));
        assertEquals(McpProtocol.SUPPORTED, supported);
    }

    @Test
    void aListenIsAcknowledgedWithWhatIsHonouredOnly() throws Exception {
        McpProtocol.Filters f = McpProtocol.Filters.of(json("{\"notifications\":{\"toolsListChanged\":true,"
                + "\"promptsListChanged\":true,\"resourceSubscriptions\":[\"file:///b\",\"file:///a\",\"\",3]}}"));
        assertEquals(List.of("file:///a", "file:///b"), f.resourceSubscriptions());
        assertEquals(
                "{\"toolsListChanged\":true,\"promptsListChanged\":true,\"resourceSubscriptions\":"
                        + "[\"file:///a\",\"file:///b\"]}",
                f.agreed(m).toString(),
                "the fixed lists are agreed to (they never fire)");
        assertEquals("{}", McpProtocol.Filters.of(null).agreed(m).toString());
    }
}
