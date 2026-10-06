package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;

import static com.editora.i18n.Messages.tr;

/**
 * The one definition of how a picker card (command palette, file finder, quick-open, switcher, branch
 * popup, …) is driven from the keyboard, and of the legend that tells the user so.
 *
 * <p>Each picker used to match {@code Ctrl}+{@code N}/{@code P}/{@code G} directly and print a hardcoded
 * {@code C-n C-p} / {@code C-g} legend. That is only true of the Emacs keymap: in the CUA, VS Code and
 * Sublime keymaps those chords are {@code file.new} / {@code editor.print}, which the scene-level
 * {@link KeyDispatcher} runs before the picker ever sees the key — so the legend advertised keys that
 * created a file behind the open palette. Navigation now resolves through the active keymap
 * ({@code nav.lineDown} / {@code nav.lineUp} / {@code nav.pageDown} / {@code nav.pageUp} /
 * {@code nav.docStart} / {@code nav.docEnd} / {@code edit.cancel} — all editor-context commands, which the
 * dispatcher leaves to a key-owning card), and the arrows, PageUp/PageDown, Enter and Esc always work. The
 * legend is built from the same keymap, so it can only name keys that reach the picker.
 */
final class PickerKeys {

    /** What a key press means to a picker. */
    enum Action {
        NONE,
        UP,
        DOWN,
        PAGE_UP,
        PAGE_DOWN,
        FIRST,
        LAST,
        ACCEPT,
        CANCEL
    }

    /** Rows moved by PageUp/PageDown when the list cannot say how many it shows. */
    private static final int DEFAULT_PAGE = 8;

    private static final String SEPARATOR = "  ·  ";

    private PickerKeys() {}

    /** The action for a key press in a picker with a query field, under the app-wide keymap. */
    static Action action(KeyEvent e) {
        return action(e, TextInputKeymap.sharedKeymap(), true);
    }

    /**
     * The action for a key press under {@code keymap} (null = the fixed keys only). {@code queryField} says
     * whether a text field has the focus: then plain Home/End stay with its caret.
     */
    static Action action(KeyEvent e, KeymapManager keymap, boolean queryField) {
        String token = KeyDispatcher.chord(e);
        if (token == null) {
            return Action.NONE;
        }
        return resolve(token, keymap == null ? null : keymap.commandFor(token), queryField);
    }

    /**
     * The pure decision behind {@link #action}: the fixed keys first, then whatever the keymap binds to the
     * caret-movement commands a picker borrows. Plain Home/End move the query field's caret when there is
     * one (as in every search box); Ctrl/Cmd+Home/End always jump to the first/last row.
     */
    static Action resolve(String token, String commandId, boolean queryField) {
        switch (token) {
            case "down":
                return Action.DOWN;
            case "up":
                return Action.UP;
            case "pagedown":
                return Action.PAGE_DOWN;
            case "pageup":
                return Action.PAGE_UP;
            case "enter":
                return Action.ACCEPT;
            case "escape":
                return Action.CANCEL;
            case "C-home", "Cmd-home", "Cmd-up":
                return Action.FIRST;
            case "C-end", "Cmd-end", "Cmd-down":
                return Action.LAST;
            case "home":
                return queryField ? Action.NONE : Action.FIRST;
            case "end":
                return queryField ? Action.NONE : Action.LAST;
            default:
                break;
        }
        if (commandId == null) {
            return Action.NONE;
        }
        return switch (commandId) {
            case "nav.lineDown" -> Action.DOWN;
            case "nav.lineUp" -> Action.UP;
            case "nav.pageDown" -> Action.PAGE_DOWN;
            case "nav.pageUp" -> Action.PAGE_UP;
            case "nav.docStart" -> Action.FIRST;
            case "nav.docEnd" -> Action.LAST;
            case KeyDispatcher.CANCEL -> Action.CANCEL;
            default -> Action.NONE;
        };
    }

    /** True when the press is the picker's cancel key: Esc, or the keymap's {@code edit.cancel} chord. */
    static boolean isCancel(KeyEvent e) {
        return action(e, TextInputKeymap.sharedKeymap(), false) == Action.CANCEL;
    }

    /**
     * The row a navigation action lands on, or -1 when {@code action} is not navigation or the list is
     * empty. Up/Down wrap around (as the pickers always did); the page and first/last jumps stop at the
     * ends. {@code current} may be -1 (nothing selected). Pure.
     */
    static int target(Action action, int current, int size, int page) {
        if (size <= 0) {
            return -1;
        }
        int step = Math.max(1, page);
        return switch (action) {
            case DOWN -> current < 0 ? 0 : Math.floorMod(current + 1, size);
            case UP -> current < 0 ? size - 1 : Math.floorMod(current - 1, size);
            case PAGE_DOWN -> Math.min(size - 1, Math.max(0, current) + step);
            case PAGE_UP -> Math.max(0, Math.max(0, current) - step);
            case FIRST -> 0;
            case LAST -> size - 1;
            default -> -1;
        };
    }

