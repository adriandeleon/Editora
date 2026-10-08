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

/**
 * Direct {@link McpTools} tests (no HTTP): tool schemas, argument validation, dispatch into the
 * bridge, and the success/error result mapping for the write/nav/inspection tools.
 */
class McpToolsTest {

    /** A configurable bridge so each test can set what the "editor" returns. */
    static final class StubBridge implements McpBridge {
        boolean openFileResult = true;
        String editError;
        String saveError;
        Selection selection;
        List<Symbol> symbols = List.of();
        GitState gitState = new GitState(false, null, null, null, 0, 0, List.of());
        List<TabInfo> tabs = List.of();
        List<TodoItem> todos = List.of();

        String openedPath;
        int openedLine;
        int openedCol;
        String editedPath;
        String editedOld;
        String editedNew;
        boolean editedAll;
        String savedPath;
        String replacedPath;
        String replacedNew;
        int replacements;
        int edits;
        int saves;
        String readPath;
        int reads;

        @Override
        public List<OpenFile> listOpenFiles() {
            return List.of();
        }

        @Override
        public BufferContent readBuffer(String path) {
            readPath = path;
            reads++;
            return null;
        }

        @Override
        public List<Diagnostic> getDiagnostics(String path) {
            return List.of();
        }

        @Override
        public List<SearchMatch> findInFiles(String q, boolean cs, boolean rx, boolean ww) {
            return List.of();
        }

        @Override
        public List<CommandInfo> listCommands() {
            return List.of();
        }

        @Override
        public boolean executeCommand(String id) {
            return false;
        }

        @Override
        public String openFile(String path, int line, int col) {
            openedPath = path;
            openedLine = line;
            openedCol = col;
            return openFileResult ? null : "No such file: " + path;
        }

        @Override
        public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
            editedPath = path;
            editedOld = oldText;
            editedNew = newText;
            editedAll = replaceAll;
            edits++;
            return editError;
        }

        @Override
        public String replaceBuffer(String path, String newText) {
            replacedPath = path;
            replacedNew = newText;
            replacements++;
            return editError;
        }

        @Override
        public String saveBuffer(String path) {
            saves++;
            savedPath = path;
            return saveError;
        }

        @Override
        public Selection getSelection() {
            return selection;
        }

        @Override
        public List<Symbol> documentSymbols(String path) {
            return symbols;
        }

        @Override
        public GitState gitStatus() {
            return gitState;
        }

        @Override
        public List<TabInfo> listTabs() {
            return tabs;
        }

        @Override
        public List<TodoItem> todoScan() {
            return todos;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubBridge bridge = new StubBridge();
    private final McpTools tools = new McpTools(bridge, mapper);

    // --- helpers ------------------------------------------------------------------------------

    private ObjectNode call(String name, String argsJson) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        if (argsJson != null) {
            params.set("arguments", mapper.readTree(argsJson));
        }
        return tools.callTool(params);
    }

    /** Parses the JSON payload embedded in the single MCP text content block. */
    private JsonNode payload(ObjectNode result) throws Exception {
        return mapper.readTree(result.get("content").get(0).get("text").asText());
    }

    private static boolean isError(ObjectNode result) {
        return result.get("isError").asBoolean();
    }

    // --- tools/list ---------------------------------------------------------------------------

    @Test
    void listsAllTools() {
        JsonNode list = tools.listToolsResult().get("tools");
        List<String> names = list.findValuesAsText("name");
        for (String expected : List.of(
                "list_open_files",
                "read_buffer",
                "get_diagnostics",
                "find_in_files",
                "list_commands",
                "execute_command",
                "open_file",
                "edit_buffer",
                "save_buffer",
                "get_selection",
                "document_symbols",
                "git_status",
                "list_tabs",
                "todo_scan")) {
            assertTrue(names.contains(expected), "missing tool: " + expected);
        }
    }

    @Test
    void listTabsIncludesNonEditorTabs() throws Exception {
        bridge.tabs = List.of(
                new McpBridge.TabInfo("editor", "Main.java", "/p/Main.java", true),
                new McpBridge.TabInfo("welcome", "Welcome", null, false));
        JsonNode arr = payload(call("list_tabs", null));
        assertEquals(2, arr.size());
        assertEquals("editor", arr.get(0).get("type").asText());
        assertTrue(arr.get(0).get("active").asBoolean());
        assertEquals("welcome", arr.get(1).get("type").asText());
        assertTrue(arr.get(1).get("path").isNull());
    }

