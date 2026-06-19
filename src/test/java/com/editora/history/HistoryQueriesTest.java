package com.editora.history;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryQueriesTest {

    private static HistoryRevision rev(String path, long ts, String reason, String label) {
        return new HistoryRevision(path, ts, 0, "sha" + ts, reason, label);
    }

    @Test
    void matchesBlankQueryAlwaysTrue() {
        HistoryRevision r = rev("/a.txt", 1, HistoryRevision.REASON_SAVE, "");
        assertTrue(HistoryQueries.matches(r, null));
        assertTrue(HistoryQueries.matches(r, ""));
        assertTrue(HistoryQueries.matches(r, "   "));
    }

    @Test
    void matchesLabelAndReasonCaseInsensitively() {
        HistoryRevision r = rev("/a.txt", 1, HistoryRevision.REASON_AUTOSAVE, "Before Refactor");
        assertTrue(HistoryQueries.matches(r, "refactor")); // label substring, case-insensitive
        assertTrue(HistoryQueries.matches(r, "AUTO")); // reason substring
        assertFalse(HistoryQueries.matches(r, "external"));
    }

    @Test
    void matchesNullRevisionWithNonBlankQueryIsFalse() {
        assertFalse(HistoryQueries.matches(null, "x"));
    }

    @Test
    void recentFlattensAcrossFilesNewestFirstAndCaps() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/a.txt", List.of(rev("/a.txt", 30, "SAVE", ""), rev("/a.txt", 10, "SAVE", "")));
        bucket.put("/b.txt", List.of(rev("/b.txt", 40, "SAVE", ""), rev("/b.txt", 20, "SAVE", "")));

        List<HistoryRevision> all = HistoryQueries.recent(bucket, 0); // unbounded
        assertEquals(
                List.of(40L, 30L, 20L, 10L),
                all.stream().map(HistoryRevision::timestamp).toList());

        List<HistoryRevision> top2 = HistoryQueries.recent(bucket, 2);
        assertEquals(
                List.of(40L, 30L), top2.stream().map(HistoryRevision::timestamp).toList());
    }

    @Test
    void recentHandlesEmptyOrNullBucket() {
        assertTrue(HistoryQueries.recent(null, 5).isEmpty());
        assertTrue(HistoryQueries.recent(new LinkedHashMap<>(), 5).isEmpty());
    }

    @Test
    void folderRevisionsKeepsOnlyFilesUnderFolderSegmentAware() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/proj/src/A.java", List.of(rev("/proj/src/A.java", 1, "SAVE", "")));
        bucket.put("/proj/src/sub/B.java", List.of(rev("/proj/src/sub/B.java", 2, "SAVE", "")));
        bucket.put("/proj/README.md", List.of(rev("/proj/README.md", 3, "SAVE", "")));
        bucket.put("/proj/src-gen/C.java", List.of(rev("/proj/src-gen/C.java", 4, "SAVE", ""))); // not under /proj/src

        Map<String, List<HistoryRevision>> under = HistoryQueries.folderRevisions(bucket, "/proj/src");
        assertEquals(List.of("/proj/src/A.java", "/proj/src/sub/B.java"), List.copyOf(under.keySet()));

        // Trailing separator on the folder key is tolerated.
        assertEquals(2, HistoryQueries.folderRevisions(bucket, "/proj/src/").size());
    }

    @Test
    void folderRevisionsEmptyForNullOrBlank() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/proj/A.java", List.of(rev("/proj/A.java", 1, "SAVE", "")));
        assertTrue(HistoryQueries.folderRevisions(bucket, null).isEmpty());
        assertTrue(HistoryQueries.folderRevisions(bucket, "").isEmpty());
        assertTrue(HistoryQueries.folderRevisions(null, "/proj").isEmpty());
    }

    @Test
    void isUnderRespectsSegmentBoundary() {
        assertTrue(HistoryQueries.isUnder("/foo/a.txt", "/foo"));
        assertTrue(HistoryQueries.isUnder("C:\\foo\\a.txt", "C:\\foo"));
        assertFalse(HistoryQueries.isUnder("/foobar/a.txt", "/foo")); // not a child of /foo
        assertFalse(HistoryQueries.isUnder("/foo", "/foo")); // the folder itself
    }

    @Test
    void revisionLabelDefaultsToEmpty() {
        // 5-arg back-compat ctor (pre-schema-2 rows) → label "".
        HistoryRevision auto = new HistoryRevision("/a.txt", 1, 0, "sha", HistoryRevision.REASON_SAVE);
        assertEquals("", auto.label());
        // null label normalizes to "".
        HistoryRevision nullLabel = new HistoryRevision("/a.txt", 1, 0, "sha", HistoryRevision.REASON_LABEL, null);
        assertEquals("", nullLabel.label());
        HistoryRevision named = rev("/a.txt", 1, HistoryRevision.REASON_LABEL, "v1.0");
        assertEquals("v1.0", named.label());
    }
}
