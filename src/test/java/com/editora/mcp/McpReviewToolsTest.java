package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review tools on {@link McpTools} (no HTTP): what each sends the window and what it answers. */
class McpReviewToolsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final McpToolsTest.StubBridge bridge = new McpToolsTest.StubBridge();
    private final McpTools tools = new McpTools(bridge, m);

    private ObjectNode call(String name, String args, String meta) throws Exception {
        ObjectNode params = m.createObjectNode();
        params.put("name", name);
        params.set("arguments", m.readTree(args));
        if (meta != null) {
            params.set("_meta", m.readTree(meta));
        }
        return tools.callTool(params);
    }

    private JsonNode payload(ObjectNode result) throws Exception {
        return m.readTree(result.at("/content/0/text").asText());
    }

    private static McpBridge.ReviewThreadInfo thread() {
        return new McpBridge.ReviewThreadInfo(
                "t1",
                "/repo/A.java",
                3,
                4,
                true,
                "open",
                null,
                "important",
                "correctness",
                List.of(
                        new McpBridge.ReviewCommentInfo("c1", "user", "", "Why?", null, 0),
                        new McpBridge.ReviewCommentInfo("c2", "agent", "Claude Code", "Because.", "x = 1;", 1000)));
    }

    @Test
    void listingGivesEachThreadWithItsLinesStateAndComments() throws Exception {
        bridge.reviews = List.of(thread());
        ObjectNode r = call("list_review_comments", "{\"path\":\"/repo/A.java\",\"state\":\"all\"}", null);
        assertFalse(r.get("isError").asBoolean(), r.toString());
        assertEquals("/repo/A.java true", bridge.lastReviewArgs);
        JsonNode t = payload(r).get(0);
        assertEquals("t1", t.get("id").asText());
        assertEquals(3, t.get("start_line").asInt());
        assertEquals(4, t.get("end_line").asInt());
        assertTrue(t.get("outdated").asBoolean());
        assertEquals("important", t.get("severity").asText());
        assertEquals("correctness", t.get("category").asText());
        assertFalse(t.has("state_note"));
        assertEquals("user", t.at("/comments/0/author").asText(), "the user is not named");
        assertEquals("Claude Code", t.at("/comments/1/author").asText());
        assertEquals("x = 1;", t.at("/comments/1/suggestion").asText());
        assertEquals("1970-01-01T00:00:01Z", t.at("/comments/1/created").asText());
        call("list_review_comments", "{}", null);
        assertEquals("null false", bridge.lastReviewArgs, "open ones by default, the whole repository");
    }

    @Test
    void addingSendsTheFindingAndAnswersItsIdOrWhyNot() throws Exception {
        String args = "{\"path\":\"/repo/A.java\",\"start_line\":2,\"end_line\":3,\"body\":\"Off by one.\","
                + "\"suggestion\":\"i < n\",\"severity\":\"important\",\"category\":\"correctness\"}";
        String meta = "{\"io.modelcontextprotocol/clientInfo\":{\"name\":\"claude-code\",\"title\":\"Claude Code\"}}";
        bridge.added = new McpBridge.ReviewAdded("t9", null, 2, 3);
        ObjectNode r = call("add_review_comment", args, meta);
        assertEquals("t9", payload(r).get("id").asText());
        assertEquals("/repo/A.java:2-3 Off by one. i < n important correctness", bridge.lastReviewArgs);
        assertEquals("Claude Code", bridge.lastAgent, "the client's title from _meta");

        bridge.added = new McpBridge.ReviewAdded(null, "Lines 9-9 are not in the file (it has 4).", 0, 0);
        ObjectNode refused = call("add_review_comment", args, null);
        assertTrue(refused.get("isError").asBoolean());
        assertTrue(refused.at("/content/0/text").asText().contains("not in the file"));
        assertEquals("Agent", bridge.lastAgent, "no client info: Agent");

        ObjectNode blank =
                call("add_review_comment", "{\"path\":\"/repo/A.java\",\"start_line\":1,\"body\":\" \"}", null);
        assertTrue(blank.get("isError").asBoolean());
        assertEquals("body is required.", blank.at("/content/0/text").asText());
    }

    @Test
    void replyAndResolveSayDoneOrTheWindowsReason() throws Exception {
        ObjectNode reply = call("reply_to_review_comment", "{\"thread_id\":\"t1\",\"body\":\"Done.\"}", null);
        assertTrue(payload(reply).get("done").asBoolean());
        assertEquals("t1 Done.", bridge.lastReviewArgs);
        ObjectNode resolve = call("resolve_review_comment", "{\"thread_id\":\"t1\",\"note\":\"Fixed\"}", null);
        assertTrue(payload(resolve).get("done").asBoolean());
        bridge.reviewError = "Review thread t1 is not open.";
        ObjectNode again = call("resolve_review_comment", "{\"thread_id\":\"t1\",\"note\":\"Fixed\"}", null);
        assertTrue(again.get("isError").asBoolean());
        assertEquals(
                "Review thread t1 is not open.", again.at("/content/0/text").asText());
        assertTrue(call("resolve_review_comment", "{\"thread_id\":\"t1\"}", null)
                .get("isError")
                .asBoolean());
    }

    @Test
    void withoutARepositoryTheToolSaysSoAndTheResourceIsEmpty() throws Exception {
        bridge.noRepository = true;
        ObjectNode r = call("list_review_comments", "{}", null);
        assertTrue(r.get("isError").asBoolean());
        assertTrue(r.at("/content/0/text").asText().contains("no Git repository"));
        assertEquals(
                "[]",
                tools.readResourceResult(McpTools.REVIEWS_URI)
                        .at("/contents/0/text")
                        .asText());
        assertEquals("[]", tools.resourceStamp(McpTools.REVIEWS_URI, java.util.Map.of()));
    }

    @Test
    void theReviewsResourceListsTheOpenThreadsAndItsStampFollowsThem() throws Exception {
        bridge.reviews = List.of(thread());
        String text = tools.readResourceResult(McpTools.REVIEWS_URI)
                .at("/contents/0/text")
                .asText();
        assertTrue(text.contains("\"t1\""), text);
        String before = tools.resourceStamp(McpTools.REVIEWS_URI, java.util.Map.of());
        bridge.reviews = List.of();
        assertFalse(before.equals(tools.resourceStamp(McpTools.REVIEWS_URI, java.util.Map.of())), "a change is seen");
        assertEquals("Agent", McpTools.agentName(null));
        assertEquals(
                "x".repeat(64),
                McpTools.agentName(m.readTree(
                        "{\"_meta\":{\"io.modelcontextprotocol/clientInfo\":{\"name\":\"" + "x".repeat(80) + "\"}}}")),
                "a long name is cut");
    }
}
