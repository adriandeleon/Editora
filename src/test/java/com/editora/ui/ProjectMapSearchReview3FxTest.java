package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import com.editora.search.ProjectFileList;
import com.editora.ui.ProjectMapSearchResult.Kind;
import com.editora.ui.ProjectMapSearchResult.Match;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review 3 search fixes in a live panel and Map: the search runs over the project's file list
 * and finds folders, paths and deep files; the list follows in-app changes; a folder match is opened; a
 * declaration match lands on its row in the file's Structure column and can open its card.
 */
@ExtendWith(DisposedPanels.class)
@Tag("fx")
class ProjectMapSearchReview3FxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    private int count;",
            "",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "",
            "    void reset() {",
            "        count = 0;",
            "    }",
            "}",
            "");

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

    @Test
    void aFolderMatchIsSelectedAndOpenedAndAPathQueryFindsADeepFile() throws Exception {
        Path scheduler = Files.createDirectories(root.resolve("cmd/kube-scheduler/app"));
        Files.writeString(scheduler.resolve("server.go"), "package app\n");
        Files.createDirectories(root.resolve("test/fixtures"));
        Files.writeString(root.resolve("test/fixtures/kube-scheduler.yaml"), "kind: Pod\n");
        Path deepFolder = Files.createDirectories(root.resolve("a/b/c/d/e/f/g/h/core/v1"));
        Path deep = Files.writeString(deepFolder.resolve("types.go"), "package v1\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");

        search(panel, "kube-scheduler");
        Path folder = root.resolve("cmd/kube-scheduler");
        waitFor(() -> folder.equals(FxTestSupport.field(surface, "selected")), "the folder match is selected");
        FxTestSupport.runOnFx(() -> {
            ProjectMapSearchResult result = view.searchResult();
            assertEquals(Kind.FOLDER, result.first().kind(), result.toString());
            assertFalse(result.partial(), "the file list covers the project");
            assertTrue(view.expandedDirectories().contains(folder), "a folder match is opened (A5)");
        });
        waitFor(() -> rowsContain(surface, scheduler), "its column lists its children");

        search(panel, "core/v1/types.go");
        waitFor(() -> deep.equals(FxTestSupport.field(surface, "selected")), "a path query lands on the deep file");
        FxTestSupport.runOnFx(() -> {
            java.util.Set<Path> lit = FxTestSupport.field(surface, "emphasized");
            assertTrue(lit.contains(deep), "a path query's match is lit, not faded");
            assertFalse(view.expandedDirectories().contains(folder), "the earlier folder match closed with its query");
        });
    }

    @Test
    void theFileListFollowsAnInAppSaveAndAFocusRegainListsAgain() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Old.java"), "class Old {}\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");

        search(panel, "Fresh");
        waitFor(() -> panel.mapFileListForTest() != null, "the first search lists the project");
        Path fresh = Files.writeString(root.resolve("src/Fresh.java"), "class Fresh {}\n");
        FxTestSupport.runOnFx(() -> panel.noteLocalWrite(fresh)); // what a first save tells the panel
        waitFor(
                () -> panel.mapFileListForTest().snapshot().files().contains("src/Fresh.java"),
                "the saved file joins the list without a new listing");
        search(panel, "Fresh");
        waitFor(() -> view.searchResult().paths().contains(fresh), "and is found");

        ProjectFileList before = FxTestSupport.callOnFx(panel::mapFileListForTest);
        Path outside = Files.writeString(root.resolve("Outside.md"), "x");
        FxTestSupport.runOnFx(panel::refreshTree); // a focus regain
        assertTrue(before.stale(), "a regain marks the list out of date");
        search(panel, "Outside");
        waitFor(() -> view.searchResult().paths().contains(outside), "the next search lists again and finds it");
        assertFalse(FxTestSupport.callOnFx(panel::mapFileListForTest).stale());
    }

    @Test
    void aDeclarationQueryLandsOnItsRowInTheFilesStructureColumnAndOpensItsCard() throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path app = Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(root.resolve("README.md"), "hello\n");
        Match add = Match.symbol(app, "src/App.java", "add", 5, "method · App");
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectPanel panel = openPanel();
        FxTestSupport.runOnFx(() -> panel.setMapSymbolSearch((searchRoot, query, open, found, status) -> {
            asked.add(query);
            found.accept(List.of(add), true);
        }));
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        ProjectMapStructure structure = FxTestSupport.field(view, "structure");

        search(panel, "@add");
        waitFor(() -> selectedSymbolNamed(surface, "add"), "the declaration row is selected (E2)");
        assertEquals(List.of("add"), asked, "the source is asked for the name without its @");
        FxTestSupport.runOnFx(() -> {
            assertTrue(structure.isOpen(app), "its file's Structure column is open");
            assertEquals(Kind.SYMBOL, view.searchResult().first().kind());
            java.util.Set<Path> lit = FxTestSupport.field(surface, "emphasized");
            assertTrue(lit.contains(app), "the declaration's file is lit");
        });

        Map<Path, ProjectMapPreview> previews = FxTestSupport.field(view, "previews");
        FxTestSupport.runOnFx(() -> view.showSearchMatch(add, true)); // what choosing the match does
        waitFor(() -> previews.keySet().stream().anyMatch(ProjectMapStructure::isSymbolPath), "its declaration card");

        search(panel, "README");
        waitFor(() -> root.resolve("README.md").equals(FxTestSupport.field(surface, "selected")), "a file query");
        FxTestSupport.runOnFx(() -> assertFalse(structure.isOpen(app), "the column the landing opened closes"));
    }

    @Test
    void everyMatchIsKeptAndOneBeyondTheOpenedFoldersCanStillBeShown() throws Exception {
        for (int index = 0; index < ProjectMapView.SEARCH_OPEN_LIMIT + 20; index++) {
            Path folder = Files.createDirectories(root.resolve(String.format("m%03d", index)));
            Files.writeString(folder.resolve("types.go"), "package m\n");
        }
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        search(panel, "types.go");
        waitFor(() -> view.searchResult().matches().size() == ProjectMapView.SEARCH_OPEN_LIMIT + 20, "all of them");
        Match last = FxTestSupport.callOnFx(() -> view.searchResult().matches().getLast());
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    ProjectMapView.SEARCH_OPEN_LIMIT + 20, view.searchResult().total());
            assertFalse(
                    view.expandedDirectories().contains(last.path().getParent()),
                    "the map opens the folders of the best few only");
            view.showSearchMatch(last, false);
        });
        waitFor(() -> last.path().equals(FxTestSupport.field(surface, "selected")), "a match past them is shown");
    }

    // --- helpers ------------------------------------------------------------------------------------

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(
                () -> rowsContain(surface, root.resolve("src"))
                        || !rows(surface).isEmpty(),
                "the first column");
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

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static boolean rowsContain(Region surface, Path path) {
        return rows(surface).stream().anyMatch(row -> row.path().equals(path));
    }

    private static boolean selectedSymbolNamed(Region surface, String name) {
        Path selected = FxTestSupport.field(surface, "selected");
        if (selected == null) {
            return false;
        }
        return rows(surface).stream()
                .anyMatch(row -> row.isSymbol()
                        && row.path().equals(selected)
                        && ProjectMapView.searchLabelNames(row.name(), name));
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
