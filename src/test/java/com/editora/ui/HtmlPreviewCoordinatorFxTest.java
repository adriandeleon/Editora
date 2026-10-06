package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
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
 * Characterization tests for {@link HtmlPreviewCoordinator} (the second extracted feature coordinator),
 * exercised against a hand-written fake {@link HtmlPreviewCoordinator.Host} with real {@link EditorBuffer}s.
 * Pins the gating, the floating-globe attach/detach (only local HTML buffers, feature on), and the toggle.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmlPreviewCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        boolean local = true;
        String lastStatus;
        int saveCount;
        int syncCount;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return buffers.isEmpty() ? null : buffers.get(0);
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return local;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }

        @Override
        public void save() {
            saveCount++;
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
        public void openExternalUrl(String url) {
            // no-op
        }
    }

    private static EditorBuffer htmlBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("page.html"); // makes isHtml() true without a file on disk
            return b;
        });
    }

    @Test
    void isEnabledTracksTheSetting() {
        FakeHost host = new FakeHost();
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        host.settings.setHtmlPreviewSupport(true);
        assertTrue(c.isEnabled());
        host.settings.setHtmlPreviewSupport(false);
        assertFalse(c.isEnabled());
    }

    @Test
    void ensureControlAttachesGlobeOnLocalHtmlBufferAndRemovesOnDisable() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        EditorBuffer html = htmlBuffer();

        FxTestSupport.runOnFx(() -> c.ensureControl(html));
        assertTrue(FxTestSupport.callOnFx(html::hasHtmlPreviewControl), "local HTML buffer gets the browser globe");

        host.settings.setHtmlPreviewSupport(false);
        FxTestSupport.runOnFx(() -> c.ensureControl(html));
        assertFalse(FxTestSupport.callOnFx(html::hasHtmlPreviewControl), "disabling the feature removes the globe");
    }

    @Test
    void browserControlMovesInsideTheEditorWhenMinimapAppears() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        EditorBuffer html = htmlBuffer();

        FxTestSupport.runOnFx(() -> {
            html.setMinimapVisible(false);
            c.ensureControl(html);
        });
        Node control = FxTestSupport.field(html, "htmlPreviewControl");
        // The control sits in the code pane's shared corner row; the row is what gets anchored.
        double withoutMinimap = FxTestSupport.callOnFx(() -> AnchorPane.getRightAnchor(control.getParent()));

        FxTestSupport.runOnFx(() -> html.setMinimapVisible(true));
        double withMinimap = FxTestSupport.callOnFx(() -> AnchorPane.getRightAnchor(control.getParent()));

        assertTrue(withMinimap > withoutMinimap, "browser control must move left instead of covering the minimap");
    }

    @Test
    void ensureControlSkipsNonLocalBuffers() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        host.local = false; // remote (SFTP) — the preview server reads sibling assets from disk
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        EditorBuffer html = htmlBuffer();

        FxTestSupport.runOnFx(() -> c.ensureControl(html));
        assertFalse(FxTestSupport.callOnFx(html::hasHtmlPreviewControl), "no globe on a non-local HTML buffer");
    }

    @Test
    void applySupportOnlySkinsHtmlBuffers() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        EditorBuffer html = htmlBuffer();
        EditorBuffer plain = FxTestSupport.callOnFx(() -> new EditorBuffer());
        host.buffers.add(html);
        host.buffers.add(plain);

        FxTestSupport.runOnFx(c::applySupport);
        assertTrue(FxTestSupport.callOnFx(html::hasHtmlPreviewControl));
        assertFalse(FxTestSupport.callOnFx(plain::hasHtmlPreviewControl));
    }

    @Test
    void toggleFlipsTheSettingAndNotifiesTheHost() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(false);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);

        FxTestSupport.runOnFx(c::toggle);
        assertTrue(host.settings.isHtmlPreviewSupport(), "toggled on");
        assertEquals(1, host.saveCount, "persisted via the host");
        assertEquals(1, host.syncCount, "re-synced the Settings window checkbox");
    }

    @Test
    void refusesToServeAnHtmlFileSittingDirectlyInTheHomeDirectory() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        java.nio.file.Path inHome = java.nio.file.Path.of(System.getProperty("user.home"), "editora-test-page.html");
        EditorBuffer html = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(inHome); // not created on disk; the folder is what matters
            return b;
        });
        host.buffers.add(html);
        try {
            FxTestSupport.runOnFx(c::open);
            assertTrue(
                    host.lastStatus != null
                            && host.lastStatus.contains(inHome.getParent().toString()),
                    "names the folder it will not serve: " + host.lastStatus);
            assertTrue(host.lastStatus.startsWith("Not serving"), host.lastStatus);
            assertEquals(0, host.saveCount, "refused before anything was started or remembered");
        } finally {
            c.shutdown();
        }
    }
}
