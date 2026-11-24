package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.editora.git.BlameParser.BlameLine;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind the history fixes: blame per line, patch bytes, the log's footer and dates. */
class GitHistoryUiEdgeCasesTest {

    private static BlameLine line(String hash, String path, String previousPath) {
        return new BlameLine(hash, "Ada", 1, "s", false, path, previousPath, "p" + hash);
    }

    @Test
    void aCommitWithBlocksFromSeveralFilesIsLookedUpPerLine() {
        // -M -C: commit c owns line 0 (moved from old/a.txt) and line 2 (copied from other/b.txt).
        BlameLine fromA = line("c", "f.txt", "old/a.txt");
        BlameLine other = line("d", "f.txt", "f.txt");
        BlameLine fromB = line("c", "f.txt", "other/b.txt");
        List<BlameLine> lines = List.of(fromA, other, fromB);

        assertSame(fromA, GitBlameCoordinator.lineNear(lines, 0, "c"));
        assertSame(fromB, GitBlameCoordinator.lineNear(lines, 2, "c"), "not the commit's first block");
        assertSame(other, GitBlameCoordinator.lineNear(lines, 1, "d"));
        assertSame(fromB, GitBlameCoordinator.lineNear(lines, 3, "c"), "a line added since: the nearest one");
        assertNull(GitBlameCoordinator.lineNear(lines, 1, "e"));
        assertNull(GitBlameCoordinator.lineNear(List.of(), 0, "c"));
    }

