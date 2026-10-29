package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.text.LspTextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real vscode-html-language-server (HTML review B2, A3): Format Document leaves the
 * content of {@code <pre>} and {@code <textarea>} byte for byte and keeps the file's final newline. With the
 * {@code {}} answer Editora gave before, the server re-spaced both and dropped the newline.
 *
 * <p>Locally: {@code ./mvnw test -Dtest=HtmlServerFormatNodeProbeFxTest -Dgroups=node -Dnode.probe=true}, with
 * {@code vscode-html-language-server} (vscode-langservers-extracted) and {@code node} on the PATH.
 */
@Tag("node")
@Tag("fx")
class HtmlServerFormatNodeProbeFxTest {

    private static final String PRE = "<pre>\n  keep   this\n     spacing\n</pre>";
    private static final String TEXTAREA = "<textarea>  raw   text  </textarea>";

    private static final String MESSY = "<html><head><title>messy</title>\n"
            + "<style>body{margin:0;padding:0}.a{color:red}</style></head>\n"
            + "<body><div><p>one</p><p>two <b>three</b></p>\n"
            + "<ul><li>a</li><li>b</li></ul></div>\n"
            + PRE
            + "\n<script>function f(){if(true){return 1}}</script>\n"
            + TEXTAREA
            + "\n</body></html>\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void formatDocumentKeepsPreTextareaAndTheFinalNewline(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("node.probe"), "opt-in: -Dnode.probe=true");
        assumeTrue(
                com.editora.doctor.DoctorProbes.onPath(List.of("vscode-html-language-server")),
                "vscode-html-language-server is not on the PATH");
        Path root = Files.createDirectories(dir.resolve("site"));
        Path file = root.resolve("messy.html");
        Files.writeString(file, MESSY);

        StringBuffer status = new StringBuffer();
        LspManager manager = new LspManager(
                (f, d) -> {},
                (type, message) ->
                        status.append(type).append(": ").append(message).append('\n'));
        try {
            manager.configure(true, Map.of());
            manager.setHtmlSettings(r -> HtmlServerSettings.defaults());
            CompletableFuture<String> failed = new CompletableFuture<>();
            manager.setOnStartFailed((serverId, sessionRoot) -> failed.complete("start failed: " + serverId));
            manager.setOnSessionCrashed((serverId, sessionRoot) -> failed.complete("crashed: " + serverId));
            Platform.runLater(() -> manager.openDocument(file, root, "html", MESSY));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!manager.supportsFormatting(file) && !failed.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(manager.supportsFormatting(file), "the HTML server did not come up: " + failed + "\n" + status);

            // No .editorconfig: the text's own final newline is kept.
            FormattingIndent indent = new FormattingIndent(2, true).withDocument(null, true);
            CompletableFuture<List<LspTextEdit>> edits = new CompletableFuture<>();
            Platform.runLater(() -> manager.formatDocument(file, indent, edits::complete));
            String formatted = apply(MESSY, edits.get(30, TimeUnit.SECONDS));

            assertNotEquals(MESSY, formatted, "the server formatted nothing:\n" + status);
            assertTrue(formatted.contains(PRE), "<pre> content changed:\n" + formatted);
            assertTrue(formatted.contains(TEXTAREA), "<textarea> content changed:\n" + formatted);
            assertTrue(formatted.endsWith("</html>\n"), "the final newline was dropped:\n" + formatted);
        } finally {
            manager.close();
        }
    }

    /** {@code text} with the server's edits applied (line/character positions, last edit first). */
    private static String apply(String text, List<LspTextEdit> edits) {
        List<Integer> lineStarts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
        List<LspTextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(LspTextEdit::startLine)
                .thenComparingInt(LspTextEdit::startCol)
                .reversed());
        StringBuilder sb = new StringBuilder(text);
        for (LspTextEdit e : sorted) {
            int start = Math.min(text.length(), lineStarts.get(e.startLine()) + e.startCol());
            int end =
                    Math.min(text.length(), lineStarts.get(Math.min(e.endLine(), lineStarts.size() - 1)) + e.endCol());
            sb.replace(start, end, e.newText());
        }
        return sb.toString();
    }
}
