package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The global bookmarks store, serialized as JSON to {@code bookmarks.json} in the config dir. Bookmarks
 * are intentionally <em>not</em> part of {@link WorkspaceState} (per-session/per-project) — they are
 * global across all files and projects, kept in their own file so they're easy to find and survive
 * project switches.
 *
 * <p>A plain Jackson POJO; the {@code com.editora.config} package is already opened to jackson.databind
 * in {@code module-info.java} (see {@link Bookmark}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookmarkStore {

    /** Absolute file path -> bookmarks (line + optional note + captured text). */
    private Map<String, List<Bookmark>> bookmarks = new LinkedHashMap<>();

    public Map<String, List<Bookmark>> getBookmarks() {
        return bookmarks;
    }

    public void setBookmarks(Map<String, List<Bookmark>> bookmarks) {
        this.bookmarks = bookmarks == null ? new LinkedHashMap<>() : bookmarks;
    }
}
