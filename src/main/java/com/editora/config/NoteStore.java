package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Personal Notes store, serialized as JSON to {@code notes.json}. Mirrors {@link BookmarkStore}:
 * bookmarks-style <em>per-project</em> bucketing — {@code byProject} maps a project key ({@code ""} = the
 * global session / no project, else the project id) to (file key → notes), where the file key is the
 * note's canonical path. Versioned via {@link #schemaVersion} (see {@code config/migration}).
 *
 * <p>A plain Jackson POJO ({@code com.editora.config} is opened to jackson.databind).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class NoteStore {

    /** Current on-disk schema version of {@code notes.json}. */
    public static final int SCHEMA_VERSION = 1;
    private int schemaVersion = SCHEMA_VERSION;

    /** Project key ({@code ""} = no project) -> (canonical file path -> notes). */
    private Map<String, Map<String, List<PersonalNote>>> byProject = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Map<String, List<PersonalNote>>> getByProject() {
        return byProject;
    }

    public void setByProject(Map<String, Map<String, List<PersonalNote>>> byProject) {
        this.byProject = byProject == null ? new LinkedHashMap<>() : byProject;
    }

    /** The notes map for one project key (file path -> notes), creating an empty bucket if absent. */
    public Map<String, List<PersonalNote>> bucket(String projectKey) {
        return byProject.computeIfAbsent(projectKey == null ? "" : projectKey, k -> new LinkedHashMap<>());
    }

    /**
     * Re-applies the user's existing order to a fresh snapshot, matching by stable note id: notes present
     * in {@code previousOrder} come first (using the up-to-date snapshot instance), then any new notes in
     * their natural order. Pure + unit-testable; keeps list order stable across re-anchoring/saves.
     */
    public static List<PersonalNote> mergePreservingOrder(List<PersonalNote> previousOrder,
            List<PersonalNote> current) {
        if (previousOrder == null || previousOrder.isEmpty()) {
            return new ArrayList<>(current == null ? List.of() : current);
        }
        List<PersonalNote> remaining = new ArrayList<>(current == null ? List.of() : current);
        List<PersonalNote> result = new ArrayList<>(remaining.size());
        for (PersonalNote prev : previousOrder) {
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).id().equals(prev.id())) {
                    result.add(remaining.remove(i));
                    break;
                }
            }
        }
        result.addAll(remaining);
        return result;
    }
}
