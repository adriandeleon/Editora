package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.Settings;
import com.editora.config.TextAnchor;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.editor.notes.NoteDraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Project Map review's window findings: the Map's own root in the No Project window (A1), its return
 * with the project (A13), work that stops while the window is closed and resumes when it shows (D1–D3), the
 * editor theme reaching an open Map (A4), New File/Folder and Rename from the Map (A7, A9), a maximized window
 * (A12), an unreadable folder in the status bar (A20), Reveal Active File (E3), the note prompt over the Map
 * (C1), output dialogs over the Map and the page note (A15, A16), and the Map at startup (A8, A17).
 */
@Tag("fx")
class ProjectMapWindowReviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static ProjectMapView map(ProjectPanel panel) {
        return FxTestSupport.field(panel, "mapView");
    }

    private static Region surface(ProjectPanel panel) {
        return FxTestSupport.field(map(panel), "surface");
    }

    private static boolean shows(ProjectPanel panel, Path path) {
        return (boolean) FxTestSupport.call(surface(panel), "contains", new Class<?>[] {Path.class}, path);
    }

    @SuppressWarnings("unchecked")
    private static Path selection(ProjectPanel panel) {
        return ((Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface(panel), "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
    }

    private static void openMap(ProjectPanelRig r) throws Exception {
        FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
        r.await("the map to list the project", () -> shows(r.panel, r.beta));
    }

    private static void waitFor(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    // --- A1 / A13: the Map's root -----------------------------------------------------------------------

    @Test
    void aPinnedMapKeepsItsFolderWhileTheTreeFollowsTheActiveTab(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.panel.setMapRootPinned(() -> true)); // the No Project window
            openMap(r);
            FxTestSupport.runOnFx(() -> map(r.panel).revealPath(r.helper));
            r.await("the revealed file on the map", () -> shows(r.panel, r.helper));
            String title =
                    FxTestSupport.callOnFx(() -> r.panel.mapWindow().stage().getTitle());

            // Opening Helper.java from the Map makes its folder the tree's root: the Map stays where it was.
            FxTestSupport.runOnFx(() -> r.panel.setRoot(r.util));
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.panel.isMapShowing());
                assertEquals(r.root, r.panel.mapRoot(), "the Map is not re-rooted by a tab switch");
                assertEquals(r.util, r.panel.getRoot(), "the tree follows the tab");
                assertTrue(shows(r.panel, r.helper), "its open branches stay open");
                assertEquals(r.helper, selection(r.panel));
                assertEquals(title, r.panel.mapWindow().stage().getTitle());
            });

            // An untitled tab: no folder for the tree, and the Map still stays.
            FxTestSupport.runOnFx(() -> r.panel.setRoot(null));
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.panel.isMapShowing(), "a tab with no folder does not close the Map");
                assertEquals(r.root, r.panel.mapRoot());
            });

            // Closed and opened again, it maps what the tree shows now.
            FxTestSupport.runOnFx(() -> {
                r.panel.mapWindow().closeByUser();
                r.panel.setRoot(r.src);
                assertEquals(r.root, r.panel.mapRoot(), "a closed Map is re-rooted when it opens, not before");
                r.panel.openMapWindow(true);
                assertEquals(r.src, r.panel.mapRoot());
            });
            r.await("the reopened Map on the tree's folder", () -> shows(r.panel, r.main));
        }
    }

    @Test
    void aMapClosedWithItsProjectComesBackWhenTheProjectDoes(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            FxTestSupport.runOnFx(() -> r.panel.setRoot(null));
            assertFalse(FxTestSupport.callOnFx(r.panel::isMapShowing), "no project, nothing to map");

            FxTestSupport.runOnFx(() -> r.panel.setRoot(r.root));
            r.await("the Map to come back with the project", r.panel::isMapShowing);
            r.await("and to list it", () -> shows(r.panel, r.beta));
        }
    }

    // --- D1 / D2 / D3: what follows the window's showing -------------------------------------------------

    @Test
    void aCardFollowsItsBufferAcrossACloseAndReopenAndAClosedMapPollsNothing(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Path file = Files.writeString(root.resolve("Live.java"), "class Live {}\n");
        AtomicReference<String> buffer = new AtomicReference<>("class Live {}\n// one\n");
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel value = new ProjectPanel(
                    path -> {},
                    (from, to) -> {},
                    path -> {},
                    path -> false,
                    file::equals,
                    path -> new ProjectMapPreview.Content(buffer.get(), false));
            value.setRoot(root);
            return value;
        });
        try {
            ProjectMapView mapView = FxTestSupport.callOnFx(() -> map(panel));
            FxTestSupport.runOnFx(() -> panel.openMapWindow(false));
            waitFor("the map to list the file", () -> shows(panel, file));
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface(panel), "setSelected", new Class<?>[] {Path.class}, file);
                FxTestSupport.call(mapView, "previewSelection", new Class<?>[] {Path.class}, file);
            });
            ProjectMapPreview card =
                    FxTestSupport.callOnFx(() -> FxTestSupport.<Map<Path, ProjectMapPreview>>field(mapView, "previews")
                            .get(file));
            waitFor("the card to show the buffer", () -> card.editor().getText().equals(buffer.get()));

            FxTestSupport.runOnFx(() -> panel.mapWindow().closeByUser());
            buffer.set("class Live {}\n// one\n// two\n");
            FxTestSupport.runOnFx(() -> {
                panel.refreshMarkers(); // a tab switch, a dirty flip, a marker change: the Map is not on screen
                panel.refreshOpenFiles();
                assertFalse(running(mapView, "cardPoll"), "a closed Map does not poll its cards");
                assertFalse(running(mapView, "cardRefresh"), "nor refresh them");
            });
            Thread.sleep(1_300); // longer than the poll interval
            assertEquals(
                    "class Live {}\n// one\n",
                    FxTestSupport.callOnFx(() -> card.editor().getText()),
                    "nothing re-read the hidden card's buffer");

            FxTestSupport.runOnFx(() -> panel.openMapWindow(false));
            waitFor(
                    "the reopened card to catch up",
                    () -> card.editor().getText().equals(buffer.get()));
            buffer.set("class Live {}\n// one\n// two\n// three\n");
            waitFor("the poll to run again", () -> card.editor().getText().equals(buffer.get()));
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void aHiddenMapSnapshotsNoRowStatesAndAShownOneLooksUpOnlyTheOpenAndMarkedPaths(@TempDir Path dir)
            throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            AtomicInteger asked = new AtomicInteger();
            ProjectPanel.MarkerActions markers = new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path file) {
                    asked.incrementAndGet();
                    return file.equals(r.beta);
                }

                @Override
                public boolean hasPersonalNotes(Path file) {
                    asked.incrementAndGet();
                    return false;
                }

                @Override
                public void addBookmark(Path file) {}

                @Override
                public void addPersonalNote(Path file) {}

                @Override
                public java.util.Collection<Path> markedPaths() {
                    return List.of(r.beta);
                }
            };
            FxTestSupport.runOnFx(() -> {
                r.panel.setOpenFiles(() -> List.copyOf(r.open));
                r.panel.setMarkerActions(markers);
            });
            openMap(r);
            r.open.add(r.alpha);
            FxTestSupport.runOnFx(() -> {
                r.panel.mapWindow().closeByUser();
                r.panel.refreshOpenFiles();
                assertEquals(
                        Set.of(), FxTestSupport.field(surface(r.panel), "openPaths"), "not looked at while hidden");
                r.panel.openMapWindow(false);
                assertEquals(Set.of(r.alpha), FxTestSupport.field(surface(r.panel), "openPaths"), "caught up on show");
                assertEquals(Set.of(r.beta), FxTestSupport.field(surface(r.panel), "bookmarkedPaths"));

                asked.set(0);
                r.panel.refreshMarkers();
                assertTrue(asked.get() <= 4, "two candidates, two questions each, not two per row: " + asked.get());
            });
        }
    }

    private static boolean running(ProjectMapView view, String timer) {
        return FxTestSupport.<PauseTransition>field(view, timer).getStatus() == Animation.Status.RUNNING;
    }

    // --- A4 / A12: the window ----------------------------------------------------------------------------

    @Test
    void anEditorThemeSwitchReachesTheOpenMapWindow(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            String sheet = "data:text/css,.root{-fx-accent:red;}";
            FxTestSupport.runOnFx(() -> {
                r.stage.getScene().getStylesheets().add(sheet); // what applyEditorTheme does to the editor scene
                Stage window = r.panel.mapWindow().stage();
                assertEquals(
                        r.stage.getScene().getStylesheets(), window.getScene().getStylesheets());
                r.stage.getScene().getStylesheets().remove(sheet);
                assertFalse(window.getScene().getStylesheets().contains(sheet), "and its removal");
            });
        }
    }

    @Test
    void aMaximizedMapWindowKeepsItsRestoreSizeAndOpensMaximizedAgain(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            state.setProjectMapWindowBounds(List.of(40.0, 30.0, 700.0, 500.0));
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}));
            openMap(r);
            FxTestSupport.drainFx();
            assertEquals(List.of(40.0, 30.0, 700.0, 500.0), FxTestSupport.callOnFx(state::getProjectMapWindowBounds));

            FxTestSupport.runOnFx(() -> r.panel.mapWindow().stage().setMaximized(true));
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            assumeTrue(FxTestSupport.callOnFx(() -> r.panel.mapWindow().isMaximized()), "the platform maximizes");
            FxTestSupport.runOnFx(() -> r.panel.mapWindow().closeByUser());
            assertTrue(state.isProjectMapWindowMaximized());
            assertEquals(
                    List.of(40.0, 30.0, 700.0, 500.0),
                    state.getProjectMapWindowBounds(),
                    "the screen's size is not the size it restores to");

            FxTestSupport.runOnFx(() -> {
                r.panel.mapWindow().stage().setMaximized(false); // a new stage would start this way
                r.panel.openMapWindow(false);
                assertTrue(r.panel.mapWindow().isMaximized(), "it opens maximized again");
            });
        }
    }

    // --- A7 / A9: file operations from the Map -----------------------------------------------------------

    @Test
    void aFolderMadeFromTheMapIsShownAndSelectedOnIt(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            FxTestSupport.runOnFx(() -> {
                Map<Path, ProjectMapModel.Entry> rows = FxTestSupport.field(surface(r.panel), "entryByPath");
                java.util.function.Function<ProjectMapModel.Entry, ContextMenu> factory =
                        FxTestSupport.field(surface(r.panel), "contextMenuFactory");
                ContextMenu menu = factory.apply(rows.get(r.docs));
                Menu newMenu = (Menu) menu.getItems().stream()
                        .filter(item -> tr("newfile.menu.new").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                MenuItem folder = newMenu.getItems().stream()
                        .filter(item -> tr("project.menu.newFolder").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                folder.fire();
                r.promptAccept.accept("assets");
            });
            Path assets = r.docs.resolve("assets");
            assertTrue(Files.isDirectory(assets));
            r.await("the new folder on the map", () -> shows(r.panel, assets));
            r.await("and selected", () -> assets.equals(selection(r.panel)));
        }
    }

    @Test
    void renamingToATakenNameSaysSoAndACaseOnlyRenameIsRecognised(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            r.answerDialogs(ButtonBar.ButtonData.OK_DONE);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface(r.panel), "select", new Class<?>[] {Path.class}, r.alpha);
                javafx.event.Event.fireEvent(
                        surface(r.panel),
                        new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                javafx.scene.input.KeyCode.F2,
                                false,
                                false,
                                false,
                                false));
                r.promptAccept.accept("beta.txt");
            });
            assertEquals(List.of(tr("project.newFolderExists", "beta.txt")), List.copyOf(r.dialogs));
            assertTrue(Files.exists(r.alpha), "nothing was renamed");
        }
        Path a = Files.writeString(dir.resolve("readme.md"), "x");
        Path link = dir.resolve("README.md");
        try {
            Files.createLink(link, a); // the same file under the other case, as a case-insensitive disk sees it
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            return;
        }
        assertTrue(ProjectPanel.caseOnlyRename(a, link));
        assertFalse(ProjectPanel.caseOnlyRename(a, dir.resolve("other.md")));
        assertFalse(ProjectPanel.caseOnlyRename(a, a));
        Files.delete(link);
        Path renamed = dir.resolve("Readme.MD");
        ProjectPanel.moveRenaming(a, renamed);
        assertTrue(Files.exists(renamed));
    }

    // --- A20 ---------------------------------------------------------------------------------------------

    @Test
    void aFolderThatCannotBeReadIsSaidInTheStatusBar(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            openMap(r);
            Path locked = Files.createDirectory(dir.resolve("locked"));
            Files.writeString(locked.resolve("inside.txt"), "x");
            try {
                Files.setPosixFilePermissions(locked, Set.of());
            } catch (UnsupportedOperationException notPosix) {
                return;
            }
            try {
                assumeTrue(!Files.isReadable(locked), "running as a user who can read anything");
                FxTestSupport.runOnFx(() -> map(r.panel).revealPath(locked.resolve("inside.txt")));
                r.await(
                        "the status line",
                        () -> r.status.contains(tr("project.map.status.unreadable", locked.getFileName())));
            } finally {
                Files.setPosixFilePermissions(
                        locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    // --- E3 ----------------------------------------------------------------------------------------------

    @Test
    void revealActiveFileOpensTheMapOnTheFileAndTheOptionFollowsTabSwitches(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.setActiveFile(r.helper);
                r.panel.revealActiveFileInMap();
                assertTrue(r.panel.isMapShowing());
            });
            r.await("the active file selected on the map", () -> r.helper.equals(selection(r.panel)));

            FxTestSupport.runOnFx(() -> r.panel.setActiveFile(r.guide));
            Thread.sleep(300);
            assertEquals(r.helper, FxTestSupport.callOnFx(() -> selection(r.panel)), "off by default: no jumping");

            FxTestSupport.runOnFx(() -> {
                assertFalse(map(r.panel).isFollowActiveFile());
                map(r.panel).setFollowActiveFile(true);
                r.panel.setActiveFile(r.main);
            });
            r.await("the opened file selected", () -> r.main.equals(selection(r.panel)));

            FxTestSupport.runOnFx(() -> {
                r.panel.mapWindow().closeByUser();
                r.panel.setActiveFile(null);
                r.panel.revealActiveFileInMap();
                assertTrue(r.status.contains(tr("status.projectMap.noActiveFile")));
            });
        }
    }

    @Test
    void aMapOpenedWithNothingSelectedStartsAtTheActiveFile(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.setActiveFile(r.guide);
                r.panel.openMapWindow(true);
            });
            r.await("the active file selected on the map", () -> r.guide.equals(selection(r.panel)));
        }
    }

    // --- C1 ----------------------------------------------------------------------------------------------

    @Test
    void aCardsAddPersonalNotePromptsOverTheMapNamingTheFileAndLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("App.java"); // written once the rig has counted its own rows
        Map<String, List<PersonalNote>> store = new java.util.LinkedHashMap<>();
        Settings settings = new Settings();
        settings.setNotesSupport(true);
        OverlayHost editorOverlay = new OverlayHost();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public OverlayHost overlayHost() {
                return editorOverlay;
            }
        };
        NotesCoordinator.Ops ops = notesOps(store);
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Files.writeString(file, "class App {\n  int x;\n  int y;\n}\n");
            FxTestSupport.runOnFx(() -> {
                editorOverlay.install(new javafx.scene.layout.StackPane());
                NotesCoordinator notes = new NotesCoordinator(host, ops);
                r.panel.setMarkerActions(new ProjectPanel.MarkerActions() {
                    @Override
                    public boolean personalNotesEnabled() {
                        return notes.isEnabled();
                    }

                    @Override
                    public boolean hasBookmarks(Path path) {
                        return false;
                    }

                    @Override
                    public boolean hasPersonalNotes(Path path) {
                        return notes.hasPersonalNotes(path);
                    }

                    @Override
                    public void addBookmark(Path path) {}

                    @Override
                    public void addPersonalNote(Path path) {
                        notes.addPersonalNote(path);
                    }

                    @Override
                    public void addPersonalNote(Path path, NoteDraft draft, OverlayHost over) {
                        notes.addPersonalNote(path, draft, over); // what ProjectTreeMarkerActions does
                    }
                });
            });
            openMap(r);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview.MarkerActions actions = FxTestSupport.field(map(r.panel), "previewMarkerActions");
                actions.addPersonalNote(
                        file, new NoteDraft(NoteScope.RANGE, new TextAnchor(1, 0, 2, 7, "int x;", "", "")));
                OverlayHost mapOverlay = r.panel.mapWindow().overlayHost();
                assertTrue(mapOverlay.isShowing(), "the prompt is over the Map, where the user clicked");
                assertFalse(editorOverlay.isShowing(), "not in the editor window behind it");
                javafx.scene.Node card = FxTestSupport.field(mapOverlay, "overlayRoot");
                assertTrue(
                        textOf(card).contains(tr("dialog.note.titleForLines", "App.java", 2, 3)),
                        "the prompt names the file and lines");
                TextArea editor = (TextArea) card.lookup(".text-area");
                editor.setText("check the bounds");
                javafx.scene.control.Button save = (javafx.scene.control.Button) card.lookupAll(".button").stream()
                        .filter(node -> node instanceof javafx.scene.control.Button b
                                && tr("dialog.save").equals(b.getText()))
                        .findFirst()
                        .orElseThrow();
                save.fire();
            });
            assertEquals(1, store.values().stream().mapToInt(List::size).sum(), "the note was added");
        }
        assertEquals(tr("dialog.note.titleFor", "src"), NotesCoordinator.noteTitleFor(Path.of("/p/src"), null));
        assertEquals(
                tr("dialog.note.titleForLine", "A.java", 5),
                NotesCoordinator.noteTitleFor(
                        Path.of("/p/A.java"), new NoteDraft(NoteScope.LINE, new TextAnchor(4, 0, 4, 0, "", "", ""))));
    }

    private static String textOf(javafx.scene.Node node) {
        StringBuilder text = new StringBuilder();
        if (node instanceof javafx.scene.control.Labeled labeled) {
            text.append(labeled.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                text.append(textOf(child));
            }
        }
        return text.toString();
    }

    private static NotesCoordinator.Ops notesOps(Map<String, List<PersonalNote>> store) {
        return new NotesCoordinator.Ops() {
            @Override
            public void openPath(Path file) {}

            @Override
            public void navigateToLine(int line) {}

            @Override
            public void openInProjectWindow(String projectKey, Path file, int line) {}

            @Override
            public String noteKey(EditorBuffer buffer) {
                return String.valueOf(buffer.getPath());
            }

            @Override
            public EditorBuffer bufferForKey(String fileKey) {
                return null;
            }

            @Override
            public EditorBuffer bufferForPath(Path file) {
                return null;
            }

            @Override
            public void installEmacsKeys(javafx.scene.control.TextInputControl control) {}

            @Override
            public void setToolWindowAvailable(boolean available) {}

            @Override
            public Map<String, List<PersonalNote>> notes() {
                return store;
            }

            @Override
            public Map<String, Map<String, List<PersonalNote>>> allNotes() {
                return Map.of("", store);
            }

            @Override
            public String currentProjectKey() {
                return "";
            }

            @Override
            public String projectName(String key) {
                return key;
            }

            @Override
            public void saveNotes() {}
        };
    }

    // --- A15 / A16 ---------------------------------------------------------------------------------------

    @Test
    void theMapsPdfDialogBelongsToTheMapWindowAndThePageNoteCountsPages(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            List<javafx.stage.Window> owners = new ArrayList<>();
            List<ProjectMapOutput> outputs = new ArrayList<>();
            CoordinatorHostStub host = new CoordinatorHostStub() {
                @Override
                public javafx.stage.Window window() {
                    return r.stage;
                }
            };
            ExportCoordinator[] exports = new ExportCoordinator[1];
            FxTestSupport.runOnFx(() -> {
                exports[0] = new ExportCoordinator(host, null, null, null, path -> {}, chooser -> {
                    owners.add(exports[0].dialogOwner());
                    return null; // cancelled: nothing is written
                });
                r.panel.setMapOutputActions(outputs::add, outputs::add);
            });
            openMap(r);
            r.await("something to export", r.panel::isMapOutputAvailable);
            FxTestSupport.runOnFx(() -> {
                r.panel.exportMapPdf();
                ProjectMapOutput output = outputs.getLast();
                assertEquals(dir.getFileName() + "-map", output.baseName(), "named for the Map's folder");
                exports[0].exportProjectMapPdf(output);
                assertSame(r.panel.mapWindow().stage(), owners.getLast(), "the Save dialog is over the Map");
                assertSame(r.stage, exports[0].dialogOwner(), "and only for the Map's own output");
                exports[0].shutdown();
            });
        }

        // A map 10% too wide for one page at 7 pt labels: shrunk a little onto one page, not split.
        ProjectMapOutputPlan.Box content = new ProjectMapOutputPlan.Box(0, 0, 1_020, 500);
        ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(content, List.of(content), 540, 720);
        assertEquals(1, plan.pages().size());
        assertTrue(plan.paperScale() * ProjectMapOutputPlan.LABEL_SIZE >= ProjectMapOutputPlan.TOLERATED_LABEL_POINTS);

        // Two columns far apart over a 2 × 2 grid: two pages, and the note says two pages, not "2 × 2".
        ProjectMapOutputPlan.Box left = new ProjectMapOutputPlan.Box(0, 0, 700, 400);
        ProjectMapOutputPlan.Box right = new ProjectMapOutputPlan.Box(800, 1_600, 700, 400);
        ProjectMapOutputPlan.Plan tiled = ProjectMapOutputPlan.plan(
                new ProjectMapOutputPlan.Box(0, 0, 1_500, 2_000), List.of(left, right), 540, 720);
        assertEquals(2, tiled.pages().size());
        assertEquals(tr("status.projectMap.outputPages", 2), ExportCoordinator.projectMapOutputNote(tiled));
    }

    // --- A8 / A17: the window's own startup and commands -------------------------------------------------

    @Test
    void aMapRememberedAsOpenReopensWithTheProjectToolWindowClosed(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":" + WorkspaceState.SCHEMA_VERSION
                        + ",\"projectViewMode\":\"MAP\",\"openToolWindows\":{}}");
        try (FxWindowFixture fx =
                FxWindowFixture.create(dir, shared -> shared.getSettings().setProjectSupport(true))) {
            ProjectPanel built = FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "projectPanel"));
            assertNotNull(built, "the panel is built at startup, for its Map window, with no tool window open");
            fx.keepConfigDir = true; // the @TempDir is JUnit's to delete
            fx.useProject();
            waitFor("the remembered Map window", built::isMapShowing);
        }
    }

    @Test
    void theMapCommandsSayWhyNothingOpensWithProjectSupportOff(@TempDir Path dir) throws Exception {
        try (FxWindowFixture fx =
                FxWindowFixture.create(dir, shared -> shared.getSettings().setProjectSupport(false))) {
            fx.keepConfigDir = true;
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            String expected = tr("palette.disabled.featureOff", tr("command.view.toggleProjects"));
            for (String id : List.of("project.toggleMapView", "projectMap.revealActiveFile")) {
                FxTestSupport.runOnFx(() -> {
                    StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
                    FxTestSupport.<javafx.scene.control.Label>field(status, "echo")
                            .setText("");
                    registry.run(id);
                    assertEquals(
                            expected,
                            FxTestSupport.<javafx.scene.control.Label>field(status, "echo")
                                    .getText(),
                            id);
                });
            }
        }
    }
}