    @Test
    void todoScanReportsMatches() throws Exception {
        bridge.todos = List.of(new McpBridge.TodoItem("/p/a.java", 12, 5, "TODO", "auth", "!", "// TODO(auth)!: fix"));
        JsonNode arr = payload(call("todo_scan", null));
        assertEquals(1, arr.size());
        assertEquals("/p/a.java", arr.get(0).get("file").asText());
        assertEquals(12, arr.get(0).get("line").asInt());
        assertEquals("TODO", arr.get(0).get("keyword").asText());
        assertEquals("auth", arr.get(0).get("tag").asText());
    }

    @Test
    void editBufferSchemaRequiresNewText() {
        JsonNode list = tools.listToolsResult().get("tools");
        for (JsonNode t : list) {
            if ("edit_buffer".equals(t.get("name").asText())) {
                assertEquals(
                        "new_text", t.get("inputSchema").get("required").get(0).asText());
                return;
            }
        }
        throw new AssertionError("edit_buffer not listed");
    }

    // --- open_file ----------------------------------------------------------------------------

    @Test
    void openFileRequiresPath() throws Exception {
        assertTrue(isError(call("open_file", "{}")));
    }

    @Test
    void openFilePassesLineAndCol() throws Exception {
        ObjectNode r = call("open_file", "{\"path\":\"/tmp/x.txt\",\"line\":12,\"col\":3}");
        assertFalse(isError(r));
        assertEquals("/tmp/x.txt", bridge.openedPath);
        assertEquals(12, bridge.openedLine);
        assertEquals(3, bridge.openedCol);
        assertTrue(payload(r).get("opened").asBoolean());
    }

    @Test
    void openFileMissingFileIsError() throws Exception {
        bridge.openFileResult = false;
        assertTrue(isError(call("open_file", "{\"path\":\"/nope\"}")));
    }

    // --- edit_buffer --------------------------------------------------------------------------

    @Test
    void editBufferRequiresNewText() throws Exception {
        assertTrue(isError(call("edit_buffer", "{\"old_text\":\"a\"}")));
    }

    @Test
    void editBufferPassesArguments() throws Exception {
        ObjectNode r = call(
                "edit_buffer",
                "{\"path\":\"/tmp/a.java\",\"old_text\":\"foo\",\"new_text\":\"bar\",\"replace_all\":true}");
        assertFalse(isError(r));
        assertEquals("/tmp/a.java", bridge.editedPath);
        assertEquals("foo", bridge.editedOld);
        assertEquals("bar", bridge.editedNew);
        assertTrue(bridge.editedAll);
        assertTrue(payload(r).get("applied").asBoolean());
    }

    @Test
    void editBufferAllowsEmptyNewTextForDeletion() throws Exception {
        ObjectNode r = call("edit_buffer", "{\"old_text\":\"gone\",\"new_text\":\"\"}");
        assertFalse(isError(r));
        assertEquals("", bridge.editedNew);
    }

