package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link HttpClientCoordinator} (the most window-entangled coordinator), exercised
 * against a fake {@link CoordinatorHost} (via {@link CoordinatorHostStub}) plus a fake {@link
 * HttpClientCoordinator.WindowOps}. Pins the gating, the lazy tool-window panel, and that the disabled
 * commands report + never touch the window.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpClientCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        boolean simpleMode = false;
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String lastStatus;

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
    }

    private static final class FakeOps implements HttpClientCoordinator.WindowOps {
        int openTab;
        int openToolWindow;
        int toggleToolWindow;
        int updateRunGating;
        String persistedEnv;

        @Override
        public void openTab(EditorBuffer buffer) {
            openTab++;
        }

        @Override
        public void openToolWindow(boolean focus) {
            openToolWindow++;
        }

        @Override
        public void toggleToolWindow() {
            toggleToolWindow++;
        }

        @Override
        public void updateRunGating() {
            updateRunGating++;
        }

        @Override
        public String savedEnvironment() {
            return "";
        }

        @Override
        public void persistEnvironment(String env) {
            persistedEnv = env;
        }
    }

    @Test
    void isEnabledTracksSettingAndSimpleMode() {
        FakeHost host = new FakeHost();
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        host.settings.setHttpClientSupport(true);
        assertTrue(c.isEnabled());
        host.simpleMode = true;
        assertFalse(c.isEnabled(), "suppressed in Simple UI mode");
        host.simpleMode = false;
        host.settings.setHttpClientSupport(false);
        assertFalse(c.isEnabled());
    }

    @Test
    void panelIsBuiltLazilyAndReused() throws Exception {
        FakeHost host = new FakeHost();
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        HttpClientPanel p1 = FxTestSupport.callOnFx(c::panel);
        HttpClientPanel p2 = FxTestSupport.callOnFx(c::panel);
        assertNotNull(p1);
        assertSame(p1, p2, "the tool-window panel is built once and reused");
    }

    @Test
    void applySupportUpdatesRunGating() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(false);
        FakeOps ops = new FakeOps();
        HttpClientCoordinator c = new HttpClientCoordinator(host, ops);

        FxTestSupport.runOnFx(c::applySupport);
        assertEquals(1, ops.updateRunGating, "applySupport re-gates the run/HTTP affordance");
    }

    @Test
    void disabledCommandsReportAndDoNotTouchTheWindow() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(false); // disabled
        FakeOps ops = new FakeOps();
        HttpClientCoordinator c = new HttpClientCoordinator(host, ops);

        FxTestSupport.runOnFx(() -> {
            c.runRequestAtCaret();
            c.runFile();
            c.toggleToolWindow();
            c.selectEnvironment();
        });
        assertEquals(tr("statusbar.tip.httpDisabled"), host.lastStatus);
        assertEquals(0, ops.toggleToolWindow, "disabled → never toggles the tool window");
        assertEquals(0, ops.openToolWindow, "disabled → never opens the tool window");
    }

    @Test
    void enabledToggleAndSelectGoThroughTheWindowOps() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        FakeOps ops = new FakeOps();
        HttpClientCoordinator c = new HttpClientCoordinator(host, ops);

        FxTestSupport.runOnFx(() -> {
            c.toggleToolWindow();
            c.selectEnvironment();
        });
        assertEquals(1, ops.toggleToolWindow);
        assertEquals(1, ops.openToolWindow, "selectEnvironment opens + focuses the tool window");
    }

    @Test
    void runRequestAtCaretWithNoHttpBufferReports() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        host.active = FxTestSupport.callOnFx(() -> new EditorBuffer()); // a plain (non-.http) buffer

        FxTestSupport.runOnFx(c::runRequestAtCaret);
        assertEquals(tr("status.http.noRequest"), host.lastStatus);
    }

    private static String tr(String key) {
        return com.editora.i18n.Messages.tr(key);
    }
}
