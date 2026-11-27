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

    /** What the server tells a client it is for (both eras). */
    static final String INSTRUCTIONS = "Editora is the user's text editor, running on this computer. These tools see"
            + " what the user has open (unsaved text included) and act through the editor: edit_buffer changes are"
            + " undoable and stay in the buffer until save_buffer writes them. Resources are the open files, their"
            + " diagnostics and the Git status.";

    // --- initialize (legacy) and server/discover (modern) ----------------------------------------

    /** The legacy handshake's answer: the revision agreed on from the one {@code requested}. */
    ObjectNode initializeResult(@org.jspecify.annotations.Nullable String requested) {
        ObjectNode r = m.createObjectNode();
        r.put("protocolVersion", McpProtocol.negotiateLegacy(requested));
        r.set("capabilities", capabilities());
        r.set("serverInfo", McpProtocol.serverInfo(m));
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    /** The modern {@code server/discover} result, before {@link McpProtocol#modernResult} completes it. */
    ObjectNode discoverResult() {
        ObjectNode r = m.createObjectNode();
        ArrayNode versions = r.putArray("supportedVersions");
        McpProtocol.SUPPORTED.forEach(versions::add);
        r.set("capabilities", capabilities());
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    /** Tools (a fixed list), and resources that can be listed, read and watched. */
    ObjectNode capabilities() {
        ObjectNode caps = m.createObjectNode();
        caps.putObject("tools");
        ObjectNode resources = caps.putObject("resources");
        resources.put("subscribe", true);
        resources.put("listChanged", true);
        return caps;
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
        ObjectNode at = obj();
        at.set("path", strProp("Absolute path of an open file; omit for the active buffer."));
        at.set("line", intProp("1-based line of the symbol."));
        at.set("column", intProp("1-based column on that line (any character of the symbol's name)."));
        tools.add(toolReq(
                "lsp_definition",
                "Where the symbol at a place of an open file is defined, from its language server: a list of"
                        + " {path, line, column} (1-based). A library symbol's path is a URI such as jdt://.",
                at,
                "line",
                "column"));
        tools.add(toolReq(
                "lsp_references",
                "Every place the symbol at a place of an open file is used, from its language server:"
                        + " {path, line, column} (1-based).",
                at.deepCopy(),
                "line",
                "column"));
        tools.add(toolReq(
                "lsp_hover",
                "The language server's hover for a place of an open file (its type, signature and docs), as"
                        + " Markdown; empty when it has none.",
                at.deepCopy(),
                "line",
                "column"));
        tools.add(toolReq(
                "workspace_symbols",
                "Symbols of the project matching a query (classes, functions…), from the language server of the"
                        + " active file: {name, container, kind, path, line}.",
                obj().set("query", strProp("Part of the name to look for.")),
                "query"));
        ObjectNode history = obj();
        history.set("path", strProp("Absolute path of an open file."));
        history.set(
                "revision", intProp("Optional: 0 for the newest revision, 1 for the one before… Omit to list them."));
        tools.add(toolReq(
                "local_history",
                "An open file's Local History, Editora's own snapshots taken on save and before risky changes:"
                        + " without 'revision' the list (newest first: index, time, reason, label, size); with"
                        + " 'revision' that revision's text.",
                history,
                "path"));
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
        ObjectNode listReviews = obj();
        listReviews.set("path", strProp("Absolute path of a file: only its threads. Omit for the whole repository."));
        listReviews.set("state", strProp("\"open\" (default) or \"all\" (resolved and dismissed too)."));
        tools.add(tool(
                "list_review_comments",
                "List the review threads of the window's Git repository: each thread's file, its lines where they are"
                        + " now (1-based; outdated when they changed since), its state, an agent finding's severity"
                        + " and category, and its comments. Open threads only unless state is \"all\".",
                listReviews));
        ObjectNode addReview = obj();
        addReview.set("path", strProp("Absolute path of a file of the window's repository and project."));
        addReview.set("start_line", intProp("First line the comment is on (1-based)."));
        addReview.set("end_line", intProp("Last line (1-based, inclusive); omit for start_line alone."));
        addReview.set("body", strProp("The comment, in Markdown."));
        addReview.set(
                "suggestion",
                strProp("Optional replacement text for those lines, which the user can apply with one click."));
        addReview.set("severity", strProp("For a finding: blocker, important, minor or nit."));
        addReview.set("category", strProp("For a finding: what kind, e.g. correctness, performance, security."));
        tools.add(toolReq(
                "add_review_comment",
                "Start a review thread on lines of a file, shown to the user after those lines in the editor and"
                        + " in the Review Comments window. Use it for review findings the user should read and act"
                        + " on; it changes no file. Returns the thread's id. A finding the user dismissed on the"
                        + " same lines is refused, with the user's reason: do not raise it again.",
                addReview,
                "path",
                "start_line",
                "body"));
        ObjectNode reply = obj();
        reply.set("thread_id", strProp("The thread's id, from list_review_comments or add_review_comment."));
        reply.set("body", strProp("The reply, in Markdown."));
        reply.set("suggestion", strProp("Optional replacement text for the thread's lines."));
        tools.add(toolReq(
                "reply_to_review_comment",
                "Reply to a review thread, e.g. to answer the user's comment. Replying reopens a resolved thread.",
                reply,
                "thread_id",
                "body"));
        ObjectNode resolve = obj();
        resolve.set("thread_id", strProp("The thread's id."));
        resolve.set("note", strProp("What resolved it, e.g. \"Fixed: the loop now stops at size - 1\"."));
        tools.add(toolReq(
                "resolve_review_comment",
                "Resolve an open review thread once what it asks is done, saying what was done. The note is kept"
                        + " with the thread for the user.",
                resolve,
                "thread_id",
                "note"));
        for (com.fasterxml.jackson.databind.JsonNode t : tools) {
            annotate((ObjectNode) t);
        }
        ObjectNode r = m.createObjectNode();
        r.set("tools", tools);
        return r;
    }

    /** How a tool behaves, for a client deciding what to ask the user first. */
    private enum Kind {
        /** Only reads. */
        READ,
        /** Changes what the editor shows, nothing in a file. */
        VIEW,
        /** Changes a buffer, undoably; nothing reaches the disk. */
        EDIT,
        /** Writes a file. */
        WRITE,
        /** Runs any command: may change anything, reach the network. */
        COMMAND
    }

    private void annotate(ObjectNode tool) {
        String name = tool.get("name").asText();
        record Info(String title, Kind kind) {}
        Info info = switch (name) {
            case "list_open_files" -> new Info("List Open Files", Kind.READ);
            case "read_buffer" -> new Info("Read Buffer", Kind.READ);
            case "get_diagnostics" -> new Info("Get Diagnostics", Kind.READ);
            case "find_in_files" -> new Info("Find in Files", Kind.READ);
            case "list_commands" -> new Info("List Commands", Kind.READ);
            case "execute_command" -> new Info("Run Command", Kind.COMMAND);
            case "open_file" -> new Info("Open File", Kind.VIEW);
            case "edit_buffer" -> new Info("Edit Buffer", Kind.EDIT);
            case "save_buffer" -> new Info("Save Buffer", Kind.WRITE);
            case "get_selection" -> new Info("Get Selection", Kind.READ);
            case "document_symbols" -> new Info("Document Symbols", Kind.READ);
            case "lsp_definition" -> new Info("Go to Definition", Kind.READ);
            case "lsp_references" -> new Info("Find References", Kind.READ);
            case "lsp_hover" -> new Info("Hover", Kind.READ);
            case "workspace_symbols" -> new Info("Workspace Symbols", Kind.READ);
            case "local_history" -> new Info("Local History", Kind.READ);
            case "git_status" -> new Info("Git Status", Kind.READ);
            case "list_tabs" -> new Info("List Tabs", Kind.READ);
            case "todo_scan" -> new Info("Scan TODOs", Kind.READ);
            case "list_review_comments" -> new Info("List Review Comments", Kind.READ);
            case "add_review_comment" -> new Info("Add Review Comment", Kind.EDIT);
            case "reply_to_review_comment" -> new Info("Reply to Review Comment", Kind.EDIT);
            case "resolve_review_comment" -> new Info("Resolve Review Comment", Kind.EDIT);
            default -> throw new IllegalStateException("No annotations for tool " + name);
        };
        tool.put("title", info.title());
        ObjectNode a = tool.putObject("annotations");
        a.put("title", info.title());
        a.put("readOnlyHint", info.kind() == Kind.READ);
        if (info.kind() != Kind.READ) {
            a.put("destructiveHint", info.kind() == Kind.WRITE || info.kind() == Kind.COMMAND);
            a.put("idempotentHint", info.kind() == Kind.VIEW || info.kind() == Kind.WRITE);
        }
        a.put("openWorldHint", info.kind() == Kind.COMMAND);
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
