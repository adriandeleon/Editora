package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;

/**
 * How many revisions Local History holds of a file, in every project's bucket — the plain copies left behind
 * when Encrypt File makes {@code name.gpg} of it (ADR 0017 keeps no history of the encrypted file, but what was
 * recorded of the plain one stays until it is purged). Counted as the history panel lists them: a revision
 * present in several buckets once. FX thread, like the index it reads.
 */
final class PlainHistoryCopies {

    private PlainHistoryCopies() {}

    static int count(ConfigManager config, Path file) {
        return count(config.getHistoryByProject(), file);
    }

    static int count(Map<String, Map<String, List<HistoryRevision>>> byProject, Path file) {
        List<Map<String, List<HistoryRevision>>> buckets = new ArrayList<>();
        for (Map<String, List<HistoryRevision>> bucket : byProject.values()) {
            if (bucket != null) {
                buckets.add(bucket);
            }
        }
        return HistoryCoordinator.mergedRevisions(buckets, PathKeys.normalizedKey(file))
                .size();
    }
}
