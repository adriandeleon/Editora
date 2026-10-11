package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transcript is bounded in characters as well as in entries: per entry, and as a whole. */
@Tag("fx")
class AgentPanelBudgetFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static AgentPanel panel() {
        return new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, path -> {});
    }

    private static VBox transcript(AgentPanel panel) {
        return FxTestSupport.field(panel, "transcriptBox");
    }

    @Test
    void aLineLongerThanOneEntryKeepsItsHeadAndSaysWhatWasCut() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AgentPanel panel = panel();
            String huge = "r".repeat(AgentPanel.MAX_ENTRY_CHARS * 3);
            panel.appendLine(huge);
            panel.appendToolLine(huge);
            Labeled line = (Labeled) transcript(panel).getChildren().get(0);
            assertTrue(line.getText().startsWith("r".repeat(AgentPanel.MAX_ENTRY_CHARS)));
            assertTrue(line.getText().length() < AgentPanel.MAX_ENTRY_CHARS + 100, "cut to one entry's worth");
            assertTrue(
                    line.getText().endsWith(tr("output.charactersCut", AgentPanel.MAX_ENTRY_CHARS * 2)),
                    "the cut is noted");
            assertTrue(panel.transcriptChars() < 2L * AgentPanel.MAX_ENTRY_CHARS + 200);
        });
    }

    @Test
    void theOldestEntriesGoOnceTheTranscriptExceedsItsCharacterBudget() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AgentPanel panel = panel();
            String line = "l".repeat(99_000);
            for (int i = 0; i < 25; i++) { // 2.5 M characters over 25 entries, far below the entry cap
                panel.appendLine(i + ":" + line);
            }
            VBox transcript = transcript(panel);
            assertTrue(panel.transcriptChars() <= AgentPanel.MAX_TRANSCRIPT_CHARS, "" + panel.transcriptChars());
            assertEquals(10, transcript.getChildren().size());
            assertTrue(((Labeled) transcript.getChildren().get(0)).getText().startsWith("15:"), "oldest first");
            assertTrue(((Labeled) transcript.getChildren().get(9)).getText().startsWith("24:"));

            panel.clearTranscript();
            assertEquals(0, panel.transcriptChars());
        });
    }

    @Test
    void aStreamingReplyStopsAtOneEntryAndNotesTheRestWhenFinalized() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AgentPanel panel = panel();
            String chunk = "words and more words ".repeat(1000); // 21,000 chars
            for (int i = 0; i < 20; i++) { // 420,000 chars offered
                panel.appendChunk(chunk);
            }
            panel.appendLine("done"); // finalizes the reply
            VBox wrapper = (VBox) transcript(panel).getChildren().get(0);
            String shown = textOf(wrapper);
            assertTrue(shown.length() <= AgentPanel.MAX_ENTRY_CHARS + 100, "kept " + shown.length());
            assertTrue(shown.contains(tr("output.charactersCut", 20 * chunk.length() - AgentPanel.MAX_ENTRY_CHARS)));
            assertTrue(panel.transcriptChars() <= AgentPanel.MAX_ENTRY_CHARS + 100 + 4);
        });
    }

    private static String textOf(Node node) {
        StringBuilder out = new StringBuilder();
        collect(node, out);
        return out.toString();
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof javafx.scene.text.Text text) {
            out.append(text.getText());
        } else if (node instanceof Labeled labeled) {
            out.append(labeled.getText());
        } else if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
