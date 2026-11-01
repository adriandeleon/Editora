package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML review 3 A202: a page outside the window's project resolves its root-relative links ({@code /css/site.css})
 * from its own site's root — the folder its server and the markup check use — not from the project's, whose
 * same-named file Go to Definition used to open while the markup check said the link was missing.
 */
@Tag("fx")
class HtmlLooseLinksFxTest {

    private static final String PAGE = """
            <!doctype html>
            <link rel="stylesheet" href="/css/site.css">
            <a href="/pages/about.html">about</a>
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aLoosePagesRootRelativeLinksStartFromItsOwnSite(@TempDir Path dir) throws Exception {
        try (WindowRig w = new WindowRig()) {
            Path project = w.fx.useProject();
            Files.createDirectories(project.resolve("css"));
            Files.createDirectories(project.resolve("pages"));
            Files.writeString(project.resolve("css/site.css"), "/* the project's */\n");
            Files.writeString(project.resolve("pages/about.html"), "<p>the project's about</p>\n");
            Path other = Files.createDirectories(dir.resolve("other"));
            Files.createDirectories(other.resolve("css"));
            Files.createDirectories(other.resolve("sub"));
            Files.writeString(other.resolve("index.html"), "<p>the other site</p>\n");
            Path css = Files.writeString(other.resolve("css/site.css"), "/* the other site's */\n");
            Path page = Files.writeString(other.resolve("sub/page.html"), PAGE);

            EditorBuffer b = w.open(page);
            // the markup check (no server here) roots the page at its site, as its server would
            w.await(
                    "the missing about page is marked",
                    () -> b.httpVariableDiagnostics().size() == 1);
            FxTestSupport.runOnFx(() -> assertEquals(
                    tr("html.check.missingFile", "/pages/about.html"),
                    b.httpVariableDiagnostics().get(0).message()));

            caret(w, "/css/site.css");
            w.run("lsp.gotoDefinition");
            w.await("the other site's stylesheet", () -> w.isActive(css));

            w.open(page);
            caret(w, "/pages/about.html");
            w.run("lsp.gotoDefinition");
            w.await(
                    "said missing, as the mark says",
                    () -> tr("status.html.linkMissing", "/pages/about.html").equals(status(w)));
            FxTestSupport.runOnFx(() -> {
                assertEquals(page, w.active().getPath(), "the project's same-named page is not opened");
                assertFalse(w.controller.hasFileOpen(project.resolve("pages/about.html")));
            });
        }
    }

    @Test
    void onlyARootRelativeLinkWaitsForTheSitesRoot() {
        assertTrue(HtmlLinkFollow.rootRelative("/css/site.css", null));
        assertTrue(HtmlLinkFollow.rootRelative(" /x ", ""));
        assertFalse(HtmlLinkFollow.rootRelative("//cdn.example.com/x.js", null), "another host");
        assertFalse(HtmlLinkFollow.rootRelative("css/site.css", null));
        assertTrue(HtmlLinkFollow.rootRelative("css/site.css", "/assets/"), "a root-relative <base>");
        assertFalse(HtmlLinkFollow.rootRelative("css/site.css", "//cdn.example.com/"));
        assertFalse(HtmlLinkFollow.rootRelative("css/site.css", "sub/"));
        assertFalse(HtmlLinkFollow.rootRelative("#top", "/assets/"), "a fragment stays in the page");
        assertFalse(HtmlLinkFollow.rootRelative("https://example.com/", "/assets/"));
    }

    private static void caret(WindowRig w, String needle) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea a = w.active().getFocusedArea();
            a.moveTo(a.getText().indexOf(needle) + 2);
        });
    }

    private static String status(WindowRig w) {
        try {
            return w.status();
        } catch (Exception e) {
            return "";
        }
    }
}
