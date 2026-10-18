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
     *
     * <p>A Python script with no explicit directory runs in <b>its own folder</b>, as Run File does (the project
     * root goes on {@code PYTHONPATH} instead), so a script that opens {@code data.txt} beside itself behaves
     * the same from its gutter ▶ and from its configuration. A Python module ({@code python -m}) has no folder of
     * its own and runs at the project root.
     */
    public static Path resolve(RunConfiguration cfg, Path projectRoot) {
        Path scriptFolder = scriptFolder(cfg);
        if (cfg.workingDir().isBlank() && ScriptRunCommand.PYTHON.equals(cfg.type())) {
            Path script = script(cfg, projectRoot, null);
            if (script != null && script.isAbsolute() && script.getParent() != null) {
                return script.getParent();
            }
        }
        if (!cfg.workingDir().isBlank()) {
            // In a remote project a directory names a folder on the project's host, not on this machine.
            if (projectRoot != null && !isLocal(projectRoot)) {
                return projectRoot.resolve(cfg.workingDir());
            }
            // In a folder whose program runs elsewhere (a container, WSL) an absolute directory is named as
            // it is there; the target says which folder of this machine that is.
            Path dir = projectRoot != null && !com.editora.process.ExecTargets.runsHere(projectRoot)
                    ? com.editora.process.ExecTargets.forPath(projectRoot).toPath(cfg.workingDir())
                    : Path.of(cfg.workingDir());
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

    /**
     * The script a Python or shell configuration runs. A relative target is relative to the project root — what
     * Save as Run Configuration and Browse write, and what the form says — so changing the Working directory
     * no longer turns {@code src/app/main.py} into {@code src/src/app/main.py}. A target that only exists
     * relative to the working directory {@code dir} (how older configurations could be written) still resolves
     * there. With no project, it is relative to {@code dir}. {@code null} for other types or a blank target.
     */
    public static Path script(RunConfiguration cfg, Path projectRoot, Path dir) {
        if (!isFileScript(cfg) || cfg.target().isBlank()) {
            return null;
        }
        String target = cfg.target().strip();
        if (projectRoot == null) {
            return script(cfg, dir);
        }
        Path fromRoot;
        try {
            fromRoot = projectRoot.resolve(target).normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        if (dir == null || dir.equals(projectRoot) || !isLocal(projectRoot) || java.nio.file.Files.exists(fromRoot)) {
            return fromRoot;
        }
        Path fromDir = script(cfg, dir);
        return fromDir != null && java.nio.file.Files.exists(fromDir) ? fromDir : fromRoot;
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
        return ScriptRunCommand.PYTHON.equals(cfg.type())
                || ScriptRunCommand.SHELL.equals(cfg.type())
                || ScriptRunCommand.isJvmScript(cfg.type());
    }

    private static boolean isLocal(Path path) {
        return path.getFileSystem() == java.nio.file.FileSystems.getDefault();
    }
}
