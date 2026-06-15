package com.editora.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a> framing for the MCP
 * endpoint: one request/response per POST (no batching). Pure response builders, unit-tested.
 */
public final class JsonRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpc() {}

    /** A success response: {@code {"jsonrpc":"2.0","id":<id>,"result":<result>}}. */
    public static ObjectNode success(ObjectMapper m, JsonNode id, JsonNode result) {
        ObjectNode r = m.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? m.nullNode() : id);
        r.set("result", result == null ? m.nullNode() : result);
        return r;
    }

    /** An error response: {@code {"jsonrpc":"2.0","id":<id>,"error":{"code":..,"message":..}}}. */
    public static ObjectNode error(ObjectMapper m, JsonNode id, int code, String message) {
        ObjectNode r = m.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? m.nullNode() : id);
        ObjectNode err = m.createObjectNode();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        r.set("error", err);
        return r;
    }
}