    /** 50 blamed lines on commit "a", except {@code owned} on {@code hash} (each with its own path). */
    private static List<BlameLine> blame(String hash, int... owned) {
        java.util.ArrayList<BlameLine> out = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            out.add(line("a", "f.txt", "f.txt"));
        }
        for (int i : owned) {
            out.set(i, line(hash, "f" + i + ".txt", "f.txt"));
        }
        return out;
    }

    @Test
    void linesPastedAboveStillFindTheLineTheGutterNames() {
        List<BlameLine> lines = blame("x", 10);
        // 50 lines pasted at the top: buffer line 60 shows disk line 10, which the gutter blames on x.
        assertSame(lines.get(10), GitBlameCoordinator.lineNear(lines, 10, "x"), "indexed by the disk line");
        // Even from the buffer line (or no disk line) the search covers the whole file: it was capped at 50 steps.
        assertSame(lines.get(10), GitBlameCoordinator.lineNear(lines, 60, "x"));
        assertSame(lines.get(10), GitBlameCoordinator.lineNear(lines, -1, "x"));
    }

    @Test
    void aNarrowedBufferPicksTheBlockAtItsDiskLine() {
        // Commit x owns disk lines 5 and 25 (two blocks); narrowed with 20 lines hidden, area line 5 is disk 25.
        List<BlameLine> lines = blame("x", 5, 25);
        assertSame(lines.get(25), GitBlameCoordinator.lineNear(lines, 25, "x"), "not the block at area line 5");
    }

    @Test
    void anUneditedPatchTabAppliesTheFileBytesWithItsMixedLineEndings() {
        // LF headers, CRLF hunk lines: what a patch of a CRLF file made on Linux looks like.
        String patch = "diff --git a/w.txt b/w.txt\n--- a/w.txt\n+++ b/w.txt\n@@ -1 +1 @@\n-old\r\n+new\r\n";
        byte[] disk = patch.getBytes(StandardCharsets.UTF_8);
        String tab = patch.replace("\r\n", "\n"); // a tab holds one line ending

        GitPatchCoordinator.TabPatch unedited = GitPatchCoordinator.tabPatch(disk, false, tab, "LF", "utf-8");
        assertArrayEquals(disk, unedited.bytes(), "the file's own bytes, CRs and all");
        assertFalse(unedited.endingsLost());

        GitPatchCoordinator.TabPatch edited = GitPatchCoordinator.tabPatch(disk, true, tab, "LF", "utf-8");
        assertArrayEquals(tab.getBytes(StandardCharsets.UTF_8), edited.bytes(), "an edit is applied as saved");
        assertTrue(edited.endingsLost(), "…and the user is told the mixed endings were not kept");

        GitPatchCoordinator.TabPatch changed =
                GitPatchCoordinator.tabPatch(disk, false, tab + "+more\n", "LF", "utf-8");
        assertArrayEquals((tab + "+more\n").getBytes(StandardCharsets.UTF_8), changed.bytes(), "the file moved on");
        assertFalse(changed.endingsLost(), "not edited: the edit warning would be wrong");
        assertTrue(changed.fileChanged(), "…it says the file changed on disk instead");
        assertFalse(unedited.fileChanged());
        assertFalse(edited.fileChanged());

        GitPatchCoordinator.TabPatch untitled = GitPatchCoordinator.tabPatch(null, false, tab, "LF", "utf-8");
        assertArrayEquals(tab.getBytes(StandardCharsets.UTF_8), untitled.bytes());
        assertFalse(untitled.endingsLost());
    }

    @Test
    void anUneditedLatin1PatchTabKeepsItsBytes() {
        byte[] disk = "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-café\n+thé\n".getBytes(StandardCharsets.ISO_8859_1);
        String tab = new String(disk, StandardCharsets.ISO_8859_1);
        assertArrayEquals(
                disk,
                GitPatchCoordinator.tabPatch(disk, false, tab, "LF", "latin1").bytes());
    }

    @Test
    void theLogFooterSaysWhenASearchedHistoryWasCut() {
        assertEquals(tr("gitlog.truncated", 200), GitLogPanel.footerNote(true, 200, 0, false));
        assertEquals(tr("gitlog.searchCapped", 5000), GitLogPanel.footerNote(false, 12, 5000, false));
        assertEquals(tr("gitlog.searchCapped", 5000), GitLogPanel.footerNote(false, 0, 5000, true));
        assertEquals(tr("gitlog.shallow"), GitLogPanel.footerNote(false, 12, 0, true));
        assertEquals("", GitLogPanel.footerNote(false, 12, 0, false));
        assertEquals("", GitLogPanel.footerNote(false, 0, 0, true));
    }

    @Test
    void aCommitDatedAheadOfTheClockShowsItsDateNotNow() {
        long now = 1_800_000_000L;
        assertEquals(tr("blame.now"), GitLogPanel.relativeDate(now + 60, "2027-01-15", now), "a little skew");
        assertEquals("2027-01-15", GitLogPanel.relativeDate(now + 86_400, "2027-01-15", now));
        assertEquals(tr("blame.future"), GitLogPanel.relativeDate(now + 86_400, "", now));
        assertEquals(tr("blame.future"), GitBlameCoordinator.relativeTimeLabel(now + 86_400, now));
        assertEquals(tr("blame.now"), GitBlameCoordinator.relativeTimeLabel(now + 60, now));
    }

    @Test
    void theDetailsAndTooltipOfAFutureCommitSayInTheFutureNotTheDateTwice() {
        long now = 1_800_000_000L;
        var future = new com.editora.git.GitLog.Entry("h", "h", "s", "Ada", now + 86_400, "2027-01-15", List.of());
        assertEquals("2027-01-15 (" + tr("blame.future") + ")", GitLogPanel.tooltipDate(future, now));
        assertEquals(
                GitLogPanel.absoluteDate(now + 86_400) + " (" + tr("blame.future") + ")",
                GitLogPanel.detailsDate(future, now));
        var undated = new com.editora.git.GitLog.Entry("h", "h", "s", "Ada", 0, "2027-01-15", List.of());
        assertEquals("2027-01-15", GitLogPanel.tooltipDate(undated, now));
        assertEquals("2027-01-15", GitLogPanel.detailsDate(undated, now));
        assertTrue(
                GitLogPanel.absoluteDate(now)
                        .endsWith(java.time.format.DateTimeFormatter.ofPattern(
                                        "zzz", java.util.Locale.getDefault(java.util.Locale.Category.FORMAT))
                                .format(java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneId.systemDefault()))),
                "the details name the zone their time is in");
    }

    @Test
    void aResultThatCouldNotBePutTogetherIsReported() {
        assertEquals(
                tr("merge.resultFailed", "boom"), MergeViewerPane.resultFailedText(new IllegalStateException("boom")));
        assertEquals(
                tr("merge.resultFailed", "NullPointerException"),
                MergeViewerPane.resultFailedText(new NullPointerException()));
    }
}
