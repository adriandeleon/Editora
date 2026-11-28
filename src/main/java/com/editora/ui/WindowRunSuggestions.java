package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What a window suggests when a run configuration is made from the active file: a configuration for a script or a
 * web app's development server, a blank one of the file's type, the main class, and the program arguments
 * remembered for a file. It lived inside {@code MainController}; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowRunSuggestions {

    private final MainController mc;

    WindowRunSuggestions(MainController mc) {
        this.mc = mc;
    }

    /** The remembered program-arguments string for {@code path} ("" when none); shared with debug launches. */
    String programArgsFor(@Nullable Path path) {
        String s = path == null
                ? null
                : mc.config.getWorkspaceState().getProgramArgs().get(path.toString());
        return s == null ? "" : s;
    }

    /** A configuration for the active Python, shell, Groovy or Kotlin file (Add / Save as Run Configuration), or null. */
    com.editora.config.@Nullable RunConfiguration suggestedScriptConfiguration() {
        EditorBuffer b = mc.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null) {
            return null;
        }
        String type = runConfigTypeOf(b);
        if (com.editora.run.ScriptRunCommand.PYTHON.equals(type)) {
            // A web app's file: its development server (manage.py runserver, flask, uvicorn). Packages looked
            // for on this machine only.
            boolean here = com.editora.process.ExecTargets.runsHere(file) && !com.editora.vfs.Vfs.isRemote(file);
            com.editora.run.PythonWebApps.Server web = com.editora.run.PythonWebApps.serve(
                    file,
                    b.getContent(),
                    com.editora.run.ProgramArgs.tokenize(programArgsFor(file)), // Flask's and uvicorn's go after
                    d -> here && java.nio.file.Files.isRegularFile(d.resolve("__init__.py")));
            if (web != null) {
                return com.editora.run.RunConfigDefaults.forWebApp(web, file, mc.windowProjectRoot());
            }
        }
        // (forScript answers null for the types that launch no script file: Java, make, npm.)
        return type == null ? null : com.editora.run.RunConfigDefaults.forScript(type, file, mc.windowProjectRoot());
    }

    /**
     * A blank configuration of the type that launches the active file, or null when no type does: what Run
     * Configurations → Add starts from when the file is not itself something to launch (a makefile, a
     * {@code package.json}, a script not saved yet), so its Type is not Java whatever is open.
     */
    com.editora.config.@Nullable RunConfiguration suggestedBlankConfiguration() {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            return null;
        }
        String type = runConfigTypeOf(b);
        return type == null
                ? null
                : com.editora.run.RunConfigDefaults.blankOfType(type, b.getPath(), mc.windowProjectRoot());
    }

    private static @Nullable String runConfigTypeOf(EditorBuffer b) {
        Path name = b.getPath() == null ? null : b.getPath().getFileName();
        return com.editora.run.RunConfigDefaults.typeFor(name == null ? null : name.toString(), b.getLanguage());
    }

    /**
     * The main class a new run configuration should start from: the one the active Java file's project
     * declares, else the first {@code main} in that file. Null when there is nothing to suggest.
     *
     * <p>Shared by {@code run.saveConfig} and Run Configurations → Add, so the two cannot disagree
     * about what "the obvious main class here" is.
     */
    @Nullable
    String suggestedMainClass() {
        return mc.mainClassSuggestions.suggest(mc.activeBuffer()); // worked out off the FX thread (PERF-01)
    }
}
