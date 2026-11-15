package com.editora.editor;

import java.util.List;
import java.util.function.Consumer;

import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A row of a right-click menu or a drop-down that cannot run now says why (KBD-04 in Pauta 6.0.1), as a menu bar
 * row does: it stays where it is, grayed, its tooltip says why, a screen reader hears why, the arrow keys reach it,
 * and choosing it (a click, Enter or Space) says why in the status bar instead of running.
 *
 * <p>A call site that used to say {@code item.setDisable(cond)} says {@link #because because(item, key)} or {@link
 * #when when(item, ok, key)} instead, naming the sentence ({@code null}: the row can run). A row that is a label
 * rather than a command ("No recent files") is a {@link #placeholder}: disabled, with no reason, as before.
 *
 * <h2>Why the row stays disabled</h2>
 *
 * <p>An unavailable row is {@linkplain MenuItem#setDisable disabled}, as it was. JavaFX then refuses it on every
 * path it owns: a click, Enter and Space ({@code ContextMenuContent.doSelect}, which also flips a check row only when
 * the row is enabled), an accelerator ({@code ControlAcceleratorSupport}), a screen reader's press, and a submenu's
 * opening ({@code Menu.show}). The one path JavaFX leaves open is {@link MenuItem#fire()}, which fires the row's
 * action whatever its state: an action filter on the row, put on with its first reason, consumes the action event of
 * a disabled row before any handler of it runs, so a command that fires a row cannot run it either.
 *
 * <p>What a disabled row loses is not its node: a context menu draws each row with a node of its own that is never
 * itself disabled (only marked {@code :disabled} for the stylesheet). So it still gets the pointer and the
 * keyboard's focus, which is what lets it carry the tooltip and say why when chosen. Each time a menu shows, its
 * rows' nodes get the sentence ({@link #install}): a tooltip, the screen reader's text, and a click and key filter
 * that say it.
 */
public final class RowReasons {

    /** The item property that holds the row's reason; present (null when it can run) once the mechanism set it. */
    private static final String REASON = "editora.rowReason";

    /** The item property set once the row's action filter is on. */
    private static final String GUARDED = "editora.rowReason.guarded";

    /** The item property that marks a row that is a label, disabled for good with no reason. */
    private static final String PLACEHOLDER = "editora.rowReason.placeholder";

    /** The row node's property set once its click and key filters are on. */
    private static final String WIRED = "editora.rowReason.wired";

    /** The row node's property that holds the tooltip {@link #decorate} put on it. */
    private static final String TIP = "editora.rowReason.tip";

    /** The menu property set once the menu decorates its rows each time it shows. */
    private static final String WATCHED = "editora.rowReason.watched";

    /**
     * The window property that holds where a chosen row says why: the window's status line, a {@code
     * Consumer<String>}. Set by each editor window on its own stage ({@link #sayIn}), never in a static map.
     */
    public static final String SAY = "editora.rowReason.say";

    private static boolean installed;

    private RowReasons() {}

    /**
     * Makes {@code item} unavailable, saying {@code reason} (one sentence for the user), or available again when
     * {@code reason} is null. The row is disabled while it has a reason, so JavaFX refuses it on every path it owns,
     * and its action filter refuses a {@link MenuItem#fire()}.
     */
    public static <T extends MenuItem> T set(T item, @Nullable String reason) {
        guard(item);
        item.getProperties().put(REASON, reason);
        item.setDisable(reason != null);
        Node node = rowNode(item);
        if (node != null) {
            decorate(item, node); // the menu is showing: the row says it now, not at the next showing
        }
        return item;
    }

    /** {@link #set} with the reason's message key: null when the row can run. */
    public static <T extends MenuItem> T because(T item, @Nullable String reasonKey) {
        return set(item, reasonKey == null ? null : tr(reasonKey));
    }

    /** Makes {@code item} available when {@code available}, and unavailable saying {@code reasonKey}'s sentence otherwise. */
    public static <T extends MenuItem> T when(T item, boolean available, String reasonKey) {
        return because(item, available ? null : reasonKey);
    }

    /**
     * Makes {@code item} available while its feature is {@code on}, and otherwise unavailable saying what the Command
     * Palette says for the feature's commands: it is turned off, and command {@code toggleId} turns it on.
     */
    public static <T extends MenuItem> T featureOn(T item, boolean on, String toggleId) {
        return set(item, on ? null : tr("palette.disabled.featureOff", tr("command." + toggleId)));
    }

    /**
     * Keeps {@code item} unavailable, saying {@code reasonKey}'s sentence, while {@code unavailable} holds: for a row
     * that follows a button's state or another value, where it used to bind its disable property.
     */
    public static <T extends MenuItem> T bind(T item, ObservableValue<Boolean> unavailable, String reasonKey) {
        when(item, !Boolean.TRUE.equals(unavailable.getValue()), reasonKey);
        unavailable.addListener((o, was, now) -> when(item, !Boolean.TRUE.equals(now), reasonKey));
        return item;
    }

    /**
     * Makes {@code item} a label in its menu rather than a command ("No recent files", "(none)"): disabled for good,
     * with no reason, as such rows always were. What tells it from a row that cannot run now.
     */
    public static <T extends MenuItem> T placeholder(T item) {
        guard(item);
        item.getProperties().put(PLACEHOLDER, Boolean.TRUE);
        item.setDisable(true);
        return item;
    }

    /** Whether {@code item} was made a {@link #placeholder}. */
    public static boolean isPlaceholder(MenuItem item) {
        return Boolean.TRUE.equals(item.getProperties().get(PLACEHOLDER));
    }

    /** Why {@code item} cannot run now, or null: it can, or it never was given a reason. */
    public static @Nullable String reasonOf(MenuItem item) {
        return item.getProperties().get(REASON) instanceof String reason ? reason : null;
    }

    /**
     * Whether {@code item} shows as unable to run: grayed, saying why. What a test asks of a row, as it asks
     * {@code MainMenuBar.showsUnavailable} of a menu bar row.
     */
    public static boolean showsUnavailable(MenuItem item) {
        return item.isDisable() && reasonOf(item) != null;
    }

    /** The tooltip that says why on {@code item}'s row while its menu shows; null when it says nothing or is not shown. */
    public static @Nullable Tooltip tooltipOf(MenuItem item) {
        Node node = rowNode(item);
        return node != null && node.getProperties().get(TIP) instanceof Tooltip tip ? tip : null;
    }

    /**
     * The node that draws {@code item} while a menu shows it, or null. A row of a submenu is drawn in the submenu's own
     * popup, where {@link MenuItem#getStyleableNode()} does not look: such a node is found among the rows of the
     * showing menus, each of which the skin marks with its item (the {@code MenuItem.class} property).
     */
    public static @Nullable Node rowNode(MenuItem item) {
        Node node = item.getStyleableNode();
        if (node != null || item.getParentMenu() == null) {
            return node;
        }
        for (Window window : Window.getWindows()) {
            if (window instanceof ContextMenu menu) {
                Node row = rowIn(menu, item);
                if (row != null) {
                    return row;
                }
            }
        }
        return null;
    }

    /** The node of {@code menu}'s skin that draws {@code item}, or null. */
    private static @Nullable Node rowIn(ContextMenu menu, MenuItem item) {
        for (Node row : rows(menu)) {
            if (row.getProperties().get(MenuItem.class) == item) {
                return row;
            }
        }
        return null;
    }

    /** The nodes that draw {@code menu}'s rows: the skin's {@code .menu-item} nodes. */
    private static java.util.Set<Node> rows(ContextMenu menu) {
        Node content = menu.getSkin() == null ? null : menu.getSkin().getNode();
        return content == null ? java.util.Set.of() : content.lookupAll(".menu-item");
    }

    /**
     * Has the rows {@code window} shows say why on {@code say} (its status line) when chosen while they cannot run.
     * A menu says it in the window it was opened from, or the editor window that owns that one.
     */
    public static void sayIn(Window window, Consumer<String> say) {
        window.getProperties().put(SAY, say);
    }

    /** Puts on the action filter that refuses a disabled row's {@link MenuItem#fire()}; once per row. */
    private static void guard(MenuItem item) {
        if (item.getProperties().containsKey(GUARDED)) {
            return;
        }
        item.getProperties().put(GUARDED, Boolean.TRUE);
        // A filter runs before every handler of the row (onAction among them), and consuming the event here stops
        // them all: consuming it in a handler would not stop the others on the same row.
        item.addEventFilter(ActionEvent.ACTION, event -> {
            if (item.isDisable()) {
                event.consume();
            }
        });
    }

    /**
     * Decorates the rows of every menu as it shows, in every window, now and later: a row with a reason gets its
     * tooltip, its spoken text and its click and key filters. Idempotent; FX thread only. Installed with {@code
     * ui.MenuRowLayout}.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w instanceof ContextMenu menu) {
                        watch(menu);
                    }
                }
            }
        });
    }

    /** Has {@code menu} decorate its rows each time it has shown (its rows' nodes exist by then); once per menu. */
    static void watch(ContextMenu menu) {
        if (menu.getProperties().containsKey(WATCHED)) {
            return;
        }
        menu.getProperties().put(WATCHED, Boolean.TRUE);
        menu.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> {
            explainTextInputMenu(menu);
            decorateRows(menu);
        });
    }

    /** The class of the rows of the menu JavaFX gives a text field or text area of its own. */
    private static final String TEXT_INPUT_ROW =
            "com.sun.javafx.scene.control.behavior.TextInputControlBehavior$ContextMenuItem";

    /**
     * Gives the grayed rows of the menu JavaFX gives a text field or text area of its own (Undo, Redo, Cut, Copy,
     * Paste, Delete, Select All, which JavaFX builds and grays as it opens) the reason they are grayed. The rows are
     * known by their place: JavaFX lays out an editable field's menu as those eight (a separator before Select All)
     * and a read-only one's as Copy and Select All. Only a row JavaFX grayed gets a reason; none is made available.
     */
    static void explainTextInputMenu(ContextMenu menu) {
        List<MenuItem> items = menu.getItems();
        if (!(menu.getOwnerNode() instanceof TextInputControl field)
                || !items.stream().allMatch(RowReasons::textInputRow)) {
            return;
        }
        String copy = field instanceof PasswordField ? "row.unavailable.password" : "palette.disabled.needsSelection";
        String[] keys = items.size() == 8
                ? new String[] {
                    "row.unavailable.nothingToUndo",
                    "row.unavailable.nothingToRedo",
                    copy,
                    copy,
                    "row.unavailable.clipboardEmpty",
                    "palette.disabled.needsSelection"
                }
                : new String[] {copy};
        for (int i = 0; i < Math.min(keys.length, items.size()); i++) {
            MenuItem item = items.get(i);
            if (item.isDisable()) {
                because(item, keys[i]);
            } else if (item.getProperties().containsKey(REASON)) {
                set(item, null); // JavaFX enabled it this time: it says nothing
            }
        }
    }

    /** Whether {@code item} is a row of the menu JavaFX gives a text field (or the separator in it). */
    private static boolean textInputRow(MenuItem item) {
        return item instanceof SeparatorMenuItem
                || TEXT_INPUT_ROW.equals(item.getClass().getName());
    }

    /** Decorates the node of each row of {@code menu} the mechanism has given a reason (or taken it from). */
    static void decorateRows(ContextMenu menu) {
        for (Node row : rows(menu)) {
            if (row.getProperties().get(MenuItem.class) instanceof MenuItem item
                    && item.getProperties().containsKey(REASON)) {
                decorate(item, row);
            }
        }
    }

    /** Gives {@code item}'s row node its reason (a tooltip, the spoken text), or takes it off when it has none. */
    private static void decorate(MenuItem item, Node node) {
        wire(item, node);
        String reason = reasonOf(item);
        Label title = titleLabel(node);
        Object shown = node.getProperties().get(TIP);
        if (shown instanceof Tooltip tip && reason != null && reason.equals(tip.getText())) {
            return; // the same reason as at the last showing
        }
        if (shown instanceof Tooltip tip) {
            Tooltip.uninstall(node, tip);
            node.getProperties().remove(TIP);
        }
        node.setAccessibleHelp(reason);
        if (title != null) {
            // A row's spoken name is its label's: the reason goes there too, as the menu bar's rows say it.
            title.setAccessibleText(reason == null ? null : spoken(item.getText(), reason));
        }
        if (reason == null) {
            return;
        }
        Tooltip tip = ControlNames.tooltip(reason);
        tip.setWrapText(true);
        tip.setMaxWidth(UiZoom.px(380));
        tip.getStyleClass().add("palette-disabled-tooltip"); // as the palette's and the menu bar's grayed rows
        Tooltip.install(node, tip);
        node.getProperties().put(TIP, tip);
    }

    /** What a screen reader says for a row that cannot run: its title, then why. Pure. */
    static String spoken(@Nullable String title, String reason) {
        return title == null || title.isBlank() ? reason : title + ". " + reason;
    }

    /** The label that shows the row's title (the skin's, which names its row), or null. */
    private static @Nullable Label titleLabel(Node node) {
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof Label label && label.getLabelFor() == node) {
                    return label;
                }
            }
        }
        return null;
    }

    /**
     * Puts on {@code node} (once) the filters that make choosing a row that cannot run say why: a click, and Enter or
     * Space while the row has the keyboard. JavaFX would do nothing there, the row being disabled; the menu then
     * closes, as it does when a menu bar row says why.
     */
    private static void wire(MenuItem item, Node node) {
        if (node.getProperties().containsKey(WIRED)) {
            return;
        }
        node.getProperties().put(WIRED, Boolean.TRUE);
        node.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (sayWhy(item)) {
                event.consume();
            }
        });
        node.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) && sayWhy(item)) {
                event.consume();
            }
        });
    }

    /**
     * Says why {@code item} cannot run, in the status line of the window its menu belongs to, and closes the menu;
     * false, and nothing said, when it can run or has no reason (a placeholder).
     */
    static boolean sayWhy(MenuItem item) {
        String reason = reasonOf(item);
        if (reason == null || !item.isDisable()) {
            return false;
        }
        ContextMenu menu = rootMenu(item);
        Consumer<String> say = menu == null ? null : sayFor(menu.getOwnerWindow());
        if (say != null) {
            say.accept(tr("status.commandUnavailable", title(item), reason));
        }
        if (menu != null) {
            menu.hide();
        }
        return true;
    }

    /** The title a status line names the row by: its own words, or its command's when it has none. */
    static String title(MenuItem item) {
        String text = item.getText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        String command = MenuChords.commandOf(item);
        return command == null ? "" : MenuTitles.of(tr("command." + command));
    }

    /** The menu at the root of {@code item}'s chain of submenus, or null when it is in none. */
    static @Nullable ContextMenu rootMenu(MenuItem item) {
        MenuItem top = item;
        while (top.getParentMenu() != null) {
            top = top.getParentMenu();
        }
        return top.getParentPopup();
    }

    /**
     * Where a row of a menu opened from {@code owner} says why: that window's status line, else the one of the window
     * that owns it (a popup's, a secondary window's editor window), else the focused editor window's, else the first.
     */
    static @Nullable Consumer<String> sayFor(@Nullable Window owner) {
        Window window = owner;
        while (window != null) {
            if (window.getProperties().get(SAY) instanceof Consumer<?> say) {
                return accepting(say);
            }
            window = window instanceof PopupWindow popup
                    ? popup.getOwnerWindow()
                    : window instanceof Stage stage ? stage.getOwner() : null;
        }
        Consumer<String> first = null;
        for (Window w : Window.getWindows()) {
            if (w.getProperties().get(SAY) instanceof Consumer<?> say) {
                if (w.isFocused()) {
                    return accepting(say);
                }
                first = first == null ? accepting(say) : first;
            }
        }
        return first;
    }

    @SuppressWarnings("unchecked")
    private static Consumer<String> accepting(Consumer<?> say) {
        return (Consumer<String>) say;
    }
}
