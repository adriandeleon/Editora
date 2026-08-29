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
 * <p>The customizable region is the toolbar's <em>left icon cluster</em> — literally the {@code ToolBar},
 * which is what overflows into a chevron when the window is narrow. The fixed tail is a separate
 * container beside it, so it is not part of this catalog and cannot be pushed into that overflow — the project-combo group, the Open-Folder icon and Recent beside it, the dev
 * badge, then Settings. Recent and Settings live there rather than here because their position is relative
 * to tail items (Recent belongs beside the project controls; Settings is pinned to the right end, where its
 * glyph lines up with the right tool stripe), which a customizable-cluster entry cannot express — see
 * {@code MainController.appendFixedTail}.
 */
public final class ToolbarCatalog {

    /** Layout token that renders a vertical toolbar separator (as opposed to an item {@link #id()}). */
    public static final String SEPARATOR = "|";

    /**
     * Items with no {@code commandId}: widgets the coordinator must map to an existing {@code @FXML} node
     * (see {@code MainController.toolbarBaseWidgets}) rather than build as a generic command button.
     *
     * <p>Declared explicitly because forgetting the mapping is invisible — the item is simply left out when
     * the toolbar is rebuilt, and the control never appears. That happened with the run-configuration
     * selector, which was declared in the FXML but never registered here, so every rebuild dropped it.
     */
    public static final java.util.Set<String> SPECIAL_WIDGET_IDS = java.util.Set.of(
            "toolbar.runConfig", "toolbar.runConfig.run", "toolbar.runConfig.debug", "toolbar.runConfig.stop");

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
        add("template.new", "template", "template.new");
        add("file.find", "open", "file.find"); // the "Open File" finder button
        add("buffer.close", "closeTab", "buffer.close");
        add("file.save", "save", "file.save");
        add("file.saveAs", "saveAs", "file.saveAs");
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
        // Run configurations: special widgets, like the Recent MenuButton — the selector is a ComboBox and
        // the three buttons act on whatever it has selected rather than dispatching a fixed command, so the
        // coordinator maps all four to their existing @FXML fields.
        add("toolbar.runConfig", "run", null);
        add("toolbar.runConfig.run", "run", null);
        add("toolbar.runConfig.debug", "debug", null);
        add("toolbar.runConfig.stop", "stopSquare", null);

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
        // File — creating and persisting a document. Buffer: Close deliberately does NOT live here: it sat
        // immediately beside Save, so a mis-click on a muscle-memory target discarded the tab instead of
        // saving it. It stays available via the catalog, the tab's own ✕, and C-x k.
        l.add("file.new");
        l.add("template.new");
        l.add("file.find");
        l.add("file.save");
        l.add("file.saveAs");
        l.add(SEPARATOR);
        l.add("edit.undo");
        l.add("edit.redo");
        l.add(SEPARATOR);
        // Cut/copy/paste are deliberately NOT here. In a keyboard-driven editor they are the three actions
        // nobody reaches for with the mouse — they already sit on the standard chord, in the Edit menu and
        // in the editor's own context menu — so on the default bar they were three icons of pure noise
        // between two groups that are actually used. They remain catalog items, one drag away in
        // Settings → Toolbar for anyone who wants them back.
        l.add("find.show");
        l.add("search.inFiles");
        l.add(SEPARATOR);
        l.add("toolbar.runConfig");
        l.add("toolbar.runConfig.run");
        l.add("toolbar.runConfig.debug");
        l.add("toolbar.runConfig.stop");
        l.add(SEPARATOR);
        // View — the three chrome/layout toggles together rather than scattered behind lone separators.
        l.add("view.splitVertical");
        l.add("view.splitHorizontal");
        l.add("view.toggleSimpleMode");
        l.add(SEPARATOR);
        l.add("palette.show");
        // Recent and Settings are appended by the fixed tail, not listed here.
        return l;
    }
}
