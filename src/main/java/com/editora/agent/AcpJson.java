package com.editora.agent;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure JSON payload builders + parsers for the <a href="https://agentclientprotocol.com">Agent Client
 * Protocol</a> (ACP) — JSON-RPC 2.0 over the agent process's stdio, newline-delimited. {@link AcpClient}
 * does the process/thread work; everything here is side-effect-free and unit-tested. JSON is mapped by
 * hand (Jackson tree API, never reflection on {@code agent} types), so the package needs no
 * {@code module-info opens}.
 */
public final class AcpJson {

    /** The ACP protocol major version this client speaks. */
    public static final int PROTOCOL_VERSION = 1;

    /** JSON-RPC error code: method not found. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC error code: internal error (a client-side handler threw). */
    public static final int INTERNAL_ERROR = -32603;

    private AcpJson() {}

    // --- envelopes ----------------------------------------------------------------------------------

    public static ObjectNode request(ObjectMapper m, long id, String method, JsonNode params) {
        ObjectNode n = envelope(m, method, params);
        n.put("id", id);
        return n;
    }

    public static ObjectNode notification(ObjectMapper m, String method, JsonNode params) {
        return envelope(m, method, params);
    }

    public static ObjectNode response(ObjectMapper m, JsonNode id, JsonNode result) {
        ObjectNode n = m.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result == null ? m.nullNode() : result);
        return n;
    }

    public static ObjectNode errorResponse(ObjectMapper m, JsonNode id, int code, String message) {
        ObjectNode n = m.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        ObjectNode err = m.createObjectNode();
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        n.set("error", err);
        return n;
    }

    private static ObjectNode envelope(ObjectMapper m, String method, JsonNode params) {
        ObjectNode n = m.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        if (params != null) {
            n.set("params", params);
        }
        return n;
    }

    // --- request params -----------------------------------------------------------------------------

    /** {@code initialize}: our protocol version + the client capabilities we serve (fs read/write). */
    public static ObjectNode initializeParams(ObjectMapper m) {
        ObjectNode fs = m.createObjectNode();
        fs.put("readTextFile", true);
        fs.put("writeTextFile", true);
        ObjectNode caps = m.createObjectNode();
        caps.set("fs", fs);
        ObjectNode p = m.createObjectNode();
        p.put("protocolVersion", PROTOCOL_VERSION);
        p.set("clientCapabilities", caps);
        return p;
    }

    /** {@code session/new}: the working directory (absolute) + no client-provided MCP servers. */
    public static ObjectNode newSessionParams(ObjectMapper m, String cwd) {
        ObjectNode p = m.createObjectNode();
        p.put("cwd", cwd);
        p.set("mcpServers", m.createArrayNode());
        return p;
    }

    /** {@code session/prompt}: one text content block. */
    public static ObjectNode promptParams(ObjectMapper m, String sessionId, String text) {
        ObjectNode block = m.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        ArrayNode prompt = m.createArrayNode();
        prompt.add(block);
        ObjectNode p = m.createObjectNode();
        p.put("sessionId", sessionId);
        p.set("prompt", prompt);
        return p;
    }

    /** {@code session/cancel} (a notification). */
    public static ObjectNode cancelParams(ObjectMapper m, String sessionId) {
        ObjectNode p = m.createObjectNode();
        p.put("sessionId", sessionId);
        return p;
    }

    /** {@code session/set_model}. */
    public static ObjectNode setModelParams(ObjectMapper m, String sessionId, String modelId) {
        ObjectNode p = m.createObjectNode();
        p.put("sessionId", sessionId);
        p.put("modelId", modelId);
        return p;
    }

    /** {@code session/set_mode}. */
    public static ObjectNode setModeParams(ObjectMapper m, String sessionId, String modeId) {
        ObjectNode p = m.createObjectNode();
        p.put("sessionId", sessionId);
        p.put("modeId", modeId);
        return p;
    }

    /** {@code session/request_permission} response: the user picked {@code optionId}, or cancelled (null). */
    public static ObjectNode permissionOutcome(ObjectMapper m, String optionId) {
        ObjectNode outcome = m.createObjectNode();
        if (optionId == null) {
            outcome.put("outcome", "cancelled");
        } else {
            outcome.put("outcome", "selected");
            outcome.put("optionId", optionId);
        }
        ObjectNode r = m.createObjectNode();
        r.set("outcome", outcome);
        return r;
    }

    // --- incoming parsing ---------------------------------------------------------------------------

    /** The kinds of {@code session/update} notification this client surfaces. */
    public enum UpdateKind {
        /** Streaming assistant text ({@code agent_message_chunk}). */
        AGENT_MESSAGE,
        /** Streaming reasoning text ({@code agent_thought_chunk}). */
        AGENT_THOUGHT,
        /** A tool call started ({@code tool_call}) — {@code text} is its title. */
        TOOL_CALL,
        /** A tool call changed state ({@code tool_call_update}) — {@code text} is the new status. */
        TOOL_CALL_UPDATE,
        /** The agent published/updated its plan — {@code text} is the flattened entries, {@code planEntries}
         *  the structured form (status per entry). */
        PLAN,
        /** The session's mode changed ({@code current_mode_update}) — {@code text} is the new mode id. */
        MODE_CHANGED,
        /** Anything unrecognized (forward-compatible: ignored by the UI). */
        OTHER
    }

    /** One parsed {@code session/update} notification. {@code planEntries} is non-empty only for
     *  {@link UpdateKind#PLAN}. */
    public record Update(String sessionId, UpdateKind kind, String text, List<PlanEntry> planEntries) {}

    /** A permission option offered by {@code session/request_permission}. */
    public record PermissionOption(String optionId, String name, String kind) {}

    /** One selectable model, as offered by {@code session/new}'s {@code models.availableModels}. */
    public record ModelInfo(String modelId, String name, String description) {}

    /** One selectable mode, as offered by {@code session/new}'s {@code modes.availableModes}. */
    public record ModeInfo(String id, String name, String description) {}

    /** One plan task: {@code status} is {@code pending}/{@code in_progress}/{@code completed}. */
    public record PlanEntry(String content, String status) {}

    /** The parsed {@code session/new} result: the new session id plus its model/mode catalogs. */
    public record SessionInfo(
            String sessionId,
            List<ModelInfo> models,
            String currentModelId,
            List<ModeInfo> modes,
            String currentModeId) {}

    /** Parses a {@code session/update} notification's params; never throws (unknown → OTHER). */
    public static Update parseUpdate(JsonNode params) {
        String sessionId = textOf(params, "sessionId");
        JsonNode update = params == null ? null : params.get("update");
        String type = textOf(update, "sessionUpdate");
        if (update == null || type == null) {
            return new Update(sessionId, UpdateKind.OTHER, "", List.of());
        }
        return switch (type) {
            case "agent_message_chunk" ->
                new Update(sessionId, UpdateKind.AGENT_MESSAGE, contentText(update), List.of());
            case "agent_thought_chunk" ->
                new Update(sessionId, UpdateKind.AGENT_THOUGHT, contentText(update), List.of());
            case "tool_call" -> new Update(sessionId, UpdateKind.TOOL_CALL, toolCallLabel(update), List.of());
            case "tool_call_update" ->
                new Update(sessionId, UpdateKind.TOOL_CALL_UPDATE, textOrEmpty(update, "status"), List.of());
            case "plan" -> new Update(sessionId, UpdateKind.PLAN, planText(update), parsePlanEntries(update));
            case "current_mode_update" ->
                new Update(sessionId, UpdateKind.MODE_CHANGED, textOrEmpty(update, "currentModeId"), List.of());
            default -> new Update(sessionId, UpdateKind.OTHER, "", List.of());
        };
    }

    /** The text of a {@code {type:"text", text:…}} content block carried in {@code update.content}. */
    public static String contentText(JsonNode update) {
        JsonNode content = update == null ? null : update.get("content");
        if (content == null) {
            return "";
        }
        String direct = textOf(content, "text");
        return direct == null ? "" : direct;
    }

    /** The permission options offered by a {@code session/request_permission} request. */
    public static List<PermissionOption> parsePermissionOptions(JsonNode params) {
        List<PermissionOption> out = new ArrayList<>();
        JsonNode options = params == null ? null : params.get("options");
        if (options != null && options.isArray()) {
            for (JsonNode o : options) {
                String id = textOf(o, "optionId");
                if (id != null) {
                    out.add(new PermissionOption(id, textOrEmpty(o, "name"), textOrEmpty(o, "kind")));
                }
            }
        }
        return out;
    }

    /** The human title of the tool call a permission request is about (best-effort, may be empty). */
    public static String permissionTitle(JsonNode params) {
        JsonNode toolCall = params == null ? null : params.get("toolCall");
        String title = textOf(toolCall, "title");
        return title != null ? title : "";
    }

    /** Parses {@code session/new}'s result: the session id plus its model/mode catalogs. Null-safe
     *  throughout — a missing/absent {@code models}/{@code modes} object yields an empty catalog. */
    public static SessionInfo parseSessionInfo(JsonNode result) {
        String sessionId = textOf(result, "sessionId");
        List<ModelInfo> models = new ArrayList<>();
        String currentModelId = null;
        JsonNode modelsNode = result == null ? null : result.get("models");
        if (modelsNode != null) {
            currentModelId = textOf(modelsNode, "currentModelId");
            JsonNode avail = modelsNode.get("availableModels");
            if (avail != null && avail.isArray()) {
                for (JsonNode mo : avail) {
                    models.add(new ModelInfo(
                            textOrEmpty(mo, "modelId"), textOrEmpty(mo, "name"), textOrEmpty(mo, "description")));
                }
            }
        }
        List<ModeInfo> modes = new ArrayList<>();
        String currentModeId = null;
        JsonNode modesNode = result == null ? null : result.get("modes");
        if (modesNode != null) {
            currentModeId = textOf(modesNode, "currentModeId");
            JsonNode avail = modesNode.get("availableModes");
            if (avail != null && avail.isArray()) {
                for (JsonNode md : avail) {
                    modes.add(new ModeInfo(
                            textOrEmpty(md, "id"), textOrEmpty(md, "name"), textOrEmpty(md, "description")));
                }
            }
        }
        return new SessionInfo(sessionId, models, currentModelId, modes, currentModeId);
    }

    private static String toolCallLabel(JsonNode update) {
        String title = textOf(update, "title");
        if (title != null && !title.isEmpty()) {
            return title;
        }
        return textOrEmpty(update, "kind");
    }

    private static String planText(JsonNode update) {
        JsonNode entries = update.get("entries");
        if (entries == null || !entries.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : entries) {
            String content = textOf(e, "content");
            if (content != null && !content.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(content);
            }
        }
        return sb.toString();
    }

    /** The structured plan entries (content + status) — a {@code plan} update always replaces the whole list. */
    private static List<PlanEntry> parsePlanEntries(JsonNode update) {
        List<PlanEntry> out = new ArrayList<>();
        JsonNode entries = update.get("entries");
        if (entries != null && entries.isArray()) {
            for (JsonNode e : entries) {
                out.add(new PlanEntry(textOrEmpty(e, "content"), textOrEmpty(e, "status")));
            }
        }
        return out;
    }

    private static String textOf(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        String v = textOf(node, field);
        return v == null ? "" : v;
    }
}
