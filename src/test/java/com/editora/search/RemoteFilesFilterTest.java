package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link RemoteFiles} keeps of a host's {@code find} listing: the local walk's rules, without the disk. */
class RemoteFilesFilterTest {

    @Test
    void hiddenFilesAndEverythingInAHiddenFolderAreLeftOut() {
        assertEquals(
                List.of("a/b.txt", "readme.md"),
                RemoteFiles.filtered(
                        List.of("readme.md", ".env", "a/b.txt", "a/.hidden", ".idea/workspace.xml", "a/.git/HEAD"),
                        null));
    }

    @Test
    void theListingIsSortedAsAWalkDeliversIt() {
        assertEquals(
                List.of("a.txt", "b/c.txt", "z.txt"), RemoteFiles.filtered(List.of("z.txt", "b/c.txt", "a.txt"), null));
    }

    @Test
    void theCeilingLeavesRoomForWhatIsFilteredOut() {
        assertTrue(RemoteFiles.findCeiling(100) > 100);
        assertEquals(Integer.MAX_VALUE, RemoteFiles.findCeiling(Integer.MAX_VALUE), "no overflow");
    }
}
