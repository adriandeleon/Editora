package com.editora.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.git.DiffParser.LineChange;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure unified-diff hunk parser (no toolkit, no repo). */
class DiffParserTest {

    @Test
    void emptyOrBlankDiffYieldsNothing() {
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse(null).isEmpty());
        assertTrue(DiffParser.parse("   \n  ").isEmpty());
    }

    @Test
    void pureInsertionIsAdded() {
        // @@ -0,0 +5,3 @@ : 3 new lines starting at new-file line 5 (0-based 4).
        List<LineChange> c = DiffParser.parse("@@ -0,0 +5,3 @@\n+a\n+b\n+c\n");
        assertEquals(1, c.size());
        assertEquals(new LineChange(4, 3, ChangeType.ADDED), c.get(0));
    }

    @Test
    void singleLineInsertionOmitsCount() {
        // @@ -0,0 +2 @@ : count defaults to 1.
        List<LineChange> c = DiffParser.parse("@@ -0,0 +2 @@\n+x\n");
        assertEquals(new LineChange(1, 1, ChangeType.ADDED), c.get(0));
    }

    @Test
    void replacementIsModified() {
        // @@ -3,2 +3,2 @@ : 2 lines changed in place at new-file line 3 (0-based 2).
        List<LineChange> c = DiffParser.parse("@@ -3,2 +3,2 @@\n-old1\n-old2\n+new1\n+new2\n");
        assertEquals(new LineChange(2, 2, ChangeType.MODIFIED), c.get(0));
    }

    @Test
    void pureDeletionMarksFollowingLine() {
        // @@ -2 +1,0 @@ : a line removed; marker sits on new-file line 1 (0-based 1).
        List<LineChange> c = DiffParser.parse("@@ -2 +1,0 @@\n-gone\n");
        assertEquals(new LineChange(1, 1, ChangeType.DELETED), c.get(0));
    }

    @Test
    void deletionAtTopOfFileClampsToZero() {
        List<LineChange> c = DiffParser.parse("@@ -1,2 +0,0 @@\n-a\n-b\n");
        assertEquals(new LineChange(0, 1, ChangeType.DELETED), c.get(0));
    }

    @Test
    void multipleHunksInOrder() {
        String diff = """
                diff --git a/f.txt b/f.txt
                index 111..222 100644
                --- a/f.txt
                +++ b/f.txt
                @@ -1,0 +1,2 @@
                +new top
                +second
                @@ -10,2 +12,2 @@
                -x
                +y
                """;
        List<LineChange> c = DiffParser.parse(diff);
        assertEquals(2, c.size());
        assertEquals(ChangeType.ADDED, c.get(0).type());
        assertEquals(0, c.get(0).startLine());
        assertEquals(ChangeType.MODIFIED, c.get(1).type());
        assertEquals(11, c.get(1).startLine());
    }

    @Test
    void toLineMapExpandsRanges() {
        Map<Integer, ChangeType> map = DiffParser.parseToLineMap("@@ -0,0 +5,3 @@\n+a\n+b\n+c\n");
        assertEquals(ChangeType.ADDED, map.get(4));
        assertEquals(ChangeType.ADDED, map.get(5));
        assertEquals(ChangeType.ADDED, map.get(6));
        assertEquals(3, map.size());
    }

    @Test
    void parseToHunkTextMapsEachMarkedLineToItsUnifiedBody() {
        String diff = """
                diff --git a/f.txt b/f.txt
                index 111..222 100644
                --- a/f.txt
                +++ b/f.txt
                @@ -2,1 +2,1 @@
                -old line
                +new line
                @@ -10,0 +11,2 @@
                +added one
                +added two
                """;
        Map<Integer, String> hunks = DiffParser.parseToHunkText(diff);
        // Modified line 2 (1-based) → 0-based key 1; tooltip is the -/+ body (file headers excluded).
        assertEquals("-old line\n+new line", hunks.get(1));
        // The two added lines (0-based 10, 11) share the addition hunk's body.
        assertEquals("+added one\n+added two", hunks.get(10));
        assertEquals("+added one\n+added two", hunks.get(11));
    }

    @Test
    void parseToHunkTextDeletionMarksFollowingLine() {
        // Pure deletion: marker sits on the new-file line below, tooltip shows the removed content.
        Map<Integer, String> hunks = DiffParser.parseToHunkText("@@ -5,2 +4,0 @@\n-gone a\n-gone b\n");
        assertEquals("-gone a\n-gone b", hunks.get(4));
        assertEquals(1, hunks.size());
    }
}
