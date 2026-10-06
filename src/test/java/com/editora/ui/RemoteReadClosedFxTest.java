package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.search.SearchQuery;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading from an SFTP connection that has been closed. Every read path names the connection and how to get
 * it back, as a save does — not the text of whichever exception the first request raised, and not an empty
 * folder or "No results".
 */
@Tag("fx")
class RemoteReadClosedFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openingReloadingListingAndSearchingSayTheConnectionIsClosed(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.start(dir));
            Files.writeString(sftp.serverPath("notes.txt"), "server\n");
            Files.writeString(sftp.serverPath("other.txt"), "other\n");
            Files.createDirectories(sftp.serverPath("sub"));
            Files.writeString(sftp.serverPath("sub/deep.txt"), "deep\n");
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            Path remote = sftp.remotePath("notes.txt");
            Path root = sftp.remotePath("");
            String closed =
                    tr("status.remote.connectionClosed", sftp.connection().id(), tr("command.remote.connect"));

            CountDownLatch loaded = new CountDownLatch(1);
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                workflows.openPath(remote);
                EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
                workflows.afterBufferLoad(opening, loaded::countDown);
                return opening;
            });
            async.await(loaded, "document load");
            async.awaitFx();
            final Tab tab = FxTestSupport.callOnFx(area::selectedTab);
            // A local tab beside it: switching between the two must keep working once the connection is gone.
            Path local = Files.writeString(dir.resolve("local.txt"), "local\n");
            CountDownLatch localLoaded = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                workflows.openPath(local);
                workflows.afterBufferLoad((EditorBuffer) area.selectedTab().getUserData(), localLoaded::countDown);
            });
            async.await(localLoaded, "local document load");
            async.awaitFx();
            Tab localTab = FxTestSupport.callOnFx(area::selectedTab);

            sftp.disconnect();

            FxTestSupport.runOnFx(() -> area.select(tab));
            async.awaitFx();
            FxTestSupport.runOnFx(() -> area.select(localTab));
            async.awaitFx();
            FxTestSupport.runOnFx(() -> area.select(tab));
            async.awaitFx();

            // Reload from disk.
            CountDownLatch reloaded = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> workflows.reloadFromDisk(tab, buffer, applied -> reloaded.countDown()));
            async.await(reloaded, "the reload to give up");
            assertEquals(tr("status.failedReload", "notes.txt", closed), echo(fx));
            assertEquals(
                    "server\n",
                    FxTestSupport.callOnFx(() -> buffer.getArea().getText()),
                    "the text in the editor is kept");

            // Open (a Project-tree double click, Recent Files, a jump to a file all end here).
            int tabs = FxTestSupport.callOnFx(() -> area.tabs().size());
            FxTestSupport.runOnFx(() -> workflows.openPath(sftp.remotePath("other.txt")));
            awaitEcho(async, fx, tr("status.failedOpen", closed));
            assertEquals(tabs, FxTestSupport.callOnFx(() -> area.tabs().size()), "no empty shell is left behind");
            // Find File on a path of the closed connection ends in the same place.
            NavigationCoordinator navigation = FxTestSupport.field(fx.controller, "navigation");
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(fx.controller, "setStatus", new Class<?>[] {String.class}, "");
                navigation.findFileChosen(sftp.remotePath("sub/deep.txt"));
            });
            awaitEcho(async, fx, tr("status.failedOpen", closed));

            // Project tree: listing a folder used to show it as empty, without a word.
            FxTestSupport.runOnFx(() -> {
                panel.setRoot(root);
                FxTestSupport.<javafx.scene.control.TreeView<Path>>field(panel, "tree")
                        .getRoot()
                        .getChildren();
            });
            awaitEchoEnding(async, fx, closed);
            assertEquals(tr("status.remote.unreadable", com.editora.vfs.Vfs.displayLabel(root), closed), echo(fx));

            // Project map: the same folder, drawn as a map.
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<javafx.scene.control.ToggleButton>field(panel, "mapModeButton")
                        .setSelected(true);
                panel.setRoot(null);
                FxTestSupport.call(fx.controller, "setStatus", new Class<?>[] {String.class}, "");
                panel.setRoot(root);
            });
            awaitEchoEnding(async, fx, closed);

            // Search in the remote folder: "No results" would be a claim about files that were never read.
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(fx.controller, "setStatus", new Class<?>[] {String.class}, ""));
            Object search = FxTestSupport.field(fx.controller, "searchCoordinator");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    search,
                    "runFileSearch",
                    new Class<?>[] {SearchQuery.class, String.class, String.class},
                    new SearchQuery("deep", false, false, false),
                    "",
                    ""));
            async.awaitFx();
            assertTrue(echo(fx).endsWith(closed), echo(fx));
        }
    }

    private static void awaitEcho(AsyncTestScope async, FxWindowFixture fx, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!expected.equals(echo(fx)) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
            async.awaitFx();
        }
        assertEquals(expected, echo(fx));
    }

    private static void awaitEchoEnding(AsyncTestScope async, FxWindowFixture fx, String suffix) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!echo(fx).endsWith(suffix) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
            async.awaitFx();
        }
        assertTrue(echo(fx).endsWith(suffix), echo(fx));
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }
}
