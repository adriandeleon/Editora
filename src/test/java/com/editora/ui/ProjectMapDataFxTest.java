package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Region;

import com.editora.config.WorkspaceState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loading, search, expansion state and Project-panel integration of the Project Map. */
class ProjectMapDataFxTest {

    @TempDir
    Path tempDir;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @org.junit.jupiter.api.BeforeEach
    void normalizeRoot() {
        root = tempDir.toAbsolutePath().normalize();
    }

    // --- Project panel integration ---------------------------------------------------------------

    @Test
    void theMapListsNothingUntilFirstShownAndReloadsEveryTimeItIsEntered() throws Exception {
        Path readme = Files.writeString(root.resolve("README.md"), "# Test");
        ProjectPanel panel = newPanel();
        try {
            ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(mapView, "surface");
            AtomicLong generation = FxTestSupport.field(mapView, "generation");
            FxTestSupport.runOnFx(() -> {
                panel.setRoot(root);
                panel.refreshTree();
                assertEquals(0, generation.get(), "a window that never shows the Map must not list for it");
            });

            showMap(panel, true);
            waitForFx(() -> contains(surface, readme));

            showMap(panel, false);
            Files.delete(readme);
            Path added = Files.writeString(root.resolve("ADDED.md"), "new");
            FxTestSupport.runOnFx(() -> {
                long before = generation.get();
                FxTestSupport.<ToggleButton>field(panel, "mapModeButton").setSelected(true);
                assertTrue(generation.get() > before, "entering Map mode must reload it");
            });
            waitForFx(() -> contains(surface, added) && !contains(surface, readme));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aRoundTripThroughTheMapKeepsTheTreesExpansionAndSelection() throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path file = Files.writeString(src.resolve("A.java"), "class A {}");
        ProjectPanel panel = newPanel();
        try {
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            FxTestSupport.runOnFx(() -> panel.setRoot(root));
            waitForFx(() -> child(tree.getRoot(), src) != null);
            FxTestSupport.runOnFx(() -> child(tree.getRoot(), src).setExpanded(true));
            waitForFx(() -> child(child(tree.getRoot(), src), file) != null);
            TreeItem<Path> rootItem = FxTestSupport.callOnFx(tree::getRoot);
            FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(child(child(tree.getRoot(), src), file)));

            showMap(panel, true);
            showMap(panel, false);

            FxTestSupport.runOnFx(() -> {
                assertSame(tree, panel.getChildren().getLast());
                assertSame(rootItem, tree.getRoot(), "the tree must not be rebuilt from the root");
                assertTrue(child(tree.getRoot(), src).isExpanded());
                assertEquals(file, tree.getSelectionModel().getSelectedItem().getValue());
            });
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void anInAppFileChangeDuringASearchReloadsTheMap() throws Exception {
        Path folder = Files.createDirectory(root.resolve("d"));
        Path keep = Files.writeString(folder.resolve("keep.txt"), "keep");
        Path other = Files.writeString(folder.resolve("other.txt"), "other");
        ProjectPanel panel = newPanel();
        try {
            ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(mapView, "surface");
            AtomicLong generation = FxTestSupport.field(mapView, "generation");
            FxTestSupport.runOnFx(() -> panel.setRoot(root));
            showMap(panel, true);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<TextField>field(panel, "filterField").setText("keep");
                FxTestSupport.<javafx.animation.PauseTransition>field(panel, "filterDebounce")
                        .stop();
                FxTestSupport.invoke(panel, "rebuildBody");
            });
            waitForFx(() -> contains(surface, keep) && contains(surface, other));

            Files.delete(other); // what the context menu's Delete does, before it refreshes the panel
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(panel, "markLocalChange");
                long before = generation.get();
                FxTestSupport.invoke(panel, "refreshAfterChange");
                assertTrue(generation.get() > before, "the Map must be reloaded, not only searched again");
            });
            waitForFx(() -> !contains(surface, other) && contains(surface, keep));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void theMapFollowsTheShowHiddenFilesSetting() throws Exception {
        Path dotGit = Files.createDirectory(root.resolve(".git"));
        Path src = Files.createDirectory(root.resolve("src"));
        ProjectPanel panel = newPanel();
        try {
            ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> panel.setRoot(root));
            showMap(panel, true);
            waitForFx(() -> contains(surface, src));
            FxTestSupport.runOnFx(() -> {
                assertFalse(contains(surface, dotGit), "hidden entries are not even loaded while the setting is off");
                assertFalse(hiddenCheckBox(surface, root).isSelected(), "the column starts on the setting");
                assertEquals(
                        tr("project.map.column.showHidden"),
                        hiddenCheckBox(surface, root).getText());
            });

            FxTestSupport.runOnFx(() -> panel.setShowHidden(true));
            waitForFx(() -> contains(surface, dotGit));
            FxTestSupport.runOnFx(() -> assertTrue(hiddenCheckBox(surface, root).isSelected()));

            // One column's own choice: off again for this column only, and its folder is listed anew.
            FxTestSupport.runOnFx(() -> hiddenCheckBox(surface, root).fire());
            waitForFx(() -> !contains(surface, dotGit) && contains(surface, src));
            FxTestSupport.runOnFx(() -> hiddenCheckBox(surface, root).fire());
            waitForFx(() -> contains(surface, dotGit));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void chipsMarkCollapsedFoldersAndFoldersCarryTheirOwnMarkers() throws Exception {
        Path a = Files.createDirectory(root.resolve("a"));
        Path deep = Files.createDirectories(a.resolve("b"));
        Path open = Files.writeString(deep.resolve("open.txt"), "open");
        Path noted = Files.writeString(deep.resolve("noted.txt"), "noted");
        Path marked = Files.createDirectory(root.resolve("marked"));
        Path plain = Files.createDirectory(root.resolve("plain"));
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel value =
                    new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false, open::equals);
            value.setOpenFiles(() -> List.of(open));
            value.setMarkerActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path file) {
                    return marked.equals(file);
                }

