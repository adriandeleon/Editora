package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitChanges;
import com.editora.git.GitFileStatus;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's Changes card (Map code browser round 2): the working tree's changed files and their diffs, opened
 * from ⋯ and a changed row's menu, stepped through hunk by hunk, each line opening its file's card or the editor.
 */
@Tag("fx")
class MapChangesCardFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** src/A.java: line 2 changed and a line added; docs/n.md untracked. */
    private static final String PATCH = String.join(
            "\n",
            "diff --git a/src/A.java b/src/A.java",
            "--- a/src/A.java",
            "+++ b/src/A.java",
            "@@ -1,3 +1,4 @@",
            " class A {",
            "-  int one;",
            "+  long one;",
            "+  long two;",
            " }",
            "@@ -8,1 +9,2 @@",
            " x",
            "+y",
            "");

    /** The Git the Map asks: the patch above, or whatever a test puts in {@code answer}. */
    private static final class Code implements ProjectMapView.FollowCode {
        GitChanges.Result answer;
        int asked;
        final List<String> followed = new CopyOnWriteArrayList<>();
        final List<Path> compared = new CopyOnWriteArrayList<>();

        Code(GitChanges.Result answer) {
            this.answer = answer;
        }

        @Override
        public void follow(Path file, int line, int column, @Nullable String name, String commandId) {
            followed.add(file.getFileName() + ":" + line + ":" + commandId);
        }

        @Override
        public void relatedFiles(Path file, Consumer<List<Path>> found) {
            found.accept(List.of());
        }

        @Override
        public void fileHistory(Path file) {}

        @Override
        public void workingChanges(Path folder, Consumer<GitChanges.Result> found) {
            asked++;
            found.accept(answer);
        }

        @Override
        public void compareWithHead(Path file) {
            compared.add(file);
        }
    }

    @Test
    void theChangesCardListsTheFilesStepsThroughTheHunksAndOpensTheirLines() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path a = Files.writeString(
                Files.createDirectories(project.resolve("src")).resolve("A.java"),
                "class A {\n  long one;\n  long two;\n}\n\n\n\n\nx\ny\n");
        Files.writeString(project.resolve("src/B.java"), "class B {}\n");
        Path notes = Files.writeString(
                Files.createDirectories(project.resolve("docs")).resolve("n.md"), "# N\n");
        GitChanges.Result changes = GitChanges.merge(
                GitChanges.parse(PATCH), List.of(GitChanges.untracked("docs/n.md", "# N\n", false)), false);
        Code code = new Code(changes);
        List<String> said = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setOnStatus(said::add);
            made.setFollowCode(code);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1300, 850);
            scene.getStylesheets()
                    .add(MapChangesCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            return made;
        });
        try {
            MapSurface surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            Path src = project.resolve("src");
            waitFor(() -> surface.contains(src));
            FxTestSupport.runOnFx(() -> {
                view.setGitStatus(Map.of(a, GitFileStatus.MODIFIED, notes, GitFileStatus.UNTRACKED));
                // A changed folder's row offers its changes.
                assertTrue(menuTexts(view, surface, src).contains(tr("project.map.changes.menu")));

                MenuItem review = FxTestSupport.field(view, "changesItem");
                review.fire();
                ProjectMapChangesCard card = view.changesCard();
                assertNotNull(card);
                assertEquals(1, code.asked);
                assertEquals(tr("project.map.changes.counts", 2, 4, 1), card.summaryText());
                assertEquals(project.resolve("docs/n.md"), card.pickedPath(), "the first in path order");
                assertSame(
                        card,
                        view.cards.openCards().stream()
                                .filter(open -> open.node() == card)
                                .findFirst()
                                .orElseThrow()
                                .node());
                assertTrue(ProjectMapView.switcherIcon(view.cards.openCards().stream()
                                .filter(open -> open.node() == card)
                                .findFirst()
                                .orElseThrow())
                        != null);

                // Next Change: docs/n.md's one hunk, then A's two, then round.
                card.step(true);
                assertEquals(tr("project.map.changes.step", 1, 3, "docs/n.md", 1), said.getLast());
                card.step(true);
                assertEquals(tr("project.map.changes.step", 2, 3, "src/A.java", 2), said.getLast());
                assertEquals(a, card.pickedPath());
                assertEquals(0, card.hunk());
                assertEquals(2, card.pickedLine(), "the hunk's first changed line");
                card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, false, false, false));
                assertEquals(tr("project.map.changes.step", 3, 3, "src/A.java", 10), said.getLast());
                card.step(true);
                assertEquals(tr("project.map.changes.step", 1, 3, "docs/n.md", 1), said.getLast(), "round");
                card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, true, false, false, false));
                assertEquals(tr("project.map.changes.step", 3, 3, "src/A.java", 10), said.getLast(), "back round");
                card.step(false);
                assertEquals(tr("project.map.changes.step", 2, 3, "src/A.java", 2), said.getLast());

                // A line: the editor at it, or Compare with HEAD.
                card.openPickedLine(4, true); // "+  long two;": line 3, 0-based 2
                assertEquals(List.of("A.java:2:"), code.followed);
                Button compare = FxTestSupport.field(card, "compare");
                compare.fire();
                assertEquals(List.of(a), code.compared);
                Button openInEditor = FxTestSupport.field(card, "openInEditor");
                openInEditor.fire();
                assertEquals("A.java:2:", code.followed.getLast());

                // The row's Show Changes picks its file in the open card.
                view.openChanges(notes);
                assertEquals(notes, card.pickedPath());
                assertEquals(2, code.asked, "asked again");
                view.openChanges(src); // a folder: its first changed file
                assertEquals(a, card.pickedPath());

                // Git's status changes: the card reads again and keeps the file picked.
                code.answer = GitChanges.merge(GitChanges.parse(PATCH), List.of(), false);
                view.setGitStatus(Map.of(a, GitFileStatus.MODIFIED));
                assertEquals(4, code.asked);
                assertEquals(a, card.pickedPath());
                assertEquals(tr("project.map.changes.counts", 1, 3, 1), card.summaryText());

                // Enter on a line: the file's card at it.
                card.openPickedLine(2, false);
            });
            waitFor(() -> view.cards.previews.keySet().stream().anyMatch(key -> key.startsWith(a)));
            FxTestSupport.runOnFx(() -> {
                ProjectMapChangesCard card = view.changesCard();
                view.cards.close(card);
                assertNull(view.changesCard());
                // Next Change typed on the Map with no card: it opens on the first change.
                view.stepChange(true);
                assertNotNull(view.changesCard());
                assertEquals(a, view.changesCard().pickedPath());
                assertEquals(tr("project.map.changes.step", 1, 2, "src/A.java", 2), said.getLast());

                // Nothing changed, and no Git.
                code.answer = GitChanges.merge(List.of(), List.of(), false);
                view.openChanges(null);
                assertEquals(tr("project.map.changes.none"), view.changesCard().summaryText());
                view.changesCard().step(true);
                assertEquals(tr("project.map.changes.none"), said.getLast());
                assertNull(view.changesCard().pickedPath());
                code.answer = GitChanges.Result.UNAVAILABLE;
                view.openChanges(null);
                assertEquals(
                        tr("project.map.changes.unavailable"),
                        view.changesCard().summaryText());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    @Test
    void aFilesRowSaysWhatItIs() {
        GitChanges.FileDiff binary = new GitChanges.FileDiff("b.png", 'M', List.of(), List.of(), 0, 0, true, false);
        GitChanges.FileDiff large = new GitChanges.FileDiff("big.txt", 'U', List.of(), List.of(), 0, 0, false, true);
        GitChanges.FileDiff text = GitChanges.untracked("t.txt", "a\nb\n", false);
        assertEquals(tr("project.map.changes.binaryShort"), ProjectMapChangesCard.fileCounts(binary));
        assertEquals(tr("project.map.changes.tooLargeShort"), ProjectMapChangesCard.fileCounts(large));
        assertEquals(tr("project.map.changes.fileCounts", 2, 0), ProjectMapChangesCard.fileCounts(text));
        assertEquals(tr("project.map.changes.added"), ProjectMapChangesCard.statusName('A'));
        assertEquals(tr("project.map.changes.deleted"), ProjectMapChangesCard.statusName('D'));
        assertEquals(tr("project.map.changes.untracked"), ProjectMapChangesCard.statusName('U'));
        assertEquals(tr("project.map.changes.modified"), ProjectMapChangesCard.statusName('M'));
        assertEquals(
                tr("project.map.changes.countsMore", 1, 2, 0),
                ProjectMapChangesCard.summaryText(new GitChanges.Result(List.of(text), true, "")));
    }

    @Test
    void theCardOnItsOwnKeysPlaceholdersAndAStepAskedEarly() throws Exception {
        Path root = Files.createDirectories(temp.resolve("own")).toRealPath();
        GitChanges.FileDiff binary = new GitChanges.FileDiff("b.png", 'M', List.of(), List.of(), 0, 0, true, false);
        GitChanges.FileDiff large = new GitChanges.FileDiff("c.txt", 'U', List.of(), List.of(), 0, 0, false, true);
        GitChanges.FileDiff mode = new GitChanges.FileDiff("d.sh", 'M', List.of(), List.of(), 0, 0, false, false);
        List<GitChanges.FileDiff> files = new java.util.ArrayList<>(GitChanges.parse(PATCH
                + String.join(
                        "\n",
                        "diff --git a/gone.txt b/gone.txt",
                        "deleted file mode 100644",
                        "@@ -1 +0,0 @@",
                        "-bye",
                        "")));
        files.addAll(List.of(binary, large, mode));
        GitChanges.Result result = GitChanges.merge(files, List.of(), false);
        List<String> opened = new CopyOnWriteArrayList<>();
        List<String> said = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapChangesCard card = FxTestSupport.callOnFx(() -> {
            ProjectMapChangesCard made = new ProjectMapChangesCard();
            made.setOnOpenLine((file, line) -> opened.add("card " + file.getFileName() + ":" + line));
            made.setOnOpenInEditor((file, line) -> opened.add("editor " + file.getFileName() + ":" + line));
            made.setOnStatus(said::add);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(made), 700, 700));
            stage[0].show();
            made.setVisible(true);
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                card.show(root, root.resolve("src/A.java"));
                assertEquals(tr("project.map.changes.reading"), card.summaryText());
                card.step(true); // before the changes: it waits for them
                card.setChanges(result);
                assertEquals(root.resolve("src/A.java"), card.pickedPath());
                assertEquals(0, card.hunk(), "the early step: from before A, A's first hunk");
            });
            FxTestSupport.runOnFx(() -> {
                assertNotNull(card.pickedPath());
                assertEquals(1, said.size(), "the early step happened once the changes came");

                // Refreshed with the same changes: the file and its hunk stay.
                int hunk = card.hunk();
                card.show(root, null);
                card.setChanges(result);
                assertEquals(hunk, card.hunk());

                // Keys: Enter in the list goes into the diff; Enter there opens the card, Ctrl+Enter the editor.
                javafx.scene.control.ListView<?> list = FxTestSupport.field(card, "files");
                javafx.scene.control.ListView<?> diff = FxTestSupport.field(card, "diff");
                card.pickFile(indexOf(result, "src/A.java"));
                list.fireEvent(key(KeyCode.ENTER, false));
                assertEquals(0, card.hunk(), "Enter in the list: the first hunk");
                diff.fireEvent(key(KeyCode.ENTER, false));
                diff.fireEvent(key(KeyCode.ENTER, true));
                assertEquals(List.of("card A.java:1", "editor A.java:1"), opened);
                card.openPickedLine(0, false); // a hunk's header: where it starts
                assertEquals("card A.java:1", opened.getLast());
                card.openPickedLine(6, false); // the second hunk's header: its first added line, 10
                assertEquals("card A.java:9", opened.getLast());
                card.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F7, false, true, false, false));
                assertEquals(0, card.hunk(), "Ctrl+F7 is not Next Change");

                // A deleted file opens nowhere; binary, too large and mode-only files say why there are no lines.
                card.pickFile(indexOf(result, "gone.txt"));
                int before = opened.size();
                card.openPickedLine(1, false);
                assertEquals(before, opened.size());
                Button openInEditor = FxTestSupport.field(card, "openInEditor");
                assertTrue(openInEditor.isDisabled());
                for (String path : List.of("b.png", "c.txt", "d.sh")) {
                    card.pickFile(indexOf(result, path));
                    javafx.scene.control.Label shown = (javafx.scene.control.Label) diff.getPlaceholder();
                    assertFalse(shown.getText().isEmpty(), path);
                }
                list.fireEvent(key(KeyCode.ENTER, false)); // no hunks: stays in the list
                assertEquals(-1, card.hunk());
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    private static int indexOf(GitChanges.Result result, String path) {
        for (int i = 0; i < result.files().size(); i++) {
            if (result.files().get(i).path().equals(path)) {
                return i;
            }
        }
        throw new AssertionError(path);
    }

    private static KeyEvent key(KeyCode code, boolean shortcut) {
        boolean mac = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac);
    }

    @Test
    void aMapWithNoFolderOrNoGitOpensNothingOrSaysSo() throws Exception {
        Path project = Files.createDirectories(temp.resolve("nogit")).toRealPath();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(made), 900, 700));
            stage[0].show();
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                view.openChanges(null);
                view.stepChange(true);
                assertNull(view.changesCard(), "no folder: nothing");
                view.setRoot(project);
                view.openChanges(null); // no Git to ask
                assertEquals(
                        tr("project.map.changes.unavailable"),
                        view.changesCard().summaryText());
                Button compare = FxTestSupport.field(view.changesCard(), "compare");
                assertTrue(compare.isDisabled());
                view.dispose();
                view.openChanges(null);
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    private static List<String> menuTexts(ProjectMapView view, MapSurface surface, Path path) {
        ProjectMapModel.Entry entry = null;
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        for (Object box : boxes) {
            ProjectMapModel.Entry candidate = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (candidate.path().equals(path.normalize())) {
                entry = candidate;
            }
        }
        if (entry == null) {
            return List.of();
        }
        ContextMenu menu = (ContextMenu) FxTestSupport.call(
                view,
                "menuWithMapItems",
                new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                entry,
                new ContextMenu());
        return menu.getItems().stream().map(MenuItem::getText).toList();
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
