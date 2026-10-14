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

    @Test
    void aColonOffersEmojiShortcodesPrefixMatchesFirst() {
        MarkdownCompletions.Offer offer = at("Nice :smil");
        assertEquals(5, offer.start(), "the colon is replaced too");
        List<String> inserts = inserts(offer);
        assertEquals(":smile:", inserts.get(0));
        assertTrue(inserts.contains(":smiley:") && inserts.contains(":sweat_smile:"), inserts.toString());
        assertTrue(inserts.indexOf(":smiley:") < inserts.indexOf(":sweat_smile:"), "prefix matches come first");
        assertEquals("\uD83D\uDE04", offer.items().get(0).detail());
        assertNull(at("at 10:30"), "a time is not a shortcode");
        assertNull(at("Nice :zzzzqq"), "nothing matches");
    }

    @Test
    void doubleBracketsOfferTheProjectsNotesAndThenTheirHeadings() throws Exception {
        Path root = doc.getParent();
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other/guide.md"), "# Other guide\n");
        com.editora.markdown.WikiTargets targets = new com.editora.markdown.WikiTargets(
                root,
                List.of(
                        doc,
                        root.resolve("docs/guide.md"),
                        root.resolve("other/guide.md"),
                        root.resolve("docs/my notes.md"),
                        root.resolve("CHANGELOG.md"),
                        root.resolve("diagram.png")));
        MarkdownCompletions.setWikiSource(d -> targets);
        try {
            MarkdownCompletions.Offer all = at("See [[");
            assertEquals(6, all.start());
            assertEquals(
                    List.of("CHANGELOG", "docs/guide", "other/guide", "my notes", "diagram.png"),
                    inserts(all),
                    "notes by name, then pictures; a name two notes share is written with its folder; not the document itself");

            assertEquals(List.of("my notes"), inserts(at("See [[not")));
            assertEquals(List.of("docs/guide", "other/guide"), inserts(at("![[gui")));
            assertNull(at("See [[zzz"));

            MarkdownCompletions.Offer headings = at("[[docs/guide#");
            assertEquals(List.of("Guide", "Set up", "Using it"), inserts(headings), "plain titles, each once");
            assertEquals(List.of("Set up"), inserts(at("[[docs/guide#set")));
            assertNull(at("[[guide#"), "an ambiguous note has no headings to offer");
            assertEquals(13, headings.start());
        } finally {
            MarkdownCompletions.setWikiSource(null);
        }
        assertNull(at("See [["), "no project, no notes");
    }
}
