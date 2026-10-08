package com.editora.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every MCP tool as a client reaches it: a JSON-RPC request over the loopback HTTP endpoint, against a
 * recording {@link McpBridge}. Each test states what arrived at the editor and what the client was told, so a
 * request that is malformed, misnamed or refused can be seen never to have reached the bridge.
 */
class McpJsonRpcToolsTest {

    /** Records every call and answers with whatever the test set. */
    static final class RecordingBridge implements McpBridge {
        final List<String> calls = new ArrayList<>();
        List<OpenFile> openFiles = List.of();
        BufferContent content;
        List<Diagnostic> diagnostics = List.of();
        List<SearchMatch> matches = List.of();
        List<CommandInfo> commands = List.of();
        boolean commandRuns = true;
        String openError;
        String editError;
        String saveError;
        Selection selection;
        List<Symbol> symbols = List.of();
        GitState git = new GitState(false, null, null, null, 0, 0, List.of());
        List<TabInfo> tabs = List.of();
        List<TodoItem> todos = List.of();
        RuntimeException failure;

        private void record(String call) {
            synchronized (calls) {
                calls.add(call);
            }
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public List<OpenFile> listOpenFiles() {
            record("listOpenFiles");
            return openFiles;
        }

        @Override
        public BufferContent readBuffer(String path) {
            record("readBuffer " + path);
            return content;
        }

        @Override
        public List<Diagnostic> getDiagnostics(String path) {
            record("getDiagnostics " + path);
            return diagnostics;
        }

        @Override
        public List<SearchMatch> findInFiles(String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
            record("findInFiles " + query + " case=" + caseSensitive + " regex=" + regex + " word=" + wholeWord);
            return matches;
        }

        @Override
        public List<CommandInfo> listCommands() {
            record("listCommands");
            return commands;
        }

        @Override
        public boolean executeCommand(String id) {
            record("executeCommand " + id);
            return commandRuns;
        }

        @Override
        public String openFile(String path, int line, int col) {
            record("openFile " + path + " " + line + ":" + col);
            return openError;
        }

        @Override
        public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
            record("editBuffer " + path + " [" + oldText + "]->[" + newText + "] all=" + replaceAll);
            return editError;
        }

        @Override
        public String replaceBuffer(String path, String newText) {
            record("replaceBuffer " + path + " [" + newText + "]");
            return editError;
        }

        @Override
        public String saveBuffer(String path) {
            record("saveBuffer " + path);
            return saveError;
        }

        @Override
        public Selection getSelection() {
            record("getSelection");
            return selection;
        }

        @Override
        public List<Symbol> documentSymbols(String path) {
            record("documentSymbols " + path);
            return symbols;
        }

        @Override
        public GitState gitStatus() {
            record("gitStatus");
            return git;
        }

        @Override
        public List<TabInfo> listTabs() {
            record("listTabs");
            return tabs;
        }

        @Override
        public List<TodoItem> todoScan() {
            record("todoScan");
            return todos;
        }
    }

    @TempDir
    Path configDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingBridge bridge = new RecordingBridge();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private McpServer server;
    private int port;
    private int nextId = 1;

    @BeforeEach
    void start() throws Exception {
        server = new McpServer(bridge, configDir);
        port = server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
        client.close();
    }

    // --- the protocol envelope ----------------------------------------------------------------------

    @Test
    void initializeNamesTheServerItsVersionAndThatItOffersTools() throws Exception {
        JsonNode response = rpc("initialize", "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}");

        JsonNode result = response.get("result");
        assertEquals(McpTools.PROTOCOL_VERSION, result.get("protocolVersion").asText());
        assertEquals("editora", result.get("serverInfo").get("name").asText());
        assertEquals(
                com.editora.AppInfo.VERSION,
                result.get("serverInfo").get("version").asText());
        assertTrue(result.get("capabilities").get("tools").isObject(), "tools are offered");
        assertEquals(1, result.get("capabilities").size(), "and nothing the server does not implement");
        assertEquals(List.of(), bridge.calls, "a handshake does not touch the editor");
    }

