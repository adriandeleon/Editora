package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link LogViewerCoordinator} — the first extracted feature coordinator. They
 * pin the log-viewer integration behavior (gating, control attach/detach, setting toggle) and double as the
 * testability payoff of the extraction: the coordinator is exercised against a hand-written fake {@link
 * LogViewerCoordinator.Host}, with real {@link EditorBuffer}s built on the FX thread.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogViewerCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A minimal Host: a Settings object, a simple-mode flag, a buffer list, and call counters. */
    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        boolean simpleMode = false;
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String lastStatus;
        int requestSaveCount;
        int syncCount;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simpleMode;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }

        @Override
        public long fileSize(Path file) {
            return 0;
        }

        @Override
        public void requestSave() {
            requestSaveCount++;
        }

        @Override
        public void syncSettingsWindow() {
            syncCount++;
        }

        @Override
        public OverlayHost overlayHost() {
            return new OverlayHost();
        }

        @Override
        public Window window() {
            return null;
        }

        String promptAnswer;
        String promptInitial;

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            promptInitial = initial;
            if (promptAnswer != null) {
                onAccept.accept(promptAnswer);
            }
        }
    }

    private static EditorBuffer logBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLogViewForced(true); // isLog() true without a file on disk
            return b;
        });
    }

    @Test
    void isEnabledTracksSettingAndSimpleMode() {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);

        host.settings.setLogViewer(true);
        host.simpleMode = false;
        assertTrue(c.isEnabled(), "on by default");

        host.settings.setLogViewer(false);
        assertFalse(c.isEnabled(), "off when the setting is off");

        host.settings.setLogViewer(true);
        host.simpleMode = true;
        assertFalse(c.isEnabled(), "suppressed in Simple UI mode");
    }

    @Test
    void handlesLogFileChecksEnabledExtensionAndLocal() {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        host.settings.setLogViewer(true);

        assertTrue(c.handlesLogFile(Path.of("server.log")));
        assertFalse(c.handlesLogFile(Path.of("notes.txt")), "non-.log file is not handled");

        host.settings.setLogViewer(false);
        assertFalse(c.handlesLogFile(Path.of("server.log")), "nothing handled when the feature is off");
    }

    @Test
    void ensureControlAttachesAndDetachesWithTheFeature() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = logBuffer();

        FxTestSupport.runOnFx(() -> c.ensureControl(log));
        assertTrue(FxTestSupport.callOnFx(log::hasLogControl), "a log buffer gets the control bar when enabled");

        host.settings.setLogViewer(false);
        FxTestSupport.runOnFx(() -> c.ensureControl(log));
        assertFalse(FxTestSupport.callOnFx(log::hasLogControl), "disabling the feature removes the control");
    }

    @Test
    void applySupportOnlySkinsLogBuffers() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = logBuffer();
        EditorBuffer plain = FxTestSupport.callOnFx(() -> new EditorBuffer());
        host.buffers.add(log);
        host.buffers.add(plain);

        FxTestSupport.runOnFx(c::applySupport);
        assertTrue(FxTestSupport.callOnFx(log::hasLogControl), "log buffer gets the control");
        assertFalse(FxTestSupport.callOnFx(plain::hasLogControl), "a plain buffer does not");
    }

    @Test
    void toggleViewerFlipsTheSettingAndNotifiesTheHost() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);

        FxTestSupport.runOnFx(c::toggleViewer);
        assertFalse(host.settings.isLogViewer(), "toggled off");
        assertEquals(1, host.requestSaveCount, "persisted via the host");
        assertEquals(1, host.syncCount, "re-synced the Settings window checkbox");

        FxTestSupport.runOnFx(c::toggleViewer);
        assertTrue(host.settings.isLogViewer(), "toggled back on");
    }

    private static final String SAMPLE = String.join(
                    "\n",
                    "2026-10-06 09:00:00 INFO  started",
                    "2026-10-06 09:00:01 WARN  pool exhausted",
                    "2026-10-06 09:00:02 ERROR payment failed",
                    "java.net.SocketTimeoutException: Read timed out",
                    "\tat com.example.Pay.authorize(Pay.java:142)",
                    "2026-10-06 09:00:03 INFO  recovered")
            + "\n";

    private static LogControlBar barOf(LogViewerCoordinator c, EditorBuffer buffer) {
        java.util.Map<EditorBuffer, LogControlBar> bars = FxTestSupport.field(c, "logBars");
        return bars.get(buffer);
    }

    /** A log buffer holding {@link #SAMPLE}, active in {@code host}, with its control bar attached. */
    private static EditorBuffer sampleLog(FakeHost host, LogViewerCoordinator c) throws Exception {
        host.settings.setLogViewer(true);
        EditorBuffer log = logBuffer();
        host.active = log;
        host.buffers.add(log);
        FxTestSupport.runOnFx(() -> {
            log.setInitialContent(SAMPLE);
            c.ensureControl(log);
        });
        return log;
    }

    @Test
    void aFilterSetFromThePaletteShowsInTheBarAndSurvivesTheNextChangeThere() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        LogControlBar bar = barOf(c, log);

        host.promptAnswer = "timed out";
        FxTestSupport.runOnFx(c::setRegexFilter);
        assertEquals("timed out", FxTestSupport.callOnFx(bar::patternText), "the bar shows the palette's pattern");
        assertEquals("3 of 6 lines", FxTestSupport.callOnFx(bar::stateText), "the whole ERROR record, counted");

        // Choosing a level in the bar used to read an empty pattern field and drop the palette's pattern.
        FxTestSupport.runOnFx(() -> {
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<String> level =
                    (javafx.scene.control.ComboBox<String>) bar.lookup(".log-level-combo");
            level.getSelectionModel().select(LogControlBar.LEVELS.indexOf(com.editora.logviewer.LogLevel.WARN));
            level.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("timed out", FxTestSupport.callOnFx(log::getLogPattern), "the pattern is still on");
        assertEquals(com.editora.logviewer.LogLevel.WARN, FxTestSupport.callOnFx(log::getLogMinLevel));

        // And the prompt opens on the pattern that is active, not on nothing.
        host.promptAnswer = null;
        FxTestSupport.runOnFx(c::setRegexFilter);
        assertEquals("timed out", host.promptInitial);

        FxTestSupport.runOnFx(c::clearFilter);
        assertFalse(FxTestSupport.callOnFx(log::isLogFiltered));
        assertEquals(null, FxTestSupport.callOnFx(bar::patternText), "clearing empties the field");
        assertEquals(null, FxTestSupport.callOnFx(bar::selectedLevel), "and resets the level");
        assertEquals("", FxTestSupport.callOnFx(bar::stateText));
        assertEquals(SAMPLE, FxTestSupport.callOnFx(log::getContent));
    }

    @Test
    void aFollowedAppendIsNotAnUndoStepAndLeavesRealEditsUndoable() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);

        FxTestSupport.runOnFx(() -> log.appendLogText("2026-10-06 09:00:04 INFO  followed line\n"));
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> log.getFocusedArea().getUndoManager().isUndoAvailable()),
                "Undo used to take the newest lines of the log back out");
        assertFalse(FxTestSupport.callOnFx(log::isDirty));

        FxTestSupport.runOnFx(() -> {
            log.getFocusedArea().insertText(0, "typed ");
            log.appendLogText("2026-10-06 09:00:05 INFO  another\n");
            log.getFocusedArea().getUndoManager().undo();
        });
        String text = FxTestSupport.callOnFx(log::getContent);
        assertTrue(text.startsWith("2026-10-06 09:00:00 INFO  started"), "the typed text is undone");
        assertTrue(text.endsWith("followed line\n2026-10-06 09:00:05 INFO  another\n"), "the followed lines stay");
    }

    @Test
    void viewAsLogIsAToggleThatAlsoClearsWhatItSetUp() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer plain = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setInitialContent(SAMPLE);
            return b;
        });
        host.active = plain;

        FxTestSupport.runOnFx(c::viewAsLog);
        assertTrue(FxTestSupport.callOnFx(plain::isLog));
        assertTrue(FxTestSupport.callOnFx(plain::hasLogControl));
        FxTestSupport.runOnFx(() -> plain.applyLogFilter(com.editora.logviewer.LogLevel.ERROR, null));

        FxTestSupport.runOnFx(c::viewAsLog);
        assertFalse(FxTestSupport.callOnFx(plain::isLog), "run again: no longer a log");
        assertFalse(FxTestSupport.callOnFx(plain::hasLogControl));
        assertFalse(FxTestSupport.callOnFx(plain::isLogFiltered), "a filter cannot outlive the control that clears it");
        assertTrue(FxTestSupport.callOnFx(plain::isEditable));
    }

    @Test
    void aLogItsNameDoesNotAnnounceIsRecognisedFromItsContent() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);

        EditorBuffer out = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("server.out"));
            c.sniffLoaded(b, SAMPLE);
            return b;
        });
        assertTrue(FxTestSupport.callOnFx(out::isLog));
        assertTrue(FxTestSupport.callOnFx(out::hasLogControl));

        EditorBuffer notes = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("notes.txt"));
            c.sniffLoaded(b, SAMPLE);
            return b;
        });
        assertFalse(FxTestSupport.callOnFx(notes::isLog), "a .txt is not sniffed, whatever it contains");

        EditorBuffer prose = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("README"));
            c.sniffLoaded(b, "Dear team,\nHere is the weekly update.\nWe shipped two features.\nThanks\n");
            return b;
        });
        assertFalse(FxTestSupport.callOnFx(prose::isLog));
    }

    @Test
    void followingResumesWhereTheBufferEndsSoNothingIsSkipped(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        Path file = dir.resolve("server.log");
        java.nio.file.Files.writeString(file, SAMPLE);
        EditorBuffer log = sampleLog(host, c);
        long loaded = java.nio.file.Files.size(file);
        long modified = java.nio.file.Files.getLastModifiedTime(file).toMillis();
        FxTestSupport.runOnFx(() -> {
            log.setPath(file);
            log.setDiskSnapshot(modified, loaded);
            c.recordLoadOffset(log, loaded);
        });
        try {
            // Written between opening the file and turning Follow on: it used to be skipped.
            append(file, "2026-10-06 09:00:04 INFO  before follow\n");
            FxTestSupport.runOnFx(c::toggleFollowCommand);
            awaitContent(log, "before follow\n");

            FxTestSupport.runOnFx(c::toggleFollowCommand); // pause
            assertFalse(FxTestSupport.callOnFx(log::isLogFollowing));
            append(file, "2026-10-06 09:00:05 ERROR while paused\n");
            FxTestSupport.runOnFx(c::toggleFollowCommand); // resume
            append(file, "2026-10-06 09:00:06 INFO  after resume\n");
            awaitContent(log, "after resume\n");

            String text = FxTestSupport.callOnFx(log::getContent);
            assertEquals(java.nio.file.Files.readString(file), text, "the buffer is the file: no gap, nothing twice");
            assertFalse(FxTestSupport.callOnFx(log::isDirty));
            assertEquals(
                    java.nio.file.Files.size(file),
                    FxTestSupport.callOnFx(() -> log.diskSnapshot().size()),
                    "the on-disk snapshot keeps up, so nothing asks to reload a file that is being followed");
        } finally {
            FxTestSupport.runOnFx(() -> c.onBufferClosed(log));
            c.shutdown();
        }
    }

    private static void append(Path file, String text) throws Exception {
        java.nio.file.Files.writeString(file, text, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void awaitContent(EditorBuffer buffer, String ending) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!FxTestSupport.callOnFx(buffer::getContent).endsWith(ending)) {
            assertTrue(System.nanoTime() < deadline, "the followed text never arrived: " + ending.strip());
            Thread.sleep(50);
        }
    }
}
