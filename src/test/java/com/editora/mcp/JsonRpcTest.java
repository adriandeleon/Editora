package com.editora.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure JSON-RPC 2.0 response builders. */
class JsonRpcTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void successWrapsResult() {
        ObjectNode r = JsonRpc.success(
                m, m.getNodeFactory().numberNode(1), m.createObjectNode().put("ok", true));
        assertEquals("2.0", r.get("jsonrpc").asText());
        assertEquals(1, r.get("id").asInt());
        assertTrue(r.get("result").get("ok").asBoolean());
        assertFalse(r.has("error"));
    }

    @Test
    void errorWrapsCodeAndMessage() {
        ObjectNode r = JsonRpc.error(m, m.getNodeFactory().numberNode(2), JsonRpc.METHOD_NOT_FOUND, "nope");
        assertEquals(2, r.get("id").asInt());
        assertEquals(JsonRpc.METHOD_NOT_FOUND, r.get("error").get("code").asInt());
        assertEquals("nope", r.get("error").get("message").asText());
        assertFalse(r.has("result"));
    }

    @Test
    void nullIdBecomesJsonNull() {
        ObjectNode r = JsonRpc.success(m, null, m.nullNode());
        assertTrue(r.get("id").isNull());
    }
}
