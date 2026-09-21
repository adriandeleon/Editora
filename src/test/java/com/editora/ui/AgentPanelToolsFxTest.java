package com.editora.ui;

import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class AgentPanelToolsFxTest {
    @Test
    void nativeProgressAndResultShareOneLazyEntryAndResetDoesNotReuseIt() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            var panel = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {});
            VBox transcript = FxTestSupport.field(panel, "transcriptBox");
            panel.startTool("read_file");
            var progress = (TitledPane) transcript.getChildren().getFirst();
            panel.appendToolResult("read_file", "observed text", false, 12);
            assertEquals(1, transcript.getChildren().size());
            assertSame(progress, transcript.getChildren().getFirst());
            assertNull(progress.getContent());
            assertTrue(progress.getText().contains("12 ms"));
            progress.setExpanded(true);
            assertEquals("observed text", ((TextArea) progress.getContent()).getText());
            panel.startTool("read_file");
            panel.clearTranscript();
            panel.appendToolResult("read_file", "permission denied", true, 0);
            assertEquals(1, transcript.getChildren().size());
            assertTrue(
                    ((TitledPane) transcript.getChildren().getFirst()).getText().startsWith("✗"));
        });
    }
}
