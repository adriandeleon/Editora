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
        assertEquals(Boolean.FALSE, format(html).get("endWithNewline"), "B104: Editora keeps the final newline");
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
        assertEquals(Boolean.FALSE, options.getBoolean("endWithNewline"), "never the server's (B104)");
        assertEquals(2, options.getTabSize());
    }

    private List<com.editora.text.LspTextEdit> format(FormattingIndent indent) throws Exception {
        CompletableFuture<List<com.editora.text.LspTextEdit>> done = new CompletableFuture<>();
        Platform.runLater(() -> manager.formatDocument(file, indent, done::complete));
        return done.get(10, TimeUnit.SECONDS);
    }

    /**
     * HTML review 2 B104: the server answers without a final newline (it is told {@code endWithNewline: false}, which
     * kept a blank line from appearing before every {@code </style>}); the edits Editora applies keep the one the
     * file had, or take off one EditorConfig says it must not have. Another server's edits are left alone.
     */
    @Test
    void theFinalNewlineIsKeptByEditoraNotTheServer() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        fake.formattingResponse = List.of(new org.eclipse.lsp4j.TextEdit(
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(1, 0)),
                "<pre>  a  </pre>"));
        var indent = new FormattingIndent(2, true).withDocument(null, true);
        assertEquals("<pre>  a  </pre>\n", format(indent).get(0).newText(), "the file ends with a newline: kept");
        assertEquals(
                "<pre>  a  </pre>",
                format(indent.withSaveRules(null, false)).get(0).newText(),
                "insert_final_newline = false: none");
        fake.formattingResponse = List.of(new org.eclipse.lsp4j.TextEdit(
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(1, 0)),
                "<pre>  a  </pre>\n\n"));
        assertEquals(
                "<pre>  a  </pre>\n\n",
                format(new FormattingIndent(2, true)).get(0).newText(),
                "nothing known of the text: as the server made it");
    }
}
