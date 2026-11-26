package com.editora.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * The MCP revisions this server speaks, and what each era asks of a message. Pure; shared by {@link McpServer} and
 * the {@code editora mcp} relay ({@link McpStdio}).
 *
 * <p>Two eras. The <b>legacy</b> one (2024-11-05 to 2025-11-25) starts with {@code initialize}, which negotiates
 * one revision. The <b>modern</b> one (2026-07-28) has no handshake: every request names its revision in
 * {@code _meta}, {@code server/discover} says what the server supports, every result says
 * {@code resultType}, list and read results say how long they stay fresh ({@code ttlMs}, {@code cacheScope}), and
 * notifications come on a {@code subscriptions/listen} stream. A request is modern exactly when its
 * {@code params._meta} names a revision.
 */
final class McpProtocol {

    /** The modern revision. */
    static final String MODERN = "2026-07-28";

    /** The legacy revisions, newest first: {@code initialize} agrees on the client's, else the first. */
    static final List<String> LEGACY = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** Every revision, newest first, as {@code server/discover} and a version error list them. */
    static final List<String> SUPPORTED = List.of(MODERN, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final String META_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";
    static final String META_SUBSCRIPTION = "io.modelcontextprotocol/subscriptionId";

    /** JSON-RPC codes the modern revision defines. */
    static final int HEADER_MISMATCH = -32020;

    static final int UNSUPPORTED_VERSION = -32022;

    /** A resource that is not there: the legacy code, and the modern one (invalid params). */
    static final int LEGACY_RESOURCE_NOT_FOUND = -32002;

    /** Results a modern client may cache, which therefore say for how long. */
    private static final Set<String> CACHEABLE = Set.of(
            "server/discover",
            "tools/list",
            "prompts/list",
            "resources/list",
            "resources/read",
            "resources/templates/list");

    private McpProtocol() {}

    /** The revision a modern request names in {@code params._meta}, or null for a legacy one. */
    static @Nullable String modernVersion(@Nullable JsonNode params) {
        if (params == null || !params.isObject()) {
            return null;
        }
        JsonNode meta = params.get("_meta");
        if (meta == null || !meta.isObject() || !meta.hasNonNull(META_VERSION)) {
            return null;
        }
        return meta.get(META_VERSION).asText();
    }

    /** What {@code initialize} agrees on: the client's legacy revision, else the newest legacy one. */
    static String negotiateLegacy(@Nullable String requested) {
        return requested != null && LEGACY.contains(requested) ? requested : LEGACY.get(0);
    }

    /** Whether {@code version} is one this server speaks. */
    static boolean supported(@Nullable String version) {
        return version != null && SUPPORTED.contains(version);
    }

    /** {@code -32022}: the revision asked for is not one of ours; {@code data} lists them. */
    static ObjectNode unsupportedVersion(ObjectMapper m, JsonNode id, String requested) {
        ObjectNode r = JsonRpc.error(m, id, UNSUPPORTED_VERSION, "Unsupported protocol version");
        ObjectNode data = m.createObjectNode();
        ArrayNode supported = data.putArray("supported");
        SUPPORTED.forEach(supported::add);
        data.put("requested", requested);
        ((ObjectNode) r.get("error")).set("data", data);
        return r;
    }

    /**
     * The name a modern request's {@code Mcp-Name} header must match: the tool of {@code tools/call}, the prompt
     * of {@code prompts/get}, the URI of {@code resources/read}; null for other methods.
     */
    static @Nullable String nameOf(String method, @Nullable JsonNode params) {
        String field = switch (method) {
            case "tools/call", "prompts/get" -> "name";
            case "resources/read" -> "uri";
            default -> null;
        };
        if (field == null || params == null || !params.hasNonNull(field)) {
            return null;
        }
        return params.get(field).asText();
    }

    /**
     * Why the standard request headers disagree with the body, or null when they agree. A header left out is not a
     * disagreement (a client that sends none still gets an answer); one that is there must match. The modern
     * revision may encode a value as {@code =?base64?…?=}, decoded before comparing.
     */
    static @Nullable String headerMismatch(
            @Nullable String versionHeader,
            @Nullable String methodHeader,
            @Nullable String nameHeader,
            @Nullable String bodyVersion,
            String method,
            @Nullable String bodyName) {
        if (versionHeader != null && bodyVersion != null && !versionHeader.equals(bodyVersion)) {
            return "Header mismatch: MCP-Protocol-Version header value '" + versionHeader
                    + "' does not match body value '" + bodyVersion + "'";
        }
        if (methodHeader != null && !decode(methodHeader).equals(method)) {
            return "Header mismatch: Mcp-Method header value '" + methodHeader + "' does not match body value '"
                    + method + "'";
        }
        if (nameHeader != null && bodyName != null && !decode(nameHeader).equals(bodyName)) {
            return "Header mismatch: Mcp-Name header value '" + nameHeader + "' does not match body value '" + bodyName
                    + "'";
        }
        return null;
    }

    /** A header value as sent: {@code =?base64?…?=} decoded (UTF-8), anything else as it is. */
    static String decode(String header) {
        if (header.startsWith("=?base64?") && header.endsWith("?=") && header.length() >= 11) {
            try {
                return new String(
                        Base64.getDecoder().decode(header.substring(9, header.length() - 2)), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return header;
            }
        }
        return header;
    }

    /**
     * Completes a modern result in place: {@code resultType}, the server's identity in {@code _meta}, and for a
     * cacheable method how long it stays fresh. {@code ttlMs} 0 means "ask again"; the lists that follow the open
     * files change at any time.
     */
    static ObjectNode modernResult(ObjectMapper m, String method, ObjectNode result) {
        result.put("resultType", "complete");
        ObjectNode meta = result.has("_meta") && result.get("_meta").isObject()
                ? (ObjectNode) result.get("_meta")
                : result.putObject("_meta");
        meta.set(META_SERVER_INFO, serverInfo(m));
        if (CACHEABLE.contains(method)) {
            boolean stable = method.equals("server/discover") || method.equals("tools/list");
            result.put("ttlMs", stable ? 300_000 : 0);
            result.put("cacheScope", "private");
        }
        return result;
    }

    /** What a {@code subscriptions/listen} asked for, and what this server honours of it. */
    record Filters(boolean toolsListChanged, boolean resourcesListChanged, List<String> resourceSubscriptions) {

        /** The request's {@code params.notifications}; prompts are not offered, so not honoured. */
        static Filters of(@Nullable JsonNode params) {
            JsonNode f = params == null ? null : params.get("notifications");
            if (f == null || !f.isObject()) {
                return new Filters(false, false, List.of());
            }
            java.util.TreeSet<String> uris = new java.util.TreeSet<>();
            if (f.path("resourceSubscriptions").isArray()) {
                f.get("resourceSubscriptions").forEach(u -> {
                    if (u.isTextual() && !u.asText().isEmpty()) {
                        uris.add(u.asText());
                    }
                });
            }
            return new Filters(
                    f.path("toolsListChanged").asBoolean(false),
                    f.path("resourcesListChanged").asBoolean(false),
                    List.copyOf(uris));
        }

        /** The acknowledgement's {@code notifications}: only what is honoured. */
        ObjectNode agreed(ObjectMapper m) {
            ObjectNode a = m.createObjectNode();
            if (toolsListChanged) {
                a.put("toolsListChanged", true); // honoured: the tool list is fixed, so it never fires
            }
            if (resourcesListChanged) {
                a.put("resourcesListChanged", true);
            }
            if (!resourceSubscriptions.isEmpty()) {
                resourceSubscriptions.forEach(a.putArray("resourceSubscriptions")::add);
            }
            return a;
        }
    }

    /** A notification; for a modern subscription, tagged with its id in {@code params._meta}. */
    static ObjectNode notification(
            ObjectMapper m, String method, @Nullable ObjectNode params, @Nullable JsonNode subscriptionId) {
        ObjectNode n = m.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        ObjectNode p = params == null ? m.createObjectNode() : params;
        if (subscriptionId != null) {
            p.putObject("_meta").set(META_SUBSCRIPTION, subscriptionId);
        }
        n.set("params", p);
        return n;
    }

    /** The server's {@code Implementation}. */
    static ObjectNode serverInfo(ObjectMapper m) {
        ObjectNode info = m.createObjectNode();
        info.put("name", "editora");
        info.put("title", "Editora");
        info.put("version", com.editora.AppInfo.VERSION);
        return info;
    }
}
