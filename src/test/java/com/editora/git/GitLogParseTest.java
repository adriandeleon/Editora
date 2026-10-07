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
        return line(hash, "", refs, subject);
    }

    private static String line(String hash, String parents, String refs, String subject) {
        return hash + "\t" + hash.substring(0, 7) + "\tAda Lovelace\t1791342219\t2026-10-06\t" + parents + "\t" + refs
                + "\t" + subject;
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

    @Test
    void aRowCarriesItsParentsFirstParentFirst() {
        GitLog.Page page = GitLog.parse(
                line("abcdef1234567890", "1111111aaaaaaaaa 2222222aaaaaaaaa", "", "Merge branch 'x'") + "\n"
                        + line("1111111aaaaaaaaa", "", "", "root"),
                10);

        assertEquals(
                List.of("1111111aaaaaaaaa", "2222222aaaaaaaaa"),
                page.entries().get(0).parents());
        assertTrue(page.entries().get(0).isMerge());
        assertTrue(page.entries().get(1).parents().isEmpty(), "a root commit has none");
        assertFalse(page.entries().get(1).isMerge());
    }

    @Test
    void aFileHistoryRecordCarriesThePathTheFileHadInThatCommit() {
        // git log --follow --name-status -z: \u0001 row NUL newline, then the NUL-separated name-status entry.
        String out = "\u0001" + line("3333333aaaaaaaaa", "2222222aaaaaaaaa", "", "edit after the rename")
                + "\0\nM\0docs/new name.md\0"
                + "\u0001" + line("2222222aaaaaaaaa", "1111111aaaaaaaaa", "", "rename")
                + "\0\nR087\0docs/old.md\0docs/new name.md\0"
                + "\u0001" + line("1111111aaaaaaaaa", "", "", "add")
                + "\0\nA\0docs/old.md\0";

        GitLog.Page page = GitLog.parseFollow(out, 10);

        assertEquals(
                List.of("edit after the rename", "rename", "add"),
                page.entries().stream().map(GitLog.Entry::subject).toList());
        assertEquals("docs/new name.md", page.followed().get("3333333aaaaaaaaa").path());
        GitService.CommitFile rename = page.followed().get("2222222aaaaaaaaa");
        assertEquals('R', rename.status());
        assertEquals("docs/new name.md", rename.path());
        assertEquals("docs/old.md", rename.origPath(), "the commit that renamed it knows both names");
        assertEquals("docs/old.md", page.followed().get("1111111aaaaaaaaa").path(), "before the rename: the old path");
        assertFalse(page.truncated());

        // With "format:" instead of "tformat:" git writes only a newline after the row; both are read.
        GitLog.Page other = GitLog.parseFollow(
                "\u0001" + line("2222222aaaaaaaaa", "", "", "rename") + "\nR100\0a.txt\0b.txt\0\0" + "\u0001"
                        + line("1111111aaaaaaaaa", "", "", "add") + "\nA\0a.txt\0",
                10);
        assertEquals(
                List.of("rename", "add"),
                other.entries().stream().map(GitLog.Entry::subject).toList());
        assertEquals("a.txt", other.followed().get("2222222aaaaaaaaa").origPath());
        assertEquals("a.txt", other.followed().get("1111111aaaaaaaaa").path());

        GitLog.Page cut = GitLog.parseFollow(out, 2);
        assertEquals(2, cut.entries().size());
        assertTrue(cut.truncated());
        assertFalse(cut.followed().containsKey("1111111aaaaaaaaa"), "the row beyond the page is dropped whole");
    }

    @Test
    void detailsAreParsedWithAMultiLineMessage() {
        String out = String.join(
                "\0",
                "abcdef1234567890",
                "1111111aaaaaaaaa 2222222aaaaaaaaa",
                "Ada Lovelace",
                "ada@example.org",
                "1791342219",
                "Grace Hopper",
                "grace@example.org",
                "1791342300",
                "HEAD -> refs/heads/main, tag: refs/tags/v1.0",
                "Subject line\n\nBody with a\ttab\nand a second line.\n\n");

        GitLog.Details d = GitLog.parseDetails(out);

        assertEquals("abcdef1234567890", d.hash());
        assertEquals(List.of("1111111aaaaaaaaa", "2222222aaaaaaaaa"), d.parents());
        assertEquals("ada@example.org", d.authorEmail());
        assertEquals(1791342219L, d.authorEpochSeconds());
        assertEquals("Grace Hopper", d.committer());
        assertEquals(1791342300L, d.commitEpochSeconds());
        assertEquals(List.of(new Ref(RefKind.LOCAL, "main", true), new Ref(RefKind.TAG, "v1.0", false)), d.refs());
        assertEquals("Subject line\n\nBody with a\ttab\nand a second line.", d.message());
        assertEquals(null, GitLog.parseDetails("fatal: bad object"));
        assertEquals(null, GitLog.parseDetails(null));
    }

    @Test
    void aContinuationPageDropsTheCommitItOverlapsWith() {
        GitLog.Page page =
                GitLog.parse(line("1111111aaaaaaaaa", "", "anchor") + "\n" + line("2222222aaaaaaaaa", "", "older"), 5);
        assertEquals(
                List.of("older"),
                page.drop(1).entries().stream().map(GitLog.Entry::subject).toList());
        assertTrue(page.drop(5).entries().isEmpty());
    }
}
