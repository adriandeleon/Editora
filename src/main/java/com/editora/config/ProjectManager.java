package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** The serialized index: the known projects and which one is active ("" = none). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Index {
        private List<Project> projects = new ArrayList<>();
        private String activeProjectId = "";

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
    }

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configDir;
    private Index index = new Index();

    public ProjectManager(Path configDir) {
        this.configDir = configDir;
        load();
    }

    private void load() {
        Path file = configDir.resolve(INDEX_FILE_NAME);
        if (Files.isReadable(file)) {
            try {
                index = json.readValue(Files.readString(file), Index.class);
            } catch (IOException e) {
                index = new Index();
            }
        }
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
        return index.getProjects().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    public void setActive(String projectId) {
        index.setActiveProjectId(projectId == null ? "" : projectId);
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
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "project";
        }
        String hash = Integer.toHexString(absRoot.hashCode());
        return slug + "-" + hash;
    }
}
