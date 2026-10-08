package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.command.KeybindingEdits;
import com.editora.config.Settings;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings pages that edit an ordered list: the toolbar, the tool windows and the shortcuts. */
@Tag("fx")
class SettingsListsFxTest {

    private SettingsRig rig;
    private Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
        settings = rig.settings;
    }

    @AfterEach
    void tearDown() throws Exception {
        rig.close();
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    // --- toolbar ---------------------------------------------------------------------------------

    /** A toolbar backend that keeps what it is given, as the coordinator does after sanitizing. */
    private static final class Toolbar implements SettingsWindow.ToolbarActions {
        final List<String> shipped;
        List<String> layout;
        int applied;

        Toolbar(List<String> shipped) {
            this.shipped = List.copyOf(shipped);
            this.layout = new ArrayList<>(shipped);
        }

        @Override
        public List<String> current() {
            return List.copyOf(layout);
        }

        @Override
        public void apply(List<String> next) {
            applied++;
            layout = new ArrayList<>(next);
        }

        @Override
        public void restoreDefault() {
            layout = new ArrayList<>(shipped);
        }
    }

    @SuppressWarnings("unchecked")
    private ListView<String> listOf(Region page, List<String> items) {
        return SettingsRig.all(page, ListView.class).stream()
                .filter(v -> v.getItems() == items)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theToolbarPageAddsRemovesAndReordersItemsAndEachEditIsAppliedAtOnce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> ids =
                    ToolbarCatalog.items().stream().map(ToolbarCatalog.Item::id).toList();
            String a = ids.get(0);
            String b = ids.get(1);
            String c = ids.get(2);
            String extra = ids.get(3);
            Toolbar toolbar = new Toolbar(List.of(a, b, c));
            rig.window.setToolbarActions(toolbar);
            rig.show();
            Region page = rig.page("TOOLBAR");
            List<String> currentItems = rig.control("toolbarCurrentItems");
            List<String> availableItems = rig.control("toolbarAvailableItems");
            ListView<String> current = listOf(page, currentItems);
            ListView<String> available = listOf(page, availableItems);
            Button add = (Button) SettingsRig.button(page, tr("settings.toolbar.add"));
            Button remove = (Button) SettingsRig.button(page, tr("settings.toolbar.remove"));
            Button up = (Button) SettingsRig.button(page, "▲");
            Button down = (Button) SettingsRig.button(page, "▼");
            Button separator = (Button) SettingsRig.button(page, tr("settings.toolbar.addSeparator"));
            Button restore = (Button) SettingsRig.button(page, tr("settings.toolbar.restoreDefault"));

            assertEquals(List.of(a, b, c), currentItems);
            assertFalse(availableItems.contains(a), "what is on the bar is not offered again");
            assertTrue(availableItems.contains(extra));
            assertEquals(ids.size() - 3, availableItems.size());

            // Nothing selected: every button but the two that need no selection is a no-op.
            add.fire();
            remove.fire();
            up.fire();
            down.fire();
            assertEquals(0, toolbar.applied);

            available.getSelectionModel().select(extra);
            add.fire(); // no position chosen: it goes to the end
            assertEquals(List.of(a, b, c, extra), toolbar.layout);
            assertFalse(availableItems.contains(extra));

            current.getSelectionModel().select(0);
            separator.fire(); // after the selected item
            assertEquals(List.of(a, ToolbarCatalog.SEPARATOR, b, c, extra), toolbar.layout);

            current.getSelectionModel().select(3);
            up.fire();
            assertEquals(List.of(a, ToolbarCatalog.SEPARATOR, c, b, extra), toolbar.layout);
            assertEquals(2, current.getSelectionModel().getSelectedIndex(), "the moved item stays selected");
            down.fire();
            down.fire();
            assertEquals(List.of(a, ToolbarCatalog.SEPARATOR, b, extra, c), toolbar.layout);
            assertEquals(4, current.getSelectionModel().getSelectedIndex());
            int before = toolbar.applied;
            down.fire(); // already last
            current.getSelectionModel().select(0);
            up.fire(); // already first
            assertEquals(before, toolbar.applied);

            current.getSelectionModel().select(4);
            remove.fire();
            assertEquals(List.of(a, ToolbarCatalog.SEPARATOR, b, extra), toolbar.layout);
            assertEquals(3, current.getSelectionModel().getSelectedIndex(), "the selection stays on the list");
            assertTrue(availableItems.contains(c), "a removed item is offered again");

            current.getSelectionModel().select(1);
            available.getSelectionModel().select(c);
            add.fire(); // after the selected row
            assertEquals(List.of(a, ToolbarCatalog.SEPARATOR, c, b, extra), toolbar.layout);

            current.getSelectionModel().clearSelection();
            separator.fire(); // no position chosen: at the end
            assertEquals(ToolbarCatalog.SEPARATOR, toolbar.layout.getLast());

            restore.fire();
            assertEquals(List.of(a, b, c), toolbar.layout);
            assertEquals(List.of(a, b, c), currentItems);

            // The bar was rearranged by dragging on it while Settings was closed.
            rig.stage().hide();
            toolbar.layout = new ArrayList<>(List.of(c, a));
            rig.show();
            assertEquals(List.of(c, a), currentItems);
            assertTrue(availableItems.contains(b));
        });
    }

    @Test
    void theToolbarPageWithNoToolbarBehindItChangesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("TOOLBAR");
            List<String> currentItems = rig.control("toolbarCurrentItems");
            assertEquals(List.of(), currentItems);
            SettingsRig.button(page, tr("settings.toolbar.addSeparator")).fire();
            SettingsRig.button(page, tr("settings.toolbar.restoreDefault")).fire();
            assertEquals(List.of(), rig.applied);
        });
    }

    // --- tool windows ----------------------------------------------------------------------------

    @Test
    void aToolWindowRowShowsHidesAndMovesTheWindowInTheRunningEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            rig.show();
            rig.page("TOOL_WINDOWS");
            ToolWindowManager tools = rig.toolWindows;
            ToolWindow notes = rig.control("notesToolWindowRef");
            CheckBox show = rig.control("notesShowCheck");
            ComboBox<ToolWindow.Side> side = rig.control("notesSideCombo");
            Button up = rig.control("notesMoveUp");
            Button down = rig.control("notesMoveDown");
            assertEquals(tools.isVisible(notes), show.isSelected());
            assertEquals(tools.currentSide(notes), side.getValue());

            show.setSelected(false);
            assertFalse(tools.isVisible(notes));
            assertTrue(side.isDisable() && up.isDisable() && down.isDisable(), "a hidden window has no place");

            show.setSelected(true);
            assertTrue(tools.isVisible(notes));
            assertFalse(side.isDisable());

            ToolWindow.Side other =
                    tools.currentSide(notes) == ToolWindow.Side.BOTTOM ? ToolWindow.Side.RIGHT : ToolWindow.Side.BOTTOM;
            side.setValue(other);
            assertEquals(other, tools.currentSide(notes));
            side.setValue(null); // no selection is not a side
            assertEquals(other, tools.currentSide(notes));
            assertEquals(SettingsWindow.sideName(other), side.getConverter().toString(other));
            assertEquals(other, side.getConverter().fromString(SettingsWindow.sideName(other)));
            assertNull(side.getConverter().fromString("no such side"));
            assertEquals("", SettingsWindow.sideName(null));

            // Put it on a side with neighbours, so there is somewhere to move to.
            side.setValue(ToolWindow.Side.RIGHT);
            List<ToolWindow> peers = tools.orderedOnSide(ToolWindow.Side.RIGHT);
            assertTrue(peers.size() > 1, "the right stripe has other windows");
            int at = peers.indexOf(notes);
            assertEquals(!tools.canMove(notes, -1), up.isDisable());
            assertEquals(!tools.canMove(notes, 1), down.isDisable());
            if (at > 0) {
                up.fire();
                assertEquals(at - 1, tools.orderedOnSide(ToolWindow.Side.RIGHT).indexOf(notes));
                down.fire();
            } else {
                down.fire();
                assertEquals(at + 1, tools.orderedOnSide(ToolWindow.Side.RIGHT).indexOf(notes));
                up.fire();
            }
            assertEquals(at, tools.orderedOnSide(ToolWindow.Side.RIGHT).indexOf(notes));
            assertEquals(!tools.canMove(notes, -1), up.isDisable(), "the arrows follow the new position");
            assertEquals(!tools.canMove(notes, 1), down.isDisable());
        });
    }

    @Test
    void aToolWindowRowFollowsWhatWasDoneToTheWindowElsewhere() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            rig.show();
            ToolWindowManager tools = rig.toolWindows;
            ToolWindow notes = rig.control("notesToolWindowRef");
            CheckBox show = rig.control("notesShowCheck");
            ComboBox<ToolWindow.Side> side = rig.control("notesSideCombo");
            rig.stage().hide();

            tools.setVisible(notes, !show.isSelected());
            ToolWindow.Side moved =
                    side.getValue() == ToolWindow.Side.RIGHT ? ToolWindow.Side.BOTTOM : ToolWindow.Side.RIGHT;
            tools.setSide(notes, moved);
            boolean visible = tools.isVisible(notes);
            rig.show();

            assertEquals(visible, show.isSelected());
            assertEquals(moved, side.getValue());
            assertEquals(visible, tools.isVisible(notes), "showing the row did not change the window");
            assertEquals(moved, tools.currentSide(notes));
        });
    }

    @Test
    void aToolWindowWhoseFeatureIsOffHasItsRowDisabledWithTheReason() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            settings.setGitSupport(true);
            settings.setLspSupport(true);
            settings.setDebugSupport(true);
            settings.setProjectSupport(true);
            rig.show();
            rig.page("TOOL_WINDOWS");

            record Row(String feature, String check, String prefix) {}
            for (Row row : List.of(
                    new Row("notesCheck", "notesShowCheck", "notes"),
                    new Row("gitCheck", "commitShowCheck", "commit"),
                    new Row("lspCheck", "problemsShowCheck", "problems"),
                    new Row("debugCheck", "debugShowCheck", "debug"))) {
                CheckBox feature = rig.control(row.feature());
                CheckBox show = rig.control(row.check());
                Label note = rig.control(row.prefix() + "DisabledNote");
                ComboBox<?> side = rig.control(row.prefix() + "SideCombo");
                Button up = rig.control(row.prefix() + "MoveUp");
                Button down = rig.control(row.prefix() + "MoveDown");
                boolean shown = show.isSelected();
                assertFalse(show.isDisable(), row.prefix());
                assertFalse(note.isVisible(), row.prefix());

                feature.setSelected(false);
                assertTrue(show.isDisable(), row.prefix());
                assertTrue(side.isDisable() && up.isDisable() && down.isDisable(), row.prefix());
                assertTrue(note.isVisible() && note.isManaged(), row.prefix() + " says why");
                assertEquals(shown, show.isSelected(), "the user's choice is kept for when the feature is back");

                feature.setSelected(true);
                assertFalse(show.isDisable(), row.prefix());
                assertFalse(note.isVisible(), row.prefix());
                assertEquals(!shown, side.isDisable(), row.prefix());
            }

            // The Project row is different: with projects off the window is not shown at all.
            CheckBox projects = rig.control("projectsCheck");
            CheckBox projectShow = rig.control("projectShowCheck");
            ComboBox<?> projectSide = rig.control("projectSideCombo");
            Label projectNote = rig.control("projectDisabledNote");
            projects.setSelected(false);
            assertFalse(settings.isProjectSupport());
            assertTrue(projectShow.isDisable() && projectSide.isDisable());
            assertFalse(projectShow.isSelected());
            assertTrue(projectNote.isVisible());
            settings.setProjectSupport(true); // switched back on by the projects.toggle command
            rig.window.syncProjectsCheck();
            assertTrue(projects.isSelected());
            assertFalse(projectShow.isDisable());
            assertFalse(projectNote.isVisible());
        });
    }

    // --- shortcuts -------------------------------------------------------------------------------

    /** A keymap of the test's own: what is bound, what a rebind would collide with, and what was asked. */
    private static final class Keys implements SettingsWindow.ShortcutActions {
        final Map<String, String> chords = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();

        @Override
        public List<SettingsWindow.Shortcut> rows() {
            List<SettingsWindow.Shortcut> out = new ArrayList<>();
            chords.forEach((id, chord) -> out.add(new SettingsWindow.Shortcut(id, "Title of " + id, chord)));
            return out;
        }

        @Override
        public List<KeybindingEdits.Conflict> conflicts(String chordSeq, String commandId) {
            List<KeybindingEdits.Conflict> out = new ArrayList<>();
            chords.forEach((id, chord) -> {
                if (!id.equals(commandId) && chordSeq.equals(chord)) {
                    out.add(new KeybindingEdits.Conflict(chord, id, KeybindingEdits.ConflictKind.EXACT));
                }
            });
            if ("C-x".equals(chordSeq)) { // a prefix of a chord whose command has no row here
                out.add(new KeybindingEdits.Conflict(
                        "C-x C-s", "hidden.command", KeybindingEdits.ConflictKind.SHADOWS));
            }
            return out;
        }

        @Override
        public void rebind(String commandId, String chordSeq) {
            log.add("rebind " + commandId + " " + chordSeq);
            chords.replaceAll((id, chord) -> chordSeq.equals(chord) ? null : chord);
            chords.put(commandId, chordSeq);
        }

        @Override
        public void reset(String commandId) {
            log.add("reset " + commandId);
            chords.put(commandId, "default-" + commandId);
        }

        @Override
        public void resetAll() {
            log.add("resetAll");
        }
    }

    private Keys keys(int rows) {
        Keys keys = new Keys();
        for (int i = 0; i < rows; i++) {
            keys.chords.put("cmd." + i, i % 3 == 2 ? null : "C-" + i);
        }
        return keys;
    }

    private List<Node> rows() {
        VBox box = rig.control("shortcutListBox");
        return box.getChildren();
    }

    private HBox row(String id) {
        Map<String, HBox> byId = rig.control("shortcutRowsById");
        return byId.get(id);
    }

    private static void click(Node node) {
        Event.fireEvent(
                node,
                new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null));
    }

    private void record(String id, KeyEvent... typed) {
        click(row(id));
        SettingsRig.button(row(id), tr("settings.shortcuts.record")).fire();
        TextField capture = SettingsRig.all(row(id), TextField.class).get(0);
        for (KeyEvent e : typed) {
            Event.fireEvent(capture, e);
        }
    }

    private static KeyEvent ctrl(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false);
    }

    @Test
    void theShortcutListIsFilteredByTitleIdOrChord() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Keys keys = keys(6);
            keys.chords.put("file.special", "M-q");
            rig.window.setShortcutActions(keys);
            rig.show();
            rig.page("KEYMAPS");
            TextField filter = rig.control("shortcutFilter");
            assertEquals(7, rows().size());

            filter.setText("  SPECIAL ");
            assertEquals(1, rows().size(), "by id, whatever the case and the spaces around it");
            filter.setText("title of cmd.4");
            assertEquals(1, rows().size(), "by title");
            filter.setText("m-q");
            assertEquals(1, rows().size(), "by chord");
            assertSame(row("file.special"), rows().get(0));
            filter.setText("no command is called this");
            assertEquals(0, rows().size());
            filter.setText("");
            assertEquals(7, rows().size());

            // An unbound command says so instead of showing an empty chord.
            assertTrue(SettingsRig.texts(row("cmd.2")).contains(tr("settings.shortcuts.unbound")));
            assertTrue(SettingsRig.texts(row("cmd.1")).contains("C-1"));
        });
    }

    @Test
    void aClickSelectsARowAndOnlyTheSelectedRowOffersRecordAndReset() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Keys keys = keys(5);
            rig.window.setShortcutActions(keys);
            rig.show();
            rig.page("KEYMAPS");

            assertEquals(0, SettingsRig.all(row("cmd.1"), Button.class).size());
            click(row("cmd.1"));
            assertEquals(2, SettingsRig.all(row("cmd.1"), Button.class).size());
            assertTrue(row("cmd.1").getStyleClass().contains("shortcut-row-selected"));
            HBox selected = row("cmd.1");
            click(selected); // a second click on the selected row leaves it as it is
            assertSame(selected, row("cmd.1"));

            click(row("cmd.3"));
            assertEquals(0, SettingsRig.all(row("cmd.1"), Button.class).size(), "one selected row at a time");
            assertTrue(row("cmd.3").isFocusTraversable(), "the selected row is the list's one Tab stop");
            assertFalse(row("cmd.0").isFocusTraversable());

            SettingsRig.button(row("cmd.3"), tr("settings.shortcuts.reset")).fire();
            assertEquals(List.of("reset cmd.3"), keys.log);
            assertTrue(SettingsRig.texts(row("cmd.3")).contains("default-cmd.3"), "the row shows the chord now bound");
        });
    }

    @Test
    void theArrowKeysWalkTheRowsAndStopAtEitherEnd() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.window.setShortcutActions(keys(40));
            rig.show();
            rig.page("KEYMAPS");
            rig.stage().getScene().getRoot().applyCss();
            rig.stage().getScene().getRoot().layout();
            ScrollPane scroll = rig.control("shortcutScroll");
            List<Node> all = rows();
            Node first = all.get(0);
            Node last = all.get(all.size() - 1);

            first.requestFocus();
            assertSame(first, rig.stage().getScene().getFocusOwner());

            KeyEvent up = key(KeyCode.UP);
            Event.fireEvent(first, up);
            assertSame(first, rig.stage().getScene().getFocusOwner(), "Up on the first row stays in the list");

            Event.fireEvent(first, key(KeyCode.END));
            assertSame(last, rig.stage().getScene().getFocusOwner());
            assertTrue(scroll.getVvalue() > 0, "the row the keyboard moved to is scrolled into view");
            assertTrue(last.isFocusTraversable());
            assertFalse(first.isFocusTraversable(), "the Tab stop moved with the focus");

            Event.fireEvent(last, key(KeyCode.DOWN));
            assertSame(last, rig.stage().getScene().getFocusOwner(), "Down on the last row stays in the list");

            Event.fireEvent(last, key(KeyCode.UP));
            assertSame(all.get(all.size() - 2), rig.stage().getScene().getFocusOwner());

            Event.fireEvent(all.get(all.size() - 2), key(KeyCode.HOME));
            assertSame(first, rig.stage().getScene().getFocusOwner());
            assertEquals(0.0, scroll.getVvalue(), 1e-9, "and back to the top");

            // A chord is not navigation: Ctrl+End belongs to whoever binds it.
            Event.fireEvent(first, ctrl(KeyCode.END));
            assertSame(first, rig.stage().getScene().getFocusOwner());
            Event.fireEvent(first, key(KeyCode.A)); // nor is a letter
            assertSame(first, rig.stage().getScene().getFocusOwner());

            Event.fireEvent(first, key(KeyCode.SPACE)); // Space selects, like Enter
            assertTrue(row("cmd.0").getStyleClass().contains("shortcut-row-selected"));
        });
    }

    @Test
    void aRecordedChordIsBoundAndCancellingKeepsTheOldOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Keys keys = keys(4);
            rig.window.setShortcutActions(keys);
            rig.show();
            rig.page("KEYMAPS");

            record("cmd.0", ctrl(KeyCode.J));
            assertTrue(row("cmd.0").getStyleClass().contains("shortcut-row-recording"));
            SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.cancel")).fire();
            assertEquals(List.of(), keys.log, "Cancel binds nothing");
            assertFalse(row("cmd.0").getStyleClass().contains("shortcut-row-recording"));

            record("cmd.0"); // nothing typed
            SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save")).fire();
            assertEquals(List.of(), keys.log, "saving an empty recording binds nothing");

            record("cmd.0", ctrl(KeyCode.J));
            Event.fireEvent(SettingsRig.all(row("cmd.0"), TextField.class).get(0), key(KeyCode.ESCAPE));
            assertEquals(List.of(), keys.log, "Escape cancels the recording");
            assertFalse(row("cmd.0").getStyleClass().contains("shortcut-row-recording"));

            record("cmd.0", ctrl(KeyCode.J));
            String typed = SettingsRig.all(row("cmd.0"), TextField.class).get(0).getText();
            assertFalse(typed.isBlank());
            SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save")).fire();
            assertEquals(List.of("rebind cmd.0 " + typed), keys.log);
            assertTrue(SettingsRig.texts(row("cmd.0")).contains(typed));
        });
    }

    @Test
    void aChordAnotherCommandHasIsOnlyTakenAfterTheUserAgrees() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Keys keys = keys(4);
            keys.chords.put("cmd.0", "C-j");
            keys.chords.put("cmd.1", "C-k");
            rig.window.setShortcutActions(keys);
            rig.show();
            rig.page("KEYMAPS");

            record("cmd.0", ctrl(KeyCode.K));
            String typed = SettingsRig.all(row("cmd.0"), TextField.class).get(0).getText();
            keys.chords.put("cmd.1", typed); // whatever the platform spells Ctrl+K as, cmd.1 has it
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE,
                    () -> SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save"))
                            .fire());
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.shortcut.conflict.title"), asked.get(0).title());
            assertTrue(
                    asked.get(0).content().contains(typed + "  —  Title of cmd.1"),
                    asked.get(0).content());
            assertEquals(List.of(), keys.log, "declined: nothing is rebound");
            assertEquals("C-j", keys.chords.get("cmd.0"));

            record("cmd.0", ctrl(KeyCode.K));
            asked = SettingsRig.answering(
                    ButtonBar.ButtonData.OK_DONE,
                    () -> SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save"))
                            .fire());
            assertEquals(1, asked.size());
            assertEquals(List.of("rebind cmd.0 " + typed), keys.log);
            assertEquals(typed, keys.chords.get("cmd.0"));
            assertNull(keys.chords.get("cmd.1"), "the other command lost the chord");
        });
    }

    @Test
    void aConflictWithACommandThatHasNoRowIsNamedByItsId() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Keys keys = keys(2);
            rig.window.setShortcutActions(keys);
            rig.show();
            rig.page("KEYMAPS");
            record("cmd.0", ctrl(KeyCode.X));
            TextField capture = SettingsRig.all(row("cmd.0"), TextField.class).get(0);
            capture.setText("C-x");
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE,
                    () -> SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save"))
                            .fire());
            assertEquals(1, asked.size());
            assertTrue(
                    asked.get(0).content().contains("C-x C-s  —  hidden.command"),
                    asked.get(0).content());
        });
    }

    @Test
    void resetAllAsksFirstAndWithNoKeymapBehindThePageDoesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("KEYMAPS");
            Button resetAll = (Button) SettingsRig.button(page, tr("settings.shortcuts.resetAll"));
            assertEquals(List.of(), SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, resetAll::fire));
            assertEquals(0, rows().size());

            Keys keys = keys(3);
            rig.window.setShortcutActions(keys);
            assertEquals(3, rows().size(), "the list fills in once a keymap is behind it");
            assertEquals(
                    1,
                    SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, resetAll::fire)
                            .size());
            assertEquals(List.of(), keys.log);
            assertEquals(
                    1,
                    SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, resetAll::fire)
                            .size());
            assertEquals(List.of("resetAll"), keys.log);
        });
    }

    @Test
    void switchingTheKeymapIsSavedAndReloadsTheLiveKeymapOnce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            int[] reloads = {0};
            rig.window.setOnKeymapChanged(() -> reloads[0]++);
            rig.show();
            ComboBox<String> keymap = rig.control("keymapCombo");
            assertEquals(settings.getKeymap(), keymap.getValue());
            String other = keymap.getItems().stream()
                    .filter(id -> !id.equals(keymap.getValue()))
                    .findFirst()
                    .orElseThrow();

            keymap.setValue(other);
            assertEquals(other, settings.getKeymap());
            assertEquals(1, reloads[0]);
            assertEquals(
                    com.editora.command.KeymapManager.displayName(other),
                    keymap.getConverter().toString(other));
            assertEquals("", keymap.getConverter().toString(null));

            keymap.setValue(null);
            assertEquals(other, settings.getKeymap());
            assertEquals(1, reloads[0]);

            // Switched by the keymap.select command instead: the combo follows without reloading again.
            String back = keymap.getItems().stream()
                    .filter(id -> !id.equals(other))
                    .findFirst()
                    .orElseThrow();
            settings.setKeymap(back);
            rig.window.syncKeymap();
            assertEquals(back, keymap.getValue());
            assertEquals(1, reloads[0]);
        });
    }
}
