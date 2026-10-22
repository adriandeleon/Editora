package com.editora.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the source of a Markdown file is coloured as, for the constructs that came out as plain text: YAML
 * front matter, raw HTML, strikethrough, the markers of lists, quotes and tables, and fences in languages
 * whose grammars Editora bundles but the upstream Markdown grammar has no rule for.
 *
 * <p>Two of these pin local changes to the vendored {@code markdown.tmLanguage.json} (see its header).
 */
class MarkdownSourceHighlightTest {

    /** The style class of the first occurrence of {@code needle} in {@code text} ("" = unstyled). */
    private static String style(String text, String needle) {
        int at = text.indexOf(needle);
        assertTrue(at >= 0, "'" + needle + "' is in the text");
        return styleAt(text, at);
    }

    private static String styleAt(String text, int offset) {
        StyleSpans<Collection<String>> spans =
                TextMateHighlighter.compute(text, GrammarRegistry.shared().forFileName("doc.md"));
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (offset < pos + span.getLength()) {
                return String.join(" ", span.getStyle());
            }
            pos += span.getLength();
        }
        return "";
    }

    // --- front matter -----------------------------------------------------------------------------------

    private static final String FRONT =
            "---\ntitle: Front\ntags: [alpha, beta]\n\nnote: \"after a blank line\"\n---\n\n"
                    + "# Heading\n\ntext\n\n---\n\nSetext\n------\n";

    @Test
    void frontMatterIsYamlBetweenItsDelimiters() {
        assertEquals("tag", style(FRONT, "title"));
        assertEquals("string", style(FRONT, "Front"));
        assertEquals("tag", style(FRONT, "tags"));
        assertEquals("string", style(FRONT, "alpha"));
        assertEquals("tag", style(FRONT, "note"), "a blank line does not end the block");
        assertEquals("string", style(FRONT, "after a blank line"));
    }

    @Test
    void theClosingDelimiterIsNotASetextHeadingAndTheDocumentGoesOnAfterIt() {
        int closing = FRONT.indexOf("\n---\n", 4) + 1;
        assertEquals("", styleAt(FRONT, closing), "the closing --- used to be a bold heading underline");
        assertEquals("", styleAt(FRONT, 0));
        assertEquals("heading", style(FRONT, "Heading"));
        assertEquals("", style(FRONT, "text"));
        assertEquals("heading", style(FRONT, "------"), "a real setext underline further down still is one");
    }

    @Test
    void threeDotsAlsoEndFrontMatter() {
        String text = "---\nkey: value\n...\n\n# After\n";
        assertEquals("tag", style(text, "key"));
        assertEquals("heading", style(text, "After"));
    }

    @Test
    void dashesThatAreNotOnTheFirstLineAreNotFrontMatter() {
        String text = "intro\n\n---\nkey: value\n---\n";
        assertEquals("", style(text, "key"), "a paragraph, not YAML");
    }

    // --- raw HTML ---------------------------------------------------------------------------------------

    @Test
    void rawHtmlIsHighlightedAsHtml() {
        String text =
                "<div class=\"box\">\n<b>bold</b>\n</div>\n\nPress <kbd>Ctrl</kbd> or <span style=\"x\">this</span>.\n\n"
                        + "<!-- a comment -->\n";
        assertEquals("tag", style(text, "div"));
        assertEquals("attribute", style(text, "class"));
        assertEquals("string", style(text, "box"));
        assertEquals("tag", style(text, "b>"));
        assertEquals("tag", style(text, "kbd"));
        assertEquals("tag", style(text, "span"));
        assertEquals("", style(text, "Press"));
        assertEquals("", style(text, "Ctrl"), "the text between tags stays prose");
        assertEquals("comment", style(text, "a comment"));
    }

    @Test
    void theEmbeddedHtmlFlavourIsNotALanguageToPickForAFile() {
        assertFalse(GrammarRegistry.shared().availableLanguageNames().contains("html-derivative"));
        assertTrue(GrammarRegistry.shared().availableLanguageNames().contains("html"));
    }

    // --- markers ----------------------------------------------------------------------------------------

    @Test
    void strikethroughQuotesListsAndTablesHaveTheirOwnClasses() {
        String text = "Plain ~~struck~~ text.\n\n> quoted **strong** words\n\n- bullet\n1. numbered\n\n"
                + "| A | B |\n|---|---|\n| c | d |\n";
        assertEquals("strikethrough", style(text, "struck"));
        assertEquals("md-marker", style(text, "> quoted"));
        assertEquals("quote", style(text, "quoted"));
        assertEquals("bold", style(text, "strong"), "markup inside a quote keeps its own class");
        assertEquals("md-marker", style(text, "- bullet"));
        assertEquals("", style(text, "bullet"));
        assertEquals("md-marker", style(text, "1. numbered"));
        assertEquals("md-marker", style(text, "| A"));
        assertEquals("md-marker", style(text, "|---"));
        assertEquals("", style(text, "Plain"));
    }

    @Test
    void theMarkerClassesAreMarkdownOnly() {
        assertEquals(null, TextMateHighlighter.styleForScopes(List.of("punctuation.definition.list.begin.yaml")));
        assertEquals(
                "md-marker", TextMateHighlighter.styleForScopes(List.of("punctuation.definition.list.begin.markdown")));
        assertEquals("md-marker", TextMateHighlighter.styleForScopes(List.of("punctuation.separator.table.markdown")));
    }

    // --- fences -----------------------------------------------------------------------------------------

    @Test
    void fencesInBundledLanguagesAreHighlightedInThatLanguage() {
        String text = "```kotlin\nfun main() {}\n```\n\n```toml\nport = 8080\n```\n\n"
                + "```mermaid\nflowchart LR\n  A --> B\n```\n\n~~~kt\nval x = 1\n~~~\n\n```nosuchlang\nplain words\n```\n";
        assertEquals("keyword", style(text, "fun"));
        assertEquals("function", style(text, "main"));
        assertEquals("number", style(text, "8080"));
        assertEquals("keyword", style(text, "flowchart"));
        assertEquals("operator", style(text, "-->"));
        assertEquals("keyword", style(text, "val"));
        assertEquals("code", style(text, "plain words"), "an unknown language is still code");
    }

    /** VS Code's {@code shellscript} fence id is shell in the source view too. */
    @Test
    void shellscriptFencesAreShell() {
        String text = "```shellscript\nif true; then echo hi; fi\n```\n";
        assertEquals("keyword", style(text, "if"));
        assertEquals("function", style(text, "echo"));
    }

    // --- themes -----------------------------------------------------------------------------------------

    @Test
    void everyStylesheetThatStylesItalicAlsoStylesTheNewClasses() throws IOException {
        Path styles = Path.of("src/main/resources/com/editora/styles");
        List<Path> sheets = new ArrayList<>();
        sheets.add(styles.resolve("syntax.css"));
        try (Stream<Path> themes = Files.list(styles.resolve("editor-themes"))) {
            themes.filter(p -> p.toString().endsWith(".css")).forEach(sheets::add);
        }
        assertTrue(sheets.size() > 5, sheets.toString());
        for (Path sheet : sheets) {
            String css = Files.readString(sheet);
            if (!css.contains("\n.text.italic")) {
                continue;
            }
            for (String cls : List.of("strikethrough", "quote", "md-marker")) {
                assertTrue(css.contains("\n.text." + cls + " "), sheet.getFileName() + " has no .text." + cls);
            }
            assertTrue(
                    css.contains("-fx-strikethrough: true"), sheet.getFileName().toString());
        }
    }
}
