package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Region;

import com.editora.git.GitFileStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Project Map review's data fixes, in a live view: reloads, cards across renames, matching, the watcher. */
@ExtendWith(DisposedPanels.class)
class ProjectMapDataReviewFxTest {

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

    // --- D4 ---------------------------------------------------------------------------------------------

    @Test
    void aRenameThatLandsWhileALoadIsListingKeepsTheRenamedFolderOpen() throws Exception {
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path page = Files.writeString(docs.resolve("page.md"), "# Page");
        Path moved = root.resolve("docs-moved");
        Fixture map = openMap();
        try {
            waitForRow(map, docs);
            FxTestSupport.runOnFx(() -> toggle(map.view(), docs));
            waitForRow(map, page);
            ExecutorService loader = FxTestSupport.field(map.view(), "loader");
            FxTestSupport.runOnFx(() -> {
                // A reload lists the old tree; in the same FX turn the folder is moved in the app. The stale
                // snapshot is already on its way to the FX thread when pathRenamed runs.
                map.view().refresh();
                CountDownLatch listed = new CountDownLatch(1);
                loader.submit(listed::countDown);
                try {
                    assertTrue(listed.await(10, TimeUnit.SECONDS));
                    Files.move(docs, moved);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                map.view().pathRenamed(docs, moved);
            });
            waitFor(() -> contains(map, moved.resolve("page.md")), "the moved folder's file is listed");
            FxTestSupport.runOnFx(() -> {
                assertTrue(map.view().expandedDirectories().contains(moved), "the moved folder is still open");
                assertFalse(map.view().expandedDirectories().contains(docs));
            });
        } finally {
            map.close();
        }
    }

    // --- C21 --------------------------------------------------------------------------------------------

    @Test
    void aPreviewCardFollowsAnInAppRenameOfItsFile() throws Exception {
        Path tool = Files.writeString(root.resolve("tool.py"), "print('tool')\n");
        Path renamed = root.resolve("helper.py");
        Fixture map = openMap();
        try {
            waitForRow(map, tool);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, tool);
                FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, tool);
            });
            waitFor(() -> previews(map.view()).containsKey(tool), "a card for tool.py");
            ProjectMapPreview card =
                    FxTestSupport.callOnFx(() -> previews(map.view()).get(tool));

            Files.move(tool, renamed);
            FxTestSupport.runOnFx(() -> map.view().pathRenamed(tool, renamed));

            FxTestSupport.runOnFx(() -> {
                assertTrue(previews(map.view()).get(renamed) == card, "the same card, under the new name");
                assertFalse(previews(map.view()).containsKey(tool));
                assertEquals(renamed, card.path());
            });
            waitFor(() -> contains(map, renamed), "the renamed row is listed");
            FxTestSupport.drainFx();
            TimeUnit.MILLISECONDS.sleep(300); // a card refresh would mark a missing file by now
            FxTestSupport.runOnFx(() -> {
                assertTrue(previews(map.view()).containsKey(renamed), "the card is still open");
                assertFalse(
                        card.getStyleClass().contains("project-map-preview-missing"),
                        "the card does not say its file no longer exists");
            });
        } finally {
            map.close();
        }
    }

    // --- B9 ---------------------------------------------------------------------------------------------

    @Test
    void filesInsideAnUntrackedFolderMatchTheGitChipAndCarryItsLetter() throws Exception {
        Path deep = Files.createDirectory(root.resolve("deep"));
        Path inner = Files.createDirectory(deep.resolve("inner"));
        Path file = Files.writeString(inner.resolve("new.txt"), "new");
        Path tracked = Files.writeString(root.resolve("tracked.txt"), "same");
        Fixture map = openMap();
        try {
            waitForRow(map, deep);
            FxTestSupport.runOnFx(() -> toggle(map.view(), deep));
            waitForRow(map, inner);
            FxTestSupport.runOnFx(() -> toggle(map.view(), inner));
            waitForRow(map, file);
            FxTestSupport.runOnFx(() -> {
                map.view().setGitStatus(Map.of(deep, GitFileStatus.UNTRACKED)); // git status: "? deep/"
                FxTestSupport.<javafx.scene.control.ToggleButton>field(map.view(), "gitFilter")
                        .fire();
                Set<Path> emphasized = FxTestSupport.field(map.surface(), "emphasized");
                assertTrue(emphasized.contains(file), "a file in a new folder is what changed");
                assertTrue(emphasized.contains(inner));
                assertFalse(emphasized.contains(tracked));
                assertEquals(
                        GitFileStatus.UNTRACKED,
                        FxTestSupport.call(map.surface(), "gitStatusOf", new Class<?>[] {Path.class}, file),
                        "its row carries the U letter");
            });
        } finally {
            map.close();
        }
    }

    // --- E6 ---------------------------------------------------------------------------------------------

    @Test
    void theSearchFindsADeclarationShownInAnOpenStructureColumn() throws Exception {
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Path other = Files.writeString(root.resolve("Other.md"), "# Other");
        Fixture map = openMap();
        try {
            waitForRow(map, app);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    map.view(),
                    "toggleStructure",
                    new Class<?>[] {ProjectMapModel.Entry.class},
                    entry(map.surface(), app)));
            waitFor(() -> symbol(map, "reset()") != null, "the members of App among " + names(map));
            Path type = FxTestSupport.callOnFx(() -> symbol(map, "App"));
            Path add = FxTestSupport.callOnFx(() -> symbol(map, "add(int, int)")); // labels carry parameter types
            Path reset = FxTestSupport.callOnFx(() -> symbol(map, "reset()"));
            assertNotNull(add, names(map));
            FxTestSupport.runOnFx(() -> {
                map.view().setQuery("reset"); // no file is called that: it is a method of App
                Set<Path> emphasized = FxTestSupport.field(map.surface(), "emphasized");
                assertTrue(emphasized.contains(reset), "the declaration that matches");
                assertTrue(emphasized.contains(type) && emphasized.contains(app), "and the rows that lead to it");
                assertFalse(emphasized.contains(add), "not its siblings");
                assertFalse(emphasized.contains(other));
            });
        } finally {
            map.close();
        }
    }

    // --- B8 ---------------------------------------------------------------------------------------------

    @Test
    void aColumnFilterInALongFolderFindsARowThatHadNotLoaded() throws Exception {
        Path huge = Files.createDirectory(root.resolve("huge"));
        for (int index = 0; index < 700; index++) {
            Files.createFile(huge.resolve(String.format("item-%04d.txt", index)));
        }
        Path wanted = huge.resolve("item-0650.txt");
        Fixture map = openMap();
        try {
            waitForRow(map, huge);
            FxTestSupport.runOnFx(() -> toggle(map.view(), huge));
            waitForRow(map, huge.resolve("item-0000.txt"));
            assertFalse(FxTestSupport.callOnFx(() -> contains(map, wanted)));
            FxTestSupport.runOnFx(() -> columnFilter(map.surface(), huge).setText("0650"));
            waitFor(() -> contains(map, wanted), "the filter reaches past the first 300 rows");
        } finally {
            map.close();
        }
    }

    // --- A10 --------------------------------------------------------------------------------------------

    @Test
    void openingAFolderListsOnlyThatFolderWhileTheOthersAreWatched() throws Exception {
        Path big = Files.createDirectory(root.resolve("big"));
        for (int index = 0; index < 50; index++) {
            Files.createFile(big.resolve("file" + index + ".txt"));
        }
        Path empty = Files.createDirectory(root.resolve("empty"));
        Fixture map = openMap();
        try {
            FxTestSupport.runOnFx(() -> map.view().setWatchedDirectories(Set.of(root, big, empty)));
            waitForRow(map, big);
            FxTestSupport.runOnFx(() -> toggle(map.view(), big));
            waitForRow(map, big.resolve("file0.txt"));
            FxTestSupport.drainFx();
            int before = map.view().listings.listedForTest.get();
            FxTestSupport.runOnFx(() -> toggle(map.view(), empty));
            waitFor(() -> map.view().expandedDirectories().contains(empty) && loaded(map), "the empty folder opens");
            assertEquals(
                    1,
                    map.view().listings.listedForTest.get() - before,
                    "only the folder just opened is listed; the root and big/ come from the cache");

            Path added = Files.createFile(big.resolve("added.txt"));
            FxTestSupport.runOnFx(() -> map.view().refresh(List.of(big))); // what a watcher event does
            waitFor(() -> contains(map, added), "the folder the watcher named is listed again");
        } finally {
            map.close();
        }
    }

    // --- A3 ---------------------------------------------------------------------------------------------

    @Test
    void aFolderTheRowLimitStarvesStaysOpenInTheView() throws Exception {
        List<Path> big = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            Path folder = Files.createDirectory(root.resolve(name));
            for (int index = 0; index < 300; index++) {
                Files.createFile(folder.resolve("f" + index + ".txt"));
            }
            big.add(folder);
        }
        Path late = Files.createDirectory(root.resolve("e"));
        Path leaf = Files.createFile(late.resolve("leaf.txt"));
        Fixture map = openMap();
        try {
            waitForRow(map, late);
            FxTestSupport.runOnFx(() -> toggle(map.view(), late));
            waitForRow(map, leaf);
            for (Path folder : big) {
                FxTestSupport.runOnFx(() -> toggle(map.view(), folder));
                waitFor(() -> map.view().expandedDirectories().contains(folder) && loaded(map), "open " + folder);
            }
            waitFor(() -> !contains(map, leaf) && loaded(map), "the deepest-in-queue folder is starved");
            FxTestSupport.runOnFx(() -> {
                assertTrue(map.view().expandedDirectories().contains(late), "it stays open");
                assertTrue(rows(map.surface()).stream()
                        .anyMatch(row -> late.equals(row.parent())
                                && row.isPlaceholder()
                                && row.placeholder().kind() == ProjectMapModel.PlaceholderKind.OVER_LIMIT));
            });
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(map.view(), "closeColumn", new Class<?>[] {Path.class}, big.getFirst()));
            waitFor(() -> contains(map, leaf), "closing a column brings it back");
        } finally {
            map.close();
        }
    }

    // --- A2 ---------------------------------------------------------------------------------------------

    @Test
    void anOutsideChangeRightAfterAnInAppRenameStillShows() throws Exception {
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path untracked = Files.writeString(docs.resolve("untracked.txt"), "u");
        Path renamed = docs.resolve("renamed.txt");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false));
            p.setRoot(root);
            return p;
        });
        TreeView<Path> tree = FxTestSupport.field(panel, "tree");
        TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
        waitFor(() -> child(rootItem, docs) != null, "docs/");
        FxTestSupport.runOnFx(() -> child(rootItem, docs).setExpanded(true));
        waitFor(() -> child(child(rootItem, docs), untracked) != null, "docs/untracked.txt");
        TimeUnit.MILLISECONDS.sleep(300); // the watch on docs/ is registered

        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(panel, "move", new Class<?>[] {Path.class, Path.class}, untracked, renamed));
        waitFor(() -> child(child(rootItem, docs), renamed) != null, "the renamed row");
        // Another program writes into the same folder well inside the window after the rename.
        Path external = Files.writeString(docs.resolve("aaa-external.txt"), "x");
        waitFor(() -> child(child(rootItem, docs), external) != null, "the outside file shows without another event");
    }

    // --- A11 --------------------------------------------------------------------------------------------

    @Test
    void aOneCharacterMapSearchFiltersWhatIsLoadedInsteadOfWalkingTheProject() throws Exception {
        Path deep = Files.createDirectories(root.resolve("a/b"));
        Files.writeString(deep.resolve("e.txt"), "e");
        List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false));
            p.setOnStatus(statuses::add);
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText("e");
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertFalse(mapView.expandedDirectories().contains(deep), "no folder is opened for one letter");
            assertTrue(statuses.contains(tr("project.map.status.searchTooShort", ProjectPanel.MIN_MAP_SEARCH_LENGTH)));
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText("e.");
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
        waitFor(() -> mapView.expandedDirectories().contains(deep), "two characters search the whole project");
        FxTestSupport.runOnFx(() -> panel.mapWindow().closeByUser());
    }

    // --- fixture ----------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Scene scene, Region surface) {
        void close() throws Exception {
            FxTestSupport.runOnFx(view::dispose);
        }
    }

    private Fixture openMap() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapDataReviewFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            view.resize(1400, 900);
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(root);
            return new Fixture(view, scene, FxTestSupport.field(view, "surface"));
        });
    }

    private static void toggle(ProjectMapView view, Path directory) {
        FxTestSupport.call(view, "toggleDirectory", new Class<?>[] {Path.class}, directory);
    }

    private static boolean loaded(Fixture map) {
        return !(boolean) FxTestSupport.field(map.view(), "loadInFlight");
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    private static boolean contains(Fixture map, Path path) {
        return rows(map.surface()).stream().anyMatch(row -> row.path().equals(path));
    }

    private static ProjectMapModel.Entry entry(Region surface, Path path) {
        return rows(surface).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not loaded: " + path));
    }

    private static Path symbol(Fixture map, String name) {
        return rows(map.surface()).stream()
                .filter(row -> row.isSymbol() && row.name().equals(name))
                .map(ProjectMapModel.Entry::path)
                .findFirst()
                .orElse(null);
    }

    private static String names(Fixture map) {
        try {
            return FxTestSupport.callOnFx(() -> rows(map.surface()).stream()
                    .map(ProjectMapModel.Entry::name)
                    .toList()
                    .toString());
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static Map<Path, ProjectMapPreview> previews(ProjectMapView view) {
        return FxTestSupport.field(view, "previews");
    }

    private static TextField columnFilter(Region surface, Path parent) {
        Map<ProjectMapModel.ColumnId, ?> controls = FxTestSupport.field(surface, "columnControls");
        Object column = controls.entrySet().stream()
                .filter(entry -> parent.equals(entry.getKey().parent()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column for " + parent));
        TextField filter = (TextField) FxTestSupport.call(column, "filter", new Class<?>[0]);
        assertNotNull(filter);
        return filter;
    }

    private static TreeItem<Path> child(TreeItem<Path> parent, Path path) {
        if (parent == null) {
            return null;
        }
        return parent.getChildren().stream()
                .filter(item -> path.equals(item.getValue()))
                .findFirst()
                .orElse(null);
    }

    private static void waitForRow(Fixture map, Path path) throws Exception {
        waitFor(() -> contains(map, path), "row " + path);
        FxTestSupport.drainFx();
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("timed out: " + message);
    }
}
