package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.completion.Completion;
import com.editora.completion.CompletionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownCompletionsTest {

    @TempDir
    Path dir;

    private Path doc;
    private MarkdownCompletions completions;

    @BeforeEach
    void files() throws Exception {
        doc = dir.resolve("README.md");
        Files.writeString(doc, "");
        Files.createDirectories(dir.resolve("docs"));
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve("docs/guide.md"), "# Guide\n\n## Set up\n\n## Set up\n\n## Using `it`\n");
        Files.writeString(dir.resolve("docs/my notes.md"), "# Notes\n");
        Files.writeString(dir.resolve("diagram.png"), "x");
        Files.writeString(dir.resolve("data.csv"), "x");
        Files.writeString(dir.resolve("CHANGELOG.md"), "# Changes\n");
        CompletionProvider base = (lang, dict, prefix, wide, prose) ->
                "mermaid".equals(lang) ? List.of(Completion.word("flowchart", "mermaid")) : List.of();
        completions = (MarkdownCompletions) MarkdownCompletions.provider(base, () -> doc);
    }

    /** The offer with the caret at the end of the document's last line. */
    private MarkdownCompletions.Offer at(String document) {
        String[] lines = document.split("\n", -1);
        int line = lines.length - 1;
        String last = lines[line];
        int start = last.length();
        while (start > 0 && (Character.isLetterOrDigit(last.charAt(start - 1)) || last.charAt(start - 1) == '_')) {
            start--;
        }
        return completions.offer(document, line, last.length(), last.substring(start), "en_US", false);
    }

    private static List<String> inserts(MarkdownCompletions.Offer offer) {
        return offer.items().stream().map(Completion::insert).toList();
    }

    @Test
    void aLinkTargetOffersTheFolderFoldersFirstThenMarkdownThenImages() {
        MarkdownCompletions.Offer offer = at("See [the guide](");
        assertEquals(List.of("docs/", "CHANGELOG.md", "diagram.png", "data.csv"), inserts(offer));
        assertEquals(16, offer.start());
        assertEquals(
                List.of("docs/", "diagram.png", "data.csv"), inserts(at("See [x](d")), "narrowed by what is typed");
    }

    @Test
    void aPathGoesIntoFoldersAndEscapesSpacesUnlessInAngleBrackets() {
        assertEquals(List.of("docs/guide.md", "docs/my%20notes.md"), inserts(at("[x](docs/")));
        assertEquals(List.of("docs/my%20notes.md"), inserts(at("[x](docs/my")));
        assertEquals(List.of("docs/guide.md", "docs/my notes.md"), inserts(at("[x](<docs/")));
        assertNull(at("[x](nosuchfolder/"), "a folder that is not there offers nothing");
        assertNull(at("[x](/abs"), "nor a path this cannot resolve");
    }

    @Test
    void hiddenEntriesAndTheDocumentItselfAreLeftOut() {
        List<String> all = inserts(at("[x]("));
        assertTrue(all.stream().noneMatch(s -> s.startsWith(".git")), all.toString());
        assertTrue(all.stream().noneMatch(s -> s.equals("README.md")), all.toString());
        assertEquals(List.of(".git/"), inserts(at("[x](.g")), "unless a dot is typed");
    }

    @Test
    void anAnchorOffersThisDocumentsHeadingsOrTheLinkedFiles() {
        String md = "# Title\n\n## Set up\n\ntext\n\n## Set up\n\n[go](#";
        assertEquals(List.of("title", "set-up", "set-up-1"), inserts(at(md)));
        assertEquals(List.of("set-up", "set-up-1"), inserts(at(md + "se")));
        MarkdownCompletions.Offer other = at("[go](docs/guide.md#");
        assertEquals(List.of("guide", "set-up", "set-up-1", "using-it"), inserts(other));
        assertEquals("### Using it".substring(1), other.items().get(3).label());
        assertEquals("#using-it", other.items().get(3).detail());
        assertNull(at("[go](docs/missing.md#"));
        assertNull(at("[go](diagram.png#"), "not a Markdown file");
    }

    @Test
    void referenceLabelsComeFromTheDefinitions() {
        String md = "[Home Page]: https://example.com\n[api]: docs/guide.md\n[^note]: a footnote\n\nSee [text][";
        MarkdownCompletions.Offer offer = at(md);
        assertEquals(List.of("Home Page", "api"), inserts(offer));
        assertEquals("https://example.com", offer.items().get(0).detail());
        assertEquals(List.of("Home Page"), inserts(at(md + "ho")));
        assertNull(at("no definitions here\n\nSee [text]["));
    }

    @Test
    void anOpeningFenceOffersLanguagesAndAClosingOneDoesNot() {
        List<String> j = inserts(at("text\n\n```j"));
        assertTrue(j.containsAll(List.of("java", "javascript", "js", "json")), j.toString());
        assertEquals(List.of("javascript"), inserts(at("```javas")));
        assertTrue(inserts(at("```")).contains("mermaid"));
        assertNull(at("```java\ncode\n```"), "the closing fence takes no language");
        assertNull(at("```python"), "what is typed is already a language");
    }

    @Test
    void mermaidKeywordsInsideAMermaidFenceOnly() {
        MarkdownCompletions.Offer offer = at("```mermaid\nflow");
        assertEquals(List.of("flowchart"), inserts(offer));
        assertEquals(-1, offer.start(), "they replace the word, the usual way");
        assertNull(at("```java\nflow"));
        assertNull(at("```mermaid\n"), "nothing typed yet");
    }

    @Test
    void codeFrontMatterAndProseOfferNothing() {
        assertNull(completions.offer("---\nlink: [x](\n---\n", 1, 10, "", "en_US", false), "front matter");
        assertNull(at("```md\n[x]("));
        assertNull(at("    [x]("));
        assertNull(at("just prose"));
        assertNull(completions.offer("use `[x](` here", 0, 9, "", "en_US", false), "inside a code span");
    }

    @Test
    void anUnsavedDocumentStillOffersWhatNeedsNoFolder() {
        MarkdownCompletions unsaved =
                (MarkdownCompletions) MarkdownCompletions.provider((a, b, c, d, e) -> List.of(), () -> null);
        assertNull(unsaved.offer("[x](", 0, 4, "", "en_US", false));
        assertEquals(
                1,
                unsaved.offer("# One\n\n[x](#", 2, 5, "", "en_US", false)
                        .items()
                        .size());
        assertNull(MarkdownCompletions.provider(null, () -> null));
        CompletionProvider base = (a, b, c, d, e) -> List.of();
        assertSame(List.of(), MarkdownCompletions.provider(base, () -> null).complete("java", "en", "x", "x", false));
    }
}
