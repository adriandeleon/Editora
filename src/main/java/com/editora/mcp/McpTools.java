package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * The MCP tool set: schemas for {@code tools/list} and dispatch for {@code tools/call}. Bridge result
 * records are mapped to JSON <em>by hand</em> (never via Jackson reflection on {@code mcp} types), so
 * the package needs no {@code module-info opens}. Each tool returns the standard MCP result shape
 * {@code {"content":[{"type":"text","text":<json>}],"isError":<bool>}}.
 */
final class McpTools {

    private final McpBridge bridge;
    private final ObjectMapper m;

    /**
     * Each tool's published {@code inputSchema.properties}, by tool name: {@link #callTool} checks the
     * arguments it is given against the very schema {@code tools/list} advertises, so the two cannot drift.
     */
    private final java.util.Map<String, JsonNode> argumentSchemas = new java.util.HashMap<>();

    McpTools(McpBridge bridge, ObjectMapper m) {
        this.bridge = bridge;
        this.m = m;
        for (JsonNode tool : listToolsResult().get("tools")) {
            argumentSchemas.put(
                    tool.get("name").asText(), tool.get("inputSchema").get("properties"));
        }
    }

    // --- initialize + tools/list (the catalog: no bridge needed) --------------------------------

    ObjectNode initializeResult(@Nullable String requested) {
        return new McpToolCatalog(m).initializeResult(requested);
    }

    ObjectNode discoverResult() {
        return new McpToolCatalog(m).discoverResult();
    }

    ObjectNode listToolsResult() {
        return new McpToolCatalog(m).listToolsResult();
    }

    // --- resources ------------------------------------------------------------------------------

    /** The diagnostics of every open file, as one JSON resource. */
    static final String DIAGNOSTICS_URI = "editora://diagnostics";

    /** The window's Git status, as one JSON resource. */
    static final String GIT_STATUS_URI = "editora://git/status";

    /** A resource that is not there, or not one this client may read; the dispatcher picks the era's code. */
    static final class ResourceNotFound extends RuntimeException {
        ResourceNotFound(String message) {
            super(message);
        }
    }

    /** Each open file's change stamp, by path (see {@link McpBridge#bufferStamps}). */
    java.util.Map<String, Long> bufferStamps() {
        return bridge.bufferStamps();
    }

