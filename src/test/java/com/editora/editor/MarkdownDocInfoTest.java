package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.markdown.MarkdownToc;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownDocInfoTest {

    private static Node parse(String md) {
        return MarkdownRenderer.parseToDocument(md);
    }

    @Test
    void theTitleIsTheFrontMattersThenTheFirstH1ThenTheFallback() {
        assertEquals("FM", MarkdownDocInfo.title(parse("---\ntitle: FM\n---\n\n# H1\n"), "file"));
        assertEquals("Quoted: yes", MarkdownDocInfo.title(parse("---\ntitle: 'Quoted: yes'\n---\n"), "file"));
        assertEquals("H1 with code", MarkdownDocInfo.title(parse("## sub\n\n# H1 *with* `code`\n\n# later\n"), "file"));
        assertEquals("file", MarkdownDocInfo.title(parse("---\ntitle:\n---\n\n## only a sub-heading\n"), "file"));
        assertNull(MarkdownDocInfo.title(parse("text\n"), null));
        assertEquals(
                "Two lines", MarkdownDocInfo.title(parse("Two\nlines\n===\n"), "file"), "a break reads as a space");
    }

    @Test
    void theLanguageIsATagFromTheFrontMatterOrTheFallback() {
        assertEquals("es", MarkdownDocInfo.language(parse("---\nlang: es\n---\n"), null));
        assertEquals("pt-BR", MarkdownDocInfo.language(parse("---\nlanguage: pt_BR\n---\n"), null));
        assertEquals("de", MarkdownDocInfo.language(parse("---\nLang: \"de\"\n---\n"), null));
        assertNull(MarkdownDocInfo.language(parse("---\nlang: not a tag!\n---\n"), null));
        assertEquals("en", MarkdownDocInfo.language(parse("# no front matter\n"), "en"));
    }

    @Test
    void headingAnchorsAreTheTableOfContentsSequenceOverTopLevelHeadings() {
        String md = "# Set up\n\n## Set up\n\n#\n\n> # Quoted\n\n### `code` & [link](x.md) text\n\n# Set up\n";
        Node ast = parse(md);
        Map<Heading, String> anchors = MarkdownDocInfo.headingAnchors(ast);
        List<String> inOrder = new ArrayList<>();
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h) {
                inOrder.add(String.valueOf(anchors.get(h)));
            }
        }
        assertEquals(List.of("set-up", "set-up-1", "null", "code--link-text", "set-up-2"), inOrder);
        assertEquals(4, anchors.size(), "the empty heading and the quoted one have none");
        assertEquals("code--link-text", MarkdownToc.slug("code & link text"), "the slug of what the reader sees");
    }

    @Test
    void aFragmentIsRecognisedAndFolded() {
        assertEquals("set-up", MarkdownDocInfo.fragment("#Set-Up"));
        assertNull(MarkdownDocInfo.fragment("#"));
        assertNull(MarkdownDocInfo.fragment("guide.md#x"));
        assertNull(MarkdownDocInfo.fragment(null));
    }
}
