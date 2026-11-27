package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.ReviewAuthor;
import com.editora.config.ReviewThread;
import com.editora.diff.DiffEngine;
import com.editora.review.ReviewAnchors;
import com.editora.review.ReviewThreads;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Add to Agent Prompt": the agent panel's prompt gains each block after what is being written, a diff gives the
 * change at its cursor, and a thread's block has its lines where they are now.
 */
@Tag("fx")
class AddToAgentPromptFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void blocksGoAfterWhatIsBeingWrittenAndNothingIsSent() throws Exception {
        int[] sent = {0};
        AgentPanel panel = FxTestSupport.callOnFx(
                () -> new AgentPanel(() -> sent[0]++, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {}));
        FxTestSupport.runOnFx(() -> panel.addToPrompt("In A, line 1:\n```\nx\n```\n"));
        assertEquals("In A, line 1:\n```\nx\n```\n\n", FxTestSupport.callOnFx(panel::promptText));
        FxTestSupport.runOnFx(() -> panel.addToPrompt("second"));
        assertEquals("In A, line 1:\n```\nx\n```\n\nsecond\n\n", FxTestSupport.callOnFx(panel::promptText));
        assertEquals(0, sent[0]);
    }

    @Test
    void aDiffGivesTheChangeAtItsCursor() throws Exception {
        String left = "a\nb\nc\n";
        String right = "a\nB\nc\nd\n";
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "A", "L", "R", "a/A.txt", "b/A.txt", left, right, model, "Monospaced", 13, true, "A.txt"));
        String block = FxTestSupport.callOnFx(pane::currentChangePrompt);
        assertTrue(block.contains("-b\n+B\n"), block);
        FxTestSupport.runOnFx(pane::goNextChange);
        FxTestSupport.runOnFx(pane::goNextChange);
        String second = FxTestSupport.callOnFx(pane::currentChangePrompt);
        assertTrue(second.contains("+d"), second);

        var same = DiffEngine.compute(left, left, DiffEngine.DiffOptions.DEFAULT);
        DiffViewerPane none = FxTestSupport.callOnFx(
                () -> new DiffViewerPane("A", "L", "R", "A", "A", left, left, same, "Monospaced", 13, true, "A.txt"));
        assertNull(FxTestSupport.callOnFx(none::currentChangePrompt));
    }

    @Test
    void aThreadsBlockHasItsLinesWhereTheyAreNow() {
        ReviewThread t = new ReviewThreads(() -> 1L)
                .start(
                        List.of(),
                        ReviewThreads.Start.onFile("A.java", ReviewAnchors.capture("a\nb\nc\n", 1, 1)),
                        ReviewAuthor.user(),
                        "Why b?",
                        null)
                .get(0);
        String block = ReviewCoordinator.threadBlock(Path.of("/r/A.java"), t, "new\na\nb\nc\n");
        assertTrue(block.contains("/r/A.java, line 3"), block);
        assertTrue(block.contains("The user: Why b?") && block.endsWith("```\nb\n```\n"), block);
    }
}
