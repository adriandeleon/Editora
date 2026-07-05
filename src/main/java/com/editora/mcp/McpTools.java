package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The MCP tool set: schemas for {@code tools/list} and dispatch for {@code tools/call}. Bridge result
 * records are mapped to JSON <em>by hand</em> (never via Jackson reflection on {@code mcp} types), so
 * the package needs no {@code module-info opens}. Each tool returns the standard MCP result shape
 * {@code {"content":[{"type":"text","text":<json>}],"isError":<bool>}}.
 */
final class McpTools {

    /** The MCP protocol revision this server implements. */
    static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpBridge bridge;
    private final ObjectMapper m;

    McpTools(McpBridge bridge, ObjectMapper m) {
        this.bridge = bridge;
        this.m = m;
    }

    // --- initialize -------------------------------------------------------------------------------

    ObjectNode initializeResult() {
        ObjectNode r = m.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = m.createObjectNode();
        caps.set("tools", m.createObjectNode()); // we offer tools; no list-changed notifications
        r.set("capabilities", caps);
        ObjectNode info = m.createObjectNode();
        info.put("name", "editora");
        info.put("version", com.editora.AppInfo.VERSION);
        r.set("serverInfo", info);
        return r;
    }

    // --- tools/list -------------------------------------------------------------------------------

    ObjectNode listToolsResult() {
        ArrayNode tools = m.createArrayNode();
        tools.add(tool("list_open_files", "List the editor's open files (path, language, dirty, active).", obj()));
        tools.add(tool(
                "read_buffer",
                "Read a buffer's live (possibly unsaved) text. Omit 'path' for the active buffer.",
                obj().set("path", strProp("Absolute path of an open file; omit for the active buffer."))));
        tools.add(tool(
                "get_diagnostics",
                "Get LSP diagnostics for a file. Omit 'path' for the active buffer's file.",
                obj().set("path", strProp("Absolute path of an open file; omit for the active buffer."))));
        ObjectNode findProps = obj();
        findProps.set("query", strProp("Text or regex to search for."));
        findProps.set("caseSensitive", boolProp("Match case (default false)."));
        findProps.set("regex", boolProp("Treat the query as a regular expression (default false)."));
        findProps.set("wholeWord", boolProp("Match whole words only (default false)."));
        tools.add(toolReq(
                "find_in_files",
                "Search the active project (and open buffers' unsaved text) for a query.",
                findProps,
                "query"));
        tools.add(tool("list_commands", "List every command that execute_command can run.", obj()));
        tools.add(toolReq(
                "execute_command",
                "Run a registered command by id (edits go through the undo stack). See list_commands.",
                obj().set("id", strProp("The command id, e.g. \"file.save\".")),
                "id"));
        ObjectNode openProps = obj();
        openProps.set("path", strProp("Absolute path of the file to open."));
        openProps.set("line", intProp("Optional 1-based line to move the caret to."));
        openProps.set("col", intProp("Optional 1-based column (used only with 'line')."));
        tools.add(toolReq(
                "open_file",
                "Open a file in the editor, optionally moving the caret to a line/column.",
                openProps,
                "path"));
        ObjectNode editProps = obj();
        editProps.set("path", strProp("Absolute path of an open file; omit for the active buffer."));
        editProps.set(
                "old_text",
                strProp("Exact text to replace (must occur exactly once unless replace_all)."
                        + " Omit or pass \"\" to replace the whole buffer."));
        editProps.set("new_text", strProp("Replacement text."));
        editProps.set("replace_all", boolProp("Replace every occurrence of old_text (default false)."));
        tools.add(toolReq(
                "edit_buffer",
                "Apply an undoable text edit to an open buffer (the user can undo it with one C-z)."
                        + " Omit 'path' for the active buffer.",
                editProps,
                "new_text"));
        tools.add(tool(
                "save_buffer",
                "Save an open buffer to its file on disk. Omit 'path' for the active buffer.",
                obj().set("path", strProp("Absolute path of an open file; omit for the active buffer."))));
        tools.add(tool(
                "get_selection",
                "Get the active buffer's caret position and current selection (1-based lines/columns).",
                obj()));
        tools.add(tool(
                "document_symbols",
                "Get a file's symbol outline (classes/methods/fields) from its language server."
                        + " Omit 'path' for the active buffer's file. Empty when no server serves the file.",
                obj().set("path", strProp("Absolute path of an open file; omit for the active buffer."))));
        tools.add(tool(
                "git_status",
                "Get the Git status for the editor's context: branch, upstream, ahead/behind, changed files.",
                obj()));
        tools.add(tool(
                "list_tabs",
                "List every open tab, including non-editor tabs (Welcome, image, hex, diff), with each tab's"
                        + " type and which one is active.",
                obj()));
        tools.add(tool(
                "todo_scan",
                "Scan the active project (and open buffers' unsaved text) for TODO/FIXME-style highlight"
                        + " patterns; returns each match's file, line/col, keyword, tag, priority, and line text.",
                obj()));
        ObjectNode r = m.createObjectNode();
        r.set("tools", tools);
        return r;
    }

