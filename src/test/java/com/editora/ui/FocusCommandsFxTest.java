package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;

import com.editora.editor.MenuChords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one walk a panel's command takes to the row or button it stands for ({@link FocusCommands}): from the focus
 * owner up, in a popup below its root, below what a window shows; a disabled, hidden or absent control refused on the
 * status line; a row of several handed to a picker (KBD-01, MENU-05 in Pauta 6.0.1). Each test passes its own windows.
 */
@Tag("fx")
class FocusCommandsFxTest {

    private static final String ID = "terminal.copy";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String notOffered(String id) {
        return tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command." + id)));
    }

    /** A shown stage holding {@code root}; the caller closes it. */
    private static Stage stage(javafx.scene.Parent root) {
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 300, 200));
        stage.show();
        root.applyCss();
        root.layout();
        return stage;
    }

    @Test
    void aBindingFromTheFocusOwnerUpRunsAndAFalseOneIsRefused() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            VBox owner = new VBox(field);
            Stage stage = stage(new StackPane(owner));
            try {
                field.requestFocus();
                AtomicInteger ran = new AtomicInteger();
                FocusCommands.bind(owner, ID, ran::incrementAndGet);
                List<String> status = new ArrayList<>();
                assertTrue(FocusCommands.run(ID, status::add, List.of(stage), List.of(), rows -> {}));
                assertEquals(1, ran.get());
                assertTrue(status.isEmpty());

                FocusCommands.bindIf(owner, ID, () -> false);
                assertFalse(FocusCommands.run(ID, status::add, List.of(stage), List.of(), rows -> {}));
                assertEquals(List.of(notOffered(ID)), status, "an action that did not apply is said");

                assertNull(FocusCommands.actionAt(field, "terminal.paste"), "nothing bound to another command");
                assertFalse(FocusCommands.run("terminal.paste", status::add, List.of(stage), List.of(), rows -> {}));
                assertEquals(notOffered("terminal.paste"), status.getLast(), "nothing offers it: said so");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aPressedButtonIsFiredOnlyWhileItIsShownAndEnabled() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AtomicInteger fired = new AtomicInteger();
            Button button = new Button("Copy");
            button.setOnAction(e -> fired.incrementAndGet());
            VBox owner = new VBox(button);
            FocusCommands.press(owner, ID, button);
            Stage stage = stage(new StackPane(owner));
            try {
                List<String> status = new ArrayList<>();
                // No focus inside: found below what the window shows.
                assertTrue(FocusCommands.run(ID, status::add, List.of(), List.of(owner), rows -> {}));
                assertEquals(1, fired.get());
                button.setDisable(true);
                assertFalse(FocusCommands.run(ID, status::add, List.of(), List.of(owner), rows -> {}));
                button.setDisable(false);
                button.setVisible(false);
                assertFalse(FocusCommands.run(ID, status::add, List.of(), List.of(owner), rows -> {}));
                assertEquals(1, fired.get(), "a hidden or disabled button is not pressed");
                assertEquals(2, status.size());
                assertTrue(FocusCommands.press(new Button(), ID).hasProperties(), "a button can be its own owner");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aMenusTaggedRowFiresAndADisabledOrHiddenOneIsRefused() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> fired = new ArrayList<>();
            MenuItem copy = FocusCommands.row(new MenuItem("Copy"), ID);
            copy.setOnAction(e -> fired.add("copy"));
            CheckMenuItem wrap = FocusCommands.row(new CheckMenuItem("Wrap"), "http.toggleResponseWrap");
            wrap.setOnAction(e -> fired.add("wrap " + wrap.isSelected()));
            MenuItem inside = FocusCommands.also(new MenuItem("Clear"), "terminal.clearScrollback");
            inside.setOnAction(e -> fired.add("clear"));
            Menu sub = new Menu("More", null, inside);
            ListView<String> list = new ListView<>();
            FocusCommands.menu(list, () -> List.of(copy, wrap, sub));
            Stage stage = stage(new StackPane(list));
            try {
                list.requestFocus();
                List<String> status = new ArrayList<>();
                List<Stage> windows = List.of(stage);
                assertTrue(FocusCommands.run(ID, status::add, windows, List.of(), rows -> {}));
                assertTrue(FocusCommands.run("http.toggleResponseWrap", status::add, windows, List.of(), r -> {}));
                assertTrue(FocusCommands.run("terminal.clearScrollback", status::add, windows, List.of(), r -> {}));
                assertEquals(List.of("copy", "wrap true", "clear"), fired, "a check row turns over as a click does");
                assertEquals("terminal.clearScrollback", FocusCommands.commandOf(inside));
                assertEquals(ID, FocusCommands.commandOf(copy));

                copy.setDisable(true);
                assertFalse(FocusCommands.run(ID, status::add, windows, List.of(), rows -> {}));
                sub.setDisable(true); // a row under a disabled submenu is as good as disabled
                assertFalse(FocusCommands.run("terminal.clearScrollback", status::add, windows, List.of(), r -> {}));
                assertEquals(3, fired.size());
                assertEquals(List.of(notOffered(ID), notOffered("terminal.clearScrollback")), status);
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aRowThatShowsAnotherCommandsChordStillAnswersItsOwn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MenuItem row = MenuChords.tag(new MenuItem("Copy"), "edit.copy");
            FocusCommands.row(row, ID);
            assertEquals("edit.copy", MenuChords.commandOf(row), "the chord shown is unchanged");
            assertEquals(ID, FocusCommands.commandOf(row));
            assertTrue(FocusCommands.rowIn(List.of(row), ID, false).action() != null);
        });
    }

    @Test
    void severalRowsOfOneCommandAreHandedToThePickerEnabledOnesOnly() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MenuItem high = FocusCommands.also(new MenuItem("High"), "todo.setSelectedPriority");
            MenuItem low = FocusCommands.also(new MenuItem("Low"), "todo.setSelectedPriority");
            MenuItem off = FocusCommands.also(new MenuItem("None"), "todo.setSelectedPriority");
            off.setDisable(true);
            List<String> fired = new ArrayList<>();
            low.setOnAction(e -> fired.add("low"));
            VBox owner = new VBox();
            FocusCommands.menu(owner, () -> List.of(new Menu("Priority", null, high, low, off)));
            List<List<MenuItem>> picked = new ArrayList<>();
            assertTrue(FocusCommands.run("todo.setSelectedPriority", s -> {}, List.of(), List.of(owner), picked::add));
            assertEquals(List.of(List.of(high, low)), picked);
            FocusCommands.choose(picked.getFirst().get(1));
            assertEquals(List.of("low"), fired, "what is picked fires as a click would");
        });
    }

    @Test
    void aRowOfManyAnswersOnlyWithTheKeyboardInIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            TextField a = new TextField("a");
            TextField b = new TextField("b");
            FocusCommands.bind(a, ID, first::incrementAndGet);
            FocusCommands.bind(b, ID, second::incrementAndGet);
            FocusCommands.focusOnly(a);
            FocusCommands.focusOnly(b);
            VBox rows = new VBox(a, b);
            Stage stage = stage(new StackPane(rows));
            try {
                List<String> status = new ArrayList<>();
                assertNull(FocusCommands.findBelow(rows, ID), "never the first row found below the window");
                b.requestFocus();
                assertTrue(FocusCommands.run(ID, status::add, List.of(stage), List.of(rows), r -> {}));
                assertEquals(0, first.get());
                assertEquals(1, second.get(), "the row with the keyboard");
                rows.setVisible(false);
                FocusCommands.bind(rows, "terminal.paste", first::incrementAndGet);
                assertNull(FocusCommands.findBelow(rows, "terminal.paste"), "a hidden part is passed over");
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aPopupThatTakesNoFocusIsLookedInBelowItsRoot() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage owner = stage(new StackPane(new TextField()));
            Popup popup = new Popup();
            AtomicInteger copied = new AtomicInteger();
            VBox card = new VBox(new Button("x"));
            FocusCommands.bind(card, ID, copied::incrementAndGet);
            popup.getContent().add(card);
            popup.show(owner);
            try {
                List<String> status = new ArrayList<>();
                assertTrue(FocusCommands.runWhereFocused(ID, status::add, List.of(owner, popup)));
                assertEquals(1, copied.get());
                assertFalse(
                        FocusCommands.runWhereFocused("terminal.paste", status::add, List.of(owner, popup)),
                        "nothing there offers it: the command goes on as it would elsewhere");
                FocusCommands.bindIf(card, "terminal.paste", () -> false);
                assertTrue(FocusCommands.runWhereFocused("terminal.paste", status::add, List.of(owner, popup)));
                assertEquals(List.of(notOffered("terminal.paste")), status, "offered but not now: said so");
                assertTrue(FocusCommands.run(ID, status::add, List.of(owner, popup), List.of(), r -> {}));
                assertEquals(2, copied.get());
            } finally {
                popup.hide();
                owner.close();
            }
        });
    }

    @Test
    void aContextMenuIsReadAfterItsShowingHandlerHasRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = new ListView<>();
            MenuItem copy = FocusCommands.row(new MenuItem("Copy"), ID);
            ContextMenu menu = new ContextMenu(copy);
            menu.setOnShowing(e -> copy.setDisable(list.getSelectionModel().isEmpty()));
            list.setContextMenu(menu);
            FocusCommands.shownMenu(list);
            List<String> status = new ArrayList<>();
            assertFalse(FocusCommands.run(ID, status::add, List.of(), List.of(list), r -> {}));
            list.getItems().add("row");
            list.getSelectionModel().select(0);
            AtomicInteger fired = new AtomicInteger();
            copy.setOnAction(e -> fired.incrementAndGet());
            assertTrue(FocusCommands.run(ID, status::add, List.of(), List.of(list), r -> {}));
            assertEquals(1, fired.get(), "enabled by the showing handler for the selection");
            list.setContextMenu(null);
            assertFalse(FocusCommands.run(ID, status::add, List.of(), List.of(list), r -> {}));
            assertEquals(2, status.size());
        });
    }

    @Test
    void aSearchFieldsClearIsItsCommandOnlyWhileTheFieldHasText() throws Exception {
        FxTestSupport.runOnFx(() -> {
            TextField field = new TextField();
            Button clear = ClearableField.clearButton(field);
            VBox box = new VBox(field, clear);
            Stage stage = stage(new StackPane(box));
            try {
                field.requestFocus();
                List<String> status = new ArrayList<>();
                assertFalse(
                        FocusCommands.run("edit.clearSearchField", status::add, List.of(stage), List.of(), r -> {}));
                field.setText("query");
                assertTrue(FocusCommands.run("edit.clearSearchField", status::add, List.of(stage), List.of(), r -> {}));
                assertEquals("", field.getText());
                assertEquals(1, status.size());
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void aControlsOwnMenuIsLookedInAndAWindowElsewhereIsBroughtForward() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = new ListView<>();
            FocusCommands.ownMenu(list);
            Stage other = stage(new StackPane(new TextField()));
            Stage stage = stage(new StackPane(list));
            try {
                list.requestFocus();
                List<String> status = new ArrayList<>();
                List<Stage> windows = List.of(other, stage);
                assertFalse(FocusCommands.run(ID, status::add, windows, List.of(), r -> {}), "no menu yet");
                AtomicInteger fired = new AtomicInteger();
                MenuItem copy = FocusCommands.row(new MenuItem("Copy"), ID);
                copy.setOnAction(e -> fired.incrementAndGet());
                list.setContextMenu(new ContextMenu(copy));
                assertTrue(FocusCommands.run(ID, status::add, windows, List.of(), r -> {}));
                assertEquals(1, fired.get(), "the row of the window the keyboard is in, wherever it is");
                assertFalse(
                        FocusCommands.runWhereFocused(ID, status::add, List.of(other)),
                        "a window without the row offers nothing");
                assertEquals(List.of(notOffered(ID)), status);
            } finally {
                stage.close();
                other.close();
            }
        });
    }

    @Test
    void whatABindingAnswersAndARadioRowIsChosenAsAClickChoosesIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            VBox owner = new VBox();
            AtomicInteger ran = new AtomicInteger();
            FocusCommands.bind(owner, ID, ran::incrementAndGet);
            FocusCommands.actionAt(owner, ID).run();
            FocusCommands.bindIf(owner, ID, () -> ran.incrementAndGet() > 0);
            FocusCommands.actionAt(owner, ID).run();
            assertEquals(2, ran.get(), "a Runnable and a BooleanSupplier both run");
            assertNull(FocusCommands.actionAt(owner, "terminal.paste"));
            MenuItem off = FocusCommands.row(new MenuItem("Off"), "terminal.paste");
            off.setDisable(true);
            FocusCommands.menu(owner, () -> List.of(off));
            assertNull(FocusCommands.actionAt(owner, "terminal.paste"), "a disabled row: nothing to run");

            javafx.scene.control.RadioMenuItem lone = new javafx.scene.control.RadioMenuItem("Lone");
            FocusCommands.choose(lone);
            assertTrue(lone.isSelected());
            FocusCommands.choose(lone);
            assertFalse(lone.isSelected(), "a radio row with no group turns over");
            javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
            lone.setToggleGroup(group);
            FocusCommands.choose(lone);
            FocusCommands.choose(lone);
            assertTrue(lone.isSelected(), "in a group it stays chosen");

            Stage bare = new Stage(); // a window with no scene offers nothing
            List<String> status = new ArrayList<>();
            assertFalse(FocusCommands.run(ID, status::add, List.of(bare), List.of(), r -> {}));
            assertFalse(FocusCommands.runWhereFocused(ID, status::add, List.of(bare)));
            assertEquals(List.of(notOffered(ID)), status);
        });
    }
}
