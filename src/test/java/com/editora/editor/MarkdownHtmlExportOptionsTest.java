package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exported page's options: coloured code, embedded images, and A's new syntax in the page. */
class MarkdownHtmlExportOptionsTest {

    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @TempDir
    Path dir;

    private static MarkdownHtmlExport.Options options(boolean highlight, boolean embed, Path base) {
        return new MarkdownHtmlExport.Options(highlight, embed, base, null);
    }

    @Test
    void fencedCodeIsColouredInlineWithoutScriptWhenAsked() {
        String md = "```java\nclass A { int x = 1; } // note\n```\n\n```nolanguage\nplain <b>\n```\n";
        String plain = MarkdownHtmlExport.toHtml(md, "t", false);
        assertTrue(plain.contains("<pre><code class=\"language-java\">class A"), plain);
        assertFalse(plain.contains("md-highlight"));

        String coloured = MarkdownHtmlExport.toHtml(md, "t", false, options(true, false, null));
        assertTrue(coloured.contains("<pre class=\"md-highlight\""), coloured);
        assertTrue(coloured.contains("<span style=\"color:#"), "token colours are inline styles: " + coloured);
        assertTrue(coloured.replaceAll("<[^>]+>", "").contains("class A { int x = 1; } // note"), coloured);
        assertTrue(
                coloured.contains("<pre><code class=\"language-nolanguage\">plain &lt;b&gt;"),
                "unknown language: plain");
        assertFalse(coloured.contains("<script"), "no script in the page");
        assertTrue(coloured.contains("script-src 'none'"));
    }

    @Test
    void localImagesAreEmbeddedAsDataUrisAndMissingOnesKeepTheirAddress() throws Exception {
        Files.write(dir.resolve("pic.png"), PNG);
        Files.writeString(dir.resolve("art.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
        String md = "![a](pic.png) ![b](art.svg) ![c](missing.png)\n";

        String linked = MarkdownHtmlExport.toHtml(md, "t", false, options(false, false, dir));
        assertTrue(linked.contains("src=\"pic.png\""), linked);

        String embedded = MarkdownHtmlExport.toHtml(md, "t", false, options(false, true, dir));
        assertTrue(
                embedded.contains(
                        "src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(PNG) + "\""),
                embedded);
        assertTrue(embedded.contains("src=\"data:image/svg+xml;base64,"), embedded);
        assertTrue(embedded.contains("src=\"missing.png\""), "what cannot be read stays a link: " + embedded);
        assertTrue(embedded.contains("alt=\"a\""));
    }

    @Test
    void linksAreStillSanitizedAndOnlyImageDataUrisPass() {
        String html = MarkdownHtmlExport.toHtml(
                "[x](javascript:alert(1)) [d](data:text/html;base64,AAAA) ![i](data:text/html;base64,AAAA)\n",
                "t",
                false,
                options(true, true, null));
        assertFalse(html.contains("javascript:"), html);
        assertFalse(html.contains("data:text/html"), html);
    }

    @Test
    void theNewSyntaxIsInThePageWithItsStyles() {
        String html = MarkdownHtmlExport.toHtml(
                "> [!TIP]\n> Try it.\n\nH~2~O m^2^ ==hot== :rocket:\n\nTerm\n: Meaning.\n",
                "t",
                false,
                options(true, true, null));
        assertTrue(html.contains("class=\"markdown-alert markdown-alert-tip\""), html);
        assertTrue(html.contains(".markdown-alert-tip"), "styled by the page's stylesheet");
        assertTrue(
                html.contains("<sub>2</sub>") && html.contains("<sup>2</sup>") && html.contains("<mark>hot</mark>"),
                html);
        assertTrue(html.contains("🚀"), html);
        assertTrue(html.contains("<dt>Term</dt>") && html.contains("<dd>"), html);
    }

    @Test
    void onlyDocumentsThatNeedFilesOrToolsAreExportedInTheBackground() {
        var embed = options(true, true, dir);
        assertFalse(MarkdownHtmlExport.readsFilesOrRunsTools("# Text\n\n```java\nx\n```\n", embed));
        assertTrue(MarkdownHtmlExport.readsFilesOrRunsTools("![a](b.png)", embed));
        assertFalse(MarkdownHtmlExport.readsFilesOrRunsTools("![a](b.png)", options(true, false, dir)));
        var withCli = new MarkdownHtmlExport.Options(false, false, null, List.of("mmdc"));
        assertTrue(MarkdownHtmlExport.readsFilesOrRunsTools("```mermaid\ngraph TD\n```\n", withCli));
        assertFalse(MarkdownHtmlExport.readsFilesOrRunsTools("```mermaid\ngraph TD\n```\n", embed));
        assertEquals("image/jpeg", MarkdownHtmlExport.mediaType(new byte[] {(byte) 0xff, (byte) 0xd8, 0}, "x"));
    }
}
