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
    private static final class FakeHost implements LogViewerCoordinator.Host {
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

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            // no-op for these tests
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
        assertTrue(FxTestSupport.callOnFx(log::hasLogControl), "a log buffer gets the floating control when enabled");

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
}
