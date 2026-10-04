package com.editora.git;

import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An unmerged path is not a staged change: listing it under Staged offered "Unstage", and
 * {@code git reset HEAD -- <path>} throws its merge stages away.
 */
class UnmergedEntryTest {

    @Test
    void aPorcelainUnmergedEntryIsUnmergedAndNotStaged() {
        GitStatus status = StatusParser.parse("# branch.head main\n"
                + "u UU N... 100644 100644 100644 100644 1111111111111111111111111111111111111111"
                + " 2222222222222222222222222222222222222222 3333333333333333333333333333333333333333 story.txt\n"
                + "1 M. N... 100644 100644 100644 1111111111111111111111111111111111111111"
                + " 2222222222222222222222222222222222222222 merged-cleanly.txt\n");

        FileEntry conflicted = status.files().get(0);
        assertEquals("story.txt", conflicted.path());
        assertTrue(conflicted.unmerged());
        assertFalse(conflicted.staged(), "nothing to unstage: the index holds the merge stages");
        assertTrue(conflicted.unstaged(), "still listed under Changes, where Stage marks it resolved");
        assertEquals(GitFileStatus.CONFLICT, GitFileStatus.of(conflicted));

        FileEntry clean = status.files().get(1);
        assertFalse(clean.unmerged());
        assertTrue(clean.staged());
    }

    @Test
    void everyUnmergedLetterPairIsRecognised() {
        for (String xy : new String[] {"UU", "AU", "UA", "DU", "UD", "AA", "DD"}) {
            FileEntry e = new FileEntry("f", xy.charAt(0), xy.charAt(1), null);
            assertTrue(e.unmerged(), xy);
            assertFalse(e.staged(), xy);
        }
        for (String xy : new String[] {"M.", ".M", "MM", "A.", "AM", "D.", ".D", "R.", "??"}) {
            assertFalse(new FileEntry("f", xy.charAt(0), xy.charAt(1), null).unmerged(), xy);
        }
    }
}
