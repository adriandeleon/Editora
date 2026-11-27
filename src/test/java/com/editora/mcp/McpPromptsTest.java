package com.editora.mcp;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP prompts on {@link McpTools} (no HTTP): what each one asks, built from the window as it is. */
class McpPromptsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final McpToolsTest.StubBridge bridge = new McpToolsTest.StubBridge();
    private final McpTools tools = new McpTools(bridge, m);

    private String prompt(String name, String args) throws Exception {
        ObjectNode params = m.createObjectNode().put("name", name);
        if (args != null) {
            params.set("arguments", m.readTree(args));
        }
        ObjectNode r = tools.getPromptResult(params);
        assertEquals("user", r.at("/messages/0/role").asText());
        assertFalse(r.get("description").asText().isEmpty());
        return r.at("/messages/0/content/text").asText();
    }

    @Test
    void theListNamesThreePromptsWithTheirOptionalArguments() {
        JsonNode prompts = tools.promptsListResult().get("prompts");
        assertEquals(3, prompts.size());
        assertEquals("review_changes", prompts.get(0).get("name").asText());
        assertEquals("focus", prompts.get(0).at("/arguments/0/name").asText());
        assertFalse(prompts.get(0).at("/arguments/0/required").asBoolean());
        assertEquals(0, prompts.get(2).get("arguments").size(), "explain_selection takes none");
    }

    @Test
    void reviewChangesListsTheChangedFilesAndHowToRecordFindings() throws Exception {
        bridge.gitState = new McpBridge.GitState(
                true,
                "/repo",
                "main",
                "origin/main",
                0,
                0,
                List.of(
                        new McpBridge.GitFileState("src/A.java", "M", ".", null),
                        new McpBridge.GitFileState("src/B.java", "R", ".", "src/Old.java")));
        String p = prompt("review_changes", "{\"focus\":\"error handling\"}");
        assertTrue(p.contains("repository at /repo (branch main)"), p);
        assertTrue(p.contains("- M. src/A.java"));
        assertTrue(p.contains("src/B.java (from src/Old.java)"));
        assertTrue(p.contains("add_review_comment") && p.contains("list_review_comments"));
        assertTrue(p.endsWith("Look most at: error handling."));
        bridge.gitState = new McpBridge.GitState(true, "/repo", null, null, 0, 0, List.of());
        assertTrue(prompt("review_changes", null).contains("no uncommitted changes"));
        bridge.gitState = new McpBridge.GitState(false, null, null, null, 0, 0, List.of());
        assertThrows(McpTools.PromptUnavailable.class, () -> prompt("review_changes", null));
    }

    @Test
    void fixOpenCommentsListsEachThreadAndHowToResolveIt() throws Exception {
        bridge.reviews = List.of(new McpBridge.ReviewThreadInfo(
                "t7",
                "/repo/A.java",
                3,
                4,
                true,
                "open",
                null,
                "important",
                "correctness",
                List.of(
                        new McpBridge.ReviewCommentInfo("c1", "agent", "Claude Code", "Off by one.", "i < n", 0),
                        new McpBridge.ReviewCommentInfo("c2", "user", "", "Please fix.", null, 1))));
        String p = prompt("fix_open_comments", "{\"path\":\"/repo/A.java\"}");
        assertEquals("/repo/A.java false", bridge.lastReviewArgs, "only that file's open threads");
        assertTrue(p.contains("## Thread t7: /repo/A.java, lines 3-4 (outdated"), p);
        assertTrue(p.contains("Severity: important (correctness)"));
        assertTrue(p.contains("Claude Code: Off by one."));
        assertTrue(p.contains("The user: Please fix."));
        assertTrue(p.contains("```\ni < n\n```"));
        assertTrue(p.contains("resolve_review_comment") && p.contains("reply_to_review_comment"));
        bridge.reviews = List.of();
        assertTrue(prompt("fix_open_comments", null).startsWith("There are no open review comments."));
        bridge.noRepository = true;
        assertThrows(McpTools.PromptUnavailable.class, () -> prompt("fix_open_comments", null));
    }

    @Test
    void explainSelectionQuotesTheSelectionOrTheCaretsLine() throws Exception {
        bridge.selection = new McpBridge.Selection("/repo/A.java", "A.java", 2, 1, 2, 1, 3, 5, "int a;\nint b;");
        String p = prompt("explain_selection", null);
        assertTrue(p.startsWith("Explain this code from /repo/A.java, lines 2-3:"), p);
        assertTrue(p.contains("```\nint a;\nint b;\n```"));
        bridge.selection = new McpBridge.Selection("/repo/A.java", "A.java", 2, 1, 2, 1, 2, 1, "");
        bridge.bufferText = "first\nsecond\nthird";
        assertTrue(prompt("explain_selection", null).contains(", line 2:\n\n```\nsecond\n```"));
        bridge.selection = null;
        assertThrows(McpTools.PromptUnavailable.class, () -> prompt("explain_selection", null));
        assertThrows(McpTools.PromptUnavailable.class, () -> prompt("no_such_prompt", null));
    }
}