    /** How many rows a PageUp/PageDown moves: one less than the list shows, so a row of context remains. */
    static int page(ListView<?> list) {
        double cell = list.getFixedCellSize();
        if (cell > 0 && list.getHeight() > cell) {
            return Math.max(1, (int) (list.getHeight() / cell) - 1);
        }
        return DEFAULT_PAGE;
    }

    /** Applies a navigation action to a plain list (every row selectable). Returns false if it was not one. */
    static boolean navigate(ListView<?> list, Action action) {
        return navigate(list, action, row -> true);
    }

    /**
     * Applies a navigation action to {@code list}, landing only on rows {@code selectable} accepts (so the
     * cursor steps over section headers and grayed-out rows). Up/Down wrap; a page or first/last jump lands
     * on the nearest selectable row at or beyond its target, else the nearest before it. Returns false —
     * leaving the event for someone else — only when {@code action} is not navigation.
     */
    static <T> boolean navigate(ListView<T> list, Action action, Predicate<? super T> selectable) {
        if (!isNavigation(action)) {
            return false;
        }
        List<T> items = list.getItems();
        int size = items.size();
        int current = list.getSelectionModel().getSelectedIndex();
        int idx = -1;
        if (action == Action.UP || action == Action.DOWN) {
            int dir = action == Action.DOWN ? 1 : -1;
            int start = current >= 0 ? current : dir > 0 ? -1 : size;
            for (int step = 1; step <= size && idx < 0; step++) {
                int i = Math.floorMod(start + dir * step, size);
                idx = selectable.test(items.get(i)) ? i : -1;
            }
        } else if (size > 0) {
            int target = target(action, current, size, page(list));
            int dir = action == Action.PAGE_DOWN || action == Action.FIRST ? 1 : -1;
            idx = seek(items, target, dir, selectable);
            if (idx < 0) {
                idx = seek(items, target, -dir, selectable);
            }
        }
        if (idx >= 0) {
            list.getSelectionModel().select(idx);
            list.scrollTo(idx);
        }
        return true;
    }

    private static <T> int seek(List<T> items, int from, int dir, Predicate<? super T> selectable) {
        for (int i = from; i >= 0 && i < items.size(); i += dir) {
            if (selectable.test(items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    static boolean isNavigation(Action action) {
        return switch (action) {
            case UP, DOWN, PAGE_UP, PAGE_DOWN, FIRST, LAST -> true;
            default -> false;
        };
    }

    // ---- legend ----

    /**
     * One legend entry: the localized {@code pickerKeys.<what>} pattern filled with the keys that trigger
     * it, or null when there are none (the entry is then left out of the legend).
     */
    static String hint(String what, String keys) {
        return keys == null || keys.isBlank() ? null : tr("pickerKeys." + what, keys);
    }

    /**
     * The picker legend for the app-wide keymap: "move", the picker's own entries (nulls skipped), "cancel".
     * Built when the card is shown, so it follows a live keymap switch.
     */
    static String legend(String... entries) {
        return legend(TextInputKeymap.sharedKeymap(), entries);
    }

    static String legend(KeymapManager keymap, String... entries) {
        List<String> parts = new ArrayList<>();
        parts.add(hint("move", withChords("↑↓", keymap, "nav.lineDown", "nav.lineUp")));
        for (String entry : entries) {
            if (entry != null) {
                parts.add(entry);
            }
        }
        parts.add(hint("cancel", withChords("esc", keymap, KeyDispatcher.CANCEL)));
        return String.join(SEPARATOR, parts);
    }

    /** {@code fixed}, followed by {@code " / "} and the keymap's chords for {@code commandIds} when all are bound. */
    static String withChords(String fixed, KeymapManager keymap, String... commandIds) {
        String chords = chords(keymap, commandIds);
        return chords == null ? fixed : fixed + " / " + chords;
    }

    /** The formatted chords for {@code commandIds}, space-separated, or null unless every one is bound. */
    static String chords(KeymapManager keymap, String... commandIds) {
        if (keymap == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String id : commandIds) {
            String chord = keymap.displayChord(id);
            if (chord == null || chord.isEmpty()) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(chord);
        }
        return sb.toString();
    }

    /**
     * The first of {@code candidates} (raw chord tokens) the keymap leaves free — neither bound nor a prefix —
     * so a picker-local key (the palette's "docs") is one that actually reaches the picker. Null when every
     * candidate is taken; with no keymap, the first candidate.
     */
    static String freeChord(KeymapManager keymap, String... candidates) {
        for (String candidate : candidates) {
            if (keymap == null || (keymap.commandFor(candidate) == null && !keymap.isPrefix(candidate))) {
                return candidate;
            }
        }
        return null;
    }
}
