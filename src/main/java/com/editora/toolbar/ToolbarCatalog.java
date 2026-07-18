package com.editora.toolbar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalog of customizable main-toolbar items and the shipped default arrangement.
 *
 * <p>Pure, toolkit-free data (no JavaFX / i18n calls): each {@link Item} carries a stable {@code id}, an
 * {@code iconKey} resolved to a JavaFX node on the UI side ({@code ui/ToolbarIcons}), and the {@code
 * commandId} the button dispatches (nullable for special widgets like the Recent MenuButton, which the
 * coordinator maps to its existing {@code @FXML} field). The {@code id} of a command button is its command
 * id; special widgets use a {@code toolbar.*} synthetic id.
 *
 * <p>The customizable region is the toolbar's <em>left icon cluster</em>. The fixed tail (project-combo
 * group, the Open-Folder icon beside it, the dev badge, About and Quit) is not part of this catalog and stays
 * pinned — see {@code MainController.arrangeToolbarTail}.
 */
public final class ToolbarCatalog {

    /** Layout token that renders a vertical toolbar separator (as opposed to an item {@link #id()}). */
    public static final String SEPARATOR = "|";

    /** A customizable toolbar item. {@code commandId} is null for a non-command widget (the Recent button). */
    public record Item(String id, String iconKey, String commandId) {}

    // Insertion order defines the "Available items" ordering in the Settings page.
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

    private static void add(String id, String iconKey, String commandId) {
        ITEMS.put(id, new Item(id, iconKey, commandId));
    }

    static {
        // --- Default toolbar buttons (mapped to existing @FXML fields by the coordinator's widget pool) ---
        add("file.new", "newFile", "file.new");
        add("template.new", "fileSheet", "template.new");
        add("file.find", "open", "file.find"); // the "Open File" finder button
        add("buffer.close", "closeTab", "buffer.close");
        add("file.save", "save", "file.save");
        add("file.saveAs", "saveAs", "file.saveAs");
        add("toolbar.recent", "recent", null); // the Recent MenuButton (special widget, no single command)
        add("file.clearRecent", "trash", "file.clearRecent");
        add("edit.undo", "undo", "edit.undo");
        add("edit.redo", "redo", "edit.redo");
        add("edit.cut", "cut", "edit.cut");
        add("edit.copy", "copy", "edit.copy");
        add("edit.paste", "paste", "edit.paste");
        add("find.show", "find", "find.show");
        add("search.inFiles", "findInFiles", "search.inFiles");
        add("view.splitVertical", "splitVertical", "view.splitVertical");
        add("view.splitHorizontal", "splitHorizontal", "view.splitHorizontal");
        add("palette.show", "palette", "palette.show");
        add("view.toggleSimpleMode", "simpleMode", "view.toggleSimpleMode");
        add("view.settings", "settings", "view.settings");

        // --- Extra command-backed icons (addable, not on the default toolbar) ---
        add("git.commit", "git", "git.commit");
        add("tool.gitLog", "gitLog", "tool.gitLog");
        add("file.run", "run", "file.run");
        add("tool.problems", "problems", "tool.problems");
        add("tool.todo", "todo", "tool.todo");
        add("tool.bookmarks", "bookmark", "tool.bookmarks");
        add("tool.structure", "structure", "tool.structure");
        add("view.toggleZen", "zen", "view.toggleZen");
    }

    private ToolbarCatalog() {}

    /** Every catalog item, in a stable order (defaults first, then extras). */
    public static List<Item> items() {
        return List.copyOf(ITEMS.values());
    }

    /** The item with this id, or {@code null} if unknown. */
    public static Item item(String id) {
        return ITEMS.get(id);
    }

    /** Whether {@code id} names a real catalog item (the {@link #SEPARATOR} token is not an item id). */
    public static boolean isKnownId(String id) {
        return ITEMS.containsKey(id);
    }

    /**
     * The shipped default toolbar arrangement (item ids + {@link #SEPARATOR} tokens), matching the current
     * hard-coded layout's left icon cluster.
     */
    public static List<String> defaultLayout() {
        List<String> l = new ArrayList<>();
        l.add("file.new");
        l.add("template.new");
        l.add("file.find");
        l.add("buffer.close");
        l.add("file.save");
        l.add("file.saveAs");
        l.add(SEPARATOR);
        l.add("toolbar.recent");
        l.add("file.clearRecent");
        l.add(SEPARATOR);
        l.add("edit.undo");
        l.add("edit.redo");
        l.add("edit.cut");
        l.add("edit.copy");
        l.add("edit.paste");
        l.add(SEPARATOR);
        l.add("find.show");
        l.add("search.inFiles");
        l.add(SEPARATOR);
        l.add("view.splitVertical");
        l.add("view.splitHorizontal");
        l.add(SEPARATOR);
        l.add("palette.show");
        l.add(SEPARATOR);
        l.add("view.toggleSimpleMode");
        l.add(SEPARATOR);
        l.add("view.settings");
        return l;
    }
}
