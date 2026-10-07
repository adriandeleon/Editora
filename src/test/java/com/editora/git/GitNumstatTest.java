package com.editora.git;

import java.util.Map;

import com.editora.git.GitNumstat.Counts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code git diff --numstat -z}: plain records, renames (three tokens), binary files, junk. */
class GitNumstatTest {

    @Test
    void plainRecordsArePathToCounts() {
        Map<String, Counts> counts = GitNumstat.parse("3\t1\tsrc/a b.txt\0" + "0\t12\tdocs/tab\there.md\0");
        assertEquals(new Counts(3, 1, false), counts.get("src/a b.txt"));
        assertEquals(new Counts(0, 12, false), counts.get("docs/tab\there.md"), "a tab in a name is part of the name");
        assertEquals(2, counts.size());
    }

    @Test
    void aRenameIsKeyedByItsNewPath() {
        Map<String, Counts> counts = GitNumstat.parse("2\t0\t\0old/name.txt\0new/name.txt\0" + "1\t1\tafter.txt\0");
        assertEquals(new Counts(2, 0, false), counts.get("new/name.txt"));
        assertEquals(new Counts(1, 1, false), counts.get("after.txt"), "the record after a rename is still read");
        assertEquals(2, counts.size());
    }

    @Test
    void aBinaryFileHasNoLineCounts() {
        assertEquals(
                new Counts(0, 0, true), GitNumstat.parse("-\t-\tlogo.png\0").get("logo.png"));
    }

    @Test
    void emptyAndMalformedOutputGivesNothing() {
        assertTrue(GitNumstat.parse("").isEmpty());
        assertTrue(GitNumstat.parse(null).isEmpty());
        assertTrue(GitNumstat.parse("not a record\0x\ty\tfile\0").isEmpty());
        assertTrue(GitNumstat.parse("1\t1\t\0only-one-path\0").isEmpty(), "a rename cut short");
        assertEquals(GitNumstat.Changes.NONE, new GitNumstat.Changes(Map.of(), Map.of()));
    }
}
