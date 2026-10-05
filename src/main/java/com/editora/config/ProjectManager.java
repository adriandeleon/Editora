package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import com.editora.config.migration.ConfigLoadProblem;
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

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ProjectManager.class.getName());

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configDir;
    private Index index = new Index();
    /** What {@link #load()} could not read as written (see {@link #loadProblems()}). */
    private final List<ConfigLoadProblem> loadProblems = new ArrayList<>();
    /** Told of a failed {@link #save()}; the default only logs. */
    private BiConsumer<Path, IOException> onWriteError =
            (file, e) -> LOG.log(java.util.logging.Level.SEVERE, "Failed to write " + file, e);

    public ProjectManager(Path configDir) {
        this.configDir = configDir;
        load();
    }

    private Path indexFile() {
        return configDir.resolve(INDEX_FILE_NAME);
    }

    private void load() {
        index = ConfigMigrations.readVersioned(
                indexFile(), json, new Index(), ConfigSchema.PROJECTS, loadProblems::add);
    }

    /**
     * What could not be read from {@code projects.json} as written: an unparseable index, one written by a
     * newer build, or values that kept their default. {@link SharedConfig} reports these with its own.
     */
    List<ConfigLoadProblem> loadProblems() {
        return List.copyOf(loadProblems);
    }

    /** Routes a failed {@link #save()} to {@code handler} instead of the log alone. */
    void setOnWriteError(BiConsumer<Path, IOException> handler) {
        this.onWriteError = handler;
    }

    /** True when the index on disk holds content that was neither loaded nor copied aside. */
    private boolean writeProtected() {
        return loadProblems.stream().anyMatch(ConfigLoadProblem::mustNotOverwrite);
    }

    /**
     * Writes the index to {@code projects.json}.
     *
     * <p>Never throws: this runs inside window open/close/focus handlers, where an exception from a full disk
     * or a read-only config dir used to abort the rest of the handler with nothing shown. A failure is
     * reported through the write-error handler instead. An index that could be neither read nor backed up is
     * left alone — the one in memory is the defaults loaded in its place.
     *
     * @return whether the index in memory is now the one on disk
     */
    public boolean save() {
        if (writeProtected()) {
            return false;
        }
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(indexFile(), json, index);
            return true;
        } catch (IOException e) {
            onWriteError.accept(indexFile(), e);
            return false;
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
        Project project = new Project(uniqueId(idFor(name, absRoot)), name, absRoot);
        index.getProjects().add(project);
        return project;
    }

    /**
     * {@code id}, or {@code id-2}, {@code id-3}, … when another project already has it. The id names the
     * project's session file and its bookmark/note/breakpoint buckets, and {@link #idFor} hashes the root to
     * only 32 bits: two folders with the same name whose paths collide would otherwise share all of them.
     */
    private String uniqueId(String id) {
        String candidate = id;
        for (int n = 2; idInUse(candidate); n++) {
            candidate = id + "-" + n;
        }
        return candidate;
    }

    private boolean idInUse(String id) {
        return index.getProjects().stream().anyMatch(p -> p.id().equals(id));
    }

    /** Per-project session-state file: {@code <configDir>/projects/<id>.json}. */
    public Path stateFile(Project project) {
        return configDir.resolve(PROJECTS_DIR).resolve(project.id() + ".json");
    }

    /** A filesystem-safe id: a name slug plus a short hash of the root path. Unique only via {@link #uniqueId}. */
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