    /** {@code resources/list}: each open file that has a path (its live text), the diagnostics, the Git status. */
    ObjectNode listResourcesResult() {
        ArrayNode list = m.createArrayNode();
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            if (f.path() == null) {
                continue; // an untitled buffer has no URI; read_buffer still reaches it
            }
            ObjectNode r = list.addObject();
            r.put("uri", fileUri(f.path()));
            r.put("name", f.title());
            r.put("title", f.title());
            r.put("description", "The live text of this open file" + (f.dirty() ? ", with unsaved changes" : ""));
            r.put("mimeType", mimeType(f.language()));
        }
        ObjectNode d = list.addObject();
        d.put("uri", DIAGNOSTICS_URI);
        d.put("name", "diagnostics");
        d.put("title", "Diagnostics");
        d.put("description", "Language-server diagnostics of every open file, by path.");
        d.put("mimeType", "application/json");
        ObjectNode rv = list.addObject();
        rv.put("uri", REVIEWS_URI);
        rv.put("name", "reviews");
        rv.put("title", "Review Comments");
        rv.put(
                "description",
                "The open review threads of the window's repository, as list_review_comments lists them.");
        rv.put("mimeType", "application/json");
        ObjectNode g = list.addObject();
        g.put("uri", GIT_STATUS_URI);
        g.put("name", "git-status");
        g.put("title", "Git Status");
        g.put("description", "Branch, upstream, ahead/behind and changed files of the window's repository.");
        g.put("mimeType", "application/json");
        ObjectNode r = m.createObjectNode();
        r.set("resources", list);
        return r;
    }

    /** {@code resources/read}: an open file's live text, or one of the JSON resources. */
    ObjectNode readResourceResult(@Nullable String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new ResourceNotFound("Missing resource uri.");
        }
        String mimeType;
        String text;
        if (uri.equals(DIAGNOSTICS_URI)) {
            mimeType = "application/json";
            text = pretty(allDiagnostics());
        } else if (uri.equals(GIT_STATUS_URI)) {
            mimeType = "application/json";
            text = contentText(gitStatusResult());
        } else if (uri.equals(REVIEWS_URI)) {
            mimeType = "application/json";
            text = reviewsText();
        } else {
            String path = filePath(uri);
            if (path == null) {
                throw new ResourceNotFound("Unknown resource: " + uri);
            }
            McpBridge.BufferContent b = bridge.readBuffer(path);
            if (b == null) {
                throw new ResourceNotFound("Not an open file: " + uri + ". Only files open in Editora are resources.");
            }
            mimeType = mimeType(b.language());
            text = b.text();
        }
        ObjectNode c = m.createObjectNode();
        c.put("uri", uri);
        c.put("mimeType", mimeType);
        c.put("text", text);
        ObjectNode r = m.createObjectNode();
        r.putArray("contents").add(c);
        return r;
    }

    private ObjectNode allDiagnostics() {
        ObjectNode byPath = m.createObjectNode();
        for (McpBridge.OpenFile f : bridge.listOpenFiles()) {
            if (f.path() != null) {
                ArrayNode arr = byPath.putArray(f.path());
                for (McpBridge.Diagnostic d : bridge.getDiagnostics(f.path())) {
                    ObjectNode o = arr.addObject();
                    o.put("line", d.line());
                    o.put("col", d.col());
                    o.put("severity", d.severity());
                    o.put("message", d.message());
                    o.put("origin", d.origin());
                }
            }
        }
        return byPath;
    }

    /** A digest of what {@code uri} reads now, cheap for an open file (its change stamp, not its text). */
    @Nullable
    String resourceStamp(String uri, java.util.Map<String, Long> bufferStamps) {
        if (uri.equals(DIAGNOSTICS_URI)) {
            return pretty(allDiagnostics());
        }
        if (uri.equals(GIT_STATUS_URI)) {
            return contentText(gitStatusResult());
        }
        if (uri.equals(REVIEWS_URI)) {
            return reviewsText();
        }
        // By URI, as resourceUris names them: turning the URI back into a path can spell it otherwise than the
        // window does (on Windows a "/tmp/a" key comes back as "C:\\tmp\\a"), and the file would never update.
        for (java.util.Map.Entry<String, Long> e : bufferStamps.entrySet()) {
            if (fileUri(e.getKey()).equals(uri)) {
                return Long.toString(e.getValue());
            }
        }
        return null;
    }

    /** The open files' URIs, as {@code resources/list} lists them (for telling when the list changed). */
    java.util.Set<String> resourceUris(java.util.Map<String, Long> bufferStamps) {
        java.util.Set<String> uris = new java.util.TreeSet<>();
        bufferStamps.keySet().forEach(p -> uris.add(fileUri(p)));
        uris.add(DIAGNOSTICS_URI);
        uris.add(REVIEWS_URI);
        uris.add(GIT_STATUS_URI);
        return uris;
    }

    static String fileUri(String path) {
        return java.nio.file.Path.of(path).toUri().toString();
    }

    /** The absolute path a {@code file:} URI names, or null for any other URI. */
    static @Nullable String filePath(String uri) {
        try {
            java.net.URI u = java.net.URI.create(uri);
            if (!"file".equalsIgnoreCase(u.getScheme())) {
                return null;
            }
            return java.nio.file.Path.of(u).toString();
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    static String mimeType(String language) {
        return switch (language) {
            case "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "html" -> "text/html";
            case "xml" -> "application/xml";
            case "css" -> "text/css";
            case "javascript" -> "text/javascript";
            case "yaml" -> "application/yaml";
            default -> "text/plain";
        };
    }

    private String pretty(JsonNode node) {
        try {
            return m.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /** The window's open review threads as JSON; an empty list when it has no repository. */
    private String reviewsText() {
        try {
            return pretty(reviewsJson(bridge.listReviews(null, false)));
        } catch (IllegalStateException e) {
            return "[]";
        }
    }

    private static String contentText(ObjectNode toolResult) {
        return toolResult.path("content").path(0).path("text").asText();
    }

    // --- prompts ----------------------------------------------------------------------------------

    /** A prompt that cannot be given (unknown, or nothing to build it from); answered as invalid params. */
    static final class PromptUnavailable extends RuntimeException {
        PromptUnavailable(String message) {
            super(message);
        }
    }

    ObjectNode promptsListResult() {
        return new McpToolCatalog(m).promptsListResult();
    }

    /** {@code prompts/get}: the prompt's one user message, built from the window as it is now. */
    ObjectNode getPromptResult(@Nullable JsonNode params) {
        String name = text(params, "name");
        JsonNode args = params == null ? null : params.get("arguments");
        String text = switch (name == null ? "" : name) {
            case "review_changes" -> reviewChangesPrompt(text(args, "focus"));
            case "fix_open_comments" -> fixOpenCommentsPrompt(text(args, "path"));
            case "explain_selection" -> explainSelectionPrompt();
            default -> throw new PromptUnavailable("Unknown prompt: " + name);
        };
        ObjectNode r = m.createObjectNode();
        for (JsonNode p : promptsListResult().get("prompts")) {
            if (p.get("name").asText().equals(name)) {
                r.put("description", p.get("description").asText());
            }
        }
        ObjectNode message = r.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return r;
    }

    private String reviewChangesPrompt(@Nullable String focus) {
        McpBridge.GitState g = bridge.gitStatus();
        if (!g.repo()) {
            throw new PromptUnavailable("The window has no Git repository to review.");
        }
        StringBuilder p = new StringBuilder("Review the uncommitted changes in the Git repository at ")
                .append(g.root())
                .append(g.branch() == null ? "" : " (branch " + g.branch() + ")")
                .append(".\n\n");
        if (g.files().isEmpty()) {
            p.append("There are no uncommitted changes: say so, and review nothing.\n");
            return p.toString();
        }
        p.append("Changed files (index, worktree):\n");
        for (McpBridge.GitFileState f : g.files()) {
            p.append("- ").append(f.index()).append(f.worktree()).append(' ').append(f.path());
            if (f.origPath() != null) {
                p.append(" (from ").append(f.origPath()).append(')');
            }
            p.append('\n');
        }
        p.append("\nRead each change with `git diff` (and `git diff --cached` for the staged ones). First call")
                .append(" list_review_comments with state \"all\": do not raise again what the user dismissed.")
                .append(" For each problem you find, call add_review_comment on its lines of the file as it is now,")
                .append(" with a severity (blocker, important, minor or nit), a category, and a suggestion when you")
                .append(" can write the fix. Do not edit any file. End with a short summary of what you found.");
        if (focus != null && !focus.isBlank()) {
            p.append("\n\nLook most at: ").append(focus.strip()).append('.');
        }
        return p.toString();
    }

    private String fixOpenCommentsPrompt(@Nullable String path) {
        List<McpBridge.ReviewThreadInfo> threads;
        try {
            threads = bridge.listReviews(path, false);
        } catch (IllegalStateException e) {
            throw new PromptUnavailable(String.valueOf(e.getMessage()));
        }
        if (threads.isEmpty()) {
            return "There are no open review comments" + (path == null ? "" : " on " + path)
                    + ". Say so, and change nothing.";
        }
        StringBuilder p = new StringBuilder("Do what each of these open review comments asks.\n");
        for (McpBridge.ReviewThreadInfo t : threads) {
            p.append("\n## Thread ").append(t.id()).append(": ").append(t.path());
            p.append(
                    t.startLine() == t.endLine()
                            ? ", line " + t.startLine()
                            : ", lines " + t.startLine() + "-" + t.endLine());
            if (t.outdated()) {
                p.append(" (outdated: those lines changed since)");
            }
            p.append('\n');
            if (t.severity() != null) {
                p.append("Severity: ").append(t.severity());
                if (t.category() != null) {
                    p.append(" (").append(t.category()).append(')');
                }
                p.append('\n');
            }
            for (McpBridge.ReviewCommentInfo c : t.comments()) {
                p.append(c.authorKind().equals("user") ? "The user" : c.author())
                        .append(": ")
                        .append(c.body().strip())
                        .append('\n');
                if (c.suggestion() != null) {
                    p.append("Suggested replacement:\n```\n")
                            .append(c.suggestion())
                            .append("\n```\n");
                }
            }
        }
        p.append("\nFor each thread, change the code to do what it asks, then call resolve_review_comment with its")
                .append(" thread_id and a note saying what you changed. If you disagree with one, change nothing for")
                .append(" it and say why with reply_to_review_comment instead.");
        return p.toString();
    }

    private String explainSelectionPrompt() {
        McpBridge.Selection sel = bridge.getSelection();
        if (sel == null) {
            throw new PromptUnavailable("No file is open in the editor.");
        }
        String code = sel.selectedText();
        int from = sel.selStartLine();
        int to = sel.selEndLine();
        if (code.isEmpty()) {
            McpBridge.BufferContent b = bridge.readBuffer(sel.path());
            String[] lines = b == null ? new String[0] : b.text().split("\n", -1);
            int i = sel.caretLine() - 1;
            code = i >= 0 && i < lines.length ? lines[i] : "";
            from = sel.caretLine();
            to = sel.caretLine();
        }
        String where = sel.path() == null ? sel.title() : sel.path();
        return "Explain this code from " + where + (from == to ? ", line " + from : ", lines " + from + "-" + to)
                + ":\n\n```\n" + code + "\n```\n\nSay what it does, why it may be written this way, and"
                + " anything in it that could surprise a reader. Change nothing.";
    }

    // --- review comments --------------------------------------------------------------------------

    /** The reviews' JSON resource. */
    static final String REVIEWS_URI = "editora://reviews";

    /** A review tool's result, or a tool error with the reason the bridge gave (no repository, a refused file). */
    private ObjectNode reviewTool(java.util.function.Supplier<ObjectNode> call) {
        try {
            return call.get();
        } catch (IllegalStateException e) {
            return errorResult(String.valueOf(e.getMessage()));
        }
    }

    /** A required argument's text; a missing or blank one is a tool error (thrown to {@link #reviewTool}). */
    private static String req(@Nullable JsonNode args, String field) {
        String v = text(args, field);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(field + " is required.");
        }
        return v;
    }

    private ObjectNode done(@Nullable String error) {
        return error == null ? textResult(m.createObjectNode().put("done", true)) : errorResult(error);
    }

    /**
     * Who is calling, for the comments it writes: the client's name a modern request carries in {@code _meta}
     * ({@code io.modelcontextprotocol/clientInfo}), else "Agent".
     */
    static String agentName(@Nullable JsonNode params) {
        JsonNode info = params == null ? null : params.path("_meta").path("io.modelcontextprotocol/clientInfo");
        String name =
                info == null ? "" : info.path("title").asText(info.path("name").asText(""));
        name = name.strip();
        return name.isEmpty() ? "Agent" : name.length() > 64 ? name.substring(0, 64) : name;
    }

    private ObjectNode reviewsResult(@Nullable JsonNode args) {
        boolean all = "all".equalsIgnoreCase(String.valueOf(text(args, "state")));
        return textResult(reviewsJson(bridge.listReviews(text(args, "path"), all)));
    }

    private ArrayNode reviewsJson(List<McpBridge.ReviewThreadInfo> threads) {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.ReviewThreadInfo t : threads) {
            ObjectNode o = arr.addObject();
            o.put("id", t.id());
            o.put("path", t.path());
            o.put("start_line", t.startLine());
            o.put("end_line", t.endLine());
            o.put("outdated", t.outdated());
            o.put("state", t.state());
            if (t.stateNote() != null) {
                o.put("state_note", t.stateNote());
            }
            if (t.severity() != null) {
                o.put("severity", t.severity());
            }
            if (t.category() != null) {
                o.put("category", t.category());
            }
            ArrayNode comments = o.putArray("comments");
            for (McpBridge.ReviewCommentInfo c : t.comments()) {
                ObjectNode co = comments.addObject();
                co.put("id", c.id());
                co.put("author_kind", c.authorKind());
                co.put("author", c.authorKind().equals("user") ? "user" : c.author());
                co.put("body", c.body());
                if (c.suggestion() != null) {
                    co.put("suggestion", c.suggestion());
                }
                co.put("created", java.time.Instant.ofEpochMilli(c.createdAt()).toString());
            }
        }
        return arr;
    }

    private ObjectNode addReviewResult(@Nullable JsonNode args, String agent) {
        McpBridge.ReviewAdded added = bridge.addReview(
                req(args, "path"),
                intArg(args, "start_line"),
                intArg(args, "end_line"),
                req(args, "body"),
                text(args, "suggestion"),
                text(args, "severity"),
                text(args, "category"),
                agent);
        if (added.id() == null) {
            return errorResult(added.error() == null ? "Not added." : added.error());
        }
        ObjectNode o = m.createObjectNode();
        o.put("id", added.id());
        o.put("start_line", added.startLine());
        o.put("end_line", added.endLine());
        return textResult(o);
    }

    // --- tools/call -------------------------------------------------------------------------------

    /** Dispatches {@code tools/call} params {@code {name, arguments}} → an MCP tool result node. */
    ObjectNode callTool(@Nullable JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            return errorResult("Missing tool name.");
        }
        String name = params.get("name").asText();
        JsonNode args = params.get("arguments");
        String invalid = argumentError(name, args);
        if (invalid != null) {
            return errorResult(invalid);
        }
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
            case "lsp_definition" ->
                reviewTool(() -> locations(
                        bridge.navigate(text(args, "path"), place(args, "line"), place(args, "column"), false)));
            case "lsp_references" ->
                reviewTool(() -> locations(
                        bridge.navigate(text(args, "path"), place(args, "line"), place(args, "column"), true)));
            case "lsp_hover" ->
                reviewTool(() -> textResult(m.createObjectNode()
                        .put("hover", bridge.hover(text(args, "path"), place(args, "line"), place(args, "column")))));
            case "workspace_symbols" -> reviewTool(() -> workspaceSymbolsResult(req(args, "query")));
            case "local_history" -> reviewTool(() -> localHistoryResult(args));
            case "git_status" -> gitStatusResult();
            case "list_tabs" -> tabsResult();
            case "todo_scan" -> todoScanResult();
            case "list_review_comments" -> reviewTool(() -> reviewsResult(args));
            case "add_review_comment" -> reviewTool(() -> addReviewResult(args, agentName(params)));
            case "reply_to_review_comment" ->
                reviewTool(() -> done(bridge.replyReview(
                        req(args, "thread_id"), req(args, "body"), text(args, "suggestion"), agentName(params))));
            case "resolve_review_comment" ->
                reviewTool(
                        () -> done(bridge.resolveReview(req(args, "thread_id"), req(args, "note"), agentName(params))));
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

    private ObjectNode readBufferResult(@Nullable String path) {
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

    private ObjectNode diagnosticsResult(@Nullable String path) {
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

    private ObjectNode executeResult(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return errorResult("execute_command requires an 'id'.");
        }
        String refusal = bridge.commandRefusal(id);
        if (refusal != null) {
            return errorResult(refusal);
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
        String error = bridge.openFile(path, intArg(args, "line"), intArg(args, "col"));
        return error == null
                ? textResult(m.createObjectNode().put("opened", true).put("path", path))
                : errorResult(error);
    }

    private ObjectNode editBufferResult(JsonNode args) {
        String newText = args != null && args.hasNonNull("new_text")
                ? args.get("new_text").asText()
                : null;
        if (newText == null) {
            return errorResult("edit_buffer requires 'new_text'.");
        }
        String path = text(args, "path");
        String oldText = text(args, "old_text"); // null when absent, JSON null, or ""
        boolean replaceAll = bool(args, "replace_all");
        String error;
        if (bool(args, "replace_whole_buffer")) {
            if (oldText != null || replaceAll) {
                return errorResult("edit_buffer: 'replace_whole_buffer' replaces the buffer's entire text, so it"
                        + " cannot be combined with 'old_text' or 'replace_all'. Pass either old_text (a targeted"
                        + " edit) or replace_whole_buffer: true, not both.");
            }
            error = bridge.replaceBuffer(path, newText);
        } else if (oldText == null) {
            // An absent, misspelled or empty old_text used to mean "replace the whole buffer".
            return errorResult("edit_buffer requires a non-empty 'old_text': the exact text to replace. To replace"
                    + " the buffer's entire text instead, pass \"replace_whole_buffer\": true (and no old_text).");
        } else {
            error = bridge.editBuffer(path, oldText, newText, replaceAll);
        }
        return error == null ? textResult(m.createObjectNode().put("applied", true)) : errorResult(error);
    }

    private ObjectNode saveBufferResult(@Nullable String path) {
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

    private ObjectNode locations(List<McpBridge.Location> found) {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.Location l : found) {
            arr.add(m.createObjectNode()
                    .put("path", l.path())
                    .put("line", l.line())
                    .put("column", l.column()));
        }
        return textResult(arr);
    }

    private ObjectNode workspaceSymbolsResult(String query) {
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.WorkspaceSymbol s : bridge.workspaceSymbols(query)) {
            ObjectNode o = m.createObjectNode().put("name", s.name());
            if (!s.container().isEmpty()) {
                o.put("container", s.container());
            }
            o.put("kind", s.kind()).put("path", s.path()).put("line", s.line());
            arr.add(o);
        }
        return textResult(arr);
    }

    private ObjectNode localHistoryResult(@Nullable JsonNode args) {
        String path = req(args, "path");
        if (args != null && args.hasNonNull("revision")) {
            return textResult(m.createObjectNode()
                    .put("revision", args.get("revision").asInt())
                    .put(
                            "text",
                            bridge.localHistoryText(path, args.get("revision").asInt())));
        }
        ArrayNode arr = m.createArrayNode();
        for (McpBridge.HistoryEntry e : bridge.localHistory(path)) {
            ObjectNode o = m.createObjectNode()
                    .put("index", e.index())
                    .put("time", java.time.Instant.ofEpochMilli(e.epochMillis()).toString())
                    .put("reason", e.reason());
            if (!e.label().isBlank()) {
                o.put("label", e.label());
            }
            arr.add(o.put("size", e.size()));
        }
        return textResult(arr);
    }

    private ObjectNode symbolsResult(@Nullable String path) {

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

    // --- argument checking ------------------------------------------------------------------------

    /**
     * Why {@code args} cannot be used to call {@code tool}, or {@code null} when they can.
     *
     * <p>Several tools take an optional argument whose absence <em>widens</em> what they act on: no
     * {@code path} means the active buffer; no {@code old_text} once meant the whole buffer. An argument that
     * is misspelled ({@code oldText}, {@code file}), empty or of the wrong type was silently treated as
     * absent, so a slip in the request turned a narrow edit into a broad one — and a following
     * {@code save_buffer} wrote it. Anything the published schema does not describe is therefore rejected by
     * name, and a {@code path} that is given must name something.
     */
    private @Nullable String argumentError(String tool, JsonNode args) {
        JsonNode schema = argumentSchemas.get(tool);
        if (schema == null || args == null || args.isNull()) {
            return null; // unknown tool: reported by the dispatcher; no arguments: nothing to check
        }
        if (!args.isObject()) {
            return tool + ": 'arguments' must be a JSON object.";
        }
        for (var fields = args.fields(); fields.hasNext(); ) {
            var field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            JsonNode declared = schema.get(name);
            if (declared == null) {
                return tool + ": unknown argument '" + name + "'." + suggestion(schema, name) + " Accepted: "
                        + accepted(schema) + ".";
            }
            if (value == null || value.isNull()) {
                continue; // an explicit null is "not given"
            }
            String type = declared.path("type").asText();
            boolean typed = switch (type) {
                case "string" -> value.isTextual();
                case "boolean" -> value.isBoolean();
                case "integer" -> value.isIntegralNumber();
                default -> true;
            };
            if (!typed) {
                return tool + ": argument '" + name + "' must be a JSON " + type + ".";
            }
            if ("path".equals(name)) {
                String problem = pathProblem(value.asText());
                if (problem != null) {
                    return tool + ": " + problem;
                }
            }
        }
        return null;
    }

    /** Why {@code path} does not name a file, or {@code null}. Pure. */
    static @Nullable String pathProblem(String path) {
        if (path.isBlank()) {
            return "'path' is empty. Pass the absolute path of the file, or leave 'path' out altogether to mean"
                    + " the active buffer.";
        }
        boolean rooted;
        try {
            rooted = java.nio.file.Path.of(path).getRoot() != null;
        } catch (RuntimeException e) {
            rooted = false;
        }
        // A relative path would be resolved against the editor's own working directory, not the project.
        return rooted ? null : "'path' must be an absolute path, got '" + path + "'.";
    }

    private static String accepted(JsonNode schema) {
        java.util.List<String> names = new java.util.ArrayList<>();
        schema.fieldNames().forEachRemaining(names::add);
        return names.isEmpty() ? "no arguments" : String.join(", ", names);
    }

    /** " Did you mean 'old_text'?" when {@code name} is a declared argument in another spelling. */
    private static String suggestion(JsonNode schema, String name) {
        String wanted = squash(name);
        for (var names = schema.fieldNames(); names.hasNext(); ) {
            String candidate = names.next();
            if (squash(candidate).equals(wanted)) {
                return " Did you mean '" + candidate + "'?";
            }
        }
        return "";
    }

    private static String squash(String name) {
        return name.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
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

    private static @Nullable String text(@Nullable JsonNode args, String field) {

        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String v = args.get(field).asText();
        return v.isEmpty() ? null : v;
    }

    private static boolean bool(@Nullable JsonNode args, String field) {
        return args != null && args.hasNonNull(field) && args.get(field).asBoolean(false);
    }

    /** A required 1-based line or column; anything below 1 is a tool error. */
    private static int place(@Nullable JsonNode args, String field) {
        int v = intArg(args, field);
        if (v < 1) {
            throw new IllegalStateException(field + " is 1-based: it must be 1 or more.");
        }
        return v;
    }

    /** Reads an optional integer argument; 0 when absent (callers treat {@code <= 0} as "not given"). */
    private static int intArg(@Nullable JsonNode args, String field) {
        return args != null && args.hasNonNull(field) ? args.get(field).asInt(0) : 0;
    }
}
