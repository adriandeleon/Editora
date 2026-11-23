package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every row of every right-click or drop-down menu the app builds is a command's (MENU-05 and KBD-01, Pauta 6.2.0),
 * or is one of the rows left untagged here, counted per file with the reason. Read from the source: a
 * {@code new MenuItem(…)} (or {@code CheckMenuItem}, {@code RadioMenuItem}) passes when it is handed straight to
 * {@code MenuChords.tag}, {@code FocusCommands.row} / {@code also} (or the Map's), or when the variable it is assigned
 * to is passed to one of those (or to a row tagger) somewhere in its file. A row made by {@code RowMenus.row} or
 * {@code ListRowCommands} is a command's by construction and is not counted.
 *
 * <p>What it cannot see: a helper that builds a row and returns it (its callers tag it, which this does not follow),
 * and whether the command a row is tagged with really does the row's work (the tests of each panel say that). A new
 * untagged row fails it, and so does a file that has fewer untagged rows than it allows: lower the count.
 */
@Tag("guard")
class MenuRowCommandCoverageTest {

    /** {@code File}: how many untagged rows it may build, and why each is outside the rule or reached otherwise. */
    private static final Map<String, Allowed> ALLOWED = Map.ofEntries(
            allow("HttpVariableFixes", 1, "a row per fix names the fix; lsp.codeActions offers the same fixes"),
            allow("BufferSpell", 3, "two disabled placeholders; a suggestion names a word (spell.correctWord)"),
            allow("SnippetSession", 1, "the choice list of a snippet placeholder: a picker"),
            allow("TerminalView", 1, "the menu's row helper: the window ties each row (setRowTagger)"),
            allow(
                    "DebugEvaluateWindow",
                    3,
                    "the history's placeholders; a past expression names it (debug.evaluateHistory opens them)"),
            allow("DebugPanel", 1, "a placeholder, replaced as the menu shows"),
            allow(
                    "DeploymentsWindow",
                    2,
                    "a site fills the form's fields (a value in an editing window); a placeholder"),
            allow(
                    "FileBreadcrumb",
                    5,
                    "a crumb's Reveal and Open Terminal name its folder (file.revealFolderInFileManager, "
                            + "file.openTerminalInFolder); a page placeholder; the listing navigates or names a file"),
            allow("FileHistoryPanel", 1, "the row helper: its callers tag the rows"),
            allow("GitHubPanel", 1, "the row helper: its callers tag the rows"),
            allow("GitLogPanel", 1, "the row helper: Reset names a mode (git.log.reset), a tag row a tag (git.tag.*)"),
            allow("GitPanel", 3, "two placeholders; a recent message names it (git.commitMessageHistory)"),
            allow("GitStashPopup", 1, "a stash's rows name it: git.stashShow and the others pick a stash"),
            allow("HtmlPreviewToggle", 2, "a placeholder; a browser names it (htmlPreview.openIn picks one)"),
            allow(
                    "HttpClientCoordinator",
                    3,
                    "the environment menu http.selectEnvironment opens: an environment, a hint, a file to open"),
            allow("LambdaBreakpoints", 1, "a lambda's row names it (debug.toggleLambdaBreakpoint acts at the caret)"),
            allow("LazyContextMenu", 2, "a placeholder; the row helper, whose callers tag the rows"),
            allow("MainController", 1, "the menu bar's empty Open Recent placeholder"),
            allow(
                    "MainMenuBar",
                    2,
                    "the menu bar's own rows, each built for its command; a placeholder, replaced as the menu first shows"),
            allow("MavenProjectCoordinator", 1, "the row helper: mavenMenu tags each row"),
            allow("PrintPreview", 1, "a zoom choice, in the drop-down print.zoom opens"),
            allow("ProjectMapChangesCard", 1, "the card's row helper: MapMenuCommands marks the rows"),
            allow("ProjectMapTrails", 3, "a trail's rows name it: projectMap commands pick a trail"),
            allow(
                    "ProjectMapView",
                    9,
                    "rows marked by MapMenuCommands; the options are marked where the menu is built"),
            allow("ProjectMapWorkingSets", 3, "a working set's rows name it: projectMap commands pick a set"),
            allow("RemoteCoordinator", 1, "an ssh-config host fills the Connect form's field (a value in a dialog)"),
            allow("RunConfigurationsWindow", 2, "an npm script fills a field of the editing window; a placeholder"),
            allow("StructurePanel", 1, "a symbol kind, in the drop-down structure.filterKinds opens"),
            allow(
                    "ToolWindowManager",
                    2,
                    "a stripe icon's rows name its tool window (view.hideToolWindowIcon, view.splitToolWindow)"));

    private record Allowed(int count, String reason) {}

    private static Map.Entry<String, Allowed> allow(String file, int count, String reason) {
        return Map.entry(file, new Allowed(count, reason));
    }

    private static final Pattern NEW_ROW =
            Pattern.compile("new\\s+(?:javafx\\.scene\\.control\\.)?(?:Check|Radio)?MenuItem\\s*\\(");

    /** A row handed straight to what ties it: {@code tag(}, {@code row(}, {@code also(} just before {@code new}. */
    private static final Pattern WRAPPED = Pattern.compile("\\b(?:tag|row|also)\\(\\s*$");

    private static final Pattern ASSIGNED = Pattern.compile("(\\w+)\\s*=\\s*$");

    @Test
    void everyMenuRowIsACommandsOrIsCountedHereWithAReason() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                int untagged = untaggedRows(source);
                if (untagged > 0) {
                    found.put(file.getFileName().toString().replace(".java", ""), untagged);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        found.forEach((file, count) -> {
            Allowed allowed = ALLOWED.get(file);
            if (allowed == null || count > allowed.count()) {
                problems.add(file + ": " + count + " right-click or drop-down rows tied to no command (allowed "
                        + (allowed == null ? 0 : allowed.count())
                        + "). Tag each with the command that does its work (MenuChords.tag, FocusCommands.row), "
                        + "or, if it is outside MENU-05, count it in ALLOWED with the reason.");
            }
        });
        ALLOWED.forEach((file, allowed) -> {
            int count = found.getOrDefault(file, 0);
            if (count < allowed.count()) {
                problems.add(file + ": allowed " + allowed.count() + " untagged rows, now " + count + ": lower it");
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void theScanTellsATaggedRowFromAnUntaggedOne() {
        assertTrue(untaggedRows("MenuItem a = new MenuItem(\"x\");") == 1);
        assertTrue(untaggedRows("MenuItem a = new MenuItem(\"x\");\nMenuChords.tag(a, \"edit.copy\");") == 0);
        assertTrue(untaggedRows("items.add(MenuChords.tag(new MenuItem(\"x\"), \"edit.copy\"));") == 0);
        assertTrue(untaggedRows("var c = FocusCommands.row(\n        new CheckMenuItem(\"x\"), \"id\");") == 0);
        assertTrue(untaggedRows("items.add(new RadioMenuItem(\"x\"));") == 1);
    }

    /** How many rows {@code source} builds that nothing in it ties to a command. */
    static int untaggedRows(String source) {
        int untagged = 0;
        Matcher m = NEW_ROW.matcher(source);
        while (m.find()) {
            String before = source.substring(Math.max(0, m.start() - 200), m.start());
            if (WRAPPED.matcher(before).find()) {
                continue;
            }
            Matcher assigned = ASSIGNED.matcher(before);
            if (assigned.find()) {
                Pattern tied =
                        Pattern.compile("\\b(?:tag|row|also|accept)\\(\\s*" + Pattern.quote(assigned.group(1)) + "\\b");
                if (tied.matcher(source).find()) {
                    continue;
                }
            }
            untagged++;
        }
        return untagged;
    }
}
