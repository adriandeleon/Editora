package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The directory read behind the file finder and the breadcrumb dropdown. */
class DirectoryListingTest {

    @Test
    void foldersComeFirstAndTheListingRemembersWhichTheyAre(@TempDir Path dir) throws Exception {
        Path zeta = Files.createDirectory(dir.resolve("Zeta"));
        Path alpha = Files.createDirectory(dir.resolve("alpha"));
        Path b = Files.writeString(dir.resolve("b.txt"), "");
        Path a = Files.writeString(dir.resolve("A.txt"), "");

        DirectoryListing listing = DirectoryListing.read(dir, false);

        assertTrue(listing.readable());
        assertEquals(List.of(alpha, zeta, a, b), listing.entries());
        assertEquals(Set.of(alpha, zeta), listing.directories());
        assertTrue(listing.isDirectory(alpha));
        assertFalse(listing.isDirectory(a));
    }

    @Test
    void aFolderPickerListsOnlyFolders(@TempDir Path dir) throws Exception {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("file.txt"), "");
        assertEquals(List.of(sub), DirectoryListing.read(dir, true).entries());
    }

    @Test
    void aFileAMissingPathAndNullAreUnreadableNotEmptyFolders(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "");
        assertFalse(DirectoryListing.read(file, false).readable());
        assertFalse(DirectoryListing.read(dir.resolve("missing"), false).readable());
        assertFalse(DirectoryListing.read(null, false).readable());
        assertTrue(DirectoryListing.read(Files.createDirectory(dir.resolve("empty")), false)
                .readable());
    }
}
