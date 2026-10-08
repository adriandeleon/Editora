package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open-files Switcher: which row it starts on, what each row and the footer say, and what Enter,
 * releasing Ctrl, Backspace / Delete / the keymap's delete-char chord and Esc do to the tabs behind it.
 */
@Tag("fx")
class SwitcherFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A switcher over three tabs: a saved file, an edited file, and a non-buffer tab (like Welcome). */
    private static final class Rig {
        final Path savedPath = Path.of("project", "src", "Saved.java").toAbsolutePath();
        final EditorBuffer saved = new EditorBuffer();
        final EditorBuffer edited = new EditorBuffer();
        final Tab savedTab = new Tab();
        final Tab editedTab = new Tab();
        final Tab welcomeTab = new Tab();
        final List<Tab> tabs = new ArrayList<>();
        final List<Tab> activated = new ArrayList<>();
        final List<Tab> closed = new ArrayList<>();
        Tab active = editedTab;
        final Switcher switcher = new Switcher(() -> tabs, () -> active, activated::add, closed::add);
        final ListView<Tab> list = FxTestSupport.field(switcher, "filesList");
        final Label footer = FxTestSupport.field(switcher, "pathLabel");
        final VBox root = FxTestSupport.field(switcher, "root");
        final Stage stage = new Stage();
        final OverlayHost host = new OverlayHost();

        Rig() {
            saved.setPath(savedPath);
            edited.setContent("one");
            edited.getArea().appendText(" two");
            savedTab.setUserData(saved);
            editedTab.setUserData(edited);
            welcomeTab.setUserData(new TabContent() {
                @Override
                public Node node() {
                    return new Label();
                }

                @Override
                public String title() {
                    return "Welcome";
                }
            });
            tabs.addAll(List.of(savedTab, editedTab, welcomeTab));
            StackPane pane = new StackPane();
            stage.setScene(new Scene(pane, 900, 700));
            stage.show();
            host.install(pane);
            switcher.setOverlayHost(host);
        }

        KeyEvent press(KeyCode code, boolean control) {
            KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false);
            FxTestSupport.invokeWith(switcher, "onKey", KeyEvent.class, e);
            return e;
        }

        ListCell<Tab> rendered(Tab item) {
            ListCell<Tab> cell = list.getCellFactory().call(list);
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {Object.class, boolean.class}, item, item == null);
            return cell;
        }

        void dispose() {
            stage.close();
            saved.dispose();
            edited.dispose();
        }
    }

    @Test
    void withoutAnOverlayHostNothingIsShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            Switcher bare = new Switcher(() -> r.tabs, () -> r.active, r.activated::add, r.closed::add);
            bare.show(r.stage, false);
            assertFalse(bare.isShown());
            bare.hide(); // nothing to hide
            assertFalse(r.host.isShowing());
            r.dispose();
        });
    }

    @Test
    void openingPreselectsTheCurrentTabAndTheFooterNamesItsPathOrTitle() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.switcher.show(r.stage, false);

            assertTrue(r.switcher.isShown());
            assertEquals(r.tabs, List.copyOf(r.list.getItems()), "tab order");
            assertSame(r.editedTab, r.list.getSelectionModel().getSelectedItem());
            assertEquals(r.edited.getTitle(), r.footer.getText(), "an unsaved buffer has no path: its title");

            assertTrue(r.press(KeyCode.UP, false).isConsumed());
            assertEquals(r.savedPath.toString(), r.footer.getText(), "a file shows its full path");
            r.press(KeyCode.UP, false);
            assertSame(r.welcomeTab, r.list.getSelectionModel().getSelectedItem(), "Up wraps to the last row");
            assertEquals("Welcome", r.footer.getText(), "a non-buffer tab shows its content's title");

            double rows = 3 * 26 + 2;
            assertEquals(rows, r.list.getPrefHeight(), "the list hugs the open-file count");
            assertTrue(r.root.getPrefWidth() >= 360, "never narrower than the minimum");
            assertTrue(r.root.getPrefWidth() <= r.stage.getWidth() - 80, "never wider than the window");
            assertEquals(r.root.getPrefWidth(), r.root.getMaxWidth(), "fixed for this showing");
            r.dispose();
        });
    }

    @Test
    void anActiveTabThatIsNotListedFallsBackToTheFirstRowAndNoTabsMeansAnEmptyFooter() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.active = new Tab();
            r.switcher.show(r.stage, false);
            assertSame(r.savedTab, r.list.getSelectionModel().getSelectedItem());
            r.switcher.hide();
            assertFalse(r.switcher.isShown());

            r.tabs.clear();
            r.switcher.show(r.stage, true);
            assertTrue(r.list.getItems().isEmpty());
            assertEquals(" ", r.footer.getText(), "the footer keeps its height with nothing to name");
            assertEquals(26 + 2, r.list.getPrefHeight(), "one row tall at the least");
            assertTrue(r.press(KeyCode.ENTER, false).isConsumed());
            assertTrue(r.activated.isEmpty(), "nothing to activate");
            assertFalse(r.switcher.isShown());
            r.dispose();
        });
    }

    @Test
    void theListStopsGrowingAtTwelveRows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            for (int i = 0; i < 20; i++) {
                r.tabs.add(new Tab());
            }
            r.switcher.show(r.stage, false);
            assertEquals(12 * 26 + 2, r.list.getPrefHeight());
            r.dispose();
        });
    }

    @Test
    void aRowShowsTheNameAnUnsavedMarkerAndAFileIconOnlyForBuffers() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();

            Label saved = (Label) r.rendered(r.savedTab).getGraphic();
            assertEquals("Saved.java", saved.getText());
            assertFalse(saved.getStyleClass().contains("dirty-name"));
            assertNotNull(saved.getGraphic(), "a file gets its type glyph");

            ListCell<Tab> cell = r.rendered(r.editedTab);
            Label edited = (Label) cell.getGraphic();
            assertEquals("• " + r.edited.getTitle(), edited.getText());
            assertTrue(edited.getStyleClass().contains("dirty-name"));

            // The same row recycled for a clean, non-buffer tab drops the marker, the style and the glyph.
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {Object.class, boolean.class}, r.welcomeTab, false);
            assertEquals("Welcome", edited.getText());
            assertFalse(edited.getStyleClass().contains("dirty-name"));
            assertNull(edited.getGraphic());

            Tab plain = new Tab();
            plain.setUserData("not a tab content");
            assertEquals("", ((Label) r.rendered(plain).getGraphic()).getText());

            FxTestSupport.call(cell, "updateItem", new Class<?>[] {Object.class, boolean.class}, null, true);
            assertNull(cell.getGraphic());
            assertNull(cell.getText());
            r.dispose();
        });
    }

    @Test
    void enterActivatesTheHighlightedTabAndEscapeActivatesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.switcher.show(r.stage, false);
            r.press(KeyCode.DOWN, false);

            assertTrue(r.press(KeyCode.ENTER, false).isConsumed());
            assertEquals(List.of(r.welcomeTab), r.activated);
            assertFalse(r.switcher.isShown());

            r.switcher.show(r.stage, false);
            assertTrue(r.press(KeyCode.ESCAPE, false).isConsumed());
            assertEquals(1, r.activated.size(), "Esc closes without switching");
            assertFalse(r.switcher.isShown());
            r.dispose();
        });
    }

    @Test
    void releasingControlActivatesTheHighlightedTabOnlyWhileShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            KeyEvent release = new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.CONTROL, false, false, false, false);

            javafx.event.Event.fireEvent(r.root, release);
            assertTrue(r.activated.isEmpty(), "a stray release before the switcher is up does nothing");

            r.switcher.show(r.stage, false);
            javafx.event.Event.fireEvent(
                    r.root, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.SHIFT, false, true, false, false));
            assertTrue(r.switcher.isShown(), "only Ctrl coming up commits");

            javafx.event.Event.fireEvent(r.root, release);
            assertEquals(List.of(r.editedTab), r.activated);
            assertFalse(r.switcher.isShown());
            r.dispose();
        });
    }

    @Test
    void backspaceAndDeleteCloseTheHighlightedFileAndUnboundKeysAreIgnored() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            KeymapManager before = TextInputKeymap.sharedKeymap();
            TextInputKeymap.setShared(null);
            try {
                r.switcher.show(r.stage, false);

                assertFalse(r.press(KeyCode.D, true).isConsumed(), "with no keymap C-d is not a close chord");
                assertFalse(r.press(KeyCode.CONTROL, true).isConsumed(), "a modifier alone is no chord at all");
                assertTrue(r.closed.isEmpty());

                assertTrue(r.press(KeyCode.BACK_SPACE, false).isConsumed());
                assertEquals(List.of(r.editedTab), r.closed);
                assertEquals(List.of(r.savedTab, r.welcomeTab), List.copyOf(r.list.getItems()));
                assertEquals(2 * 26 + 2, r.list.getPrefHeight(), "the list shrinks with it");

                r.list.getSelectionModel().select(r.welcomeTab);
                assertTrue(r.press(KeyCode.DELETE, false).isConsumed());
                assertEquals(List.of(r.editedTab, r.welcomeTab), r.closed);
                assertEquals(r.savedPath.toString(), r.footer.getText(), "the footer follows the new highlight");

                r.list.getSelectionModel().clearSelection();
                assertTrue(r.press(KeyCode.DELETE, false).isConsumed());
                assertEquals(2, r.closed.size(), "nothing highlighted, nothing closed");
                assertEquals(" ", r.footer.getText());
            } finally {
                TextInputKeymap.setShared(before);
                r.dispose();
            }
        });
    }

    @Test
    void theKeymapsDeleteCharChordClosesTheHighlightedFile() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            KeymapManager before = TextInputKeymap.sharedKeymap();
            KeymapManager emacs = new KeymapManager();
            emacs.loadNamed("emacs");
            TextInputKeymap.setShared(emacs);
            try {
                r.switcher.show(r.stage, false);
                String hint = FxTestSupport.<Label>field(r.switcher, "hint").getText();
                assertTrue(
                        hint.contains("⌫ / " + emacs.displayChord("edit.deleteChar")),
                        "the legend names both close keys: " + hint);

                assertTrue(r.press(KeyCode.D, true).isConsumed());
                assertEquals(List.of(r.editedTab), r.closed);

                assertTrue(r.press(KeyCode.N, true).isConsumed(), "the keymap's line-down chord moves the highlight");
                assertFalse(r.press(KeyCode.Q, false).isConsumed(), "a key bound to nothing here is left alone");
            } finally {
                TextInputKeymap.setShared(before);
                r.dispose();
            }
        });
    }
}
