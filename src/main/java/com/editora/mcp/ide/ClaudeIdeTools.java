package com.editora.mcp.ide;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import com.editora.mcp.McpBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The IDE tools Claude Code calls over its IDE connection, with the names, arguments and answers the editor plugins
 * use (see {@link ClaudeIdeServer}), answered through the window's {@link McpBridge}. {@code openDiff} is a
 * {@code propose_edit} of the whole file: it waits for Accept (then saves, and answers {@code FILE_SAVED}) or Reject
 * ({@code DIFF_REJECTED}).
 */
final class ClaudeIdeTools {

    private final ObjectMapper m;
    private final McpBridge bridge;
    private final Supplier<List<Path>> folders;

    ClaudeIdeTools(ObjectMapper m, McpBridge bridge, Supplier<List<Path>> folders) {
        this.m = m;
        this.bridge = bridge;
        this.folders = folders;
    }

    /** {@code tools/list}: the IDE tools and their arguments. */
    static ObjectNode list(ObjectMapper m) {
        ObjectNode r = m.createObjectNode();
        ArrayNode tools = r.putArray("tools");
        tool(m, tools, "openFile", "Open a file in the editor.", "filePath");
        tool(
                m,
                tools,
                "openDiff",
                "Show a proposed change of a file as a diff and wait for Accept or Reject.",
                "old_file_path",
                "new_file_path",
                "new_file_contents",
                "tab_name");
        tool(m, tools, "getCurrentSelection", "The active editor's selection.");
        tool(m, tools, "getLatestSelection", "The most recent selection.");
        tool(m, tools, "getOpenEditors", "The open editor tabs.");
        tool(m, tools, "getWorkspaceFolders", "The window's folders.");
        tool(m, tools, "getDiagnostics", "The problems the language servers report.");
        tool(m, tools, "checkDocumentDirty", "Whether an open file has unsaved changes.", "filePath");
        tool(m, tools, "saveDocument", "Save an open file.", "filePath");
        tool(m, tools, "close_tab", "Close a diff opened with openDiff.", "tab_name");
        tool(m, tools, "closeAllDiffTabs", "Close every diff opened with openDiff.");
        return r;
    }

    private static void tool(ObjectMapper m, ArrayNode tools, String name, String description, String... required) {
        ObjectNode t = tools.addObject();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode req = schema.putArray("required");
        for (String r : required) {
            props.putObject(r).put("type", "string");
            req.add(r);
        }
        if ("getDiagnostics".equals(name)) {
            props.putObject("uri").put("type", "string");
        }
    }

    ObjectNode call(String tool, JsonNode args) {
        return switch (tool) {
            case "openFile" -> openFile(args);
            case "openDiff" -> openDiff(args);
            case "getCurrentSelection", "getLatestSelection" -> selection();
            case "getOpenEditors" -> openEditors();
            case "getWorkspaceFolders" -> workspaceFolders();
            case "getDiagnostics" -> diagnostics(args.path("uri").asText(""));
            case "checkDocumentDirty" -> dirty(args.path("filePath").asText(""));
            case "saveDocument" -> save(args.path("filePath").asText(""));
            case "close_tab" -> text("TAB_CLOSED"); // a proposal's tab closes with its answer
            case "closeAllDiffTabs" -> text("CLOSED_0_DIFF_TABS");
            default -> error("Unknown tool: " + tool);
        };
    }

    private ObjectNode openFile(JsonNode args) {
        String path = args.path("filePath").asText("");
        if (path.isBlank()) {
            return error("filePath is required.");
        }
        String refused = bridge.openFile(path, 0, 0);
        return refused == null ? text("Opened file: " + path) : error(refused);
    }

    private ObjectNode openDiff(JsonNode args) {
        String path =
                args.path("new_file_path").asText(args.path("old_file_path").asText(""));
        String contents = args.path("new_file_contents").asText("");
        if (path.isBlank()) {
            return error("new_file_path is required.");
        }
        if (!isOpen(path)) {
            String refused = bridge.openFile(path, 0, 0); // a proposal is of an open buffer
            if (refused != null) {
                return error(refused);
            }
        }
        McpBridge.ProposalOutcome o = bridge.proposeEdit(
                path, null, contents, false, true, args.path("tab_name").asText(""), "Claude Code");
        if (!"accepted".equals(o.outcome())) {
            return text("DIFF_REJECTED");
        }
        String notSaved = bridge.saveBuffer(path); // Claude Code reads the file from disk after FILE_SAVED
        if (notSaved != null) {
            return error("Accepted, but not saved: " + notSaved);
        }
        ObjectNode r = text("FILE_SAVED");
        r.withArray("content").addObject().put("type", "text").put("text", contents);
        return r;
    }

