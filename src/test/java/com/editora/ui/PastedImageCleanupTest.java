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

    /** The three steps in a row, as the window runs them across its threads. */
    private static PastedImageCleanup.Outcome run(
            Path doc, String text, Set<Path> created, java.util.function.Predicate<Path> usedElsewhere, Trash.Bin bin) {
        List<Path> candidates = PastedImageCleanup.candidates(doc, text, created, usedElsewhere);
        List<Path> unused = PastedImageCleanup.unusedOnDisk(doc, candidates, () -> false);
        PastedImageCleanup.Outcome outcome = PastedImageCleanup.trash(unused, bin, () -> false);
        created.removeAll(outcome.trashed());
        created.removeAll(outcome.gone());
        return outcome;
    }

    @Test
    void anImageTheSavedDocumentNoLongerMentionsGoesToTheTrash() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path used = image("kept.png");
        Path unused = image("gone.png");
        Set<Path> created = new LinkedHashSet<>(List.of(used, unused));
        Bin bin = new Bin();
        PastedImageCleanup.Outcome outcome = run(doc, "see ![x](assets/kept.png)\n", created, n -> false, bin);
        assertEquals(List.of(unused), bin.trashed);
        assertTrue(Files.exists(used));
        assertEquals(Set.of(used), created, "the trashed file is no longer ours to watch");
        assertEquals(Messages.tr("status.markdown.imagesTrashed", 1), PastedImageCleanup.note(outcome));
    }

    @Test
    void onlyFilesOnTheSessionsOwnListAreEverTouched() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path theirs = image("old-photo.png"); // in assets/, unreferenced, but not created by a paste
        Bin bin = new Bin();
        assertEquals("", PastedImageCleanup.note(run(doc, "no images\n", new LinkedHashSet<>(), n -> false, bin)));
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
        assertEquals(
                List.of(movedToSibling),
                PastedImageCleanup.candidates(doc, "empty\n", created, file -> file.equals(movedToOpenBuffer)),
                "an open buffer's mention is settled without touching the disk");
        assertEquals(
                "", PastedImageCleanup.note(run(doc, "empty\n", created, file -> file.equals(movedToOpenBuffer), bin)));
        assertTrue(bin.trashed.isEmpty());
        assertEquals(2, created.size(), "still watched: a later save may find them unused");
    }

    @Test
    void anotherOpenDocumentMentionsAFileByThePathFromItsOwnFolder() throws Exception {
        Path file = image("shot.png");
        assertTrue(PastedImageCleanup.mentions(dir.resolve("other.md"), "![x](assets/shot.png)", file));
        assertTrue(PastedImageCleanup.mentions(dir.resolve("sub/deep.md"), "![x](../assets/shot.png)", file));
        assertFalse(
                PastedImageCleanup.mentions(dir.resolve("sub/deep.md"), "![x](assets/shot.png)", file),
                "a picture of the same name in another folder is another picture");
        assertTrue(PastedImageCleanup.mentions(null, "![x](assets/shot.png)", file), "never saved: the name counts");
        assertFalse(PastedImageCleanup.mentions(null, "nothing", file));
    }

    @Test
    void withoutATrashTheFileStaysAndTheMessageSaysSo() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path unused = image("gone.png");
        Set<Path> created = new LinkedHashSet<>(List.of(unused));
        Bin bin = new Bin();
        bin.available = false;
        PastedImageCleanup.Outcome outcome = run(doc, "empty\n", created, n -> false, bin);
        assertTrue(Files.exists(unused), "never deleted for good");
        assertEquals(Messages.tr("status.markdown.imagesNotTrashed", 1), PastedImageCleanup.note(outcome));
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
        assertEquals(1, run(doc, "empty\n", created, n -> false, failing).kept());
        assertTrue(Files.exists(unused));
    }

    @Test
    void aFileThatIsGoneAlreadyIsForgotten() throws Exception {
        Path doc = dir.resolve("notes.md");
        Files.createDirectories(dir.resolve("assets"));
        Set<Path> created = new LinkedHashSet<>(List.of(dir.resolve("assets/never.png")));
        assertEquals("", PastedImageCleanup.note(run(doc, "empty\n", created, n -> false, new Bin())));
        assertTrue(created.isEmpty());
    }

    @Test
    void cancellingStopsBeforeAnythingIsRead_orMoved() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path unused = image("gone.png");
        Files.writeString(dir.resolve("other.md"), "nothing\n");
        Bin bin = new Bin();
        assertEquals(List.of(), PastedImageCleanup.unusedOnDisk(doc, List.of(unused), () -> true), "window closing");
        PastedImageCleanup.Outcome outcome = PastedImageCleanup.trash(List.of(unused), bin, () -> true);
        assertTrue(outcome.trashed().isEmpty() && outcome.kept() == 0);
        assertTrue(Files.exists(unused));
    }

    @Test
    void aFolderTooLargeToCheckMeansNothingIsKnownToBeUnused() throws Exception {
        Path doc = dir.resolve("notes.md");
        Path unused = image("gone.png");
        Files.writeString(dir.resolve("huge.md"), "x".repeat((int) PastedImageCleanup.MAX_SIBLING_BYTES + 1));
        assertEquals(List.of(), PastedImageCleanup.unusedOnDisk(doc, List.of(unused), () -> false));
    }
}
