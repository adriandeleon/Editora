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
        final com.editora.config.WorkspaceState workspace = new com.editora.config.WorkspaceState();

        @Override
        public com.editora.config.WorkspaceState workspaceState() {
            return workspace;
        }

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
        final List<EditorBuffer> opened = new ArrayList<>();

        @Override
        public void openTab(EditorBuffer buffer) {
            opened.add(buffer);
            buffers.add(buffer);
            active = buffer;
        }

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
        assertEquals(
                "timed out",
                FxTestSupport.callOnFx(() -> bar.query().include()),
                "the bar shows the palette's pattern");
        assertEquals("3 of 6 lines", FxTestSupport.callOnFx(bar::stateText), "the whole ERROR record, counted");

        // Choosing a level in the bar used to read an empty pattern field and drop the palette's pattern.
        FxTestSupport.runOnFx(() -> {
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<String> level =
                    (javafx.scene.control.ComboBox<String>) bar.lookup(".log-level-combo");
            level.getSelectionModel().select(LogControlBar.LEVELS.indexOf(com.editora.logviewer.LogLevel.WARN));
            level.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("timed out", FxTestSupport.callOnFx(() -> log.getLogQuery().include()), "the pattern is still on");
        assertEquals(com.editora.logviewer.LogLevel.WARN, FxTestSupport.callOnFx(log::getLogMinLevel));

        // And the prompt opens on the pattern that is active, not on nothing.
        host.promptAnswer = null;
        FxTestSupport.runOnFx(c::setRegexFilter);
        assertEquals("timed out", host.promptInitial);

        FxTestSupport.runOnFx(c::clearFilter);
        assertFalse(FxTestSupport.callOnFx(log::isLogFiltered));
        assertEquals(null, FxTestSupport.callOnFx(() -> bar.query().include()), "clearing empties the field");
        assertEquals(null, FxTestSupport.callOnFx(bar::selectedLevel), "and resets the level");
        assertEquals("", FxTestSupport.callOnFx(bar::stateText));
        assertEquals(SAMPLE, FxTestSupport.callOnFx(log::getContent));
    }

    @Test
    void theHideFieldAndMatchCaseFilterFromTheBar() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        LogControlBar bar = barOf(c, log);

        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField hide = (javafx.scene.control.TextField) bar.lookup(".log-hide-field");
            hide.setText("payment");
            hide.getOnAction().handle(new javafx.event.ActionEvent()); // Enter
        });
        assertEquals(
                String.join(
                                "\n",
                                "2026-10-06 09:00:00 INFO  started",
                                "2026-10-06 09:00:01 WARN  pool exhausted",
                                "2026-10-06 09:00:03 INFO  recovered")
                        + "\n",
                FxTestSupport.callOnFx(() -> log.getFocusedArea().getText()),
                "the ERROR record is hidden with its stack trace");
        assertEquals("3 of 6 lines", FxTestSupport.callOnFx(bar::stateText));

        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.ToggleButton aa = (javafx.scene.control.ToggleButton) bar.lookup(".log-case-toggle");
            aa.fire(); // Match Case on: "payment" is lower case in the log, so the same record stays hidden
        });
        assertTrue(FxTestSupport.callOnFx(() -> log.getLogQuery().matchCase()));
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField hide = (javafx.scene.control.TextField) bar.lookup(".log-hide-field");
            hide.setText("PAYMENT");
            hide.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals(
                SAMPLE,
                FxTestSupport.callOnFx(() -> log.getFocusedArea().getText()),
                "matching case, PAYMENT hides nothing");

        host.promptAnswer = "pool";
        FxTestSupport.runOnFx(c::setHideFilter);
        assertEquals("pool", FxTestSupport.callOnFx(() -> bar.query().exclude()), "the palette's pattern shows");
        assertTrue(FxTestSupport.callOnFx(() -> log.getLogQuery().matchCase()), "and keeps Match Case");
        assertEquals("5 of 6 lines", FxTestSupport.callOnFx(bar::stateText));

        FxTestSupport.runOnFx(c::clearFilter);
        assertEquals(null, FxTestSupport.callOnFx(() -> bar.query().exclude()), "clearing empties the hide field");
        assertEquals(SAMPLE, FxTestSupport.callOnFx(log::getContent));
    }

    /** A command line that runs {@code java} from this JDK on a source file. */
    private static String javaRunning(Path source) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "'" + java + "' '" + source + "'";
    }

    @Test
    void aStreamShowsACommandsOutputPausesAndStopsWithItsTab(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path ticker = dir.resolve("Ticker.java");
        try (var in = LogViewerCoordinatorFxTest.class.getResourceAsStream("log-stream-ticker.txt")) {
            java.nio.file.Files.copy(in, ticker); // a program the test runs, not a test that waits
        }
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        FxTestSupport.runOnFx(() -> c.startStream(LogStreamSource.COMMAND, javaRunning(ticker)));
        EditorBuffer tab = host.opened.get(0);
        assertTrue(FxTestSupport.callOnFx(tab::isLog), "a stream opens as a log");
        assertTrue(FxTestSupport.callOnFx(tab::isReadOnly));
        assertTrue(FxTestSupport.callOnFx(tab::isLogFollowing));
        FxTestSupport.awaitFxCondition(
                () -> tab.getContent().contains("tick 3"), "the ticks arrive", java.time.Duration.ofSeconds(30));

        FxTestSupport.runOnFx(c::toggleFollowCommand); // pause
        assertFalse(FxTestSupport.callOnFx(tab::isLogFollowing));
        String paused = FxTestSupport.callOnFx(tab::getContent);
        FxTestSupport.awaitFxCondition(() -> c.heldStreamChars(tab) > 200, "new ticks held back while paused");
        assertEquals(paused, FxTestSupport.callOnFx(tab::getContent), "paused: lines wait");
        FxTestSupport.runOnFx(c::toggleFollowCommand); // resume
        FxTestSupport.awaitFxCondition(
                () -> tab.getContent().length() > paused.length() + 100, "the lines that waited, and more");
        assertFalse(FxTestSupport.callOnFx(tab::isDirty), "a stream never asks to be saved");

        FxTestSupport.runOnFx(() -> c.onBufferClosed(tab));
        assertFalse(FxTestSupport.callOnFx(() -> c.isStreaming(tab)));
    }

    @Test
    void aStreamThatEndsSaysSoAndOneThatCannotStartToo() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        String javaExe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        FxTestSupport.runOnFx(() -> c.startStream(LogStreamSource.COMMAND, "'" + javaExe + "' -version"));
        EditorBuffer tab = host.opened.get(0);
        FxTestSupport.awaitFxCondition(
                () -> !c.isStreaming(tab), "java -version ends", java.time.Duration.ofSeconds(30));
        assertTrue(FxTestSupport.callOnFx(tab::getContent).contains("version"), "its standard error is shown too");
        assertTrue(host.lastStatus.contains("code 0"), host.lastStatus);
        FxTestSupport.runOnFx(c::toggleFollowCommand);
        assertTrue(host.lastStatus.contains("ended"), "nothing to resume: " + host.lastStatus);

        FxTestSupport.runOnFx(() -> c.startStream(LogStreamSource.COMMAND, "editora-no-such-program-here"));
        EditorBuffer missing = host.opened.get(1);
        FxTestSupport.awaitFxCondition(
                () -> !c.isStreaming(missing), "the start fails", java.time.Duration.ofSeconds(30));
        assertTrue(host.lastStatus.startsWith("Could not start"), host.lastStatus);

        int tabs = host.opened.size();
        FxTestSupport.runOnFx(() -> c.startStream(LogStreamSource.SSH_FILE, "no-path-here"));
        assertEquals(tabs, host.opened.size(), "what cannot be streamed opens no tab");
        assertTrue(host.lastStatus.startsWith("Not something to stream"));
    }

    @Test
    void aTimeRangeFiltersShowsInTheBarAndSurvivesTypingAPattern() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        LogControlBar bar = barOf(c, log);

        host.promptAnswer = "09:00:01 - 09:00:02";
        FxTestSupport.runOnFx(c::setTimeRange);
        assertEquals(
                "2026-10-06 09:00:01 to 2026-10-06 09:00:02.999 · 4 of 6 lines",
                FxTestSupport.callOnFx(bar::stateText),
                "the WARN and the ERROR with its trace; a time without a date is on the caret line's day");

        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField pattern = (javafx.scene.control.TextField) bar.lookup(".log-filter-field");
            pattern.setText("payment");
            pattern.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertTrue(FxTestSupport.callOnFx(() -> log.getLogQuery().hasTimeRange()), "typing a pattern keeps the range");
        assertTrue(FxTestSupport.callOnFx(bar::stateText).endsWith("3 of 6 lines"));

        host.promptAnswer = null;
        FxTestSupport.runOnFx(c::setTimeRange);
        assertEquals("2026-10-06 09:00:01 .. 2026-10-06 09:00:02.999", host.promptInitial, "the prompt opens on it");

        FxTestSupport.runOnFx(c::clearTimeRange);
        assertEquals("payment", FxTestSupport.callOnFx(() -> log.getLogQuery().include()), "the pattern stays");
        assertFalse(FxTestSupport.callOnFx(() -> log.getLogQuery().hasTimeRange()));

        host.promptAnswer = "09:00:00 - soon";
        FxTestSupport.runOnFx(c::setTimeRange);
        assertTrue(host.lastStatus.contains("09:00:00 - soon"), "a range it cannot read is said, not applied");
        FxTestSupport.runOnFx(c::clearFilter);
    }

    @Test
    void goToTimeAndShowFromTheCaretLine() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        LogControlBar bar = barOf(c, log);

        host.promptAnswer = "09:00:02.500";
        FxTestSupport.runOnFx(c::goToTime);
        assertEquals(
                5, FxTestSupport.callOnFx(() -> log.getFocusedArea().getCurrentParagraph()), "first line at or after");

        FxTestSupport.runOnFx(() -> log.getFocusedArea().moveTo(4, 0)); // a stack-trace line: its record's time
        FxTestSupport.runOnFx(() -> c.timeRangeAtCaret(true));
        assertTrue(FxTestSupport.callOnFx(bar::stateText).endsWith("4 of 6 lines"), "from 09:00:02 on");

        host.promptAnswer = "11:00";
        FxTestSupport.runOnFx(c::goToTime);
        assertTrue(host.lastStatus.startsWith("No line"), host.lastStatus);
    }

    @Test
    void summarizeOpensAMarkdownReportOfTheWholeLog() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        FxTestSupport.runOnFx(
                () -> log.applyLogFilter(com.editora.logviewer.LogLevel.ERROR, com.editora.logviewer.LogQuery.NONE));
        FxTestSupport.runOnFx(c::summarize);
        FxTestSupport.awaitFxCondition(() -> !host.opened.isEmpty(), "the report opens");
        EditorBuffer report = host.opened.get(0);
        String md = FxTestSupport.callOnFx(report::getContent);
        assertTrue(md.contains("| Records | 4 |"), "the whole log, not the filtered view: " + md);
        assertEquals("markdown", FxTestSupport.callOnFx(report::getLanguage));
        assertTrue(host.lastStatus.startsWith("Summary of"), host.lastStatus);
    }

    @Test
    void mergedLogsOpenInOneTabInTheOrderTheyWereWritten(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path api = java.nio.file.Files.writeString(
                dir.resolve("api.log"), "2026-11-24 10:00:00 INFO  api up\n2026-11-24 10:00:02 ERROR api down\n");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(bytes)) {
            gz.write("2026-11-24 10:00:01 WARN  db slow\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path db = java.nio.file.Files.write(dir.resolve("db.log.1.gz"), bytes.toByteArray());
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);

        FxTestSupport.runOnFx(() -> c.mergeFiles(List.of(api)));
        assertTrue(host.lastStatus.startsWith("Choose two"), host.lastStatus);

        FxTestSupport.runOnFx(() -> c.mergeFiles(List.of(api, db)));
        FxTestSupport.awaitFxCondition(() -> !host.opened.isEmpty(), "the merged tab opens");
        EditorBuffer merged = host.opened.get(0);
        assertEquals(
                "2026-11-24 10:00:00 INFO  api up\n2026-11-24 10:00:01 WARN  db slow\n2026-11-24 10:00:02 ERROR api down\n",
                FxTestSupport.callOnFx(merged::getContent),
                "a compressed log is read decompressed");
        assertTrue(FxTestSupport.callOnFx(merged::isLog));
        assertTrue(FxTestSupport.callOnFx(merged::isReadOnly));
        assertEquals(1, FxTestSupport.callOnFx(() -> merged.getLogSources().sourceOf(1)), "line 2 is db's");
        assertTrue(host.lastStatus.contains("3 lines"), host.lastStatus);

        FxTestSupport.runOnFx(() -> c.mergeFiles(List.of(api, dir.resolve("missing.log"))));
        FxTestSupport.awaitFxCondition(() -> host.lastStatus.startsWith("Could not merge"), "a missing file is said");
    }

    @Test
    void nextProblemStepsThroughWarningsAndErrorsInALog() throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = sampleLog(host, c);
        FxTestSupport.runOnFx(() -> log.getFocusedArea().moveTo(0));
        assertTrue(FxTestSupport.callOnFx(() -> c.stepProblemInLog(true)));
        assertEquals(1, FxTestSupport.callOnFx(() -> log.getFocusedArea().getCurrentParagraph()), "the WARN line");
        assertTrue(FxTestSupport.callOnFx(() -> c.stepProblemInLog(true)));
        assertEquals(2, FxTestSupport.callOnFx(() -> log.getFocusedArea().getCurrentParagraph()), "the ERROR line");

        host.active = null;
        assertFalse(FxTestSupport.callOnFx(() -> c.stepProblemInLog(true)), "no log: the LSP's Next Problem runs");
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
        FxTestSupport.runOnFx(
                () -> plain.applyLogFilter(com.editora.logviewer.LogLevel.ERROR, com.editora.logviewer.LogQuery.NONE));

        FxTestSupport.runOnFx(c::viewAsLog);
        assertFalse(FxTestSupport.callOnFx(plain::isLog), "run again: no longer a log");
        assertFalse(FxTestSupport.callOnFx(plain::hasLogControl));
        assertFalse(FxTestSupport.callOnFx(plain::isLogFiltered), "a filter cannot outlive the control that clears it");
        assertTrue(FxTestSupport.callOnFx(plain::isEditable));
    }

    /** A buffer for {@code file} as the next session opens it: content in, then the remembered view back. */
    private static EditorBuffer reopened(LogViewerCoordinator c, String file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of(file).toAbsolutePath());
            b.setInitialContent(SAMPLE);
            c.sniffLoaded(b, SAMPLE);
            c.restoreView(b);
            return b;
        });
    }

    @Test
    void aLogsLevelFloorAndFilterComeBackInTheNextSession() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        EditorBuffer log = reopened(c, "app.log");
        host.active = log;
        assertTrue(host.workspace.getLogViews().isEmpty(), "an unfiltered log leaves nothing behind");

        host.promptAnswer = "timed out";
        FxTestSupport.runOnFx(c::setRegexFilter);
        host.promptAnswer = "pool";
        FxTestSupport.runOnFx(c::setHideFilter);
        int saves = host.requestSaveCount;

        EditorBuffer next = reopened(c, "app.log");
        assertTrue(FxTestSupport.callOnFx(next::isLogFiltered));
        assertEquals(
                new com.editora.logviewer.LogQuery("timed out", "pool", false),
                FxTestSupport.callOnFx(next::getLogQuery));
        assertEquals(saves, host.requestSaveCount, "putting the view back does not save the session again");

        FxTestSupport.runOnFx(c::clearFilter);
        assertTrue(host.workspace.getLogViews().isEmpty(), "a cleared filter is forgotten");
        EditorBuffer third = reopened(c, "app.log");
        assertFalse(FxTestSupport.callOnFx(third::isLogFiltered));
    }

    @Test
    void viewAsLogIsRememberedBothWays() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setLogViewer(true);
        LogViewerCoordinator c = new LogViewerCoordinator(host);

        EditorBuffer notes = reopened(c, "notes.txt");
        host.active = notes;
        FxTestSupport.runOnFx(c::viewAsLog);
        FxTestSupport.runOnFx(() -> notes.applyLogFilter(null, com.editora.logviewer.LogQuery.NONE));
        assertTrue(FxTestSupport.callOnFx(reopened(c, "notes.txt")::isLog), "View as Log comes back");

        EditorBuffer out = reopened(c, "server.out");
        assertTrue(FxTestSupport.callOnFx(out::isLog), "sniffed from its lines");
        host.active = out;
        FxTestSupport.runOnFx(c::viewAsLog);
        assertFalse(FxTestSupport.callOnFx(out::isLog));
        assertFalse(
                FxTestSupport.callOnFx(reopened(c, "server.out")::isLog),
                "turned off, its lines do not make it a log again");

        host.active = notes;
        FxTestSupport.runOnFx(c::viewAsLog);
        assertFalse(
                host.workspace
                        .getLogViews()
                        .containsKey(Path.of("notes.txt").toAbsolutePath().toString()),
                "a .txt turned back off is as a fresh open shows it: nothing to keep");
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

    @Test
    void aRotationDoesNotReplaceALogTheUserTypedInto(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        LogViewerCoordinator c = new LogViewerCoordinator(host);
        Path file = dir.resolve("annotated.log");
        java.nio.file.Files.writeString(file, SAMPLE);
        EditorBuffer log = sampleLog(host, c);
        long loaded = java.nio.file.Files.size(file);
        long modified = java.nio.file.Files.getLastModifiedTime(file).toMillis();
        FxTestSupport.runOnFx(() -> {
            log.setPath(file);
            log.setDiskSnapshot(modified, loaded);
            log.markClean();
            c.recordLoadOffset(log, loaded);
        });
        try {
            FxTestSupport.runOnFx(c::toggleFollowCommand);
            append(file, "2026-10-06 09:00:04 INFO  followed\n");
            awaitContent(log, "followed\n");
            FxTestSupport.runOnFx(() -> log.getArea().insertText(0, "MY NOTE: incident starts here\n"));
            assertTrue(FxTestSupport.callOnFx(log::isDirty));

            // logrotate copytruncate, or a restart: the file starts over.
            java.nio.file.Files.writeString(file, "2026-10-06 11:00:00 INFO  new file\n");
            OnFx.until(
                    () -> !log.isLogFollowing() || log.getContent().startsWith("2026-10-06 11:00:00"),
                    "the rotation to be noticed",
                    java.time.Duration.ofSeconds(8));

            String text = FxTestSupport.callOnFx(log::getContent);
            assertTrue(text.startsWith("MY NOTE: incident starts here\n"), "the typed text survives: " + text);
            assertTrue(text.endsWith("followed\n"), "and so does the log it annotates");
            assertFalse(FxTestSupport.callOnFx(log::isLogFollowing), "following stopped rather than replace it");
            assertTrue(FxTestSupport.callOnFx(log::isDirty));
            assertTrue(FxTestSupport.callOnFx(() -> log.getArea().isUndoAvailable()));
            assertEquals(com.editora.i18n.Messages.tr("status.log.followStoppedEdited"), host.lastStatus);
            assertEquals(
                    loaded + "2026-10-06 09:00:04 INFO  followed\n".length(),
                    FxTestSupport.callOnFx(() -> log.diskSnapshot().size()),
                    "the snapshot is still the old file's, so saving over the rotated one asks first");
        } finally {
            FxTestSupport.runOnFx(() -> c.onBufferClosed(log));
            c.shutdown();
        }
    }

    private static void append(Path file, String text) throws Exception {
        java.nio.file.Files.writeString(file, text, java.nio.file.StandardOpenOption.APPEND);
    }

    private static void awaitContent(EditorBuffer buffer, String ending) throws Exception {
        OnFx.until(
                () -> buffer.getContent().endsWith(ending),
                "the followed text to arrive: " + ending.strip(),
                java.time.Duration.ofSeconds(8));
    }
}
