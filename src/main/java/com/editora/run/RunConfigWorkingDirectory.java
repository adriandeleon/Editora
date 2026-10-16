package com.editora.run;

import java.nio.file.Path;

import com.editora.config.RunConfiguration;

/** Resolves the working directory for a non-Java saved run configuration. */
public final class RunConfigWorkingDirectory {

    private RunConfigWorkingDirectory() {}

    /**
     * Uses the configuration's explicit directory, then the open project's root. With no project, a Python
     * or shell script with an absolute path still runs beside itself. Build-tool targets are names rather than
     * paths, so they cannot supply a directory of their own.
     *
     * <p>A <em>relative</em> working directory is relative to the project root (the field's prompt says
     * "blank ⇒ project root"), or with no project to an absolute script's folder — never to wherever the
     * Editora process happened to be started, which is what {@code Path.of("sub")} on its own meant. With no
     * base to resolve against the answer is {@code null}: the caller reports it instead of running somewhere
     * arbitrary.
     */
    public static Path resolve(RunConfiguration cfg, Path projectRoot) {
        Path scriptFolder = scriptFolder(cfg);
        if (!cfg.workingDir().isBlank()) {
            // In a remote project a directory names a folder on the project's host, not on this machine.
            if (projectRoot != null && !isLocal(projectRoot)) {
                return projectRoot.resolve(cfg.workingDir());
            }
            Path dir = Path.of(cfg.workingDir());
            if (dir.isAbsolute()) {
                return dir;
            }
            Path base = projectRoot != null ? projectRoot : scriptFolder;
            return base == null ? null : base.resolve(dir).normalize();
        }
        if (projectRoot != null) {
            return projectRoot;
        }
        return scriptFolder;
    }

    /**
     * The script a Python or shell configuration runs, resolved against its working directory {@code dir}
     * (where the interpreter will look for a relative path); {@code null} for other types or a blank target.
     */
    public static Path script(RunConfiguration cfg, Path dir) {
        if (!isFileScript(cfg) || cfg.target().isBlank()) {
            return null;
        }
        Path target = dir == null
                ? Path.of(cfg.target().strip())
                : dir.resolve(cfg.target().strip());
        return target.normalize();
    }

    /** An absolute script target's folder; null for a relative or non-file target. */
    private static Path scriptFolder(RunConfiguration cfg) {
        if (!isFileScript(cfg) || cfg.target().isBlank()) {
            return null;
        }
        try {
            Path target = Path.of(cfg.target().strip());
            return target.isAbsolute() ? target.getParent() : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    private static boolean isFileScript(RunConfiguration cfg) {
        return ScriptRunCommand.PYTHON.equals(cfg.type()) || ScriptRunCommand.SHELL.equals(cfg.type());
    }

    private static boolean isLocal(Path path) {
        return path.getFileSystem() == java.nio.file.FileSystems.getDefault();
    }
}
