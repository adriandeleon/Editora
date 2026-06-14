package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Local File History index, serialized as JSON to {@code history/index.json} in the config dir.
 * Modeled on {@link BreakpointStore}: kept in its own file but <em>scoped per project</em> — revisions
 * are bucketed by project key ({@code ""} = the global session / no project, otherwise the project id),
 * so switching projects shows only that project's file history.
 *
 * <p>This holds only revision <em>metadata</em> ({@link HistoryRevision}); the gzip'd revision bodies
 * live in {@code history/blobs/}, content-addressed by sha. A plain Jackson POJO; the
 * {@code com.editora.config} package is already opened to jackson.databind in {@code module-info.java}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryStore {

    /** Current on-disk schema version of {@code history/index.json}. */
    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;

    /** Project key ({@code ""} = no project) -> (absolute file path -> revisions, newest-first). */
    private Map<String, Map<String, List<HistoryRevision>>> byProject = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Map<String, List<HistoryRevision>>> getByProject() {
        return byProject;
    }

    public void setByProject(Map<String, Map<String, List<HistoryRevision>>> byProject) {
        this.byProject = byProject == null ? new LinkedHashMap<>() : byProject;
    }

    /** The history map for one project key, creating an empty bucket if absent. */
    public Map<String, List<HistoryRevision>> bucket(String projectKey) {
        return byProject.computeIfAbsent(projectKey == null ? "" : projectKey, k -> new LinkedHashMap<>());
    }
}
