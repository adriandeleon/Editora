package com.editora.git;

import java.util.List;

import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code git status --porcelain=v2} as git can really write it: quoted names with every C escape, lines cut
 * short, counters that are not numbers. A malformed line is skipped; it never takes the other lines with it.
 */
class StatusParserEdgeCasesTest {

    private static final String HASHES = "100644 100644 100644 1111111 2222222";

    @Test
    void noOutputAtAllIsACleanRepositoryWithNoBranchKnown() {
        GitStatus status = StatusParser.parse(null);
        assertTrue(status.isRepo());
        assertEquals("", status.branch());
        assertEquals("", status.upstream());
        assertEquals(0, status.ahead());
        assertEquals(0, status.behind());
        assertEquals(List.of(), status.files());
    }

    @Test
    void aheadAndBehindAreReadBySignAndAnythingElseCountsAsZero() {
        GitStatus status = StatusParser.parse(
                "# branch.oid abc\n# branch.head  topic \n# branch.upstream origin/topic\n# branch.ab +12 -3\n");
        assertEquals("topic", status.branch());
        assertEquals("origin/topic", status.upstream());
        assertEquals(12, status.ahead());
        assertEquals(3, status.behind());

        GitStatus garbled = StatusParser.parse("# branch.ab +many -few 7\n");
        assertEquals(0, garbled.ahead(), "not a number: unknown, shown as none");
        assertEquals(0, garbled.behind());

        GitStatus behindOnly = StatusParser.parse("# branch.ab -4\n");
        assertEquals(0, behindOnly.ahead());
        assertEquals(4, behindOnly.behind());
    }

    @Test
    void everyKindOfEntryLineIsRead() {
        GitStatus status = StatusParser.parse("1 .M N... " + HASHES + " changed file.txt\n"
                + "2 R. N... " + HASHES + " R100 new name.txt\told name.txt\n"
                + "u UU N... 100644 100644 100644 100644 1111111 2222222 3333333 both.txt\n"
                + "? fresh.txt\n"
                + "! ignored.log\n"
                + "\n");
        assertEquals(
                List.of(
                        new FileEntry("changed file.txt", '.', 'M', null),
                        new FileEntry("new name.txt", 'R', '.', "old name.txt"),
                        new FileEntry("both.txt", 'U', 'U', null),
                        new FileEntry("fresh.txt", '?', '?', null)),
                status.files(),
                "a name keeps its spaces; an ignored entry is not listed");
    }

    @Test
    void aLineCutShortOrWithAMalformedStateIsSkippedAndTheRestIsKept() {
        GitStatus status = StatusParser.parse(
                "1 .M N... 100644\n" // too few fields
                        + "1 MOD N... " + HASHES + " three-letter-state.txt\n"
                        + "2 R. N... " + HASHES + "\n" // no score, no path
                        + "2 RXX N... " + HASHES + " R100 a.txt\tb.txt\n"
                        + "u UU N... 100644 both.txt\n"
                        + "u U N... 100644 100644 100644 100644 1111111 2222222 3333333 short-state.txt\n"
                        + "? \n" // a "?" with no name
                        + "?\n"
                        + "x unknown kind\n"
                        + "? kept.txt\n");
        assertEquals(List.of(new FileEntry("kept.txt", '?', '?', null)), status.files());
    }

    @Test
    void aRenameLineWithoutItsOldNameHasNoOriginalPath() {
        GitStatus status = StatusParser.parse("2 R. N... " + HASHES + " R100 only-new.txt\n");
        assertEquals(List.of(new FileEntry("only-new.txt", 'R', '.', null)), status.files());
    }

    @Test
    void quotedNamesAreDecodedOnBothSidesOfARename() {
        GitStatus status = StatusParser.parse(
                "2 R. N... " + HASHES + " R100 \"caf\\303\\251.txt\"\t\"old\\tname.txt\"\n? \"sp\\\"ace.txt\"\n");
        assertEquals(
                List.of(
                        new FileEntry("café.txt", 'R', '.', "old\tname.txt"),
                        new FileEntry("sp\"ace.txt", '?', '?', null)),
                status.files());
    }

    @Test
    void unquotePathLeavesWhatIsNotQuotedAlone() {
        assertNull(StatusParser.unquotePath(null));
        assertEquals("", StatusParser.unquotePath(""));
        assertEquals("\"", StatusParser.unquotePath("\""));
        assertEquals("plain.txt", StatusParser.unquotePath("plain.txt"));
        assertEquals("\"open only", StatusParser.unquotePath("\"open only"));
        assertEquals("close only\"", StatusParser.unquotePath("close only\""));
        assertEquals("", StatusParser.unquotePath("\"\""));
    }

    @Test
    void unquotePathDecodesEveryEscapeGitWrites() {
        assertEquals("a\u0007b", StatusParser.unquotePath("\"a\\ab\""));
        assertEquals("a\bb", StatusParser.unquotePath("\"a\\bb\""));
        assertEquals("a\tb", StatusParser.unquotePath("\"a\\tb\""));
        assertEquals("a\nb", StatusParser.unquotePath("\"a\\nb\""));
        assertEquals("a\u000Bb", StatusParser.unquotePath("\"a\\vb\""));
        assertEquals("a\fb", StatusParser.unquotePath("\"a\\fb\""));
        assertEquals("a\rb", StatusParser.unquotePath("\"a\\rb\""));
        assertEquals("a\"b", StatusParser.unquotePath("\"a\\\"b\""));
        assertEquals("a\\b", StatusParser.unquotePath("\"a\\\\b\""));
        assertEquals("a b", StatusParser.unquotePath("\"a\\ b\""), "an escape git does not define is the character");
    }

    @Test
    void unquotePathReadsOctalBytesAndLiteralNonAscii() {
        assertEquals("café.txt", StatusParser.unquotePath("\"caf\\303\\251.txt\""));
        assertEquals("A", StatusParser.unquotePath("\"\\101\""));
        assertEquals("\u0007z", StatusParser.unquotePath("\"\\7z\""), "one digit, then an ordinary character");
        assertEquals("\u003F8", StatusParser.unquotePath("\"\\778\""), "8 is not an octal digit");
        assertEquals("\u0001", StatusParser.unquotePath("\"\\1\""), "an escape at the very end");
        // core.quotePath=false: only the tab forces the quotes; é and the emoji stay literal inside them.
        assertEquals("new\tname é 😀.txt", StatusParser.unquotePath("\"new\\tname é 😀.txt\""));
        assertEquals("ends in a backslash\\", StatusParser.unquotePath("\"ends in a backslash\\\""));
    }
}
