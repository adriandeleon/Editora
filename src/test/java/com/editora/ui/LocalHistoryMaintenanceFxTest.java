package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local History housekeeping against the real window: the startup sweep, the purge commands, charsets. */
@Tag("fx")
class LocalHistoryMaintenanceFxTest {

    private static final long DAY = 86_400_000L;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void startupSweepsFilesThatWereNeverSavedAgain(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path config = Files.createDirectory(dir.resolve("config"));
        String forgotten = PathKeys.normalizedKey(dir.resolve(".env"));
        String active = PathKeys.normalizedKey(dir.resolve("active.txt"));
        // An index as an earlier session left it: one file saved once long ago, one with old and new rows.
        ConfigManager seed = new ConfigManager(config);
        seed.load();
        seed.shared().historyBucket("").put(forgotten, List.of(rev(forgotten, now - 400 * DAY, "secret")));
        seed.shared()
                .historyBucket("some-project")
                .put(active, List.of(rev(active, now - DAY, "fresh"), rev(active, now - 45 * DAY, "stale")));
        seed.shared().saveHistory();
        assertTrue(seed.shared().flushWrites());
        seed.shared().shutdown();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create(config, false, false, false, List.of(), c -> {}));
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            async.awaitFx();
            async.awaitWorker(worker);
            async.awaitFx();

            Map<String, Map<String, List<HistoryRevision>>> index = FxTestSupport.callOnFx(fx.shared::historyByProject);
            assertFalse(
                    index.getOrDefault("", Map.of()).containsKey(forgotten),
                    "a revision past even the protected lease is removed although its file was never saved again");
            assertEquals(
                    List.of("fresh"),
                    index.get("some-project").get(active).stream()
                            .map(HistoryRevision::sha256)
                            .toList(),
                    "the age limit reaches every project, not only the file being saved");
            assertTrue(fx.shared.flushWrites());
            String saved = Files.readString(fx.shared.getHistoryFile());
            assertFalse(saved.contains("secret") || saved.contains("stale"), "the swept index is persisted");
        }
    }

    @Test
    void purgingTheCurrentFileIsConfirmedAndRemovesItsContentFromDisk(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("credentials.txt"), "token = hunter2\n");
        Path other = Files.writeString(dir.resolve("other.txt"), "keep me\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            EditorBuffer otherBuffer = open(fx, other);
            EditorBuffer buffer = open(fx, file); // added last: the active buffer
            FxTestSupport.runOnFx(() -> {
                history.record(otherBuffer, HistoryRevision.REASON_SAVE);
                history.record(buffer, HistoryRevision.REASON_SAVE);
            });
            settle(async, fx, worker);
            String secretSha = HistoryBlobStore.sha256("token = hunter2\n");
            String keptSha = HistoryBlobStore.sha256("keep me\n");
            assertNotNull(blobs.get(secretSha));
            String key = PathKeys.normalizedKey(file);

            CountDownLatch cancelled = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled));
                history.purgeActiveFile();
            });
            async.await(cancelled, "purge cancellation");
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(
                            () -> fx.shared.historyBucket("").get(key).size()),
                    "Cancel keeps it");

            // The same file was also recorded from another project's window.
            FxTestSupport.runOnFx(() -> fx.shared
                    .historyBucket("/another/project")
                    .put(
                            key,
                            new java.util.ArrayList<>(
                                    fx.shared.historyBucket("").get(key))));

            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed));
                history.purgeActiveFile();
            });
            async.await(confirmed, "purge confirmation");
            settle(async, fx, worker);

            assertFalse(FxTestSupport.callOnFx(() -> fx.shared.historyBucket("").containsKey(key)));
            assertFalse(
                    FxTestSupport.callOnFx(
                            () -> fx.shared.historyBucket("/another/project").containsKey(key)),
                    "purging a file forgets it in every project's history");
            assertFalse(Files.readString(fx.shared.getHistoryFile()).contains(secretSha), "gone from the index file");
            assertNull(
                    blobs.get(secretSha),
                    "and the content itself is deleted now, not at the next throttled collection");
            assertNotNull(blobs.get(keptSha), "another file's history is untouched");
        }
    }

    @Test
    void purgingTheProjectRemovesEveryFileAndWorksWhileHistoryIsSwitchedOff(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("a.txt"), "first body\n");
        Path second = Files.writeString(dir.resolve("b.txt"), "second body\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            ExecutorService worker = FxTestSupport.field(fx.shared.historyService(), "exec");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            EditorBuffer a = open(fx, first);
            EditorBuffer b = open(fx, second);
            FxTestSupport.runOnFx(() -> {
                history.record(a, HistoryRevision.REASON_SAVE);
                history.record(b, HistoryRevision.REASON_SAVE);
            });
            settle(async, fx, worker);
            assertEquals(
                    2, FxTestSupport.callOnFx(() -> fx.shared.historyBucket("").size()));
            // Turning the feature off must not strand what it already stored.
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setLocalHistory(false));

            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed));
                history.purgeProject();
            });
            async.await(confirmed, "project purge confirmation");
            settle(async, fx, worker);

            assertTrue(FxTestSupport.callOnFx(() -> fx.shared.historyBucket("").isEmpty()));
            assertNull(blobs.get(HistoryBlobStore.sha256("first body\n")));
            assertNull(blobs.get(HistoryBlobStore.sha256("second body\n")));
            com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            assertTrue(registry.get("localHistory.purgeProject").isPresent(), "the purge commands are registered");
            assertTrue(registry.get("localHistory.purgeFile").isPresent());
        }
    }

    @Test
    void aUtf16FileIsCapturedAsTextBeforeDeletion(@TempDir Path dir) throws Exception {
        String text = "café\nsecond line\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);
        Path file = Files.write(dir.resolve("wide.txt"), bytes);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<HistoryCoordinator.DeleteCapture> capture = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> history.captureBeforeDeleteDurably(file, value -> {
                capture.set(value);
                captured.countDown();
            }));
            async.await(captured, "pre-delete capture");

            assertTrue(capture.get().durable());
            List<HistoryRevision> revisions =
                    FxTestSupport.callOnFx(() -> fx.shared.historyBucket("").get(PathKeys.normalizedKey(file)));
            assertNotNull(revisions, "UTF-16 text has a NUL in every ASCII character and was skipped as binary");
            HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
            assertEquals(text, blobs.get(revisions.get(0).sha256()), "decoded as UTF-16, not as mangled UTF-8");
            assertEquals(HistoryRevision.REASON_DELETE, revisions.get(0).reason());
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static HistoryRevision rev(String path, long timestamp, String sha) {
        return new HistoryRevision(path, timestamp, 10, sha, HistoryRevision.REASON_SAVE);
    }

    /** Lets a record/purge travel worker → FX → index write → collection, without sleeping. */
    private static void settle(AsyncTestScope async, FxWindowFixture fx, ExecutorService worker) throws Exception {
        for (int round = 0; round < 3; round++) {
            async.awaitWorker(worker);
            async.awaitFx();
            assertTrue(fx.shared.flushWrites());
        }
        async.awaitWorker(worker);
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static void pressDialog(ButtonBar.ButtonData buttonData, CountDownLatch pressed) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            assertEquals(tr("dialog.history.purge.title"), ((javafx.stage.Stage) window).getTitle());
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .ifPresent(type -> {
                        pressed.countDown();
                        Button button = (Button) pane.lookupButton(type);
                        if (tr("dialog.history.purge.button").equals(type.getText())) {
                            assertTrue(button.getStyleClass().contains("danger"));
                        }
                        button.fire();
                    });
        }
    }
}