    // --- tools/call -------------------------------------------------------------------------------

    /** Dispatches {@code tools/call} params {@code {name, arguments}} → an MCP tool result node. */
    ObjectNode callTool(JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            return errorResult("Missing tool name.");
        }
        String name = params.get("name").asText();
        JsonNode args = params.get("arguments");
        return switch (name) {
            case "list_open_files" -> openFilesResult();
            case "read_buffer" -> readBufferResult(text(args, "path"));
            case "get_diagnostics" -> diagnosticsResult(text(args, "path"));
            case "find_in_files" -> findResult(args);
            case "list_commands" -> commandsResult();
            case "execute_command" -> executeResult(text(args, "id"));
            case "open_file" -> openFileResult(args);
            case "edit_buffer" -> editBufferResult(args);
            case "save_buffer" -> saveBufferResult(text(args, "path"));
            case "get_selection" -> selectionResult();
            case "document_symbols" -> symbolsResult(text(args, "path"));
            case "git_status" -> gitStatusResult();
            case "list_tabs" -> tabsResult();
            case "todo_scan" -> todoScanResult();
            default -> errorResult("Unknown tool: " + name);
        };
    }

    private ObjectNode openFilesResult() {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            ObjectNode o = m.createObjectNode();
            o.put("path", f.path());
            o.put("title", f.title());
            o.put("language", f.language());
            o.put("dirty", f.dirty());
            o.put("active", f.active());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode tabsResult() {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.TabInfo t : bridge.listTabs()) {
            ObjectNode o = m.createObjectNode();
            o.put("type", t.type());
            o.put("title", t.title());
            o.put("path", t.path());
            o.put("active", t.active());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode todoScanResult() {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.TodoItem t : bridge.todoScan()) {
            ObjectNode o = m.createObjectNode();
            o.put("file", t.file());
            o.put("line", t.line());
            o.put("col", t.col());
            o.put("keyword", t.keyword());
            o.put("tag", t.tag());
            o.put("priority", t.priority());
            o.put("text", t.text());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode readBufferResult(String path) {
        McpBridge.BufferContent b = bridge.readBuffer(path);
        if (b == null) {
            return errorResult(path == null ? "No active buffer." : "No open buffer for: " + path);
        }
        ObjectNode o = m.createObjectNode();
        o.put("path", b.path());
        o.put("title", b.title());
        o.put("language", b.language());
        o.put("dirty", b.dirty());
        o.put("text", b.text());
        return textResult(o);
    }

    private ObjectNode diagnosticsResult(String path) {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.Diagnostic d : bridge.getDiagnostics(path)) {
            ObjectNode o = m.createObjectNode();
            o.put("line", d.line());
            o.put("col", d.col());
            o.put("severity", d.severity());
            o.put("message", d.message());
            o.put("origin", d.origin());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode findResult(JsonNode args) {
        String query = text(args, "query");
        if (query == null || query.isEmpty()) {
            return errorResult("find_in_files requires a non-empty 'query'.");
        }
        List<McpBridge.SearchMatch> hits =
                bridge.findInFiles(query, bool(args, "caseSensitive"), bool(args, "regex"), bool(args, "wholeWord"));
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.SearchMatch h : hits) {
            ObjectNode o = m.createObjectNode();
            o.put("file", h.file());
            o.put("line", h.line());
            o.put("col", h.col());
            o.put("lineText", h.lineText());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode commandsResult() {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.CommandInfo c : bridge.listCommands()) {
            ObjectNode o = m.createObjectNode();
            o.put("id", c.id());
            o.put("title", c.title());
            o.put("description", c.description());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode executeResult(String id) {
        if (id == null || id.isEmpty()) {
            return errorResult("execute_command requires an 'id'.");
        }
        boolean ran = bridge.executeCommand(id);
        return ran
                ? textResult(m.createObjectNode().put("ran", true).put("id", id))
                : errorResult("No such command: " + id);
    }

    private ObjectNode openFileResult(JsonNode args) {
        String path = text(args, "path");
        if (path == null) {
            return errorResult("open_file requires a 'path'.");
        }
        boolean opened = bridge.openFile(path, intArg(args, "line"), intArg(args, "col"));
        return opened
                ? textResult(m.createObjectNode().put("opened", true).put("path", path))
                : errorResult("No such file: " + path);
    }

    private ObjectNode editBufferResult(JsonNode args) {
        String newText = args != null && args.hasNonNull("new_text")
                ? args.get("new_text").asText()
                : null;
        if (newText == null) {
            return errorResult("edit_buffer requires 'new_text'.");
        }
        String error =
                bridge.editBuffer(text(args, "path"), text(args, "old_text"), newText, bool(args, "replace_all"));
        return error == null ? textResult(m.createObjectNode().put("applied", true)) : errorResult(error);
    }

    private ObjectNode saveBufferResult(String path) {
        String error = bridge.saveBuffer(path);
        return error == null ? textResult(m.createObjectNode().put("saved", true)) : errorResult(error);
    }

    private ObjectNode selectionResult() {
        McpBridge.Selection s = bridge.getSelection();
        if (s == null) {
            return errorResult("No active buffer.");
        }
        ObjectNode o = m.createObjectNode();
        o.put("path", s.path());
        o.put("title", s.title());
        o.put("caretLine", s.caretLine());
        o.put("caretCol", s.caretCol());
        o.put("selStartLine", s.selStartLine());
        o.put("selStartCol", s.selStartCol());
        o.put("selEndLine", s.selEndLine());
        o.put("selEndCol", s.selEndCol());
        o.put("selectedText", s.selectedText());
        return textResult(o);
    }

    private ObjectNode symbolsResult(String path) {
        return textResult(symbolArray(bridge.documentSymbols(path)));
    }

    private ArrayNode symbolArray(List<McpBridge.Symbol> symbols) {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.Symbol s : symbols) {
            ObjectNode o = m.createObjectNode();
            o.put("name", s.name());
            if (!s.detail().isEmpty()) {
                o.put("detail", s.detail());
            }
            o.put("kind", s.kind());
            o.put("line", s.line());
            o.put("endLine", s.endLine());
            if (!s.children().isEmpty()) {
                o.set("children", symbolArray(s.children()));
            }
            arr.add(o);
        }
        return arr;
    }

    private ObjectNode gitStatusResult() {
        McpBridge.GitState g = bridge.gitStatus();
        ObjectNode o = m.createObjectNode();
        o.put("repo", g.repo());
        if (g.repo()) {
            o.put("root", g.root());
            o.put("branch", g.branch());
            o.put("upstream", g.upstream());
            o.put("ahead", g.ahead());
            o.put("behind", g.behind());
            ArrayNode files = m.createArrayNode();
            for (McpBridge.GitFileState f : g.files()) {
                ObjectNode fo = m.createObjectNode();
                fo.put("path", f.path());
                fo.put("index", f.index());
                fo.put("worktree", f.worktree());
                if (f.origPath() != null) {
                    fo.put("origPath", f.origPath());
                }
                files.add(fo);
            }
            o.set("files", files);
        }
        return textResult(o);
    }

    // --- result + schema helpers ------------------------------------------------------------------

    /** Wraps {@code data} (serialized to pretty JSON) as a single MCP text content block. */
    private ObjectNode textResult(JsonNode data) {
        String text;
        try {
            text = m.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (Exception e) {
            return errorResult("Serialization failed: " + e.getMessage());
        }
        ObjectNode block = m.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        ObjectNode r = m.createObjectNode();
        ArrayNode content = m.createArrayNode();
        content.add(block);
        r.set("content", content);
        r.put("isError", false);
        return r;
    }

    private ObjectNode errorResult(String message) {
        ObjectNode block = m.createObjectNode();
        block.put("type", "text");
        block.put("text", message);
        ObjectNode r = m.createObjectNode();
        ArrayNode content = m.createArrayNode();
        content.add(block);
        r.set("content", content);
        r.put("isError", true);
        return r;
    }

    private ObjectNode tool(String name, String description, ObjectNode properties) {
        return toolReq(name, description, properties);
    }

    private ObjectNode toolReq(String name, String description, ObjectNode properties, String... required) {
        ObjectNode t = m.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = m.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        if (required.length > 0) {
            ArrayNode req = m.createArrayNode();
            for (String s : required) {
                req.add(s);
            }
            schema.set("required", req);
        }
        t.set("inputSchema", schema);
        return t;
    }

    private ObjectNode obj() {
        return m.createObjectNode();
    }

    private ObjectNode strProp(String description) {
        return m.createObjectNode().put("type", "string").put("description", description);
    }

    private ObjectNode boolProp(String description) {
        return m.createObjectNode().put("type", "boolean").put("description", description);
    }

    private ObjectNode intProp(String description) {
        return m.createObjectNode().put("type", "integer").put("description", description);
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String v = args.get(field).asText();
        return v.isEmpty() ? null : v;
    }

    private static boolean bool(JsonNode args, String field) {
        return args != null && args.hasNonNull(field) && args.get(field).asBoolean(false);
    }

    /** Reads an optional integer argument; 0 when absent (callers treat {@code <= 0} as "not given"). */
    private static int intArg(JsonNode args, String field) {
        return args != null && args.hasNonNull(field) ? args.get(field).asInt(0) : 0;
    }
}
