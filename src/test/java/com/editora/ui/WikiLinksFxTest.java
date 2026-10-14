package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where a wiki link leads: through the project's link index, and beside the document without one. */
@Tag("fx")
class WikiLinksFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private MarkdownLinkGraph graph;

    @AfterEach
    void dispose() {
        if (graph != null) {
            graph.dispose();
        }
        WikiLinks.enabled = true;
    }

    @Test
    void aNoteIsFoundAnywhereInTheProjectOnceTheIndexIsBuiltAndBesideTheDocumentBefore(@TempDir Path dir)
            throws Exception {
        Path root = Files.createDirectories(dir.resolve("project")).toRealPath();
        Path doc = Files.writeString(root.resolve("README.md"), "[[setup]] [[twin]]\n");
        Path beside = Files.writeString(root.resolve("Beside.md"), "");
        Path setup =
                Files.writeString(Files.createDirectories(root.resolve("docs")).resolve("Setup.md"), "");
        Files.writeString(Files.createDirectories(root.resolve("a")).resolve("twin.md"), "");
        Files.writeString(Files.createDirectories(root.resolve("b")).resolve("twin.md"), "");
        Path text = Files.writeString(root.resolve("docs/notes.txt"), "");

        // No project knows this document yet: only its own folder is looked in, names compared without case.
        assertEquals(beside, WikiLinks.resolve(doc, "beside"));
        assertNull(WikiLinks.resolve(doc, "setup"));
        assertEquals("Beside.md", WikiLinks.relativePath(doc, "BESIDE"));

        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public Settings settings() {
                return new Settings();
            }
        };
        graph = new MarkdownLinkGraph(host, () -> root, () -> true);
        assertNull(WikiLinks.resolve(doc, "setup"), "asking starts the index; it has not landed yet");
        FxTestSupport.callOnFx(() -> graph.submit(ix -> {})).get(20, TimeUnit.SECONDS);

        assertEquals(setup, WikiLinks.resolve(doc, "setup"));
        assertEquals(setup, WikiLinks.resolve(doc, "docs/setup"));
        assertEquals(text, WikiLinks.resolve(doc, "notes.txt"), "any file may be named in full");
        assertEquals("docs/Setup.md", WikiLinks.relativePath(doc, "Setup"), "what a Markdown link to it carries");
        assertEquals("../README.md", WikiLinks.relativePath(setup, "readme"));
        assertNull(WikiLinks.resolve(doc, "twin"), "two notes of that name: ambiguous");
        assertNull(WikiLinks.relativePath(doc, "nowhere"));
        assertEquals(doc, WikiLinks.resolve(doc, ""), "[[#heading]] is this document");

        WikiLinks.enabled = false;
        assertNull(WikiLinks.resolve(doc, "setup"), "switched off, nothing is a wiki link");
    }

    @Test
    void theWikiFormIsParsedIntoTargetAndHeading() {
        assertEquals(new WikiLinks.Parsed("note", ""), WikiLinks.parse("[[note]]"));
        assertEquals(new WikiLinks.Parsed("my note", "A heading"), WikiLinks.parse("[[ my note # A heading ]]"));
        assertEquals(new WikiLinks.Parsed("", "top"), WikiLinks.parse("[[#top|label]]"));
        assertEquals(true, WikiLinks.isWikiForm("[[a]]"));
        assertEquals(false, WikiLinks.isWikiForm("[a](b)"));
        assertEquals(false, WikiLinks.isWikiForm("[[]]"));
    }
}
