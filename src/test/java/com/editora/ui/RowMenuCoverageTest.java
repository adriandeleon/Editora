package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every list, tree and table in the app's windows has a right-click menu (MENU-05, Pauta 6.0.1), or is one the rule
 * leaves out, named here with the reason. Read from the source: a control declared {@code X<…> name = new X<>…}
 * passes when its file gives {@code name} a menu, through {@link ListRowCommands#install}, {@link RowMenus#install},
 * {@code setContextMenu}, {@code setOnContextMenuRequested}, or {@link RowContextMenu} for a menu on its cells.
 */
@Tag("guard")
class RowMenuCoverageTest {

    /** {@code File.field} of a control left without a menu, and why. Each must still be one, and still have none. */
    private static final Map<String, String> LEFT_OUT = Map.ofEntries(
            // The overlay list of a picker or a palette: outside the rule.
            Map.entry("CompletionPopup.list", "picker"),
            Map.entry("CodeActionPopup.list", "picker"),
            Map.entry("BuildActionsPopup.list", "picker"),
            Map.entry("CommandPalette.list", "palette"),
            Map.entry("FileFinder.list", "picker"),
            Map.entry("QuickOpen.list", "picker"),
            Map.entry("SearchEverywherePopup.list", "picker"),
            Map.entry("SearchInFilesPopup.list", "picker"),
            Map.entry("Switcher.filesList", "picker"),
            Map.entry("WorkspaceSymbolPopup.list", "picker"),
            Map.entry("MultiSelectPicker.list", "picker"),
            Map.entry("RecipientPicker.list", "picker"),
            Map.entry("MacroSettingsPane.commands", "the picker list of the Choose Command dialog"),
            // Pickers whose rows have a menu all the same, opened from the cell (BranchPopup.rowMenu, StashCell).
            Map.entry("BranchPopup.list", "picker; its cells open the row menu"),
            Map.entry("GitStashPopup.list", "picker; its cells open the row menu"),
            Map.entry("GitStashPopup.files", "a mouse-transparent read-out inside the stash picker"),
            // Lists that only navigate, switching what their view shows: outside the rule.
            Map.entry("DirectoryReviewPane.files", "navigation: picks the file whose diff is shown"),
            Map.entry("PatchReviewPane.files", "navigation: picks the file whose diff is shown"),
            // The tree previews answer the right-click on their holder (BufferPreview.showTreePreviewContextMenu).
            Map.entry("StructuredTree.tree", "the preview holder's menu (Export PDF, Print); no row actions"),
            Map.entry("XmlTree.tree", "the preview holder's menu (Export PDF, Print); no row actions"),
            // Not done yet: its rows' Set URL, Fetch and Prune have no command to run.
            Map.entry("GitManagerOverlay.list", "pending: Git remotes and work trees manager"));

    private static final Pattern DECLARATION =
            Pattern.compile("(?:ListView|TreeView|TableView|TreeTableView)<[^=;]*?>\\s+(\\w+)\\s*=\\s*new\\s+"
                    + "(?:ListView|TreeView|TableView|TreeTableView)<");

    @Test
    void everyListTreeAndTableHasARightClickMenuOrIsLeftOutForAReason() throws IOException {
        List<String> missing = new ArrayList<>();
        TreeSet<String> stale = new TreeSet<>(LEFT_OUT.keySet());
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                String type = file.getFileName().toString().replace(".java", "");
                Matcher m = DECLARATION.matcher(source);
                while (m.find()) {
                    String key = type + "." + m.group(1);
                    boolean menu = hasMenu(source, m.group(1));
                    if (LEFT_OUT.containsKey(key)) {
                        if (!menu) {
                            stale.remove(key);
                        }
                    } else if (!menu) {
                        missing.add(key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "these have no right-click menu: " + missing);
        assertTrue(
                stale.isEmpty(),
                () -> "left out here, but gone or given a menu since (drop them from the map): " + stale);
    }

    static boolean hasMenu(String source, String name) {
        String n = Pattern.quote(name);
        return Stream.of(
                        "ListRowCommands\\.install\\(\\s*" + n + "\\b",
                        "RowMenus\\.install\\(\\s*" + n + "\\b",
                        "\\b" + n + "\\.setContextMenu\\(",
                        "\\b" + n + "\\.setOnContextMenuRequested\\(",
                        "RowContextMenu\\.(?:install|anchor)\\(\\s*" + n + "\\b")
                .anyMatch(p -> Pattern.compile(p).matcher(source).find());
    }
}
