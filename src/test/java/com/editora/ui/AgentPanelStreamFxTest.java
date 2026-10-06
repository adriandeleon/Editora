package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import com.editora.editor.MarkdownRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link AgentPanel} renders a reply while it streams in: promptly, without re-parsing the whole reply
 * for every render, and ending in exactly what one render of the finished text gives.
 */
@Tag("fx")
class AgentPanelStreamFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static AgentPanel panel() {
        return new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {});
    }

    /** The visible text under {@code node}, in order. */
    private static String textOf(Node node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString();
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof Text text) {
            out.append(text.getText());
        } else if (node instanceof Labeled labeled) {
            out.append(labeled.getText());
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    /** The style classes of each top-level block of the streaming/finished message, in order. */
    private static List<String> blockKinds(Node rendered) {
        List<String> kinds = new ArrayList<>();
        Pane blocks = (Pane) ((Parent) rendered).getChildrenUnmodifiable().get(0);
        for (Node block : blocks.getChildren()) {
            kinds.add(block.getClass().getSimpleName() + block.getStyleClass());
        }
        return kinds;
    }

    private static Node wholeRender(String markdown) {
        return MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseToDocument(markdown), null, null, MarkdownRenderer.ImagePolicy.DATA_ONLY);
    }

    private static VBox transcript(AgentPanel panel) {
        return FxTestSupport.field(panel, "transcriptBox");
    }

    private static Node message(AgentPanel panel) {
        VBox wrapper = (VBox) transcript(panel).getChildren().get(0);
        return wrapper.getChildren().get(0);
    }

    /** What the pacing timer does when it fires: bring the streaming message up to date. */
    private static void streamingRender(AgentPanel panel) {
        FxTestSupport.call(panel, "renderCurrentMarkdown", new Class<?>[] {boolean.class}, false);
    }

    private static final String REPLY = String.join(
            "\n",
            "# Plan",
            "",
            "First I will read the file, then **change** it.",
            "",
            "1. read `src/Main.java`",
            "2. edit it",
            "",
            "3. run the tests",
            "",
            "```java",
            "class Main {",
            "",
            "    int field;",
            "}",
            "```",
            "",
            "> a quoted remark",
            "",
            "> that continues",
            "",
            "| a | b |",
            "|---|---|",
            "| 1 | 2 |",
            "",
            "Done. See ~/notes/todo.md for the rest.",
            "");

    /** The first chunk used to show nothing until the stream paused for 150 ms. */
    @Test
    void theFirstChunkIsShownAtOnce() throws Exception {
        String shown = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = panel();
            panel.appendChunk("Hello");
            return textOf(transcript(panel));
        });

        assertEquals("Hello", shown);
    }

    /** Chunks arriving faster than the interval share renders instead of each costing (or postponing) one. */
    @Test
    void aBurstOfChunksIsNotRenderedChunkByChunk() throws Exception {
        int[] renders = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = panel();
            for (int i = 0; i < 500; i++) {
                panel.appendChunk("word" + i + " ");
            }
            int whileStreaming = panel.wholeRenderCount() + panel.tailRenderCount();
            panel.setBusy(false); // the turn ended: the rest is rendered now, not after a delay
            String shown = textOf(transcript(panel));
            return new int[] {whileStreaming, shown.contains("word499") ? 1 : 0};
        });

        assertEquals(1, renders[0], "500 chunks in one burst: the first is shown, the rest wait their turn");
        assertEquals(1, renders[1], "ending the turn shows everything received");
    }

    /**
     * Every streaming render used to parse the reply from its first character: the cost of a render grew
     * with the reply, and the cost of a reply with its square. Now a render parses what can still change.
     */
    @Test
    void aLongStreamingReplyIsNotReparsedFromTheStartOnEveryRender() throws Exception {
        int paragraphs = 200;
        String paragraph = "This is one paragraph of a long streamed reply, about a hundred characters in length.\n\n";
        int[] result = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = panel();
            for (int i = 0; i < paragraphs; i++) {
                panel.appendChunk(paragraph);
                streamingRender(panel);
            }
            return new int[] {panel.wholeRenderCount(), panel.tailRenderCount(), panel.tailCharsRendered()};
        });

        long reparsingEverything = (long) paragraph.length() * paragraphs * (paragraphs + 1) / 2;
        assertEquals(1, result[0], "only the first render parses the whole message");
        assertEquals(paragraphs, result[1]);
        assertTrue(
                result[2] <= 3L * paragraph.length() * paragraphs,
                "parsed " + result[2] + " characters; re-parsing everything each time is " + reparsingEverything);
    }

    /** Finished blocks keep their nodes: nothing above the tail is rebuilt, re-laid-out or re-styled. */
    @Test
    void settledBlocksKeepTheirNodesAcrossRenders() throws Exception {
        boolean[] same = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = panel();
            panel.appendChunk("First paragraph.\n\nSecond paragraph.\n\nThird, still\n");
            streamingRender(panel); // the first incremental pass settles the first two paragraphs
            Pane blocks = FxTestSupport.field(panel, "streamBlocks");
            Node first = blocks.getChildren().get(0);
            Node second = blocks.getChildren().get(1);
            Node tail = blocks.getChildren().get(2);

            panel.appendChunk("arriving.");
            streamingRender(panel);

            return new boolean[] {
                blocks.getChildren().get(0) == first,
                blocks.getChildren().get(1) == second,
                blocks.getChildren().get(2) == tail,
                blocks.getChildren().size() == 3
            };
        });

        assertTrue(same[0], "the first paragraph's node is reused");
        assertTrue(same[1], "the second paragraph's node is reused");
        assertFalse(same[2], "only the tail is rebuilt");
        assertTrue(same[3]);
    }

    /** Rendering in pieces must show what rendering in one piece shows — at every step of the stream. */
    @Test
    void aStreamedReplyLooksLikeTheSameTextRenderedWhole() throws Exception {
        List<String> mismatches = FxTestSupport.callOnFx(() -> {
            List<String> problems = new ArrayList<>();
            AgentPanel panel = panel();
            StringBuilder received = new StringBuilder();
            for (int i = 0; i < REPLY.length(); i += 7) {
                String chunk = REPLY.substring(i, Math.min(REPLY.length(), i + 7));
                received.append(chunk);
                panel.appendChunk(chunk);
                streamingRender(panel);
                Node whole = wholeRender(received.toString());
                if (!textOf(whole).equals(textOf(message(panel)))) {
                    problems.add("text after " + received.length() + " chars");
                }
                if (!blockKinds(whole).equals(blockKinds(message(panel)))) {
                    problems.add("blocks after " + received.length() + " chars: " + blockKinds(message(panel))
                            + " vs whole " + blockKinds(whole));
                }
            }
            return problems;
        });

        assertEquals(List.of(), mismatches);
    }

    /** The finished message is one whole render, whatever the stream was split into on the way. */
    @Test
    void theFinishedMessageIsRenderedWholeAndKeepsItsClickablePaths() throws Exception {
        Object[] result = FxTestSupport.callOnFx(() -> {
            List<String> opened = new ArrayList<>();
            AgentPanel panel = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, opened::add);
            for (int i = 0; i < REPLY.length(); i += 11) {
                panel.appendChunk(REPLY.substring(i, Math.min(REPLY.length(), i + 11)));
                streamingRender(panel);
            }
            int wholeBefore = panel.wholeRenderCount();
            panel.appendLine("next entry"); // finalizes the message
            Node finished = ((VBox) transcript(panel).getChildren().get(0))
                    .getChildren()
                    .get(0);
            boolean pathWired = hasClickablePath(finished);
            return new Object[] {
                panel.wholeRenderCount() - wholeBefore,
                textOf(finished).equals(textOf(wholeRender(REPLY))),
                blockKinds(finished).equals(blockKinds(wholeRender(REPLY))),
                pathWired,
                transcript(panel).getChildren().size()
            };
        });

        assertEquals(1, result[0], "one whole render on completion");
        assertTrue((Boolean) result[1]);
        assertTrue((Boolean) result[2]);
        assertTrue((Boolean) result[3], "inline-code paths stay clickable");
        assertEquals(2, result[4], "the message and the line after it");
    }

    private static boolean hasClickablePath(Node node) {
        if (node.getStyleClass().contains("agent-code-path")) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (hasClickablePath(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A second message starts clean: nothing of the first one's stream state leaks into it. */
    @Test
    void aNewMessageStartsItsOwnStream() throws Exception {
        String second = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = panel();
            panel.appendChunk("One.\n\nTwo.\n\nThree");
            streamingRender(panel);
            panel.appendLine("❯ next prompt");
            panel.appendChunk("Fresh reply");
            streamingRender(panel);
            VBox wrapper = (VBox) transcript(panel).getChildren().get(2);
            return textOf(wrapper);
        });

        assertEquals("Fresh reply", second);
    }
}