    @Test
    void editBufferSurfacesBridgeError() throws Exception {
        bridge.editError = "old_text not found in the buffer.";
        ObjectNode r = call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\"}");
        assertTrue(isError(r));
        assertTrue(r.get("content").get(0).get("text").asText().contains("not found"));
    }

    // --- a missing, misspelled or empty argument must never widen what a tool does ----------------

    @Test
    void editBufferWithoutOldTextIsRefusedInsteadOfReplacingTheWholeBuffer() throws Exception {
        for (String args : List.of(
                "{\"path\":\"/tmp/a.java\",\"new_text\":\"BETA\"}",
                "{\"path\":\"/tmp/a.java\",\"old_text\":\"\",\"new_text\":\"BETA\"}",
                "{\"path\":\"/tmp/a.java\",\"old_text\":null,\"new_text\":\"BETA\"}",
                "{\"new_text\":\"BETA\",\"replace_all\":true}")) {
            ObjectNode r = call("edit_buffer", args);
            assertTrue(isError(r), args);
            String message = r.get("content").get(0).get("text").asText();
            assertTrue(message.contains("old_text") && message.contains("replace_whole_buffer"), message);
        }
        assertEquals(0, bridge.edits + bridge.replacements, "nothing reached the editor");
    }

    @Test
    void aMisspelledArgumentIsRejectedByNameNotIgnored() throws Exception {
        // find_in_files spells its arguments in camelCase; edit_buffer's are snake_case.
        ObjectNode r = call("edit_buffer", "{\"path\":\"/tmp/a.java\",\"oldText\":\"beta\",\"new_text\":\"BETA\"}");
        assertTrue(isError(r));
        String message = r.get("content").get(0).get("text").asText();
        assertTrue(message.contains("'oldText'") && message.contains("'old_text'"), message);
        assertEquals(0, bridge.edits + bridge.replacements);

        // {"file": ...} used to save / read whatever buffer happened to be active.
        ObjectNode save = call("save_buffer", "{\"file\":\"/tmp/a.java\"}");
        assertTrue(isError(save));
        assertTrue(save.get("content").get(0).get("text").asText().contains("'file'"));
        assertEquals(0, bridge.saves);
        assertTrue(isError(call("read_buffer", "{\"filePath\":\"/tmp/a.java\"}")));
        assertEquals(0, bridge.reads);
        assertTrue(isError(call("find_in_files", "{\"query\":\"x\",\"case_sensitive\":true}")));
        assertTrue(isError(call("list_open_files", "{\"path\":\"/tmp/a.java\"}")), "a tool with no arguments");
        assertTrue(isError(call("edit_buffer", "[\"new_text\"]")), "arguments must be an object");
    }

    @Test
    void anEmptyOrRelativePathIsRejectedInsteadOfMeaningTheActiveBuffer() throws Exception {
        for (String tool : List.of("read_buffer", "save_buffer", "get_diagnostics", "document_symbols", "open_file")) {
            for (String path : List.of("", "  ", "src/Main.java")) {
                ObjectNode r = call(tool, "{\"path\":\"" + path + "\"}");
                assertTrue(isError(r), tool + " path='" + path + "'");
                assertTrue(r.get("content").get(0).get("text").asText().contains("'path'"), tool);
            }
        }
        assertTrue(isError(call("edit_buffer", "{\"path\":\"\",\"old_text\":\"a\",\"new_text\":\"b\"}")));
        assertEquals(0, bridge.saves + bridge.reads + bridge.edits);
        assertNull(bridge.openedPath);
        // Omitting it is still how the active buffer is named.
        assertFalse(isError(call("save_buffer", "{}")));
        assertNull(bridge.savedPath);
        assertFalse(isError(call("save_buffer", null)));
    }

    @Test
    void aWronglyTypedArgumentIsRejected() throws Exception {
        assertTrue(isError(call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_all\":\"yes\"}")));
        assertTrue(isError(call("edit_buffer", "{\"old_text\":7,\"new_text\":\"b\"}")));
        assertTrue(isError(call("open_file", "{\"path\":\"/tmp/x.txt\",\"line\":\"12\"}")));
        assertEquals(0, bridge.edits);
        assertNull(bridge.openedPath);
    }

    @Test
    void replacingTheWholeBufferTakesAnExplicitArgument() throws Exception {
        ObjectNode r = call(
                "edit_buffer", "{\"path\":\"/tmp/a.java\",\"new_text\":\"all new\",\"replace_whole_buffer\":true}");
        assertFalse(isError(r));
        assertEquals("/tmp/a.java", bridge.replacedPath);
        assertEquals("all new", bridge.replacedNew);
        assertEquals(0, bridge.edits);
        // ...and is not combined with a targeted edit: which of the two was meant?
        assertTrue(
                isError(call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_whole_buffer\":true}")));
        assertTrue(isError(
                call("edit_buffer", "{\"new_text\":\"b\",\"replace_whole_buffer\":true,\"replace_all\":true}")));
        assertEquals(1, bridge.replacements);
        // false is the same as absent
        assertFalse(
                isError(call("edit_buffer", "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_whole_buffer\":false}")));
        assertEquals(1, bridge.edits);
    }

    /** Every argument a tool accepts is in its published schema, and nothing else is: one list, two uses. */
    @Test
    void theEditBufferSchemaDescribesWhatTheToolAccepts() {
        for (JsonNode t : tools.listToolsResult().get("tools")) {
            if ("edit_buffer".equals(t.get("name").asText())) {
                JsonNode props = t.get("inputSchema").get("properties");
                List<String> names = new java.util.ArrayList<>();
                props.fieldNames().forEachRemaining(names::add);
                assertEquals(List.of("path", "old_text", "new_text", "replace_all", "replace_whole_buffer"), names);
                assertEquals(
                        "boolean", props.get("replace_whole_buffer").get("type").asText());
                assertFalse(
                        props.get("old_text").get("description").asText().contains("Omit or pass"),
                        "the description no longer advertises the implicit whole-buffer replacement");
                assertTrue(t.get("description").asText().contains("replace_whole_buffer"));
                return;
            }
        }
        throw new AssertionError("edit_buffer not listed");
    }

    // --- save_buffer --------------------------------------------------------------------------

    @Test
    void saveBufferOkAndErrorPaths() throws Exception {
        assertFalse(isError(call("save_buffer", "{\"path\":\"/tmp/a.java\"}")));
        assertEquals("/tmp/a.java", bridge.savedPath);
        bridge.saveError = "Untitled buffer has no file path; Save As must be done in the editor.";
        assertTrue(isError(call("save_buffer", null)));
    }

    // --- get_selection ------------------------------------------------------------------------

    @Test
    void selectionWithoutBufferIsError() throws Exception {
        assertTrue(isError(call("get_selection", null)));
    }

    @Test
    void selectionMapsAllFields() throws Exception {
        bridge.selection = new McpBridge.Selection("/tmp/a.java", "a.java", 3, 7, 2, 1, 3, 7, "sel");
        JsonNode p = payload(call("get_selection", null));
        assertEquals("/tmp/a.java", p.get("path").asText());
        assertEquals(3, p.get("caretLine").asInt());
        assertEquals(7, p.get("caretCol").asInt());
        assertEquals(2, p.get("selStartLine").asInt());
        assertEquals("sel", p.get("selectedText").asText());
    }

    // --- document_symbols ---------------------------------------------------------------------

    @Test
    void symbolsNestChildrenAndDropEmptyDetail() throws Exception {
        bridge.symbols = List.of(new McpBridge.Symbol(
                "ClassA",
                "",
                "class",
                1,
                20,
                List.of(new McpBridge.Symbol("run", "(String) : void", "method", 3, 5, List.of()))));
        JsonNode p = payload(call("document_symbols", null));
        JsonNode cls = p.get(0);
        assertEquals("ClassA", cls.get("name").asText());
        assertNull(cls.get("detail")); // empty detail omitted
        JsonNode child = cls.get("children").get(0);
        assertEquals("run", child.get("name").asText());
        assertEquals("(String) : void", child.get("detail").asText());
        assertEquals(3, child.get("line").asInt());
    }

    // --- git_status ---------------------------------------------------------------------------

    @Test
    void gitStatusOutsideRepoIsCompact() throws Exception {
        JsonNode p = payload(call("git_status", null));
        assertFalse(p.get("repo").asBoolean());
        assertNull(p.get("branch"));
    }

    @Test
    void gitStatusInRepoListsFiles() throws Exception {
        bridge.gitState = new McpBridge.GitState(
                true,
                "/repo",
                "main",
                "origin/main",
                2,
                1,
                List.of(new McpBridge.GitFileState("src/A.java", "M", ".", null)));
        JsonNode p = payload(call("git_status", null));
        assertTrue(p.get("repo").asBoolean());
        assertEquals("main", p.get("branch").asText());
        assertEquals(2, p.get("ahead").asInt());
        JsonNode f = p.get("files").get(0);
        assertEquals("src/A.java", f.get("path").asText());
        assertEquals("M", f.get("index").asText());
        assertNull(f.get("origPath")); // null origPath omitted
    }

    @Test
    void unknownToolIsError() throws Exception {
        assertTrue(isError(call("does_not_exist", null)));
    }
}
