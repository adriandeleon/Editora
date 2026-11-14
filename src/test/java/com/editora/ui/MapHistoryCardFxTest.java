package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.git.BlameParser;
import com.editora.git.GitService;
import com.editora.git.LineHistory;
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
 * The Project Map's History card (Map code browser gap 12): a declaration's commits, the picked commit's diff of it,
 * and who last changed each of its lines, opened beside its card from the card's menu or a Structure row's.
 */
@Tag("fx")
class MapHistoryCardFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;
    private final List<String> opened = new CopyOnWriteArrayList<>();

    MapHistoryCardFxTest() throws Exception {}

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

    @Test
    void aDeclarationsHistoryOpensBesideItsCardWithItsCommitsTheirDiffAndItsBlame() throws Exception {
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
            ProjectMapCardStructure column = showStructure(fileCard);
            ProjectMapPreview m5 = openDeclaration(column, "m5(int)");

            FxTestSupport.runOnFx(() -> {
                MenuItem item = menuItem(followMenu(m5), tr("project.map.history.menu"));
                assertFalse(item.isDisable());
                item.fire();
            });
            await(
                    "m5's two commits",
                    () -> history() != null && history().commits().size() == 2);
            ProjectMapHistoryCard history = history();
            FxTestSupport.runOnFx(() -> {
                assertTrue(history.title().contains("m5"), history.title());
                assertEquals(
                        List.of("m5 adds five", "Big methods"),
                        history.commits().stream()
                                .map(LineHistory.Commit::subject)
                                .toList());
                assertTrue(
                        history.summaryText().startsWith(tr("project.map.history.counts", 2, 2)),
                        history.summaryText());
                assertEquals("m5 adds five", history.pickedCommit().subject(), "the newest is picked");
                assertTrue(
                        history.diffLines().contains("+        return a + 5;"),
                        history.diffLines().toString());
                assertTrue(
                        history.diffLines().contains("-        return a;"),
                        history.diffLines().toString());
                assertTrue(history.isVisible());
                double[] card = MapCards.drawnBounds(m5);
                double[] beside = MapCards.drawnBounds(history);
                assertFalse(
                        beside[0] < card[0] + card[2]
                                && card[0] < beside[0] + beside[2]
                                && beside[1] < card[1] + card[3]
                                && card[1] < beside[1] + beside[3],
                        "beside its card, not over it");
                assertTrue(cards().openCards().stream().anyMatch(open -> open.node() == history));
                assertTrue(cards().outputNodes().contains(history));
                assertFalse(cards().isEmpty());
            });

            // Blame: who last changed each of m5's lines now.
            FxTestSupport.runOnFx(() -> history.setMode(ProjectMapHistoryCard.Mode.BLAME));
            await("blame arrived", () -> history.blameTexts().stream().anyMatch(text -> text.contains("Bob")));
            FxTestSupport.runOnFx(() -> {
                List<String> rows = history.blameTexts();
                int bob = indexContaining(rows, "return a + 5;");
                assertTrue(rows.get(bob).startsWith("Bob"), rows.get(bob));
                int ada = indexContaining(rows, "a += 0;");
                assertTrue(rows.get(ada).startsWith("Ada"), rows.get(ada));
                history.pickBlameLine(ada);
                assertEquals("Big methods", history.pickedCommit().subject(), "a line picks its commit");
                assertTrue(history.openPickedBlameLine());
                history.pickBlameLine(bob);
                assertEquals("m5 adds five", history.pickedCommit().subject());
                assertEquals(ProjectMapHistoryCard.Mode.BLAME, history.mode());
            });
            await("the commit opened", () -> opened.size() == 1);
            assertTrue(opened.get(0).endsWith(" Big.java null"), opened.toString());

            // Enter on a commit opens its diff too; the next card opens in Blame, as this one was left.
            FxTestSupport.runOnFx(() -> {
                ListView<?> commits = FxTestSupport.field(history, "commits");
                key(commits, KeyCode.ENTER);
            });
            await("Enter opened the commit", () -> opened.size() == 2);

            // Escape in it closes it.
            FxTestSupport.runOnFx(() -> key(history, KeyCode.ESCAPE));
            await("closed", () -> history() == null);
            FxTestSupport.runOnFx(() -> assertNull(history.getParent()));

            // Asked again: opens with Blame, the way it was left.
            FxTestSupport.runOnFx(() -> cards().openHistory(m5));
            await("reopened", () -> history() != null && history().commits().size() == 2);
            FxTestSupport.runOnFx(() ->
                    assertEquals(ProjectMapHistoryCard.Mode.BLAME, history().mode()));

            // A file card has no declaration; nonsense lines and a file outside any repository have no history.
            FxTestSupport.runOnFx(() -> {
                assertTrue(
                        followMenu(fileCard).getItems().stream()
                                .noneMatch(
                                        item -> tr("project.map.history.menu").equals(item.getText())),
                        "a file card's menu has no Declaration History");
                assertNull(cards().openHistory(fileCard));
                cards().close(history());
                assertNull(history());
            });
            List<LineHistory.Status> statuses = new CopyOnWriteArrayList<>();
            Path outside = Files.createDirectories(temp.resolve("outside")).resolve("O.java");
            Files.writeString(outside, "class O {}\n");
            FxTestSupport.runOnFx(() -> {
                service.lineHistory(big, -1, 0, 10, result -> statuses.add(result.status()));
                service.lineHistory(big, 3, 1, 10, result -> statuses.add(result.status()));
                service.lineHistory(big, 0, 1, 0, result -> statuses.add(result.status()));
                service.lineHistory(outside, 0, 0, 10, result -> statuses.add(result.status()));
            });
            await("four answers", () -> statuses.size() == 4);
            assertEquals(
                    List.of(LineHistory.Status.UNAVAILABLE),
                    statuses.stream().distinct().toList());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aStructureRowsMenuOpensItAndOneHistoryCardServesEachDeclarationInTurn() throws Exception {
        writeBig();
        List<String> asked = new CopyOnWriteArrayList<>();
        Map<String, LineHistory.Result> answers = new java.util.concurrent.ConcurrentHashMap<>();
        map = openMap();
        FxTestSupport.runOnFx(() -> map.view().setFollowCode(new FakeGit(asked, answers)));
        Path big = root.resolve("Big.java");
        ProjectMapPreview fileCard = openFileCard(big);
        ProjectMapCardStructure column = showStructure(fileCard);

        answers.put("m0", new LineHistory.Result(LineHistory.Status.NEW_LINES, List.of(), false, root, ""));
        FxTestSupport.runOnFx(() -> menuItem(rowMenu(column, "m0(int)"), tr("project.map.history.menu"))
                .fire());
        await("m0's history asked", () -> history() != null && asked.size() == 1);
        ProjectMapHistoryCard history = history();
        await("its card is open", () -> history.file() != null);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("project.map.history.newLines"), history.summaryText());
            assertTrue(history.commits().isEmpty());
            assertTrue(history.declarationName().contains("m0"), history.declarationName());
            assertTrue(history.startLine() >= 0 && history.endLine() > history.startLine());
        });

        LineHistory.Commit commit = new LineHistory.Commit(
                "abcdef0123456789",
                "Cy",
                1,
                "m3 commit",
                "Big.java",
                "Old.java",
                List.of("@@ -1 +1 @@", "-a", "+b"),
                1,
                1);
        answers.put("m3", new LineHistory.Result(LineHistory.Status.OK, List.of(commit), true, root, ""));
        FxTestSupport.runOnFx(() -> menuItem(rowMenu(column, "m3(int)"), tr("project.map.history.menu"))
                .fire());
        await("m3's history", () -> history().commits().size() == 1);
        FxTestSupport.runOnFx(() -> {
            assertSame(history, history(), "one History card, filled again");
            assertTrue(history.title().contains("m3"), history.title());
            assertEquals(tr("project.map.history.countsMore", 1, 1), history.summaryText());
            assertEquals(List.of("@@ -1 +1 @@", "-a", "+b"), history.diffLines());
            // An answer for the earlier ask is dropped.
            long ask = history.show(history.file(), "m3", history.startLine(), history.endLine(), "");
            history.setHistory(ask - 1, LineHistory.Result.unavailable());
            history.setBlame(ask - 1, List.of());
            assertEquals(tr("project.map.history.reading"), history.summaryText());
            history.setHistory(ask, new LineHistory.Result(LineHistory.Status.OK, List.of(commit), false, root, ""));
            history.setLines(ask, "a\nb\nc\n");
            history.setBlame(ask, List.of());
            assertTrue(history.openPickedBlameLine() == false, "no line picked");

            // Open Commit, from the picked commit: its old path goes along.
            Region bar = FxTestSupport.field(history, "openCommit");
            ((javafx.scene.control.Button) bar).fire();
        });
        await("opened", () -> opened.size() == 1);
        assertEquals(root + " abcdef0123456789 Big.java Old.java", opened.get(0));

        // Escape on the map closes it with the other cards; the map's zoom carries it along.
        FxTestSupport.runOnFx(() -> {
            cards().bringIntoView(history);
            double scale = history.mapScale();
            cards().moveWithViewport(0, 0, 1, 0, 0, 1.25);
            assertEquals(1.25, history.mapScale(), 1e-9);
            cards().moveWithViewport(0, 0, 1.25, 0, 0, scale);
            assertTrue(cards().closeVisible());
            assertNull(cards().historyCard());
        });
    }

    @Test
    void itMovesAndResizesByKeyAndMouseAndItsRowsAnswerDoubleClicksAndEnter() throws Exception {
        List<String> escaped = new CopyOnWriteArrayList<>();
        List<String> modes = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapHistoryCard card = FxTestSupport.callOnFx(() -> {
            ProjectMapHistoryCard made = new ProjectMapHistoryCard();
            javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane(made);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(pane, 1000, 800));
            stage[0].getScene()
                    .getStylesheets()
                    .add(MapHistoryCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].show();
            made.constrainTo(500, 500); // not placed yet: nothing to fit
            made.setOnEscape(() -> escaped.add("escape"));
            made.setOnModeChanged(mode -> modes.add(mode.name()));
            made.setOnOpenCommit(commit -> opened.add(commit.subject()));
            made.place(new ProjectMapPreview.Placement(20, 20, 480, 540));
            long ask = made.show(Path.of("/p/F.java"), "", 1, 3, "a\nb\nc\nd\n");
            LineHistory.Commit newer =
                    new LineHistory.Commit("aaaaaaa111", "Ann", 10, "newer", "F.java", null, List.of("+x"), 1, 0);
            LineHistory.Commit older =
                    new LineHistory.Commit("bbbbbbb222", "Ben", 5, "older", "F.java", null, List.of("-y"), 0, 1);
            made.setHistory(ask, new LineHistory.Result(LineHistory.Status.OK, List.of(newer, older), false, null, ""));
            List<BlameParser.BlameLine> blame = new ArrayList<>();
            blame.add(new BlameParser.BlameLine("0000000", "-", 0, "", true));
            blame.add(new BlameParser.BlameLine("bbbbbbb222", "Ben", 5, "older", false));
            blame.add(new BlameParser.BlameLine("0000000", "Not Yet", 0, "", true));
            blame.add(new BlameParser.BlameLine("ccccccc333", "Cy", 3, "elsewhere", false));
            made.setBlame(ask, blame);
            made.applyCss();
            made.layout();
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                assertEquals(tr("project.map.history.titleFile", "F.java"), card.title(), "no name: the file's");
                assertTrue(card.placed());

                // Alt+arrows move it, Alt+Shift+arrows resize it.
                double x = card.getLayoutX();
                double width = card.getWidth();
                Event.fireEvent(card, keyEvent(KeyCode.RIGHT, false, true));
                assertEquals(x + ProjectMapPreview.KEYBOARD_STEP, card.getLayoutX(), 1e-9);
                Event.fireEvent(card, keyEvent(KeyCode.RIGHT, true, true));
                assertEquals(width + ProjectMapPreview.KEYBOARD_STEP, card.getWidth(), 1e-9);
                Event.fireEvent(card, keyEvent(KeyCode.ESCAPE, true, false));
                assertTrue(escaped.isEmpty(), "Shift+Escape is not Escape");
                Event.fireEvent(card, keyEvent(KeyCode.ESCAPE, false, false));
                assertEquals(1, escaped.size());

                // Dragged by its title bar, sized by its grip; another button does neither.
                Region titleBar = FxTestSupport.field(card, "titleBar");
                Region grip = FxTestSupport.field(card, "resizeGrip");
                double y = card.getLayoutY();
                Event.fireEvent(titleBar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 100, 100, false));
                Event.fireEvent(titleBar, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 100, 100, true));
                Event.fireEvent(titleBar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 130, 110, false));
                assertEquals(y, card.getLayoutY(), 1e-9, "a drag with no button down moves nothing");
                Event.fireEvent(titleBar, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 130, 110, true));
                assertEquals(y + 10, card.getLayoutY(), 1e-9);
                double height = card.getHeight();
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 0, 0, false));
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 0, 0, true));
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 0, -40, false));
                assertEquals(height, card.getHeight(), 1e-9);
                Event.fireEvent(grip, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 0, -40, true));
                assertEquals(height - 40, card.getHeight(), 1e-9);
                card.constrainTo(300, 300);
                assertTrue(card.getWidth() < 300 && card.getHeight() < 300, "fitted into a smaller map area");

                // Changes and Blame: one of them is always on.
                ToggleGroupProbe.clear(card);
                assertEquals(ProjectMapHistoryCard.Mode.CHANGES, card.mode());
                javafx.scene.control.ToggleButton blameButton = FxTestSupport.field(card, "blameButton");
                blameButton.fire();
                assertEquals(ProjectMapHistoryCard.Mode.BLAME, card.mode());
                assertEquals(List.of("BLAME"), modes);
                card.applyCss();
                card.layout();
            });

            // A double click on a commit, or on a blamed line, opens its commit; Enter on a blamed line too.
            FxTestSupport.runOnFx(() -> {
                ListView<?> commits = FxTestSupport.field(card, "commits");
                javafx.scene.control.ListCell<?> first = cellOf(commits, 0);
                Event.fireEvent(first, click(1));
                assertTrue(opened.isEmpty(), "one click picks");
                Event.fireEvent(first, click(2));
                assertEquals(List.of("newer"), opened);

                ListView<?> blame = FxTestSupport.field(card, "blame");
                List<String> rows = card.blameTexts();
                assertTrue(rows.get(0).startsWith("Ben"), rows.toString());
                assertTrue(rows.get(1).startsWith(tr("project.map.history.uncommitted")), rows.toString());
                Event.fireEvent(cellOf(blame, 0), click(2));
                assertEquals(List.of("newer", "older"), opened);
                card.pickBlameLine(1);
                key(blame, KeyCode.ENTER);
                assertEquals(2, opened.size(), "an uncommitted line has no commit");
                card.pickBlameLine(2);
                assertEquals("newer", card.pickedCommit().subject(), "a commit not listed picks nothing new");
                assertFalse(card.openPickedBlameLine());
                card.pickBlameLine(0);
                assertEquals("older", card.pickedCommit().subject());
                key(blame, KeyCode.ENTER);
                assertEquals(3, opened.size());
                ListView<?> list = FxTestSupport.field(card, "commits");
                list.getSelectionModel().clearSelection();
                assertNull(card.pickedCommit());
                assertTrue(card.diffLines().isEmpty());
                ((javafx.scene.control.Button) FxTestSupport.field(card, "openCommit")).fire();
                key(list, KeyCode.ENTER);
                assertEquals(3, opened.size(), "nothing picked: nothing to open");
                card.focusContent();
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    /** Presses the mode button that is on again: it stays on. */
    private static final class ToggleGroupProbe {
        static void clear(ProjectMapHistoryCard card) {
            javafx.scene.control.ToggleGroup group = FxTestSupport.field(card, "modes");
            javafx.scene.control.ToggleButton on = (javafx.scene.control.ToggleButton) group.getSelectedToggle();
            on.fire();
            assertSame(on, group.getSelectedToggle(), "one of the two is always on");
        }
    }

    private static javafx.scene.control.ListCell<?> cellOf(ListView<?> list, int index) {
        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof javafx.scene.control.ListCell<?> cell && cell.getIndex() == index && !cell.isEmpty()) {
                return cell;
            }
        }
        throw new AssertionError("no cell " + index);
    }

    private static KeyEvent keyEvent(KeyCode code, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, alt, false);
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, MouseButton button, double x, double y, boolean down) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                button,
                1,
                false,
                false,
                false,
                false,
                down && button == MouseButton.PRIMARY,
                false,
                down && button == MouseButton.SECONDARY,
                false,
                false,
                false,
                null);
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
                true,
                null);
    }

    @Test
    void theCardSpotIsBesideItsSourceWhereItCoversLeast() {
        double[] source = {400, 100, 300, 200};
        List<double[]> none = List.of();
        double[] right = MapCards.historySpot(source, 480, 540, 320, 260, 1400, 900, none);
        assertEquals(400 + 300 + 18, right[0], 1e-9, "right of it");
        assertEquals(100, right[1], 1e-9, "level with its top");
        assertEquals(480, right[2], 1e-9);

        // Something on its right: the left side covers less.
        double[] wide = {500, 100, 300, 200};
        double[] left =
                MapCards.historySpot(wide, 380, 540, 320, 260, 1400, 900, List.of(new double[] {820, 0, 600, 900}));
        assertEquals(500 - 18 - 380, left[0], 1e-9, "left of it");

        // A narrow panel: below it.
        double[] narrowSource = {10, 10, 300, 100};
        double[] below = MapCards.historySpot(narrowSource, 480, 540, 320, 260, 400, 900, none);
        assertEquals(10 + 100 + 18, below[1], 1e-9, "below it");

        assertNull(MapCards.historySpot(new double[] {10, 10, 380, 880}, 480, 540, 320, 260, 400, 900, none));
    }

    @Test
    void theSummaryAndRowsSayWhatGitAnswered() {
        long now = 1_000_000;
        assertEquals(
                tr("project.map.history.unavailable"),
                ProjectMapHistoryCard.summaryText(LineHistory.Result.unavailable(), now));
        assertEquals(
                tr("project.map.history.tooSlow"),
                ProjectMapHistoryCard.summaryText(
                        new LineHistory.Result(LineHistory.Status.TOO_SLOW, List.of(), false, null, ""), now));
        assertEquals(
                tr("project.map.history.failed", "bad"),
                ProjectMapHistoryCard.summaryText(LineHistory.Result.failed(null, "bad"), now));
        assertEquals(
                tr("project.map.history.none"),
                ProjectMapHistoryCard.summaryText(
                        new LineHistory.Result(LineHistory.Status.OK, List.of(), false, null, ""), now));
        LineHistory.Commit first =
                new LineHistory.Commit("1234567890", "Ann", now - 7200, "s", "F", null, List.of(), 2, 0);
        String one = ProjectMapHistoryCard.summaryText(
                new LineHistory.Result(LineHistory.Status.OK, List.of(first), false, null, ""), now);
        assertEquals(
                tr("project.map.history.counts", 1, 1)
                        + " "
                        + tr(
                                "project.map.history.since",
                                "Ann",
                                GitBlameCoordinator.relativeTimeLabel(now - 7200, now)),
                one);
        assertEquals(
                tr(
                        "project.map.history.commitDetail",
                        "Ann",
                        GitBlameCoordinator.relativeTimeLabel(now - 7200, now),
                        "1234567",
                        2,
                        0),
                ProjectMapHistoryCard.commitDetail(first, now));

        assertEquals("", ProjectMapHistoryCard.blameAnnotation(null, now));
        assertEquals(
                tr("project.map.history.uncommitted"),
                ProjectMapHistoryCard.blameAnnotation(new BlameParser.BlameLine("0000000", "Not", 0, "", true), now));
        assertEquals(
                tr("project.map.history.blameBy", "Bo", GitBlameCoordinator.relativeTimeLabel(now - 60, now)),
                ProjectMapHistoryCard.blameAnnotation(
                        new BlameParser.BlameLine("1234567", "Bo", now - 60, "", false), now));

        assertEquals("project-map-history-hunk", ProjectMapHistoryCard.diffStyle("@@ -1 +1 @@"));
        assertEquals("project-map-history-added", ProjectMapHistoryCard.diffStyle("+x"));
        assertEquals("project-map-history-removed", ProjectMapHistoryCard.diffStyle("-x"));
        assertNull(ProjectMapHistoryCard.diffStyle(" x"));

        assertEquals(List.of("b", "c"), ProjectMapHistoryCard.declarationLines("a\nb\nc\nd", 1, 2));
        assertEquals(List.of("d"), ProjectMapHistoryCard.declarationLines("a\nb\nc\nd", 3, 9), "as many as there are");
        assertTrue(ProjectMapHistoryCard.declarationLines("a", 5, 6).isEmpty());
        assertTrue(ProjectMapHistoryCard.declarationLines("a", -1, 0).isEmpty());
    }

    // --- helpers --------------------------------------------------------------------------------------------

    /** Git as the panel serves it, recording what is opened. */
    private ProjectMapView.FollowCode followCode(GitService service) {
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
            public void lineHistory(
                    Path file, int startLine, int endLine, int max, Consumer<LineHistory.Result> found) {
                service.lineHistory(file, startLine, endLine, max, found);
            }

            @Override
            public void blame(Path repo, Path file, Consumer<List<BlameParser.BlameLine>> found) {
                service.blame(repo, file, found);
            }

            @Override
            public void openCommitFile(Path repo, String hash, String path, @Nullable String previousPath) {
                opened.add(repo + " " + hash + " " + path + " " + previousPath);
            }
        };
    }

    /** Git answering with canned histories by the declaration's name. */
    private final class FakeGit implements ProjectMapView.FollowCode {
        private final List<String> asked;
        private final Map<String, LineHistory.Result> answers;

        FakeGit(List<String> asked, Map<String, LineHistory.Result> answers) {
            this.asked = asked;
            this.answers = answers;
        }

        @Override
        public void follow(Path file, int line, int column, @Nullable String name, String commandId) {}

        @Override
        public void relatedFiles(Path file, Consumer<List<Path>> found) {
            found.accept(List.of());
        }

        @Override
        public void fileHistory(Path file) {}

        @Override
        public void lineHistory(Path file, int startLine, int endLine, int max, Consumer<LineHistory.Result> found) {
            String name = history() == null ? "" : history().declarationName();
            asked.add(name);
            LineHistory.Result answer = answers.entrySet().stream()
                    .filter(entry -> name.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(LineHistory.Result.unavailable());
            found.accept(answer);
        }

        @Override
        public void blame(Path repo, Path file, Consumer<List<BlameParser.BlameLine>> found) {
            found.accept(List.of());
        }

        @Override
        public void openCommitFile(Path repo, String hash, String path, @Nullable String previousPath) {
            opened.add(repo + " " + hash + " " + path + " " + previousPath);
        }
    }

    private static ContextMenu followMenu(ProjectMapPreview card) {
        ContextMenu menu = new ContextMenu();
        @SuppressWarnings("unchecked")
        List<MenuItem> items = (List<MenuItem>) FxTestSupport.call(card, "followMenuItems", new Class<?>[0]);
        menu.getItems().setAll(items);
        return menu;
    }

    private static ContextMenu rowMenu(ProjectMapCardStructure column, String label) {
        java.util.function.Function<ProjectMapStructure.Node, ContextMenu> menuFor =
                FxTestSupport.field(column, "menuFor");
        var tree = column.tree();
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            ProjectMapStructure.Node node = tree.getTreeItem(i).getValue().node();
            if (node != null && node.symbol().label().equals(label)) {
                return menuFor.apply(node);
            }
        }
        throw new AssertionError("no row " + label);
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        for (MenuItem item : ProjectMapPreview.flatten(menu.getItems())) { // submenus' items too (the Git one)
            if (text.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("no " + text + " in "
                + menu.getItems().stream().map(MenuItem::getText).toList());
    }

    private static int indexContaining(List<String> rows, String text) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError("no row with " + text + " in " + rows);
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

    private @Nullable ProjectMapHistoryCard history() {
        return cards().historyCard();
    }

    private MapCards cards() {
        return FxTestSupport.field(map.view(), "cards");
    }

    private Path writeBig() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        return Files.writeString(root.resolve("Big.java"), ProjectMapCardsReview3FxTest.source());
    }

    private Fixture openMap() throws Exception {
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(MapHistoryCardFxTest.class
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
            return column != null
                    && column.visibleRows().stream()
                            .anyMatch(row -> row.node() != null
                                    && row.node().symbol().label().equals("m11(int)"));
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
                    key(tree, KeyCode.ENTER);
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

    private List<Path> rows() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0])).path())
                .toList();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures() {
        return FxTestSupport.field(map.view(), "cardStructures");
    }

    private static void key(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
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

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}
}
