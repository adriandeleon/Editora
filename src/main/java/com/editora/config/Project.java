package com.editora.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import com.editora.vfs.Vfs;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * A named project: a single root folder whose session state (open files, layout, folds) is persisted
 * separately under {@code ~/.editora/projects/<id>.json}. See {@link ProjectManager}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(String id, String name, String root) {

    public Project {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        root = root == null ? "" : root;
    }

    /**
     * The root folder as a path, or {@code null} when there is none to use right now: the project has no
     * root, or it lives on a remote host that is not connected. {@code root} is stored the way
     * {@link Vfs#toStorableString} writes a path, so a local root is its plain path string (as it always
     * was) and a remote one is its {@code sftp://} URI. Never {@code Path.of(root())}: for a remote root that
     * quietly yields a meaningless relative local path.
     */
    @JsonIgnore
    public @Nullable Path rootPath() {
        try {
            return Vfs.parseStorable(root);
        } catch (InvalidPathException e) {
            return null; // a root written on another OS
        }
    }

    /** Whether the root is on a remote host (connected or not). */
    @JsonIgnore
    public boolean remote() {
        return Vfs.isRemoteUri(root);
    }

    /**
     * Whether {@code file} is inside this project's root. False for a file on another filesystem than the
     * root, which paths of different providers cannot even be asked about.
     */
    public boolean contains(Path file) {
        Path rootPath = file == null ? null : rootPath();
        return rootPath != null
                && rootPath.getFileSystem() == file.getFileSystem()
                && file.toAbsolutePath()
                        .normalize()
                        .startsWith(rootPath.toAbsolutePath().normalize());
    }

    // Jackson needs a no-arg-friendly shape for records; the canonical constructor + @JsonProperty-less
    // record components deserialize fine with jackson-databind 2.12+.
}
