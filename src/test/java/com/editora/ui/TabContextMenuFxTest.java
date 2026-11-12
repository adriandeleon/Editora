package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Compare With… compares the file of the tab that was right-clicked. The row ran the command, which is about
     * the active file, so on a background tab it compared the wrong one.
     */
    @Test
    void compareWithComparesTheTabThatWasRightClicked(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.txt"), "one\n");
        Path second = Files.writeString(dir.resolve("second.txt"), "two\n");
        Path other = Files.writeString(dir.resolve("zeta.txt"), "three\n");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(first, 0, 0), new MainController.OpenTarget(second, 0, 0)),
                c -> {});
        try (AsyncTestScope async = new AsyncTestScope()) {
            String[] names = new String[2]; // the right-clicked tab's file, the active one's
            javafx.scene.Scene[] scene = new javafx.scene.Scene[1];
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
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
                names[0] = backgroundBuffer.getPath().getFileName().toString();
                names[1] = active.getPath().getFileName().toString();
                scene[0] = background.getTabPane().getScene();

                ContextMenu menu = background.getContextMenu();
                menu.getOnShowing().handle(null); // builds the lazy menu, as its first showing does
                menu.getItems().stream()
                        .filter(item -> tr("menu.compareWith").equals(item.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire();
                assertSame(
                        active,
                        FxTestSupport.call(c, "activeBuffer", new Class<?>[] {}),
                        "picking the other file does not need the tab brought forward");
            });
            // The file picker is up: name the other file in it and accept.
            SaveGuardsFxTest.awaitOnFx(async, "the Compare With picker", () -> pickerField(scene[0]) != null);
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.TextField field = pickerField(scene[0]);
                field.setText(other.toString());
                javafx.event.Event.fireEvent(
                        field,
                        new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                javafx.scene.input.KeyCode.ENTER,
                                false,
                                false,
                                false,
                                false));
            });
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            SaveGuardsFxTest.awaitOnFx(async, "the diff tab", () -> diffTitle(area) != null);
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        tr("diff.title.compare", names[0], "zeta.txt"),
                        diffTitle(area),
                        "the right-clicked tab's file is the one compared, not the active " + names[1]);
            });
        } finally {
            fx.dispose();
        }
    }

    /** The path field of the file picker shown over the window, or null while there is none. */
    private static javafx.scene.control.TextField pickerField(javafx.scene.Scene scene) {
        javafx.scene.Node card = scene.getRoot().lookup(".command-palette");
        return card == null ? null : (javafx.scene.control.TextField) card.lookup(".text-field");
    }

    /** The title of the window's diff tab, or null while there is none. */
    private static String diffTitle(EditorArea area) {
        return area.tabs().stream()
                .map(Tab::getUserData)
                .filter(DiffViewerPane.class::isInstance)
                .map(content -> ((DiffViewerPane) content).title())
                .findFirst()
                .orElse(null);
    }

    /**
     * Reveal in Map sits beside Reveal in File Manager and selects the tab's file on the Project Map, opening the
     * Map window for it (Map explore, position 18).
     */
    @Test
    void revealInMapSelectsTheTabsFileOnTheMap(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(file, 0, 0)),
                c -> {});
        try {
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                EditorArea area = FxTestSupport.field(c, "editorArea");
                Tab tab = area.tabs().stream()
                        .filter(t -> FxTestSupport.call(c, "bufferOf", new Class<?>[] {Tab.class}, t)
                                instanceof EditorBuffer)
                        .findFirst()
                        .orElseThrow();
                ContextMenu menu = tab.getContextMenu();
                menu.getOnShowing().handle(null);
                List<String> labels =
                        menu.getItems().stream().map(MenuItem::getText).toList();
                int at = labels.indexOf(tr("project.map.menu.revealInMap"));
                assertTrue(at >= 0, labels.toString());
                assertEquals(tr("menu.revealInFileManager"), labels.get(at + 1), "beside Reveal in File Manager");
                menu.getItems().get(at).fire();
            });
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            boolean shown = false;
            while (!shown && System.nanoTime() < deadline) {
                shown = FxTestSupport.callOnFx(() -> {
                    ProjectPanel panel = fx.controller.projectPanel();
                    ProjectMapView map = FxTestSupport.field(panel, "mapView");
                    javafx.scene.layout.Region surface = FxTestSupport.field(map, "surface");
                    Object selected = FxTestSupport.call(surface, "selectedPath", new Class<?>[0]);
                    // Same file, not same spelling: macOS's temp folder is /var, a link to /private/var.
                    return panel.isMapShowing()
                            && selected instanceof Path at
                            && java.nio.file.Files.isSameFile(at, file);
                });
                if (!shown) {
                    Thread.sleep(25);
                }
            }
            assertTrue(shown, "the Map window opened with the tab's file selected");
            FxTestSupport.runOnFx(() -> fx.controller.projectPanel().mapWindow().closeByUser());
        } finally {
            fx.dispose();
        }
    }

    /**
     * Simple UI mode trims the tab menu as it trims the menu bar — no Git, Local History, diff, terminal or
     * bulk-close entries, and no doubled separators where they were — and the same, already built menu comes
     * back in full when the mode is turned off.
     */
    @Test
    void simpleModeTrimsTheMenuAndTurningItOffRestoresIt(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\n");
        FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(dir.resolve("config")),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(file, 0, 0)),
                c -> {});
        try {
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                c.chrome.toggleSimpleMode();
                assertTrue(c.chrome.simpleModeActive());
                EditorArea area = FxTestSupport.field(c, "editorArea");
                Tab tab = area.tabs().stream()
                        .filter(t -> FxTestSupport.call(c, "bufferOf", new Class<?>[] {Tab.class}, t)
                                instanceof EditorBuffer)
                        .findFirst()
                        .orElseThrow();
                ContextMenu menu = tab.getContextMenu();
                menu.getOnShowing().handle(null); // builds the lazy menu, as its first showing does
                assertEquals(
                        List.of(
                                tr("menu.save"),
                                tr("menu.saveAs"),
                                tr("menu.encrypt"),
                                "-",
                                tr("menu.print"),
                                tr("menu.exportPdf"),
                                "-",
                                tr("menu.close"),
                                tr("menu.closeOthers"),
                                tr("menu.closeAll"),
                                "-",
                                tr("project.map.menu.revealInMap"), // as Reveal Active File, kept in Simple mode
                                tr("menu.revealInFileManager"),
                                tr("menu.copyPath"),
                                tr("menu.rename")),
                        visibleEntries(menu));

                c.chrome.toggleSimpleMode();
                assertFalse(c.chrome.simpleModeActive());
                menu.getOnShowing().handle(null); // a later showing of the same menu
                List<String> full = visibleEntries(menu);
                for (String key : List.of(
                        "menu.closeUnmodified",
                        "menu.closeLeft",
                        "menu.closeRight",
                        "project.menu.git",
                        "menu.compareWith",
                        "menu.openTerminal",
                        "menu.pin")) {
                    assertTrue(full.contains(tr(key)), key + " in " + full);
                }
            });
        } finally {
            fx.dispose();
        }
    }

    /** The menu as drawn: visible items by label, a visible separator as {@code "-"}. */
    private static List<String> visibleEntries(ContextMenu menu) {
        return menu.getItems().stream()
                .filter(MenuItem::isVisible)
                .map(item -> item instanceof SeparatorMenuItem ? "-" : item.getText())
                .toList();
    }
}
