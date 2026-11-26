package com.editora.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the MCP server says about itself without touching the editor: the {@code initialize} result and the
 * {@code tools/list} schemas. {@link McpTools} serves them from the window; {@link McpStdio} serves the same ones
 * when no Editora is running, so an agent started first still sees the tools (and each call says why it failed).
 */
final class McpToolCatalog {

    private final ObjectMapper m;

    McpToolCatalog(ObjectMapper m) {
        this.m = m;
    }

    // --- initialize -------------------------------------------------------------------------------

    ObjectNode initializeResult() {
        ObjectNode r = m.createObjectNode();
        r.put("protocolVersion", McpTools.PROTOCOL_VERSION);
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
                "Run a registered command by id (edits go through the undo stack). See list_commands."
                        + " The result says only that the command was run, not what it achieved: to save a"
                        + " buffer and learn whether it reached the disk, use save_buffer.",
                obj().set("id", strProp("The command id, e.g. \"file.save\".")),
                "id"));
        ObjectNode openProps = obj();
        openProps.set(
                "path",
                strProp("Absolute path of the file to open: inside the window's project folder, or already"
                        + " open in the editor."));
        openProps.set("line", intProp("Optional 1-based line to move the caret to."));
        openProps.set("col", intProp("Optional 1-based column (used only with 'line')."));
        tools.add(toolReq(
                "open_file",
                "Open a file of the window's project in the editor (or select one that is already open),"
                        + " optionally moving the caret to a line/column. A file outside the project folder is"
                        + " refused; the user has to open it.",
                openProps,
                "path"));
        ObjectNode editProps = obj();
        editProps.set("path", strProp("Absolute path of an open file; omit for the active buffer."));
        editProps.set(
                "old_text",
                strProp("Exact text to replace. It must occur exactly once in the buffer's whole text unless"
                        + " replace_all is true. Required, and not empty, unless replace_whole_buffer is true."));
        editProps.set("new_text", strProp("Replacement text (\"\" deletes old_text)."));
        editProps.set("replace_all", boolProp("Replace every occurrence of old_text (default false)."));
        editProps.set(
                "replace_whole_buffer",
                boolProp("Set true to replace the buffer's entire text with new_text; old_text and replace_all"
                        + " must then be omitted. Refused when the buffer changed since your last read_buffer of"
                        + " it (read it again and retry). Default false."));
        tools.add(toolReq(
                "edit_buffer",
                "Edit an open buffer through the editor's undo history; nothing is written to disk until"
                        + " save_buffer. Replaces old_text with new_text. To replace the buffer's entire text,"
                        + " pass replace_whole_buffer: true instead of old_text — an edit without old_text is"
                        + " rejected, never widened to the whole buffer. Omit 'path' for the active buffer.",
                editProps,
                "new_text"));
        tools.add(tool(
                "save_buffer",
                "Save an open buffer to its file on disk and wait for the write: the result is 'saved' only"
                        + " when the bytes are on disk, and an error when the save was refused, failed or is"
                        + " still pending. Omit 'path' for the active buffer.",
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
}
