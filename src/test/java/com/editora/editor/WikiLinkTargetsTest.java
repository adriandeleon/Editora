package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.markdown.MarkdownWikiLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a wiki link leads: the built-in "beside the document" resolver, the seam, and a link's address. */
class WikiLinkTargetsTest {

    @AfterEach
    void restore() {
        WikiLinkTargets.setResolver(null);
    }

    @Test
    void besideTheDocumentAFileOfThatNameIsFoundWithOrWithoutAMarkdownExtension(@TempDir Path dir) throws Exception {
        Path note = Files.writeString(dir.resolve("note.md"), "# Note\n");
        Path other = Files.writeString(dir.resolve("Other Note.markdown"), "x");
        Path pic = Files.writeString(dir.resolve("pic.png"), "x");
        Files.createDirectories(dir.resolve("sub"));
        Path deep = Files.writeString(dir.resolve("sub/deep.md"), "x");

        assertEquals(note, WikiLinkTargets.resolve(dir, "note"));
        assertEquals(note, WikiLinkTargets.resolve(dir, "note.md"));
        assertEquals(note, WikiLinkTargets.resolve(dir, " note "));
        assertEquals(other, WikiLinkTargets.resolve(dir, "Other Note"));
        assertEquals(pic, WikiLinkTargets.resolve(dir, "pic.png"));
        assertEquals(deep, WikiLinkTargets.resolve(dir, "sub/deep"));
        assertNull(WikiLinkTargets.resolve(dir, "nowhere"));
        assertNull(WikiLinkTargets.resolve(dir, "sub"), "a folder is not a note");
        assertNull(WikiLinkTargets.resolve(dir, ""));
        assertNull(WikiLinkTargets.resolve(dir, null));
        assertNull(WikiLinkTargets.resolve(null, "note"), "a document with no file has nothing beside it");
        assertNull(WikiLinkTargets.resolve(dir.resolve("sub"), "../../../etc/passwd-not-there"));
    }

    @Test
    void anInstalledResolverIsAskedInsteadAndAThrowingOneIsUnresolved(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.writeString(dir.resolve("far.md"), "x");
        long before = WikiLinkTargets.epoch();
        WikiLinkTargets.setResolver((folder, target) -> "far".equals(target) ? elsewhere : null);
        assertNotEquals(before, WikiLinkTargets.epoch(), "previews drawn before are out of date");
        assertEquals(elsewhere, WikiLinkTargets.resolve(null, "far"));
        assertNull(WikiLinkTargets.resolve(dir, "note"));

        WikiLinkTargets.setResolver((folder, target) -> {
            throw new IllegalStateException("index not ready");
        });
        assertNull(WikiLinkTargets.resolve(dir, "far"));

        long then = WikiLinkTargets.epoch();
        WikiLinkTargets.changed();
        assertNotEquals(then, WikiLinkTargets.epoch());
        WikiLinkTargets.setResolver(null);
        assertNull(WikiLinkTargets.resolve(null, "far"), "the built-in one again: only beside the document");
        assertEquals(elsewhere, WikiLinkTargets.resolve(dir, "far"));
    }

    @Test
    void aLinksAddressIsItsWrittenFormAndReadsBackAsTargetAndAnchor() {
        String plain = WikiLinkTargets.address(MarkdownWikiLinks.parse("My note", false));
        assertEquals("wikilink:My note", plain);
        assertTrue(WikiLinkTargets.isWikiAddress(plain));
        assertEquals("My note", WikiLinkTargets.targetOf(plain));
        assertNull(WikiLinkTargets.fragmentOf(plain));

        String deep = WikiLinkTargets.address(MarkdownWikiLinks.parse("note#Some Heading!|label", false));
        assertEquals("wikilink:note#Some Heading!", deep);
        assertEquals("note", WikiLinkTargets.targetOf(deep));
        assertEquals("some-heading", WikiLinkTargets.fragmentOf(deep));

        assertEquals(
                "#only-heading",
                WikiLinkTargets.address(MarkdownWikiLinks.parse("#Only Heading", false)),
                "a link into this document is an ordinary anchor link");
        assertFalse(WikiLinkTargets.isWikiAddress("#only-heading"));
        assertFalse(WikiLinkTargets.isWikiAddress("https://example.com"));
        assertFalse(WikiLinkTargets.isWikiAddress(null));
    }

    @Test
    void anEmbedIsAPictureByItsExtension() {
        assertTrue(WikiLinkTargets.isImage("pic.png"));
        assertTrue(WikiLinkTargets.isImage("assets/Photo.JPG"));
        assertTrue(WikiLinkTargets.isImage("d.svg"));
        assertFalse(WikiLinkTargets.isImage("note"));
        assertFalse(WikiLinkTargets.isImage("note.md"));
        assertFalse(WikiLinkTargets.isImage(null));
    }
}
