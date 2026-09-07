package com.editora.run;

import java.nio.file.Path;

import com.editora.config.RunConfiguration;

/** Resolves the working directory for a non-Java saved run configuration. */
public final class RunConfigWorkingDirectory {

    private RunConfigWorkingDirectory() {}

    /**
     * Uses the configuration's explicit directory, then the open project's root. With no project, a Python
     * or shell script can still run beside an absolute/working-directory-relative target path. Build-tool
     * targets are names rather than paths, so they cannot supply a directory of their own.
     */
    public static Path resolve(RunConfiguration cfg, Path projectRoot) {
        if (!cfg.workingDir().isBlank()) {
            return Path.of(cfg.workingDir());
        }
        if (projectRoot != null) {
            return projectRoot;
        }
        if ((ScriptRunCommand.PYTHON.equals(cfg.type()) || ScriptRunCommand.SHELL.equals(cfg.type()))
                && !cfg.target().isBlank()) {
            return Path.of(cfg.target()).toAbsolutePath().getParent();
        }
        return null;
    }
}
