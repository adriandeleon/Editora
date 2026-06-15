package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Tracks the user's projects and the active one, persisted as JSON in {@code <configDir>/projects.json}.
 * Each project's session state ({@link WorkspaceState}) lives in its own
 * {@code <configDir>/projects/<id>.json} (see {@link #stateFile(Project)}); this class only manages the
 * index. Missing/malformed input falls back to "no project" (the default global session).
 */
public class ProjectManager {

    static final String INDEX_FILE_NAME = "projects.json";
    static final String PROJECTS_DIR = "projects";

    /** The serialized index: the known projects, the last-focused one, and the open-window set. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Index {
        /** Current on-disk schema version of {@code projects.json}. v1→v2 added {@code openProjectIds}. */
        public static final int SCHEMA_VERSION = 2;

        private int schemaVersion = SCHEMA_VERSION;
        private List<Project> projects = new ArrayList<>();
        /** The last-focused project ("" = the no-project/global window). Drives focus on restore. */
        private String activeProjectId = "";
        /** Project ids whose windows were open at last quit ("" = the global window). Empty ⇒ open global. */
        private List<String> openProjectIds = new ArrayList<>();

        public int getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public List<Project> getProjects() {
            return projects;
        }

        public void setProjects(List<Project> projects) {
            this.projects = projects == null ? new ArrayList<>() : projects;
        }

        public String getActiveProjectId() {
            return activeProjectId;
        }

        public void setActiveProjectId(String activeProjectId) {
            this.activeProjectId = activeProjectId == null ? "" : activeProjectId;
        }

        public List<String> getOpenProjectIds() {
            return openProjectIds;
        }

        public void setOpenProjectIds(List<String> openProjectIds) {
            this.openProjectIds = openProjectIds == null ? new ArrayList<>() : openProjectIds;
        }
    }

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configDir;
    private Index index = new Index();

    public ProjectManager(Path configDir) {
        this.configDir = configDir;
        load();
    }

    private void load() {
        index = ConfigMigrations.readVersioned(
                configDir.resolve(INDEX_FILE_NAME), json, new Index(), ConfigSchema.PROJECTS);
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            json.writeValue(configDir.resolve(INDEX_FILE_NAME).toFile(), index);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + INDEX_FILE_NAME, e);
        }
    }

    public List<Project> list() {
        return List.copyOf(index.getProjects());
    }

    /** The active project, or {@code null} when none is open (the default global session). */
    public Project active() {
        String id = index.getActiveProjectId();
        if (id.isEmpty()) {
            return null;
        }
        return index.getProjects().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public void setActive(String projectId) {
        index.setActiveProjectId(projectId == null ? "" : projectId);
    }

    /** The project keys whose windows were open ({@code ""} = the global window). Callers persist via {@link #save()}. */
    public List<String> openProjectIds() {
        return List.copyOf(index.getOpenProjectIds());
    }

    /** Records that a window for {@code projectKey} ({@code ""} = global) is open. Callers persist via {@link #save()}. */
    public void markOpen(String projectKey) {
        String key = projectKey == null ? "" : projectKey;
        if (!index.getOpenProjectIds().contains(key)) {
            index.getOpenProjectIds().add(key);
        }
    }

    /** Records that the window for {@code projectKey} ({@code ""} = global) has closed. Callers persist via {@link #save()}. */
    public void markClosed(String projectKey) {
        index.getOpenProjectIds().remove(projectKey == null ? "" : projectKey);
    }

    /**
     * Replaces the open-window set with exactly {@code keys} (order preserved, de-duplicated, {@code null}
     * → {@code ""}). Used to reconcile the persisted set to the live set of open windows. Persist via
     * {@link #save()}.
     */
    public void setOpenWindows(java.util.Collection<String> keys) {
        List<String> list = new ArrayList<>();
        for (String k : keys) {
            String key = k == null ? "" : k;
            if (!list.contains(key)) {
                list.add(key);
            }
        }
        index.setOpenProjectIds(list);
    }

    /** True if a window for {@code projectKey} ({@code ""} = global) is recorded as open. */
    public boolean isOpen(String projectKey) {
        return index.getOpenProjectIds().contains(projectKey == null ? "" : projectKey);
    }

    /**
     * Removes the project from the index and deletes its per-project session-state file. The project's
     * folder and its files on disk are left untouched. Clears the active project if it was the one
     * removed. Callers persist via {@link #save()}.
     */
    public boolean delete(String projectId) {
        if (projectId == null || projectId.isEmpty()) {
            return false;
        }
        boolean removed = index.getProjects().removeIf(p -> p.id().equals(projectId));
        if (projectId.equals(index.getActiveProjectId())) {
            index.setActiveProjectId("");
        }
        index.getOpenProjectIds().remove(projectId); // a deleted project can't have an open window
        try {
            Files.deleteIfExists(configDir.resolve(PROJECTS_DIR).resolve(projectId + ".json"));
        } catch (IOException ignored) {
            // best effort — a leftover state file is harmless
        }
        return removed;
    }

    /**
     * Returns the existing project for {@code root} (matched by absolute path), or creates and stores a
     * new one named {@code name}. Does not change the active project or persist; callers do that.
     */
    public Project createOrGet(String name, Path root) {
        String absRoot = root.toAbsolutePath().normalize().toString();
        for (Project p : index.getProjects()) {
            if (p.root().equals(absRoot)) {
                return p;
            }
        }
        Project project = new Project(idFor(name, absRoot), name, absRoot);
        index.getProjects().add(project);
        return project;
    }

    /** Per-project session-state file: {@code <configDir>/projects/<id>.json}. */
    public Path stateFile(Project project) {
        return configDir.resolve(PROJECTS_DIR).resolve(project.id() + ".json");
    }

    /** A filesystem-safe, reasonably-unique id: a name slug plus a short hash of the root path. */
    private static String idFor(String name, String absRoot) {
        String slug =
                name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "project";
        }
        String hash = Integer.toHexString(absRoot.hashCode());
        return slug + "-" + hash;
    }
}
