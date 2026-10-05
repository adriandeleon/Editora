package com.editora.config.migration;

import java.nio.file.Path;
import java.util.List;

/**
 * Something {@link ConfigMigrations#readVersioned} could not read as written, reported to the caller so it
 * can be shown to the user instead of silently loading defaults.
 *
 * @param file the config file that was read
 * @param kind what went wrong
 * @param skipped the top-level property names that kept their default ({@link Kind#VALUES_SKIPPED} only)
 * @param backup where the original content was preserved, or {@code null} when preserving it failed
 */
public record ConfigLoadProblem(Path file, Kind kind, List<String> skipped, Path backup) {

    public enum Kind {
        /** Individual values had the wrong type; every other value in the file was kept. */
        VALUES_SKIPPED,
        /** The file could not be parsed or migrated at all; defaults were loaded. */
        UNREADABLE,
        /** The file was written by a newer build; it was moved aside and defaults were loaded. */
        NEWER_VERSION,
        /**
         * The file holds bytes that are not UTF-8 (it was saved in another encoding). It was read, with each
         * such byte replaced by U+FFFD, so the values holding them are not what was written — and the next
         * save writes the replacements back.
         */
        NOT_UTF8
    }

    public ConfigLoadProblem {
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /**
     * True when the file on disk is the only copy of content this build did not load: writing the in-memory
     * defaults over it would destroy that content, so the owner must not save the file this session.
     *
     * <p>Never for a file that was read, even imperfectly ({@link Kind#VALUES_SKIPPED}, {@link Kind#NOT_UTF8}):
     * refusing to save it would cost every later change in order to protect one value.
     */
    public boolean mustNotOverwrite() {
        return backup == null && (kind == Kind.UNREADABLE || kind == Kind.NEWER_VERSION);
    }
}
