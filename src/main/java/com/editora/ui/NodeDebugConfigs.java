package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import org.jspecify.annotations.Nullable;

/**
 * Debug on a {@code node} or {@code npm} run configuration: the launch is planned the way Run plans it, so Debug
 * and Run start the program the same way. A {@code node} configuration goes through {@link NodeRunLauncher} (the
 * same {@code NodeRunPlan}: interpreter, Node arguments, the shebang, {@code --enable-source-maps}, the
 * environment, the working folder); an npm script runs the project's package manager ({@code npm run <script>})
 * under vscode-js-debug, which attaches to every Node process it starts. Pure helpers here; the caller supplies
 * what is resolved off the FX thread.
 */
final class NodeDebugConfigs {

    private NodeDebugConfigs() {}

    /**
     * The js-debug launch for a planned Node run: {@code argv} split at the script ({@code target}, as the
     * machine running it names it) into the runtime with its arguments, the script, and its arguments. Null when
     * the script is not in {@code argv} (nothing to split at), or there is no folder to run it in.
     */
    static DapManager.@Nullable JsProgram fromRunPlan(
            List<String> argv, String target, Map<String, String> env, @Nullable Path dir) {
        int at = argv == null || target == null ? -1 : argv.indexOf(target);
        if (at < 1 || dir == null) {
            return null;
        }
        return new DapManager.JsProgram(
                argv.subList(0, at), target, argv.subList(at + 1, argv.size()), dir, env == null ? Map.of() : env);
    }

    /**
     * The js-debug launch for an npm script: {@code argv} is the package manager's whole command line
     * ({@code npm run start -- …}), launched as js-debug's {@code runtimeExecutable}/{@code runtimeArgs} with no
     * program of its own. {@code npmEnv} (colour, the configuration's variables) is set on top.
     */
    static DapManager.@Nullable JsProgram forPackageManager(List<String> argv, Path dir, Map<String, String> npmEnv) {
        if (argv == null || argv.isEmpty()) {
            return null;
        }
        return new DapManager.JsProgram(argv, "", List.of(), dir, new LinkedHashMap<>(npmEnv));
    }
}
