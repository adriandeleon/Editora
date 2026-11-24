package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.editor.MenuChords;
import com.editora.editor.RowReasons;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The one way a command finds the control it acts on when that control is a row of a right-click menu or a button
 * that only the mouse reached (KBD-01 and MENU-05, Pauta 6.2.0). A control {@linkplain #bind binds} what a command
 * does to a node, kept in the node's own properties (never in a static map, so a closed window is not kept alive),
 * or gives a node the menu a right-click would open there ({@link #menu}), whose rows are {@linkplain #row marked}
 * with their commands. A command then looks from where the keyboard is, up ({@link #find}), and runs the first
 * binding it meets, or fires the marked row of the first menu that has one, as a click would.
 *
 * <p>The Project Map's commands ({@link MapButtonCommands}) look in the Map's window only. The panels' commands
 * ({@link PanelCommands}) {@linkplain #run look} from the focus owner of the focused window up, then of each other
 * window, and then down from what is showing in the main window (the active tab, each open tool window), so the
 * palette reaches a terminal or a diff while the keyboard is in the editor. With nothing there, or the row disabled
 * where the menu would show it disabled, the status line says so and nothing runs.
 */
class FocusCommands {

    /** The node property a column or a note stuck on a card keeps its card under (see {@link #stuckOn}). */
    private static final String CARD = "editora.mapCard";

    /** The node property a node keeps the builder of its right-click menu under (see {@link #menu}). */
    private static final String MENU = MenuChords.ROWS;

    /** The menu item property naming the command that fires a row tagged with another command's chord. */
    private static final String ALSO = "editora.mapRow";

    /** The node property that keeps a node's offers to the keyboard in it (see {@link #focusOnly}). */
    private static final String FOCUS_ONLY = "editora.focusCommandsOnly";

    /** Not instantiated: {@link MapButtonCommands} extends it only so the Map's call sites name it as before. */
    FocusCommands() {}

    /** Has {@code commandId} run {@code action} while the keyboard is in {@code owner} or below it. */
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
     * Has {@code commandId} press {@code button} as a click would, while the keyboard is in {@code owner}: the button
     * keeps its words and its own action. Refused while the button is hidden or disabled, when a click would do nothing.
     */
    static void press(Node owner, String commandId, ButtonBase button) {
        MenuChords.offerButton(owner, commandId, button);
    }

    /** {@link #press} with {@code button} as its own owner: a button that is not inside what the keyboard is in. */
    static <B extends ButtonBase> B press(B button, String commandId) {
        press(button, commandId, button);
        return button;
    }

    /**
     * As {@link #bind(Node, String, Runnable)}, and {@code button} runs {@code action} too, keeping the words and the
     * tooltip it has: a button whose label says what it does ("Open", "Files"), or that names a place.
     */
    static void runs(Node owner, ButtonBase button, String commandId, Runnable action) {
        bind(owner, commandId, action);
        button.setOnAction(event -> action.run());
    }

    /**
     * Keeps what {@code node} binds and offers (one row of many, a Doctor check) for the keyboard in it: a command
     * looking below what the window shows ({@link #findBelow}) passes it over, as it would otherwise pick the first
     * row.
     */
    static void focusOnly(Node node) {
        node.getProperties().put(FOCUS_ONLY, Boolean.TRUE);
    }

    /** {@code attachment} (a column, a note) is stuck on {@code card}: a command it does not take is the card's. */
    static void stuckOn(Node attachment, Node card) {
        attachment.getProperties().put(CARD, card);
    }

    /**
     * Gives {@code owner} the right-click menu a command looks in while the keyboard is there: {@code items} builds it
     * as a right-click would, for the selected row or the caret, and a command fires its row ({@link #row}). Null from
     * it: nothing is selected, so there is no menu.
     */
    static void menu(Node owner, Supplier<@Nullable List<MenuItem>> items) {
        MenuChords.offerRows(owner, items);
    }

    /** {@link #menu} for a control whose menu is its own context menu, built once and kept. */
    static void ownMenu(Control control) {
        menu(control, () -> {
            ContextMenu menu = LazyContextMenu.menuOf(control);
            return menu == null ? null : menu.getItems();
        });
    }

    /**
     * {@link #menu} for a control whose own context menu is built once and set for each showing by its showing
     * handler (which rows are offered, which are disabled): the handler runs, as a right-click runs it, before a
     * command looks for its row.
     */
    static void shownMenu(Control control) {
        menu(control, () -> {
            ContextMenu menu = LazyContextMenu.menuOf(control);
            if (menu == null) {
                return null;
            }
            javafx.event.Event.fireEvent(
                    menu, new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
            return menu.getItems();
        });
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

    /** The command {@code item} is marked for, by {@link #row} or {@link #also}; null when it is marked for none. */
    static @Nullable String commandOf(MenuItem item) {
        return item.getProperties().get(ALSO) instanceof String also ? also : MenuChords.commandOf(item);
    }

    /**
     * What {@code commandId} runs where {@code focus} is, or null: asked of each node from it up, a node
     * {@link #stuckOn} a card going on from the card.
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
     * null action) a row of the menu there that is disabled by the menu's own rule, so the command says so instead,
     * with the row's {@code reason} when it has one ({@link RowReasons}); and, for an action {@linkplain #bind bound}
     * as a {@code Runnable}, that very action.
     */
    record Found(
            @Nullable BooleanSupplier action,
            @Nullable Runnable bound,
            List<MenuItem> choices,
            @Nullable String reason) {

        Found(@Nullable BooleanSupplier action, @Nullable Runnable bound, List<MenuItem> choices) {
            this(action, bound, choices, null);
        }

        Found(@Nullable BooleanSupplier action, @Nullable Runnable bound) {
            this(action, bound, List.of());
        }

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
            Found here = foundOn(node, commandId);
            if (here != null) {
                return here;
            }
            node = node.hasProperties() && node.getProperties().get(CARD) instanceof Node card
                    ? card
                    : node.getParent();
        }
        return null;
    }

    /** What {@code node} itself gives {@code commandId}: its binding, or its menu's row for it; null when neither. */
    private static @Nullable Found foundOn(Node node, String commandId) {
        if (!node.hasProperties()) {
            return null;
        }
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
            return rowIn(items, commandId, false);
        }
        return null;
    }

    /**
     * What {@code commandId} does inside {@code root}: the first showing node below it, depth first, that bound the
     * command or whose menu has a row for it; null when none does. A hidden node (a tab not selected) is skipped with
     * everything under it.
     */
    static @Nullable Found findBelow(Node root, String commandId) {
        if (!root.isVisible()) {
            return null;
        }
        Found here =
                root.hasProperties() && root.getProperties().containsKey(FOCUS_ONLY) ? null : foundOn(root, commandId);
        if (here != null) {
            return here;
        }
        if (root instanceof Parent parent) {
            for (Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                Found below = findBelow(child, commandId);
                if (below != null) {
                    return below;
                }
            }
        }
        return null;
    }

    /**
     * The row of {@code items} (submenus included) for {@code commandId}, or null; disabled under a disabled menu. A
     * command marked on several rows (the choices of a submenu: a priority, a keyword) also gets every one of them that
     * is enabled, for {@link #run} to offer as a list.
     */
    static @Nullable Found rowIn(List<?> items, String commandId, boolean under) {
        List<MenuItem> all = new ArrayList<>();
        List<Boolean> off = new ArrayList<>();
        List<@Nullable String> reasons = new ArrayList<>();
        collect(items, commandId, under ? "" : null, all, off, reasons);
        if (all.isEmpty()) {
            return null;
        }
        List<MenuItem> choices = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (!off.get(i)) {
                choices.add(all.get(i));
            }
        }
        MenuItem first = all.get(0);
        String reason = off.get(0) ? reasons.get(0) : null;
        return new Found(off.get(0) ? null : () -> choose(first), null, choices, reason);
    }

    /**
     * Collects the rows of {@code items} marked with {@code commandId}, whether each is off, and why. {@code under} is
     * null outside a disabled submenu, and inside one the submenu's reason ("" when it has none).
     */
    private static void collect(
            List<?> items,
            String commandId,
            @Nullable String under,
            List<MenuItem> all,
            List<Boolean> off,
            List<@Nullable String> reasons) {
        for (Object entry : items) {
            if (!(entry instanceof MenuItem item)) {
                continue;
            }
            boolean disabled = under != null || item.isDisable() || !item.isVisible();
            String reason = under != null ? under : item.isVisible() ? RowReasons.reasonOf(item) : null;
            if (commandId.equals(MenuChords.commandOf(item))
                    || commandId.equals(item.getProperties().get(ALSO))) {
                all.add(item);
                off.add(disabled);
                reasons.add(reason == null || reason.isEmpty() ? null : reason);
            } else if (item instanceof Menu menu) {
                collect(
                        menu.getItems(),
                        commandId,
                        disabled ? (reason == null ? "" : reason) : null,
                        all,
                        off,
                        reasons);
            }
        }
    }

    /**
     * Chooses {@code item} as a click on it would: a check row turns over, a radio row is chosen, then it fires. A
     * disabled row is refused, as a click refuses it: false, and nothing turned over or run.
     */
    static boolean choose(MenuItem item) {
        if (item.isDisable()) {
            return false;
        }
        if (item instanceof CheckMenuItem check) {
            check.setSelected(!check.isSelected());
        } else if (item instanceof RadioMenuItem radio) {
            radio.setSelected(radio.getToggleGroup() != null || !radio.isSelected());
        }
        item.fire();
        return true;
    }

    /**
     * What {@code commandId} does in {@code window}: from its focus owner up, or, in a popup that takes no keyboard (a
     * debugger's value popup), below its root; null when nothing there offers it.
     */
    private static @Nullable Found foundIn(Window window, String commandId) {
        Scene scene = window.getScene();
        if (scene == null) {
            return null;
        }
        Node focus = scene.getFocusOwner();
        if (focus == null && takesNoFocus(window)) {
            return findBelow(scene.getRoot(), commandId);
        }
        return find(focus, commandId);
    }

    /** Whether {@code window} is a popup that never has the keyboard (a value popup); a context menu is not one. */
    private static boolean takesNoFocus(Window window) {
        return window instanceof PopupWindow && !(window instanceof ContextMenu);
    }

    /**
     * Runs {@code commandId} on what the focused window's keyboard is in, or in a showing popup, when that offers it,
     * and answers true (the status line says so when it offers the row disabled); false when nothing there offers it,
     * so the command goes on to act as it does elsewhere (the Debug window's row commands on its own views).
     */
    static boolean runWhereFocused(String commandId, Consumer<String> status, List<? extends Window> windows) {
        for (Window window : windows) {
            if (!window.isFocused() && !takesNoFocus(window)) {
                continue;
            }
            Found found = foundIn(window, commandId);
            if (found != null) {
                BooleanSupplier action = found.action();
                if (action == null || !action.getAsBoolean()) {
                    status.accept(refusal(commandId, found));
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Runs command {@code commandId} where it is offered, or says on {@code status} why it did not run: looked for from
     * the focus owner of each of {@code windows} up (the focused window first), then below each of {@code showing} (the
     * main window's active tab and open tool windows), then below each window's root. A window other than the focused one
     * is brought forward first,
     * so nothing runs where the user is not looking. A command marked on several rows of the menu (a submenu's choices)
     * hands the enabled ones to {@code pick}, which asks which. Answers whether it ran.
     */
    static boolean run(
            String commandId,
            Consumer<String> status,
            List<? extends Window> windows,
            List<? extends Node> showing,
            Consumer<List<MenuItem>> pick) {
        List<Window> ordered = new ArrayList<>(windows);
        ordered.sort((a, b) -> Boolean.compare(b.isFocused(), a.isFocused()));
        Found found = null;
        Window in = null;
        for (Window window : ordered) {
            found = foundIn(window, commandId);
            if (found != null) {
                in = window;
                break;
            }
        }
        for (int i = 0; found == null && i < showing.size(); i++) {
            found = findBelow(showing.get(i), commandId);
        }
        // Last, what a window shows with the keyboard elsewhere in it (the Settings page on screen, its buttons).
        for (int i = 0; found == null && i < ordered.size(); i++) {
            Scene scene = ordered.get(i).getScene();
            found = scene == null ? null : findBelow(scene.getRoot(), commandId);
            in = found == null ? null : ordered.get(i);
        }
        if (in instanceof Stage stage && !stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
        if (found != null && found.choices().size() > 1) {
            pick.accept(found.choices()); // a row of several (a priority, a keyword): which one is the user's
            return true;
        }
        BooleanSupplier action = found == null ? null : found.action();
        if (action != null && action.getAsBoolean()) {
            return true;
        }
        status.accept(refusal(commandId, found));
        return false;
    }

    /**
     * What the status line says when {@code commandId} did not run: why its row cannot run now, as the row's tooltip
     * says it ({@code status.commandUnavailable}), or, when nothing offers it or the row says nothing, that it is not
     * offered here.
     */
    static String refusal(String commandId, @Nullable Found found) {
        String title = DebugPanelText.bareTitle(tr("command." + commandId));
        String reason = found == null ? null : found.reason();
        return reason == null ? tr("status.rows.notOffered", title) : tr("status.commandUnavailable", title, reason);
    }
}
