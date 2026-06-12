package com.editora.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.editora.git.GitService.CommitFile;
import com.editora.git.RelativeTime.Span;
import com.editora.git.RelativeTime.Unit;

/** Unit tests for {@code git diff-tree --name-status} parsing and relative-time bucketing. */
class NameStatusTest {

    @Test
    void parsesAddModifyDeleteAndRename() {
        String out = """
                M\tsrc/App.java
                A\tsrc/New.java
                D\tsrc/Old.java
                R096\tsrc/From.java\tsrc/To.java
                """;
        List<CommitFile> files = GitService.parseNameStatus(out);
        assertEquals(4, files.size());

        assertEquals('M', files.get(0).status());
        assertEquals("src/App.java", files.get(0).path());
        assertNull(files.get(0).origPath());

        assertEquals('A', files.get(1).status());
        assertEquals('D', files.get(2).status());

        CommitFile rename = files.get(3);
        assertEquals('R', rename.status());
        assertEquals("src/To.java", rename.path());
        assertEquals("src/From.java", rename.origPath());
    }

    @Test
    void emptyInputYieldsEmptyList() {
        assertTrue(GitService.parseNameStatus("").isEmpty());
        assertTrue(GitService.parseNameStatus(null).isEmpty());
    }

    @Test
    void relativeTimeBuckets() {
        assertEquals(new Span(Unit.NOW, 0), RelativeTime.of(1000, 1030));
        assertEquals(new Span(Unit.MINUTES, 5), RelativeTime.of(0, 5 * 60));
        assertEquals(new Span(Unit.HOURS, 2), RelativeTime.of(0, 2 * 3600));
        assertEquals(new Span(Unit.DAYS, 3), RelativeTime.of(0, 3 * 86400));
        assertEquals(new Span(Unit.WEEKS, 2), RelativeTime.of(0, 14 * 86400));
        assertEquals(new Span(Unit.MONTHS, 2), RelativeTime.of(0, 60L * 86400));
        assertEquals(new Span(Unit.YEARS, 1), RelativeTime.of(0, 400L * 86400));
        // Future timestamps clamp to NOW (no negative ages).
        assertEquals(new Span(Unit.NOW, 0), RelativeTime.of(2000, 1000));
    }
}
