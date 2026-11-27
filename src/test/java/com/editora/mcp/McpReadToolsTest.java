package com.editora.mcp;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The code-intelligence and Local History tools on {@link McpTools}: what each asks the window and answers. */
class McpReadToolsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final List<String> asked = new ArrayList<>();
    private String failure;

    private final McpBridge bridge = new McpToolsTest.StubBridge() {
        @Override
        public List<Location> navigate(String path, int line, int column, boolean references) {
            asked.add((references ? "refs " : "def ") + path + " " + line + ":" + column);
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            return List.of(new Location("/r/B.java", 10, 5), new Location("jdt://contents/x", 1, 1));
        }

        @Override
        public String hover(String path, int line, int column) {
            asked.add("hover " + line + ":" + column);
            return "`int x`";
        }

        @Override
        public List<WorkspaceSymbol> workspaceSymbols(String query) {
            asked.add("symbols " + query);
            return List.of(new WorkspaceSymbol("Foo", "", "class", "/r/Foo.java", 3));
        }

        @Override
        public List<HistoryEntry> localHistory(String path) {
            return List.of(
                    new HistoryEntry(0, 2000, "SAVE", "", 12),
                    new HistoryEntry(1, 1000, "AGENT", "Before agent write", 10));
        }

        @Override
        public String localHistoryText(String path, int index) {
            return "text " + index;
        }
    };
    private final McpTools tools = new McpTools(bridge, m);

    private ObjectNode call(String name, String args) throws Exception {
        ObjectNode params = m.createObjectNode();
        params.put("name", name);
        params.set("arguments", m.readTree(args));
        return tools.callTool(params);
    }

    private JsonNode payload(ObjectNode result) throws Exception {
        return m.readTree(result.at("/content/0/text").asText());
    }

    @Test
    void everyNewToolIsReadOnly() {
        for (JsonNode t : tools.listToolsResult().get("tools")) {
            String name = t.get("name").asText();
            if (List.of("lsp_definition", "lsp_references", "lsp_hover", "workspace_symbols", "local_history")
                    .contains(name)) {
                assertTrue(t.at("/annotations/readOnlyHint").asBoolean(), name);
            }
        }
    }

    @Test
    void definitionsAndReferencesAreOneBasedPlaces() throws Exception {
        JsonNode defs = payload(call("lsp_definition", "{\"path\":\"/r/A.java\",\"line\":4,\"column\":9}"));
        assertEquals("def /r/A.java 4:9", asked.get(0));
        assertEquals("/r/B.java", defs.get(0).get("path").asText());
        assertEquals(10, defs.get(0).get("line").asInt());
        assertEquals("jdt://contents/x", defs.get(1).get("path").asText(), "a library place is its URI");
        call("lsp_references", "{\"line\":1,\"column\":1}");
        assertEquals("refs null 1:1", asked.get(1), "the active buffer");

        ObjectNode zero = call("lsp_definition", "{\"line\":0,\"column\":1}");
        assertTrue(zero.get("isError").asBoolean());
        assertEquals(2, asked.size(), "a 0 line never reaches the window");

        failure = "No language server serves this file.";
        ObjectNode none = call("lsp_definition", "{\"line\":1,\"column\":1}");
        assertTrue(none.get("isError").asBoolean());
        assertEquals(failure, none.at("/content/0/text").asText());
    }

    @Test
    void hoverAndSymbolsAnswerAsTheServerDoes() throws Exception {
        assertEquals(
                "`int x`",
                payload(call("lsp_hover", "{\"line\":2,\"column\":3}"))
                        .get("hover")
                        .asText());
        JsonNode syms = payload(call("workspace_symbols", "{\"query\":\"Fo\"}"));
        assertEquals("Foo", syms.get(0).get("name").asText());
        assertFalse(syms.get(0).has("container"), "an empty container is left out");
        assertTrue(call("workspace_symbols", "{\"query\":\" \"}").get("isError").asBoolean());
    }

    @Test
    void localHistoryListsRevisionsOrGivesOne() throws Exception {
        JsonNode list = payload(call("local_history", "{\"path\":\"/r/A.java\"}"));
        assertEquals(2, list.size());
        assertEquals("1970-01-01T00:00:02Z", list.get(0).get("time").asText());
        assertEquals("Before agent write", list.get(1).get("label").asText());
        assertFalse(list.get(0).has("label"));
        assertEquals(
                "text 1",
                payload(call("local_history", "{\"path\":\"/r/A.java\",\"revision\":1}"))
                        .get("text")
                        .asText());
    }
}
