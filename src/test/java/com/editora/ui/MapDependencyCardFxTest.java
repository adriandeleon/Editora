package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's Dependencies card (Map code browser gap 14): what uses a file or folder and what it uses, and how a
 * folder's parts use each other, opened from ⋯, a row's menu or a file card's menu, read from the files' imports.
 */
@Tag("fx")
class MapDependencyCardFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;

    MapDependencyCardFxTest() throws Exception {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (map != null) {
            FxTestSupport.runOnFx(() -> {
                map.view().dispose();
                map.stage().close();
            });
        }
        async.close();
    }

    /** app uses core; core's Repo uses db; db uses core's Repo back; core's Service uses Repo, its own package's. */
    private void writeProject() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        write("app/Main.java", "package app;\n\nimport core.Service;\nimport core.Repo;\n\nclass Main {}\n");
        write("core/Service.java", "package core;\n\npublic class Service {\n    Repo repo;\n}\n");
        write("core/Repo.java", "package core;\n\nimport db.Db;\n\npublic class Repo {\n    Db db;\n}\n");
        write("db/Db.java", "package db;\n\nimport core.Repo;\n\npublic class Db {}\n");
        write("README.md", "# Project\n");
    }

    private void write(String rel, String text) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theProjectsDependenciesOpenInsideAsAMatrixWithItsCycle() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        FxTestSupport.runOnFx(() -> {
            MenuItem item = FxTestSupport.field(map.view(), "dependenciesItem");
            item.fire();
        });
        await("the matrix", () -> card() != null && card().matrixShown() != null);
        ProjectMapDependencyCard card = card();
        FxTestSupport.runOnFx(() -> {
            assertEquals("", card.subject());
            assertTrue(card.subjectIsFolder());
            assertEquals(ProjectMapDependencyCard.View.INSIDE, card.view());
            assertEquals(tr("project.map.deps.title", "project"), card.title());
            ProjectMapDependencies.Matrix matrix = card.matrixShown();
            assertEquals(
                    List.of("app", "core", "db"),
                    matrix.parts().stream()
                            .map(ProjectMapDependencies.Part::rel)
                            .toList());
            assertEquals(1, matrix.cycles());
            assertTrue(card.summaryText().contains(tr("project.map.deps.cycles", 1)), card.summaryText());
            assertTrue(card.isVisible());
            assertSame(card, map.view().dependencyCard());
            assertTrue(cards().openCards().stream().anyMatch(open -> open.node() == card));

            assertTrue(card.pickCell(1, 2), "core uses db");
            assertEquals(tr("project.map.deps.whyCell", "core/", "db/"), card.whyHeading());
            assertEquals(List.of("core/Repo.java:3 → db/Db.java"), card.whyLines());
            assertFalse(card.pickCell(0, 0), "a part does not use itself");

            card.pressRow(1); // core: inside it
            assertEquals("core", card.subject());
            assertEquals(ProjectMapDependencyCard.View.INSIDE, card.view());
            assertEquals(
                    List.of("core/Service.java", "core/Repo.java"),
                    card.matrixShown().parts().stream()
                            .map(ProjectMapDependencies.Part::rel)
                            .toList());
            assertTrue(card.goBack());
            assertEquals("", card.subject());
            assertFalse(card.goBack(), "nothing before");
        });

        // Escape closes it, and the keyboard goes back to the map.
        FxTestSupport.runOnFx(() -> key(card, KeyCode.ESCAPE));
        FxTestSupport.runOnFx(() -> assertNull(map.view().dependencyCard()));
    }

    @Test
    void aFoldersRowMenuOpensItAroundItsUsersAndWhatItUses() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = (ContextMenu) FxTestSupport.call(
                    map.view(),
                    "menuWithMapItems",
                    new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                    entry(root.resolve("core")),
                    new ContextMenu());
            menuItem(menu, tr("project.map.deps.menu")).fire();
        });
        await(
                "core's dependencies",
                () -> card() != null && !card().usersShown().isEmpty());
        ProjectMapDependencyCard card = card();
        FxTestSupport.runOnFx(() -> {
            assertEquals("core", card.subject());
            assertEquals(ProjectMapDependencyCard.View.AROUND, card.view());
            assertEquals(List.of("app", "db"), card.usersShown(), "by folder: app's two uses first");
            assertEquals(List.of("db"), card.usesShown());
            assertTrue(card.summaryText().contains(tr("project.map.deps.mutualFolders", 1)), card.summaryText());
            assertTrue(card.pickNode("app", true));
            assertEquals(
                    List.of("app/Main.java:3 → core/Service.java", "app/Main.java:4 → core/Repo.java"),
                    card.whyLines());
            assertFalse(card.pickNode("nowhere", true));

            ContextMenu readme = (ContextMenu) FxTestSupport.call(
                    map.view(),
                    "menuWithMapItems",
                    new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                    entry(root.resolve("README.md")),
                    new ContextMenu());
            assertTrue(
                    readme.getItems().stream()
                            .noneMatch(item -> tr("project.map.deps.menu").equals(item.getText())),
                    "a file whose imports are not read has no Dependencies");
        });

        // Its import line opens Main's card at that line.
        FxTestSupport.runOnFx(() -> assertTrue(card.openPickedLine()));
        await(
                "Main's card",
                () -> previews().keySet().stream()
                        .anyMatch(key -> key.toString().contains("Main.java")));
    }

    @Test
    void aFileCardsMenuOpensItBesideTheCardAndItWalksAndRefreshes() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        Path repo = root.resolve("core/Repo.java");
        FxTestSupport.runOnFx(() -> map.view().setFollowCode(new ProjectMapView.FollowCode() {
            @Override
            public void follow(Path file, int line, int column, @Nullable String name, String commandId) {}

            @Override
            public void relatedFiles(Path file, java.util.function.Consumer<List<Path>> found) {
                found.accept(List.of());
            }

            @Override
            public void fileHistory(Path file) {}
        }));
        ProjectMapPreview fileCard = FxTestSupport.callOnFx(() -> cards().openFileCard(repo));
        await("Repo's card", () -> !fileCard.editor().getText().isEmpty());
        FxTestSupport.runOnFx(() ->
                menuItem(followMenu(fileCard), tr("project.map.deps.menu")).fire());
        await("Repo's dependencies", () -> card() != null && !card().usesShown().isEmpty());
        ProjectMapDependencyCard card = card();
        FxTestSupport.runOnFx(() -> {
            assertEquals("core/Repo.java", card.subject());
            assertFalse(card.subjectIsFolder());
            assertEquals(List.of("app/Main.java", "core/Service.java", "db/Db.java"), card.usersShown());
            assertEquals(List.of("db/Db.java"), card.usesShown());
            assertEquals(tr("project.map.deps.title", "Repo.java"), card.title());
            double[] beside = MapCards.drawnBounds(card);
            double[] from = MapCards.drawnBounds(fileCard);
            assertFalse(
                    beside[0] < from[0] + from[2]
                            && from[0] < beside[0] + beside[2]
                            && beside[1] < from[1] + from[3]
                            && from[1] < beside[1] + beside[3],
                    "beside its card, not over it");

            assertTrue(card.enterNode("db/Db.java", false));
            assertEquals("db/Db.java", card.subject());
            assertEquals(List.of("core/Repo.java"), card.usesShown());
            assertTrue(card.goBack());
            assertEquals("core/Repo.java", card.subject());
            assertFalse(card.enterNode("nowhere", false));
        });

        // Repo stops using Db: refresh reads it again (and only it).
        write("core/Repo.java", "package core;\n\npublic class Repo {}\n");
        Files.setLastModifiedTime(
                repo, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        FxTestSupport.runOnFx(() -> refreshButton(card).fire());
        await("Repo uses nothing now", () -> card.usesShown().isEmpty());
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of("app/Main.java", "core/Service.java", "db/Db.java"), card.usersShown());
            assertTrue(card.summaryText().startsWith(tr("project.map.deps.counts", 3, 0)), card.summaryText());
        });

        // Asked again on another file: the same card moves there, Back returns.
        FxTestSupport.runOnFx(() -> map.view().openDependencies(root.resolve("README.md"), null));
        FxTestSupport.runOnFx(() -> {
            assertSame(card, card());
            assertEquals(tr("project.map.deps.notRead"), card.summaryText());
            assertTrue(card.goBack());
            assertEquals("core/Repo.java", card.subject());
        });
    }

    @Test
    void summariesAndWeightsAreWordedFromTheCounts() throws Exception {
        ProjectMapDependencies deps = new ProjectMapDependencies(
                List.of("a/A.java", "b/B.java"),
                List.of(
                        new ProjectMapImports.Edge("a/A.java", "b/B.java", 0, "import b.B;"),
                        new ProjectMapImports.Edge("b/B.java", "a/A.java", 0, "import a.A;")),
                true);
        ProjectMapDependencies.Around around = deps.around("a", true, ProjectMapDependencies.Grouping.FOLDERS);
        String text = ProjectMapDependencyCard.aroundSummary(around, deps);
        assertTrue(text.startsWith(tr("project.map.deps.inFolder", 1)), text);
        assertTrue(text.contains(tr("project.map.deps.countsFolder", 1, 1)), text);
        assertTrue(text.contains(tr("project.map.deps.mutualFolders", 1)), text);
        assertTrue(text.endsWith(tr("project.map.deps.truncated", 2)), text);
        String inside = ProjectMapDependencyCard.insideSummary(deps.matrix(""), deps, "x");
        assertTrue(inside.startsWith(tr("project.map.deps.insideCounts", "x", 2, 2)), inside);
        assertTrue(inside.contains(tr("project.map.deps.cycles", 1)), inside);
        ProjectMapDependencies none = new ProjectMapDependencies(List.of("a/A.java", "b/B.java"), List.of(), false);
        assertTrue(ProjectMapDependencyCard.insideSummary(none.matrix(""), none, "x")
                .contains(tr("project.map.deps.noCycles")));
        assertEquals(1, ProjectMapDependencyCard.weight(1));
        assertEquals(2, ProjectMapDependencyCard.weight(2));
        assertEquals(3, ProjectMapDependencyCard.weight(6));
        assertEquals(4, ProjectMapDependencyCard.weight(20));
    }

    @Test
    void theCardSaysItIsReadingUntilTheAnswerComesAndMovesAndZoomsWithTheMap() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        FxTestSupport.runOnFx(() -> {
            ProjectMapDependencyCard card = new ProjectMapDependencyCard();
            card.show(root, "core", true);
            card.setReading();
            assertEquals(tr("project.map.deps.reading"), card.summaryText());
            assertNull(card.matrixShown());
            card.setDependencies(ProjectMapDependencies.EMPTY);
            assertEquals(
                    tr("project.map.deps.inFolder", 0) + " " + tr("project.map.deps.countsFolder", 0, 0),
                    card.summaryText());
            card.focusContent();
        });
        FxTestSupport.runOnFx(() -> map.view().openDependencies(root.resolve("db"), null));
        await("db's dependencies", () -> card() != null && !card().usesShown().isEmpty());
        FxTestSupport.runOnFx(() -> {
            ProjectMapDependencyCard card = card();
            double width = card.getWidth();
            cards().moveWithViewport(0, 0, 1.0, 0, 0, 2.0);
            assertEquals(2.0, card.mapScale(), 1e-9);
            assertEquals(width, card.getWidth(), 1e-9, "its own size stays; the map's zoom scales it");
        });
    }

    @Test
    void theCardOnItsOwnTogglesKeysMenusAndOverflow() throws Exception {
        List<ProjectMapImports.Edge> edges = new java.util.ArrayList<>();
        List<String> files = new java.util.ArrayList<>(List.of("hub/Hub.java", "Root.java", "solo/Solo.java"));
        int many = ProjectMapDependencyCard.MAX_NODES + 3;
        for (int i = 0; i < many; i++) {
            String user = "users/U" + i + ".java";
            files.add(user);
            edges.add(new ProjectMapImports.Edge(user, "hub/Hub.java", 2, "import hub.Hub;"));
        }
        edges.add(new ProjectMapImports.Edge("hub/Hub.java", "Root.java", 1, "Root"));
        edges.add(new ProjectMapImports.Edge("Root.java", "hub/Hub.java", 1, "Hub"));
        ProjectMapDependencies deps = new ProjectMapDependencies(files, edges, false);
        List<String> revealed = new java.util.ArrayList<>();
        List<String> opened = new java.util.ArrayList<>();
        Path project = temp.resolve("p");
        Stage[] stage = new Stage[1];
        ProjectMapDependencyCard card = FxTestSupport.callOnFx(() -> {
            ProjectMapDependencyCard made = new ProjectMapDependencyCard();
            made.setOnReveal(path -> revealed.add(project.relativize(path).toString()));
            made.setOnOpen((path, line) -> opened.add(project.relativize(path) + ":" + line));
            javafx.scene.layout.Pane host = new javafx.scene.layout.Pane(made);
            Scene scene = new Scene(host, 1000, 800);
            scene.getStylesheets()
                    .add(MapDependencyCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0] = new Stage();
            stage[0].setScene(scene);
            stage[0].show();
            assertNull(made.root(), "not shown yet");
            made.show(project, "hub/Hub.java", false);
            made.setDependencies(deps);
            made.place(new ProjectMapPreview.Placement(10, 10, 640, 600));
            host.applyCss();
            host.layout();
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        ProjectMapDependencyCard.MAX_NODES, card.usersShown().size(), "the rest are +N more");
                assertEquals(List.of("Root.java"), card.usesShown());
                ToggleButtons toggles = toggles(card);
                assertTrue(toggles.inside().isDisable(), "a file has no inside");

                // Folders: one box per folder, kept for files.
                toggles.folders().fire();
                assertEquals(List.of("users", ""), card.usersShown());
                assertTrue(card.pickNode("", false), "the root's own files, as a folder");
                assertEquals(List.of("hub/Hub.java:2 → Root.java"), card.whyLines());
                toggles.folders().fire(); // pressed again: stays on
                toggles.files().fire();
                assertEquals(
                        ProjectMapDependencyCard.MAX_NODES, card.usersShown().size());

                // Enter on a drawn file moves the card onto it; Backspace goes back.
                NodeButtonHandle root = node(card, "Root.java", false);
                key(root.button(), KeyCode.ENTER);
                assertEquals("Root.java", card.subject());
                key(card, KeyCode.BACK_SPACE);
                assertEquals("hub/Hub.java", card.subject());

                // A double-click does the same.
                Button user = node(card, "users/U0.java", true).button();
                Event.fireEvent(user, click(2));
                assertEquals("users/U0.java", card.subject());
                assertTrue(card.goBack());

                // The import lines: Enter and a double-click open them.
                assertTrue(card.pickNode("users/U1.java", true));
                key(whyList(card), KeyCode.ENTER);
                assertEquals(List.of("users/U1.java:2"), opened);
                whyList(card).applyCss();
                whyList(card).layout();
                for (Node cell : whyList(card).lookupAll(".list-cell")) {
                    if (cell instanceof javafx.scene.control.ListCell<?> listCell && listCell.getItem() != null) {
                        Event.fireEvent(cell, click(2));
                    }
                }
                assertEquals(2, opened.size(), opened.toString());

                // A node's menu: Show on Map, Open as Card, move onto it, look inside.
                ContextMenu menu = node(card, "Root.java", false).button().getContextMenu();
                menuItem(menu, tr("project.map.deps.showOnMap")).fire();
                assertEquals(List.of("Root.java"), revealed);
                menuItem(menu, tr("project.map.deps.openCard")).fire();
                assertEquals("Root.java:-1", opened.get(2));
                menuItem(menu, tr("project.map.deps.focusHere")).fire();
                assertEquals("Root.java", card.subject());
                card.goBack();
                toggles.folders().fire();
                ContextMenu folderMenu = node(card, "users", true).button().getContextMenu();
                menuItem(folderMenu, tr("project.map.deps.insideHere")).fire();
                assertEquals("users", card.subject());
                assertEquals(ProjectMapDependencyCard.View.INSIDE, card.view());
                assertEquals(
                        ProjectMapDependencies.MAX_PARTS,
                        card.matrixShown().parts().size());
                assertTrue(
                        card.summaryText()
                                .contains(tr("project.map.deps.partsLeft", many - ProjectMapDependencies.MAX_PARTS)),
                        card.summaryText());
                card.focusContent();

                // A folder of one file: nothing to compare Inside, alone Around.
                card.show(project, "solo", true);
                assertEquals(ProjectMapDependencyCard.View.INSIDE, card.view(), "as the last folder");
                assertEquals(tr("project.map.deps.noParts"), visibleEmptyText(card));
                card.focusContent();
                toggles.around().fire();
                assertEquals(ProjectMapDependencyCard.View.AROUND, card.view());
                assertEquals(tr("project.map.deps.aloneFolder"), visibleEmptyText(card));
                card.show(project, "solo/Solo.java", false);
                assertEquals(tr("project.map.deps.alone"), visibleEmptyText(card));
                card.focusContent();

                // The project: Inside, its subject pressed shows it on the map.
                card.show(project, "", true);
                assertEquals(ProjectMapDependencyCard.View.INSIDE, card.view());
                card.focusContent();
                toggles.around().fire();
                graphSubject(card).fire();
                assertEquals("", revealed.get(revealed.size() - 1));
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    @Test
    void theCardsFrameMovesResizesAndClosesByKeyAndPointer() throws Exception {
        Stage[] stage = new Stage[1];
        List<String> closed = new java.util.ArrayList<>();
        ProjectMapDependencyCard card = FxTestSupport.callOnFx(() -> {
            ProjectMapDependencyCard made = new ProjectMapDependencyCard();
            made.setOnEscape(() -> closed.add("escape"));
            javafx.scene.layout.Pane host = new javafx.scene.layout.Pane(made);
            host.resize(1000, 800);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(host, 1000, 800));
            stage[0].show();
            made.constrainTo(500, 500); // not placed: nothing
            assertFalse(made.placed());
            made.place(new ProjectMapPreview.Placement(100, 100, 500, 400));
            assertTrue(made.placed());
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                Event.fireEvent(
                        card, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, true, false));
                assertEquals(100 + ProjectMapPreview.KEYBOARD_STEP, card.getLayoutX(), 1e-9);
                Event.fireEvent(
                        card, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, true, false, true, false));
                assertEquals(400 + ProjectMapPreview.KEYBOARD_STEP, card.getHeight(), 1e-9);
                Event.fireEvent(
                        card, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, true, false, false, false));
                assertTrue(closed.isEmpty(), "Shift+Escape is not Escape");
                key(card, KeyCode.ESCAPE);
                assertEquals(List.of("escape"), closed);

                Node bar = card.lookup(".project-map-preview-header");
                double x = card.getLayoutX();
                Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.PRIMARY, true));
                Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, 40, 10, MouseButton.PRIMARY, true));
                assertEquals(x + 30, card.getLayoutX(), 1e-9);
                Event.fireEvent(bar, mouse(MouseEvent.MOUSE_DRAGGED, 80, 10, MouseButton.NONE, false));
                assertEquals(x + 30, card.getLayoutX(), 1e-9, "no button held: no drag");
                Event.fireEvent(bar, mouse(MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY, false));

                Node grip = card.lookup(".project-map-preview-resize");
                double width = card.getWidth();
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, 0, 0, MouseButton.PRIMARY, true));
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, 50, 0, MouseButton.PRIMARY, true));
                assertEquals(width + 50, card.getWidth(), 1e-9);
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, 0, 0, MouseButton.SECONDARY, false));
                card.constrainTo(400, 350);
                assertEquals(ProjectMapDependencyCard.MIN_HEIGHT <= card.getHeight(), true);
                assertTrue(card.getWidth() <= 400);
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    @Test
    void theIndexReadsOnlyWhatChangedAndAnswersEveryCaller() throws Exception {
        writeProject();
        write("go.mod", "module example.com/p\n");
        write("big/Big.java", "package big;\n" + "// x\n".repeat(200_000));
        write("cmd/main.go", "package main\nimport \"example.com/p/lib\"\n");
        write("lib/l.go", "package lib\n");
        List<String> listed = new java.util.ArrayList<>(List.of(
                "app/Main.java",
                "core/Service.java",
                "core/Repo.java",
                "db/Db.java",
                "README.md",
                "go.mod",
                "big/Big.java",
                "cmd/main.go",
                "lib/l.go",
                "gone/Gone.java"));
        int[] lists = new int[1];
        MapDependencyIndex index = new MapDependencyIndex();
        List<ProjectMapDependencies> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            FxTestSupport.runOnFx(() -> {
                index.load(answers::add); // no root: nothing
                index.setRoot(root);
                index.setRoot(root); // the same: nothing forgotten
                index.setFileSource(base -> {
                    lists[0]++;
                    return new com.editora.search.ProjectFileList.Snapshot(listed, List.of(), false);
                });
                index.load(answers::add);
                index.load(answers::add); // asked while reading: once more after, both answered
                assertTrue(index.loading());
            });
            await("both answered", () -> answers.size() == 2);
            FxTestSupport.runOnFx(() -> {
                ProjectMapDependencies deps = answers.get(1);
                assertSame(deps, index.latest());
                assertFalse(index.loading());
                assertTrue(deps.read("core/Repo.java"));
                assertFalse(deps.read("big/Big.java"), "too big to read");
                assertFalse(deps.read("gone/Gone.java"), "listed but gone");
                assertEquals(
                        List.of("lib/l.go"),
                        deps.around("cmd/main.go", false, ProjectMapDependencies.Grouping.FILES).uses().stream()
                                .map(ProjectMapDependencies.Node::rel)
                                .toList(),
                        "through go.mod's module path");
                assertEquals(2, lists[0]);
            });

            // A source that cannot list, or fails: the last answer stands.
            FxTestSupport.runOnFx(() -> {
                index.setFileSource(base -> null);
                index.load(answers::add);
            });
            await("answered from the last", () -> answers.size() == 3);
            FxTestSupport.runOnFx(() -> {
                assertSame(answers.get(1), answers.get(2));
                index.setFileSource(base -> {
                    throw new IllegalStateException("boom");
                });
                index.load(answers::add);
            });
            await("answered despite the failure", () -> answers.size() == 4);

            // Another root forgets everything; the default source lists it.
            FxTestSupport.runOnFx(() -> {
                index.setFileSource(null);
                index.setRoot(root.resolve("core"));
                assertNull(index.latest());
                index.load(answers::add);
            });
            await("core alone", () -> answers.size() == 5);
            FxTestSupport.runOnFx(() -> assertEquals(2, answers.get(4).fileCount()));
        } finally {
            FxTestSupport.runOnFx(index::dispose);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private record ToggleButtons(
            javafx.scene.control.ToggleButton around,
            javafx.scene.control.ToggleButton inside,
            javafx.scene.control.ToggleButton files,
            javafx.scene.control.ToggleButton folders) {}

    private static ToggleButtons toggles(ProjectMapDependencyCard card) {
        return new ToggleButtons(
                FxTestSupport.field(card, "aroundButton"),
                FxTestSupport.field(card, "insideButton"),
                FxTestSupport.field(card, "filesButton"),
                FxTestSupport.field(card, "foldersButton"));
    }

    private record NodeButtonHandle(Button button) {}

    private static NodeButtonHandle node(ProjectMapDependencyCard card, String rel, boolean user) {
        Object graph = FxTestSupport.field(card, "graph");
        Object button = FxTestSupport.call(graph, "button", new Class<?>[] {String.class, boolean.class}, rel, user);
        assertTrue(button instanceof Button, "no " + rel);
        return new NodeButtonHandle((Button) button);
    }

    private static Button graphSubject(ProjectMapDependencyCard card) {
        Object graph = FxTestSupport.field(card, "graph");
        return FxTestSupport.field(graph, "subject");
    }

    private static javafx.scene.control.ListView<?> whyList(ProjectMapDependencyCard card) {
        return FxTestSupport.field(card, "why");
    }

    private static String visibleEmptyText(ProjectMapDependencyCard card) {
        javafx.scene.control.Label empty = FxTestSupport.field(card, "empty");
        assertTrue(empty.isVisible());
        return empty.getText();
    }

    private static MouseEvent click(int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, double screenX, double screenY, MouseButton button, boolean down) {
        return new MouseEvent(
                type,
                1,
                1,
                screenX,
                screenY,
                button,
                1,
                false,
                false,
                false,
                false,
                down && button == MouseButton.PRIMARY,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private @Nullable ProjectMapDependencyCard card() {
        return cards().dependencyCard();
    }

    private static Button refreshButton(ProjectMapDependencyCard card) {
        return FxTestSupport.field(card, "refresh");
    }

    private static ContextMenu followMenu(ProjectMapPreview card) {
        ContextMenu menu = new ContextMenu();
        @SuppressWarnings("unchecked")
        List<MenuItem> items = (List<MenuItem>) FxTestSupport.call(card, "followMenuItems", new Class<?>[0]);
        menu.getItems().setAll(items);
        return menu;
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        for (MenuItem item : menu.getItems()) {
            if (text.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("no " + text + " in "
                + menu.getItems().stream().map(MenuItem::getText).toList());
    }

    private ProjectMapModel.Entry entry(Path path) {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        for (Object box : boxes) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        return new ProjectMapModel.Entry(path, root, 1, Files.isDirectory(path));
    }

    private MapCards cards() {
        return FxTestSupport.field(map.view(), "cards");
    }

    private List<Path> rows() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0])).path())
                .toList();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private Fixture openMap() throws Exception {
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(MapDependencyCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(project);
            return new Fixture(view, scene, stage, FxTestSupport.field(view, "surface"));
        });
    }

    private static void key(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}
}
