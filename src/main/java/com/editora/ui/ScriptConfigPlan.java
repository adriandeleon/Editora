package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.editora.config.RunConfiguration;
import com.editora.run.EnvVars;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.ScriptRunCommand;
import com.editora.run.ShellCommand;
import org.jspecify.annotations.Nullable;

/**
 * What a script configuration's Run needs from the disk, read off the FX thread (PERF-01, Pauta 6.2.0): the script
 * it names and its folder, the configuration's environment with any {@code @file} entry loaded, the script's
 * first line, whether its interpreter is there, the npm project's package manager, and the shell launch its shebang
 * asks for with that launch's folder. {@link RunCoordinator} reads it on the FX thread and says what is wrong or
 * starts the run.
 *
 * @param envError a {@code ProgramArgs.UnclosedQuoteException} or an {@code EnvVars.EnvFileException}, else null
 * @param shellError the shell launch's {@code ProgramArgs.UnclosedQuoteException}, else null
 * @param shebangDir the folder the shell launch runs in; null with {@code missingShebangDir} the folder not there
 */
record ScriptConfigPlan(
        @Nullable Path script,
        boolean dirMissing,
        boolean scriptMissing,
        Map<String, String> env,
        @Nullable RuntimeException envError,
        @Nullable String firstLine,
        @Nullable String missingInterpreter,
        com.editora.build.NpmPackageManager.@Nullable Detection npm,
        ShellCommand.@Nullable Launch shell,
        @Nullable RuntimeException shellError,
        @Nullable Path shebangDir,
        @Nullable Path missingShebangDir) {

    /** Reads the plan. Blocking: on a thread of its own. */
    static ScriptConfigPlan of(
            RunConfiguration cfg,
            @Nullable Path root,
            @Nullable Path projectRoot,
            Path cwd,
            boolean remote,
            boolean elsewhere) {
        com.editora.perf.BlockingSites.enter("ScriptConfigPlan.of");
        Path script = RunConfigWorkingDirectory.script(cfg, root, cwd);
        boolean dirMissing = !remote && !Files.isDirectory(cwd);
        boolean scriptMissing = !remote && !dirMissing && script != null && !Files.isRegularFile(script);
        Map<String, String> env = Map.of();
        RuntimeException envError = null;
        try {
            env = EnvVars.resolve(cfg.env(), RunCoordinator.inheritedVariables(elsewhere), cwd);
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException | EnvVars.EnvFileException e) {
            envError = e;
        }
        boolean python = ScriptRunCommand.isPython(cfg.type());
        boolean shellType = ScriptRunCommand.SHELL.equals(cfg.type());
        String firstLine = (python && !remote) || shellType ? RunCoordinator.firstLine(script) : null;
        String missingInterpreter = ScriptRunCommand.NODE.equals(cfg.type()) && !elsewhere
                ? com.editora.run.MissingProgram.missingInterpreter(
                        cfg.interpreter(), p -> Files.isRegularFile(Path.of(p)))
                : null;
        var npm = ScriptRunCommand.NPM.equals(cfg.type())
                ? com.editora.build.BuildTool.npmManager(cwd, projectRoot)
                : null;
        ShellCommand.Launch shell = null;
        RuntimeException shellError = null;
        Path shebangDir = cwd;
        Path missingShebangDir = null;
        if (shellType) {
            try {
                shell = ShellCommand.launch(
                        firstLine,
                        cfg.target(),
                        cfg.interpreter(),
                        cfg.interpreterOptions(),
                        elsewhere ? null : p -> Files.isExecutable(Path.of(p)));
                String chdir = shell.chdir();
                if (chdir != null && !chdir.isBlank()) {
                    Path dir = cwd.resolve(chdir).normalize();
                    if (!remote && !Files.isDirectory(dir)) {
                        shebangDir = null;
                        missingShebangDir = dir;
                    } else {
                        shebangDir = dir;
                    }
                }
            } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
                shellError = e;
            }
        }
        return new ScriptConfigPlan(
                script,
                dirMissing,
                scriptMissing,
                env,
                envError,
                firstLine,
                missingInterpreter,
                npm,
                shell,
                shellError,
                shebangDir,
                missingShebangDir);
    }
}
