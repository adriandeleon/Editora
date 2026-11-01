package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parse of a card's last change (Project Map explore round, position 9). */
class GitServiceLastChangeTest {

    @Test
    void theFirstCommitOfTheLogIsTheLastChange() {
        GitService.LastChange change = GitService.parseLastChange(
                "9ca80ef427376b044028955715650174c0b7bf4d\u0000Ada Lovelace\u00001781367494\u0000style: a subject\u0000with a NUL\n"
                        + "0123456789abcdef0123456789abcdef01234567\u0000Bob\u00001\u0000older\n",
                true);
        assertEquals("9ca80ef427376b044028955715650174c0b7bf4d", change.hash());
        assertEquals("Ada Lovelace", change.author());
        assertEquals(1781367494L, change.epochSeconds());
        assertEquals("style: a subject\u0000with a NUL", change.subject(), "the subject is the rest of the line");
        assertTrue(change.lines());
    }

    @Test
    void nothingForAnEmptyOrUnreadableLog() {
        assertNull(GitService.parseLastChange("", false));
        assertNull(GitService.parseLastChange(null, false));
        assertNull(GitService.parseLastChange("fatal: no such path\n", false));
        GitService.LastChange undated = GitService.parseLastChange("abcdef0\u0000Ada\u0000soon\u0000s\n", false);
        assertEquals(0, undated.epochSeconds(), "an unreadable time is no time");
        assertFalse(undated.lines());
    }
}
