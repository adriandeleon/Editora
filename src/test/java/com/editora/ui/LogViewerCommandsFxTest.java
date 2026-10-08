package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.logviewer.LogLevel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log viewer's palette commands: what each says when the feature is off or the file is not a log, the
 * level picker, jumping between warnings and errors, following a log that has no file, and a filter over a
 * log large enough to be computed off the FX thread.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogViewerCommandsFxTest {

    private static final String SAMPLE = "2024-01-01 10:00:00 INFO  starting\n"
            + "2024-01-01 10:00:01 WARN  disk is nearly full\n"
            + "2024-01-01 10:00:02 INFO  still running\n"
            + "2024-01-01 10:00:03 ERROR request failed\n"
            + "2024-01-01 10:00:04 INFO  done\n";

    private final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public synchronized void setStatus(String message) {
            statuses.add(message);
            notifyAll();
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public Window window() {
            return stage;
        }

        synchronized String last() {
            return statuses.isEmpty() ? null : statuses.getLast();
        }

        synchronized void await(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (statuses.stream().noneMatch(wanted)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("no such status; got " + statuses);
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
    }

    private Stage stage;
    private OverlayHost overlay;
    private Host host;
    private LogViewerCoordinator logs;

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            StackPane root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 800, 500));
            stage.show();
            overlay = new OverlayHost();
            overlay.install(root);
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void create() throws Exception {
        host = new Host();
        host.settings.setLogViewer(true);
        FxTestSupport.runOnFx(() -> logs = new LogViewerCoordinator(host));
    }

    @AfterEach
    void shutdown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (overlay.isShowing()) {
                overlay.hide();
            }
            logs.shutdown();
            host.buffers.forEach(EditorBuffer::dispose);
        });
    }

    private EditorBuffer log(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLogViewForced(true); // a log without a file on disk
            b.setContent(content);
            host.buffers.add(b);
            host.active = b;
            logs.ensureControl(b);
            b.getArea().moveTo(0);
            return b;
        });
    }

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    private List<Runnable> commands() {
        return List.of(
                logs::toggleFollowCommand,
                logs::setLevelFilter,
                logs::setRegexFilter,
                logs::focusFilter,
                logs::clearFilter,
                logs::jumpToNextError,
                logs::jumpToPreviousError);
    }

    @Test
    void everyCommandSaysWhenTheViewerIsOffOrTheFileIsNotALog() throws Exception {
        EditorBuffer plain = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("not a log\n");
            host.buffers.add(b);
            return b;
        });
        fx(() -> commands().forEach(Runnable::run)); // no buffer at all
        assertEquals(7, host.statuses.size());
        assertTrue(host.statuses.stream().allMatch(tr("status.log.notLog")::equals), host.statuses.toString());

        host.statuses.clear();
        host.active = plain;
        fx(() -> commands().forEach(Runnable::run));
        assertTrue(host.statuses.stream().allMatch(tr("status.log.notLog")::equals), host.statuses.toString());
        assertEquals(7, host.statuses.size());

        host.statuses.clear();
        host.settings.setLogViewer(false);
        fx(() -> {
            commands().forEach(Runnable::run);
            logs.viewAsLog();
        });
        assertEquals(8, host.statuses.size());
        assertTrue(host.statuses.stream().allMatch(tr("status.log.disabled")::equals), host.statuses.toString());
        assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "no picker for a command that cannot run");
        assertFalse(FxTestSupport.callOnFx(plain::isLogViewForced));

        host.statuses.clear();
        host.active = null;
        fx(logs::viewAsLog);
        assertEquals(List.of(), host.statuses, "with no file there is nothing to view as a log");
    }

    @Test
    void theLevelPickerFiltersToTheChosenLevelAndAbove() throws Exception {
        EditorBuffer b = log(SAMPLE);
        fx(logs::setLevelFilter);
        FxTestSupport.drainFx();
        fx(() -> {
            StackPane root = FxTestSupport.field(overlay, "overlayRoot");
            Node card = root.getChildren().get(1);
            ListView<?> rows = (ListView<?>) card.lookup(".list-view");
            assertEquals(LogControlBar.levelLabels(), rows.getItems());
            rows.getSelectionModel().select(LogControlBar.LEVELS.indexOf(LogLevel.WARN));
            Event.fireEvent(
                    card.lookup(".text-field"),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        assertEquals(LogLevel.WARN, FxTestSupport.callOnFx(b::getLogMinLevel));
        assertTrue(FxTestSupport.callOnFx(b::isLogFiltered));
        List<String> shown =
                FxTestSupport.callOnFx(() -> b.getArea().getText().lines().toList());
        assertEquals(2, shown.size(), shown.toString());
        assertTrue(shown.get(0).contains("WARN") && shown.get(1).contains("ERROR"), shown.toString());
        assertEquals(tr("status.log.filtered"), host.last());

        fx(logs::clearFilter);
        assertFalse(FxTestSupport.callOnFx(b::isLogFiltered));
        assertEquals(tr("status.log.filterCleared"), host.last());
        assertEquals(SAMPLE, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void jumpingWalksTheWarningsAndErrorsBothWaysAndWraps() throws Exception {
        EditorBuffer b = log(SAMPLE);
        fx(logs::jumpToNextError);
        assertEquals(1, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        fx(logs::jumpToNextError);
        assertEquals(3, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        fx(logs::jumpToNextError);
        assertEquals(1, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "past the last: the first");
        fx(logs::jumpToPreviousError);
        assertEquals(3, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "before the first: the last");
        assertEquals(List.of(), host.statuses);

        EditorBuffer quiet = log("2024-01-01 10:00:00 INFO  all is well\n");
        fx(logs::jumpToNextError);
        assertEquals(tr("status.log.noError"), host.last());
        assertEquals(0, FxTestSupport.callOnFx(() -> quiet.getArea().getCurrentParagraph()));
    }

    @Test
    void aLogWithNoFileCannotBeFollowed() throws Exception {
        EditorBuffer b = log(SAMPLE);
        fx(logs::toggleFollowCommand);
        assertEquals(tr("status.log.followUnavailable"), host.last());
        assertFalse(FxTestSupport.callOnFx(b::isLogFollowing));
    }

    @Test
    void viewingAsALogIsRefusedForAFileThatAlreadyIsOneByName() throws Exception {
        EditorBuffer named = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("server.log");
            b.setContent(SAMPLE);
            host.buffers.add(b);
            host.active = b;
            return b;
        });
        assertTrue(FxTestSupport.callOnFx(named::isLog), "an unsaved buffer is a log by its display name");
        fx(logs::viewAsLog);
        assertEquals(tr("status.log.viewAsLog"), host.last());
        assertFalse(FxTestSupport.callOnFx(named::isLogViewForced), "nothing to switch: it is a log already");
    }

    @Test
    void aLargeLogIsFilteredOffTheFxThreadAndTheViewChangesWhenTheResultIsIn() throws Exception {
        StringBuilder big = new StringBuilder(LogViewerCoordinator.ASYNC_FILTER_CHARS + 4096);
        int lines = 0;
        while (big.length() < LogViewerCoordinator.ASYNC_FILTER_CHARS + 1024) {
            big.append("2024-01-01 10:00:00 INFO  line ").append(lines++).append(" of filler text for the log\n");
        }
        big.append("2024-01-01 10:00:01 ERROR the one line that matters\n");
        EditorBuffer b = log(big.toString());

        host.statuses.clear();
        fx(() -> FxTestSupport.call(
                logs,
                "applyFilter",
                new Class<?>[] {EditorBuffer.class, LogLevel.class, String.class, boolean.class},
                b,
                LogLevel.ERROR,
                null,
                true));
        host.await(tr("status.log.filtered")::equals);
        assertEquals(
                "2024-01-01 10:00:01 ERROR the one line that matters",
                FxTestSupport.callOnFx(() -> b.getArea().getText().strip()));
        assertTrue(FxTestSupport.callOnFx(b::isLogFiltered));
    }
}
