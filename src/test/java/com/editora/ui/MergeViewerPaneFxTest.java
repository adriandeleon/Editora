package com.editora.ui;

import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.ThreeWayMerge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class MergeViewerPaneFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void choiceUpdatesEditableResultAndSaveAppliesItsExactText() throws Exception {
        var merge = ThreeWayMerge.merge(
                "before\r\nold\r\nafter\r\n", "before\r\nours\r\nafter\r\n", "before\r\ntheirs\r\nafter\r\n");
        AtomicReference<String> applied = new AtomicReference<>();
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", merge.file(), "Monospaced", 13, "\r\n", true, applied::set));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
        try {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            assertTrue(result.isEditable());
            assertTrue(result.getText().contains("||||||| common ancestor"));

            Button acceptOurs = button((Parent) pane.node(), "Accept Ours");
            FxTestSupport.runOnFx(acceptOurs::fire);
            assertEquals("before\nours\nafter\n", result.getText());

            FxTestSupport.runOnFx(() -> result.setText("before\r\ncustom\r\nafter\r\n"));
            Button save = button((Parent) pane.node(), "Save resolution");
            FxTestSupport.runOnFx(save::fire);
            assertEquals("before\r\ncustom\r\nafter\r\n", applied.get());
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
}
