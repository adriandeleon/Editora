package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.web.Browsers;
import com.editora.web.Browsers.Browser;
import com.editora.web.HtmlPreviewService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link HtmlPreviewCoordinator} (the second extracted feature coordinator),
 * exercised against a hand-written fake {@link CoordinatorHost} with real {@link EditorBuffer}s. Pins the gating,
 * the floating-globe attach/detach (only local HTML buffers, feature on, following the language), the refusal of
 * remote buffers and unsafe roots, the status lines, and the toggle.
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
    void openAndOpenInRefuseARemoteBuffer() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        host.local = false; // an SFTP buffer: its assets would be read over the network, its home exposed
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        host.buffers.add(htmlBuffer());
        try {
            FxTestSupport.runOnFx(c::open);
            assertEquals(Messages.tr("status.htmlPreview.remote"), host.lastStatus);
            assertEquals(0, host.saveCount, "nothing served, no browser remembered");
            host.lastStatus = null;
            FxTestSupport.runOnFx(c::openIn);
            assertEquals(Messages.tr("status.htmlPreview.remote"), host.lastStatus, "no browser picker either");
        } finally {
            c.shutdown();
        }
    }

    @Test
    void aLanguageThatArrivesLaterBringsTheGlobe() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host);
        EditorBuffer noExtension = FxTestSupport.callOnFx(EditorBuffer::new); // plain text at creation
        FxTestSupport.runOnFx(() -> {
            c.wireBuffer(noExtension);
            c.ensureControl(noExtension);
        });
        assertFalse(FxTestSupport.callOnFx(noExtension::hasHtmlPreviewControl));
        FxTestSupport.runOnFx(() -> noExtension.setLanguageOverride("html")); // as a modeline or the picker does
        assertTrue(FxTestSupport.callOnFx(noExtension::hasHtmlPreviewControl), "the globe follows the language");
        FxTestSupport.runOnFx(() -> noExtension.setLanguageOverride("css"));
        assertFalse(FxTestSupport.callOnFx(noExtension::hasHtmlPreviewControl));
    }

    @Test
    void labelsAreLocalizedAndAMissingBrowserIsNamedNotShownAsAnId() {
        assertEquals(
                Messages.tr("htmlPreview.systemDefault"),
                HtmlPreviewCoordinator.label(new Browser(Browsers.SYSTEM_DEFAULT, "System Default")));
        assertEquals("Firefox", HtmlPreviewCoordinator.label(new Browser("firefox", "Firefox")));
        java.util.Map<String, String> names = java.util.Map.of("firefox", "Firefox");
        assertEquals("Firefox", SettingsWindow.browserComboLabel("firefox", names, true));
        assertEquals("brave", SettingsWindow.browserComboLabel("brave", names, false), "not looked up yet");
        assertEquals("Brave (not found)", SettingsWindow.browserComboLabel("brave", names, true));
    }

    @Test
    void statusLinesSayWhatFailed() {
        HtmlPreviewService.Result opened =
                new HtmlPreviewService.Result(HtmlPreviewService.Outcome.OPENED, "http://127.0.0.1:1/t/1/i.html", null);
        assertEquals("HTML preview at http://127.0.0.1:1/t/1/i.html", HtmlPreviewCoordinator.statusFor(opened, "X"));
        HtmlPreviewService.Result server =
                new HtmlPreviewService.Result(HtmlPreviewService.Outcome.SERVER_FAILED, null, "Address in use");
        assertEquals(
                "Couldn't start the preview server: Address in use", HtmlPreviewCoordinator.statusFor(server, "X"));
        HtmlPreviewService.Result launch =
                new HtmlPreviewService.Result(HtmlPreviewService.Outcome.LAUNCH_FAILED, "u", "No such file");
        assertEquals(
                "Couldn't open the browser: Vivaldi: No such file",
                HtmlPreviewCoordinator.statusFor(launch, "Vivaldi"));
        HtmlPreviewService.Result exited = new HtmlPreviewService.Result(HtmlPreviewService.Outcome.EXITED, "u", "1");
        assertTrue(HtmlPreviewCoordinator.statusFor(exited, "Opera").startsWith("Opera quit right away (exit code 1)"));
    }

    @Test
    void typingInTheSitesStylesheetSwapsItInTheOpenPage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path page = java.nio.file.Files.writeString(
                dir.resolve("index.html"), "<link rel=stylesheet href=site.css><body>x</body>");
        java.nio.file.Path css = java.nio.file.Files.writeString(dir.resolve("site.css"), "body{}");
        FakeHost host = new FakeHost();
        host.settings.setHtmlPreviewSupport(true);
        HtmlPreviewCoordinator c = new HtmlPreviewCoordinator(host); // the fake's opener opens nothing
        EditorBuffer html = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(page);
            b.setContent(java.nio.file.Files.readString(page));
            return b;
        });
        EditorBuffer sheet = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(css);
            b.setContent("body{}");
            return b;
        });
        host.buffers.add(html);
        host.buffers.add(sheet);
        try {
            FxTestSupport.runOnFx(() -> {
                c.wireBuffer(html);
                c.wireBuffer(sheet);
                c.open();
            });
            String url = null;
            for (int i = 0; i < 100 && url == null; i++) {
                Thread.sleep(50);
                String st = FxTestSupport.callOnFx(() -> host.lastStatus);
                if (st != null && st.startsWith("HTML preview at ")) {
                    url = st.substring("HTML preview at ".length());
                }
            }
            assertTrue(url != null, "served: " + host.lastStatus);
            FxTestSupport.runOnFx(() -> sheet.getArea().replaceText("body{color:red}")); // typed, not saved
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String poll = url.replace("/index.html", "/__editora_livereload?v=1");
            String answer = "";
            for (int i = 0; i < 60 && !answer.endsWith("css"); i++) {
                Thread.sleep(50); // the settled-edit pulse fires 250 ms after the last keystroke
                answer = client.send(
                                java.net.http.HttpRequest.newBuilder(java.net.URI.create(poll))
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString())
                        .body();
            }
            assertEquals("2 css", answer, "the open page swaps its stylesheet");
            String served = client.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(url.replace("index.html", "site.css")))
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString())
                    .body();
            assertEquals("body{color:red}", served, "the unsaved stylesheet text is served");
        } finally {
            c.shutdown();
        }
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
