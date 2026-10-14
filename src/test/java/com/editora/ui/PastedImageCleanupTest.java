package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.i18n.Messages;
import com.editora.io.Trash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PastedImageCleanupTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void messages() {
        Messages.init("en");
    }

    /** A bin that remembers what it was given and moves it out of the way. */
    private static final class Bin implements Trash.Bin {
        final List<Path> trashed = new ArrayList<>();
        boolean available = true;

        @Override
        public boolean accepts(Path file) {
            return available;
        }

        @Override
        public void trash(Path file) throws IOException {
            Files.delete(file);
            trashed.add(file);
        }
    }

    private Path image(String name) throws IOException {
        Path assets = Files.createDirectories(dir.resolve("assets"));
        return Files.write(assets.resolve(name), new byte[] {1});
    }

    @Test
    void anImageTheSavedDocumentNoLongerMentionsGoesToTheTrash() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path used = image("kept.png");
        Path unused = image("gone.png");
        Set<Path> created = new LinkedHashSet<>(List.of(used, unused));
        Bin bin = new Bin();
        String note = PastedImageCleanup.afterSave(doc, "see ![x](assets/kept.png)\n", created, n -> false, bin);
        assertEquals(List.of(unused), bin.trashed);
        assertTrue(Files.exists(used));
        assertEquals(Set.of(used), created, "the trashed file is no longer ours to watch");
        assertTrue(note.contains(Messages.tr("status.markdown.imagesTrashed", 1)), note);
    }

    @Test
    void onlyFilesOnTheSessionsOwnListAreEverTouched() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path theirs = image("old-photo.png"); // in assets/, unreferenced, but not created by a paste
        Bin bin = new Bin();
        assertEquals("", PastedImageCleanup.afterSave(doc, "no images\n", new LinkedHashSet<>(), n -> false, bin));
        assertTrue(Files.exists(theirs));
        assertTrue(bin.trashed.isEmpty());
    }

    @Test
    void anImageAnotherDocumentUsesStays() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path movedToOpenBuffer = image("a.png");
        Path movedToSibling = image("b.png");
        Files.writeString(dir.resolve("other.md"), "![moved here](assets/b.png)\n");
        Set<Path> created = new LinkedHashSet<>(List.of(movedToOpenBuffer, movedToSibling));
        Bin bin = new Bin();
        String note = PastedImageCleanup.afterSave(doc, "empty\n", created, name -> name.equals("a.png"), bin);
        assertEquals("", note);
        assertTrue(bin.trashed.isEmpty());
        assertEquals(2, created.size(), "still watched: a later save may find them unused");
    }

    @Test
    void withoutATrashTheFileStaysAndTheMessageSaysSo() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path unused = image("gone.png");
        Set<Path> created = new LinkedHashSet<>(List.of(unused));
        Bin bin = new Bin();
        bin.available = false;
        String note = PastedImageCleanup.afterSave(doc, "empty\n", created, n -> false, bin);
        assertTrue(Files.exists(unused), "never deleted for good");
        assertTrue(note.contains(Messages.tr("status.markdown.imagesNotTrashed", 1)), note);
        assertFalse(created.isEmpty());

        Trash.Bin failing = new Trash.Bin() {
            @Override
            public boolean accepts(Path file) {
                return true;
            }

            @Override
            public void trash(Path file) throws IOException {
                throw new IOException("no");
            }
        };
        assertTrue(PastedImageCleanup.afterSave(doc, "empty\n", created, n -> false, failing)
                .contains(Messages.tr("status.markdown.imagesNotTrashed", 1)));
        assertTrue(Files.exists(unused));
    }

    @Test
    void aFileThatIsGoneAlreadyIsForgotten() throws Exception {
        Path doc = dir.resolve("notes.md");
        Files.createDirectories(dir.resolve("assets"));
        Set<Path> created = new LinkedHashSet<>(List.of(dir.resolve("assets/never.png")));
        assertEquals("", PastedImageCleanup.afterSave(doc, "empty\n", created, n -> false, new Bin()));
        assertTrue(created.isEmpty());
    }
}
