package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.editops.HtmlLinks;
import com.editora.text.ColorSwatch;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real vscode-html-language-server (HTML review B6, D5, D9): the markup check joins the
 * server's pulled diagnostics, the server's document link for {@code about.html#team} comes back split into the
 * file and its fragment (the server sends {@code about.html%23team}), and its colour provider answers with the
 * swatch for {@code red}.
 *
 * <p>Locally: {@code ./mvnw test -Dtest=HtmlServerNodeProbeFxTest -Dgroups=node -Dnode.probe=true}, with
 * {@code vscode-html-language-server} (vscode-langservers-extracted) on the PATH.
 */
@Tag("node")
@Tag("fx")
class HtmlServerNodeProbeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void linksChecksAndColoursFromTheRealServer(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("node.probe"), "opt-in: -Dnode.probe=true");
        Path root = Files.createDirectories(dir.resolve("site"));
        Files.writeString(root.resolve("package.json"), "{\"name\":\"probe\",\"version\":\"1.0.0\"}\n");
        Files.createDirectories(root.resolve("pages"));
        Files.writeString(root.resolve("pages/about.html"), "<h2 id=\"team\">Team</h2>\n");
        Path file = root.resolve("index.html");
        String text = """
                <!doctype html>
                <html><head><style>body { color: red; }</style></head>
                <body>
                <a href="pages/about.html#team">Team</a>
                <main><div>unclosed
                </main>
                </body></html>
                """;
        Files.writeString(file, text);

        CompletableFuture<List<LspDiagnostic>> diagnostics = new CompletableFuture<>();
        StringBuffer status = new StringBuffer();
        LspManager manager = new LspManager(
                (f, d) -> {
                    if (f.equals(file)
                            && d != null
                            && d.stream().anyMatch(x -> HtmlMarkupLint.SOURCE.equals(x.source()))) {
                        diagnostics.complete(d);
                    }
                },
                (type, message) ->
                        status.append(type).append(": ").append(message).append('\n'));
        try {
            manager.configure(true, Map.of());
            Platform.runLater(() -> manager.openDocument(file, root, "html", text));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!manager.supportsDocumentColor(file) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(manager.supportsDocumentColor(file), "the HTML server did not get ready:\n" + status);

            manager.pullDiagnostics(file);
            List<LspDiagnostic> got = diagnostics.get(30, TimeUnit.SECONDS);
            assertTrue(
                    got.stream().anyMatch(d -> "unclosed-element".equals(d.code()) && d.startLine() == 4),
                    "the unclosed <div>: " + got);

            CompletableFuture<HtmlLinks.Target> link = new CompletableFuture<>();
            manager.documentLinkTarget(file, 3, 15, link::complete);
            HtmlLinks.Target target = link.get(30, TimeUnit.SECONDS);
            assertTrue(target != null, "a document link at the href");
            assertEquals(
                    root.resolve("pages/about.html").toRealPath(), target.file().toRealPath());
            assertEquals("team", target.fragment());

            CompletableFuture<List<ColorSwatch>> colors = new CompletableFuture<>();
            manager.documentColors(file, colors::complete);
            List<ColorSwatch> swatches = colors.get(30, TimeUnit.SECONDS);
            assertEquals(1, swatches.size(), swatches.toString());
            assertEquals(1, swatches.get(0).line());
            assertEquals("ff0000ff", swatches.get(0).hex());
        } finally {
            manager.close();
        }
    }
}
