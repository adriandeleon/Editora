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

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitChanges;
import com.editora.git.GitService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map gaps round 3, item 3: the Changes card compares the working tree with where the branch left the default branch
 * (or another one) — what the branch changes, as a pull request shows it — marks the files that changed on the Map's
 * rows and the folders holding them, compares a file with that base, and marks the words a changed line changes.
 */
@Tag("fx")
class MapBranchReviewFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final String PATCH = String.join(
            "\n",
            "diff --git a/src/A.java b/src/A.java",
            "--- a/src/A.java",
            "+++ b/src/A.java",
            "@@ -1,3 +1,4 @@",
            " class A {",
            "-  int one = 1;",
            "+  long one = 1;",
            "+  long two;",
            " }",
            "");

    /** The Git the Map asks, answering each base as the test says; answers wait when {@code held} is set. */
    private static final class Code implements ProjectMapView.FollowCode {
        GitChanges.Result answer;
        final List<GitService.ChangesBase> bases = new CopyOnWriteArrayList<>();
        final List<String> compared = new CopyOnWriteArrayList<>();

        @Nullable
        List<Consumer<GitChanges.Result>> held;

        Code(GitChanges.Result answer) {
            this.answer = answer;
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
        public void workingChanges(Path folder, GitService.ChangesBase base, Consumer<GitChanges.Result> found) {
            bases.add(base);
            if (held != null) {
                held.add(found);
            } else {
                found.accept(answer);
            }
        }

        @Override
        public void reviewRefs(Path folder, Consumer<GitService.ReviewRefs> found) {
            found.accept(
                    new GitService.ReviewRefs("feature", "main", List.of("feature", "main", "origin/main", "topic")));
        }

        @Override
        public void compareWithHead(Path file) {
            compared.add("HEAD " + file.getFileName());
        }

        @Override
        public void compareWith(Path file, String ref, String label) {
            compared.add(ref + " " + file.getFileName() + " " + label);
        }
    }

    @Test
    void theCardReviewsTheBranchMarksTheMapAndTheChangedWords() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path a = Files.writeString(
                Files.createDirectories(project.resolve("src")).resolve("A.java"), "class A {\n  long one = 1;\n}\n");
        Path n = Files.writeString(
                Files.createDirectories(project.resolve("docs")).resolve("n.md"), "# N\n");
        GitChanges.Result branch = GitChanges.merge(
                        GitChanges.parse(PATCH), List.of(GitChanges.untracked("docs/n.md", "# N\n", false)), false)
                .withBase("0123456789abcdef0123456789abcdef01234567");
        Code code = new Code(branch);
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setFollowCode(code);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1300, 850);
            scene.getStylesheets()
                    .add(MapBranchReviewFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            return made;
        });
        try {
            MapSurface surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            waitFor(() -> surface.contains(project.resolve("src")));
            FxTestSupport.runOnFx(() -> {
                view.openChanges(null);
                ProjectMapChangesCard card = view.changesCard();
                assertEquals(List.of(GitService.ChangesBase.HEAD), code.bases, "the uncommitted changes first");
                assertEquals(0, surface.reviewMark(a), "HEAD's changes are the rows' Git marks already");

                // Against offers HEAD, this branch since main, and since another branch.
                List<MenuItem> items = card.againstItems();
                assertEquals(4, items.size());
                assertEquals(
                        tr("project.map.changes.againstHeadItem"), items.get(0).getText());
                assertEquals(
                        tr("project.map.changes.againstForkItem", "main"),
                        items.get(1).getText());
                assertTrue(items.get(2) instanceof SeparatorMenuItem);
                Menu other = (Menu) items.get(3);
                assertEquals(
                        List.of("origin/main", "topic"),
                        other.getItems().stream().map(MenuItem::getText).toList(),
                        "not the branch itself, nor main twice");

                items.get(1).fire();
                assertEquals(new GitService.ChangesBase("main", true), card.base());
                assertEquals(new GitService.ChangesBase("main", true), code.bases.getLast());
                assertEquals(tr("project.map.changes.againstFork", "main"), againstText(card));
                assertEquals(ProjectMapChangesCard.summaryText(branch), card.summaryText());

                // The branch's files are marked on the Map, and the folders holding them.
                assertEquals('M', surface.reviewMark(a));
                assertEquals('U', surface.reviewMark(n));
                assertEquals('*', surface.reviewMark(project.resolve("src")));
                assertEquals(0, surface.reviewMark(project.resolve("README.md")));
                String tip = (String) FxTestSupport.call(
                        surface,
                        "tooltipText",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        new ProjectMapModel.Entry(project.resolve("src"), project, 1, true));
                assertTrue(tip.contains(tr("project.map.tooltip.reviewHolds")), tip);
                String fileTip = (String) FxTestSupport.call(
                        surface,
                        "tooltipText",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        new ProjectMapModel.Entry(n, project, 2, false));
                assertTrue(fileTip.contains(tr("project.map.tooltip.reviewChanged")), fileTip);
                surface.snapshot(null, null); // the stripes are drawn

                // Compare compares with the branch's base.
                card.pickFile(1); // src/A.java, after docs/n.md
                Button compare = FxTestSupport.field(card, "compare");
                assertEquals(tr("project.map.changes.compareBase"), compare.getText());
                compare.fire();
                assertEquals(
                        "0123456789abcdef0123456789abcdef01234567 A.java "
                                + tr("project.map.changes.againstFork", "main"),
                        code.compared.getLast());

                // The changed words of the changed line are marked, on both sides.
                Map<Integer, int[][]> words = card.words();
                assertEquals(java.util.Set.of(2, 3), words.keySet(), "the removed line and the added one it became");
                ListView<GitChanges.Line> diff = FxTestSupport.field(card, "diff");
                diff.applyCss();
                diff.layout();
                assertFalse(diff.lookupAll(".project-map-changes-word").isEmpty(), "a word drawn marked");
            });

            // Another branch, then one Git knows nothing of, then one with nothing changed since.
            FxTestSupport.runOnFx(() -> {
                ProjectMapChangesCard card = view.changesCard();
                Menu other = (Menu) card.againstItems().get(3);
                code.answer = new GitChanges.Result(List.of(), false, "no base: topic");
                other.getItems().get(1).fire();
                assertEquals(tr("project.map.changes.noBase", "topic"), card.summaryText());
                assertTrue(surface.reviewMark(a) == 0, "nothing read: nothing marked");
                code.answer = new GitChanges.Result(List.of(), false, "").withBase("abc");
                card.setBase(new GitService.ChangesBase("origin/main", true));
                assertEquals(tr("project.map.changes.noneSince", "origin/main"), card.summaryText());

                // A slower answer for a base left since is dropped.
                code.held = new ArrayList<>();
                card.setBase(new GitService.ChangesBase("main", true));
                card.setBase(GitService.ChangesBase.HEAD);
                code.held.getFirst().accept(branch);
                assertEquals(tr("project.map.changes.reading"), card.summaryText());
                code.held.getLast().accept(GitChanges.Result.UNAVAILABLE);
                assertEquals(tr("project.map.changes.unavailable"), card.summaryText());
                code.held = null;
                assertEquals(0, surface.reviewMark(a));

                // Against HEAD, Compare is HEAD's.
                code.answer = branch;
                card.setBase(GitService.ChangesBase.HEAD); // the same: nothing read again
                FxTestSupport.<Button>field(card, "refresh").fire();
                card.pickFile(1); // src/A.java, after docs/n.md
                FxTestSupport.<Button>field(card, "compare").fire();
                assertEquals("HEAD A.java", code.compared.getLast());

                // Closing the card takes the marks away.
                card.setBase(new GitService.ChangesBase("main", true));
                assertEquals('M', surface.reviewMark(a));
                MapCards cards = FxTestSupport.field(view, "cards");
                FxTestSupport.call(cards, "closeChangesCard", new Class<?>[] {});
                assertNull(view.changesCard());
                assertEquals(0, surface.reviewMark(a));
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    @Test
    void gitAnswersOffTheFxThreadAndAStoppedServiceAnswersNothing() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        com.editora.git.GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("A.txt"), "a\n");
        com.editora.git.GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        Files.writeString(repo.resolve("A.txt"), "a\nb\n");
        GitService service = new GitService();
        try {
            assertEquals(
                    1,
                    changes(service, repo, new GitService.ChangesBase("main", true))
                            .files()
                            .size());
            assertEquals("main", refs(service, repo).branch());
            service.shutdown();
            assertEquals(
                    GitChanges.Result.UNAVAILABLE, changes(service, repo, new GitService.ChangesBase("main", true)));
            assertEquals(GitService.ReviewRefs.NONE, refs(service, repo));
        } finally {
            service.shutdown();
        }
    }

    private static GitChanges.Result changes(GitService service, Path folder, GitService.ChangesBase base)
            throws Exception {
        java.util.concurrent.CompletableFuture<GitChanges.Result> answer =
                new java.util.concurrent.CompletableFuture<>();
        service.workingChanges(folder, base, answer::complete);
        return answer.get(30, TimeUnit.SECONDS);
    }

    private static GitService.ReviewRefs refs(GitService service, Path folder) throws Exception {
        java.util.concurrent.CompletableFuture<GitService.ReviewRefs> answer =
                new java.util.concurrent.CompletableFuture<>();
        service.reviewRefs(folder, answer::complete);
        return answer.get(30, TimeUnit.SECONDS);
    }

    @Test
    void wordsArePairedLineByLineAndCutIntoPieces() {
        List<GitChanges.Line> lines = List.of(
                new GitChanges.Line(GitChanges.Kind.HUNK, 1, 1, "@@ -1,4 +1,3 @@"),
                new GitChanges.Line(GitChanges.Kind.REMOVED, 1, 0, "int a = 1;"),
                new GitChanges.Line(GitChanges.Kind.REMOVED, 2, 0, "int b = 2;"),
                new GitChanges.Line(GitChanges.Kind.ADDED, 0, 1, "int a = 10;"),
                new GitChanges.Line(GitChanges.Kind.CONTEXT, 3, 2, "x"),
                new GitChanges.Line(GitChanges.Kind.REMOVED, 4, 0, "gone"),
                new GitChanges.Line(GitChanges.Kind.CONTEXT, 5, 3, "y"),
                new GitChanges.Line(GitChanges.Kind.REMOVED, 6, 0, "z".repeat(ProjectMapChangesCard.MAX_WORD_LINE + 1)),
                new GitChanges.Line(GitChanges.Kind.ADDED, 0, 4, "w"));
        Map<Integer, int[][]> words = ProjectMapChangesCard.wordSpans(lines);
        assertEquals(java.util.Set.of(1, 3), words.keySet(), "a with a; b, gone and the long line have no partner");
        assertArrayEquals(new int[] {8, 9}, words.get(1)[0]);
        assertArrayEquals(new int[] {8, 10}, words.get(3)[0]);

        assertEquals(
                List.of(
                        new ProjectMapChangesCard.Piece("int a = ", false),
                        new ProjectMapChangesCard.Piece("10", true),
                        new ProjectMapChangesCard.Piece(";", false)),
                ProjectMapChangesCard.pieces("int a = 10;", words.get(3)));
        assertEquals(List.of(new ProjectMapChangesCard.Piece("abc", false)), ProjectMapChangesCard.pieces("abc", null));
        assertEquals(List.of(new ProjectMapChangesCard.Piece("", false)), ProjectMapChangesCard.pieces("", null));
        assertEquals(
                List.of(new ProjectMapChangesCard.Piece("ab", true)),
                ProjectMapChangesCard.pieces("ab", new int[][] {{0, 9}}),
                "a span past the end stops at it");
        assertEquals(
                List.of(new ProjectMapChangesCard.Piece("a", false), new ProjectMapChangesCard.Piece("b", true)),
                ProjectMapChangesCard.pieces("ab", new int[][] {{1, 2}, {1, 1}}),
                "an empty span adds nothing");

        assertEquals(
                tr("project.map.changes.againstHead"), ProjectMapChangesCard.againstText(GitService.ChangesBase.HEAD));
        assertEquals(
                tr("project.map.changes.againstRef", "v1"),
                ProjectMapChangesCard.againstText(new GitService.ChangesBase("v1", false)));
        assertEquals(
                Map.of(),
                ProjectMapView.reviewChanges(
                        Path.of("/p"), GitService.ChangesBase.HEAD, GitChanges.Result.UNAVAILABLE));
        assertEquals(
                tr("project.map.changes.unavailable"),
                ProjectMapChangesCard.summaryText(
                        GitChanges.Result.UNAVAILABLE, new GitService.ChangesBase("m", true)));
    }

    private static String againstText(ProjectMapChangesCard card) {
        javafx.scene.control.MenuButton against = FxTestSupport.field(card, "against");
        return against.getText();
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out");
    }
}
