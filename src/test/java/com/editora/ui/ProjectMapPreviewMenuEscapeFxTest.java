package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escape with a preview card's right-click menu open closes the menu, not the card. The open popup is handed
 * the key before the card's own filter and hides itself, so the card saw "no menu showing" and closed too.
 */
@Tag("fx")
class ProjectMapPreviewMenuEscapeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void escape(ProjectMapPreview card) {
        javafx.event.Event.fireEvent(
                card.editor(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
    }

    @Test
    void theFirstEscapeDismissesTheMenuAndOnlyTheSecondClosesTheCard(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "x");
        List<String> calls = new ArrayList<>();
        ProjectMapPreview[] made = new ProjectMapPreview[1];
        Stage[] shown = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            ProjectMapPreview card = new ProjectMapPreview(p -> {});
            made[0] = card;
            Stage stage = new Stage();
            shown[0] = stage;
            Pane host = new Pane(card);
            stage.setScene(new Scene(host, 1000, 700));
            stage.show();
            card.showFile(file, new ProjectMapPreview.Content("one\ntwo\n", false), null);
            card.setOnEscape(() -> calls.add("card closed"));
            host.applyCss();
            host.layout();
            ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");

            card.editor()
                    .getOnContextMenuRequested()
                    .handle(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 300, 300, false, null));
            assertTrue(menu.isShowing());

            escape(card);
            assertFalse(menu.isShowing(), "Escape dismissed the menu");
            assertEquals(List.of(), calls, "and left the card open");
        });
        // The next key press is a new event: with no menu up, Escape is the card's.
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            escape(made[0]);
            assertEquals(List.of("card closed"), calls);
            made[0].dispose();
            shown[0].close();
        });
    }
}
