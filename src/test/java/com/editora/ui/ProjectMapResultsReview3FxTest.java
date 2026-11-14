package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import com.editora.ui.ProjectMapSearchResult.Match;
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
 * The Project Map review 3 results fixes in a live panel and Map window: the results strip and its keys (B1, E5,
 * A9), the Map window's own status line (A2), the no-match banner (B12), "+N more" in a search (B16), what a
 * screen reader hears (B11) and clearing a search that keeps the place (B2).
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapResultsReview3FxTest {

    @TempDir
    Path temp;

    private Path root;
    private final List<Path> opened = new CopyOnWriteArrayList<>();
    private final List<String> editorStatus = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    // B1, E5, A9

    @Test
    void theStripCountsTheMatchesAndTheFieldsKeysAndF3StepThroughThem() throws Exception {
        int count = ProjectMapView.SEARCH_OPEN_LIMIT + 10;
        for (int index = 0; index < count; index++) {
            Path folder = Files.createDirectories(root.resolve(String.format("m%03d", index)));
            Files.writeString(folder.resolve("types.go"), "package m\n");
        }
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        TextField field = FxTestSupport.field(panel, "mapFilterField");
        ProjectMapResultsStrip strip = panel.mapResultsStrip();

        search(panel, "types.go");
        waitFor(() -> view.searchResult().matches().size() == count, "every match is found");
        waitFor(() -> tr("project.map.results.count", 1, count).equals(strip.countText()), "the strip says 1 of 40");
        List<Match> matches = FxTestSupport.callOnFx(() -> view.searchResult().matches());
        FxTestSupport.runOnFx(() -> {
            assertTrue(strip.isVisible());
            assertEquals(count, strip.list().getItems().size(), "the list holds every match");
            assertFalse(strip.showsPartial());
            Set<Path> open = view.expandedDirectories();
            assertTrue(open.contains(
                    matches.get(ProjectMapView.SEARCH_OPEN_LIMIT - 1).path().getParent()));
            assertFalse(
                    open.contains(
                            matches.get(ProjectMapView.SEARCH_OPEN_LIMIT).path().getParent()),
                    "the map opens the folders of the best 30 only (A9)");
        });
        waitFor(() -> matches.getFirst().path().equals(selected(surface)), "the first match is selected");

        press(field, KeyCode.ENTER, false, false);
        waitFor(() -> matches.get(1).path().equals(selected(surface)), "Enter goes to the next match");
        FxTestSupport.runOnFx(() -> assertEquals(tr("project.map.results.count", 2, count), strip.countText()));
        press(field, KeyCode.ENTER, true, false);
        waitFor(() -> matches.getFirst().path().equals(selected(surface)), "Shift+Enter to the previous");
        press(field, KeyCode.ENTER, true, false);
        Match last = matches.getLast();
        waitFor(() -> last.path().equals(selected(surface)), "and from the first it wraps to the last");
        FxTestSupport.runOnFx(() -> assertTrue(
                view.expandedDirectories().contains(last.path().getParent()),
                "the match stepped to beyond the best 30 gets its folder opened"));

        press(field, KeyCode.F3, false, false);
        waitFor(() -> matches.getFirst().path().equals(selected(surface)), "F3 in the field steps too");
        FxTestSupport.runOnFx(surface::requestFocus);
        KeyEvent f3 = press(surface, KeyCode.F3, false, false);
        assertTrue(f3.isConsumed(), "F3 belongs to the map");
        waitFor(() -> matches.get(1).path().equals(selected(surface)), "F3 on the map steps to the next match");
        press(surface, KeyCode.F3, true, false);
        waitFor(() -> matches.getFirst().path().equals(selected(surface)), "Shift+F3 to the previous");

        FxTestSupport.runOnFx(() -> strip.list().getSelectionModel().select(5));
        waitFor(() -> matches.get(5).path().equals(selected(surface)), "choosing a row of the list lands on it");
        FxTestSupport.runOnFx(() -> assertEquals(tr("project.map.results.count", 6, count), strip.countText()));

        assertTrue(opened.isEmpty(), "stepping opens nothing");
        press(field, KeyCode.ENTER, false, true);
        waitFor(() -> opened.contains(matches.get(5).path()), "Ctrl/Cmd+Enter opens the current match");

        search(panel, "");
        waitFor(() -> !strip.isVisible(), "the strip goes with the query");
    }

    @Test
    void enterTypedBeforeTheSearchRanSearchesTheNewQueryInsteadOfSteppingThroughTheOldOne() throws Exception {
        for (String name : List.of("alpha1.txt", "alpha2.txt", "beta1.txt", "beta2.txt")) {
            Files.writeString(
                    Files.createDirectories(root.resolve(name.substring(0, 4))).resolve(name), "x");
        }
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        TextField field = FxTestSupport.field(panel, "mapFilterField");
        search(panel, "alpha");
        waitFor(() -> view.searchResult().matches().size() == 2, "the first query's matches");

        // Typed, and Enter at once: the debounce has not run, so the Map still holds "alpha".
        FxTestSupport.runOnFx(() -> field.setText("beta"));
        press(field, KeyCode.ENTER, false, false);
        waitFor(() -> "beta".equals(view.searchResult().query()), "Enter searched the query typed");
        Path firstBeta = FxTestSupport.callOnFx(
                () -> view.searchResult().matches().getFirst().path());
        waitFor(() -> firstBeta.equals(selected(surface)), "and landed on its first match, not a second alpha");
    }

    // A2, B12

    @Test
    void theMapsMessagesStayInItsWindowAndTheBannerNamesTheQueryAsTyped() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path hit = Files.writeString(src.resolve("hit.txt"), "x");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Label banner = FxTestSupport.field(view, "noMatchesLabel");
        ProjectMapResultsStrip strip = panel.mapResultsStrip();

        // A partial answer with matches: the strip marks it and the Map's own line says so, with no "type more".
        typeOnly(panel, "hit");
        FxTestSupport.runOnFx(() -> {
            view.setQuery("hit");
            view.setSearchMatches("hit", List.of(hit), 1, true);
        });
        String partial = tr(
                "project.map.status.search.partial",
                com.editora.search.ProjectFileList.MAX_FILES,
                com.editora.search.ProjectFileList.MAX_DEPTH);
        waitFor(() -> panel.mapStatusLine().text().contains(partial), "the Map window says the search was partial");
        FxTestSupport.runOnFx(() -> {
            assertTrue(strip.showsPartial(), "the strip marks a partial answer");
            assertFalse(panel.mapStatusLine().text().contains("Type more"));
        });

        // No match: the banner names the query typed, and says the search was partial when it was.
        search(panel, "zzqq");
        waitFor(() -> banner.isVisible(), "the no-match banner shows");
        // (The rig respects .gitignore, so the banner goes on to say ignored files are not searched, review 3 A11.)
        FxTestSupport.runOnFx(() ->
                assertTrue(banner.getText().startsWith(tr("project.map.noMatches.query", "zzqq")), banner.getText()));
        typeOnly(panel, "zzqqx");
        FxTestSupport.runOnFx(() -> {
            view.setQuery("zzqqx");
            view.setSearchMatches(new ProjectMapSearchResult("zzqqx", List.of(), 0, true));
        });
        waitFor(
                () -> tr("project.map.noMatches.partial", "zzqqx").equals(banner.getText()),
                "a refined query is named, not the earlier one (B12), and partial is said (A2)");
        FxTestSupport.runOnFx(() -> assertEquals(tr("project.map.results.none"), strip.countText()));

        // Too short to search: said in the Map window; cleared with the query.
        search(panel, "z");
        waitFor(
                () -> tr("project.map.status.searchTooShort", ProjectPanel.MIN_MAP_SEARCH_LENGTH)
                        .equals(panel.mapStatusLine().text()),
                "the Map's line explains a one-letter query");
        search(panel, "");
        waitFor(() -> panel.mapStatusLine().text().isEmpty(), "a search's notice goes with its query");
        assertEquals(List.of(), editorStatus, "the editor window heard none of it");
    }

    // B16

    @Test
    void inASearchMoreSaysTheRowsItHidesDoNotMatchAndHidesNoMatch() throws Exception {
        Path hits = Files.createDirectories(root.resolve("hits"));
        for (int index = 0; index < ProjectMapModel.DIRECTORY_CHUNK + 20; index++) {
            Files.writeString(hits.resolve(String.format("a%03d.txt", index)), "x");
        }
        int matchCount = ProjectMapView.SEARCH_OPEN_LIMIT + 10;
        for (int index = 0; index < matchCount; index++) {
            Files.writeString(hits.resolve(String.format("zneedle%02d.txt", index)), "x");
        }
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        FxTestSupport.runOnFx(() -> FxTestSupport.<MapLoader>field(view, "mapLoader").canvasWholeFolders =
                folder -> false); // "+N more" is a remote root's way since Map explore 15
        Region surface = FxTestSupport.field(view, "surface");

        search(panel, "zneedle");
        waitFor(() -> view.searchResult().matches().size() == matchCount, "all matches");
        Path lastMatch = hits.resolve(String.format("zneedle%02d.txt", matchCount - 1));
        waitFor(() -> rowsContain(surface, lastMatch), "a match past the best 30, in an open folder, is loaded");
        Pattern notMatching = Pattern.compile(
                Pattern.quote(tr("project.map.row.moreNotMatching", 987)).replace("987", "\\E[\\d,.\\s]+\\Q"));
        waitFor(
                () -> rows(surface).stream()
                        .anyMatch(row -> row.isMore()
                                && hits.equals(row.parent())
                                && notMatching.matcher(row.name()).matches()),
                "the hidden rest of the folder reads as not matching");
        FxTestSupport.runOnFx(() -> {
            for (int index = 0; index < matchCount; index++) {
                assertTrue(rowsContain(surface, hits.resolve(String.format("zneedle%02d.txt", index))));
            }
        });
    }

    // B11

    @Test
    void aScreenReaderHearsTheFolderAndWhichMatchOfHowMany() throws Exception {
        for (String folder : List.of("pkg/proxy/iptables", "pkg/proxy/ipvs", "pkg/proxy/nftables")) {
            Files.writeString(Files.createDirectories(root.resolve(folder)).resolve("proxier.go"), "package p\n");
        }
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        search(panel, "proxier.go");
        waitFor(() -> view.searchResult().matches().size() == 3, "three matches");
        Path first = FxTestSupport.callOnFx(
                () -> view.searchResult().matches().getFirst().path());
        waitFor(() -> first.equals(selected(surface)), "the first is selected");
        String folder = root.relativize(first.getParent()).toString().replace('\\', '/');
        waitFor(
                () -> {
                    String text = surface.getAccessibleText();
                    return text != null
                            && text.contains(folder)
                            && text.contains(tr("project.map.accessible.searchMatch", 1, 3));
                },
                "the row's folder and its place among the matches are spoken");
        Label count = FxTestSupport.field(panel.mapResultsStrip(), "count");
        FxTestSupport.runOnFx(() -> assertEquals(tr("project.map.results.count", 1, 3), count.getAccessibleText()));
    }

    // B2

    @Test
    void clearingTheSearchKeepsThePlaceAndOneBackReturnsToTheRowBefore() throws Exception {
        Path readme = Files.writeString(root.resolve("README.md"), "hello\n");
        Path deepFolder = Files.createDirectories(root.resolve("a/b/c"));
        Path deep = Files.writeString(deepFolder.resolve("deep.txt"), "x");
        Path other = Files.createDirectories(root.resolve("other"));
        Path otherFile = Files.writeString(other.resolve("inside.txt"), "x");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        FxTestSupport.runOnFx(() -> view.revealPath(readme));
        waitFor(() -> readme.equals(selected(surface)), "the row before the search");

        search(panel, "deep");
        waitFor(() -> deep.equals(selected(surface)), "the search lands on its match");
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(view, "toggleDirectory", Path.class, other));
        waitFor(() -> rowsContain(surface, otherFile), "a folder opened by hand during the search");

        search(panel, "");
        waitFor(() -> view.searchResult().isEmpty() && !FxTestSupport.<Boolean>field(view, "loadInFlight"), "cleared");
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals(deep, selected(surface), "the selection stays where the search took the user");
            Set<Path> open = view.expandedDirectories();
            assertTrue(open.containsAll(List.of(root.resolve("a"), root.resolve("a/b"), deepFolder)), open.toString());
            assertTrue(open.contains(other), "the folder opened by hand stays open");
        });
        waitFor(() -> rowsContain(surface, deep) && rowsContain(surface, otherFile), "both stay on the map");

        FxTestSupport.runOnFx(surface::requestFocus);
        pressAlt(surface, KeyCode.LEFT);
        waitFor(() -> readme.equals(selected(surface)), "one Back returns to the row before the search");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(opened::add, (a, b) -> {}, x -> {}, x -> false));
            p.setOnStatus(editorStatus::add);
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        return panel;
    }

    private static void search(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
    }

    /** Puts {@code query} in the field without running the search (the test hands the Map its answer). */
    private static void typeOnly(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
        });
    }

    private static KeyEvent press(Node target, KeyCode code, boolean shift, boolean shortcut) throws Exception {
        boolean mac = com.editora.command.KeymapManager.isMac();
        KeyEvent event =
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac);
        return FxTestSupport.callOnFx(() -> {
            // A handler on the stage sees the event only if nothing consumed it on the way.
            boolean[] reachedEnd = {false};
            javafx.event.EventHandler<KeyEvent> probe = seen -> reachedEnd[0] = true;
            javafx.stage.Window window = target.getScene().getWindow();
            window.addEventHandler(KeyEvent.KEY_PRESSED, probe);
            try {
                Event.fireEvent(target, event);
            } finally {
                window.removeEventHandler(KeyEvent.KEY_PRESSED, probe);
            }
            if (!reachedEnd[0]) {
                event.consume();
            }
            return event;
        });
    }

    private static void pressAlt(Node target, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, true, false)));
    }

    private static Path selected(Region surface) {
        return FxTestSupport.field(surface, "selected");
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static boolean rowsContain(Region surface, Path path) {
        return rows(surface).stream().anyMatch(row -> row.path().equals(path));
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
