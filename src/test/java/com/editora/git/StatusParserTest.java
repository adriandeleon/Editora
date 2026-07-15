package com.editora.git;

import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure {@code git status --porcelain=v2 --branch} parser. */
class StatusParserTest {

    @Test
    void branchHeadersAheadBehindAndUpstream() {
        String out = """
                # branch.oid abc123
                # branch.head feature/git
                # branch.upstream origin/feature/git
                # branch.ab +3 -2
                """;
        GitStatus s = StatusParser.parse(out);
        assertTrue(s.isRepo());
        assertEquals("feature/git", s.branch());
        assertEquals("origin/feature/git", s.upstream());
        assertEquals(3, s.ahead());
        assertEquals(2, s.behind());
        assertTrue(s.isClean());
    }

    @Test
    void noUpstreamLeavesAheadBehindZero() {
        GitStatus s = StatusParser.parse("# branch.head main\n");
        assertEquals("main", s.branch());
        assertEquals("", s.upstream());
        assertEquals(0, s.ahead());
        assertEquals(0, s.behind());
    }

    @Test
    void ordinaryStagedAndUnstagedEntries() {
        String out = """
                # branch.head main
                1 M. N... 100644 100644 100644 aaa bbb staged.txt
                1 .M N... 100644 100644 100644 ccc ddd unstaged.txt
                1 MM N... 100644 100644 100644 eee fff both.txt
                """;
        GitStatus s = StatusParser.parse(out);
        assertEquals(3, s.files().size());

        FileEntry staged = s.files().get(0);
        assertEquals("staged.txt", staged.path());
        assertTrue(staged.staged());
        assertFalse(staged.unstaged());

        FileEntry unstaged = s.files().get(1);
        assertFalse(unstaged.staged());
        assertTrue(unstaged.unstaged());

        FileEntry both = s.files().get(2);
        assertTrue(both.staged());
        assertTrue(both.unstaged());
    }

    @Test
    void untrackedEntry() {
        GitStatus s = StatusParser.parse("# branch.head main\n? new-file.txt\n");
        FileEntry e = s.files().get(0);
        assertEquals("new-file.txt", e.path());
        assertTrue(e.untracked());
        assertFalse(e.staged());
        assertFalse(e.unstaged());
    }

    @Test
    void renameEntryCapturesOriginalPath() {
        // 2 R. <sub> <mH> <mI> <mW> <hH> <hI> <Xscore> <path>\t<origPath>
        String out =
                "# branch.head main\n" + "2 R. N... 100644 100644 100644 aaa bbb R100 new/name.txt\told/name.txt\n";
        GitStatus s = StatusParser.parse(out);
        FileEntry e = s.files().get(0);
        assertEquals("new/name.txt", e.path());
        assertEquals("old/name.txt", e.origPath());
        assertTrue(e.staged());
    }

    @Test
    void ignoredEntriesAreSkipped() {
        GitStatus s = StatusParser.parse("# branch.head main\n! ignored.log\n");
        assertTrue(s.files().isEmpty());
    }

    @Test
    void pathWithSpacesPreserved() {
        String out = "# branch.head main\n" + "1 .M N... 100644 100644 100644 ccc ddd my file with spaces.txt\n";
        GitStatus s = StatusParser.parse(out);
        assertEquals("my file with spaces.txt", s.files().get(0).path());
    }

    @Test
    void cQuotedNonAsciiPathIsDecoded() {
        // With core.quotePath=true (git's default), "café.txt" is emitted as "caf\303\251.txt".
        String out = "# branch.head main\n" + "1 .M N... 100644 100644 100644 ccc ddd \"caf\\303\\251.txt\"\n";
        GitStatus s = StatusParser.parse(out);
        assertEquals("café.txt", s.files().get(0).path(), "the quoted octal-escaped UTF-8 path is decoded");
    }

    @Test
    void cQuotedRenameDecodesBothPaths() {
        String out = "# branch.head main\n"
                + "2 R. N... 100644 100644 100644 aaa bbb R100 \"nu\\303\\251vo.txt\"\t\"vi\\303\\251jo.txt\"\n";
        GitStatus s = StatusParser.parse(out);
        FileEntry e = s.files().get(0);
        assertEquals("nuévo.txt", e.path());
        assertEquals("viéjo.txt", e.origPath());
    }

    @Test
    void cQuotedEscapesForTabAndQuoteAreDecoded() {
        // A name with a literal tab and a quote: git quotes it as "a\tb\"c.txt".
        String out = "# branch.head main\n" + "? \"a\\tb\\\"c.txt\"\n";
        GitStatus s = StatusParser.parse(out);
        assertEquals("a\tb\"c.txt", s.files().get(0).path());
    }
}
