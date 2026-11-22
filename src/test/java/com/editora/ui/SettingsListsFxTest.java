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
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings pages that edit an ordered list: the toolbar, the tool windows and the shortcuts. */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class) // searches and filters answer before the call returns
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
            List<String> currentItems = FxTestSupport.field(rig.<Object>control("toolbarSettings"), "currentItems");
            List<String> availableItems = FxTestSupport.field(rig.<Object>control("toolbarSettings"), "availableItems");
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
            List<String> currentItems = FxTestSupport.field(rig.<Object>control("toolbarSettings"), "currentItems");
            assertEquals(List.of(), currentItems);
            SettingsRig.button(page, tr("settings.toolbar.addSeparator")).fire();
            SettingsRig.button(page, tr("settings.toolbar.restoreDefault")).fire();
            assertEquals(List.of(), rig.applied);
        });
    }

    // --- tool windows ----------------------------------------------------------------------------

    private ToolWindowsSettingsPane.Row twRow(String id) {
        ToolWindowsSettingsPane pane = rig.control("toolWindowSettings");
        return pane.row(id);
    }

    @Test
    void aToolWindowRowShowsHidesAndMovesTheWindowInTheRunningEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setNotesSupport(true);
            rig.show();
            rig.page("TOOL_WINDOWS");
            ToolWindowManager tools = rig.toolWindows;
            ToolWindow notes = twRow("notes").window();
            CheckBox show = twRow("notes").show();
            ComboBox<ToolWindow.Side> side = twRow("notes").side();
            Button up = twRow("notes").up();
            Button down = twRow("notes").down();
            assertEquals(tools.isVisible(notes), show.isSelected());
            assertEquals(tools.currentSide(notes), side.getValue());

            show.setSelected(false);
            assertFalse(tools.isVisible(notes));
            assertTrue(up.isDisable() && down.isDisable(), "a hidden window has no place in the stripe");
            assertFalse(side.isDisable(), "but its side can still be chosen");

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
            List<ToolWindow> peers = tools.stripeOrder(ToolWindow.Side.RIGHT);
            assertTrue(peers.size() > 1, "the right stripe has other windows");
            int at = peers.indexOf(notes);
            assertEquals(!tools.canMove(notes, -1), up.isDisable());
            assertEquals(!tools.canMove(notes, 1), down.isDisable());
            if (at > 0) {
                up.fire();
                assertEquals(at - 1, tools.stripeOrder(ToolWindow.Side.RIGHT).indexOf(notes));
                down.fire();
            } else {
                down.fire();
                assertEquals(at + 1, tools.stripeOrder(ToolWindow.Side.RIGHT).indexOf(notes));
                up.fire();
            }
            assertEquals(at, tools.stripeOrder(ToolWindow.Side.RIGHT).indexOf(notes));
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
            ToolWindow notes = twRow("notes").window();
            CheckBox show = twRow("notes").show();
            ComboBox<ToolWindow.Side> side = twRow("notes").side();
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

            record Gate(String feature, String id) {}
            for (Gate gate : List.of(
                    new Gate("notesCheck", "notes"),
                    new Gate("gitCheck", "commit"),
                    new Gate("lspCheck", "problems"),
                    new Gate("debugCheck", "debug"))) {
                CheckBox feature = rig.control(gate.feature());
                ToolWindowsSettingsPane.Row row = twRow(gate.id());
                CheckBox show = row.show();
                Label note = row.disabledNote();
                ComboBox<?> side = row.side();
                Button up = row.up();
                Button down = row.down();
                boolean shown = show.isSelected();
                assertFalse(show.isDisable(), gate.id());
                assertFalse(note.isVisible(), gate.id());

                feature.setSelected(false);
                assertTrue(show.isDisable(), gate.id());
                assertTrue(side.isDisable() && up.isDisable() && down.isDisable(), gate.id());
                assertTrue(note.isVisible() && note.isManaged(), gate.id() + " says why");
                assertEquals(shown, show.isSelected(), "the user's choice is kept for when the feature is back");

                feature.setSelected(true);
                assertFalse(show.isDisable(), gate.id());
                assertFalse(note.isVisible(), gate.id());
                assertFalse(side.isDisable(), gate.id());
            }

            // The Project row is different: with projects off the window is not shown at all.
            CheckBox projects = rig.control("projectsCheck");
            CheckBox projectShow = twRow("project").show();
            ComboBox<?> projectSide = twRow("project").side();
            Label projectNote = twRow("project").disabledNote();
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

    /** The command ids of the rows the shortcut list holds, in order: the list is virtualized, so not their nodes. */
    private List<String> rows() {
        return SettingsRig.shortcutIds(rig.window);
    }

    /** The row on screen for {@code id}, laid out as it is now. */
    private HBox row(String id) {
        return SettingsRig.shortcutRow(rig.window, id);
    }

    /** Whether the list shows command {@code id}'s row inside its own bounds right now. */
    private boolean onScreen(ListView<SettingsWindow.Shortcut> list, String id) {
        Map<String, HBox> shown = rig.control("shortcutRowsById");
        HBox row = shown.get(id);
        if (row == null || row.getScene() == null) {
            return false;
        }
        javafx.geometry.Bounds inList = list.sceneToLocal(row.localToScene(row.getBoundsInLocal()));
        return inList.getMinY() >= -1 && inList.getMaxY() <= list.getHeight() + 1;
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
            assertEquals("file.special", rows().get(0));
            assertNotNull(row("file.special"), "and it is the row on screen");
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
            assertEquals(3, SettingsRig.all(row("cmd.1"), Button.class).size()); // Record, Reset, Remove
            assertTrue(row("cmd.1").getStyleClass().contains("shortcut-row-selected"));
            HBox selected = row("cmd.1");
            click(selected); // a second click on the selected row leaves it as it is
            assertSame(selected, row("cmd.1"));

            click(row("cmd.3"));
            assertEquals(0, SettingsRig.all(row("cmd.1"), Button.class).size(), "one selected row at a time");
            ListView<SettingsWindow.Shortcut> list = SettingsRig.shortcutList(rig.window);
            assertTrue(list.isFocusTraversable(), "the list is the one Tab stop");
            assertEquals("cmd.3", list.getSelectionModel().getSelectedItem().id(), "on the selected row");
            assertFalse(row("cmd.3").isFocusTraversable());
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
            // The rows are the cells of a virtualized list: the keyboard is on the list, and the row the arrow
            // keys are on is the list's own selection.
            ListView<SettingsWindow.Shortcut> list = SettingsRig.shortcutList(rig.window);
            int last = list.getItems().size() - 1;
            java.util.function.IntSupplier at = () -> list.getSelectionModel().getSelectedIndex();

            list.requestFocus();
            list.getSelectionModel().select(0);
            assertSame(list, rig.stage().getScene().getFocusOwner());

            Event.fireEvent(list, key(KeyCode.UP));
            assertEquals(0, at.getAsInt(), "Up on the first row stays in the list");
            assertSame(list, rig.stage().getScene().getFocusOwner());

            Event.fireEvent(list, key(KeyCode.END));
            assertEquals(last, at.getAsInt());
            SettingsRig.layOut(list);
            assertTrue(onScreen(list, "cmd." + last), "the row the keyboard moved to is scrolled into view");
            assertFalse(onScreen(list, "cmd.0"), "which the first row no longer is");
            assertSame(list, rig.stage().getScene().getFocusOwner(), "the Tab stop is still the list");

            Event.fireEvent(list, key(KeyCode.DOWN));
            assertEquals(last, at.getAsInt(), "Down on the last row stays in the list");
            assertSame(list, rig.stage().getScene().getFocusOwner());

            Event.fireEvent(list, key(KeyCode.UP));
            assertEquals(last - 1, at.getAsInt());

            Event.fireEvent(list, key(KeyCode.HOME));
            assertEquals(0, at.getAsInt());
            SettingsRig.layOut(list);
            assertTrue(onScreen(list, "cmd.0"), "and back to the top");

            // A chord is not navigation: Ctrl+End belongs to whoever binds it.
            Event.fireEvent(list, ctrl(KeyCode.END));
            assertEquals(0, at.getAsInt());
            Event.fireEvent(list, key(KeyCode.A)); // nor is a letter
            assertEquals(0, at.getAsInt());
            assertSame(list, rig.stage().getScene().getFocusOwner());

            Node first = list;
            Event.fireEvent(first, key(KeyCode.SPACE)); // Space selects, like Enter
            assertTrue(row("cmd.0").getStyleClass().contains("shortcut-row-selected"));
        });
    }

    /**
     * PERF-08 (Pauta 6.0.1): the list builds a row for what is on screen, however many commands there are, and a
     * key typed in the filter builds the rows it leaves on screen, not one for every command that matches.
     */
    @Test
    void theShortcutListBuildsOnlyTheRowsOnScreenAndAFilterKeyDoesNotRebuildThemAll() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.window.setShortcutActions(keys(5_000));
            rig.show();
            rig.page("KEYMAPS");
            rig.stage().getScene().getRoot().applyCss();
            rig.stage().getScene().getRoot().layout();
            ListView<SettingsWindow.Shortcut> list = SettingsRig.shortcutList(rig.window);
            assertEquals(5_000, list.getItems().size(), "every command is a row of the list");
            int onScreen = list.lookupAll(".shortcut-row").size();
            assertTrue(onScreen > 0 && onScreen < 100, "rows with nodes: " + onScreen);
            assertEquals(
                    "Title of cmd.0", SettingsRig.texts(row("cmd.0")).get(0), "the first row is the first command");

            Object page = FxTestSupport.field(rig.window, "keymapsPage");
            TextField filter = rig.control("shortcutFilter");
            int before = FxTestSupport.<Integer>field(page, "shortcutRowsBuilt");
            for (String typed : List.of("c", "cm", "cmd", "cmd.", "cmd.4")) {
                filter.setText(typed);
                SettingsRig.layOut(list);
            }
            int built = FxTestSupport.<Integer>field(page, "shortcutRowsBuilt") - before;
            assertTrue(rows().size() > 1_000, "the filter still lists every match: " + rows().size());
            assertTrue(built > 0 && built < 5 * 100, "rows built by five keys in the filter: " + built);
            assertTrue(list.lookupAll(".shortcut-row").size() < 100);
        });
    }

    /** PERF-10: the arrow keys keep the row they are on in view, with no restyle of the list to find where it is. */
    @Test
    void walkingTheShortcutListWithTheArrowKeysKeepsTheRowInView() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.window.setShortcutActions(keys(450));
            rig.show();
            rig.page("KEYMAPS");
            rig.stage().getScene().getRoot().applyCss();
            rig.stage().getScene().getRoot().layout();
            ListView<SettingsWindow.Shortcut> list = SettingsRig.shortcutList(rig.window);
            list.requestFocus();
            list.getSelectionModel().select(0);
            for (int i = 1; i <= 300; i++) {
                Event.fireEvent(list, key(KeyCode.DOWN));
                list.layout(); // the layout a pulse does, and no more
                assertEquals(i, list.getSelectionModel().getSelectedIndex());
                assertTrue(onScreen(list, "cmd." + i), "Down to row " + i + " left it out of view");
            }
            for (int i = 299; i >= 250; i--) {
                Event.fireEvent(list, key(KeyCode.UP));
                list.layout();
                assertTrue(onScreen(list, "cmd." + i), "Up to row " + i + " left it out of view");
            }
            // Selecting a row far down (Enter) builds its buttons and leaves it where it is, in view.
            Event.fireEvent(list, key(KeyCode.ENTER));
            assertEquals(3, SettingsRig.all(row("cmd.250"), Button.class).size());
            assertTrue(onScreen(list, "cmd.250"));
            assertEquals(250, list.getSelectionModel().getSelectedIndex());
        });
    }

    /** The question before a snippet file's comments are lost by a save: only its Save answers yes. */
    @Test
    void theSnippetSaveQuestionIsAnsweredWithNoDialog() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Object pages = FxTestSupport.field(rig.window, "snippetPages");
            String message = SnippetsEditor.commentsLostMessage(java.nio.file.Path.of("go.json"));
            java.util.function.BooleanSupplier ask = () ->
                    (boolean) FxTestSupport.call(pages, "confirmSnippetSave", new Class<?>[] {String.class}, message);
            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.CANCEL_CLOSE)) {
                assertFalse(ask.getAsBoolean(), "Cancel does not save");
                Dialogs.Asked asked = answers.only();
                assertEquals(message, asked.content());
                assertEquals(
                        List.of(
                                new Dialogs.Answer(
                                        tr("confirm.action.save"), ButtonBar.ButtonData.OK_DONE, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, true, false)),
                        asked.answers(),
                        "Save is red and never the default; Cancel is");
                answers.nowClosing();
                assertFalse(ask.getAsBoolean(), "nor does closing the question");
                answers.nowPressing(tr("confirm.action.save"));
                assertTrue(ask.getAsBoolean());
            }
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
            // Answered through the hook, with no window: Cancel, the question closed, then its action.
            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.CANCEL_CLOSE)) {
                SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save")).fire();
                List<Dialogs.Asked> asked = answers.asked();
                assertEquals(1, asked.size());
                assertEquals(tr("dialog.shortcut.conflict.title"), asked.get(0).title());
                assertTrue(
                        asked.get(0).content().contains(typed + "  —  Title of cmd.1"),
                        asked.get(0).content());
                assertEquals(
                        List.of(
                                new Dialogs.Answer(
                                        tr("confirm.action.bind"), ButtonBar.ButtonData.OK_DONE, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, true, false)),
                        asked.get(0).answers(),
                        "Bind is red and never the default; Cancel is");
                assertEquals(List.of(), keys.log, "declined: nothing is rebound");
                assertEquals("C-j", keys.chords.get("cmd.0"));

                answers.nowClosing();
                record("cmd.0", ctrl(KeyCode.K));
                SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save")).fire();
                assertEquals(List.of(), keys.log, "the question closed: nothing is rebound");

                answers.clear();
                answers.nowPressing(ButtonBar.ButtonData.OK_DONE);
                record("cmd.0", ctrl(KeyCode.K));
                SettingsRig.button(row("cmd.0"), tr("settings.shortcuts.save")).fire();
                assertEquals(1, answers.asked().size());
            }
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
