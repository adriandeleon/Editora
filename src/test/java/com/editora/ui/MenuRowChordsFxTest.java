package com.editora.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.MenuChords;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A right-click menu row that runs a command shows that command's chord from the live keymap: the chord is
 * read again each time the menu opens, so a keymap switch shows on the next right-click, and a row whose
 * command has no chord shows none.
 */
@Tag("fx")
class MenuRowChordsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void reset() {
        MenuChords.setChords(Map::of);
    }

    private static @Nullable String chord(MenuItem item) {
        KeyCombination shown = item.getAccelerator();
        return shown == null ? null : shown.getDisplayText();
    }

    private static MenuItem row(List<MenuItem> items, String key) {
        return items.stream()
                .filter(i -> tr(key).equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + key + " in "
                        + items.stream().map(MenuItem::getText).toList()));
    }

    private static ContextMenu showingMenu() {
        return Window.getWindows().stream()
                .filter(w -> w instanceof ContextMenu && w.isShowing())
                .map(w -> (ContextMenu) w)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no right-click menu is showing"));
    }

    @Test
    void aConsolesMenuShowsTheKeymapsChordAndTheNewOneAfterAKeymapSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed("cua");
            MenuChords.setChords(keymap::displayChords);
            CodeArea console = new CodeArea("build output");
            ConsoleContextMenu.install(console);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(console), 300, 200));
            stage.show();
            try {
                ContextMenuEvent rightClick =
                        new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null);
                console.fireEvent(rightClick);
                ContextMenu menu = showingMenu();
                String cua = chord(row(menu.getItems(), "editmenu.copy"));
                assertNotNull(cua, "Copy is bound in the CUA keymap");
                assertEquals(keymap.displayChord("edit.copy"), cua);
                assertEquals(keymap.displayChord("edit.selectAll"), chord(row(menu.getItems(), "editmenu.selectAll")));
                menu.hide();

                keymap.loadNamed("emacs");
                console.fireEvent(rightClick);
                String emacs = chord(row(showingMenu().getItems(), "editmenu.copy"));
                assertEquals(keymap.displayChord("edit.copy"), emacs, "re-read as the menu opens");
                assertNotEquals(cua, emacs);
                showingMenu().hide();
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aCommitRowsMenuShowsAChordOnlyOnTheRowsACommandRuns() throws Exception {
        Map<String, String> chords = new HashMap<>();
        chords.put("git.stageSelected", "C-c s");
        GitPanel panel = FxTestSupport.callOnFx(() -> new GitPanel(inert(GitPanel.Actions.class)));
        GitStatus status = new GitStatus(true, "main", "", 0, 0, List.of(new FileEntry("edited.txt", '.', 'M', null)));
        FxTestSupport.runOnFx(() -> {
            MenuChords.setChords(() -> chords);
            panel.setStatus(status);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            TreeItem<Object> group = tree.getRoot().getChildren().get(0);
            Object fileRow = group.getChildren().get(0).getValue();
            ContextMenu menu =
                    (ContextMenu) FxTestSupport.call(panel, "buildMenu", new Class<?>[] {List.class}, List.of(fileRow));
            assertEquals("C-c s", chord(row(menu.getItems(), "gitpanel.menu.stage")), "Stage is Stage Selected");
            assertNull(chord(row(menu.getItems(), "gitpanel.menu.discard")), "no command discards the selection");

            // The binding is removed: the cell empties on the next showing, it never keeps a stale chord.
            chords.clear();
            MenuChords.refresh(menu.getItems());
            assertNull(chord(row(menu.getItems(), "gitpanel.menu.stage")));
        });
    }

    @Test
    void theKeymapsCopyChordCopiesAMapCardsSelection() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager before = TextInputKeymap.sharedKeymap();
            KeymapManager emacs = new KeymapManager();
            emacs.loadNamed("emacs");
            TextInputKeymap.setShared(emacs);
            try {
                ProjectMapPreview card = new ProjectMapPreview(ignored -> {});
                CodeArea text = card.editor();
                text.replaceText("alpha beta");
                text.selectRange(6, 10);
                // M-w is Copy in the Emacs keymap; RichTextFX's own bindings do not know it.
                text.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, false, true, false));
                assertEquals("beta", Clipboard.getSystemClipboard().getString());
            } finally {
                if (before != null) {
                    TextInputKeymap.setShared(before);
                }
            }
        });
    }

    /** An implementation of {@code type} whose methods do nothing and answer false, an empty list or null. */
    @SuppressWarnings("unchecked")
    private static <T> T inert(Class<T> type) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
                MenuRowChordsFxTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    Class<?> returns = method.getReturnType();
                    return returns == boolean.class ? Boolean.FALSE : returns == List.class ? List.of() : null;
                });
    }
}
