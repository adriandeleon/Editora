package com.editora.lsp;

import java.util.List;

import com.editora.editor.MarkdownRenderer;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hover popup always parses Markdown, so every shape a server sends must arrive as Markdown. */
class HoverMarkdownTest {

    private static String html(String markdown) {
        return org.commonmark.renderer.html.HtmlRenderer.builder()
                .build()
                .render(MarkdownRenderer.parseToDocument(markdown));
    }

    /** Pyright's plaintext hover: {@code __init__} was shown as a bold "init", and the lines were folded. */
    @Test
    void plaintextContentIsNotInterpretedAsMarkdown() {
        var hover = new Hover(new MarkupContent(
                "plaintext", "(method) def __init__(my_value: int) -> None\n  indented <T>\n\nCreate *one* Foo."));

        String rendered = html(HoverMarkdown.of(hover));

        assertTrue(rendered.contains("def __init__(my_value: int) -&gt; None"), rendered);
        assertTrue(rendered.contains("Create *one* Foo."), rendered);
        assertTrue(rendered.contains("&lt;T&gt;"), rendered);
        assertTrue(rendered.contains("<br"), "a line break inside a paragraph is kept: " + rendered);
        assertTrue(rendered.contains("\u00a0\u00a0indented"), "indentation is kept: " + rendered);
        assertFalse(rendered.contains("<strong>") || rendered.contains("<em>"), rendered);
    }

    @Test
    void markdownContentIsUsedAsIs() {
        var hover = new Hover(new MarkupContent("markdown", "```python\ndef f()\n```\n---\nCreate \\*one\\*"));
        assertEquals("```python\ndef f()\n```\n---\nCreate \\*one\\*", HoverMarkdown.of(hover));
    }

    /** jdtls sends its signature as {@code MarkedString{language: java}}: that is code, not prose. */
    @Test
    void aMarkedStringWithALanguageIsFenced() {
        var hover = new Hover(List.of(
                Either.forRight(new MarkedString("java", "Map<String, List<Integer>> demo.Repo.index(Path root)")),
                Either.forLeft("Builds the **index**.")));

        String markdown = HoverMarkdown.of(hover);

        assertEquals(
                "```java\nMap<String, List<Integer>> demo.Repo.index(Path root)\n```\n\nBuilds the **index**.",
                markdown);
        String rendered = html(markdown);
        assertTrue(rendered.contains("List&lt;Integer&gt;&gt; demo.Repo"), rendered);
        assertTrue(rendered.contains("<strong>index</strong>"), "a bare MarkedString is Markdown: " + rendered);
    }

    @Test
    void aFenceIsLongerThanAnyBacktickRunInTheCode() {
        assertEquals("````md\na ``` b\n````", HoverMarkdown.fenced("md", "a ``` b"));
    }

    @Test
    void nothingToShowIsTheEmptyString() {
        assertEquals("", HoverMarkdown.of(null));
        assertEquals("", HoverMarkdown.of(new Hover()));
    }
}
