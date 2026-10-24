package com.editora.editor;

import java.util.List;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.language.LanguageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which file names open as Markdown — with its grammar, and so its preview, outline and lint. */
class MarkdownFileNamesTest {

    @Test
    void everyCommonMarkdownExtensionIsMarkdownInAnyCase() {
        for (String name : List.of(
                "a.md",
                "a.markdown",
                "a.mdown",
                "a.mkd",
                "a.mkdn",
                "a.mdwn",
                "page.mdx",
                "report.Rmd",
                "paper.qmd",
                "README.MD",
                "docs/Guide.Markdown")) {
            assertEquals("markdown", LanguageRegistry.forFileName(name), name);
            assertNotNull(GrammarRegistry.shared().forFileName(name), name + " has the Markdown grammar");
            assertSame(
                    GrammarRegistry.shared().forFileName("a.md"),
                    GrammarRegistry.shared().forFileName(name),
                    name);
        }
    }

    @Test
    void lookalikesAreNotMarkdown() {
        for (String name : List.of("README", "notes.txt", "a.mdb", "a.md.bak", "a.rst", "a.adoc")) {
            assertEquals(LanguageRegistry.plaintext(), LanguageRegistry.forFileName(name), name);
        }
    }
}
