package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.Region;

import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import com.editora.ui.ProjectMapSearchResult.Match;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map code browser gap 10: the Map searches the files' text ({@code %text}, and Find in Files / Find in Folder… in
 * the Map window) through the window's Find in Files, the files holding the text stand out, and stepping through
 * the lines found opens each in a card on the Map — a preview the next replaces, kept with Ctrl+Enter.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class MapTextSearchFxTest {

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    // --- pure -------------------------------------------------------------------------------------------------

    @Test
    void percentSearchesTheTextAndMatchesNoRowByName() {
        ProjectMapSearch.Query text = ProjectMapSearch.Query.parse("  %  needle ");
        assertTrue(text.contents());
        assertEquals("needle", text.text());
        assertFalse(text.symbols() || text.filesOnly() || text.path() || text.foldersOnly());
        assertEquals(ProjectMapSearch.NO_NAME, text.nameText(), "only the files found stand out");
        assertEquals("", ProjectMapSearch.Query.parse("%n").nameText(), "too short to search: nothing filtered");
        assertFalse(ProjectMapSearch.Query.parse("%a/b").path(), "a slash is text, not a path");
        assertFalse(ProjectMapSearch.Query.parse("needle").contents());
        assertFalse(ProjectMapSearch.Query.parse("%needle").onlyFiles().contents());

        assertTrue(ProjectMapView.textMark("%needle").matcher("a NEEDLE here").find(), "any case");
        assertFalse(ProjectMapView.textMark("%Needle").matcher("a needle here").find(), "a capital: as typed");
        assertTrue(ProjectMapView.textMark("%a.b(").matcher("x a.b( y").find(), "the text, not a pattern");
        assertFalse(ProjectMapView.textMark("%a.b(").matcher("x axb( y").find());
    }

    @Test
    void aTextSearchsOutcomeIsOneMatchPerLineUnderTheRoot() {
        Path a = root.resolve("src/A.java");
        Path outside = root.resolveSibling("elsewhere/B.java");
        String longLine = "    " + "x".repeat(ProjectPanel.MAX_TEXT_MATCH + 20) + "   ";
        SearchService.Outcome outcome = new SearchService.Outcome(
                List.of(
                        new FileResult(
                                a,
                                List.of(new LineMatch(3, 5, 6, "  int needle;  "), new LineMatch(9, 1, 6, longLine))),
                        new FileResult(outside, List.of(new LineMatch(1, 1, 6, "needle")))),
                3,
                2,
                true);
        ProjectMapSearchResult result = ProjectPanel.textMatches("%needle", root, outcome);
        assertEquals("%needle", result.query());
        assertEquals(2, result.matches().size(), "an open buffer outside the folder is left out");
        Match first = result.matches().getFirst();
        assertEquals(ProjectMapSearchResult.Kind.TEXT, first.kind());
        assertEquals(a, first.path());
        assertEquals("src/A.java", first.relativePath());
        assertEquals(2, first.line(), "0-based");
        assertEquals("int needle;", first.detail(), "trimmed");
        assertEquals("A.java", first.label());
        assertTrue(result.matches().get(1).detail().endsWith("…"));
        assertEquals(
                ProjectPanel.MAX_TEXT_MATCH + 1,
                result.matches().get(1).detail().length());
        assertEquals(3, result.total());
        assertTrue(result.partial(), "the search stopped short");
        assertEquals(List.of(a), result.paths(), "one file, once");
        assertEquals(
                tr("project.map.results.textRow", "A.java", 3, "int needle;"), ProjectMapResultsStrip.rowText(first));
        assertTrue(ProjectPanel.textMatches("%x", root, null).matches().isEmpty(), "nothing searched");
    }

    // --- in a live Map ----------------------------------------------------------------------------------------

    @Test
    void steppingThroughTheLinesFoundOpensEachInACardThatTheNextReplaces() throws Exception {
        Path a = Files.createDirectories(root.resolve("a"));
        Path b = Files.createDirectories(root.resolve("b"));
        Path alpha = Files.writeString(
                a.resolve("Alpha.java"), "class Alpha {\n    void run() {\n        String s = \"needle\";\n    }\n}\n");
        Path beta = Files.writeString(
                b.resolve("Beta.java"),
                "class Beta {\n    void one() {\n        needle();\n    }\n\n    void two() {\n        needle();\n"
                        + "    }\n}\n");
        Files.writeString(root.resolve("README.md"), "# Notes\n\nNo Needle here, only the word.\n");
        List<SearchQuery> asked = new CopyOnWriteArrayList<>();
        List<Path> askedIn = new CopyOnWriteArrayList<>();
        SearchService service = new SearchService();
        TextSearchActions actions = new TextSearchActions(service, asked, askedIn);
        ProjectPanel panel = openPanel(actions);
        try {
            ProjectMapView view = FxTestSupport.field(panel, "mapView");
            MapCards cards = FxTestSupport.field(view, "cards");
            Region surface = FxTestSupport.field(view, "surface");
            ProjectMapResultsStrip strip = panel.mapResultsStrip();

            search(panel, "%n");
            FxTestSupport.runOnFx(() -> {
                assertTrue(asked.isEmpty(), "too short: nothing searched");
                assertEquals(
                        tr("project.map.status.textPrompt", root.getFileName().toString()), statusText(panel));
            });

            search(panel, "%needle");
            waitFor(() -> view.searchResult().matches().size() == 4, "every line with the text, any case");
            FxTestSupport.runOnFx(() -> {
                assertEquals("needle", asked.getLast().text());
                assertFalse(asked.getLast().caseSensitive());
                assertEquals(root, askedIn.getLast());
                List<Match> matches = view.searchResult().matches();
                assertTrue(matches.stream().allMatch(m -> m.kind() == ProjectMapSearchResult.Kind.TEXT));
                assertEquals(4, strip.list().getItems().size());
                assertTrue(cards.openCards().isEmpty(), "landing on the first opens no card");
            });
            List<Match> matches =
                    FxTestSupport.callOnFx(() -> view.searchResult().matches());
            int alphaHit = indexOf(matches, alpha);
            int betaHit = indexOf(matches, beta);

            FxTestSupport.runOnFx(() -> view.resultsGoTo(alphaHit, false));
            waitFor(() -> cardOf(cards, alpha) != null, "the line's card");
            waitFor(() -> "needle".equals(cardOf(cards, alpha).editor().getSelectedText()), "the text selected");
            ProjectMapPreview first = FxTestSupport.callOnFx(() -> cardOf(cards, alpha));
            FxTestSupport.runOnFx(() -> {
                assertTrue(cards.links.isPreview(first), "a preview");
                assertEquals(alpha, selected(surface), "its file selected on the map");
            });

            FxTestSupport.runOnFx(() -> view.resultsGoTo(betaHit, false));
            waitFor(() -> cardOf(cards, beta) != null, "the next line's card");
            FxTestSupport.runOnFx(() -> {
                assertFalse(cards.isOpen(first), "it replaced the last one");
                assertEquals(1, cards.openCards().size());
            });

            FxTestSupport.runOnFx(() -> view.resultsGoTo(betaHit, true)); // Ctrl+Enter: kept
            ProjectMapPreview kept = FxTestSupport.callOnFx(() -> cardOf(cards, beta));
            FxTestSupport.runOnFx(() -> {
                assertFalse(cards.links.isPreview(kept), "kept");
                view.resultsGoTo(alphaHit, false);
            });
            waitFor(() -> cardOf(cards, alpha) != null, "the first line's card again");
            FxTestSupport.runOnFx(() -> {
                assertTrue(cards.isOpen(kept), "a kept card stays");
                assertEquals(2, cards.openCards().size());
            });

            // Find in Folder… on a folder's row: the same search, in that folder.
            ContextMenu menu = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                    panel,
                    "contextMenuFor",
                    new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                    mapRow(b),
                    true,
                    false));
            MenuItem findInFolder = menu.getItems().stream()
                    .filter(item -> tr("project.menu.findInFolder").equals(item.getText()))
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> {
                assertEquals(Boolean.TRUE, findInFolder.getProperties().get(ProjectPanel.STAYS_IN_MAP));
                findInFolder.fire();
            });
            waitFor(() -> view.searchResult().matches().size() == 2, "only the folder's lines");
            FxTestSupport.runOnFx(() -> {
                assertEquals(b, askedIn.getLast());
                assertEquals(
                        "%needle",
                        FxTestSupport.<TextField>field(panel, "mapFilterField").getText());
                assertTrue(view.searchResult().matches().stream()
                        .allMatch(m -> m.path().equals(beta)));
                assertEquals(tr("project.map.status.textIn", "b"), statusText(panel));
            });

            // A capital is searched as typed; a name search forgets the folder.
            search(panel, "%Needle");
            waitFor(() -> "Needle".equals(asked.getLast().text()), "searched again");
            FxTestSupport.runOnFx(() -> assertTrue(asked.getLast().caseSensitive()));
            search(panel, "#Beta");
            FxTestSupport.runOnFx(() -> assertTrue(panel.mapFindText(null), "Find in Files typed in the Map"));
            waitFor(() -> "%Beta".equals(view.searchResult().query()), "the name typed, now the text searched for");
            FxTestSupport.runOnFx(() -> {
                assertEquals(root, askedIn.getLast(), "the whole folder again");
                assertEquals("Beta", asked.getLast().text());
                assertEquals(1, view.searchResult().matches().size(), "class Beta");
                assertTrue(
                        (Boolean) FxTestSupport.call(
                                panel, "resultsFindCommand", new Class<?>[] {String.class}, "search.inFilesPopup"),
                        "the command is the Map's");
            });
            // A search that stopped short says so.
            actions.fixed = new SearchService.Outcome(
                    List.of(new FileResult(alpha, List.of(new LineMatch(3, 20, 6, "String s = \"needle\";")))),
                    5000,
                    1,
                    true);
            search(panel, "%needle");
            waitFor(() -> view.searchResult().partial(), "the stopped search's answer");
            waitFor(
                    () -> statusText(panel).contains(tr("project.map.status.search.textPartial")),
                    "the notice says it stopped short, in Find in Files' words");
        } finally {
            service.shutdown();
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** File actions whose text search is a real Find in Files over the disk. */
    private static final class TextSearchActions implements ProjectPanel.FileActions {
        /** An answer given instead of searching; null searches. */
        SearchService.@Nullable Outcome fixed;

        private final SearchService service;
        private final List<SearchQuery> asked;
        private final List<Path> askedIn;

        TextSearchActions(SearchService service, List<SearchQuery> asked, List<Path> askedIn) {
            this.service = service;
            this.asked = asked;
            this.askedIn = askedIn;
        }

        @Override
        public void searchText(SearchQuery query, Path folder, Consumer<SearchService.@Nullable Outcome> answer) {
            asked.add(query);
            askedIn.add(folder);
            SearchService.Outcome given = fixed;
            if (given != null) {
                answer.accept(given);
                return;
            }
            service.search(query, folder, Map.of(), List.of(), List.of(), answer::accept, null);
        }

        @Override
        public boolean localHistoryEnabled() {
            return false;
        }

        @Override
        public void showLocalHistory(Path file) {}

        @Override
        public GitPathScope gitScope(Path path) {
            return GitPathScope.NONE;
        }

        @Override
        public void gitShowFileHistory(Path file) {}

        @Override
        public void gitCompareWithHead(Path file) {}

        @Override
        public void gitCompareWithBranch(Path file) {}

        @Override
        public void gitCompareWithTag(Path file) {}

        @Override
        public void gitCompareWithRevision(Path file) {}

        @Override
        public void gitAnnotate(Path file) {}

        @Override
        public void gitStage(Path file) {}

        @Override
        public void gitUnstage(Path file) {}

        @Override
        public void gitRevert(Path file) {}

        @Override
        public void gitAddToGitignore(Path file) {}
    }

    private ProjectPanel openPanel(ProjectPanel.FileActions actions) throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(f -> {}, (x, y) -> {}, x -> {}, x -> false));
            p.setFileActions(actions);
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !FxTestSupport.<List<?>>field(surface, "entries").isEmpty(), "the first column");
        return panel;
    }

    private static TreeItem<Path> mapRow(Path path) {
        try {
            Class<?> type = Class.forName("com.editora.ui.ProjectPanel$MapRowItem");
            var made = type.getDeclaredConstructor(Path.class);
            made.setAccessible(true);
            @SuppressWarnings("unchecked")
            TreeItem<Path> row = (TreeItem<Path>) made.newInstance(path);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void search(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
    }

    private static String statusText(ProjectPanel panel) {
        ProjectMapStatusLine line = FxTestSupport.field(panel, "mapStatusLine");
        return line.text();
    }

    private static @Nullable ProjectMapPreview cardOf(MapCards cards, Path file) {
        return cards.previews.values().stream()
                .filter(card -> file.equals(card.path()))
                .findFirst()
                .orElse(null);
    }

    private static int indexOf(List<Match> matches, Path file) {
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).path().equals(file)) {
                return index;
            }
        }
        throw new AssertionError("no match in " + file);
    }

    private static Path selected(Region surface) {
        return FxTestSupport.field(surface, "selected");
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
