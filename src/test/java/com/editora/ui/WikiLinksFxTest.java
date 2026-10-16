package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        CompletableFuture<Void> landed = new CompletableFuture<>();
        graph.addListener(() -> landed.complete(null));
        // Asking off the FX thread hands the build to it. Held, so the build cannot land (a walk of five files
        // takes a millisecond) before this thread reads the answer: under load it sometimes did.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Platform.runLater(() -> {
            held.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(held.await(20, TimeUnit.SECONDS));
        try {
            assertNull(WikiLinks.resolve(doc, "setup"), "asking starts the index; it has not landed yet");
        } finally {
            release.countDown();
        }
        landed.get(20, TimeUnit.SECONDS); // nothing else asked for it

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
    void aDocumentInANestedProjectIsAnsweredByTheInnermostProject(@TempDir Path dir) throws Exception {
        // Two windows: one on a folder, one on a project inside it. Each has its index; the outer one also
        // covers the inner project's files, but a document in the inner project belongs to the inner one. The
        // first live index that contained the document used to answer, whichever window was opened first.
        Path outerRoot = dir.toRealPath();
        Path root = Files.createDirectories(outerRoot.resolve("project"));
        Path doc = Files.writeString(root.resolve("README.md"), "[[setup]]\n");
        Path inner =
                Files.writeString(Files.createDirectories(root.resolve("docs")).resolve("Setup.md"), "");
        Files.writeString(
                Files.createDirectories(outerRoot.resolve("elsewhere")).resolve("Setup.md"), "");
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public Settings settings() {
                return new Settings();
            }
        };
        MarkdownLinkGraph outer = new MarkdownLinkGraph(host, () -> outerRoot, () -> true);
        try {
            FxTestSupport.callOnFx(() -> outer.submit(ix -> {})).get(20, TimeUnit.SECONDS);
            graph = new MarkdownLinkGraph(host, () -> root, () -> true);
            FxTestSupport.callOnFx(() -> graph.submit(ix -> {})).get(20, TimeUnit.SECONDS);

            assertEquals(inner, WikiLinks.resolve(doc, "setup"), "the outer folder has two Setup.md; the project one");
        } finally {
            outer.dispose();
        }
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
