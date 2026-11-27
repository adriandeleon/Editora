package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.config.SharedConfig;

/**
 * The viewed marks of a Git review tab, kept per repository in {@code reviews.json} (the config folder, never the
 * project): a file is viewed while its diff has the digest it had when it was marked, so a file that changed since is
 * shown unviewed again. FX thread.
 */
final class StoredViewed implements PatchReviewPane.Viewed {

    private final SharedConfig shared;
    private final String repoKey;
    private final List<String> paths;
    private final List<String> digests;

    StoredViewed(SharedConfig shared, String repoKey, List<String> paths, List<String> digests) {
        this.shared = shared;
        this.repoKey = repoKey;
        this.paths = List.copyOf(paths);
        this.digests = List.copyOf(digests);
    }

    /**
     * What a file's diff looks like, in a few characters: both sides' lengths and hashes. Cheap ({@link String#hashCode}
     * is kept by the string), and a mark is only a reminder, so a rare collision costs a file shown viewed. Pure.
     */
    static String digest(String left, String right) {
        return left.length() + ":" + Integer.toHexString(left.hashCode()) + "/" + right.length() + ":"
                + Integer.toHexString(right.hashCode());
    }

    @Override
    public boolean viewed(int index) {
        if (index < 0 || index >= paths.size()) {
            return false;
        }
        Map<String, String> stored = shared.reviewViewed(repoKey);
        return digests.get(index).equals(stored.get(paths.get(index)));
    }

    @Override
    public void set(int index, boolean viewed) {
        if (index >= 0 && index < paths.size()) {
            shared.setReviewViewed(repoKey, paths.get(index), viewed ? digests.get(index) : null);
        }
    }
}
