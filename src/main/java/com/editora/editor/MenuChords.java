package com.editora.editor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.collections.ListChangeListener;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * Shows a right-click menu item's keybinding in the menu's own right-hand column, the way the menu bar shows
 * it beside every command.
 *
 * <p>An item opts in by naming the command it does the same thing as ({@link #tag}); the chord is then read
 * from the active keymap — never baked into the text — and re-read each time a context menu opens, so a
 * keymap switch shows on the next right-click even for a menu built once.
 *
 * <h2>Why a display-only accelerator</h2>
 *
 * <p>A {@link MenuItem} draws its accelerator, right-aligned in a column of its own, from
 * {@link KeyCombination#getDisplayText()}. That is exactly the layout wanted, so the chord goes there — as a
 * {@link Shown} combination that only <em>displays</em>. It never matches a key event, so it can never fire the
 * item or take a keystroke from the key dispatcher, and it can hold what no real {@code KeyCombination}
 * can: an Emacs multi-key sequence like {@code C-x C-;}. The menu bar avoids accelerators for the same two
 * reasons (see {@code MainMenuBar}) and draws its chord column by hand; a context menu is a JavaFX popup on
 * every platform, so the native column is available to it.
 */
public final class MenuChords {

    /** The {@link MenuItem#getProperties()} key an item's command id is stored under. */
    private static final String KEY = "editora.menuChord.command";

    /** command id → displayed chord, in the active keymap's notation. */
    private static Supplier<Map<String, String>> chords = Map::of;

    private static boolean watching;

    private MenuChords() {}

    /**
     * The keybinding source, set by each window as it starts (the keymap is app-wide, so any one will do).
     * The first call also starts refreshing every context menu as it opens.
     */
    public static void setChords(Supplier<Map<String, String>> source) {
        useChords(source);
        if (!watching) {
            watching = true;
            Window.getWindows().addListener((ListChangeListener<Window>) c -> {
                while (c.next()) {
                    for (Window w : c.getAddedSubList()) {
                        if (w instanceof ContextMenu menu) {
                            refresh(menu.getItems());
                        }
                    }
                }
            });
        }
    }

    /** Swaps the keybinding source without watching windows — what {@link #setChords} does first; for tests. */
    static void useChords(Supplier<Map<String, String>> source) {
        chords = source;
    }

    /**
     * Marks {@code item} as doing what command {@code commandId} does, and shows that command's chord on it.
     * Tag only an exact equivalent: a chord beside an item promises that pressing it does the same thing.
     */
    public static <T extends MenuItem> T tag(T item, String commandId) {
        item.getProperties().put(KEY, commandId);
        apply(item, chords.get());
        return item;
    }

    /** The command an item was {@linkplain #tag tagged} with, or null. */
    public static @Nullable String commandOf(MenuItem item) {
        return item.getProperties().get(KEY) instanceof String id ? id : null;
    }

    /** Re-reads the chord of every tagged item in {@code items}, submenus included. */
    public static void refresh(List<MenuItem> items) {
        Map<String, String> current = chords.get();
        refresh(items, current);
    }

    private static void refresh(List<MenuItem> items, Map<String, String> current) {
        for (MenuItem item : items) {
            apply(item, current);
            if (item instanceof Menu menu) {
                refresh(menu.getItems(), current);
            }
        }
    }

    private static void apply(MenuItem item, Map<String, String> current) {
        String id = commandOf(item);
        if (id == null) {
            return;
        }
        String chord = current.get(id);
        KeyCombination shown = item.getAccelerator();
        if (chord == null || chord.isBlank()) {
            if (shown instanceof Shown) {
                item.setAccelerator(null); // the binding was removed
            }
            return;
        }
        if (!(shown instanceof Shown s && s.text.equals(chord))) {
            item.setAccelerator(new Shown(chord));
        }
    }

    /**
     * A key combination that is only text: drawn in the accelerator column, never matched. Identity equality,
     * so it can never stand in for — or displace — a real accelerator in a scene's accelerator map.
     */
    static final class Shown extends KeyCombination {
        private final String text;

        Shown(String text) {
            this.text = text;
        }

        @Override
        public boolean match(KeyEvent event) {
            return false;
        }

        @Override
        public String getDisplayText() {
            return text;
        }

        @Override
        public String getName() {
            return text;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
