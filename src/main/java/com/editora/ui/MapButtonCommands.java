package com.editora.ui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Control;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.editor.MenuChords;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the Project Map's buttons that only the mouse reached: the header bar's Back, Forward, help
 * and options, and the buttons of a card, a note or a column. A header command runs the method its button runs. A
 * card command acts on whatever the Map's keyboard is in, as {@link ProjectMapView#focusedCard} finds it: from the
 * Map scene's focus owner up, the first node that {@linkplain #bind took} the command runs it, and a column or a
 * note stuck on a card passes what it does not take on to its card. With the Map closed, or the keyboard in nothing
 * that takes the command, the status line says so.
 *
 * <p>The same walk serves the Map's menu rows ({@link MapMenuCommands}): a node may give the menu a right-click would
 * open there ({@link #menu}), and a command fires the row of it {@linkplain #row marked} with the command, or is
 * refused when the menu shows that row disabled.
 */
final class MapButtonCommands {

    private static final String HIERARCHY_FLIP = "projectMap.hierarchyFlip";

    /** The card commands, in palette order: each runs what {@link #bind} gave it where the Map's keyboard is. */
    private static final List<String> CARD_COMMANDS = List.of(
            "projectMap.cardPreviousDeclaration",
            "projectMap.cardNextDeclaration",
            "projectMap.cardZoomOut",
            "projectMap.cardZoomIn",
            "projectMap.cardPin",
            "projectMap.cardStructure",
            "projectMap.cardNotes",
            "projectMap.cardClose",
            HIERARCHY_FLIP,
            "projectMap.changesOpenInEditor",
            "projectMap.changesCompare",
            "projectMap.trailStopNote");

    /** The node property a column or a note stuck on a card keeps its card under (see {@link #stuckOn}). */
    private static final String CARD = "editora.mapCard";

    /** The node property a card, the map's rows or a list keeps the builder of its menu under (see {@link #menu}). */
    private static final String MENU = "editora.mapMenu";

    /** The menu item property naming the command that fires a row tagged with another command's chord. */
    private static final String ALSO = "editora.mapRow";

    private MapButtonCommands() {}

    static void register(MainController mc) {
        // The list the Menu key on Back and Forward opens: with it a command, those two are not Tab stops.
        mc.registry.register(Command.of(
                "projectMap.history",
                () -> withMap(mc, view -> {
                    raise(view);
                    if (!view.showHistoryMenu()) {
                        mc.setStatus(tr("status.projectMap.noHistory"));
                    }
                })));
        // A trail stop's note never takes the keyboard (it is a sheet of labels), so its × has a command of its own.
        mc.registry.register(Command.of(
                "projectMap.trailNoteHide",
                () -> withMap(mc, view -> {
                    for (Node child : List.copyOf(view.viewsCanvasHost().getChildren())) {
                        if (child instanceof ProjectMapTrailNote note) {
                            note.close();
                            return;
                        }
                    }
                    mc.setStatus(tr("status.projectMap.noTrailNote"));
                })));
        mc.registry.register(Command.of("projectMap.back", () -> withMap(mc, view -> view.moveHistory(-1))));
        mc.registry.register(Command.of("projectMap.forward", () -> withMap(mc, view -> view.moveHistory(1))));
        mc.registry.register(Command.of(
                "projectMap.help",
                () -> withMap(mc, view -> {
                    raise(view);
                    view.toggleHelp();
                })));
        mc.registry.register(Command.of(
                "projectMap.options",
                () -> withMap(mc, view -> {
                    raise(view);
                    view.showOptions();
                })));
        for (String id : CARD_COMMANDS) {
            mc.registry.register(Command.of(id, () -> withMap(mc, view -> runWhereFocused(mc, view, id))));
        }
        MapMenuCommands.register(mc); // the rest of the Map's rows and buttons, found the same way
    }

    /** Has {@code commandId} run {@code action} while the Map's keyboard is in {@code owner}: a card, a column. */
    static void bind(Node owner, String commandId, Runnable action) {
        owner.getProperties().put(commandId, action);
    }

    /** As {@link #bind(Node, String, Runnable)} for a button: it runs {@code action} too, under the command's name. */
    static void bind(Node owner, ButtonBase button, String commandId, Runnable action) {
        bind(owner, button, commandId, () -> CommandTips.title(commandId), action);
    }

    /**
     * As {@link #bind(Node, ButtonBase, String, Runnable)} for a button that keeps its own words ({@code title}) where
     * they say more than the command's: which column it closes, that a note closed here is kept.
     */
    static void bind(Node owner, ButtonBase button, String commandId, Supplier<String> title, Runnable action) {
        bind(owner, commandId, action);
        button.setOnAction(event -> action.run());
        CommandTips.name(button, commandId, title);
    }

    /**
     * As {@link #bind(Node, String, Runnable)} for an action that may not apply now: false from it (the find bar is
     * closed, no ▶ on the caret's line) and the command says it is not available, as the control would do nothing.
     */
    static void bindIf(Node owner, String commandId, BooleanSupplier action) {
        owner.getProperties().put(commandId, action);
    }

    /**
     * Has {@code commandId} press {@code button} of {@code owner} (a card's bar) as a click would, while the Map's
     * keyboard is in the card: the button keeps its words and its own action. Refused while the button is hidden or
     * disabled, when a click would do nothing.
     */
    static void press(Node owner, String commandId, ButtonBase button) {
        bindIf(owner, commandId, () -> {
            if (!button.isVisible() || button.isDisabled()) {
                return false;
            }
            button.fire();
            return true;
        });
    }

    /**
     * As {@link #bind(Node, String, Runnable)}, and {@code button} runs {@code action} too, keeping the words and the
     * tooltip it has: a button whose label says what it does ("Open", "Files"), or that names a place.
     */
    static void runs(Node owner, ButtonBase button, String commandId, Runnable action) {
        bind(owner, commandId, action);
        button.setOnAction(event -> action.run());
    }

    /** {@code attachment} (a column, a note) is stuck on {@code card}: a command it does not take is the card's. */
    static void stuckOn(Node attachment, Node card) {
        attachment.getProperties().put(CARD, card);
    }

    /**
     * Gives {@code owner} (a card, the map's rows, a list) the right-click menu a command looks in while the Map's
     * keyboard is there: {@code items} builds it as a right-click would, for the selected row or the caret, and a
     * command fires its row ({@link #row}). Null from it: nothing is selected, so there is no menu.
     */
    static void menu(Node owner, Supplier<@Nullable List<MenuItem>> items) {
        owner.getProperties().put(MENU, items);
    }

    /** {@link #menu} for a control whose menu is its own context menu: a node of a card's graph, a place button. */
    static void ownMenu(Control control) {
        menu(
                control,
                () -> control.getContextMenu() == null
                        ? null
                        : control.getContextMenu().getItems());
    }

    /**
     * Marks {@code item} as the row command {@code commandId} fires, and shows the command's chord on it; a row that
     * shows another command's chord already (the editor's Go to Definition, which typed in a card does the same) keeps
     * it, and {@code commandId} still finds the row.
     */
    static <T extends MenuItem> T row(T item, String commandId) {
        if (MenuChords.commandOf(item) == null) {
            MenuChords.tag(item, commandId);
        } else {
            item.getProperties().put(ALSO, commandId);
        }
        return item;
    }

    /**
     * Marks {@code item} as a row {@code commandId} fires without showing that command's chord on it: a row that asks
     * something else than the chord typed there does (a declaration card's Callees, asked about its declaration).
     */
    static <T extends MenuItem> T also(T item, String commandId) {
        item.getProperties().put(ALSO, commandId);
        return item;
    }

    /**
     * What {@code commandId} runs where {@code focus} is, or null: asked of each node from it up, the way
     * {@link ProjectMapView#focusedCard} walks, a node {@link #stuckOn} a card going on from the card.
     */
    static @Nullable Runnable actionAt(@Nullable Node focus, String commandId) {
        Found found = find(focus, commandId);
        if (found == null || found.action() == null) {
            return null;
        }
        Runnable bound = found.bound();
        return bound != null ? bound : found.action()::getAsBoolean;
    }

    /**
     * What a command finds where the keyboard is: the action to run, which answers false when it did not apply, or (a
     * null action) a row of the menu there that is disabled by the menu's own rule, so the command says so instead;
     * and, for an action {@linkplain #bind bound} as a {@code Runnable}, that very action.
     */
    record Found(@Nullable BooleanSupplier action, @Nullable Runnable bound) {

        Found(@Nullable BooleanSupplier action) {
            this(action, null);
        }
    }

    /**
     * What {@code commandId} does where {@code focus} is, or null when nothing there takes it: from {@code focus} up,
     * the first node that {@linkplain #bind bound} the command runs its action, and the first whose {@linkplain #menu
     * menu} has a row for it fires that row (or answers it disabled); a node {@link #stuckOn} a card goes on from it.
     */
    static @Nullable Found find(@Nullable Node focus, String commandId) {
        Node node = focus;
        while (node != null) {
            if (node.hasProperties()) {
                Object bound = node.getProperties().get(commandId);
                if (bound instanceof Runnable action) {
                    return new Found(
                            () -> {
                                action.run();
                                return true;
                            },
                            action);
                }
                if (bound instanceof BooleanSupplier action) {
                    return new Found(action);
                }
                if (node.getProperties().get(MENU) instanceof Supplier<?> menu && menu.get() instanceof List<?> items) {
                    Found row = rowIn(items, commandId, false);
                    if (row != null) {
                        return row;
                    }
                }
            }
            node = node.hasProperties() && node.getProperties().get(CARD) instanceof Node card
                    ? card
                    : node.getParent();
        }
        return null;
    }

    /** The row of {@code items} (submenus included) for {@code commandId}, or null; disabled under a disabled menu. */
    static @Nullable Found rowIn(List<?> items, String commandId, boolean under) {
        for (Object entry : items) {
            if (!(entry instanceof MenuItem item)) {
                continue;
            }
            boolean off = under || item.isDisable() || !item.isVisible();
            if (commandId.equals(MenuChords.commandOf(item))
                    || commandId.equals(item.getProperties().get(ALSO))) {
                return new Found(off ? null : () -> choose(item));
            }
            if (item instanceof Menu menu) {
                Found inside = rowIn(menu.getItems(), commandId, off);
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    /** Chooses {@code item} as a click on it would: a check row turns over, a radio row is chosen, then it fires. */
    static boolean choose(MenuItem item) {
        if (item instanceof CheckMenuItem check) {
            check.setSelected(!check.isSelected());
        } else if (item instanceof RadioMenuItem radio) {
            radio.setSelected(radio.getToggleGroup() != null || !radio.isSelected());
        }
        item.fire();
        return true;
    }

    /** Runs {@code op} on the Map while its window is open; says that it is not otherwise. */
    static void withMap(MainController mc, Consumer<ProjectMapView> op) {
        ProjectPanel panel = mc.projectPanel;
        if (panel == null || !panel.isMapShowing()) {
            mc.setStatus(tr("status.projectMap.notShowing"));
        } else {
            op.accept(panel.mapView());
        }
    }

    /** Runs card command {@code id} on what the Map's keyboard is in, or says that nothing there takes it. */
    private static void runWhereFocused(MainController mc, ProjectMapView view, String id) {
        Scene scene = view.getScene();
        Runnable action = actionAt(scene == null ? null : scene.getFocusOwner(), id);
        if (action != null) {
            action.run();
        } else {
            mc.setStatus(
                    HIERARCHY_FLIP.equals(id) ? tr("status.projectMap.noHierarchy") : tr("status.projectMap.noCard"));
        }
    }

    /** Brings the Map's window forward and gives it the keyboard: the help and the menu are shown in it. */
    static void raise(ProjectMapView view) {
        if (view.getScene() != null && view.getScene().getWindow() instanceof Stage stage && !stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
    }
}
