package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.git.GitService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Project Map explore round, the cards group (positions 6–12): find in a card, pinned cards and the refused ninth,
 * declaration stepping and Show in Structure, a card's Git context, a file's notes on its code card, and the glance
 * framing — with the title still outlasting the status at a card's minimum width.
 */
@Tag("fx")
class ProjectMapCardsExploreFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;
    private final List<String> statuses = new CopyOnWriteArrayList<>();

    ProjectMapCardsExploreFxTest() throws Exception {}

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

    // --- 6: find in a card ------------------------------------------------------------------------------

    @Test
    void findInACardStepsThroughItsMatchesAndEscapeClosesTheBarKeepingTheMatch() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            card.editor().moveTo(0);
            assertTrue(card.runMarkerCommand("find.show"), "Find typed in the card is the card's");
            assertTrue(card.findShown());
            assertTrue(card.findField().isFocused(), "the keyboard is in the find field");
            card.findField().setText("return a");
            assertEquals(tr("project.map.preview.find.count", 1, 12), card.findCountText());
            assertEquals("return a", card.editor().getSelectedText());
            int first = card.editor().getSelection().getStart();
            key(card.findField(), KeyCode.ENTER, false, false);
            assertEquals(tr("project.map.preview.find.count", 2, 12), card.findCountText(), "Enter: the next");
            assertTrue(card.editor().getSelection().getStart() > first);
            key(card.findField(), KeyCode.ENTER, true, false);
            assertEquals(tr("project.map.preview.find.count", 1, 12), card.findCountText(), "Shift+Enter: back");
            key(card.findField(), KeyCode.ENTER, true, false);
            assertEquals(tr("project.map.preview.find.count", 12, 12), card.findCountText(), "it wraps");
            assertTrue(card.runMarkerCommand("find.next"), "Find Next (F3) steps too");
            assertEquals(tr("project.map.preview.find.count", 1, 12), card.findCountText());

            card.findField().setText("RETURN A");
            assertEquals(tr("project.map.preview.find.count", 1, 12), card.findCountText(), "case ignored");
            javafx.scene.control.ToggleButton matchCase = FxTestSupport.field(card, "findCase");
            matchCase.fire();
            assertEquals(tr("project.map.preview.find.none"), card.findCountText(), "Match case finds none");
            matchCase.fire();
            card.findField().setText("return a");
            int at = card.editor().getSelection().getStart();

            key(card.findField(), KeyCode.ESCAPE, false, false);
            assertFalse(card.findShown(), "Escape closes the bar");
            assertTrue(previews().containsValue(card), "and not the card");
            assertEquals(at, card.editor().getSelection().getStart(), "the match stays selected");
            assertEquals("return a", card.editor().getSelectedText());
        });
    }

    // --- 7: pin -------------------------------------------------------------------------------------------

    @Test
    void aPinnedCardIsNeverEvictedAndANinthIsRefusedWhenEveryCardIsPinned() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(Files.writeString(root.resolve("f" + i + ".txt"), "file " + i + "\n"));
        }
        map = openMap();
        ProjectMapPreview first = openFileCard(files.get(0));
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.ToggleButton pin = FxTestSupport.field(first, "pin");
            pin.fire(); // the title bar's pin
            assertTrue(first.pinned());
        });
        for (int i = 1; i <= 8; i++) {
            openFileCard(files.get(i));
        }
        FxTestSupport.runOnFx(() -> {
            assertEquals(8, previews().size());
            assertSame(first, previews().get(files.get(0)), "the pinned card, the oldest, stayed");
            assertFalse(previews().containsKey(files.get(1)), "the oldest unpinned card went instead");
            previews().values().forEach(card -> card.setPinned(true));
        });
        statuses.clear();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, files.get(9));
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, files.get(9));
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals(8, previews().size(), "the cap stays at eight");
            assertFalse(previews().containsKey(files.get(9)), "a ninth card is refused");
            assertTrue(
                    statuses.contains(tr("status.projectMap.allCardsPinned", MapCards.MAX_OPEN_PREVIEWS)),
                    "and the status line says to unpin one: " + statuses);
            ProjectMapPreview card = previews().get(files.get(8));
            card.setPinned(false);
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, files.get(9));
            assertTrue(previews().containsKey(files.get(9)), "unpinned, a card makes room again");
            assertFalse(previews().containsKey(files.get(8)));
        });
    }

    // --- 8: declaration stepping, Show in Structure ---------------------------------------------------------

    @Test
    void aDeclarationCardStepsThroughItsFilesDeclarationsAndShowsItselfInTheCardsColumn() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview fileCard = openFileCard(big);
        ProjectMapCardStructure column = showStructure(fileCard);
        ProjectMapPreview card = openDeclaration(column, "m0(int)");
        FxTestSupport.runOnFx(() -> {
            assertTrue(card.title().endsWith("› m0(int)"), card.title());
            Button previous = FxTestSupport.field(card, "previousDeclaration");
            Button next = FxTestSupport.field(card, "nextDeclaration");
            assertFalse(previous.isDisable(), "the class comes before its first method");
            assertFalse(next.isDisable());

            key(card.editor(), KeyCode.PAGE_DOWN, false, true); // Alt+Page Down
            assertTrue(card.title().endsWith("› m1(int)"), "the next declaration, in the same card: " + card.title());
            assertTrue(
                    card.editor().getText().contains("int m1(int a)"),
                    card.editor().getText());
            assertFalse(card.editor().getText().contains("m0("));
            Path m1 = keyOf(card);
            assertTrue(m1.getFileName().toString().contains("m1"), "the card is kept under its declaration now");

            next.fire();
            assertTrue(card.title().endsWith("› m2(int)"), card.title());
            key(card.editor(), KeyCode.PAGE_UP, false, true);
            key(card.editor(), KeyCode.PAGE_UP, false, true);
            key(card.editor(), KeyCode.PAGE_UP, false, true);
            assertTrue(card.title().endsWith("› Big"), "back to the class: " + card.title());
            assertTrue(previous.isDisable(), "nothing comes before it");
            key(card.editor(), KeyCode.PAGE_UP, false, true);
            assertTrue(card.title().endsWith("› Big"), "and Alt+Page Up stays");

            key(card.editor(), KeyCode.PAGE_DOWN, false, true);
            key(card.editor(), KeyCode.PAGE_DOWN, false, true);
            key(card.editor(), KeyCode.PAGE_DOWN, false, true);
            assertTrue(card.title().endsWith("› m2(int)"), card.title());
            FxTestSupport.<MapCards>field(map.view(), "cards").cardsShowInStructure(card);
            ProjectMapStructure.Node selected = column.tree()
                    .getSelectionModel()
                    .getSelectedItem()
                    .getValue()
                    .node();
            assertNotNull(selected);
            assertEquals(keyOf(card), selected.path(), "Show in Structure selects its row in the file card's column");
            assertTrue(column.contains(map.scene().getFocusOwner()), "with the keyboard there");
        });
    }

    @Test
    void showInStructureSelectsTheDeclarationInTheMapsOwnColumn() throws Exception {
        Path big = writeBig();
        map = openMap();
        await("row", () -> rows().contains(big));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                map.view(),
                "toggleStructure",
                new Class<?>[] {ProjectMapModel.Entry.class},
                new ProjectMapModel.Entry(big, root, 0, false)));
        await("the map's column lists m3", () -> rowLabels().contains("m3(int)"));
        ProjectMapModel.Entry m3 = FxTestSupport.callOnFx(() -> entryLabelled("m3(int)"));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<MapCards>field(map.view(), "cards").openDeclarationCard(m3));
        await("the declaration card", () -> previews().containsKey(m3.path()));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, big);
            ProjectMapPreview card = previews().get(m3.path());
            FxTestSupport.<MapCards>field(map.view(), "cards").cardsShowInStructure(card);
            @SuppressWarnings("unchecked")
            ProjectMapModel.Entry selected = ((java.util.Optional<ProjectMapModel.Entry>)
                            FxTestSupport.call(map.surface(), "selectedEntry", new Class<?>[0]))
                    .orElseThrow();
            assertEquals(m3.path(), selected.path(), "the Map's own Structure column has its row selected");
        });
    }

    // --- 9: Git context -------------------------------------------------------------------------------------

    @Test
    void aCardsStatusEndsWithItsLastChangeAndADeclarationCardWithItsLines() throws Exception {
        Path big = writeBig();
        git(root, null, "init", "-q");
        git(root, "Ada", "add", "Big.java");
        git(root, "Ada", "commit", "-q", "-m", "Big methods");
        List<String> lines = new ArrayList<>(List.of(Files.readString(big).split("\n", -1)));
        int m5Return = lineAfter(lines, "int m5(int a)", "return a;");
        lines.set(m5Return, "        return a + 5;");
        Files.writeString(big, String.join("\n", lines));
        git(root, "Bob", "commit", "-q", "-am", "m5 adds five");

        GitService service = new GitService();
        try {
            map = openMap();
            FxTestSupport.runOnFx(() -> map.view().setFollowCode(followCode(service)));
            ProjectMapPreview fileCard = openFileCard(big);
            await("the file's last change", () -> fileCard.lastChangeText().startsWith(lastChange("Bob")));
            FxTestSupport.runOnFx(() -> {
                Label status = FxTestSupport.field(fileCard, "status");
                assertTrue(status.getText().endsWith(fileCard.lastChangeText()), "at the end of the status line");
                assertTrue(fileCard.statusTipText().contains("m5 adds five"), "the subject in its tooltip");
            });
            ProjectMapCardStructure column = showStructure(fileCard);
            ProjectMapPreview m0 = openDeclaration(column, "m0(int)");
            await(
                    "m0's lines were last changed by Ada",
                    () -> m0.lastChangeText().startsWith(lastChange("Ada")));
            FxTestSupport.runOnFx(() -> assertTrue(
                    m0.statusTipText()
                            .contains(tr("project.map.preview.lastChangeLinesTip", "Big methods", "")
                                    .replace(" ()", "")),
                    m0.statusTipText()));
            ProjectMapPreview m5 = openDeclaration(column, "m5(int)");
            await("m5's by Bob", () -> m5.lastChangeText().startsWith(lastChange("Bob")));

            int runs = service.lastChangeRunsForTest();
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<MapCards>field(map.view(), "cards").refresh(false));
            Thread.sleep(400);
            FxTestSupport.drainFx();
            assertEquals(runs, service.lastChangeRunsForTest(), "asked again, the answers come from the cache");

            // A new commit moves HEAD: once its read of HEAD is stale, the card asks again and follows it.
            lines = new ArrayList<>(List.of(Files.readString(big).split("\n", -1)));
            lines.set(lineAfter(lines, "int m0(int a)", "return a;"), "        return a + 0;");
            Files.writeString(big, String.join("\n", lines));
            git(root, "Cy", "commit", "-q", "-am", "m0 adds zero");
            long now = System.nanoTime();
            service.setNanoClockForTest(() -> now + GitService.HEAD_TTL_NANOS + 1_000_000_000L);
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<MapCards>field(map.view(), "cards").refresh(false));
            await("m0 follows the new HEAD", () -> m0.lastChangeText().startsWith(lastChange("Cy")));
            FxTestSupport.runOnFx(() -> assertTrue(m5.lastChangeText().startsWith(lastChange("Bob"))));
        } finally {
            service.shutdown();
        }
    }

    // --- 10: outlines of files with no tab from a running server -------------------------------------------

    @Test
    void aClosedFilesOutlineComesFromARunningServerAndFallsBackWithoutOne() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path demo = Files.writeString(root.resolve("Demo.java"), "class Demo {\n    void heuristic() {\n    }\n}\n");
        List<String> asked = new CopyOnWriteArrayList<>();
        boolean[] serving = {true};
        List<com.editora.lsp.SymbolNode>[] answer = new List[] {
            List.of(new com.editora.lsp.SymbolNode(
                    "Served",
                    "",
                    "class",
                    0,
                    3,
                    List.of(new com.editora.lsp.SymbolNode("fromServer", "()", "method", 1, 2, List.of()))))
        };
        ProjectMapStructure structure = FxTestSupport.callOnFx(() -> new ProjectMapStructure(path -> null));
        try {
            FxTestSupport.runOnFx(() -> structure.setClosedFileSymbols(new ProjectMapStructure.ClosedFileSymbols() {
                @Override
                public boolean serves(Path file) {
                    return serving[0];
                }

                @Override
                public void symbols(
                        Path file, String text, Consumer<@Nullable List<com.editora.lsp.SymbolNode>> found) {
                    asked.add(text);
                    found.accept(answer[0]);
                }
            }));
            FxTestSupport.runOnFx(() -> structure.ensure(demo));
            await("the server's outline", () -> labels(structure, demo).contains("Served"));
            assertEquals(List.of(Files.readString(demo)), asked, "asked once, with the file's text");
            FxTestSupport.runOnFx(() -> {
                assertTrue(structure.outlinedByServer(demo));
                structure.refresh(java.util.Set.of(demo));
            });
            FxTestSupport.drainFx();
            Thread.sleep(200);
            FxTestSupport.drainFx();
            assertEquals(1, asked.size(), "an unchanged file is not asked about again");

            answer[0] = null; // the server answers with nothing: the heuristic outline it is
            Files.writeString(demo, "class Demo {\n    void edited() {\n    }\n}\n");
            Files.setLastModifiedTime(
                    demo, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            FxTestSupport.runOnFx(() -> structure.refresh(java.util.Set.of(demo)));
            await("the heuristic outline", () -> labels(structure, demo).contains("Demo"));
            FxTestSupport.runOnFx(() -> assertFalse(structure.outlinedByServer(demo)));

            serving[0] = false; // no server runs: it is never asked
            Files.writeString(demo, "class Demo {\n    void again() {\n    }\n}\n");
            Files.setLastModifiedTime(
                    demo, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
            int before = asked.size();
            FxTestSupport.runOnFx(() -> structure.refresh(java.util.Set.of(demo)));
            await("outlined again", () -> labels(structure, demo).toString().contains("again"));
            assertEquals(before, asked.size(), "nothing is asked of a server that is not running");
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    @Test
    void aSlowServerLetsTheHeuristicOutlineShowFirstAndReplacesItWhenItAnswers() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path demo = Files.writeString(root.resolve("Slow.java"), "class Slow {\n}\n");
        List<Consumer<@Nullable List<com.editora.lsp.SymbolNode>>> pending = new CopyOnWriteArrayList<>();
        ProjectMapStructure structure = FxTestSupport.callOnFx(() -> new ProjectMapStructure(path -> null));
        try {
            FxTestSupport.runOnFx(() -> {
                structure.setClosedFileSymbols(new ProjectMapStructure.ClosedFileSymbols() {
                    @Override
                    public boolean serves(Path file) {
                        return true;
                    }

                    @Override
                    public void symbols(
                            Path file, String text, Consumer<@Nullable List<com.editora.lsp.SymbolNode>> found) {
                        pending.add(found);
                    }
                });
                structure.ensure(demo);
            });
            await("asked", () -> !pending.isEmpty());
            long start = System.nanoTime();
            await(
                    "the heuristic outline after the wait",
                    () -> labels(structure, demo).contains("Slow"));
            assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            >= ProjectMapStructure.SERVER_WAIT_MILLIS - 400,
                    "it waited for the server first");
            FxTestSupport.runOnFx(() -> pending.getFirst()
                    .accept(List.of(new com.editora.lsp.SymbolNode("Late", "", "class", 0, 1, List.of()))));
            await("the server's, when it came", () -> labels(structure, demo).contains("Late"));
        } finally {
            FxTestSupport.runOnFx(structure::dispose);
        }
    }

    // --- 11: one way to show notes ----------------------------------------------------------------------------

    @Test
    void aFilesNotesBadgeOpensItsCodeCardWithTheStickyNotesOutAndAFoldersItsNoteCard() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path folder = Files.createDirectories(root.resolve("docs"));
        Path file = Files.writeString(root.resolve("Noted.java"), "class Noted {\n}\n");
        Notes notes = new Notes();
        notes.add(file, "about the file");
        notes.add(folder, "about the folder");
        map = openMap();
        await("rows", () -> rows().contains(file) && rows().contains(folder));
        FxTestSupport.runOnFx(() -> {
            map.view().setNotePreviewActions(notes);
            FxTestSupport.call(map.view(), "cardsShowNotes", new Class<?>[] {Path.class}, file);
        });
        await("the file's card", () -> previews().containsKey(file));
        await("its notes stuck on", () -> previews().get(file).notesShown());
        FxTestSupport.runOnFx(() -> {
            assertTrue(noteCards().isEmpty(), "no note card for a file");
            FxTestSupport.call(map.view(), "cardsShowNotes", new Class<?>[] {Path.class}, folder);
            assertTrue(noteCards().containsKey(folder), "a folder's notes are its note card");

            javafx.scene.control.CheckMenuItem hide = FxTestSupport.field(map.view(), "hideOpenNotes");
            hide.setSelected(true);
            assertFalse(noteCards().get(folder).isVisible(), "Hide all open Personal Notes hides the note card");
            assertTrue(previews().get(file).notesShown(), "the sticky notes stay stuck on");
            hide.setSelected(false);
            assertTrue(noteCards().get(folder).isVisible());
        });
    }

    // --- 12 and C11: glance framing, the title bar at the minimum width ---------------------------------------

    @Test
    void aCardSaysItIsAGlanceAndItsTitleOutlastsTheStatusAtTheMinimumWidth() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview fileCard = openFileCard(big);
        ProjectMapCardStructure column = showStructure(fileCard);
        ProjectMapPreview card = openDeclaration(column, "m10(int)");
        FxTestSupport.runOnFx(() -> {
            Label lock = FxTestSupport.field(card, "readOnly");
            assertEquals(tr("project.map.preview.glance"), lock.getAccessibleText());
            assertEquals(tr("project.map.preview.glance"), lock.getTooltip().getText());
            for (ProjectMapPreview each : List.of(fileCard, card)) {
                Label status = FxTestSupport.field(each, "status");
                status.setText(
                        "Lines 1–20 of 100 · Colours for the first 3048 lines · Last change: Somebody, 3 days ago");
                each.resize(ProjectMapPreview.MIN_WIDTH, each.getHeight());
                each.applyCss();
                each.layout();
                HBox bar = FxTestSupport.field(each, "titleBar");
                bar.layout();
                Label title = FxTestSupport.field(each, "title");
                javafx.scene.text.Text name =
                        new javafx.scene.text.Text(ProjectMapPreview.titleKey(each.title(), each.range() != null));
                name.setFont(title.getFont());
                double wanted = Math.min(name.getLayoutBounds().getWidth(), ProjectMapPreview.MIN_WIDTH * 0.5);
                assertTrue(title.getWidth() >= wanted - 1, each.title() + " keeps its room: " + title.getWidth());
                // The status is what gets cut (its text is longer than the bar has room for), never the title,
                // whose room is checked above; how wide each ends up depends on the platform's font.
                assertTrue(
                        status.getWidth() < status.prefWidth(-1) - 0.5,
                        "the status gives way first: " + status.getWidth() + " of " + status.prefWidth(-1));
                Button close = FxTestSupport.field(each, "close");
                Button open = FxTestSupport.field(each, "open");
                assertTrue(close.isVisible() && open.isVisible());
                assertTrue(
                        close.getBoundsInParent().getMaxX() <= bar.getWidth() + 0.5,
                        "Close stays inside the bar: " + close.getBoundsInParent() + " in " + bar.getWidth());
                assertTrue(open.getWidth() >= open.prefWidth(-1) - 0.5, "Open is never cut");
            }
            javafx.scene.control.ToggleButton pin = FxTestSupport.field(card, "pin");
            card.setPinned(true);
            card.layout();
            assertTrue(pin.isVisible(), "a pressed pin stays in the bar");
        });
        javafx.scene.Node help = FxTestSupport.callOnFx(ProjectMapHelp::build);
        FxTestSupport.runOnFx(() -> {
            List<String> texts = new ArrayList<>();
            collectLabels(help, texts);
            int heading = texts.indexOf(tr("project.map.help.cards"));
            assertTrue(heading >= 0);
            assertEquals(tr("project.map.help.cardsIntro"), texts.get(heading + 1), "the Cards section opens with it");
        });
    }

    // --- fixture ----------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}

    private static String lastChange(String author) {
        return tr("project.map.preview.lastChange", author, "")
                .replaceAll(",\\s*$", "")
                .strip();
    }

    private static int lineAfter(List<String> lines, String start, String wanted) {
        int at = 0;
        while (!lines.get(at).contains(start)) {
            at++;
        }
        while (!lines.get(at).contains(wanted)) {
            at++;
        }
        return at;
    }

    private static void collectLabels(javafx.scene.Node node, List<String> out) {
        if (node instanceof Label label) {
            out.add(label.getText());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, out);
            }
        }
    }

    private static ProjectMapView.FollowCode followCode(GitService service) {
        return new ProjectMapView.FollowCode() {
            @Override
            public void follow(Path file, int line, int column, @Nullable String name, String commandId) {}

            @Override
            public void relatedFiles(Path file, Consumer<List<Path>> found) {
                found.accept(List.of());
            }

            @Override
            public void fileHistory(Path file) {}

            @Override
            public void lastChange(
                    Path file, int startLine, int endLine, Consumer<GitService.@Nullable LastChange> found) {
                service.lastChange(file, startLine, endLine, found);
            }
        };
    }

    private static void git(Path dir, @Nullable String author, String... args) throws Exception {
        List<String> command =
                new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null"));
        command.addAll(List.of(args));
        ProcessBuilder builder =
                new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        String who = author == null ? "Nobody" : author;
        Map<String, String> env = builder.environment();
        env.put("GIT_AUTHOR_NAME", who);
        env.put("GIT_AUTHOR_EMAIL", who.toLowerCase(java.util.Locale.ROOT) + "@example.com");
        env.put("GIT_COMMITTER_NAME", who);
        env.put("GIT_COMMITTER_EMAIL", who.toLowerCase(java.util.Locale.ROOT) + "@example.com");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, "git " + args[0] + ": " + out);
    }

    private Path writeBig() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        return Files.writeString(root.resolve("Big.java"), ProjectMapCardsReview3FxTest.source());
    }

    private Fixture openMap() throws Exception {
        if (root == null) {
            root = Files.createDirectories(temp.resolve("project")).toRealPath();
        }
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapCardsExploreFxTest.class
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

    private ProjectMapPreview openFileCard(Path file) throws Exception {
        await("row " + file.getFileName(), () -> rows().contains(file));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, file);
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, file);
        });
        await(
                "card " + file.getFileName(),
                () -> previews().containsKey(file)
                        && !previews().get(file).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(file));
    }

    private ProjectMapCardStructure showStructure(ProjectMapPreview card) throws Exception {
        FxTestSupport.runOnFx(() -> card.setStructureShown(true));
        await("the column lists the methods", () -> {
            ProjectMapCardStructure column = cardStructures().get(card);
            return column != null && columnLabels(column).contains("m11(int)");
        });
        return FxTestSupport.callOnFx(() -> cardStructures().get(card));
    }

    /** Opens the declaration card of {@code label} from a card's column, as its Enter does. */
    private ProjectMapPreview openDeclaration(ProjectMapCardStructure column, String label) throws Exception {
        Path[] key = new Path[1];
        FxTestSupport.runOnFx(() -> {
            var tree = column.tree();
            for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                ProjectMapStructure.Node node = tree.getTreeItem(i).getValue().node();
                if (node != null && node.symbol().label().equals(label)) {
                    key[0] = node.path();
                    tree.getSelectionModel().select(i);
                    column.focus();
                    key(tree, KeyCode.ENTER, false, false);
                    return;
                }
            }
            throw new AssertionError("no row " + label);
        });
        await(
                "declaration card " + label,
                () -> previews().containsKey(key[0])
                        && !previews().get(key[0]).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(key[0]));
    }

    private Path keyOf(ProjectMapPreview card) {
        return previews().entrySet().stream()
                .filter(entry -> entry.getValue() == card)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private List<Path> rows() {
        return entries().stream().map(ProjectMapModel.Entry::path).toList();
    }

    private List<ProjectMapModel.Entry> entries() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                .toList();
    }

    private List<String> rowLabels() {
        return entries().stream()
                .map(entry ->
                        entry.symbol() == null ? entry.name() : entry.symbol().label())
                .toList();
    }

    private ProjectMapModel.Entry entryLabelled(String label) {
        return entries().stream()
                .filter(entry ->
                        entry.symbol() != null && entry.symbol().label().equals(label))
                .findFirst()
                .orElseThrow();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private Map<Path, ProjectMapNotePreview> noteCards() {
        return FxTestSupport.field(map.view(), "notePreviews");
    }

    private Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures() {
        return FxTestSupport.field(map.view(), "cardStructures");
    }

    private static List<String> columnLabels(ProjectMapCardStructure column) {
        return column.visibleRows().stream()
                .map(row ->
                        row.node() == null ? row.message() : row.node().symbol().label())
                .toList();
    }

    private static List<String> labels(ProjectMapStructure structure, Path file) {
        try {
            return FxTestSupport.callOnFx(() -> {
                List<String> out = new ArrayList<>();
                collect(structure.view(file).roots(), out);
                return out;
            });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void collect(List<ProjectMapStructure.Node> nodes, List<String> out) {
        for (ProjectMapStructure.Node node : nodes) {
            out.add(node.symbol().label());
            collect(node.children(), out);
        }
    }

    static void key(Node target, KeyCode code, boolean shift, boolean alt) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, alt, false));
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    /** The Personal Notes of a few paths, as the window's notes coordinator would serve them. */
    private static final class Notes implements ProjectPanel.MarkerActions {
        private final Map<Path, List<PersonalNote>> notes = new HashMap<>();

        void add(Path path, String body) {
            notes.computeIfAbsent(path, ignored -> new ArrayList<>())
                    .add(PersonalNote.create(
                            FileIdentity.of(path),
                            NoteScope.LINE,
                            new TextAnchor(0, 0, 0, 0, "", "", ""),
                            body,
                            List.of()));
        }

        @Override
        public boolean personalNotesEnabled() {
            return true;
        }

        @Override
        public boolean hasBookmarks(Path file) {
            return false;
        }

        @Override
        public boolean hasPersonalNotes(Path file) {
            return notes.containsKey(file);
        }

        @Override
        public void addBookmark(Path file) {}

        @Override
        public void addPersonalNote(Path file) {}

        @Override
        public List<PersonalNote> personalNotes(Path path) {
            return notes.getOrDefault(path, List.of());
        }
    }
}
