package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.config.ConfigManager;
import com.editora.config.FileIdentity;
import com.editora.config.HistoryRevision;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.TextAnchor;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Project: Delete" says it removes the project from the list and its saved session. It also dropped the
 * project's personal notes, bookmarks, breakpoints and its whole Local History — unannounced, with no backup.
 */
@Tag("fx")
class ProjectDeleteKeepsDataFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void deletingAProjectKeepsItsNotesBookmarksBreakpointsAndHistory(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("demo"));
        String file = root.resolve("Main.java").toString();
        String body = "the only copy of a deleted file\n";
        String sha = HistoryBlobStore.sha256(body);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            ProjectManager projects = FxTestSupport.field(fx.controller, "projects");

            Project project = FxTestSupport.callOnFx(() -> {
                Project p = projects.createOrGet("Demo", root);
                projects.save();
                Files.createDirectories(projects.stateFile(p).getParent());
                Files.writeString(projects.stateFile(p), "{}");
                PersonalNote note = PersonalNote.create(
                        new FileIdentity(file, file, 1, 1, ""),
                        NoteScope.LINE,
                        new TextAnchor(0, 0, 0, 0, "", "", ""),
                        "remember this",
                        List.of());
                fx.shared.notesBucket(p.id()).put(file, List.of(note));
                fx.shared.bookmarksBucket(p.id()).put(file, List.of(new Bookmark(3, "here", "")));
                fx.shared.breakpointsBucket(p.id()).put(file, List.of(new Breakpoint(4, "", "", true, "")));
                blobs.put(body, sha);
                fx.shared
                        .historyBucket(p.id())
                        .put(
                                file,
                                List.of(new HistoryRevision(
                                        file,
                                        System.currentTimeMillis(),
                                        body.length(),
                                        sha,
                                        HistoryRevision.REASON_DELETE)));
                fx.shared.saveNotes();
                fx.shared.saveBookmarks();
                fx.shared.saveBreakpoints();
                fx.shared.saveHistory();
                return p;
            });
            settle(async, fx, worker);

            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressOk(confirmed));
                FxTestSupport.call(fx.controller, "deleteProject", new Class<?>[] {Project.class}, project);
            });
            async.await(confirmed, "project delete confirmation");
            // Whatever the delete left unreferenced must not be collected later either: force the next
            // blob collection instead of waiting out its ten-minute throttle.
            FxTestSupport.runOnFx(() -> {
                fx.shared.historyService().requestGc();
                fx.shared.saveHistory();
            });
            settle(async, fx, worker);

            assertTrue(FxTestSupport.callOnFx(projects::list).isEmpty(), "the project left the list");
            assertFalse(Files.exists(projects.stateFile(project)), "and its saved session is gone, as the dialog says");
            String id = project.id();
            assertEquals(
                    "remember this",
                    FxTestSupport.callOnFx(
                            () -> fx.shared.allNotes().get(id).get(file).get(0).body()),
                    "its personal notes are kept");
            assertEquals(
                    3,
                    FxTestSupport.callOnFx(() ->
                            fx.shared.allBookmarks().get(id).get(file).get(0).line()));
            assertEquals(
                    4,
                    FxTestSupport.callOnFx(() ->
                            fx.shared.breakpointsBucket(id).get(file).get(0).line()));
            assertEquals(
                    sha,
                    FxTestSupport.callOnFx(() -> fx.shared
                            .historyByProject()
                            .get(id)
                            .get(file)
                            .get(0)
                            .sha256()),
                    "its Local History index is kept");
            assertEquals(body, blobs.get(sha), "and the revision body survives the next blob collection");

            // Re-adding the folder re-attaches all of it: the id is derived from the name and the folder.
            Project again = FxTestSupport.callOnFx(() -> projects.createOrGet("Demo", root));
            assertEquals(id, again.id());

            // A later session reads the same buckets from disk.
            assertTrue(fx.shared.flushWrites());
            ConfigManager reread = new ConfigManager(fx.configDir);
            reread.load();
            try {
                assertNotNull(reread.shared().allNotes().get(id), "notes.json still has the bucket");
                assertNotNull(reread.shared().allBookmarks().get(id), "bookmarks.json still has the bucket");
                assertNotNull(reread.shared().historyByProject().get(id), "history/index.json still has the bucket");
            } finally {
                reread.shared().shutdown();
            }
        }
    }

    private static void settle(AsyncTestScope async, FxWindowFixture fx, ExecutorService worker) throws Exception {
        for (int round = 0; round < 3; round++) {
            async.awaitWorker(worker);
            async.awaitFx();
            assertTrue(fx.shared.flushWrites());
        }
        async.awaitWorker(worker);
    }

    private static void pressOk(CountDownLatch pressed) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            assertEquals(tr("dialog.deleteProject.title"), ((Stage) window).getTitle());
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .ifPresent(type -> {
                        pressed.countDown();
                        ((Button) pane.lookupButton(type)).fire();
                    });
        }
    }
}
