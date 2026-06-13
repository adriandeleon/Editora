package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bookmarks store, serialized as JSON to {@code bookmarks.json} in the config dir. Kept in its own
 * file (not inside {@link WorkspaceState}) so all bookmarks are easy to find in one place, but
 * <em>scoped per project</em>: bookmarks are bucketed by project key ({@code ""} = the global session /
 * no project, otherwise the project id), so switching projects shows only that project's bookmarks.
 *
 * <p>A plain Jackson POJO; the {@code com.editora.config} package is already opened to jackson.databind
 * in {@code module-info.java} (see {@link Bookmark}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookmarkStore {

    /** Current on-disk schema version of {@code bookmarks.json}. */
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;

    /** Project key ({@code ""} = no project) -> (absolute file path -> bookmarks). */
    private Map<String, Map<String, List<Bookmark>>> byProject = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Map<String, List<Bookmark>>> getByProject() {
        return byProject;
    }

    public void setByProject(Map<String, Map<String, List<Bookmark>>> byProject) {
        this.byProject = byProject == null ? new LinkedHashMap<>() : byProject;
    }

    /** The bookmark map for one project key, creating an empty bucket if absent. */
    public Map<String, List<Bookmark>> bucket(String projectKey) {
        return byProject.computeIfAbsent(projectKey == null ? "" : projectKey, k -> new LinkedHashMap<>());
    }

    /**
     * Backward-compat for the brief pre-release format where {@code bookmarks.json} held a single flat
     * {@code "bookmarks"} map (no projects). Folds any such legacy map into the {@code ""} (no-project)
     * bucket on read. Write-only (no getter), so new files only emit {@link #byProject}.
     */
    @JsonProperty("bookmarks")
    private void setLegacyFlatBookmarks(Map<String, List<Bookmark>> legacy) {
        if (legacy != null && !legacy.isEmpty()) {
            bucket("").putAll(legacy);
        }
    }

    /**
     * Re-applies the user's custom within-file order to a fresh snapshot. Bookmarks have no stable id —
     * they're matched first by line, else by their (non-empty) captured {@code lineText} (which follows
     * the content across edits) — so the order survives both note edits and line shifts. Bookmarks in
     * {@code previousOrder} are emitted in that order (using the up-to-date snapshot entry), then any new
     * snapshot bookmarks are appended in their natural order. Pure + unit-tested.
     */
    public static List<Bookmark> mergePreservingOrder(List<Bookmark> previousOrder, List<Bookmark> current) {
        if (previousOrder == null || previousOrder.isEmpty()) {
            return new ArrayList<>(current == null ? List.of() : current);
        }
        List<Bookmark> remaining = new ArrayList<>(current == null ? List.of() : current);
        List<Bookmark> result = new ArrayList<>(remaining.size());
        for (Bookmark prev : previousOrder) {
            int i = indexOfMatch(remaining, prev);
            if (i >= 0) {
                result.add(remaining.remove(i));
            }
        }
        result.addAll(remaining); // new bookmarks, in their natural (line) order
        return result;
    }

    /** First index in {@code list} matching {@code target} by line, else by non-empty {@code lineText}; -1 if none. */
    private static int indexOfMatch(List<Bookmark> list, Bookmark target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).line() == target.line()) {
                return i;
            }
        }
        if (!target.lineText().isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                if (target.lineText().equals(list.get(i).lineText())) {
                    return i;
                }
            }
        }
        return -1;
    }
}
