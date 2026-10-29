package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.config.Settings;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTML server gets its settings through {@link LspManager} (HTML review B2, A3, A4, D3): answered per document,
 * pushed again when they change — the auto-close switch included — with a project's {@code html} object for the
 * project's own root only, and a formatting request carries the document's wrap column and final newline.
 */
@Tag("fx")
class LspHtmlSettingsFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private List<FakeLanguageServer> fakes;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("html", "html-ls"));
        manager.setHtmlSettings(new Settings(), null, root);
        file = root.resolve("index.html");
        Files.writeString(file, "<pre>  a  </pre>\n");
        manager.setDocumentIndent(file, new FormattingIndent(2, true).withDocument(72, true));
        manager.openDocument(file, root, "html", "<pre>  a  </pre>\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> askHtml() throws Exception {
        ConfigurationItem item = new ConfigurationItem();
        item.setSection("html");
        item.setScopeUri(LspTestHooks.uri(file));
        return (Map<String, Object>) manager.sessionForTest(file)
                .configuration(new ConfigurationParams(List.of(item)))
                .get()
                .get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> format(Map<String, Object> html) {
        return (Map<String, Object>) html.get("format");
    }

    @Test
    void theAnswerIsTheDocumentsOwn() throws Exception {
        Map<String, Object> html = askHtml();
        assertEquals("pre,code,textarea", format(html).get("contentUnformatted"));
        assertEquals(72, format(html).get("wrapLineLength"), "the file's max_line_length");
        assertEquals(Boolean.TRUE, format(html).get("endWithNewline"), "the text ends with a newline");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aChangeIsPushedAndTheProjectsObjectServesItsRoot() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        int before = fake.configurations.size();
        Settings s = new Settings();
        s.setAutoCloseTags(false);
        manager.setHtmlSettings(s, Map.of("format", Map.of("wrapAttributes", "force-aligned")), root);
        assertTrue(fake.configurations.size() > before, "pushed");
        var pushed = (Map<String, Object>) ((Map<String, Object>)
                        FakeLanguageServer.last(fake.configurations).getSettings())
                .get("html");
        assertEquals(Boolean.FALSE, pushed.get("autoClosingTags"), "Editora closes tags: the server must not");
        assertEquals("force-aligned", format(pushed).get("wrapAttributes"));
        assertEquals(Boolean.FALSE, askHtml().get("autoClosingTags"));

        // Another project: this root's server gets the user's settings, not the old project's.
        manager.setHtmlSettings(
                s, Map.of("format", Map.of("wrapAttributes", "force-aligned")), root.resolveSibling("other"));
        assertEquals("auto", format(askHtml()).get("wrapAttributes"));
    }

    @Test
    void aFormattingRequestCarriesTheWrapColumnAndFinalNewline() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        CompletableFuture<Object> done = new CompletableFuture<>();
        Platform.runLater(() ->
                manager.formatDocument(file, new FormattingIndent(2, true).withDocument(null, false), done::complete));
        done.get(10, TimeUnit.SECONDS);
        var options = FakeLanguageServer.last(fake.formattings).getOptions();
        assertEquals(120, options.getNumber("wrapLineLength").intValue(), "no max_line_length: the setting");
        assertEquals(Boolean.FALSE, options.getBoolean("endWithNewline"), "the text as it is now");
        assertEquals(2, options.getTabSize());
    }
}
