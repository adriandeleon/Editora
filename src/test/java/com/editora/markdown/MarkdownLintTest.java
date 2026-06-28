package com.editora.markdown;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.editora.markdown.MarkdownLint.Diagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure Markdown linter (one assertion group per rule). */
class MarkdownLintTest {

    private static Set<String> codes(String md) {
        return MarkdownLint.lint(md).stream().map(Diagnostic::code).collect(Collectors.toSet());
    }

    private static boolean has(String md, String code) {
        return MarkdownLint.lint(md).stream().anyMatch(d -> d.code().equals(code));
    }

    @Test
    void md009TrailingWhitespace() {
        List<Diagnostic> d = MarkdownLint.lint("ok line\ntrailing  \n");
        Diagnostic td =
                d.stream().filter(x -> x.code().equals("MD009")).findFirst().orElseThrow();
        assertEquals(2, td.line());
        assertEquals(9, td.column()); // "trailing" is 8 chars; first trailing space is column 9
    }

    @Test
    void md012MultipleBlankLines() {
        assertTrue(has("a\n\n\nb\n", "MD012"));
        assertFalse(has("a\n\nb\n", "MD012"));
    }

    @Test
    void md018And019HeadingSpacing() {
        assertTrue(has("#Heading\n", "MD018"));
        assertTrue(has("#   Heading\n", "MD019"));
        assertFalse(has("# Heading\n", "MD018"));
        assertFalse(has("# Heading\n", "MD019"));
    }

    @Test
    void md025MultipleTopLevelHeadings() {
        assertTrue(has("# One\n\n# Two\n", "MD025"));
        assertFalse(has("# One\n\n## Two\n", "MD025"));
    }

    @Test
    void md040FencedCodeNeedsLanguage() {
        assertTrue(has("```\ncode\n```\n", "MD040"));
        assertFalse(has("```java\ncode\n```\n", "MD040"));
    }

    @Test
    void md047FinalNewline() {
        assertTrue(has("# Title", "MD047")); // no trailing newline
        assertFalse(has("# Title\n", "MD047")); // exactly one
        assertTrue(has("# Title\n\n", "MD047")); // more than one
    }

    @Test
    void md052BrokenReferenceLink() {
        assertTrue(has("See [text][missing] here.\n", "MD052"));
        assertFalse(has("See [text][ok] here.\n\n[ok]: https://example.com\n", "MD052"));
    }

    @Test
    void noFalsePositivesInCleanDocument() {
        String md = "# Title\n\nSome text with a [link][ref].\n\n```java\nint x = 1;\n```\n\n[ref]: https://x.io\n";
        assertTrue(codes(md).isEmpty(), () -> "unexpected: " + codes(md));
    }

    @Test
    void emptyAndNull() {
        assertTrue(MarkdownLint.lint(null).isEmpty());
        assertTrue(MarkdownLint.lint("").isEmpty());
    }

    @Test
    void md001HeadingIncrement() {
        assertTrue(has("# A\n\n### C\n", "MD001"));
        assertFalse(has("# A\n\n## B\n", "MD001"));
    }

    @Test
    void md010HardTabs() {
        assertTrue(has("text\twith tab\n", "MD010"));
        assertFalse(has("text with spaces\n", "MD010"));
    }

    @Test
    void md022HeadingsSurroundedByBlanks() {
        assertTrue(has("# A\ntext\n", "MD022")); // heading not followed by a blank line
        assertFalse(has("# A\n\ntext\n", "MD022"));
    }

    @Test
    void md023HeadingIndent() {
        assertTrue(has("  ## Indented\n", "MD023"));
        assertFalse(has("## Flush\n", "MD023"));
    }

    @Test
    void md026HeadingTrailingPunctuation() {
        assertTrue(has("# Title.\n", "MD026"));
        assertTrue(has("## Done! ##\n", "MD026")); // closing-hash form
        assertFalse(has("# Title\n", "MD026"));
    }

    @Test
    void md031FencedCodeSurroundedByBlanks() {
        assertTrue(has("text\n\n```java\ncode\n```\ntext\n", "MD031")); // no blank after the fence
        assertFalse(has("text\n\n```java\ncode\n```\n\ntext\n", "MD031"));
    }

    @Test
    void md034BareUrl() {
        assertTrue(has("See https://example.com here.\n", "MD034"));
        assertFalse(has("See [x](https://example.com) here.\n", "MD034"));
        assertFalse(has("See <https://example.com> here.\n", "MD034"));
    }

    @Test
    void md041FirstLineHeading() {
        assertTrue(has("Not a heading\n", "MD041"));
        assertFalse(has("# Heading\n", "MD041"));
        assertFalse(has("---\ntitle: x\n---\n\n# Heading\n", "MD041")); // front matter is skipped
    }

    @Test
    void perRuleDisabledSuppressesEmission() {
        assertFalse(MarkdownLint.lint("#Heading\n", Set.of("MD018")).stream()
                .anyMatch(d -> d.code().equals("MD018")));
    }

    @Test
    void inlineDisableDirectiveSuppresses() {
        assertFalse(MarkdownLint.lint("<!-- markdownlint-disable MD018 -->\n#Heading\n").stream()
                .anyMatch(d -> d.code().equals("MD018")));
        assertFalse(MarkdownLint.lint("<!-- markdownlint-disable-next-line MD018 -->\n#Heading\n").stream()
                .anyMatch(d -> d.code().equals("MD018")));
        assertTrue(has("#Heading\n", "MD018")); // still flagged without the directive
    }
}
