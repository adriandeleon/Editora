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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link HttpClientCoordinator}, exercised against a fake {@link CoordinatorHost}
 * (via {@link CoordinatorHostStub}) plus a fake {@link HttpClientCoordinator.WindowOps}. Pins the gating, the
 * per-buffer in-editor response preview (attach / detach / one panel per buffer / drop on close), and that the
 * disabled commands report rather than acting.
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
        int updateRunGating;
        String persistedEnv;

        @Override
        public void openTab(EditorBuffer buffer) {
            openTab++;
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

    /** An unsaved buffer whose display name gives it the {@code http} language, so {@code isHttpFile()}. */
    private static EditorBuffer httpBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("requests.http");
            return b;
        });
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
    void previewIsAttachedToAnHttpBufferAndMakesItPreviewable() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        EditorBuffer b = httpBuffer();
        assertTrue(b.isHttpFile());
        assertFalse(b.hasPreview(), "no response panel injected yet");

        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(b));
        assertTrue(b.hasHttpPreview());
        assertTrue(b.hasPreview(), "the injected panel is what makes the mode toggle attach");
    }

    @Test
    void previewIsNotAttachedToANonHttpBufferOrWhenDisabled() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());

        EditorBuffer plain = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(plain));
        assertFalse(plain.hasHttpPreview(), "a non-.http buffer never gets the response preview");

        host.settings.setHttpClientSupport(false);
        EditorBuffer b = httpBuffer();
        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(b));
        assertFalse(b.hasHttpPreview(), "feature off → no preview");
    }

    @Test
    void turningTheFeatureOffDetachesThePreviewAndReturnsTheBufferToEditor() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        EditorBuffer b = httpBuffer();
        host.buffers.add(b);

        FxTestSupport.runOnFx(() -> {
            c.applySupport();
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
        });
        assertTrue(b.hasHttpPreview());
        assertEquals(EditorBuffer.MarkdownViewMode.SPLIT, b.getMarkdownViewMode());

        host.settings.setHttpClientSupport(false);
        FxTestSupport.runOnFx(c::applySupport);
        assertFalse(b.hasHttpPreview());
        assertFalse(b.hasPreview());
        assertEquals(
                EditorBuffer.MarkdownViewMode.EDITOR,
                b.getMarkdownViewMode(),
                "the panel is gone, so the buffer must fall back to the source rather than strand in SPLIT");
    }

    @Test
    void eachHttpBufferGetsItsOwnPanelAndItIsDroppedOnClose() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        EditorBuffer a = httpBuffer();
        EditorBuffer b = httpBuffer();

        FxTestSupport.runOnFx(() -> {
            c.ensureHttpPreview(a);
            c.ensureHttpPreview(b);
        });
        Object panelA = FxTestSupport.callOnFx(() -> c.panelForTest(a));
        Object panelB = FxTestSupport.callOnFx(() -> c.panelForTest(b));
        assertNotNull(panelA);
        assertNotNull(panelB);
        assertNotSame(panelA, panelB, "a Node can't live in two tabs — one panel per buffer");

        // Re-running the attach must not replace the existing panel (it would drop the response history).
        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(a));
        assertSame(panelA, FxTestSupport.callOnFx(() -> c.panelForTest(a)));

        FxTestSupport.runOnFx(() -> c.onBufferClosed(a));
        assertNotNull(FxTestSupport.callOnFx(() -> c.panelForTest(b)), "closing one buffer keeps the other's");
        assertNull(FxTestSupport.callOnFx(() -> c.panelForTest(a)), "the closed buffer's panel is released");
    }

    @Test
    void runningARequestRevealsThePreviewFromEditorMode() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        EditorBuffer b = httpBuffer();

        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(b));
        assertEquals(EditorBuffer.MarkdownViewMode.EDITOR, b.getMarkdownViewMode());

        FxTestSupport.runOnFx(b::revealHttpPreview);
        assertEquals(
                EditorBuffer.MarkdownViewMode.SPLIT,
                b.getMarkdownViewMode(),
                "a run shows the response beside the source, like the tool window used to auto-open");

        // Already showing a preview → the user's chosen mode wins; the nudge must not override it.
        FxTestSupport.runOnFx(() -> {
            b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW);
            b.revealHttpPreview();
        });
        assertEquals(EditorBuffer.MarkdownViewMode.PREVIEW, b.getMarkdownViewMode());
    }

    /**
     * Whether {@code root}'s scene graph contains {@code target} anywhere below it. A {@link
     * javafx.scene.control.SplitPane}'s items only become real <em>children</em> once its skin is built
     * (which needs a live {@code Scene}), so descend into {@code getItems()} explicitly — otherwise a
     * detached SPLIT view looks empty here even though it is wired correctly.
     */
    private static boolean containsNode(javafx.scene.Node root, javafx.scene.Node target) {
        if (root == target) {
            return true;
        }
        if (root instanceof javafx.scene.control.SplitPane sp) {
            for (javafx.scene.Node item : sp.getItems()) {
                if (containsNode(item, target)) {
                    return true;
                }
            }
        }
        if (root instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                if (containsNode(child, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void thePanelIsActuallyMountedInTheViewHostInBothSplitAndPreview() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(true);
        HttpClientCoordinator c = new HttpClientCoordinator(host, new FakeOps());
        EditorBuffer b = httpBuffer();
        FxTestSupport.runOnFx(() -> c.ensureHttpPreview(b));
        HttpClientPanel panel = FxTestSupport.callOnFx(() -> c.panelForTest(b));

        assertFalse(
                FxTestSupport.callOnFx(() -> containsNode(b.getNode(), panel)), "EDITOR mode shows only the source");

        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT));
        assertTrue(
                FxTestSupport.callOnFx(() -> containsNode(b.getNode(), panel)),
                "SPLIT must mount the response panel beside the editor");

        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.PREVIEW));
        assertTrue(
                FxTestSupport.callOnFx(() -> containsNode(b.getNode(), panel)),
                "PREVIEW must mount the response panel (wrapped so the mode toggle can overlay it)");

        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR));
        assertFalse(FxTestSupport.callOnFx(() -> containsNode(b.getNode(), panel)));
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
    void disabledCommandsReportAndDoNotAct() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHttpClientSupport(false); // disabled
        FakeOps ops = new FakeOps();
        HttpClientCoordinator c = new HttpClientCoordinator(host, ops);
        EditorBuffer b = httpBuffer();
        host.active = b;

        FxTestSupport.runOnFx(() -> {
            c.runRequestAtCaret();
            c.runFile();
            c.selectEnvironment();
        });
        assertEquals(tr("statusbar.tip.httpDisabled"), host.lastStatus);
        assertFalse(b.hasHttpPreview(), "disabled → never attaches the preview");
        assertEquals(0, ops.openTab);
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
