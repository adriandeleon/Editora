package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Copy Permalink: the editor's Git: Copy Permalink at the caret or the selected lines, and the Project Map's card and
 * row menus (Map code browser round 2).
 */
@Tag("fx")
class CopyPermalinkFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theEditorCommandCopiesTheSelectedLinesLink() throws Exception {
        GitTestRepo repo = GitTestRepo.init(temp);
        Path file = repo.write("src/A.java", "class A {\n  int one;\n  int two;\n}\n");
        repo.commitAll("start");
        repo.git("remote", "add", "origin", "git@github.com:me/app.git");
        String sha = new String(repo.git("rev-parse", "HEAD").out()).strip();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FxTestSupport.runOnFx(() -> {
                git.applySupport();
                Clipboard.getSystemClipboard().clear();
                git.copyPermalink(); // no file open
            });
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
            await(
                    "the file's tab",
                    () -> buffer(area) != null && file.equals(buffer(area).getPath()));
            FxTestSupport.runOnFx(() -> {
                var editor = buffer(area).getArea();
                editor.selectRange(1, 0, 3, 0); // lines 2 and 3; the end at the next line's start leaves it out
                git.copyPermalink();
            });
            String lines = "https://github.com/me/app/blob/" + sha + "/src/A.java#L2-L3";
            await(
                    "the link copied",
                    () -> lines.equals(Clipboard.getSystemClipboard().getString()));
            FxTestSupport.runOnFx(() -> {
                var editor = buffer(area).getArea();
                editor.moveTo(3, 0);
                Clipboard.getSystemClipboard().clear();
                git.copyPermalink();
            });
            String caret = "https://github.com/me/app/blob/" + sha + "/src/A.java#L4";
            await(
                    "the caret's line copied",
                    () -> caret.equals(Clipboard.getSystemClipboard().getString()));
        }
    }

    @Test
    void theMapsCardAndRowMenusCopyWhatTheyShow() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path file = Files.writeString(
                Files.createDirectories(root.resolve("src")).resolve("B.java"),
                "class B {\n  void a() {}\n  void b() {}\n}\n");
        List<String> asked = new ArrayList<>();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Stage[] stage = new Stage[1];
            ProjectMapView view = FxTestSupport.callOnFx(() -> {
                ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
                stage[0] = new Stage();
                stage[0].setScene(new Scene(made, 1200, 800));
                stage[0].show();
                made.setFollowCode(new ProjectMapView.FollowCode() {
                    @Override
                    public void follow(Path f, int line, int column, @Nullable String name, String commandId) {}

                    @Override
                    public void relatedFiles(Path f, java.util.function.Consumer<List<Path>> found) {
                        found.accept(List.of());
                    }

                    @Override
                    public void fileHistory(Path f) {}

                    @Override
                    public void copyPermalink(Path target, int startLine, int endLine) {
                        asked.add(root.relativize(target).toString().replace('\\', '/') + " " + startLine + "-"
                                + endLine);
                    }
                });
                made.setRoot(root);
                return made;
            });
            try {
                await("the src row", () -> rowEntry(view, root.resolve("src")) != null);
                MapCards cards = FxTestSupport.field(view, "cards");
                ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(file));
                await("the card's text", () -> card.editor().getLength() > 0);
                FxTestSupport.runOnFx(() -> {
                    card.editor().moveTo(2, 0);
                    menuItem(card, tr("project.map.permalink.copy")).fire();
                    card.editor().selectRange(1, 0, 2, 4);
                    menuItem(card, tr("project.map.permalink.copy")).fire();
                    assertEquals(List.of("src/B.java 2-2", "src/B.java 1-2"), asked);
                });

                await("the src row", () -> rowEntry(view, root.resolve("src")) != null);
                FxTestSupport.runOnFx(() -> {
                    ContextMenu menu = (ContextMenu) FxTestSupport.call(
                            view,
                            "menuWithMapItems",
                            new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                            rowEntry(view, root.resolve("src")),
                            new ContextMenu());
                    menu.getItems().stream()
                            .filter(item -> tr("project.map.permalink.copy").equals(item.getText()))
                            .findFirst()
                            .orElseThrow()
                            .fire();
                    assertEquals("src -1--1", asked.get(2), "a folder: no lines");
                });
            } finally {
                FxTestSupport.runOnFx(() -> {
                    view.dispose();
                    stage[0].close();
                });
            }
        }
    }

    @Test
    void theStatusLineSaysWhatWasCopiedOrWhyNot() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String url = "https://example.com/x";
            assertEquals(
                    tr("status.git.permalink.copied", url),
                    GitPermalinks.copy(new GitService.Permalink(GitService.Permalink.Status.OK, url, true)));
            assertEquals(url, Clipboard.getSystemClipboard().getString());
            assertEquals(
                    tr("status.git.permalink.unpushed", url),
                    GitPermalinks.message(new GitService.Permalink(GitService.Permalink.Status.OK, url, false)));
            assertEquals(
                    tr("status.git.permalink.newLines", url),
                    GitPermalinks.message(new GitService.Permalink(GitService.Permalink.Status.NEW_LINES, url, true)));
            for (GitService.Permalink.Status status : List.of(
                    GitService.Permalink.Status.NO_REPOSITORY,
                    GitService.Permalink.Status.NO_COMMIT,
                    GitService.Permalink.Status.NO_REMOTE,
                    GitService.Permalink.Status.UNTRACKED)) {
                Clipboard.getSystemClipboard().clear();
                String said = GitPermalinks.copy(new GitService.Permalink(status, "", false));
                assertFalse(said.isBlank());
                assertFalse(Clipboard.getSystemClipboard().hasString(), "nothing copied");
            }
        });
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static MenuItem menuItem(ProjectMapPreview card, String text) {
        @SuppressWarnings("unchecked")
        List<MenuItem> items = (List<MenuItem>) FxTestSupport.call(card, "followMenuItems", new Class<?>[0]);
        return items.stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " in "
                        + items.stream().map(MenuItem::getText).toList()));
    }

    private static ProjectMapModel.@Nullable Entry rowEntry(ProjectMapView view, Path path) {
        Object surface = FxTestSupport.field(view, "surface");
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        for (Object box : boxes) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        return null;
    }

    private static @Nullable EditorBuffer buffer(EditorArea area) {
        Tab tab = area.selectedTab();
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.callOnFx(condition)) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }
}