    @Test
    void theResponseCarriesTheRequestsIdWhateverItsJsonType() throws Exception {
        assertEquals(
                7,
                send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}")
                        .get("id")
                        .asInt());
        JsonNode text = send("{\"jsonrpc\":\"2.0\",\"id\":\"req-9\",\"method\":\"ping\"}");
        assertEquals("req-9", text.get("id").asText());
        assertTrue(text.get("result").isObject());
        assertEquals(0, text.get("result").size(), "ping answers an empty object");
    }

    @Test
    void anUnknownMethodAndAMissingMethodAreErrorsNotSilence() throws Exception {
        JsonNode unknown = rpc("resources/list", null);
        assertEquals(JsonRpc.METHOD_NOT_FOUND, unknown.get("error").get("code").asInt());
        assertTrue(unknown.get("error").get("message").asText().contains("resources/list"));
        assertNull(unknown.get("result"));

        JsonNode missing = send("{\"jsonrpc\":\"2.0\",\"id\":2}");
        assertEquals(JsonRpc.METHOD_NOT_FOUND, missing.get("error").get("code").asInt());
        assertEquals(List.of(), bridge.calls);
    }

    @Test
    void aBodyThatIsNotJsonIsAParseErrorWithANullId() throws Exception {
        HttpResponse<String> response = post(server.token(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":");

        assertEquals(200, response.statusCode());
        JsonNode body = mapper.readTree(response.body());
        assertEquals(JsonRpc.PARSE_ERROR, body.get("error").get("code").asInt());
        assertTrue(body.get("id").isNull());
        assertEquals(List.of(), bridge.calls);
    }

    @Test
    void aNotificationIsAcknowledgedWithoutABodyAndRunsNothing() throws Exception {
        HttpResponse<String> initialized =
                post(server.token(), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, initialized.statusCode());
        assertEquals("", initialized.body());

        // A tools/call without an id is a notification too: nothing could report its outcome, so it must not
        // be carried out.
        HttpResponse<String> call = post(
                server.token(),
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"save_buffer\"}}");
        assertEquals(202, call.statusCode());
        assertEquals(List.of(), bridge.calls, "a call nobody waits for is not applied");
    }

    @Test
    void onlyAnAuthorizedPostReachesTheTools() throws Exception {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"save_buffer\"}}";

        assertEquals(401, post(null, call).statusCode(), "no token");
        assertEquals(401, post(server.token() + "0", call).statusCode(), "a longer token");
        assertEquals(
                401,
                post(server.token().substring(1), call).statusCode(),
                "a token that only shares the real one's tail");
        HttpRequest basic = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + server.token())
                .POST(HttpRequest.BodyPublishers.ofString(call))
                .build();
        assertEquals(
                401, client.send(basic, HttpResponse.BodyHandlers.ofString()).statusCode(), "another scheme");
        HttpRequest get = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + server.token())
                .GET()
                .build();
        assertEquals(405, client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode(), "GET is not served");

        assertEquals(List.of(), bridge.calls, "none of them saved anything");
    }

    @Test
    void aBridgeFailureIsAnInternalErrorCarryingItsMessage() throws Exception {
        bridge.failure = new IllegalStateException("The editor did not answer within 5 s; will not be applied.");

        JsonNode response = call("save_buffer", "{\"path\":\"/tmp/a.java\"}");

        assertEquals(JsonRpc.INTERNAL_ERROR, response.get("error").get("code").asInt());
        assertEquals(
                "The editor did not answer within 5 s; will not be applied.",
                response.get("error").get("message").asText());
        assertNull(response.get("result"), "a failure is never also a 'saved'");
    }

    // --- tools/list ---------------------------------------------------------------------------------

    @Test
    void everyListedToolIsDispatchedAndNothingUnlistedIs() throws Exception {
        JsonNode tools = rpc("tools/list", null).get("result").get("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.get("name").asText()));
        assertEquals(
                List.of(
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
                        "todo_scan"),
                names);

        for (JsonNode tool : tools) {
            String name = tool.get("name").asText();
            // The least a client can send: every required argument, as a string.
            com.fasterxml.jackson.databind.node.ObjectNode arguments = mapper.createObjectNode();
            if (tool.get("inputSchema").has("required")) {
                tool.get("inputSchema").get("required").forEach(required -> arguments.put(required.asText(), "x"));
            }
            if (arguments.has("path")) {
                arguments.put("path", "/tmp/x");
            }
            if (name.equals("edit_buffer")) {
                arguments.put("old_text", "y");
            }
            int before = bridge.calls.size();
            JsonNode result = call(name, arguments.toString()).get("result");
            assertTrue(result.has("isError"), name + " answers a tool result");
            assertEquals(before + 1, bridge.calls.size(), name + " reaches the editor exactly once");
        }

        int before = bridge.calls.size();
        for (String unlisted : List.of("write_file", "read_file", "delete_file", "run_shell", "LIST_OPEN_FILES", "")) {
            JsonNode result = call(unlisted, "{}").get("result");
            assertTrue(result.get("isError").asBoolean(), unlisted);
            assertEquals("Unknown tool: " + unlisted, text(result));
        }
        assertEquals(before, bridge.calls.size(), "an unknown tool reaches nothing");
    }

    @Test
    void aCallWithoutAToolNameIsRefused() throws Exception {
        for (String params : new String[] {null, "{}", "{\"name\":null}", "{\"arguments\":{\"path\":\"/tmp/a\"}}"}) {
            JsonNode result = rpc("tools/call", params).get("result");
            assertTrue(result.get("isError").asBoolean(), String.valueOf(params));
            assertEquals("Missing tool name.", text(result));
        }
        assertEquals(List.of(), bridge.calls);
    }

    // --- the read tools -----------------------------------------------------------------------------

    @Test
    void listOpenFilesReportsEachBufferIncludingAnUntitledOne() throws Exception {
        bridge.openFiles = List.of(
                new McpBridge.OpenFile("/work/a.java", "a.java", "java", true, true),
                new McpBridge.OpenFile(null, "Untitled-1", null, false, false));

        JsonNode files = payload(call("list_open_files", null));

        assertEquals(2, files.size());
        assertEquals("/work/a.java", files.get(0).get("path").asText());
        assertEquals("java", files.get(0).get("language").asText());
        assertTrue(files.get(0).get("dirty").asBoolean());
        assertTrue(files.get(0).get("active").asBoolean());
        assertTrue(files.get(1).get("path").isNull(), "an untitled buffer has no path, not the text \"null\"");
        assertEquals("Untitled-1", files.get(1).get("title").asText());
        assertFalse(files.get(1).get("dirty").asBoolean());
        assertEquals(List.of("listOpenFiles"), bridge.calls);
    }

    @Test
    void readBufferReturnsTheLiveTextAndSaysWhenThereIsNoSuchBuffer() throws Exception {
        bridge.content = new McpBridge.BufferContent("/work/a.java", "a.java", "java", true, "línea 1\n\t\"quoted\"\n");

        JsonNode read = payload(call("read_buffer", "{\"path\":\"/work/a.java\"}"));

        assertEquals("línea 1\n\t\"quoted\"\n", read.get("text").asText(), "the text survives the JSON round trip");
        assertEquals("/work/a.java", read.get("path").asText());
        assertTrue(read.get("dirty").asBoolean());
        payload(call("read_buffer", null));
        payload(call("read_buffer", "{\"path\":null}"));
        assertEquals(List.of("readBuffer /work/a.java", "readBuffer null", "readBuffer null"), bridge.calls);

        bridge.content = null;
        JsonNode missing = call("read_buffer", "{\"path\":\"/etc/shadow\"}").get("result");
        assertTrue(missing.get("isError").asBoolean());
        assertEquals("No open buffer for: /etc/shadow", text(missing));
        JsonNode none = call("read_buffer", null).get("result");
        assertEquals("No active buffer.", text(none));
    }

    @Test
    void getDiagnosticsMapsEachDiagnostic() throws Exception {
        bridge.diagnostics = List.of(
                new McpBridge.Diagnostic(3, 9, "ERROR", "cannot find symbol", "javac"),
                new McpBridge.Diagnostic(1, 1, "WARNING", "unused import", null));

        JsonNode diagnostics = payload(call("get_diagnostics", "{\"path\":\"/work/a.java\"}"));

        assertEquals(2, diagnostics.size());
        assertEquals(3, diagnostics.get(0).get("line").asInt());
        assertEquals(9, diagnostics.get(0).get("col").asInt());
        assertEquals("ERROR", diagnostics.get(0).get("severity").asText());
        assertEquals("cannot find symbol", diagnostics.get(0).get("message").asText());
        assertEquals("javac", diagnostics.get(0).get("origin").asText());
        assertTrue(diagnostics.get(1).get("origin").isNull());
        assertEquals(List.of("getDiagnostics /work/a.java"), bridge.calls);

        bridge.diagnostics = List.of();
        JsonNode empty = call("get_diagnostics", null).get("result");
        assertFalse(empty.get("isError").asBoolean(), "no diagnostics is an answer, not an error");
        assertEquals(0, payload(empty).size());
    }

    @Test
    void findInFilesPassesItsFlagsAndRefusesAnEmptyQuery() throws Exception {
        bridge.matches = List.of(new McpBridge.SearchMatch("/work/a.java", 4, 7, "    int needle = 1;"));

        JsonNode hits =
                payload(call("find_in_files", "{\"query\":\"needle\",\"caseSensitive\":true,\"wholeWord\":true}"));

        assertEquals(1, hits.size());
        assertEquals("/work/a.java", hits.get(0).get("file").asText());
        assertEquals(4, hits.get(0).get("line").asInt());
        assertEquals(7, hits.get(0).get("col").asInt());
        assertEquals("    int needle = 1;", hits.get(0).get("lineText").asText());
        payload(call("find_in_files", "{\"query\":\"a.*b\",\"regex\":true}"));
        payload(call("find_in_files", "{\"query\":\"plain\",\"caseSensitive\":null}"));
        assertEquals(
                List.of(
                        "findInFiles needle case=true regex=false word=true",
                        "findInFiles a.*b case=false regex=true word=false",
                        "findInFiles plain case=false regex=false word=false"),
                bridge.calls);

        bridge.calls.clear();
        for (String arguments : new String[] {
            null,
            "{}",
            "{\"query\":\"\"}",
            "{\"query\":null}",
            "{\"query\":7}",
            "{\"query\":[\"a\"]}",
            "{\"query\":\"a\",\"regex\":\"true\"}",
            "{\"query\":\"a\",\"path\":\"/etc\"}"
        }) {
            assertTrue(
                    call("find_in_files", arguments)
                            .get("result")
                            .get("isError")
                            .asBoolean(),
                    String.valueOf(arguments));
        }
        assertEquals(List.of(), bridge.calls, "no search was started for any of them");
    }

    @Test
    void getSelectionDocumentSymbolsGitStatusTabsAndTodosAreMappedThroughTheEndpoint() throws Exception {
        bridge.selection = new McpBridge.Selection(null, "Untitled-1", 2, 5, 2, 1, 2, 5, "word");
        bridge.symbols = List.of(new McpBridge.Symbol(
                "Outer", "", "class", 1, 30, List.of(new McpBridge.Symbol("run", "void", "method", 4, 9, List.of()))));
        bridge.git = new McpBridge.GitState(
                true,
                "/work",
                "main",
                null,
                0,
                2,
                List.of(
                        new McpBridge.GitFileState("new.txt", "?", "?", null),
                        new McpBridge.GitFileState("b.txt", "R", ".", "a.txt")));
        bridge.tabs = List.of(
                new McpBridge.TabInfo("welcome", "Welcome", null, false),
                new McpBridge.TabInfo("editor", "a.java", "/work/a.java", true));
        bridge.todos = List.of(new McpBridge.TodoItem("/work/a.java", 8, 5, "TODO", "adl", "high", "// TODO(adl): !"));

        JsonNode selection = payload(call("get_selection", "{}"));
        assertTrue(selection.get("path").isNull());
        assertEquals("word", selection.get("selectedText").asText());
        assertEquals(5, selection.get("caretCol").asInt());
        assertEquals(1, selection.get("selStartCol").asInt());

        JsonNode symbols = payload(call("document_symbols", "{\"path\":\"/work/a.java\"}"));
        assertEquals("Outer", symbols.get(0).get("name").asText());
        assertFalse(symbols.get(0).has("detail"), "an empty detail is left out");
        assertEquals("void", symbols.get(0).get("children").get(0).get("detail").asText());
        assertFalse(symbols.get(0).get("children").get(0).has("children"));

        JsonNode git = payload(call("git_status", null));
        assertEquals("main", git.get("branch").asText());
        assertTrue(git.get("upstream").isNull(), "a branch without an upstream");
        assertEquals(2, git.get("behind").asInt());
        assertFalse(git.get("files").get(0).has("origPath"));
        assertEquals("a.txt", git.get("files").get(1).get("origPath").asText());

        JsonNode tabs = payload(call("list_tabs", null));
        assertEquals("welcome", tabs.get(0).get("type").asText());
        assertTrue(tabs.get(0).get("path").isNull());
        assertTrue(tabs.get(1).get("active").asBoolean());

        JsonNode todos = payload(call("todo_scan", null));
        assertEquals("TODO", todos.get(0).get("keyword").asText());
        assertEquals("adl", todos.get(0).get("tag").asText());
        assertEquals("high", todos.get(0).get("priority").asText());
        assertEquals(8, todos.get(0).get("line").asInt());

        assertEquals(
                List.of("getSelection", "documentSymbols /work/a.java", "gitStatus", "listTabs", "todoScan"),
                bridge.calls);
    }

    @Test
    void listCommandsThenExecuteCommandRunsExactlyTheNamedCommand() throws Exception {
        bridge.commands = List.of(new McpBridge.CommandInfo("file.save", "Save", "Save the active file"));

        JsonNode commands = payload(call("list_commands", null));
        assertEquals("file.save", commands.get(0).get("id").asText());
        assertEquals("Save", commands.get(0).get("title").asText());
        assertEquals("Save the active file", commands.get(0).get("description").asText());

        JsonNode ran = payload(call("execute_command", "{\"id\":\"file.save\"}"));
        assertTrue(ran.get("ran").asBoolean());
        assertEquals("file.save", ran.get("id").asText());

        bridge.commandRuns = false;
        JsonNode unknown =
                call("execute_command", "{\"id\":\"file.format-disk\"}").get("result");
        assertTrue(unknown.get("isError").asBoolean());
        assertEquals("No such command: file.format-disk", text(unknown));
        assertEquals(
                List.of("listCommands", "executeCommand file.save", "executeCommand file.format-disk"), bridge.calls);

        bridge.calls.clear();
        for (String arguments :
                new String[] {null, "{}", "{\"id\":\"\"}", "{\"id\":null}", "{\"id\":12}", "{\"command\":\"file.save\"}"
                }) {
            assertTrue(
                    call("execute_command", arguments)
                            .get("result")
                            .get("isError")
                            .asBoolean(),
                    String.valueOf(arguments));
        }
        assertEquals(List.of(), bridge.calls, "a command is never guessed");
    }

    // --- the tools that change something ------------------------------------------------------------

    @Test
    void openFilePassesThePathAsGivenAndOnlyAbsolutePathsReachTheEditor() throws Exception {
        JsonNode opened = payload(call("open_file", "{\"path\":\"/work/a.java\",\"line\":12,\"col\":3}"));
        assertTrue(opened.get("opened").asBoolean());
        assertEquals("/work/a.java", opened.get("path").asText());
        payload(call("open_file", "{\"path\":\"/work/a.java\"}"));
        // Not resolved here: whether a path that climbs out of the project may be opened is the editor's call,
        // and it has to see the path as the client wrote it to make it.
        payload(call("open_file", "{\"path\":\"/work/../etc/passwd\"}"));
        assertEquals(
                List.of("openFile /work/a.java 12:3", "openFile /work/a.java 0:0", "openFile /work/../etc/passwd 0:0"),
                bridge.calls);

        bridge.openError = "Refused: /etc/passwd is outside the project folder /work.";
        JsonNode refused = call("open_file", "{\"path\":\"/etc/passwd\"}").get("result");
        assertTrue(refused.get("isError").asBoolean(), "a file the editor did not open is not reported as opened");
        assertEquals(bridge.openError, text(refused), "and the client is told why");
        bridge.openError = null;

        bridge.calls.clear();
        for (String arguments : new String[] {
            null,
            "{}",
            "{\"path\":\"\"}",
            "{\"path\":\"   \"}",
            "{\"path\":\"a.java\"}",
            "{\"path\":\"../../etc/passwd\"}",
            "{\"path\":\"~/.ssh/id_rsa\"}",
            "{\"path\":17}",
            "{\"path\":\"/work/a.java\",\"line\":1.5}",
            "{\"path\":\"/work/a.java\",\"line\":\"3\"}",
            "{\"path\":\"/work/a.java\",\"column\":3}",
            "[\"/work/a.java\"]",
            "\"/work/a.java\""
        }) {
            assertTrue(
                    call("open_file", arguments).get("result").get("isError").asBoolean(), String.valueOf(arguments));
        }
        assertEquals(List.of(), bridge.calls, "nothing relative, empty or mistyped is handed to the editor");
    }

    @Test
    void editBufferReachesTheEditorOnlyAsTheEditThatWasAskedFor() throws Exception {
        JsonNode applied = payload(call(
                "edit_buffer",
                "{\"path\":\"/work/a.java\",\"old_text\":\"a\",\"new_text\":\"b\",\"replace_all\":true}"));
        assertTrue(applied.get("applied").asBoolean());
        payload(call("edit_buffer", "{\"old_text\":\"gone\",\"new_text\":\"\"}"));
        payload(call("edit_buffer", "{\"path\":\"/work/a.java\",\"new_text\":\"all\",\"replace_whole_buffer\":true}"));
        assertEquals(
                List.of(
                        "editBuffer /work/a.java [a]->[b] all=true",
                        "editBuffer null [gone]->[] all=false",
                        "replaceBuffer /work/a.java [all]"),
                bridge.calls);

        bridge.editError = "old_text not found in the buffer.";
        JsonNode refused =
                call("edit_buffer", "{\"old_text\":\"zzz\",\"new_text\":\"b\"}").get("result");
        assertTrue(refused.get("isError").asBoolean());
        assertEquals("old_text not found in the buffer.", text(refused));

        bridge.editError = null;
        bridge.calls.clear();
        for (String arguments : new String[] {
            null,
            "{}",
            "{\"old_text\":\"a\"}",
            "{\"new_text\":\"b\"}",
            "{\"old_text\":\"\",\"new_text\":\"b\"}",
            "{\"old_text\":null,\"new_text\":\"b\"}",
            "{\"old_text\":\"a\",\"new_text\":null}",
            "{\"old_text\":\"a\",\"new_text\":5}",
            "{\"oldText\":\"a\",\"newText\":\"b\"}",
            "{\"path\":\"a.java\",\"old_text\":\"a\",\"new_text\":\"b\"}",
            "{\"old_text\":\"a\",\"new_text\":\"b\",\"replace_whole_buffer\":true}",
            "{\"new_text\":\"b\",\"replace_whole_buffer\":\"true\"}",
            "{\"new_text\":\"b\",\"replace_whole_buffer\":true,\"replace_all\":true}"
        }) {
            assertTrue(
                    call("edit_buffer", arguments).get("result").get("isError").asBoolean(), String.valueOf(arguments));
        }
        assertEquals(List.of(), bridge.calls, "a request that is not exactly one kind of edit changes nothing");
    }

    @Test
    void saveBufferReportsSavedOnlyWhenTheEditorSaysSo() throws Exception {
        JsonNode saved = payload(call("save_buffer", "{\"path\":\"/work/a.java\"}"));
        assertTrue(saved.get("saved").asBoolean());
        payload(call("save_buffer", null));
        assertEquals(List.of("saveBuffer /work/a.java", "saveBuffer null"), bridge.calls);

        bridge.saveError = "Not saved: the buffer is still unsaved and the file on disk was not updated.";
        JsonNode failed = call("save_buffer", "{\"path\":\"/work/a.java\"}").get("result");
        assertTrue(failed.get("isError").asBoolean());
        assertEquals(bridge.saveError, text(failed));
        assertFalse(text(failed).contains("\"saved\""));

        bridge.saveError = null;
        bridge.calls.clear();
        for (String arguments : new String[] {
            "{\"path\":\"\"}",
            "{\"path\":\"a.java\"}",
            "{\"path\":\"./a.java\"}",
            "{\"path\":false}",
            "{\"file\":\"/work/a.java\"}",
            "{\"path\":\"/work/a.java\",\"force\":true}",
            "[]"
        }) {
            assertTrue(
                    call("save_buffer", arguments).get("result").get("isError").asBoolean(), String.valueOf(arguments));
        }
        assertEquals(List.of(), bridge.calls, "a slip in 'path' never saves the active buffer instead");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    private HttpResponse<String> post(String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends one raw request body with the right token and parses the JSON-RPC response. */
    private JsonNode send(String body) throws Exception {
        HttpResponse<String> response = post(server.token(), body);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode parsed = mapper.readTree(response.body());
        assertEquals("2.0", parsed.get("jsonrpc").asText());
        assertTrue(parsed.has("result") != parsed.has("error"), "exactly one of result and error: " + response.body());
        return parsed;
    }

    private JsonNode rpc(String method, String paramsJson) throws Exception {
        int id = nextId++;
        com.fasterxml.jackson.databind.node.ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (paramsJson != null) {
            request.set("params", mapper.readTree(paramsJson));
        }
        JsonNode response = send(mapper.writeValueAsString(request));
        assertEquals(id, response.get("id").asInt());
        return response;
    }

    private JsonNode call(String tool, String argumentsJson) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode params = mapper.createObjectNode();
        params.put("name", tool);
        if (argumentsJson != null) {
            params.set("arguments", mapper.readTree(argumentsJson));
        }
        return rpc("tools/call", params.toString());
    }

    /** The JSON a successful tool call carries in its one text block. */
    private JsonNode payload(JsonNode responseOrResult) throws Exception {
        JsonNode result = responseOrResult.has("result") ? responseOrResult.get("result") : responseOrResult;
        assertFalse(result.get("isError").asBoolean(), text(result));
        assertEquals(1, result.get("content").size());
        assertEquals("text", result.get("content").get(0).get("type").asText());
        return mapper.readTree(text(result));
    }

    private static String text(JsonNode result) {
        return result.get("content").get(0).get("text").asText();
    }
}
