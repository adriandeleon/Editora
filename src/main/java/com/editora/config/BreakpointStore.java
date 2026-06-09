package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The breakpoints store, serialized as JSON to {@code breakpoints.json} in the config dir. Modeled on
 * {@link BookmarkStore}: kept in its own file (not inside {@link WorkspaceState}) but <em>scoped per
 * project</em> — breakpoints are bucketed by project key ({@code ""} = the global session / no project,
 * otherwise the project id), so switching projects shows only that project's breakpoints.
 *
 * <p>A plain Jackson POJO; the {@code com.editora.config} package is already opened to jackson.databind
 * in {@code module-info.java}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BreakpointStore {

    /** Current on-disk schema version of {@code breakpoints.json}. */
    public static final int SCHEMA_VERSION = 1;
    private int schemaVersion = SCHEMA_VERSION;

    /** Project key ({@code ""} = no project) -> (absolute file path -> breakpoints). */
    private Map<String, Map<String, List<Breakpoint>>> byProject = new LinkedHashMap<>();

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, Map<String, List<Breakpoint>>> getByProject() {
        return byProject;
    }

    public void setByProject(Map<String, Map<String, List<Breakpoint>>> byProject) {
        this.byProject = byProject == null ? new LinkedHashMap<>() : byProject;
    }

    /** The breakpoint map for one project key, creating an empty bucket if absent. */
    public Map<String, List<Breakpoint>> bucket(String projectKey) {
        return byProject.computeIfAbsent(projectKey == null ? "" : projectKey, k -> new LinkedHashMap<>());
    }

    /**
     * Re-applies the prior within-file order to a fresh snapshot. Breakpoints have no stable id — they're
     * matched first by line, else by their (non-empty) captured {@code lineText} (which follows the
     * content across edits) — so the order survives both edits to the breakpoint and line shifts.
     * Breakpoints in {@code previousOrder} are emitted first (using the up-to-date snapshot entry), then
     * any new snapshot breakpoints are appended in their natural order. Pure + unit-tested.
     */
    public static List<Breakpoint> mergePreservingOrder(List<Breakpoint> previousOrder,
            List<Breakpoint> current) {
        if (previousOrder == null || previousOrder.isEmpty()) {
            return new ArrayList<>(current == null ? List.of() : current);
        }
        List<Breakpoint> remaining = new ArrayList<>(current == null ? List.of() : current);
        List<Breakpoint> result = new ArrayList<>(remaining.size());
        for (Breakpoint prev : previousOrder) {
            int i = indexOfMatch(remaining, prev);
            if (i >= 0) {
                result.add(remaining.remove(i));
            }
        }
        result.addAll(remaining);
        return result;
    }

    private static int indexOfMatch(List<Breakpoint> list, Breakpoint target) {
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
