package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R6: the plain copies of an encrypted file's original that Local History still holds, in every bucket. */
class PlainHistoryCopiesTest {

    @Test
    void countsEveryBucketAndARevisionInSeveralOnce() {
        Path file = Path.of("notes.md").toAbsolutePath();
        String key = PathKeys.normalizedKey(file);
        HistoryRevision shared = new HistoryRevision(key, 1, 3, "aaa", HistoryRevision.REASON_SAVE, "");
        HistoryRevision own = new HistoryRevision(key, 2, 3, "bbb", HistoryRevision.REASON_SAVE, "");
        HistoryRevision other = new HistoryRevision(key, 3, 3, "ccc", HistoryRevision.REASON_AUTOSAVE, "");
        Map<String, Map<String, List<HistoryRevision>>> byProject = new LinkedHashMap<>();
        byProject.put("", new LinkedHashMap<>(Map.of(key, List.of(own, shared))));
        byProject.put("/some/project", new LinkedHashMap<>(Map.of(key, List.of(shared, other))));
        byProject.put("/unrelated", new LinkedHashMap<>(Map.of("/elsewhere.txt", List.of(own))));

        assertEquals(3, PlainHistoryCopies.count(byProject, file));
        assertEquals(0, PlainHistoryCopies.count(byProject, Path.of("other.md").toAbsolutePath()));
        assertEquals(0, PlainHistoryCopies.count(Map.of(), file));
    }
}
