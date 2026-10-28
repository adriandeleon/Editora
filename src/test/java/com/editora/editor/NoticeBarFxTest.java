package com.editora.editor;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A banner's buttons keep their labels however long the message is (XML review 2 C5): the XML install banner's
 * Install and Not now read "…" even in a 1700 px window, because the wrapping message took their room.
 */
@Tag("fx")
class NoticeBarFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @Test
    void theButtonsNeverShrinkAndTheMessageWrapsInstead() throws Exception {
        String longMessage = "The XML language server isn’t installed. ".repeat(8);
        double[] sizes = EditorFx.callFx(() -> {
            NoticeBar bar = new NoticeBar("Not now", "lsp-install-bar");
            bar.set(longMessage, "Install", () -> {}, () -> {});
            VBox root = new VBox(bar.node());
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 520, 200));
            stage.show();
            root.applyCss();
            root.layout();
            Button[] buttons = bar.buttonsForTest();
            Label message = (Label) bar.node().getChildren().get(0);
            double[] out = {
                buttons[0].getWidth(),
                buttons[0].prefWidth(-1),
                buttons[1].getWidth(),
                buttons[1].prefWidth(-1),
                message.getHeight(),
                message.getFont().getSize()
            };
            stage.hide();
            return out;
        });
        assertTrue(sizes[0] >= sizes[1] - 0.5, "Install keeps its width: " + sizes[0] + " < " + sizes[1]);
        assertTrue(sizes[2] >= sizes[3] - 0.5, "Not now keeps its width: " + sizes[2] + " < " + sizes[3]);
        assertTrue(sizes[4] > sizes[5] * 2, "the message wraps onto more lines: height " + sizes[4]);
    }
}
