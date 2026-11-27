package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code test_results} and {@code debug_state} on {@link McpTools}: what each answers. */
class McpTestDebugToolsTest {

    private final ObjectMapper m = new ObjectMapper();
    private boolean askedPassed;
    private boolean ran = true;

    private final McpTools tools = new McpTools(
            new McpToolsTest.StubBridge() {
                @Override
                public TestSummary testResults(boolean includePassed) {
                    askedPassed = includePassed;
                    return ran
                            ? new TestSummary(
                                    true,
                                    false,
                                    1,
                                    3,
                                    2,
                                    1,
                                    0,
                                    0,
                                    List.of(new TestCase(
                                            "FooTest",
                                            "adds",
                                            "FAILED",
                                            12,
                                            "expected 2",
                                            "at Foo.add",
                                            "/r/FooTest.java",
                                            9)))
                            : new TestSummary(false, false, 0, 0, 0, 0, 0, 0, List.of());
                }

                @Override
                public DebugState debugState() {
                    return new DebugState(
                            "SUSPENDED",
                            List.of(new Frame(7, "main", "/r/Main.java", 4, 1), new Frame(8, "<native>", "", 0, 0)),
                            7,
                            List.of(new Variable(0, "Locals", "", ""), new Variable(1, "x", "int", "41")));
                }
            },
            m);

    private JsonNode call(String name, String args) throws Exception {
        ObjectNode params = m.createObjectNode();
        params.put("name", name);
        params.set("arguments", m.readTree(args));
        return m.readTree(tools.callTool(params).at("/content/0/text").asText());
    }

    @Test
    void theLastRunGivesItsCountsAndWhatDidNotPass() throws Exception {
        JsonNode r = call("test_results", "{}");
        assertFalse(askedPassed, "the failures only, by default");
        assertTrue(r.get("ran").asBoolean());
        assertEquals(1, r.get("exitCode").asInt());
        assertEquals(1, r.at("/counts/failed").asInt());
        JsonNode t = r.get("tests").get(0);
        assertEquals("FooTest", t.get("suite").asText());
        assertEquals("expected 2", t.get("message").asText());
        assertEquals(9, t.get("line").asInt());
        call("test_results", "{\"include_passed\":true}");
        assertTrue(askedPassed);
        ran = false;
        JsonNode none = call("test_results", "{}");
        assertFalse(none.get("ran").asBoolean());
        assertFalse(none.has("tests"), "no run: nothing else");
    }

    @Test
    void thePausedSessionGivesItsStackAndVariables() throws Exception {
        JsonNode d = call("debug_state", "{}");
        assertEquals("SUSPENDED", d.get("state").asText());
        assertEquals(7, d.get("selectedFrame").asInt());
        assertEquals("/r/Main.java", d.at("/frames/0/path").asText());
        assertFalse(d.at("/frames/1").has("path"), "a frame with no source has no place");
        assertEquals("x", d.at("/variables/1/name").asText());
        assertEquals("int", d.at("/variables/1/type").asText());
        assertFalse(d.at("/variables/0").has("type"));
    }
}