                @Override
                public boolean hasPersonalNotes(Path file) {
                    return noted.equals(file) || marked.equals(file);
                }

                @Override
                public void addBookmark(Path file) {}

                @Override
                public void addPersonalNote(Path file) {}

                @Override
                public java.util.Collection<Path> markedPaths() {
                    return List.of(marked, noted);
                }
            });
            return value;
        });
        try {
            ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> panel.setRoot(root));
            showMap(panel, true);
            waitForFx(() -> contains(surface, a) && contains(surface, marked));

            FxTestSupport.runOnFx(() -> {
                mapView.refreshStates();
                assertTrue(
                        FxTestSupport.<Set<Path>>field(surface, "bookmarkedPaths")
                                .contains(marked),
                        "a folder bookmark shows in the Map as it does in the Tree");
                assertTrue(FxTestSupport.<Set<Path>>field(surface, "notedPaths").contains(marked));

                ToggleButton openChip = FxTestSupport.field(mapView, "openFilter");
                openChip.fire();
                Set<Path> emphasized = FxTestSupport.field(surface, "emphasized");
                assertTrue(emphasized.contains(a), "the collapsed folder that holds the open file");
                assertFalse(emphasized.contains(plain));
                assertFalse(emphasized.contains(marked));
                openChip.fire();

                FxTestSupport.<ToggleButton>field(mapView, "personalNotesFilter")
                        .fire();
                emphasized = FxTestSupport.field(surface, "emphasized");
                assertTrue(emphasized.contains(a), "the collapsed folder that holds the noted file");
                assertTrue(emphasized.contains(marked), "the folder with its own note");
                assertFalse(emphasized.contains(plain));
                FxTestSupport.<ToggleButton>field(mapView, "personalNotesFilter")
                        .fire();

                FxTestSupport.<ToggleButton>field(mapView, "bookmarksFilter").fire();
                emphasized = FxTestSupport.field(surface, "emphasized");
                assertTrue(emphasized.contains(marked));
                assertFalse(emphasized.contains(a));
            });
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void treeOrMapAndTheNavigationOptionsAreRememberedPerWorkspace() throws Exception {
        Files.createDirectory(root.resolve("src"));
        WorkspaceState state = new WorkspaceState();
        state.setProjectViewMode(WorkspaceState.PROJECT_VIEW_MAP);
        state.setProjectMapKeepZoom(false);
        AtomicInteger saves = new AtomicInteger();
        ProjectPanel panel = newPanel();
        try {
            ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> {
                panel.setRememberedMapState(() -> state, saves::incrementAndGet);
                panel.setRoot(root);

                assertTrue(panel.isMapMode(), "the workspace last showed the Map");
                assertSame(mapView, panel.getChildren().getLast());
                assertFalse(FxTestSupport.<javafx.scene.control.CheckMenuItem>field(mapView, "keepZoomOnOpen")
                        .isSelected());
                assertFalse((boolean) FxTestSupport.field(surface, "keepZoomOnColumnOpen"));
                assertTrue((boolean) FxTestSupport.field(surface, "focusNewColumn"));
                assertEquals(0, saves.get(), "restoring is not a change");

                panel.toggleMapView();
                assertFalse(panel.isMapMode());
                assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
                assertEquals(1, saves.get());

                panel.toggleMapView();
                assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());

                FxTestSupport.<javafx.scene.control.CheckMenuItem>field(mapView, "focusNewColumn")
                        .setSelected(false);
                assertFalse(state.isProjectMapFocusNewColumn());
                FxTestSupport.<javafx.scene.control.CheckMenuItem>field(mapView, "keepZoomOnOpen")
                        .setSelected(true);
                assertTrue(state.isProjectMapKeepZoom());

                assertTrue(
                        FxTestSupport.<ToggleButton>field(panel, "mapModeButton")
                                .isFocusTraversable(),
                        "the Tree/Map switch must be reachable with Tab");
                assertTrue(FxTestSupport.<ToggleButton>field(panel, "treeMode").isFocusTraversable());
            });
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    // --- Loading and limits ----------------------------------------------------------------------

    @Test
    void activatingTheMoreRowLoadsTheNextChunkOfThatFolder() throws Exception {
        Path big = folderWithFiles("big", 350);
        Path moreRow = big.resolve(ProjectMapModel.PLACEHOLDER_NAME);
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, big));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, big)));
            waitForFx(() -> contains(surface, moreRow));

            FxTestSupport.runOnFx(() -> {
                ProjectMapModel.Entry more = entry(surface, moreRow);
                assertTrue(more.isMore());
                assertEquals(tr("project.map.row.more", 50), more.name());
                assertFalse(contains(surface, big.resolve(fileName(349))));
                assertTrue(FxTestSupport.<Set<Path>>field(surface, "expandedSnapshot")
                        .contains(big));
                assertNull(
                        FxTestSupport.<java.util.function.Function<ProjectMapModel.Entry, ?>>field(
                                        surface, "contextMenuFactory")
                                .apply(more),
                        "a more row is not a file and has no file menu");
                // Keyboard: the row is selected, then activated.
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, moreRow);
                activate(mapView, more);
            });
            waitForFx(() -> contains(surface, big.resolve(fileName(349))) && !contains(surface, moreRow));
            FxTestSupport.runOnFx(() -> assertEquals(
                    big.resolve(fileName(349)),
                    FxTestSupport.field(surface, "selected"),
                    "the selection moves to the last row once the more row is gone"));
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void aFolderTheOverallLimitLeavesEmptyIsNotShownExpandedAndTheUserIsTold() throws Exception {
        List<Path> folders = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            folders.add(folderWithFiles(name, 300));
        }
        Path last = folderWithFiles("e", 10);
        List<String> statuses = new CopyOnWriteArrayList<>();
        ProjectMapView mapView = newMap(statuses);
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, last));
            for (Path folder : folders) {
                FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, folder)));
                waitForFx(() -> contains(surface, folder.resolve(fileName(0))));
            }
            String limit = tr("project.map.status.limit", ProjectMapModel.MAX_VISIBLE_ITEMS);
            assertEquals(List.of(limit), statuses, "said once, when the fourth folder was cut short");

            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, last)));
            waitForFx(() -> statuses.size() == 2);

            assertEquals(limit, statuses.getLast());
            FxTestSupport.runOnFx(() -> {
                assertFalse(contains(surface, last.resolve(fileName(0))));
                assertFalse(
                        FxTestSupport.<Set<Path>>field(surface, "expandedSnapshot")
                                .contains(last),
                        "no rows loaded, so the folder must not look open");
                assertFalse(mapView.expandedDirectories().contains(last));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void anEmptyFolderOpensAStubColumnThatDoesNothingWhenActivated() throws Exception {
        Path empty = Files.createDirectory(root.resolve("empty"));
        Path stub = empty.resolve(ProjectMapModel.PLACEHOLDER_NAME);
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            AtomicLong generation = FxTestSupport.field(mapView, "generation");
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 700, 420);
                mapView.resize(700, 420);
                mapView.layout();
                mapView.setRoot(root);
            });
            waitForFx(() -> contains(surface, empty));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, empty)));
            waitForFx(() -> contains(surface, stub));

            FxTestSupport.runOnFx(() -> {
                mapView.layout();
                ProjectMapModel.Entry row = entry(surface, stub);
                assertEquals(tr("project.map.row.emptyFolder"), row.name());
                assertTrue(FxTestSupport.<Set<Path>>field(surface, "expandedSnapshot")
                        .contains(empty));
                assertTrue(
                        FxTestSupport.<List<?>>field(surface, "columnBoxes").size() >= 3,
                        "the empty folder gets a column of its own");
                long before = generation.get();
                activate(mapView, row);
                assertEquals(before, generation.get(), "a stub row is inert");
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void queuedLoadsThatWereSupersededAreSkippedAndASlowLoadIsVisible() throws Exception {
        Files.createDirectory(root.resolve("src"));
        ProjectMapView mapView = newMap(new ArrayList<>());
        CountDownLatch release = new CountDownLatch(1);
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            ExecutorService loader = FxTestSupport.field(mapView, "loader");
            loader.submit(() -> {
                release.await();
                return null;
            });
            FxTestSupport.runOnFx(() -> {
                mapView.setRoot(root);
                for (int request = 0; request < 9; request++) {
                    mapView.refresh();
                }
                assertTrue((boolean) FxTestSupport.field(surface, "loadPending"));
                assertTrue((boolean) FxTestSupport.field(mapView, "loadInFlight"));
            });
            // The listing is "slow": after the notice delay the map says it is loading.
            waitForFx(() -> FxTestSupport.<javafx.scene.control.Label>field(mapView, "loadingLabel")
                    .isVisible());

            release.countDown();
            waitForFx(() -> contains(surface, root.resolve("src")));

            assertEquals(1, mapView.loadsStartedForTest.get(), "ten requests, one listing");
            FxTestSupport.runOnFx(() -> {
                assertFalse((boolean) FxTestSupport.field(surface, "loadPending"));
                assertFalse(FxTestSupport.<javafx.scene.control.Label>field(mapView, "loadingLabel")
                        .isVisible());
            });
        } finally {
            release.countDown();
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void aReloadAfterDisposeIsIgnored() throws Exception {
        ProjectMapView mapView = newMap(new ArrayList<>());
        FxTestSupport.runOnFx(() -> {
            mapView.setRoot(root);
            mapView.dispose();
            mapView.refresh(); // threw RejectedExecutionException on the FX thread
            mapView.setShowHidden(false);
        });
    }

    // --- Search ----------------------------------------------------------------------------------

    @Test
    void aSearchWithMoreMatchesThanFitSelectsTheFirstAndSaysHowManyAreShown() throws Exception {
        List<Path> matches = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            Path leaf = Files.createDirectories(root.resolve(String.format("d%03d/x/y/z", index)));
            matches.add(Files.createFile(leaf.resolve("hit.txt")));
        }
        List<String> statuses = new CopyOnWriteArrayList<>();
        ProjectMapView mapView = newMap(statuses);
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, root.resolve("d000")));
            FxTestSupport.runOnFx(() -> {
                mapView.setQuery("hit");
                mapView.setSearchMatches("hit", matches);
            });
            waitForFx(() -> contains(surface, matches.getFirst()) && !statuses.isEmpty());

            FxTestSupport.runOnFx(() -> assertEquals(matches.getFirst(), FxTestSupport.field(surface, "selected")));
            assertEquals(List.of(tr("project.map.status.matchesShown", 239, 300)), statuses);
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void closingASearchOpenedFolderWorksAndNeverLeaksIntoTheManualSet() throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path main = Files.createDirectory(src.resolve("main"));
        Path hit = Files.writeString(main.resolve("Hit.java"), "class Hit {}");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path guide = Files.writeString(docs.resolve("guide.md"), "# Guide");
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            Set<Path> manual = FxTestSupport.field(mapView, "expanded");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, docs));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, docs)));
            waitForFx(() -> contains(surface, guide));

            FxTestSupport.runOnFx(() -> {
                mapView.setQuery("Hit");
                mapView.setSearchMatches("Hit", List.of(hit));
            });
            waitForFx(() -> contains(surface, hit));
            FxTestSupport.runOnFx(() -> assertEquals(hit, FxTestSupport.field(surface, "selected")));

            // The column's close button.
            FxTestSupport.runOnFx(() -> FxTestSupport.call(mapView, "closeColumn", new Class<?>[] {Path.class}, main));
            waitForFx(() -> !contains(surface, hit));
            FxTestSupport.runOnFx(() -> {
                assertFalse(mapView.expandedDirectories().contains(main));
                assertEquals(Set.of(root, docs), manual);
            });

            // The folder's chevron: closes src, then opens it again as the search had it.
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, src)));
            waitForFx(() -> !contains(surface, main));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, src)));
            waitForFx(() -> contains(surface, main));
            FxTestSupport.runOnFx(() -> assertEquals(Set.of(root, docs), manual));

            // The same query re-run (an in-app file change) must not take the selection back.
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, guide);
                mapView.setSearchMatches("Hit", List.of(hit));
                assertEquals(guide, FxTestSupport.field(surface, "selected"));
            });

            FxTestSupport.runOnFx(() -> mapView.setQuery(""));
            waitForFx(() -> !contains(surface, main) && contains(surface, guide));
            FxTestSupport.runOnFx(() -> {
                assertEquals(Set.of(root, docs), mapView.expandedDirectories());
                assertEquals(Set.of(root, docs), manual, "clearing the query restores exactly the manual set");
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    // --- Expansion state -------------------------------------------------------------------------

    @Test
    void revealingAnUnloadedPathKeepsOtherBranchesOpenAndForgetsPathsThatAreGone() throws Exception {
        Path a = Files.createDirectory(root.resolve("a"));
        Path b = Files.createDirectory(root.resolve("b"));
        Path c = Files.createDirectory(root.resolve("c"));
        Path x = Files.writeString(a.resolve("x.txt"), "x");
        Path y = Files.writeString(b.resolve("y.txt"), "y");
        Path z = Files.writeString(c.resolve("z.txt"), "z");
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, a));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, a)));
            waitForFx(() -> contains(surface, x));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, b)));
            waitForFx(() -> contains(surface, y));

            FxTestSupport.runOnFx(() -> mapView.revealPath(z));
            waitForFx(() -> z.equals(FxTestSupport.field(surface, "selected")));
            FxTestSupport.runOnFx(() -> {
                assertEquals(Set.of(root, a, b, c), mapView.expandedDirectories(), "a and b must stay open");
                assertTrue(contains(surface, x) && contains(surface, y));
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, z); // as a click records it
                mapView.revealPath(y); // loaded: selected at once, and recorded after z
                assertEquals(y, FxTestSupport.field(surface, "selected"));
            });

            // z is deleted and its column closed; Back then asks for a path that no longer resolves.
            Files.delete(z);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(mapView, "closeColumn", new Class<?>[] {Path.class}, c));
            waitForFx(() -> !contains(surface, z));
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, y);
                assertTrue(FxTestSupport.<List<Path>>field(mapView, "selectionHistory")
                        .contains(z));
                FxTestSupport.<Button>field(mapView, "backButton").fire();
            });
            waitForFx(() -> !(boolean) FxTestSupport.field(mapView, "loadInFlight")
                    && mapView.expandedDirectories().contains(c));
            FxTestSupport.runOnFx(() -> {
                assertNull(FxTestSupport.field(mapView, "pendingSelection"), "must not stay armed for a dead path");
                assertFalse(FxTestSupport.<List<Path>>field(mapView, "selectionHistory")
                        .contains(z));
                assertEquals(y, FxTestSupport.field(surface, "selected"));
                assertTrue(mapView.expandedDirectories().containsAll(Set.of(a, b)));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void expansionFollowsAnInAppRenameAndForgetsFoldersThatAreGone() throws Exception {
        Path a = Files.createDirectory(root.resolve("a"));
        Path sub = Files.createDirectory(a.resolve("sub"));
        Path leaf = Files.writeString(sub.resolve("leaf.txt"), "leaf");
        Path renamed = root.resolve("renamed");
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(root));
            waitForFx(() -> contains(surface, a));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, a)));
            waitForFx(() -> contains(surface, sub));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, sub)));
            waitForFx(() -> contains(surface, leaf));

            Files.move(a, renamed);
            FxTestSupport.runOnFx(() -> {
                mapView.pathRenamed(a, renamed);
                mapView.refresh();
            });
            waitForFx(() -> contains(surface, renamed.resolve("sub/leaf.txt")));
            FxTestSupport.runOnFx(() -> assertEquals(
                    Set.of(root, renamed, renamed.resolve("sub")),
                    mapView.expandedDirectories(),
                    "the renamed folder and what was open under it stay open"));

            // Deleted outside the editor: its expansion must not wait for the name to come back.
            deleteTree(renamed);
            FxTestSupport.runOnFx(mapView::refresh);
            waitForFx(() -> mapView.expandedDirectories().equals(Set.of(root)));
            Files.createDirectories(renamed.resolve("sub"));
            FxTestSupport.runOnFx(mapView::refresh);
            waitForFx(() -> contains(surface, renamed));
            FxTestSupport.runOnFx(() -> assertFalse(contains(surface, renamed.resolve("sub"))));
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void returningToAFolderRestoresItsOpenBranches() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path inner = Files.createDirectory(first.resolve("inner"));
        Path file = Files.writeString(inner.resolve("f.txt"), "f");
        Path second = Files.createDirectory(root.resolve("second"));
        Path other = Files.writeString(second.resolve("g.txt"), "g");
        ProjectMapView mapView = newMap(new ArrayList<>());
        try {
            Region surface = FxTestSupport.field(mapView, "surface");
            FxTestSupport.runOnFx(() -> mapView.setRoot(first));
            waitForFx(() -> contains(surface, inner));
            FxTestSupport.runOnFx(() -> activate(mapView, entry(surface, inner)));
            waitForFx(() -> contains(surface, file));
            FxTestSupport.runOnFx(() -> FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, file));

            // The window with no project: the root follows the active tab's folder.
            FxTestSupport.runOnFx(() -> mapView.setRoot(second));
            waitForFx(() -> contains(surface, other));
            FxTestSupport.runOnFx(() -> mapView.setRoot(first));
            waitForFx(() -> contains(surface, file));
            FxTestSupport.runOnFx(() -> {
                assertEquals(Set.of(first, inner), mapView.expandedDirectories());
                assertEquals(file, FxTestSupport.field(surface, "selected"));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static ProjectPanel newPanel() throws Exception {
        return FxTestSupport.callOnFx(() -> new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false));
    }

    private static ProjectMapView newMap(List<String> statuses) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(statuses::add);
            return view;
        });
    }

    private static void showMap(ProjectPanel panel, boolean map) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.<ToggleButton>field(panel, map ? "mapModeButton" : "treeMode")
                .setSelected(true));
    }

    private static boolean contains(Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
    }

    private static ProjectMapModel.Entry entry(Region surface, Path path) {
        return FxTestSupport.<List<ProjectMapModel.Entry>>field(surface, "entries").stream()
                .filter(candidate -> candidate.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not loaded: " + path));
    }

    private static void activate(ProjectMapView mapView, ProjectMapModel.Entry entry) {
        FxTestSupport.call(mapView, "activate", new Class<?>[] {ProjectMapModel.Entry.class}, entry);
    }

    private static CheckBox hiddenCheckBox(Region surface, Path parent) {
        Map<ProjectMapModel.ColumnId, ?> controls = FxTestSupport.field(surface, "columnControls");
        Object column = controls.entrySet().stream()
                .filter(candidate -> parent.equals(candidate.getKey().parent()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
        return (CheckBox) FxTestSupport.call(column, "showHidden", new Class<?>[0]);
    }

    private static TreeItem<Path> child(TreeItem<Path> parent, Path value) {
        if (parent == null) {
            return null;
        }
        return parent.getChildren().stream()
                .filter(item -> value.equals(item.getValue()))
                .findFirst()
                .orElse(null);
    }

    private Path folderWithFiles(String name, int count) throws IOException {
        Path folder = Files.createDirectory(root.resolve(name));
        for (int index = 0; index < count; index++) {
            Files.createFile(folder.resolve(fileName(index)));
        }
        return folder;
    }

    private static String fileName(int index) {
        return String.format("file%04d.txt", index);
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(FxTestSupport.callOnFx(condition::getAsBoolean), "timed out waiting for the FX state");
    }
}
