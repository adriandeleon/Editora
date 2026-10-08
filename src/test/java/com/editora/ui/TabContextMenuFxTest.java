package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tab's right-click menu, as a real window builds it. */
@Tag("fx")
class TabContextMenuFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Print… and Export to PDF… are on the tab menu, and act on the tab that was right-clicked: the commands
     * work on the active buffer, so a background tab is brought forward first.
     *
     * <p>Only the export is fired — its Save dialog is replaced and cancelled. Print… is checked for presence
     * alone: firing it would ask the machine for a printer job.
     */
    @Test
    void printAndExportActOnTheTabThatWasRightClicked(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.txt"), "one\n");
        Path second = Files.writeString(dir.resolve("second.txt"), "two\n");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(first, 0, 0), new MainController.OpenTarget(second, 0, 0)),
                c -> {});
        try {
            List<String> suggested = new ArrayList<>();
            List<java.io.File> startedIn = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                c.exports.chooseDestination = chooser -> {
                    suggested.add(chooser.getInitialFileName());
                    startedIn.add(chooser.getInitialDirectory());
                    return null; // cancelled
                };
                EditorBuffer active = (EditorBuffer) FxTestSupport.call(c, "activeBuffer", new Class<?>[] {});
                EditorArea area = FxTestSupport.field(c, "editorArea");
                Tab background = null;
                EditorBuffer backgroundBuffer = null;
                for (Tab tab : area.tabs()) {
                    Object content = FxTestSupport.call(c, "bufferOf", new Class<?>[] {Tab.class}, tab);
                    if (content instanceof EditorBuffer b && b != active) {
                        background = tab;
                        backgroundBuffer = b;
                    }
                }
                assertTrue(background != null, "two files are open, one of them in the background");
                assertNotSame(active, backgroundBuffer);

                ContextMenu menu = background.getContextMenu();
                menu.getOnShowing().handle(null); // builds the lazy menu, as its first showing does
                List<String> labels =
                        menu.getItems().stream().map(MenuItem::getText).toList();
                assertTrue(labels.contains(tr("menu.print")), labels.toString());
                assertTrue(labels.contains(tr("menu.exportPdf")), labels.toString());

                menu.getItems().stream()
                        .filter(item -> tr("menu.exportPdf").equals(item.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire();
                String name = backgroundBuffer.getPath().getFileName().toString();
                assertEquals(List.of(name.replace(".txt", ".pdf")), suggested);
                assertEquals(List.of(dir.toFile()), startedIn, "the Save dialog opens beside the document");
                assertSame(
                        backgroundBuffer,
                        FxTestSupport.call(c, "activeBuffer", new Class<?>[] {}),
                        "the right-clicked tab is the one exported, so it is brought forward");
            });
        } finally {
            fx.dispose();
        }
    }
}
