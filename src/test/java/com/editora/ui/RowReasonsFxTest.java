package com.editora.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Side;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import com.editora.editor.MenuChords;
import com.editora.editor.RowReasons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A right-click row that cannot run says why, as a menu bar row does (KBD-04 in Pauta 6.0.1): it stays, grayed as a
 * disabled row is, its tooltip and its spoken text say why, the arrow keys reach it, and choosing it says why in the
 * status line. Above all it never runs: not on a click, Enter, Space, an accelerator, {@code fire()} or a command
 * that fires the row; a check row does not turn over and a submenu does not open.
 */
@Tag("fx")
class RowReasonsFxTest {

    private static final String WHY = "palette.disabled.needsSelection";

    private Stage stage;
    private Button owner;
    private final List<String> said = new ArrayList<>();
    private final List<ContextMenu> menus = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            menus.forEach(ContextMenu::hide);
            if (stage != null) {
                stage.close();
            }
        });
    }

    /** A window with a button to open menus from, whose status line is {@link #said}. */
    private void window() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stage = new Stage();
            owner = new Button("owner");
            stage.setScene(new Scene(new StackPane(owner), 500, 500));
            RowReasons.sayIn(stage, said::add);
            stage.show();
            stage.requestFocus();
        });
    }

    private ContextMenu show(MenuItem... items) throws Exception {
        ContextMenu menu = FxTestSupport.callOnFx(() -> {
            ContextMenu m = new ContextMenu(items);
            menus.add(m);
            m.show(owner, Side.BOTTOM, 0, 0);
            return m;
        });
        settle();
        return menu;
    }

    /** Lets the menu just shown lay out: its skin and rows are made in the pulse that follows. */
    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        PulseChecks.pulses(1);
    }

    private static MenuItem counting(MenuItem item, AtomicInteger runs) {
        item.setOnAction(e -> runs.incrementAndGet());
        return item;
    }

    private static void release(Node row) {
        Event.fireEvent(
                row,
                new MouseEvent(
                        MouseEvent.MOUSE_RELEASED,
                        1,
                        1,
                        1,
                        1,
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
                        true,
                        null));
    }

    private static void key(ContextMenu menu, KeyCode code) {
        Node focus = menu.getScene().getFocusOwner();
        Node target = focus != null ? focus : menu.getSkin().getNode();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static Node node(MenuItem item) throws Exception {
        Node node = FxTestSupport.callOnFx(() -> RowReasons.rowNode(item));
        assertNotNull(node, "the row is drawn while its menu shows");
        return node;
    }

    @Test
    void anUnavailableRowStaysGrayedAndSaysWhyInItsTooltipAndToAScreenReader() throws Exception {
        window();
        MenuItem cut = new MenuItem("Cut");
        MenuItem plain = new MenuItem("Plain");
        FxTestSupport.runOnFx(() -> {
            RowReasons.because(cut, WHY);
            plain.setDisable(true); // drawn as a row disabled the old way, to compare the look with
        });
        show(new MenuItem("First"), cut, plain);
        Node row = node(cut);
        Node old = node(plain);
        FxTestSupport.runOnFx(() -> {
            assertTrue(cut.isVisible(), "not hidden");
            assertTrue(RowReasons.showsUnavailable(cut));
            assertEquals(tr(WHY), RowReasons.reasonOf(cut));
            Tooltip tip = RowReasons.tooltipOf(cut);
            assertNotNull(tip, "its tooltip says why");
            assertEquals(tr(WHY), tip.getText());
            assertEquals(tr(WHY), row.getAccessibleHelp());
            Label title = (Label) row.lookup(".label");
            assertEquals("Cut. " + tr(WHY), title.getAccessibleText(), "a screen reader hears the title, then why");
            assertTrue(String.valueOf(row.queryAccessibleAttribute(AccessibleAttribute.TEXT))
                    .contains(tr(WHY)));
            assertEquals(old.getOpacity(), row.getOpacity(), "grayed exactly as a disabled row is");
            assertTrue(row.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("disabled")));
            assertTrue(old.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("disabled")));
            assertFalse(row.isDisabled(), "the row's node still takes the pointer and the keyboard");
            assertNull(RowReasons.tooltipOf(plain), "a row disabled without a reason says nothing");
        });
    }

    @Test
    void thePointerRestingOnTheRowShowsItsTooltip() throws Exception {
        window();
        MenuItem cut = new MenuItem("Cut with a reason");
        FxTestSupport.runOnFx(() -> RowReasons.because(cut, WHY));
        show(new MenuItem("First"), cut);
        Node row = node(cut);
        Bounds at = FxTestSupport.callOnFx(() -> row.localToScreen(row.getBoundsInLocal()));
        FxTestSupport.runOnFx(() -> new Robot().mouseMove(at.getCenterX(), at.getCenterY()));
        boolean showing = PulseChecks.holdsOnFxWithin(
                () -> {
                    Tooltip tip = RowReasons.tooltipOf(cut);
                    return tip != null && tip.isShowing();
                },
                Duration.ofSeconds(4));
        assertTrue(showing, "a grayed row's node gets the pointer, so its tooltip shows");
        FxTestSupport.runOnFx(() -> new Robot().mouseMove(0, 0));
    }

    @Test
    void theArrowKeysReachTheRowAndEnterSaysWhyInsteadOfRunning() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem first = counting(new MenuItem("First"), runs);
        MenuItem cut = counting(new MenuItem("Cut"), runs);
        FxTestSupport.runOnFx(() -> RowReasons.because(cut, WHY));
        ContextMenu menu = show(first, cut);
        Node row = node(cut);
        // From wherever the menu put the keyboard, Down walks the rows and stops on the grayed one too.
        boolean reached = false;
        for (int i = 0; i < 3 && !reached; i++) {
            FxTestSupport.runOnFx(() -> key(menu, KeyCode.DOWN));
            reached = FxTestSupport.callOnFx(() -> menu.getScene().getFocusOwner() == row);
        }
        settle();
        assertTrue(reached, "the arrow keys land on the grayed row");
        FxTestSupport.runOnFx(() -> key(menu, KeyCode.ENTER));
        settle();
        assertEquals(0, runs.get(), "Enter on it runs nothing");
        assertEquals(List.of(tr("status.commandUnavailable", "Cut", tr(WHY))), said, "and says why");
        assertFalse(FxTestSupport.callOnFx(menu::isShowing), "the menu closes, as the menu bar's does");
    }

    @Test
    void spaceAndAClickSayWhyAndRunNothing() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem cut = counting(new MenuItem("Cut"), runs);
        FxTestSupport.runOnFx(() -> RowReasons.because(cut, WHY));
        ContextMenu menu = show(new MenuItem("First"), cut);
        Node row = node(cut);
        FxTestSupport.runOnFx(() -> {
            row.requestFocus();
            key(menu, KeyCode.SPACE);
        });
        settle();
        assertEquals(0, runs.get());
        assertEquals(1, said.size());
        ContextMenu reopened = show(new MenuItem("First"), cut);
        Node again = node(cut);
        FxTestSupport.runOnFx(() -> release(again));
        settle();
        assertEquals(0, runs.get(), "a click runs nothing");
        assertEquals(2, said.size(), "and says why");
        assertFalse(FxTestSupport.callOnFx(reopened::isShowing));
    }

    @Test
    void itNeverRunsOnFireOnAnAcceleratorOrFromACommandThatFiresTheRow() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger handled = new AtomicInteger();
        MenuItem delete = counting(new MenuItem("Delete"), runs);
        delete.addEventHandler(javafx.event.ActionEvent.ACTION, e -> handled.incrementAndGet());
        KeyCombination chord = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        FxTestSupport.runOnFx(() -> {
            delete.setAccelerator(chord);
            ContextMenu menu = new ContextMenu(delete);
            menus.add(menu);
            owner.setContextMenu(menu); // its accelerator goes into the scene, as a control's menu's does
            MenuChords.tag(delete, "x.delete");
            FocusCommands.ownMenu(owner);

            RowReasons.when(delete, true, WHY);
            owner.getScene().getAccelerators().get(chord).run();
            assertEquals(1, runs.get(), "the accelerator is live: an available row runs on it");

            RowReasons.when(delete, false, WHY);
            owner.getScene().getAccelerators().get(chord).run();
            delete.fire();
            assertFalse(FocusCommands.choose(delete), "a command choosing the row is refused");
            owner.requestFocus();
            FocusCommands.Found found = FocusCommands.find(owner, "x.delete");
            assertNotNull(found);
            assertNull(found.action(), "a command finds the row unavailable");
            assertEquals(tr(WHY), found.reason());
            assertEquals(1, runs.get(), "the accelerator, fire() and the command ran nothing");
            assertEquals(1, handled.get(), "nor any other handler of the row");
        });
    }

    @Test
    void aCommandThatFindsTheRowSaysItsReasonOnTheStatusLine() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem copy = counting(new MenuItem("Copy"), runs);
        List<String> status = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = new ContextMenu(MenuChords.tag(copy, "edit.copy"));
            menus.add(menu);
            owner.setContextMenu(menu);
            FocusCommands.ownMenu(owner);
            RowReasons.because(copy, WHY);
            owner.requestFocus();
            FocusCommands.run("edit.copy", status::add, List.of(stage), List.of(), rows -> {});
        });
        assertEquals(0, runs.get());
        assertEquals(
                List.of(tr("status.commandUnavailable", DebugPanelText.bareTitle(tr("command.edit.copy")), tr(WHY))),
                status);
        // Nothing offers it at all: the generic sentence stays for that.
        status.clear();
        FxTestSupport.runOnFx(() -> {
            owner.setContextMenu(null);
            FocusCommands.run("edit.copy", status::add, List.of(stage), List.of(), rows -> {});
        });
        assertEquals(List.of(tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command.edit.copy")))), status);
    }

    @Test
    void aCheckOrRadioRowThatCannotRunNeverTurnsOver() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        CheckMenuItem check = (CheckMenuItem) counting(new CheckMenuItem("Wrap"), runs);
        RadioMenuItem radio = (RadioMenuItem) counting(new RadioMenuItem("Two"), runs);
        ToggleGroup group = new ToggleGroup();
        RadioMenuItem one = new RadioMenuItem("One");
        one.setToggleGroup(group);
        radio.setToggleGroup(group);
        List<Boolean> flips = new ArrayList<>();
        check.selectedProperty().addListener((o, a, b) -> flips.add(b));
        radio.selectedProperty().addListener((o, a, b) -> flips.add(b));
        FxTestSupport.runOnFx(() -> {
            one.setSelected(true);
            RowReasons.because(check, WHY);
            RowReasons.because(radio, WHY);
        });
        show(one, check, radio);
        FxTestSupport.runOnFx(() -> release(check.getStyleableNode()));
        settle();
        ContextMenu shown = show(one, check, radio);
        FxTestSupport.runOnFx(() -> {
            radio.getStyleableNode().requestFocus();
            key(shown, KeyCode.ENTER);
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            assertFalse(FocusCommands.choose(check));
            assertFalse(FocusCommands.choose(radio));
            check.fire();
            radio.fire();
        });
        assertEquals(List.of(), flips, "neither row turned over");
        assertEquals(0, runs.get());
        assertEquals(2, said.size(), "each said why when chosen");
        assertTrue(one.isSelected());
    }

    @Test
    void aSubmenuThatCannotRunDoesNotOpenAndItsRowsDoNotRun() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem inside = counting(new MenuItem("Revert"), runs);
        Menu git = new Menu("Git", null, inside);
        FxTestSupport.runOnFx(() -> {
            MenuChords.tag(inside, "x.revert");
            RowReasons.because(git, WHY);
        });
        ContextMenu menu = show(new MenuItem("First"), git);
        Node row = node(git);
        FxTestSupport.runOnFx(() -> {
            row.requestFocus();
            key(menu, KeyCode.RIGHT);
            git.show();
        });
        settle();
        assertFalse(FxTestSupport.callOnFx(git::isShowing), "it does not open");
        FxTestSupport.runOnFx(() -> {
            inside.fire(); // the submenu's filter refuses what passes through it
            FocusCommands.Found found = FocusCommands.rowIn(List.of(git), "x.revert", false);
            assertNotNull(found);
            assertNull(found.action(), "a row under it is out of reach");
            assertEquals(tr(WHY), found.reason(), "and says the submenu's reason");
            key(menu, KeyCode.ENTER);
        });
        settle();
        assertEquals(0, runs.get());
        assertEquals(List.of(tr("status.commandUnavailable", "Git", tr(WHY))), said, "Enter on it says why");
    }

    @Test
    void aGrayedRowInsideAnOpenSubmenuSaysWhyToo() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem revert = counting(new MenuItem("Revert"), runs);
        Menu git = new Menu("Git", null, new MenuItem("Stage"), revert);
        FxTestSupport.runOnFx(() -> RowReasons.because(revert, WHY));
        ContextMenu menu = show(new MenuItem("First"), git);
        Node gitRow = node(git);
        boolean onGit = false;
        for (int i = 0; i < 3 && !onGit; i++) {
            FxTestSupport.runOnFx(() -> key(menu, KeyCode.DOWN));
            onGit = FxTestSupport.callOnFx(() -> menu.getScene().getFocusOwner() == gitRow);
        }
        assertTrue(onGit);
        FxTestSupport.runOnFx(() -> key(menu, KeyCode.RIGHT)); // the keyboard opens the submenu, as a user does
        settle();
        Node row = node(revert);
        FxTestSupport.runOnFx(() -> {
            assertTrue(git.isShowing(), "the submenu opened");
            assertNotNull(RowReasons.tooltipOf(revert), "its grayed row carries the reason");
            assertEquals(tr(WHY), row.getAccessibleHelp());
            release(row);
        });
        settle();
        assertEquals(0, runs.get());
        assertEquals(List.of(tr("status.commandUnavailable", "Revert", tr(WHY))), said);
    }

    @Test
    void anAvailableRowRunsAsItDidAndSaysNothing() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem cut = counting(new MenuItem("Cut"), runs);
        FxTestSupport.runOnFx(() -> {
            RowReasons.because(cut, WHY);
            RowReasons.because(cut, null);
        });
        show(cut);
        Node row = node(cut);
        FxTestSupport.runOnFx(() -> {
            assertFalse(cut.isDisable());
            assertFalse(RowReasons.showsUnavailable(cut));
            assertNull(RowReasons.tooltipOf(cut));
            assertNull(row.getAccessibleHelp());
            release(row);
        });
        settle();
        assertEquals(1, runs.get(), "a click runs it");
        assertEquals(List.of(), said);
        ContextMenu again = show(cut);
        Node row2 = node(cut);
        FxTestSupport.runOnFx(() -> {
            row2.requestFocus();
            key(again, KeyCode.ENTER);
        });
        settle();
        assertEquals(2, runs.get(), "Enter runs it");
        assertEquals(List.of(), said);
        FxTestSupport.runOnFx(cut::fire);
        assertEquals(3, runs.get());
    }

    @Test
    void aReasonGivenOrTakenWhileTheMenuShowsShowsAtOnce() throws Exception {
        window();
        MenuItem paste = new MenuItem("Paste");
        FxTestSupport.runOnFx(() -> RowReasons.when(paste, true, WHY));
        show(paste);
        FxTestSupport.runOnFx(() -> {
            assertNull(RowReasons.tooltipOf(paste));
            RowReasons.because(paste, "palette.disabled.needsBuffer");
            assertEquals(
                    tr("palette.disabled.needsBuffer"),
                    RowReasons.tooltipOf(paste).getText());
            RowReasons.because(paste, WHY);
            assertEquals(tr(WHY), RowReasons.tooltipOf(paste).getText());
            RowReasons.because(paste, null);
            assertNull(RowReasons.tooltipOf(paste));
            assertNull(paste.getStyleableNode().getAccessibleHelp());
        });
    }

    @Test
    void aBoundRowFollowsItsValue() throws Exception {
        SimpleBooleanProperty off = new SimpleBooleanProperty(true);
        MenuItem save = new MenuItem("Save");
        FxTestSupport.runOnFx(() -> {
            RowReasons.bind(save, off, WHY);
            assertTrue(RowReasons.showsUnavailable(save));
            off.set(false);
            assertFalse(save.isDisable());
            assertNull(RowReasons.reasonOf(save));
            off.set(true);
            assertEquals(tr(WHY), RowReasons.reasonOf(save));
            MenuItem open = RowReasons.bind(new MenuItem("Open"), new SimpleBooleanProperty(false), WHY);
            assertFalse(open.isDisable(), "bound to a value that is false, it starts available");
        });
    }

    @Test
    void aPlaceholderIsALabelThatSaysNothing() throws Exception {
        window();
        AtomicInteger runs = new AtomicInteger();
        MenuItem none = counting(new MenuItem("(none)"), runs);
        FxTestSupport.runOnFx(() -> RowReasons.placeholder(none));
        show(none);
        Node row = node(none);
        FxTestSupport.runOnFx(() -> {
            assertTrue(none.isDisable());
            assertTrue(RowReasons.isPlaceholder(none));
            assertFalse(RowReasons.showsUnavailable(none), "a label is not a command that cannot run");
            assertNull(RowReasons.tooltipOf(none));
            release(row);
            none.fire();
        });
        settle();
        assertEquals(0, runs.get());
        assertEquals(List.of(), said, "choosing a label says nothing");
    }
}