    private boolean isOpen(String path) {
        return bridge.listOpenFiles().stream().anyMatch(f -> path.equals(f.path()));
    }

    private ObjectNode selection() {
        McpBridge.Selection s = bridge.getSelection();
        ObjectNode o = m.createObjectNode();
        if (s == null || s.path() == null) {
            o.put("success", false).put("message", "No active editor found");
            return json(o);
        }
        o.put("success", true);
        o.put("text", s.selectedText());
        o.put("filePath", s.path());
        ObjectNode sel = o.putObject("selection");
        sel.putObject("start").put("line", s.selStartLine() - 1).put("character", s.selStartCol() - 1);
        sel.putObject("end").put("line", s.selEndLine() - 1).put("character", s.selEndCol() - 1);
        sel.put("isEmpty", s.selectedText().isEmpty());
        return json(o);
    }

    private ObjectNode openEditors() {
        ObjectNode o = m.createObjectNode();
        ArrayNode tabs = o.putArray("tabs");
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            if (f.path() == null) {
                continue;
            }
            tabs.addObject()
                    .put("uri", Path.of(f.path()).toUri().toString())
                    .put("isActive", f.active())
                    .put("label", f.title())
                    .put("languageId", f.language())
                    .put("isDirty", f.dirty());
        }
        return json(o);
    }

    private ObjectNode workspaceFolders() {
        ObjectNode o = m.createObjectNode().put("success", true);
        ArrayNode list = o.putArray("folders");
        List<Path> all = folders.get();
        for (Path p : all) {
            list.addObject()
                    .put(
                            "name",
                            p.getFileName() == null
                                    ? p.toString()
                                    : p.getFileName().toString())
                    .put("uri", p.toUri().toString())
                    .put("path", p.toString());
        }
        o.put("rootPath", all.isEmpty() ? "" : all.get(0).toString());
        return json(o);
    }

    private ObjectNode diagnostics(String uri) {
        ArrayNode out = m.createArrayNode();
        String only = uri.isBlank()
                ? null
                : uri.startsWith("file:") ? Path.of(java.net.URI.create(uri)).toString() : uri;
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            if (f.path() == null || (only != null && !only.equals(f.path()))) {
                continue;
            }
            ObjectNode file =
                    out.addObject().put("uri", Path.of(f.path()).toUri().toString());
            ArrayNode list = file.putArray("diagnostics");
            for (McpBridge.Diagnostic d : bridge.getDiagnostics(f.path())) {
                ObjectNode e = list.addObject().put("message", d.message()).put("severity", d.severity());
                ObjectNode range = e.putObject("range");
                range.putObject("start").put("line", d.line() - 1).put("character", d.col() - 1);
                range.putObject("end").put("line", d.line() - 1).put("character", d.col() - 1);
                e.put("source", d.origin());
            }
        }
        return json(out);
    }

    private ObjectNode dirty(String path) {
        ObjectNode o = m.createObjectNode();
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            if (path.equals(f.path())) {
                o.put("success", true)
                        .put("filePath", path)
                        .put("isDirty", f.dirty())
                        .put("isUntitled", false);
                return json(o);
            }
        }
        o.put("success", false).put("message", "Document not open: " + path);
        return json(o);
    }

    private ObjectNode save(String path) {
        ObjectNode o = m.createObjectNode().put("filePath", path);
        if (!isOpen(path)) {
            return json(o.put("success", false).put("message", "Document not open: " + path));
        }
        String refused = bridge.saveBuffer(path);
        return json(
                refused == null
                        ? o.put("success", true).put("saved", true).put("message", "Document saved successfully")
                        : o.put("success", false).put("saved", false).put("message", refused));
    }

    private ObjectNode text(String text) {
        ObjectNode r = m.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        return r;
    }

    private ObjectNode json(JsonNode value) {
        return text(value.toString());
    }

    private ObjectNode error(String message) {
        ObjectNode r = text(message);
        r.put("isError", true);
        return r;
    }
}
