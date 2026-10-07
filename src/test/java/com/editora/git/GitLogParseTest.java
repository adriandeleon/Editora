package com.editora.git;

import java.util.List;

import com.editora.git.GitLog.Ref;
import com.editora.git.GitLog.RefKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Git Log's row model: fields, ref decorations and the truncation flag, parsed from one {@code git log}. */
class GitLogParseTest {

    private static String line(String hash, String refs, String subject) {
        return hash + "\t" + hash.substring(0, 7) + "\tAda Lovelace\t1791342219\t2026-10-06\t" + refs + "\t" + subject;
    }

    @Test
    void aRowCarriesAuthorTimeAndSubject() {
        GitLog.Page page = GitLog.parse(line("abcdef1234567890", "", "Fix\tthe tab"), 10);

        assertEquals(1, page.entries().size());
        GitLog.Entry e = page.entries().get(0);
        assertEquals("abcdef1234567890", e.hash());
        assertEquals("abcdef1", e.shortHash());
        assertEquals("Ada Lovelace", e.author());
        assertEquals(1791342219L, e.epochSeconds());
        assertEquals("2026-10-06", e.date());
        assertEquals("Fix\tthe tab", e.subject(), "the subject is the last field, so a tab inside it survives");
        assertTrue(e.refs().isEmpty());
        assertFalse(page.truncated());
    }

    @Test
    void decorationsBecomeTypedRefs() {
        List<Ref> refs = GitLog.parseRefs("HEAD -> refs/heads/main, tag: refs/tags/v1.0, refs/remotes/origin/main,"
                + " refs/remotes/origin/HEAD, refs/heads/origin/odd, refs/stash");

        assertEquals(
                List.of(
                        new Ref(RefKind.LOCAL, "main", true),
                        new Ref(RefKind.TAG, "v1.0", false),
                        new Ref(RefKind.REMOTE, "origin/main", false),
                        // a LOCAL branch that merely looks remote: only full ref names can tell them apart
                        new Ref(RefKind.LOCAL, "origin/odd", false),
                        new Ref(RefKind.OTHER, "stash", false)),
                refs,
                "origin/HEAD only repeats the remote's default branch and is dropped");
    }

    @Test
    void aDetachedHeadIsItsOwnRef() {
        assertEquals(
                List.of(new Ref(RefKind.HEAD, "HEAD", true), new Ref(RefKind.LOCAL, "main", false)),
                GitLog.parseRefs("HEAD, refs/heads/main"));
        assertTrue(GitLog.parseRefs("").isEmpty());
        assertTrue(GitLog.parseRefs(null).isEmpty());
    }

    @Test
    void oneRowBeyondTheLimitMarksThePageTruncatedAndIsDropped() {
        String out = String.join(
                "\n",
                line("1111111aaaaaaaaa", "", "newest"),
                line("2222222aaaaaaaaa", "", "middle"),
                line("3333333aaaaaaaaa", "", "oldest"));

        GitLog.Page cut = GitLog.parse(out, 2);
        assertEquals(2, cut.entries().size());
        assertEquals("middle", cut.entries().get(1).subject());
        assertTrue(cut.truncated(), "git returned a third commit, so the list is not the whole history");

        GitLog.Page whole = GitLog.parse(out, 3);
        assertEquals(3, whole.entries().size());
        assertFalse(whole.truncated());
    }

    @Test
    void malformedLinesAreSkipped() {
        assertTrue(GitLog.parse("not a log line\n\n", 5).entries().isEmpty());
        assertTrue(GitLog.parse(null, 5).entries().isEmpty());
    }
}
