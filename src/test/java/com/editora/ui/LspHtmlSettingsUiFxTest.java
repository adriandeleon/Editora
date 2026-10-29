package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A window hands the HTML server its settings (HTML review B2, A3, A4, D3): the user's Settings → LSP → HTML values
 * and the editor's auto-close switch, pushed again on every apply; a project's {@code html} object; and Format
 * Document carries the buffer's wrap column and final newline.
 */
@Tag("fx")
class LspHtmlSettingsUiFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("html", true));
        fx.open("index.html", "<pre>\n  keep   this\n</pre>\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPushedHtml() {
        Map<String, Object> settings = (Map<String, Object>)
                FakeLanguageServer.last(fx.server().configurations).getSettings();
        return (Map<String, Object>) settings.get("html");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format(Map<String, Object> html) {
        return (Map<String, Object>) html.get("format");
    }

    @Test
    void theAutoCloseSwitchAndTheHtmlSettingsArePushed() throws Exception {
        fx.host.settings.setAutoCloseTags(false);
        fx.host.settings.setHtmlFormatWrapAttributes("force");
        fx.run(() -> fx.coordinator.applyXmlSettings()); // every settings apply runs it

        Map<String, Object> html = lastPushedHtml();
        assertEquals(Boolean.FALSE, html.get("autoClosingTags"));
        assertEquals("force", format(html).get("wrapAttributes"));
        assertEquals("pre,code,textarea", format(html).get("contentUnformatted"));
    }

    @Test
    void aProjectsHtmlObjectIsLaidOver() throws Exception {
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(
                root.resolve(".editora/settings.json"), "{\"html\": {\"format\": {\"wrapLineLength\": 100}}}");
        fx.ops.projectRoot = root;

        fx.run(() -> fx.coordinator.reloadProjectSettings());

        assertEquals(100, format(lastPushedHtml()).get("wrapLineLength"));
    }

    @Test
    void formatDocumentSendsTheBuffersWrapColumnAndFinalNewline() throws Exception {
        fx.run(() -> fx.coordinator.formatDocument());

        var options = FakeLanguageServer.last(fx.server().formattings).getOptions();
        assertEquals(120, options.getNumber("wrapLineLength").intValue());
        assertEquals(Boolean.TRUE, options.getBoolean("endWithNewline"), "the buffer ends with a newline");
    }
}
