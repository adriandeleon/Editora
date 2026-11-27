package com.editora.review;

import java.util.List;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewThread;
import com.editora.diff.DiffModels.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AgentPromptBlocks}: what "Add to Agent Prompt" writes for a selection, a thread and a diff change. */
class AgentPromptBlocksTest {

    @Test
    void aSelectionNamesItsFileAndLines() {
        assertEquals(
                "In /r/A.java, line 3:\n```\nint x;\n```\n", AgentPromptBlocks.selection("/r/A.java", 3, 3, "int x;"));
        assertTrue(AgentPromptBlocks.selection("/r/A.java", 2, 4, "a\nb\nc\n").startsWith("In /r/A.java, lines 2-4:"));
    }

    @Test
    void aFenceInsideTheTextDoesNotEndTheBlock() {
        String block = AgentPromptBlocks.selection("/r/README.md", 1, 3, "```java\nx\n```");
        assertTrue(block.contains("````\n```java\nx\n```\n````"), block);
    }

    @Test
    void aThreadGivesEachCommentByWhoWroteItAndItsLines() {
        ReviewThreads ops = new ReviewThreads(() -> 1L);
        var anchor = ReviewAnchors.capture("a\nb\nc\n", 1, 1);
        ReviewThread t = ops.start(
                        List.of(),
                        new ReviewThreads.Start(
                                "A.java", ReviewThread.Side.NEW, null, anchor, ReviewThread.Severity.NIT, "style"),
                        ReviewAuthor.agent("Claude Code"),
                        "Rename b.",
                        null)
                .get(0);
        String block = AgentPromptBlocks.thread("/r/A.java", 2, 2, t, "b");
        assertTrue(block.startsWith("A review finding on /r/A.java, line 2 (thread " + t.id() + ", open):\n"), block);
        assertTrue(block.contains("Claude Code: Rename b.\n"), block);
        assertTrue(block.endsWith("```\nb\n```\n"), block);
    }

    @Test
    void aChangeIsAUnifiedHunkWithItsFirstLines() {
        String block = AgentPromptBlocks.change(
                "a/A.java",
                "b/A.java",
                List.of(
                        Row.removed("old();", 4),
                        new Row(com.editora.diff.DiffModels.RowType.MODIFIED, "x = 1;", 5, "x = 2;", 4, null, null),
                        Row.added("more();", 5)));
        assertTrue(block.startsWith("A change in b/A.java (compared with a/A.java):\n```diff\n"), block);
        assertTrue(block.contains("@@ -4,2 +4,2 @@\n-old();\n-x = 1;\n+x = 2;\n+more();\n```"), block);
        assertNull(AgentPromptBlocks.change("a", "b", List.of(Row.equal("same", 1, 1))));
    }
}
